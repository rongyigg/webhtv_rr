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
import androidx.compose.foundation.layout.widthIn
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
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.fongmi.android.tv.R
import com.fongmi.android.tv.api.SiteApi
import com.fongmi.android.tv.api.config.VodConfig
import com.fongmi.android.tv.bean.Class as VodType
import com.fongmi.android.tv.bean.Result
import com.fongmi.android.tv.bean.Site
import com.fongmi.android.tv.bean.TmdbConfig
import com.fongmi.android.tv.bean.TmdbItem
import com.fongmi.android.tv.bean.TmdbMatchCache
import com.fongmi.android.tv.bean.TmdbPerson
import com.fongmi.android.tv.bean.Vod
import com.fongmi.android.tv.impl.Callback
import com.fongmi.android.tv.model.SiteViewModel
import com.fongmi.android.tv.service.TmdbService
import com.fongmi.android.tv.setting.Setting
import com.fongmi.android.tv.setting.SiteBlockSetting
import com.fongmi.android.tv.setting.TmdbSitePolicy
import com.fongmi.android.tv.ui.helper.TmdbMatcher
import com.fongmi.android.tv.ui.hero.ChangeSourceDialog
import com.fongmi.android.tv.ui.hero.HeroAccentColor
import com.fongmi.android.tv.ui.hero.HeroFocusBrush
import com.fongmi.android.tv.ui.hero.HeroHomeLogoButton
import com.fongmi.android.tv.ui.hero.HeroImageLoader
import com.fongmi.android.tv.ui.hero.HeroSourceEntry
import com.fongmi.android.tv.ui.hero.SourceSidebar
import com.fongmi.android.tv.ui.hero.SourceSidebarMode
import com.fongmi.android.tv.ui.hero.TmdbMatchDialog
import com.fongmi.android.tv.ui.hero.openHeroDetailOrFolder
import com.fongmi.android.tv.utils.Notify
import com.fongmi.android.tv.utils.PushParser
import com.google.gson.JsonObject
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 首页 override 里的一条完整 TMDB 数据。
 *
 * 首页只用其中三样：提档后的横图（[highResBackdrop]）、简介（[overview]）、艺术字 Logo（[logoUrl]）；
 * 详情页还要 [item] / [detail] / [cast] 才能零请求直接拼出 `Ready`。
 * 所以首页内存 overrides 与 [HeroTmdbPrefetchCache] 共用这一个模型，不维护两套字段。
 *
 * **不含竖版海报**：接口卡片图（`poster.url`）一律保持源站原图，TMDB 只增强背景 / 简介 / Logo。
 */
internal data class HeroTmdbData(
    val item: TmdbItem,
    /** `TmdbService.detail` 的原始 JSON：只有「匹配 + 横图」时为 null，焦点条目补详情后才有值。 */
    val detail: JsonObject? = null,
    val cast: List<TmdbPerson> = emptyList(),
    /** 已按当前语言取过译文的简介；空串 = 不覆盖源站简介。 */
    val overview: String = "",
    /** 艺术字 Logo 的完整地址；null / 空串 = 回落 32sp 文字标题。 */
    val logoUrl: String? = null,
    val highResBackdrop: String = "",
    /** 是否手动匹配命中：只有手动命中才允许覆盖剧名（历史决策）。 */
    val manual: Boolean = false,
)

/**
 * 首页预拉取好的 TMDB 详情，交给详情页直接取用（消除「点进详情页再等一轮网络」的卡顿）。
 *
 * 键 = [heroTmdbKey]（站点 + 条目 id）：与首页 `posterKey`、详情页 `siteKey + vodId` 是同一套规则，
 * 不同源里 id 相同的条目不会互相串数据。
 *
 * **只收完整数据**：[HeroTmdbData.detail] 为 null（只匹配到条目、还没拉详情）一律不入缓存 ——
 * 详情页拿到它时要能做到一条请求都不发。
 */
internal object HeroTmdbPrefetchCache {

    /**
     * 条数上限：详情 JSON 单条几十 KB 到几百 KB。
     *
     * 缓存里存的是与首页 override **同一个对象引用**，不额外复制；命中率靠「预取 + 焦点条目补写」，
     * 这里只负责内存有界（详情页取走即删，正常使用不会有几十条同时驻留）。
     */
    private const val MAX_ENTRIES = 24

    private val entries = ConcurrentHashMap<String, HeroTmdbData>()

    /** 写入一条完整数据；`detail` 为空时忽略（调用方无需自己判断数据是否完整）。 */
    fun put(key: String, data: HeroTmdbData) {
        if (key.isBlank() || data.detail == null) return
        // 超上限就丢一条（任意一条）：只求有界，精确 LRU 对「进详情页即取走」的场景没有价值。
        if (entries.size >= MAX_ENTRIES && !entries.containsKey(key)) {
            entries.keys.firstOrNull()?.let { entries.remove(it) }
        }
        entries[key] = data
    }

    /** 取出并删除：详情页用过一次就作废，避免用户手动改过匹配后仍拿到旧条目。 */
    fun take(key: String): HeroTmdbData? = entries.remove(key)

    /** 显式作废（手动改成别的 TMDB 条目时调用）。 */
    fun remove(key: String) {
        if (key.isNotBlank()) entries.remove(key)
    }
}

/**
 * 站点内条目身份：`站点 key | 条目 id`。
 *
 * 首页 override、首页→详情页预取缓存、详情页取缓存三处必须用同一个键。
 * **不能用剧名**：同名不同季 / 重制版会互相覆盖，`Vod.id` 才是站点内唯一键。
 */
internal fun heroTmdbKey(siteKey: String, vodId: String): String = siteKey + "|" + vodId

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
        // 冷启动自愈：进程重启后 VodConfig 还是空壳（没有任何页面触发过配置加载），
        // 首页会拿空站点去请求 homeContent，表现就是「接口源全丢了」。
        ensureVodConfigLoaded()
        // 首页真实化：记录「当前用的是哪个源」。onResume 靠它判断用户是不是刚从设置页换了源。
        loadedHomeSiteKey = VodConfig.get().getHome().key
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                HeroHomeScreen(
                    siteViewModel = siteViewModel,
                    // 首次加载与切源后重载都走这里，日志只在一个地方打。
                    onRefreshHome = { refreshHome("界面请求") },
                    // 卡片确定键 → 真实详情页，带上真实的 (Vod, Site)、当前横版背景，以及首页已预取的 TMDB 艺术字 / 简介。
                    // folder 条目（网盘聚合壳，如 atvp_detail:）交给原版分类页，其余进 Hero 详情页。
                    onOpenDetail = { vod, site, backdrop, logo, overview ->
                        openHeroDetailOrFolder(this, vod, site, backdrop, logo, overview)
                    },
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
     * 冷启动自愈：如果 [VodConfig] 还没有站点，就从**本地已保存的配置**加载一次。
     *
     * 背景：本项目的 `VodConfig` 是纯内存单例，进程重启后是空壳；没有这步的话，
     * `getHome()` 返回空 [Site]，`homeContent()` 必然失败 —— 用户看到的就是「重启后接口全丢了」。
     *
     * - `init()`：把 config 指向 Room 里保存的 `Config.vod()`（用户上次用的接口地址）；
     * - `load(callback)`：内部 `Task.submit`，异步执行，不卡首帧；成功后回调在主线程；
     * - 只在 `getHome().isEmpty()` 时触发：已经加载过的页面完全不受影响；
     * - 失败（例如从没配过源）只记日志，绝不弹窗打扰。
     */
    private fun ensureVodConfigLoaded() {
        if (!VodConfig.get().getHome().isEmpty()) return
        Log.w(TAG_HOME, "冷启动 VodConfig 为空，尝试从本地已保存配置加载")
        VodConfig.get().init().load(object : Callback() {
            override fun success() {
                Log.d(TAG_HOME, "本地配置加载完成: 站点数=${VodConfig.get().getSites().size}")
                // 回调已经在主线程（BaseConfig 用 App.post 派发 success）。
                refreshHome("冷启动配置加载完成")
            }

            override fun error(msg: String) {
                Log.w(TAG_HOME, "本地配置加载失败: $msg")
            }
        })
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
    // 卡片确定键：回传真实的 (Vod, Site)、这张卡当前显示的横版背景（TMDB 命中时是 w1920），
    // 以及首页已经预取好的 TMDB 艺术字 / 简介 —— 详情页首帧就能出完整画面。
    onOpenDetail: (Vod, Site, String, String, String) -> Unit,
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
    // 【动态分类】Tab 数量现在由 `Result.types` 决定（不同源 3~20 个不等），
    // 所以 requester 必须用**固定容量池**：列表内容变化时 requester 实例不变，
    // 不会出现「分类到达 → 焦点从 Tab 上掉下来 / requester 目标失效」。
    val tabRequesters = remember { List(TAB_REQUESTER_POOL) { FocusRequester() } }

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
    // 换源弹窗（View Dialog）打开状态 + 关闭后要还焦点的那张卡（-1 = 没有待处理）。
    // **必须声明在 result 观察者之前**：观察者要用它判断「这条 result 是不是弹窗自己的」。
    var showSourceDialog by remember { mutableStateOf(false) }
    // 已经自动加载过的「站点:分类」。分类结果本身也可能为空，用它挡掉来回死循环。
    var autoCategoryKey by remember { mutableStateOf<String?>(null) }
    // 当前接口源真实返回的分类（`Result.types`）：**Tab 列表的唯一数据源**。
    // 分类结果本身通常不带 types，所以这里单独缓存；声明在观察者之前。
    var homeTypes by remember { mutableStateOf<List<VodType>>(emptyList()) }
    // 当前 Tab 列表来自哪个源：换源后即使新源不返回分类，也要把旧源的 Tab 清掉，
    // 否则用户会在新源上看到一串点了也没数据的「幽灵分类」。
    var homeTabsSiteKey by remember { mutableStateOf("") }
    // 【动态分类 Tab】= 固定的「推荐」+ 源返回的每一个分类，**直接持有 VodType**。
    // 以前是「写死 6 个文案，再用 contains 去猜对应哪个分类」，源分类名一变就匹配不上、
    // Tab 也就永远只切视觉不发请求。现在 index → type 是硬绑定，请求直接用 typeId。
    // 截断到 requester 池大小，保证 `requesters[index]` 永远有实例。
    val homeTabs = remember(homeTypes) {
        buildList {
            add(HomeTab(label = TAB_RECOMMEND, type = null))
            homeTypes.take(TAB_REQUESTER_POOL - 1).forEach { add(HomeTab(label = it.typeName, type = it)) }
        }
    }
    // 用户当前是否停在「分类 Tab」上（index > 0）。
    // 分类请求返回空列表时不能触发下面「首页为空 → 自动加载第一个分类」的兜底，
    // 否则会把用户明确选中的分类悄悄换成第一个分类；[reloadHome] 会把它复位。
    var categoryTabActive by remember { mutableStateOf(false) }

    /**
     * 重新拉一次首页，并复位「自动分类」标记。
     *
     * 首次进入、小房子切源都走这里；设置页返回由 Activity 的 `onResume` 直接调 `onRefreshHome()`。
     */
    fun reloadHome() {
        autoCategoryKey = null
        categoryTabActive = false
        onRefreshHome()
    }

    DisposableEffect(siteViewModel) {
        // `SiteViewModel.init()` 会把 `result` 置为 null，LiveData 会把 null 一起派发下来，
        // 所以这里必须按可空接收：null 直接忽略（等价于「还没拿到数据」）。
        val observer = Observer<Result?> { result ->
            if (result == null) return@Observer
            // 换源弹窗打开期间，`result` 通道归弹窗（folder 下探的分类结果 / 卡片补详情）：
            // 本页必须让路，否则目录结果会把首页海报行整排换成文件夹内容。
            // 关窗后由 [refreshCurrentHome] 重新拉一次，顺带作废弹窗可能还在途的回包。
            if (showSourceDialog) {
                Log.d(TAG_HOME, "换源弹窗打开中，首页忽略 result 通道")
                return@Observer
            }
            val site = VodConfig.get().getHome()
            // 【动态分类 Tab 的唯一数据源】`Result.types` 决定整排 Tab。
            // - 换源后的第一条结果：**无条件同步**（新源没有分类就把旧源的 Tab 收掉）；
            // - 同一个源：只在 types 非空时更新（分类首包通常不带 types，不能把 Tab 清空）。
            if (site.key != homeTabsSiteKey) {
                homeTabsSiteKey = site.key
                homeTypes = result.types
            } else if (result.types.isNotEmpty()) {
                homeTypes = result.types
            }
            // 【关键】`SiteApi.homeContent` / `categoryContent` 都不像 `searchContent` 那样给每条 Vod 盖 site。
            // 不补的话 `Vod.getSiteKey()` 是空串：详情页「排除当前源」直接失效、来源标签也是空白。
            result.list.forEach { it.setSite(site) }
            Log.d(TAG_HOME, "获取到真实数据条数=${result.list.size} msg=${result.msg} site=${site.key}")
            // 与原版 HomeActivity.addVideo() 完全一致：首页 list 为空但站点返回了分类时，
            // 自动去拉第一个分类的第 1 页。只返回 class、不返回 list 的源靠这一步才有内容，
            // 少了它就是「原版正常、我们空白」。
            val firstType = result.types.firstOrNull()
            // 只在「首页」兜底：用户明确停在分类 Tab 上时不自动改写分类。
            if (!categoryTabActive && result.list.isEmpty() && firstType != null) {
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
    // 当前选中的分类：**焦点移到哪个 Tab 就选哪个**（电视端少按一次键）。
    // 初值 -1 = 还没进过 Tab 栏（此时所有 Tab 都不涂红，只有焦点态）。
    // 确定键仍然保留，作为“确认”语义的兼容入口。
    var selectedTabIndex by remember { mutableIntStateOf(-1) }

    /**
     * 按**当前停留的 Tab**重新拉一次数据（换源弹窗关闭后专用）。
     *
     * 弹窗打开期间 `result` 通道归弹窗（folder 下探 / 卡片补详情），本页的结果被忽略；
     * 关窗后必须按用户当前所在 Tab 重新请求：
     * 1. 恢复本页数据（若开窗时正好有首页请求在途，它的回包已经被丢弃）；
     * 2. 新请求会按 TaskType 作废弹窗还在途的那一轮（`SiteViewModel.execute` 会 cancel 旧 future
     *    并让旧回包在 taskId 校验处直接返回），避免关窗后旧目录结果再打回首页。
     *
     * 停在分类 Tab 时必须重拉**该分类**，不能退回「推荐」——否则 Tab 选中态与列表内容会错位。
     *
     * 放在 `selectedTabIndex` 之后：Kotlin 局部变量从声明处才开始生效，写在前面会拿不到它。
     */
    fun refreshCurrentHome() {
        val index = selectedTabIndex
        val type = homeTabs.getOrNull(index)?.type
        val site = VodConfig.get().getHome()
        if (index > 0 && type != null && type.typeId.isNotBlank() && site.key.isNotEmpty()) {
            categoryTabActive = true
            Log.d(TAG_HOME, "换源弹窗关闭，重拉当前分类: ${type.typeName}(${type.typeId})")
            siteViewModel.categoryContent(site.key, type.typeId, "1", true, hashMapOf())
            return
        }
        Log.d(TAG_HOME, "换源弹窗关闭，重拉首页")
        reloadHome()
    }

    // 已经为哪个 Tab 拉过数据：焦点在同一项上抖动 / 确定键重复触发时不再发请求。
    // 初值 0 = 「推荐」的首屏数据由 `LaunchedEffect(Unit) → reloadHome()` 负责。
    var loadedTabIndex by remember { mutableIntStateOf(0) }
    var focusedPosterIndex by remember { mutableIntStateOf(0) }
    // 焦点是否还在「Tab 栏 + 海报区」这一模块里。
    // 《焦点总结》第 ②③ 条的分界线：只有还在模块内，选中的 Tab 才挂红框；
    // 一旦 ↑ 回顶栏，选中态整体休眠，不留任何红框。
    var moduleFocused by remember { mutableStateOf(false) }
    // 换源弹窗（View Dialog）打开状态见上方 `showSourceDialog`（必须在 result 观察者之前声明）。
    // TMDB 手动匹配弹窗（Compose Dialog）打开状态；关闭后的焦点同样由 pendingPosterFocus 交还。
    var showTmdbDialog by remember { mutableStateOf(false) }
    var pendingPosterFocus by remember { mutableIntStateOf(-1) }

    // TMDB 匹配结果：按「站点 key | 条目 id → 完整 TMDB 数据」记一份 override。
    // 【只增强，不替换卡片图】接口返回的竖版海报（`poster.url`）一律保持原样，
    // 这里只替换全屏背景（横图）、信息区简介、剧名行的艺术字 Logo。
    var tmdbOverrides by remember { mutableStateOf<Map<String, HeroTmdbData>>(emptyMap()) }
    // 手动匹配确认后 +1：手动换了条目（或上次详情没拉成功）时强制焦点详情 effect 重跑一次。
    // 用「计数器」而不是在弹窗回调里自己发请求，避免同一个条目被并发请求两遍。
    var tmdbFocusRevision by remember { mutableIntStateOf(0) }
    val posters = remember(homePosters, tmdbOverrides) {
        homePosters.map { poster ->
            val override = tmdbOverrides[poster.posterKey]
            if (override == null) {
                poster
            } else {
                // 只有「有值」才覆盖：TMDB 没给横图 / 译文时，源站的图与简介原样保留。
                poster.copy(
                    backdropUrl = override.highResBackdrop.ifBlank { poster.backdropUrl },
                    synopsis = override.overview.ifBlank { poster.synopsis },
                )
            }
        }
    }

    /**
     * 列表级落库：只写「匹配到的条目 + 提档后的横图」，**不拉详情**。
     *
     * 目的只是让整排卡片的背景先就位（换焦点时不再闪回源站竖图）；详情 / 演员 / Logo 太重，
     * 只在焦点真正停住的条目上拉一次，见下面的 `LaunchedEffect(heroPosterKey, heroTmdbId)`。
     * 已存在 override（手动 / 焦点条目先到）的 key 以先到的为准。
     */
    fun mergeHomeTmdbMatches(matches: List<HomeTmdbMatch?>): Int {
        var merged: Map<String, HeroTmdbData>? = null
        var added = 0
        for (match in matches) {
            if (match == null) continue
            val key = match.poster.posterKey
            if (key in (merged ?: tmdbOverrides)) continue
            merged = (merged ?: tmdbOverrides) + (key to match.item.toHomeTmdbData(manual = match.manual))
            added++
        }
        merged?.let { tmdbOverrides = it }
        return added
    }

    /**
     * 焦点条目的详情落库：补简介 / Logo / 演员，并把同一条数据写进 [HeroTmdbPrefetchCache]。
     *
     * 两道闸门：
     * 1. 期间手动改成了别的 TMDB 条目 → 丢弃（否则「背景是这部、简介是另一部」）；
     * 2. 手动优先于自动 → 自动结果不允许盖掉手动结果。
     * 空值不覆盖已有值：列表级匹配可能已经写过横图，详情里没有就保留它。
     */
    fun mergeHomeTmdbDetail(key: String, data: HeroTmdbData) {
        val current = tmdbOverrides[key]
        if (current != null) {
            if (current.item.tmdbId != data.item.tmdbId) return
            if (current.manual && !data.manual) return
        }
        val merged = data.copy(
            highResBackdrop = data.highResBackdrop.ifBlank { current?.highResBackdrop.orEmpty() },
            manual = current?.manual == true || data.manual,
        )
        tmdbOverrides = tmdbOverrides + (key to merged)
        // 只在「这条数据此刻仍然有效」时写缓存：详情页拿到的一定是当前这条条目的数据。
        HeroTmdbPrefetchCache.put(key, merged)
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

    // ------------------------------------------------------------------
    // TMDB 自动预匹配：给首页每张卡补高清横图 / 简介（不动卡片竖图）
    // ------------------------------------------------------------------
    // 1. 只写内存 override，**不写** `TmdbMatchCache`：自动匹配可能判错，缓存留给手动匹配与详情页，
    //    一次误判不该把整部剧的图片永久钉死；
    // 2. key 用「站点|条目 id」列表而不是 `homePosters` 本身：`Vod` 没有值相等语义，
    //    用列表内容做 key，切 Tab 回来内容没变就不会把整轮搜索再跑一遍；
    // 3. 这一轮**只匹配、不拉详情**：搜索走 1 天磁盘缓存，整排背景先就位；
    //    详情 / 演员 / Logo 太重，只给焦点真正停住的那一张拉（见下面的焦点 effect）。
    val posterKeys = remember(homePosters) { homePosters.map { it.posterKey } }
    LaunchedEffect(posterKeys) {
        if (homePosters.isEmpty()) return@LaunchedEffect
        val config = TmdbConfig.effectiveCurrent()
        if (!config.isReady()) return@LaunchedEffect
        // 已经有 override（手动 / 上一轮自动）的条目直接跳过。
        val targets = homePosters.filter { it.posterKey !in tmdbOverrides && it.vod.name.isNotBlank() }
        if (targets.isEmpty()) return@LaunchedEffect

        val service = TmdbService()
        // 缓存读一次就够（`Setting.getTmdbMatchCache` 是 synchronized 的读-解析，不必每张都做）。
        val cache = withContext(Dispatchers.IO) { Setting.getTmdbMatchCache() }

        var applied = 0
        try {
            coroutineScope {
                // 分块并发：TMDB 没有批量接口，串行 20+ 次要十几秒；
                // 每块 3 个既能压住并发，又让排在前面的（首屏可见）最先出结果、先换图。
                for (chunk in targets.chunked(TMDB_PREMATCH_CONCURRENCY)) {
                    val matches = chunk.map { poster ->
                        async(Dispatchers.IO) { resolveHomeTmdb(poster, cache, service, config) }
                    }.awaitAll()
                    // 逐块落库：切 Tab / 换源取消时，已经完成的块不会白跑。
                    applied += mergeHomeTmdbMatches(matches)
                }
            }
        } catch (e: CancellationException) {
            // 列表已换 / 页面已退出：已完成的块已经生效，原样抛出交给协程取消机制。
            throw e
        } catch (e: TmdbService.AuthException) {
            // Key 失效：整轮停止，不拿同一个坏 Key 把剩下的条目全试一遍。
            Log.w(TAG_HOME, "tmdb 预匹配鉴权失败: ${e.message}")
            return@LaunchedEffect
        } catch (e: Exception) {
            Log.w(TAG_HOME, "tmdb 预匹配失败: ${e.message}")
        }
        if (applied > 0) Log.d(TAG_HOME, "tmdb 预匹配完成: $applied/${targets.size}")
    }

    // ------------------------------------------------------------------
    // 焦点条目：补详情 / 演员 / 简介 / 艺术字 Logo，并预载演员头像
    // ------------------------------------------------------------------
    // 这里**只补详情、不再搜索**：条目由上面的列表级匹配写进 override。
    // effect 的第二个 key 就是「这张卡匹配到的 tmdbId」，匹配一落库 effect 就重跑，
    // 所以既不用等固定时间，也不会把同一次搜索发两遍。
    // 方向键滑过时焦点每 ~50ms 换一张，防抖到停住才发；切走即随 key 变化取消。
    val heroPosterKey = heroPoster?.posterKey
    val heroTmdbId = heroPosterKey?.let { tmdbOverrides[it]?.item?.tmdbId }
    LaunchedEffect(heroPosterKey, heroTmdbId, tmdbFocusRevision) {
        val poster = heroPoster ?: return@LaunchedEffect
        // 列表级匹配还没结果（或这部本来就没匹配上）：没有条目就没有详情可拉，等下一次 key 变化。
        val existing = tmdbOverrides[poster.posterKey] ?: return@LaunchedEffect
        if (existing.detail != null) {
            // 详情已经在内存里（手动匹配拉过 / 刚才停在这张）：不重复请求，只补写跨页缓存
            // （切 Tab 回来时 override 还在，但缓存条目可能已被别的 Tab 挤掉）。
            HeroTmdbPrefetchCache.put(poster.posterKey, existing)
            return@LaunchedEffect
        }
        val config = TmdbConfig.effectiveCurrent()
        if (!config.isReady()) return@LaunchedEffect
        delay(HERO_TMDB_FOCUS_DEBOUNCE_MS)

        val data = try {
            withContext(Dispatchers.IO) {
                loadHomeTmdbData(HomeTmdbMatch(poster, existing.item, existing.manual), TmdbService(), config)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TmdbService.AuthException) {
            Log.w(TAG_HOME, "tmdb 焦点详情鉴权失败: ${e.message}")
            return@LaunchedEffect
        } catch (e: Exception) {
            Log.w(TAG_HOME, "tmdb 焦点详情失败: ${e.message}")
            null
        }
        if (data == null) return@LaunchedEffect
        // 落库走的是同一个 `mergeHomeTmdbDetail`：期间手动改成别的条目时它自己会丢弃这次结果。
        mergeHomeTmdbDetail(poster.posterKey, data)
        // 演员头像一起入队：详情页点进去时头像已经落在 Coil 磁盘缓存里，不用再等一轮。
        preloadHeroAvatars(context, data.cast)
        Log.d(TAG_HOME, "tmdb 焦点详情完成: ${poster.title} cast=${data.cast.size}")
    }

    // ------------------------------------------------------------------
    // 焦点前后各 3 张预加载
    // ------------------------------------------------------------------
    // 用 `posters`（已提档）而不是 `homePosters`：预热源站糊图等于白做。
    // Coil 3.3 已移除请求优先级，请求按入队顺序消费 —— 当前背景第一个入队就是最高优先级。
    DisposableEffect(posters, focusedPosterIndex) {
        val loader = SingletonImageLoader.get(context)
        posters.getOrNull(focusedPosterIndex)?.let { current ->
            enqueuePreload(loader, context, current.backdropUrl, HERO_BACKDROP_PRELOAD_WIDTH)
            // 焦点条目补写跨页缓存：切 Tab 回来时 override 还在，但缓存条目可能已被别的 Tab 挤掉。
            // `put` 会忽略「只匹配到、还没拉详情」的数据，所以这里不会把空壳塞进去。
            tmdbOverrides[current.posterKey]?.let { data ->
                HeroTmdbPrefetchCache.put(current.posterKey, data)
                // 艺术字 Logo 一起预热：否则「文字标题消失 → Logo 才到」中间会空一段。
                enqueuePreload(loader, context, data.logoUrl.orEmpty(), HERO_LOGO_PRELOAD_WIDTH)
            }
        }
        for (offset in -HERO_PRELOAD_RANGE..HERO_PRELOAD_RANGE) {
            val poster = posters.getOrNull(focusedPosterIndex + offset) ?: continue
            enqueuePreload(loader, context, poster.url, HERO_POSTER_PRELOAD_WIDTH)
            // 当前这张的背景上面已经入过队，这里只补前后邻居。
            if (offset != 0) enqueuePreload(loader, context, poster.backdropUrl, HERO_BACKDROP_PRELOAD_WIDTH)
        }
        onDispose { }
    }

    // 任意一排 → 顶栏：默认落在最左侧的第一项（小房子），不记忆上次位置。
    // 同时清掉「模块内」标记：选中的分类 Tab 立刻退成无红框、无红底（规则 ③）。
    fun focusTopBar() {
        moduleFocused = false
        requestFocus(topBarRequesters, 0)
    }

    // 任意一排 → Tab：
    // - 从卡片按 ↑ 上来时必须回到**当前选中的分类**（红框那一个），
    //   而不是「焦点上一次路过的 Tab」；
    // - 焦点即选中之后，选中项 == 最后一次聚焦项，两者天然一致；
    // - 还没进过 Tab 栏（-1）时退回「焦点上次停留项」，再不行落第一项。
    // 注意：这里只移动焦点；选中态由 Tab 自己的 onFocused 更新（焦点即选中）。
    fun focusTab(index: Int? = null) {
        val fallback = if (selectedTabIndex >= 0) selectedTabIndex else rememberedTabIndex
        // 【动态分类】边界跟着真实 Tab 列表走：`homeTabs` 永远至少有「推荐」，
        // 但源分类可能在运行中变少，这里再收敛一次，绝不出现 `coerceIn(0, -1)`。
        val target = (index ?: fallback).coerceIn(0, homeTabs.lastIndex.coerceAtLeast(0))
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
    //
    // [poster] 由长按的那张卡直接传进来：**不能只依赖 [heroPoster]**，
    // 否则首屏数据还没回填 / 焦点刚落上去时 heroPoster 为空，长按会被这里直接吃掉。
    fun changeSource(poster: HeroPoster? = null) {
        // 【排查日志】长按到底有没有触发：没有这一行 = 按键事件根本没进来。
        Log.e("ChangeSource", "长按触发，准备呼出换源弹窗")
        // 关键词 = 当前这张卡对应的**真实剧名**（`Vod.getName()`）。
        // 优先用长按的这张卡自己的剧名；拿不到才退回首页信息区当前海报。
        val target = poster ?: heroPoster
        if (target == null) {
            Log.e("ChangeSource", "换源忽略: 没有海报数据（既没传卡片，heroPoster 也为空）")
            Notify.show("当前没有可换源的剧集")
            return
        }
        val keyword = target.title
        if (keyword.isBlank()) {
            Log.e("ChangeSource", "换源忽略: 剧名为空，无法检索")
            Notify.show("剧名为空，无法换源")
            return
        }
        val currentSiteKey = VodConfig.get().getHome().key
        // 候选源规则集中在 ChangeSourceDialog.candidates：
        // isSearchable + isChangeable + 非隐藏 + 未被屏蔽 + 非当前源，最后按站点健康度排序，
        // 与 TmdbDetailActivity 的换源完全一致。
        var candidates = ChangeSourceDialog.candidates(currentSiteKey)
        Log.e(
            "ChangeSource",
            "换源候选源: size=${candidates.size}, keyword=[$keyword], current=$currentSiteKey",
        )
        if (candidates.isEmpty()) {
            // 【兜底】真实数据下候选源可能因为「当前源不可换 / 全被屏蔽」而空：
            // 直接退回全部可搜索源再试一次，绝不静默失败。
            candidates = VodConfig.get().getSites().filter { it.isSearchable }
            Log.e("ChangeSource", "候选源为空 → 兜底全部可搜索源: size=${candidates.size}")
        }
        if (candidates.isEmpty()) {
            Log.e("ChangeSource", "兜底后仍无可搜索源 → 不弹窗，直接提示")
            Notify.show(context.getString(R.string.play_change_empty))
            return
        }
        // 【排查日志】真正呼出前的入参：候选源数量 + 关键词（首尾空格肉眼不可见，用 [] 包住）。
        Log.e("ChangeSource", "准备呼出: keyword=[$keyword], sites=${candidates.size}")
        pendingPosterFocus = focusedPosterIndex
        showSourceDialog = true
        try {
            ChangeSourceDialog.show(
                context = context,
                // 必须复用 Activity 作用域的 ViewModel：它自带两个单线程 executor，不能按弹窗 new。
                viewModel = siteViewModel,
                sites = candidates,
                keyword = keyword,
                // 全局并发搜索：先按 quick = false 跑，把不支持 quick 的源也搜一遍。
                quick = false,
                onSourceSelected = { vod, site ->
                    // 用户在弹窗里选中了另一部剧：vod 已由 SiteApi.searchContent 盖过 site，
                    // 两者同源，直接用它俩打开该源的真实详情页，不再按剧名二次搜索。
                    Log.d(TAG_HOME, "换源选中: ${site.displayName} / ${vod.name}")
                    // 换源结果是新的竖版海报，没有首页预热过的横图 / 艺术字 / 简介，交空串让详情页
                    // 按 `vod.pic` 裁切、由 TMDB 到达后再补 Logo 与简介。
                    onOpenDetail(vod, site, "", "", "")
                },
                onClosed = {
                    showSourceDialog = false
                    // 弹窗打开期间 `result` 通道归弹窗，本页结果被忽略；关窗后按当前 Tab 重拉一次。
                    refreshCurrentHome()
                },
            )
        } catch (e: Throwable) {
            // 【排查日志】弹窗构造 / show() 抛异常时不再静默闪退：打全栈 + 复位占位状态。
            Log.e("ChangeSource", "弹窗呼出崩溃", e)
            showSourceDialog = false
            Notify.show("换源弹窗打开失败: ${e.message}")
        }
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

    // ------------------------------------------------------------------
    // 焦点即选中之后的**防抖加载**
    // ------------------------------------------------------------------
    // 焦点在 Tab 上快速滑动时，`selectedTabIndex` 会连续变化：
    // - 红色选中态**立刻**跟着焦点走（纯 UI，零成本，不防抖）；
    // - 真正发请求放在这里，停稳 [TAB_SWITCH_DEBOUNCE_MS] 才执行，中间的中间态全部被取消。
    // 再加一层：`loadedTabIndex` 挡住「同一项重复触发」（确定键 / 焦点抖动）。
    // 即便仍有并发请求，`SiteViewModel.execute` 内部也会 cancel 同一 TaskType 的上一轮。
    LaunchedEffect(selectedTabIndex, homeTypes) {
        val index = selectedTabIndex
        if (index < 0 || index == loadedTabIndex) return@LaunchedEffect
        // 停稳才继续：快速滑过多个 Tab 时 effect 会被下一次选中立刻取消，只有最后一格走到下面。
        delay(TAB_SWITCH_DEBOUNCE_MS)
        // 0 = 「推荐」：回到首页首屏数据。
        if (index == 0) {
            loadedTabIndex = 0
            Log.d(TAG_HOME, "焦点切回「推荐」，重新拉首页")
            reloadHome()
            return@LaunchedEffect
        }
        // 【动态分类】index 直接对应 `homeTabs[index]`：type 是源返回的真实 VodType，
        // 不再用「设计稿文案 contains 分类名」去猜，也就不会出现「Tab 能切、请求发不出去」。
        val tab = homeTabs.getOrNull(index) ?: return@LaunchedEffect
        val type = tab.type ?: return@LaunchedEffect
        val site = VodConfig.get().getHome()
        if (site.key.isEmpty()) return@LaunchedEffect
        // 分类还没回来（冷启动配置仍在加载 / 首页请求在途）：**不标记 loaded**，
        // 等 `homeTypes` 变化后本 effect 会自动重跑一次，避免「先滑到 Tab、分类后到」永远对不上。
        if (type.typeId.isBlank()) {
            Log.d(TAG_HOME, "分类 Tab「${tab.label}」缺少 typeId，暂不请求")
            return@LaunchedEffect
        }
        loadedTabIndex = index
        categoryTabActive = true
        Log.d(TAG_HOME, "焦点切分类: ${tab.label} -> ${type.typeName}(${type.typeId})")
        siteViewModel.categoryContent(site.key, type.typeId, "1", true, hashMapOf())
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
                HeroInfoArea(poster = heroPoster, tmdbData = tmdbOverrides[heroPoster.posterKey])
            } else {
                HeroHomeStatus(
                    loading = homeResult == null,
                    message = homeResult?.msg.orEmpty(),
                )
            }
            HeroTabBar(
                state = tabState,
                tabs = homeTabs,
                requesters = tabRequesters,
                // -1 = 还没进过 Tab 栏（此时所有 Tab 都不涂红，只有焦点态才红）。
                selectedIndex = selectedTabIndex,
                // 焦点还在模块内：让「选中但失焦」的 Tab 挂红框（规则 ②）。
                moduleFocused = moduleFocused,
                // 【焦点即选中】焦点移到哪个分类 Tab，就立刻选中哪个：
                // 红色选中态立即跟着焦点走，数据加载由上面的防抖 effect 统一处理。
                onFocused = { index ->
                    rememberedTabIndex = index
                    moduleFocused = true
                    selectedTabIndex = index
                },
                // 确定键保留（兼容原有按键习惯）：焦点即选中之后这里通常已是同一个值。
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
                // 同时把这张卡此刻已经显示出来的横图（TMDB 命中时是 w1920）、艺术字 Logo、
                // TMDB 简介一起带过去：详情页首帧就是完整画面，不闪背景 / 不闪文字标题、不闪小海报。
                onClick = { poster ->
                    val tmdbData = tmdbOverrides[poster.posterKey]
                    onOpenDetail(
                        poster.vod,
                        poster.site,
                        poster.backdropUrl,
                        tmdbData?.logoUrl.orEmpty(),
                        tmdbData?.overview.orEmpty(),
                    )
                },
                // 卡片长按确定键：呼出换源弹窗（见 changeSource）。
                onLongPress = { poster ->
                    Log.e("ChangeSource", "卡片长按触发，准备执行 changeSource")
                    changeSource(poster)
                },
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
        // 确定键写回 override → 首页这张卡的背景 / 简介 / 艺术字 Logo 换成 TMDB 内容
        // （卡片竖图始终是接口原图，不替换）；关闭后由 LaunchedEffect(showTmdbDialog) 把焦点还给长按的那张卡。
        // 真实数据可能为空（切到空源 / 请求失败），此时没有剧名可匹配，弹窗直接不渲染。
        val tmdbPoster = heroPoster
        if (showTmdbDialog && tmdbPoster != null) {
            TmdbMatchDialog(
                initialKeyword = tmdbPoster.title,
                // 真实搜索要带上这条目的源站身份：`TmdbMatcher.search(keyword, vod)` 用它取年份、
                // 季号与备注来排序候选，和自动匹配完全是同一套规则。
                vod = tmdbPoster.vod,
                onConfirm = { item ->
                    val key = tmdbPoster.posterKey
                    // 同一条 TMDB 记录 → 保留已经拉到的简介 / Logo / 演员 / 真横图；
                    // 换了条目 → 旧数据必须整体作废，否则会出现「海报是这部、背景或简介是另一部」。
                    val prev = tmdbOverrides[key]?.takeIf { it.item.tmdbId == item.tmdbId }
                    if (prev == null) HeroTmdbPrefetchCache.remove(key)
                    // 直接用真实搜索结果：标题 / 副标题 / 简介 / 海报 / 横图都是 TMDB 原值。
                    // 横图统一提到 w1920，与自动预匹配（`toHomeTmdbData`）逐字一致，避免同一张图两个档位。
                    val manualData = HeroTmdbData(
                        item = item,
                        detail = prev?.detail,
                        cast = prev?.cast.orEmpty(),
                        overview = prev?.overview.orEmpty().ifBlank { item.overview },
                        logoUrl = prev?.logoUrl,
                        highResBackdrop = upgradeTmdbImage(item.backdropUrl.ifBlank { item.posterUrl }, "w1920"),
                        manual = true,
                    )
                    tmdbOverrides = tmdbOverrides + (key to manualData)
                    // 手动条目本身没有详情：已有就顺手写缓存；没有就交给焦点详情 effect 补拉
                    // （`tmdbFocusRevision` 变化会强制它重跑一次，避免这里与 effect 并发发同一个请求）。
                    HeroTmdbPrefetchCache.put(key, manualData)
                    if (prev?.detail == null) tmdbFocusRevision++
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

/**
 * 横向遮罩（XML `maskLeft`，drawable `bg_hero_mask_h`）。
 *
 * 原版右侧非常透亮：只有左侧压黑托住剧名 / 简介，35% 处已经完全透明，
 * 保证背景大图右半屏（和海报区）不被灰掉。
 */
@Composable
private fun HeroMaskLeft() {
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
 * 纵向遮罩（XML `maskBottom`，drawable `bg_hero_mask_v`）。
 *
 * 上 55% 全亮（原版背景不被压暗），只有底部 25% 渐到黑，托住海报区与信息区。
 */
@Composable
private fun HeroMaskBottom() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0.0f to Color.Transparent,
                    0.55f to Color.Transparent,
                    0.75f to Color.Black.copy(alpha = 0.6f),
                    1.0f to Color.Black.copy(alpha = 0.95f),
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
 * - 剧名：32sp 粗体、固定高 36dp、`gravity=bottom`、阴影 #cc000000 dx2 dy2 radius12（L72-83）；
 *   有 TMDB 艺术字 Logo 时改用 L82-89 的 72dp 高 / 720dp 最宽 Logo 行。
 * - 元数据：14sp、#ccffffff、高 20dp、marginTop 2dp、阴影 #aa000000 1.5/1.5/8（L93-104）。
 * - 简介：12sp、#bbffffff、高 40dp、maxLines 2、lineSpacingExtra 3dp、
 *   外层横向 LinearLayout `weightSum=2` 而子项 `weight=1` → 宽度取一半（L105-125）。
 *
 * 内容随海报焦点变化，用 [AnimatedContent] 做“淡入 + 轻微上滑 / 淡出 + 轻微上移”，
 * 320ms 比背景略快，文字先落位，避免整屏同时变化显得迟滞。
 *
 * `tmdbData` 只影响剧名行：有艺术字 Logo（`logoUrl`）时整行换成 Logo（高 72dp、最宽 720dp、
 * `ContentScale.Fit`、贴底左对齐）并隐藏文字；没有 Logo 时仍显示 32sp 文字，
 * 且**只有手动匹配**才允许覆盖剧名（自动匹配可能判错，见历史决策）。
 */
@Composable
private fun HeroInfoArea(poster: HeroPoster, tmdbData: HeroTmdbData?) {
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
        val logoUrl = tmdbData?.logoUrl.orEmpty()
        val hasLogo = logoUrl.isNotBlank()
        Column(modifier = Modifier.fillMaxWidth()) {
            // 剧名行（原版 `activity_home_v2.xml` L82-89 / `HomeActivityV2.D2(pos)`）：
            // 有 TMDB 艺术字 Logo → 整行 72dp，只显示 Logo、隐藏 32sp 文字；
            // 没有 Logo → 保持 36dp 的 32sp 文字标题。两种状态不混排，各自固定高度。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (hasLogo) HERO_LOGO_HEIGHT else HERO_TITLE_HEIGHT),
                contentAlignment = Alignment.BottomStart,
            ) {
                if (hasLogo) {
                    AsyncImage(
                        model = logoUrl,
                        contentDescription = current.title,
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.BottomStart,
                        modifier = Modifier
                            .height(HERO_LOGO_HEIGHT)
                            .widthIn(max = HERO_LOGO_MAX_WIDTH),
                    )
                } else {
                    Text(
                        // 只有**手动**匹配才覆盖剧名：自动匹配可能判错，名字改错比图糊更严重（历史决策）。
                        text = if (tmdbData?.manual == true) {
                            tmdbData.item.title.ifBlank { current.title }
                        } else {
                            current.title
                        },
                        color = Color.White,
                        fontSize = HERO_TITLE_SP.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(shadow = HERO_TITLE_SHADOW),
                    )
                }
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
 *   **焦点即选中**：焦点经过哪个 Tab，`selectedIndex` 就跟着变成哪个（数据加载由防抖 effect 负责）；
 *   还没进过 Tab 栏时 `selectedIndex = -1`，此时整排都没有红底。
 * - 形状为胶囊（[HERO_TAB_SHAPE]，13dp 圆角，高度 26dp 时刚好半圆）。
 * 背景不做颜色动画，避免过渡帧出现半透明色块。
 * 左右键不交给系统焦点搜索，而是显式 [focusTab]，这样横向滚动与选中态永远同步。
 *
 * [tabs] 由 `homeTypes`（`Result.types`）动态生成：第 0 项固定是「推荐」，
 * 其余每一项都带着源返回的真实分类，所以本组件不再持有任何写死的分类文案。
 */
@Composable
private fun HeroTabBar(
    state: LazyListState,
    tabs: List<HomeTab>,
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
        tabs.forEachIndexed { index, tab ->
            // 用 `item(key = index)` 而不是文案做 key：不同源可能返回同名分类
            // （同名不同 typeId），文案做 key 会直接撞成 "Key was already used" 而崩溃。
            // 下标 key 同时保证「切源后 Tab 数量变化」时组合位置稳定。
            item(key = index) {
                HeroTabChip(
                    label = tab.label,
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
    // 焦点即选中：selected 会跟着焦点一起走，所以「聚焦」与「选中」通常是同一个 Tab；
    // 规则 ② 只在焦点离开 Tab 栏（↑ 回顶栏 / ↓ 进海报行）之后才看得到。
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
    onLongPress: (HeroPoster) -> Unit,
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
                // 长按确定键 = 呼出换源弹窗：把这张卡自己带上去，上层不用再猜 heroPoster。
                onLongPress = { onLongPress(poster) },
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
    // 长按自己计时：遥控器长按会重复发 KeyDown，Compose 的 clickable long-click 在 TV 上不可靠，
    // 所以这里用「首次 KeyDown 起一个 Job → delay → 直接回调」的方式，**完全不等 KeyUp**。
    //
    // 不用 `LaunchedEffect(pressAt)`：那条路只能靠改状态间接触发/取消，
    // 触发后还要先把状态复位（否则弹窗抢走 KeyUp 就永远停在「按着」），时序很难保证。
    // Job 是唯一可靠的取消句柄：KeyUp / 失焦 / 离开组合都能精确取消。
    val scope = rememberCoroutineScope()
    var longPressJob by remember { mutableStateOf<Job?>(null) }
    // 这一次按下是否已经被长按消费：KeyUp 时据此决定「不再触发点击」，并且必须消费掉 KeyUp。
    // 只在下次 `repeatCount == 0` 的 KeyDown 复位 —— 弹窗会抢走 KeyUp，不能指望它来复位。
    var isLongPressTriggered by remember { mutableStateOf(false) }
    var downLongPressJob by remember { mutableStateOf<Job?>(null) }
    var isDownLongPressTriggered by remember { mutableStateOf(false) }

    // 卡片被移出组合（切分类 / 换源后列表重建）时取消计时，绝不让协程打到已销毁的卡片上。
    DisposableEffect(Unit) {
        onDispose {
            longPressJob?.cancel()
            downLongPressJob?.cancel()
        }
    }

    Column(
        modifier = Modifier
            .width(POSTER_CARD_WIDTH)
            .focusRequester(requester)
            .onFocusChanged {
                // 这里只负责把“焦点换到哪一张”通知给上层，焦点样式统一走 interactionSource。
                if (it.isFocused) {
                    onFocused()
                } else {
                    // 失焦只取消计时，**不复位触发标记**：弹窗拿到焦点时 KeyUp 会送到弹窗窗口，
                    // 卡片收不到；若在这里复位，焦点回到卡片后的第一个 KeyUp 会被误判成点击。
                    longPressJob?.cancel()
                    longPressJob = null
                    downLongPressJob?.cancel()
                    downLongPressJob = null
                }
            }
            .onPreviewKeyEvent { event ->
                // 确定键 / 回车完全由卡片接管（见下方 when 的前两个分支），
                // 这样「短按 = 进详情、长按 ≥ 1s = 呼出换源弹窗」才不会和 clickable 冲突。
                when (event.key) {
                    Key.DirectionCenter, Key.Enter -> {
                        if (event.type == KeyEventType.KeyDown) {
                            // 系统长按会重复发 KeyDown：只有首次按下（repeatCount == 0）才起计时，
                            // 其余重复事件一律吞掉，绝不透传给底层 clickable 触发一次点击。
                            if (event.nativeKeyEvent.repeatCount == 0) {
                                isLongPressTriggered = false
                                longPressJob?.cancel()
                                longPressJob = scope.launch {
                                    delay(POSTER_LONG_PRESS_MS)
                                    // 到时**直接**触发，不等 KeyUp：先置标记再回调，
                                    // 上层同步把弹窗标志位置 true，Compose 毫秒级重组把弹窗盖上来。
                                    isLongPressTriggered = true
                                    longPressJob = null
                                    Log.e("ChangeSource", "卡片长按 ${POSTER_LONG_PRESS_MS}ms 触发换源")
                                    onLongPress()
                                }
                            }
                        } else {
                            // KeyUp：先取消计时；已经触发过长按 → 这个 KeyUp 必须被消费，
                            // 绝不能再让卡片响应一次点击（否则弹窗下面会同时打开详情页）。
                            longPressJob?.cancel()
                            longPressJob = null
                            if (!isLongPressTriggered) onClick()
                        }
                        return@onPreviewKeyEvent true
                    }

                    else -> Unit
                }
                // 方向下键必须在下面的「只处理 KeyDown」总闸之前拦一次，
                // 否则 KeyUp 会被直接放行，永远算不出按住了多久。
                if (event.key == Key.DirectionDown) {
                    if (event.type == KeyEventType.KeyDown) {
                        if (event.nativeKeyEvent.repeatCount == 0) {
                            isDownLongPressTriggered = false
                            downLongPressJob?.cancel()
                            downLongPressJob = scope.launch {
                                delay(TMDB_LONG_PRESS_MS)
                                // 同确定键：到时直接呼出 TMDB 手动匹配弹窗，不等松手。
                                isDownLongPressTriggered = true
                                downLongPressJob = null
                                Log.e("ChangeSource", "卡片长按下键 ${TMDB_LONG_PRESS_MS}ms 触发 TMDB 匹配")
                                onLongPressTmdb()
                            }
                        }
                    } else {
                        // 短按 → 原来的下移；长按已经呼出过 TMDB → 这次 KeyUp 什么都不做。
                        downLongPressJob?.cancel()
                        downLongPressJob = null
                        if (!isDownLongPressTriggered) onMoveDown()
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

/** TMDB 预匹配的并发块大小：TMDB 没有批量接口，串行 20+ 次要十几秒。 */
private const val TMDB_PREMATCH_CONCURRENCY = 3

/** 预加载半径：焦点前后各 3 张。 */
private const val HERO_PRELOAD_RANGE = 3

/** 预加载解码宽度：只影响预热那张图占多少内存，不影响磁盘缓存里原始字节的清晰度。 */
private const val HERO_POSTER_PRELOAD_WIDTH = 360
private const val HERO_BACKDROP_PRELOAD_WIDTH = 960

/** 艺术字 Logo 的预热解码宽度：展示上限是 720dp 宽 / 72dp 高，2x 屏按 800px 解码足够，不影响磁盘缓存里原始字节。 */
private const val HERO_LOGO_PRELOAD_WIDTH = 800

/**
 * 焦点详情请求的防抖窗口（毫秒）。
 *
 * 电视端按住方向键时焦点每 ~50ms 换一张卡，若每次焦点变化都发详情请求，滑过一排就是十几次。
 * 220ms 与 Tab 切换的防抖同档：用户停手后到请求发出几乎无感，滑过则一次都不发。
 */
private const val HERO_TMDB_FOCUS_DEBOUNCE_MS = 220L

/** 演员头像的预热解码宽度：详情页头像约 60dp，2x 屏 120~192px，给 192 足够。 */
private const val HERO_AVATAR_PRELOAD_WIDTH = 192

/**
 * 每次最多预热几个演员头像。
 *
 * `TmdbService.cast` 上限 18 人，详情页首屏只看得到前几个；预热太多会跟背景 / 海报抢磁盘与网络。
 */
private const val HERO_CAST_PRELOAD_LIMIT = 12

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

/**
 * 首页 override 的稳定身份：`站点 key + 条目 id`。
 *
 * 不能用剧名：同名不同季 / 重制版会互相覆盖；`Vod.id` 才是站点内唯一键。
 * 少数源不给 id，此时退回剧名，至少不会把所有条目并成同一个 key。
 */
private val HeroPoster.posterKey: String
    get() = heroTmdbKey(site.key, vod.id.ifBlank { vod.name })

// TMDB 图床的宽度档位（从低到高）。`original` 不在表里：原图可能 4K，电视盒子扛不住，
// 也绝不应该在「提档」时被降级成定宽图。
private val TMDB_IMAGE_WIDTHS =
    listOf("w45", "w92", "w154", "w185", "w300", "w342", "w500", "w780", "w1280", "w1920")

/**
 * 把 TMDB 图片地址提到 [targetWidth] 档，**只升不降**。
 *
 * - `/t/p/w342/abc.jpg` → `/t/p/w500/abc.jpg`；
 * - 已经是更大的档位、`original`、或自定义图床（不在 `/t/p/` 下）时原样返回，
 *   用户自己配的 `imageBase` / `backdropBase` 不会被改写。
 *
 * `internal`（而不是 private）：详情页拼演员头像也要用同一套提档规则，
 * 两处表达式必须逐字一致，否则首页预热与详情页显示是两个 URL，磁盘缓存白热。
 */
internal fun upgradeTmdbImage(url: String, targetWidth: String): String {
    if (url.isBlank()) return url
    val marker = "/t/p/"
    val markerIndex = url.indexOf(marker)
    if (markerIndex < 0) return url
    val sizeStart = markerIndex + marker.length
    val sizeEnd = url.indexOf('/', sizeStart)
    if (sizeEnd < 0) return url
    val current = TMDB_IMAGE_WIDTHS.indexOf(url.substring(sizeStart, sizeEnd))
    val target = TMDB_IMAGE_WIDTHS.indexOf(targetWidth)
    if (current < 0 || target < 0 || current >= target) return url
    return url.substring(0, sizeStart) + targetWidth + url.substring(sizeEnd)
}

/**
 * 一条已解析好的首页 TMDB 匹配：海报卡 → 条目，[manual] 表示命中来自手动匹配缓存。
 *
 * `manual` 一路带到详情页（`TmdbDetailState.Ready.manual`）：只有手动命中才允许覆盖剧名。
 */
private data class HomeTmdbMatch(
    val poster: HeroPoster,
    val item: TmdbItem,
    val manual: Boolean,
)

/**
 * 单条首页海报的 TMDB 解析：**手动缓存 → 自动缓存 → 在线搜索**，顺序与详情页一致。
 *
 * 无 Key / 该源被排除 / 搜不到时返回 null，调用方保持源站图片不变（不改变任何原有行为）。
 */
private suspend fun resolveHomeTmdb(
    poster: HeroPoster,
    cache: TmdbMatchCache,
    service: TmdbService,
    config: TmdbConfig,
): HomeTmdbMatch? {
    val siteKey = poster.site.key
    val vod = poster.vod
    if (!TmdbSitePolicy.isEnabled(config, siteKey, vod.id)) return null
    return try {
        val manual = cache.findManual(siteKey, vod.id, vod.name)
        val item = manual
            ?: cache.find(siteKey, vod.id, vod.name)
            ?: TmdbMatcher(service, config).searchAndMatch(vod.name, vod)
        if (item == null) null else HomeTmdbMatch(poster, item, manual != null)
    } catch (e: CancellationException) {
        throw e
    } catch (e: TmdbService.AuthException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG_HOME, "tmdb 预匹配失败: ${vod.name} | ${e.message}")
        null
    }
}

/**
 * 焦点条目的详情补全：补详情 / 演员 / 译文简介 / 艺术字 Logo。
 *
 * 只发**一次**请求：`detail` 走 7 天磁盘缓存（`includeRelated = false`，少带推荐 / 相似，响应体小一半），
 * `cast` 与 `translatedOverview` 都在这份 JSON 上本地解析，不再发网络。
 *
 * 失败返回 null：首页继续用已匹配到的横图 + 源站简介，详情页下次进来再走原来的「缓存 → 搜索」链路。
 */
private suspend fun loadHomeTmdbData(
    match: HomeTmdbMatch,
    service: TmdbService,
    config: TmdbConfig,
): HeroTmdbData? = try {
    val detail = service.detail(match.item, config, false)
    match.item.toHomeTmdbData(
        detail = detail,
        cast = service.cast(detail, config),
        overview = service.translatedOverview(detail, config).orEmpty(),
        // 复用详情页的提取规则（中文优先、否则第一张有 file_path 的），避免两处逻辑漂移。
        logoUrl = extractTmdbLogo(detail, config.getImageBase()),
        manual = match.manual,
    )
} catch (e: CancellationException) {
    throw e
} catch (e: TmdbService.AuthException) {
    throw e
} catch (e: Exception) {
    Log.w(TAG_HOME, "tmdb 详情预取失败: ${match.poster.title} | ${e.message}")
    null
}

/**
 * `TmdbItem` → 首页 override 用的完整数据（[HeroTmdbData]）。
 *
 * 只提档横图：w780 → w1920（TMDB 图床实测支持 w1920）；`original` / 自定义图床原样保留，
 * 绝不改写用户自己配的图床。竖版海报**不提档也不替换**：卡片图始终是接口返回的那张。
 * 没有横图时退回竖图当全屏背景（与首页原来「竖图裁 16:9」的行为一致）。
 */
private fun TmdbItem.toHomeTmdbData(
    detail: JsonObject? = null,
    cast: List<TmdbPerson> = emptyList(),
    overview: String = "",
    logoUrl: String? = null,
    manual: Boolean = false,
): HeroTmdbData = HeroTmdbData(
    item = this,
    detail = detail,
    cast = cast,
    overview = overview,
    logoUrl = logoUrl,
    highResBackdrop = upgradeTmdbImage(backdropUrl.ifBlank { posterUrl }, "w1920"),
    manual = manual,
)

/**
 * 预加载一张图：只入队、不等待。
 *
 * [width] 是**解码尺寸上限**，不是下载尺寸：Coil 的磁盘缓存存的是网络原始字节，
 * 与解码尺寸无关，所以小尺寸预热不会让真正显示时变糊，只是少占内存。
 */
private fun enqueuePreload(loader: ImageLoader, context: Context, url: String, width: Int) {
    if (url.isBlank()) return
    loader.enqueue(
        ImageRequest.Builder(context)
            .data(url)
            .size(width)
            .build()
    )
}

/**
 * 预载焦点条目的演员头像。
 *
 * 详情页演员卡是 [HERO_AVATAR_PRELOAD_WIDTH] 量级（60dp 上下，电视 2x 屏约 192px），
 * 所以解码尺寸给到 192 就够；只入队、不等待，失败也不影响首页。
 *
 * URL 走 [homeCastAvatarUrl]，与详情页 `mergeTmdb` 的 `avatarUrl` 是**同一个表达式**：
 * 两边只要有一个字不同，用户点进去时就会重新下一张，预热等于白做。
 */
private fun preloadHeroAvatars(context: Context, cast: List<TmdbPerson>) {
    if (cast.isEmpty()) return
    val loader = SingletonImageLoader.get(context)
    var queued = 0
    for (person in cast) {
        if (queued >= HERO_CAST_PRELOAD_LIMIT) break
        val url = homeCastAvatarUrl(person.profileUrl)
        if (url.isBlank()) continue
        enqueuePreload(loader, context, url, HERO_AVATAR_PRELOAD_WIDTH)
        queued++
    }
}

/** 演员头像地址：统一提到 w342（与详情页 `mergeTmdb` 的 `avatarUrl` 逐字一致）。 */
private fun homeCastAvatarUrl(profileUrl: String): String =
    upgradeTmdbImage(profileUrl.orEmpty(), "w342")

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

// 分类 Tab 的固定首项：「推荐」= 首页首屏数据（`homeContent`）；其余项全部来自 `Result.types`。
private const val TAB_RECOMMEND = "推荐"

// 动态分类 Tab 的 FocusRequester 池容量：源一般返回 3~20 个分类，
// 池子固定不随列表重建，切源 / 分类后到时焦点都不会因为 requester 重建而掉下来。
private const val TAB_REQUESTER_POOL = 32

/**
 * 首页分类 Tab 的一项。
 *
 * [type] 为 null 表示「推荐」（首页首屏 `homeContent`）；其余项直接持有源返回的分类，
 * 于是 `index → 分类` 是硬绑定：请求时直接用 `type.typeId`，不再用文案去猜。
 */
private data class HomeTab(val label: String, val type: VodType?)

/**
 * 「焦点即选中」的请求防抖窗口（毫秒）。
 *
 * 电视端按住方向键时焦点会以 ~50ms 的间隔连续跳格，若每次 `selectedTabIndex` 变化都立刻发请求，
 * 滑过 6 个 Tab 就是 6 个并发请求。这里只拦**网络请求**：红色选中态是纯 UI，仍然立刻跟随焦点。
 * 220ms 略大于系统长按连发间隔，用户停手后到请求发出几乎无感。
 */
private const val TAB_SWITCH_DEBOUNCE_MS = 220L

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
// 艺术字 Logo：原版 `activity_home_v2.xml` L82-89 —— 高 72dp、最宽 720dp、贴底左对齐、Fit 等比。
// 没有 Logo 时剧名行回落到文字高度 36dp，不能把文字行也撑到 72dp（会把元数据/简介整体下推）。
private val HERO_LOGO_HEIGHT = 72.dp
private val HERO_LOGO_MAX_WIDTH = 720.dp
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

// 长按阈值：按住 1s 才当成「长按确定键 = 换源」，短按仍是进详情。
// 用 1s 而不是 500ms：遥控器长按会先重复发 KeyDown，阈值太短容易在「想短按」时误触发换源。
private const val POSTER_LONG_PRESS_MS = 1000L

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
