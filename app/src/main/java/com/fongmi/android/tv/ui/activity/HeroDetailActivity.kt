package com.fongmi.android.tv.ui.activity

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import coil3.request.crossfade
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
import com.fongmi.android.tv.ui.helper.TmdbRatingFormatter
import com.fongmi.android.tv.ui.hero.ChangeSourceDialog
import com.fongmi.android.tv.ui.hero.EpisodeViewDialog
import com.fongmi.android.tv.ui.hero.HeroDetailCastAdapter
import com.fongmi.android.tv.ui.hero.HeroHorizontalGapDecoration
import com.fongmi.android.tv.ui.hero.HeroImageLoader
import com.fongmi.android.tv.ui.hero.HeroTmdbPrefetchCache
import com.fongmi.android.tv.ui.hero.SourceSelectionStore
import com.fongmi.android.tv.ui.hero.heroTmdbKey
import com.fongmi.android.tv.ui.hero.openHeroDetailOrFolder
import com.fongmi.android.tv.ui.hero.upgradeTmdbImage
import com.fongmi.android.tv.utils.Notify
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Hero 详情页（100% 纯 View）。
 *
 * 数据与副作用全部在 [HeroDetailStateManager]：result 通道分流、跨源兜底、folder 下探、
 * TMDB 元数据、收藏读写、弹窗状态都由它持有；本文件只负责
 * 「把状态渲染到 `activity_hero_detail_view.xml`」与「把遥控器事件接回状态机」。
 *
 * 视觉规格与 Compose 版逐项一致：
 * - 背景 / 海报 / Logo / 演职员头像走 Coil，参数与首页 `rememberHeroImageModel` 同源；
 * - 剧名 / 元数据 / 简介 / 导演主演 = [HeroDetail] 合并结果；
 * - 按钮组 36dp、13sp 加粗、4dp 圆角，焦点态用 selector（红渐变色块）；
 * - 演职员行 RecyclerView 横向，头像 60dp、焦点 2dp 红圈 + 1.08 放大。
 *
 * 焦点契约：
 * - 默认落在「播放」；←/→ 在按钮间移动；
 * - 「播放」按 ↓ → 演职员行（落在上次停留的演员）；演员行 ↑ → 回到「播放」；
 *   行内 ←/→ 横移，↓ 消费；首/末项端点消费；
 * - 点「播放」→ [EpisodeViewDialog]（纯 View Dialog）：选中集数直接起播；
 * - 点演员 → 命中 TMDB 人物时进 `TmdbPersonActivity`；旧 adb 假数据入口只保留回焦；
 * - 纯展示文本（剧名/元数据/简介）没有任何 focusable / clickable。
 */
class HeroDetailActivity : AppCompatActivity() {

    // ------------------------------------------------------------------
    // 数据
    // ------------------------------------------------------------------

    /** Activity 作用域的检索 ViewModel：进页预搜索 / 换源弹窗共用，`onDestroy` 里摘观察者并停搜索。 */
    private val siteViewModel: SiteViewModel by lazy {
        ViewModelProvider(this)[SiteViewModel::class.java]
    }

    private lateinit var state: HeroDetailStateManager

    /** Intent 首帧数据：详情 / TMDB 都在这份 base 上重新合并（绝不叠加）。 */
    private lateinit var initialDetail: HeroDetail

    private var vodId = ""
    private var siteKey = ""
    private var siteName = ""

    /** 传入 Vod 自带线路时非空：跳过详情请求，直接把这份线路交给选集弹窗。 */
    private var inlineVod: Vod? = null

    /** 后台预搜索：第一条结果决定「换源」按钮文案，也是「播放」兜底目标。 */
    private var preSearchVod: Vod? = null
    private var preSearchSite: Site? = null
    private var preSearchResults: List<Vod> = emptyList()

    private val preSearchObserver = Observer<Result?> { onPreSearchResult(it) }
    private val detailObserver = Observer<Result?> { result ->
        state.onDetailResult(result, preSearchVod, preSearchSite)
        render()
    }

    // ---- 弹窗 / 协程 ----
    private var episodeDialog: EpisodeViewDialog? = null
    private var sourceDialog: ChangeSourceDialog? = null
    private var tmdbJob: Job? = null
    private var tmdbKey: String? = null
    private var fallbackJob: Job? = null

    // ---- 渲染记忆（避免同一张图重复 load / 同一份列表重复 notify） ----
    private var focusedCast = 0
    private var castIds: List<String> = emptyList()
    private var renderedBackdrop: String? = null
    private var renderedPoster: String? = null
    private var renderedLogo: String? = null

    // ------------------------------------------------------------------
    // 视图
    // ------------------------------------------------------------------

    private lateinit var backdrop: ImageView
    private lateinit var posterCard: View
    private lateinit var poster: ImageView
    private lateinit var titleTextSlot: View
    private lateinit var titleText: TextView
    private lateinit var titleLogoSlot: View
    private lateinit var titleLogo: ImageView
    private lateinit var meta: TextView
    private lateinit var synopsis: TextView
    private lateinit var credit: TextView
    private lateinit var loadingRow: View
    private lateinit var stateText: TextView
    private lateinit var actionPlay: TextView
    private lateinit var actionChangeSource: TextView
    private lateinit var actionKeep: TextView
    private lateinit var castSection: View
    private lateinit var castList: RecyclerView
    private lateinit var castAdapter: HeroDetailCastAdapter

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(AndroidColor.BLACK))
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        HeroImageLoader.install()
        setContentView(R.layout.activity_hero_detail_view)
        bindViews()
        setupCastList()
        setupActions()

        // 真实详情主键：首页海报卡 / 换源弹窗已经把它塞进 Intent；旧入口（只有剧名）为空。
        val rawVodId = intent.getStringExtra(EXTRA_VOD_ID).orEmpty()
        // 原版 `VideoActivity.checkId()`：`push://` 是嗅探 / 推送专用地址，换成 PUSH 站点再走详情。
        val pushId = rawVodId.takeIf { it.startsWith(PUSH_PREFIX) }?.substring(PUSH_PREFIX.length)
        vodId = pushId ?: rawVodId
        // 换源弹窗要「排除当前源」：优先用这条数据自己的源，没有（旧入口）才回落到首页源。
        siteKey = if (pushId != null) {
            SiteApi.PUSH
        } else {
            intent.getStringExtra(EXTRA_SITE_KEY).orEmpty().ifBlank {
                VodConfig.get().getHome().key
            }
        }
        siteName = intent.getStringExtra(EXTRA_SITE_NAME).orEmpty()
        // 真实数据入口：首页海报卡 / 换源弹窗会把整条 Vod + Site 的关键字段塞进 Intent。
        initialDetail = readDetail(siteKey, vodId)
        // 传下来的整条 Vod：如果它自己已经带 flags（WebHome 内联源 / 聚合结果），就不用再请求。
        val passedVod = readVod()
        val inlinePlayable = passedVod != null && passedVod.hydrateInlineEpisodes()
        inlineVod = if (inlinePlayable) passedVod else null

        state = HeroDetailStateManager(
            activity = this,
            siteViewModel = siteViewModel,
            vodId = vodId,
            siteKey = siteKey,
            siteName = siteName,
            inlineVod = inlineVod,
        )

        // 【致命日志】传进来的 Vod 形状：搜索 / 首页通常是轻量结果（flagsSize=0），必须走详情请求。
        Log.e(
            TAG_DETAIL,
            "收到的 Vod: id=${passedVod?.id}, site=$siteKey, flagsSize=${passedVod?.flags?.size ?: 0}, " +
                "playableLines=${passedVod?.flags?.count { !it.episodes.isNullOrEmpty() } ?: 0}, " +
                "playUrlLen=${passedVod?.playUrl?.length ?: 0}, vodId=$vodId, inlinePlayable=$inlinePlayable, " +
                "vodSiteKey=${passedVod?.site?.key}, vodSiteApi=${passedVod?.site?.api}, " +
                "vodSiteJar=${passedVod?.site?.jar}, vodSiteExtLen=${passedVod?.site?.ext?.length ?: 0}",
        )

        // 观察者只创建一次：详情 / 播放兜底 / folder 下探三路共用 `result` 通道。
        siteViewModel.result.observeForever(detailObserver)

        // 【真实详情】进页就请求：结果写 `SiteViewModel.result`，由上面的观察者接收。
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
                val site = VodConfig.get().getSite(siteKey)
                Log.d(
                    TAG_DETAIL,
                    "请求详情: siteKey=$siteKey homeKey=${VodConfig.get().getHome().key} " +
                        "lookupKey=${site.key} name=${site.displayName} type=${site.type} " +
                        "api=${site.api} extLen=${site.ext.length} jar=${site.jar} id=$vodId",
                )
                if (site.isEmpty) {
                    Log.e(
                        TAG_DETAIL,
                        "站点配置里查不到 siteKey=$siteKey：详情请求必然返回空结果（api/ext/jar 全空）",
                    )
                }
                siteViewModel.detailContent(siteKey, vodId, true)
            }
        }

        // 【后台预搜索】换源按钮要显示「第一个搜到的源」，所以进页就并发搜一轮（`search` 通道）。
        startPreSearch(initialDetail.title, rawVodId)

        render()
        // 默认焦点落在「播放」：等首帧布局完成再请求，否则 requestFocus 会失败。
        actionPlay.post { if (!isFinishing && !isDestroyed) actionPlay.requestFocus() }
    }

    override fun onResume() {
        super.onResume()
        // 让按钮按数据库真实状态刷新：收藏页 / 其它入口改过数据库，回到本页必须跟着变。
        state.updateKeepButtonState()
        renderKeep()
    }

    override fun onDestroy() {
        siteViewModel.search.removeObserver(preSearchObserver)
        siteViewModel.result.removeObserver(detailObserver)
        // 离开详情页就停掉在途的预搜索，别让后台线程继续跑。
        siteViewModel.stopSearch()
        tmdbJob?.cancel()
        fallbackJob?.cancel()
        episodeDialog?.dismiss()
        episodeDialog = null
        sourceDialog?.dismiss()
        sourceDialog = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // 视图绑定 / 事件
    // ------------------------------------------------------------------

    private fun bindViews() {
        backdrop = findViewById(R.id.backdrop)
        posterCard = findViewById(R.id.posterCard)
        poster = findViewById(R.id.poster)
        titleTextSlot = findViewById(R.id.titleTextSlot)
        titleText = findViewById(R.id.titleText)
        titleLogoSlot = findViewById(R.id.titleLogoSlot)
        titleLogo = findViewById(R.id.titleLogo)
        meta = findViewById(R.id.meta)
        synopsis = findViewById(R.id.synopsis)
        credit = findViewById(R.id.credit)
        loadingRow = findViewById(R.id.loadingRow)
        stateText = findViewById(R.id.stateText)
        actionPlay = findViewById(R.id.actionPlay)
        actionChangeSource = findViewById(R.id.actionChangeSource)
        actionKeep = findViewById(R.id.actionKeep)
        castSection = findViewById(R.id.castSection)
        castList = findViewById(R.id.castList)
    }

    private fun setupCastList() {
        castAdapter = HeroDetailCastAdapter(
            onFocused = { index -> focusedCast = index },
            onClick = { index -> onCastClicked(index) },
            onMoveUp = { actionPlay.requestFocus() },
        )
        castList.apply {
            layoutManager = LinearLayoutManager(this@HeroDetailActivity, RecyclerView.HORIZONTAL, false)
            adapter = castAdapter
            itemAnimator = null
            // 高度是 wrap_content（跟着演员项走），绝不能 hasFixedSize：否则 0 → N 项时不会重新测量。
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            clipChildren = false
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            addItemDecoration(HeroHorizontalGapDecoration(dp(12)))
        }
    }

    private fun setupActions() {
        // 图标尺寸与 Compose 版一致：播放 24dp，换源 / 收藏 20dp。
        setActionIcon(actionPlay, R.drawable.ic_widget_play, 24)
        setActionIcon(actionChangeSource, R.drawable.ic_detail_change, 20)
        setActionIcon(actionKeep, R.drawable.ic_detail_keep_off, 20)
        listOf(actionPlay, actionChangeSource, actionKeep).forEach { button ->
            button.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) {
                    false
                } else {
                    when (keyCode) {
                        // ↓ 进入最底部的演职员行，落在上次停留的演员；没有演员时不消费，交给系统搜索。
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (castAdapter.itemCount == 0) {
                                false
                            } else {
                                focusCast(focusedCast)
                                true
                            }
                        }

                        // ↑ 已到内容区顶部、直接消费。
                        KeyEvent.KEYCODE_DPAD_UP -> true
                        else -> false
                    }
                }
            }
        }
        actionPlay.setOnClickListener { onPlayClicked() }
        actionChangeSource.setOnClickListener { onChangeSourceClicked() }
        actionKeep.setOnClickListener {
            state.toggleKeep(this, state.detailResult?.vod, currentPage())
            renderKeep()
        }
    }

    private fun setActionIcon(button: TextView, iconRes: Int, sizeDp: Int) {
        val drawable = ContextCompat.getDrawable(this, iconRes) ?: return
        val size = dp(sizeDp)
        drawable.setBounds(0, 0, size, size)
        button.setCompoundDrawables(drawable, null, null, null)
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    /** 当前可渲染页面：Intent 首帧 → 详情接口 → TMDB（每次基于新 base 重算，评分不叠加）。 */
    private fun currentPage(): HeroDetail =
        state.buildPage(initialDetail, state.detailResult?.vod, state.tmdbState)

    private fun render() {
        val vod = state.detailResult?.vod
        val page = currentPage()
        bindTitle(page)
        meta.text = page.meta
        synopsis.text = page.synopsis
        val creditLine = page.creditLine()
        credit.text = creditLine
        credit.visibility = if (creditLine.isBlank()) View.GONE else View.VISIBLE
        bindImages(page)
        bindDetailState(vod)
        actionChangeSource.text = changeSourceLabel()
        renderKeep()
        bindCast(page)
        syncDialogs()
        syncFallbackTimeout()
        maybeLoadTmdb(vod)
    }

    /**
     * 剧名区：有 TMDB 艺术字走 96dp Logo，没有则 32sp 文字跑马灯。
     *
     * Logo 加载期间继续显示文字（Compose 版 SubcomposeAsyncImage 的 loading / error 槽），
     * 图片到达才隐藏文字，避免「先空一行、再出图」。
     */
    private fun bindTitle(page: HeroDetail) {
        titleText.text = page.title
        // 跑马灯需要 selected 状态才会滚动。
        titleText.isSelected = true
        val logo = page.logoUrl.orEmpty()
        if (logo.isBlank()) {
            renderedLogo = null
            titleLogoSlot.visibility = View.GONE
            titleTextSlot.visibility = View.VISIBLE
            return
        }
        titleLogoSlot.visibility = View.VISIBLE
        if (logo == renderedLogo) return
        renderedLogo = logo
        titleLogo.load(logo) {
            crossfade(300)
            listener(
                onSuccess = { _, _ -> titleTextSlot.visibility = View.GONE },
                onError = { _, _ -> titleTextSlot.visibility = View.VISIBLE },
            )
        }
    }

    /** 背景只认横图；右上角小海报在「有 TMDB 背景大图」时整块隐藏。 */
    private fun bindImages(page: HeroDetail) {
        if (page.backdropUrl != renderedBackdrop) {
            renderedBackdrop = page.backdropUrl
            if (page.backdropUrl.isBlank()) {
                backdrop.setImageDrawable(null)
            } else {
                backdrop.load(page.backdropUrl) { crossfade(800) }
            }
        }
        val showPoster = !page.hasTmdbBackdrop
        posterCard.visibility = if (showPoster) View.VISIBLE else View.GONE
        if (showPoster && page.posterUrl != renderedPoster) {
            renderedPoster = page.posterUrl
            if (page.posterUrl.isBlank()) {
                poster.setImageDrawable(null)
            } else {
                poster.load(page.posterUrl) { crossfade(300) }
            }
        }
    }

    /** 加载中 / 请求失败 / 确实没有剧集 / 需要特殊解析：四种状态分开显示。 */
    private fun bindDetailState(vod: Vod?) {
        val detailState = detailState(vod)
        val loading = detailState == DetailState.Loading || state.playLoading
        loadingRow.visibility = if (loading) View.VISIBLE else View.GONE
        when {
            loading -> stateText.visibility = View.GONE
            detailState == DetailState.Error -> {
                val error = state.detailResult?.msg.orEmpty()
                // 与 Compose 版 DETAIL_ERROR_COLOR 同值：失败提示用淡红，和普通空态区分开。
                stateText.setTextColor(0xFFFF8A80.toInt())
                stateText.text = if (error.isBlank()) {
                    "详情加载失败，按「播放」重试"
                } else {
                    "详情加载失败：$error（按「播放」重试）"
                }
                stateText.visibility = View.VISIBLE
            }

            detailState == DetailState.Empty -> {
                // 与 Compose 版 DETAIL_META_COLOR 同值：空态是说明文字，不用告警色。
                stateText.setTextColor(0xCCFFFFFF.toInt())
                stateText.text = state.emptyHint
                stateText.visibility = View.VISIBLE
            }

            else -> stateText.visibility = View.GONE
        }
    }

    private fun bindCast(page: HeroDetail) {
        val cast = page.cast
        castSection.visibility = if (cast.isEmpty()) View.GONE else View.VISIBLE
        val ids = cast.map { it.id }
        if (ids != castIds) {
            castIds = ids
            castAdapter.submit(cast)
        }
        if (focusedCast > cast.lastIndex) focusedCast = 0
        // TMDB 演员列表整体替换占位数据后，旧的演员下标可能越界。
        state.clampActorDialogIndex(cast.size)
    }

    private fun renderKeep() {
        val checked = state.keepCheckedState.value
        actionKeep.text = if (checked) "已收藏" else "收藏"
        setActionIcon(
            actionKeep,
            if (checked) R.drawable.ic_detail_keep_on else R.drawable.ic_detail_keep_off,
            20,
        )
    }

    private fun changeSourceLabel(): String {
        val name = preSearchSite?.displayName.orEmpty()
        return if (name.isBlank()) "换源" else "换源 ($name)"
    }

    /** 与 Compose 版完全一致的四种状态判定：`flags` 优先，绝不把失败说成「没有剧集」。 */
    private fun detailState(vod: Vod?): DetailState {
        val flags = vod?.flags.orEmpty()
        val result = state.detailResult
        return when {
            flags.isNotEmpty() -> DetailState.Ready
            vodId.isBlank() -> DetailState.Empty
            state.special -> DetailState.Empty
            result == null -> DetailState.Loading
            result.list.isEmpty() -> DetailState.Error
            else -> DetailState.Empty
        }
    }

    // ------------------------------------------------------------------
    // 弹窗 / 兜底 / TMDB
    // ------------------------------------------------------------------

    private fun syncDialogs() {
        if (state.showPlayDialog && state.playFlags.isNotEmpty()) {
            if (episodeDialog != null) return
            // 跨源兜底成功提示：等选集弹窗真正拿到真实剧集后再提示。
            state.onPlayDialogShown()
            val episodeVod = state.playVod
            // 起播的源：本页详情 → 本页源；跨源兜底 → 预搜索命中的那个源。
            val episodeSiteKey = state.playSiteKey
            // 起播 id：优先用详情回包里的 id；回包没带时只有本页自己的源才能回落到页面主键。
            val episodeVodId = episodeVod?.id.orEmpty().ifBlank {
                if (episodeSiteKey == siteKey) vodId else ""
            }
            episodeDialog = EpisodeViewDialog(
                context = this,
                flags = state.playFlags,
                lastWatchedEpisodeIndex = -1,
                onEpisodeSelected = { flag, episode ->
                    state.showPlayDialog = false
                    startEpisode(this, episodeSiteKey, episodeVod, episodeVodId, flag, episode)
                },
                onDismiss = {
                    state.showPlayDialog = false
                    episodeDialog = null
                    restoreFocus()
                },
            ).also { it.show() }
            return
        }
        if (episodeDialog != null && !state.showPlayDialog) {
            episodeDialog?.dismiss()
            episodeDialog = null
        }
    }

    /** 预搜索完全没结果时最多等 10 秒，给一次明确反馈（对应 Compose 的 `LaunchedEffect`）。 */
    private fun syncFallbackTimeout() {
        if (state.pendingPlayFallback) {
            if (fallbackJob != null) return
            fallbackJob = lifecycleScope.launch {
                state.onFallbackTimeout()
                fallbackJob = null
                render()
            }
        } else {
            fallbackJob?.cancel()
            fallbackJob = null
        }
    }

    /**
     * TMDB 元数据：`vodId / siteKey / name / year` 变化才重新加载（对应 Compose 的 LaunchedEffect key）。
     * `loadTmdb` 内部已有 Ready / Loading 守卫，重复调用安全。
     */
    private fun maybeLoadTmdb(vod: Vod?) {
        val key = "$vodId|$siteKey|${vod?.name.orEmpty()}|${vod?.year.orEmpty()}"
        if (key == tmdbKey) return
        tmdbKey = key
        tmdbJob?.cancel()
        tmdbJob = lifecycleScope.launch {
            state.loadTmdb(vod)
            render()
        }
    }

    // ------------------------------------------------------------------
    // 点击
    // ------------------------------------------------------------------

    private fun onPlayClicked() {
        // 【唯一数据源】选集弹窗只认 `flags`；只有存在真实 http/m3u8 地址才算当前源可播。
        val flags = state.detailResult?.vod?.flags.orEmpty()
        val fallbackVod = preSearchVod
        val fallbackSite = preSearchSite
        val currentPlayable = flags.any { it.hasRealVideoUrl() }
        when {
            state.playLoading -> Notify.show("正在加载剧集，请稍候")

            // 1) 当前源有真实视频链接：直接弹自己的选集。
            currentPlayable -> {
                state.autoSwitchNotice = false
                state.playFlags = flags
                state.playVod = state.detailResult?.vod
                state.playSiteKey = siteKey
                state.showPlayDialog = true
                render()
            }

            // 2) 详情还在加载：等回包后按同一套「真实链接」规则再判断。
            detailState(state.detailResult?.vod) == DetailState.Loading -> {
                state.pendingPlayOpen = true
                state.playLoading = true
                Notify.show("正在加载剧集，请稍候")
                render()
            }

            // 3) 当前源不可播（动作项 / 伪集数 / 空地址）：改拉预搜索第一个结果。
            fallbackVod != null && fallbackSite != null && fallbackVod.id.isNotBlank() -> {
                Log.e(
                    TAG_DETAIL,
                    "当前源无真实视频链接，自动切换: site=${fallbackSite.key} " +
                        "id=${fallbackVod.id} 集数=${flags.sumOf { it.episodes.orEmpty().size }} " +
                        "真实链接线路=${flags.count { it.hasRealVideoUrl() }}",
                )
                state.autoSwitchNotice = true
                Notify.show("当前源不可播，正在切换可用源…")
                state.requestPlayTarget(fallbackVod, fallbackSite)
                render()
            }

            // 4) 预搜索还没回来：显示 Loading 等待，结果一到自动继续。
            else -> {
                state.pendingPlayFallback = true
                state.playLoading = true
                Log.e(TAG_DETAIL, "当前源无真实视频链接，等待预搜索结果…")
                Notify.show("正在寻找可用源，请稍候")
                render()
            }
        }
    }

    private fun onChangeSourceClicked() {
        val page = currentPage()
        // 候选源规则与首页完全一致：isSearchable + isChangeable + 非隐藏 + 未被屏蔽 + 非当前源。
        var candidates = ChangeSourceDialog.candidates(siteKey)
        if (candidates.isEmpty()) {
            // 【兜底】真实数据下候选源可能因为「当前源不可换 / 全被屏蔽」而空：退回全部可搜索源。
            candidates = VodConfig.get().getSites().filter { it.isSearchable }
        }
        if (candidates.isEmpty() || page.title.isBlank()) {
            Notify.show(getString(R.string.play_change_empty))
            return
        }
        state.pendingActionFocus = DETAIL_ACTION_CHANGE_SOURCE
        state.showSourceDialog = true
        // 打开换源弹窗 = 放弃这次「播放」等待：清掉在途的播放状态，
        // 否则弹窗里的详情回包会被本页误当成「选集数据」直接弹选集。
        state.playLoading = false
        state.pendingPlayOpen = false
        state.pendingPlayId = null
        state.pendingPlaySite = null
        state.pendingPlayVod = null
        state.pendingPlayDrill = 0
        state.pendingPlayCategory = null
        try {
            sourceDialog = ChangeSourceDialog.show(
                context = this,
                sites = candidates,
                keyword = page.title,
                quick = false,
                // 进页预搜索的结果直接铺开：弹窗首帧就有卡片，不再等一轮网络。
                initialResults = preSearchResults,
                onSourceSelected = { vod, site ->
                    Notify.show(
                        getString(
                            R.string.source_sidebar_switched,
                            "${site.displayName} · ${vod.name}",
                        ),
                    )
                    // 换源结果和首页点卡片完全一致：folder 条目走分类页，其余进详情页。
                    openHeroDetailOrFolder(this, vod, site)
                },
                onClosed = {
                    state.showSourceDialog = false
                    state.playLoading = false
                    sourceDialog = null
                    // 弹窗打开期间 result 通道归弹窗，本页的在途详情回包会被丢弃；
                    // 关窗后按自己的主键重拉一次：既恢复页面，也作废弹窗可能还在途的请求。
                    if (state.ownsResultChannel) {
                        Log.e(TAG_DETAIL, "换源弹窗关闭，重拉详情: site=$siteKey id=$vodId")
                        siteViewModel.detailContent(siteKey, vodId, false)
                    } else {
                        inlineVod?.let { state.detailResult = Result.vod(it) }
                    }
                    render()
                    restoreFocus()
                },
            )
        } catch (e: Throwable) {
            Log.e("ChangeSource", "弹窗呼出崩溃", e)
            state.showSourceDialog = false
            sourceDialog = null
            Notify.show("换源弹窗打开失败: ${e.message}")
        }
    }

    private fun onCastClicked(index: Int) {
        val person = currentPage().tmdbPersons.getOrNull(index)
        if (person != null) {
            // 命中 TMDB → 进 View 版人物页（首帧用 extras，随后按 personId 全量刷新）。
            TmdbPersonActivity.start(this, person, siteKey, Setting.getDetailOpenMode())
        } else {
            // 旧 adb 假数据入口：演员占位弹窗尚未迁移到 View，只保留「回到被点头像」的焦点语义。
            state.pendingCastFocus = index
            restoreFocus()
        }
    }

    // ------------------------------------------------------------------
    // 焦点
    // ------------------------------------------------------------------

    /**
     * 三个弹窗都关闭后才抢焦点：
     * - 刚关掉演员弹窗 → 回到被点的那位演员；
     * - 刚关掉换源弹窗 → 回到「换源」按钮；
     * - 否则（首次进入 / 关闭播放弹窗）→ 回到「播放」。
     *
     * 用 `postOnAnimation` 逐帧重试，替代 Compose 版的 `withFrameNanos`：弹窗刚关的那一帧
     * RecyclerView 项可能还没恢复可聚焦。
     */
    private fun restoreFocus() {
        if (state.showPlayDialog || state.showSourceDialog || state.actorDialogIndex != null) return
        val castTarget = state.pendingCastFocus
        state.pendingCastFocus = -1
        if (castTarget >= 0) {
            requestCastFocus(castTarget, 5)
            return
        }
        val actionTarget = state.pendingActionFocus
        state.pendingActionFocus = -1
        if (actionTarget >= 0) {
            requestActionFocus(actionTarget, 5)
            return
        }
        requestActionFocus(DETAIL_ACTION_PLAY, 3)
    }

    private fun focusCast(index: Int) = requestCastFocus(index, 1)

    private fun requestCastFocus(index: Int, retries: Int) {
        if (castAdapter.itemCount == 0) return
        val target = index.coerceIn(0, castAdapter.itemCount - 1)
        val manager = castList.layoutManager as? LinearLayoutManager
        val child = manager?.findViewByPosition(target)
        if (child != null && child.requestFocus()) return
        castList.scrollToPosition(target)
        if (retries <= 0) return
        castList.postOnAnimation { requestCastFocus(target, retries - 1) }
    }

    private fun requestActionFocus(index: Int, retries: Int) {
        val target = when (index) {
            DETAIL_ACTION_CHANGE_SOURCE -> actionChangeSource
            DETAIL_ACTION_KEEP -> actionKeep
            else -> actionPlay
        }
        if (target.requestFocus()) return
        if (retries <= 0) return
        target.postOnAnimation { requestActionFocus(index, retries - 1) }
    }

    // ------------------------------------------------------------------
    // 预搜索
    // ------------------------------------------------------------------

    private fun startPreSearch(keyword: String, rawVodId: String) {
        if (rawVodId.isBlank() || keyword.isBlank()) return
        // 1. 源集合 = 换源弹窗的候选源（排除当前源）：按钮上显示的必须是「点开换源真的能换过去的源」；
        // 2. quick=false：`quick=true` 会跳过 `quickSearch=0` 的源（大部分源都是这种）。
        val candidates = ChangeSourceDialog.candidates(siteKey)
        val searchable = candidates.ifEmpty {
            VodConfig.get().getSites().filter { it.isSearchable }
        }
        // 侧边栏勾选持久化：没勾的源连请求都不发（与换源弹窗的全局检索保持一致）。
        val checked = SourceSelectionStore.checkedSites(this, searchable)
        Log.e(
            TAG_DETAIL,
            "进页后台预搜索: keyword=[$keyword], 源=${searchable.size}, " +
                "勾选=${checked.size}, 候选源=${candidates.size}, quick=false",
        )
        if (checked.isEmpty()) {
            Log.e(TAG_DETAIL, "预搜索跳过: 侧边栏没有勾选任何源")
            return
        }
        siteViewModel.search.observeForever(preSearchObserver)
        siteViewModel.searchContent(checked, keyword, false)
    }

    /**
     * 预搜索结果：一个源回一条，**第一条结果就决定按钮上显示的源名**。
     *
     * 同一条剧可能在多个源都命中：按「源 key + 剧 id」去重，弹窗里不会出现重复卡。
     */
    private fun onPreSearchResult(result: Result?) {
        if (result == null) return
        val items = result.list
        // 第一条**有效**结果：有 id 且不是动作伪条目（`vod_tag=action` / 动作 JSON 卡）。
        val firstValid = items.firstOrNull { it.id.isNotBlank() && !it.isActionItem() }
        val site = firstValid?.site ?: items.firstOrNull()?.site
        if (site == null) {
            if (items.isNotEmpty()) {
                Log.w(TAG_DETAIL, "预搜索结果无法归因到源: listSize=${items.size}, 已丢弃")
            }
            return
        }
        Log.e(
            TAG_DETAIL,
            "预搜索结果: site=${site.key}(${site.displayName}) listSize=${items.size}",
        )
        preSearchResults = (preSearchResults + items)
            .distinctBy { vod -> "${vod.siteKey}:${vod.id}" }
        if (preSearchVod == null && firstValid != null) {
            preSearchVod = firstValid
            preSearchSite = site
        }
        actionChangeSource.text = changeSourceLabel()
        // 预搜索是异步的：当前源不可播、结果还没到时先登记等待，结果一到自动继续下探 / 拉详情。
        if (state.pendingPlayFallback) {
            state.onPreSearchReady(preSearchVod, preSearchSite)
            render()
        }
    }

    // ------------------------------------------------------------------
    // Intent → 首帧数据
    // ------------------------------------------------------------------

    /**
     * Intent → 首帧页面数据。
     *
     * 有 `EXTRA_PIC` 就说明是从首页 / 换源弹窗进来的真实条目：
     * 剧名、海报、元数据、简介先按 Intent 直出，详情接口回来后再由
     * [mergeDetail] 用真实 [Vod] 整体覆盖（含年份 / 地区 / 分类 / 简介 / 导演 / 主演）。
     *
     * 演职员：真实条目**不再**沿用 [DEMO_DETAIL] 的假演员（TMDB 到达前整行隐藏）；
     * 只有旧 adb 入口（既没有海报、也没有 `extra_vod_id`）才保留假数据用于离线验证。
     */
    private fun readDetail(siteKey: String, vodId: String): HeroDetail {
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { return DEMO_DETAIL }
        val pic = intent.getStringExtra(EXTRA_PIC).orEmpty()
        // 旧 adb 入口 = 「只有剧名」：既没有海报，也没有详情主键（`extra_vod_id`）。
        val demo = pic.isBlank() && intent.getStringExtra(EXTRA_VOD_ID).isNullOrBlank()
        // 首页已经预取到 TMDB w1920 横图时，把它作为**首帧背景**直出。
        val extraBackdrop = intent.getStringExtra(EXTRA_BACKDROP).orEmpty()
        // 只有「确实和竖图不同」才算横图：同值时仍按源站图处理，行为与改动前完全一致。
        val wideExtra = extraBackdrop.isNotBlank() && extraBackdrop != pic
        // 历史页 / 换源弹窗入口**不带** `extra_backdrop`：同步读一次本地缓存拿真横图；拿不到就留空。
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
            logoUrl = extraLogo.takeIf { it.isNotBlank() },
            // 首帧就已经是横图时直接判定「有背景大图」：否则右上角小海报会先渲染出来再消失。
            hasTmdbBackdrop = wideBackdrop,
            hasWideBackdrop = wideBackdrop,
            // 首帧简介已经是 TMDB 译文时，`mergeDetail` 不要再用源站 `vod.content` 盖掉它。
            hasTmdbOverview = extraOverview.isNotBlank(),
            // 真实条目清空假演员：TMDB 未配置 / 未命中时演员行为空（UI 整行隐藏）。
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
                // 手动匹配优先：与 `loadTmdb` 里的读取顺序完全一致。
                (cache.findManual(siteKey, vodId, title) ?: cache.find(siteKey, vodId, title))
                    ?.backdropUrl.orEmpty()
            }.getOrDefault("")
        }
        // 提档到 w1920：与首页 `highResBackdrop` 逐字一致，直接命中首页预热好的磁盘缓存。
        return upgradeTmdbImage(cached, TMDB_BACKDROP_WIDTH)
    }

    /** Intent 里的整条 [Vod]（Parcelable，`flags` / `episodes` 都在里面）。 */
    @Suppress("DEPRECATION")
    private fun readVod(): Vod? = intent.getParcelableExtra(EXTRA_VOD)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

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
        /** 首页预取到的 TMDB 艺术字 Logo（透明 PNG）：首帧直接出艺术字，不等第二轮网络。 */
        private const val EXTRA_LOGO = "extra_logo"
        /** 首页预取到的 TMDB 简介：首帧直接显示，不让简介先空着 / 先显示源站短简介。 */
        private const val EXTRA_OVERVIEW = "extra_overview"

        /**
         * 统一入口：首页海报卡确定键、换源弹窗选中后调用。
         *
         * [Vod] 本身是 Parcelable（`flags` / `episodes` 都随 Parcel 传递），所以整条一起带过去：
         * 详情页先看它有没有自带可播线路（WebHome 内联源 / 聚合结果），有就直接渲染、不发详情请求。
         * 标量字段仍然逐个塞进 Intent，供首帧直出和旧入口使用；[Site] 只带 key + 名称。
         *
         * [backdropUrl]：首页卡片当前显示的横版背景（TMDB 命中时是 w1920）。
         * 空串 = 没有横图，详情页照旧用 `vod.pic` 裁 16:9。
         * [logoUrl] / [overview]：首页已经预取到的 TMDB 艺术字与简介，空串 = 没有。
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
internal fun needsSpecialParse(vodId: String): Boolean =
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
internal const val MAX_PLAY_DRILL = 3

/** 当前源不可播、预搜索结果还没到时最多等这么久，避免「正在寻找可用源」无限转圈。 */
internal const val PLAY_FALLBACK_WAIT_MS = 10_000L

/**
 * 首帧横图的提档目标：与首页 `highResBackdrop` 同为 `w1920`。
 *
 * 逐字一致才能直接命中首页预热好的 Coil 磁盘缓存；换成 `w1280` / `original` 都要重新下载。
 */
private const val TMDB_BACKDROP_WIDTH = "w1920"

/** 网盘聚合插件的 folder 壳前缀（与 `ChangeSourceDialog` / `HeroEntryRoute` 的判定一致）。 */
private const val ATVP_FOLDER_PREFIX = "atvp_detail:"

/** 这条集数的地址是不是动作指令（`msubinfo-...`，兼容整串带前导 `$` 的写法）。 */
internal fun Episode.isActionCommand(): Boolean {
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
internal fun Flag.hasPlayableEpisode(): Boolean =
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
internal fun Flag.hasRealVideoUrl(): Boolean =
    episodes.orEmpty().any { it.hasRealVideoUrl() }

/**
 * 目录里的「动作伪条目」（`vod_tag=action` / 带动作 JSON 的卡片）。
 *
 * 网盘源的二级目录常混入「📄 媒体信息 / 🔍 全局搜索」这类条目，它们不是可播放文件；
 * 播放兜底下探时直接跳过，避免把动作卡当剧集继续请求。
 */
internal fun Vod.isActionItem(): Boolean =
    "action".equals(getTag(), ignoreCase = true) || getAction().isNotBlank()

/**
 * 这个目标是不是「目录」而不是剧集详情。
 *
 * 网盘聚合源（🍍快映等）在搜索结果里给的是 `atvp_detail:507769` 这种 folder 壳 id，
 * 它**只能**走 `categoryContent`；一旦拿去调 `detailContent`，插件会把整串壳 id
 * 当成上游详情主键转发，必然 500（日志里就是 `Connection refused`）。
 */
internal fun Vod.isFolderTarget(): Boolean =
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

/**
 * 在已挂载的 FocusRequester 列表里安全请求焦点：未组合的 Lazy 项会抛异常，这里降级返回 false。
 */
internal fun requestDetailFocus(requesters: List<FocusRequester>, index: Int): Boolean {
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
internal data class HeroCast(val id: String, val name: String, val avatarUrl: String)

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
internal sealed class TmdbDetailState {
    object Idle : TmdbDetailState()
    object Loading : TmdbDetailState()

    class Ready(
        val item: TmdbItem,
        val detail: JsonObject,
        val persons: List<TmdbPerson>,
        val manual: Boolean,
        /**
         * 艺术字 Logo 的完整地址；null = 这部剧没有 logo（回落文字标题）。
         *
         * 由 [loadTmdbLogo] 在 IO 线程算好带进来（**详情优先、`/images` 兜底**），
         * 不在 [mergeTmdb] 里现取：那是组合期 / 主线程，绝不能发网络请求。
         */
        val logo: String? = null,
    ) : TmdbDetailState()

    class Error(val message: String) : TmdbDetailState()
}

internal data class HeroDetail(
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
internal fun HeroDetail.mergeDetail(vod: Vod?, siteName: String): HeroDetail {
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
internal fun HeroDetail.mergeTmdb(
    item: TmdbItem?,
    detail: JsonObject?,
    persons: List<TmdbPerson>,
    config: TmdbConfig,
    manualHit: Boolean,
    /** [loadTmdbLogo] 在 IO 线程算好的艺术字地址；null = 这部剧没有 logo，保持原值（回落文字）。 */
    logo: String? = null,
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
        // 艺术字 Logo：由 [loadTmdbLogo] 在 IO 线程取好（详情优先、无语言过滤的 `/images` 兜底），
        // 这里只做「有就换、没有就保持原值（回落 32sp 文字）」。
        logoUrl = logo ?: logoUrl,
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
 * 从 TMDB 的图片 JSON 里取艺术字 Logo（透明 PNG），两种结构都吃：
 *
 * - 详情：`{ …, "images": { "logos": [ … ] } }`；
 * - `/{movie|tv}/{id}/images`：`{ "id": …, "logos": [ … ] }`（**根级**，没有 `images` 包一层）。
 *
 * 优先级与原版 `TmdbApi.r(json)` 完全一致：
 * 1. `iso_639_1` 以 `zh` 开头的中文 Logo（zh-CN / zh-TW / zh-HK …）；
 * 2. 没有中文就退到第一张有 `file_path` 的（通常是英文 / 原语种）。
 *
 * **不抛异常**：`logos` 缺失、类型不对、值为 JSON null、`file_path` 为空、
 * `imageBase` 为空时一律返回 null，调用方 `?: logoUrl` 继续走文字标题回落。
 * 返回值是拼好 `imageBase` 的**完整绝对地址**，可直接交给 Coil。
 *
 * `internal`（而不是 private）：首页预匹配也要用同一套规则给信息区提 Logo，
 * 两处各写一份必然漂移，这里作为唯一实现。函数本身无状态、不抛异常，可跨协程并发调用。
 */
internal fun extractTmdbLogo(detail: JsonObject?, imageBase: String): String? {
    if (detail == null || imageBase.isBlank()) return null
    // `as?` 一次性挡住「键不存在」「值是 JSON null」「值不是对象 / 数组」三种情况，不会抛异常。
    // 先按详情结构取 `images.logos`，取不到再按 `/images` 的根级 `logos` —— 只认一种就会漏掉另一半。
    val logos = (detail.get("images") as? JsonObject)?.get("logos") as? JsonArray
        ?: detail.get("logos") as? JsonArray
        ?: return null

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
 * 艺术字 Logo 的唯一取图入口（首页 / 详情页共用）。
 *
 * 顺序**与原版 `gd3.C()` → `TmdbApi.k(id, media)` 完全一致：详情优先、`/images` 兜底**。
 * 1. 详情 JSON 里就有 logo（`images.logos`）→ 直接用，**不再发第二条请求**。
 *    中文片走的就是这一支：详情只回一张 `zh` 主 logo，和原版截图里那张完全一致；
 * 2. 详情没有 logo（外文片的常态：`append_to_response=images` 只回当前语言那一档，
 *    `hu` / `it` / `ja` … 的原语种艺术字整批缺席）→ 再问一次**不带语言过滤**的
 *    `/images`（原版 `TmdbApi.k` 那条），从全量清单里按 `r()` 的规则挑。
 *
 * 实测（2026-11，`api.tmdb.org/3`）：tv/297253 详情 1 张 / `/images` 3 张；
 * movie/13912、14811 详情 **0 张** / `/images` 5~6 张 —— 少了兜底这一步，外文片永远只剩纯文字。
 *
 * `/images` 失败（网络 / 鉴权 / 结构异常）一律当作「没有」，继续用详情里那份：
 * 艺术字是装饰，绝不因为它把整页拖崩。协程取消照常抛出。
 */
internal fun loadTmdbLogo(service: TmdbService, item: TmdbItem, detail: JsonObject?, config: TmdbConfig): String? {
    val imageBase = tmdbLogoBase(config.getImageBase())
    extractTmdbLogo(detail, imageBase)?.let { return it }
    val images = try {
        service.images(item, config)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
    return extractTmdbLogo(images, imageBase)
}

/**
 * 艺术字图床档位：原版 `TmdbApi.r()` 拼的是 `zu1.C()`，即 **w500**。
 *
 * 我们的 [TmdbConfig.getImageBase] 是海报档 `w342`。logo 又宽又扁，按 72dp 高度铺开时
 * 宽度常常远超 342px，用 w342 会发虚（观感上就是「我们的艺术字更小更糊」）。
 * 只把比 w500 更小的标准档位提上来；`w780` / `w1280` / `original` 与用户自定义的
 * 非 TMDB 图床**原样返回**，绝不改写别人的地址。
 */
private fun tmdbLogoBase(imageBase: String): String {
    val marker = "/t/p/"
    val index = imageBase.indexOf(marker)
    if (index < 0) return imageBase
    val sizeStart = index + marker.length
    val slash = imageBase.indexOf('/', sizeStart)
    val sizeEnd = if (slash < 0) imageBase.length else slash
    if (imageBase.substring(sizeStart, sizeEnd) !in SMALL_LOGO_TIERS) return imageBase
    return imageBase.substring(0, sizeStart) + "w500" + imageBase.substring(sizeEnd)
}

/** 比 w500 小的标准档位（只有这些需要提档）。 */
private val SMALL_LOGO_TIERS = setOf("w45", "w92", "w154", "w185", "w300", "w342")

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
internal const val TAG_DETAIL = "HeroDetail"

/** 「播放」在按钮组里的固定下标。 */
internal const val DETAIL_ACTION_PLAY = 0

/** 「换源」在按钮组里的固定下标（关闭换源弹窗后焦点必须回到这里）。 */
private const val DETAIL_ACTION_CHANGE_SOURCE = 1

/** 「收藏」在按钮组里的固定下标（读写本地 Keep 表）。 */
private const val DETAIL_ACTION_KEEP = 2

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
