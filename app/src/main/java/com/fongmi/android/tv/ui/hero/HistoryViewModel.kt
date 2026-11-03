package com.fongmi.android.tv.ui.hero

import android.app.Activity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.fongmi.android.tv.App
import com.fongmi.android.tv.api.config.VodConfig
import com.fongmi.android.tv.bean.Config
import com.fongmi.android.tv.bean.History
import com.fongmi.android.tv.impl.Callback
import com.fongmi.android.tv.ui.activity.SearchActivity
import com.fongmi.android.tv.ui.activity.TmdbDetailActivity
import com.fongmi.android.tv.ui.activity.VideoActivity
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import com.fongmi.android.tv.utils.HistoryProgressFormatter
import com.fongmi.android.tv.utils.ResUtil
import com.fongmi.android.tv.utils.Task
import java.util.Locale
import java.util.regex.Pattern

/**
 * HeroUI 历史页（Compose）的状态与业务。
 *
 * 逐条对应 `ref_docs/o11.java`（原版历史 Fragment）：
 * - [load]      ← `a0()` L238-246 + `m11` case 3 L56-59（后台线程读库 → 主线程刷新）
 * - [select]    ← `p0(i, z)` L457-511（站点标签 / 进度 / 剧名 / 元数据 / 简介 / Logo / 背景）
 * - [deleteAt]  ← `pp` case 14 L361-372（`history.delete()` + 本地移除 + 重排选中）
 * - [openResume] / [openDetail] ← `pp` case 14 L374-385 + `g20` case 2 L104-119
 *
 * **不碰数据层**：只调用 `History.getForDisplay()` / `history.delete()` /
 * `HistoryResumeCoordinator` 之外的既有公开入口（见 [open] 的三步分支）。
 * 所有线程切换走 `Task.execute`（读库）+ `App.post`（回主线程），与工程既有写法一致。
 */
class HistoryViewModel : ViewModel() {

    var state: HistoryUiState by mutableStateOf(HistoryUiState())
        private set

    /** 首次装载（对应 `a0()` → `m11` case 3）。 */
    fun load() = loadInternal(keepSelection = false)

    /** 刷新事件 / 删除后重载（对应 `a0()`）。 */
    fun reload() = loadInternal(keepSelection = true)

    private fun loadInternal(keepSelection: Boolean) {
        val previousKey = if (keepSelection) state.selectedItem?.key else null
        state = state.copy(loading = !state.loaded, empty = false)
        Task.execute {
            // `History.getForDisplay()` 内部已经做了「全局历史聚合 + 排序」（History.java L201-206），
            // 这里不再二次排序（原版 `History.get()` 的排序规则见待确认清单）。
            val items = History.getForDisplay().toList()
            App.post {
                val index = when {
                    items.isEmpty() -> -1
                    previousKey == null -> 0
                    else -> items.indexOfFirst { it.key == previousKey }.takeIf { it >= 0 } ?: 0
                }
                state = state.copy(items = items, loading = false, loaded = true, empty = items.isEmpty())
                if (index >= 0) select(index) else clearSelection()
            }
        }
    }

    /** 选中项变化（对应 `p0`）：所有左侧 Hero 信息一次性重算。 */
    fun select(index: Int) {
        val items = state.items
        if (index < 0 || index >= items.size) {
            clearSelection()
            return
        }
        val history = items[index]
        state = state.copy(
            selectedIndex = index,
            hero = buildHero(history),
        )
        prefetch(index)
    }

    private fun clearSelection() {
        state = state.copy(selectedIndex = -1, hero = HeroInfo())
    }

    /**
     * 删除（对应 `pp` case 14 L361-372）：先落库再改内存，最后按原版规则回退选中项。
     *
     * `history.delete()` 是 History bean 自身方法（`History.java` L1177），不碰 catvod 数据层。
     */
    fun deleteAt(index: Int) {
        val items = state.items
        if (index < 0 || index >= items.size) return
        val target = items[index]
        Task.execute {
            target.delete()
            App.post {
                if (index !in state.items.indices) return@post
                val remaining = state.items.toMutableList().apply { removeAt(index) }
                state = state.copy(items = remaining, empty = remaining.isEmpty(), loaded = true, loading = false)
                when {
                    remaining.isEmpty() -> clearSelection()
                    // 删除的是当前项：回退到 min(index, size - 1)（原版 `m11` case 1 L39 同一个公式）。
                    else -> select(index.coerceAtMost(remaining.size - 1))
                }
            }
        }
    }

    /**
     * 卡片确定键（原版 `pp` case 14 终点 = `o11.h0` → `DetailActivity.q2` 之外的「续播」分支）。
     *
     * [history] 默认取当前选中项；触摸点击时由调用方传入被点的那一条，避免「点了但没先聚焦」的错位。
     */
    fun openResume(activity: Activity, history: History? = state.selectedItem) =
        open(activity, history, resume = true)

    /** 「查看详情」按钮（原版 `g20` case 2 终点 = `o11.h0` → `DetailActivity.q2`）。 */
    fun openDetail(activity: Activity, history: History? = state.selectedItem) =
        open(activity, history, resume = false)

    /**
     * 点击三步分支（严格按 `pp` case 14 L374-385 / `g20` case 2 L104-119 的顺序，不允许跳过前两步）：
     *
     * 1. `Config.find(cid) == null` → 去搜索页（本工程无 `CollectActivity`，见文档「入口映射表」）；
     * 2. `cid != VodConfig.getCid()` → `VodConfig.selectConfig(config, cb)`，加载完成后再进终点；
     * 3. 终点：卡片 = 续播，按钮 = 详情。
     */
    private fun open(activity: Activity, history: History?, resume: Boolean) {
        if (history == null) return
        val config = Config.find(history.cid)
        if (config == null) {
            // 原版 `CollectActivity.c2(context, vodName)`：本工程 main 源集没有 CollectActivity
            // （仅 leanback 有），等价入口是 `SearchActivity.directFromHistory`，
            // 与 `HistoryResumeCoordinator.open` L48-50 的兜底分支同一入口。
            SearchActivity.directFromHistory(activity, history)
            return
        }
        if (history.cid != VodConfig.getCid()) {
            // 原版 `ds3.v(config, new a3(o11Var, resume, history))`：切源成功后再进终点。
            VodConfig.selectConfig(
                config,
                object : Callback() {
                    override fun success() {
                        endpoint(activity, history, resume)
                    }
                },
            )
            return
        }
        endpoint(activity, history, resume)
    }

    private fun endpoint(activity: Activity, history: History, resume: Boolean) {
        if (resume) {
            // 原版 `VideoActivity.q3(...)` 续播；本工程既有公开入口。
            VideoActivity.startFromHistory(activity, history)
        } else {
            // 原版 `DetailActivity.q2(context, siteKey, vodId, vodName, vodPic, vodRemarks, "")`。
            TmdbDetailActivity.startFromHistory(activity, history)
        }
    }

    /**
     * 选中项 Hero 信息（`p0` L457-511 的逐项映射）。
     *
     * `gd3.*` / `je2.*` 在本工程没有公开等价入口，对应字段直接取 History bean 已解析好的值：
     * - `gd3.l(vodName)`（背景）→ `history.wallPic`
     * - `gd3.t(vodName)` / `vodPic`（海报）→ `history.vodPic`
     * - `gd3.q(vodName)`（Logo）→ 无字段，恒空 → 剧名文字常显
     * - `gd3.s(vodName)`（简介）→ 无字段，恒空 → 简介行隐藏
     */
    private fun buildHero(history: History): HeroInfo {
        val name = history.vodName.orEmpty()
        val remarks = history.vodRemarks.orEmpty()
        val position = history.position
        val duration = history.duration
        val showProgress = position > 0 && duration > 0
        return HeroInfo(
            name = name,
            meta = HistoryMetaText.build(history),
            desc = "",
            pageSite = remarks,
            episode = if (showProgress) HistoryEpisodeLabel.resolve(remarks, name) else "",
            progressText = if (showProgress) progressText(position, duration) else "",
            progress = if (showProgress) (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f,
            showProgress = showProgress,
            backdropUrl = history.wallPic.orEmpty(),
            posterUrl = history.vodPic.orEmpty(),
            logoUrl = "",
        )
    }

    /**
     * `nm3.j(position) + " / " + nm3.j(duration)`（`o11.java` L675）。
     *
     * `nm3.j` 内部是 `pm3.M`（ref_docs 未收录，见待确认 #2），这里改用工程既有的
     * [HistoryProgressFormatter]（`utils/HistoryProgressFormatter.java` L10-19，mm:ss / h:mm:ss）。
     */
    private fun progressText(position: Long, duration: Long): String =
        "${HistoryProgressFormatter.format(position, duration)} / " +
            HistoryProgressFormatter.format(duration, duration)

    /**
     * 背景窗口预热（`o11.q0` L536-551：±2 项 Glide 150dp × 1.5 预热）+ `k0` L356-368（±2 背景窗口）。
     *
     * 原版 `gd3.g(...)` / `je2.d(...)` 是 TMDB 侧接口，本工程没有等价入口，
     * 这里只对已存在的 URL 做 Coil 预热，不发起任何 TMDB 查询。
     */
    private fun prefetch(index: Int) {
        val items = state.items
        for (i in (index - PREFETCH_RANGE)..(index + PREFETCH_RANGE)) {
            if (i < 0 || i >= items.size) continue
            val item = items[i]
            val poster = item.vodPic.orEmpty()
            if (poster.isNotEmpty()) HeroImagePrefetch.poster(poster)
            val backdrop = item.wallPic.orEmpty()
            if (backdrop.isNotEmpty()) HeroImagePrefetch.backdrop(backdrop)
        }
    }

    companion object {

        /** 预热窗口半径：±2（`o11.k0` L358 `je2.d(i - 2, i + 2)`、L360 `for (i2 = i - 2; i2 <= i + 2; i2++)`）。 */
        const val PREFETCH_RANGE = 2
    }
}

/** 历史页一次性状态快照。 */
data class HistoryUiState(
    val items: List<History> = emptyList(),
    val selectedIndex: Int = -1,
    val loading: Boolean = true,
    val loaded: Boolean = false,
    val empty: Boolean = false,
    val hero: HeroInfo = HeroInfo(),
) {

    val selectedItem: History?
        get() = items.getOrNull(selectedIndex)
}

/**
 * 左侧 Hero 信息区 + 页面角标的渲染数据。
 *
 * 字段与 `ref_docs/fragment_history.xml` 的控件一一对应，数值全部在 `HistoryScreen.kt` 里写死。
 */
data class HeroInfo(
    /** `heroNameText`（L90-102）。 */
    val name: String = "",
    /** `heroMetaText`（L111-123）= `pr2.b(vodName, b0(history), vodSiteRemarks)`。 */
    val meta: String = "",
    /** `heroDescText`（L130-144）= `gd3.s(vodName)`；本工程无来源，恒空。 */
    val desc: String = "",
    /** `pageSite`（L32-47）= `history.vodRemarks`。 */
    val pageSite: String = "",
    /** `progressEpisode`（L187-199）。 */
    val episode: String = "",
    /** `progressTotal`（L200-212）。 */
    val progressText: String = "",
    /** `progressBar` 进度 0..1（`HistoryProgressBar.java` L677-681）。 */
    val progress: Float = 0f,
    /** `progressGroup` 是否可见：`position > 0 && duration > 0`（`o11.r0` L568）。 */
    val showProgress: Boolean = false,
    /** 全屏 `heroBg` 背景大图（L13-19）。 */
    val backdropUrl: String = "",
    /** `posterCardImg` 回退海报（L60-65）。 */
    val posterUrl: String = "",
    /** `heroLogoImg`（L103-110）；本工程无来源，恒空。 */
    val logoUrl: String = "",
)

/**
 * `o11.b0(History)` L93-146 的逐行移植：Hero 信息区元数据行。
 *
 * 字段映射（原版 `gd3.*` → 本工程 `History` bean）：
 * | 原版 | 含义 | 本工程 |
 * | --- | --- | --- |
 * | `gd3.w(name)` | 年份 | `history.year` |
 * | `gd3.n(name)` | 类型串 | `history.typeName` |
 * | `gd3.m(name)` | 集数备注 | `history.vodRemarks` |
 * | `gd3.r(name)` | 媒体类型 | `history.mediaType` |
 * | `gd3.v(name)` | 总集数 | 无字段，恒空（分支因此跳过，等价原版「取不到」） |
 * | `vodSiteRemarks` | 站点备注 | `history.vodRemarks`（本工程未单独存站点备注） |
 */
internal object HistoryMetaText {

    /** 原版判定「有集数语义」的五个关键字（`o11.java` L101）。 */
    private val EPISODE_WORDS = listOf("集", "期", "季", "完结", "全")

    fun build(history: History): String {
        val year = history.year.orEmpty()
        val type = history.typeName.orEmpty()
        val episodeRemark = history.vodRemarks.orEmpty()
        val mediaType = history.mediaType.orEmpty()
        val total = ""
        val siteRemarks = history.vodRemarks.orEmpty()
        val hasEpisode = EPISODE_WORDS.any { siteRemarks.contains(it) }

        // L102-126：先算出「媒体类型」那一段（原版变量名 strV）。
        val mediaSegment: String = when {
            siteRemarks.contains("完结") -> when {
                total.isNotEmpty() -> "全${total}集"
                episodeRemark.isEmpty() || !episodeRemark.contains("全") ->
                    if (!hasEpisode) episodeRemark.ifEmpty { "剧集" } else siteRemarks
                else -> episodeRemark
            }
            episodeRemark.isNotEmpty() && episodeRemark.contains("全") && siteRemarks.contains("全") -> episodeRemark
            else -> if (!hasEpisode) episodeRemark else siteRemarks
        }

        // L127-129：年份、类型、集数语义、集数备注全空 → 直接回站点名。
        if (year.isEmpty() && type.isEmpty() && !hasEpisode && episodeRemark.isEmpty()) {
            return history.siteName.orEmpty()
        }

        // L130-145：年份 · 媒体类型 · 类型串（空段跳过）。
        val builder = StringBuilder()
        if (year.isNotEmpty()) builder.append(year)
        val media = if (mediaType == "movie") "电影" else mediaSegment.ifEmpty { "剧集" }
        builder.append(if (builder.isNotEmpty()) " · " else "")
        builder.append(media)
        if (type.isNotEmpty()) {
            builder.append(" · ")
            builder.append(type)
        }
        return builder.toString()
    }
}

/**
 * `o11.r0(History)` L571-669 的逐行移植：进度区「集数」文本。
 *
 * 解析顺序严格照抄（命中即停）：
 * O0（日期）→ P0（第N期[上中下]）→ Q0（先导片/加更/彩蛋/番外/花絮/预告/直拍）
 * → F0 去扩展名 → G0 去编码 → H0（EP/E+数字）→ K0（第N集/话/期）→ I0（D+数字）
 * → L0（disc N）→ N0（[N]）→ J0（开头数字）→ M0（结尾数字）→ 全部不中回落剧名。
 *
 * 正则与 [Z] 的格式化完全照抄 `o11.java` L42-53 / L83-89。
 */
internal object HistoryEpisodeLabel {

    private val F0 = Pattern.compile(
        "\\.(?:mp4|mkv|avi|m2ts|ts|rmvb|rm|flv|wmv|mov|webm|mpg|mpeg|vob|iso|m4v|3gp|f4v)\\b",
        Pattern.CASE_INSENSITIVE,
    )
    private val G0 = Pattern.compile("[xh]26[45]|hevc|avc|10bit|8bit", Pattern.CASE_INSENSITIVE)
    private val H0 = Pattern.compile("[Ee][Pp]?(\\d{1,4})")
    private val I0 = Pattern.compile("\\b[Dd](\\d{1,4})\\b")
    private val J0 = Pattern.compile("^(\\d{1,4})(?![0-9A-Za-z]|\\.\\d)")
    private val K0 = Pattern.compile("第(\\d{1,4})[集话期]")
    private val L0 = Pattern.compile("(?i)disc\\s*(\\d{1,4})")
    private val M0 = Pattern.compile("(?<=[\\]\\)）.\\s])(\\d{1,3})\\s*$")
    private val N0 = Pattern.compile("\\[\\s*((?!(?:480|720)\\s*\\])([0-9]{1,3})(?:[Vv][0-9]{1,2})?)\\s*\\]")
    private val O0 = Pattern.compile("(?<!\\d)(?:19|20)\\d{6}(?!\\d)|(?<!\\d)(?:19|20)\\d{2}[-._]\\d{1,2}[-._]\\d{1,2}(?!\\d)")
    private val P0 = Pattern.compile("第(\\d{1,4})期\\s*[（(]?\\s*([上中下])?")
    private val Q0 = Pattern.compile("先导片|加更版|加更|彩蛋|番外|花絮|预告|直拍")

    /** `o11.Z` L83-89：`E%02d`，非数字返回 null。 */
    private fun z(value: String): String? =
        value.toIntOrNull()?.let { String.format(Locale.ROOT, "E%02d", it) }

    fun resolve(remarks: String?, vodName: String?): String {
        // L571-607：日期优先（O0），命中后再看 P0 / Q0 追加后缀。
        var label: String? = null
        if (!remarks.isNullOrEmpty()) {
            val date = O0.matcher(remarks)
            if (date.find()) {
                var text = date.group().orEmpty().replace('.', '-').replace('_', '-')
                if (text.matches(Regex("(?:19|20)\\d{6}"))) {
                    text = text.substring(0, 4) + "-" + text.substring(4, 6) + "-" + text.substring(6)
                }
                val period = P0.matcher(remarks)
                if (period.find()) {
                    text += " 第${period.group(1)}期"
                    if (period.group(2) != null) text += "（${period.group(2)}）"
                } else {
                    val special = Q0.matcher(remarks)
                    if (special.find()) text += " ${special.group()}"
                }
                label = text
            }
        }

        // L608-666：没有日期时，按 F0 → G0 → H0 → K0 → I0 → L0 → N0 → J0 → M0 逐级回退。
        if (label == null) {
            val source = remarks.orEmpty()
            label = if (source.isEmpty()) {
                null
            } else {
                val extension = F0.matcher(source)
                val trimmed = if (extension.find()) source.substring(0, extension.start()) else source
                val cleaned = G0.matcher(trimmed).replaceAll(" ")
                val h = H0.matcher(cleaned)
                val k = K0.matcher(cleaned)
                val i = I0.matcher(cleaned)
                val l = L0.matcher(cleaned)
                val n = N0.matcher(cleaned)
                val j = J0.matcher(cleaned)
                val m = M0.matcher(cleaned)
                when {
                    h.find() -> z(h.group(1))
                    k.find() -> z(k.group(1))
                    i.find() -> i.group(0).uppercase(Locale.ROOT)
                    l.find() -> String.format(Locale.ROOT, "D%02d", l.group(1).toInt())
                    n.find() -> z(n.group(2))
                    j.find() -> {
                        val head = j.group(1).orEmpty()
                        // 4 位年份（19xx / 20xx）不能当集数，改取结尾数字（L641-650）。
                        if (head.length == 4 && (head.startsWith("19") || head.startsWith("20"))) {
                            if (m.find()) z(m.group(1)) else null
                        } else {
                            z(head)
                        }
                    }
                    m.find() -> z(m.group(1))
                    else -> null
                }
            }
        }

        // L667-669：全部不中 → 回落剧名。
        return label ?: vodName.orEmpty()
    }
}

/**
 * `s11.b(History)` L48-53：历史页卡片右上角 TMDB 角标是否显示。
 *
 * 原版判定 = 剧名非空 && `gd3.t(vodName)`（TMDB 海报）非空 && （`gd3.z(vodName)`（TMDB 背景）
 * 或 `gd3.p(key)`（按 key 取 TMDB 海报）非空）。
 *
 * **本工程没有 `gd3.*` 公开入口**，这里用 History bean 里已解析好的图片字段做等价近似：
 * 剧名非空 && 海报非空 && 背景非空。差异已记入待确认清单 #9。
 */
internal object HistoryCardTags {

    fun isTmdbTagVisible(history: History?): Boolean {
        if (history == null) return false
        if (history.vodName.isNullOrEmpty()) return false
        if (history.vodPic.isNullOrEmpty()) return false
        return !history.wallPic.isNullOrEmpty()
    }
}

/**
 * 图片预热（对应 `o11.q0` L536-551 的 ±2 海报预热与 `o11.k0` L356-368 的背景窗口）。
 *
 * 只预热 History bean 里已有的 URL，不发起任何 TMDB 查询：
 * - 海报：150dp × 1.5（`o11.q0` L536-537 `g2.b(150)` / `(int)(iB * 1.5f)`）；
 * - 背景：1920 × 1080（`HeroImageView.java` L125-126 `this.p = 1920; this.q = 1080`）。
 */
internal object HeroImagePrefetch {

    private const val POSTER_WIDTH_DP = 150
    private const val POSTER_HEIGHT_RATIO = 1.5f
    private const val BACKDROP_WIDTH = 1920
    private const val BACKDROP_HEIGHT = 1080

    fun poster(url: String) {
        val width = ResUtil.dp2px(POSTER_WIDTH_DP)
        enqueue(url, width, (width * POSTER_HEIGHT_RATIO).toInt())
    }

    fun backdrop(url: String) = enqueue(url, BACKDROP_WIDTH, BACKDROP_HEIGHT)

    private fun enqueue(url: String, width: Int, height: Int) {
        if (url.isEmpty() || width <= 0 || height <= 0) return
        try {
            val context = App.get()
            SingletonImageLoader.get(context).enqueue(
                ImageRequest.Builder(context).data(url).size(width, height).build(),
            )
        } catch (ignored: Throwable) {
            // 预热失败不影响主流程（原版 `q0` 的预热同样整体包在 try/catch 里）。
        }
    }
}
