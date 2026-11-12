package com.fongmi.android.tv.ui.activity

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
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
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
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
import com.fongmi.android.tv.server.Server
import com.fongmi.android.tv.service.ManageService
import com.fongmi.android.tv.service.PlaybackService
import com.fongmi.android.tv.service.TmdbService
import com.fongmi.android.tv.setting.Setting
import com.fongmi.android.tv.setting.SiteBlockSetting
import com.fongmi.android.tv.setting.TmdbSitePolicy
import com.fongmi.android.tv.ui.audio.AudioMiniPlayer
import com.fongmi.android.tv.ui.custom.HeroImageView
import com.fongmi.android.tv.ui.custom.ProgressLayout
import com.fongmi.android.tv.ui.dialog.TmdbMatchViewDialog
import com.fongmi.android.tv.ui.helper.TmdbMatcher
import com.fongmi.android.tv.ui.hero.ChangeSourceDialog
import com.fongmi.android.tv.ui.hero.HeroHorizontalGapDecoration
import com.fongmi.android.tv.ui.hero.HeroImageLoader
import com.fongmi.android.tv.ui.hero.HeroPosterAdapter
import com.fongmi.android.tv.ui.hero.HeroPosterCard
import com.fongmi.android.tv.ui.hero.HeroSourceEntry
import com.fongmi.android.tv.ui.hero.HeroTmdbData
import com.fongmi.android.tv.ui.hero.HeroTmdbPrefetchCache
import com.fongmi.android.tv.ui.hero.HomePosterItem
import com.fongmi.android.tv.ui.hero.SourceSidebar
import com.fongmi.android.tv.ui.hero.SourceSidebarMode
import com.fongmi.android.tv.ui.hero.heroTmdbKey
import com.fongmi.android.tv.ui.hero.openHeroDetailOrFolder
import com.fongmi.android.tv.ui.hero.upgradeTmdbImage
import com.fongmi.android.tv.utils.Notify
import com.fongmi.android.tv.utils.Util
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
 * - **焦点驱动的 TMDB 详情**（第三步）：焦点一变就掐旧发新，并预取 `i-2 … i+2` 的滑动窗口，
 *   结果经 `posterKey` 校验才落地，详见 [requestFocusedTmdbDetail] / [onTmdbDetailReady]。
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
    // 海报行长按状态机（逐字对应 ref_docs/HomeActivityV2.java L231-232 / L1236-1249 / L1344-1360）
    //   V = isKeyDown            按下中、还没抬起
    //   W = isLongPressTriggered  长按已经触发
    //   U = longPressCenterRunnable  长按确定键 → 换源
    //   X = longPressDownRunnable    长按下键   → TMDB 手动匹配
    // ------------------------------------------------------------------
    /** 原版 `V`：按下中（`repeatCount == 0` 时置 true，抬起时清掉）。 */
    private var isKeyDown = false

    /** 原版 `W`：长按已触发（Runnable 到点置 true，抬起时清掉）。 */
    private var isLongPressTriggered = false

    /** 原版 `U`：长按确定键 → 换源弹窗。 */
    private val longPressCenterRunnable = Runnable { onPosterLongPressFired(KeyEvent.KEYCODE_DPAD_CENTER) }

    /** 原版 `X`：长按下键 → TMDB 手动匹配弹窗。 */
    private val longPressDownRunnable = Runnable { onPosterLongPressFired(KeyEvent.KEYCODE_DPAD_DOWN) }

    /** 长按阈值：写死 1000ms（防误触，不用系统 500ms 的默认长按时长）。 */
    private val posterLongPressMs = 1000L

    /**
     * TMDB 手动匹配弹窗（纯 View）实例。
     *
     * 直接 `TmdbMatchViewDialog.create(this).show()`，不再经过 Compose 桥接；
     * 保留引用只为在 [onDestroy] 里主动关窗，避免 WindowLeaked。
     */
    private var tmdbMatchDialog: TmdbMatchViewDialog? = null

    /**
     * TMDB 弹窗是否正在显示。
     *
     * [dispatchKeyEvent] 第一行据此吞掉**所有**按键，绝不允许焦点漏到底层海报列表：
     * 呼出前置 true，弹窗 dismiss 回调里置 false。
     */
    private var isTmdbShowing = false

    /**
     * TMDB 弹窗代号：每次呼出自增。
     *
     * `Dialog.dismiss()` 的 dismiss 回调是 Handler 异步消息，旧弹窗的回调可能**晚于**
     * 新弹窗的 `show()` 才执行；没有这层校验，它会把新弹窗刚置上的 [isTmdbShowing]
     * 复位成 false，于是新弹窗整个生命周期都不再拦截，按键重新漏到底层海报列表。
     */
    private var tmdbDialogSerial = 0

    /**
     * 横图同步兜底缓存：`posterKey → 已解析横图`。
     *
     * `bindBackdrop` 在**首帧绘制之前**同步查一次磁盘匹配缓存；命中后写进这里，
     * 焦点来回移动时不必重复读盘（`Setting.getTmdbMatchCache()` 是 synchronized 的读盘 + Gson 解析）。
     * 每次 `renderHome` 换数据时清空，避免换源后拿到旧地址。
     */
    private val syncBackdrops = HashMap<String, String>()

    // ------------------------------------------------------------------
    // XML 节点
    // ------------------------------------------------------------------
    private lateinit var heroBg: HeroImageView
    private lateinit var heroNameText: TextView
    private lateinit var heroLogoImg: ImageView
    private lateinit var heroMetaText: TextView
    private lateinit var heroDescText: TextView
    /**
     * `heroInfo`：Hero 文案整块（标题 / 艺术字 / 元数据 / 简介）。
     *
     * 下探目录时按原版 `HomeActivityV2.f()` 的做法把整块收起（原版对应的是 Hero 区的可见性切换），
     * 出栈 / 切 Tab / 换源时再恢复。
     */
    private lateinit var heroInfo: View
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

    /**
     * 海报行的翻页状态（原版 `HomeActivityV2` 的 `T0` / `R0` / `V0` / `U0` 四件套）。
     *
     * [PagingState.typeId] = null 表示**这个列表没有页码**：首页「推荐」走 `homeContent()`，
     * 而站点接口 `Spider.homeContent(boolean)` 根本没有页码参数，原版同样不给推荐流翻页
     * （`o2()` 的第一条守卫就是 `T0 == null` 直接 return）。分类 Tab / 目录下探才有页码。
     */
    private data class PagingState(
        /** 当前列表的 typeId（原版 `T0`）；null = 这个列表不分页。 */
        val typeId: String? = null,
        /** 当前列表所属接口源：换源后迟到的下一页结果必须丢弃。 */
        val siteKey: String = "",
        /** 已经显示的页码（原版 `R0` 的计数器）；1 = 只有首屏。 */
        val page: Int = 1,
        /** 后面还有没有页（原版 `V0`）。 */
        val hasMore: Boolean = false,
        /** 下一页请求在途（原版 `U0`）。 */
        val loading: Boolean = false,
        /** 在途的这次请求是「追加」而不是「首屏」：结果落地走 [appendPage]。 */
        val appending: Boolean = false,
    )

    /** 当前海报行的翻页状态；每次换列表（换源 / 切 Tab / 进出目录）都要重开一份。 */
    private var paging = PagingState()

    /**
     * 原版 `HomeActivityV2.c1`：首页下探栈。
     *
     * 每进一层目录，先把**上一层整页状态**压进来（原版 `u31`：列表、选中位、Tab、焦点标记）；
     * 返回键按原版 `U1()`（L1089-1113）出栈，把列表 / 焦点原样恢复，不重新请求。
     * 空栈 = 当前停在首页根页。
     */
    private val folderStack = ArrayDeque<FolderDrillState>()

    /**
     * 首页「双击返回退出」上一次按下返回键的时间戳（毫秒）。
     *
     * 只在 [onBackPressed] 里读写：0 = 当前不在确认窗口内。
     * 目录出栈 / 关闭弹窗这类「消费掉返回键」的操作会把它清零，避免刚退出目录再按一次就误退出。
     */
    private var lastBackPressTime = 0L

    /** 原版 `u31`：一层目录的整页快照。 */
    private data class FolderDrillState(
        val items: List<HomePosterItem>,
        val focusedIndex: Int,
        val tabIndex: Int,
        val lastFocusedTabIndex: Int,
        /**
         * 上一层的翻页状态（原版 `u31.j` 存的就是 `R0` 里的页码）。
         *
         * 出栈恢复它，返回上一层后还能继续往下翻，而不是退回「只有首屏、没有下一页」。
         */
        val paging: PagingState,
    )

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

    /**
     * 焦点请求 Job：每次焦点落到新卡片都「先掐断上一条、再发这一条」（原版 `getAndSet` 语义）。
     * 没有延时、没有排队：上一条在 `cancel()` 的瞬间就结束，永远不会堆在后台。
     */
    private var tmdbRequestJob: Job? = null

    /** 滑动窗口 `i-2 … i+2`：下标 → 该位置的预取 Job，出窗即取消。 */
    private val tmdbWindowJobs = mutableMapOf<Int, Job>()

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
        pendingEmptyRetry?.let { handler.removeCallbacks(it) }
        pendingEmptyRetry = null
        // 长按的两个定时器与 Compose 弹窗宿主都必须跟着 Activity 一起撤掉，否则回调会打到已销毁的 View。
        recycler.removeCallbacks(longPressDownRunnable)
        recycler.removeCallbacks(longPressCenterRunnable)
        isKeyDown = false
        isLongPressTriggered = false
        // 纯 View 弹窗随 Activity 一起收尾：先关窗再复位拦截标志，避免 WindowLeaked 与残留吞键。
        tmdbMatchDialog?.dismiss()
        tmdbMatchDialog = null
        isTmdbShowing = false
        // 在途 TMDB 请求（焦点那条 + 整个滑动窗口）一起作废：Activity 已经没了，回调绝不能再去碰 View。
        tmdbRequestJob?.cancel()
        tmdbWindowJobs.values.forEach { it.cancel() }
        tmdbWindowJobs.clear()
        // 取消两个长生命周期 ImageView 上的在途请求（回收站里的是 Adapter 自己的职责）。
        heroLogoImg.dispose()
        posterCardImg.dispose()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // 1. 目录里按返回：先 `U1()` 出栈回到上一层（原版行为），不算「退出」。
        //    侧边栏 / 匹配弹窗都是独立 Dialog 窗口，系统返回键先由它们自己消费，
        //    走不到这里时不需要额外探测。
        if (exitFolder()) {
            lastBackPressTime = 0L
            return
        }
        // 2. 已经停在首页根页：2 秒内连按两次才真正退出 App（杀进程，不留后台）。
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastBackPressTime < EXIT_CONFIRM_WINDOW_MS) {
            lastBackPressTime = 0L
            exitCompletely()
            return
        }
        lastBackPressTime = currentTime
        Toast.makeText(this, R.string.app_exit_confirm, Toast.LENGTH_SHORT).show()
    }

    /**
     * 彻底退出：停后台服务 → 清空本应用任务栈 → 杀进程。
     *
     * 只 `finish()` / `super.onBackPressed()` 的后果就是用户看到的「退到后台，进程还在」：
     * 播放服务（支持后台播放）、本地 HTTP 服务、下载 / 推送等后台组件仍然活着，通知栏还能继续播。
     * 这里先按原版 `AppExitCoordinator.exit` 的同一顺序优雅收尾，再 `killProcess` 保证进程和
     * 所有后台线程立即结束，下次启动就是真正的冷启动。
     *
     * **`killProcess` 必须放最后**：它之后本方法不会再返回，写在它后面的清理永远不会执行。
     */
    private fun exitCompletely() {
        runCatching {
            val service = Server.get().service
            AudioMiniPlayer.deactivateForFull(service)
            App.stopBackgroundServices()
            ManageService.stop(this)
            PlaybackService.shutdown(this)
            Server.get().shutdown()
        }.onFailure { Log.w(TAG, "退出前清理后台服务失败: ${it.message}") }
        finishAffinity()
        android.os.Process.killProcess(android.os.Process.myPid())
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
        heroInfo = findViewById(R.id.heroInfo)
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
            // 顶栏环形焦点：最后一项「设置」再按 → 回到左侧小房子。
            onRightEdge = { homeBtn.requestFocus() },
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
            // 选中位变化要同步刷到已附着的 Tab 上（不发 notify，避免异步重绑留下选中红框残影）。
            host = typeRecycler,
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
        // 小房子 / 菜单键共用同一个入口：全局接口源侧边栏。
        homeBtn.setOnClickListener { openSourceSidebar() }
        // 顶栏环形焦点：小房子再按 ← 回到顶栏最后一项「设置」。
        // 焦点链在 [wireFocusChain] 里只声明了「小房子 → 右 = navRecycler」，
        // 没有方向键监听时 ← 会被系统几何搜索吞掉（往右 / 往上找候选），所以这里显式接住。
        // 注意 `View.OnKeyListener.onKey(v, keyCode, event)` 是**三参数**，少写一个参数会被当成 keyCode。
        homeBtn.setOnKeyListener { _, _, event ->
            if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                focusNavItem(navAdapter.itemCount - 1)
                true
            } else {
                false
            }
        }
    }

    /**
     * 全局接口源侧边栏（原 Compose 首页 `HeroTopBar.onHomeConfirm` 的同一套实现）。
     *
     * 列出全部可用源（隐藏源 / 用户屏蔽源由 `SiteBlockSetting.filter(…, false)` 过滤），单选模式，
     * 当前首页源保持选中态并作为默认焦点。`SourceSidebar` 是独立 Dialog 窗口，会自己抢焦点。
     *
     * 关闭后焦点必须还给首页：优先还原打开前的 `currentFocus`（菜单键可能从海报卡呼出），
     * 取不到才回落到小房子。单一还原函数保证「选中切换」与「直接关闭」两条路径一致。
     */
    private fun openSourceSidebar() {
        val homeSites = SiteBlockSetting.filter(VodConfig.get().getSites(), false)
        if (homeSites.isEmpty()) {
            Notify.show(getString(R.string.play_change_empty))
            return
        }
        val previousFocus: View = currentFocus ?: homeBtn
        // 拿到实例才能在回调里「先销毁再切源」：窗口一旦留在屏幕上，后面的 setHome + 重拉首页
        // 会全部在它背后发生，用户看到的就是弹窗盖在新首页上。
        var sidebar: SourceSidebar? = null
        sidebar = SourceSidebar.show(
            context = this,
            entries = HeroSourceEntry.of(homeSites),
            mode = SourceSidebarMode.Single,
            currentSiteKey = VodConfig.get().getHome().key,
            onSiteSelected = { site ->
                // 1.【绝对第一步】关闭并销毁窗口。`dismiss()` 是真正销毁（Dialog 窗口，不走
                //   addView，所以不需要 removeView），且幂等 —— 侧边栏自己在确定键时也先关一次，
                //   这里再兜一次，保证任何进入路径都不会留一层窗口盖在首页上。
                sidebar?.dismiss()
                sidebar = null
                val current = VodConfig.get().getHome()
                if (site.key == current.key) {
                    // 选中的就是当前源：不重复发请求，只给个反馈。
                    Notify.show(getString(R.string.source_sidebar_switched, site.displayName))
                } else {
                    // 2. 切换数据源。
                    Log.d(TAG, "侧边栏切换接口源: ${current.key} -> ${site.key}")
                    VodConfig.get().setHome(site)
                    // 3. 重新加载数据。`setHome` 只写配置并 post `RefreshEvent.home()`，
                    //    本页不订阅 EventBus，必须自己再拉一次，否则列表还是旧源的数据。
                    refreshHome("侧边栏切换接口源 ${site.displayName}")
                    Notify.show(getString(R.string.source_sidebar_switched, site.displayName))
                }
                // 4. 焦点归位：窗口已经销毁，焦点必须显式还给打开它的位置。
                restoreFocus(previousFocus)
            },
            onClosed = { restoreFocus(previousFocus) },
        )
    }

    /** 侧边栏关闭后把焦点还给首页；目标已不可聚焦时回落到小房子。 */
    private fun restoreFocus(target: View) {
        target.post {
            if (!target.requestFocus()) homeBtn.post { homeBtn.requestFocus() }
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
     * - 在 `main` 源集里（搜索 / 历史 / 收藏）：本类可以直接引用，且与已验收的纯 View 页面同一条路由
     *   （`HeroSearchActivity` / `HistoryViewActivity` / `KeepViewActivity`），不退回 leanback 的旧页面；
     * - 只在某个 flavor 里（直播 / 推送 / 设置）：见 [openFlavorActivity]。
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
            NAV_HISTORY -> HistoryViewActivity.start(this)
            NAV_KEEP -> KeepViewActivity.start(this)
            NAV_PUSH -> openFlavorActivity(PUSH_ACTIVITY_CLASS, Activity::class.java, label)
            NAV_SETTINGS -> openFlavorActivity(SETTING_ACTIVITY_CLASS, Activity::class.java, label)
        }
    }

    /**
     * 打开**只存在于某个 flavor** 的页面（直播 / 推送 / 设置）。
     *
     * 本类在 `main` 源集、两个变体都要编译，而 `PushActivity` / `SettingActivity` 只在
     * `app/src/leanback/`（mobile 清单里没有声明），直接写类名会让 mobile 变体编译失败。
     * 因此沿用全项目已有做法（`HeroHomeActivity.openSettings`）：`Class.forName` + 反射调
     * `start(...)`；失败不崩，只提示。
     *
     * [paramType] 必须与目标类 `start` 的形参**完全一致**：
     * `LiveActivity.start(Context)`，`PushActivity` / `SettingActivity` 都是 `start(Activity)`。
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
            // 【翻页追加】这次结果是「下一页」：只往行尾追加，绝不重走首页 / 目录的首屏逻辑
            // （原版 `T1(Result)` 的 `z` 分支，L927-983：`B0` 只 `addAll`、不 `clear`，
            //  Tab 栏 / 空态 / 自动分类一概不碰）。必须在「下探目录」判断之前分派。
            if (paging.appending) {
                appendPage(list, site, result.pageCount)
                return@observe
            }
            // 【下探中】这次结果属于当前目录：只替换海报行，绝不改 Tab 栏 / 首页状态，
            // 也绝不重新走首页的空态 / 自动分类逻辑（原版 `B2(List)`：只 `B0.clear/addAll`
            // + `D2(0,...)` 重画第 0 条）。
            if (folderStack.isNotEmpty()) {
                renderFolder(list, site, result.pageCount)
                return@observe
            }
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
                        // 这一页内容来自分类接口（不是 homeContent）：记住 typeId，
                        // 行尾才能继续下探第 2、3… 页（原版 L888-892 同一处理）。
                        resetPaging(first.typeId, site.key.orEmpty())
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
            renderHome(list, site, result.pageCount)
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
        // 换源 / 回首页重拉：目录视图整体作废。目录卡片必须跟着清掉，
        // 否则新源的首页上会继续挂着上一层目录的数据（视觉上的「满屏污染」）。
        if (exitFolderDrill()) {
            items = emptyList()
            posterAdapter.submit(emptyList())
        }
        // 新的一次请求已经发起：还没执行的重试作废，避免 2.5 秒后又多打一轮。
        pendingEmptyRetry?.let { handler.removeCallbacks(it) }
        pendingEmptyRetry = null
        val site = VodConfig.get().getHome()
        loadedHomeSiteKey = site.key.orEmpty()
        // 推荐 Tab 的「已加载」标识跟着源走：换源后必须重新请求。
        loadedTabKey = HOME_TAB_PREFIX + loadedHomeSiteKey
        categoryTabActive = false
        // 推荐流没有页码（`Spider.homeContent(boolean)` 没有 page 参数）：翻页状态整份重开。
        resetPaging(typeId = null, siteKey = loadedHomeSiteKey)
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
    private fun renderHome(list: List<Vod>, site: Site, pageCount: Int) {
        val next = buildPosterItems(list, site)
        items = next
        // 整页落地：按 `Result.pageCount` 重算「后面还有没有页」（原版 L952 的 `V0`）。
        applyFirstPageMeta(pageCount)
        // 换了一整页数据：上一页解析出来的横图地址全部作废（换源 / 换 Tab 后 key 可能撞上旧值）。
        syncBackdrops.clear()
        // 上一页的窗口预取同样作废：下标已经指向另一批条目，整批取消后由 [updateTmdbWindow] 重建。
        tmdbWindowJobs.values.forEach { it.cancel() }
        tmdbWindowJobs.clear()
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
        // 与焦点切换同一个入口：掐旧发新 + i-2 … i+2 滑动窗口，没有任何延时。
        requestFocusedTmdbDetail()
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
            // 【竖图优先级】手动匹配优先：手动匹配过的条目，用户选中的 TMDB 海报已经落盘
            // （`putManual` + `putTmdbMatchCache`），冷启动首帧就直接用它，不再等异步结果。
            // 自动匹配**不覆盖**源站图（红线）：没手动过就仍只认 `vod.pic`，没图显示占位。
            val posterUrl = manual?.posterUrl?.takeIf { it.isNotBlank() } ?: vod.pic.orEmpty()
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
     * 与 `HeroShared.kt` 里的 `HeroLogoCache` 读同一份 prefs（名字都是 `hero_logo_cache`）。
     * 那个 object 负责首页 / 详情页的写入，本页只读，所以这里单独拼一份只读映射，
     * 但 prefs 名必须与它保持一致；那边改名这里要同步。
     */
    private fun readLogoCache(): Map<String, String> = runCatching {
        App.get().getSharedPreferences(LOGO_CACHE_PREFS, Context.MODE_PRIVATE).all
            .mapNotNull { (key, value) ->
                (value as? String)?.takeIf { it.isNotBlank() }?.let { key to it }
            }
            .toMap()
    }.getOrDefault(emptyMap())

    // ==================================================================
    // 翻页（行尾下探下一页）
    // ==================================================================

    /**
     * 重开一份翻页状态：页码归 1、清掉「还有下一页」、作废在途的追加标记。
     *
     * 每次换列表（换源 / 切 Tab / 进出目录）都必须调用，否则上一份列表的页码会串到新列表上，
     * 出现「第 3 页的内容接到另一个分类后面」。
     */
    private fun resetPaging(typeId: String?, siteKey: String) {
        paging = PagingState(typeId = typeId, siteKey = siteKey)
    }

    /**
     * 整页数据落地后按 `Result.pageCount` 决定「后面还有没有页」。
     *
     * 判据逐字照抄原版 L952：`V0 = page < pageCount || pageCount == 0`（此处 page = 1）。
     * `pageCount == 0` = 源站没给总页数（大量 JS 源如此），此时按「还有」处理，
     * 一直翻到真的取回一页空结果才收口（原版 L903-916）。
     */
    private fun applyFirstPageMeta(pageCount: Int) {
        val hasMore = paging.typeId != null && (1 < pageCount || pageCount == 0)
        paging = paging.copy(page = 1, hasMore = hasMore, loading = false, appending = false)
    }

    /**
     * 行尾下探下一页（原版 `o2()`，L1742-1751）。
     *
     * 守卫顺序与原版逐条对应：没有页码（推荐流）→ 没有下一页 → 已经在加载 → 源已换。
     * 请求走分类接口 `categoryContent(key, typeId, page + 1, true, extend)`（原版 `H.c(...)`）。
     */
    private fun requestNextPage() {
        val typeId = paging.typeId ?: return
        if (!paging.hasMore || paging.loading) return
        // 用列表自己的源 key（目录卡片可能来自别的源），换源时 `resetPaging` 已经整份作废。
        val siteKey = paging.siteKey
        if (siteKey.isEmpty()) return
        val next = paging.page + 1
        paging = paging.copy(loading = true, appending = true)
        Log.d(TAG, "海报行下探第 $next 页: site=$siteKey typeId=$typeId")
        siteViewModel.categoryContent(siteKey, typeId, next.toString(), true, hashMapOf())
    }

    /**
     * 下一页落地（原版 `T1(Result)` 的追加分支，L927-983）：**只**往行尾追加，绝不重画整排。
     *
     * 1. 空页 → 收口（原版 L910 `V0 = false`）；
     * 2. 按 `posterKey` 去重（原版按 `vod.getId()` 去重，L928-937）：整页都是重复条目也收口；
     * 3. `posterAdapter.append` 只发 `notifyItemRangeInserted`：已显示的卡片（含焦点卡）
     *    不会被重新绑定，焦点、1.1 倍放大、Coil 已加载的图、Hero 区都不会被打断。
     */
    private fun appendPage(list: List<Vod>, site: Site, pageCount: Int) {
        val page = paging.page + 1
        paging = paging.copy(page = page, loading = false, appending = false)
        if (list.isEmpty()) {
            paging = paging.copy(hasMore = false)
            Log.d(TAG, "第 $page 页为空，翻页收口（共 ${items.size} 条）")
            return
        }
        val known = items.mapTo(HashSet()) { it.posterKey }
        val fresh = buildPosterItems(list, site).filter { it.posterKey !in known }
        paging = paging.copy(hasMore = page < pageCount || pageCount == 0)
        if (fresh.isEmpty()) {
            paging = paging.copy(hasMore = false)
            Log.d(TAG, "第 $page 页全是重复条目，翻页收口（共 ${items.size} 条）")
            return
        }
        items = items + fresh
        posterAdapter.append(fresh)
        Log.d(TAG, "第 $page 页追加 ${fresh.size} 条，共 ${items.size} 条，hasMore=${paging.hasMore}")
    }

    // ==================================================================
    // Hero 渲染（D2 / E2）
    // ==================================================================

    /**
     * 渲染第 [index] 张卡对应的 Hero 区：文本 + 艺术字 + 大图，全部同步写。
     *
     * 文本永远先写：即使大图还要解码，观众看到的也是「这张卡的标题 + 逐渐淡入的横图」，
     * 而不是上一部剧的标题配下一部剧的图。
     *
     * 无论冷启动、焦点切换还是异步 TMDB 结果触发的重绘，都只有一条规则（原版 `E2(pos)`）：
     * **有横图 → 先收 posterCard 再发起加载**，没有横图 → 才让 posterCard 显示竖图。
     */
    private fun applyHero(index: Int) {
        val item = items.getOrNull(index) ?: return
        focusedIndex = index
        focusedPosterKey = item.posterKey
        // 【下探中】目录页不画 Hero：原版 `f()` 进入目录后只展开卡片，不加截上一层的横图 / 文案。
        // 这里连 TMDB 覆盖也一并跳过，避免把上一部剧的大图留在屏幕上（`showEmpty()` 同款清理）。
        if (folderStack.isNotEmpty()) {
            hideHeroSurface()
            return
        }
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
        bindBackdrop(item, tmdb, index)
    }

    /**
     * 下探目录时把 Hero 整块收起（原版 `HomeActivityV2.f()` 进入目录后不再展示 Hero 区）。
     *
     * 必须走 `heroBg.reset()` 而不是只 `setVisibility(GONE)`：`HeroImageView` 里的底图
     * `heroBgUnder` 不跟着清就会把上一部剧的整屏位图留在屏幕上（清屏链见 `HeroImageView.reset()`）。
     */
    private fun hideHeroSurface() {
        heroBg.reset()
        heroBg.visibility = View.GONE
        posterCard.visibility = View.GONE
        heroInfo.visibility = View.GONE
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
    private fun bindBackdrop(item: HomePosterItem, tmdb: HeroTmdbData?, position: Int) {
        // 【首帧防闪第一步】在任何布局 / 绘制 / 图片请求之前，把横图**同步**解析出来：
        // 覆盖表 → item（`buildPosterItems` 已查过一遍）→ 进程内 prefetch → 磁盘匹配缓存。
        // 冷启动那次绘制只在这里判一次，判空就会一直空到数据回来，绝不会「先竖图后横图」。
        val backdrop = resolveBackdrop(item, tmdb)

        // 【铁律】只要拿到横图，立刻收竖图，再发请求 —— 顺序绝不能反，
        // 也绝不把 `posterCard.visibility = View.GONE` 写进 `loadImage` 的异步回调。
        if (backdrop.isNotEmpty()) {
            posterCard.visibility = View.GONE
            HeroImageView.setSharedUrl(backdrop)
            heroBg.visibility = View.VISIBLE
            // 不传回调：竖图在上面那一行之前就已经收掉了，成功 / 失败都不需要再动 `posterCard`。
            heroBg.loadImage(backdrop, position, null)
            return
        }

        // 走完全部同步来源仍然没有横图 → 确认该显示竖图，这时才允许 posterCard 可见。
        //
        // 【逆向硬复刻】原版 `HomeActivityV2.E2` 的无横图分支只有一句 `a0.a()`，然后就把
        // posterCard 显出来；它**从不**单独 `setVisibility(GONE)` 了事 —— 因为清屏的全部职责
        // 都在 `a() → b() → d()` 里：先清底图 `heroBgUnder`，再取消淡入、取消在途请求、
        // 最后 `setImageDrawable(null)` 清主图。这里同样只走 `reset()` 这一个入口，
        // 顺序固定为「先掐掉所有在途/动画 → 底图 → 主图」，然后才把 heroBg 收起来和显示竖图。
        // 少清底图那一步，上一部剧的整屏位图就会留在 heroBgUnder 上继续铺满首页大图区。
        HeroImageView.setSharedUrl("")
        heroBg.reset()
        heroBg.visibility = View.GONE
        posterCard.visibility = View.VISIBLE
        loadPosterCard(item.posterUrl)
    }

    /**
     * 同步解析一张卡此刻的横图地址（**零网络、零异步**）。
     *
     * 顺序即优先级：
     * 1. [tmdb]：本次会话已经拿到的 TMDB 覆盖，以它为准（它没有真横图就认为是空，回落竖图）；
     * 2. `item.backdropUrl`：`buildPosterItems` 建表时已经同步查过一轮；
     * 3. [syncBackdrops]：本轮首页已解析过的结果（含「确认没有」的空串），避免重复读盘；
     * 4. [HeroTmdbPrefetchCache]：进程内详情缓存，比磁盘新；
     * 5. [Setting.getTmdbMatchCache]：冷启动唯一可靠来源，手动匹配优先。
     *
     * @return 提档后的横图地址；空串 = 确认没有横图，此时 `posterCard` 才允许显示竖图。
     */
    private fun resolveBackdrop(item: HomePosterItem, tmdb: HeroTmdbData?): String {
        if (tmdb != null) return if (tmdb.item.backdropUrl.isNullOrBlank()) "" else tmdb.highResBackdrop
        if (item.backdropUrl.isNotBlank()) return item.backdropUrl
        syncBackdrops[item.posterKey]?.let { return it }
        val resolved = readBackdropFromCaches(item)
        // 命中与「确认没有」都记下来：焦点来回移动时不再读盘。
        syncBackdrops[item.posterKey] = resolved
        return resolved
    }

    /** 真正去查缓存的部分：prefetch 优先，其次磁盘匹配缓存（手动匹配优先）。 */
    private fun readBackdropFromCaches(item: HomePosterItem): String {
        HeroTmdbPrefetchCache.peek(item.posterKey)?.let { data ->
            if (!data.item.backdropUrl.isNullOrBlank()) return data.highResBackdrop
        }
        // `Setting.getTmdbMatchCache()` 是 synchronized 的读盘 + Gson 解析：
        // 每张卡最多读一次，结果由 [syncBackdrops] 记住。
        val cache = runCatching { Setting.getTmdbMatchCache() }.getOrNull() ?: return ""
        val siteKey = item.site.key.orEmpty()
        val vodId = item.vod.id.orEmpty()
        val name = item.vod.name.orEmpty()
        val manual = runCatching { cache.findManual(siteKey, vodId, name) }.getOrNull()
        val matched = manual ?: runCatching { cache.find(siteKey, vodId, name) }.getOrNull()
        if (matched == null || matched.backdropUrl.isNullOrBlank()) return ""
        return upgradeTmdbImage(matched.backdropUrl.orEmpty(), TMDB_BACKDROP_WIDTH)
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
     * 焦点 TMDB 请求入口（原版 `getAndSet`）：**先掐断上一条，再发当前这一条**，没有任何延时。
     *
     * 1. `tmdbRequestJob?.cancel()` 立刻作废旧请求（不占连接、不回写、不重绘）；
     * 2. 起当前焦点这一条；
     * 3. 铺开 `i-2 … i+2` 的滑动窗口，左右各两张提前备好。
     *
     * 结果只有 [onTmdbDetailReady] 一个落地口，且以 `posterKey` 判身份（下标会漂移）。
     */
    private fun requestFocusedTmdbDetail() {
        // 目录页没有 Hero：不为目录卡片发 TMDB 详情请求（没有地方画横图 / 艺术字）。
        if (folderStack.isNotEmpty()) return
        // 1. 立刻掐断上一个请求
        tmdbRequestJob?.cancel()
        tmdbRequestJob = lifecycleScope.launch(Dispatchers.IO) {
            // 2. 发起当前新请求
            fetchTmdbDetailForPosition(focusedIndex)
        }
        // 3. 滑动窗口 i-2 … i+2
        updateTmdbWindow(focusedIndex)
    }

    /**
     * 单条 TMDB 详情拉取（**只在 IO 线程调用**，与旧实现同一条链路）：
     * 手动缓存 → 自动缓存 → 在线搜索 → 详情 → 演员 / 译文简介 / 艺术字 Logo。
     *
     * 结果统一交给主线程的 [onTmdbDetailReady]：非焦点条目只写缓存、不重绘。
     */
    private suspend fun fetchTmdbDetailForPosition(position: Int) {
        val item = items.getOrNull(position) ?: return
        // 已经有完整详情（刚停过这张 / 手动匹配拉过）：不重复请求，只补写跨页缓存。
        val existing = tmdbOverrides[item.posterKey]
        if (existing != null && existing.detail != null) {
            HeroTmdbPrefetchCache.put(item.posterKey, existing)
            // 元数据有、图不一定有（进程重启后磁盘里有元数据、图却可能被清过）。
            prefetchBackdropImage(existing.highResBackdrop)
            return
        }
        // 没配 Key / 该源被排除 / 没有剧名：保持源站画面，绝不发无效请求。
        val config = TmdbConfig.effectiveCurrent()
        if (!config.isReady() || item.vod.name.orEmpty().isBlank()) return
        if (!TmdbSitePolicy.isEnabled(config, item.site.key, item.vod.id)) return
        val data = loadTmdbDetail(item, config) ?: return
        // 【大图预取】元数据一落地，立刻把 backdropUrl 静默下进 Coil 缓存 —— **必须在切主线程之前**：
        // 这样下载与 `onTmdbDetailReady` 的重绘并行开始，焦点真落到这张卡时 `heroBg.loadImage()`
        // 直接命中内存 / 磁盘，不再出现「先黑一帧、图才淡入」。
        // `highResBackdrop` 在没有真横图时是空串（见 loadTmdbDetail），空串直接跳过。
        prefetchBackdropImage(data.highResBackdrop)
        withContext(Dispatchers.Main) { onTmdbDetailReady(item.posterKey, data) }
    }

    /**
     * 静默预取一张背景大图（原版 `je2.e(...)` 的等价机制）：**只下缓存，不碰任何 View**。
     *
     * 滑动窗口（i-2 … i+2）原来只预取了**元数据**，大图实体要等焦点真正落到那张卡、
     * `heroBg.loadImage()` 发请求时才第一次下载，于是「焦点移过去 → 先空一帧 → 图才淡入」。
     * 这里在元数据落地的同一刻就把图丢给 Coil，焦点移过去时直接命中缓存。
     *
     * 与 `HeroImageView.loadImage` 共用**同一个** data + size（[HeroImageView.prefetch]），
     * 内存缓存键天然一致；磁盘缓存键只认 URL，也一定命中。
     *
     * 只在 IO 线程调用（[fetchTmdbDetailForPosition] 的上下文），绝不阻塞主线程。
     */
    private fun prefetchBackdropImage(url: String?) {
        if (url.isNullOrBlank()) return
        HeroImageView.prefetch(this, url)
    }

    /**
     * 原版滑动窗口 `i-2 … i+2`：出窗的 Job 立刻取消并移除，入窗且没缓存的位置起一条新 Job。
     *
     * 只有「做减法」的淘汰和「做加法」的预取，没有任何排队 / 信号量 / 延时。
     * 中心位置由 [tmdbRequestJob] 负责，这里跳过，避免同一条并发两次。
     */
    private fun updateTmdbWindow(centerIndex: Int) {
        val start = (centerIndex - 2).coerceAtLeast(0)
        val end = (centerIndex + 2).coerceAtMost(items.size - 1)

        // 1. 淘汰：遍历 map，对于 index < start 或 > end 的 job，直接 cancel 并从 map 移除
        val iterator = tmdbWindowJobs.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key < start || entry.key > end) {
                entry.value.cancel()
                iterator.remove()
            }
        }

        // 2. 预取：遍历 start..end，没有缓存且 map 里没有正在跑的 job，就起一条
        for (i in start..end) {
            if (i == centerIndex) continue
            if (!hasTmdbCache(i) && !tmdbWindowJobs.containsKey(i)) {
                val job = lifecycleScope.launch(Dispatchers.IO) {
                    fetchTmdbDetailForPosition(i)
                    // 成功后从 map 移除
                    withContext(Dispatchers.Main) { tmdbWindowJobs.remove(i) }
                }
                tmdbWindowJobs[i] = job
            }
        }
    }

    /** 该位置是否已经有 TMDB 详情（内存覆盖表 / 进程内预取缓存任一命中即算有）。 */
    private fun hasTmdbCache(position: Int): Boolean {
        val item = items.getOrNull(position) ?: return true
        if (tmdbOverrides[item.posterKey]?.detail != null) return true
        return HeroTmdbPrefetchCache.peek(item.posterKey) != null
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
        // 磁盘里已有的（手动 / 自动）都不必再搜。
        val cachedItem = manualItem ?: cache.find(siteKey, vod.id, vod.name)
        // 显式声明可空：Java 侧是平台类型，`?:` 链的推断结果直接 `== null` 判断 + 智能转换最稳。
        val matched: TmdbItem? = cachedItem ?: TmdbMatcher(service, config).searchAndMatch(vod.name, vod)
        if (matched == null) {
            null
        } else {
            val detail = service.detail(matched, config, false)
            // 【冷启动根治】本次是**在线新匹配**的条目 → 立刻落盘。
            // 否则它只活在内存（overrides / prefetch），下次冷启动 `resolveBackdrop`
            // 查不到任何横图，第一帧必然先画竖图、等异步结果回来再收起 —— 这就是那颗闪。
            if (cachedItem == null) persistAutoTmdbMatch(siteKey, vod, matched)
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
        applyHero(index)
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
     *
     * **长按（1000ms，防误触）**：
     * - `ACTION_DOWN` + `repeatCount == 0` → `isKeyDown = true`、`isLongPressTriggered = false`，
     *   `recycler` 上 `removeCallbacks` + `postDelayed(..., 1000L)`；
     * - `ACTION_DOWN` + `repeatCount > 0` → 方法最顶部的 **W 锁**直接 `return true`（`isLongPressTriggered == true` 时），
     *   其余情况在 `ACTION_DOWN` 分支里吞掉，弹窗弹着也一个连发都漏不出去；
     * - `ACTION_UP` → 撤定时器、算短按、再把 `isLongPressTriggered` 复位。
     *
     * **短按只给确定键**：`KEYCODE_DPAD_DOWN` 的短按只吞事件，绝不跳详情。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 【TMDB 手动匹配弹窗 · 终极防线】弹窗显示期间，所有按键一律吞掉。
        // 弹窗自身已有 setOnKeyListener + DecorView 抢焦点，这里再兜第一行，
        // 保证任何情况下都绝不让焦点漏到底层海报列表。
        if (isTmdbShowing) return true
        // 【菜单键】呼出全局接口源侧边栏（与 Compose 首页小房子同一实现）。
        // DOWN 只认首次按下，避免按住时重复弹窗；UP 一并吞掉，防止落到默认菜单处理。
        if (event.keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                openSourceSidebar()
            }
            return true
        }
        // 【原版硬移植 · 全方法第一条判断】W（isLongPressTriggered）锁：
        // 长按已触发 + 按键连发 → 直接吞掉，任何弹窗判断、任何事件下发之前就结束。
        // 对应 `HomeActivityV2.java`：
        //   if (keyEvent.getAction() == KeyEvent.ACTION_DOWN && this.W && keyEvent.getRepeatCount() > 0) return true;
        if (event.action == KeyEvent.ACTION_DOWN && isLongPressTriggered && event.repeatCount > 0) {
            return true
        }
        // 【强制日志 · 第一步】每条按键都必须在这里留痕，用来判断「事件到底死在哪」：
        //  - 压根没这条日志  → 事件没进 Activity（被别的焦点窗口 / 系统层吞了）；
        //  - 有日志 focus=false → 事件进来了，但焦点不在海报行，长按分支不会启动；
        //  - 有日志 focus=true  → 事件与焦点都正常，问题只可能在定时器 / 弹窗那一步。
        val posterFocused = ::recycler.isInitialized &&
            (recycler.findFocus() != null || recycler.hasFocus())
        val key = event.keyCode
        // 海报行只接管「下键 / 确定键」。
        val isPosterKey = key == KeyEvent.KEYCODE_DPAD_DOWN ||
            key == KeyEvent.KEYCODE_DPAD_CENTER ||
            key == KeyEvent.KEYCODE_ENTER

        // ── 海报行 ← / →：整行自己路由，行首 / 行尾原地不动 ─────────────────────
        // 原版（Leanback 横向列表）左右键只改「选中位」：`l2(±1)` 先做边界判断，越界直接
        // return，焦点永远出不了这一行（`HomeActivityV2` L1210-1213 / L1721-1733）。
        // RecyclerView 没有这条边界：走到最后一列再按 → 时，`FocusFinder` 在同一条「光束」里
        // 找不到候选，会退化成「主轴搜索」，把焦点交给**上方**分类 Tab 行里最靠右的那一项 ——
        // 这就是「长按 → 跳到 Tab 栏」的来源；焦点落进 Tab 行还会立刻刷新选中态
        // （`notifyItemChanged` + 220ms 后整页重载），撞上 Tab 行自己的布局过程就是那个
        // 「极小几率、难复现」的崩溃。所以这里不再「只拦边界、其余交给系统」，而是整行左右键
        // 都由自己路由：相邻卡片 → 直接 `focusPoster`；行首 / 行尾 → 吃掉（行尾顺带下探下一页）；
        // 位置未知（`NO_POSITION`，正好在 notify 之后的一帧）→ 也吃掉，绝不漏给几何搜索。
        if ((key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) && posterFocused) {
            val position = posterRowPosition()
            if (position != RecyclerView.NO_POSITION) {
                val target = if (key == KeyEvent.KEYCODE_DPAD_RIGHT) position + 1 else position - 1
                if (target in items.indices) {
                    // 只有真正的按下（含连发）才挪焦点；抬起事件一并吞掉，不留「半截按键」。
                    if (event.action == KeyEvent.ACTION_DOWN) focusPoster(target)
                } else if (key == KeyEvent.KEYCODE_DPAD_RIGHT && event.action == KeyEvent.ACTION_DOWN) {
                    // 行尾：后面还有页就先下探下一页（原版 `V1()` / `o2()`），没有就原地不动。
                    requestNextPage()
                }
            }
            return true
        }

        // ── ACTION_UP：真正的抬手，长按状态只在这里复位 ─────────────────────────
        if (event.action == KeyEvent.ACTION_UP && isPosterKey && (isKeyDown || isLongPressTriggered)) {
            recycler.removeCallbacks(longPressDownRunnable)
            recycler.removeCallbacks(longPressCenterRunnable)
            val shortPress = isKeyDown && !isLongPressTriggered
            isKeyDown = false
            // 弹窗弹出时不改这个标记，必须等到这里（真正收到 ACTION_UP）才允许清零。
            isLongPressTriggered = false
            // 【防误触】短按跳详情只绑定确定键 / Enter；下键短按一律不跳转。
            if (shortPress && key != KeyEvent.KEYCODE_DPAD_DOWN) {
                items.getOrNull(focusedIndex)?.let(::onPosterClicked)
            }
            return true
        }

        // ── ACTION_DOWN ─────────────────────────────────────────────────────────
        if (event.action == KeyEvent.ACTION_DOWN) {
            // 未触发的连发：同样吃掉，避免按住时焦点先乱跳、也避免重复起定时器。
            // （W 已触发的连发在方法最顶部就被拦掉了，走不到这里。）
            if (isPosterKey && posterFocused && event.repeatCount != 0) return true
            // 【焦点判定】`findFocus()` 覆盖「焦点在卡片子 View 上」这一最常见情况，
            // `hasFocus()` 兜底「焦点就是这个 RecyclerView 自己」；任一成立即认定为海报行。
            if (isPosterKey && posterFocused) {
                isKeyDown = true
                isLongPressTriggered = false
                if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
                    recycler.removeCallbacks(longPressDownRunnable)
                    recycler.postDelayed(longPressDownRunnable, posterLongPressMs)
                } else {
                    recycler.removeCallbacks(longPressCenterRunnable)
                    recycler.postDelayed(longPressCenterRunnable, posterLongPressMs)
                }
                return true
            }
        }

        // ── 其余按键：原焦点路由原样保留 ───────────────────────────────────────
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> if (onDpadUp()) return true
                KeyEvent.KEYCODE_DPAD_DOWN -> if (onDpadDown()) return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * 1000ms 到点：先落 `isLongPressTriggered = true`，再弹窗。
     *
     * **W 只在两处归零，且都不在这里**：
     * 1. 真正收到 `ACTION_UP`（[dispatchKeyEvent] 的 UP 分支）—— 手指抬起；
     * 2. 下一次全新按下（`ACTION_DOWN` + `repeatCount == 0`）—— 原版同样在新一轮按下时清 W。
     *
     * 弹窗弹着、手指还按着的整个过程中 W 一直为 true，因此所有连发都会被方法最顶部的 W 锁吞掉。
     */
    private fun onPosterLongPressFired(key: Int) {
        // 【强制日志 · 第二步】定时器真的到点了才会打这一条。
        //  - 有 `Activity: ... repeat=0` 但从来没有这条 → 定时器没跑（条件没过 / 被 removeCallbacks 了）；
        //  - 这条出现却不弹窗 → 问题在弹窗本身，不在按键。
        isLongPressTriggered = true
        val item = items.getOrNull(focusedIndex) ?: return
        when (key) {
            KeyEvent.KEYCODE_DPAD_DOWN -> showTmdbManualMatch(item)      // X：下键 → TMDB 手动匹配
            else -> showChangeSource(item)                                // U：确定键 → 换源
        }
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
     * 长按方向下键：TMDB 手动匹配。
     *
     * 直接开纯 View 弹窗（[TmdbMatchViewDialog]），不再经过 Compose 桥接：
     * 没有 0×0 的 ComposeView、没有 setContent、没有额外的组合开销，
     * 弹窗的布局 / 键盘 / 联想词 / 焦点流转全部由它自己那套 XML + Adapter 负责。
     */
    private fun showTmdbManualMatch(item: HomePosterItem) {
        // 领一个新代号：旧弹窗的 dismiss 回调如果迟到，会因代号不匹配被直接丢弃。
        val serial = ++tmdbDialogSerial
        tmdbMatchDialog?.dismiss()
        val dialog = TmdbMatchViewDialog.create(this)
            .keyword(item.title)
            .vod(item.vod)
            .listener { tmdbItem ->
                // 内存覆盖 → 磁盘落盘 → 卡片竖图 + Hero 重绘，全部由这一个函数一次做完。
                applyManualTmdbMatch(item, tmdbItem)
            }
            .dismissListener {
                // 关窗（点卡片确认 / 返回键 / 取消）后统一复位：解除按键拦截 + 焦点还给长按那张卡。
                // dismiss 回调是 Handler 异步消息：旧弹窗的回调可能晚于新弹窗的 show() 才到，
                // 不加这道判断就会把新弹窗的拦截标志清成 false（按键立刻漏到底层列表）。
                if (serial != tmdbDialogSerial) return@dismissListener
                isTmdbShowing = false
                tmdbMatchDialog = null
                recycler.post { focusPoster(focusedIndex) }
            }
        tmdbMatchDialog = dialog
        // 先置位再 show()：从窗口出现的第一帧起，Activity 的 dispatchKeyEvent 就全部吞掉。
        isTmdbShowing = true
        dialog.show()
    }

    /**
     * 手动匹配结果落地：与 Compose 首页 `onConfirm` 同一套规则 ——
     * 同一条目保留简介 / Logo，换了条目整体作废；并写回 `TmdbMatchCache`（重启不丢）。
     *
     * 三段职责，顺序不能换：
     * 1. **内存**：`tmdbOverrides` 换上新条目（换了条目先作废旧预取，避免旧横图 / 艺术字继续盖着）；
     * 2. **磁盘**：`putManual` + `putTmdbMatchCache`，冷启动 `buildPosterItems` 直接读回这份 posterUrl；
     * 3. **界面**：原地改写 [HomePosterItem.posterUrl] → 刷这一格卡片 + 重绘 Hero 大图。
     */
    private fun applyManualTmdbMatch(item: HomePosterItem, tmdbItem: TmdbItem) {
        val prev = tmdbOverrides[item.posterKey]?.takeIf { it.item.tmdbId == tmdbItem.tmdbId }
        // 换了条目：进程内预取里那条旧数据（横图 / 艺术字 / 简介）必须显式作废，
        // 否则下一轮 `buildPosterItems` / `readBackdropFromCaches` 还会优先命中它。
        if (prev == null) {
            HeroTmdbPrefetchCache.remove(item.posterKey)
            syncBackdrops.remove(item.posterKey)
        }
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
        // 【即时生效】竖图换成用户选中的 TMDB 海报；没有海报（都没图）才回落源站图。
        val nextPosterUrl = tmdbItem.posterUrl.orEmpty().ifBlank { item.vod.pic.orEmpty() }
        val posterChanged = nextPosterUrl != item.posterUrl
        item.posterUrl = nextPosterUrl
        // 用条目自己的下标而不是 focusedIndex：长按判定与回调之间焦点可能已经移走。
        val index = items.indexOfFirst { it.posterKey == item.posterKey }.takeIf { it >= 0 } ?: focusedIndex
        if (posterChanged && index in items.indices) {
            // 卡片会被 `onBindViewHolder` 重新绑定并重新 load 这张图（Adapter 内部按 url 变化才重载）。
            posterAdapter.notifyItemChanged(index)
        }
        // 标题 / 评分 / 简介 / 横图 / 艺术字一起重画（大图在右侧 posterCard 时也换成新竖图）。
        applyHero(index)
    }

    /**
     * 把**在线新匹配**到的条目写进磁盘匹配缓存 —— 这是冷启动唯一的同步横图来源。
     *
     * 不落盘的话，匹配结果只活在内存（`tmdbOverrides` / `HeroTmdbPrefetchCache`），
     * 下次冷启动 `resolveBackdrop` 什么都查不到，首帧只能先画竖图再等异步结果收起。
     *
     * - 只在 TMDB 的 IO 链路里调用，不占主线程；
     * - `TmdbMatchCache.put` 内部拒绝覆盖手动选择，不会冲掉用户手动匹配；
     * - 失败只记日志，本次会话的界面不受影响。
     */
    private fun persistAutoTmdbMatch(siteKey: String?, vod: Vod, item: TmdbItem) {
        if (item.tmdbId <= 0) return
        runCatching {
            synchronized(Setting::class.java) {
                val cache = Setting.getTmdbMatchCache()
                cache.put(siteKey, vod.id.ifBlank { vod.name }, vod.name, item)
                Setting.putTmdbMatchCache(cache)
            }
        }.onFailure { Log.w(TAG, "自动匹配落盘失败: ${vod.name} | ${it.message}") }
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
        // 焦点一变：立刻掐断上一条请求、发起这一条，并重铺 i-2 … i+2 的预取窗口。
        requestFocusedTmdbDetail()
        // 【行尾预取】焦点进到最后 [PAGE_PREFETCH_DISTANCE] 张时就提前拉下一页：
        // 用户一路按 → 到行尾时新卡片已经追加好，翻页是「无缝」的，不用停在行尾等网络。
        if (position >= items.size - PAGE_PREFETCH_DISTANCE) requestNextPage()
    }

    /**
     * 卡片确定键：正片进详情页，目录壳在首页**原地下探**（严格复刻原版 `HomeActivityV2.f()`）。
     *
     * <p>【逆向硬复刻】原版 `f()` 的判断顺序（L1529-1630）：
     * <pre>
     * if (vod.isAction())  { ...; return; }                 // 插件动作项：不跳页
     * if (!vod.isFolder()) { DetailActivity.q2(...); return; }  // 正片：详情页
     * // vod.isFolder() → 分类 / 目录壳
     * c1.push(u31(...));                                    // ① 压入下探栈
     * d1 = vod.getName(); T0 = vod.getId(); ...             // ② 记录当前目录
     * B0.clear(); L.setSelectedPosition(0);                 // ③ 清空首页列表 + 选中位归零
     * L.setDescendantFocusability(BLOCK); L.requestFocus(); // ④ 焦点交给（空）海报行
     * Z.setVisibility(8); Y.setVisibility(0);               // ⑤ 空态收起、进度显示
     * H.c(f2().getKey(), vod.getId(), "1", new HashMap());  // ⑥ 用分类接口展开目录
     * </pre>
     * 原版的下探结果继续灌回**同一张海报列表**，本类按 `B2(List)`（L366-381）落同一步：
     * [renderFolder] 只替换海报行，Tab 栏 / 首页状态一律不动；返回键按 `U1()`（L1089-1113）
     * 出栈恢复上一层。正片仍走 [openHeroDetailOrFolder]，详情页首帧带上横图 / 艺术字 / 简介。
     */
    private fun onPosterClicked(item: HomePosterItem) {
        // 【原版 HomeActivityV2.f() 的 folder 分支】分类 / 目录壳在首页**原地展开**：
        // 压栈 → `B0.clear()` → 收起 Hero → 选中位归零 → 用分类接口拉这一层。
        // 绝不把目录 id 当详情页 id 去请求，也绝不把目录结果当成「推荐」首页数据。
        if (item.vod.isFolder) {
            enterFolder(item)
            return
        }
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

    // ==================================================================
    // 目录下探（原版 `HomeActivityV2.f()` / `B2()` / `U1()` 的等价实现）
    // ==================================================================

    /**
     * 进入一层目录（原版 `f(Vod)` 的 folder 分支，L1534-1584）。
     *
     * 顺序与原版一一对应，一步不能少：
     * 1. `c1.push(u31(...))`：备份当前整页（列表 / 焦点 / Tab）；
     * 2. `B0.clear()`：立刻清空海报行，上一层的卡片绝不能再留在屏幕上；
     * 3. 收起 Hero（大图 / 竖图 / 文案）—— 目录页没有 Hero 区；
     * 4. `L.setSelectedPosition(0)` + `FOCUS_BLOCK_DESCENDANTS` + `requestFocus()`：焦点归零；
     * 5. `Z.setVisibility(8)` / `Y.setVisibility(0)`：空态收起、进度显示；
     * 6. `H.c(siteKey, vod.getId(), "1", new HashMap())`：用分类接口展开这一层目录。
     */
    private fun enterFolder(item: HomePosterItem) {
        val vod = item.vod
        val site = item.site
        // 1.【原版 c1.push(u31(...))】先备份，不然后退时没有任何东西可恢复。
        folderStack.addLast(
            FolderDrillState(
                items = items,
                focusedIndex = focusedIndex,
                tabIndex = tabAdapter.selectedIndex,
                lastFocusedTabIndex = lastFocusedTabIndex,
                // 快照里清掉在途标记：进目录会 `cancelResult()`，那一页永远不会回来，
                // 原样存下去会让返回后的行尾翻页被 `loading` 永久卡住。
                paging = paging.copy(loading = false, appending = false),
            )
        )
        // 2.【原版 B0.clear()】整排立刻清空（Adapter 与 Activity 两份列表必须同步换掉）。
        items = emptyList()
        posterAdapter.submit(emptyList())
        // 3.【收起 Hero】`reset()` 连 `heroBgUnder` 底图一起清，绝不让上一部剧的大图留在屏幕上。
        hideHeroSurface()
        // 4.【原版 L.setSelectedPosition(0) + setDescendantFocusability + requestFocus()】
        //    先挡住子项抢焦点，再把焦点交给（已清空的）海报行；数据回来再放开。
        focusedIndex = 0
        recycler.scrollToPosition(0)
        recycler.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        recycler.requestFocus()
        // 5.【原版 Z.setVisibility(8) / Y.setVisibility(0)】
        posterEmpty.visibility = View.GONE
        posterProgress.visibility = View.VISIBLE
        // 6.【原版 H.c(siteKey, vod.getId(), "1", new HashMap())】
        //    这一层的翻页上下文 = 目录自己的 id：行尾还能继续下探第 2、3… 页。
        resetPaging(vod.id, site.key.orEmpty())
        Log.d(TAG, "下探目录: site=${site.key} id=${vod.id} name=${vod.name}")
        siteViewModel.categoryContent(site.key, vod.id, "1", true, hashMapOf())
    }

    /**
     * 目录数据落地（原版 `B2(List)`，L366-381）：**只**替换海报行。
     *
     * Tab 栏 / 首页的空态与自动分类逻辑一律不动 —— 目录结果绝不能冒充「推荐」首页数据，
     * 这正是「下探之后整页被同质化卡片污染」的根源。
     */
    private fun renderFolder(list: List<Vod>, site: Site, pageCount: Int) {
        val next = buildPosterItems(list, site)
        items = next
        // 这一层目录的首屏落地：同样按 `Result.pageCount` 决定后面还有没有页。
        applyFirstPageMeta(pageCount)
        // 换了一整页目录数据：上一页解析出来的横图 / 预取窗口全部作废。
        syncBackdrops.clear()
        tmdbWindowJobs.values.forEach { it.cancel() }
        tmdbWindowJobs.clear()
        posterProgress.visibility = View.GONE
        // 数据回来了：卡片可以接焦点了（对应原版进入目录前的 FOCUS_BLOCK_DESCENDANTS）。
        recycler.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        focusedIndex = 0
        if (next.isEmpty()) {
            posterEmpty.visibility = View.VISIBLE
            return
        }
        posterEmpty.visibility = View.GONE
        posterAdapter.submit(next)
        recycler.visibility = View.VISIBLE
        recycler.scrollToPosition(0)
        // 原版 `D2(0, ...)`：Hero 状态跟着第 0 条走（下探期间由 [applyHero] 统一收起）。
        applyHero(0)
        progressLayout.showContent()
        // 原版 `L.requestFocus()`：进入目录后焦点就在海报行第 0 张。
        recycler.post { focusPoster(0) }
    }

    /**
     * 原版 `U1()`（L1089-1113）：出栈一层，把上一层列表 / 焦点原样恢复，不重新请求。
     *
     * @return true = 消费了这次返回键；false = 已经在根页，交给系统。
     */
    private fun exitFolder(): Boolean {
        if (folderStack.isEmpty()) return false
        // 这一层的目录请求还在路上也必须作废：迟到的结果绝不能写回上一层。
        siteViewModel.cancelResult()
        val state = folderStack.removeLast()
        items = state.items
        // 出栈恢复上一层的翻页状态（原版 `U1()` 连 `R0` 的页码一起还原）：返回后还能继续翻页。
        paging = state.paging
        syncBackdrops.clear()
        tmdbWindowJobs.values.forEach { it.cancel() }
        tmdbWindowJobs.clear()
        posterProgress.visibility = View.GONE
        posterEmpty.visibility = View.GONE
        recycler.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        tabAdapter.selectedIndex = state.tabIndex
        lastFocusedTabIndex = state.lastFocusedTabIndex
        categoryTabActive = state.tabIndex > 0
        val index = if (state.items.isEmpty()) 0 else state.focusedIndex.coerceIn(0, state.items.lastIndex)
        focusedIndex = index
        posterAdapter.submit(items)
        if (items.isEmpty()) {
            recycler.visibility = View.GONE
            return true
        }
        recycler.visibility = View.VISIBLE
        // 只有回到根页才恢复 Hero：还有上一层目录时继续藏着。
        if (folderStack.isEmpty()) {
            heroInfo.visibility = View.VISIBLE
            applyHero(index)
        }
        recycler.post { focusPoster(index) }
        return true
    }

    /**
     * 离开目录视图（切 Tab / 换源 / 重拉首页）：清空下探栈、作废在途目录请求、恢复 Hero。
     *
     * @return true = 确实离开了一个目录。调用方必须把它当成「整页换数据」，不能再走
     *         「同一个 Tab 已加载就跳过」的快速返回，否则目录卡片会被当成这个 Tab 的内容留在屏幕上。
     */
    private fun exitFolderDrill(): Boolean {
        if (folderStack.isEmpty()) return false
        folderStack.clear()
        siteViewModel.cancelResult()
        // 目录层作废：翻页状态一起清掉，随后的 refreshHome / selectTab 会按新列表重开。
        resetPaging(typeId = null, siteKey = "")
        heroInfo.visibility = View.VISIBLE
        return true
    }

    private fun onTabFocused(index: Int) {
        // 【崩溃防线】焦点变化回调可能发生在 Tab 行**自己的布局过程中**（长按 → 被几何搜索
        // 抢到 Tab 栏，或 `focusTab()` 在 post 里抢焦点时撞上 layout）。此刻
        // `startSmoothScroll`（[scrollTabToCenter]）会抛
        // `IllegalStateException: Cannot call this method while RecyclerView is computing
        // a layout or scrolling` —— 这正是「极小几率、难复现」的那个崩溃。
        // 布局中一律推迟到下一拍：选中态与请求都不会丢，只是晚一帧。
        if (typeRecycler.isComputingLayout) {
            typeRecycler.post {
                // 推迟一帧后必须复核：这一拍焦点可能已经移走，绝不能把「刚才那一下」当成当前选中，
                // 否则 220ms 后 `selectTab` 会去加载一个用户早已离开的分类。
                val focused = typeRecycler.findFocus()
                val position = focused
                    ?.let { typeRecycler.findContainingViewHolder(it)?.absoluteAdapterPosition }
                if (position == index) onTabFocused(index)
            }
            return
        }
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
        // 切 Tab = 离开目录：目录卡片绝不能冒充这个 Tab 的内容，整页必须重新加载。
        if (exitFolderDrill()) {
            loadedTabKey = ""
            items = emptyList()
            posterAdapter.submit(emptyList())
        }
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
            // 推荐流没有页码：翻页状态整份重开（`refreshHome` 里也会做一次，这里先清干净）。
            resetPaging(typeId = null, siteKey = site.key.orEmpty())
            refreshHome("推荐 Tab")
            return
        }
        val type = homeTypes.getOrNull(index - 1) ?: return
        if (type.typeId.isNullOrBlank() || site.key.isNullOrEmpty()) return
        if (key == loadedTabKey && items.isNotEmpty()) return
        loadedTabKey = key
        categoryTabActive = true
        // 换分类 = 换一份可分页的列表：页码归 1，typeId 换成这个分类。
        resetPaging(type.typeId, site.key.orEmpty())
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

    /**
     * 海报行里「真正持有焦点的那个下标」。
     *
     * - 焦点在卡片内部子 View 上 → `findContainingViewHolder` 向上找到它所属的卡片；
     * - 焦点就是 RecyclerView 自己（下探目录时 `recycler.requestFocus()`）→ 退回 [focusedIndex]
     *   （它在 `applyHero()` 里与焦点同步更新）；
     * - holder 正好处在 notify / 布局过程中 → `absoluteAdapterPosition` 是 `NO_POSITION`，
     *   此时**不能**当成「不是行尾」放行（放行 = 几何搜索把焦点交给 Tab 栏），原样返回 -1，
     *   由调用方吃掉这次按键，等下一帧位置稳定再正常移动。
     */
    private fun posterRowPosition(): Int {
        val focus = recycler.findFocus() ?: return focusedIndex
        val holder = recycler.findContainingViewHolder(focus) ?: return focusedIndex
        return holder.absoluteAdapterPosition
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

        /** 首页双击返回退出的确认窗口：两次返回间隔小于该值才真正退出。 */
        private const val EXIT_CONFIRM_WINDOW_MS = 2000L

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
         * 海报行间距：步进必须是原版的 **132dp**（卡宽 116dp + 间距 16dp）。
         *
         * 原版 `ref_docs/xx0.java` 的 `I(Style)`：`(960 − 6×16 − 48) / 7 = 116dp` 卡宽，
         * 加 16dp 间距 → 132dp 步进。本项目海报卡固定 120dp（`HeroPosterCard`，历史页 / 搜索页共用，
         * 不能改），所以把间距收成 12dp，步进仍是 132dp，实测原版卡心 81.8 / 214.2 / 346.8 / 479.2…
         * 与本项目 24 + 132k + 60 完全对上：
         *  - 静止：24 + 7×120 + 6×12 = 936dp，第 8 张起点 948dp → 屏宽 960dp 正好露出 12dp 卡边；
         *  - 居中：焦点卡两侧各 3 整卡（3×132 = 396dp）刚好铺满 24dp 内边距到屏幕中心；
         *  - 第 4 张（第 5 张卡）卡心 = 24 + 4×132 + 60 = 612dp，滚动量 612 − 480 = 132dp，
         *    前 4 张卡心都在视口中心左侧、dx ≤ 0 被夹成 0，所以「到第 5 张才开始滚」。
         */
        private const val POSTER_GAP_DP = 12

        /** 分类 Tab 间距：同文件 L786 `K.setHorizontalSpacing(g2.b(12))`。 */
        private const val TAB_GAP_DP = 12

        /**
         * 行尾预取距离：焦点进到最后 2 张时就提前请求下一页（原版靠滚动监听触发）。
         *
         * 与 TMDB 详情的 `i-2 … i+2` 滑动窗口同一思路：等真的按到行尾再请求，用户会先看到
         * 一次「卡住」；预取把这个等待藏进用户滑到最后两张的过程中。
         */
        private const val PAGE_PREFETCH_DISTANCE = 2

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

        /** 与 `HeroLogoCache`（`HeroShared.kt`）共用同一份 prefs。 */
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
    /**
     * 宿主 RecyclerView：`selectedIndex` 一变就要**同步**把 `isSelected` 刷到已附着的子项上。
     * 顶栏（NAV）用不到，默认 null。
     */
    private val host: RecyclerView? = null,
    /** 顶栏最后一项再按 → 的回调：把焦点交回左侧「小房子」，与「小房子 ←」一起构成环形焦点。 */
    private val onRightEdge: () -> Unit = {},
) : RecyclerView.Adapter<HomeChipAdapter.Holder>() {

    private val items = ArrayList<String>()
    private val density = context.resources.displayMetrics.density
    private val accent = ContextCompat.getColor(context, R.color.hero_accent)

    /**
     * 分类 Tab 三态底色：焦点 / 选中态直接复用全局 Drawable，其余只用到 `home_tab_idle_*` 颜色。
     * 这里不出现任何十六进制字面量，也不在代码里拼渐变。
     */
    private val tabIdleSurface = ContextCompat.getColor(context, R.color.home_tab_idle_surface)
    private val tabIdleText = ContextCompat.getColor(context, R.color.home_tab_idle_text)

    /**
     * 分类 Tab 文字色（状态驱动）：聚焦 / 选中 → 纯白，其余 → 60% 白。
     * 与 [tabBackground] 一起交给状态机，代码里不再按事件顺序改色。
     */
    private val tabTextColors: ColorStateList = ColorStateList(
        arrayOf(
            intArrayOf(android.R.attr.state_focused),
            intArrayOf(android.R.attr.state_selected),
            intArrayOf(),
        ),
        intArrayOf(Color.WHITE, Color.WHITE, tabIdleText),
    )

    /**
     * 选中下标：Tab 用它画「失焦仍选中」的红色描边；-1 = 未选中。
     *
     * 【残影根因 · 2026-11-12】旧实现用 `notifyItemChanged(old/value)` 换背景：那是**异步**的，
     * 焦点 A→B 时 A 已经失焦、`selectedIndex` 还是 A，于是 A 先按「选中未聚焦」画成细线红框，
     * 等下一次 layout 重绑才变回常态底 —— 屏幕上就是「实心胶囊已经到 B，A 还留着一个红框」。
     * 现在改成两件事：① 背景 / 文字色由 [tabBackground] 的 `StateListDrawable` 按 View 真实
     * 状态位取值（每帧都正确，不可能过期）；② 这里**同步**刷 `isSelected`，不发任何 notify，
     * 也就不会再撞上 Tab 行自己的 layout（那个 `IllegalStateException` 的另一个来源）。
     */
    var selectedIndex: Int = -1
        set(value) {
            if (field == value) return
            field = value
            syncSelection()
        }

    /** 把 `isSelected` 立刻同步到已附着的子项；未附着的项由 [onBindViewHolder] 按当前下标补上。 */
    private fun syncSelection() {
        val recycler = host ?: return
        for (index in 0 until recycler.childCount) {
            val child = recycler.getChildAt(index)
            val position = recycler.getChildAdapterPosition(child)
            if (position != RecyclerView.NO_POSITION) child.isSelected = position == selectedIndex
        }
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
            if (style == HomeChipStyle.NAV) {
                // 顶栏环形焦点：焦点在最后一项（设置）时再按 →，交回左侧「小房子」，
                // 而不是交给系统几何搜索去乱跳。中间项照常向右移动。
                // `View.OnKeyListener.onKey(v, keyCode, event)` 是**三参数**。
                holder.text.setOnKeyListener { _, _, event ->
                    if (event.action == KeyEvent.ACTION_DOWN &&
                        event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT &&
                        holder.bindingAdapterPosition == itemCount - 1
                    ) {
                        onRightEdge()
                        true
                    } else {
                        false
                    }
                }
            }
            holder.text.setOnFocusChangeListener { _, hasFocus ->
                val position = holder.bindingAdapterPosition
                if (hasFocus && position != RecyclerView.NO_POSITION) onFocused(position)
                applyVisual(holder, hasFocus, position)
                if (style == HomeChipStyle.NAV) {
                    // 顶栏焦点态整体平滑放大 1.1 倍（只走绘制层，不动布局）。
                    // 缩放对象是「文字 + 下划线」整列，下划线与文字始终同心；pivot = 竖列中心（= 64dp 行心），
                    // 所以基准 44..46dp / 20dp 的横线放大后正好落在原版实测的 45..47dp / 22dp。
                    holder.root.animate()
                        .scaleX(if (hasFocus) TOP_BAR_FOCUS_SCALE else 1f)
                        .scaleY(if (hasFocus) TOP_BAR_FOCUS_SCALE else 1f)
                        .setDuration(FOCUS_ANIM_MS)
                        .start()
                }
            }
        }

    /**
     * 顶栏文字项：竖列 = [24dp 文字区] + [20×2dp 红色短横线]，**两者之间不留间距**，
     * 顶部再预留 2dp（= 横线高），使 24dp 文字区正好落在 64dp 行的 20..44dp、横线紧贴 44..46dp。
     *
     * 几何来源（`ref_docs/1.png`，1920×1080 @2x，焦点在「搜索」）：文字墨迹 y 23.5..39dp、
     * 红色短横线 y 45..47dp、横线宽 22dp。原版焦点态把整项放大 1.1 倍（pivot = 行心 32dp），
     * 所以基准几何是「横线 44..46dp、宽 20dp」：`32 + (44−32)×1.1 = 45.2dp`、`20 × 1.1 = 22dp`，
     * 与实测逐像素吻合。旧实现按 Compose 版在文字与横线之间留了 6dp（基准 50..52dp），
     * 放大后落在 51.8..54dp，比原版低 7dp；横线也按 26dp 画，放大后 28.6dp，比原版宽 6.6dp。
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
            // 紧贴文字区下沿：竖列里不设 topMargin，几何完全由 [TOP_BAR_UNDERLINE_RESERVE_DP] 决定。
            layoutParams = LinearLayout.LayoutParams(
                dp(TOP_BAR_UNDERLINE_WIDTH_DP),
                dp(TOP_BAR_UNDERLINE_HEIGHT_DP),
            )
        }
        column.addView(text)
        column.addView(underline)
        return Holder(root = column, text = text, underline = underline)
    }

    /**
     * 分类 Tab：逐项照抄 `ref_docs/adapter_type.xml` —— 13sp、`layout_height="26dp"`、宽 wrap_content。
     *
     * 三态底色（原版 `ref_docs/selector_item_round.xml` 的 focused / selected / normal）在 Kotlin 侧由
     * [tabBackground] 生成的状态机负责，见该方法与 `colors.xml` 的 `home_tab_*`。
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
            // 文字色与三态底色都交给状态机：焦点 / 选中怎么变，都由 View 当时的真实状态位取值。
            setTextColor(tabTextColors)
            background = tabBackground()
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
        // 复用 / 新绑定时补上选中位（`syncSelection` 只覆盖已附着的子项）。
        if (style == HomeChipStyle.TAB) holder.text.isSelected = position == selectedIndex
        applyVisual(holder, holder.text.isFocused, position)
    }

    /**
     * 焦点视觉（照抄 Compose 版 `HeroTopBarItem`，L1730-1816）：
     * - 顶栏：常态白 60% + Medium；焦点纯白 + SemiBold（系统 `sans-serif` BOLD）+ 下方红色短横线。
     *   缩放在焦点监听里做，只作用于绘制层，下划线不会跳动。
     * - 分类 Tab：**不在这里画**。三态（聚焦 = 实心红渐变胶囊 / 选中未聚焦 = 细线红框胶囊 /
     *   其余 = 深色半透明胶囊）由 [tabBackground] 的 `StateListDrawable` + [tabTextColors] 的
     *   `ColorStateList` 按状态位自动取值，见 [selectedIndex] 上的残影说明。
     */
    private fun applyVisual(holder: Holder, focused: Boolean, position: Int) {
        val text = holder.text
        when (style) {
            HomeChipStyle.NAV -> {
                text.setTextColor(if (focused) Color.WHITE else TOP_BAR_IDLE_TEXT)
                text.typeface = if (focused) NAV_FOCUS_TYPEFACE else NAV_IDLE_TYPEFACE
                // 横线始终占位（2dp，紧贴文字区下沿），这里只切透明度：文字宽度变化不会顶动它。
                holder.underline?.alpha = if (focused) 1f else 0f
            }
            HomeChipStyle.TAB -> Unit
        }
    }

    /**
     * 分类 Tab 背景 —— 三态唯一的生成入口（与原版 `selector_item_round.xml` 同构），
     * 焦点 / 选中态全部复用全局 Drawable，代码里不写色值：
     * - `state_focused`：`@drawable/bg_global_focus_capsule`（红渐变实心胶囊，28dp 全圆角）；
     * - `state_selected`：`@drawable/bg_global_selected_capsule_border`（同形状透明底 + 细线红框）；
     * - 其余：`home_tab_idle_surface` 深色半透明**胶囊**（同 28dp 全圆角，
     *   照抄 `ref_docs/shape_chip_round_normal.xml` 的 `corners radius=28dp`）。
     *
     * 用 `StateListDrawable` 而不是在焦点回调里 `setBackground`：焦点与选中是两个独立状态位，
     * 代码里分两处按事件顺序重画，一旦「先失焦、后改 selectedIndex」就会留下一帧
     * 「实心胶囊已在 B、A 还是选中红框」的残影；交给状态机后每帧按真实状态取值。
     */
    private fun tabBackground(): Drawable {
        val states = StateListDrawable()
        ContextCompat.getDrawable(context, R.drawable.bg_global_focus_capsule)?.let {
            states.addState(intArrayOf(android.R.attr.state_focused), it)
        }
        ContextCompat.getDrawable(context, R.drawable.bg_global_selected_capsule_border)?.let {
            states.addState(intArrayOf(android.R.attr.state_selected), it)
        }
        states.addState(intArrayOf(), idleCapsule())
        return states
    }

    /** 未选中常态底：深色半透明胶囊（与焦点 / 选中态同 28dp 全圆角，切换时不跳形）。 */
    private fun idleCapsule(): Drawable = GradientDrawable().apply {
        cornerRadius = dp(TAB_IDLE_RADIUS_DP).toFloat()
        setColor(tabIdleSurface)
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

        /** 焦点红色短横线：20dp × 2dp（焦点态整项放大 1.1 倍后，正好是原版实测的 22dp 宽）。 */
        private const val TOP_BAR_UNDERLINE_WIDTH_DP = 20
        private const val TOP_BAR_UNDERLINE_HEIGHT_DP = 2

        /** 短横线端部圆角：ref_docs/shape_red_bar.xml `corners radius=2dp`。 */
        private const val TOP_BAR_UNDERLINE_RADIUS_DP = 2

        /**
         * 顶部预留 = 横线高（2dp）：让 24dp 文字区在 64dp 行里居中（20..44dp），
         * 横线紧贴文字区下沿（44..46dp）。原版没有「文字与横线之间」的间距，
         * 横线是直接接在文字区下沿的（放大 1.1 后落在实测的 45..47dp）。
         */
        private const val TOP_BAR_UNDERLINE_RESERVE_DP = TOP_BAR_UNDERLINE_HEIGHT_DP

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

        /**
         * 分类 Tab 常态底圆角：直接照抄 `ref_docs/shape_chip_round_normal.xml` 的 `corners radius=28dp`。
         *
         * 28dp 远超 26dp 行高的一半，`GradientDrawable` 会把它夹成「全圆角」，
         * 于是常态底和焦点 / 选中态的 28dp 胶囊完全同形（只是不填充红色），不会出现「常态是方角、
         * 一聚焦突然变胶囊」的跳变。
         */
        private const val TAB_IDLE_RADIUS_DP = 28

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

