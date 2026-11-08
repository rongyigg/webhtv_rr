package com.fongmi.android.tv.ui.activity

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import coil3.dispose
import coil3.load
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.error
import coil3.request.placeholder
import com.fongmi.android.tv.App
import com.fongmi.android.tv.BuildConfig
import com.fongmi.android.tv.R
import com.fongmi.android.tv.api.config.VodConfig
import com.fongmi.android.tv.bean.Class as VodType
import com.fongmi.android.tv.bean.Site
import com.fongmi.android.tv.bean.TmdbConfig
import com.fongmi.android.tv.bean.TmdbItem
import com.fongmi.android.tv.bean.TmdbMatchCache
import com.fongmi.android.tv.bean.Vod
import com.fongmi.android.tv.impl.Callback
import com.fongmi.android.tv.model.SiteViewModel
import com.fongmi.android.tv.service.TmdbService
import com.fongmi.android.tv.setting.Setting
import com.fongmi.android.tv.setting.TmdbSitePolicy
import com.fongmi.android.tv.ui.custom.HeroImageView
import com.fongmi.android.tv.ui.custom.ProgressLayout
import com.fongmi.android.tv.ui.helper.TmdbMatcher
import com.fongmi.android.tv.ui.hero.HeroHorizontalGapDecoration
import com.fongmi.android.tv.ui.hero.HeroImageLoader
import com.fongmi.android.tv.ui.hero.HeroPosterAdapter
import com.fongmi.android.tv.ui.hero.HeroPosterCard
import com.fongmi.android.tv.ui.hero.HomePosterItem
import com.fongmi.android.tv.ui.hero.openHeroDetailOrFolder
import com.fongmi.android.tv.utils.Util
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 首页纯 View 版（第三步）：启动页 + 焦点驱动 TMDB 详情 + 详情页入口 + Tab 居中。
 *
 * **本文件不引入任何 Compose 类型**：所有节点都是 XML 里的原生 View，
 * 海报卡复用全局唯一的 [HeroPosterCard]（View 体系），大图复用 [HeroImageView]，
 * 加载 / 空态复用 [ProgressLayout]。
 *
 * 渲染契约（对齐原版 `HomeActivityV2.D2(pos)` / `E2(pos)`）：
 * 1. 数据没回来：`ProgressLayout` 处于 PROGRESS，`bottomContent` 整块隐藏，顶栏也不可见 ——
 *    屏幕上只剩 loading，绝不会先露一行文字。
 * 2. 第一条数据回来的**同一帧**：先写 Hero 文本 / Logo / 大图，再 `showContent()`；
 *    横图是否存在的判定完全来自同步读到的本地缓存，不存在「先一张竖图、再换成横图」的中间态。
 * 3. 海报行焦点变化：同步更新剧名 / Logo / 元数据 / 简介；有横图 → `heroBg` 淡入并隐藏 posterCard；
 *    没有横图 → 清空 `heroBg`，竖图只放在右上角 posterCard，绝不拉伸铺满全屏。
 *
 * 数据来源：
 * - 首页列表：`SiteViewModel.homeContent()`（与 Compose 首页同一条 LiveData 通道）；
 * - 横图 / 评分 / 年份 / 手动词名：`Setting.getTmdbMatchCache()`（磁盘匹配缓存，整页只读一次）；
 * - 艺术字 Logo：进程内 `HeroTmdbPrefetchCache` → 磁盘 `hero_logo_cache`（与 `HeroLogoCache` 同一份 prefs）；
 * - 简介：进程内详情缓存里的译文优先，否则用源站 `Vod.content`；
 * - **焦点驱动的 TMDB 详情**（第三步）：缓存里没有横图 / 艺术字时，停住 300ms 后异步请求一次详情，
 *   结果经 `posterKey` 校验才落地，详见 [requestTmdbDetail] / [onTmdbDetailReady]。
 *
 * 与 `HeroHomeActivity` 的差异（本步范围）：
 * - 不复用 `BaseActivity`：它只存在于 `leanback` / `mobile` 两个 flavor 源集，`main` 源集在编译期看不到它；
 *   这里按 `HeroHomeActivity` 的做法继承 `AppCompatActivity`，并补上等价的
 *   `Setting.wrapDisplay`（界面缩放）+ 沉浸式全屏。
 * - 已完成：启动页入口、焦点驱动 TMDB 详情、卡片确定键进详情页、分类 Tab 焦点居中。
 *   未做（后续步骤）：换源弹窗、TMDB 手动匹配弹窗、筛选面板、顶栏其它入口。
 */
class HomeViewActivity : AppCompatActivity() {

    /**
     * Activity 作用域的 [SiteViewModel]。
     *
     * 首页 `homeContent()` 与分类 `categoryContent()` 共用这一个实例：两条请求都写 `result`
     * 这条 LiveData，且 `SiteViewModel.execute` 内部会按 TaskType 作废旧回包，不会串结果。
     */
    private val siteViewModel: SiteViewModel by lazy {
        ViewModelProvider(this)[SiteViewModel::class.java]
    }

    /** 分类 Tab 焦点切换的请求防抖（与 Compose 版同一个 220ms）。 */
    private val handler = Handler(Looper.getMainLooper())
    private var pendingTabLoad: Runnable? = null

    // ------------------------------------------------------------------
    // XML 节点
    // ------------------------------------------------------------------
    private lateinit var heroBg: HeroImageView
    private lateinit var heroNameText: TextView
    private lateinit var heroLogoImg: ImageView
    private lateinit var heroMetaText: TextView
    private lateinit var heroDescText: TextView
    private lateinit var posterCard: FrameLayout
    private lateinit var posterCardImg: ImageView
    private lateinit var posterEmpty: View
    private lateinit var posterProgress: View
    private lateinit var progressLayout: ProgressLayout
    private lateinit var navBar: View
    private lateinit var homeBtn: View
    private lateinit var navRecycler: RecyclerView
    private lateinit var typeRecycler: RecyclerView
    private lateinit var recycler: RecyclerView

    // ------------------------------------------------------------------
    // 数据 / 状态
    // ------------------------------------------------------------------
    private lateinit var posterAdapter: HeroPosterAdapter
    private lateinit var navAdapter: HomeChipAdapter
    private lateinit var tabAdapter: HomeChipAdapter

    /** 当前整排首页数据；下标与 Adapter 内的列表严格一致。 */
    private var items: List<HomePosterItem> = emptyList()

    /** 当前 Hero 区对应的下标：列表重建后夹回合法范围。 */
    private var focusedIndex: Int = 0

    /** 当前接口源真实返回的分类（`Result.types`）：Tab 列表的唯一数据源。 */
    private var homeTypes: List<VodType> = emptyList()

    /** 上一次发起 `homeContent()` 时用的接口源 key：`onResume` 靠它判断用户是否换了源。 */
    private var loadedHomeSiteKey: String = ""

    /** 已经加载过的 Tab：`home:<siteKey>` 或 `type:<siteKey>:<typeId>`，避免同项重复请求。 */
    private var loadedTabKey: String = ""

    /** 当前 Tab 列表来自哪个源：换源后要把旧源的 Tab 清掉。 */
    private var tabsSiteKey: String = ""

    /** 用户是否明确停在分类 Tab 上（首页列表为空时不能悄悄改成第一个分类）。 */
    private var categoryTabActive: Boolean = false

    /** 已经自动加载过的「站点:分类」：分类结果本身也可能为空，用它挡掉死循环。 */
    private var autoCategoryKey: String? = null

    /** 当前应显示的 Logo 地址：异步失败回调只在它没被下一次焦点覆盖时才回落文字。 */
    private var currentLogoUrl: String = ""

    /** 右上角 posterCard 上已经显示的竖图地址：同地址不重复请求，避免异步重绘时闪一下。 */
    private var currentPosterUrl: String = ""

    /**
     * 进程内 TMDB 覆盖表：`posterKey → 完整详情`。
     *
     * 焦点异步拿到的横图 / 艺术字 / 评分 / 年份 / 简介全部落在这里，
     * [applyHero] 是**唯一**读它的地方，界面不会出现两套渲染路径。
     */
    private val tmdbOverrides = HashMap<String, HeroTmdbData>()

    /**
     * 当前 Hero 区对应的条目身份：`heroTmdbKey(site.key, vod.id)`。
     *
     * 异步回调的**第一道过期判据**。不能用下标：切分类 / 换源 / 重新加载后下标会整体漂移。
     */
    private var focusedPosterKey: String = ""

    /** TMDB 异步任务域：Activity 销毁时整域取消；单条请求由 [tmdbJob] 单独取消。 */
    private val tmdbScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var tmdbJob: Job? = null

    override fun attachBaseContext(newBase: Context) {
        // 与全站其它页面共用同一套「设计画布」（同 HeroHomeActivity）。
        super.attachBaseContext(Setting.wrapDisplay(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 图片引擎必须先就绪：HeroImageView / 海报卡 / posterCard 都走这个 Coil 单例。
        HeroImageLoader.install()
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        WindowCompat.setDecorFitsSystemWindows(window, false)
        Util.hideSystemUI(this)
        setContentView(R.layout.activity_home_view)
        bindViews()
        setupRecyclerViews()
        setupActions()
        showLoading()
        observeHome()
        ensureVodConfigLoaded()
        refreshHome("onCreate")
    }

    /** 从设置页回来：接口源变了就重拉首页（与 Compose 首页同一判定）。 */
    override fun onResume() {
        super.onResume()
        val site = VodConfig.get().getHome()
        if (site.key.orEmpty() == loadedHomeSiteKey) return
        Log.d(TAG, "接口源已变化: $loadedHomeSiteKey -> ${site.key}，重新请求 homeContent")
        refreshHome("设置页返回")
    }

    override fun onDestroy() {
        pendingTabLoad?.let { handler.removeCallbacks(it) }
        pendingTabLoad = null
        // 在途 TMDB 请求 / 防抖一起作废：Activity 已经没了，回调绝不能再去碰 View。
        tmdbJob?.cancel()
        tmdbScope.cancel()
        // 取消两个长生命周期 ImageView 上的在途请求（回收站里的是 Adapter 自己的职责）。
        heroLogoImg.dispose()
        posterCardImg.dispose()
        super.onDestroy()
    }

    // ==================================================================
    // 初始化
    // ==================================================================

    private fun bindViews() {
        heroBg = findViewById(R.id.heroBg)
        heroNameText = findViewById(R.id.heroNameText)
        heroLogoImg = findViewById(R.id.heroLogoImg)
        heroMetaText = findViewById(R.id.heroMetaText)
        heroDescText = findViewById(R.id.heroDescText)
        posterCard = findViewById(R.id.posterCard)
        posterCardImg = findViewById(R.id.posterCardImg)
        posterEmpty = findViewById(R.id.posterEmpty)
        posterProgress = findViewById(R.id.posterProgress)
        progressLayout = findViewById(R.id.progressLayout)
        navBar = findViewById(R.id.navBar)
        homeBtn = findViewById(R.id.homeBtn)
        navRecycler = findViewById(R.id.navRecycler)
        typeRecycler = findViewById(R.id.typeRecycler)
        recycler = findViewById(R.id.recycler)
    }

    private fun setupRecyclerViews() {
        // 【硬性要求】三个列表都是水平 LinearLayoutManager，在 Activity 里显式初始化。
        navRecycler.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        typeRecycler.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        recycler.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

        // 顶栏：目前只有「小房子」有动作，其余项先占位（后续步骤接各自页面）。
        navAdapter = HomeChipAdapter(
            context = this,
            style = HomeChipStyle.NAV,
            onFocused = { },
            onSelected = { },
        )
        navRecycler.adapter = navAdapter
        navAdapter.submit(NAV_LABELS)
        navRecycler.addItemDecoration(HeroHorizontalGapDecoration(dp(TOP_BAR_ITEM_GAP_DP)))

        // 分类 Tab：焦点即选中（视觉立刻跟随），请求由 220ms 防抖负责。
        tabAdapter = HomeChipAdapter(
            context = this,
            style = HomeChipStyle.TAB,
            onFocused = ::onTabFocused,
            onSelected = ::selectTab,
        )
        tabAdapter.selectedIndex = 0
        typeRecycler.adapter = tabAdapter
        typeRecycler.addItemDecoration(HeroHorizontalGapDecoration(dp(TAB_GAP_DP)))

        // 海报行：复用全局 HeroPosterCard；焦点即把 Vod 交给 Activity 渲染 Hero 区。
        posterAdapter = HeroPosterAdapter(
            onFocused = ::onPosterFocused,
            onClick = ::onPosterClicked,
        )
        recycler.adapter = posterAdapter
        recycler.apply {
            // 焦点放大 / 抬升阴影都要画到卡片自身边界之外，这几层都不能裁剪。
            clipToPadding = false
            clipChildren = false
            setHasFixedSize(true)
            itemAnimator = null
            overScrollMode = View.OVER_SCROLL_NEVER
            // 容器本身永远不抢焦点：只允许卡片可聚焦。
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        recycler.addItemDecoration(HeroHorizontalGapDecoration(dp(POSTER_GAP_DP)))
        // 行尾 padding 必须按当前宽度算：最后一张也能滚到屏幕正中。
        recycler.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            val end = ((view.width - dp(HeroPosterCard.CARD_WIDTH_DP)) / 2).coerceAtLeast(dp(24))
            if (view.paddingRight != end) {
                view.setPadding(view.paddingLeft, view.paddingTop, end, view.paddingBottom)
            }
        }

        // 分类栏默认 GONE（XML 骨架就是这样）：没有分类时焦点链直接跳过它。
        typeRecycler.visibility = View.GONE
        wireFocusChain(hasTabs = false)
    }

    private fun setupActions() {
        // 原版「小房子」是换源弹窗；换源弹窗还没迁到 View 版，本步先让它重拉当前源。
        homeBtn.setOnClickListener {
            Log.d(TAG, "顶栏小房子：重新请求首页")
            refreshHome("顶栏小房子")
        }
    }

    private fun wireFocusChain(hasTabs: Boolean) {
        if (hasTabs) {
            homeBtn.nextFocusDownId = R.id.typeRecycler
            navRecycler.nextFocusDownId = R.id.typeRecycler
            typeRecycler.nextFocusUpId = R.id.navRecycler
            typeRecycler.nextFocusDownId = R.id.recycler
            recycler.nextFocusUpId = R.id.typeRecycler
        } else {
            // 没有分类栏时跳过 GONE 的节点，否则向下焦点搜索会落空。
            homeBtn.nextFocusDownId = R.id.recycler
            navRecycler.nextFocusDownId = R.id.recycler
            recycler.nextFocusUpId = R.id.navRecycler
        }
    }

    // ==================================================================
    // 首屏状态：loading / empty / content
    // ==================================================================

    /**
     * 数据未回来：只显示 loading。
     *
     * 顶栏、Hero 文本、posterCard 全部不可见；`bottomContent` 由 [ProgressLayout] 整块隐藏，
     * 因此不存在「先出字、再闪图」的第一帧。
     */
    private fun showLoading() {
        navBar.visibility = View.INVISIBLE
        posterCard.visibility = View.GONE
        posterEmpty.visibility = View.GONE
        posterProgress.visibility = View.GONE
        // HeroImageView 持有旧画面：清空后再隐藏，避免中途 visible 时闪上一张图。
        heroBg.reset()
        heroBg.visibility = View.GONE
        progressLayout.showProgress()
    }

    /** 首页接口返回空：用骨架里自带的空态（Lottie + 文案），不放任何假数据。 */
    private fun showEmpty() {
        navBar.visibility = View.VISIBLE
        posterCard.visibility = View.GONE
        heroBg.reset()
        heroBg.visibility = View.GONE
        heroNameText.visibility = View.INVISIBLE
        heroLogoImg.visibility = View.GONE
        heroMetaText.visibility = View.INVISIBLE
        heroDescText.visibility = View.INVISIBLE
        recycler.visibility = View.GONE
        posterProgress.visibility = View.GONE
        posterEmpty.visibility = View.VISIBLE
        progressLayout.showContent()
    }

    // ==================================================================
    // 数据
    // ==================================================================

    private fun observeHome() {
        // `SiteViewModel.init()` 会把 result 置成 null 并派发；null = 还没拿到数据，继续 loading。
        siteViewModel.result.observe(this) { result ->
            if (result == null) return@observe
            // `SiteApi.homeContent` / `categoryContent` 不给每条 Vod 盖 site，这里统一补上：
            // 不补的话 `Vod.getSiteKey()` 是空串，后续详情 / 换源都用不了。
            val site = VodConfig.get().getHome()
            val list = result.list.orEmpty()
            val types = result.types.orEmpty()
            list.forEach { it.setSite(site) }
            updateTabs(site, types)
            // 与原版 `HomeActivity.addVideo()` 一致：首页 list 为空但站点返回了分类时，
            // 自动拉第一个分类的第 1 页（只返回 class、不返回 list 的源靠这一步才有内容）。
            if (!categoryTabActive && list.isEmpty()) {
                val first = types.firstOrNull()
                if (first != null && !first.typeId.isNullOrBlank()) {
                    val categoryKey = site.key.orEmpty() + ":" + first.typeId
                    if (categoryKey != autoCategoryKey) {
                        autoCategoryKey = categoryKey
                        loadedTabKey = tabKeyOf(1)
                        Log.d(TAG, "首页列表为空，自动加载第一个分类: ${first.typeName}(${first.typeId})")
                        siteViewModel.categoryContent(site.key, first.typeId, "1", true, hashMapOf())
                        return@observe
                    }
                }
            }
            renderHome(list, site)
        }
    }

    /**
     * 冷启动自愈：进程重启后 [VodConfig] 是空壳，先从本地已保存配置加载一次。
     *
     * 与 `HeroHomeActivity.ensureVodConfigLoaded` 同一逻辑：只在 `getHome().isEmpty()` 时触发，
     * 失败只记日志，绝不弹窗。
     */
    private fun ensureVodConfigLoaded() {
        if (!VodConfig.get().getHome().isEmpty()) return
        Log.w(TAG, "冷启动 VodConfig 为空，尝试从本地已保存配置加载")
        VodConfig.get().init().load(object : Callback() {
            override fun success() {
                Log.d(TAG, "本地配置加载完成: 站点数=${VodConfig.get().getSites().size}")
                refreshHome("冷启动配置加载完成")
            }

            override fun error(msg: String) {
                Log.w(TAG, "本地配置加载失败: $msg")
            }
        })
    }

    private fun refreshHome(reason: String) {
        val site = VodConfig.get().getHome()
        loadedHomeSiteKey = site.key.orEmpty()
        // 推荐 Tab 的「已加载」标识跟着源走：换源后必须重新请求。
        loadedTabKey = HOME_TAB_PREFIX + loadedHomeSiteKey
        categoryTabActive = false
        Log.d(TAG, "请求 homeContent（$reason）: key=${site.key} name=${site.displayName}")
        if (site.key.isNullOrEmpty()) {
            Log.w(TAG, "当前接口源为空，homeContent 必然失败：请先在设置页添加接口源")
        }
        siteViewModel.homeContent()
    }

    private fun updateTabs(site: Site, types: List<VodType>) {
        // 换源后无条件同步（新源没有分类就把旧源的 Tab 收掉）；同一个源只在 types 非空时更新
        // （分类首包通常不带 types，不能把 Tab 清空）。
        if (site.key.orEmpty() != tabsSiteKey) {
            tabsSiteKey = site.key.orEmpty()
            homeTypes = types
        } else if (types.isNotEmpty()) {
            homeTypes = types
        }
        val labels = buildList {
            add(TAB_RECOMMEND)
            homeTypes.take(MAX_TABS - 1).forEach { add(it.typeName.orEmpty().ifBlank { "分类" }) }
        }
        tabAdapter.submit(labels)
        val hasTabs = labels.size > 1
        typeRecycler.visibility = if (hasTabs) View.VISIBLE else View.GONE
        wireFocusChain(hasTabs)
        if (tabAdapter.selectedIndex > labels.lastIndex) tabAdapter.selectedIndex = 0
    }

    /**
     * 把 `Result.list` 灌进 View 的**唯一入口**。
     *
     * 所有本地缓存的读取都在这里同步完成，然后一次性：
     * 提交列表 → 渲染第一张卡 → `showContent()`。
     * 三步在同一个主线程 turn 里，所以 loading 与第一屏内容之间没有中间帧。
     */
    private fun renderHome(list: List<Vod>, site: Site) {
        val next = buildPosterItems(list, site)
        items = next
        navBar.visibility = View.VISIBLE
        if (next.isEmpty()) {
            showEmpty()
            return
        }
        focusedIndex = focusedIndex.coerceIn(0, next.lastIndex)
        posterAdapter.submit(next)
        recycler.visibility = View.VISIBLE
        posterEmpty.visibility = View.GONE
        posterProgress.visibility = View.GONE
        // 【防闪】先写 Hero（文本 / Logo / 横图三态），再让内容区可见；顺序不能反。
        applyHero(focusedIndex)
        progressLayout.showContent()
        // 首屏也要走一次 TMDB：只靠焦点驱动的话，用户不按键第一屏就永远只有竖图。
        // 请求带 300ms 防抖 + 可取消，快速切分类不会堆积。
        items.getOrNull(focusedIndex)?.let(::requestTmdbDetail)
        // 首屏 / 切分类后数据落地时，如果整个窗口还没有焦点，落到第一张卡。
        if (window.decorView.findFocus() == null) {
            recycler.post { focusPoster(focusedIndex) }
        }
    }

    /**
     * `List<Vod>` → 海报行条目：**全项目 View 版的唯一映射点**。
     *
     * 只读本地缓存（磁盘匹配缓存 + 进程内详情 + Logo prefs），不发任何网络请求。
     * `Setting.getTmdbMatchCache()` 是 synchronized 的「读盘 + Gson 解析」，
     * 所以整页只调一次，绝不给每张卡各读一遍。
     */
    private fun buildPosterItems(list: List<Vod>, fallbackSite: Site): List<HomePosterItem> {
        if (list.isEmpty()) return emptyList()
        val cache: TmdbMatchCache? = runCatching { Setting.getTmdbMatchCache() }.getOrNull()
        val logoCache = readLogoCache()
        return list.map { vod ->
            val site = vod.site ?: fallbackSite
            val vodId = vod.id.orEmpty()
            val siteKey = site.key.orEmpty()
            val key = heroTmdbKey(siteKey, vodId.ifBlank { vod.name.orEmpty() })
            // 手动匹配优先：手动词名 / 竖图与自动匹配冲突时以用户选择为准（与首页 Compose 一致）。
            val manual = cache?.let {
                runCatching { it.findManual(siteKey, vodId, vod.name) }.getOrNull()
            }
            val matched = manual ?: cache?.let {
                runCatching { it.find(siteKey, vodId, vod.name) }.getOrNull()
            }
            // 进程内已取到完整详情时，它比磁盘缓存新；`peek` 不删除，详情页仍然可以 take()。
            val prefetch = HeroTmdbPrefetchCache.peek(key)
            // 【横图判定】只认 TMDB 原始 `backdropUrl` 非空的那一条：
            // `highResBackdrop` 内部有「没有横图就退回竖图」的兜底，那一条不能算横图，
            // 否则又会把竖图拉满全屏（原版 E2 的 if-else 正是在这里生效）。
            val backdrop = when {
                prefetch != null && !prefetch.item.backdropUrl.isNullOrBlank() -> prefetch.highResBackdrop
                matched != null && !matched.backdropUrl.isNullOrBlank() ->
                    upgradeTmdbImage(matched.backdropUrl.orEmpty(), TMDB_BACKDROP_WIDTH)
                else -> ""
            }
            val logo = prefetch?.logoUrl?.takeIf { it.isNotBlank() } ?: logoCache[key]
            val rating = prefetch?.rating?.takeIf { it.isNotBlank() }
                ?: formatRating(matched?.rating ?: 0.0)
            val year = prefetch?.year?.takeIf { it.isNotBlank() }
                ?: extractYear(matched?.subtitle)
            val title = if (manual != null) {
                manual.title.orEmpty().ifBlank { vod.name.orEmpty() }
            } else {
                vod.name.orEmpty()
            }
            val posterUrl = if (manual != null) {
                // 手动匹配是用户明确选定的条目，源站图可能为空 / 403，这时换 TMDB 竖图。
                manual.posterUrl.orEmpty().ifBlank { vod.pic.orEmpty() }
            } else {
                vod.pic.orEmpty().ifBlank { matched?.posterUrl.orEmpty() }
            }
            HomePosterItem(
                posterKey = key,
                title = title.ifBlank { "未命名" },
                meta = buildMeta(vod, year, rating),
                synopsis = prefetch?.overview?.takeIf { it.isNotBlank() }
                    ?: vod.content.orEmpty(),
                posterUrl = posterUrl,
                backdropUrl = backdrop,
                logoUrl = logo,
                vod = vod,
                site = site,
            )
        }
    }

    /**
     * 艺术字 Logo 的本地缓存：`posterKey → logoUrl`。
     *
     * 与 `HeroHomeActivity.kt` 里的私有 `HeroLogoCache` 读同一份 prefs（名字 `hero_logo_cache`）
     * —— 那个 object 是 file-private，外部不可见，而本步不允许修改 `HeroHomeActivity.kt`。
     * 这里只读不写；写入仍由已有首页 / 详情页负责。若那边改了 prefs 名，这里要同步。
     */
    private fun readLogoCache(): Map<String, String> = runCatching {
        App.get().getSharedPreferences(LOGO_CACHE_PREFS, Context.MODE_PRIVATE).all
            .mapNotNull { (key, value) ->
                (value as? String)?.takeIf { it.isNotBlank() }?.let { key to it }
            }
            .toMap()
    }.getOrDefault(emptyMap())

    // ==================================================================
    // Hero 渲染（D2 / E2）
    // ==================================================================

    /**
     * 渲染第 [index] 张卡对应的 Hero 区：文本 + 艺术字 + 大图，全部同步写。
     *
     * 文本永远先写：即使大图还要解码，观众看到的也是「这张卡的标题 + 逐渐淡入的横图」，
     * 而不是上一部剧的标题配下一部剧的图。
     *
     * [upgrade] = true 表示这是**异步 TMDB 结果**触发的重绘（缓存里原本没有横图）：
     * 此时右上角竖图先顶着，等大图真正落地再收，避免「竖图消失 → 黑屏 → 大图淡入」的中间态。
     * 正常焦点切换仍走 `upgrade = false`，保持原版 `E2(pos)` 的「有横图就立刻收竖图」。
     */
    private fun applyHero(index: Int, upgrade: Boolean = false) {
        val item = items.getOrNull(index) ?: return
        focusedIndex = index
        focusedPosterKey = item.posterKey
        // 【叠加层】TMDB 异步结果只在这里生效：文本 / 评分 / 简介 / 横图 / 艺术字一次换齐。
        val tmdb = tmdbOverrides[item.posterKey]
        heroNameText.text = item.title
        // 空态时这 3 个文案是 INVISIBLE；回到内容态必须在同一帧显式恢复，
        // 否则 `ProgressLayout.showContent()` 之后 Hero 区只有一张大图、没有文字。
        heroMetaText.text = renderMeta(item, tmdb)
        heroMetaText.visibility = View.VISIBLE
        heroDescText.text = tmdb?.overview?.takeIf { it.isNotBlank() } ?: item.synopsis
        heroDescText.visibility = View.VISIBLE
        bindLogo(item, tmdb)
        bindBackdrop(item, tmdb, index, upgrade)
    }

    /** 元数据行：没有 TMDB 覆盖时用同步缓存拼好的那行；有覆盖时用详情里的年份 / 评分重拼。 */
    private fun renderMeta(item: HomePosterItem, tmdb: HeroTmdbData?): String =
        if (tmdb == null) item.meta else buildMeta(item.vod, tmdb.year, tmdb.rating)

    /**
     * 艺术字 Logo：有图 → 整行换成 Logo（隐藏文字）；没有 / 加载失败 → 32sp 文字。
     *
     * 加载期间保持文字隐藏：冷启动命中磁盘缓存时通常不到一帧；
     * 失败才回落文字，避免「先闪一行文字、再换成艺术字」。
     */
    private fun bindLogo(item: HomePosterItem, tmdb: HeroTmdbData?) {
        // 详情里的艺术字优先；详情还没回来时用同步缓存（`hero_logo_cache`）里的那条。
        val url = tmdb?.logoUrl?.takeIf { it.isNotBlank() } ?: item.logoUrl.orEmpty()
        // 同一张 Logo 已经画在屏幕上：异步重绘直接跳过，不让 Coil 再做一次淡入。
        if (url.isNotBlank() && url == currentLogoUrl && heroLogoImg.visibility == View.VISIBLE) return
        currentLogoUrl = url
        if (url.isBlank()) {
            heroLogoImg.dispose()
            showTitleFallback(item.title)
            return
        }
        heroNameText.visibility = View.GONE
        heroLogoImg.visibility = View.VISIBLE
        val logoListener = object : ImageRequest.Listener {
            override fun onError(request: ImageRequest, result: ErrorResult) {
                // 焦点可能已经移走：只处理「当前仍在显示这张 Logo」的失败。
                if (currentLogoUrl == url) showTitleFallback(item.title)
            }
        }
        heroLogoImg.load(url) {
            crossfade(LOGO_CROSSFADE_MS)
            listener(logoListener)
        }
    }

    private fun showTitleFallback(title: String) {
        heroLogoImg.visibility = View.GONE
        heroNameText.text = title
        heroNameText.visibility = View.VISIBLE
    }

    /**
     * 大图 / 竖图二选一（原版 `E2(pos)`）：
     * - 有横图 → `heroBg.loadImage()` 交叉淡入，隐藏右上角 posterCard；
     * - 没有横图 → 清空并隐藏 `heroBg`，竖图只放在 posterCard 里。
     *
     * 横图**只认真横图**：`HeroTmdbData.highResBackdrop` 在没横图时会退回竖图，
     * 所以这里必须先看 `tmdb.item.backdropUrl`，否则又会把竖图拉满全屏。
     */
    private fun bindBackdrop(item: HomePosterItem, tmdb: HeroTmdbData?, position: Int, upgrade: Boolean) {
        val backdrop = when {
            // 详情已经回来：以详情为准（没有真横图就用竖图，哪怕缓存里曾匹配到别的条目）。
            tmdb != null -> if (tmdb.item.backdropUrl.isNullOrBlank()) "" else tmdb.highResBackdrop
            else -> item.backdropUrl
        }
        if (backdrop.isNotBlank()) {
            // 正常切换：立刻收竖图，新图在 `heroBg` 里 450ms 淡入（旧图由 HeroImageView 垫在
            // heroBgUnder 上，所以整个淡入期间画面都是满的，不会先黑一下）。
            if (!upgrade) posterCard.visibility = View.GONE
            HeroImageView.setSharedUrl(backdrop)
            heroBg.visibility = View.VISIBLE
            heroBg.loadImage(backdrop, position) {
                if (focusedPosterKey != item.posterKey) return@loadImage
                // 【落地判据】`lastUrl` 只在成功贴图后仍等于本地址（失败会被清成 null），
                // 所以它能区分「这张新图真的上屏了」和「保持着上一张旧图 / 什么都没画」。
                if (heroBg.lastUrl == backdrop && heroBg.drawable != null) {
                    posterCard.visibility = View.GONE
                } else if (!upgrade) {
                    // 横图彻底失败（403 / 404 / 解码失败）：把竖图放回来，绝不留一整屏黑。
                    posterCard.visibility = View.VISIBLE
                    loadPosterCard(item.posterUrl)
                }
            }
            return
        }
        HeroImageView.setSharedUrl("")
        heroBg.reset()
        heroBg.visibility = View.GONE
        posterCard.visibility = View.VISIBLE
        loadPosterCard(item.posterUrl)
    }

    private fun loadPosterCard(url: String) {
        if (url.isBlank()) {
            if (currentPosterUrl.isBlank()) return
            currentPosterUrl = ""
            posterCardImg.dispose()
            posterCardImg.setImageResource(R.drawable.shape_source_poster_placeholder)
            return
        }
        // 同一张竖图已经在显示（异步重绘 / 同一条目重新渲染）：不重复请求，避免淡入闪一下。
        if (url == currentPosterUrl && posterCardImg.drawable != null) return
        currentPosterUrl = url
        posterCardImg.load(url) {
            crossfade(POSTER_CARD_CROSSFADE_MS)
            placeholder(R.drawable.shape_source_poster_placeholder)
            error(R.drawable.shape_source_poster_placeholder)
        }
    }

    // ==================================================================
    // TMDB 异步详情（焦点驱动）
    // ==================================================================

    /**
     * 焦点条目的 TMDB 详情请求：**本步唯一新增的网络入口**。
     *
     * 四条约定，缺一条就会出现「图片对不上剧名」：
     * 1. **可取消**：每次先 `cancel()` 上一条，滑过一排时旧请求立刻作废，不占连接也不回写；
     * 2. **防抖**：方向键滑过时焦点每几十毫秒换一张，停手 [TMDB_FOCUS_DEBOUNCE_MS] 才发；
     * 3. **单一落地口**：结果只由 [onTmdbDetailReady] 校验后写入 [tmdbOverrides] 并重绘；
     * 4. **身份判据是 `posterKey`**（站点 key + 条目 id），不是下标 —— 切分类 / 换源后下标会漂移。
     */
    private fun requestTmdbDetail(item: HomePosterItem) {
        tmdbJob?.cancel()
        tmdbJob = null
        // 已经有完整详情（刚停过这张 / 手动匹配拉过）：不重复请求，只补写跨页缓存
        // （缓存条目可能已被别的 Tab 挤掉，但内存 overrides 还在）。
        val existing = tmdbOverrides[item.posterKey]
        if (existing != null && existing.detail != null) {
            HeroTmdbPrefetchCache.put(item.posterKey, existing)
            return
        }
        // 没配 Key / 该源被排除 / 没有剧名：保持源站画面，绝不发无效请求。
        val config = TmdbConfig.effectiveCurrent()
        if (!config.isReady() || item.vod.name.orEmpty().isBlank()) return
        if (!TmdbSitePolicy.isEnabled(config, item.site.key, item.vod.id)) return
        // 发起时就固定身份：回调里只认这一个 key，之后焦点再动都与本次请求无关。
        val key = item.posterKey
        tmdbJob = tmdbScope.launch {
            delay(TMDB_FOCUS_DEBOUNCE_MS)
            // 阻塞式 OkHttp 只能在 IO 线程跑；协程被取消后 withContext 的结果直接丢弃。
            val data = withContext(Dispatchers.IO) { loadTmdbDetail(item, config) } ?: return@launch
            onTmdbDetailReady(key, data)
        }
    }

    /**
     * 阻塞式网络链路（**只在 IO 线程调用**），顺序与详情页 / Compose 首页一致：
     * 手动缓存 → 自动缓存 → 在线搜索 → 详情 → 演员 / 译文简介 / 艺术字 Logo。
     *
     * 任何一步失败都返回 null：首页保持已显示的横图 / 竖图与源站简介，绝不把画面换成空白。
     */
    private fun loadTmdbDetail(item: HomePosterItem, config: TmdbConfig): HeroTmdbData? = try {
        val service = TmdbService()
        val siteKey = item.site.key
        val vod = item.vod
        val cache = Setting.getTmdbMatchCache()
        val manualItem = cache.findManual(siteKey, vod.id, vod.name)
        // 显式声明可空：Java 侧是平台类型，`?:` 链的推断结果直接 `== null` 判断 + 智能转换最稳。
        val matched: TmdbItem? = manualItem
            ?: cache.find(siteKey, vod.id, vod.name)
            ?: TmdbMatcher(service, config).searchAndMatch(vod.name, vod)
        if (matched == null) {
            null
        } else {
            val detail = service.detail(matched, config, false)
            HeroTmdbData(
                item = matched,
                detail = detail,
                cast = service.cast(detail, config),
                overview = service.translatedOverview(detail, config).orEmpty(),
                // 与详情页共用同一套提取规则，避免两处逻辑漂移。
                logoUrl = extractTmdbLogo(detail, config.getImageBase()),
                // 【只提横图】没有 `backdropUrl` 时留空，首页继续走竖图分支（E2 的 if-else）。
                highResBackdrop = if (matched.backdropUrl.isNullOrBlank()) {
                    ""
                } else {
                    upgradeTmdbImage(matched.backdropUrl, TMDB_BACKDROP_WIDTH)
                },
                manual = manualItem != null,
                rating = formatRating(matched.rating),
                year = extractYear(matched.subtitle),
            )
        }
    } catch (e: CancellationException) {
        // 焦点已移走 / 页面退出：原样抛出，交给协程取消机制。
        throw e
    } catch (e: TmdbService.AuthException) {
        Log.w(TAG, "TMDB 鉴权失败: ${e.message}")
        null
    } catch (e: Exception) {
        Log.w(TAG, "TMDB 详情失败: ${item.title} | ${e.message}")
        null
    }

    /**
     * 异步结果落地：**全项目唯一**能把 TMDB 数据写进界面或缓存的地方。
     *
     * 过期拦截（三重，任何一条不满足就整包丢弃，绝不覆盖当前画面）：
     * 1. 回调里的 `key` 必须仍等于 [focusedPosterKey] —— 用户已经移走焦点就丢弃；
     * 2. `items[focusedIndex].posterKey` 必须也是它 —— 列表被重建 / 换源 / 切 Tab 后下标漂移；
     * 3. 合并时比对 `tmdbId` 与 `manual` —— 自动结果不许盖掉手动匹配。
     * 三条都是主线程上的内存比较，与随后的重绘在同一个消息里，中间不会插进别的焦点事件。
     */
    private fun onTmdbDetailReady(key: String, data: HeroTmdbData) {
        mergeTmdbData(key, data)
        if (focusedPosterKey != key) {
            if (BuildConfig.DEBUG) Log.d(TAG, "TMDB 结果过期，丢弃: $key")
            return
        }
        val index = focusedIndex
        if (items.getOrNull(index)?.posterKey != key) return
        // 【450ms 淡入】重绘仍走 `heroBg.loadImage()`：HeroImageView 内部把旧图垫到 heroBgUnder，
        // 新图做 1.03→1.0 + alpha 0→1 的 450ms 淡入，换背景永远是叠化，不会先清空再闪黑。
        applyHero(index, upgrade = true)
    }

    /** 写入覆盖表：同一 key 只保留一条；空字段不覆盖已有值；自动结果不许盖手动结果。 */
    private fun mergeTmdbData(key: String, data: HeroTmdbData) {
        val current = tmdbOverrides[key]
        if (current != null) {
            if (current.item.tmdbId != data.item.tmdbId) return
            if (current.manual && !data.manual) return
        }
        val merged = data.copy(
            highResBackdrop = data.highResBackdrop.ifBlank { current?.highResBackdrop.orEmpty() },
            logoUrl = data.logoUrl?.takeIf { it.isNotBlank() } ?: current?.logoUrl,
            overview = data.overview.ifBlank { current?.overview.orEmpty() },
            manual = current?.manual == true || data.manual,
        )
        tmdbOverrides[key] = merged
        // 详情页进来时直接 take()：零请求出首帧（两边用的是同一个 `heroTmdbKey`）。
        HeroTmdbPrefetchCache.put(key, merged)
    }

    // ==================================================================
    // 焦点
    // ==================================================================

    /**
     * 海报行焦点变化：Adapter 把这一张的 [Vod] 传进来，这里按 position 取同一条展示数据。
     *
     * 用 position 取而不是直接用 Adapter 传的对象：Adapter 与 Activity 各自持有一份 list，
     * position 是两者唯一稳定的对应关系（`submit` 保证同下标同 `posterKey`）。
     */
    private fun onPosterFocused(position: Int, vod: Vod) {
        if (BuildConfig.DEBUG) Log.d(TAG, "海报焦点 position=$position vod=${vod.name}")
        applyHero(position)
        scrollPostersTo(position)
        // 焦点停住就去拉这条的 TMDB 详情（防抖 + 可取消），回来时再校验 posterKey。
        items.getOrNull(position)?.let(::requestTmdbDetail)
    }

    /**
     * 卡片确定键：进这条数据**所属源**的真实详情页。
     *
     * 与 Compose 首页（`HeroHomeScreen.onClick` → `openHeroDetailOrFolder`）同一条路由：
     * folder 壳（网盘聚类的 `atvp_detail:`）交给分类页展开，其余进 Hero 详情页。
     * 已经把当前画面上的横图 / 艺术字 / TMDB 简介一起带过去，详情页首帧不再等第二轮网络。
     */
    private fun onPosterClicked(item: HomePosterItem) {
        val tmdb = tmdbOverrides[item.posterKey]
        Log.d(TAG, "海报确定键: ${item.title} → 详情页")
        openHeroDetailOrFolder(
            activity = this,
            vod = item.vod,
            site = item.site,
            backdropUrl = tmdb?.highResBackdrop ?: item.backdropUrl,
            logoUrl = tmdb?.logoUrl?.takeIf { it.isNotBlank() } ?: item.logoUrl.orEmpty(),
            overview = tmdb?.overview.orEmpty(),
        )
    }

    private fun onTabFocused(index: Int) {
        // 【居中】焦点一动就先把选中的 Tab 平滑滚到屏幕正中（与海报行同一套几何），
        // 即使下面因为「同一个 Tab」提前 return，视觉也已经跟上了。
        scrollTabToCenter(index)
        if (tabAdapter.selectedIndex == index) return
        // 焦点即选中：红色选中态立刻跟随，网络请求等 220ms 防抖。
        tabAdapter.selectedIndex = index
        pendingTabLoad?.let { handler.removeCallbacks(it) }
        val runnable = Runnable { selectTab(index) }
        pendingTabLoad = runnable
        handler.postDelayed(runnable, TAB_SWITCH_DEBOUNCE_MS)
    }

    /**
     * 按当前 Tab 请求数据：0 = 推荐（homeContent），其余 = 源返回的分类。
     *
     * 同一个 Tab 已经加载过就跳过，避免焦点来回移动时反复请求。
     */
    private fun selectTab(index: Int) {
        pendingTabLoad?.let { handler.removeCallbacks(it) }
        pendingTabLoad = null
        tabAdapter.selectedIndex = index
        scrollTabToCenter(index)
        val site = VodConfig.get().getHome()
        val key = tabKeyOf(index)
        if (index <= 0) {
            categoryTabActive = false
            if (key == loadedTabKey && items.isNotEmpty()) return
            refreshHome("推荐 Tab")
            return
        }
        val type = homeTypes.getOrNull(index - 1) ?: return
        if (type.typeId.isNullOrBlank() || site.key.isNullOrEmpty()) return
        if (key == loadedTabKey && items.isNotEmpty()) return
        loadedTabKey = key
        categoryTabActive = true
        Log.d(TAG, "切换分类: ${type.typeName}(${type.typeId})")
        siteViewModel.categoryContent(site.key, type.typeId, "1", true, hashMapOf())
    }

    private fun tabKeyOf(index: Int): String {
        val siteKey = VodConfig.get().getHome().key.orEmpty()
        if (index <= 0) return HOME_TAB_PREFIX + siteKey
        val typeId = homeTypes.getOrNull(index - 1)?.typeId.orEmpty()
        return TYPE_TAB_PREFIX + siteKey + ":" + typeId
    }

    /**
     * 焦点卡滚动：
     * - 前 4 张：整行回最左（已经在最左就完全不动）；
     * - 第 5 张起：`smoothScrollToPosition` 把目标卡滚进视口。
     *
     * 「目标卡精确居中」需要自定义 `LinearLayoutManager` + `LinearSmoothScroller`；
     * 本步按施工要求保留系统 `LinearLayoutManager`，居中吸附留到下一步再补。
     */
    private fun scrollPostersTo(index: Int) {
        if (index !in items.indices) return
        if (index < POSTER_SCROLL_ANCHOR) {
            if (recycler.computeHorizontalScrollOffset() != 0) recycler.smoothScrollToPosition(0)
            return
        }
        recycler.smoothScrollToPosition(index)
    }

    /** 把焦点交给第 [index] 张卡；目标还没布局出来就先瞬移定位，再在下一拍补抢。 */
    private fun focusPoster(index: Int) {
        if (index !in items.indices) return
        val attached = recycler.findViewHolderForAdapterPosition(index)?.itemView
        if (attached != null && attached.isAttachedToWindow && attached.requestFocus()) return
        val manager = recycler.layoutManager as? LinearLayoutManager ?: return
        manager.scrollToPositionWithOffset(
            index,
            if (index < POSTER_SCROLL_ANCHOR) 0 else centeredOffset(),
        )
        recycler.post {
            recycler.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus()
        }
    }

    /** 卡片左边缘距 paddingStart 的距离：让卡片中心落在 RecyclerView 的几何中心。 */
    private fun centeredOffset(): Int {
        val offset = (recycler.width - dp(HeroPosterCard.CARD_WIDTH_DP)) / 2 - recycler.paddingLeft
        return offset.coerceAtLeast(0)
    }

    /**
     * 把第 [index] 个分类 Tab 平滑滚到**屏幕正中**（与海报行同一套几何）。
     *
     * 与海报行「前 4 张不动」不同：Tab 行一共只有几个到十几个，始终让选中项居中更好认，
     * 首尾两端由 RecyclerView 自己夹住（滚到头就停），不会把行滚出边界。
     */
    private fun scrollTabToCenter(index: Int) {
        if (index !in 0 until tabAdapter.itemCount) return
        val manager = typeRecycler.layoutManager as? LinearLayoutManager ?: return
        manager.startSmoothScroll(CenterSmoothScroller(this).apply { targetPosition = index })
    }

    // ==================================================================
    // 小工具
    // ==================================================================

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private companion object {

        private const val TAG = "HomeView"

        // 顶栏顺序（对照 ref_docs/home_ui.png / Compose 版 TOP_BAR_LABELS）。
        private val NAV_LABELS = listOf("首页", "直播", "搜索", "历史", "收藏", "推送", "设置")

        private const val TAB_RECOMMEND = "推荐"

        /** 分类 Tab 上限：与 Compose 版 FocusRequester 池（32）对齐，超出的分类不显示。 */
        private const val MAX_TABS = 32

        /** 分类 Tab 焦点切换的请求防抖：与 Compose 版同一个 220ms。 */
        private const val TAB_SWITCH_DEBOUNCE_MS = 220L

        /**
         * TMDB 焦点详情防抖：与 Compose 首页 `HERO_TMDB_FOCUS_DEBOUNCE_MS` 同一个 300ms。
         *
         * 方向键滑过一排时焦点每 ~50ms 换一张，停手 300ms 才发请求 —— 滑过不发。
         */
        private const val TMDB_FOCUS_DEBOUNCE_MS = 300L

        /** 海报行「前 4 张不滚动」的分界：与原版 Leanback 的 `POSTER_SCROLL_ANCHOR` 一致。 */
        private const val POSTER_SCROLL_ANCHOR = 4

        private const val POSTER_GAP_DP = 12
        private const val TAB_GAP_DP = 12
        private const val TOP_BAR_ITEM_GAP_DP = 40

        private const val LOGO_CROSSFADE_MS = 150

        private const val POSTER_CARD_CROSSFADE_MS = 300

        /** 横向背景图提档目标：w1920（TMDB 实测支持的档位）。 */
        private const val TMDB_BACKDROP_WIDTH = "w1920"

        /** 与 `HeroLogoCache`（HeroHomeActivity.kt 内私有 object）共用同一份 prefs。 */
        private const val LOGO_CACHE_PREFS = "hero_logo_cache"

        private const val HOME_TAB_PREFIX = "home:"
        private const val TYPE_TAB_PREFIX = "type:"

        private val YEAR_REGEX = Regex("(?:19|20)\\d{2}")
    }

    /** 元数据行：`年份 · 分类 · 备注 · TMDB 评分`，空字段自动省略。 */
    private fun buildMeta(vod: Vod, year: String, rating: String): String = listOf(
        year.ifBlank { vod.year.orEmpty() },
        vod.typeName.orEmpty(),
        vod.remarks.orEmpty(),
        if (rating.isNotBlank()) "TMDB $rating" else "",
    )
        .filter { it.isNotBlank() }
        .joinToString(" · ")

    /** TMDB 评分：`8.6`；0 分返回空串（元数据行不出现 `TMDB 0.0`）。 */
    private fun formatRating(rating: Double): String =
        if (rating > 0.0) String.format(Locale.ROOT, "%.1f", rating) else ""

    /** TMDB 年份：从 `TmdbItem.subtitle`（`电影 · 2014-05-23 · 评分 7.8`）抓 4 位年份。 */
    private fun extractYear(subtitle: String?): String =
        YEAR_REGEX.find(subtitle.orEmpty())?.value.orEmpty()
}

/**
 * 顶栏 / 分类 Tab 的通用文字项（纯 View）。
 *
 * 不新建 item XML：两项都只是一个 TextView + 焦点态背景，代码创建可以少两份布局文件，
 * 也避免与全局海报卡的尺寸规则混在一起。
 */
private enum class HomeChipStyle { NAV, TAB }

private class HomeChipAdapter(
    private val context: Context,
    private val style: HomeChipStyle,
    private val onFocused: (Int) -> Unit,
    private val onSelected: (Int) -> Unit,
) : RecyclerView.Adapter<HomeChipAdapter.Holder>() {

    private val items = ArrayList<String>()
    private val density = context.resources.displayMetrics.density
    private val accent = ContextCompat.getColor(context, R.color.hero_accent)

    /** 选中下标：Tab 用它画「失焦仍选中」的红色描边；-1 = 未选中。 */
    var selectedIndex: Int = -1
        set(value) {
            if (field == value) return
            val old = field
            field = value
            if (old >= 0) notifyItemChanged(old)
            if (value >= 0) notifyItemChanged(value)
        }

    fun submit(next: List<String>) {
        if (next == items) return
        items.clear()
        items.addAll(next)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val text = TextView(parent.context).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            isFocusable = true
            isFocusableInTouchMode = true
            // API 26 才有的方法，minSdk 24 必须判版本。
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                defaultFocusHighlightEnabled = false
            }
            stateListAnimator = null
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            when (style) {
                HomeChipStyle.NAV -> {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    setTextColor(Color.WHITE)
                }
                HomeChipStyle.TAB -> {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    minHeight = dp(26)
                    setPadding(dp(12), 0, dp(12), 0)
                    setTextColor(Color.WHITE)
                }
            }
        }
        val holder = Holder(text)
        text.setOnClickListener {
            val position = holder.bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION) onSelected(position)
        }
        text.setOnFocusChangeListener { view, hasFocus ->
            val position = holder.bindingAdapterPosition
            if (hasFocus && position != RecyclerView.NO_POSITION) onFocused(position)
            applyVisual(view as TextView, hasFocus, position)
            if (style == HomeChipStyle.NAV) {
                // 顶栏焦点态整体平滑放大 1.1 倍（只走绘制层，不动布局）。
                view.animate()
                    .scaleX(if (hasFocus) TOP_BAR_FOCUS_SCALE else 1f)
                    .scaleY(if (hasFocus) TOP_BAR_FOCUS_SCALE else 1f)
                    .setDuration(FOCUS_ANIM_MS)
                    .start()
            }
        }
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.text.text = items[position]
        applyVisual(holder.text, holder.text.isFocused, position)
    }

    private fun applyVisual(text: TextView, focused: Boolean, position: Int) {
        when (style) {
            HomeChipStyle.NAV -> {
                text.alpha = if (focused) 1f else TOP_BAR_IDLE_ALPHA
                text.setTypeface(null, if (focused) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            }
            HomeChipStyle.TAB -> {
                val selected = position == selectedIndex
                text.setTextColor(if (focused || selected) Color.WHITE else TAB_IDLE_TEXT)
                text.background = tabBackground(focused, selected)
            }
        }
    }

    /** 三态严格分离：焦点红底 → 选中红框 → 全透明（与 Compose `HeroTabChip` 同一套规则）。 */
    private fun tabBackground(focused: Boolean, selected: Boolean): Drawable = GradientDrawable().apply {
        cornerRadius = dp(13).toFloat()
        when {
            focused -> setColor(accent)
            selected -> {
                setColor(Color.TRANSPARENT)
                setStroke((1.5f * density).roundToInt().coerceAtLeast(1), accent)
            }
            else -> setColor(Color.TRANSPARENT)
        }
    }

    private fun dp(value: Int): Int = (value * density).roundToInt()

    inner class Holder(val text: TextView) : RecyclerView.ViewHolder(text)

    private companion object {
        private const val TOP_BAR_FOCUS_SCALE = 1.1f
        private const val TOP_BAR_IDLE_ALPHA = 0.6f
        private const val TAB_IDLE_TEXT = 0x99FFFFFF.toInt()
        private const val FOCUS_ANIM_MS = 150L
    }
}

/**
 * 让目标项停在视口正中的平滑滚动器（分类 Tab 行专用）。
 *
 * 海报行用的是「前 4 张不动、第 5 张起才滚」的 Leanback 规则，Tab 行项少且每一格都要认，
 * 所以这里始终居中。只重写水平方向：
 * `dx = 视口中心 - 目标项中心`（`onTargetFound` 内部按 `-dx` 滚动，所以这个符号不能反，
 * 与 `HeroHomeActivity.HeroPosterSmoothScroller` 同一套约定）；
 * 垂直方向固定 `SNAP_TO_START` 且 dy 恒为 0（整行高度就是 wrap_content，没有垂直滚动空间）。
 *
 * 目标靠近首尾时，RecyclerView 自己会把滚动夹到边界，不会把整行滚出屏幕。
 */
private class CenterSmoothScroller(context: Context) : LinearSmoothScroller(context) {

    override fun getVerticalSnapPreference(): Int = LinearSmoothScroller.SNAP_TO_START

    override fun calculateDyToMakeVisible(view: View, snapPreference: Int): Int = 0

    override fun calculateDxToMakeVisible(view: View, snapPreference: Int): Int {
        val manager = layoutManager ?: return 0
        // 视口内容区中心（减掉左右 padding，typeRecycler 是 24dp 对称内边距 → 正好屏幕中心）。
        val viewportCenter = manager.paddingLeft +
            (manager.width - manager.paddingLeft - manager.paddingRight) / 2
        // 目标项中心：只取布局后的左右边界，不含外边距 / ItemDecoration 间距。
        val targetCenter = (view.left + view.right) / 2
        return viewportCenter - targetCenter
    }
}
