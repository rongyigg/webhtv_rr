package com.fongmi.android.tv.ui.hero

import com.fongmi.android.tv.App
import com.fongmi.android.tv.bean.History
import com.fongmi.android.tv.utils.ResUtil
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import java.util.Locale
import java.util.regex.Pattern

/**
 * 历史页纯 View 版的文本 / 角标 / 预热工具。
 *
 * 这些 object 原来定义在 `HistoryViewModel.kt`（Compose 版）里；Compose 页面删除后
 * 原样搬到这里，保证 `HistoryMetaText` / `HistoryEpisodeLabel` / `HistoryCardTags` /
 * `HeroImagePrefetch` 的逐行行为不变，只改宿主文件。
 */

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
