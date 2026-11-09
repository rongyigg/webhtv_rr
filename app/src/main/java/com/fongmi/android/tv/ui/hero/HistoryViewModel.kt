package com.fongmi.android.tv.ui.hero

import android.app.Activity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.fongmi.android.tv.App
import com.fongmi.android.tv.bean.History
import com.fongmi.android.tv.bean.TmdbItem
import com.fongmi.android.tv.bean.TmdbMatchCache
import com.fongmi.android.tv.setting.Setting
import com.fongmi.android.tv.ui.activity.HeroDetailActivity
import com.fongmi.android.tv.ui.activity.HeroLogoCache
import com.fongmi.android.tv.ui.activity.VideoActivity
import com.fongmi.android.tv.ui.activity.heroTmdbKey
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
 * **不碰数据层**：只调用 `History.getForDisplay()` / `history.delete()` 这类既有公开入口，
 * 跳转同样直接走工程既有入口（[VideoActivity.startFromHistoryFullscreen] / [HeroDetailActivity.start]），
 * 不做任何配置/cid 前置校验。
 * 所有线程切换走 `Task.execute`（读库）+ `App.post`（回主线程），与工程既有写法一致。
 */
class HistoryViewModel : ViewModel() {

    var state: HistoryUiState by mutableStateOf(HistoryUiState())
        private set

    /**
     * TMDB 匹配缓存：整页只读一次（与 `HomeViewActivity` 的 `syncCache` 同一策略）。
     *
     * `Setting.getTmdbMatchCache()` 每次调用都要做一次整表 Gson 解析，而 [buildHero] 在
     * 每一次焦点切换时都要查背景图（[tmdbBackdrop]），不能在那里逐次调用。
     */
    private var tmdbCache: TmdbMatchCache? = null

    /**
     * 首次装载（对应 `a0()` → `m11` case 3）：后台读库 → 主线程刷新。
     *
     * 数据唯一来源是 `History.getForDisplay()`（内部已含「全局历史聚合 + 按 createTime 倒序」），
     * 不再有 Mock 回退分支：真实记录为空就直接进入空态。
     */
    fun load() {
        loadInternal(keepSelection = false)
    }

    /** 刷新事件 / 删除后重载（对应 `a0()`）。 */
    fun reload() = loadInternal(keepSelection = true)

    private fun loadInternal(keepSelection: Boolean) {
        val previousKey = if (keepSelection) state.selectedItem?.key else null
        state = state.copy(loading = !state.loaded, empty = false)
        Task.execute {
            // TMDB 匹配缓存整页只读一次：读盘 / 解析都留在后台线程，主线程只拿引用。
            val tmdb = runCatching { Setting.getTmdbMatchCache() }.getOrNull()
            // `History.getForDisplay()` 内部已经做了「全局历史聚合 + 排序」（History.java L201-206），
            // 这里不再二次排序（原版 `History.get()` 的排序规则见待确认清单）。
            val items = History.getForDisplay().toList()
            App.post {
                tmdbCache = tmdb
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
            App.post { removeItemAt(index) }
        }
    }

    /** 从内存列表移除第 [index] 项，并按原版 `m11` case 1 L39 的公式回退选中项。 */
    private fun removeItemAt(index: Int) {
        if (index !in state.items.indices) return
        val remaining = state.items.toMutableList().apply { removeAt(index) }
        state = state.copy(items = remaining, empty = remaining.isEmpty(), loaded = true, loading = false)
        when {
            remaining.isEmpty() -> clearSelection()
            // 删除的是当前项：回退到 min(index, size - 1)（原版 `m11` case 1 L39 同一个公式）。
            else -> select(index.coerceAtMost(remaining.size - 1))
        }
    }

    /**
     * 卡片单击 / 确定键 → **全屏续播**（原版 `pp` case 14 终点 = `o11.h0` 的「续播」分支）。
     *
     * 走 [VideoActivity.startFromHistoryFullscreen]：内部仍是工程既有的历史续播链路
     * （`TmdbDetailActivity.startFromHistory` 或 `startDirect(..., history)`，把整条 `History`
     * 带进播放页续播），只是多带一个「直接全屏」标记 —— 不再显示 leanback 播放页的
     * 「左侧小窗 + 右侧选集」嵌入式首屏，也不带任何 PIP / 悬浮窗参数。
     * 历史页**不做** `Config.find` / `cid` 前置校验（那是原版首页/收藏页的切源逻辑，
     * 在这里只会把已入库的历史降级到搜索页）。
     *
     * [history] 默认取当前选中项；触摸点击时由调用方传入被点的那一条，避免「点了但没先聚焦」的错位。
     */
    fun openResume(activity: Activity, history: History? = state.selectedItem) {
        val item = history ?: return
        VideoActivity.startFromHistoryFullscreen(activity, item)
    }

    /**
     * 「查看详情」按钮 → **真实详情页**（`ref_docs/HistoryFragment.java` L338-344 `h0()`）：
     *
     * ```java
     * DetailActivity.q2(L(), history.getSiteKey(), history.getVodId(), history.getVodName(),
     *                   history.getVodPic(), history.getVodRemarks(), "");
     * ```
     *
     * 七个参数**全部来自这条历史记录本身**：不构造 Mock `Vod`、不塞假 `siteKey` / 假 `vodId`。
     * 详情页拿到真实 `vodId` 后自己走 `SiteViewModel.detailContent` 拉线路与选集。
     */
    fun openDetail(activity: Activity, history: History? = state.selectedItem) {
        val item = history ?: return
        HeroDetailActivity.start(
            activity,
            item.siteKey,
            item.vodId,
            item.vodName.orEmpty(),
            item.vodPic.orEmpty(),
            item.vodRemarks.orEmpty(),
            "",
        )
    }

    /**
     * 选中项 Hero 信息（`p0` L457-511 的逐项映射）。
     *
     * `gd3.*` / `je2.*` 在本工程没有公开等价入口，对应字段直接取 History bean 已解析好的值，
     * 或取 TMDB 匹配缓存里的同源数据：
     * - `gd3.l(vodName)`（背景）→ TMDB 匹配缓存里的横图（[tmdbBackdrop]，对应 `q0` 的 `TmdbCache.l`）
     * - `gd3.t(vodName)` / `vodPic`（海报）→ `history.vodPic`
     * - `gd3.q(vodName)`（Logo）→ 艺术字缓存 [HeroLogoCache]（[tmdbLogo]）
     * - `gd3.s(vodName)`（简介）→ TMDB 匹配缓存里的 `overview`（[tmdbItem]）
     *
     * 三者都必须来自**同一条** TMDB 缓存记录：`p0` L470-471 的 `TmdbCache.s` / `TmdbCache.q`
     * 与原版 `q0` 的背景图是同一个条目，混用不同来源会出现「图是 A 剧、字是 B 剧」。
     */
    private fun buildHero(history: History): HeroInfo {
        val name = history.vodName.orEmpty()
        val remarks = history.vodRemarks.orEmpty()
        val position = history.position
        val duration = history.duration
        val showProgress = position > 0 && duration > 0
        // TMDB 记录整条查一次：背景 / 简介共用，避免同一次焦点切换里做两遍 Gson 之外的查表。
        val tmdb = tmdbItem(history)
        return HeroInfo(
            name = name,
            meta = HistoryMetaText.build(history),
            // `p0` L470 `d0.j.setText(TmdbCache.s(vodName))`：简介为空时 UI 自己留空占位。
            desc = tmdb?.overview.orEmpty(),
            pageSite = remarks,
            episode = if (showProgress) HistoryEpisodeLabel.resolve(remarks, name) else "",
            progressText = if (showProgress) progressText(position, duration) else "",
            progress = if (showProgress) (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f,
            showProgress = showProgress,
            // `q0` L514-526：**只认 TMDB 横图**。为空时 `HistoryScreen` 会走
            // 「`heroBg.reset()` + 显示右上角 posterCard」那条分支，绝不拿竖图拉伸铺满全屏。
            backdropUrl = tmdb?.backdropUrl.orEmpty(),
            posterUrl = history.vodPic.orEmpty(),
            // `p0` L471-495 `TmdbCache.q(vodName)`：有艺术字就隐藏文字标题，没有就回落文字。
            logoUrl = tmdbLogo(history),
        )
    }

    /**
     * 当前这条历史对应的 TMDB 匹配记录（`TmdbMatchCache`，key = 站点 + vodId）。
     *
     * 与 `History.enrichTmdbId()` L1148 / `SubtitleTmdbResolver` 同一套用法。
     * 缓存对象在 [loadInternal] 的后台线程里整页读一次，这里只做内存查询。
     */
    private fun tmdbItem(history: History): TmdbItem? {
        val cache = tmdbCache ?: return null
        val siteKey = runCatching { history.siteKey }.getOrNull().orEmpty()
        val vodId = runCatching { history.vodId }.getOrNull().orEmpty()
        if (siteKey.isEmpty() || vodId.isEmpty()) return null
        return runCatching { cache.find(siteKey, vodId, history.vodName.orEmpty()) }.getOrNull()
    }

    /**
     * 全屏背景大图（`HistoryFragment.q0()` L514-526 `TmdbCache.l(vodName)` 的等价实现）。
     *
     * 原版分支：
     * - 命中横图 → `heroBg.f(url, i, null)`，并隐藏右侧 `posterCard`；
     * - 未命中 → `heroBg.a()`（`reset()`），显示右侧 `posterCard`。
     */
    private fun tmdbBackdrop(history: History): String = tmdbItem(history)?.backdropUrl.orEmpty()

    /**
     * 艺术字 Logo（`p0` L471-495 `TmdbCache.q(vodName)` 的等价实现）。
     *
     * `TmdbMatchCache.Entry` 没有 logo 字段（见 `TmdbMatchCache.java`），本工程把 TMDB 艺术字
     * 单独落在 [HeroLogoCache]（`posterKey → logoUrl`，与首页 / 详情页同一套 key
     * [heroTmdbKey]）。命中就返回 URL，`p0` 的「隐藏文字标题、显示 heroLogoImg」由 UI 执行；
     * 未命中返回空串，UI 继续显示 32sp 文字标题，与 `p0` L474-491 的两条分支一一对应。
     */
    private fun tmdbLogo(history: History): String {
        val siteKey = runCatching { history.siteKey }.getOrNull().orEmpty()
        val vodId = runCatching { history.vodId }.getOrNull().orEmpty()
        if (siteKey.isEmpty() || vodId.isEmpty()) return ""
        return HeroLogoCache.get(heroTmdbKey(siteKey, vodId))
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
     * 原版 `gd3.g(...)` / `je2.d(...)` 是 TMDB 侧接口，本工程用 [TmdbMatchCache] 的横图代替，
     * 只对已存在的 URL 做 Coil 预热，不发起任何 TMDB 查询。
     * 预热必须与 `HistoryScreen` 里 `HeroBackdrop` 真正会加载的那张**完全一致**：原来预热的是
     * `wallPic`，而现在背景走 TMDB 横图，预热错了图就等于焦点落上去仍然要现下载。
     */
    private fun prefetch(index: Int) {
        val items = state.items
        for (i in (index - PREFETCH_RANGE)..(index + PREFETCH_RANGE)) {
            if (i < 0 || i >= items.size) continue
            val item = items[i]
            val poster = item.vodPic.orEmpty()
            if (poster.isNotEmpty()) HeroImagePrefetch.poster(poster)
            val backdrop = tmdbBackdrop(item)
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
    /** `heroDescText`（L130-144）= `gd3.s(vodName)`；本工程取 TMDB 匹配缓存的 `overview`。 */
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
    /** `heroLogoImg`（L103-110）；非空时隐藏 `heroNameText`，取 [HeroLogoCache]。 */
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
