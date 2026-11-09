package com.fongmi.android.tv.ui.activity

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.dispose
import coil3.load
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.fongmi.android.tv.App
import com.fongmi.android.tv.R
import com.fongmi.android.tv.bean.History
import com.fongmi.android.tv.bean.TmdbItem
import com.fongmi.android.tv.bean.TmdbMatchCache
import com.fongmi.android.tv.event.RefreshEvent
import com.fongmi.android.tv.setting.Setting
import com.fongmi.android.tv.ui.custom.CustomTypeView
import com.fongmi.android.tv.ui.custom.HeroImageView
import com.fongmi.android.tv.ui.custom.HistoryProgressBar
import com.fongmi.android.tv.ui.custom.ProgressLayout
import com.fongmi.android.tv.ui.hero.HeroHorizontalGapDecoration
import com.fongmi.android.tv.ui.hero.HeroImageLoader
import com.fongmi.android.tv.ui.hero.HeroImagePrefetch
import com.fongmi.android.tv.ui.hero.HistoryEpisodeLabel
import com.fongmi.android.tv.ui.hero.HistoryMetaText
import com.fongmi.android.tv.ui.hero.HistoryPosterAdapter
import com.fongmi.android.tv.utils.HistoryProgressFormatter
import com.fongmi.android.tv.utils.ImgUtil
import com.fongmi.android.tv.utils.Task
import com.fongmi.android.tv.utils.Util
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

/**
 * 历史页（100% 纯 View，替代原 Compose `HeroHistoryActivity` + `HistoryScreen`）。
 *
 * 结构与行为逐条对应 `ref_docs/HistoryFragment.java` / `fragment_history.xml`：
 * - 数据：`History.getForDisplay()`（内部就是 `AppDatabase.get().getHistoryDao().findAll()` +
 *   分季聚合），再按 `updateTime` 倒序；空列表直接进 `ProgressLayout` 空态，不造 Mock；
 * - 焦点：海报行每一张卡获得焦点 → [select] → [bindHero] + [bindProgress]；
 * - 大图：有 TMDB 横图 → `heroBg.loadImage()`；无横图 → `heroBg.reset()` + 右上角 `posterCard`；
 * - 艺术字：命中 `HeroLogoCache` → 隐藏 32sp 剧名并显示 `heroLogoImg`，否则回落文字；
 * - 续播 / 查看详情：强制无转场（`FLAG_ACTIVITY_NO_ANIMATION` + `overridePendingTransition(0, 0)`）。
 *
 * 本文件不引用任何 Compose 类型。
 */
class HistoryViewActivity : AppCompatActivity() {

    private lateinit var heroBg: HeroImageView
    private lateinit var maskLeft: View
    private lateinit var maskBottom: View
    private lateinit var pageSite: TextView
    private lateinit var posterCard: FrameLayout
    private lateinit var posterCardImg: ImageView
    private lateinit var heroNameText: TextView
    private lateinit var heroLogoImg: ImageView
    private lateinit var heroMetaText: TextView
    private lateinit var heroDescText: TextView
    private lateinit var btnResume: CustomTypeView
    private lateinit var progressGroup: View
    private lateinit var progressEpisode: TextView
    private lateinit var progressTotal: TextView
    private lateinit var progressBar: HistoryProgressBar
    private lateinit var progressLayout: ProgressLayout
    private lateinit var posterRecycler: RecyclerView

    private lateinit var posterAdapter: HistoryPosterAdapter

    private var items: List<History> = emptyList()
    private var selectedIndex = -1
    private var tmdbCache: TmdbMatchCache? = null

    /** 编辑模式（原版 `HistoryFragment.g0`）：长按卡片进入，返回键退出。 */
    private var isEditMode = false

    /** 删除后强制落在这一条（列表已变，`previousKey` 可能刚好是被删掉的那条）。 */
    private var pendingFocusIndex: Int? = null

    private var currentLogoUrl = ""

    /** 上一次真正交给 `ImgUtil` 的竖图地址；`null` = 还没加载过。 */
    private var currentPosterUrl: String? = null

    /** 读库回包序号：快速切换 / 退出时丢弃过期结果。 */
    private var loadToken = 0

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Setting.wrapDisplay(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 图片引擎先就绪：HeroImageView / 卡片 / posterCard 都走同一个 Coil 单例。
        HeroImageLoader.install()
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        WindowCompat.setDecorFitsSystemWindows(window, false)
        Util.hideSystemUI(this)

        setContentView(R.layout.activity_history_view)
        bindViews()
        setupUi()
        load(keepSelection = false)
    }

    override fun onStart() {
        super.onStart()
        if (!EventBus.getDefault().isRegistered(this)) EventBus.getDefault().register(this)
    }

    override fun onStop() {
        if (EventBus.getDefault().isRegistered(this)) EventBus.getDefault().unregister(this)
        super.onStop()
    }

    override fun onDestroy() {
        loadToken++
        heroLogoImg.dispose()
        // posterCard 走 Glide（`ImgUtil`），按视图生命周期释放，与卡片内部加载同一套。
        ImgUtil.clear(posterCardImg)
        super.onDestroy()
    }

    /**
     * 编辑态返回键只退出编辑模式，不退出页面（原版 `HistoryActivity` 同语义）。
     *
     * 工程已打开 `enableOnBackInvokedCallback`，但本页没有注册任何启用的
     * `OnBackPressedCallback`；`OnBackPressedDispatcher` 只有在存在启用回调时才向系统
     * 注册 `OnBackInvokedCallback`，所以系统返回键仍然走 `Activity.onBackPressed()` 虚分派。
     */
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (isEditMode) {
            exitEditMode()
            return
        }
        super.onBackPressed()
    }

    /** 播放进度回写等 → 重新读库（原版 `o11.onRefreshEvent` 的 `a0()` 分支）。 */
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onRefreshEvent(event: RefreshEvent) {
        if (event.type == RefreshEvent.Type.HISTORY) load(keepSelection = true)
    }

    // ------------------------------------------------------------------
    // 初始化
    // ------------------------------------------------------------------

    private fun bindViews() {
        heroBg = findViewById(R.id.heroBg)
        maskLeft = findViewById(R.id.maskLeft)
        maskBottom = findViewById(R.id.maskBottom)
        pageSite = findViewById(R.id.pageSite)
        posterCard = findViewById(R.id.posterCard)
        posterCardImg = findViewById(R.id.posterCardImg)
        heroNameText = findViewById(R.id.heroNameText)
        heroLogoImg = findViewById(R.id.heroLogoImg)
        heroMetaText = findViewById(R.id.heroMetaText)
        heroDescText = findViewById(R.id.heroDescText)
        btnResume = findViewById(R.id.btnResume)
        progressGroup = findViewById(R.id.progressGroup)
        progressEpisode = findViewById(R.id.progressEpisode)
        progressTotal = findViewById(R.id.progressTotal)
        progressBar = findViewById(R.id.progressBar)
        progressLayout = findViewById(R.id.progressLayout)
        posterRecycler = findViewById(R.id.posterRecycler)
    }

    private fun setupUi() {
        // 原版 `H()` L163-166：遮罩与大图在视图创建时置为可见（无数据时上层是纯黑）。
        maskLeft.visibility = View.VISIBLE
        maskBottom.visibility = View.VISIBLE
        pageSite.visibility = View.GONE
        posterCard.visibility = View.GONE
        heroBg.visibility = View.GONE
        heroNameText.visibility = View.INVISIBLE
        heroLogoImg.visibility = View.GONE
        heroMetaText.visibility = View.INVISIBLE
        heroDescText.visibility = View.INVISIBLE
        btnResume.visibility = View.INVISIBLE
        progressGroup.visibility = View.GONE

        // `nm3.i`：剧名超宽以跑马灯展示，最大宽度 = 屏宽 × 0.6。
        heroNameText.isSingleLine = true
        heroNameText.ellipsize = TextUtils.TruncateAt.MARQUEE
        heroNameText.marqueeRepeatLimit = -1
        heroNameText.isSelected = true
        heroNameText.maxWidth = (resources.displayMetrics.widthPixels * HERO_NAME_WIDTH_RATIO).toInt()

        progressEpisode.isSingleLine = true
        progressEpisode.ellipsize = TextUtils.TruncateAt.MARQUEE
        progressEpisode.marqueeRepeatLimit = -1
        progressEpisode.isSelected = true

        // 海报行：main 源集不能引用 leanback 的 HorizontalGridView，用 RecyclerView + 水平 LayoutManager。
        posterAdapter = HistoryPosterAdapter(
            onFocused = { position -> select(position) },
            onClick = { position ->
                // 原版 `pp.java` case 14：编辑态点击 = 删除该条；否则 = 续播。
                if (isEditMode) deleteAt(position) else items.getOrNull(position)?.let(::openResume)
            },
            onLongPress = { enterEditMode() },
        )
        posterRecycler.apply {
            layoutManager = LinearLayoutManager(this@HistoryViewActivity, RecyclerView.HORIZONTAL, false)
            adapter = posterAdapter
            itemAnimator = null
            setHasFixedSize(true)
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            clipChildren = false
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            addItemDecoration(HeroHorizontalGapDecoration(dp(16)))
        }

        btnResume.setOnClickListener { openDetail() }
        progressLayout.showProgress()
    }

    // ------------------------------------------------------------------
    // 数据
    // ------------------------------------------------------------------

    /**
     * 读库并刷新列表（原版 `a0()` → 后台线程 → 主线程 `p0()`）。
     *
     * 数据唯一来源是真实 Room 历史表：`History.getForDisplay()` 内部调用
     * `AppDatabase.get().getHistoryDao().findAll()` 并做分季聚合；这里只补一条
     * 「更新时间倒序」，满足本页明确的排序要求。
     */
    private fun load(keepSelection: Boolean) {
        val token = ++loadToken
        val previousKey = if (keepSelection) items.getOrNull(selectedIndex)?.key else null
        val hadFocus = currentFocus != null
        // 删除后要落的卡片：下标属于删除前的列表，这里先取出，选中时再按新长度收敛。
        val pending = pendingFocusIndex
        pendingFocusIndex = null
        Task.execute {
            val cache = runCatching { Setting.getTmdbMatchCache() }.getOrNull()
            val loaded = runCatching {
                History.getForDisplay().sortedByDescending { it.updateTime }
            }.getOrNull() ?: emptyList()
            App.post {
                if (token != loadToken) return@post
                tmdbCache = cache
                items = loaded
                posterAdapter.submit(loaded)
                if (loaded.isEmpty()) {
                    exitEditMode()
                    clearSelection()
                    progressLayout.showEmpty()
                    return@post
                }
                val index = when {
                    pending != null -> pending.coerceIn(0, loaded.size - 1)
                    previousKey == null -> 0
                    else -> loaded.indexOfFirst { it.key == previousKey }.takeIf { it >= 0 } ?: 0
                }
                select(index)
                progressLayout.showContent()
                if (pending != null || !hadFocus) {
                    posterRecycler.post { focusPoster(index) }
                }
            }
        }
    }

    /** 选中第 [index] 条：Hero 文本 / 艺术字 / 横图 / 进度一次性换齐。 */
    private fun select(index: Int) {
        if (index < 0 || index >= items.size) return
        selectedIndex = index
        val history = items[index]
        bindHero(history, index)
        bindProgress(history)
        prefetch(index)
    }

    private fun clearSelection() {
        selectedIndex = -1
        currentLogoUrl = ""
        currentPosterUrl = null
        heroBg.reset()
        heroBg.visibility = View.GONE
        posterCard.visibility = View.GONE
        pageSite.visibility = View.GONE
        heroNameText.visibility = View.INVISIBLE
        heroLogoImg.visibility = View.GONE
        heroMetaText.visibility = View.INVISIBLE
        heroDescText.visibility = View.INVISIBLE
        btnResume.visibility = View.INVISIBLE
        progressGroup.visibility = View.GONE
        progressBar.setProgress(0f)
    }

    // ------------------------------------------------------------------
    // 编辑模式（原版 `HistoryFragment.m0` / `pp.java` case 14）
    // ------------------------------------------------------------------

    /** 长按任意卡片进入编辑模式；所有卡片一起显示删除角标（适配器级开关）。 */
    private fun enterEditMode() {
        if (isEditMode || items.isEmpty()) return
        isEditMode = true
        posterAdapter.setEditMode(true)
    }

    /** 退出编辑模式：只改状态与角标可见性，不动列表、不动焦点。 */
    private fun exitEditMode() {
        if (!isEditMode) return
        isEditMode = false
        posterAdapter.setEditMode(false)
    }

    /**
     * 编辑态点击卡片 = 删除这一条（`pp.java` case 14 → `history.delete()`）。
     *
     * 这里用 `deleteDisplayItem()`：列表数据来自 `History.getForDisplay()`（含分季聚合），
     * 与 leanback / mobile 的 `HistoryActivity.onItemDelete` 同一条删除路径。
     */
    private fun deleteAt(position: Int) {
        val history = items.getOrNull(position) ?: return
        history.deleteDisplayItem()
        // 原版 `if (adapter.getItemCount() == 0) setDelete(false)`：删空就退出编辑态。
        if (items.size <= 1) exitEditMode()
        // 与 `FocusRestorer.nextPosition` 同义：优先落在原位置（原位置的下一张已经补上）。
        pendingFocusIndex = position.coerceAtLeast(0)
        load(keepSelection = true)
    }

    // ------------------------------------------------------------------
    // Hero 渲染（`o11.p0` L458-512 / `q0` L514-556）
    // ------------------------------------------------------------------

    private fun bindHero(history: History, position: Int) {
        val name = history.vodName.orEmpty()

        // s0()：左上角站点角标 = vodRemarks；空串直接 GONE。
        val remarks = history.vodRemarks.orEmpty()
        pageSite.text = remarks
        pageSite.visibility = if (remarks.isEmpty()) View.GONE else View.VISIBLE

        // p0() L468：剧名（可见性由艺术字结果决定）。
        heroNameText.text = name

        // p0() L469：元数据行（年份 · 媒体类型 · 类型串 / 回站点名）。
        val meta = HistoryMetaText.build(history)
        heroMetaText.text = meta
        heroMetaText.visibility = if (meta.isEmpty()) View.INVISIBLE else View.VISIBLE

        // p0() L470：简介；XML 要求 maxLines=2 + ellipsize=end（布局里写死）。
        val desc = tmdbItem(history)?.overview.orEmpty()
        heroDescText.text = desc
        heroDescText.visibility = if (desc.isEmpty()) View.INVISIBLE else View.VISIBLE

        // p0() L471-495：艺术字优先，没有才显示文字标题。
        bindLogo(history, name)

        // q0() L514-556：有横图 → 大图；没横图 → reset + 右上角竖图。
        bindBackdrop(history, position)

        btnResume.visibility = View.VISIBLE
    }

    /**
     * 艺术字绑定：命中 `HeroLogoCache` → 隐藏剧名、显示 `heroLogoImg`；
     * 未命中或加载失败 → 回落 32sp 文字标题（`p0` L474 / L489）。
     */
    private fun bindLogo(history: History, name: String) {
        val url = tmdbLogo(history)
        if (url.isEmpty()) {
            currentLogoUrl = ""
            heroLogoImg.dispose()
            heroLogoImg.visibility = View.GONE
            heroNameText.text = name
            heroNameText.visibility = if (name.isEmpty()) View.INVISIBLE else View.VISIBLE
            return
        }
        // 同一张 Logo 已经画在屏幕上：异步重绘不再闪一次淡入。
        if (url == currentLogoUrl && heroLogoImg.visibility == View.VISIBLE) return
        currentLogoUrl = url
        heroNameText.visibility = View.GONE
        heroLogoImg.visibility = View.VISIBLE
        heroLogoImg.load(url) {
            crossfade(LOGO_CROSSFADE_MS)
            listener(object : ImageRequest.Listener {
                override fun onError(request: ImageRequest, result: ErrorResult) {
                    // 焦点可能已经移走：只处理「当前仍在显示这张 Logo」的失败。
                    if (currentLogoUrl == url) {
                        currentLogoUrl = ""
                        heroLogoImg.visibility = View.GONE
                        heroNameText.text = name
                        heroNameText.visibility = if (name.isEmpty()) View.INVISIBLE else View.VISIBLE
                    }
                }
            })
        }
    }

    /**
     * 大图 / 竖图二选一（`o11.q0` L514-536）：
     * - 有 TMDB 横图 → `heroBg.loadImage()`，同时隐藏右上角 posterCard；
     * - 没有横图 → `heroBg.reset()`（先清底图再清主图），显示 posterCard 并加载 `vodPic`。
     *
     * 横图**只认真横图**：`TmdbItem.backdropUrl` 为空就是空，绝不拿竖图拉伸铺满全屏。
     */
    private fun bindBackdrop(history: History, position: Int) {
        val backdrop = tmdbBackdrop(history)
        if (backdrop.isNotEmpty()) {
            posterCard.visibility = View.GONE
            HeroImageView.setSharedUrl(backdrop)
            heroBg.visibility = View.VISIBLE
            heroBg.loadImage(backdrop, position, null)
            return
        }
        HeroImageView.setSharedUrl("")
        heroBg.reset()
        heroBg.visibility = View.GONE
        posterCard.visibility = View.VISIBLE
        loadPosterCard(history)
    }

    /**
     * 右上角竖图：原版 `HistoryPosterPresenter.a(history, posterCardImg, 0, 0)`
     * → `ImgUtil.load(vodName, vodPic, imageView, 0, 0)`。
     *
     * 走 `ImgUtil` 而不是 Coil：它负责解析 `@Headers=` / `@Cookie=` / `@Referer=` 指令、
     * 给豆瓣图床补 Referer，并在加载失败时回落「剧名首字」占位 —— 与卡片内部加载同一套。
     */
    private fun loadPosterCard(history: History) {
        val url = history.vodPic.orEmpty()
        if (url == currentPosterUrl) return
        currentPosterUrl = url
        if (url.isBlank()) {
            ImgUtil.clear(posterCardImg)
            posterCardImg.setImageResource(R.drawable.shape_source_poster_placeholder)
            return
        }
        ImgUtil.load(history.vodName.orEmpty(), url, posterCardImg, true)
    }

    // ------------------------------------------------------------------
    // 进度（`o11.r0` L562-686）
    // ------------------------------------------------------------------

    private fun bindProgress(history: History) {
        val position = history.position
        val duration = history.duration
        val show = position > 0 && duration > 0
        progressGroup.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) {
            progressBar.setProgress(0f)
            return
        }
        progressEpisode.text = HistoryEpisodeLabel.resolve(history.vodRemarks, history.vodName)
        progressTotal.text = "${formatDuration(position)} / ${formatDuration(duration)}"
        progressBar.setProgress((position.toFloat() / duration.toFloat()).coerceIn(0f, 1f))
    }

    private fun formatDuration(ms: Long): String = HistoryProgressFormatter.format(ms, ms)

    // ------------------------------------------------------------------
    // TMDB 查询（`TmdbCache.l / s / q` 的等价实现）
    // ------------------------------------------------------------------

    private fun tmdbItem(history: History): TmdbItem? {
        val cache = tmdbCache ?: return null
        val siteKey = history.siteKey.orEmpty()
        val vodId = history.vodId.orEmpty()
        if (siteKey.isEmpty() || vodId.isEmpty()) return null
        return runCatching { cache.find(siteKey, vodId, history.vodName.orEmpty()) }.getOrNull()
    }

    /**
     * 横图地址：`TmdbCache.l(name)` 的等价物。命中后提档到 w1920（与首页同一条 `upgradeTmdbImage`），
     * 避免拿到低分辨率 `w780` 再被拉满全屏。
     */
    private fun tmdbBackdrop(history: History): String {
        val url = tmdbItem(history)?.backdropUrl.orEmpty()
        if (url.isEmpty()) return ""
        return runCatching { upgradeTmdbImage(url, TMDB_BACKDROP_WIDTH) }.getOrDefault(url)
    }

    /** 艺术字：`TmdbCache.q(name)` 的等价物（`hero_logo_cache`，key = siteKey|vodId）。 */
    private fun tmdbLogo(history: History): String {
        val siteKey = history.siteKey.orEmpty()
        val vodId = history.vodId.orEmpty()
        if (siteKey.isEmpty() || vodId.isEmpty()) return ""
        return HeroLogoCache.get(heroTmdbKey(siteKey, vodId))
    }

    /** 预热 ±2 项的海报与横图（`o11.k0` / `q0` L537-552）。 */
    private fun prefetch(index: Int) {
        for (i in (index - PREFETCH_RANGE)..(index + PREFETCH_RANGE)) {
            if (i < 0 || i >= items.size) continue
            val item = items[i]
            item.vodPic.orEmpty().takeIf { it.isNotEmpty() }?.let { HeroImagePrefetch.poster(it) }
            tmdbBackdrop(item).takeIf { it.isNotEmpty() }?.let { HeroImagePrefetch.backdrop(it) }
        }
    }

    // ------------------------------------------------------------------
    // 焦点
    // ------------------------------------------------------------------

    private fun focusPoster(index: Int) {
        if (index !in items.indices) return
        val manager = posterRecycler.layoutManager as? LinearLayoutManager ?: return
        posterRecycler.post {
            manager.scrollToPositionWithOffset(index, 0)
            posterRecycler.post {
                posterRecycler.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus()
            }
        }
    }

    // ------------------------------------------------------------------
    // 跳转：续播 / 查看详情（全部强制无转场）
    // ------------------------------------------------------------------

    /**
     * 续播：`o11.h0` L338-344 的「起播」分支 → 工程既有
     * `VideoActivity.startFromHistoryFullscreen`（leanback / mobile 两个 flavor 都有）。
     *
     * 该方法内部的 Intent 已经带 `FLAG_ACTIVITY_NO_ANIMATION`；
     * 这里再补一次 `overridePendingTransition(0, 0)`，覆盖部分 ROM 仍播放默认窗口动画的情况。
     */
    @Suppress("DEPRECATION")
    private fun openResume(history: History) {
        if (isFinishing || isDestroyed) return
        VideoActivity.startFromHistoryFullscreen(this, history)
        overridePendingTransition(0, 0)
    }

    /**
     * 「查看详情」：`DetailActivity.q2(...)` 的工程等价入口 [HeroDetailActivity.start]。
     * 七个参数全部来自这条真实历史记录，不构造 Mock。
     */
    @Suppress("DEPRECATION")
    private fun openDetail() {
        val history = items.getOrNull(selectedIndex) ?: return
        if (isFinishing || isDestroyed) return
        HeroDetailActivity.start(
            this,
            history.siteKey.orEmpty(),
            history.vodId.orEmpty(),
            history.vodName.orEmpty(),
            history.vodPic.orEmpty(),
            history.vodRemarks.orEmpty(),
            "",
            noAnimation = true,
        )
        overridePendingTransition(0, 0)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {

        /** `nm3.i`：剧名最大宽度 = 屏宽 × 0.6。 */
        private const val HERO_NAME_WIDTH_RATIO = 0.6f

        private const val TMDB_BACKDROP_WIDTH = "w1920"
        private const val LOGO_CROSSFADE_MS = 200
        private const val PREFETCH_RANGE = 2

        fun start(context: Context) {
            context.startActivity(
                Intent(context, HistoryViewActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION),
            )
        }
    }
}
