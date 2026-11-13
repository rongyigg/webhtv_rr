/**
 * ============================================================================================
 * Hero 系列页面的公共 API（TMDB 数据模型 / 预取缓存 / Logo 缓存 / 图片档位）
 * ============================================================================================
 *
 * 旧 Compose 首页 `HeroHomeActivity` 已删除，但下面这些符号仍被
 * 纯 View 首页（HomeViewActivity）、详情页（HeroDetailActivity）、新历史页（HistoryViewActivity）、
 * 收藏页（KeepViewActivity）以及 leanback 播放页（VideoActivity）、FileUtil 使用，
 * 因此原样搬迁到本文件，**逻辑与常量值一字未改**，只改了包名。
 *
 * 内容：
 * - [HeroTmdbData]：跨页统一的 TMDB 数据模型；
 * - [HeroTmdbPrefetchCache]：内存预取缓存（首页预取 → 详情页 / 历史页直接取用）；
 * - [HeroLogoCache]：`posterKey → logoUrl` 落盘 + 内存缓存；
 * - [clearHeroCaches]：设置页「清理缓存」的统一入口；
 * - [heroTmdbKey] / [tmdbLogoKey]：跨页一致的缓存 key 规则；
 * - [upgradeTmdbImage]：TMDB 图片地址只升不降的档位升级。
 *
 * 保持同一份实现是刻意为之：key 规则或常量一旦分叉，「首页预取的东西详情页取不到」，
 * 表现为封面先空一帧再闪现。
 */
package com.fongmi.android.tv.ui.hero

import android.content.Context
import coil3.SingletonImageLoader
import com.fongmi.android.tv.App
import com.fongmi.android.tv.bean.TmdbItem
import com.fongmi.android.tv.bean.TmdbPerson
import com.google.gson.JsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 首页 override 里的一条完整 TMDB 数据。
 *
 * 首页只用其中三样：提档后的横图（[highResBackdrop]）、简介（[overview]）、艺术字 Logo（[logoUrl]）；
 * 详情页还要 [item] / [detail] / [cast] 才能零请求直接拼出 `Ready`。
 * 所以首页内存 overrides 与 [HeroTmdbPrefetchCache] 共用这一个模型，不维护两套字段。
 *
 * **不含竖版海报**：接口卡片图（`poster.url`）一律保持源站原图，TMDB 只增强背景 / 简介 / Logo。
 */
internal data class HeroTmdbData(
    val item: TmdbItem,
    /** `TmdbService.detail` 的原始 JSON：只有「匹配 + 横图」时为 null，焦点条目补详情后才有值。 */
    val detail: JsonObject? = null,
    val cast: List<TmdbPerson> = emptyList(),
    /** 已按当前语言取过译文的简介；空串 = 不覆盖源站简介。 */
    val overview: String = "",
    /** 艺术字 Logo 的完整地址；null / 空串 = 回落 32sp 文字标题。 */
    val logoUrl: String? = null,
    val highResBackdrop: String = "",
    /** 是否手动匹配命中：只有手动命中才允许覆盖剧名（历史决策）。 */
    val manual: Boolean = false,
    /**
     * TMDB 评分文案（如 `8.6`）；空串 = 没有评分。
     *
     * 与 [year] 一起只用于元数据行：原版 `HomeActivityV2` 的 `D2(pos)` 会把
     * `年份 · 分类 · TMDB 评分` 拼在同一行，缓存命中时第一帧就要有，不能等详情回来。
     */
    val rating: String = "",
    /** 上映 / 首播年份（从 `TmdbItem.subtitle` 里的 4 位年份抓）；空串 = 未知。 */
    val year: String = "",
)

/**
 * 首页预拉取好的 TMDB 详情，交给详情页直接取用（消除「点进详情页再等一轮网络」的卡顿）。
 *
 * 键 = [heroTmdbKey]（站点 + 条目 id）：与首页 `posterKey`、详情页 `siteKey + vodId` 是同一套规则，
 * 不同源里 id 相同的条目不会互相串数据。
 *
 * **只收完整数据**：[HeroTmdbData.detail] 为 null（只匹配到条目、还没拉详情）一律不入缓存 ——
 * 详情页拿到它时要能做到一条请求都不发。
 */
internal object HeroTmdbPrefetchCache {

    /**
     * 条数上限：详情 JSON 单条几十 KB 到几百 KB。
     *
     * 缓存里存的是与首页 override **同一个对象引用**，不额外复制；命中率靠「预取 + 焦点条目补写」，
     * 这里只负责内存有界（详情页取走即删，正常使用不会有几十条同时驻留）。
     */
    private const val MAX_ENTRIES = 24

    private val entries = ConcurrentHashMap<String, HeroTmdbData>()

    /** 写入一条完整数据；`detail` 为空时忽略（调用方无需自己判断数据是否完整）。 */
    fun put(key: String, data: HeroTmdbData) {
        if (key.isBlank() || data.detail == null) return
        // 超上限就丢一条（任意一条）：只求有界，精确 LRU 对「进详情页即取走」的场景没有价值。
        if (entries.size >= MAX_ENTRIES && !entries.containsKey(key)) {
            entries.keys.firstOrNull()?.let { entries.remove(it) }
        }
        entries[key] = data
        // Logo 再单独落一份极简磁盘缓存：`TmdbMatchCache.Entry` 没有 logo 字段，
        // 冷启动第一帧只能靠这份键值把艺术字直接画出来（详见 [HeroLogoCache]）。
        HeroLogoCache.save(key, data.logoUrl.orEmpty())
        // 同时落一份与接口源无关的 TMDB 身份键：换源 / 换采集站后 vodId 会变，
        // 历史页只能拿到 `TmdbItem`，靠它仍能命中同一部作品的艺术字。
        HeroLogoCache.save(tmdbLogoKey(data.item), data.logoUrl.orEmpty())
    }

    /** 取出并删除：详情页用过一次就作废，避免用户手动改过匹配后仍拿到旧条目。 */
    fun take(key: String): HeroTmdbData? = entries.remove(key)

    /**
     * 只读命中、**不删除**。
     *
     * 首页首帧要用它把艺术字 Logo 直接画出来（磁盘匹配缓存里没有 Logo 字段），
     * 这条数据随后还要交给详情页 `take()`，所以首页只能 peek，不能把条目取走。
     */
    fun peek(key: String): HeroTmdbData? = entries[key]

    /** 显式作废（手动改成别的 TMDB 条目时调用）。 */
    fun remove(key: String) {
        if (key.isNotBlank()) entries.remove(key)
        HeroLogoCache.remove(key)
    }

    /** 「清理缓存」联动：进程内详情也一起丢掉，否则清完文件回首页仍能读到旧条目。 */
    fun clear() {
        entries.clear()
    }
}

/**
 * 首页艺术字 Logo 的极简磁盘缓存：`posterKey → logoUrl`。
 *
 * `TmdbMatchCache.Entry` 只有 tmdbId / title / 横图 / 评分等字段，**没有 logoUrl**
 * （见 `TmdbMatchCache.java`）。所以冷启动时即使匹配命中磁盘缓存，Logo 也只能等联网详情回来，
 * 视觉上就是「先一行 32sp 文字，几百毫秒后再换成艺术字」。
 *
 * 这里单独存一份只读展示用的键值：
 * - 首次访问 `memory` 时**同步**把整个 prefs 读进内存（SharedPreferences 本来也是一次性解析 XML），
 *   之后 `snapshot()` 全是内存查表，冷启动第一帧没有异步空窗；
 * - 写入点只有 [HeroTmdbPrefetchCache.put]（拿到带 Logo 的完整详情时才写），频率极低；
 * - 写内存 + `apply()` 异步落盘：当前进程立刻可读，又不阻塞焦点切换；
 * - 条数封顶 [MAX_ENTRIES]，超出时丢任意一条，避免长期使用后无限增长。
 */
internal object HeroLogoCache {

    private const val PREFS_NAME = "hero_logo_cache"
    private const val MAX_ENTRIES = 200

    private val prefs by lazy { App.get().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    /**
     * 进程内镜像：`posterKey → logoUrl`。
     *
     * 用 [ConcurrentHashMap]：详情协程可能在 IO 线程写，首帧 / 焦点切换在主线程读。
     * `snapshot()` 直接返回这个活映射，后续 [save] 写入当前进程立即可见。
     */
    private val memory: ConcurrentHashMap<String, String> by lazy {
        ConcurrentHashMap<String, String>().apply {
            prefs.all.forEach { (key, value) ->
                if (value is String && value.isNotBlank()) put(key, value)
            }
        }
    }

    /** 首帧构建展示数据专用：一次返回全部 Logo，调用方只做内存查表。 */
    fun snapshot(): Map<String, String> = memory

    /**
     * 单条查询：历史页 `p0()` 的 `TmdbCache.q(vodName)` 等价物。
     *
     * 只在内存里查表（[memory] 首次访问时已同步读完整个 prefs），可以在焦点切换的
     * 主线程上直接调用，不会产生磁盘 IO。
     */
    fun get(posterKey: String): String =
        if (posterKey.isBlank()) "" else memory[posterKey].orEmpty()

    fun save(posterKey: String, logoUrl: String) {
        if (posterKey.isBlank() || logoUrl.isBlank()) return
        // 同一个 URL 不重复写：焦点来回移动时 `put` 会被反复调用。
        if (memory[posterKey] == logoUrl) return
        if (memory.size >= MAX_ENTRIES && !memory.containsKey(posterKey)) {
            memory.keys.firstOrNull()?.let { remove(it) }
        }
        // 先写内存：当前进程立刻能读到；`apply()` 异步落盘，不阻塞主线程。
        memory[posterKey] = logoUrl
        prefs.edit().putString(posterKey, logoUrl).apply()
    }

    /** 手动改成别的 TMDB 条目时同步作废：旧 Logo 是上一部作品的，不能留到下一次冷启动。 */
    fun remove(posterKey: String) {
        if (posterKey.isBlank()) return
        memory.remove(posterKey)
        if (prefs.contains(posterKey)) prefs.edit().remove(posterKey).apply()
    }

    /** 「清理缓存」联动：内存镜像 + prefs 一起清（prefs 在 `shared_prefs/`，不在 cacheDir 里）。 */
    fun clear() {
        memory.clear()
        prefs.edit().clear().apply()
    }
}

/**
 * 「清理缓存」联动的 Hero 内存缓存清理（由 `FileUtil.clearCache` 调用）。
 *
 * 磁盘部分已经由 `Path.clear(Path.cache())` 删掉：`cacheDir/hero_image_cache`（Coil 位图）
 * 与 `cacheDir/app_cache`（`TmdbMatchCache` 的 JSON）都在 cacheDir 下。
 * 删文件删不到的只剩这三份**进程内**状态：
 * 1. Coil 内存位图缓存（不清的话回首页还是旧图）；
 * 2. `HeroTmdbPrefetchCache`（详情预取，清完还能直接进详情页不联网）；
 * 3. `HeroLogoCache`（艺术字存在 `shared_prefs/hero_logo_cache`，不在 cacheDir 里）。
 *
 * 注意：主页 Compose 里的 `tmdbOverrides` 是本轮会话内已经生效的展示数据，
 * 退出首页 / 重新进列表时随 `homePosters` 重建，不在这里强行改写。
 */
fun clearHeroCaches() {
    HeroTmdbPrefetchCache.clear()
    HeroLogoCache.clear()
    SingletonImageLoader.get(App.get()).memoryCache?.trimToSize(0)
}

/**
 * 站点内条目身份：`站点 key | 条目 id`。
 *
 * 首页 override、首页→详情页预取缓存、详情页取缓存三处必须用同一个键。
 * **不能用剧名**：同名不同季 / 重制版会互相覆盖，`Vod.id` 才是站点内唯一键。
 */
internal fun heroTmdbKey(siteKey: String, vodId: String): String = siteKey + "|" + vodId

/**
 * 跨接口源的 TMDB 身份键：`tmdb | 媒体类型 | tmdbId`。
 *
 * [heroTmdbKey] 是「站点 key | 条目 id」，换一个采集站后同一个作品就是另一个键，
 * 历史页只能从 `TmdbItem` 拿到 tmdbId / mediaType，查不到任何源头写入的艺术字。
 * 所以在 [HeroTmdbPrefetchCache.put] 里额外落这一份与源无关的键，供历史页兜底读取。
 *
 * 无效条目（无 tmdbId / 无 mediaType）返回空串，[HeroLogoCache] 会直接忽略空键。
 */
internal fun tmdbLogoKey(item: TmdbItem?): String {
    if (item == null) return ""
    if (item.tmdbId <= 0) return ""
    val mediaType = item.mediaType.orEmpty().lowercase()
    if (mediaType.isBlank()) return ""
    return "tmdb|" + mediaType + "|" + item.tmdbId
}

// TMDB 图床的宽度档位（从低到高）。`original` 不在表里：原图可能 4K，电视盒子扛不住，
// 也绝不应该在「提档」时被降级成定宽图。
private val TMDB_IMAGE_WIDTHS =
    listOf("w45", "w92", "w154", "w185", "w300", "w342", "w500", "w780", "w1280", "w1920")

/**
 * 把 TMDB 图片地址提到 [targetWidth] 档，**只升不降**。
 *
 * - `/t/p/w342/abc.jpg` → `/t/p/w500/abc.jpg`；
 * - 已经是更大的档位、`original`、或自定义图床（不在 `/t/p/` 下）时原样返回，
 *   用户自己配的 `imageBase` / `backdropBase` 不会被改写。
 *
 * `internal`（而不是 private）：详情页拼演员头像也要用同一套提档规则，
 * 两处表达式必须逐字一致，否则首页预热与详情页显示是两个 URL，磁盘缓存白热。
 */
internal fun upgradeTmdbImage(url: String, targetWidth: String): String {
    if (url.isBlank()) return url
    val marker = "/t/p/"
    val markerIndex = url.indexOf(marker)
    if (markerIndex < 0) return url
    val sizeStart = markerIndex + marker.length
    val sizeEnd = url.indexOf('/', sizeStart)
    if (sizeEnd < 0) return url
    val current = TMDB_IMAGE_WIDTHS.indexOf(url.substring(sizeStart, sizeEnd))
    val target = TMDB_IMAGE_WIDTHS.indexOf(targetWidth)
    if (current < 0 || target < 0 || current >= target) return url
    return url.substring(0, sizeStart) + targetWidth + url.substring(sizeEnd)
}
