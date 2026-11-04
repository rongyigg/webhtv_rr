package com.fongmi.android.tv.ui.hero

/**
 * 历史页假数据。
 *
 * 本阶段只服务 UI 与焦点联调：不读本地库、不访问网络。
 * TODO(数据层对接)：接 `History` 表时只替换本文件的列表，[com.fongmi.android.tv.ui.activity.HeroHistoryActivity]
 *  以及 [HeroPosterCard] 都不用改。
 *
 * 字段来源（真值，不得私自改）：
 * - [title] / [meta] / [synopsis] → `ref_docs/fragment_history.xml` L90-145 的 `heroNameText` 32sp、
 *   `heroMetaText` 14sp、`heroDescText` 12sp 三块文本；首批文案取自 `ref_docs/history_page.png`
 *   与首页 `HeroHomeActivity` 的 Mock 种子，不新造世界观。
 * - [site] → `ref_docs/HistoryPosterPresenter.java` L91-94 `history.getSiteName()`，
 *   对应卡片左上角标签（`adapter_history_poster.xml` L50-62 `siteTag`），映射到
 *   [HeroPosterCard.topStartTag]（全局铁律：卡片只允许用这一个组件）。
 * - [remark] → 卡片底部备注，历史页原版不显示（`adapter_history_poster.xml` 没有该节点），
 *   这里只作为数据字段保留，绑定时空串即隐藏。
 * - [episode] / [totalEpisodes] / [positionMs] / [durationMs] → `fragment_history.xml` L186-218
 *   的 `progressEpisode` / `progressTotal` / `progressBar` 三件套；`history_page.png` 实测
 *   文案为「E02」+「27:15 / 47:34」。
 * - [poster] / [backdrop] → 同首页 Mock 一样用 picsum 稳定链接，分别对应
 *   `adapter_history_poster` 的 120×180 海报与 `fragment_history.xml` L13-19 的全屏 `heroBg`。
 */
internal data class HeroHistoryItem(
    val id: String,
    val title: String,
    /** 来源站点名（`Vod.getSiteName()` 的取值），用于卡片左上角标签。 */
    val site: String,
    /** 备注（如「更新至02集」）。历史页卡片默认不显示，保留给「继续观看」列表复用。 */
    val remark: String,
    /** Hero 信息区元数据行：`2026·全18集·悬疑/犯罪·豆 6.0`。 */
    val meta: String,
    /** Hero 信息区两行简介。 */
    val synopsis: String,
    /** 当前观看集号（1 基）。 */
    val episode: Int,
    /** 总集数。 */
    val totalEpisodes: Int,
    /** 已观看位置（毫秒）。 */
    val positionMs: Long,
    /** 单集总时长（毫秒）。 */
    val durationMs: Long,
    /** 海报（120×180）；空串走 [HeroPosterCard] 的渐变占位。 */
    val poster: String,
    /** 全屏背景大图；空串保持纯黑底。 */
    val backdrop: String,
)

/**
 * 历史记录假数据：首批四条对应 `ref_docs/history_page.png` 里可见的《醉梦》《小城良方》
 * 《把黑夜点燃》《尚公主》，其余复用首页已有的 Mock 种子，避免凭空造新剧。
 *
 * 《醉梦》的 meta / 简介 / 进度严格照抄 `history_page.png`（2026·全18集·悬疑/犯罪·豆 6.0，
 * E02，27:15 / 47:34），其余为 Mock 阶段占位值。
 */
internal val HERO_HISTORY_ITEMS: List<HeroHistoryItem> = listOf(
    HeroHistoryItem(
        id = "h0",
        title = "醉梦",
        site = "豆瓣",
        remark = "更新至02集",
        meta = "2026·全18集·悬疑/犯罪·豆 6.0",
        synopsis = "以千禧年企业改革为背景，记者沈乐水重返拉城，通过镜头串联起啤酒厂十余年的兴衰变迁，" +
            "揭开女工刘雪梅等一系列死亡事件背后交织的权谋阴谋。",
        episode = 2,
        totalEpisodes = 18,
        positionMs = 27 * 60_000L + 15_000L,
        durationMs = 47 * 60_000L + 34_000L,
        poster = "https://picsum.photos/seed/hero-history-poster-0/400/600",
        backdrop = "https://picsum.photos/seed/hero-history-backdrop-0/1280/720",
    ),
    HeroHistoryItem(
        id = "h1",
        title = "小城良方",
        site = "TMDB",
        remark = "更新至20集",
        meta = "2026·全28集·都市/家庭·豆 7.8",
        synopsis = "一间老药铺，藏着三代人的处方与心事。",
        episode = 20,
        totalEpisodes = 28,
        positionMs = 12 * 60_000L + 4_000L,
        durationMs = 45 * 60_000L + 10_000L,
        poster = "https://picsum.photos/seed/hero-history-poster-1/400/600",
        backdrop = "https://picsum.photos/seed/hero-history-backdrop-1/1280/720",
    ),
    HeroHistoryItem(
        id = "h2",
        title = "把黑夜点燃",
        site = "爱奇艺",
        remark = "全24集",
        meta = "2026·全24集·悬疑/犯罪·豆 7.2",
        synopsis = "一桩旧案把三个人重新绑在一起，他们决定把黑夜点燃。",
        episode = 7,
        totalEpisodes = 24,
        positionMs = 5 * 60_000L + 42_000L,
        durationMs = 44 * 60_000L + 20_000L,
        poster = "https://picsum.photos/seed/hero-history-poster-2/400/600",
        backdrop = "https://picsum.photos/seed/hero-history-backdrop-2/1280/720",
    ),
    HeroHistoryItem(
        id = "h3",
        title = "尚公主",
        site = "腾讯视频",
        remark = "更新至07集",
        meta = "2026·全36集·古装/爱情·豆 6.9",
        synopsis = "和亲路上她换了身份，也换了半个王朝的命运。",
        episode = 7,
        totalEpisodes = 36,
        positionMs = 31 * 60_000L + 20_000L,
        durationMs = 48 * 60_000L + 5_000L,
        poster = "https://picsum.photos/seed/hero-history-poster-3/400/600",
        backdrop = "https://picsum.photos/seed/hero-history-backdrop-3/1280/720",
    ),
    HeroHistoryItem(
        id = "h4",
        title = "后会无期",
        site = "优酷",
        remark = "全24集",
        meta = "2026·全24集·剧情/公路·豆 7.5",
        synopsis = "三个男人一路向西，在公路尽头重新认识了自己。",
        episode = 12,
        totalEpisodes = 24,
        positionMs = 8 * 60_000L + 15_000L,
        durationMs = 43 * 60_000L + 37_000L,
        poster = "https://picsum.photos/seed/hero-history-poster-4/400/600",
        backdrop = "https://picsum.photos/seed/hero-history-backdrop-4/1280/720",
    ),
    HeroHistoryItem(
        id = "h5",
        title = "玉簟秋",
        site = "芒果TV",
        remark = "更新至26集",
        meta = "2025·全32集·剧情/古装·豆 7.0",
        synopsis = "深宅秋凉，她把每一步都走成了棋。",
        episode = 26,
        totalEpisodes = 32,
        positionMs = 19 * 60_000L + 48_000L,
        durationMs = 46 * 60_000L + 12_000L,
        poster = "https://picsum.photos/seed/hero-history-poster-5/400/600",
        backdrop = "https://picsum.photos/seed/hero-history-backdrop-5/1280/720",
    ),
)
