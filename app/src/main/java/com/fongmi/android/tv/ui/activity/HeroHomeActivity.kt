package com.fongmi.android.tv.ui.activity

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import coil3.compose.AsyncImage
import com.fongmi.android.tv.R
import com.fongmi.android.tv.api.SiteApi
import com.fongmi.android.tv.api.config.VodConfig
import com.fongmi.android.tv.bean.Result
import com.fongmi.android.tv.bean.Site
import com.fongmi.android.tv.bean.Vod
import com.fongmi.android.tv.model.SiteViewModel
import com.fongmi.android.tv.setting.Setting
import com.fongmi.android.tv.setting.SiteBlockSetting
import com.fongmi.android.tv.ui.hero.ChangeSourceDialog
import com.fongmi.android.tv.ui.hero.HeroAccentColor
import com.fongmi.android.tv.ui.hero.HeroFocusBrush
import com.fongmi.android.tv.ui.hero.HeroHomeLogoButton
import com.fongmi.android.tv.ui.hero.HeroImageLoader
import com.fongmi.android.tv.ui.hero.HeroSourceEntry
import com.fongmi.android.tv.ui.hero.SourceSidebar
import com.fongmi.android.tv.ui.hero.SourceSidebarMode
import com.fongmi.android.tv.ui.hero.TmdbMatchDialog
import com.fongmi.android.tv.ui.hero.TmdbMatchItem
import com.fongmi.android.tv.utils.Notify
import com.fongmi.android.tv.utils.PushParser
import kotlinx.coroutines.launch

/**
 * HeroUI（Compose）全屏沉浸式首页。
 *
 * 本文件的每一个尺寸都直接取自 `ref_docs/activity_home_v2.xml` 的真实节点参数，
 * 不再有“按屏幕反推”或“目测调参”的数值。行号注释指向该 XML。
 *
 * 分层（自下而上，与 XML 的子节点顺序一一对应）：
 * 1. [HeroBackdrop]：全屏背景大图（XML `heroBg`），随海报焦点用 [Crossfade] 平滑切换。
 * 2. [HeroMaskLeft]：横向黑色渐变（XML `maskLeft` / `bg_hero_mask_h`）。
 * 3. [HeroMaskBottom]：纵向黑色渐变（XML `maskBottom` / `bg_hero_mask_v`）。
 * 4. 内容 Column（XML `bottomContent` + `navBar`）：顶栏 → 弹性留白 → 左侧剧名信息 → 分类 Tab → 海报区。
 *
 * 关键参数（XML 行号）：
 * - 全局左右边距 24dp：`navBar` L208-209、`heroInfo` L69-70、`typeRecycler` L138-139、`recycler` L157-158。
 * - 顶栏高 64dp：L207。
 * - 剧名 32sp 粗体 / 固定高 36dp / 阴影 #cc000000 2,2,12：L72-83。
 * - 元数据 14sp / #ccffffff / marginTop 2dp / 高 20dp：L93-104。
 * - 简介 12sp / #bbffffff / 高 40dp / maxLines 2 / lineSpacingExtra 3dp：L112-125。
 * - 信息区 marginBottom 22dp：L68；Tab 栏 marginBottom 12dp：L137；海报区高 215dp：L147。
 * - 卡片 120×180dp / 8dp 圆角 / 标题 marginTop 8dp + 13sp：`ref_docs/adapter_vod_rect.xml` L24-27、L45-57。
 *
 * 焦点契约（三区各自持有 FocusRequester，全部显式交接，不依赖 2D 自动搜索）：
 * - 进入 Tab 栏（无论从 TopBar ↓ 还是从海报行 ↑）：落到**记忆中的**上一次选中 Tab。
 * - 进入 TopBar 或海报行（从别的排切入）：一律落到各自最左侧的第一项。
 * - 海报行 ←/→ 行内移动；仅当焦点到达第 5 张（index = 4）时把该段推到视觉最左侧，
 *   index 0..3 不滚动、左侧不露出上一张的边；海报行 ↓ 留在原地（已在最底行）。
 */
class HeroHomeActivity : ComponentActivity() {

    /**
     * Activity 作用域的 [SiteViewModel]。
     *
     * 首页真实化（`homeContent()` 写 `result`）和换源弹窗（`searchContent(List)` 写 `search`）
     * **必须共用这一个实例**：它构造函数里就建了两个单线程 executor，按次 new 会泄漏线程；
     * 同时两条 LiveData 通道各自独立，首页刷新不会打断换源检索。
     */
    private val siteViewModel: SiteViewModel by lazy {
        ViewModelProvider(this)[SiteViewModel::class.java]
    }

    /**
     * 上一次发起 `homeContent()` 时用的接口源 key。
     *
     * [onResume] 拿它和当前 [VodConfig] 的首页源比对：不同就说明用户刚在设置页换了源，
     * 需要重新拉一次首页；相同则直接跳过，避免每次回到前台都白刷一遍。
     */
    private var loadedHomeSiteKey: String = ""

    /** 上一次按返回键的单调时间戳（毫秒）；`null` 表示当前不在“等待第二次确认”的窗口内。 */
    private var lastBackPressedAt: Long? = null

    /**
     * 与全站其它页面共用同一套「设计画布」。
     *
     * `ui/base/BaseActivity`（Java 体系）和 [HeroSearchActivity] 都在这里套 `Setting.wrapDisplay`，
     * 于是全站 Compose 拿到的 density 都来自同一份配置（界面缩放 × 系统 density，设计画布 960×540dp）。
     * 本页是 [ComponentActivity]，不走 BaseActivity；缺了这一步就会退回**系统原始 density**，
     * 在用户把「界面缩放」调小/调大时，首页所有 dp 与其它页面对不上（1dp 偏大、字号整体变大），
     * 弹出的 Compose Dialog 也会跟着一起被放大。
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Setting.wrapDisplay(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(AndroidColor.BLACK))
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        installDoubleBackToExit()
        HeroImageLoader.install()
        // 首页真实化：记录「当前用的是哪个源」。onResume 靠它判断用户是不是刚从设置页换了源。
        loadedHomeSiteKey = VodConfig.get().getHome().key
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                HeroHomeScreen(
                    siteViewModel = siteViewModel,
                    // 首次加载与切源后重载都走这里，日志只在一个地方打。
                    onRefreshHome = { refreshHome("界面请求") },
                    // 卡片确定键 → 真实详情页，带上真实的 (Vod, Site)。
                    onOpenDetail = { vod, site -> HeroDetailActivity.start(this, vod, site) },
                    // 顶栏「设置」：SettingActivity 只存在于 leanback flavor，见 openSettings 的说明。
                    onOpenSettings = { openSettings() },
                )
            }
        }
    }

    /**
     * 从设置页回来时强制刷新首页。
     *
     * 用户在设置页里新增 / 切换接口源后，[VodConfig] 的内存状态已经变了，
     * 但首页不会自动重拉 —— 这里按「源 key 是否变化」判断，变了就重新请求一次。
     *
     * 为什么不每次都刷：`onResume` 在弹窗关闭、回到前台时也会触发，
     * 每次无条件请求会让首页反复闪进度条，也浪费一次网络往返。
     */
    override fun onResume() {
        super.onResume()
        val site = VodConfig.get().getHome()
        Log.d(TAG_HOME, "onResume 当前接口源: key=${site.key} name=${site.displayName}")
        if (site.key == loadedHomeSiteKey) return
        Log.d(TAG_HOME, "接口源已变化: ${loadedHomeSiteKey} -> ${site.key}，重新请求 homeContent")
        refreshHome("设置页返回")
    }

    /**
     * 真正发起一次首页请求。
     *
     * [SiteViewModel.homeContent] 内部是 `SiteApi.homeContent(VodConfig.get().getHome())`，
     * 写的是 `result` 这条 LiveData（**不是**换源弹窗用的 `search`），所以两者互不干扰。
     */
    private fun refreshHome(reason: String) {
        val site = VodConfig.get().getHome()
        loadedHomeSiteKey = site.key
        Log.d(
            TAG_HOME,
            "请求 homeContent（$reason）: key=${site.key} name=${site.displayName} " +
                "总源数=${VodConfig.get().getSites().size}",
        )
        if (site.key.isEmpty()) {
            Log.w(TAG_HOME, "当前接口源为空，homeContent 必然失败：请先在设置页添加接口源")
        }
        siteViewModel.homeContent()
    }

    /**
     * 顶栏「设置」。
     *
     * `SettingActivity` 只存在于 `app/src/leanback/`，而本文件在 `main` 源集、同时被
     * mobile / leanback 两个 manifest 引用 —— `main` 在编译期看不到那个类，
     * 直接写 `SettingActivity.start(this)` 会让 mobile 变体编译失败。
     *
     * 所以这里用类名反射调用它现成的 `public static void start(Activity)`：
     * - leanback：正常打开设置页；
     * - mobile：类不存在 → 走 onFailure 提示，不崩溃。
     *
     * 等设置页下沉到 `main` 源集后，删掉这段反射、改回直接调用即可。
     */
    private fun openSettings() {
        runCatching {
            Class.forName(SETTING_ACTIVITY_CLASS)
                .getMethod("start", Activity::class.java)
                .invoke(null, this)
        }.onFailure {
            Notify.show(SETTINGS_UNAVAILABLE_HINT)
        }
    }

    /**
     * TV 端“双击返回键退出”：
     * 第一次按返回只弹提示并吞掉事件，[EXIT_BACK_INTERVAL_MS] 内再按一次才真正 [finish]。
     * 超过窗口再按，则重新计时并再次提示。
     *
     * 用 [SystemClock.elapsedRealtime] 而不是 `System.currentTimeMillis`：单调时钟不受系统时间跳变影响。
     * 回调挂到 Activity 的 dispatcher 上，生命周期自动跟随，无需在 onDestroy 里手动移除；
     * 占位 Dialog 的返回键由 Dialog 窗口自己消费，不会走到这里。
     */
    private fun installDoubleBackToExit() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val now = SystemClock.elapsedRealtime()
                val last = lastBackPressedAt
                if (last != null && now - last <= EXIT_BACK_INTERVAL_MS) {
                    lastBackPressedAt = null
                    finish()
                    return
                }
                lastBackPressedAt = now
                Notify.show(EXIT_BACK_HINT)
            }
        })
    }

    /**
     * 播放桥接：复用项目现有的 [VideoActivity] + [PushParser] 入口。
     * Intent 与 Bundle 全部由 VideoActivity 内部封装，这里不拼任何 EXTRA key。
     */
    fun launchPlayback(context: Context, vodName: String) {
        val activity = context.findActivity() ?: return
        val push = PushParser.of(DEMO_STREAM_URL, vodName)
        VideoActivity.startDirect(activity, SiteApi.PUSH, push.getId(), push.getName(), null, null)
    }

    private fun Context.findActivity(): Activity? {
        var current: Context = this
        while (true) {
            if (current is Activity) return current
            current = (current as? ContextWrapper)?.baseContext ?: return null
        }
    }

    private companion object {

        // 公开测试流，仅用于确认“点击 → 原有 PlaybackActivity”这条链路；可随时替换为任意可访问直链。
        private const val DEMO_STREAM_URL = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"

        // 双击返回键的确认窗口：2 秒内再按才退出。
        private const val EXIT_BACK_INTERVAL_MS = 2000L
        private const val EXIT_BACK_HINT = "再按一次返回键退出应用"

        // 顶栏「设置」：SettingActivity 只存在于 leanback flavor，用类名反射调用（见 openSettings）。
        private const val SETTING_ACTIVITY_CLASS = "com.fongmi.android.tv.ui.activity.SettingActivity"
        private const val SETTINGS_UNAVAILABLE_HINT = "当前版本没有设置页"
    }
}

@Composable
private fun HeroHomeScreen(
    // Activity 作用域的 SiteViewModel：首页 `homeContent()` 与换源弹窗共用这一个实例。
    siteViewModel: SiteViewModel,
    // 重新拉首页数据。首次进入、小房子切源、设置页返回都走这一个入口。
    onRefreshHome: () -> Unit,
    // 卡片确定键：回传真实的 (Vod, Site)，由 Activity 交给 HeroDetailActivity。
    onOpenDetail: (Vod, Site) -> Unit,
    // 顶栏「设置」：真正打开页面的逻辑在 HeroHomeActivity 里（需要按 flavor 反射），由调用方传 lambda。
    onOpenSettings: () -> Unit,
) {
    val posterState = rememberLazyListState()
    val tabState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 三个焦点区各自持有一组 FocusRequester：跨区移动用显式 requestFocus，
    // 避免 2D 焦点搜索在“顶栏项宽 ≠ 卡片宽”时落空。
    val topBarRequesters = remember { List(TOP_BAR_LABELS.size) { FocusRequester() } }
    val tabRequesters = remember { List(HERO_TABS.size) { FocusRequester() } }

    // ------------------------------------------------------------------
    // 首页真实数据
    // ------------------------------------------------------------------
    // `SiteViewModel.homeContent()` 写的是 `result` 这条 LiveData；换源弹窗用的是 `search`，
    // 两条通道各自独立，首页刷新不会打断换源检索。
    //
    // 本项目没有 `androidx.compose.runtime:runtime-livedata` 依赖（没有 `observeAsState`），
    // 这里用行为完全等价的 observeForever + onDispose 摘除，包括「立刻重放上一次值」这一特性 ——
    // 所以 Activity 重建时旧数据会先回来，不会闪一下空列表。
    var homeResult by remember { mutableStateOf<Result?>(null) }
    // 已经自动加载过的「站点:分类」。分类结果本身也可能为空，用它挡掉来回死循环。
    var autoCategoryKey by remember { mutableStateOf<String?>(null) }

    /**
     * 重新拉一次首页，并复位「自动分类」标记。
     *
     * 首次进入、小房子切源都走这里；设置页返回由 Activity 的 `onResume` 直接调 `onRefreshHome()`。
     */
    fun reloadHome() {
        autoCategoryKey = null
        onRefreshHome()
    }

    DisposableEffect(siteViewModel) {
        // `SiteViewModel.init()` 会把 `result` 置为 null，LiveData 会把 null 一起派发下来，
        // 所以这里必须按可空接收：null 直接忽略（等价于「还没拿到数据」）。
        val observer = Observer<Result?> { result ->
            if (result == null) return@Observer
            val site = VodConfig.get().getHome()
            // 【关键】`SiteApi.homeContent` / `categoryContent` 都不像 `searchContent` 那样给每条 Vod 盖 site。
            // 不补的话 `Vod.getSiteKey()` 是空串：详情页「排除当前源」直接失效、来源标签也是空白。
            result.list.forEach { it.setSite(site) }
            Log.d(TAG_HOME, "获取到真实数据条数=${result.list.size} msg=${result.msg} site=${site.key}")
            // 与原版 HomeActivity.addVideo() 完全一致：首页 list 为空但站点返回了分类时，
            // 自动去拉第一个分类的第 1 页。只返回 class、不返回 list 的源靠这一步才有内容，
            // 少了它就是「原版正常、我们空白」。
            val firstType = result.types.firstOrNull()
            if (result.list.isEmpty() && firstType != null) {
                val categoryKey = site.key + ":" + firstType.typeId
                if (categoryKey != autoCategoryKey) {
                    autoCategoryKey = categoryKey
                    Log.d(TAG_HOME, "首页列表为空，自动加载第一个分类: ${firstType.typeName}(${firstType.typeId})")
                    siteViewModel.categoryContent(site.key, firstType.typeId, "1", true, hashMapOf())
                    return@Observer
                }
            }
            homeResult = result
        }
        siteViewModel.result.observeForever(observer)
        onDispose { siteViewModel.result.removeObserver(observer) }
    }
    // 首次进入就请求一次。失败 / 空列表一律交给下面的空态，**绝不回退任何假数据**。
    LaunchedEffect(Unit) {
        Log.d(TAG_HOME, "首次进入首页，触发 homeContent")
        reloadHome()
    }

    /** Vod → HeroPoster 的唯一映射点；`homeResult == null`（首帧 / 首次请求在途）时返回空列表。 */
    val homePosters = remember(homeResult) { homeResult.toHomePosters() }


    // Tab 栏的记忆索引：从第一排按“下”或从第三排按“上”进入时，都落回这一项。
    var rememberedTabIndex by remember { mutableIntStateOf(0) }
    // 真正的“选中分类”：**只有确定键能改**，焦点移动一律不改。
    // 初值 -1 = 用户还没明确选过任何分类（此时海报用第 0 类数据兜底，但 Tab 一律不涂红）。
    // 焦点态由每个 Tab 自己用 onFocusChanged 局部管理，不落到这个全局状态里。
    var selectedTabIndex by remember { mutableIntStateOf(-1) }
    var focusedPosterIndex by remember { mutableIntStateOf(0) }
    // 焦点是否还在「Tab 栏 + 海报区」这一模块里。
    // 《焦点总结》第 ②③ 条的分界线：只有还在模块内，选中的 Tab 才挂红框；
    // 一旦 ↑ 回顶栏，选中态整体休眠，不留任何红框。
    var moduleFocused by remember { mutableStateOf(false) }
    // 换源弹窗（View Dialog）打开状态 + 关闭后要还焦点的那张卡（-1 = 没有待处理）。
    var showSourceDialog by remember { mutableStateOf(false) }
    // TMDB 手动匹配弹窗（Compose Dialog）打开状态；关闭后的焦点同样由 pendingPosterFocus 交还。
    var showTmdbDialog by remember { mutableStateOf(false) }
    var pendingPosterFocus by remember { mutableIntStateOf(-1) }

    // TMDB 手动匹配结果：按「剧名 → 匹配项」记一份 override，
    // 命中时把首页这张卡的海报与背景图一起换成 TMDB 海报（只换图，不动 Vod 本身）。
    var tmdbOverrides by remember { mutableStateOf<Map<String, TmdbMatchItem>>(emptyMap()) }
    val posters = remember(homePosters, tmdbOverrides) {
        homePosters.map { poster ->
            val override = tmdbOverrides[poster.title]
            if (override != null) poster.copy(url = override.poster, backdropUrl = override.poster) else poster
        }
    }
    // 【防越界】真实列表长度不固定：必须跟着 posters.size 重建。
    // 写死成 `List(常量)` 会在接口源返回更多条目时于 `requesters[index]` 处越界崩溃。
    val posterRequesters = remember(posters.size) { List(posters.size) { FocusRequester() } }
    // 真实数据可能为空：这里必须可空，不能用 `posters[0]` 兜底（空列表会直接抛越界）。
    val heroPoster = posters.getOrNull(focusedPosterIndex) ?: posters.firstOrNull()

    // 换源 / 刷新后列表可能变短：把记忆下标夹回合法范围，
    // 否则「信息区显示第一张、焦点却在第 N 张」会错位。
    LaunchedEffect(posters.size) {
        if (focusedPosterIndex > posters.lastIndex) {
            focusedPosterIndex = posters.lastIndex.coerceAtLeast(0)
        }
    }

    // 任意一排 → 顶栏：默认落在最左侧的第一项（小房子），不记忆上次位置。
    // 同时清掉「模块内」标记：选中的分类 Tab 立刻退成无红框、无红底（规则 ③）。
    fun focusTopBar() {
        moduleFocused = false
        requestFocus(topBarRequesters, 0)
    }

    // 任意一排 → Tab：
    // - 从卡片按 ↑ 上来时必须回到**当前选中的分类**（红框那一个），
    //   而不是「焦点上一次路过的 Tab」——那个只是实心红块，用户根本没按确定键，
    //   回到它等于把用户选好的分类换成别的（图 1 标注的 bug）；
    // - 还没按过任何分类（-1）时退回「焦点上次停留项」，再不行落第一项。
    // 注意：这里只移动焦点，**绝不改 selectedTabIndex**。
    fun focusTab(index: Int? = null) {
        val fallback = if (selectedTabIndex >= 0) selectedTabIndex else rememberedTabIndex
        val target = (index ?: fallback).coerceIn(0, HERO_TABS.lastIndex)
        rememberedTabIndex = target
        scope.launch { tabState.animateScrollToItem(target) }
        if (!requestFocus(tabRequesters, target)) requestFocus(tabRequesters, 0)
    }

    // → 海报行：从别的排切入时用默认参数 index = 0，永远落在最左侧的第一张。
    fun focusPoster(index: Int = 0) {
        // 真实数据可能为空（首次加载中 / 接口源返回空）：空列表时 `coerceIn(0, -1)` 会直接抛
        // IllegalArgumentException，所以必须先挡掉，焦点留在原地。
        if (posters.isEmpty()) return
        val target = index.coerceIn(0, posters.lastIndex)
        // 靠左吸附：焦点在 index 0..3 时锚点恒为 0，列表不动，右侧露出第 8 张的边；
        // 焦点到 index 4 及以后，锚点 = 焦点索引 → 被选中的卡正好停在 24dp 基准线上，
        // 上一张被挤到 24dp 左侧、在屏幕最左缘出血露出一截。
        val anchor = if (target >= POSTER_SCROLL_ANCHOR) target else 0
        if (requestFocus(posterRequesters, target)) {
            if (posterState.firstVisibleItemIndex == anchor && posterState.firstVisibleItemScrollOffset == 0) return
            scope.launch { posterState.animateScrollToItem(anchor) }
            return
        }
        // 目标卡片还没组合（例如刚切分类就要跳到靠后的卡片）：先滚过去，等一帧再请求焦点。
        scope.launch {
            posterState.scrollToItem(anchor)
            withFrameNanos { }
            requestFocus(posterRequesters, target)
        }
    }

    // 海报行内左右移动：到行首/行尾时事件已被消费，焦点留在原地，不会掉出海报区。
    fun focusPosterHorizontal(fromIndex: Int, delta: Int) {
        val target = fromIndex + delta
        if (target !in posters.indices) return
        focusPoster(target)
    }

    // 长按卡片确定键 → 换源弹窗（View 体系独立 Dialog，见 [ChangeSourceDialog]，层级盖在首页之上）。
    // 打开期间用 [showSourceDialog] 占位，关闭后由下面的 LaunchedEffect 把焦点还给长按的那张卡。
    fun changeSource() {
        // 关键词 = 当前这张卡对应的**真实剧名**（`Vod.getName()`）。
        // 还没有数据时换源没有意义，直接忽略这次长按。
        val poster = heroPoster ?: return
        val keyword = poster.title
        val currentSiteKey = VodConfig.get().getHome().key
        // 候选源规则集中在 ChangeSourceDialog.candidates：
        // isSearchable + isChangeable + 非隐藏 + 未被屏蔽 + 非当前源，最后按站点健康度排序，
        // 与 TmdbDetailActivity 的换源完全一致。
        val candidates = ChangeSourceDialog.candidates(currentSiteKey)
        if (candidates.isEmpty()) {
            Notify.show(context.getString(R.string.play_change_empty))
            return
        }
        pendingPosterFocus = focusedPosterIndex
        showSourceDialog = true
        ChangeSourceDialog.show(
            context = context,
            // 必须复用 Activity 作用域的 ViewModel：它自带两个单线程 executor，不能按弹窗 new。
            viewModel = siteViewModel,
            sites = candidates,
            keyword = keyword,
            // 与 TmdbDetailActivity L11261 的换源检索一致：只搜 quick 源。
            quick = true,
            onSourceSelected = { vod, site ->
                // 用户在弹窗里选中了另一部剧：vod 已由 SiteApi.searchContent 盖过 site，
                // 两者同源，直接用它俩打开该源的真实详情页，不再按剧名二次搜索。
                Log.d(TAG_HOME, "换源选中: ${site.displayName} / ${vod.name}")
                onOpenDetail(vod, site)
            },
            onClosed = { showSourceDialog = false },
        )
    }

    // 换源弹窗关闭后再抢焦点：Dialog 消失时首页窗口要过一帧才重新拿到焦点，
    // 单次 requestFocus 常落空（与详情页同一套处理），所以重试几帧。
    LaunchedEffect(showSourceDialog) {
        if (showSourceDialog) return@LaunchedEffect
        val target = pendingPosterFocus
        pendingPosterFocus = -1
        if (target < 0) return@LaunchedEffect
        repeat(5) {
            if (requestFocus(posterRequesters, target)) return@LaunchedEffect
            withFrameNanos { }
        }
    }

    // TMDB 匹配弹窗关闭后再抢焦点：Compose Dialog 消失时首页窗口同样要过一帧才重新拿到焦点，
    // 所以和换源弹窗一样重试几帧，把焦点还给长按的那张卡。
    LaunchedEffect(showTmdbDialog) {
        if (showTmdbDialog) return@LaunchedEffect
        val target = pendingPosterFocus
        pendingPosterFocus = -1
        if (target < 0) return@LaunchedEffect
        repeat(5) {
            if (requestFocus(posterRequesters, target)) return@LaunchedEffect
            withFrameNanos { }
        }
    }

    // 启动时焦点落在“小房子”；侧边栏关闭后的焦点由 onClosed 回调交还。
    LaunchedEffect(Unit) {
        requestFocus(topBarRequesters, 0)
    }

    // 切分类后海报行回到最左，保证“Tab ↓ 落到第一张”和视觉一致。
    LaunchedEffect(selectedTabIndex) {
        posterState.scrollToItem(0)
    }

    // 数据从「有」变「无」（切到空源 / 接口源返回空）时，海报区的焦点会悬空，
    // 这里统一收回小房子，保证任何时候都有明确的焦点落点。
    LaunchedEffect(posters.isEmpty()) {
        if (!posters.isEmpty()) return@LaunchedEffect
        withFrameNanos { }
        requestFocus(topBarRequesters, 0)
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // ---- 底层：全屏背景大图 + 横向/纵向遮罩（XML 顺序：heroBg → maskLeft → maskBottom）----
        // 没有数据时传空串，Coil 不加载任何东西，只剩黑色底 + 两层遮罩。
        HeroBackdrop(url = heroPoster?.backdropUrl.orEmpty())
        HeroMaskLeft()
        HeroMaskBottom()

        // ---- 顶层：与 XML 的 bottomContent + navBar 同构 ----
        // 横向边距不在这里统一设置：XML 里 navBar / heroInfo / typeRecycler / recycler
        // 各自持有 24dp 的 paddingStart|End，这里同样由各区自己加，保证与 XML 一一对应。
        Column(modifier = Modifier.fillMaxSize()) {
            HeroTopBar(
                requesters = topBarRequesters,
                onHomeConfirm = {
                    // 小房子确定键拉起全局接口源侧边栏（View Dialog），关闭后焦点回到小房子。
                    // 列出「全部可用源」：隐藏源与用户屏蔽源由 SiteBlockSetting.filter(…, false) 过滤。
                    val homeSites = SiteBlockSetting.filter(VodConfig.get().getSites(), false)
                    if (homeSites.isEmpty()) {
                        Notify.show(context.getString(R.string.play_change_empty))
                    } else {
                        SourceSidebar.show(
                            context = context,
                            entries = HeroSourceEntry.of(homeSites),
                            mode = SourceSidebarMode.Single,
                            // 当前首页源在侧边栏里保持选中态，也是默认焦点（按 key 匹配，不用下标）。
                            currentSiteKey = VodConfig.get().getHome().key,
                            onSiteSelected = { site ->
                                val current = VodConfig.get().getHome()
                                if (site.key == current.key) {
                                    // 选中的就是当前源：不重复发请求，只给个反馈。
                                    Notify.show(context.getString(R.string.source_sidebar_switched, site.displayName))
                                } else {
                                    // 真正切换首页接口源。
                                    // `setHome` 会写配置并 post `RefreshEvent.home()`，但本页不订阅 EventBus，
                                    // 所以必须自己再拉一次 homeContent()，否则界面还是旧源的数据。
                                    Log.d(TAG_HOME, "小房子切换接口源: ${current.key} -> ${site.key}")
                                    VodConfig.get().setHome(site)
                                    // 换源必须连「自动分类」标记一起复位，否则新源若同样需要自动分类，
                                    // 会命中旧 key 的判断而不重新请求。
                                    reloadHome()
                                    Notify.show(context.getString(R.string.source_sidebar_switched, site.displayName))
                                }
                            },
                            onClosed = {
                                scope.launch {
                                    withFrameNanos { }
                                    requestFocus(topBarRequesters, 0)
                                }
                            },
                        )
                    }
                },
                // 顶栏「历史」→ HeroUI 历史页（View 体系，复用 HeroPosterCard）。
                // 这里在 HeroHomeScreen 作用域内，`context` 就是上面 LocalContext.current 拿到的那个。
                onOpenHistory = { HeroHistoryActivity.start(context) },
                // 顶栏「搜索」→ HeroSearchActivity（Compose 体系，纯 UI 结构 + 假数据）。
                // 把首页当前这张大图一起带过去：搜索页用它做磨砂背景（同原版模糊首页大图的做法）。
                onOpenSearch = { HeroSearchActivity.start(context, heroPoster?.backdropUrl.orEmpty()) },
                // 顶栏「设置」→ SettingActivity（只存在于 leanback flavor，由 Activity 侧反射打开）。
                onOpenSettings = onOpenSettings,
                // 顶栏 ↓ 进入 Tab 栏：落到记忆中的 Tab，而不是与顶栏项横向对齐的那一个。
                onMoveDown = { focusTab() },
            )
            // XML 里 bottomContent 用 layout_alignParentBottom 贴底；Compose 用弹性留白表达同一语义。
            Spacer(modifier = Modifier.weight(1f))
            // 真实数据为空时**只显示状态文案**，绝不回退任何假数据。
            // homeResult == null → 首次请求还在途（或还没触发），显示「正在加载」。
            if (heroPoster != null) {
                HeroInfoArea(poster = heroPoster)
            } else {
                HeroHomeStatus(
                    loading = homeResult == null,
                    message = homeResult?.msg.orEmpty(),
                )
            }
            HeroTabBar(
                state = tabState,
                requesters = tabRequesters,
                // -1 = 尚未明确选择任何分类 → 所有 Tab 都不涂红（只有焦点态才红）。
                selectedIndex = selectedTabIndex,
                // 焦点还在模块内：让「选中但失焦」的 Tab 挂红框（规则 ②）。
                moduleFocused = moduleFocused,
                // 焦点只记录“上次停留位置”，不改变分类，也不改变选中态。
                onFocused = { index ->
                    rememberedTabIndex = index
                    moduleFocused = true
                },
                // 确定键才是「明确被选中」：只有这里会切换分类（红底随之转移）。
                onConfirm = { index ->
                    rememberedTabIndex = index
                    selectedTabIndex = index
                },
                // Tab ↑ 回顶栏、↓ 进海报行：两边都默认落在各自最左侧的第一项。
                onMoveUp = { focusTopBar() },
                onMoveDown = { focusPoster() },
                onMoveLeft = { index -> focusTab(index - 1) },
                onMoveRight = { index -> focusTab(index + 1) },
            )
            PosterArea(
                posters = posters,
                state = posterState,
                requesters = posterRequesters,
                onFocused = { index ->
                    focusedPosterIndex = index
                    // 焦点进了卡片：选中的分类 Tab 从实心红块退成红框（规则 ②），
                    // 两张卡与 Tab 的焦点指示同时可见，用户始终知道自己在看哪个分类。
                    moduleFocused = true
                },
                // 卡片确定键：打开这条数据**所属源**的真实详情页（Vod 与 Site 一起带过去）。
                onClick = { poster -> onOpenDetail(poster.vod, poster.site) },
                // 卡片长按确定键：呼出换源弹窗（见 changeSource）。
                onLongPress = { changeSource() },
                // 卡片长按方向下键 1.5s：呼出 TMDB 手动匹配弹窗（见下面的 TmdbMatchDialog）。
                onLongPressTmdb = { index ->
                    pendingPosterFocus = index
                    showTmdbDialog = true
                },
                // 海报行 ↑ 回 Tab 栏：落到**当前选中的分类**（见 focusTab 的注释）。
                onMoveUp = { focusTab() },
                onMoveDown = { },
                onMoveLeft = { index -> focusPosterHorizontal(index, -1) },
                onMoveRight = { index -> focusPosterHorizontal(index, 1) },
            )
        }

        // ---- 最上层：TMDB 手动匹配弹窗（Compose Dialog，层级盖在首页之上）----
        // 确定键写回 override → 首页对应卡片的海报/背景换成 TMDB 海报；
        // 关闭后由 LaunchedEffect(showTmdbDialog) 把焦点还给长按的那张卡。
        // 真实数据可能为空（切到空源 / 请求失败），此时没有剧名可匹配，弹窗直接不渲染。
        val tmdbPoster = heroPoster
        if (showTmdbDialog && tmdbPoster != null) {
            TmdbMatchDialog(
                initialKeyword = tmdbPoster.title,
                onConfirm = { item ->
                    tmdbOverrides = tmdbOverrides + (tmdbPoster.title to item)
                    showTmdbDialog = false
                },
                onDismiss = { showTmdbDialog = false },
            )
        }
    }
}

/**
 * 底层背景大图（XML `heroBg`）。
 *
 * 用 [Crossfade] 让“海报焦点变化 → 背景切换”是交叉淡入淡出，而不是硬切；
 * 300ms 与左侧文字的 320ms 呼应：背景略慢半拍，注意力先落在文字上。
 */
@Composable
private fun HeroBackdrop(url: String) {
    Crossfade(
        targetState = url,
        animationSpec = tween(durationMillis = HERO_BACKDROP_FADE_MS),
        label = "heroBackdrop",
        modifier = Modifier.fillMaxSize(),
    ) { backdropUrl ->
        AsyncImage(
            model = backdropUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** 横向遮罩（XML `maskLeft`，drawable `bg_hero_mask_h`）：左侧压暗，保证剧名/简介在浅色背景上可读。 */
@Composable
private fun HeroMaskLeft() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.horizontalGradient(
                    0.0f to Color.Black.copy(alpha = 0.78f),
                    0.52f to Color.Black.copy(alpha = 0.16f),
                    1.0f to Color.Transparent,
                ),
            ),
    )
}

/** 纵向遮罩（XML `maskBottom`，drawable `bg_hero_mask_v`）：顶部压暗顶栏、底部接近纯黑托住海报区。 */
@Composable
private fun HeroMaskBottom() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0.0f to Color.Black.copy(alpha = 0.86f),
                    0.26f to Color.Black.copy(alpha = 0.30f),
                    0.58f to Color.Black.copy(alpha = 0.62f),
                    1.0f to Color.Black.copy(alpha = 0.97f),
                ),
            ),
    )
}

/**
 * 顶部导航栏（XML `navBar`，L199-262）：
 * - 高 64dp（L207），左右 padding 各 24dp（L208-209），内容垂直居中（L200 `gravity=center_vertical`）。
 * - `homeBtn` 28dp（L219-220），`navRecycler` 距它 20dp（L259 `layout_marginStart=20dp`）。
 *
 * 文字样式：第一排独立使用 [TOP_BAR_SP] = 16sp，比第二排 Tab 的 13sp 大一档，形成视觉层级；
 * 字重常态 Medium、焦点 SemiBold；未选中纯白 60% 透明度，
 * 焦点态纯白 + 平滑放大 1.1 倍 + 下方 2dp 红色短横线（[HeroAccentColor]）。
 */
@Composable
private fun HeroTopBar(
    requesters: List<FocusRequester>,
    onHomeConfirm: () -> Unit,
    // 顶栏「历史」→ HeroUI 历史页。Context 不在这里取：本函数是普通 Composable，
    // 作用域里没有 `context` 变量（上层的 LocalContext.current 在 HeroHomeScreen 里），
    // 所以由调用方把「已捕获 context 的 lambda」传进来。
    onOpenHistory: () -> Unit,
    // 顶栏「搜索」→ HeroSearchActivity。同上，由调用方传入已捕获 context 的 lambda。
    onOpenSearch: () -> Unit,
    // 顶栏「设置」→ SettingActivity（只在 leanback flavor）。同样由调用方捕获 Activity 后传入。
    onOpenSettings: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(NAV_BAR_HEIGHT)
            .padding(horizontal = HERO_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // homeBtn：只有“小房子”有动作（占位 Dialog），其余项留待后续接入对应页面。
        HeroTopBarItem(
            label = TOP_BAR_LABELS[0],
            isHome = true,
            requester = requesters[0],
            onConfirm = onHomeConfirm,
            onMoveDown = onMoveDown,
        )
        Row(
            modifier = Modifier.padding(start = NAV_ITEMS_START_GAP),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TOP_BAR_ITEM_SPACING),
        ) {
            for (index in 1 until TOP_BAR_LABELS.size) {
                HeroTopBarItem(
                    label = TOP_BAR_LABELS[index],
                    isHome = false,
                    requester = requesters[index],
                    // 目前「历史」「搜索」「设置」已接好页面，其余项仍是占位（确定键无动作）。
                    onConfirm = {
                        when (TOP_BAR_LABELS[index]) {
                            TOP_BAR_HISTORY_LABEL -> onOpenHistory()
                            TOP_BAR_SEARCH_LABEL -> onOpenSearch()
                            TOP_BAR_SETTINGS_LABEL -> onOpenSettings()
                        }
                    },
                    onMoveDown = onMoveDown,
                )
            }
        }
    }
}

@Composable
private fun HeroTopBarItem(
    label: String,
    isHome: Boolean,
    requester: FocusRequester,
    onConfirm: () -> Unit,
    onMoveDown: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val textColor by animateColorAsState(
        targetValue = if (focused) Color.White else Color.White.copy(alpha = TOP_BAR_IDLE_ALPHA),
        animationSpec = tween(durationMillis = 150),
        label = "topBarText",
    )
    // 焦点态不再改字号（那样会顶动下划线），改为整体平滑放大 1.1 倍。
    val focusScale by animateFloatAsState(
        targetValue = if (focused) TOP_BAR_FOCUS_SCALE else 1f,
        animationSpec = tween(durationMillis = TOP_BAR_ANIM_MS),
        label = "topBarScale",
    )
    val underlineAlpha by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMillis = 150),
        label = "topBarUnderline",
    )
    val interaction = remember { MutableInteractionSource() }

    // 小房子严格照抄 ref_docs/activity_home_v2.xml 的 homeBtn：
    // homeBtn 28dp → homeBtnClip 24dp → 图标 16dp（marginBottom 1dp），全部在 [HeroHomeLogoButton] 内固定。
    val itemModifier = Modifier
        .focusRequester(requester)
        .onFocusChanged { focused = it.isFocused }
        .onPreviewKeyEvent { event ->
            // 顶栏任意一项按“下”都显式交给分类 Tab，保证边界处不会丢焦点。
            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                onMoveDown()
                true
            } else {
                false
            }
        }
        .clickable(interactionSource = interaction, indication = null, onClick = onConfirm)

    if (isHome) {
        // 复用全局「小房子」组件：尺寸固定为 ref_docs/activity_home_v2.xml 的
        // homeBtn 28dp / homeBtnClip 24dp / 图标 16dp（marginBottom 1dp），不再由调用方传参。
        // ↓ 显式交给分类 Tab，保证边界处不会丢焦点。
        HeroHomeLogoButton(
            requester = requester,
            onConfirm = onConfirm,
            onDirection = { key ->
                if (key == Key.DirectionDown) {
                    onMoveDown()
                    true
                } else {
                    false
                }
            },
        )
    } else {
        Column(
            modifier = itemModifier,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 顶部预留与「间距 + 下划线」等高的空白，让 24dp 文字区的中心正好落在
            // 64dp navBar 的垂直中线上，与小房子同心。
            // 不用 offset / marginTop / 非对称 padding —— 那些都会把文字整体顶偏。
            Spacer(modifier = Modifier.height(TOP_BAR_UNDERLINE_RESERVE))
            // 文字项固定 24dp 内容区，字号为第一排专属的 [TOP_BAR_SP] = 16sp，
            // 放大只走 graphicsLayer，不参与布局，下划线不会跳动。
            Box(
                modifier = Modifier.height(TOP_BAR_TEXT_BOX),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    modifier = Modifier.graphicsLayer {
                        scaleX = focusScale
                        scaleY = focusScale
                    },
                    color = textColor,
                    // 第一排 16sp（第二排 Tab 仍是 13sp），常态 Medium、焦点 SemiBold。
                    fontSize = TOP_BAR_SP.sp,
                    fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                )
            }
            Spacer(modifier = Modifier.height(TOP_BAR_UNDERLINE_GAP))
            // 焦点态指示器：2dp 高红色短横线（长度固定 26dp，仅淡入淡出，避免文字宽度变化导致抖动）。
            Box(
                modifier = Modifier
                    .width(TOP_BAR_UNDERLINE_WIDTH)
                    .height(TOP_BAR_UNDERLINE_HEIGHT)
                    .alpha(underlineAlpha)
                    .background(HeroAccentColor, RoundedCornerShape(2.dp)),
            )
        }
    }
}

/**
 * 左侧剧名信息层（XML `heroInfo`，L61-127）。
 *
 * 节点参数逐条对应：
 * - 容器：`layout_marginBottom=22dp`（L68）+ `paddingStart/End=24dp`（L69-70）。
 * - 剧名：32sp 粗体、固定高 36dp、`gravity=bottom`、阴影 #cc000000 dx2 dy2 radius12（L72-83）。
 * - 元数据：14sp、#ccffffff、高 20dp、marginTop 2dp、阴影 #aa000000 1.5/1.5/8（L93-104）。
 * - 简介：12sp、#bbffffff、高 40dp、maxLines 2、lineSpacingExtra 3dp、
 *   外层横向 LinearLayout `weightSum=2` 而子项 `weight=1` → 宽度取一半（L105-125）。
 *
 * 内容随海报焦点变化，用 [AnimatedContent] 做“淡入 + 轻微上滑 / 淡出 + 轻微上移”，
 * 320ms 比背景略快，文字先落位，避免整屏同时变化显得迟滞。
 */
@Composable
private fun HeroInfoArea(poster: HeroPoster) {
    AnimatedContent(
        targetState = poster,
        transitionSpec = {
            // 进场：淡入 + 从下方 1/6 高度滑入；退场：淡出 + 向上 1/6 高度滑出。
            val enter = fadeIn(tween(HERO_INFO_FADE_MS)) +
                slideInVertically(tween(HERO_INFO_FADE_MS)) { height -> height / 6 }
            val exit = fadeOut(tween(HERO_INFO_FADE_MS)) +
                slideOutVertically(tween(HERO_INFO_FADE_MS)) { height -> -height / 6 }
            enter togetherWith exit
        },
        contentAlignment = Alignment.BottomStart,
        label = "heroInfo",
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HERO_PADDING)
            .padding(bottom = HERO_INFO_BOTTOM_GAP),
    ) { current ->
        Column(modifier = Modifier.fillMaxWidth()) {
            // 剧名：固定 36dp，文字贴底。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(HERO_TITLE_HEIGHT),
                contentAlignment = Alignment.BottomStart,
            ) {
                Text(
                    text = current.title,
                    color = Color.White,
                    fontSize = HERO_TITLE_SP.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(shadow = HERO_TITLE_SHADOW),
                )
            }
            Spacer(modifier = Modifier.height(HERO_META_TOP_GAP))
            // 元数据：固定 20dp，文字贴底。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(HERO_META_HEIGHT),
                contentAlignment = Alignment.BottomStart,
            ) {
                Text(
                    text = current.meta,
                    color = HERO_META_COLOR,
                    fontSize = HERO_META_SP.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(shadow = HERO_META_SHADOW),
                )
            }
            Spacer(modifier = Modifier.height(HERO_DESC_TOP_GAP))
            // 简介：外层 weightSum=2 / 子项 weight=1 → 实际占父宽的一半。
            Text(
                text = current.synopsis,
                modifier = Modifier
                    .fillMaxWidth(HERO_DESC_WIDTH_FRACTION)
                    .height(HERO_DESC_HEIGHT),
                color = HERO_DESC_COLOR,
                fontSize = HERO_DESC_SP.sp,
                lineHeight = HERO_DESC_LINE_HEIGHT_SP.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(shadow = HERO_DESC_SHADOW),
            )
        }
    }
}

/**
 * 首页空态 / 加载态。
 *
 * 真实数据为空时**只显示文案**，不显示任何假海报或假剧名。
 * 版式与 [HeroInfoArea] 完全一致（左右 24dp、底部 22dp、36dp 标题行 + 20dp 元数据行 + 简介行），
 * 于是从加载态切到真实数据时文字不会跳位。
 *
 * @param loading `homeResult == null` 时为 true：首次请求还在途（或还没触发）。
 * @param message 接口返回的提示语；`Result.empty()` 的失败场景下为空串，用默认文案兜底。
 */
@Composable
private fun HeroHomeStatus(loading: Boolean, message: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HERO_PADDING)
            .padding(bottom = HERO_INFO_BOTTOM_GAP),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(HERO_TITLE_HEIGHT),
            contentAlignment = Alignment.BottomStart,
        ) {
            Text(
                text = if (loading) "正在加载首页…" else "暂无首页数据",
                color = Color.White,
                fontSize = HERO_TITLE_SP.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(shadow = HERO_TITLE_SHADOW),
            )
        }
        Spacer(modifier = Modifier.height(HERO_META_TOP_GAP))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(HERO_META_HEIGHT),
            contentAlignment = Alignment.BottomStart,
        ) {
            Text(
                text = message.ifBlank {
                    if (loading) "正在请求当前接口源…" else "当前接口源没有返回任何内容"
                },
                color = HERO_META_COLOR,
                fontSize = HERO_META_SP.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(shadow = HERO_META_SHADOW),
            )
        }
        Spacer(modifier = Modifier.height(HERO_DESC_TOP_GAP))
        Text(
            text = "按「小房子」可以切换接口源，或到设置页检查配置",
            modifier = Modifier
                .fillMaxWidth(HERO_DESC_WIDTH_FRACTION)
                .height(HERO_DESC_HEIGHT),
            color = HERO_DESC_COLOR,
            fontSize = HERO_DESC_SP.sp,
            lineHeight = HERO_DESC_LINE_HEIGHT_SP.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(shadow = HERO_DESC_SHADOW),
        )
    }
}

/**
 * 分类 Tab 栏（XML `typeRecycler`，L128-141）：
 * - 左右 padding 24dp（L138-139）、`paddingBottom=2dp`（L130）、`layout_marginBottom=12dp`（L137）。
 *
 * 胶囊样式严格对照 `ref_docs/adapter_type.xml` 的 `selector_item_round`，三态严格分离：
 * - focused（无论是否选中）：[HeroFocusBrush] 红渐变色块 + 纯白字；
 * - 失焦但 selected、且焦点还在 Tab/海报模块内：**红框描边 + 透明底**（规则 ②）；
 * - 焦点离开模块（↑ 回顶栏）或从未选中：`Color.Transparent` 全透明底 + 60% 白字（规则 ③）。
 *   **selected 只由确定键产生**，焦点经过某个 Tab（包括左右键移动）不会把它置为 selected；
 *   用户还没按过确定键时 `selectedIndex = -1`，此时整排都没有红底。
 * - 形状为胶囊（[HERO_TAB_SHAPE]，13dp 圆角，高度 26dp 时刚好半圆）。
 * 背景不做颜色动画，避免过渡帧出现半透明色块。
 * 左右键不交给系统焦点搜索，而是显式 [focusTab]，这样横向滚动与选中态永远同步。
 */
@Composable
private fun HeroTabBar(
    state: LazyListState,
    requesters: List<FocusRequester>,
    selectedIndex: Int,
    moduleFocused: Boolean,
    onFocused: (Int) -> Unit,
    onConfirm: (Int) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: (Int) -> Unit,
    onMoveLeft: (Int) -> Unit,
    onMoveRight: (Int) -> Unit,
) {
    LazyRow(
        state = state,
        modifier = Modifier
            .fillMaxWidth()
            // XML L137：Tab 栏与海报区之间 12dp。
            .padding(bottom = HERO_TAB_BOTTOM_GAP),
        contentPadding = PaddingValues(
            start = HERO_PADDING,
            end = HERO_PADDING,
            bottom = HERO_TAB_BOTTOM_PADDING,
        ),
        horizontalArrangement = Arrangement.spacedBy(HERO_TAB_SPACING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HERO_TABS.forEachIndexed { index, label ->
            // 用 item(key = ...) 而不是 itemsIndexed：后者与海报行的 itemsIndexed 同名，
            // 两个扩展函数无法同时 import，这里直接用 LazyListScope 的成员函数。
            item(key = label) {
                HeroTabChip(
                    label = label,
                    selected = index == selectedIndex,
                    moduleFocused = moduleFocused,
                    requester = requesters[index],
                    onFocused = { onFocused(index) },
                    onConfirm = { onConfirm(index) },
                    onMoveUp = onMoveUp,
                    onMoveDown = { onMoveDown(index) },
                    onMoveLeft = { onMoveLeft(index) },
                    onMoveRight = { onMoveRight(index) },
                )
            }
        }
    }
}

@Composable
private fun HeroTabChip(
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
    var focused by remember { mutableStateOf(false) }
    // 背景三态，两种情况在 modifier 里分开叠加（不留任何隐式默认分支）：
    //   1. isFocused  → HeroFocusBrush 红渐变色块（实心块统一渐变，不再用纯红）
    //   2. selected 且焦点还在 Tab/海报模块内 → 红色描边 + 透明底（规则 ②）
    //   3. 其余（含焦点回顶栏）→ Color.Transparent，绝不能有红块 / 灰块（规则 ③）
    // selected 只由确定键产生；焦点经过不会改它。
    val selectedRing = selected && !focused && moduleFocused
    // 当前分类即使只剩红框，文字也用纯白，和焦点态保持同一层级。
    val chipTextColor = if (focused || (selected && moduleFocused)) Color.White else HERO_TAB_IDLE_TEXT
    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .focusRequester(requester)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
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
            // 基础 Box + Text：不是 Material3 的 FilterChip / Button / Surface，
            // 没有任何自带底色；clickable 传 indication = null，不会残留 ripple 圆圈。
            .clickable(interactionSource = interaction, indication = null, onClick = onConfirm)
            // 高度锁死 26dp + 13dp 圆角 = 完美半圆胶囊；左右 12dp、上下 0dp。
            .height(HERO_TAB_HEIGHT)
            .clip(HERO_TAB_SHAPE)
            // 焦点态才铺红，而且是全局渐变；失焦后一律透明（规则 ②③ 的差别只体现在描边上）。
            .then(
                if (focused) {
                    Modifier.background(HeroFocusBrush, HERO_TAB_SHAPE)
                } else {
                    Modifier
                },
            )
            // 规则 ②：失焦但仍是当前分类、焦点还在模块内 → 红框。
            .then(
                if (selectedRing) {
                    Modifier.border(HERO_TAB_RING_STROKE, HeroAccentColor, HERO_TAB_SHAPE)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = HERO_TAB_H_PADDING, vertical = HERO_TAB_V_PADDING),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = chipTextColor,
            fontSize = HERO_LABEL_SP.sp,
            fontWeight = if (focused || selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

/**
 * 海报区（XML `posterArea`，L142-191）：整块固定高 215dp（L147），内部 recycler 同为 215dp（L156）。
 *
 * 卡片尺寸严格照抄 `ref_docs/adapter_vod_rect.xml`：120dp × 180dp、8dp 圆角、间距 12dp。
 * 列表**靠左吸附**，不做任何居中/对称换算：
 * - `contentPadding = (start 24dp, end 0dp)`，LazyRow 自身铺满整行且不加外层 padding，
 *   于是它的裁剪边界就是屏幕边缘 —— 等价于 Android 的 `clipToPadding=false`：
 *   静止时第一张卡正好压在 24dp 基准线上；滚动后卡片可以一路出血到 x = 0。
 * - 焦点在 index 0..3 时锚点恒为 0，列表不动，右侧自然露出第 8 张卡的边。
 * - 焦点到 index 4 及以后，锚点 = 焦点索引，被选中的卡正好停在 24dp 基准线上，
 *   上一张则被挤到 24dp 左侧、在屏幕最左缘露出一截（出血贴边）。
 */
@Composable
private fun PosterArea(
    posters: List<HeroPoster>,
    state: LazyListState,
    requesters: List<FocusRequester>,
    onFocused: (Int) -> Unit,
    onClick: (HeroPoster) -> Unit,
    onLongPress: () -> Unit,
    onLongPressTmdb: (Int) -> Unit,
    onMoveUp: (Int) -> Unit,
    onMoveDown: (Int) -> Unit,
    onMoveLeft: (Int) -> Unit,
    onMoveRight: (Int) -> Unit,
) {
    LazyRow(
        state = state,
        // 铺满整行、无外层 padding：LazyRow 的裁剪边界 = 屏幕边缘（Compose 等价于 clipToPadding=false）。
        modifier = Modifier
            .fillMaxWidth()
            .height(POSTER_AREA_HEIGHT),
        contentPadding = PaddingValues(start = HERO_PADDING, end = 0.dp),
        horizontalArrangement = Arrangement.spacedBy(POSTER_GAP),
    ) {
        itemsIndexed(items = posters, key = { _, poster -> poster.id }) { index, poster ->
            PosterCard(
                poster = poster,
                requester = requesters[index],
                onFocused = { onFocused(index) },
                onClick = { onClick(poster) },
                // 长按确定键 = 呼出换源弹窗（与这张卡具体是哪部剧无关，换的是接口源）。
                onLongPress = { onLongPress() },
                // 长按方向下键 1.5s = 呼出 TMDB 手动匹配弹窗（按这张卡自己的剧名去匹配）。
                onLongPressTmdb = { onLongPressTmdb(index) },
                // 把“从哪一张发起”带回上层：滚动锚点与左右边界都在上层统一处理。
                onMoveUp = { onMoveUp(index) },
                onMoveDown = { onMoveDown(index) },
                onMoveLeft = { onMoveLeft(index) },
                onMoveRight = { onMoveRight(index) },
            )
        }
    }
}

/**
 * 单张海报卡片：图片区与文字区完全剥离。
 *
 * 尺寸与样式全部对齐参考文件（`ref_docs/adapter_vod_rect.xml`）：
 * - 图片：120dp × 180dp（L24-25），固定尺寸，严格 2:3；圆角 8dp（L27 `Vod.Grid.Large`）。
 * - 标题：13sp（L46）+ marginTop 8dp（L53）+ `#ccffffff`（L47）+ marquee（L48），
 *   这里用 13sp + 8dp 间距 + [basicMarquee]（仅焦点态跑马灯，未聚焦时省略号收尾）。
 * - 焦点缩放：`ref_docs/VodRectPresenter.java` 第 39-41 行 `1.08f`，本项目按产品要求取 1.1。
 * - 描边/阴影：VodRectPresenter 第 90-101 行的 overlay 视觉厚度约 2dp，Compose 用贴边 `border(2.dp)`
 *   加焦点态 `shadow(18.dp)` 表达同一层级。
 *
 * `graphicsLayer` 只挂在图片 Box 上：文字容器字号不变，放大时既不变形也不会被挤压截断。
 */
@Composable
private fun PosterCard(
    poster: HeroPoster,
    requester: FocusRequester,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onLongPressTmdb: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
) {
    // 真实焦点状态一律从 interactionSource 取（Modifier.clickable 会派发 FocusInteraction），
    // 不再用 border(width = 0.dp) 表达“无边框”——0dp 描边在 Skia 里等于 hairline，
    // 会给未选中的卡片画出一圈 1px 红边。
    val interaction = remember { MutableInteractionSource() }
    val isFocused by interaction.collectIsFocusedAsState()
    // 只有真正获得焦点时才存在描边；否则传 null，Modifier.border 完全不绘制。
    val borderStroke: BorderStroke? =
        if (isFocused) BorderStroke(POSTER_FOCUS_BORDER, HeroAccentColor) else null

    val scale by animateFloatAsState(
        targetValue = if (isFocused) POSTER_FOCUS_SCALE else 1f,
        animationSpec = spring(
            dampingRatio = POSTER_SPRING_DAMPING,
            stiffness = POSTER_SPRING_STIFFNESS,
        ),
        label = "posterScale",
    )
    val elevation by animateDpAsState(
        targetValue = if (isFocused) POSTER_FOCUS_ELEVATION else 0.dp,
        animationSpec = spring(
            dampingRatio = POSTER_SPRING_DAMPING,
            stiffness = POSTER_SPRING_STIFFNESS,
        ),
        label = "posterElevation",
    )
    // 长按确认键自己计时：遥控器不发 View/Compose 的 long-click，只能靠按下时刻自己算。
    // `pressAt == 0L` 表示当前没有按着（长按时系统会重复发 KeyDown，用它去重）。
    var pressAt by remember { mutableLongStateOf(0L) }
    // 方向下键同样自己计时：短按仍是「下移」，按住 ≥ [TMDB_LONG_PRESS_MS] 再松手才呼出 TMDB 匹配弹窗。
    var downPressAt by remember { mutableLongStateOf(0L) }

    Column(
        modifier = Modifier
            .width(POSTER_CARD_WIDTH)
            .focusRequester(requester)
            .onFocusChanged {
                // 这里只负责把“焦点换到哪一张”通知给上层，焦点样式统一走 interactionSource。
                if (it.isFocused) {
                    onFocused()
                } else {
                    // 焦点离开时把两个计时都清零，避免下次进来用上一次的按下时刻算出假长按。
                    pressAt = 0L
                    downPressAt = 0L
                }
            }
            .onPreviewKeyEvent { event ->
                // 确定键 / 回车完全由卡片接管（见下方 when 的前两个分支），
                // 这样「短按 = 进详情、长按 ≥ 500ms = 呼出换源弹窗」才不会和 clickable 冲突。
                when (event.key) {
                    Key.DirectionCenter, Key.Enter -> {
                        if (event.type == KeyEventType.KeyDown) {
                            if (pressAt == 0L) pressAt = System.currentTimeMillis()
                        } else {
                            val held = if (pressAt == 0L) 0L else System.currentTimeMillis() - pressAt
                            pressAt = 0L
                            if (held >= POSTER_LONG_PRESS_MS) onLongPress() else onClick()
                        }
                        return@onPreviewKeyEvent true
                    }

                    else -> Unit
                }
                // 方向下键必须在下面的「只处理 KeyDown」总闸之前拦一次，
                // 否则 KeyUp 会被直接放行，永远算不出按住了多久。
                if (event.key == Key.DirectionDown) {
                    if (event.type == KeyEventType.KeyDown) {
                        if (downPressAt == 0L) downPressAt = System.currentTimeMillis()
                    } else {
                        val held = if (downPressAt == 0L) 0L else System.currentTimeMillis() - downPressAt
                        downPressAt = 0L
                        // 长按 → TMDB 手动匹配弹窗；短按 → 原来的下移（首页为空操作）。
                        if (held >= TMDB_LONG_PRESS_MS) onLongPressTmdb() else onMoveDown()
                    }
                    return@onPreviewKeyEvent true
                }
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // 三个方向由卡片自己接管：左右决定行滚动锚点，上回分类条。
                // （↓ 已在上面单独处理：短按下移 / 长按呼出 TMDB 匹配，走不到这里。）
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

                    else -> false
                }
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 图片区：只有这一层参与缩放、描边与阴影。120dp × 180dp 是 adapter_vod_rect 的固定值。
        Box(
            modifier = Modifier
                .width(POSTER_CARD_WIDTH)
                .height(POSTER_CARD_HEIGHT)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .shadow(elevation = elevation, shape = POSTER_SHAPE, clip = false)
                .clip(POSTER_SHAPE)
                .background(POSTER_PLACEHOLDER)
                // 焦点框：未聚焦时 borderStroke 为 null，直接跳过 border 修饰符，一行都不会画；
                // 只有聚焦时才是 2dp 主题红（用 0dp 描边“隐藏”在 Skia 里仍会画出 hairline 红边）。
                .then(if (borderStroke != null) Modifier.border(borderStroke, POSTER_SHAPE) else Modifier),
        ) {
            AsyncImage(
                model = poster.url,
                contentDescription = poster.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // 文字区：marginTop 8dp + 13sp 单行，图片放大时这里尺寸与字号都不变。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = POSTER_TITLE_GAP),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = poster.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (isFocused) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier),
                color = if (isFocused) Color.White else POSTER_TITLE_COLOR,
                fontSize = POSTER_TITLE_SP.sp,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 在已挂载的 FocusRequester 列表里安全地请求焦点：未组合的 Lazy 项会抛异常，这里降级返回 false。 */
private fun requestFocus(requesters: List<FocusRequester>, index: Int): Boolean {
    val requester = requesters.getOrNull(index) ?: return false
    return runCatching { requester.requestFocus() }.isSuccess
}

/** 首页日志统一 TAG：`adb logcat -s HeroHome` 就能看到源切换、请求与回调条数。 */
private const val TAG_HOME = "HeroHome"

/**
 * 首页一张海报卡。
 *
 * 前六个字段只服务 UI；[vod] / [site] 是**真实数据本体**，点确定键时直接交给详情页，
 * 不再按剧名二次搜索（剧名可能重名，`Vod.getId()` 才是站点内唯一键）。
 */
private data class HeroPoster(
    /** 在列表中的下标：只用于 LazyRow 的稳定 key，不参与业务。 */
    val id: Int,
    val title: String,
    val meta: String,
    val synopsis: String,
    val url: String,
    val backdropUrl: String,
    /** 真实的点播条目（名称 / 图片 / 简介 / 年份 / 站点都在里面）。 */
    val vod: Vod,
    /** 这条数据所属的接口源。`homeContent()` 不盖 site，由观察者手动补好后再带下来。 */
    val site: Site,
)

/**
 * `Result` → 首页海报列表：全项目**唯一**的映射点。
 *
 * - `null`（首帧 / 首次请求还在途）→ 空列表，交给 [HeroHomeStatus] 显示加载态；
 * - 请求失败（[Result.empty]）或接口源本身没返回内容 → 同样是空列表，显示空态。
 *
 * 任何情况下都不回退假数据：宁可黑屏 + 一行提示，也不让用户点到一部不存在的剧。
 */
private fun Result?.toHomePosters(): List<HeroPoster> {
    val items = this?.list.orEmpty()
    // 兜底源：`homeContent()` 不盖 site，理论上观察者已经补过；万一为空就用当前首页源。
    val fallbackSite = VodConfig.get().getHome()
    return items.mapIndexed { index, vod ->
        val site = vod.site ?: fallbackSite
        HeroPoster(
            id = index,
            title = vod.name.ifBlank { "未命名" },
            meta = buildMeta(vod),
            synopsis = vod.content,
            // 首页接口只给竖版 `vod_pic`，没有独立的横版背景图：背景同样用它，
            // 由 Coil 的 ContentScale.Crop 裁成 16:9（比留一片纯黑更接近真实产品观感）。
            url = vod.pic,
            backdropUrl = vod.pic,
            vod = vod,
            site = site,
        )
    }
}

/** 元数据行：`年份 · 分类 · 备注`，空字段自动省略，不会留下多余的 `·`。 */
private fun buildMeta(vod: Vod): String = listOf(vod.year, vod.typeName, vod.remarks)
    .filter { it.isNotBlank() }
    .joinToString(" · ")

// 顶栏顺序：小房子 | 直播 | 搜索 | 历史 | 收藏 | 推送 | 设置（对照 ref_docs/home_ui.png）。
private val TOP_BAR_LABELS = listOf("首页", "直播", "搜索", "历史", "收藏", "推送", "设置")

// 顶栏里已接好页面的项：历史 → HeroHistoryActivity（View 体系 + HeroPosterCard）、
// 搜索 → HeroSearchActivity（Compose 体系）、设置 → SettingActivity（仅 leanback，见 openSettings）。
// 直播 / 收藏 / 推送 等对应页面就绪后再按同一写法接。
private const val TOP_BAR_HISTORY_LABEL = "历史"

private const val TOP_BAR_SEARCH_LABEL = "搜索"

private const val TOP_BAR_SETTINGS_LABEL = "设置"

// 分类 Tab 顺序对照 ref_docs/home_ui.png 的胶囊分类条。
private val HERO_TABS = listOf("推荐", "电影", "电视剧", "动漫", "综艺", "纪录片")

// ---------------------------------------------------------------------------
// 全局边距：activity_home_v2.xml 里 navBar(L208-209) / heroInfo(L69-70) /
// typeRecycler(L138-139) / recycler(L157-158) 的 paddingStart|End 全部是 24dp。
// ---------------------------------------------------------------------------
private val HERO_PADDING = 24.dp

// ---------------------------------------------------------------------------
// 顶部导航 navBar（L199-262）
// ---------------------------------------------------------------------------
private val NAV_BAR_HEIGHT = 64.dp          // L207 layout_height（homeBtn 在此高度内 center_vertical）
private val NAV_ITEMS_START_GAP = 20.dp     // L259 navRecycler layout_marginStart
private val TOP_BAR_TEXT_BOX = 24.dp        // 文字项固定内容区（16sp 的贴合高度）
private val TOP_BAR_UNDERLINE_WIDTH = 26.dp // 焦点状态红色短横线长度
private val TOP_BAR_UNDERLINE_HEIGHT = 2.dp // 焦点状态红色短横线高度
private val TOP_BAR_UNDERLINE_GAP = 6.dp    // 文字与短横线之间的间距
// 上方预留 = 间距 + 横线高度，使 24dp 文字区的中心与 64dp navBar 中心重合（与小房子同心）。
private val TOP_BAR_UNDERLINE_RESERVE = TOP_BAR_UNDERLINE_GAP + TOP_BAR_UNDERLINE_HEIGHT
private const val TOP_BAR_SP = 16f          // 第一排字号（比第二排 Tab 的 13sp 大一档，形成层级）
private const val TOP_BAR_FOCUS_SCALE = 1.1f // 焦点态整体放大 1.1 倍（只走 graphicsLayer，不动布局）
private const val TOP_BAR_ANIM_MS = 150
private const val TOP_BAR_IDLE_ALPHA = 0.6f
private val TOP_BAR_ITEM_SPACING = 40.dp    // navRecycler 项间距（放大字号后仍保持宽松，不挤在一起）

// ---------------------------------------------------------------------------
// 左侧 Hero 信息区 heroInfo（L61-127）
// ---------------------------------------------------------------------------
private val HERO_INFO_BOTTOM_GAP = 22.dp    // L68 layout_marginBottom
private val HERO_TITLE_HEIGHT = 36.dp       // L79 layout_height
private const val HERO_TITLE_SP = 32f       // L72 textSize
private val HERO_META_TOP_GAP = 2.dp        // L100 layout_marginTop
private val HERO_META_HEIGHT = 20.dp        // L99 layout_height
private const val HERO_META_SP = 14f        // L93 textSize
private val HERO_DESC_TOP_GAP = 4.dp        // L109 layout_marginTop
private val HERO_DESC_HEIGHT = 40.dp        // L118 layout_height
private const val HERO_DESC_SP = 12f        // L112 textSize
private const val HERO_DESC_WIDTH_FRACTION = 0.5f // L110 weightSum=2 + L124 weight=1
private const val HERO_BACKDROP_FADE_MS = 300
private const val HERO_INFO_FADE_MS = 320

// 简介 lineSpacingExtra=3dp（L125）。Android 的 12sp 默认行高 ≈ 14sp，加 3dp → 17sp。
private const val HERO_DESC_LINE_HEIGHT_SP = 17f

// XML 阴影参数是 px 值，Compose 的 Shadow 同样按 px 解释，因此直接照抄。
private val HERO_TITLE_SHADOW = Shadow(
    color = Color(0xCC000000),
    offset = Offset(2f, 2f),
    blurRadius = 12f,
)
private val HERO_META_SHADOW = Shadow(
    color = Color(0xAA000000),
    offset = Offset(1.5f, 1.5f),
    blurRadius = 8f,
)
private val HERO_DESC_SHADOW = Shadow(
    color = Color(0xAA000000),
    offset = Offset(1.2f, 1.2f),
    blurRadius = 6f,
)

private val HERO_META_COLOR = Color(0xCCFFFFFF)  // L94
private val HERO_DESC_COLOR = Color(0xBBFFFFFF)  // L113

// ---------------------------------------------------------------------------
// 分类 Tab 栏 typeRecycler（L128-141）
// ---------------------------------------------------------------------------
private val HERO_TAB_BOTTOM_GAP = 12.dp     // L137 layout_marginBottom
private val HERO_TAB_BOTTOM_PADDING = 2.dp  // L130 paddingBottom
private val HERO_TAB_SPACING = 16.dp        // Tab 之间间距
private val HERO_TAB_HEIGHT = 26.dp         // 胶囊高度，严格 26dp
private val HERO_TAB_H_PADDING = 12.dp      // 左右 12dp
private val HERO_TAB_V_PADDING = 0.dp       // 上下 0dp（高度由 HERO_TAB_HEIGHT 锁死）
private val HERO_TAB_SHAPE = RoundedCornerShape(13.dp) // 13dp = 高度的一半，完美半圆胶囊
// 未选中且未聚焦的 Tab 只用文字区分：背景恒为 Color.Transparent（直接在 HeroTabChip 里写死，
// 不再保留背景色常量，避免以后被误用成灰色），文字用 60% 白。
private val HERO_TAB_IDLE_TEXT = Color.White.copy(alpha = 0.6f)
// 规则 ②：失焦但仍选中时，改成「透明底 + 1.5dp 红框」，框宽与搜索页胶囊的选中框保持一致。
private val HERO_TAB_RING_STROKE = 1.5.dp

// ---------------------------------------------------------------------------
// 海报区 posterArea（L142-191）+ 卡片 adapter_vod_rect（L24-27、L45-57）
// ---------------------------------------------------------------------------
private val POSTER_AREA_HEIGHT = 215.dp     // L147 / L156
private val POSTER_TITLE_GAP = 8.dp         // adapter_vod_rect L53 layout_marginTop
private const val POSTER_TITLE_SP = 13f     // adapter_vod_rect L46 textSize
private val POSTER_TITLE_COLOR = Color(0xCCFFFFFF) // adapter_vod_rect L47

// 卡片尺寸严格照抄 adapter_vod_rect.xml（L24-25），不再按屏幕反推。
private val POSTER_CARD_WIDTH = 120.dp
private val POSTER_CARD_HEIGHT = 180.dp
private val POSTER_GAP = 12.dp

// 焦点进入第 5 张（index = 4）起，被选中的卡靠左吸附到 24dp 基准线；index 0..3 一律停在行首。
private const val POSTER_SCROLL_ANCHOR = 4

// 参考 ref_docs/VodRectPresenter.java 第 39-41 行：原实现 hasFocus() ? 1.08f : 1.0f；本项目按定稿取 1.1。
private const val POSTER_FOCUS_SCALE = 1.1f

// 焦点缩放的弹簧参数：dampingRatio 0.8（略有回弹但不过冲）+ stiffness 400（电视遥控器连按时跟手）。
private const val POSTER_SPRING_DAMPING = 0.8f
private const val POSTER_SPRING_STIFFNESS = 400f

private val POSTER_FOCUS_ELEVATION = 18.dp

// 参考 VodRectPresenter.java 第 90-101 行：overlay 线宽 density*1dp、四边各外扩 1dp，视觉厚度约 2dp。
private val POSTER_FOCUS_BORDER = 2.dp

// styles.xml:12-14 —— `Vod.Grid.Large` 的 cornerSize 精确值就是 8dp（父样式 `Vod.Grid` 同为 8dp 圆角）。
private val POSTER_SHAPE = RoundedCornerShape(8.dp)
private val POSTER_PLACEHOLDER = Color(0xFF161616)

// 长按阈值：与历史页卡片的长按计时（500ms）保持一致，超过就当成「长按确定键」。
private const val POSTER_LONG_PRESS_MS = 500L

// 方向下键的长按阈值：按住 1.5s 才呼出 TMDB 手动匹配弹窗，短按仍是原来的下移。
private const val TMDB_LONG_PRESS_MS = 1500L

// ---------------------------------------------------------------------------
// 全局主题色统一在 HeroCommonUi 定义：
// - HeroAccentColor（#FF0000）：顶栏下划线、海报焦点边框、Tab 选中红框；
// - HeroFocusBrush（#E50914 → #F44336 → 玫瑰粉）：所有**实心红块**（分类 Tab 焦点态）。
// 本文件不再重复声明颜色常量。
// ---------------------------------------------------------------------------

// 第二排（Tab 胶囊）专用字号：第一排已独立到 [TOP_BAR_SP] = 16sp，形成视觉层级。
private const val HERO_LABEL_SP = 13f
