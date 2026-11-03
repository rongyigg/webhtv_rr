package com.fongmi.android.tv.ui.hero

/**
 * 接口源 / 换源相关假数据。
 *
 * 本阶段只服务 UI 与焦点联调：不访问网络、不读取真实站点配置。
 * TODO(数据层对接)：接入真实数据时只替换本文件的列表，[SourceSidebar] / [ChangeSourceDialog] 不用改。
 */

/** 侧边栏条目：站点 + 资源类型，显示为「豆瓣 | 海报」。 */
internal data class HeroSourceEntry(
    val id: String,
    /** 所属站点名，与 [HeroSourceResult.site] 同一套取值，用于多选后过滤搜索结果。 */
    val site: String,
    val label: String,
)

/**
 * 换源结果卡片。字段与逆向 `adapter_search.xml` 一一对应：
 * - [name]    → `@id/name`   16sp 剧名（底部底板）
 * - [remark]  → `@id/remark` 14sp 备注（海报内偏下）
 * - [site]    → `@id/site`   来源（海报左上角小标）+ 与 [HeroSourceEntry.site] 同一套取值，用于多选过滤
 * - [date]    → 与 [site] 一起显示在左上角小标里
 * - [poster]  → 海报地址；为空时卡片走渐变占位 + 剧名首字
 */
internal data class HeroSourceResult(
    val id: String,
    val site: String,
    val name: String,
    val remark: String,
    val date: String,
    val poster: String = "",
)

/** 换源弹窗顶部站点栏。 */
internal val HERO_SOURCE_SITES: List<String> = listOf(
    "豆瓣",
    "TMDB",
    "爱奇艺",
    "腾讯视频",
    "优酷",
    "哔哩哔哩",
    "芒果TV",
    "1905电影网",
)

/** 侧边栏条目：每个站点都能单独勾选「海报 / 剧照」两类资源。 */
internal val HERO_SOURCE_ENTRIES: List<HeroSourceEntry> = HERO_SOURCE_SITES.flatMapIndexed { index, site ->
    listOf(
        HeroSourceEntry("$index-poster", site, "$site | 海报"),
        HeroSourceEntry("$index-still", site, "$site | 剧照"),
    )
}

/** 侧边栏默认全部勾选。 */
internal val HERO_SOURCE_INITIAL_CHECKED: BooleanArray = BooleanArray(HERO_SOURCE_ENTRIES.size) { true }

/** 结果总数：与卡片列表解耦，用来验证顶部灰色统计文案。 */
internal const val HERO_SOURCE_RESULT_TOTAL = 424

/** 换源结果假数据：日期 / 来源 / 剧名 / 备注 四项齐全，poster 留空以走渐变占位。 */
internal val HERO_SOURCE_RESULTS: List<HeroSourceResult> = listOf(
    HeroSourceResult("r0", "豆瓣", "小城良方", "更新至20集", "2026-10-28"),
    HeroSourceResult("r1", "TMDB", "漫长的季节", "全12集", "2026-10-27"),
    HeroSourceResult("r2", "爱奇艺", "繁花", "更新至30集", "2026-10-25"),
    HeroSourceResult("r3", "腾讯视频", "三体", "全30集", "2026-10-24"),
    HeroSourceResult("r4", "优酷", "狂飙", "全39集", "2026-10-22"),
    HeroSourceResult("r5", "哔哩哔哩", "灵笼", "更新至08集", "2026-10-20"),
    HeroSourceResult("r6", "芒果TV", "去有风的地方", "全40集", "2026-10-18"),
    HeroSourceResult("r7", "1905电影网", "流浪地球2", "HD国语", "2026-10-16"),
)
