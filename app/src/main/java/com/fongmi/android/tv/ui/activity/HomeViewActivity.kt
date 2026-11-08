package com.fongmi.android.tv.ui.activity

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
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
import com.fongmi.android.tv.ui.hero.ChangeSourceDialog
import com.fongmi.android.tv.ui.hero.HeroHorizontalGapDecoration
import com.fongmi.android.tv.ui.hero.HeroImageLoader
import com.fongmi.android.tv.ui.hero.HeroPosterAdapter
import com.fongmi.android.tv.ui.hero.HeroPosterCard
import com.fongmi.android.tv.ui.hero.HomePosterItem
import com.fongmi.android.tv.ui.hero.TmdbMatchDialog
import com.fongmi.android.tv.ui.hero.openHeroDetailOrFolder
import com.fongmi.android.tv.utils.Notify
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
    // 海报行长按（下键 / 确定键，1000ms）
    // ------------------------------------------------------------------
    /** 长按已触发：抬起之前吞掉所有按键（含 repeatCount > 0 的连发）。 */
    private var isLongPressTriggered = false

    /** 1000ms 定时器本体（对应 ref_docs 里的 `this.X` / `this.U`）。 */
    private var longPressRunnable: Runnable? = null

    /** TMDB 手动匹配弹窗的 Compose 宿主（0×0，只承载组合；窗口由弹窗自己开）。 */
    private var tmdbMatchHost: ComposeView? = null

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

    /**
     * 上一次停留的分类 Tab 下标（原版 `K.getSelectedPosition()` 的替身）。
     *
     * 海报区按 ↑ 时回到这里，而不是永远回第 0 个「推荐」：
     * 用户在第 3 个分类看片，往下一格再往上，必须回到第 3 个 Tab（原版 HomeActivityV2 L1313 `t2()`）。
     */
    private var lastFocusedTabIndex = 0

    /** 已经自动加载过的「站点:分类」：分类结果本身也可能为空，用它挡掉死循环。 */
    private var autoCategoryKey: String? = null

    /**
     * 本地配置是否正在异步加载（[ensureVodConfigLoaded] 已发起、回调还没回来）。
     *
     * 为 true 时「当前接口源为空」只是冷启动的暂时状态：必须保持 loading 等回调，
     * 绝不能用空 [Site] 发 `homeContent()`（那必然抛异常并被吞成空结果 → 页面被钉死在空态）。
     */
    private var configLoadPending: Boolean = false

    /** 空结果的自动重试是否已经用掉：真实空源只重试一次，避免反复请求。 */
    private var emptyRetryUsed: Boolean = false

    /** 在途的「空结果自动重试」：Activity 销毁时必须移除，回调不能碰已销毁的 View。 */
    private var pendingEmptyRetry: Runnable? = null

    /**
     * 「请先在设置中配置接口」这条提示是否已经弹过。
     *
     * 空源状态会被 `onResume` / 配置加载回调 / 重试反复走到，不去重就会连弹好几次 Toast。
     * 一旦配好源（`refreshHome` 里 key 非空）就复位，用户后来把源删掉时还能再被提醒一次。
     */
    private var noSourceHintShown: Boolean = false

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

    /**
     * 【长按锁的解除兜底】弹窗（换源 / TMDB 手动匹配）都是**独立 Window**，抢到焦点后
     * 本 Activity 的窗口就收不到按键了 —— 长按那把锁也就永远等不到 ACTION_UP。
     * 窗口重新拿到焦点 = 弹窗已关闭 / 从后台回来，此时按键必然不处于按住状态，
     * 无条件解锁，否则首页会一直吞按键（比"漏放一次短按"严重得多）。
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) isLongPressTriggered = false
    }

    override fun onDestroy() {
        pendingTabLoad?.let { handler.removeCallbacks(it) }
        pendingTabLoad = null
        pendingEmptyRetry?.let { handler.removeCallbacks(it) }
        pendingEmptyRetry = null
        // 长按定时器与 Compose 弹窗宿主都必须跟着 Activity 一起撤掉，否则回调会打到已销毁的 View。
        cancelLongPress()
        isLongPressTriggered = false
        dismissTmdbMatchHost()
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

        // 顶栏：【本步已完成】七项全部接上真实页面，见 [onNavSelected]。
        navAdapter = HomeChipAdapter(
            context = this,
            style = HomeChipStyle.NAV,
            onFocused = { },
            onSelected = ::onNavSelected,
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
        // 【左右内边距】保持 XML 的 24dp / 24dp，**不再**按屏宽改写行尾 padding。
        // 原版 `activity_home_v2.xml` L148-160 两端都是 24dp；把行尾改成 (宽-120dp)/2 会让
        // 最后一张永远停在屏幕正中，行尾就永远滚不到头。
        // 滚动位置全部交给 [scrollPostersTo] 的系统 API，padding 只认 XML。

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

    // ==================================================================
    // 顶栏路由（七项）
    // ==================================================================

    /**
     * 顶栏点击 / 确定键的统一入口（D-pad 确定键与触屏点击都走 item 的 `setOnClickListener`）。
     *
     * **按文案分派，不按硬编码下标**：[NAV_LABELS] 是这份菜单的唯一顺序来源，
     * 以后增删一项也不会把「设置」指到别的页面。
     *
     * 目标页分两类：
     * - 在 `main` 源集里（搜索 / 历史）：本类可以直接引用，且与已验收的 Compose 首页同一条路由
     *   （`HeroSearchActivity` / `HeroHistoryActivity`），不退回 leanback 的旧页面；
     * - 只在某个 flavor 里（直播 / 收藏 / 推送 / 设置）：见 [openFlavorActivity]。
     *
     * 顶栏第一项是「小房子」图标（`homeBtn`），文字项里**没有「首页」**：
     * 原版 navRecycler 只放 直播 / 搜索 / 历史 / 收藏 / 推送 / 设置。
     */
    private fun onNavSelected(position: Int) {
        val label = navAdapter.labelAt(position) ?: return
        Log.d(TAG, "顶栏点击: $label")
        when (label) {
            NAV_LIVE -> openFlavorActivity(LIVE_ACTIVITY_CLASS, Context::class.java, label)
            NAV_SEARCH -> HeroSearchActivity.start(this, currentSearchBackdrop())
            NAV_HISTORY -> HeroHistoryActivity.start(this)
            NAV_KEEP -> openFlavorActivity(KEEP_ACTIVITY_CLASS, Activity::class.java, label)
            NAV_PUSH -> openFlavorActivity(PUSH_ACTIVITY_CLASS, Activity::class.java, label)
            NAV_SETTINGS -> openFlavorActivity(SETTING_ACTIVITY_CLASS, Activity::class.java, label)
        }
    }

    /**
     * 打开**只存在于某个 flavor** 的页面（直播 / 收藏 / 推送 / 设置）。
     *
     * 本类在 `main` 源集、两个变体都要编译，而 `PushActivity` / `SettingActivity` 只在
     * `app/src/leanback/`（mobile 清单里没有声明），直接写类名会让 mobile 变体编译失败。
     * 因此沿用全项目已有做法（`HeroHomeActivity.openSettings`）：`Class.forName` + 反射调
     * `start(...)`；失败不崩，只提示。
     *
     * [paramType] 必须与目标类 `start` 的形参**完全一致**：
     * `LiveActivity.start(Context)`，`KeepActivity` / `PushActivity` / `SettingActivity` 都是 `start(Activity)`。
     */
    private fun openFlavorActivity(className: String, paramType: Class<*>, label: String) {
        runCatching {
            Class.forName(className).getMethod("start", paramType).invoke(null, this)
        }.onFailure { e ->
            Log.w(TAG, "顶栏「$label」打不开: $className | ${e.message}")
            Notify.show("当前版本没有${label}页")
        }
    }

    /** 搜索页的磨砂大图：把当前 Hero 正在显示的横图带过去（与 Compose 首页同一个来源）。 */
    private fun currentSearchBackdrop(): String {
        val item = items.getOrNull(focusedIndex) ?: return ""
        return tmdbOverrides[item.posterKey]?.highResBackdrop ?: item.backdropUrl.orEmpty()
    }

    /**
     * 没配接口源时的「救命」提示：Toast 一句 + 把焦点直接送到顶栏「设置」。
     *
     * 调用前顶栏必须可见（[showEmpty] 已经 `navBar.visibility = VISIBLE`），否则焦点送不过去。
     * [noSourceHintShown] 保证只提示一次，配置好源之后复位。
     */
    private fun showNoSourceHint() {
        if (noSourceHintShown) return
        noSourceHintShown = true
        Log.w(TAG, "没有可用接口源，提示用户去设置页配置")
        Notify.show(NO_SOURCE_HINT)
        navBar.post { focusNavItem(NAV_LABELS.indexOf(NAV_SETTINGS)) }
    }

    /** 把焦点交给顶栏第 [index] 项：holder 还没挂上就先定位，再在下一拍补抢（与 [focusPoster] 同一套）。 */
    private fun focusNavItem(index: Int) {
        if (index !in 0 until navAdapter.itemCount) return
        val attached = navRecycler.findViewHolderForAdapterPosition(index)?.itemView
        if (attached != null && attached.isAttachedToWindow && attached.requestFocus()) return
        navRecycler.scrollToPosition(index)
        navRecycler.post {
            navRecycler.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus()
        }
    }

    /**
     * 焦点链：顶栏 →（分类栏）→ 海报行，再原路返回。
     *
     * **顶栏 / 分类栏必须先「让位」**：两者都是 `RecyclerView`，而 `RecyclerView` 构造函数里
     * 调了 `setFocusableInTouchMode(true)` —— 它自己就是一个可聚焦节点。方向键跨行搜索时，
     * 框架会把这个整行容器本身当成候选（它的矩形正好盖住全部子项），焦点被它吞掉：
     * 屏幕上看不到任何高亮，再按方向键也动不了。
     *
     * 海报行早就按这条规则处理过（`setupRecyclerViews` 里 `recycler.isFocusable = false`），
     * 这里把另外两行补上：容器永远不接焦点，只允许子项接。让位之后，跨行方向键由系统的
     * 几何焦点搜索接管（向上落到正上方的那个顶栏项 / 选中 Tab，不会跳最右边的「设置」）。
     */
    private fun wireFocusChain(hasTabs: Boolean) {
        navRecycler.apply {
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        typeRecycler.apply {
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
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
            // 【失败 ≠ 空源】list 和 types 同时为空时，99% 是 `SiteViewModel.execute` 的失败分支
            // 派发的 `Result.empty()`（它没有 msg，回调里与「源站真的没有首页」完全同形）。
            // 只做一次静默重试：网络抖动不再把页面永久钉在「这里什么都没有」，
            // 真实空源最多多等 [EMPTY_RETRY_MS]，第二次仍为空才显示空态。
            if (!categoryTabActive && list.isEmpty() && types.isEmpty() &&
                !emptyRetryUsed && !site.key.isNullOrEmpty()
            ) {
                emptyRetryUsed = true
                Log.w(TAG, "首页结果为空（可能是请求失败），${EMPTY_RETRY_MS}ms 后自动重试一次")
                pendingEmptyRetry?.let { handler.removeCallbacks(it) }
                val retry = Runnable {
                    pendingEmptyRetry = null
                    // 这 2.5 秒里用户可能已经切到分类 Tab：那次点击会重新请求分类，
                    // 这次「推荐」的自动重试必须作废，否则会把用户刚看的分类整页换掉。
                    if (!categoryTabActive) refreshHome("空结果自动重试", resetEmptyRetry = false)
                }
                pendingEmptyRetry = retry
                handler.postDelayed(retry, EMPTY_RETRY_MS)
                return@observe
            }
            renderHome(list, site)
        }
    }

    /**
     * 冷启动自愈：进程重启后 [VodConfig] 是空壳，先从本地已保存配置加载一次。
     *
     * 与 `HeroHomeActivity.ensureVodConfigLoaded` 同一逻辑：只在 `getHome().isEmpty()` 时触发，
     * 失败只记日志、页面落到空态，绝不弹窗。
     */
    private fun ensureVodConfigLoaded() {
        if (!VodConfig.get().getHome().isEmpty()) return
        Log.w(TAG, "冷启动 VodConfig 为空，尝试从本地已保存配置加载")
        // 【顺序契约】onCreate 里本方法先于 `refreshHome("onCreate")` 调用：
        // 先把「配置在途」的标记立起来，`refreshHome` 看到它就不会用空 Site 发请求。
        configLoadPending = true
        VodConfig.get().init().load(object : Callback() {
            override fun success() {
                configLoadPending = false
                Log.d(TAG, "本地配置加载完成: 站点数=${VodConfig.get().getSites().size}")
                refreshHome("冷启动配置加载完成")
            }

            override fun error(msg: String) {
                configLoadPending = false
                Log.w(TAG, "本地配置加载失败: $msg")
                // 真的没有可用源：明确显示空态，不能让用户对着转圈等。
                if (items.isEmpty()) {
                    showEmpty()
                    // 空态 + 一句「请先在设置中配置接口」+ 焦点送到顶栏「设置」。
                    showNoSourceHint()
                }
            }
        })
    }

    /**
     * 真正发起一次首页请求。
     *
     * [resetEmptyRetry] = false 只用于 [observeHome] 那一次自动重试：重试回来的空结果
     * 必须落到「空态」而不是再排一次重试（否则真实空源会无限请求）。
     */
    private fun refreshHome(reason: String, resetEmptyRetry: Boolean = true) {
        // 新的一次请求已经发起：还没执行的重试作废，避免 2.5 秒后又多打一轮。
        pendingEmptyRetry?.let { handler.removeCallbacks(it) }
        pendingEmptyRetry = null
        val site = VodConfig.get().getHome()
        loadedHomeSiteKey = site.key.orEmpty()
        // 推荐 Tab 的「已加载」标识跟着源走：换源后必须重新请求。
        loadedTabKey = HOME_TAB_PREFIX + loadedHomeSiteKey
        categoryTabActive = false
        if (resetEmptyRetry) emptyRetryUsed = false
        Log.d(TAG, "请求 homeContent（$reason）: key=${site.key} name=${site.displayName}")
        if (site.key.isNullOrEmpty()) {
            // 【冷启动硬伤】VodConfig 是纯内存单例，进程重启后「先空后满」。用空 Site 发
            // `homeContent()` 必然在 SiteApi 里抛异常，SiteViewModel 又把异常吞成 `Result.empty()`，
            // 页面就被钉死在「这里什么都没有」。所以：配置在途 → 保持 loading 等回调；
            // 没有在途加载 → 用户确实没配源，直接空态。
            Log.w(TAG, "当前接口源为空，暂不请求 homeContent：请先在设置页添加接口源")
            if (!configLoadPending && items.isEmpty()) {
                showEmpty()
                // 顶栏在 `showEmpty()` 里才恢复可见，提示必须放在它后面（否则焦点送不过去）。
                showNoSourceHint()
            }
            return
        }
        // 源已经配好：允许下一次「没源」再提示一次。
        noSourceHintShown = false
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
        // 停留位置也要夹回合法范围：换源后分类数量会变少。
        if (lastFocusedTabIndex > labels.lastIndex) lastFocusedTabIndex = 0
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
        // 数据落地：作废还没执行的重试，并把「一次空结果」的重试额度还回去。
        pendingEmptyRetry?.let { handler.removeCallbacks(it) }
        pendingEmptyRetry = null
        emptyRetryUsed = false
        focusedIndex = focusedIndex.coerceIn(0, next.lastIndex)
        posterAdapter.submit(next)
        // 推荐流（homeContent）成功 = 第 0 个 Tab「推荐」的内容：选中态必须跟着回到 0。
        // 否则从小房子 / onResume 重拉首页后，Tab 上还留着上一次分类的红框，与列表内容错位。
        if (!categoryTabActive) tabAdapter.selectedIndex = 0
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
        // 【顺序照抄原版 E2(pos)】先判横图地址，再决定竖图与 heroBg —— 两者互斥，不共帧。
        if (backdrop.isNullOrEmpty()) {
            // 没有真横图：清空 heroBg，竖图只放在右上角 posterCard 里。
            HeroImageView.setSharedUrl("")
            heroBg.reset()
            heroBg.visibility = View.GONE
            posterCard.visibility = View.VISIBLE
            loadPosterCard(item.posterUrl)
            return
        }
        // 有横图：**先**收竖图、**再**发请求。绝不能等 loadImage 回调成功后才藏，
        // 否则首帧（尤其是 upgrade = true 的 TMDB 升级那一拍）必闪一张竖图。
        posterCard.visibility = View.GONE
        HeroImageView.setSharedUrl(backdrop)
        heroBg.visibility = View.VISIBLE
        heroBg.loadImage(backdrop, position) {
            if (focusedPosterKey != item.posterKey) return@loadImage
            // 【落地判据】`lastUrl` 只在成功贴图后仍等于本地址（失败会被清成 null），
            // 所以它能区分「这张新图真的上屏了」和「保持着上一张旧图 / 什么都没画」。
            // 成功分支什么都不用做：竖图在发起请求之前就已经收掉了。
            if (heroBg.lastUrl == backdrop && heroBg.drawable != null) return@loadImage
            // 横图彻底失败（403 / 404 / 解码失败）：把竖图放回来，绝不留一整屏黑。
            // `upgrade` 分支多看一眼 `heroBg.drawable`：升级失败且 Hero 区什么都没有时必须回填。
            if (!upgrade || heroBg.drawable == null) {
                posterCard.visibility = View.VISIBLE
                loadPosterCard(item.posterUrl)
            }
        }
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
     * 方向键焦点回跳（逐条照抄 ref_docs/HomeActivityV2.java L1301-1384）：
     *
     * - 海报行 `L` ↑ → 分类栏 `K` 可见则 `t2()`（回到 `K` 上次停留的 Tab），否则回顶栏；
     * - 分类栏 `K` ↑ → 小房子 `b0`；
     * - 小房子 `b0` / 顶栏 `J` ↓ → 分类栏可见则回上次停留的 Tab，否则回海报行；
     * - 分类栏 `K` ↓ → 海报行 `L.requestFocus()`（回到行内上一次聚焦的卡）。
     *
     * **为什么放在 Activity 级**：`nextFocusUpId/DownId` 只在「被聚焦的 View 自己」上生效，
     * 焦点落在 Adapter 代码创建的子项上时容器属性不起作用，FocusFinder 会按几何去猜
     * （海报行 ↑ 常常落到最右边的「设置」）。在分发口拦截是唯一能 100% 命中原版语义的位置。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // ── 【长按锁】最开头：触发后到抬起之前，所有按键全部吞掉 ───────────────────
        // 抬起这一下也吞掉：卡片收不到 UP 就不会走 onClick，长按不会被当成一次短按点进详情。
        if (isLongPressTriggered) {
            if (event.action == KeyEvent.ACTION_UP) {
                isLongPressTriggered = false // 松开按键，解除锁定
                return true
            }
            return true // 没松开之前，所有按下事件（包括 repeatCount > 0 的连发）全部拦截
        }
        // ── 【按下】第一次按下（repeatCount == 0）起 1000ms 定时器 ─────────────────
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val key = event.keyCode
            // 只有海报区里的下键 / 确定键算长按；顶栏 / 分类栏的下键仍然走焦点路由。
            val longPressKey = key == KeyEvent.KEYCODE_DPAD_DOWN ||
                key == KeyEvent.KEYCODE_DPAD_CENTER ||
                key == KeyEvent.KEYCODE_ENTER
            if (longPressKey && recycler.findFocus() != null) {
                cancelLongPress()
                val runnable = Runnable { onPosterLongPressFired(key) }
                longPressRunnable = runnable
                handler.postDelayed(runnable, POSTER_LONG_PRESS_MS)
                // 下键由本方法独占（海报行向下没有可聚焦目标）；确定键继续下发，短按靠 View 的 onClick。
                if (key == KeyEvent.KEYCODE_DPAD_DOWN) return true
            }
        }
        // ── 【抬起】没到 1 秒就松手：撤定时器，事件放行（原短按逻辑照旧）───────────
        if (event.action == KeyEvent.ACTION_UP && longPressRunnable != null) {
            cancelLongPress()
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> if (onDpadUp()) return true
                KeyEvent.KEYCODE_DPAD_DOWN -> if (onDpadDown()) return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * 1000ms 到点：**先上锁**，再弹对应 Dialog —— 从这一刻起 [dispatchKeyEvent] 最开头的
     * [isLongPressTriggered] 分支会把后续所有按下 / 连发 / 抬起全部吞掉，直到用户松手。
     *
     * 对照 ref_docs/HomeActivityV2.dispatchKeyEvent：`this.V / this.W` 两个状态位 +
     * `removeCallbacks` / `postDelayed(..., getLongPressTimeout())`，抬起时统一清状态。
     */
    private fun onPosterLongPressFired(key: Int) {
        longPressRunnable = null
        isLongPressTriggered = true
        val item = items.getOrNull(focusedIndex) ?: return
        when (key) {
            KeyEvent.KEYCODE_DPAD_DOWN -> showTmdbManualMatch(item)     // 下键 → TMDB 手动匹配
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> showChangeSource(item) // 确定键 → 换源
        }
    }

    /** 撤掉还没到点的定时器（没触发就不上锁，按键继续按原逻辑走）。 */
    private fun cancelLongPress() {
        longPressRunnable?.let { handler.removeCallbacks(it) }
        longPressRunnable = null
    }

    /**
     * 长按确定键：换源弹窗（View 版 [ChangeSourceDialog]，候选项与 Compose 首页 `changeSource` 同一套规则）。
     */
    private fun showChangeSource(item: HomePosterItem) {
        val keyword = item.title
        if (keyword.isBlank()) {
            Notify.show("剧名为空，无法换源")
            return
        }
        val currentSiteKey = VodConfig.get().getHome().key
        var candidates = ChangeSourceDialog.candidates(currentSiteKey)
        // 兜底：当前源不可换 / 全被屏蔽时退回全部可搜索源，绝不静默失败。
        if (candidates.isEmpty()) candidates = VodConfig.get().getSites().filter { it.isSearchable }
        if (candidates.isEmpty()) {
            Notify.show(getString(R.string.play_change_empty))
            return
        }
        ChangeSourceDialog.show(
            context = this,
            viewModel = siteViewModel,
            sites = candidates,
            keyword = keyword,
            quick = false,
            onSourceSelected = { vod, site ->
                openHeroDetailOrFolder(
                    activity = this,
                    vod = vod,
                    site = site,
                    backdropUrl = "",
                    logoUrl = "",
                    overview = "",
                )
            },
            // 关窗后把焦点还给长按的那张卡，遥控器不会掉到窗口外。
            onClosed = { recycler.post { focusPoster(focusedIndex) } },
        )
    }

    /**
     * 长按方向下键：TMDB 手动匹配（复用 Compose 首页那份 [TmdbMatchDialog]，不另写第二套）。
     *
     * 弹窗是 Compose 组件，这里用 0×0 的 [ComposeView] 挂在 Activity 的 content 上承载组合
     * （与 `ChangeSourceDialog` 承载 `HeroPlayDialog` 的写法一致），真正的窗口由弹窗自己开。
     */
    private fun showTmdbManualMatch(item: HomePosterItem) {
        dismissTmdbMatchHost()
        val host = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            layoutParams = ViewGroup.LayoutParams(0, 0)
        }
        host.setContent {
            TmdbMatchDialog(
                initialKeyword = item.title,
                vod = item.vod,
                onConfirm = { tmdbItem ->
                    applyManualTmdbMatch(item, tmdbItem)
                    dismissTmdbMatchHost()
                    // 新条目立刻生效：标题 / 简介 / 横图 / 艺术字一次换齐。
                    applyHero(focusedIndex)
                },
                onDismiss = {
                    dismissTmdbMatchHost()
                    recycler.post { focusPoster(focusedIndex) }
                },
            )
        }
        (findViewById<ViewGroup>(android.R.id.content))?.addView(host)
        tmdbMatchHost = host
    }

    private fun dismissTmdbMatchHost() {
        val host = tmdbMatchHost ?: return
        tmdbMatchHost = null
        (host.parent as? ViewGroup)?.removeView(host)
        host.disposeComposition()
    }

    /**
     * 手动匹配结果落地：与 Compose 首页 `onConfirm` 同一套规则 ——
     * 同一条目保留简介 / Logo，换了条目整体作废；并写回 `TmdbMatchCache`（重启不丢）。
     */
    private fun applyManualTmdbMatch(item: HomePosterItem, tmdbItem: TmdbItem) {
        val prev = tmdbOverrides[item.posterKey]?.takeIf { it.item.tmdbId == tmdbItem.tmdbId }
        tmdbOverrides[item.posterKey] = HeroTmdbData(
            item = tmdbItem,
            detail = prev?.detail,
            cast = prev?.cast.orEmpty(),
            overview = prev?.overview.orEmpty().ifBlank { tmdbItem.overview },
            logoUrl = prev?.logoUrl,
            highResBackdrop = upgradeTmdbImage(
                tmdbItem.backdropUrl.ifBlank { tmdbItem.posterUrl },
                TMDB_BACKDROP_WIDTH,
            ),
            manual = true,
            rating = formatRating(tmdbItem.rating),
            year = extractYear(tmdbItem.subtitle),
        )
        runCatching {
            synchronized(Setting::class.java) {
                val cache = Setting.getTmdbMatchCache()
                val titles = listOf(item.vod.name, item.title).filter { it.isNotBlank() }.distinct()
                cache.putManual(item.site.key, item.vod.id.ifBlank { item.vod.name }, titles, tmdbItem)
                Setting.putTmdbMatchCache(cache)
            }
        }.onFailure { Log.w(TAG, "手动匹配落盘失败: ${item.title} | ${it.message}") }
    }

    private fun onDpadUp(): Boolean {
        // 海报行 ↑：回到上次停留的分类 Tab（原版 `return this.K.getVisibility() == 0 ? t2() : this.J.requestFocus()`）。
        if (recycler.findFocus() != null) return focusLastTabOrHome()
        // 分类 Tab ↑：交给小房子（原版 `this.b0.requestFocus()`）。
        if (typeRecycler.findFocus() != null) return homeBtn.requestFocus()
        return false
    }

    private fun onDpadDown(): Boolean {
        // 顶栏（小房子 / 文字项）↓：有分类栏 → 上次停留的 Tab；没有 → 直接进海报行第 0 张
        // （原版 L1313 `return this.K.getVisibility() == 0 ? t2() : this.L.requestFocus()`）。
        if (navBar.findFocus() != null) {
            return if (typeRecycler.visibility == View.VISIBLE) {
                focusTab(lastFocusedTabIndex)
            } else {
                focusFirstPoster()
            }
        }
        // 分类 Tab ↓：进入海报行，**焦点强制重置到第 0 张**（清单3，见 [focusFirstPoster]）。
        if (typeRecycler.findFocus() != null) return focusFirstPoster()
        return false
    }

    /** 海报行 ↑ 的公共分支：有分类栏 → 上次停留的 Tab；没有 → 小房子。 */
    private fun focusLastTabOrHome(): Boolean =
        if (typeRecycler.visibility == View.VISIBLE) focusTab(lastFocusedTabIndex) else homeBtn.requestFocus()

    /**
     * 【清单3】从顶栏 / 分类栏按 ↓ 进入海报行：焦点**强制**落到第 0 张，并清掉行内的焦点记忆。
     *
     * 原版 `HomeActivityV2` L1338 的分支是 `this.L.requestFocus()`：RecyclerView 会把焦点交给
     * 行内上一次聚焦的那张卡。用户要求「进入列表或切了分类后必须从第一张开始」，
     * 所以这里不用 `requestFocus()`，而是显式 `focusPoster(0)`：同时把 `focusedIndex` 归零，
     * Hero 区、TMDB 请求、下一次 ↑ 的分支都以第 0 张为准。
     */
    private fun focusFirstPoster(): Boolean {
        if (items.isEmpty()) return false
        focusedIndex = 0
        focusPoster(0)
        return true
    }

    /**
     * 【清单3】把海报行与 Hero 一起拉回第 0 张（切换分类 Tab 时调用）。
     *
     * 切回已经加载过的 Tab 时列表内容不会重载，Hero 必须在这里同步换到第 0 条；
     * 会重载时这次 `applyHero(0)` 只是一帧过渡，随后的 `renderHome` 会用新数据覆盖同一张。
     */
    private fun resetPosterToStart() {
        focusedIndex = 0
        recycler.scrollToPosition(0)
        if (items.isNotEmpty()) applyHero(0)
    }

    /** 把焦点交给第 [index] 个分类 Tab：holder 已挂上就直接抢，否则先定位、下一拍补抢（同 [focusPoster]）。 */
    private fun focusTab(index: Int): Boolean {
        if (tabAdapter.itemCount == 0) return false
        val safe = index.coerceIn(0, tabAdapter.itemCount - 1)
        val attached = typeRecycler.findViewHolderForAdapterPosition(safe)?.itemView
        if (attached != null && attached.isAttachedToWindow && attached.requestFocus()) return true
        typeRecycler.scrollToPosition(safe)
        typeRecycler.post {
            typeRecycler.findViewHolderForAdapterPosition(safe)?.itemView?.requestFocus()
        }
        return true
    }

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
        // 记住停留位置：海报行 ↑ / 小房子 ↓ 都要回到这一个是第几个 Tab。
        lastFocusedTabIndex = index
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
        lastFocusedTabIndex = index
        tabAdapter.selectedIndex = index
        scrollTabToCenter(index)
        // 【清单3】切换分类 = 清空海报行的焦点记忆：列表立刻回第 0 张，Hero 同步到第 0 条。
        // 分类数据是异步回来的，`renderHome` 落地时读到的 `focusedIndex` 已经是 0，
        // 所以「按 ↓ 进海报行」在切分类前后都一定从第一张开始，不会继承上一个分类的焦点位置。
        resetPosterToStart()
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
     * 焦点卡滚动：标准 [LinearSmoothScroller] 子类 + `SNAP_TO_CENTER`。
     *
     * 不再有任何手算边界：`dx` 只由「视口中心 - 目标项中心」决定，滚动量由 RecyclerView 自己
     * 夹到 `[0, maxScroll]`。行首因为左边有 24dp 内边距（`clipToPadding="false"`），
     * 前几张的目标中心本来就在视口中心左侧/附近，算出来的 dx ≤ 0，滚动量被夹成 0，自然贴边不滚；
     * 行尾同理贴右。中间段自然居中。
     */
    private fun scrollPostersTo(index: Int) {
        if (index !in items.indices) return
        val smoothScroller = CenterSmoothScroller(this)
        smoothScroller.targetPosition = index
        (recycler.layoutManager as LinearLayoutManager).startSmoothScroll(smoothScroller)
    }

    /** 把焦点交给第 [index] 张卡；目标还没布局出来就先瞬移定位，再在下一拍补抢。 */
    private fun focusPoster(index: Int) {
        if (index !in items.indices) return
        val attached = recycler.findViewHolderForAdapterPosition(index)?.itemView
        if (attached != null && attached.isAttachedToWindow && attached.requestFocus()) return
        recycler.scrollToPosition(index)
        recycler.post {
            val target = recycler.findViewHolderForAdapterPosition(index)?.itemView
            if (target != null && target.requestFocus()) return@post
            // 首屏数据刚落地的第一拍可能还没走完布局（holder 尚未挂上）：
            // 再等一拍补抢一次，保证「数据回来后第一张卡一定拿到焦点、拉起大图」。
            recycler.post {
                recycler.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus()
            }
        }
    }

    /**
     * 把第 [index] 个分类 Tab 平滑滚到视口正中（同一套 [CenterSmoothScroller]，首尾由 RecyclerView 夹住）。
     */
    private fun scrollTabToCenter(index: Int) {
        if (index !in 0 until tabAdapter.itemCount) return
        val smoothScroller = CenterSmoothScroller(this)
        smoothScroller.targetPosition = index
        (typeRecycler.layoutManager as LinearLayoutManager).startSmoothScroll(smoothScroller)
    }

    // ==================================================================
    // 小工具
    // ==================================================================

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private companion object {

        private const val TAG = "HomeView"

        // 顶栏顺序（对照 ref_docs/activity_home_v2.xml 的 navRecycler + 逆向截图图2）。
        // 第一项是「小房子」图标（homeBtn），文字项里**没有「首页」**：
        // 原版只有 直播 / 搜索 / 历史 / 收藏 / 推送 / 设置 六项。
        // 点击分派用这几条文案常量，不写裸串、不写下标。
        private const val NAV_LIVE = "直播"
        private const val NAV_SEARCH = "搜索"
        private const val NAV_HISTORY = "历史"
        private const val NAV_KEEP = "收藏"
        private const val NAV_PUSH = "推送"
        private const val NAV_SETTINGS = "设置"

        private val NAV_LABELS = listOf(
            NAV_LIVE, NAV_SEARCH, NAV_HISTORY, NAV_KEEP, NAV_PUSH, NAV_SETTINGS,
        )

        // 顶栏目标页只写类名、不引用类：`PushActivity` / `SettingActivity` 只存在于 leanback，
        // 本类在 `main` 源集里必须对 mobile 变体也能编译（详见 `openFlavorActivity`）。
        private const val LIVE_ACTIVITY_CLASS = "com.fongmi.android.tv.ui.activity.LiveActivity"
        private const val KEEP_ACTIVITY_CLASS = "com.fongmi.android.tv.ui.activity.KeepActivity"
        private const val PUSH_ACTIVITY_CLASS = "com.fongmi.android.tv.ui.activity.PushActivity"
        private const val SETTING_ACTIVITY_CLASS = "com.fongmi.android.tv.ui.activity.SettingActivity"

        /** 没有任何接口源时的引导文案。 */
        private const val NO_SOURCE_HINT = "请先在设置中配置接口"

        private const val TAB_RECOMMEND = "推荐"

        /** 分类 Tab 上限：与 Compose 版 FocusRequester 池（32）对齐，超出的分类不显示。 */
        private const val MAX_TABS = 32

        /** 分类 Tab 焦点切换的请求防抖：与 Compose 版同一个 220ms。 */
        private const val TAB_SWITCH_DEBOUNCE_MS = 220L

        /**
         * 海报行长按阈值：1000ms（原版用 `ViewConfiguration.getLongPressTimeout()`，
         * 这里按需求固定 1s，下键 / 确定键共用）。
         */
        private const val POSTER_LONG_PRESS_MS = 1000L

        /**
         * TMDB 焦点详情防抖：与 Compose 首页 `HERO_TMDB_FOCUS_DEBOUNCE_MS` 同一个 300ms。
         *
         * 方向键滑过一排时焦点每 ~50ms 换一张，停手 300ms 才发请求 —— 滑过不发。
         */
        private const val TMDB_FOCUS_DEBOUNCE_MS = 300L

        /**
         * 海报行间距：`ref_docs/HomeActivityV2.java` L776 `L.setHorizontalSpacing(g2.b(16))`。
         * 原版是 16dp（不是 Compose 版的 12dp）。
         */
        private const val POSTER_GAP_DP = 16

        /** 分类 Tab 间距：同文件 L786 `K.setHorizontalSpacing(g2.b(12))`。 */
        private const val TAB_GAP_DP = 12

        /**
         * 顶栏文字间距：同文件 L780 `J.setHorizontalSpacing(g2.b(16))`。
         *
         * `ref_docs/home_ui.png` 实测（1920×1080 @2x，即 960×540dp）：相邻顶栏文字中心距 156px = 78dp，
         * 减去文本 advance ≈30dp 与左右各 16dp 内边距，正好是 16dp。
         */
        private const val TOP_BAR_ITEM_GAP_DP = 16

        /**
         * 首页结果「list 和 types 都为空」时的自动重试延时。
         *
         * `SiteViewModel.execute` 的失败分支派发的是 `Result.empty()`（没有 msg），
         * 与「源站确实没有首页」在回调里无法区分，所以只做一次静默重试，不无限轮询。
         */
        private const val EMPTY_RETRY_MS = 2500L

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
 * 不新建 item XML：两项都是用代码创建的 TextView（顶栏再包一层竖列放焦点红色短横线），
 * 少两份布局文件，也避免与全局海报卡的尺寸规则混在一起。
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

    /**
     * 分类 Tab 三态颜色：**全部**来自 `colors.xml` 的 `home_tab_*`（每个值的取色来源都写在那里的注释里），
     * 代码里不再出现任何十六进制字面量。
     */
    private val tabFocusGradient = intArrayOf(
        ContextCompat.getColor(context, R.color.home_tab_focus_start),
        ContextCompat.getColor(context, R.color.home_tab_focus_end),
    )
    private val tabStrokeColor = ContextCompat.getColor(context, R.color.home_tab_stroke)
    private val tabIdleSurface = ContextCompat.getColor(context, R.color.home_tab_idle_surface)
    private val tabIdleText = ContextCompat.getColor(context, R.color.home_tab_idle_text)

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

    /** 该下标的文案：点击 / 确定键按文案分派，不硬编码下标。 */
    fun labelAt(position: Int): String? = items.getOrNull(position)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        when (style) {
            HomeChipStyle.NAV -> createNavHolder(parent)
            HomeChipStyle.TAB -> createTabHolder(parent)
        }.also { holder ->
            holder.text.setOnClickListener {
                val position = holder.bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onSelected(position)
            }
            holder.text.setOnFocusChangeListener { _, hasFocus ->
                val position = holder.bindingAdapterPosition
                if (hasFocus && position != RecyclerView.NO_POSITION) onFocused(position)
                applyVisual(holder, hasFocus, position)
                if (style == HomeChipStyle.NAV) {
                    // 顶栏焦点态整体平滑放大 1.1 倍（只走绘制层，不动布局）。
                    // 缩放对象是「文字 + 下划线」整列，下划线与文字始终同心。
                    holder.root.animate()
                        .scaleX(if (hasFocus) TOP_BAR_FOCUS_SCALE else 1f)
                        .scaleY(if (hasFocus) TOP_BAR_FOCUS_SCALE else 1f)
                        .setDuration(FOCUS_ANIM_MS)
                        .start()
                }
            }
        }

    /**
     * 顶栏文字项：竖列 = [24dp 文字区] + 6dp 间距 + [26×2dp 红色短横线]，
     * 顶部再预留 8dp（= 间距 + 横线高），使 24dp 文字区的中心正好落在 64dp 行高的中线上
     * （与 Compose 版 `TOP_BAR_UNDERLINE_RESERVE / TEXT_BOX / GAP / HEIGHT` 逐值一致）。
     *
     * 横线常驻布局、只切透明度：焦点切换时文字宽度变化不会把横线顶跑，也不会顶动整行。
     */
    private fun createNavHolder(parent: ViewGroup): Holder {
        val context = parent.context
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // 竖列里 gravity=CENTER 同时负责「整块垂直居中」与「子项水平居中」。
            gravity = Gravity.CENTER
            clipChildren = false
            clipToPadding = false
            setPadding(0, dp(TOP_BAR_UNDERLINE_RESERVE_DP), 0, 0)
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        val text = newTextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(TOP_BAR_TEXT_BOX_DP),
            )
            setTextSize(TypedValue.COMPLEX_UNIT_SP, NAV_TEXT_SP)
            setTextColor(TOP_BAR_IDLE_TEXT)
            typeface = NAV_IDLE_TYPEFACE
            // 首个文字距 navRecycler 左沿 17dp（含字形 side bearing）→ 16dp 内边距。
            setPadding(dp(NAV_ITEM_PADDING_H_DP), 0, dp(NAV_ITEM_PADDING_H_DP), 0)
        }
        val underline = View(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(TOP_BAR_UNDERLINE_RADIUS_DP).toFloat()
                setColor(accent)
            }
            // 常驻布局，焦点态只改透明度（原版 150ms 淡入淡出）。
            alpha = 0f
            layoutParams = LinearLayout.LayoutParams(
                dp(TOP_BAR_UNDERLINE_WIDTH_DP),
                dp(TOP_BAR_UNDERLINE_HEIGHT_DP),
            ).apply {
                topMargin = dp(TOP_BAR_UNDERLINE_GAP_DP)
            }
        }
        column.addView(text)
        column.addView(underline)
        return Holder(root = column, text = text, underline = underline)
    }

    /**
     * 分类 Tab：逐项照抄 `ref_docs/adapter_type.xml` —— 13sp、`layout_height="26dp"`、宽 wrap_content。
     *
     * 三态底色（原版 `selector_item_round.xml` 的 focused / selected / normal）在 Kotlin 侧由
     * [tabBackground] 生成，见该方法与 `colors.xml` 的 `home_tab_*`；这里只做首帧的常态底。
     *
     * 左右留白：`setBackground` 会把 drawable 自带的 padding 写进 View，但这里用的
     * `GradientDrawable` 没有自带 padding，所以水平留白恒为实测的 12dp。
     */
    private fun createTabHolder(parent: ViewGroup): Holder {
        val text = newTextView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(TAB_ITEM_HEIGHT_DP),
            )
            setTextSize(TypedValue.COMPLEX_UNIT_SP, TAB_TEXT_SP)
            setTextColor(tabIdleText)
            background = tabBackground(focused = false, selected = false)
            setPadding(dp(TAB_ITEM_PADDING_H_DP), 0, dp(TAB_ITEM_PADDING_H_DP), 0)
        }
        return Holder(root = text, text = text, underline = null)
    }

    /** 两种 item 共用的文本基座：可聚焦、去掉系统焦点高亮、无状态动画。 */
    private fun newTextView(context: Context): TextView = TextView(context).apply {
        gravity = Gravity.CENTER
        includeFontPadding = false
        isFocusable = true
        isFocusableInTouchMode = true
        // API 26 才有的方法，minSdk 24 必须判版本。
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            defaultFocusHighlightEnabled = false
        }
        stateListAnimator = null
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.text.text = items[position]
        applyVisual(holder, holder.text.isFocused, position)
    }

    /**
     * 焦点视觉（照抄 Compose 版 `HeroTopBarItem`，L1730-1816）：
     * - 顶栏：常态白 60% + Medium；焦点纯白 + SemiBold（系统 `sans-serif` BOLD）+ 下方红色短横线。
     *   缩放在焦点监听里做，只作用于绘制层，下划线不会跳动。
     * - 分类 Tab：严格三态 —— 聚焦 = 焦点实心红（横向渐变）；选中未聚焦 = 透明底 + 红框；
     *   其余 = 深色半透明胶囊底。三种底色都由 [tabBackground] 生成。
     */
    private fun applyVisual(holder: Holder, focused: Boolean, position: Int) {
        val text = holder.text
        when (style) {
            HomeChipStyle.NAV -> {
                text.setTextColor(if (focused) Color.WHITE else TOP_BAR_IDLE_TEXT)
                text.typeface = if (focused) NAV_FOCUS_TYPEFACE else NAV_IDLE_TYPEFACE
                // 横线始终占位（2dp + 6dp 间距），这里只切透明度：文字宽度变化不会顶动它。
                holder.underline?.alpha = if (focused) 1f else 0f
            }
            HomeChipStyle.TAB -> {
                // position 可能是 NO_POSITION(-1)，必须排除，否则会把 -1 当成「已选中」。
                val selected = position >= 0 && position == selectedIndex
                // 三态严格判断，顺序不能换：
                //   1. hasFocus            → 焦点实心红（横向渐变），白字
                //   2. 未聚焦 && 已选中     → 透明底 + 红框描边，白字
                //   3. 其余                → 深色半透明胶囊底，暗白字
                text.isSelected = selected
                text.background = tabBackground(focused, selected)
                text.setTextColor(if (focused || selected) Color.WHITE else tabIdleText)
            }
        }
    }

    /**
     * 分类 Tab 胶囊背景 —— 三态唯一的生成入口，颜色全部取自 `colors.xml` 的 `home_tab_*`：
     * - [focused]：焦点实心红，**横向 2 段渐变** `home_tab_focus_start → home_tab_focus_end`
     *   （ref_docs/tab_ui.png 实测，禁止用纯色或 3 段近似）；
     * - 选中未聚焦：**透明底** + [TAB_STROKE_DP] 描边 `home_tab_stroke`；
     * - 其余：`home_tab_idle_surface` 深色半透明胶囊底。
     * 圆角照抄 `ref_docs/shape_chip_round_normal.xml` 的 28dp（26dp 高的胶囊被夹成半圆端）。
     */
    private fun tabBackground(focused: Boolean, selected: Boolean): GradientDrawable {
        val radius = dp(TAB_PILL_RADIUS_DP).toFloat()
        if (focused) {
            return GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, tabFocusGradient).apply {
                cornerRadius = radius
            }
        }
        return GradientDrawable().apply {
            cornerRadius = radius
            setColor(if (selected) Color.TRANSPARENT else tabIdleSurface)
            if (selected) setStroke(dp(TAB_STROKE_DP), tabStrokeColor)
        }
    }

    private fun dp(value: Int): Int = (value * density).roundToInt()

    /**
     * 两种 item 的统一 holder：[root] 是缩放 / 焦点态的作用对象，
     * [underline] 只有顶栏有（分类 Tab 为 null）。
     */
    inner class Holder(
        val root: View,
        val text: TextView,
        val underline: View?,
    ) : RecyclerView.ViewHolder(root)

    private companion object {
        /**
         * 顶栏字号：ref_docs/home_ui.png 实测墨迹高 29px @2x ≈ 14.5dp，
         * 16sp 的中文字形墨迹约 0.88em ≈ 14.1dp，两者吻合；也与 Compose 版 `TOP_BAR_SP = 16f` 一致。
         */
        private const val NAV_TEXT_SP = 16f

        /** 顶栏 item 水平内边距：实测首个字形距 navRecycler 左沿 17dp（含 side bearing）。 */
        private const val NAV_ITEM_PADDING_H_DP = 16

        /** 顶栏文字区固定高度：Compose `TOP_BAR_TEXT_BOX = 24dp`（16sp 字形的上沿高度）。 */
        private const val TOP_BAR_TEXT_BOX_DP = 24

        /** 焦点红色短横线：Compose `TOP_BAR_UNDERLINE_WIDTH/HEIGHT` = 26dp × 2dp。 */
        private const val TOP_BAR_UNDERLINE_WIDTH_DP = 26
        private const val TOP_BAR_UNDERLINE_HEIGHT_DP = 2

        /** 文字与短横线之间的间距：Compose `TOP_BAR_UNDERLINE_GAP = 6dp`。 */
        private const val TOP_BAR_UNDERLINE_GAP_DP = 6

        /** 短横线端部圆角：ref_docs/shape_red_bar.xml `corners radius=2dp`。 */
        private const val TOP_BAR_UNDERLINE_RADIUS_DP = 2

        /** 顶部预留 = 间距 + 横线高（Compose `TOP_BAR_UNDERLINE_RESERVE`）：保证文字区与 64dp 行同心。 */
        private const val TOP_BAR_UNDERLINE_RESERVE_DP = TOP_BAR_UNDERLINE_GAP_DP + TOP_BAR_UNDERLINE_HEIGHT_DP

        /** 分类 Tab 字号：ref_docs/adapter_type.xml `android:textSize="13sp"`。 */
        private const val TAB_TEXT_SP = 13f

        /** 分类 Tab 高度：同文件 `android:layout_height="26dp"`。 */
        private const val TAB_ITEM_HEIGHT_DP = 26

        /**
         * 分类 Tab 水平内边距：12dp（`ref_docs/tab_ui.png` 实测，与 Compose `HERO_TAB_H_PADDING` 一致）。
         *
         * 原版这份留白一半来自 shape 的 padding（`ref_docs/shape_chip_round_*.xml` 6dp），
         * 一半来自 `CustomTypeView` 自己；这里直接写总宽 12dp 并放在 setBackground 之后，
         * 几何与原版一致、又不受 drawable padding 覆盖规则的影响。
         */
        private const val TAB_ITEM_PADDING_H_DP = 12

        private const val TOP_BAR_FOCUS_SCALE = 1.1f

        /** 顶栏常态文字：纯白 60%（Compose `TOP_BAR_IDLE_ALPHA = 0.6f` → alpha 0x99）。 */
        private const val TOP_BAR_IDLE_TEXT = 0x99FFFFFF.toInt()

        /** 常态字重 Medium（500）；焦点 SemiBold 用系统粗体近似（Compose `FontWeight.Medium / SemiBold`）。 */
        private val NAV_IDLE_TYPEFACE: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        private val NAV_FOCUS_TYPEFACE: Typeface = Typeface.create("sans-serif", Typeface.BOLD)

        /** 分类 Tab 胶囊圆角：`ref_docs/shape_chip_round_normal.xml` `corners radius=28dp`。 */
        private const val TAB_PILL_RADIUS_DP = 28

        /** 分类 Tab 选中描边宽度：工程内同族 `shape_source_site_chip_selected.xml` 2dp。 */
        private const val TAB_STROKE_DP = 2

        private const val FOCUS_ANIM_MS = 150L
    }
}

/**
 * 居中平滑滚动器：海报行与分类 Tab 行共用。
 *
 * 逻辑与作业给定代码逐字一致 —— `SNAP_TO_CENTER` + 「视口中心 - 目标项中心」，
 * 不含任何 `position < N` / `itemCount - N` 之类的边界计算。首尾贴边完全由
 * RecyclerView 自身的滚动夹取与 `clipToPadding="false"` 的 24dp 内边距决定。
 */
class CenterSmoothScroller(context: Context) : LinearSmoothScroller(context) {
    override fun getHorizontalSnapPreference(): Int = SNAP_TO_START
    override fun getVerticalSnapPreference(): Int = SNAP_TO_START

    override fun calculateDxToMakeVisible(view: View, snapPreference: Int): Int {
        val manager = layoutManager ?: return 0
        val viewportCenter = manager.paddingLeft + (manager.width - manager.paddingLeft - manager.paddingRight) / 2
        val targetCenter = (view.left + view.right) / 2
        return viewportCenter - targetCenter
    }
}

