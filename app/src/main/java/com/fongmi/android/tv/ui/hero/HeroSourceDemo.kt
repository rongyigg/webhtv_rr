package com.fongmi.android.tv.ui.hero

import androidx.compose.ui.graphics.Color

/**
 * 接口源 / 换源相关假数据。
 *
 * 本阶段只服务 UI 与焦点联调：不访问网络、不读取真实站点配置。
 * 接入真实数据时只需替换本文件里的列表，[SourceSidebar] / [ChangeSourceDialog] 不用改。
 */

/** 一个接口源站点：名称 + 单字图标 + 图标底色。 */
internal data class HeroSourceSite(
    val id: String,
    val name: String,
    val glyph: String,
    val color: Color,
)

/**
 * 侧边栏条目：站点 + 资源类型（海报 / 剧照）。
 * 显示为「豆瓣 | 海报」这种形式，和需求示例一致。
 */
internal data class HeroSourceEntry(
    val site: HeroSourceSite,
    val type: String,
) {
    val key: String = "${site.id}-$type"

    val displayName: String = "${site.name} | $type"
}

/** 换源结果卡片：日期 + 来源名；剧名由弹窗参数传入（当前 = 详情页剧名）。 */
internal data class HeroSourceResult(
    val id: String,
    val date: String,
    val source: String,
    val gradientStart: Color,
    val gradientEnd: Color,
)

internal val HERO_SOURCE_SITES: List<HeroSourceSite> = listOf(
    HeroSourceSite("douban", "豆瓣", "豆", Color(0xFF2E7D32)),
    HeroSourceSite("tmdb", "TMDB", "T", Color(0xFF1565C0)),
    HeroSourceSite("iqiyi", "爱奇艺", "爱", Color(0xFF00897B)),
    HeroSourceSite("tencent", "腾讯视频", "腾", Color(0xFF1E88E5)),
    HeroSourceSite("youku", "优酷", "优", Color(0xFF00ACC1)),
    HeroSourceSite("bilibili", "哔哩哔哩", "B", Color(0xFFE91E63)),
    HeroSourceSite("mgtv", "芒果TV", "芒", Color(0xFFFB8C00)),
    HeroSourceSite("m1905", "1905电影网", "1", Color(0xFFC62828)),
)

/** 侧边栏默认条目：每个站点都能单独勾选「海报 / 剧照」两类资源。 */
internal val HERO_SOURCE_ENTRIES: List<HeroSourceEntry> = HERO_SOURCE_SITES.flatMap { site ->
    listOf(
        HeroSourceEntry(site = site, type = "海报"),
        HeroSourceEntry(site = site, type = "剧照"),
    )
}

/**
 * 结果总数：与卡片列表解耦，用来验证顶部「424个条目」的灰色统计文案。
 * 真实数据接入后由搜索结果 count 提供。
 */
internal const val HERO_SOURCE_RESULT_TOTAL = 424

/** 红绿渐变占位卡：不依赖网络图片，先验证横向滚动、边框与焦点缩放。 */
internal val HERO_SOURCE_RESULTS: List<HeroSourceResult> = listOf(
    HeroSourceResult("r0", "2026-10-28", "豆瓣", Color(0xFFB23A3A), Color(0xFF2F7D5A)),
    HeroSourceResult("r1", "2026-10-27", "TMDB", Color(0xFF8E2A2A), Color(0xFF1F6F5C)),
    HeroSourceResult("r2", "2026-10-25", "爱奇艺", Color(0xFFAA3A4A), Color(0xFF3E8E5A)),
    HeroSourceResult("r3", "2026-10-24", "腾讯视频", Color(0xFFC0392B), Color(0xFF27AE60)),
    HeroSourceResult("r4", "2026-10-22", "优酷", Color(0xFF9C2B2B), Color(0xFF2E8B57)),
    HeroSourceResult("r5", "2026-10-20", "哔哩哔哩", Color(0xFFB5404A), Color(0xFF1E7A4E)),
    HeroSourceResult("r6", "2026-10-18", "芒果TV", Color(0xFFA63A2E), Color(0xFF357A52)),
    HeroSourceResult("r7", "2026-10-16", "1905电影网", Color(0xFF8F2F35), Color(0xFF2B6F5E)),
    HeroSourceResult("r8", "2026-10-15", "豆瓣", Color(0xFFB23A3A), Color(0xFF2F7D5A)),
    HeroSourceResult("r9", "2026-10-13", "TMDB", Color(0xFF8E2A2A), Color(0xFF1F6F5C)),
)
