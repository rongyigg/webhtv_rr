package com.fongmi.android.tv.ui.activity

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import coil3.compose.AsyncImage
import coil3.compose.SubcomposeAsyncImage
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.fongmi.android.tv.R
import com.fongmi.android.tv.api.SiteApi
import com.fongmi.android.tv.api.config.VodConfig
import com.fongmi.android.tv.bean.Episode
import com.fongmi.android.tv.bean.Flag
import com.fongmi.android.tv.bean.Result
import com.fongmi.android.tv.bean.Site
import com.fongmi.android.tv.bean.TmdbConfig
import com.fongmi.android.tv.bean.TmdbItem
import com.fongmi.android.tv.bean.TmdbPerson
import com.fongmi.android.tv.bean.Vod
import com.fongmi.android.tv.model.SiteViewModel
import com.fongmi.android.tv.service.TmdbService
import com.fongmi.android.tv.setting.Setting
import com.fongmi.android.tv.setting.TmdbSitePolicy
import com.fongmi.android.tv.ui.helper.TmdbMatcher
import com.fongmi.android.tv.ui.helper.TmdbRatingFormatter
import com.fongmi.android.tv.ui.hero.ChangeSourceDialog
import com.fongmi.android.tv.ui.hero.HeroAccentColor
import com.fongmi.android.tv.ui.hero.HeroDialogShell
import com.fongmi.android.tv.ui.hero.HeroFocusBrush
import com.fongmi.android.tv.ui.hero.HeroImageLoader
import com.fongmi.android.tv.ui.hero.HeroSectionTitle
import com.fongmi.android.tv.ui.hero.SourceSelectionStore
import com.fongmi.android.tv.ui.hero.openHeroDetailOrFolder
import com.fongmi.android.tv.utils.Notify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * HeroUI（Compose）详情页。
 *
 * 数据链路：
 * - 进页先看传入 [Vod] 是否自带 `flags`（WebHome 内联源 / 聚合结果）：有就直接渲染，跳过网络请求；
 * - 没有才用 `extra_vod_id` 调 `SiteViewModel.detailContent(siteKey, vodId, refresh = true)`
 *   （首次进页就绕过旧缓存），观察 `SiteViewModel.result` 拿真实 [Vod]：
 *   简介 / 年份 / 地区 / 分类 / 导演 / 主演全部用真实字段，
 *   `Vod.flags[].episodes` 是 [HeroPlayDialog] 与起播的唯一数据源；
 * - 特殊 ID 完全照抄原版 `VideoActivity.checkId()`：`push://` 换成 [SiteApi.PUSH] + 去前缀的 id；
 *   **只有**空 ID 和 `msearch:` 直接显示空态（原版 `setEmpty(false)`）；
 *   其余 id（含 `atvp_detail:` 这类插件内部 id）一律正常发详情请求，和原版一致，绝不自行拦截。
 * - 起播只调用原版公开入口 [VideoActivity.startDirect]（不碰 PlayerManager / PlaybackActivity，
 *   也不自己拼 Intent）；线路 / 集名 / 集地址直接来自真实 [Flag] / [Episode]。
 *
 * 主页面（从下往上）：
 * 1. [HeroDetailBackdrop]：全屏背景大图。
 * 2. [HeroDetailMaskHorizontal]：左侧 0.85 黑 → 35% 处完全透明（`bg_hero_mask_h`，与首页同参数）。
 * 3. [HeroDetailMaskVertical]：上 55% 全透明，底部渐到 0.9 黑（`bg_hero_mask_v`，与首页同参数）。
 * 4. 右上角浮动小海报 150dp × 210dp、marginTop 72dp、marginEnd 80dp、内边距 4dp。
 * 5. 左下内容区（整体贴底、paddingBottom 32dp）：剧名/Logo → 元数据 → 简介 → 按钮组 → **演职员行**。
 *    线路与选集已整体移出主页面，收进 [HeroPlayDialog]。
 *
 * 尺寸/字号/边距逐条对照 `ref_docs/activity_detail.xml`（下文简称 XML，行号即该文件行号），
 * 本次需求（Logo 位、简介 4 行、按钮顺序、演职员可聚焦）与 XML 冲突处以需求为准：
 * - 内容容器：左右 padding 24dp、paddingBottom 32dp、整体贴底。
 * - 剧名：有 Logo → 高 72dp / 最宽 720dp / 等比靠左（XML `heroLogoImg` L82-89）；
 *   无 Logo → 32sp 粗体 / 高 36dp / 贴底 / `basicMarquee()` 跑马灯防溢出 / 阴影 #cc000000 2,2,12（L69-81）。
 * - 元数据：14sp / #ccffffff / 固定高 20dp / marginTop 2dp / 阴影 #aa000000 1.5,1.5,8（L90-102）。
 * - 简介：12sp / #bbffffff / 最宽 440dp / 最多 4 行 / 行距 +3dp（XML 原为半宽 2 行，按需求放宽）。
 * - 按钮组：播放 / 换源 / 收藏，高 36dp、13sp 粗体、左右 padding 16dp、图标文字间距 4dp、按钮间距 12dp（L125-189）。
 * - 演职员：`LazyRow`，60dp 圆形头像 + 12sp 单行名字；焦点态红圈 + 1.08 倍放大。
 *
 * 焦点契约：
 * - 默认落在「播放」；←/→ 在按钮间移动。
 * - 「播放」按 ↓ → 演职员行（落在上次停留的演员）；演员行 ↑ → 回到「播放」；行内 ←/→ 横移，↓ 消费。
 * - 点「播放」→ [HeroPlayDialog]：按 `lastWatchedEpisodeIndex` 强制吸附历史集（-1 落第 1 集），
 *   并把该集所在分组自动切好。
 * - 点某位演员 → [HeroActorDialog]（立即弹出、数据异步加载）；关闭后焦点**回到被点的那位演员**。
 * - 纯展示文本（剧名/元数据/简介）没有任何 `focusable` / `clickable` 修饰符。
 */
class HeroDetailActivity : ComponentActivity() {

    /**
     * 详情页后台预搜索（`SiteViewModel.search` 通道，与详情用的 `result` 通道互不干扰）。
     *
     * 进页就把「换源」要用的全局搜索跑起来：
     * 1. 换源按钮上直接显示第一个搜到的源名（[preSearchSite]）；
     * 2. 点开换源弹窗时把 [preSearchResults] 直接塞进去，跳过弹窗内的首轮检索；
     * 3. 本页详情拿不到剧集时，「播放」可以用 [preSearchVod] 兜底（拉它的详情再弹选集）。
     *
     * 用 Compose `mutableStateOf` 而不是普通字段：按钮文案要跟着结果到达自动重组。
     */
    private val preSearchVod = mutableStateOf<Vod?>(null)
    private val preSearchSite = mutableStateOf<Site?>(null)
    private val preSearchResults = mutableStateOf<List<Vod>>(emptyList())

    /** `search` 通道的观察者：一个源回一条结果，第一条就是按钮上要显示的源。 */
    private val preSearchObserver = Observer<Result?> { result -> onPreSearchResult(result) }

    /** Activity 作用域的检索 ViewModel：预搜索 / 换源弹窗共用，`onDestroy` 里摘观察者并停搜索。 */
    private var siteViewModel: SiteViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(AndroidColor.BLACK))
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        HeroImageLoader.install()
        // 真实详情主键：首页海报卡 / 换源弹窗已经把它塞进 Intent；旧入口（只有剧名）为空。
        val rawVodId = intent.getStringExtra(EXTRA_VOD_ID).orEmpty()
        // 原版 `VideoActivity.checkId()`：
        // `push://xxx` 是嗅探 / 推送专用地址，原版会把它换成 PUSH 站点 + 去掉前缀的 id，
        // 再走普通详情请求；这里照抄，绝不自己另造规则。
        val pushId = rawVodId.takeIf { it.startsWith(PUSH_PREFIX) }?.substring(PUSH_PREFIX.length)
        val vodId = pushId ?: rawVodId
        // 换源弹窗要「排除当前源」：优先用这条数据自己的源，没有（旧入口）才回落到首页源。
        val siteKey = if (pushId != null) {
            SiteApi.PUSH
        } else {
            intent.getStringExtra(EXTRA_SITE_KEY).orEmpty().ifBlank {
                VodConfig.get().getHome().key
            }
        }
        val siteName = intent.getStringExtra(EXTRA_SITE_NAME).orEmpty()
        // 真实数据入口：首页海报卡 / 换源弹窗会把整条 Vod + Site 的关键字段塞进 Intent。
        // 只有剧名的旧入口（adb 直接拉起）继续整页回落 DEMO_DETAIL。
        // `siteKey` / `vodId` 必须先算好：首帧横图要拿它们在本地 TMDB 缓存里做同步查找。
        val detail = readDetail(siteKey, vodId)
        // 传下来的整条 Vod：如果它自己已经带 flags（WebHome 内联源 / 聚合结果），就不用再请求。
        val passedVod = readVod()
        val inlinePlayable = passedVod != null && passedVod.hydrateInlineEpisodes()
        // 【致命日志】传进来的 Vod 形状：搜索 / 首页通常是轻量结果（flagsSize=0），必须走详情请求；
        // 只有 flagsSize>0 才轮到「要不要跳过请求」。siteKey / vodId 也一起打，便于核对站点是否查错。
        Log.e(
            TAG_DETAIL,
            "收到的 Vod: id=${passedVod?.id}, site=$siteKey, flagsSize=${passedVod?.flags?.size ?: 0}, " +
                "playableLines=${passedVod?.flags?.count { !it.episodes.isNullOrEmpty() } ?: 0}, " +
                "playUrlLen=${passedVod?.playUrl?.length ?: 0}, vodId=$vodId, inlinePlayable=$inlinePlayable, " +
                // 卡片自带的 Site 是否比配置里的更完整：若这里 api/jar 也空，
                // 说明这条数据来自「片单/导航」类 spider 临时造的站点，配置里根本没有它。
                "vodSiteKey=${passedVod?.site?.key}, vodSiteApi=${passedVod?.site?.api}, " +
                "vodSiteJar=${passedVod?.site?.jar}, vodSiteExtLen=${passedVod?.site?.ext?.length ?: 0}",
        )
        // 换源弹窗需要 Activity 作用域的 SiteViewModel（构造函数里就建了两个单线程 executor，
        // 绝不能按弹窗 new）。详情页自己持有一份，和首页互不影响。
        val siteViewModel = ViewModelProvider(this)[SiteViewModel::class.java]
        this.siteViewModel = siteViewModel
        // 【真实详情】进页就请求：结果写 `SiteViewModel.result`，由 Compose 侧的观察者接收。
        // 详情接口返回的 `Vod.flags[].episodes` 是选集弹窗与起播的唯一数据源。
        when {
            // 1. 已经自带线路：直接渲染，绝不发请求（内联源本来就没有可查的详情接口）。
            inlinePlayable -> Log.d(
                TAG_DETAIL,
                "传入 Vod 已带线路，跳过详情请求: id=$vodId flags=${passedVod?.flags?.size} " +
                    "集数=${passedVod?.flags?.sumOf { it.episodes.orEmpty().size }}",
            )

            // 2. 没有主键 / 聚合搜索临时 ID：原版 `checkId()` 同样直接进空态，不发请求。
            vodId.isBlank() -> Log.d(TAG_DETAIL, "缺少剧集 ID，跳过详情请求: siteKey=$siteKey")

            needsSpecialParse(vodId) -> Log.d(
                TAG_DETAIL,
                "msearch 临时 ID，跳过详情请求: siteKey=$siteKey id=$vodId",
            )

            // 3. 正常详情：首次进页就带 refresh=true，绕过可能已经坏掉的旧缓存。
            else -> {
                // 排查「详情拿不到线路」的第一手证据：key 能不能真的查到站点配置、ext 有没有丢。
                // `SiteApi.detailContent` 内部会按 key 重新查 Site；查不到时拿到的是空 Site，
                // 表现为 api/ext 全空、请求必然失败（csp_xxx 这类依赖 ext 的源尤其明显）。
                val site = VodConfig.get().getSite(siteKey)
                Log.d(
                    TAG_DETAIL,
                    "请求详情: siteKey=$siteKey homeKey=${VodConfig.get().getHome().key} " +
                        "lookupKey=${site.key} name=${site.displayName} type=${site.type} " +
                        "api=${site.api} extLen=${site.ext.length} jar=${site.jar} id=$vodId",
                )
                // 这条是「spider 退化成 SpiderNull」的最强信号：key 在配置里查不到 → api 为空 →
                // `BaseLoader.getSpider()` 直接返回 SpiderNull → 详情返回空串 → id 空 / 线路 0。
                if (site.isEmpty) {
                    Log.e(
                        TAG_DETAIL,
                        "站点配置里查不到 siteKey=$siteKey：详情请求必然返回空结果（api/ext/jar 全空）",
                    )
                }
                siteViewModel.detailContent(siteKey, vodId, true)
            }
        }
        // 【后台预搜索】换源按钮要显示「第一个搜到的源」，所以进页就并发搜一轮。
        // 走 `search` 通道（详情走 `result`），互不干扰。
        //
        // 1. 源集合 = 换源弹窗的候选源（排除当前源）：按钮上显示的必须是「点开换源真的能换过去的源」；
        // 2. quick=false：`quick=true` 会跳过 `quickSearch=0` 的源（大部分源都是这种），
        //    表现为一个结果都搜不到 → 换源按钮永远显示不出源名。
        val searchKeyword = detail.title
        if (rawVodId.isNotBlank() && searchKeyword.isNotBlank()) {
            val candidates = ChangeSourceDialog.candidates(siteKey)
            val searchable = candidates.ifEmpty {
                VodConfig.get().getSites().filter { it.isSearchable }
            }
            // 侧边栏勾选持久化：没勾的源连请求都不发（与换源弹窗的全局检索保持一致）。
            val checked = SourceSelectionStore.checkedSites(this, searchable)
            Log.e(
                TAG_DETAIL,
                "进页后台预搜索: keyword=[$searchKeyword], 源=${searchable.size}, " +
                    "勾选=${checked.size}, 候选源=${candidates.size}, quick=false",
            )
            if (checked.isEmpty()) {
                Log.e(TAG_DETAIL, "预搜索跳过: 侧边栏没有勾选任何源")
            } else {
                siteViewModel.search.observeForever(preSearchObserver)
                siteViewModel.searchContent(checked, searchKeyword, false)
            }
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                HeroDetailScreen(
                    activity = this@HeroDetailActivity,
                    detail = detail,
                    vodId = vodId,
                    siteKey = siteKey,
                    siteName = siteName,
                    siteViewModel = siteViewModel,
                    inlineVod = if (inlinePlayable) passedVod else null,
                    firstSearchVod = preSearchVod.value,
                    firstSearchSite = preSearchSite.value,
                    preSearchResults = preSearchResults.value,
                )
            }
        }
    }

    /**
     * 预搜索结果：一个源回一条，**第一条结果就决定按钮上显示的源名**。
     *
     * 同一条剧可能在多个源都命中：按「源 key + 剧 id」去重，弹窗里不会出现重复卡。
     * 结果里每条 [Vod] 都已由 `SiteApi.searchContent` 盖过 `setSite(site)`。
     */
    private fun onPreSearchResult(result: Result?) {
        if (result == null) return
        val items = result.list
        // 第一条**有效**结果：有 id 且不是动作伪条目（`vod_tag=action` / 动作 JSON 卡）。
        // 它既决定「换源」按钮上显示的源名，也是点「播放」时要优先拉取的目标。
        val firstValid = items.firstOrNull { it.id.isNotBlank() && !it.isActionItem() }
        val site = firstValid?.site ?: items.firstOrNull()?.site
        if (site == null) {
            // 空结果是「这个源没搜到」，属于正常；有结果却没有 site 才是异常（无法归因到源）。
            if (items.isNotEmpty()) {
                Log.w(TAG_DETAIL, "预搜索结果无法归因到源: listSize=${items.size}, 已丢弃")
            }
            return
        }
        Log.e(
            TAG_DETAIL,
            "预搜索结果: site=${site.key}(${site.displayName}) listSize=${items.size}",
        )
        preSearchResults.value = (preSearchResults.value + items)
            .distinctBy { vod -> "${vod.siteKey}:${vod.id}" }
        if (preSearchVod.value == null && firstValid != null) {
            preSearchVod.value = firstValid
            preSearchSite.value = site
        }
    }

    override fun onDestroy() {
        siteViewModel?.let { viewModel ->
            viewModel.search.removeObserver(preSearchObserver)
            // 离开详情页就停掉在途的预搜索，别让后台线程继续跑。
            viewModel.stopSearch()
        }
        super.onDestroy()
    }

    /**
     * Intent → 首帧页面数据。
     *
     * 有 `EXTRA_PIC` 就说明是从首页 / 换源弹窗进来的真实条目：
     * 剧名、海报、元数据、简介先按 Intent 直出，详情接口回来后再由
     * [mergeDetail] 用真实 [Vod] 整体覆盖（含年份 / 地区 / 分类 / 简介 / 导演 / 主演）。
     *
     * 演职员：真实条目**不再**沿用 [DEMO_DETAIL] 的假演员（TMDB 到达前整行隐藏，
     * 命中后由 TMDB 真演员整体替换）；只有旧 adb 入口（既没有海报、也没有 `extra_vod_id`）
     * 才保留假数据用于离线验证。
     * 线路与选集则完全来自 `SiteViewModel.detailContent` 返回的 `Vod.flags`，不再有假数据。
     */
    private fun readDetail(siteKey: String, vodId: String): HeroDetail {
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { return DEMO_DETAIL }
        val pic = intent.getStringExtra(EXTRA_PIC).orEmpty()
        // 旧 adb 入口 = 「只有剧名」：既没有海报，也没有详情主键（`extra_vod_id`）。
        // 真实历史记录经常没有 `vod_pic`，但有 `vod_id`（详情页靠它拉真实线路 / 选集），
        // 不能因为没海报就整页掉回 [DEMO_DETAIL] 的假数据。
        val demo = pic.isBlank() && intent.getStringExtra(EXTRA_VOD_ID).isNullOrBlank()
        // 首页已经预取到 TMDB w1920 横图时，把它作为**首帧背景**直出：
        // 详情接口只给竖版 `vod.pic`，等它回来再换背景会让首帧从横图硬切成竖图。
        val extraBackdrop = intent.getStringExtra(EXTRA_BACKDROP).orEmpty()
        // 只有「确实和竖图不同」才算横图：同值时仍按源站图处理，行为与改动前完全一致。
        val wideExtra = extraBackdrop.isNotBlank() && extraBackdrop != pic
        // 【首帧防闪·核心】历史页 / 换源弹窗入口**不带** `extra_backdrop`。改动前这里会退回
        // `pic`（源站竖图）铺满全屏，用户看到的就是「先进来一张被拉伸的竖图，再被 TMDB 横图换掉」。
        // 现在同步读一次本地缓存（进程内预取 → 磁盘匹配缓存）拿真横图；拿不到就留空，
        // 由底层纯黑 Box 兜底 —— 竖图只允许待在右上角小海报里，绝不进全屏背景。
        val cachedBackdrop = if (wideExtra) extraBackdrop else syncWideBackdrop(siteKey, vodId, title)
        // 非空且确实不是那张竖图才算数（匹配缓存里偶尔会把竖图写进 backdrop 字段）。
        val firstBackdrop = cachedBackdrop.takeIf { it.isNotBlank() && it != pic }
        val wideBackdrop = firstBackdrop != null
        // 首页信息区此刻显示的 TMDB 艺术字 / 简介：一起直出，避免进来先闪一下文字标题与源站简介。
        val extraLogo = intent.getStringExtra(EXTRA_LOGO).orEmpty()
        val extraOverview = intent.getStringExtra(EXTRA_OVERVIEW).orEmpty()
        // 元数据：年份 · 地区 · 分类 · 备注 · 接口源，空字段自动省略，不留多余的 `·`。
        val meta = listOf(
            intent.getStringExtra(EXTRA_YEAR).orEmpty(),
            intent.getStringExtra(EXTRA_AREA).orEmpty(),
            intent.getStringExtra(EXTRA_TYPE).orEmpty(),
            intent.getStringExtra(EXTRA_REMARKS).orEmpty(),
            intent.getStringExtra(EXTRA_SITE_NAME).orEmpty(),
        ).filter { it.isNotBlank() }.joinToString(" · ")
        return DEMO_DETAIL.copy(
            title = title,
            meta = meta.ifBlank { if (demo) DEMO_DETAIL.meta else "" },
            // 简介优先级：首页预取的 TMDB 译文 → Intent 里的源站简介 → 离线假数据。
            synopsis = extraOverview
                .ifBlank { intent.getStringExtra(EXTRA_CONTENT).orEmpty() }
                .ifBlank { if (demo) DEMO_DETAIL.synopsis else "" },
            posterUrl = pic,
            // 首帧背景：只认真横图。没有就空串（底层黑底），**绝不**再用竖图 `pic` 铺满。
            backdropUrl = firstBackdrop.orEmpty(),
            // 首帧直接出艺术字：有 Logo 时 `HeroDetailTitle` 走 72dp Logo，否则回落 32sp 文字。
            logoUrl = extraLogo.takeIf { it.isNotBlank() },
            // 【关键·不闪小图】首帧就已经是横图时直接判定「有背景大图」：
            // 否则右上角小海报会先渲染出来、等 TMDB Ready 才消失，正好点进详情时闪一下。
            // 注意判 `wideBackdrop` 而不是 `extraBackdrop != null`：`start()` 永远会 put 这个 extra，
            // 空串时它非 null 但不是横图，用 null 判断会把「没有横图」也误判成大图。
            // 现在 `wideBackdrop` 还包含「本地 TMDB 缓存命中」这一路，判它才是真正的「首帧有没有横图」。
            hasTmdbBackdrop = wideBackdrop,
            // 横图标记：`mergeDetail` 不再碰背景，`mergeTmdb` 据此保留更高清的档位、不闪小海报。
            hasWideBackdrop = wideBackdrop,
            // 首帧简介已经是 TMDB 译文时，`mergeDetail` 不要再用源站 `vod.content` 盖掉它。
            hasTmdbOverview = extraOverview.isNotBlank(),
            // 真实条目清空假演员：TMDB 未配置 / 未命中时演员行为空（UI 整行隐藏），
            // 不会出现「真数据 + 10 位假演员」混排；旧 adb 入口（demo）保留假演员。
            cast = if (demo) DEMO_DETAIL.cast else emptyList(),
        )
    }

    /**
     * 首帧横图的**同步**来源（按新鲜度排序，命中即返回）：
     * 1. [HeroTmdbPrefetchCache]：首页刚预取过的条目，横图 / 详情都在内存里，零 IO；
     * 2. `TmdbMatchCache`：历史页 / 换源入口冷启动时唯一的本地来源（`AppCache` + Gson）。
     *
     * **只认 `TmdbItem.backdropUrl`（真横图字段）**，绝不读 `HeroTmdbData.highResBackdrop`：
     * 它在没有横图时会退回竖版 `posterUrl`，拿它当首帧背景正是「竖图被拉伸铺满」的来源。
     */
    private fun syncWideBackdrop(siteKey: String, vodId: String, title: String): String {
        if (vodId.isBlank()) return ""
        val prefetched = runCatching {
            HeroTmdbPrefetchCache.peek(heroTmdbKey(siteKey, vodId))?.item?.backdropUrl.orEmpty()
        }.getOrDefault("")
        val cached = prefetched.ifBlank {
            runCatching {
                val cache = Setting.getTmdbMatchCache()
                // 手动匹配优先：与下面 LaunchedEffect 里的读取顺序完全一致。
                (cache.findManual(siteKey, vodId, title) ?: cache.find(siteKey, vodId, title))?.backdropUrl.orEmpty()
            }.getOrDefault("")
        }
        // 提档到 w1920：与首页 `highResBackdrop` 逐字一致，直接命中首页预热好的磁盘缓存。
        return upgradeTmdbImage(cached, TMDB_BACKDROP_WIDTH)
    }

    /**
     * Intent 里的整条 [Vod]（Parcelable，`flags` / `episodes` 都在里面）。
     *
     * 旧入口（只有剧名）、进程重建后 Extra 丢失、或第三方调用方没带 Vod 时返回 null。
     */
    @Suppress("DEPRECATION")
    private fun readVod(): Vod? = intent.getParcelableExtra(EXTRA_VOD)

    companion object {

        private const val EXTRA_VOD = "extra_vod"
        private const val EXTRA_VOD_ID = "extra_vod_id"
        /** 原版 `checkId()` 识别的嗅探 / 推送地址前缀，命中后换成 [SiteApi.PUSH] 站点。 */
        private const val PUSH_PREFIX = "push://"
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_PIC = "extra_pic"
        private const val EXTRA_YEAR = "extra_year"
        private const val EXTRA_AREA = "extra_area"
        private const val EXTRA_TYPE = "extra_type"
        private const val EXTRA_REMARKS = "extra_remarks"
        private const val EXTRA_CONTENT = "extra_content"
        private const val EXTRA_SITE_KEY = "extra_site_key"
        private const val EXTRA_SITE_NAME = "extra_site_name"
        /**
         * 首页预取的横版背景（TMDB w1920 / 提档后的宽图）。
         *
         * 与 `extra_pic`（源站竖图）分开传：详情页首帧直接用横图，后续 `mergeDetail`
         * 也不会再用竖图覆盖它 —— 否则点进详情会先看到横图、再硬切成竖图。
         */
        private const val EXTRA_BACKDROP = "extra_backdrop"
        /**
         * 首页预取到的 TMDB 艺术字 Logo（透明 PNG）。
         *
         * 首页信息区已经显示它了，点进来时若再等 `readDetail → TMDB 请求 → mergeTmdb`，
         * 会有几百毫秒的「文字标题 → Logo」切换；随 Intent 带过来就能首帧直接出艺术字。
         */
        private const val EXTRA_LOGO = "extra_logo"
        /** 首页预取到的 TMDB 简介：首帧直接显示，不让简介先空着 / 先显示源站短简介。 */
        private const val EXTRA_OVERVIEW = "extra_overview"

        /**
         * 统一入口：首页海报卡确定键、换源弹窗选中后调用。
         *
         * [Vod] 本身是 Parcelable（`flags` / `episodes` 都随 Parcel 传递），所以整条一起带过去：
         * 详情页先看它有没有自带可播线路（WebHome 内联源 / 聚合结果），有就直接渲染、不发详情请求。
         * 标量字段仍然逐个塞进 Intent，供首帧直出和旧入口使用；[Site] 只带 key + 名称
         * （详情页只需要它做「排除当前源」和元数据展示，不需要完整站点配置）。
         *
         * `extra_vod_id` 是详情接口的主键：没有内联线路时用它调 `SiteViewModel.detailContent`，
         * 拿回真实的 `flags / episodes`；线路与选集不再有假数据。
         *
         * [backdropUrl]：首页卡片当前显示的横版背景（TMDB 命中时是 w1920）。
         * 空串 = 没有横图，详情页照旧用 `vod.pic` 裁 16:9。
         * [logoUrl] / [overview]：首页已经预取到的 TMDB 艺术字与简介，空串 = 没有，仍走原来的
         * 「先显示源站内容、TMDB 到达后再替换」链路。
         */
        fun start(
            context: Context,
            vod: Vod,
            site: Site,
            backdropUrl: String = "",
            logoUrl: String = "",
            overview: String = "",
            noAnimation: Boolean = false,
        ) {
            val intent = Intent(context, HeroDetailActivity::class.java)
                .putExtra(EXTRA_VOD, vod)
                .putExtra(EXTRA_VOD_ID, vod.id)
                .putExtra(EXTRA_TITLE, vod.name)
                .putExtra(EXTRA_PIC, vod.pic)
                .putExtra(EXTRA_BACKDROP, backdropUrl)
                .putExtra(EXTRA_LOGO, logoUrl)
                .putExtra(EXTRA_OVERVIEW, overview)
                .putExtra(EXTRA_YEAR, vod.year)
                .putExtra(EXTRA_AREA, vod.area)
                .putExtra(EXTRA_TYPE, vod.typeName)
                .putExtra(EXTRA_REMARKS, vod.remarks)
                .putExtra(EXTRA_CONTENT, vod.content)
                .putExtra(EXTRA_SITE_KEY, site.key)
                .putExtra(EXTRA_SITE_NAME, site.displayName)
            // 历史页要求「查看详情」零转场：只给显式开启的调用方加标记，首页原有转场不变。
            if (noAnimation) intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            context.startActivity(intent)
        }

        /**
         * 历史记录入口：`ref_docs/HistoryFragment.java` L338-344 `h0()` 的等价实现。
         *
         * ```java
         * DetailActivity.q2(L(), history.getSiteKey(), history.getVodId(), history.getVodName(),
         *                   history.getVodPic(), history.getVodRemarks(), "");
         * ```
         *
         * 只接收原版 `q2` 的**七个标量**。本工程没有 `DetailActivity`，所以在这里把标量还原成
         * 详情页真正需要的 [Vod] + [Site]，再交给上面的统一入口：
         * - [Vod] 只填真实字段（id / 名称 / 海报 / 备注 / 简介），**不注入任何假数据**；
         * - [Site] 从当前配置里按 `siteKey` 查（`VodConfig.get().getSite`），配置还没加载时用
         *   key 兜底，保证详情页「排除当前源」用的仍是真实 key。
         */
        fun start(
            context: Context,
            siteKey: String,
            vodId: String,
            vodName: String,
            vodPic: String,
            vodRemarks: String,
            vodContent: String,
            noAnimation: Boolean = false,
        ) {
            val vod = Vod().apply {
                id = vodId
                name = vodName
                pic = vodPic
                remarks = vodRemarks
                content = vodContent
            }
            val site = VodConfig.get().getSite(siteKey).takeIf { !it.isEmpty } ?: Site().apply { key = siteKey }
            start(context, vod, site, noAnimation = noAnimation)
        }

        /**
         * 兼容入口：只有剧名时整页回落 [DEMO_DETAIL]，用于 adb 直接拉起验证：
         * `adb shell am start -n com.silent.android.webhtv/com.fongmi.android.tv.ui.activity.HeroDetailActivity`
         */
        fun start(context: Context, title: String) {
            context.startActivity(
                Intent(context, HeroDetailActivity::class.java).putExtra(EXTRA_TITLE, title),
            )
        }
    }
}

/**
 * 需要「特殊解析」的临时 ID：原版 `VideoActivity.checkId()` 只拦空 ID 和 `msearch:`。
 *
 * `msearch:` 是聚合搜索把多个源的结果拼成的一条临时条目，普通详情接口必然查不到。
 * **不要**在这里追加 `atvp_detail:` 之类的插件内部 id：原版对它们照常调用 `getDetail()`，
 * 由生成该 id 的 spider 自己解析；提前拦截只会把「spider 解析失败」伪装成「不支持」。
 */
private fun needsSpecialParse(vodId: String): Boolean =
    vodId.startsWith("msearch:", ignoreCase = true)

/**
 * 猫源 / 网盘聚合源在 `vod_play_url` 里塞的**动作指令**集数。
 *
 * 这类源把「📄 媒体信息 / 🔍 全局搜索 / ➕ 稍后再看 / ➖ 取消追剧 / ➕ 加入追剧」伪装成集数：
 * ```
 * 📄 媒体信息$msubinfo-db%3A36675942%7C小城良方#🔍 全局搜索$msubsearch-小城良方#...
 * ```
 * `Flag.setEpisodes()` 按第一个 `$` 拆成 `name=📄 媒体信息`、`url=msubinfo-db%3A...`，
 * 于是 HeroUI 会把它当成普通集数渲染 —— 点下去必然起播失败。
 *
 * 本项目**没有**这套动作的分发器（`SiteApi.action` 只处理 `vod_tag=action` 的整条记录，
 * 且 type=4 时把 action 当 URL 直接 GET），所以这里做**识别 + 剔除**：
 * 选集弹窗在渲染前就把它们过滤掉，绝不把动作串送进起播；
 * 若某条线路过滤后一集不剩，弹窗显示「该源无可用剧集」。
 *
 * 名单只用于「灰显 / 打日志」；判断当前源能不能直接播必须看 [Episode.hasRealVideoUrl]：
 * 动作名可能继续增加，但「不是 http 链接」这一条永远不会变。
 */
private val ACTION_EPISODE_NAMES = listOf("msubinfo", "msubsearch", "msubdel", "msubadd", "watchadd")

/** 播放目标为 folder 时的最大下探层数（detail → category → detail …），防止目录套目录死循环。 */
private const val MAX_PLAY_DRILL = 3

/** 当前源不可播、预搜索结果还没到时最多等这么久，避免「正在寻找可用源」无限转圈。 */
private const val PLAY_FALLBACK_WAIT_MS = 10_000L

/** 网盘聚合插件的 folder 壳前缀（与 `ChangeSourceDialog` / `HeroEntryRoute` 的判定一致）。 */
private const val ATVP_FOLDER_PREFIX = "atvp_detail:"

/** 这条集数的地址是不是动作指令（`msubinfo-...`，兼容整串带前导 `$` 的写法）。 */
private fun Episode.isActionCommand(): Boolean {
    val raw = getUrl().trim()
    if (raw.isEmpty()) return false
    // 以 `$` 开头的整串本身就是动作指令（`$msubinfo-...` / `$msubsearch-...`），
    // 不管后面跟的是什么名字，都不是可播放地址。
    if (raw.startsWith("$")) return true
    return ACTION_EPISODE_NAMES.any { name -> raw == name || raw.startsWith("${name}-") }
}

/**
 * 这条线路过滤掉动作指令后是否还有**真实集数**（宽松判定）。
 *
 * 只用于「换源目标 / folder 子条目」：网盘插件解析出的集数可能是插件内部地址，
 * 不能一律要求 http，否则快映这类源拿到的 29 集会全部被丢掉。
 * 判断「当前源能不能直接播」必须用更严格的 [Flag.hasRealVideoUrl]。
 */
private fun Flag.hasPlayableEpisode(): Boolean =
    episodes.orEmpty().any { !it.isActionCommand() }

/**
 * 这条集数是不是**真正能起播的视频地址**。
 *
 * 片单 / 猫源会把「➕ 加入追剧$msubadd-...」这类动作项伪装成普通集数，
 * 旧规则只按动作名过滤，名单缺一个就会弹出一个「只有动作项」的空选集。
 * 这里改成看地址本身：必须是 http(s) 或 m3u8，其余（空地址 / 动作指令 / 插件伪协议）都不算。
 */
private fun Episode.hasRealVideoUrl(): Boolean {
    val url = getUrl().trim()
    if (url.isEmpty() || isActionCommand()) return false
    return url.startsWith("http://", ignoreCase = true) ||
        url.startsWith("https://", ignoreCase = true) ||
        url.contains(".m3u8", ignoreCase = true)
}

/** 这条线路是否至少有一条真实 http/m3u8 播放地址（当前源可播的唯一标准）。 */
private fun Flag.hasRealVideoUrl(): Boolean =
    episodes.orEmpty().any { it.hasRealVideoUrl() }

/**
 * 目录里的「动作伪条目」（`vod_tag=action` / 带动作 JSON 的卡片）。
 *
 * 网盘源的二级目录常混入「📄 媒体信息 / 🔍 全局搜索」这类条目，它们不是可播放文件；
 * 播放兜底下探时直接跳过，避免把动作卡当剧集继续请求。
 */
private fun Vod.isActionItem(): Boolean =
    "action".equals(getTag(), ignoreCase = true) || getAction().isNotBlank()

/**
 * 这个目标是不是「目录」而不是剧集详情。
 *
 * 网盘聚合源（🍍快映等）在搜索结果里给的是 `atvp_detail:507769` 这种 folder 壳 id，
 * 它**只能**走 `categoryContent`；一旦拿去调 `detailContent`，插件会把整串壳 id
 * 当成上游详情主键转发，必然 500（日志里就是 `Connection refused`）。
 */
private fun Vod.isFolderTarget(): Boolean =
    isFolder || id.startsWith(ATVP_FOLDER_PREFIX, ignoreCase = true)

/**
 * 传入的 [Vod] 是否已经自带可播放线路；顺手把「只有 urls、还没拆成 episodes」的线路补好。
 *
 * `Flag.setEpisodes("")` 会造出一条空地址剧集，所以只在 `urls` 非空时才补，
 * 绝不能直接对整个 Vod 调 `setFlags()`。
 */
private fun Vod.hydrateInlineEpisodes(): Boolean {
    val flagCount = flags.size
    flags.forEach { flag ->
        if (flag.episodes.isNullOrEmpty() && flag.urls.isNotBlank()) {
            // `Flag.setEpisodes()` 内部直接操作 `getEpisodes()`，若该字段为 null 会 NPE；
            // 这里兜住异常并打日志，绝不让详情页因为一条坏线路崩溃。
            runCatching { flag.setEpisodes(flag.urls) }
                .onSuccess {
                    Log.e(
                        TAG_DETAIL,
                        "hydrate: flag=${flag.flag} urlsLen=${flag.urls.length} -> " +
                            "episodes=${flag.episodes.orEmpty().size}",
                    )
                }
                .onFailure {
                    Log.e(TAG_DETAIL, "hydrate 失败: flag=${flag.flag} urlsLen=${flag.urls.length}", it)
                }
        }
    }
    val playable = flags.count { !it.episodes.isNullOrEmpty() }
    Log.e(
        TAG_DETAIL,
        "hydrateInlineEpisodes: flags=$flagCount playableLines=$playable, " +
            "playUrlLen=${playUrl.length}（playableLines=0 时详情页会走网络请求）",
    )
    return playable > 0
}

@Composable
private fun HeroDetailScreen(
    activity: Activity,
    detail: HeroDetail,
    vodId: String,
    siteKey: String,
    siteName: String,
    siteViewModel: SiteViewModel,
    /** 传入 Vod 自带的线路（非空表示已跳过网络请求，直接渲染）。 */
    inlineVod: Vod?,
    /** 后台预搜索的第一条结果：换源按钮上显示它的源名，「播放」兜底时用它拉详情。 */
    firstSearchVod: Vod?,
    /** 后台预搜索第一条结果所属的源。 */
    firstSearchSite: Site?,
    /** 后台预搜索的全部结果：点「换源」直接塞进弹窗，跳过弹窗首轮检索。 */
    preSearchResults: List<Vod>,
) {
    val context = LocalContext.current
    // ------------------------------------------------------------------
    // 真实详情（`SiteViewModel.result`）
    // ------------------------------------------------------------------
    // 本项目没有 `runtime-livedata`（没有 observeAsState），沿用首页 / 搜索页的
    // observeForever + onDispose 写法：销毁时一定摘除观察者，同时保留 LiveData
    // 「立刻重放上一次值」的特性 —— Activity 重建时详情不会闪回骨架。
    var detailResult by remember {
        // 传入 Vod 已经带线路：直接当成详情结果，首帧就是 Ready，不等任何网络。
        mutableStateOf(inlineVod?.let { Result.vod(it) })
    }
    // TMDB 元数据状态：与详情接口并行，到达后由 `page` 重新合并（不阻塞首帧，失败也不影响起播）。
    var tmdbState by remember { mutableStateOf<TmdbDetailState>(TmdbDetailState.Idle) }
    // ---- 弹窗 / 跨源播放状态：**必须声明在 result 观察者之前**（观察者要用它们分流）----
    var showSourceDialog by remember { mutableStateOf(false) }
    var showPlayDialog by remember { mutableStateOf(false) }
    /** 选集弹窗实际渲染的线路：默认取本页详情；跨源兜底时换成那个源的线路。 */
    var playFlags by remember { mutableStateOf<List<Flag>>(emptyList()) }
    var playVod by remember { mutableStateOf<Vod?>(null) }
    /** 选集弹窗起播用的源 key：默认本页源，跨源兜底时是预搜索命中的那个源。 */
    var playSiteKey by remember { mutableStateOf(siteKey) }
    /** 非 null = 正在等「播放兜底」那个源的详情回包；回包一到直接弹选集。 */
    var pendingPlayId by remember { mutableStateOf<String?>(null) }
    var pendingPlaySite by remember { mutableStateOf<Site?>(null) }
    var pendingPlayVod by remember { mutableStateOf<Vod?>(null) }
    /** true = 用户点了「播放」但当前没有线路，正在等**本页**详情回包后自动弹选集。 */
    var pendingPlayOpen by remember { mutableStateOf(false) }
    /** 选集数据加载中：点过「播放」到回包之间显示加载圈，并挡掉重复请求。 */
    var playLoading by remember { mutableStateOf(false) }
    /** 播放目标无真实集数时的 folder 下探层数（detail → category → detail …），到上限就停。 */
    var pendingPlayDrill by remember { mutableIntStateOf(0) }
    /** 非 null = 正在等 folder 的二级目录回包（`categoryContent`），回包后继续拉子条目详情。 */
    var pendingPlayCategory by remember { mutableStateOf<String?>(null) }
    /** true = 当前源不可播、预搜索结果还没到：显示 Loading，结果一到自动继续（见 LaunchedEffect）。 */
    var pendingPlayFallback by remember { mutableStateOf(false) }
    /** true = 这次播放来自「跨源兜底」：选集弹窗真正拿到真实剧集后提示用户已自动换源。 */
    var autoSwitchNotice by remember { mutableStateOf(false) }
    // 本页是否真的拥有 `result` 通道：只有「自己发过详情请求」的页面才有资格消费它。
    // 内联源 / 没有主键 / 需要特殊解析的页面从不发详情请求，任何 result 都不是给本页的。
    val ownsResultChannel = vodId.isNotBlank() && !needsSpecialParse(vodId) && inlineVod == null
    // 后台预搜索是**异步**到达的：下面的 `DisposableEffect` 只创建一次观察者，
    // 直接捕获函数参数会永远拿到首帧的 null（换源按钮有名字了、播放兜底却拿不到结果）。
    // `rememberUpdatedState` 让观察者每次回调读到的都是最新一次组合的值。
    val latestSearchVod by rememberUpdatedState(firstSearchVod)
    val latestSearchSite by rememberUpdatedState(firstSearchSite)
    // 空态文案：result 观察者（播放兜底也失败）和页面正文都要用。
    // Kotlin 局部变量的作用域从声明处开始，所以必须先声明再进 `DisposableEffect`（不能前向引用）。
    val special = needsSpecialParse(vodId)
    val emptyHint = when {
        vodId.isBlank() -> "缺少剧集 ID，无法加载剧集"
        special -> "该源需要特殊解析，暂不支持直接播放"
        else -> "该资源暂无可用剧集"
    }
    /**
     * 「播放目标」统一入口：当前源没有真实集数时，改拉预搜索第一个结果（按钮上显示的那个源）。
     *
     * **folder 必须先分流**：`atvp_detail:` / `vod_tag=folder` 的 id 是目录壳，
     * 拿去调 `detailContent` 会被插件当成上游详情主键 → 500。
     * 所以这类目标直接走 `categoryContent`，由观察者的 `pendingPlayCategory` 分支接管，
     * 逐级下探到真正的剧集再弹选集。
     */
    fun requestPlayTarget(vod: Vod, site: Site) {
        playLoading = true
        pendingPlayOpen = false
        pendingPlayId = null
        pendingPlaySite = site
        pendingPlayVod = vod
        pendingPlayDrill = 0
        if (vod.isFolderTarget()) {
            // 目录壳：绝不调 detailContent，直接拉二级目录。
            pendingPlayCategory = vod.id
            Log.e("HeroDetail", "准备下探目录: site=${site.key}, id=${vod.id}")
            Log.e(
                TAG_DETAIL,
                "播放目标是 folder，直接拉目录: site=${site.key} folder=${vod.id}",
            )
            siteViewModel.categoryContent(site.key, vod.id, "1", true, hashMapOf())
        } else {
            pendingPlayCategory = null
            siteViewModel.detailContent(site.key, vod.id)
        }
    }
    // 预搜索是异步的：当前源不可播、结果还没到时先登记等待，结果一到自动继续下探 / 拉详情。
    LaunchedEffect(firstSearchVod, firstSearchSite, pendingPlayFallback) {
        if (!pendingPlayFallback) return@LaunchedEffect
        val vod = firstSearchVod
        val site = firstSearchSite
        if (vod == null || site == null || vod.id.isBlank()) return@LaunchedEffect
        pendingPlayFallback = false
        autoSwitchNotice = true
        Log.e(TAG_DETAIL, "预搜索结果到达，自动切换: site=${site.key} id=${vod.id}")
        requestPlayTarget(vod, site)
    }
    // 兜底等待不能无限转圈：预搜索完全没有结果时给一次明确反馈。
    LaunchedEffect(pendingPlayFallback) {
        if (!pendingPlayFallback) return@LaunchedEffect
        delay(PLAY_FALLBACK_WAIT_MS)
        if (!pendingPlayFallback) return@LaunchedEffect
        pendingPlayFallback = false
        playLoading = false
        Log.e(TAG_DETAIL, "等待预搜索超时: 仍没有可用源")
        Notify.show(emptyHint)
    }
    // 跨源兜底成功：选集弹窗真正拿到真实剧集后再提示，避免网络失败时误报。
    LaunchedEffect(showPlayDialog, autoSwitchNotice) {
        if (!showPlayDialog || !autoSwitchNotice) return@LaunchedEffect
        autoSwitchNotice = false
        Notify.show("已为您自动切换至可用源")
    }
    DisposableEffect(siteViewModel, vodId) {
        val observer = Observer<Result?> { result ->
            if (result == null) return@Observer
            // 0) 「播放」正在等**本页**详情：拿到线路直接弹选集；仍没有线路就退到预搜索的源。
            if (pendingPlayOpen) {
                pendingPlayOpen = false
                playLoading = false
                val incoming = result.vod
                val incomingFlags = incoming.flags
                Log.e(
                    TAG_DETAIL,
                    "播放等待详情返回: id=${incoming.id} flags=${incomingFlags.size} " +
                        "集数=${incomingFlags.sumOf { it.episodes.orEmpty().size }} code=${result.code}",
                )
                val previousVod = detailResult?.vod
                detailResult = result
                // 只有「存在真实 http/m3u8 地址」才算当前源能播：动作项 / 伪集数 / 空地址都不算。
                if (incomingFlags.any { it.hasRealVideoUrl() }) {
                    playFlags = incomingFlags
                    // 详情回包没带剧名时退回当前页已有的 Vod，绝不让弹窗丢标题。
                    playVod = incoming.takeIf { it.name.isNotBlank() } ?: previousVod
                    playSiteKey = siteKey
                    // 本页自己就能播：清掉可能残留的「已自动换源」提示标记。
                    autoSwitchNotice = false
                    showPlayDialog = true
                    return@Observer
                }
                // 本页只有动作指令 / 伪集数 / 没有集数：改拉预搜索第一个结果（按钮上显示的那个源）。
                val fallbackVod = latestSearchVod
                val fallbackSite = latestSearchSite
                if (fallbackVod != null && fallbackSite != null && fallbackVod.id.isNotBlank()) {
                    Log.e(
                        TAG_DETAIL,
                        "准备拉取换源目标详情: site=${latestSearchVod?.site?.key}, id=${latestSearchVod?.id}",
                    )
                    autoSwitchNotice = true
                    Notify.show("当前源不可播，正在切换可用源…")
                    requestPlayTarget(fallbackVod, fallbackSite)
                } else {
                    // 预搜索还没回来：登记等待，结果一到由 LaunchedEffect 自动继续。
                    pendingPlayFallback = true
                    playLoading = true
                    Log.e(TAG_DETAIL, "播放等待详情无真实视频链接，等待预搜索结果…")
                }
                return@Observer
            }
            // 1) folder 下探：`categoryContent` 的二级目录回来了 → 选第一个条目继续拉详情。
            //    分类接口和详情共用 `result` 通道，所以必须先于普通详情分支判断。
            val pendingFolder = pendingPlayCategory
            if (pendingFolder != null) {
                pendingPlayCategory = null
                val targetSite = pendingPlaySite
                // 分类接口不会给每条结果盖 site（原版整页共用一个 key），这里补上。
                val items = result.list.map { child ->
                    if (child.site == null && targetSite != null) child.apply { site = targetSite } else child
                }
                Log.e("HeroDetail", "下探结果: listSize=${result.list.size}")
                Log.e(
                    TAG_DETAIL,
                    "预搜索源目录返回: folder=$pendingFolder listSize=${items.size} " +
                        "code=${result.code} msg=${result.msg}",
                )
                // 跳过动作伪条目，并排除「刚展开的这一个 folder」自己（防自环，层数上限是最后一道闸）。
                // 按需求取**第一个**有效条目：是剧集就补详情弹选集，还是目录就继续下探。
                val child = items.firstOrNull {
                    it.id.isNotBlank() && it.id != pendingFolder && !it.isActionItem()
                }
                if (child == null || targetSite == null) {
                    playLoading = false
                    Notify.show("该源暂无可用剧集")
                    pendingPlaySite = null
                    pendingPlayVod = null
                    pendingPlayDrill = 0
                    return@Observer
                }
                pendingPlayVod = child
                pendingPlayDrill++
                // 目录条目自带真实线路（部分聚合源）→ 直接弹选集，不再多发一轮详情。
                if (child.flags.any { it.hasPlayableEpisode() }) {
                    playLoading = false
                    playFlags = child.flags
                    playVod = child
                    playSiteKey = targetSite.key
                    showPlayDialog = true
                    pendingPlaySite = null
                    pendingPlayVod = null
                    pendingPlayDrill = 0
                    return@Observer
                }
                if (child.isFolderTarget()) {
                    // folder 套 folder：层数用完就停，绝不死循环。
                    if (pendingPlayDrill >= MAX_PLAY_DRILL) {
                        playLoading = false
                        Notify.show("目录层级过深，暂不支持播放")
                        pendingPlaySite = null
                        pendingPlayVod = null
                        pendingPlayDrill = 0
                        return@Observer
                    }
                    pendingPlayCategory = child.id
                    Log.e(
                        TAG_DETAIL,
                        "目录条目仍是 folder，继续下探: id=${child.id} depth=$pendingPlayDrill",
                    )
                    siteViewModel.categoryContent(targetSite.key, child.id, "1", true, hashMapOf())
                } else {
                    pendingPlayId = child.id
                    Log.e(
                        TAG_DETAIL,
                        "目录条目补详情: site=${targetSite.key} id=${child.id} depth=$pendingPlayDrill",
                    )
                    siteViewModel.detailContent(targetSite.key, child.id)
                }
                return@Observer
            }
            // 2) 播放目标（预搜索第一个源 / 其 folder 子条目）的详情回来了：
            //    有真实集数 → 直接弹选集；没有 → 用 `categoryContent` 下探二级目录。
            val pendingId = pendingPlayId
            if (pendingId != null) {
                pendingPlayId = null
                val incoming = result.vod
                val incomingFlags = incoming.flags
                // 需求指定的排查日志：先看这条详情到底有没有 flags / episodes。
                Log.e(
                    "HeroDetail",
                    "预搜索源详情返回: flagsSize=${result?.vod?.flags?.size}, " +
                        "episodesSize=${result?.vod?.flags?.firstOrNull()?.episodes?.size}",
                )
                Log.e(
                    TAG_DETAIL,
                    "播放目标详情: id=${incoming.id} flags=${incomingFlags.size} " +
                        "真实集数=${incomingFlags.sumOf { flag -> flag.episodes.orEmpty().count { !it.isActionCommand() } }} " +
                        "code=${result.code} depth=$pendingPlayDrill",
                )
                val targetSite = pendingPlaySite
                if (incomingFlags.any { it.hasPlayableEpisode() }) {
                    playLoading = false
                    playFlags = incomingFlags
                    // 详情回包没带剧名 / 主键时退回发起请求的那条 Vod：跨源起播只认请求时的 id。
                    playVod = incoming.takeIf { it.name.isNotBlank() && it.id.isNotBlank() }
                        ?: pendingPlayVod
                        ?: incoming
                    playSiteKey = targetSite?.key ?: siteKey
                    showPlayDialog = true
                    pendingPlaySite = null
                    pendingPlayVod = null
                    pendingPlayDrill = 0
                    return@Observer
                }
                // 0 集数：只有「看起来是 folder」或「详情带动作指令（网盘聚合源）」才下探；
                // 普通源真的没有剧集时绝不去拉分类页，否则可能把别的剧当成选集播出去。
                // 回包可能丢掉 folder 壳 id（日志里 id 直接为空），所以把「发起请求的那条」也算上。
                val looksLikeFolder = incoming.isFolderTarget() ||
                    pendingPlayVod?.isFolderTarget() == true
                val hasActionEpisodes = incomingFlags.any { flag ->
                    flag.episodes.orEmpty().any { it.isActionCommand() }
                }
                if (targetSite != null && pendingPlayDrill < MAX_PLAY_DRILL &&
                    (looksLikeFolder || hasActionEpisodes)
                ) {
                    pendingPlayDrill++
                    pendingPlayCategory = pendingId
                    playLoading = true
                    Log.e(
                        TAG_DETAIL,
                        "预搜索源无集数，下探目录: site=${targetSite.key} folder=$pendingId " +
                            "depth=$pendingPlayDrill",
                    )
                    siteViewModel.categoryContent(targetSite.key, pendingId, "1", true, hashMapOf())
                } else {
                    playLoading = false
                    Notify.show("该源暂无可用剧集")
                    pendingPlaySite = null
                    pendingPlayVod = null
                    pendingPlayDrill = 0
                }
                return@Observer
            }
            // 3) 换源弹窗打开期间，`result` 通道归弹窗（folder 下探 / 卡片补详情）。
            //    本页必须让路：目录结果没有 flags，会把详情页误判成「该资源暂无可用剧集」。
            if (showSourceDialog) {
                Log.d(TAG_DETAIL, "换源弹窗打开中，详情页忽略 result 通道")
                return@Observer
            }
            // 4) 本页不发详情请求的页面：任何 result 都是别人的（弹窗 / 其它页面），一律不接。
            if (!ownsResultChannel) {
                Log.d(TAG_DETAIL, "本页不拥有 result 通道，忽略: site=$siteKey id=$vodId")
                return@Observer
            }
            // 【排查日志 1】Result 形状：listSize=0 说明请求失败 / 接口没返回数据，
            // listSize>0 才轮到「flags 为什么是空」的问题。
            Log.d(
                TAG_DETAIL,
                "获取到详情: listSize=${result.list.size}, code=${result.code}, msg=${result.msg}",
            )
            val incoming = result.vod
            // 【致命日志】详情请求回来的线路 / 集数：
            // flagsSize=0 → 接口没给播放字段；flagsSize>0 但 episodesSize=0 → 有线路没拆出集。
            Log.e(
                TAG_DETAIL,
                "请求回来的 flagsSize=${incoming.flags.size}, " +
                    "episodesSize=${incoming.flags.firstOrNull()?.episodes?.size ?: 0}",
            )
            Log.d(
                TAG_DETAIL,
                "Vod详情: name=${incoming.name}, id=${incoming.id}, flagsSize=${incoming.flags.size}",
            )
            // 【排查日志 2】播放字段：`Vod.setFlags()` 就是靠这两个字段生成线路的；
            // 这里为空说明接口压根没给播放数据（或字段名不对），与选集弹窗无关。
            Log.d(
                TAG_DETAIL,
                "Vod播放字段: playFrom=[${incoming.playFrom}] playUrlLen=${incoming.playUrl.length}",
            )
            if (incoming.playUrl.isNotBlank()) {
                Log.d(TAG_DETAIL, "Vod播放字段(前 800 字): ${incoming.playUrl.take(800)}")
            }
            if (incoming.flags.isNotEmpty()) {
                val first = incoming.flags[0]
                val playable = incoming.flags.count { !it.episodes.isNullOrEmpty() }
                Log.d(
                    TAG_DETAIL,
                    "第一条线路: flag=${first.flag}, show=${first.show}, " +
                        "episodesSize=${first.episodes.orEmpty().size}, " +
                        "可播放线路=$playable/${incoming.flags.size}",
                )
            } else {
                Log.e(TAG_DETAIL, "【严重】未解析出任何线路，请检查请求 key 和 id 是否正确！")
            }
            // 【排查日志 3】动作指令集数：网盘聚合 / 猫源会把「媒体信息 / 全局搜索 / 稍后再看」
            // 伪装成集数（`📄 媒体信息$msubinfo-...`）。本站没有这套动作的分发器，
            // 它们会被灰显、点了只提示不起播 —— 这里先把「到底有几条」落成日志，
            // 否则用户只会看到「点集数没反应」，分不清是解析失败还是本来就不可播。
            val actionEpisodes = incoming.flags.flatMap { flag ->
                flag.episodes.orEmpty().filter { it.isActionCommand() }
            }
            Log.e(
                TAG_DETAIL,
                "动作指令集数: count=${actionEpisodes.size}, " +
                    "totalEpisodes=${incoming.flags.sumOf { it.episodes.orEmpty().size }}",
            )
            actionEpisodes.forEach { episode ->
                Log.e(
                    TAG_DETAIL,
                    "  动作项: name=${episode.getName()} url=${episode.getUrl()}",
                )
            }
            // 详情结果只来自本页的 `detailContent`（换源弹窗写的是 `search` 通道）。
            // 即使源站返回的 id 与请求 id 不完全一致，只要结果到了就必须接收：
            // 这里再按 id 丢弃，会把一次成功的详情请求误判成「无可用剧集」。
            if (incoming.id.isNotBlank() && incoming.id != vodId) {
                Log.w(TAG_DETAIL, "详情 id 与请求不一致，仍然接收: resultId=${incoming.id} 期望=$vodId")
            }
            detailResult = result
        }
        siteViewModel.result.observeForever(observer)
        onDispose { siteViewModel.result.removeObserver(observer) }
    }
    val vod = detailResult?.vod
    // 【严格判定】只要 flags 非空就算有线路，**不再按 episodes 过滤**：
    // 某条线路确实没有集数时，选集弹窗显示「该线路暂无集数」，
    // 绝不能因为第一条线路是空占位就把整部剧判成「无可用剧集」。
    val flags = remember(detailResult) { vod?.flags.orEmpty() }
    // 先落到局部 val：`detailResult` 是委托属性，Kotlin 不对它做智能转换。
    val result = detailResult
    // 【关键】「加载中」「请求失败」「确实没有剧集」「需要特殊解析」是四种完全不同的状态：
    // 请求失败时 `Result` 的 list 为空（`SiteViewModel` 失败时会 post `Result.empty()`），
    // 以前这种情况会被误判成「该资源暂无可用剧集」，把失败说成没有资源。
    // `flags` 优先判定：只要接口返回了线路（哪怕某条线路 episodes 为空），就算 Ready；
    // 选集弹窗负责把空线路显示成「该线路暂无集数」，绝不把整部剧误判成「无可用剧集」。
    val detailState = when {
        flags.isNotEmpty() -> DetailState.Ready
        vodId.isBlank() -> DetailState.Empty
        special -> DetailState.Empty
        result == null -> DetailState.Loading
        result.list.isEmpty() -> DetailState.Error
        else -> DetailState.Empty
    }
    val detailError = result?.msg.orEmpty()
    // Intent 字段用于首帧直出；详情到达后整体换成真实 Vod（年份 / 地区 / 分类 / 简介 / 导演 / 主演）。
    // `tmdbState` 是 key：TMDB 到达后在这层**基于新 base 重新合并**，绝不叠加在上一次 page 上
    //（否则评分会被追加两次）。key 变化也会让 `cast` / `meta` 与演员下标同步刷新。
    val page = remember(detail, detailResult, siteName, tmdbState) {
        val base = detail.mergeDetail(vod, siteName)
        val ready = tmdbState as? TmdbDetailState.Ready ?: return@remember base
        base.mergeTmdb(
            item = ready.item,
            detail = ready.detail,
            persons = ready.persons,
            config = TmdbConfig.effectiveCurrent(),
            manualHit = ready.manual,
        )
    }
    // ------------------------------------------------------------------
    // TMDB 元数据（阶段 1：展示 + 读 TmdbMatchCache）
    // ------------------------------------------------------------------
    // 为什么独立成 LaunchedEffect，而不是塞进 `detailResult` 的观察者：
    // 1. 观察者在主线程回调，且播放兜底 / folder 下探都会让它重入，TMDB 的同步 OkHttp 请求会被触发多次；
    // 2. LaunchedEffect 随 key 变化自动取消、离开页面随组合销毁自动停止，不必手写摘除。
    // 闸门：没有 Key / 本页源被排除 / 没有真实 vodId（旧 adb 入口）时**一次网络都不发**，页面与改动前一致。
    LaunchedEffect(vodId, siteKey, vod?.name, vod?.year) {
        // 已经拿到（或正在拉）TMDB 数据时不再重发：folder 下探 / 预搜索回包都会让 `detailResult` 变化，
        // 但剧集身份没变；不守卫会把已 Ready 的元数据覆盖成 Error。
        if (tmdbState is TmdbDetailState.Ready || tmdbState is TmdbDetailState.Loading) return@LaunchedEffect
        val current = vod ?: return@LaunchedEffect
        val config = TmdbConfig.effectiveCurrent()
        if (!config.isReady() || vodId.isBlank() || !TmdbSitePolicy.isEnabled(config, siteKey, vodId)) {
            tmdbState = TmdbDetailState.Idle
            return@LaunchedEffect
        }
        if (activity.isFinishing) return@LaunchedEffect

        // 首页预拉取命中：详情 / 演员已经在内存里，直接出结果，一条网络请求都不发。
        // 这是消除「点进详情页先白等一轮搜索 + 详情」的关键；未命中时下面照旧走「缓存 → 搜索 → 详情」。
        // 站点开关与 Key 的闸门在上面已经过完，所以这里不会让被排除的源蹭到 TMDB 数据。
        val prefetched = HeroTmdbPrefetchCache.take(heroTmdbKey(siteKey, vodId))
        if (prefetched != null) {
            tmdbState = TmdbDetailState.Ready(
                item = prefetched.item,
                // 缓存只收完整数据（`put` 校验过 detail != null），这里兜空对象只为满足非空类型。
                detail = prefetched.detail ?: JsonObject(),
                persons = prefetched.cast,
                manual = prefetched.manual,
            )
            Log.d(
                TAG_DETAIL,
                "tmdb 命中首页预取 manual=${prefetched.manual} id=${prefetched.item.tmdbId} persons=${prefetched.cast.size}",
            )
            return@LaunchedEffect
        }

        tmdbState = TmdbDetailState.Loading
        val ready = withContext(Dispatchers.IO) {
            try {
                val service = TmdbService()
                // 1) 先读手动匹配缓存，再读自动匹配缓存（与 TmdbUIAdapter.getCachedMatch 语义一致）。
                val cache = Setting.getTmdbMatchCache()
                val manual = cache.findManual(siteKey, vodId, current.name)
                // 2) 缓存未命中才搜索：详情页要的是「单条最佳」，不是候选列表。
                val item = manual
                    ?: cache.find(siteKey, vodId, current.name)
                    ?: TmdbMatcher(service, config).searchAndMatch(current.name, current)
                    ?: return@withContext null
                // 3) 详情与演员必须来自同一份 JSON（append_to_response 已在服务端拼好，且带 7 天磁盘缓存）。
                val detail = service.detail(item, config)
                TmdbDetailState.Ready(item, detail, service.cast(detail, config), manual != null)
            } catch (e: CancellationException) {
                // 页面已退出 / key 已变化：原样抛出，交给协程取消机制。
                throw e
            } catch (e: TmdbService.AuthException) {
                // Key 失效 / 被拒：不崩页，按「无 TMDB 数据」处理。
                Log.w(TAG_DETAIL, "tmdb 鉴权失败: ${e.message}")
                null
            } catch (e: Exception) {
                Log.w(TAG_DETAIL, "tmdb 加载失败: ${e.message}")
                null
            }
        }
        if (ready == null) {
            tmdbState = TmdbDetailState.Error("")
            Log.d(TAG_DETAIL, "tmdb 无可用数据（未命中或请求失败）")
            return@LaunchedEffect
        }
        tmdbState = ready
        Log.d(
            TAG_DETAIL,
            "tmdb ready manual=${ready.manual} id=${ready.item.tmdbId} persons=${ready.persons.size}",
        )
    }
    // 两组可聚焦区域：按钮组 + 演职员行。跨区移动全部显式 requestFocus，不依赖 2D 焦点搜索。
    val actionRequesters = remember { List(DETAIL_ACTIONS.size) { FocusRequester() } }
    // 换源按钮文案：后台预搜索出第一个源之后，直接把源名写在按钮上（没结果时保持「换源」）。
    val changeSourceLabel = remember(firstSearchSite) {
        val name = firstSearchSite?.displayName.orEmpty()
        if (name.isBlank()) "换源" else "换源 ($name)"
    }
    val castRequesters = remember(page.cast.size) { List(page.cast.size) { FocusRequester() } }
    var focusedAction by remember { mutableIntStateOf(DETAIL_ACTION_PLAY) }
    var focusedCast by remember { mutableIntStateOf(0) }
    // `showPlayDialog` / `showSourceDialog` 已在 result 观察者之前声明（观察者要用它们分流）。
    // 正在查看的演员下标；非 null 时演员详情弹窗可见。
    var actorDialogIndex by remember { mutableStateOf<Int?>(null) }
    // 弹窗关闭后要回去的演员下标；-1 表示「没有待恢复的演员焦点」。
    var pendingCastFocus by remember { mutableIntStateOf(-1) }
    // 弹窗关闭后要回去的按钮下标；-1 表示「没有待恢复的按钮焦点」。
    // 换源弹窗关闭时必须回到「换源」，而不是默认的「播放」。
    var pendingActionFocus by remember { mutableIntStateOf(-1) }

    // 三个弹窗都关闭后才抢焦点：
    // - 刚关掉演员弹窗 → 回到被点的那位演员（需求：焦点必须回到刚才点击的头像）；
    // - 刚关掉换源弹窗 → 回到「换源」按钮（需求三.3）；
    // - 否则（首次进入 / 关闭播放弹窗）→ 回到「播放」。
    LaunchedEffect(showPlayDialog, showSourceDialog, actorDialogIndex) {
        if (showPlayDialog || showSourceDialog || actorDialogIndex != null) return@LaunchedEffect
        val castTarget = pendingCastFocus
        pendingCastFocus = -1
        if (castTarget >= 0) {
            repeat(5) {
                if (requestDetailFocus(castRequesters, castTarget)) return@LaunchedEffect
                withFrameNanos { }
            }
            return@LaunchedEffect
        }
        val actionTarget = pendingActionFocus
        pendingActionFocus = -1
        if (actionTarget >= 0) {
            repeat(5) {
                if (requestDetailFocus(actionRequesters, actionTarget)) return@LaunchedEffect
                withFrameNanos { }
            }
            return@LaunchedEffect
        }
        repeat(3) {
            if (requestDetailFocus(actionRequesters, DETAIL_ACTION_PLAY)) return@LaunchedEffect
            withFrameNanos { }
        }
    }
    // TMDB 演员列表整体替换占位数据后，旧的演员下标可能越界：夹回合法范围，空列表直接关闭弹窗。
    //（`HeroActorDialog` 消费处另有 getOrNull 兜底，避免「重组先于本效果」的那一帧越界。）
    LaunchedEffect(page.cast.size) {
        val index = actorDialogIndex ?: return@LaunchedEffect
        actorDialogIndex = if (page.cast.isEmpty()) null else index.coerceIn(0, page.cast.lastIndex)
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // ---- 底层：全屏背景 + 两层遮罩（顺序：背景 → 横向遮罩 → 纵向遮罩）----
        HeroDetailBackdrop(url = page.backdropUrl)
        HeroDetailMaskHorizontal()
        HeroDetailMaskVertical()

        // ---- 右上角浮动小海报：150dp × 210dp（XML posterCard L30-48）----
        // 原版规则：有 TMDB 背景大图时隐藏小海报（整块不进组合，不是改透明度）；
        // 没有背景大图（无 Key / 未命中 / TMDB 无 backdrop）才用海报兜底，与改动前一致。
        if (!page.hasTmdbBackdrop) {
            HeroDetailPoster(
                url = page.posterUrl,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = POSTER_TOP_GAP, end = POSTER_END_GAP),
            )
        }

        // ---- 左下内容区：整体贴底，左右 24dp、底部 32dp 防贴屏幕边缘 ----
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(
                    start = HERO_PADDING,
                    end = HERO_PADDING,
                    bottom = CONTENT_BOTTOM_PADDING,
                ),
        ) {
            // 1. 剧名：有 Logo 显示艺术字，没有则 32sp 文字跑马灯（纯展示，不可聚焦）。
            HeroDetailTitle(detail = page)
            Spacer(modifier = Modifier.height(DETAIL_TITLE_META_GAP))

            // 2. 元数据固定 20dp、文字贴底（XML L90-102）。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(DETAIL_META_HEIGHT),
                contentAlignment = Alignment.BottomStart,
            ) {
                Text(
                    text = page.meta,
                    color = DETAIL_META_COLOR,
                    fontSize = DETAIL_META_SP.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(shadow = DETAIL_META_SHADOW),
                )
            }
            Spacer(modifier = Modifier.height(DETAIL_META_DESC_GAP))

            // 3. 简介：最宽 440dp、最多 4 行、超出省略（纯展示，不可聚焦）。
            Text(
                text = page.synopsis,
                modifier = Modifier.widthIn(max = DETAIL_DESC_MAX_WIDTH),
                color = DETAIL_DESC_COLOR,
                fontSize = DETAIL_DESC_SP.sp,
                lineHeight = DETAIL_DESC_LINE_HEIGHT_SP.sp,
                maxLines = DETAIL_DESC_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(shadow = DETAIL_DESC_SHADOW),
            )

            // 3.1 真实导演 / 主演：详情接口给了才显示，单行省略（纯展示，不可聚焦）。
            val credit = page.creditLine()
            if (credit.isNotBlank()) {
                Spacer(modifier = Modifier.height(DETAIL_CREDIT_TOP_GAP))
                Text(
                    text = credit,
                    modifier = Modifier.widthIn(max = DETAIL_DESC_MAX_WIDTH),
                    color = DETAIL_META_COLOR,
                    fontSize = DETAIL_CREDIT_SP.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(shadow = DETAIL_META_SHADOW),
                )
            }

            // 3.2 加载中 / 加载失败 / 空态分开显示：绝不把「请求失败」说成「没有剧集」，
            // 也不把「临时 ID 需要特殊解析」说成「这个源没有这部剧」。
            // `playLoading` 也算加载中：点了「播放」正在补详情时必须给出可见反馈。
            if (detailState == DetailState.Loading || playLoading) {
                Spacer(modifier = Modifier.height(DETAIL_CREDIT_TOP_GAP))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DETAIL_LOADING_GAP),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(DETAIL_LOADING_SIZE),
                        color = HeroAccentColor,
                        strokeWidth = DETAIL_LOADING_STROKE,
                    )
                    Text(
                        text = "正在加载剧集…",
                        color = DETAIL_META_COLOR,
                        fontSize = DETAIL_CREDIT_SP.sp,
                    )
                }
            } else if (detailState == DetailState.Error) {
                Spacer(modifier = Modifier.height(DETAIL_CREDIT_TOP_GAP))
                Text(
                    text = if (detailError.isBlank()) {
                        "详情加载失败，按「播放」重试"
                    } else {
                        "详情加载失败：$detailError（按「播放」重试）"
                    },
                    modifier = Modifier.widthIn(max = DETAIL_DESC_MAX_WIDTH),
                    color = DETAIL_ERROR_COLOR,
                    fontSize = DETAIL_CREDIT_SP.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else if (detailState == DetailState.Empty) {
                Spacer(modifier = Modifier.height(DETAIL_CREDIT_TOP_GAP))
                Text(
                    text = emptyHint,
                    modifier = Modifier.widthIn(max = DETAIL_DESC_MAX_WIDTH),
                    color = DETAIL_META_COLOR,
                    fontSize = DETAIL_CREDIT_SP.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // 4. 按钮组：紧跟在简介下方，默认焦点「播放」。
            Spacer(modifier = Modifier.height(DETAIL_ACTIONS_TOP_GAP))
            HeroDetailActionRow(
                requesters = actionRequesters,
                // 「换源 (源名)」：源名来自进页预搜索的第一条结果。
                changeSourceLabel = changeSourceLabel,
                onFocused = { index -> focusedAction = index },
                // 「播放」拉起选集弹窗；「换源」拉起换源弹窗（收藏仍是占位）。
                onConfirm = { index ->
                    when (index) {
                        DETAIL_ACTION_PLAY -> {
                            // 【唯一数据源】选集弹窗只认 `flags`（`Vod.setFlags()` 从 playFrom/playUrl 解析）。
                            // 判定标准不再看「有没有集数」：网盘源会把 `msubadd` 这类动作项伪装成集数，
                            // 只有存在真实 http/m3u8 地址才算当前源可播；否则一律走预搜索兜底。
                            val fallbackVod = firstSearchVod
                            val fallbackSite = firstSearchSite
                            val currentPlayable = flags.any { it.hasRealVideoUrl() }
                            when {
                                playLoading -> Notify.show("正在加载剧集，请稍候")
                                // 1) 当前源有**真实视频链接**：直接弹自己的选集。
                                currentPlayable -> {
                                    autoSwitchNotice = false
                                    playFlags = flags
                                    playVod = vod
                                    playSiteKey = siteKey
                                    showPlayDialog = true
                                }
                                // 2) 详情还在加载：等回包后按同一套「真实链接」规则再判断。
                                detailState == DetailState.Loading -> {
                                    pendingPlayOpen = true
                                    playLoading = true
                                    Notify.show("正在加载剧集，请稍候")
                                }
                                // 3) 当前源不可播（动作项 / 伪集数 / 空地址）：改拉预搜索第一个结果。
                                fallbackVod != null && fallbackSite != null && fallbackVod.id.isNotBlank() -> {
                                    Log.e(
                                        TAG_DETAIL,
                                        "当前源无真实视频链接，自动切换: site=${fallbackSite.key} " +
                                            "id=${fallbackVod.id} " +
                                            "集数=${flags.sumOf { it.episodes.orEmpty().size }} " +
                                            "真实链接线路=${flags.count { it.hasRealVideoUrl() }}",
                                    )
                                    autoSwitchNotice = true
                                    Notify.show("当前源不可播，正在切换可用源…")
                                    requestPlayTarget(fallbackVod, fallbackSite)
                                }
                                // 4) 预搜索还没回来：显示 Loading 等待，结果一到自动继续（见 LaunchedEffect）。
                                else -> {
                                    pendingPlayFallback = true
                                    playLoading = true
                                    Log.e(TAG_DETAIL, "当前源无真实视频链接，等待预搜索结果…")
                                    Notify.show("正在寻找可用源，请稍候")
                                }
                            }
                        }
                        DETAIL_ACTION_CHANGE_SOURCE -> {
                            // 【排查日志】换源按钮到底有没有点进来：没有这一行 = 点击事件没到。
                            Log.e("ChangeSource", "详情页换源按钮点击，准备呼出换源弹窗")
                            // 候选源规则与首页完全一致：
                            // isSearchable + isChangeable + 非隐藏 + 未被屏蔽 + 非当前源，再按站点健康度排序。
                            // 「当前源」= 这条数据自己的源（首页带过来的 site.key），不是首页源。
                            var candidates = ChangeSourceDialog.candidates(siteKey)
                            Log.e(
                                "ChangeSource",
                                "详情页换源候选源: size=${candidates.size}, keyword=[${page.title}], current=$siteKey",
                            )
                            if (candidates.isEmpty()) {
                                // 【兜底】真实数据下候选源可能因为「当前源不可换 / 全被屏蔽」而空：
                                // 退回全部可搜索源再试一次，绝不静默失败。
                                candidates = VodConfig.get().getSites().filter { it.isSearchable }
                                Log.e(
                                    "ChangeSource",
                                    "详情页候选源为空 → 兜底全部可搜索源: size=${candidates.size}",
                                )
                            }
                            if (candidates.isEmpty() || page.title.isBlank()) {
                                Log.e(
                                    "ChangeSource",
                                    "详情页换源不弹窗: sites=${candidates.size}, keyword=[${page.title}]",
                                )
                                Notify.show(context.getString(R.string.play_change_empty))
                            } else {
                                Log.e(
                                    "ChangeSource",
                                    "准备呼出: keyword=[${page.title}], sites=${candidates.size}",
                                )
                                pendingActionFocus = DETAIL_ACTION_CHANGE_SOURCE
                                showSourceDialog = true
                                // 打开换源弹窗 = 放弃这次「播放」等待：清掉在途的播放状态，
                                // 否则弹窗里的详情回包会被本页误当成「选集数据」直接弹选集。
                                playLoading = false
                                pendingPlayOpen = false
                                pendingPlayId = null
                                pendingPlaySite = null
                                pendingPlayVod = null
                                pendingPlayDrill = 0
                                pendingPlayCategory = null
                                // 换源弹窗改为 View 体系独立 Dialog；showSourceDialog 只用于关闭后的焦点交还。
                                try {
                                    ChangeSourceDialog.show(
                                        context = context,
                                        sites = candidates,
                                        // 详情页的剧名现在是真实值（来自首页 / 上一次换源），直接用它检索。
                                        keyword = page.title,
                                        // 全局并发搜索：先按 quick = false 跑，把不支持 quick 的源也搜一遍。
                                        quick = false,
                                        // 进页预搜索的结果直接铺开：弹窗首帧就有卡片，不再等一轮网络。
                                        initialResults = preSearchResults,
                                        onSourceSelected = { vod, site ->
                                            Notify.show(
                                                context.getString(
                                                    R.string.source_sidebar_switched,
                                                    "${site.displayName} · ${vod.name}",
                                                ),
                                            )
                                            // 换源结果和首页点卡片完全一致：folder 条目走分类页，其余进详情页。
                                            // 选中条目已经带好 site，不再按剧名二次搜索。
                                            openHeroDetailOrFolder(activity, vod, site)
                                        },
                                        onClosed = {
                                            showSourceDialog = false
                                            playLoading = false
                                            // 弹窗打开期间 result 通道归弹窗，本页的在途详情回包会被丢弃；
                                            // 关窗后按自己的主键重拉一次：既恢复页面，也作废弹窗可能还在途的请求
                                            // （`SiteViewModel.execute` 会 cancel 旧 future 并让旧回包失效）。
                                            if (ownsResultChannel) {
                                                Log.e(
                                                    TAG_DETAIL,
                                                    "换源弹窗关闭，重拉详情: site=$siteKey id=$vodId",
                                                )
                                                siteViewModel.detailContent(siteKey, vodId, false)
                                            } else if (inlineVod != null) {
                                                // 内联源不联网：把首帧那份线路原样恢复，绝不让弹窗的目录结果留在页面上。
                                                detailResult = Result.vod(inlineVod)
                                            }
                                        },
                                    )
                                } catch (e: Throwable) {
                                    // 【排查日志】弹窗构造 / show() 抛异常时不再静默闪退。
                                    Log.e("ChangeSource", "弹窗呼出崩溃", e)
                                    showSourceDialog = false
                                    Notify.show("换源弹窗打开失败: ${e.message}")
                                }
                            }
                        }
                    }
                },
                onMoveLeft = { index -> if (index > 0) focusAction(actionRequesters, index - 1) { focusedAction = it } },
                onMoveRight = { index ->
                    if (index < actionRequesters.lastIndex) focusAction(actionRequesters, index + 1) { focusedAction = it }
                },
                // ↓ 进入最底部的演职员行，落在上次停留的那位演员。
                onMoveDown = { focusCast(castRequesters, focusedCast) },
            )

            // 5. 演职员行（最底部）：可横向浏览、可点击，↑ 回按钮组。
            // 真实条目在 TMDB 到达前演员为空（不再显示假演员）：整行隐藏，避免只剩一个空标题。
            if (page.cast.isNotEmpty()) {
                Spacer(modifier = Modifier.height(DETAIL_CAST_TOP_GAP))
                HeroCastSection(
                    cast = page.cast,
                    requesters = castRequesters,
                    onFocused = { index -> focusedCast = index },
                    // 命中 TMDB → 进 View 版人物页（首帧用 extras，随后按 personId 全量刷新）；
                    // 未命中 → 保持原行为：记下「要回到哪个头像」并打开本地占位弹窗（不等网络）。
                    onConfirm = { index ->
                        val person = page.tmdbPersons.getOrNull(index)
                        if (person != null) {
                            TmdbPersonActivity.start(activity, person, siteKey, Setting.getDetailOpenMode())
                        } else {
                            pendingCastFocus = index
                            actorDialogIndex = index
                        }
                    },
                    onMoveUp = { focusAction(actionRequesters, DETAIL_ACTION_PLAY) { focusedAction = it } },
                    onMoveLeft = { index -> if (index > 0) focusCast(castRequesters, index - 1) },
                    onMoveRight = { index ->
                        if (index < castRequesters.lastIndex) focusCast(castRequesters, index + 1)
                    },
                )
            }
        }
    }

    if (showPlayDialog && playFlags.isNotEmpty()) {
        val episodeVod = playVod
        // 起播的源：本页详情 → 本页源；跨源兜底 → 预搜索命中的那个源。
        val episodeSiteKey = playSiteKey
        // 起播 id：优先用详情回包里的 id；回包没带时，**只有本页自己的源**才能回落到页面主键
        // （跨源时那个 id 属于别的源，绝不能拿来顶）。
        val episodeVodId = episodeVod?.id.orEmpty().ifBlank {
            if (episodeSiteKey == siteKey) vodId else ""
        }
        HeroPlayDialog(
            // 真实线路：`Vod.flags` 原样传入，某条线路没集数时弹窗内部显示「该线路暂无集数」。
            flags = playFlags,
            // 历史进度还没接本地库：-1 = 没看过，焦点落第 1 集。
            lastWatchedEpisodeIndex = -1,
            // 选中真实 (线路, 集) 后直接走原版起播入口，不自己拼 Intent。
            onEpisodeSelected = { flag, episode ->
                // 先关弹窗再起播：VideoActivity 是另一个页面，弹窗留在背后会闪一下。
                showPlayDialog = false
                startEpisode(
                    activity,
                    episodeSiteKey,
                    episodeVod,
                    episodeVodId,
                    flag,
                    episode,
                )
            },
            onDismiss = { showPlayDialog = false },
        )
    }

    // 演员详情：点击即弹出，数据在弹窗内部异步加载。
    // 演员列表可能因 TMDB 到达 / 未命中而变短或清空：这里用 getOrNull 兜底，绝不抛 IndexOutOfBounds。
    actorDialogIndex?.let { index ->
        page.cast.getOrNull(index)?.let { actor ->
            HeroActorDialog(
                actor = actor,
                onDismiss = { actorDialogIndex = null },
            )
        }
    }
}

/**
 * 首帧横图的提档目标：与首页 `highResBackdrop` 同为 `w1920`。
 *
 * 逐字一致才能直接命中首页预热好的 Coil 磁盘缓存；换成 `w1280` / `original` 都要重新下载。
 */
private const val TMDB_BACKDROP_WIDTH = "w1920"

/**
 * 剧名区：兼容 TMDB 艺术字 Logo。
 *
 * - 有 Logo：整行高 96dp（与首页统一）、最宽 720dp、等比缩放、贴底左对齐；
 *   图片没到 / 加载失败时，槽里继续渲染原本的 32sp 文字标题 —— 无感降级，不会先空一行。
 * - 无 Logo：32sp 粗体文字 + [basicMarquee]，长剧名持续滚动而不是被省略号吞掉。
 * 两种情况都不挂 `focusable` / `clickable`，遥控器选不中。
 */
@Composable
private fun HeroDetailTitle(detail: HeroDetail) {
    // `orEmpty()` 而不是留着可空：下面 `if (hasLogo)` 分支里 Kotlin 无法把 `String?` 智能转型，
    // 直接传 `rememberHeroImageModel(logo)` 会编译不过。
    val logo = detail.logoUrl.orEmpty()
    val hasLogo = logo.isNotBlank()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (hasLogo) LOGO_HEIGHT else DETAIL_TITLE_HEIGHT),
        contentAlignment = Alignment.BottomStart,
    ) {
        if (hasLogo) {
            SubcomposeAsyncImage(
                // 与首页共用同一份请求参数（含 300ms 淡入），首页预热过的艺术字这里直接命中磁盘缓存。
                model = rememberHeroImageModel(logo),
                contentDescription = detail.title,
                contentScale = ContentScale.Fit,
                // 等比缩放后仍靠左，避免窄 Logo 在 720dp 的盒子里居中。
                alignment = Alignment.BottomStart,
                // fillMaxHeight 吃满 96dp 行高；fillMaxWidth 是给 loading / error 槽里的文字
                // 留排版宽度 —— Subcompose 在图片到达前没有固有尺寸，只写 widthIn 会缩成 0 宽。
                modifier = Modifier
                    .fillMaxHeight()
                    .widthIn(max = LOGO_MAX_WIDTH)
                    .fillMaxWidth(),
                loading = { HeroDetailTitleFallback(detail.title) },
                error = { HeroDetailTitleFallback(detail.title) },
            )
        } else {
            HeroDetailTitleText(detail.title)
        }
    }
}

/**
 * Logo 槽里的文字兜底：贴底左对齐，和真正显示 Logo 时占同一条基线。
 *
 * 与首页 `HeroInfo` 的 loading / error 槽逐字同构：Subcompose 在图片未到达时
 * 不会自动按 `contentAlignment` 排槽内内容，必须自己包一层 `fillMaxSize` 的 Box。
 */
@Composable
private fun HeroDetailTitleFallback(text: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomStart,
    ) {
        HeroDetailTitleText(text)
    }
}

/**
 * 详情页剧名的纯文字形态：32sp 粗体白字 + 阴影 + 跑马灯。
 *
 * 「无 Logo」「Logo 加载中」「Logo 加载失败」三处共用同一份样式，
 * 图片到位瞬间不会出现字号 / 位置跳变。
 */
@Composable
private fun HeroDetailTitleText(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .basicMarquee(),
        color = Color.White,
        fontSize = DETAIL_TITLE_SP.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        softWrap = false,
        style = TextStyle(shadow = DETAIL_TITLE_SHADOW),
    )
}

/** 背景切换时长（毫秒）：800ms 与首页一致，首帧 → TMDB 横图是缓慢交叉淡化，不会硬切。 */
private const val DETAIL_BACKDROP_FADE_MS = 800

/**
 * 全屏背景大图。
 *
 * 用 [Crossfade] 而不是裸 [AsyncImage]：首帧 `extra_backdrop`（首页预取的 w1920 横图）到
 * TMDB 真背景之间可能有一次替换，交叉淡入淡出才不会出现「闪一下黑底再出新图」。
 * 同一 URL 时 Crossfade 不启动动画，因此锁死背景后不会有多余的过渡。
 */
@Composable
private fun HeroDetailBackdrop(url: String) {
    Crossfade(
        targetState = url,
        animationSpec = tween(durationMillis = DETAIL_BACKDROP_FADE_MS),
        label = "heroDetailBackdrop",
        modifier = Modifier.fillMaxSize(),
    ) { backdropUrl ->
        AsyncImage(
            model = rememberHeroImageModel(backdropUrl),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * 横向遮罩（原版 `bg_hero_mask_h` / 首页 `maskLeft`）。
 *
 * 与首页同一组参数：只有左侧 35% 之内压黑托住剧名 / 简介，右侧 65% 起完全透亮，
 * 保证 TMDB 背景大图不被整屏灰掉。
 */
@Composable
private fun HeroDetailMaskHorizontal() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.horizontalGradient(
                    0.0f to Color.Black.copy(alpha = 0.85f),
                    0.35f to Color.Black.copy(alpha = 0.0f),
                    1.0f to Color.Transparent,
                ),
            ),
    )
}

/**
 * 纵向遮罩（原版 `bg_hero_mask_v` / 首页 `maskBottom`）。
 *
 * 上 55% 全亮（背景图保持原亮度），底部渐到 0.9 黑，只托住左下角信息区与演员行。
 */
@Composable
private fun HeroDetailMaskVertical() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0.0f to Color.Transparent,
                    0.55f to Color.Transparent,
                    1.0f to Color.Black.copy(alpha = 0.9f),
                ),
            ),
    )
}

/**
 * 右上角浮动小海报：150dp × 210dp，marginTop 72dp、marginEnd 80dp、内边距 4dp
 * （XML `posterCard` L30-48：`shape_poster_card` 圆角 12dp + `black_20` 底 + `clipToOutline`）。
 * 只做视觉锚点，不参与焦点流转（不挂 focusable）。
 */
@Composable
private fun HeroDetailPoster(url: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(width = POSTER_WIDTH, height = POSTER_HEIGHT)
            .clip(POSTER_SHAPE)
            .background(POSTER_BACKGROUND)
            .padding(POSTER_INNER_PADDING),
    ) {
        AsyncImage(
            model = rememberHeroImageModel(url),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * 演职员行（Netflix 风格）：标题「导演 / 主演」+ 横向 `LazyRow`。
 *
 * 与上一版不同，这里**可聚焦**：按钮组 ↓ 进入本行，行内 ←/→ 横移，↑ 回「播放」。
 * 头像 60dp 圆形，焦点态加 2dp 红圈并放大到 1.08 倍；名字 12sp 单行省略。
 */
@Composable
private fun HeroCastSection(
    cast: List<HeroCast>,
    requesters: List<FocusRequester>,
    onFocused: (Int) -> Unit,
    onConfirm: (Int) -> Unit,
    onMoveUp: () -> Unit,
    onMoveLeft: (Int) -> Unit,
    onMoveRight: (Int) -> Unit,
) {
    HeroSectionTitle(text = "导演 / 主演", fontSize = CAST_TITLE_SP.sp)
    Spacer(modifier = Modifier.height(CAST_TITLE_GAP))
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(CAST_ITEM_GAP),
        // 列表铺到屏幕右缘，最后一位演员可以贴边滑出，不做硬性截断。
        contentPadding = PaddingValues(end = HERO_PADDING),
    ) {
        itemsIndexed(items = cast, key = { _, item -> item.id }) { index, item ->
            HeroCastItem(
                cast = item,
                requester = requesters[index],
                onFocused = { onFocused(index) },
                onConfirm = { onConfirm(index) },
                onMoveUp = onMoveUp,
                onMoveLeft = { onMoveLeft(index) },
                onMoveRight = { onMoveRight(index) },
            )
        }
    }
}

@Composable
private fun HeroCastItem(
    cast: HeroCast,
    requester: FocusRequester,
    onFocused: () -> Unit,
    onConfirm: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val isFocused by interaction.collectIsFocusedAsState()
    // 焦点态轻微放大，配合红圈做选中反馈；160ms 过渡，避免 TV 上硬切。
    val scale by animateFloatAsState(
        targetValue = if (isFocused) CAST_FOCUS_SCALE else 1f,
        label = "castScale",
    )

    Column(
        modifier = Modifier
            .width(CAST_ITEM_WIDTH)
            .focusRequester(requester)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionUp -> {
                        onMoveUp()
                        true
                    }

                    Key.DirectionLeft -> {
                        onMoveLeft()
                        true
                    }

                    Key.DirectionRight -> {
                        onMoveRight()
                        true
                    }

                    // 演职员行是最底部区域，↓ 无处可去，直接消费。
                    Key.DirectionDown -> true

                    else -> false
                }
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onConfirm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 圆形头像：先 clip 再 border，描边画在图片之上；焦点态换成 2dp 红圈。
        Box(
            modifier = Modifier
                .size(CAST_AVATAR_SIZE)
                .scale(scale)
                .clip(CircleShape)
                .background(CAST_AVATAR_BACKGROUND)
                .border(
                    width = if (isFocused) CAST_FOCUS_BORDER else CAST_AVATAR_BORDER,
                    color = if (isFocused) HeroAccentColor else CAST_AVATAR_BORDER_COLOR,
                    shape = CircleShape,
                ),
        ) {
            AsyncImage(
                model = rememberHeroImageModel(cast.avatarUrl),
                contentDescription = cast.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(modifier = Modifier.height(CAST_NAME_GAP))
        Text(
            text = cast.name,
            modifier = Modifier.fillMaxWidth(),
            color = if (isFocused) Color.White else CAST_NAME_COLOR,
            fontSize = CAST_NAME_SP.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

// ---------------------------------------------------------------------------
// 演员详情：点击即弹窗，文本走轻量缓存，图片全部交给 Coil
// ---------------------------------------------------------------------------

/**
 * 演员详情弹窗（骨架）。
 *
 * **不阻塞 UI**：点击时只是把 [HeroDetailScreen] 里的 `actorDialogIndex` 置位，
 * Dialog 当帧组合、立刻显示「加载中」，遥控器不会卡。
 * 真正的数据请求在 [LaunchedEffect] 里切到 [Dispatchers.IO]；接 TMDB 时只改 [loadActorProfile]。
 *
 * 缓存策略：
 * - **只缓存文本**（简介 / 生日 / 出生地）→ [ActorProfileCache]，进程内 Map，轻量、无磁盘 IO。
 * - **头像与作品海报一律不落自定义缓存**，全交给 Coil 默认 LRU 磁盘缓存（满 250MB 自动淘汰），
 *   这里只通过 `AsyncImage(model = url)` 走默认 ImageLoader，不写任何下载/保存逻辑。
 */
@Composable
private fun HeroActorDialog(actor: HeroCast, onDismiss: () -> Unit) {
    // 命中缓存时首帧就有内容；未命中则显示加载态。
    var profile by remember(actor.id) { mutableStateOf(ActorProfileCache.get(actor.id)) }

    LaunchedEffect(actor.id) {
        if (profile != null) return@LaunchedEffect
        val loaded = loadActorProfile(actor)
        ActorProfileCache.put(actor.id, loaded)
        profile = loaded
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false,
        ),
    ) {
        HeroDialogShell(modifier = Modifier.fillMaxWidth(ACTOR_DIALOG_WIDTH_FRACTION)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = rememberHeroImageModel(actor.avatarUrl),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(ACTOR_DIALOG_AVATAR)
                        .clip(CircleShape)
                        .background(CAST_AVATAR_BACKGROUND),
                )
                Spacer(modifier = Modifier.width(ACTOR_DIALOG_AVATAR_GAP))
                Column {
                    Text(
                        text = actor.name,
                        color = Color.White,
                        fontSize = ACTOR_DIALOG_NAME_SP.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "演员档案",
                        color = ACTOR_DIALOG_HINT_COLOR,
                        fontSize = ACTOR_DIALOG_HINT_SP.sp,
                        maxLines = 1,
                    )
                }
            }
            Spacer(modifier = Modifier.height(ACTOR_DIALOG_HEADER_GAP))
            val loaded = profile
            if (loaded == null) {
                // Loading 态：2 秒假延迟期间显示，绝不占用主线程。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(ACTOR_DIALOG_SPINNER),
                        strokeWidth = 2.dp,
                        color = HeroAccentColor,
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "加载中…",
                        color = ACTOR_DIALOG_HINT_COLOR,
                        fontSize = ACTOR_DIALOG_BODY_SP.sp,
                    )
                }
            } else {
                Text(
                    text = loaded.summary,
                    color = Color.White,
                    fontSize = ACTOR_DIALOG_BODY_SP.sp,
                    lineHeight = ACTOR_DIALOG_BODY_LINE_HEIGHT.sp,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "${loaded.birthday} · ${loaded.place}",
                    color = ACTOR_DIALOG_HINT_COLOR,
                    fontSize = ACTOR_DIALOG_HINT_SP.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 取演员文本档案：当前是 2 秒假延迟 + 占位文案，接口接好后替换函数体即可。
 *
 * 真实实现建议：`GET /person/{person_id}?language=zh-CN`，只取 `biography`/`birthday`/`place_of_birth`；
 * 头像（`profile_path`）与作品海报**不要**在这里下载，直接拼成 URL 交给 Coil。
 */
private suspend fun loadActorProfile(actor: HeroCast): ActorProfile = withContext(Dispatchers.IO) {
    delay(ACTOR_PROFILE_FAKE_DELAY_MS)
    ActorProfile(
        name = actor.name,
        birthday = "1988-06-12",
        place = "中国 · 上海",
        summary = "演员详情开发中。\n\n这里将展示 TMDB 返回的演员简介：出生地、生日、代表作与个人履历。",
    )
}

/** 演员文本档案：只放文本字段，图片 URL 由调用方直接喂给 Coil，不参与缓存。 */
private data class ActorProfile(
    val name: String,
    val birthday: String,
    val place: String,
    val summary: String,
)

/**
 * 轻量文本缓存：进程内 Map，够用且零依赖。
 *
 * 换成 Room 时只需实现同样的 `get` / `put` 两个方法，调用点不动。
 * **绝不缓存图片**：Coil 自己的磁盘 LRU 已经处理了淘汰，重复缓存只会白白占空间。
 */
private object ActorProfileCache {

    private val store = java.util.concurrent.ConcurrentHashMap<String, ActorProfile>()

    fun get(actorId: String): ActorProfile? = store[actorId]

    fun put(actorId: String, profile: ActorProfile) {
        store[actorId] = profile
    }
}

/** 按钮组：播放 / 换源 / 收藏，横向排列、间距 12dp。 */
@Composable
private fun HeroDetailActionRow(
    requesters: List<FocusRequester>,
    /** 换源按钮的动态文案（`换源 (源名)`）：预搜索还没结果时就是「换源」。 */
    changeSourceLabel: String,
    onFocused: (Int) -> Unit,
    onConfirm: (Int) -> Unit,
    onMoveLeft: (Int) -> Unit,
    onMoveRight: (Int) -> Unit,
    onMoveDown: (Int) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(DETAIL_ACTION_GAP)) {
        DETAIL_ACTIONS.forEachIndexed { index, action ->
            HeroDetailActionButton(
                action = action,
                // 只有「换源」允许覆盖文案，其余按钮保持 [DETAIL_ACTIONS] 里的静态文案。
                label = if (index == DETAIL_ACTION_CHANGE_SOURCE) changeSourceLabel else action.label,
                requester = requesters[index],
                onFocused = { onFocused(index) },
                onConfirm = { onConfirm(index) },
                onMoveLeft = { onMoveLeft(index) },
                onMoveRight = { onMoveRight(index) },
                onMoveDown = { onMoveDown(index) },
            )
        }
    }
}

/**
 * 单颗按钮：高 36dp、字号 13sp、图标在左文字在右。
 *
 * 焦点态按需求取 [HeroFocusBrush] 红渐变色块 + 纯白文字/图标（实心块全局统一渐变）；
 * 常态沿用原版 `shape_detail_item_normal` 的 `white_10`（`#1AFFFFFF`）底 + 4dp 圆角。
 *
 * 方向键：←/→ 在按钮间移动，↓ 进入演职员行，↑ 已到内容区顶部、直接消费。
 */
@Composable
private fun HeroDetailActionButton(
    action: HeroDetailAction,
    /** 实际显示的文案：默认取 [action]，换源按钮由调用方覆盖成「换源 (源名)」。 */
    label: String,
    requester: FocusRequester,
    onFocused: () -> Unit,
    onConfirm: () -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
    onMoveDown: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val isFocused by interaction.collectIsFocusedAsState()

    Row(
        modifier = Modifier
            .height(DETAIL_ACTION_HEIGHT)
            .focusRequester(requester)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> {
                        onMoveLeft()
                        true
                    }

                    Key.DirectionRight -> {
                        onMoveRight()
                        true
                    }

                    Key.DirectionDown -> {
                        onMoveDown()
                        true
                    }

                    Key.DirectionUp -> true

                    else -> false
                }
            }
            .clip(DETAIL_ACTION_SHAPE)
            // 焦点态 = 全局红渐变色块（原先是纯红 solid，现统一到 [HeroFocusBrush]）。
            .then(
                if (isFocused) {
                    Modifier.background(HeroFocusBrush, DETAIL_ACTION_SHAPE)
                } else {
                    Modifier.background(DETAIL_ACTION_IDLE_BACKGROUND, DETAIL_ACTION_SHAPE)
                },
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onConfirm)
            .padding(horizontal = DETAIL_ACTION_H_PADDING),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DETAIL_ACTION_ICON_GAP),
    ) {
        Image(
            painter = painterResource(id = action.iconRes),
            contentDescription = null,
            colorFilter = ColorFilter.tint(Color.White),
            modifier = Modifier.size(action.iconSize),
        )
        Text(
            text = label,
            color = Color.White,
            fontSize = DETAIL_ACTION_SP.sp,
            // XML L134：textStyle="bold"，三颗按钮全部加粗。
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------------------
// 选集弹窗：外壳 + 线路栏 + 单行控制栏 + 固定 4 排网格（零内部滚动）
// ---------------------------------------------------------------------------

/**
 * 选集弹窗（终稿）。
 *
 * 版式自上而下（尺寸逐条对照 `ref_docs/`）：
 * 1. 外壳 [HeroDialogShell]：`#9914171F` 半透明深灰蓝 + 16dp 大圆角 + 18dp 内边距。
 * 2. 「播放线路」：4dp × 16dp 红竖条 + 15sp 加粗标题（不可聚焦），下方 32dp / 14sp 线路按钮。
 *    —— 顶部原来的「选集徽章 + 剧名」整行已删除，弹窗第一行就是播放线路。
 * 3. 「选集」控制栏：**单行** Row，依次为红竖条 + 15sp「选集」（不可聚焦）、少列/多列、
 *    正序/倒序、分组按钮；按钮统一 32dp 高 / 14sp，全部可聚焦，←/→ 在本行内滑动。
 * 4. 选集网格：固定 [EPISODE_GRID_ROWS] 排，高度 = 排数 × 34dp + 间距，`userScrollEnabled = false`
 *    禁止内部滚动；卡片 34dp / 16sp 居中 + `basicMarquee()` 防溢出。
 *
 * 分组是「4 排 × 当前列数」的动态分页（多列 16 集一组 / 少列 8 集一组），
 * 所以不管剧集总数是 50 还是几百集，网格永远只有 4 排，翻页全靠边缘按键，绝不滚动。
 *
 * 历史记忆（本阶段用参数注入，接库后只换数据来源、不动这里的逻辑）：
 * - [lastWatchedEpisodeIndex] 是**0 基集索引**（0 = 第 1 集），-1 表示新剧。
 * - 新剧：焦点落第 1 集；老剧：分组先自动切到该集所在组，再把焦点落到该集本身。
 * - 用户按确定键时回调 [onEpisodeSelected]，直接带出真实的 `(Flag, Episode)` 组合。
 *
 * 焦点契约：
 * - 打开落在历史集（`lastWatchedEpisodeIndex`）；新剧落第 1 集。
 * - 线路栏 ←/→ 在线路间移动，端点消费；↓ 进控制栏；↑ 消费。
 * - 控制栏 ←/→ 在「列数 / 排序 / 分组」之间滑动；↓ 进网格（尽量落回同一集）；↑ 回线路栏。
 * - 网格组内 ←/→/↑/↓ 正常移动；**任意一行**的最右一列按 → 切下一组（普通行落同行第 1 列，
 *   最后一行落新组最后一集），**任意一行**的最左一列按 ← 切上一组（落上一组同行最右一列）。
 * - 列数 / 排序写进 SharedPreferences，下次打开弹窗沿用上次选择；返回键关闭弹窗。
 */
@Composable
internal fun HeroPlayDialog(
    flags: List<Flag>,
    lastWatchedEpisodeIndex: Int = -1,
    onEpisodeSelected: (Flag, Episode) -> Unit = { _, _ -> },
    onDismiss: () -> Unit,
) {
    // 一条线路都没有时不弹空壳；有线路但某条没集数，由下面的网格区显示「该线路暂无集数」。
    if (flags.isEmpty()) return

    // 列数 / 排序本地持久化：换剧、关弹窗、杀进程后再进来仍是上次的选择。
    val context = LocalContext.current
    val prefs = remember(context) {
        context.getSharedPreferences(DIALOG_PREFS_NAME, Context.MODE_PRIVATE)
    }
    // 排序只有「正序 / 倒序」两态：默认正序，点一次整体翻转。
    var descending by remember { mutableStateOf(prefs.getBoolean(DIALOG_PREF_DESCENDING, false)) }
    // 列数只有「多列(4) / 少列(2)」两态：默认多列。
    var fewColumns by remember { mutableStateOf(prefs.getBoolean(DIALOG_PREF_FEW_COLUMNS, false)) }

    // 当前线路：线路栏「焦点即选中」，网格始终只渲染它的 `episodes`。
    var selectedFlag by remember { mutableIntStateOf(0) }
    val currentFlag = flags[selectedFlag.coerceIn(0, flags.lastIndex)]

    // 【动作指令过滤】网盘聚合 / 猫源把「📄 媒体信息 / 🔍 全局搜索 / ➕ 稍后再看」伪装成集数
    // （`📄 媒体信息$msubinfo-db%3A...`）。这类 url 不是播放地址，直接**从选集里剔掉**，
    // 不再灰显：用户看到的每一格都保证能起播。
    val lineEpisodes = remember(flags) {
        flags.map { flag -> flag.episodes.orEmpty().filterNot { it.isActionCommand() } }
    }
    // 全部线路过滤后都没集数 = 这个源只给了动作指令，UI 显示「该源无可用剧集」。
    val hasPlayableEpisode = lineEpisodes.any { it.isNotEmpty() }

    // 基准表直接用源站返回的 `Episode` 顺序：**不再按集号重排**。
    // 很多源的集名根本没有数字（`Episode.number` 全是 0），按 number 排序会把顺序打乱；
    // 源站顺序本身就是正序，历史索引 / 倒序镜像都以它为坐标原点。
    val ascending = remember(lineEpisodes, selectedFlag) {
        lineEpisodes.getOrElse(selectedFlag.coerceIn(0, lineEpisodes.lastIndex)) { emptyList() }
    }
    val ordered = remember(descending, ascending) { if (descending) ascending.asReversed() else ascending }
    val columns = if (fewColumns) EPISODE_COLUMNS_FEW else EPISODE_COLUMNS_MANY
    // 组容量 = 4 排 × 当前列数：多列 16 集 / 少列 8 集，永远刚好填满 4 排。
    val pageSize = EPISODE_GRID_ROWS * columns
    val pageCount = ((ordered.size + pageSize - 1) / pageSize).coerceAtLeast(1)

    // 历史索引 → 升序下标；-1（新剧）或越界统一收敛到第 1 集。
    val watchedIndex = remember(lastWatchedEpisodeIndex, ascending) {
        if (lastWatchedEpisodeIndex in ascending.indices) lastWatchedEpisodeIndex else 0
    }
    // 升序下标 → 当前排序下的显示下标（倒序时整体镜像），据此算初始分组。
    val initialIndex = remember(watchedIndex, descending, ascending) {
        (if (descending) ascending.lastIndex - watchedIndex else watchedIndex).coerceAtLeast(0)
    }
    val initialEpisode = remember(ordered, initialIndex) { ordered.getOrNull(initialIndex) }

    var page by remember { mutableIntStateOf(initialIndex / pageSize) }
    /** 焦点集在「当前组内」的下标；焦点不在网格时保留最后一次有效值。 */
    var anchorCell by remember { mutableIntStateOf(initialIndex % pageSize) }
    /** 焦点 / 当前集直接用 `Episode` 对象本身做锚点（不同线路的集数互不干扰）。 */
    var anchorEpisode by remember { mutableStateOf(initialEpisode) }
    /** 正在播放的集：只做红字高亮，切线路后按该线路的集数重置。 */
    var playingEpisode by remember { mutableStateOf(initialEpisode) }
    var focusedFlag by remember { mutableIntStateOf(0) }
    // 焦点是否还在「线路 / 控制栏 / 选集网格」的下半区。
    // 只在线路栏上时为 false；一旦进入控制栏或网格就为 true ——
    // 此时当前线路、当前分组都要留下红框（《焦点总结》规则 ②），让用户始终知道在放哪条线路、哪一组。
    var contentFocused by remember { mutableStateOf(false) }
    /** 控制栏下标：0 = 列数、1 = 排序、2 起为分组按钮。 */
    var focusedControl by remember { mutableIntStateOf(CONTROL_COLUMNS) }

    val pageItems = remember(ordered, page, pageSize) {
        ordered.drop(page * pageSize).take(pageSize)
    }

    // 待落焦格 / 待落焦按钮：-1 = 不抢焦点；serial 让同一目标连续请求也能重新触发。
    var pendingCell by remember { mutableIntStateOf(initialIndex % pageSize) }
    var cellSerial by remember { mutableIntStateOf(0) }
    var pendingControl by remember { mutableIntStateOf(-1) }
    var controlSerial by remember { mutableIntStateOf(0) }

    // 切线路：集数长度 / 顺序都可能不同，分组与焦点回到新线路第 1 集。
    // 首次组合不重置（保留上面的历史集落点），只在 selectedFlag 真正变化时执行。
    var lastFlagIndex by remember { mutableIntStateOf(selectedFlag) }
    LaunchedEffect(selectedFlag) {
        if (lastFlagIndex == selectedFlag) return@LaunchedEffect
        lastFlagIndex = selectedFlag
        page = 0
        anchorCell = 0
        anchorEpisode = ordered.firstOrNull()
        playingEpisode = anchorEpisode
        pendingCell = -1
    }

    val flagRequesters = remember(flags.size) { List(flags.size) { FocusRequester() } }
    val columnsRequester = remember { FocusRequester() }
    val sortRequester = remember { FocusRequester() }
    // 分组按钮走 LazyRow（剧集多时分组会溢出），requesters 随页数重建。
    val groupRequesters = remember(pageCount) { List(pageCount) { FocusRequester() } }
    // 网格 item 用「组内下标」做 key：切组时节点原样复用，焦点不会被整片销毁重建成空档。
    val cellRequesters = remember(pageSize) { List(pageSize) { FocusRequester() } }
    val groupListState = rememberLazyListState()

    fun focusFlag(index: Int) {
        if (requestDetailFocus(flagRequesters, index)) focusedFlag = index
    }

    /** 控制栏落焦：登记目标，真正的滚动 / 请求交给下面的 LaunchedEffect（越界时夹到首尾）。 */
    fun focusControl(index: Int) {
        val target = index.coerceIn(0, CONTROL_GROUP_START + pageCount - 1)
        focusedControl = target
        pendingControl = target
        controlSerial++
    }

    /** 组内落焦：把下标收敛到当前组内，越界时夹到首尾。 */
    fun focusCell(index: Int) {
        if (pageItems.isEmpty()) return
        val target = if (index < 0) 0 else index.coerceAtMost(pageItems.lastIndex)
        anchorCell = target
        anchorEpisode = pageItems[target]
        pendingCell = target
        cellSerial++
    }

    /** 换组落焦：写入新组状态并把目标格排进请求队列。 */
    fun landOnPage(target: Int, items: List<Episode>, index: Int) {
        page = target
        anchorCell = index
        anchorEpisode = items[index]
        pendingCell = index
        cellSerial++
    }

    /**
     * 右切下一组：普通行落「新组同一行的第 1 列」；
     * 最后一行落「新组最后一集」（继续往下看，不再从行首重新扫）。
     * 已是最后一组时什么都不做，事件由调用方消费，绝不越界。
     */
    fun pageForward(row: Int, fromLastRow: Boolean) {
        val target = page + 1
        if (target !in 0 until pageCount) return
        val items = ordered.drop(target * pageSize).take(pageSize)
        if (items.isEmpty()) return
        val index = if (fromLastRow) items.lastIndex else (row * columns).coerceAtMost(items.lastIndex)
        landOnPage(target, items, index)
    }

    /** 左切上一组：落「上一组同一行的最右一列」；该行没有格子时落上一组最后一集。 */
    fun pageBackward(row: Int) {
        val target = page - 1
        if (target !in 0 until pageCount) return
        val items = ordered.drop(target * pageSize).take(pageSize)
        if (items.isEmpty()) return
        val index = if (row > items.lastIndex / columns) {
            items.lastIndex
        } else {
            (row * columns + columns - 1).coerceAtMost(items.lastIndex)
        }
        landOnPage(target, items, index)
    }

    /** 从控制栏进入网格：优先落回当前集，找不到就落第 1 格。 */
    fun enterGrid() {
        val index = pageItems.indexOfFirst { it === anchorEpisode }
        focusCell(if (index >= 0) index else 0)
    }

    /** 点分组按钮：只换组，焦点留在按钮上（不抢进网格）。 */
    fun selectPage(target: Int) {
        if (target !in 0 until pageCount) return
        val items = ordered.drop(target * pageSize).take(pageSize)
        if (items.isEmpty()) return
        page = target
        anchorCell = 0
        anchorEpisode = items.first()
        pendingCell = -1
    }

    /** 排序翻转：按「集对象」重新定位分组；焦点留在 Toggle 上，不把焦点抢进网格。 */
    fun toggleSort() {
        val current = anchorEpisode
        val next = !descending
        val reordered = ascending.let { if (next) it.asReversed() else it }
        descending = next
        prefs.edit().putBoolean(DIALOG_PREF_DESCENDING, next).apply()
        val index = reordered.indexOfFirst { it === current }
        if (index >= 0) {
            page = index / pageSize
            anchorCell = index % pageSize
        }
        pendingCell = -1
    }

    /** 列数切换：组容量从 16 ↔ 8 变化，按「全局集下标」重算分组与组内位置。 */
    fun toggleColumns() {
        val lastEpisode = (ordered.size - 1).coerceAtLeast(0)
        val episodeIndex = (page * pageSize + anchorCell).coerceIn(0, lastEpisode)
        fewColumns = !fewColumns
        prefs.edit().putBoolean(DIALOG_PREF_FEW_COLUMNS, fewColumns).apply()
        val newSize = EPISODE_GRID_ROWS * (if (fewColumns) EPISODE_COLUMNS_FEW else EPISODE_COLUMNS_MANY)
        val newPageCount = ((ordered.size + newSize - 1) / newSize).coerceAtLeast(1)
        page = (episodeIndex / newSize).coerceIn(0, newPageCount - 1)
        anchorCell = episodeIndex % newSize
        // 焦点留在列数按钮上，等用户按 ↓ 再进网格，避免切列数时焦点乱跳。
        pendingCell = -1
    }

    /**
     * 网格方向键：组内上下左右移动 + 「整列」边缘翻页。
     *
     * 翻页判定只看列：任意一行的**最左一列**按 ← = 上一组，**最右一列**按 → = 下一组，
     * 所以不必先把焦点挪到右下角才能翻页；两端的落点见 [pageForward] / [pageBackward]。
     * 上下键永远不翻页，末行 ↓ 只消费。
     */
    fun handleGridKey(key: Key): Boolean {
        if (pageItems.isEmpty()) return false
        val lastIndex = pageItems.lastIndex
        val base = anchorCell.coerceIn(0, lastIndex)
        val column = base % columns
        val row = base / columns
        val lastRow = lastIndex / columns
        return when (key) {
            Key.DirectionLeft -> {
                if (column == 0) pageBackward(row) else focusCell(base - 1)
                true
            }

            Key.DirectionRight -> {
                when {
                    column == columns - 1 -> pageForward(row, fromLastRow = row == lastRow)
                    base == lastIndex -> pageForward(row, fromLastRow = true)
                    else -> focusCell(base + 1)
                }
                true
            }

            Key.DirectionUp -> {
                if (row > 0) focusCell(base - columns) else focusControl(focusedControl)
                true
            }

            Key.DirectionDown -> {
                if (base + columns <= lastIndex) focusCell(base + columns)
                true
            }

            else -> false
        }
    }

    // 网格落焦：Lazy 项要先组合出来 `requestFocus` 才拿得到节点，所以逐帧重试。
    LaunchedEffect(page, pageSize, pendingCell, cellSerial) {
        val target = pendingCell
        if (target < 0 || target !in pageItems.indices) return@LaunchedEffect
        repeat(FOCUS_RETRY_FRAMES) {
            if (requestDetailFocus(cellRequesters, target)) {
                pendingCell = -1
                return@LaunchedEffect
            }
            withFrameNanos { }
        }
    }

    // 控制栏落焦：分组按钮在 LazyRow 里，先滚到可视区再请求焦点。
    LaunchedEffect(pageCount, pendingControl, controlSerial) {
        val target = pendingControl
        if (target < 0) return@LaunchedEffect
        if (target >= CONTROL_GROUP_START) {
            runCatching { groupListState.scrollToItem(target - CONTROL_GROUP_START) }
        }
        repeat(FOCUS_RETRY_FRAMES) {
            if (requestControlFocus(columnsRequester, sortRequester, groupRequesters, target)) {
                pendingControl = -1
                return@LaunchedEffect
            }
            withFrameNanos { }
        }
    }

    // 打开弹窗时把「历史集所在分组」按钮滚进可视区，和网格自动切组保持一致。
    LaunchedEffect(Unit) {
        runCatching { groupListState.scrollToItem(page.coerceIn(0, (pageCount - 1).coerceAtLeast(0))) }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        // 全屏遮罩 + 居中面板；遮罩让底图继续透出来一点，形成「半透明」层次。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(DIALOG_SCRIM),
            contentAlignment = Alignment.Center,
        ) {
            HeroDialogShell(modifier = Modifier.fillMaxWidth(DIALOG_WIDTH_FRACTION)) {
                // ---- 播放线路：4dp 红竖条标题 + 32dp 横排（标题纯展示，不可聚焦）----
                HeroSectionTitle(text = "播放线路", fontSize = DIALOG_SECTION_TITLE_SP.sp)
                Spacer(modifier = Modifier.height(DIALOG_TITLE_GAP))
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(DIALOG_FLAG_ROW_HEIGHT),
                    horizontalArrangement = Arrangement.spacedBy(DIALOG_FLAG_GAP),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    itemsIndexed(
                        items = flags,
                        // 线路名可能重复（不同解析线路都叫「线路一」），key 里带上稳定下标。
                        key = { index, flag -> Flag.stableKey(flag, index) },
                    ) { index, flag ->
                        HeroFlagChip(
                            label = flag.getShow(),
                            selected = index == selectedFlag,
                            moduleFocused = contentFocused,
                            requester = flagRequesters[index],
                            // 焦点回到线路栏 = 暂时离开下半区：红框收起，只留当前项的焦点块。
                            // 【焦点即选中】焦点移到哪条线路就切到哪条，不用再按确定键。
                            // 线路切换是纯本地状态（不联网），所以立即生效、不做防抖。
                            onFocused = {
                                focusedFlag = index
                                contentFocused = false
                                selectedFlag = index
                            },
                            // 确定键保留（兼容原有按键习惯）：焦点即选中之后这里通常已是同一个值。
                            onConfirm = { selectedFlag = index },
                            // 线路栏 ↑ 上方只有不可聚焦的分区标题，直接消费。
                            onMoveUp = { },
                            onMoveDown = { focusControl(focusedControl) },
                            onMoveLeft = { if (index > 0) focusFlag(index - 1) },
                            onMoveRight = { if (index < flags.lastIndex) focusFlag(index + 1) },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(DIALOG_SECTION_GAP))

                // ---- 选集单行控制栏：标题（不可聚焦）+ 列数 + 排序 + 分组，按钮 32dp / 14sp ----
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(DIALOG_TOGGLE_HEIGHT),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HeroSectionTitle(text = "选集", fontSize = DIALOG_SECTION_TITLE_SP.sp)
                    Spacer(modifier = Modifier.width(DIALOG_CONTROL_GAP))
                    HeroToggleButton(
                        label = if (fewColumns) "少列" else "多列",
                        moduleFocused = contentFocused,
                        requester = columnsRequester,
                        onFocused = {
                            focusedControl = CONTROL_COLUMNS
                            contentFocused = true
                        },
                        onConfirm = { toggleColumns() },
                        onMoveUp = { focusFlag(focusedFlag) },
                        onMoveDown = { enterGrid() },
                        onMoveLeft = { focusControl(CONTROL_COLUMNS - 1) },
                        onMoveRight = { focusControl(CONTROL_COLUMNS + 1) },
                    )
                    Spacer(modifier = Modifier.width(DIALOG_CONTROL_GAP))
                    HeroToggleButton(
                        label = if (descending) "倒序" else "正序",
                        moduleFocused = contentFocused,
                        requester = sortRequester,
                        onFocused = {
                            focusedControl = CONTROL_SORT
                            contentFocused = true
                        },
                        onConfirm = { toggleSort() },
                        onMoveUp = { focusFlag(focusedFlag) },
                        onMoveDown = { enterGrid() },
                        onMoveLeft = { focusControl(CONTROL_SORT - 1) },
                        onMoveRight = { focusControl(CONTROL_SORT + 1) },
                    )
                    Spacer(modifier = Modifier.width(DIALOG_CONTROL_GAP))
                    // 分组按钮：剧集多时一行放不下，LazyRow 会把焦点按钮滚进可视区。
                    LazyRow(
                        state = groupListState,
                        modifier = Modifier
                            .weight(1f)
                            .height(DIALOG_TOGGLE_HEIGHT),
                        horizontalArrangement = Arrangement.spacedBy(DIALOG_CONTROL_GAP),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        items(count = pageCount, key = { index -> index }) { index ->
                            val start = index * pageSize + 1
                            val end = minOf((index + 1) * pageSize, ordered.size)
                            val controlIndex = CONTROL_GROUP_START + index
                            HeroToggleButton(
                                // 空线路（episodes 为空）时只有一个分组，别显示成「1-0」。
                                label = if (ordered.isEmpty()) "0" else "$start-$end",
                                selected = index == page,
                                moduleFocused = contentFocused,
                                requester = groupRequesters[index],
                                onFocused = {
                                    focusedControl = controlIndex
                                    contentFocused = true
                                    // 【焦点即选中】焦点移到哪个分组就切到哪一组，下方集数网格立刻刷新。
                                    // 纯本地状态（`ordered` 已全部在内存里），不联网、无需防抖；
                                    // `selectPage` 内部会 `pendingCell = -1`，不会把焦点抢进网格。
                                    selectPage(index)
                                },
                                // 确定键保留（兼容原有按键习惯）。
                                onConfirm = { selectPage(index) },
                                onMoveUp = { focusFlag(focusedFlag) },
                                onMoveDown = { enterGrid() },
                                onMoveLeft = { focusControl(controlIndex - 1) },
                                onMoveRight = { focusControl(controlIndex + 1) },
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(DIALOG_CONTROL_GAP))

                // ---- 固定 4 排网格：高度写死、userScrollEnabled = false，翻组只走边缘按键 ----
                if (pageItems.isEmpty()) {
                    // 线路存在但没有任何集数：显示提示，不弹空网格，也绝不把整部剧判成「无可用剧集」。
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(EPISODE_GRID_HEIGHT),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            // 全部线路过滤后都没集数 → 这个源只给了动作指令；
                            // 只有当前线路为空 → 单条线路没集数。
                            text = if (hasPlayableEpisode) "该线路暂无集数" else "该源无可用剧集",
                            color = Color(0x99FFFFFF),
                            fontSize = DIALOG_SECTION_TITLE_SP.sp,
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columns),
                        userScrollEnabled = false,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(EPISODE_GRID_HEIGHT)
                            // 边界切组统一在网格层拦截：格子本身不处理方向键。
                            .onKeyEvent { event ->
                                event.type == KeyEventType.KeyDown && handleGridKey(event.key)
                            },
                        horizontalArrangement = Arrangement.spacedBy(EPISODE_GRID_GAP),
                        verticalArrangement = Arrangement.spacedBy(EPISODE_GRID_GAP),
                    ) {
                        pageItems.forEachIndexed { index, episode ->
                            item(key = index) {
                                HeroEpisodeCell(
                                    // 原版推荐文案：`desc + name`（TMDB 刮削后是「S01E02 标题」，普通源就是集名）。
                                    label = episode.getDisplayName(),
                                    playing = episode === playingEpisode,
                                    // 动作指令（媒体信息 / 全局搜索 / 稍后再看 / 取消追剧）已在 `lineEpisodes`
                                    // 里被过滤掉，这里的灰显只作兜底（未来若有别的入口绕过过滤）。
                                    actionOnly = episode.isActionCommand(),
                                    requester = cellRequesters[index],
                                    onFocused = {
                                        anchorCell = index
                                        anchorEpisode = episode
                                        // 焦点进网格：线路 / 分组保持红框（规则 ②）。
                                        contentFocused = true
                                    },
                                    onConfirm = {
                                        // 【关键防线】动作指令绝不能进起播：本站没有这套动作的分发器，
                                        // 送进 VideoActivity 只会拿 `msubinfo-...` 当 URL 请求，必然失败。
                                        if (episode.isActionCommand()) {
                                            Log.e(
                                                TAG_DETAIL,
                                                "动作集数不可播放: name=${episode.getName()} url=${episode.getUrl()} " +
                                                    "flag=${currentFlag.getFlag()}",
                                            )
                                            Notify.show("该线路仅用于元数据操作")
                                        } else {
                                            playingEpisode = episode
                                            // 直接把真实 (线路, 集) 交给详情页，由它调用原版起播入口。
                                            // TODO(接入历史库)：这里补 History.push，目前只负责起播。
                                            onEpisodeSelected(currentFlag, episode)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 播放线路项：32dp 高 / 14sp（`ref_docs/adapter_v2_site.xml` 的 39dp 按需求压到 32dp）。
 *
 * 三态（《焦点总结》）：
 * - 聚焦 → [HeroFocusBrush] 红渐变色块 + 白字；
 * - 失焦但仍是当前线路、且焦点在下半区 → 红框描边 + 弱底（规则 ②）；
 * - 其余 → 常态深灰底白字（规则 ③）。
 */
@Composable
private fun HeroFlagChip(
    label: String,
    selected: Boolean,
    moduleFocused: Boolean,
    requester: FocusRequester,
    onFocused: () -> Unit,
    onConfirm: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val isFocused by interaction.collectIsFocusedAsState()
    val selectedRing = selected && !isFocused && moduleFocused

    Box(
        modifier = Modifier
            .height(DIALOG_FLAG_ROW_HEIGHT)
            .focusRequester(requester)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionUp -> {
                        onMoveUp()
                        true
                    }

                    Key.DirectionDown -> {
                        onMoveDown()
                        true
                    }

                    Key.DirectionLeft -> {
                        onMoveLeft()
                        true
                    }

                    Key.DirectionRight -> {
                        onMoveRight()
                        true
                    }

                    else -> false
                }
            }
            .clip(DIALOG_FLAG_SHAPE)
            // 底色只表达「是否是当前线路」：当前线路弱亮，其余深灰。
            // 焦点态下面再盖一层不透明红渐变，完全盖住这个底色。
            .background(if (selected) DIALOG_FLAG_SELECTED_BG else DIALOG_FLAG_IDLE_BG)
            // 焦点态用全局红渐变整块填充（实心块统一色）。
            .then(
                if (isFocused) {
                    Modifier.background(HeroFocusBrush, DIALOG_FLAG_SHAPE)
                } else {
                    Modifier
                },
            )
            .then(
                if (selectedRing) {
                    Modifier.border(DIALOG_FLAG_RING_STROKE, HeroAccentColor, DIALOG_FLAG_SHAPE)
                } else {
                    Modifier
                },
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onConfirm)
            .padding(horizontal = DIALOG_FLAG_H_PADDING),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = DIALOG_FLAG_SP.sp,
            fontWeight = if (selected || isFocused) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

/**
 * 控制栏按钮：32dp 高 / 14sp（列数、排序、分组三处共用）。
 *
 * 与线路按钮同一套三态：聚焦 → 红渐变色块；失焦但仍选中且焦点在下半区 → 红框；
 * 其余 → 深灰底白字（规则 ②③）。
 */
@Composable
private fun HeroToggleButton(
    label: String,
    requester: FocusRequester,
    onFocused: () -> Unit,
    onConfirm: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
    selected: Boolean = false,
    moduleFocused: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val isFocused by interaction.collectIsFocusedAsState()
    val selectedRing = selected && !isFocused && moduleFocused

    Box(
        modifier = Modifier
            .height(DIALOG_TOGGLE_HEIGHT)
            .focusRequester(requester)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionUp -> {
                        onMoveUp()
                        true
                    }

                    Key.DirectionDown -> {
                        onMoveDown()
                        true
                    }

                    Key.DirectionLeft -> {
                        onMoveLeft()
                        true
                    }

                    Key.DirectionRight -> {
                        onMoveRight()
                        true
                    }

                    else -> false
                }
            }
            .clip(DIALOG_TOGGLE_SHAPE)
            .background(if (selected) DIALOG_TOGGLE_SELECTED_BG else DIALOG_TOGGLE_IDLE_BG)
            .then(
                if (isFocused) {
                    Modifier.background(HeroFocusBrush, DIALOG_TOGGLE_SHAPE)
                } else {
                    Modifier
                },
            )
            .then(
                if (selectedRing) {
                    Modifier.border(DIALOG_TOGGLE_RING_STROKE, HeroAccentColor, DIALOG_TOGGLE_SHAPE)
                } else {
                    Modifier
                },
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onConfirm)
            .padding(horizontal = DIALOG_TOGGLE_H_PADDING),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = DIALOG_TOGGLE_SP.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/**
 * 选集格子：高 34dp、16sp 居中、单行 `basicMarquee()` 防溢出。
 * 焦点态 = [HeroFocusBrush] 渐变红块 + `HeroAccentColor` 2dp 环；当前播放集红字。
 * 方向键不在这里处理：统一交给网格层的 `onKeyEvent`，保证边界切组判断只有一处。
 */
@Composable
private fun HeroEpisodeCell(
    label: String,
    playing: Boolean,
    // 动作指令集数（`msubinfo-...` 等）：不是播放地址，灰显 + 确定键只提示、不起播。
    actionOnly: Boolean,
    requester: FocusRequester,
    onFocused: () -> Unit,
    onConfirm: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val isFocused by interaction.collectIsFocusedAsState()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(EPISODE_CELL_HEIGHT)
            .focusRequester(requester)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .clip(EPISODE_CELL_SHAPE)
            .background(
                when {
                    // 动作项永远不亮焦点红块：它不能被误认为「可播放」。
                    // 焦点态与其他实心块共用首页 Tab 同源的 [HeroFocusBrush]。
                    isFocused && !actionOnly -> HeroFocusBrush
                    actionOnly -> SolidColor(EPISODE_CELL_ACTION_BACKGROUND)
                    else -> SolidColor(EPISODE_CELL_BACKGROUND)
                },
            )
            .then(
                if (isFocused && !actionOnly) {
                    Modifier.border(EPISODE_CELL_BORDER, HeroAccentColor, EPISODE_CELL_SHAPE)
                } else {
                    Modifier
                },
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onConfirm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = EPISODE_CELL_H_PADDING)
                // 超长剧集名不省略号硬截断，跑马灯滚动显示（adapter_episode.xml 的 marquee）。
                .basicMarquee(iterations = Int.MAX_VALUE),
            color = when {
                actionOnly -> EPISODE_CELL_ACTION_TEXT
                playing -> HeroAccentColor
                else -> Color.White
            },
            fontSize = EPISODE_CELL_SP.sp,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 在已挂载的 FocusRequester 列表里安全请求焦点：未组合的 Lazy 项会抛异常，这里降级返回 false。
 */
private fun requestDetailFocus(requesters: List<FocusRequester>, index: Int): Boolean {
    val requester = requesters.getOrNull(index) ?: return false
    return runCatching { requester.requestFocus() }.isSuccess
}

/** 按钮组的左右移动：统一走这里，成功后才更新记忆索引。 */
private fun focusAction(requesters: List<FocusRequester>, index: Int, onFocused: (Int) -> Unit) {
    if (requestDetailFocus(requesters, index)) onFocused(index)
}

/**
 * 演职员行移动：只负责请求焦点。
 * 记忆索引由每个条目的 `onFocusChanged` 维护（Lazy 项未组合时 request 会失败，忽略即可）。
 */
private fun focusCast(requesters: List<FocusRequester>, index: Int) {
    requestDetailFocus(requesters, index)
}

/**
 * 控制栏下标 → 焦点：0 = 列数，1 = 排序，2 起为分组按钮。
 * 未组合的分组按钮（LazyRow 里滑出可视区）会抛异常，这里降级返回 false 由调用方重试。
 */
private fun requestControlFocus(
    columnsRequester: FocusRequester,
    sortRequester: FocusRequester,
    groupRequesters: List<FocusRequester>,
    index: Int,
): Boolean = when (index) {
    CONTROL_COLUMNS -> runCatching { columnsRequester.requestFocus() }.isSuccess
    CONTROL_SORT -> runCatching { sortRequester.requestFocus() }.isSuccess
    else -> requestDetailFocus(groupRequesters, index - CONTROL_GROUP_START)
}

/** 按钮组定义：图标、图标尺寸、文案全部集中在这里，新增按钮只改这个列表。 */
private data class HeroDetailAction(
    val label: String,
    val iconRes: Int,
    val iconSize: Dp,
)

/**
 * 演职员：稳定 key + 姓名 + 头像地址。
 *
 * 真实入口下三个字段全部来自 TMDB（[HeroDetail.tmdbPersons]），[id] 由 personId + 下标拼成，
 * 只服务 `LazyRow` 的稳定 key（同一演员在同一影片里出现两次也不会撞 key）；
 * 点击时改用下标去 [HeroDetail.tmdbPersons] 取完整 `TmdbPerson` 交给人物页。
 * 旧 adb 入口仍是 picsum 占位数据。
 */
private data class HeroCast(val id: String, val name: String, val avatarUrl: String)

/**
 * 详情加载状态：四态互斥，UI 与播放按钮都按它分支。
 *
 * - [Loading]：请求还在路上（或正在重试）；
 * - [Ready]：拿到了至少一条线路（`episodes` 可以为空，选集弹窗会提示「该线路暂无集数」）；
 * - [Empty]：接口成功返回但确实没有可播剧集，或 ID 为空 / 需要特殊解析（临时 ID）；
 * - [Error]：请求失败 / 接口没返回数据（`Result.list` 为空），必须和 [Empty] 区分开。
 */
private enum class DetailState { Loading, Ready, Empty, Error }

/**
 * TMDB 元数据加载状态（与 [DetailState] 完全独立：TMDB 失败绝不影响详情 / 选集 / 起播）。
 *
 * - [Idle]：没有 Key、本页源被排除、或旧 adb 入口（无 vodId）——不展示任何 TMDB 内容；
 * - [Loading]：请求在途（UI 仍显示源站数据，不阻塞首帧）；
 * - [Ready]：[manual] 为 true 表示命中来自手动匹配缓存，只有这种情况才允许覆盖剧名；
 * - [Error]：未命中 / 鉴权失败 / 网络失败——同样不改变页面数据，只是不展示 TMDB 内容。
 */
private sealed class TmdbDetailState {
    object Idle : TmdbDetailState()
    object Loading : TmdbDetailState()

    class Ready(
        val item: TmdbItem,
        val detail: JsonObject,
        val persons: List<TmdbPerson>,
        val manual: Boolean,
    ) : TmdbDetailState()

    class Error(val message: String) : TmdbDetailState()
}

private data class HeroDetail(
    val title: String,
    val meta: String,
    val synopsis: String,
    val posterUrl: String,
    val backdropUrl: String,
    /** TMDB 艺术字 Logo；null / 空串时回落 32sp 纯文字标题。 */
    val logoUrl: String?,
    val cast: List<HeroCast>,
    /** 真实详情里的导演 / 主演；旧入口或接口没返回时为空，UI 自动隐藏这一行。 */
    val director: String = "",
    val actor: String = "",
    /** TMDB 评分文案（如 `8.6` / `8.6 · 7.9`）：已并入 [meta] 尾段，这里保留原值便于排查。 */
    val rating: String = "",
    /** TMDB 条目 id；0 = 未命中（阶段 2 写缓存 / 跳 TMDB 详情要用）。 */
    val tmdbId: Int = 0,
    /** `movie` / `tv`；空串 = 未命中。 */
    val tmdbMediaType: String = "",
    /** 演员点击载荷：与 [cast] 同序同长，直接交给 `TmdbPersonActivity.start`。 */
    val tmdbPersons: List<TmdbPerson> = emptyList(),
    /**
     * 是否拿到了 TMDB 背景大图（横图）。
     *
     * 原版视觉规则：有背景大图时隐藏右上角浮动小海报，只有「没有背景大图」才用 posterCard 兜底。
     * 默认 false = 照常显示小海报（无 Key / 未命中 / TMDB 无 backdrop 时与改动前完全一致）。
     */
    val hasTmdbBackdrop: Boolean = false,
    /**
     * 当前 [backdropUrl] 是否已经是「横图」（首页预取的 `extra_backdrop` 或 TMDB backdrop）。
     *
     * 详情接口回填（[mergeDetail]）不碰背景，只有 TMDB 真横图（[mergeTmdb]）能替换它；
     * 源站竖图只进右上角小海报。为 true 时 TMDB 没有 backdrop 也不把海报从小海报换成大背景，
     * 避免第二帧「小海报从无到有」地冒出来。
     */
    val hasWideBackdrop: Boolean = false,
    /**
     * 当前 [synopsis] 是否已经是 TMDB 译文（首页预取随 Intent 带来的 `extra_overview`）。
     *
     * 为 true 时 [mergeDetail] 不再用源站 `vod.content` 覆盖简介：用户点进来看到的就是 TMDB 简介，
     * 详情接口回来再换成源站短简介、等 TMDB 再换回去，会明显闪一次。
     */
    val hasTmdbOverview: Boolean = false,
) {
    /** 导演 / 主演单行文案；两者都为空时返回空串（不占位、不显示）。 */
    fun creditLine(): String = buildString {
        if (director.isNotBlank()) append("导演：").append(director)
        if (actor.isNotBlank()) {
            if (isNotEmpty()) append("　")
            append("主演：").append(actor)
        }
    }
}

/**
 * 详情接口返回后，用真实 [Vod] 覆盖首帧的 Intent 数据。
 *
 * 只覆盖接口真的给了的字段：空字段保留 Intent 里的值，避免「接口缺 year 就把年份抹掉」。
 * 导演 / 主演先以文字行展示真实值；演职员头像行不在本函数处理，由 [mergeTmdb] 填 TMDB 真演员。
 */
private fun HeroDetail.mergeDetail(vod: Vod?, siteName: String): HeroDetail {
    if (vod == null || vod.id.isBlank()) return this
    val realMeta = listOf(
        vod.year,
        vod.area,
        vod.typeName,
        vod.remarks,
        vod.siteName.orEmpty().ifBlank { siteName },
    ).filter { it.isNotBlank() }.joinToString(" · ")
    return copy(
        title = vod.name.ifBlank { title },
        meta = realMeta.ifBlank { meta },
        // 【锁死简介】首帧已经是首页预取的 TMDB 译文时，不再用源站 `vod.content` 覆盖，
        // 否则「TMDB 简介 → 源站短简介 → TMDB 简介」会明显闪两次。
        synopsis = if (hasTmdbOverview) synopsis else vod.content.ifBlank { synopsis },
        posterUrl = vod.pic.ifBlank { posterUrl },
        // 【竖图永不铺满】源站详情回来的 `vod.pic` 是竖版海报，只能进右上角小海报（上一行），
        // **不能**再写进全屏背景：没有 TMDB 横图时保持现有值（可能是空串，由底层纯黑 Box 兜底）。
        //
        // 改动前这里会把 `vod.pic` 写成背景，于是「首帧黑底 → 详情回来竖图铺满 → TMDB 回横图」
        // 要闪两次；现在首帧与详情帧都不碰竖图，只有 TMDB 真横图才会替换背景。
        // `hasWideBackdrop` 只在首帧 / TMDB 拿到真横图时为 true，`mergeTmdb` 据此继续锁死横图。
        director = vod.director,
        actor = vod.actor,
    )
}

/**
 * TMDB 数据合并：**必须**基于 [mergeDetail] 的结果执行（调用方每次都从
 * `detail.mergeDetail(...)` 重算，所以评分只会追加一次，不会随重组叠加）。
 *
 * 字段策略：
 * - 剧名：只有「手动匹配命中」才覆盖，自动匹配保留源站名（源站名是用户点进来的那部剧的证据）；
 * - 简介：TMDB 译文非空且**比现有更长**才覆盖（照抄 `TmdbUIAdapter.enrichVod` 的长度策略）；
 *   首帧若已带首页预取的 TMDB 简介，`mergeDetail` 已保证不会先被源站简介盖掉；
 * - 背景：TMDB 有值且**不是同一张图**才覆盖（同一张只差档位时保留首帧那份，见 `keepBackdrop`）；
 *   海报同理，空值保留详情接口给的图；
 * - 评分：并入 [meta] 尾段，不新增控件；演员：整份替换，与 [tmdbPersons] 同序同长；
 * - `logoUrl`：TMDB 有 Logo 才替换 32sp 文字标题，没有就保持原值（回落文字）。
 *
 * 手动命中已由调用方算好并通过 [manualHit] 传入，本函数不再重复查缓存。
 */
private fun HeroDetail.mergeTmdb(
    item: TmdbItem?,
    detail: JsonObject?,
    persons: List<TmdbPerson>,
    config: TmdbConfig,
    manualHit: Boolean,
): HeroDetail {
    if (item == null) return this
    val rating = TmdbRatingFormatter.format(item)
    // 合并函数在组合期执行：TMDB JSON 结构异常时不能把整页炸掉，失败就当没有简介。
    val tmdbSynopsis = runCatching { TmdbService().translatedOverview(detail, config) }
        .getOrNull()
        .orEmpty()
        .ifBlank { item.overview.orEmpty() }
    val mergedMeta = when {
        rating.isBlank() -> meta
        meta.isBlank() -> rating
        else -> "$meta · $rating"
    }
    val tmdbBackdrop = item.backdropUrl.orEmpty()
    // 【锁死背景】同一张图（只差 w780 / w1920 档位）不重新赋值：字符串不变 → Coil 认为 model
    // 没变、不会重新加载，也就没有闪烁；同时不会把首页预热好的 w1920 降回详情默认的 w780。
    val keepBackdrop = tmdbBackdrop.isBlank() || sameTmdbImage(tmdbBackdrop, backdropUrl)
    return copy(
        title = if (manualHit) item.title.orEmpty().ifBlank { title } else title,
        meta = mergedMeta,
        synopsis = if (tmdbSynopsis.length > synopsis.length) tmdbSynopsis else synopsis,
        posterUrl = item.posterUrl.orEmpty().ifBlank { posterUrl },
        backdropUrl = if (keepBackdrop) backdropUrl else tmdbBackdrop,
        // 只有「确实有背景大图」才隐藏右上角小海报：TMDB 自己给了 backdrop，或首帧已经是
        // 首页带来的横图（`hasWideBackdrop`）。后者必须一起认：否则 TMDB 没有 backdrop 时，
        // 第二帧会把小海报从无到有「冒」出来，和进来时的无小海报状态对不上。
        hasTmdbBackdrop = tmdbBackdrop.isNotBlank() || hasWideBackdrop,
        // 一旦拿到横图（首页 / TMDB）就长期为 true：源站详情永远不能用竖图把背景盖回去。
        hasWideBackdrop = hasWideBackdrop || tmdbBackdrop.isNotBlank(),
        // 艺术字 Logo：TMDB `images.logos` 里优先中文，命中就替换 32sp 文字标题；
        // 提取不到（无 logos / 结构异常 / path 为空）返回 null，`?: logoUrl` 保持原来的 null 回落文字。
        logoUrl = extractTmdbLogo(detail, config.getImageBase()) ?: logoUrl,
        cast = persons.mapIndexed { index, person ->
            HeroCast(
                // 下标入 key：同一演员被判给多个角色时 personId 可能重复，LazyRow 的 key 必须唯一。
                id = "${person.personId}-$index",
                name = person.name.orEmpty(),
                // 与首页 `preloadHeroAvatars` 用同一表达式：URL 必须逐字一致，预热才能命中磁盘缓存。
                avatarUrl = upgradeTmdbImage(person.profileUrl.orEmpty(), "w342"),
            )
        },
        rating = rating,
        tmdbId = item.tmdbId,
        tmdbMediaType = item.mediaType.orEmpty(),
        tmdbPersons = persons,
    )
}

/**
 * 两张 TMDB 图是否指向同一张原图：`/t/p/w1920/abc.jpg` 与 `/t/p/w780/abc.jpg` 只有档位不同。
 *
 * 用于 [mergeTmdb] 的背景锁：同一张图不重新赋值，既避免 Coil 重新加载闪一下，
 * 也避免把首页预热的 w1920 降回 w780。非 TMDB 地址只认完全相同。
 */
private fun sameTmdbImage(left: String, right: String): Boolean {
    if (left.isBlank() || right.isBlank()) return false
    if (left == right) return true
    val leftPath = tmdbImagePath(left)
    val rightPath = tmdbImagePath(right)
    return leftPath.isNotBlank() && leftPath == rightPath
}

/** 取 `/t/p/<size>/` 之后的路径（含前导 `/`）；非 TMDB 图床返回原串（只允许全等匹配）。 */
private fun tmdbImagePath(url: String): String {
    val marker = "/t/p/"
    val markerIndex = url.indexOf(marker)
    if (markerIndex < 0) return url
    val sizeEnd = url.indexOf('/', markerIndex + marker.length)
    if (sizeEnd < 0) return url
    return url.substring(sizeEnd)
}

/**
 * 从 TMDB 详情 JSON 的 `images.logos` 里取艺术字 Logo（透明 PNG）。
 *
 * 优先级与 `TmdbService.detail` 的 `include_image_language` 对齐：
 * 1. `iso_639_1` 以 `zh` 开头的中文 Logo（zh-CN / zh-TW / zh-HK …）；
 * 2. 没有中文就退到第一张有 `file_path` 的（通常是英文 / 原语种）。
 *
 * **不抛异常**：`images` / `logos` 缺失、类型不对、值为 JSON null、`file_path` 为空、
 * `imageBase` 为空时一律返回 null，调用方 `?: logoUrl` 继续走文字标题回落。
 * 返回值是拼好 `imageBase` 的**完整绝对地址**，可直接交给 Coil。
 *
 * `internal`（而不是 private）：首页预匹配也要用同一套规则给信息区提 Logo，
 * 两处各写一份必然漂移，这里作为唯一实现。函数本身无状态、不抛异常，可跨协程并发调用。
 */
internal fun extractTmdbLogo(detail: JsonObject?, imageBase: String): String? {
    if (detail == null || imageBase.isBlank()) return null
    // `as?` 一次性挡住「键不存在」「值是 JSON null」「值不是对象 / 数组」三种情况，不会抛异常。
    val images = detail.get("images") as? JsonObject ?: return null
    val logos = images.get("logos") as? JsonArray ?: return null

    /** 取字符串字段：非 primitive（含 JSON null）或异常时返回空串。 */
    fun text(obj: JsonObject, key: String): String {
        val element = obj.get(key) ?: return ""
        if (!element.isJsonPrimitive) return ""
        return runCatching { element.asString }.getOrDefault("")
    }

    // 先过滤成对象列表，两轮扫描共用，避免每张图重复做类型判断。
    val items = logos.mapNotNull { it as? JsonObject }
    // 1) 中文优先。
    for (item in items) {
        if (!text(item, "iso_639_1").startsWith("zh")) continue
        val path = text(item, "file_path")
        if (path.isNotBlank()) return imageBase + path
    }
    // 2) 没有中文 Logo：取第一张有 file_path 的。
    for (item in items) {
        val path = text(item, "file_path")
        if (path.isNotBlank()) return imageBase + path
    }
    return null
}

/**
 * 真实起播：只调用原版公开入口 [VideoActivity.startDirectFullscreen]，绝不自己拼 Intent、
 * 也不碰 PlayerManager / PlaybackActivity。
 *
 * 用「Fullscreen」变体而不是 [VideoActivity.startDirect]：leanback 的 `VideoActivity`
 * 默认是「左侧小窗 + 右侧选集」的嵌入式首屏，Hero UI 点集数要**直接全屏起播**。
 * 两种 flavor 的签名一致（手机版内部就是原 `startDirect`）。
 */
internal fun startEpisode(
    activity: Activity,
    siteKey: String,
    vod: Vod?,
    vodId: String,
    flag: Flag,
    episode: Episode,
) {
    val id = vodId.ifBlank { vod?.id.orEmpty() }
    if (id.isBlank()) {
        Notify.show("缺少剧集 ID，无法播放")
        return
    }
    if (episode.getUrl().isBlank()) {
        Notify.show("该集暂无播放地址")
        return
    }
    Log.d(TAG_DETAIL, "起播: site=$siteKey id=$id flag=${flag.getFlag()} 集=${episode.getName()}")
    VideoActivity.startDirectFullscreen(
        activity,
        siteKey,
        id,
        vod?.name.orEmpty(),
        vod?.pic.orEmpty(),
        vod?.remarks.orEmpty(),
        flag.getFlag(),
        episode.getName(),
        episode.getUrl(),
    )
}

/** 假数据：picsum 按 seed 返回稳定图片，不依赖 TMDB Key 即可验证 Coil 链路。 */
private val DEMO_DETAIL = HeroDetail(
    title = "后会无期",
    meta = "2026 · 剧情 · 全 50 集 · 9.1",
    // 故意写长，用来验证「简介最多 4 行 + 省略号」以及 440dp 的换行宽度。
    synopsis = "三个男人一路向西，在公路尽头重新认识了自己。他们带着各自的秘密出发，" +
        "却在无人区里把话说得比过去十年都清楚。有人在加油站丢了钱包，有人在沙漠里捡回一条狗，" +
        "还有人把最想说的话留到了最后一句。",
    posterUrl = "https://picsum.photos/seed/hero-detail-poster/300/420",
    backdropUrl = "https://picsum.photos/seed/hero-detail-backdrop/1280/720",
    // 演示「无 Logo」分支：显示 32sp 文字 + 跑马灯。
    // 要验证 Logo 分支，把下面换成 TMDB 的 logo 地址（`https://image.tmdb.org/t/p/w500/xxx.png`）即可。
    logoUrl = null,
    cast = listOf(
        "李海潮", "陈屿", "林晚舟", "周野", "苏见月",
        "顾云深", "沈青禾", "许知白", "叶南", "宋一诺",
    ).mapIndexed { index, name ->
        HeroCast(
            id = "hero-cast-$index",
            name = name,
            avatarUrl = "https://picsum.photos/seed/hero-cast-$index/120/120",
        )
    },
    // 线路 / 选集不再有假数据：真实数据由 `SiteViewModel.detailContent` 的 `Vod.flags` 提供。
    director = "",
    actor = "",
)

/** 按钮顺序按需求固定为 播放 | 换源 | 收藏。 */
private val DETAIL_ACTIONS = listOf(
    HeroDetailAction(label = "播放", iconRes = R.drawable.ic_widget_play, iconSize = 24.dp),
    HeroDetailAction(label = "换源", iconRes = R.drawable.ic_detail_change, iconSize = 20.dp),
    HeroDetailAction(label = "收藏", iconRes = R.drawable.ic_detail_keep_off, iconSize = 20.dp),
)

/** 全局左侧基准线：内容区左侧 padding 严格 24dp（原版详情页同值）。 */
private val HERO_PADDING = 24.dp

/** 详情页日志标签（请求 / 到达 / 起播各一条，排查「点了没反应」时先看这里）。 */
private const val TAG_DETAIL = "HeroDetail"

/** 「播放」在按钮组里的固定下标。 */
private const val DETAIL_ACTION_PLAY = 0

/** 「换源」在按钮组里的固定下标（关闭换源弹窗后焦点必须回到这里）。 */
private const val DETAIL_ACTION_CHANGE_SOURCE = 1

// ---------------------------------------------------------------------------
// 左下内容区（activity_detail.xml detailTop L62-190）
// ---------------------------------------------------------------------------
/** 内容区底部留白：防止最后一行演职员贴到屏幕边缘。 */
private val CONTENT_BOTTOM_PADDING = 32.dp
/** TMDB 艺术字 Logo：高 96dp（与首页 `HERO_LOGO_HEIGHT` 一致）、最宽 720dp。 */
private val LOGO_HEIGHT = 96.dp
private val LOGO_MAX_WIDTH = 720.dp
private const val DETAIL_TITLE_SP = 32f
private val DETAIL_TITLE_HEIGHT = 36.dp
private val DETAIL_TITLE_META_GAP = 2.dp
private val DETAIL_META_HEIGHT = 20.dp
private const val DETAIL_META_SP = 14f
private val DETAIL_META_COLOR = Color(0xCCFFFFFF)
/** 详情请求失败提示：淡红，和普通元数据区分开。 */
private val DETAIL_ERROR_COLOR = Color(0xFFFF8A80)
private val DETAIL_META_DESC_GAP = 4.dp
/** 简介最大宽度 440dp：每行约 30 个汉字，4 行刚好不喧宾夺主。 */
private val DETAIL_DESC_MAX_WIDTH = 440.dp
private const val DETAIL_DESC_MAX_LINES = 4
private const val DETAIL_DESC_SP = 12f
private const val DETAIL_DESC_LINE_HEIGHT_SP = 17f
private val DETAIL_DESC_COLOR = Color(0xBBFFFFFF)
private val DETAIL_ACTIONS_TOP_GAP = 12.dp
private val DETAIL_CAST_TOP_GAP = 16.dp
/** 真实导演 / 主演文字行：与简介同一视觉层级，字号略小于元数据。 */
private val DETAIL_CREDIT_TOP_GAP = 8.dp
private const val DETAIL_CREDIT_SP = 12f
/** 详情加载态转圈：小尺寸，不抢按钮组的视觉重心。 */
private val DETAIL_LOADING_SIZE = 16.dp
private val DETAIL_LOADING_GAP = 8.dp
private val DETAIL_LOADING_STROKE = 2.dp

// XML 阴影参数是 px 值，Compose 的 Shadow 同样按 px 解释，因此直接照抄。
private val DETAIL_TITLE_SHADOW = Shadow(
    color = Color(0xCC000000),
    offset = Offset(2f, 2f),
    blurRadius = 12f,
)
private val DETAIL_META_SHADOW = Shadow(
    color = Color(0xAA000000),
    offset = Offset(1.5f, 1.5f),
    blurRadius = 8f,
)
private val DETAIL_DESC_SHADOW = Shadow(
    color = Color(0xAA000000),
    offset = Offset(1.2f, 1.2f),
    blurRadius = 6f,
)

// ---------------------------------------------------------------------------
// 演职员区（Netflix 风格）：60dp 圆形头像 + 12sp 名字，可聚焦
// ---------------------------------------------------------------------------
private const val CAST_TITLE_SP = 14f
private val CAST_TITLE_GAP = 10.dp
private val CAST_AVATAR_SIZE = 60.dp
private val CAST_AVATAR_BORDER = 1.dp
private val CAST_AVATAR_BORDER_COLOR = Color(0x66FFFFFF)
private val CAST_AVATAR_BACKGROUND = Color(0xFF2A2A2A)
private val CAST_NAME_GAP = 6.dp
private const val CAST_NAME_SP = 12f
private val CAST_NAME_COLOR = Color(0xCCFFFFFF)
private val CAST_ITEM_WIDTH = 76.dp
private val CAST_ITEM_GAP = 12.dp

/** 焦点态：2dp 红圈 + 1.08 倍放大。 */
private val CAST_FOCUS_BORDER = 2.dp
private const val CAST_FOCUS_SCALE = 1.08f

// ---------------------------------------------------------------------------
// 演员详情弹窗（轻量骨架）
// ---------------------------------------------------------------------------
private const val ACTOR_DIALOG_WIDTH_FRACTION = 0.5f
private val ACTOR_DIALOG_AVATAR = 56.dp
private val ACTOR_DIALOG_AVATAR_GAP = 12.dp
private val ACTOR_DIALOG_HEADER_GAP = 16.dp
private val ACTOR_DIALOG_SPINNER = 18.dp
private const val ACTOR_DIALOG_NAME_SP = 18f
private const val ACTOR_DIALOG_BODY_SP = 14f
private const val ACTOR_DIALOG_BODY_LINE_HEIGHT = 22f
private const val ACTOR_DIALOG_HINT_SP = 12f
private val ACTOR_DIALOG_HINT_COLOR = Color(0x99FFFFFF)

/** 假接口延迟：2 秒，用来验证 Loading 态与「不阻塞遥控器」。 */
private const val ACTOR_PROFILE_FAKE_DELAY_MS = 2_000L

// ---------------------------------------------------------------------------
// 右上浮动海报 150dp × 210dp（activity_detail.xml posterCard L30-48）
// ---------------------------------------------------------------------------
private val POSTER_WIDTH = 150.dp
private val POSTER_HEIGHT = 210.dp
private val POSTER_TOP_GAP = 72.dp
private val POSTER_END_GAP = 80.dp
private val POSTER_INNER_PADDING = 4.dp
private val POSTER_SHAPE = RoundedCornerShape(12.dp)
private val POSTER_BACKGROUND = Color(0x33000000) // black_20

// ---------------------------------------------------------------------------
// 按钮组：高 36dp / 字号 13sp 加粗 / 间距 12dp（L125-189）
// ---------------------------------------------------------------------------
private val DETAIL_ACTION_HEIGHT = 36.dp
private const val DETAIL_ACTION_SP = 13f
private val DETAIL_ACTION_GAP = 12.dp
private val DETAIL_ACTION_ICON_GAP = 4.dp
private val DETAIL_ACTION_H_PADDING = 16.dp
private val DETAIL_ACTION_SHAPE = RoundedCornerShape(4.dp)
private val DETAIL_ACTION_IDLE_BACKGROUND = Color(0x1AFFFFFF) // white_10

// ---------------------------------------------------------------------------
// 统一弹窗外壳：外壳实现与常量已抽到 HeroCommonUi.HeroDialogShell（选集 / 换源共用）
// ---------------------------------------------------------------------------
private const val DIALOG_WIDTH_FRACTION = 0.82f

/**
 * 弹窗背后的自绘遮罩。
 *
 * Compose `Dialog` 不能像 `Window.setDimAmount()` 那样直接调窗口暗度（项目内也没有
 * `DialogWindowProvider` 先例），所以选集弹窗用这层全屏自绘遮罩代替窗口 dim。
 * 取 50%：与换源弹窗的 `DIM_AMOUNT = 0.5f`、工程既有
 * `TmdbDetailActivity 0.56` / `ChoiceDialog 0.58` 同一量级，背景必须能透出来，
 * 不能再回到 60% 那种「纯黑 + 纯红」的刺眼组合。
 *
 * 【色相】不再用纯黑 `0x80000000`：纯黑会把背景色相全部压成 0，看起来就是一块死黑。
 * 改成逆向 ref_docs/shape_dialog_source.xml 的 `#14171F` 同一色相
 * （面板底色 [HeroCommonUi] 的 `#9914171F` 也是它），亮度下降但色温保留，
 * 背景透出来的部分仍然是「被压暗的画面」而不是「黑布」。
 */
private val DIALOG_SCRIM = Color(0x8014171F)

// 选集弹窗本地偏好（SharedPreferences）：跨剧、退弹窗、杀进程都保持上次的列数与排序
private const val DIALOG_PREFS_NAME = "hero_detail_ui"
private const val DIALOG_PREF_FEW_COLUMNS = "episode_few_columns"
private const val DIALOG_PREF_DESCENDING = "episode_descending"

// 顶部「选集徽章 + 剧名」整行已按需求删除，弹窗第一行直接是「播放线路」

// 分区间距：标题下 8dp、区块之间 16dp、控制栏到网格 8dp
private val DIALOG_TITLE_GAP = 8.dp
private val DIALOG_SECTION_GAP = 16.dp
private val DIALOG_CONTROL_GAP = 8.dp
private const val DIALOG_SECTION_TITLE_SP = 15f

// 播放线路：32dp 高 / 14sp；深灰底白字，焦点红底白字
private val DIALOG_FLAG_ROW_HEIGHT = 32.dp
private val DIALOG_FLAG_GAP = 12.dp
private val DIALOG_FLAG_H_PADDING = 14.dp
private val DIALOG_FLAG_SHAPE = RoundedCornerShape(4.dp)
private const val DIALOG_FLAG_SP = 14f
private val DIALOG_FLAG_IDLE_BG = Color(0x1AFFFFFF)
private val DIALOG_FLAG_SELECTED_BG = Color(0x33FFFFFF)
// 规则 ②：失焦但仍选中时补的 1.5dp 红框，宽度与首页分类 Tab / 搜索页胶囊一致。
private val DIALOG_FLAG_RING_STROKE = 1.5.dp

// 控制栏按钮（列数 / 排序 / 分组）：32dp 高 / 14sp；深灰底白字，焦点红底白字
private val DIALOG_TOGGLE_HEIGHT = 32.dp
private val DIALOG_TOGGLE_H_PADDING = 14.dp
private val DIALOG_TOGGLE_SHAPE = RoundedCornerShape(4.dp)
private const val DIALOG_TOGGLE_SP = 14f
private val DIALOG_TOGGLE_IDLE_BG = Color(0x1AFFFFFF)
private val DIALOG_TOGGLE_SELECTED_BG = Color(0x33FFFFFF)
private val DIALOG_TOGGLE_RING_STROKE = 1.5.dp

/** 单行控制栏下标：0 = 列数、1 = 排序、2 起为分组按钮。 */
private const val CONTROL_COLUMNS = 0
private const val CONTROL_SORT = 1
private const val CONTROL_GROUP_START = 2

/** Lazy 项从请求焦点到真正拿到节点要几帧，多给几帧兜住首帧未 attach。 */
private const val FOCUS_RETRY_FRAMES = 10

// ---------------------------------------------------------------------------
// 选集网格：固定 4 排、格高 34dp、间距 8dp，高度写死且禁止内部滚动
// 组容量 = 4 排 × 当前列数 → 多列 16 集 / 少列 8 集，翻组只靠边缘按键
// ---------------------------------------------------------------------------
private const val EPISODE_GRID_ROWS = 4
private val EPISODE_GRID_GAP = 8.dp
private val EPISODE_CELL_HEIGHT = 34.dp

/** 固定 4 排的总高度：4 × 34dp + 3 × 8dp = 160dp，一行都不许裁切。 */
private val EPISODE_GRID_HEIGHT =
    EPISODE_CELL_HEIGHT * EPISODE_GRID_ROWS + EPISODE_GRID_GAP * (EPISODE_GRID_ROWS - 1)
private const val EPISODE_COLUMNS_MANY = 4
private const val EPISODE_COLUMNS_FEW = 2
private val EPISODE_CELL_SHAPE = RoundedCornerShape(6.dp)
private val EPISODE_CELL_BORDER = 2.dp
private val EPISODE_CELL_H_PADDING = 8.dp
private val EPISODE_CELL_BACKGROUND = Color(0x14FFFFFF)

// 动作指令集数（`msubinfo-...` 等）专用：底色更暗、字更灰，和「可播放的集数」一眼分开。
private val EPISODE_CELL_ACTION_BACKGROUND = Color(0x0AFFFFFF)
private val EPISODE_CELL_ACTION_TEXT = Color(0x66FFFFFF)
private const val EPISODE_CELL_SP = 16f
