package com.fongmi.android.tv.ui.activity

import android.content.Context
import android.content.Intent
import android.app.Activity
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import com.fongmi.android.tv.App
import com.fongmi.android.tv.R
import com.fongmi.android.tv.api.config.VodConfig
import com.fongmi.android.tv.bean.Result
import com.fongmi.android.tv.bean.Flag
import com.fongmi.android.tv.bean.Site
import com.fongmi.android.tv.bean.Vod
import com.fongmi.android.tv.bean.Word
import com.fongmi.android.tv.model.SearchProgress
import com.fongmi.android.tv.model.SiteViewModel
import com.fongmi.android.tv.setting.Setting
import com.fongmi.android.tv.setting.SiteBlockSetting
import com.fongmi.android.tv.ui.hero.ACCENT_RED
import com.fongmi.android.tv.ui.hero.FIRST_KEY_INDEX
import com.fongmi.android.tv.ui.hero.HERO_KEYS
import com.fongmi.android.tv.ui.hero.HOUSE_KEY_INDEX
import com.fongmi.android.tv.ui.hero.HeroFocusBrush
import com.fongmi.android.tv.ui.hero.HeroImageLoader
import com.fongmi.android.tv.ui.hero.HeroKey
import com.fongmi.android.tv.ui.hero.HeroKeyAction
import com.fongmi.android.tv.ui.hero.HeroPosterCard
import com.fongmi.android.tv.ui.hero.HeroPosterViewRegistry
import com.fongmi.android.tv.ui.hero.HeroSourceEntry
import com.fongmi.android.tv.ui.hero.INPUT_HEIGHT
import com.fongmi.android.tv.ui.hero.KEY_COLUMNS
import com.fongmi.android.tv.ui.hero.KEY_ROWS
import com.fongmi.android.tv.ui.hero.KEY_SIZE
import com.fongmi.android.tv.ui.hero.KEY_SPACING
import com.fongmi.android.tv.ui.hero.KEYWORD_MAX_LENGTH
import com.fongmi.android.tv.ui.hero.KeyboardSection
import com.fongmi.android.tv.ui.hero.PLACEHOLDER_INDEX
import com.fongmi.android.tv.ui.hero.SourceSidebar
import com.fongmi.android.tv.ui.hero.SourceSelectionStore
import com.fongmi.android.tv.ui.hero.SourceSidebarMode
import com.fongmi.android.tv.ui.hero.requestFocus
import com.fongmi.android.tv.utils.Notify
import com.fongmi.android.tv.utils.SearchSuggest
import com.github.catvod.net.OkHttp
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * HeroUI（Compose）搜索页。
 *
 * 版式基准 = 原版截图 + `ref_docs/activity_search.xml`：**上下弹性布局，一屏直排**。
 * ```
 * BoxWithConstraints(fillMaxSize)                                   // 只用来量一次视口高度
 * └─ Column(fillMaxSize + padding(top=24dp))                        // 唯一布局根，无横向 padding、无整体滚动
 *   ├─ Row(fillMaxWidth + weight(1f))                                // L12-16：上半区
 *   │    ├─ KeyboardSection(start=24 + wrapContentWidth + 等比缩放)    // L17-65：输入行 40 + 8 + 键盘 280
 *   │    └─ RightContentColumn(weight(1f) + end=24 + verticalScroll) // L66-143：历史 + 热搜 / 建议
 *   └─ if (hasInput) ResultSection(fillMaxWidth + wrapContentHeight + padding(top=12dp))
 *        ├─ LazyRow(height=32dp, contentPadding(24, 24), 间距 8dp)     // L152-161：站点 Tab
 *        ├─ Spacer(8dp)
 *        └─ Box(fillMaxWidth + height=215dp)                          // L162-167：海报容器
 *             └─ LazyRow(fillMaxSize, contentPadding(24, 24),间距 12dp)
 *                  └─ AndroidView(HeroPosterCard 120×214dp，绝不加尺寸 Modifier)
 * ```
 *
 * 三条硬约束：
 * 1. **根节点没有横向 padding**。24dp 左边距由键盘前面的一个 `Spacer(24dp)` 让出
 *    （且必须在缩放子树之外，缩放后才对得齐），右列自己 `padding(end=24dp)`，
 *    两条 `LazyRow` 用 `contentPadding(24, 24)`。
 *    Compose 的 LazyRow 按自身外框裁剪，只有它的滚动区够宽，第一项「全部」在焦点放大
 *    1.08 时才不会被切边 —— 这正是原版 `clipChildren=false + clipToPadding=false` 的等价写法。
 * 2. **上下半区彻底分离**：上半区 `weight(1f)`、下半区 `wrapContentHeight()`（绝不给 weight），
 *    两者顺序排列，物理上不可能互相覆盖。左列**只按内容自适应**（`wrapContentWidth`、无
 *    `fillMaxHeight`、无滚动），右列内容超长时才在自身 `verticalScroll` 里滚。
 * 3. 海报区高度由**外层 `Box(height=215dp)`** 决定（原版 FrameLayout 就是这么写的），
 *    卡片自身 `onMeasure` 是 120×214dp，215 = 214 + 1dp 余量；`AndroidView` 里不加任何尺寸 Modifier。
 *
 * 键盘尺寸恒定：缩放系数按「结果区存在」这一最坏情形一次性算出
 * （`(视口高 - 24 - 267) / 328`），**与 `hasInput` 无关**，所以清空输入不会让键盘突然变大。
 *
 * 键盘第一行与 `ref_docs/xd1.java` L27 一致：`(占位, <, >, ⌫, 键盘, 房子, A)`，之后 B-Z、0-9，
 * 共 42 格 = 6 行 × 7 列，最右列正是 A / H / O / V / 2 / 9（与 `topbar_search_page.png` 一致）。
 *
 * 焦点契约：
 * - 输入框 ↓ / 放大镜 ↓ → 键盘第一行；键盘内四向移动由 [FocusRequester] 精确控制；
 * - **键盘最右列按 → 不消费、返回 false**，交给 Compose 原生 2D 焦点搜索，
 *   按几何位置自动落到右侧「同一排」的第一个胶囊；
 * - **键盘最后一行按 ↓** → 有结果区时按所在列落到站点 Tab，无结果区时交还原生搜索；
 * - **胶囊按 ← / ↓ 都不消费**，交原生 2D 搜索：← 回键盘最右列；
 *   ↓ 先落同一区块的下一行（如「建议」第二排），再落下一个区块 / 站点 Tab；
 *   Tab ↓ → 首张海报；海报 ←→ 行内移动、↑ → 站点 Tab；确定键 → [HeroDetailActivity.start]。
 *
 * 外观 / 焦点态（严格按《焦点总结》的三态）：
 * - 背景 = 首页大图极小尺寸解码后放大（等价高斯模糊）+ 半透明黑遮罩，同原版
 *   `SearchActivityV2` 的 `heroBitmap` 模糊 + `0x33000000` 蒙层；
 * - **聚焦**（胶囊 / 站点 Tab）→ 实心红渐变色块 #E50914 → #F44336 → 玫瑰粉 + 纯白字，无描边；
 * - **失焦但仍选中、且焦点还在结果区** → 站点 Tab 挂红框描边 + 弱底，提示下方属于哪个站点；
 * - **焦点离开结果区**（回键盘 / 顶栏）→ 无红底无红框，选中态暂时休眠。
 */
class HeroSearchActivity : ComponentActivity() {

    /**
     * 与首页 / 历史页同一个 ViewModel 类（各自持有自己的实例：`ViewModelProvider(this)` 是
     * Activity 作用域，页面销毁即回收，不会把搜索结果串到别的页面）。
     *
     * 搜索写的是 `search` 这条 LiveData，首页写的是 `result`，两条互不干扰。
     */
    private val siteViewModel: SiteViewModel by lazy {
        ViewModelProvider(this)[SiteViewModel::class.java]
    }

    /**
     * 【关键】与全站其它页面共用同一套「设计画布」。
     *
     * `ui/base/BaseActivity` 用 AutoSize 把屏幕固定成 960×540dp，再按「界面缩放」设置
     * 换算（`designW = 960 / factor`、`designH = 540 / factor`）；换算到 density 上，
     * 等价于 `density = 系统 density × factor`，正是 [Setting.wrapUiScale] 做的事。
     *
     * 本页是 Compose 的 [ComponentActivity]，不走 BaseActivity。以前没有套用这一步，
     * 于是用原始 density 渲染：用户把界面缩放调成 0.85 时，其它页面按 635dp 高的画布排版，
     * 本页只有 540dp —— 固定内容（24 + 键盘 328 + 结果区 267 = 619dp）装不下，
     * 键盘就被压到结果区下面，同时整体比其它页面大 1/0.85 ≈ 1.18 倍，看起来又粗又大。
     *
     * 套用之后：density 与全站一致 → dp 视口变回 635dp → 键盘完整显示、字号与其它页相同。
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Setting.wrapDisplay(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 【仅用于定位崩溃】先装全局兜底日志：任何线程的未捕获异常都会在
        // `AndroidRuntime: FATAL EXCEPTION` 之前多打一条带完整堆栈的 `HeroSearch` 日志，
        // `adb logcat | grep -E "AndroidRuntime|HeroSearch"` 就能直接看到崩溃行号。
        installCrashLogger()
        window.setBackgroundDrawable(ColorDrawable(AndroidColor.BLACK))
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        HeroImageLoader.install()
        // 首页带过来的那张大图：本页把它当磨砂底图（原版就是把首页 heroBitmap 模糊后当背景）。
        val backdropUrl = intent?.getStringExtra(EXTRA_BACKDROP)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                HeroSearchScreen(
                    backdropUrl = backdropUrl,
                    siteViewModel = siteViewModel,
                )
            }
        }
    }

    companion object {

        private const val EXTRA_BACKDROP = "hero_backdrop"

        // 全局 handler 只能装一次：Activity 每次重建都装会不断套娃，堆栈也会被反复包一层。
        @Volatile
        private var crashLoggerInstalled = false

        /**
         * 【仅用于定位崩溃】把未捕获异常原样记录到 logcat，再交回系统默认处理。
         *
         * 这里**不吞异常**：该崩还是崩，但 `AndroidRuntime` 之前一定先有一条
         * `HeroSearch: 全局捕获到崩溃` 的完整堆栈，能直接看到崩溃行号与线程名。
         * 确认崩溃修完后可以整段删掉，不影响任何业务逻辑。
         */
        private fun installCrashLogger() {
            if (crashLoggerInstalled) return
            crashLoggerInstalled = true
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                Log.e(
                    TAG_SEARCH,
                    "全局捕获到崩溃: thread=${thread.name} message=${throwable.message}",
                    throwable,
                )
                // 交回系统默认处理：保持原有「崩了就崩」的行为，绝不留下半死进程。
                previous?.uncaughtException(thread, throwable)
            }
        }

        /** 统一入口：首页顶栏「搜索」调用，并把首页当前那张大图一起带过来做磨砂背景。 */
        fun start(context: Context, backdropUrl: String? = null) {
            context.startActivity(
                Intent(context, HeroSearchActivity::class.java).putExtra(EXTRA_BACKDROP, backdropUrl),
            )
        }
    }
}

@Composable
private fun HeroSearchScreen(
    backdropUrl: String?,
    siteViewModel: SiteViewModel,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 输入内容用 TextFieldValue 维护：readOnly 只吃自己的虚拟键盘，光标由键盘 < / > 驱动。
    var field by remember { mutableStateOf(TextFieldValue("")) }
    // 历史：启动时从 Prefers("keyword") 读 JSON（同 `xp.java` 的 a=7 分支），改动立刻写回。
    var history by remember { mutableStateOf(readHistory()) }
    var selectedSite by remember { mutableIntStateOf(0) }
    var focusedSite by remember { mutableIntStateOf(0) }
    // 结果区是否持有焦点：输入被清空导致结果区整体消失时，用它把焦点安全送回输入框。
    var resultsFocused by remember { mutableStateOf(false) }

    // ---- 真实搜索状态 ----
    // 累积结果：`searchContent(List<Site>, ...)` 是**每个站点各回一条 Result**，
    // 不是一次性返回全集，所以这里必须累加，不能整体覆盖。
    var searchResults by remember { mutableStateOf<List<Vod>>(emptyList()) }
    // 本次搜索真正生效的关键词：用户改了输入但还没按搜索时，下面的旧结果不能冒充新结果。
    var searchedKeyword by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var searchProgress by remember { mutableStateOf<SearchProgress?>(null) }
    // 搜索请求序号：点热词 / 点历史 / 放大镜 / 键盘搜索键都只改这个序号，
    // 真正的请求统一在 `LaunchedEffect(searchSerial)` 里发 —— 保证「输入框已更新、结果区已重组」之后才发请求，
    // 快速连点时也只有最后一次生效。
    var searchSerial by remember { mutableIntStateOf(0) }
    var searchKeyword by remember { mutableStateOf("") }

    // ---- 搜索页内嵌下钻（folder 条目）----
    // folder 条目（网盘聚合壳，如 `atvp_detail:`）的正确动作是拿它的 id 走**分类接口** `t=` 展开目录，
    // 而不是拿它去请求 `ids=` 详情（站源会判成业务错误，返回空 id / 0 线路）。
    // 原版是在点击处跳到分类页；这里改成**留在搜索页**做二级列表：
    // 压栈 → `categoryContent(...)` → 用分类结果替换下方海报行 → 返回键 / 「返回上一级」出栈。
    var drillStack by remember { mutableStateOf<List<DrillEntry>>(emptyList()) }
    var drillResults by remember { mutableStateOf<List<Vod>>(emptyList()) }
    var drillLoading by remember { mutableStateOf(false) }
    val drillActive = drillStack.isNotEmpty()

    // ---- 点卡片直达选集（不再先跳详情页）----
    // 搜索 / 下钻结果里，`vod.flags` 非空 = 已经带着真实线路 → 直接弹选集；
    // 没有 flags 才去请求一次 `detailContent`，结果回来后同样直接弹选集。
    var showPlayDialog by remember { mutableStateOf(false) }
    var playFlags by remember { mutableStateOf<List<Flag>>(emptyList()) }
    var playVod by remember { mutableStateOf<Vod?>(null) }
    var playSite by remember { mutableStateOf<Site?>(null) }
    // 非空 = 正在等这条 id 的详情结果；用来区分「选集请求」和「下钻请求」。
    var pendingPlayId by remember { mutableStateOf<String?>(null) }

    // ---- 真实热搜 / 建议 ----
    // 热搜：先用本地缓存（Prefers("hot")）秒出，再异步刷一次网络（同 `X1()` 的两步）。
    var hotWords by remember { mutableStateOf(parseWords(Setting.getHot())) }
    var hotLoaded by remember { mutableStateOf(false) }
    // 建议：输入变化时向爱奇艺要，空结果**不清空**上一次的词（原版容错一致）。
    var suggestWords by remember { mutableStateOf<List<String>>(emptyList()) }

    val keyword = field.text
    val hasInput = keyword.trim().isNotEmpty()
    // 三态：无输入 → 「历史 + 热搜」；有输入 → 「历史 + 建议」。
    val wordTitle = if (hasInput) TITLE_SUGGEST else TITLE_HOT
    val words: List<String> = if (hasInput) suggestWords else hotWords

    // 结果区只有「当前输入 == 本次搜索用的词」时才显示，避免改了关键词还挂着上一次的结果。
    val resultsMatchInput = keyword.trim() == searchedKeyword

    // 站点 Tab 完全由真实结果推导：`全部` + 返回过内容的站点（顺序 = 结果到达顺序），
    // 与 `SearchActivityV2.c2()` 里 `this.R.a(site)` 的时机一致。
    // 关键词改过但还没重新搜索时（resultsMatchInput=false）结果区是空的，Tab 也一并收回到「全部」。
    val resultSites: List<Site> = remember(searchResults, resultsMatchInput) {
        if (!resultsMatchInput) emptyList() else searchResults.mapNotNull { it.site }.distinctBy { it.key }
    }
    val searchSiteLabels: List<String> = remember(resultSites) {
        listOf(TITLE_ALL) + resultSites.map { it.displayName }
    }
    val searchVisible: List<Vod> = remember(searchResults, resultSites, selectedSite, resultsMatchInput) {
        if (!resultsMatchInput) {
            emptyList()
        } else {
            val site = resultSites.getOrNull(selectedSite - 1)
            if (site == null) searchResults else searchResults.filter { it.site?.key == site.key }
        }
    }
    // 下钻层级存在时，下方海报行整块换成「当前目录的分类结果」，
    // 站点 Tab 行只剩一个「返回上一级」——不再按源分组（目录结果本来就只有这一个源）。
    val siteLabels: List<String> = if (drillActive) listOf(DRILL_BACK_LABEL) else searchSiteLabels
    val visibleResults: List<Vod> = if (drillActive) drillResults else searchVisible

    val inputRequester = remember { FocusRequester() }
    val searchRequester = remember { FocusRequester() }
    val keyRequesters = remember { List(HERO_KEYS.size) { FocusRequester() } }
    // 历史 / 热搜建议 / 站点 Tab 三组列表都是**运行时变长**的（热搜拉回来、结果逐站点到达），
    // 所以统一用一份定长 FocusRequester 池：列表内容变化时 requester 实例不变，
    // 不会出现「热搜加载完成 → 焦点从胶囊上掉下来」。
    val historyRequesters = remember { List(FOCUS_REQUESTER_POOL) { FocusRequester() } }
    val wordRequesters = remember { List(FOCUS_REQUESTER_POOL) { FocusRequester() } }
    val siteRequesters = remember { List(FOCUS_REQUESTER_POOL) { FocusRequester() } }
    // 「右侧第一排第一个胶囊」的固定落点：键盘最右列 → 这里；站点 Tab ↑ → 也回这里。
    //
    // 【为什么拆成两个 requester】历史区与热搜/建议区是**两个不同的 Composable**，
    // 且谁是「第一排」由 `history.isEmpty()` 二选一决定。点热搜时同一帧内会发生两件事：
    //   1) `recordHistory(text)` → history 从空变非空 → 「第一排」从热搜区跳到历史区；
    //   2) `hasInput` 变 true → words 从 hotWords 切成 suggestWords → 热搜区整体被销毁。
    // 如果两处共用一个 requester，`Modifier.focusRequester` 会在同一帧里先解绑旧节点、
    // 再绑定新节点；这中间任何一次 `requestFocus()` 都会撞上「未初始化 / 已失效」而抛异常。
    // 拆开之后两个 requester 各自只属于一个区块，跨区块交接只在下一帧发生，天然安全。
    val historyFirstRequester = remember { FocusRequester() }
    val wordFirstRequester = remember { FocusRequester() }
    // 海报卡是 Android View（HeroPosterCard），Compose 的 FocusRequester 摸不到它，
    // 用一张「下标 → View」的登记表跨区交接焦点，卡片自带的 2dp 红描边才会亮。
    val posterRegistry = remember { HeroPosterViewRegistry() }

    val siteState = rememberLazyListState()
    val posterState = rememberLazyListState()
    // 右列兜底滚动（对应原版 activity_search.xml L66-73 的 NestedScrollView）。
    // 只在内容超过可视高度时才会真正滚动；720dp 屏上默认静止。
    val rightScroll = rememberScrollState()

    // 焦点回到输入框/键盘 = 离开结果区，清掉标记，避免之后清空输入时把焦点从键盘抢走。
    fun focusInput() {
        resultsFocused = false
        requestFocus(inputRequester, TAG_SEARCH)
    }

    fun focusKeyboard(index: Int) {
        resultsFocused = false
        val target = index.coerceIn(FIRST_KEY_INDEX, HERO_KEYS.lastIndex)
        if (!requestFocus(keyRequesters, target, TAG_SEARCH)) {
            requestFocus(keyRequesters, FIRST_KEY_INDEX, TAG_SEARCH)
        }
    }

    /**
     * 站点 Tab ↑ / 「第一排第一个胶囊」的固定落点：右侧历史第一条（没有历史就是热搜第一条）。
     *
     * 【防崩溃】必须按**运行时**的 `history.isNotEmpty()` 二选一，而不是在组合期把
     * requester 传进已经消失的区块：点热搜会让 history 立刻非空、热搜区立刻销毁，
     * 这时能接收焦点的只有历史第一条。焦点请求本身也已 runCatching 兜底。
     */
    fun focusRightFirstChip() {
        val target = if (history.isNotEmpty()) historyFirstRequester else wordFirstRequester
        requestFocus(target, TAG_SEARCH)
    }

    /**
     * 落焦点到站点 Tab。
     *
     * 【防崩溃 + 防失效】结果区是 `hasInput` 之后才插入组合树的，且站点 Tab 数量会随
     * 真实结果动态增长。这里不再「等一帧就请求一次」，而是**逐帧重试**：
     * 目标 requester 还没挂载时 `requestFocus()` 返回 false，就继续等下一帧，
     * 直到成功或超出重试上限；任何一次失败都只记日志，绝不抛出。
     */
    fun focusSite(index: Int) {
        if (!hasInput) {
            focusInput()
            return
        }
        // 【防越界】站点 Tab 随真实结果动态增长：列表为空 / 下标越界都必须先收敛，
        // 否则 `coerceIn(0, -1)` 会直接抛 IllegalArgumentException。
        val count = siteLabels.size
        if (count <= 0) return
        val target = index.coerceIn(0, count - 1)
        focusedSite = target
        scope.launch {
            runCatching { siteState.animateScrollToItem(target) }
            var ok = false
            for (attempt in 0 until FOCUS_RETRY_FRAMES) {
                withFrameNanos { }
                if (requestFocus(siteRequesters, target, TAG_SEARCH)) {
                    ok = true
                    break
                }
            }
            if (!ok) Log.w(TAG_SEARCH, "站点 Tab 焦点未落位: index=$target count=$count")
        }
    }

    /**
     * 落焦点到海报行。
     *
     * [count] 必须由调用方显式传入**当前实际渲染的条目数**：下钻结果由 LiveData 回调异步写入，
     * 若在这里读组合期的 `visibleResults`，闭包里拿到的会是上一帧的旧列表（甚至是空的），
     * 于是「下钻后焦点不落到海报上」。
     */
    fun focusPoster(index: Int, count: Int) {
        // 【防越界 / 防崩溃】结果为空时 `coerceIn(0, -1)` 同样会抛异常；
        // 这里必须提前返回，焦点留在站点 Tab 上，绝不去请求一张不存在的卡。
        if (!hasInput || count <= 0) return
        val target = index.coerceIn(0, count - 1)
        scope.launch {
            runCatching {
                posterState.animateScrollToItem(if (target >= POSTER_SCROLL_ANCHOR) target else 0)
            }
            withFrameNanos { }
            if (!posterRegistry.requestFocus(target)) {
                withFrameNanos { }
                posterRegistry.requestFocus(target)
            }
        }
    }

    /**
     * 打开一个 folder 条目：压栈 + 用**分类接口**展开目录（`t=vod.id`）。
     *
     * 与原版 `CollectActivity.onItemClick` → `VodActivity.start(..., Result.folder(item))` 的语义一致，
     * 区别只是落点从「原版分类页」换成「本页的下方海报行」。
     * 请求写的是 `SiteViewModel.result`（详情同一条通道），搜索结果走的是 `search`，两者互不干扰。
     */
    fun openDrillDown(vod: Vod, site: Site) {
        drillStack = drillStack + DrillEntry(site, vod)
        drillResults = emptyList()
        drillLoading = true
        selectedSite = 0
        focusedSite = 0
        Log.e(
            TAG_SEARCH,
            "下钻 folder: site=${site.key} id=${vod.id} name=${vod.name} " +
                "depth=${drillStack.size} api=${site.api}",
        )
        siteViewModel.categoryContent(site.key, vod.id, "1", true, hashMapOf())
    }

    /**
     * 返回上一级：出栈。
     *
     * - 还有上级目录 → 重新展开上级（分类结果本来就会被下级覆盖，必须重新请求）；
     * - 已经到顶 → 清空目录结果，原搜索结果原样恢复（`searchResults` 从未被改动）。
     */
    fun exitDrillDown() {
        if (drillStack.isEmpty()) return
        drillStack = drillStack.dropLast(1)
        drillResults = emptyList()
        selectedSite = 0
        focusedSite = 0
        val parent = drillStack.lastOrNull()
        Log.e(TAG_SEARCH, "返回上一级: 剩余层级=${drillStack.size} parent=${parent?.vod?.id}")
        if (parent == null) {
            drillLoading = false
            scope.launch {
                withFrameNanos { }
                focusSite(0)
            }
        } else {
            drillLoading = true
            siteViewModel.categoryContent(parent.site.key, parent.vod.id, "1", true, hashMapOf())
        }
    }

    /**
     * 点卡片直达选集：**不再先跳详情页**。
     *
     * - `vod.flags` 非空（下钻 / 分类结果常常直接带线路）→ 立刻弹 [HeroPlayDialog]；
     * - 没有 flags → 先请求一次 `detailContent`，结果回来后由 `result` 观察者直接弹选集。
     */
    fun openPlayDialog(vod: Vod, site: Site) {
        if (vod.id.isBlank()) {
            Notify.show("缺少剧集 ID，无法选集")
            return
        }
        playVod = vod
        playSite = site
        val inline = vod.flags
        if (inline.isNotEmpty()) {
            playFlags = inline
            pendingPlayId = null
            showPlayDialog = true
            Log.e(
                TAG_SEARCH,
                "点卡片直达选集: id=${vod.id} site=${site.key} flags=${inline.size} " +
                    "集数=${inline.sumOf { it.episodes.orEmpty().size }}",
            )
        } else {
            playFlags = emptyList()
            pendingPlayId = vod.id
            showPlayDialog = false
            Log.e(TAG_SEARCH, "点卡片无线路，请求详情后直达选集: id=${vod.id} site=${site.key}")
            siteViewModel.detailContent(site.key, vod.id)
        }
    }

    fun setKeyword(text: String) {
        field = TextFieldValue(text = text, selection = TextRange(text.length))
    }

    /**
     * 记一条历史：**去重 → 提到最前 → 最多 9 条 → 立刻持久化**。
     *
     * 与 `xp.java` 的 `a(String)` 逐行对应：
     * ```java
     * list.remove(str); list.add(0, str);
     * if (list.size() > 9) list.remove(9);
     * vx0.S(App.l.h.toJson(list), "keyword");   // ← 本项目的等价物：Setting.putKeyword(...)
     * ```
     * 只改内存的话，退出页面就全丢了 —— 这是这次接入的重点之一。
     */
    fun recordHistory(text: String) {
        val value = text.trim()
        if (value.isEmpty()) return
        val next = (listOf(value) + history.filterNot { it == value }).take(HISTORY_LIMIT)
        if (next == history) return
        history = next
        Setting.putKeyword(App.gson().toJson(next))
    }

    /** 与 `CustomKeyboard.onTextClick` L31-36 一致：总长不超过 19，插入到光标处。 */
    fun insert(text: String) {
        val value = field
        if (value.text.length >= KEYWORD_MAX_LENGTH) return
        val start = minOf(value.selection.start, value.selection.end).coerceIn(0, value.text.length)
        val end = maxOf(value.selection.start, value.selection.end).coerceIn(0, value.text.length)
        val merged = value.text.replaceRange(start, end, text)
        if (merged.length > KEYWORD_MAX_LENGTH) return
        field = TextFieldValue(text = merged, selection = TextRange(start + text.length))
    }

    fun backspace() {
        val value = field
        val start = minOf(value.selection.start, value.selection.end).coerceIn(0, value.text.length)
        val end = maxOf(value.selection.start, value.selection.end).coerceIn(0, value.text.length)
        if (start != end) {
            field = TextFieldValue(value.text.removeRange(start, end), TextRange(start))
            return
        }
        if (start == 0) return
        field = TextFieldValue(value.text.removeRange(start - 1, start), TextRange(start - 1))
    }

    fun moveCaret(delta: Int) {
        val caret = (field.selection.start + delta).coerceIn(0, field.text.length)
        field = field.copy(selection = TextRange(caret))
    }

    /**
     * 统一的「请求搜索」入口：**只更新状态**，真正的请求由 `LaunchedEffect(searchSerial)` 发。
     *
     * 为什么不在点击回调里直接请求：
     * 1. 点热词 / 历史时 `keyword` 还是**旧值**，直接调 `confirmSearch()` 会搜错词；
     * 2. 结果区是靠 `hasInput` 从 false → true 才插入的，同一帧请求焦点会落到还没组合出来的节点上；
     * 3. 快速连点多个胶囊时，序号一变 effect 就重启，前一轮不会再写结果。
     *
     * 真正的「点热词闪退」根因见下方协程里的 ①：胶囊销毁 → 焦点被系统恢复给输入框 →
     * CoreTextField 用「旧布局 + 新文本」算光标位置而越界（`offset(3) is out of bounds [0, 0)`）。
     */
    fun requestSearch(text: String = keyword) {
        val value = text.trim()
        if (value.isEmpty()) {
            focusInput()
            return
        }
        // 【防崩溃 · 异步化】绝不能在按键 / 点击回调里同步改状态。
        // 点热搜会在同一帧内改三处状态：`field`（hasInput false→true）、`history`、`searchKeyword`，
        // 热搜区因此被销毁；而此刻遥控器按键事件仍在分发栈里，后续 KeyUp / 事件消费
        // 一旦碰到已销毁的节点，就是在原生层崩，runCatching 完全兜不住。
        // 先让本次事件彻底走完、UI 稳定下来，再改状态并交给 `LaunchedEffect(searchSerial)` 发请求。
        scope.launch {
            delay(SEARCH_CLICK_SETTLE_MS)
            // ① 【真凶修复】先把焦点收回输入框，**然后**才改文本。
            //    崩溃栈：IllegalArgumentException: offset(3) is out of bounds [0, 0)
            //      at AndroidParagraph.getBoundingBox
            //      at CoreTextFieldKt.bringSelectionEndIntoView
            //      at invokeSuspend(...)
            //    原因：胶囊被销毁后系统把焦点“恢复”到输入框，而 CoreTextField 在**聚焦回调**里
            //    用「上一次布局结果」+「当前 value」算光标位置。若同一帧既换文本又聚焦，
            //    就是「新 value 4 个字 + 旧 layout 0 个字」→ getBoundingBox(3) 直接越界。
            //    这里在改文本之前先聚焦：此刻 value 与 layout 仍是旧的一致状态，聚焦协程
            //    捕获到的是一对匹配数据，后面再换文本就不会再触发该路径。
            focusInput()
            // ② 等一帧布局，确保聚焦已真正生效，再动文本。
            withFrameNanos { }
            // ③ 点胶囊时 keyword 还是旧值，必须先把输入框同步成新词。
            if (field.text != value) setKeyword(value)
            recordHistory(value)
            searchKeyword = value
            searchSerial++
        }
    }

    /** 顶部放大镜 / 键盘搜索键：走同一个入口（兼容原有调用点）。 */
    fun confirmSearch() = requestSearch()

    /** 侧边栏勾选（持久化）：搜索页、首页、换源弹窗共用同一份，避免各页各选一套。 */
    fun readCheckedFlags(sites: List<Site>): BooleanArray =
        SourceSelectionStore.checkedFlags(context, sites)

    /**
     * 小房子键 → 全局接口源侧边栏（复用 [SourceSidebar]，绝不自造 Dialog）。
     *
     * 按需求用 [SourceSidebarMode.Multi]：带勾选圈与「全选 / 全不选」，
     * 确定键 = 切换勾选（不关闭），返回键关闭后由 `onClosed` 把焦点交还房子键。
     */
    fun openSourceSidebar() {
        // 真实接口源：隐藏源与用户屏蔽源由 SiteBlockSetting 过滤（与首页小房子、换源弹窗同一套）。
        val sites = SiteBlockSetting.filter(VodConfig.get().getSites(), false)
        // 勾选变化才需要重搜：用户在侧边栏里连点几次，只发最后一轮请求。
        var selectionChanged = false
        SourceSidebar.show(
            context = context,
            entries = HeroSourceEntry.of(sites),
            mode = SourceSidebarMode.Multi,
            // 与首页 / 换源弹窗共用同一份持久化勾选：打开时先还原，不再默认全选。
            initialChecked = readCheckedFlags(sites),
            onCheckedChanged = { flags ->
                // 落盘：下次搜索 / 换源 / 预搜索都只请求勾选了的源。
                SourceSelectionStore.save(context, sites, flags)
                selectionChanged = true
            },
            onSiteSelected = { },
            onClosed = {
                // 勾选变了且已经搜过：按新勾选立刻重搜一轮（没勾的源不会再发请求）。
                if (selectionChanged && searchedKeyword.isNotBlank()) {
                    selectionChanged = false
                    searchSerial++
                }
                // Dialog 关闭后当前窗口尚未拿到焦点，延时一帧再 requestFocus 才不会被丢。
                scope.launch {
                    withFrameNanos { }
                    if (!requestFocus(keyRequesters, HOUSE_KEY_INDEX)) {
                        withFrameNanos { }
                        requestFocus(keyRequesters, HOUSE_KEY_INDEX)
                    }
                }
            },
        )
    }

    fun onKeyClick(key: HeroKey) {
        when (key) {
            is HeroKey.Text -> insert(key.value)

            is HeroKey.Action -> when (key.action) {
                HeroKeyAction.CursorLeft -> moveCaret(-1)
                HeroKeyAction.CursorRight -> moveCaret(1)
                HeroKeyAction.Backspace -> backspace()
                // 原版这个位置是「键盘布局切换键」；本页没有符号键盘页，改为清空输入。
                HeroKeyAction.Keyboard -> field = TextFieldValue("")
                HeroKeyAction.House -> openSourceSidebar()
            }

            // 占位格不聚焦，由 HeroKeyPlaceholder 单独渲染，走不到这里。
            else -> Unit
        }
    }

    fun onKeyMove(index: Int, key: Key): Boolean {
        val row = index / KEY_COLUMNS
        val column = index % KEY_COLUMNS
        return when (key) {
            Key.DirectionLeft -> {
                // 最左列（含第一行占位格右侧的 `<`）不再左移，就地消费。
                if (column > 0 && index - 1 > PLACEHOLDER_INDEX) requestFocus(keyRequesters, index - 1)
                true
            }

            Key.DirectionRight -> {
                // 最右列 = A / H / O / V / 2 / 9：**不拦截，返回 false 放行**。
                // Compose 原生 2D 焦点搜索会按几何位置自动选中右侧「对应排」的第一个胶囊，
                // 比手写「永远跳到第一排第一个」精确得多。
                if (column == KEY_COLUMNS - 1) {
                    false
                } else {
                    requestFocus(keyRequesters, index + 1)
                    true
                }
            }

            Key.DirectionUp -> {
                if (row > 0) requestFocus(keyRequesters, index - KEY_COLUMNS) else focusInput()
                true
            }

            Key.DirectionDown -> {
                when {
                    row < KEY_ROWS - 1 -> {
                        requestFocus(keyRequesters, index + KEY_COLUMNS)
                        true
                    }
                    // 最后一行（3–9）继续下按：有结果区就按「所在列」落到站点 Tab
                    // （Tab 数量不足时由 focusSite 内部收敛到最后一项），没有结果区就放行给原生 2D 搜索。
                    hasInput -> {
                        focusSite(column)
                        true
                    }

                    else -> false
                }
            }

            else -> false
        }
    }

    // 进入页面默认焦点在输入框（原版 SearchActivity.onResume L612 keyword.requestFocus()）。
    LaunchedEffect(Unit) {
        withFrameNanos { }
        requestFocus(inputRequester)
    }

    // 输入被清空 → 结果区整块消失，此时把焦点从已销毁的结果区送回输入框。
    LaunchedEffect(hasInput) {
        if (!hasInput && resultsFocused) {
            resultsFocused = false
            withFrameNanos { }
            focusInput()
        }
    }

    // 结果集缩小（新一轮搜索返回的站点变少）时把「选中站点」收回有效范围：
    // 焦点即选中之后 selectedSite 跟着焦点走，越界会让下方海报与 Tab 高亮对不上。
    LaunchedEffect(siteLabels.size) {
        if (selectedSite > siteLabels.lastIndex) selectedSite = siteLabels.lastIndex.coerceAtLeast(0)
    }

    // ------------------------------------------------------------------
    // 真实数据 1：搜索结果（`SiteViewModel.search`）
    // ------------------------------------------------------------------
    // `SiteViewModel.searchContent(List<Site>, ...)` 的语义是「每个站点回一条 Result」，
    // 所以这里必须**累加**：覆盖写只会剩下最后完成的那个源，正是「只出一个站点的结果」的经典成因。
    //
    // 用 DisposableEffect 而不是 observeAsState：本项目没引 `runtime-livedata`；
    // 用 DisposableEffect 又能保证页面销毁时一定 removeObserver（LaunchedEffect + awaitDispose 等价）。
    DisposableEffect(siteViewModel) {
        // `SiteViewModel.init()` 会把 search 置 null，LiveData 连 null 一起派发，必须按可空接收。
        val observer = Observer<Result?> { result ->
            if (result == null) return@Observer
            val incoming = result.list
            if (incoming.isEmpty()) return@Observer
            // 同一个站点重复回调（分页 / 重试）时按「站点 key + vod id」去重，绝不重复堆同一张卡。
            val merged = (searchResults + incoming).distinctBy { vod -> vod.site?.key to vod.id }
            searchResults = merged
            Log.d(TAG_SEARCH, "收到结果: site=${result.list.firstOrNull()?.site?.key} 本批=${incoming.size} 累计=${merged.size}")
        }
        siteViewModel.search.observeForever(observer)
        onDispose { siteViewModel.search.removeObserver(observer) }
    }

    // 进度：`current/total` 用来做「搜索中 x/y」，`finished` 一到就退出加载态。
    DisposableEffect(siteViewModel) {
        val observer = Observer<SearchProgress?> { progress ->
            searchProgress = progress
            // SearchProgress 是 Java record，Kotlin 里访问器是**方法**（finished()/current()/total()），
            // 不是 getXxx 属性，写成 `progress.finished` 编译不过。
            if (progress != null && progress.finished()) {
                searching = false
                Log.d(TAG_SEARCH, "搜索完成: ${progress.current()}/${progress.total()} 累计=${searchResults.size}")
            }
        }
        siteViewModel.searchProgress.observeForever(observer)
        onDispose { siteViewModel.searchProgress.removeObserver(observer) }
    }

    // ------------------------------------------------------------------
    // 真实数据 1.5：`SiteViewModel.result`（详情 / 分类共用这条通道）
    // ------------------------------------------------------------------
    // `SiteApi.categoryContent` / `detailContent` 写的是 `result` 通道（搜索写的是 `search`），
    // 两条互不干扰。这里按优先级分流：
    //   1. `pendingPlayId != null` → 这是「点卡片后补详情」，拿到线路直接弹选集；
    //   2. 否则且处于下钻层级 → 这是目录结果，替换下方海报行。
    DisposableEffect(siteViewModel) {
        val observer = Observer<Result?> { result ->
            if (result == null) return@Observer
            // ---- 优先级 1：点卡片后补详情 → 直达选集 ----
            if (pendingPlayId != null) {
                val incoming = result.vod
                val incomingFlags = incoming?.flags.orEmpty()
                Log.e(
                    TAG_SEARCH,
                    "详情返回(直达选集): id=${incoming?.id} flags=${incomingFlags.size} " +
                        "集数=${incomingFlags.sumOf { it.episodes.orEmpty().size }} " +
                        "code=${result.code} msg=${result.msg}",
                )
                pendingPlayId = null
                if (incomingFlags.isEmpty()) {
                    Notify.show("该剧暂无可用线路")
                } else {
                    // 详情结果比搜索结果完整：剧名 / 海报 / 线路都以它为准。
                    if (incoming != null && incoming.name.isNotBlank()) playVod = incoming
                    playFlags = incomingFlags
                    showPlayDialog = true
                }
                return@Observer
            }
            // ---- 优先级 2：folder 下钻的分类结果 ----
            if (drillStack.isEmpty()) return@Observer
            val current = drillStack.last().site
            // 分类接口不会给每条结果盖 site（原版整页共用一个 key），这里补上：
            // 卡片左上角的站点标签、点开后的选集弹窗都要靠它。
            drillResults = result.list.map { vod ->
                if (vod.site == null) vod.apply { site = current } else vod
            }
            drillLoading = false
            Log.e(
                TAG_SEARCH,
                "下钻结果: depth=${drillStack.size} id=${drillStack.last().vod.id} " +
                    "listSize=${result.list.size} code=${result.code} msg=${result.msg}",
            )
        }
        siteViewModel.result.observeForever(observer)
        onDispose { siteViewModel.result.removeObserver(observer) }
    }

    // 目录结果到达后把焦点落到第一张海报；空目录 / 失败则落到「⬅️ 返回上一级」。
    // 这里必须显式传 `drillResults.size`：`visibleResults` 是组合期局部变量，闭包里会读到旧值。
    LaunchedEffect(drillResults, drillStack.size) {
        if (drillStack.isEmpty()) return@LaunchedEffect
        withFrameNanos { }
        if (drillResults.isNotEmpty()) focusPoster(0, drillResults.size) else focusSite(0)
    }

    // 下钻层级存在时，返回键先退回上一级，而不是直接退出搜索页。
    BackHandler(enabled = drillActive) { exitDrillDown() }

    // ------------------------------------------------------------------
    // 统一触发：搜索请求（点热词 / 点历史 / 放大镜 / 键盘搜索键）
    // ------------------------------------------------------------------
    // **只有这里**会真正调用 `searchContent`：
    // - 时序安全：此时输入框与结果区已经重组完成，`focusSite(0)` 才有节点可落；
    // - 串行化：快速连点胶囊时序号一变 effect 就重启，前一轮不会再往结果区写数据。
    LaunchedEffect(searchSerial) {
        if (searchSerial == 0) return@LaunchedEffect
        val text = searchKeyword
        if (text.isEmpty()) return@LaunchedEffect
        try {
            // 1. 清空上一轮结果 + 进入加载态（原版 S0 里那一长串 clear）。
            searchResults = emptyList()
            // 新一轮搜索必须退出下钻：否则旧的目录结果会压在新搜索结果上。
            drillStack = emptyList()
            drillResults = emptyList()
            drillLoading = false
            searchedKeyword = text
            selectedSite = 0
            focusedSite = 0
            searching = true
            searchProgress = null
            // 2. 收集可搜索站点：与 `f2()` 完全一致，只过滤 isSearchable()。
            val searchable = VodConfig.get().getSites().filter { it.isSearchable() }
            // 侧边栏勾选（持久化）→ 允许发请求的源集合：没勾的源连请求都不发。
            val enabledSiteKeys = SourceSelectionStore.checkedSites(context, searchable)
                .map { it.key }
                .toSet()
            val sites = searchable.filter { it.key in enabledSiteKeys }
            Log.d(
                TAG_SEARCH,
                "开始搜索: keyword=$text 可搜索站点=${searchable.size}, " +
                    "勾选=${sites.size}, 跳过=${searchable.size - sites.size}",
            )
            if (sites.isEmpty()) {
                // 一个都没勾：不发请求，直接收掉加载态（与换源弹窗的空勾选行为一致）。
                Log.e(TAG_SEARCH, "搜索跳过: 侧边栏没有勾选任何源")
                searching = false
                return@LaunchedEffect
            }
            // 3. 发起搜索（quick = false，与原版一致）。
            siteViewModel.searchContent(sites, text, false)
            // 4. 结果区刚插入（hasInput false→true），等一帧再落焦点。
            //    `focusSite` 内部已做「逐帧重试 + runCatching + 越界收敛」，
            //    这里再包一层 try/catch 只是最后一道保险：焦点失败永远不能等于 App 崩溃。
            withFrameNanos { }
            focusSite(0)
        } catch (e: CancellationException) {
            // 用户又点了一次 / 页面退出：effect 被正常取消，不要当成错误。
            throw e
        } catch (e: Throwable) {
            // 【防崩溃】LaunchedEffect 体内未捕获的异常会直接打到 Recomposer，必闪退。
            Log.e(TAG_SEARCH, "搜索流程异常: $e", e)
            searching = false
        }
    }

    // ------------------------------------------------------------------
    // 真实数据 2：热搜（`SearchActivityV2.X1` 的空输入分支）
    // ------------------------------------------------------------------
    LaunchedEffect(keyword) {
        if (keyword.trim().isNotEmpty()) return@LaunchedEffect
        // 已经拿到过网络结果就不再重复请求（同一页面内来回清空输入不刷接口）。
        if (hotLoaded && hotWords.isNotEmpty()) return@LaunchedEffect
        // 【防崩溃】网络/解析异常必须就地吞掉：LaunchedEffect 里漏出去的异常会直接
        // 崩掉 Recomposer。热搜拿不到只是少几个词，绝不能影响页面可用性。
        val body = try {
            withContext(Dispatchers.IO) {
                OkHttp.string(HOT_URL, mapOf("Referer" to HOT_REFERER))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG_SEARCH, "热搜请求失败: $e", e)
            return@LaunchedEffect
        }
        // 【刷新保护】请求返回时用户可能已经开始输入 / 已经点了热词：热搜区不再展示，
        // 再写 `hotWords` 只会制造一次「点击瞬间的额外重组」，直接丢弃这次结果。
        if (keyword.trim().isNotEmpty()) return@LaunchedEffect
        val fresh = try {
            parseWords(body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG_SEARCH, "热搜解析失败: $e", e)
            return@LaunchedEffect
        }
        if (fresh.isEmpty()) {
            // 空结果 / 请求失败：保留缓存，绝不清空已有词。
            Log.w(TAG_SEARCH, "热搜为空，保留缓存 ${hotWords.size} 条")
            return@LaunchedEffect
        }
        hotWords = fresh
        hotLoaded = true
        // 与 `vx0.S(..., "hot")` 一致：把原始 JSON 存下来，下次进页面秒出。
        runCatching { Setting.putHot(body) }
        Log.d(TAG_SEARCH, "热搜更新: ${fresh.size} 条")
    }

    // ------------------------------------------------------------------
    // 真实数据 3：建议词（`X1` 的非空分支，爱奇艺联想接口）
    // ------------------------------------------------------------------
    LaunchedEffect(keyword) {
        val text = keyword.trim()
        if (text.isEmpty()) return@LaunchedEffect
        // 电视键盘是一格一格敲的，防抖 250ms，避免每按一个字母都打一次接口。
        delay(SUGGEST_DEBOUNCE_MS)
        val body = try {
            withContext(Dispatchers.IO) { OkHttp.string(SearchSuggest.iqiyiUrl(text)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG_SEARCH, "建议请求失败: $text $e", e)
            return@LaunchedEffect
        }
        val fresh = try {
            parseWords(body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG_SEARCH, "建议解析失败: $text $e", e)
            return@LaunchedEffect
        }
        if (fresh.isEmpty()) {
            // 原版容错：拿不到就不动列表，不要让「建议」整块消失。
            Log.w(TAG_SEARCH, "建议为空: $text")
            return@LaunchedEffect
        }
        suggestWords = fresh
        Log.d(TAG_SEARCH, "建议更新: $text -> ${fresh.size} 条")
    }

    // ------------------------------------------------------------------
    // 骨架 1：上下结构。根节点只有 Column：没有 Box 叠加、没有整体滚动。
    //
    // 外面这层 BoxWithConstraints 只干一件事：量出视口高度，算出键盘的**恒定**缩放系数。
    //   上半区可用高度 = 视口高 - 顶部 24dp - 结果区固定 267dp
    // 注意式子里没有 hasInput —— 有结果、没结果都按同一基准缩放，
    // 所以键盘尺寸恒定，不会再出现「清空输入键盘变大、一搜索键盘变小」的跳变。
    // ------------------------------------------------------------------
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val keyboardBaseDensity = LocalDensity.current
        // 是否让出下半区只影响「空出来多少空白」，不影响键盘大小：永远用最坏情形做基准。
        // 下界取 0 而不是「可读性下限」：宁可极小，也绝不允许键盘高度超过上半区而盖住海报。
        val keyboardBandHeight = maxHeight - ROOT_PADDING_TOP - RESULT_BAND_HEIGHT
        val keyboardScale = (keyboardBandHeight / KEYBOARD_NATURAL_HEIGHT).coerceIn(0f, 1f)

        // 背景层：首页大图 → 极小尺寸解码（等价高斯模糊）→ 半透明黑遮罩，
        // 原版 SearchActivityV2 的 U1() + setBackgroundColor(0x33000000) 就是这个结构。
        HeroSearchBackground(backdropUrl)

        Column(
            modifier = Modifier
                .fillMaxSize()
                // 【标准 1】外层不放 start/end padding：横向的 24dp 缓冲区交给各子节点自己处理
                // （左列前面一个 24dp Spacer、两条 LazyRow 用 contentPadding 补两端），
                // 这样 LazyRow 的滚动区就是整屏宽度，第一项（「全部」）左右都有 24dp 可裁缓冲。
                .padding(top = ROOT_PADDING_TOP),
        ) {
            // 上半区：weight(1f) 吃掉下半区固定高度之外的全部空间。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                // 左列 24dp 左边距：放在缩放子树**外面**，缩放时才有和两条 LazyRow 一致的 24dp。
                Spacer(modifier = Modifier.width(ROOT_PADDING_H))

                // 左列：按 keyboardScale 等比缩放。
                //   keyboardScale == 1f → 完全等于原版「键 40dp、间距 8dp」，一个像素都不动；
                //   视口不够高        → 整棵键盘子树按比例缩小，6 行 + 输入行永远完整，绝不压到下半区。
                // 走 LocalDensity 而不是 graphicsLayer：文字按新密度重新排版，不会发虚；
                // 只作用于键盘子树，右侧胶囊与海报卡（HeroPosterCard 按 Activity density 自绘
                // 120×214dp）完全不受影响。
                CompositionLocalProvider(
                    LocalDensity provides Density(
                        keyboardBaseDensity.density * keyboardScale,
                        keyboardBaseDensity.fontScale,
                    ),
                ) {
                    KeyboardSection(
                        field = field,
                        inputRequester = inputRequester,
                        searchRequester = searchRequester,
                        keyRequesters = keyRequesters,
                        onInputDown = { focusKeyboard(FIRST_KEY_INDEX) },
                        onSearch = { confirmSearch() },
                        onSearchDown = { focusKeyboard(FIRST_KEY_INDEX) },
                        onSearchLeft = { focusInput() },
                        onKeyClick = { key -> onKeyClick(key) },
                        onKeyMove = { index, key -> onKeyMove(index, key) },
                    )
                }

                Spacer(modifier = Modifier.width(LEFT_COLUMN_MARGIN_END))

                // 右列：历史 / 热搜（无输入）或 建议（有输入）。
                // 右端 24dp 对应 activity_search.xml L11 paddingEnd="24dp"：外层已放开 padding，
                // 这里自己补回来，否则最后一枚胶囊会贴死在屏幕右边缘。
                RightContentColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(end = ROOT_PADDING_H),
                    scroll = rightScroll,
                    history = history,
                    words = words,
                    wordTitle = wordTitle,
                    hasInput = hasInput,
                    historyRequesters = historyRequesters,
                    wordRequesters = wordRequesters,
                    historyFirstRequester = historyFirstRequester,
                    wordFirstRequester = wordFirstRequester,
                    onWordClick = { text ->
                        // 点热词 / 历史 = 直接搜：先更新输入框，再由 searchSerial 统一触发请求。
                        requestSearch(text)
                    },
                    // 胶囊 ↓ 到底时的兜底落点：**回到刚才高亮的站点 Tab**（《焦点总结》第 4 条），
                    // 而不是盲目落到第一个。
                    onDownToResults = { focusSite(focusedSite) },
                )
            }

            // 下半区：【标准 3】独立 Column，只按内容量高（12dp 间距 + 32dp Tab 行 + 8dp + 215dp 海报 Box），
            // 不吃 weight、不 fillMaxSize，所以永远挤压不到上半区键盘。
            if (hasInput) {
                ResultSection(
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight()
                        .padding(top = RESULT_SECTION_TOP_GAP),
                    siteState = siteState,
                    posterState = posterState,
                    siteLabels = siteLabels,
                    items = visibleResults,
                    selectedSite = selectedSite,
                    resultModuleFocused = resultsFocused,
                    searching = searching,
                    progress = searchProgress,
                    // 下钻时的空态文案必须说「目录」，不能沿用「输入关键词后按搜索键」。
                    emptyHint = if (drillActive) {
                        if (drillLoading) "正在加载目录…" else "该目录暂无内容"
                    } else {
                        null
                    },
                    siteRequesters = siteRequesters,
                    posterRegistry = posterRegistry,
                    onSiteFocused = { index ->
                        focusedSite = index
                        resultsFocused = true
                        // 【焦点即选中】不用按确定键：焦点移到哪个站点 Tab，下方海报立刻切到该站点。
                        selectedSite = index
                    },
                    onSiteConfirm = { index ->
                        if (drillActive) {
                            // 下钻时 Tab 行只有「⬅️ 返回上一级」这一项，确定键 = 出栈。
                            exitDrillDown()
                        } else {
                            // 兼容确定键：焦点即选中之后这里通常已经是同一个值，保留是为了不破坏原有按键习惯。
                            selectedSite = index
                            focusedSite = index
                        }
                    },
                    onSiteUp = { focusRightFirstChip() },
                    onSiteDown = { focusPoster(0, visibleResults.size) },
                    onSiteLeft = { index -> focusSite(index - 1) },
                    onSiteRight = { index -> focusSite(index + 1) },
                    onPosterFocused = { resultsFocused = true },
                    onPosterOpen = { vod ->
                        // 与 `ks2.java`（原版搜索卡片）一致：点开前把当前关键词记进历史。
                        recordHistory(keyword)
                        // 分类结果已经补过 site；下钻层级里再用当前目录的源兜底，最后才回首页源。
                        val site = vod.site ?: drillStack.lastOrNull()?.site ?: VodConfig.get().getHome()
                        // folder 条目（网盘聚合壳）留在本页展开目录；其余**直达选集**，不再先跳详情页。
                        if (vod.isDrillDownFolder()) openDrillDown(vod, site) else openPlayDialog(vod, site)
                    },
                    onPosterUp = { focusSite(focusedSite) },
                    onPosterLeft = { index -> if (index > 0) focusPoster(index - 1, visibleResults.size) },
                    onPosterRight = { index ->
                        if (index < visibleResults.lastIndex) focusPoster(index + 1, visibleResults.size)
                    },
                )
            }
        }

        // 点卡片直达选集：线路到手就弹这里，不再先跳详情页。
        // HeroPlayDialog 自己是一个 Dialog 窗口，放在 Column 里也不占任何布局。
        if (showPlayDialog && playFlags.isNotEmpty()) {
            val episodeVod = playVod
            val episodeSite = playSite
            HeroPlayDialog(
                flags = playFlags,
                // 搜索页还没有接历史库：-1 = 新剧，焦点落第 1 集。
                lastWatchedEpisodeIndex = -1,
                onEpisodeSelected = { flag, episode ->
                    showPlayDialog = false
                    val activity = context as? Activity
                    if (activity == null) {
                        Notify.show("缺少 Activity，无法起播")
                    } else if (episodeSite != null) {
                        // 起播复用详情页那一个公开入口（只调 VideoActivity.startDirect）。
                        startEpisode(
                            activity,
                            episodeSite.key,
                            episodeVod,
                            episodeVod?.id.orEmpty(),
                            flag,
                            episode,
                        )
                    }
                },
                onDismiss = { showPlayDialog = false },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 背景层：首页大图 → 磨砂 → 半透明黑遮罩
// ---------------------------------------------------------------------------

/**
 * 本页背景，结构对齐原版 `SearchActivityV2`：
 *
 * ```java
 * view.setBackgroundResource(R.drawable.bg_hero_mask_v);   // 纵向压黑
 * view2.setBackgroundColor(855638016);                    // 0x33000000，20% 黑
 * U1();  // 把首页传进来的 heroBitmap 丢到后台线程模糊，再 setImageBitmap 铺满
 * ```
 *
 * 结论：**原版是「高斯模糊的首页大图 + 半透明黑遮罩」，不是纯粹的半透明蒙版**——
 * 图2 里能看见海报的色块轮廓却完全看不出细节，正是模糊后的结果；
 * 如果只是半透明蒙版，下面的海报和文字会是清晰的。
 *
 * 这里做**真正的模糊**，不依赖 API 级别：
 * 1. Coil 把图解码到 [BACKDROP_SAMPLE_WIDTH]×[BACKDROP_SAMPLE_HEIGHT]（160×90）；
 * 2. [HeroImageLoader.blurredBackdrop] 在这张小图上跑分离式盒式模糊（两趟 ≈ 高斯）；
 * 3. 放大铺满整屏时双线性插值再做一次平滑。
 *
 * 之前只做「解码到 48×27 再拉伸」：等价于 40 倍最近邻放大，横向残影会变成一条条硬色带
 * （图3 就是那个样子），既不像模糊也压不住背景，反而更干扰看字。
 * 纯 Kotlin 模糊在所有 Android 版本上表现一致，不需要 `RenderEffect`（API 31+）
 * 或已废弃的 RenderScript，绘制成本仍然只是一张小图。
 *
 * 没有大图时（例如从别处直接打开本页）退回原来的纯色渐变。
 */
@Composable
private fun HeroSearchBackground(backdropUrl: String?) {
    val context = LocalContext.current
    // 模糊底图是异步算出来的：算完之前先显示纯黑，避免闪一下未模糊的原图。
    var backdrop by remember(backdropUrl) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(backdropUrl) {
        backdrop = null
        if (backdropUrl.isNullOrBlank()) return@LaunchedEffect
        backdrop = HeroImageLoader.blurredBackdrop(
            context = context,
            url = backdropUrl,
            width = BACKDROP_SAMPLE_WIDTH,
            height = BACKDROP_SAMPLE_HEIGHT,
            blurRadius = BACKDROP_BLUR_RADIUS,
        )?.asImageBitmap()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        val bitmap = backdrop
        if (bitmap == null) {
            Box(modifier = Modifier.fillMaxSize().background(FALLBACK_BACKGROUND))
        } else {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // ① 整体压黑：原版 0x33000000（20%）在真机白色字幕上根本不够，
            //    这里用 60% —— 保留色块与氛围，但压在底下的内容不会跟文字抢注意力（图2 的观感）。
            Box(modifier = Modifier.fillMaxSize().background(BACKDROP_SCRIM))
            // ② 底部压黑：海报行下方是白字标题，必须有深底托住。
            Box(modifier = Modifier.fillMaxSize().background(BACKDROP_BOTTOM_FADE))
        }
    }
}

// ---------------------------------------------------------------------------
// 骨架 2：左侧键盘区（328dp = 7×40 + 6×8）—— 实现已抽到 ui/hero/HeroKeyboard.kt，
// 搜索页与 TMDB 匹配弹窗共用同一个 internal KeyboardSection，本文件不再保留第二份。
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// 右侧：历史 + 热搜/建议
// ---------------------------------------------------------------------------

/**
 * 右列：历史 + 热搜（无输入）/ 建议（有输入）。
 *
 * `verticalScroll` 只是兜底（对应原版 `activity_search.xml` L66-73 的 `NestedScrollView`）：
 * 720dp 屏上两块内容约 200dp，远小于上半区可视高度，正常状态静止不动；
 * 只有极端字体放大或历史胶囊极多时才会真正滚动。
 *
 * 站点 Tab 与海报行在**下半区**（[ResultSection]），不在这里，所以右列滚动永远碰不到它们。
 */
@Composable
private fun RightContentColumn(
    modifier: Modifier = Modifier,
    scroll: ScrollState,
    history: List<String>,
    words: List<String>,
    wordTitle: String,
    hasInput: Boolean,
    historyRequesters: List<FocusRequester>,
    wordRequesters: List<FocusRequester>,
    historyFirstRequester: FocusRequester,
    wordFirstRequester: FocusRequester,
    onWordClick: (String) -> Unit,
    onDownToResults: () -> Unit,
) {
    Column(modifier = modifier.verticalScroll(scroll)) {
        // a. 历史
        HeroWordSection(
            title = TITLE_HISTORY,
            words = history,
            requesters = historyRequesters,
            // 历史非空时，「右侧第一排第一个」就是历史第一条。
            firstRowRequester = if (history.isNotEmpty()) historyFirstRequester else null,
            isFirstRowSection = history.isNotEmpty(),
            resultsVisible = hasInput,
            onWordClick = onWordClick,
            onDownToResults = onDownToResults,
        )

        // b. 建议（有输入）/ 热搜（无输入）
        if (words.isNotEmpty()) {
            Spacer(modifier = Modifier.height(RIGHT_SECTION_GAP))
            HeroWordSection(
                title = wordTitle,
                words = words,
                requesters = wordRequesters,
                // 【关键】热搜/建议区只在「历史为空」时才是第一排；两个区块各用各的
                // requester，点热搜（history 立刻非空）时热搜区被销毁也不会牵连历史区。
                firstRowRequester = if (history.isEmpty()) wordFirstRequester else null,
                isFirstRowSection = history.isEmpty(),
                resultsVisible = hasInput,
                onWordClick = onWordClick,
                onDownToResults = onDownToResults,
            )
        }
    }
}

/**
 * 区块标题：4dp × 16dp 红条（`shape_red_bar.xml`：#e50914、2dp 圆角）+ 20sp 粗体白字，
 * 条与文字间距 10dp，整行下方 8dp（`activity_search.xml` L92/L109）。
 */
@Composable
private fun HeroSectionTitle(title: String) {
    Row(
        modifier = Modifier.padding(bottom = SECTION_TITLE_BOTTOM_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(SECTION_BAR_WIDTH)
                .height(SECTION_BAR_HEIGHT)
                .background(ACCENT_RED, SECTION_BAR_SHAPE),
        )
        Spacer(modifier = Modifier.width(SECTION_BAR_MARGIN_END))
        Text(
            text = title,
            color = Color.White,
            fontSize = SECTION_TITLE_SP.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/**
 * 历史 / 热搜 / 建议区：`FlowRow`，胶囊 16sp、内边距 6dp×2dp（紧贴文字的小药丸，同
 * `shape_chip_round_normal.xml`）、12dp 圆角、外边距 6dp。
 * 整体左移 6dp 抵消首尾外边距，对应 XML 的 `layout_marginStart="-6dp"`。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeroWordSection(
    title: String,
    words: List<String>,
    requesters: List<FocusRequester>,
    // 只有「第一排第一个胶囊」拿得到它：键盘最右列直接打到这里。
    firstRowRequester: FocusRequester?,
    isFirstRowSection: Boolean,
    resultsVisible: Boolean,
    onWordClick: (String) -> Unit,
    onDownToResults: () -> Unit,
) {
    if (words.isEmpty()) return

    Column(modifier = Modifier.fillMaxWidth()) {
        HeroSectionTitle(title)
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .offset(x = -CHIP_MARGIN, y = -CHIP_MARGIN),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            words.forEachIndexed { index, word ->
                val isFirstChip = isFirstRowSection && index == 0
                HeroWordChip(
                    text = word,
                    requester = if (isFirstChip) {
                        firstRowRequester ?: requesters.getOrNull(index)
                    } else {
                        requesters.getOrNull(index)
                    },
                    resultsVisible = resultsVisible,
                    onDownToResults = onDownToResults,
                    onClick = { onWordClick(word) },
                    modifier = Modifier.padding(CHIP_MARGIN),
                )
            }
        }
    }
}

/**
 * 胶囊标签：**不铺纯黑**，底色沿用 `selector_item_round.xml` 的半透明黑（`#33000000`）；
 * 焦点态是原版 `shape_item_round_focused` 的**红色渐变色块**
 * （#E50914 → #F44336 → 玫瑰粉），整块实心、无描边。
 *
 * 底色药丸紧贴文字：横向 6dp、纵向 2dp（`shape_chip_round_normal.xml` 只声明了左右 6dp），
 * 不做大块 padding —— 大块 padding 会让半透明底看起来比文字宽一圈。
 */
@Composable
private fun HeroWordChip(
    text: String,
    requester: FocusRequester?,
    resultsVisible: Boolean,
    onDownToResults: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val isFocused by interaction.collectIsFocusedAsState()
    var focusFlag by remember { mutableStateOf(false) }
    val focused = isFocused || focusFlag
    val focusManager = LocalFocusManager.current

    Box(
        modifier = modifier
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .clip(CHIP_SHAPE)
            // 焦点态 = **红色渐变色块**（左深右浅：#E50914 → #F44336 → 玫瑰粉），
            // 整块实心变红、无描边 —— 与原版截图里「开心锤锤」的焦点样式一致。
            .background(
                brush = if (focused) HeroFocusBrush else SolidColor(CHIP_BG_NORMAL),
                shape = CHIP_SHAPE,
            )
            .onFocusChanged { focusFlag = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    // 【关键】确定键 / Enter **不再在这里拦截**。
                    // 点热搜会立刻改 field / history / hasInput，热搜区在同一帧被销毁；
                    // 如果在 onPreviewKeyEvent 里同步调 onClick()，按键事件还在分发途中，
                    // 后续的 KeyUp / 事件消费会打到已销毁的节点上（原生层直接崩，runCatching 兜不住）。
                    // 交给下面的 `.clickable`：它内部完整处理 KeyDown + KeyUp + 消费，
                    // 且回调走的是 Compose 的点击分发，不在按键分发栈里改状态。

                    // ←/→ 一律不拦截：交给 Compose 原生 2D 焦点搜索，
                    // 它会按几何位置自动把焦点交给键盘最右列（高度最接近的那一排）。
                    //
                    // ↓ 同样**先走原生 2D 搜索**：它会先落到同一区块的下一行，
                    // 已经是最后一行时再落到下一个区块/站点 Tab。
                    // 以前的写法直接跳到站点 Tab，于是「建议」第二行的胶囊永远进不去。
                    Key.DirectionDown -> {
                        if (focusManager.moveFocus(FocusDirection.Down)) {
                            true
                        } else if (resultsVisible) {
                            // 原生搜索不到目标（例如区块刚好被清空）：兜底落到站点 Tab。
                            onDownToResults()
                            true
                        } else {
                            false
                        }
                    }

                    else -> false
                }
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = CHIP_PADDING_H, vertical = CHIP_PADDING_V),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = CHIP_TEXT_SP.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------------------
// 骨架 3：下半区（站点 Tab + 海报行）
// ---------------------------------------------------------------------------

/**
 * 下半区结果区（对应 `activity_search.xml` L145-207 的 `resultSection`）：
 * 独立 `Column(fillMaxWidth + wrapContentHeight + padding(top = 12dp))`，高度只由内容决定，
 * 不吃 weight、不 fillMaxSize，**永远挤压不到上半区键盘**。
 * ```
 * Column(wrapContentHeight)                       // L145-151：match_parent 宽 + wrap 高 + marginTop 12
 *   ├─ LazyRow(height = 32dp)                     // L152-161：siteRecycler，32dp 行高
 *   ├─ Spacer(8dp)                                // Tab 行与海报区之间 8dp
 *   └─ Box(fillMaxWidth + height = 215dp)         // L162-167：FrameLayout height="215dp"
 *        └─ LazyRow(fillMaxSize)                  // L168-175：recycler match_parent
 * ```
 *
 * 【标准 1】两条 `LazyRow` 的 `contentPadding` 都是 `start = 24dp, end = 24dp`。
 * Compose 的 `LazyRow` 会按**自身外框**裁剪超出边界的子项，而外层根节点现在没有横向 padding，
 * LazyRow 的滚动区等于整屏宽度；首项左侧 24dp 的 contentPadding 就是那块裁剪缓冲区，
 * 「全部」在焦点放大 1.08 时也不会被切边。等价于原版 `clipChildren=false +
 * clipToPadding=false + 根 paddingStart=24dp` 的组合效果（L6-7 / L10-11 / L155-156 / L170-171）。
 *
 * 海报区**【标准 3】高度 215dp 由外层 `Box` 写死**（原版 FrameLayout 就是这么写的），
 * 卡片自身 `onMeasure` 是 120×214dp，215 = 214 + 1dp 余量，绝不会被切；
 * `AndroidView` 里**绝不加任何尺寸 Modifier**。
 */
@Composable
private fun ResultSection(
    modifier: Modifier = Modifier,
    siteState: LazyListState,
    posterState: LazyListState,
    siteLabels: List<String>,
    items: List<Vod>,
    selectedSite: Int,
    // 结果区（Tab 行 / 海报）当前是否持有焦点 —— 站点 Tab 三态里的第 2 态要用。
    resultModuleFocused: Boolean,
    searching: Boolean,
    progress: SearchProgress?,
    // 非空时覆盖空态文案（下钻目录用）；null = 沿用搜索页原来的三态文案。
    emptyHint: String?,
    siteRequesters: List<FocusRequester>,
    posterRegistry: HeroPosterViewRegistry,
    onSiteFocused: (Int) -> Unit,
    onSiteConfirm: (Int) -> Unit,
    onSiteUp: () -> Unit,
    onSiteDown: () -> Unit,
    onSiteLeft: (Int) -> Unit,
    onSiteRight: (Int) -> Unit,
    onPosterFocused: () -> Unit,
    onPosterOpen: (Vod) -> Unit,
    onPosterUp: (Int) -> Unit,
    onPosterLeft: (Int) -> Unit,
    onPosterRight: (Int) -> Unit,
) {
    Column(modifier = modifier) {
        // 站点 Tab：行高 36dp（原版 siteRecycler wrap_content = 36dp），胶囊本体 26dp 垂直居中。
        LazyRow(
            state = siteState,
            modifier = Modifier
                .fillMaxWidth()
                .height(SITE_ROW_HEIGHT),
            contentPadding = PaddingValues(
                start = RESULT_CONTENT_PADDING,
                end = RESULT_CONTENT_PADDING,
            ),
            horizontalArrangement = Arrangement.spacedBy(SITE_ITEM_SPACING),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 池子只有 FOCUS_REQUESTER_POOL 个 requester，站点数异常多时截断，绝不越界。
            itemsIndexed(items = siteLabels.take(siteRequesters.size)) { index, label ->
                HeroSiteTab(
                    label = label,
                    selected = index == selectedSite,
                    resultModuleFocused = resultModuleFocused,
                    requester = siteRequesters[index],
                    onFocused = { onSiteFocused(index) },
                    onConfirm = { onSiteConfirm(index) },
                    onMoveUp = onSiteUp,
                    onMoveDown = onSiteDown,
                    onMoveLeft = { onSiteLeft(index) },
                    onMoveRight = { onSiteRight(index) },
                )
            }
        }

        // Tab 行与海报 Box 之间 8dp。
        Spacer(modifier = Modifier.height(SITE_POSTER_GAP))

        // 【标准 3】海报容器：高度 215dp（`activity_search.xml` L162-167 的 FrameLayout
        // height="215dp"），内部 LazyRow 填满它。卡片自身 onMeasure 是 120×214dp，
        // 215 = 214 + 1dp 余量，横向滚动交给 LazyRow。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(POSTER_BOX_HEIGHT),
        ) {
            LazyRow(
                state = posterState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = RESULT_CONTENT_PADDING,
                    end = RESULT_CONTENT_PADDING,
                ),
                horizontalArrangement = Arrangement.spacedBy(POSTER_ITEM_SPACING),
            ) {
                itemsIndexed(items = items) { index, vod ->
                    // 【红线】HeroPosterCard 自己 onMeasure 死锁 120×214dp，
                    // 这里绝不加 Modifier.size()/width()/height()，直接放进去。
                    AndroidView(
                        factory = { context ->
                            HeroPosterCard(context).also { card -> posterRegistry.bind(index, card) }
                        },
                        update = { card ->
                            // 字段映射逐条对应 `ks2.java`（原版搜索卡片）：
                            //   name → 剧名；getSiteName() → 左上站点标签；getRemarks() → 左下集数标签；
                            //   pic → 海报地址。
                            // 只在值变化时写，避免每次重组都触发 HeroPosterCard 内部的图片重载。
                            if (card.title != vod.name) card.title = vod.name
                            if (card.topStartTag != vod.siteName) card.topStartTag = vod.siteName
                            if (card.bottomStartTag != vod.remarks) card.bottomStartTag = vod.remarks
                            if (card.imageUrl != vod.pic) card.imageUrl = vod.pic

                            card.setOnFocusChangeListener { _, hasFocus ->
                                if (hasFocus) onPosterFocused()
                            }
                            card.setOnClickListener { onPosterOpen(vod) }
                            card.setOnKeyListener { _, keyCode, event ->
                                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                                when (keyCode) {
                                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                                        onPosterLeft(index)
                                        true
                                    }

                                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                        onPosterRight(index)
                                        true
                                    }

                                    KeyEvent.KEYCODE_DPAD_UP -> {
                                        onPosterUp(index)
                                        true
                                    }

                                    else -> false
                                }
                            }
                        },
                        onRelease = { card -> posterRegistry.unbind(index, card) },
                    )
                }
            }

            // 加载 / 空态提示：**画在 215dp 容器内部**，不改任何尺寸，也不参与焦点。
            if (items.isEmpty()) {
                val hint = emptyHint ?: when {
                    searching -> if (progress != null && progress.total() > 0) {
                        "搜索中 ${progress.current()}/${progress.total()}"
                    } else {
                        "搜索中…"
                    }

                    progress != null -> "没有找到相关内容"
                    else -> "输入关键词后按搜索键"
                }
                Text(
                    text = hint,
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 16.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

/**
 * 站点胶囊：26dp 高、16sp、13dp 圆角（`adapter_type.xml` L3/L10，字号按需求统一到 16sp）；
 * 选中放大 1.08 / 140ms（`dz2.java` L45-47）。
 *
 * 三态严格按《焦点总结》，与首页分类 Tab 同一套规则：
 * 1. **聚焦**（不论是否选中）→ 实心红渐变色块 + 纯白字，无描边（图1 的焦点样式）；
 * 2. **失焦 + 当前站点 + 焦点还在下方结果区** → 红框描边 + 弱底，
 *    提示「下方这批海报属于哪个站点」；
 * 3. **焦点离开结果区**（回键盘 / 顶栏）→ 半透明普通底，无红底无红框，选中态暂时休眠。
 *
 * 第 2 态必须依赖 [resultModuleFocused]：只看 `selected` 会在焦点回到键盘时
 * 仍然挂一圈红框，正是《焦点总结》第 3 条要消掉的「多出来的红框」。
 */
@Composable
private fun HeroSiteTab(
    label: String,
    selected: Boolean,
    resultModuleFocused: Boolean,
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
    var focusFlag by remember { mutableStateOf(false) }
    val focused = isFocused || focusFlag
    // 三态里的第 2 态：失焦、仍是当前站点、且焦点还在结果区。
    val selectedRing = selected && !focused && resultModuleFocused
    val scale by animateFloatAsState(
        targetValue = if (selected) SITE_SELECTED_SCALE else 1f,
        animationSpec = tween(durationMillis = SITE_SCALE_DURATION_MS),
        label = "siteTabScale",
    )

    Box(
        modifier = Modifier
            .scale(scale)
            .height(SITE_TAB_HEIGHT)
            .focusRequester(requester)
            .clip(SITE_TAB_SHAPE)
            // 焦点态 = 红色渐变色块，和胶囊共用同一支 HeroFocusBrush，全页焦点色一致。
            .background(
                brush = if (focused) HeroFocusBrush else SolidColor(CHIP_BG_NORMAL),
                shape = SITE_TAB_SHAPE,
            )
            // 第 2 态：只有「失焦 + 选中 + 焦点仍在结果区」才挂红框。
            .then(
                if (selectedRing) {
                    Modifier.border(CHIP_STROKE, FOCUS_RED, SITE_TAB_SHAPE)
                } else {
                    Modifier
                },
            )
            .onFocusChanged {
                focusFlag = it.isFocused
                if (it.isFocused) onFocused()
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    // 确定键同样交给 `.clickable`（KeyDown + KeyUp 完整处理），
                    // 避免在按键分发栈里同步改状态导致下方海报行内容替换时的事件悬空。

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
            .clickable(interactionSource = interaction, indication = null, onClick = onConfirm)
            // 站点 Tab 的左右内边距**独立于胶囊**：胶囊这次收窄到 6dp，Tab 保持原尺寸不变。
            .padding(horizontal = SITE_TAB_PADDING_H),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = SITE_TAB_SP.sp,
            fontWeight = if (focused || selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------------------
// 真实数据：本地历史 + 热搜/建议解析
// ---------------------------------------------------------------------------

/**
 * 读历史：`Prefers("keyword")` 里存的是 JSON 字符串数组。
 *
 * 与 `xp.java` 构造函数的 a=7 分支逐字对应：
 * ```java
 * List list = vx0.D("keyword").isEmpty()
 *     ? new ArrayList()
 *     : App.l.h.fromJson(vx0.D("keyword"), TypeToken.getParameterized(List.class, String.class).getType());
 * ```
 * 本项目的 `vx0.D/S` 就是 [Setting.getKeyword] / [Setting.putKeyword]（同一套 Prefers 键名）。
 *
 * 任何脏数据（旧版本写坏的、手工改过的）都退化成空列表，绝不让搜索页因为历史记录崩掉。
 */
private fun readHistory(): List<String> {
    val raw = Setting.getKeyword()
    if (raw.isBlank()) return emptyList()
    return runCatching {
        App.gson().fromJson<List<String>>(raw, object : TypeToken<List<String>>() {}.type)
    }.getOrNull()
        ?.filter { it.isNotBlank() }
        ?.distinct()
        ?.take(HISTORY_LIMIT)
        ?: emptyList()
}

/**
 * 解析热词 / 建议词。
 *
 * 两个接口的外层结构恰好都能被 [Word] 直接吃掉：
 * - 360kan 热搜：`{"errno":0,"data":[{"title":"开心锤锤",...}]}` → `data[].title`
 * - 爱奇艺建议：`{"code":"A00000","data":[{"name":"凡人歌",...}]}` → `data[].name`
 *
 * [Word.Data] 上就是 `@SerializedName(value = "title", alternate = "name")`，
 * 所以同一个解析器能同时覆盖两者（原版 `zr2` 回调也是这么做的）。
 */
private fun parseWords(json: String): List<String> {
    if (json.isBlank()) return emptyList()
    return Word.objectFrom(json).data
        .map { it.title.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
}

// ---------------------------------------------------------------------------
// 尺寸常量（每一条都能在 ref_docs 里找到出处，见对应注释）
// ---------------------------------------------------------------------------

// 背景：首页大图先解码到 160×90，再做盒式模糊（两趟 ≈ 高斯），最后放大铺满。
// 160×90 足够保留「大面积色块 / 明暗分布」，又小到模糊一帧就能算完（见 HeroSearchBackground）。
private const val BACKDROP_SAMPLE_WIDTH = 160
private const val BACKDROP_SAMPLE_HEIGHT = 90
// 模糊半径（单位 = 采样图上的像素）。8 × (1920/160 ≈ 12) ≈ 屏幕上 96px 的模糊，符合图2 的观感。
private const val BACKDROP_BLUR_RADIUS = 8
private val BACKDROP_SCRIM = Color(0x99000000)      // 60% 黑：压住背景细节，只留氛围色块
private val BACKDROP_BOTTOM_FADE = Brush.verticalGradient(
    0.0f to Color.Transparent,
    0.55f to Color.Black.copy(alpha = 0.35f),
    1.0f to Color.Black.copy(alpha = 0.92f),
)
// 没有大图时的兜底：原来的深蓝黑渐变。
private val FALLBACK_BACKGROUND = Brush.verticalGradient(
    0.0f to Color(0xFF0B1119),
    0.55f to Color(0xFF101823),
    1.0f to Color.Black,
)

// 根容器：activity_search.xml L4 → paddingTop 24dp；横向 24dp 不再放在根上（标准 1），
// 而是拆给各子节点：键盘自己 padding(start=24)、右列自己 padding(end=24)、
// 两条 LazyRow 用 contentPadding(24, 24) —— 只有 LazyRow 自己的滚动区够宽，焦点放大才不被裁。
private val ROOT_PADDING_H = 24.dp
private val ROOT_PADDING_TOP = 24.dp

// 键盘 / 输入框的全部尺寸常量已抽到 `ui/hero/HeroKeyboard.kt`（internal），
// 搜索页与 TMDB 匹配弹窗共用同一份；这里只留本页布局自己的边距与缩放基准。
private val LEFT_COLUMN_MARGIN_END = 24.dp          // activity_search.xml L21

// 左列内容自然高度 = 输入行 40 + 8(activity_search.xml L61 marginTop) + 6×40 + 5×8 = 328dp，
// 宽度 = 7×40 + 6×8 = 328dp；两者都由内容撑出（wrapContentWidth），不再写死、不再 fillMaxHeight。
// 上半区可用高度不足这个值时，按 maxHeight / 该值 等比缩小整棵键盘子树（见 HeroSearchScreen）。
private val KEYBOARD_NATURAL_HEIGHT = INPUT_HEIGHT + KEY_SPACING + KEY_SIZE * 6 + KEY_SPACING * 5

// 焦点红（胶囊 / 站点 Tab 描边共用；键盘红底同色，已随键盘抽到 HeroKeyboard.kt）。
private val FOCUS_RED = Color(0xFFFF0000)

// 区块标题：20sp 粗体白 + 4×20dp 红条（shape_red_bar.xml：#e50914、2dp 圆角）+ 10dp 间距。
private const val SECTION_TITLE_SP = 20f
private val SECTION_BAR_WIDTH = 4.dp
private val SECTION_BAR_HEIGHT = 20.dp
private val SECTION_BAR_MARGIN_END = 10.dp
private val SECTION_BAR_SHAPE = RoundedCornerShape(2.dp)
private val SECTION_TITLE_BOTTOM_GAP = 8.dp

// 胶囊：16sp；内边距严格照 `adapter_search_word.xml` + `shape_chip_round_normal.xml`
// （shape 只声明 paddingStart/End = 6dp、上下 0dp），所以**横向 6dp、纵向只留 2dp 呼吸**，
// 底色就是紧贴文字的一圈小药丸；外边距 6dp 与原版 layout_margin 一致。
private const val CHIP_TEXT_SP = 16f
private val CHIP_PADDING_H = 6.dp
private val CHIP_PADDING_V = 2.dp
private val CHIP_MARGIN = 6.dp
private val CHIP_SHAPE = RoundedCornerShape(12.dp)
private val CHIP_STROKE = 1.5.dp
private val CHIP_BG_NORMAL = Color(0x33000000)      // black_20（半透明，不是纯黑）

// 站点 Tab：16sp（与胶囊标签同字号）+ 行高 32dp（activity_search.xml L152-161）+ 间距 8dp。
private const val SITE_TAB_SP = 16f
private val SITE_TAB_HEIGHT = 26.dp
private val SITE_TAB_SHAPE = RoundedCornerShape(13.dp)
// Tab 自己的左右内边距（12dp），不跟随胶囊收窄，保持图1里的块宽。
private val SITE_TAB_PADDING_H = 12.dp
private const val SITE_SELECTED_SCALE = 1.08f
private const val SITE_SCALE_DURATION_MS = 140
private val SITE_ROW_HEIGHT = 32.dp
private val SITE_ITEM_SPACING = 8.dp
// Tab 行与海报 Box 之间的 8dp。
private val SITE_POSTER_GAP = 8.dp
// 两条 LazyRow 的内容内边距：start/end 都留 24dp（标准 1）。
// 外层根节点已不再有横向 padding，LazyRow 的滚动区就是整屏宽度，
// 首项左侧的 24dp 就是焦点放大 1.08 时的裁剪缓冲区，「全部」不会被切边。
private val RESULT_CONTENT_PADDING = 24.dp
private val RESULT_SECTION_TOP_GAP = 12.dp         // activity_search.xml L151
private val RIGHT_SECTION_GAP = 16.dp              // 「历史」与「建议 / 热搜」之间的紧凑间隔

// 海报：外层 Box 高 215dp（activity_search.xml L162-167 FrameLayout height="215dp"），
// 卡片自身 onMeasure = 120×214dp，卡间距 12dp。
private val POSTER_BOX_HEIGHT = 215.dp
private val POSTER_ITEM_SPACING = 12.dp
private const val POSTER_SCROLL_ANCHOR = 4

// 下半区（结果区）固定占用的总高度 = 12(顶部间距) + 32(Tab 行) + 8(Tab/海报间距) + 215(海报) = 267dp。
// 键盘缩放系数**永远按「结果区存在」这一最坏情形**计算，所以有无搜索结果时按键大小完全一致，
// 不会出现「没结果时大、有结果时小」的跳变。
private val RESULT_BAND_HEIGHT = RESULT_SECTION_TOP_GAP + SITE_ROW_HEIGHT + SITE_POSTER_GAP + POSTER_BOX_HEIGHT

// 红色 @color/red = #E50914 与键盘常量一起抽到 HeroKeyboard.kt（internal ACCENT_RED）。

// 焦点红块统一用全局 [HeroFocusBrush]（HeroCommonUi）：#E50914 → #F44336 → 玫瑰粉，
// 与首页分类 Tab、播放线路、换源弹窗站点标签共用同一支渐变，不再各自维护一份色标。

// 历史最多 9 条（输入上限 19 字随键盘常量一起抽到 HeroKeyboard.kt）。
private const val HISTORY_LIMIT = 9

// 区块标题文案（与 topbar_search_page.png 一致）。
private const val TITLE_HISTORY = "历史"
private const val TITLE_HOT = "热搜"
private const val TITLE_SUGGEST = "建议"
// 结果区第一个站点 Tab：聚合所有源的结果（同原版 `dz2` 里第一项「全部」）。
private const val TITLE_ALL = "全部"

// ---------------------------------------------------------------------------
// 搜索页内嵌下钻（folder 条目）
// ---------------------------------------------------------------------------

/**
 * 下钻栈的一层：folder 条目 + 它所属的源。
 *
 * 存整个 [Site] 而不是 key：请求要原样带上 `api / type / ext`，
 * 只留 key 再回查 `VodConfig` 在「临时站点」场景下会查成空 Site。
 */
private data class DrillEntry(val site: Site, val vod: Vod)

/** 下钻时站点 Tab 行里唯一的按钮文案（确定键 = 出栈，等价于返回键）。 */
private const val DRILL_BACK_LABEL = "⬅️ 返回上一级"

/** 网盘聚合插件的 folder 壳前缀（与 `Vod.isFolder()` 的 `vod_tag=folder` 等价）。 */
private const val ATVP_FOLDER_PREFIX = "atvp_detail:"

/**
 * folder 判定：原版在点击处就分流到分类页，而搜索 / 历史结果可能丢掉 `vod_tag=folder`，
 * 所以这里连 `atvp_detail:` 前缀一起认（与 `HeroEntryRoute` 的判定保持一致）。
 */
private fun Vod.isDrillDownFolder(): Boolean =
    isFolder || id.startsWith(ATVP_FOLDER_PREFIX, ignoreCase = true)

// 动态列表（历史 / 热搜建议 / 站点 Tab）共用的 FocusRequester 池容量：
// 站点总数一般十几个、热词一次 30 条左右，64 足够且不需要随内容重建 requester。
private const val FOCUS_REQUESTER_POOL = 64

// 站点 Tab 落焦点的逐帧重试上限：结果区要等 `hasInput` 之后才进组合树，
// 一般 1~2 帧即可挂载；给 8 帧余量既不会无限空转，也覆盖得了慢设备的一帧长布局。
private const val FOCUS_RETRY_FRAMES = 8

private const val TAG_SEARCH = "HeroSearch"

// 热搜：360kan 排行榜（`SearchActivityV2.X1()` 的原地址与原 Referer，一字不改）。
private const val HOT_URL = "https://api.web.360kan.com/v1/rank?cat=1"
private const val HOT_REFERER = "https://www.360kan.com/rank/general"

// 建议词防抖：电视键盘逐字输入，250ms 内的连续按键只打一次接口。
private const val SUGGEST_DEBOUNCE_MS = 250L

// 点击胶囊（热搜 / 历史 / 建议）后到真正改状态之间的缓冲（毫秒）：
// 让遥控器这次 KeyDown/KeyUp 彻底分发完，避免「改状态销毁节点」和「按键事件仍在分发」撞在同一帧。
private const val SEARCH_CLICK_SETTLE_MS = 50L
