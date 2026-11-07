package com.fongmi.android.tv.ui.activity

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color as AndroidColor
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.dispose
import coil3.load
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.fongmi.android.tv.App
import com.fongmi.android.tv.BuildConfig
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
import com.fongmi.android.tv.ui.hero.HeroPosterCard
import com.fongmi.android.tv.ui.hero.HeroSourceEntry
import com.fongmi.android.tv.ui.hero.SourceSidebar
import com.fongmi.android.tv.ui.hero.SourceSidebarMode
import com.fongmi.android.tv.ui.hero.TmdbMatchDialog
import com.fongmi.android.tv.ui.hero.openHeroDetailOrFolder
import com.fongmi.android.tv.utils.Notify
import com.fongmi.android.tv.utils.PushParser
import com.google.gson.JsonObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    /**
     * TMDB 评分文案（如 `8.6`）；空串 = 没有评分。
     *
     * 与 [year] 一起只用于元数据行：原版 `HomeActivityV2` 的 `D2(pos)` 会把
     * `年份 · 分类 · TMDB 评分` 拼在同一行，缓存命中时第一帧就要有，不能等详情回来。
     */
    val rating: String = "",
    /** 上映 / 首播年份（从 `TmdbItem.subtitle` 里的 4 位年份抓）；空串 = 未知。 */
    val year: String = "",
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
        // Logo 再单独落一份极简磁盘缓存：`TmdbMatchCache.Entry` 没有 logo 字段，
        // 冷启动第一帧只能靠这份键值把艺术字直接画出来（详见 [HeroLogoCache]）。
        HeroLogoCache.save(key, data.logoUrl.orEmpty())
    }

    /** 取出并删除：详情页用过一次就作废，避免用户手动改过匹配后仍拿到旧条目。 */
    fun take(key: String): HeroTmdbData? = entries.remove(key)

    /**
     * 只读命中、**不删除**。
     *
     * 首页首帧要用它把艺术字 Logo 直接画出来（磁盘匹配缓存里没有 Logo 字段），
     * 这条数据随后还要交给详情页 `take()`，所以首页只能 peek，不能把条目取走。
     */
    fun peek(key: String): HeroTmdbData? = entries[key]

    /** 显式作废（手动改成别的 TMDB 条目时调用）。 */
    fun remove(key: String) {
        if (key.isNotBlank()) entries.remove(key)
        HeroLogoCache.remove(key)
    }
}

/**
 * 首页艺术字 Logo 的极简磁盘缓存：`posterKey → logoUrl`。
 *
 * `TmdbMatchCache.Entry` 只有 tmdbId / title / 横图 / 评分等字段，**没有 logoUrl**
 * （见 `TmdbMatchCache.java`）。所以冷启动时即使匹配命中磁盘缓存，Logo 也只能等联网详情回来，
 * 视觉上就是「先一行 32sp 文字，几百毫秒后再换成艺术字」。
 *
 * 这里单独存一份只读展示用的键值：
 * - 首次访问 `memory` 时**同步**把整个 prefs 读进内存（SharedPreferences 本来也是一次性解析 XML），
 *   之后 `snapshot()` 全是内存查表，冷启动第一帧没有异步空窗；
 * - 写入点只有 [HeroTmdbPrefetchCache.put]（拿到带 Logo 的完整详情时才写），频率极低；
 * - 写内存 + `apply()` 异步落盘：当前进程立刻可读，又不阻塞焦点切换；
 * - 条数封顶 [MAX_ENTRIES]，超出时丢任意一条，避免长期使用后无限增长。
 */
private object HeroLogoCache {

    private const val PREFS_NAME = "hero_logo_cache"
    private const val MAX_ENTRIES = 200

    private val prefs by lazy { App.get().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    /**
     * 进程内镜像：`posterKey → logoUrl`。
     *
     * 用 [ConcurrentHashMap]：详情协程可能在 IO 线程写，首帧 / 焦点切换在主线程读。
     * `snapshot()` 直接返回这个活映射，后续 [save] 写入当前进程立即可见。
     */
    private val memory: ConcurrentHashMap<String, String> by lazy {
        ConcurrentHashMap<String, String>().apply {
            prefs.all.forEach { (key, value) ->
                if (value is String && value.isNotBlank()) put(key, value)
            }
        }
    }

    /** 首帧构建展示数据专用：一次返回全部 Logo，调用方只做内存查表。 */
    fun snapshot(): Map<String, String> = memory

    fun save(posterKey: String, logoUrl: String) {
        if (posterKey.isBlank() || logoUrl.isBlank()) return
        // 同一个 URL 不重复写：焦点来回移动时 `put` 会被反复调用。
        if (memory[posterKey] == logoUrl) return
        if (memory.size >= MAX_ENTRIES && !memory.containsKey(posterKey)) {
            memory.keys.firstOrNull()?.let { remove(it) }
        }
        // 先写内存：当前进程立刻能读到；`apply()` 异步落盘，不阻塞主线程。
        memory[posterKey] = logoUrl
        prefs.edit().putString(posterKey, logoUrl).apply()
    }

    /** 手动改成别的 TMDB 条目时同步作废：旧 Logo 是上一部作品的，不能留到下一次冷启动。 */
    fun remove(posterKey: String) {
        if (posterKey.isBlank()) return
        memory.remove(posterKey)
        if (prefs.contains(posterKey)) prefs.edit().remove(posterKey).apply()
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
 * 1. [HeroBackdrop]：全屏背景大图（XML `heroBg`），旧图托底 + 新图淡入切换。
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
 * - 海报行 ←/→ 行内移动；首尾各留半屏 padding，第一张静止时就在屏幕正中；
 *   前 4 张（index 0..3）整行不动，第 5 张（index = 4）起继续把焦点卡滚到正中。
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
    // 海报行是原生 RecyclerView（AndroidView 承载）：Compose 侧只留一个握柄，
    // 跨区移动 / 弹窗关闭还焦点都按 index 直接请求，不再维护一整套 posterRequesters。
    val posterRow = remember { HeroPosterRowHost() }
    val tabState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 【冷启动第一帧】先把 Logo 磁盘缓存一次性读进内存（首次访问同步解析整个 prefs XML）。
    // 必须发生在 `syncCache` / `posters` 构建与首帧绘制之前：这样每张卡都是内存查表，
    // 不会出现「先文字、几百毫秒后再换成艺术字」的异步空窗。
    val syncLogoMap = remember { HeroLogoCache.snapshot() }

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

    // 【首帧同步命中】列表一成型就把 `TmdbMatchCache` 读一遍（原版 `TmdbCache` 的第一帧直读）：
    // 命中则横图 / 评分 / 年份 / 手动剧名当场就位，用户看不到「源站竖图 → TMDB 横图」的硬切，
    // 也不会先闪一行没有评分的元数据。读盘只做一次（`remember(homePosters)`），不是每张卡一次。
    val syncCache = remember(homePosters) { readHeroSyncCache(homePosters, syncLogoMap) }

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
    // 三级优先级：网络 / 手动 override（最新）> 首帧同步缓存（磁盘）> 源站原值。
    // 同步缓存只参与展示，**不写进 `tmdbOverrides`**：否则焦点预取会误以为「已解析」而跳过，
    // 详情页也拿不到 `detail`；这里只负责让第一帧就有横图 / 评分 / 手动剧名。
    val posters = remember(homePosters, tmdbOverrides, syncCache, syncLogoMap) {
        homePosters.map { poster ->
            val data = tmdbOverrides[poster.posterKey] ?: syncCache.matches[poster.posterKey]
            // 【Logo 独立兜底】艺术字缓存按 `posterKey` 存，手动换 TMDB 条目时已同步清掉；
            // 所以即使匹配缓存没命中（`data == null`），只要 Logo 缓存在，第一帧也能画艺术字。
            val logoUrl = data?.logoUrl?.takeIf { it.isNotBlank() }
                ?: syncLogoMap[poster.posterKey]?.takeIf { it.isNotBlank() }
            if (data == null && logoUrl == null) {
                poster
            } else {
                // 只有「有值」才覆盖：TMDB 没给横图 / 译文时，源站的图与简介原样保留。
                // 注意：`poster.title` 保持源站剧名不动 —— 换源弹窗的关键词必须是真实站源标题
                // （见 `changeSource`）。手动词名由 `HeroInfoArea` 从 `tmdbData.manual` 直接取，
                // 首帧同步缓存里已经带着它，展示效果一致，但不会污染换源检索。
                poster.copy(
                    logoUrl = logoUrl,
                    // 评分 / 年份来自 TMDB；备注仍取源站（「更新至12集」这类信息 TMDB 没有）。
                    meta = data?.let { buildMeta(poster.vod, it.year, it.rating) } ?: poster.meta,
                    backdropUrl = data?.highResBackdrop?.takeIf { it.isNotBlank() } ?: poster.backdropUrl,
                    synopsis = data?.overview?.takeIf { it.isNotBlank() } ?: poster.synopsis,
                )
            }
        }
    }

    /**
     * 列表级落库：只写「匹配到的条目 + 提档后的横图」，**不拉详情**。
     *
     * 目的只是让整排卡片的背景先就位（换焦点时不再闪回源站竖图）；详情 / 演员 / Logo 太重，
     * 只在焦点真正停住的条目上拉一次，见下面的焦点驱动预取。
     * 已存在 override（手动 / 焦点条目先到）的 key 以先到的为准。
     */
    fun mergeHomeTmdbMatches(matches: List<HomeTmdbMatch?>): Int {
        var merged: Map<String, HeroTmdbData>? = null
        var added = 0
        for (match in matches) {
            if (match == null) continue
            val key = match.poster.posterKey
            if (key in (merged ?: tmdbOverrides)) continue
            val base = match.item.toHomeTmdbData(manual = match.manual)
            // 列表级匹配没有详情，拿不到 logo。如果首帧同步缓存里**同一条 TMDB 记录**已有艺术字，
            // 先沿用它 —— 否则 override 一落库就会把缓存里的 Logo 遮掉，焦点切过去先闪一行文字。
            val cached = syncCache.matches[key]
            val data = if (
                base.logoUrl.isNullOrBlank() &&
                cached?.logoUrl?.isNotBlank() == true &&
                cached.item.tmdbId == base.item.tmdbId
            ) {
                base.copy(logoUrl = cached.logoUrl)
            } else {
                base
            }
            merged = (merged ?: tmdbOverrides) + (key to data)
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
    // TMDB 焦点驱动预取：当前焦点 + 左右各 3 张，滑过即取消
    // ------------------------------------------------------------------
    // 不再全局遍历整页：20+ 条同时搜索会互相抢连接，真正要看的当前这一张反而排最后。
    // 现在只做两件事：
    // 1. 当前焦点：已有 override 就直接拉详情；没有就先搜索再拉详情（简介 / 演员 / 艺术字 Logo）；
    // 2. 左右各 3 张：500ms 后只做搜索级匹配，让横图先就位；详情等真正聚焦到它时再补。
    // 焦点一变，旧 LaunchedEffect 立即取消，不会把上一条的搜索结果写进新条目。
    // key 仍用「站点|条目 id」列表而不是 `homePosters`：`Vod` 没有值相等语义。
    val posterKeys = remember(homePosters) { homePosters.map { it.posterKey } }
    val heroPosterKey = heroPoster?.posterKey
    LaunchedEffect(heroPosterKey, posterKeys, tmdbFocusRevision) {
        val poster = heroPoster ?: return@LaunchedEffect
        val config = TmdbConfig.effectiveCurrent()
        if (!config.isReady()) return@LaunchedEffect
        // 方向键滑过一排时焦点每 ~50ms 换一张：停手 300ms 才发，滑过不发。
        delay(HERO_TMDB_FOCUS_DEBOUNCE_MS)
        try {
            coroutineScope {
                // 首帧同步读出来的那份缓存直接复用，避免列表成型后立刻再把同一份 JSON 解一遍。
                val cache = syncCache.raw ?: withContext(Dispatchers.IO) { Setting.getTmdbMatchCache() }
                val service = TmdbService()
                val focusIndex = posters.indexOfFirst { it.posterKey == poster.posterKey }

                // 1) 当前焦点：搜索（已有 override 就直接用）→ 详情，优先占满连接。
                launch {
                    val existing = tmdbOverrides[poster.posterKey]
                    if (existing?.detail != null) {
                        // 详情已经在内存（手动匹配拉过 / 刚才停在这张）：不重复请求，只补写跨页缓存
                        // （切 Tab 回来时 override 还在，但缓存条目可能已被别的 Tab 挤掉）。
                        HeroTmdbPrefetchCache.put(poster.posterKey, existing)
                    } else if (poster.vod.name.isNotBlank()) {
                        val match = existing?.let { HomeTmdbMatch(poster, it.item, it.manual) }
                            ?: withContext(Dispatchers.IO) { resolveHomeTmdb(poster, cache, service, config) }
                        if (match != null) {
                            // Compose 状态只能回主线程改；结果一到就落库，排在前面的条目先换背景。
                            withContext(Dispatchers.Main.immediate) {
                                mergeHomeTmdbMatches(listOf(match))
                            }
                            val data = withContext(Dispatchers.IO) { loadHomeTmdbData(match, service, config) }
                            if (data != null) {
                                // 落库走同一个 `mergeHomeTmdbDetail`：期间手动改成别的条目时它自己会丢弃这次结果。
                                withContext(Dispatchers.Main.immediate) {
                                    mergeHomeTmdbDetail(poster.posterKey, data)
                                }
                                // 只更新首页数据，**不预载演员头像**：详情页进去自己会加载。
                                Log.d(TAG_HOME, "tmdb 焦点详情完成: ${poster.title}")
                            }
                        }
                    }
                }

                // 2) 邻居：当前焦点先跑；500ms 后按「右 1 → 左 1 → 右 2 → 左 2 …」严格串行，
                //    每张之间再等 500ms。只补搜索级匹配（`resolveHomeTmdb` 不做详情 / 演员），
                //    绝不和当前焦点抢连接。用户继续移动焦点时整个 effect 被取消，两个 launch 一起停。
                if (focusIndex >= 0) {
                    launch {
                        delay(HERO_TMDB_NEIGHBOR_DELAY_MS)
                        for (offset in 1..HERO_PRELOAD_RANGE) {
                            for (index in intArrayOf(focusIndex + offset, focusIndex - offset)) {
                                val neighbor = posters.getOrNull(index)
                                if (neighbor != null &&
                                    neighbor.vod.name.isNotBlank() &&
                                    tmdbOverrides[neighbor.posterKey] == null
                                ) {
                                    val match = withContext(Dispatchers.IO) {
                                        resolveHomeTmdb(neighbor, cache, service, config)
                                    }
                                    if (match != null) {
                                        withContext(Dispatchers.Main.immediate) {
                                            mergeHomeTmdbMatches(listOf(match))
                                        }
                                    }
                                }
                                // 强制排队：无论这一张是命中缓存、跳过还是失败，都停顿 500ms 再拉下一张。
                                delay(HERO_TMDB_NEIGHBOR_DELAY_MS)
                            }
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            // 焦点已换 / 页面已退出：已完成的条目已经生效，原样抛出交给协程取消机制。
            throw e
        } catch (e: TmdbService.AuthException) {
            // Key 失效：停止本轮，不拿同一个坏 Key 把邻居再试一遍。
            Log.w(TAG_HOME, "tmdb 焦点预取鉴权失败: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG_HOME, "tmdb 焦点预取失败: ${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // 图片预加载窗口：焦点前后各 3 张，窗口外**绝不**入队
    // ------------------------------------------------------------------
    // 用 `posters`（已提档）而不是 `homePosters`：预热源站糊图等于白做。
    // Coil 3.3 已移除请求优先级，请求按入队顺序消费 —— 当前背景第一个入队就是最高优先级。
    // 原版 `PosterCache` 同样只预热 `[i - 2, i + 2]` 的窗口，滑过整页不会把几十张图全排进队列。
    LaunchedEffect(posters, focusedPosterIndex) {
        if (posters.isEmpty()) return@LaunchedEffect
        val loader = SingletonImageLoader.get(context)
        val currentIndex = focusedPosterIndex.coerceIn(0, posters.lastIndex)
        val start = maxOf(0, currentIndex - HERO_PRELOAD_RANGE)
        val end = minOf(posters.lastIndex, currentIndex + HERO_PRELOAD_RANGE)
        // 焦点条目最先入队：当前这一屏的背景 + 艺术字 Logo，必须比邻居更早出图。
        val current = posters[currentIndex]
        enqueuePreload(loader, context, current.backdropUrl, HERO_BACKDROP_PRELOAD_WIDTH)
        // 焦点条目补写跨页缓存：切 Tab 回来时 override 还在，但缓存条目可能已被别的 Tab 挤掉。
        // `put` 会忽略「只匹配到、还没拉详情」的数据，所以这里不会把空壳塞进去。
        tmdbOverrides[current.posterKey]?.let { data ->
            HeroTmdbPrefetchCache.put(current.posterKey, data)
        }
        // 艺术字 Logo 一起预热（同步缓存命中的也要）：否则「文字标题消失 → Logo 才到」中间会空一段。
        enqueuePreload(loader, context, current.logoUrl.orEmpty(), HERO_LOGO_PRELOAD_WIDTH)
        // 只遍历窗口内的下标：范围外的一张都不 enqueue。
        for (i in start..end) {
            val poster = posters[i]
            enqueuePreload(loader, context, poster.url, HERO_POSTER_PRELOAD_WIDTH)
            // 当前这张的背景上面已经入过队，这里只补前后邻居。
            if (i != currentIndex) enqueuePreload(loader, context, poster.backdropUrl, HERO_BACKDROP_PRELOAD_WIDTH)
        }
    }

    // 任意一排 → 顶栏：默认落在最左侧的第一项（小房子），不记忆上次位置。
    // 同时清掉「模块内」标记：选中的分类 Tab 立刻退成无红框、无红底（规则 ③）。
    fun focusTopBar() {
        // 【来源守卫】只有焦点确实还停在「Tab 栏 + 海报区」这个模块里，才把焦点交给顶栏。
        //
        // 这里是**唯一**会把焦点无条件送给「小房子」的入口：一旦被空态回收、弹窗关闭、
        // 数据刷新之类的旁路误触发，用户就会从海报行被直接甩到顶栏第一项。
        // 启动（LaunchedEffect(Unit)）、空态回收、侧边栏 onClosed 都走各自的
        // `requestFocus(topBarRequesters, 0)`，不经过本函数，所以这道守卫不会挡住它们。
        if (!moduleFocused) return
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

    // → 海报行：从别的排切入时用默认参数 index = 0，第一张贴左 24dp。
    // 滚动规则统一在 HeroPosterRecyclerView 里：
    // - 第 1~4 张（index 0..3）：整行在最左，第一张贴左 24dp；
    // - 第 5 张（index 4）起：把焦点卡平滑滚到屏幕正中。
    fun focusPoster(index: Int = 0) {
        // 真实数据可能为空（首次加载中 / 接口源返回空）：空列表时 `coerceIn(0, -1)` 会直接抛
        // IllegalArgumentException，所以必须先挡掉，焦点留在原地。
        if (posters.isEmpty()) return
        val target = index.coerceIn(0, posters.lastIndex)
        if (posterRow.focusPoster(target)) return
        // 目标卡还没布局（首帧 / 刚切分类就要跳到靠后的卡片）：
        // focusPoster 已把它瞬间滚进屏幕，RecyclerView 要下一帧才完成布局，这里多等几帧再抢。
        scope.launch {
            repeat(5) {
                withFrameNanos { }
                if (posterRow.focusPoster(target)) return@launch
            }
        }
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
            if (posterRow.focusPoster(target)) return@LaunchedEffect
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
            if (posterRow.focusPoster(target)) return@LaunchedEffect
            withFrameNanos { }
        }
    }

    // 启动时焦点落在“小房子”；侧边栏关闭后的焦点由 onClosed 回调交还。
    LaunchedEffect(Unit) {
        requestFocus(topBarRequesters, 0)
    }

    // 切分类后海报行回到最左，保证“Tab ↓ 落到第一张”和视觉一致。
    LaunchedEffect(selectedTabIndex) {
        posterRow.scrollToStart()
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
    // 这里在**窗口真的没有任何焦点持有者**时收回小房子，保证任何时候都有明确的焦点落点。
    //
    // 【守卫边界，必须知道】Compose 持有焦点时 `findFocus()` 返回的是 `AndroidComposeView`
    // （非 null），所以本条**只能**堵「焦点真空」这一种边角场景：顶栏 / Tab / 海报卡
    // 持有焦点时一律不动。否则「方向键切到空分类 → 列表变空 → 焦点被强行搬到小房子」
    // 就是一条稳定复现的乱跳路径（本 bug 的根因之一）。
    val composeRootView = LocalView.current
    LaunchedEffect(posters.isEmpty()) {
        if (!posters.isEmpty()) return@LaunchedEffect
        withFrameNanos { }
        if (composeRootView.rootView.findFocus() != null) return@LaunchedEffect
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
                HeroInfoArea(
                    poster = heroPoster,
                    // 网络 / 手动 override 优先；还没回来时用首帧同步缓存里的 Logo / 手动词名，
                    // 这样艺术字在第一帧就能画出来，不会先闪一行 32sp 文字再换成 Logo。
                    tmdbData = tmdbOverrides[heroPoster.posterKey] ?: syncCache.matches[heroPoster.posterKey],
                )
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
                row = posterRow,
                focusedPosterIndex = focusedPosterIndex,
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
                        // Logo 同样兜底：override 没有时用海报上带下来的同步缓存值。
                        tmdbData?.logoUrl ?: poster.logoUrl.orEmpty(),
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
                // 左右移动交给 RecyclerView 自己的焦点搜索（原生 LinearLayoutManager 会正确滚到下一张），
                // 方向下键在卡片里被消费：短按不动（下面没有可聚焦控件）、长按 1.5s 呼出 TMDB 匹配。
                onMoveUp = { focusTab() },
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
                        // 元数据行与列表预匹配保持同一套规则：手动换了条目，评分 / 年份当场跟着换。
                        rating = item.ratingText(),
                        year = item.yearText(),
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
 * 逆向原版 `HeroImageView` 是**两层 ImageView**：旧图一直铺在底层，新图真正加载成功后才在
 * 它上面淡入，完全就位才丢掉旧图。Compose 的 `Crossfade` 只有固定时长窗口，网络慢时
 * 新图还没解码完、旧图已经淡出，中间就会露黑帧 —— 这里改成显式双图层托底：
 *
 * 1. 底层：最近一张**已成功显示**的图（同一请求参数，Coil 内存缓存命中，几乎零成本）；
 * 2. 顶层：目标图，`onSuccess` 之前 `alpha = 0`，成功后 800ms 淡入；
 * 3. 淡入结束才释放底层，全屏大图不会长期同时驻留两张。
 *
 * 左侧文字仍用 320ms，先落位，背景再慢慢跟上。
 */
@Composable
private fun HeroBackdrop(url: String) {
    var shownUrl by remember { mutableStateOf(url) }    // 目标：当前应该显示的图
    var previousUrl by remember { mutableStateOf("") }   // 托底：最近一张已经成功显示的图
    var loadedUrl by remember { mutableStateOf("") }     // Coil 回调：最新成功加载的 URL

    LaunchedEffect(url) {
        if (url.isBlank()) {
            // 与改造前一致：没有海报时背景整块为空，不把上一部剧的图留在屏幕上。
            shownUrl = ""
            previousUrl = ""
            loadedUrl = ""
            return@LaunchedEffect
        }
        if (url == shownUrl) return@LaunchedEffect
        // 只拿「已成功显示过」的图托底，绝不拿一张还在加载的 URL 当底图。
        previousUrl = loadedUrl
        shownUrl = url
    }

    val current = shownUrl
    // 只有「确实存在另一张已加载的旧图」才需要从 0 淡入；首屏 / 切回同一张时直接 1，
    // 交给 Coil 自己的 500ms 淡入，避免多等 800ms，也避免切回时中间空一帧。
    // 每换一个 URL 就换一只 Animatable：新图的第一帧 alpha 就是 0，
    // 不会出现「内存缓存命中 → 先整张闪一下 → 再从头淡入」。
    val hasPrevious = previousUrl.isNotBlank() && previousUrl != current
    val fade = remember(current) { Animatable(if (hasPrevious) 0f else 1f) }

    LaunchedEffect(current, loadedUrl) {
        if (current.isBlank() || loadedUrl != current) return@LaunchedEffect
        fade.animateTo(1f, tween(durationMillis = HERO_BACKDROP_FADE_MS))
        // 新图已经完全盖住旧图：释放底层，避免两张全屏图长期同时驻留。
        previousUrl = ""
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (hasPrevious) {
            AsyncImage(
                model = rememberHeroImageModel(previousUrl),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (current.isNotBlank()) {
            AsyncImage(
                model = rememberHeroImageModel(current),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                // 新图成功之前 alpha 恒为 0：底层旧图始终可见，不会出现空白帧。
                onSuccess = { loadedUrl = current },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = fade.value },
            )
        }
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
 *   有 TMDB 艺术字 Logo 时改用 L82-89 的 Logo 行（本工程放大到 96dp 高 / 720dp 最宽）。
 * - 元数据：14sp、#ccffffff、高 20dp、marginTop 2dp、阴影 #aa000000 1.5/1.5/8（L93-104）。
 * - 简介：12sp、#bbffffff、高 40dp、maxLines 2、lineSpacingExtra 3dp、
 *   外层横向 LinearLayout `weightSum=2` 而子项 `weight=1` → 宽度取一半（L105-125）。
 *
 * 内容随海报焦点变化，用 [AnimatedContent] 做“淡入 + 轻微上滑 / 淡出 + 轻微上移”，
 * 320ms 比背景略快，文字先落位，避免整屏同时变化显得迟滞。
 *
 * `tmdbData` 只影响剧名行：有艺术字 Logo（`logoUrl`）时整行换成 Logo（高 96dp、最宽 720dp、
 * `ContentScale.Fit`、贴底左对齐）并隐藏文字；没有 Logo 或加载失败时仍显示 32sp 文字，
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
        // Logo 优先级：网络 / 手动 override（最新）→ 首帧同步缓存（已随 `poster` 带下来）。
        val logoUrl = (tmdbData?.logoUrl ?: current.logoUrl).orEmpty()
        val hasLogo = logoUrl.isNotBlank()
        // 只有**真正加载失败**才回落文字：loading 阶段保持空白，才不会先闪文字再换艺术字。
        var logoFailed by remember(logoUrl) { mutableStateOf(false) }
        // Logo 单独用 150ms 淡入：比背景的 500ms 短得多，冷启动时艺术字第一帧就位，不拖泥带水。
        val context = LocalContext.current
        val logoModel = remember(context, logoUrl) {
            ImageRequest.Builder(context)
                .data(logoUrl)
                .crossfade(HERO_LOGO_CROSSFADE_MS)
                .build()
        }
        // 剧名文字兜底：只有**手动**匹配才覆盖剧名（自动匹配可能判错，名字改错比图糊更严重）。
        val titleText = if (tmdbData?.manual == true) {
            tmdbData.item.title.ifBlank { current.title }
        } else {
            current.title
        }
        Column(modifier = Modifier.fillMaxWidth()) {
            // 剧名行（原版 `activity_home_v2.xml` L82-89 / `HomeActivityV2.D2(pos)`）：
            // 有 TMDB 艺术字 Logo → 整行 96dp（艺术字太小时眼睛要凑上去，96dp 才和元数据/简介比例协调）；
            // 没有 Logo **或 Logo 加载失败** → 保持 36dp 的 32sp 文字标题。两种状态不混排，各自固定高度。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (hasLogo && !logoFailed) HERO_LOGO_HEIGHT else HERO_TITLE_HEIGHT),
                contentAlignment = Alignment.BottomStart,
            ) {
                if (hasLogo && !logoFailed) {
                    // Logo 来自首帧同步缓存 / 已完成的详情：直接画图，**不写 loading 槽、不画文字**。
                    // 图未解码完时这一行保持透明空白（Coil 本地命中通常不到一帧），
                    // 绝不会先闪一行 32sp 文字再换成艺术字。
                    AsyncImage(
                        model = logoModel,
                        contentDescription = current.title,
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.BottomStart,
                        onError = { logoFailed = true },
                        modifier = Modifier
                            .fillMaxHeight()
                            .widthIn(max = HERO_LOGO_MAX_WIDTH),
                    )
                } else {
                    HeroTitleText(titleText)
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
 * 首页剧名的纯文字形态：32sp 粗体白字 + 阴影 + 单行省略。
 *
 * 抽成独立 composable 是为了让「没有 Logo」「Logo 加载中」「Logo 加载失败」三种情况
 * 共用**逐字一致**的文字样式 —— 三处各写一遍迟早会漂移，加载完成瞬间字号跳一下就露馅。
 */
@Composable
private fun HeroTitleText(text: String) {
    Text(
        text = text,
        color = Color.White,
        fontSize = HERO_TITLE_SP.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(shadow = HERO_TITLE_SHADOW),
    )
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
 * 海报区（XML `posterArea`，L142-191）：整块固定高 215dp（L147）。
 *
 * 内部是**原生 RecyclerView**（`AndroidView` 承载），不再用 Compose `LazyRow`：
 * LazyRow 在电视端快速连按时会滚动跳跃、复用卡片时图片错位；原生 RecyclerView 的
 * `onViewRecycled` 能真正取消在途图片请求，配合自定义 SmoothScroller 得到稳定的居中吸附。
 *
 * 尺寸与对齐全部由 [HeroPosterRecyclerView] 负责：
 * - 卡片：全局唯一 [HeroPosterCard] 120dp × 214dp，间距 12dp（ItemDecoration，末项不加）；
 * - `paddingStart = 24dp`：第一张贴左；
 * - `paddingEnd = (屏宽 - 120dp) / 2` + `clipToPadding = false`：最后一张能滚到屏幕正中；
 * - 焦点 0..3：整行不动；焦点 4 起：焦点卡平滑滚到屏幕正中。
 */
@Composable
private fun PosterArea(
    posters: List<HeroPoster>,
    row: HeroPosterRowHost,
    focusedPosterIndex: Int,
    onFocused: (Int) -> Unit,
    onClick: (HeroPoster) -> Unit,
    onLongPress: (HeroPoster) -> Unit,
    onLongPressTmdb: (Int) -> Unit,
    onMoveUp: (Int) -> Unit,
) {
    AndroidView(
        // 回调只在 factory 里挂一次：它们捕获的是 Compose 的状态委托（读写永远拿到最新值），
        // 每次重组重建 lambda 只会白白分配对象。
        factory = { context ->
            HeroPosterRecyclerView(context).also { view ->
                // 不用 `apply`：apply 的隐式接收者会让 `onClick(...)` 先解析到 RecyclerView 自己的
                // 可空回调属性，和外层同名参数撞车；这里显式用 `view.` 前缀，避免隐式接收者遮蔽。
                view.onPosterFocused = { index -> onFocused(index) }
                view.onClick = { poster -> onClick(poster) }
                view.onLongPress = { poster -> onLongPress(poster) }
                view.onLongPressTmdb = { index -> onLongPressTmdb(index) }
                view.onMoveUp = { index -> onMoveUp(index) }
                row.attach(view)
            }
        },
        onRelease = { view ->
            // 列表被移除（切源 / 换页 / 退出）时彻底断开：
            // Adapter 持有卡片，回调持有 Compose 状态，都不能跟着 View 一起泄漏。
            view.onPosterFocused = null
            view.onClick = null
            view.onLongPress = null
            view.onLongPressTmdb = null
            view.onMoveUp = null
            row.detach(view)
            view.adapter = null
        },
        update = { view ->
            // 数据去重在 bindData 内部：列表没变时完全不动，避免每次重组都 notifyDataSetChanged。
            view.bindData(posters)
            // 焦点同步：只有 index 变化且焦点还在海报行内才滚动，不会和用户按键抢动画。
            view.syncFocus(focusedPosterIndex)
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(POSTER_AREA_HEIGHT),
    )
}

/** 在已挂载的 FocusRequester 列表里安全地请求焦点：未组合的 Lazy 项会抛异常，这里降级返回 false。 */
private fun requestFocus(requesters: List<FocusRequester>, index: Int): Boolean {
    val requester = requesters.getOrNull(index) ?: return false
    return runCatching { requester.requestFocus() }.isSuccess
}

/** 首页日志统一 TAG：`adb logcat -s HeroHome` 就能看到源切换、请求与回调条数。 */
private const val TAG_HOME = "HeroHome"

// ---------------------------------------------------------------------------
// 【临时诊断，仅 debug】首页海报行焦点路径日志：`adb logcat -s HeroFocusDiag`
//
// 只读数、只打日志，不参与任何判定；release 下 `BuildConfig.DEBUG` 被折叠成 false，
// 配合 `inline` 连字符串拼接一起剥掉。诊断结束后整块删除（连同 a~e 五处调用点）即可。
// ---------------------------------------------------------------------------
private const val TAG_FOCUS_DIAG = "HeroFocusDiag"

private inline fun focusDiag(message: () -> String) {
    if (BuildConfig.DEBUG) Log.d(TAG_FOCUS_DIAG, message())
}

/** `View.FOCUS_*` → 可读方向名。 */
private fun focusDirName(direction: Int): String = when (direction) {
    View.FOCUS_LEFT -> "LEFT"
    View.FOCUS_RIGHT -> "RIGHT"
    View.FOCUS_UP -> "UP"
    View.FOCUS_DOWN -> "DOWN"
    View.FOCUS_FORWARD -> "FORWARD"
    View.FOCUS_BACKWARD -> "BACKWARD"
    else -> "DIR($direction)"
}

/** `KeyEvent.KEYCODE_DPAD_*` → 可读方向名。 */
private fun keyDirName(keyCode: Int): String = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_LEFT -> "LEFT"
    KeyEvent.KEYCODE_DPAD_RIGHT -> "RIGHT"
    KeyEvent.KEYCODE_DPAD_UP -> "UP"
    KeyEvent.KEYCODE_DPAD_DOWN -> "DOWN"
    else -> "KEY($keyCode)"
}

/** 日志用身份串：类名 + 焦点态 + 是否挂窗口，避免 `toString()` 里的哈希噪音。 */
private fun View?.diagName(): String {
    val view = this ?: return "null"
    return "${view.javaClass.simpleName}(focused=${view.isFocused}, attached=${view.isAttachedToWindow})"
}

/** 预加载半径：焦点前后各 3 张。 */
private const val HERO_PRELOAD_RANGE = 3

/** 预加载解码宽度：只影响预热那张图占多少内存，不影响磁盘缓存里原始字节的清晰度。 */
private const val HERO_POSTER_PRELOAD_WIDTH = 360
private const val HERO_BACKDROP_PRELOAD_WIDTH = 960

/** 艺术字 Logo 的预热解码宽度：展示上限是 720dp 宽 / 96dp 高，2x 屏按 800px 解码足够，不影响磁盘缓存里原始字节。 */
private const val HERO_LOGO_PRELOAD_WIDTH = 800

/**
 * 焦点详情请求的防抖窗口（毫秒）。
 *
 * 电视端按住方向键时焦点每 ~50ms 换一张卡，若每次焦点变化都发详情请求，滑过一排就是十几次。
 * 300ms 对应逆向原版「新焦点到来先取消上一个任务，再延迟发请求」的节奏：
 * 用户停手后约 0.3 秒出结果，连续滑过则一次都不发。
 * 取消由 `LaunchedEffect(heroPosterKey, posterKeys, ...)` 的 key 变化天然完成（换 key = cancel 上一条协程），
 * 不需要额外持有 `Job` 句柄，也就不会有忘记清理的泄漏。
 */
private const val HERO_TMDB_FOCUS_DEBOUNCE_MS = 300L

/**
 * 邻居预热的起跑延迟 + 每张之间的强制间隔（毫秒）。
 *
 * 当前焦点条目的搜索 + 详情先跑；500ms 后才开始补左右各 3 张的**搜索级**匹配，
 * 并且每张之间同样停顿 500ms，避免多条请求同时抢 TMDB 连接。
 * 焦点继续移动时整个 effect 取消，这里一起停。
 */
private const val HERO_TMDB_NEIGHBOR_DELAY_MS = 500L

/**
 * 首页一张海报卡。
 *
 * 前六个字段只服务 UI；[vod] / [site] 是**真实数据本体**，点确定键时直接交给详情页，
 * 不再按剧名二次搜索（剧名可能重名，`Vod.getId()` 才是站点内唯一键）。
 */
private data class HeroPoster(
    /** 在列表中的下标：仅用于排查 / 日志，不参与业务（RecyclerView 按 position 绑定）。 */
    val id: Int,
    val title: String,
    val meta: String,
    val synopsis: String,
    val url: String,
    val backdropUrl: String,
    /**
     * 艺术字 Logo：**由磁盘同步缓存直接带下来**，不依赖 `TmdbMatchCache` 是否命中。
     *
     * 冷启动第一帧就有值，信息区可以直接画图，不会先闪一行 32sp 文字再换成艺术字。
     * null / 空串 = 用 [title] 的文字标题（对齐逆向原版 `HeroImageView` 的两态）。
     */
    val logoUrl: String? = null,
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
 * 首页首帧同步命中的 TMDB 匹配：`posterKey → 已转换好的展示数据`。
 *
 * 对应逆向原版 `TmdbCache` 的「渲染第一帧之前先查缓存」：命中就直接填横图 / 评分 / 年份 / 手动词名，
 * 用户看不到「源站竖图硬切 TMDB 横图」，也不会先闪一行没有评分的元数据。
 */
private data class HeroSyncCache(
    val matches: Map<String, HeroTmdbData>,
    /** 同一次读盘的原始缓存：焦点预取直接复用，冷启动不必把同一份 JSON 再解析一遍。 */
    val raw: TmdbMatchCache?,
) {
    companion object {
        val EMPTY = HeroSyncCache(emptyMap(), null)
    }
}

/**
 * 列表成型时**同步**读一次 `TmdbMatchCache`（原版 `TmdbCache` 的第一帧直读）。
 *
 * - 整个列表只读一次盘：`Setting.getTmdbMatchCache()` 是 synchronized 的「读盘 + Gson 解析」，
 *   每张卡查一次会把主线程卡住；
 * - 手动域优先（`findManual`）：手动词名 / 横图与自动匹配冲突时以用户选择为准；
 * - **只产出展示数据**：不写 `tmdbOverrides`、不写 [HeroTmdbPrefetchCache]，
 *   焦点预取和详情页仍走原有「已解析 / 手动优先」闸门，不会被首帧数据误判成已完成；
 * - 磁盘缓存没有 Logo 字段（`TmdbMatchCache.Entry` 无 logoUrl），所以 Logo 从
 *   [HeroLogoCache.snapshot] 的内存映射直接取：冷启动第一帧就有艺术字，不再等联网详情。
 */
private fun readHeroSyncCache(
    posters: List<HeroPoster>,
    logoMap: Map<String, String> = HeroLogoCache.snapshot(),
): HeroSyncCache {
    if (posters.isEmpty()) return HeroSyncCache.EMPTY
    val cache = runCatching { Setting.getTmdbMatchCache() }.getOrNull() ?: return HeroSyncCache.EMPTY
    val matches = HashMap<String, HeroTmdbData>()
    for (poster in posters) {
        val vod = poster.vod
        val manual = runCatching { cache.findManual(poster.site.key, vod.id, vod.name) }.getOrNull()
        val item = manual ?: runCatching { cache.find(poster.site.key, vod.id, vod.name) }.getOrNull() ?: continue
        val data = item.toHomeTmdbData(manual = manual != null)
        matches[poster.posterKey] = data.copy(
            // 冷启动第一帧的 Logo 优先级：磁盘缓存（上次联网拿到的艺术字）→ 进程内详情缓存 → 空。
            logoUrl = logoMap[poster.posterKey]?.takeIf { it.isNotBlank() }
                ?: HeroTmdbPrefetchCache.peek(poster.posterKey)?.logoUrl
                ?: data.logoUrl,
        )
    }
    return HeroSyncCache(matches, cache)
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
        Log.w(TAG_HOME, "tmdb 匹配失败: ${vod.name} | ${e.message}")
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
    // 缓存命中 / 在线匹配都要能立刻拼出 `年份 · 分类 · 备注 · TMDB 8.6`，不必等详情回包。
    rating = ratingText(),
    year = yearText(),
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
            .crossfade(HERO_IMAGE_CROSSFADE_MS)
            .size(width)
            .build()
    )
}

/**
 * 统一的图片请求：首页 / 详情页所有 `AsyncImage` 都从这里拿 model。
 *
 * - 固定 [HERO_IMAGE_CROSSFADE_MS] 淡入：首载、换图、预热命中都不会硬切；
 * - `remember(context, url)`：同一个 URL 重组时复用同一个请求，Coil 才能命中内存 / 磁盘缓存，
 *   否则每帧新建 ImageRequest 会被当成新请求重新读一遍；
 * - `internal`：详情页（同包）的背景 / 海报 / 演员头像复用同一份实现，
 *   保证与首页预热**参数逐字一致**，点进详情时图片已经在缓存里。
 */
@Composable
internal fun rememberHeroImageModel(url: String): ImageRequest {
    val context = LocalContext.current
    return remember(context, url) {
        ImageRequest.Builder(context)
            .data(url)
            .crossfade(HERO_IMAGE_CROSSFADE_MS)
            .build()
    }
}

/**
 * 元数据行：`年份 · 分类 · 备注 · TMDB 评分`，空字段自动省略，不会留下多余的 `·`。
 *
 * [year] / [rating] 来自 TMDB（缓存命中时第一帧就有值）：年份只补源站缺失的年，
 * 评分永远追加在最后 —— 与逆向原版 `HomeActivityV2.D2(pos)` 的排布一致。
 */
private fun buildMeta(vod: Vod, year: String = "", rating: String = ""): String = listOf(
    year.ifBlank { vod.year },
    vod.typeName,
    vod.remarks,
    if (rating.isNotBlank()) "TMDB $rating" else "",
)
    .filter { it.isNotBlank() }
    .joinToString(" · ")

/** TMDB 评分文案：`8.6`；没有评分（0）时返回空串，元数据行不出现 `TMDB 0.0`。 */
private fun TmdbItem.ratingText(): String =
    if (rating > 0.0) String.format(Locale.ROOT, "%.1f", rating) else ""

/**
 * TMDB 年份：从 `subtitle`（`电影 · 2014-05-23 · 评分 7.8`）里抓第一个 4 位年份。
 *
 * 与详情页 `TmdbItem.subtitle` 的拼装规则对应，不额外请求接口：缓存命中时 `subtitle` 已经在盘上。
 */
private fun TmdbItem.yearText(): String =
    HERO_TMDB_YEAR.find(subtitle.orEmpty())?.value ?: ""

private val HERO_TMDB_YEAR = Regex("(?:19|20)\\d{2}")

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
// 艺术字 Logo：原版 `activity_home_v2.xml` L82-89 是 72dp，本工程按需求放大到 96dp ——
// 电视 3 米外 72dp 的艺术字笔画会糊在一起，96dp 才和 32sp 标题、元数据行形成正常层级。
// 没有 Logo 时剧名行回落到文字高度 36dp，不能把文字行也撑到 96dp（会把元数据/简介整体下推）。
private val HERO_LOGO_HEIGHT = 96.dp
private val HERO_LOGO_MAX_WIDTH = 720.dp
private const val HERO_TITLE_SP = 32f       // L72 textSize
private val HERO_META_TOP_GAP = 2.dp        // L100 layout_marginTop
private val HERO_META_HEIGHT = 20.dp        // L99 layout_height
private const val HERO_META_SP = 14f        // L93 textSize
private val HERO_DESC_TOP_GAP = 4.dp        // L109 layout_marginTop
private val HERO_DESC_HEIGHT = 40.dp        // L118 layout_height
private const val HERO_DESC_SP = 12f        // L112 textSize
private const val HERO_DESC_WIDTH_FRACTION = 0.5f // L110 weightSum=2 + L124 weight=1
private const val HERO_BACKDROP_FADE_MS = 800
private const val HERO_INFO_FADE_MS = 320
/** 所有 Coil 请求的统一淡入时长：图片从透明淡到不透明，换图 / 首载都不会「啪」地硬切。 */
private const val HERO_IMAGE_CROSSFADE_MS = 500

/** 艺术字 Logo 的淡入时长：比背景短，冷启动第一帧直接出字，不做 500ms 的慢淡入。 */
private const val HERO_LOGO_CROSSFADE_MS = 150

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
private const val POSTER_TITLE_SP = 13f     // adapter_vod_rect L46 textSize

// 卡片间距 12dp；卡片自身的宽高 / 圆角 / 焦点红框全部写死在 HeroPosterCard 里。
private val POSTER_GAP = 12.dp

// 前 4 张（index 0..3）不滚动；第 5 张（index = 4）起把焦点卡滚到屏幕正中，
// 与逆向原版 HorizontalGridView 的“第 5 张才居中”节奏一致。
private const val POSTER_SCROLL_ANCHOR = 4

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

// ---------------------------------------------------------------------------
// 海报行（原生 RecyclerView）
// ---------------------------------------------------------------------------

/** 平滑滚动时长：普通滚动 300ms（`calculateTimeForScrolling`）。 */
private const val POSTER_SCROLL_MS = 300

/** 末段减速时长：500ms（`calculateTimeForDeceleration`）。 */
private const val POSTER_DECELERATION_MS = 500

/** 海报卡片图片淡入：300ms（产品定稿；首页其它大图仍用 500ms）。 */
private const val POSTER_CARD_CROSSFADE_MS = 300

/** 焦点卡放大倍数（与原 Compose 卡片一致；只影响绘制，不影响布局）。 */
private const val POSTER_FOCUS_SCALE = 1.1f

/** 焦点放大 / 阴影动画时长。 */
private const val POSTER_FOCUS_ANIM_MS = 150L

/** 焦点卡抬升高度（dp）：配合放大投一层影子，让卡片真的「浮起来」。 */
private const val POSTER_FOCUS_ELEVATION_DP = 18f

/**
 * Compose ↔ 海报行 RecyclerView 的握柄。
 *
 * `FocusRequester` 只能定位 Compose 节点，定位不到 `AndroidView` 里的卡片；
 * RecyclerView 挂载时把实例登记进来，Compose 跨区移动（Tab ↓ / 弹窗关闭还焦点）
 * 直接按 index 请求焦点，不再维护一整套 `posterRequesters`。
 */
private class HeroPosterRowHost {

    private var view: HeroPosterRecyclerView? = null

    fun attach(target: HeroPosterRecyclerView) {
        view = target
    }

    fun detach(target: HeroPosterRecyclerView) {
        if (view === target) view = null
    }

    fun focusPoster(index: Int): Boolean = view?.focusPoster(index) == true

    fun scrollToStart() {
        view?.scrollToStart()
    }
}

/**
 * 海报行 Adapter：**只做数据绑定**，尺寸 / 圆角 / 焦点红框全部来自全局唯一 [HeroPosterCard]。
 *
 * 图片走 Coil 的 View 扩展（300ms 淡入）而不是卡片内部的 Glide：
 * 卡片已置 `externalImageLoading = true`，自身不再发请求；`onViewRecycled` 里
 * `dispose()` 取消在途下载，彻底避免快速滑动时「上一张图晚到、贴到复用的卡上」。
 *
 * 按键语义与重构前的 Compose 卡片逐条对齐：
 * - 确定键短按 = 进详情；按住 1s = 换源弹窗（触发后吞掉后续所有事件，直到 KeyUp）；
 * - 方向下键短按 = 原地不动（下面没有可聚焦控件）；按住 1.5s = TMDB 手动匹配；
 * - 方向上下左右之外的按键全部放行；左右交给 RecyclerView 自己的焦点搜索。
 */
private class HeroPosterAdapter(
    private val onFocused: (Int) -> Unit,
    private val onClick: (HeroPoster) -> Unit,
    private val onLongPress: (HeroPoster) -> Unit,
    private val onLongPressTmdb: (Int) -> Unit,
    private val onMoveUp: (Int) -> Unit,
) : RecyclerView.Adapter<HeroPosterAdapter.ViewHolder>() {

    private var items: List<HeroPoster> = emptyList()

    /** 返回 true 表示列表真的换了（调用方据此复位焦点锚点）。 */
    fun submit(next: List<HeroPoster>): Boolean {
        // HeroPoster 是 data class，Vod 没有 equals，但列表重建时元素引用不变 => 这里能挡住无意义的刷新。
        if (next == items) return false
        items = next
        notifyDataSetChanged()
        return true
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val card = HeroPosterCard(parent.context).apply {
            // 首页剧名 13sp（adapter_vod_rect L46）；宽高 / 圆角 / 红框全部写死在卡片里，这里不动。
            titleTextSizeSp = POSTER_TITLE_SP
            // 图片交给 Coil：卡片自己不再发 Glide 请求，也不会画「剧名首字」盖住海报。
            externalImageLoading = true
        }
        val holder = ViewHolder(card)
        card.setOnClickListener {
            holder.poster()?.let(onClick)
        }
        card.setOnLongClickListener {
            val poster = holder.poster() ?: return@setOnLongClickListener false
            onLongPress(poster)
            true
        }
        card.setOnKeyListener { _, keyCode, event -> holder.handleKey(keyCode, event) }
        val density = card.resources.displayMetrics.density
        card.setOnFocusChangeListener { _, hasFocus ->
            // 【焦点放大】原生 View 版：整张卡 1.1x 缩放 + 抬升阴影，150ms 收尾。
            // 缩放是绘制层 transform，不参与测量，邻居不会跟着抖（与原 Compose 卡片一致）。
            card.animate()
                .scaleX(if (hasFocus) POSTER_FOCUS_SCALE else 1f)
                .scaleY(if (hasFocus) POSTER_FOCUS_SCALE else 1f)
                .translationZ(if (hasFocus) POSTER_FOCUS_ELEVATION_DP * density else 0f)
                .setDuration(POSTER_FOCUS_ANIM_MS)
                .start()
            if (hasFocus) {
                val position = holder.bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onFocused(position)
            } else {
                // 失焦只停计时，**不复位**「已触发」标记：弹窗拿走了 KeyUp，卡片收不到。
                holder.cancelTimers()
            }
        }
        return holder
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val poster = items[position]
        val card = holder.card
        holder.cancelTimers()
        // tag 只是排查用的旁路信息，不参与点击定位（点击一律按 bindingAdapterPosition 取）。
        card.tag = poster
        // 先 title、再 imageUrl：两个 setter 都会走 bindPoster()，externalImageLoading = true
        // 时它们只维护占位状态，不会发 Glide 请求。
        card.title = poster.title
        card.imageUrl = poster.url
        // Coil View 扩展 + 300ms 淡入；取消在 onViewRecycled 的 dispose()。
        card.posterImageView.load(poster.url) {
            crossfade(POSTER_CARD_CROSSFADE_MS)
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.cancelTimers()
        // 【必须】取消在途的 Coil 请求：请求晚到时会把上一张图贴到复用的卡上（串图 / 错位）。
        holder.card.posterImageView.dispose()
        // 兜底：取消卡片内部可能残留的 Glide 请求并复位占位图。
        holder.card.releasePoster()
        holder.card.tag = null
        // 复位焦点动画：被回收的卡可能还停在 1.1x / 抬升状态，不复位会带到下一条数据上。
        holder.card.animate().cancel()
        holder.card.scaleX = 1f
        holder.card.scaleY = 1f
        holder.card.translationZ = 0f
    }

    inner class ViewHolder(val card: HeroPosterCard) : RecyclerView.ViewHolder(card) {

        private var centerTimer: Runnable? = null
        private var centerTriggered = false
        private var downTimer: Runnable? = null

        /** 当前绑定的条目：位置可能因为 notifyDataSetChanged 失效，取不到就当作无数据。 */
        fun poster(): HeroPoster? = items.getOrNull(bindingAdapterPosition)

        fun cancelTimers() {
            centerTimer?.let(card::removeCallbacks)
            centerTimer = null
            downTimer?.let(card::removeCallbacks)
            downTimer = null
        }

        fun handleKey(keyCode: Int, event: KeyEvent): Boolean {
            // 【诊断 a】方向键入口：方向、第几张、是否已在边界、长按锁是否在吞键。
            val horizontal =
                keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
            if (horizontal || keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                focusDiag {
                    val position = bindingAdapterPosition
                    val count = (card.parent as? RecyclerView)?.adapter?.itemCount ?: 0
                    val boundary = when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> position == 0
                        KeyEvent.KEYCODE_DPAD_RIGHT -> position == count - 1
                        else -> false
                    }
                    "a/handleKey 收到 ${keyDirName(keyCode)} action=${event.action} repeat=${event.repeatCount} " +
                        "pos=$position/$count 在边界=$boundary 长按锁=$centerTriggered"
                }
            }
            // 【长按锁】长按一旦被消费，后续所有事件（系统重复 KeyDown、迟到的 KeyUp、方向键）
            // 全部吃掉，只有松开按键（KeyUp）才解锁 —— 否则弹窗下面会再打开一次详情页。
            if (centerTriggered) {
                if (event.action == KeyEvent.ACTION_UP) centerTriggered = false
                if (horizontal) {
                    // 【诊断 a】这条分支会把左右键吞掉：焦点不动，也不会进 focusSearch。
                    focusDiag { "a/handleKey 长按锁吞掉 ${keyDirName(keyCode)} -> return true" }
                }
                return true
            }
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        // 系统长按会重复发 KeyDown：只有首次按下才起计时，其余重复事件吞掉。
                        if (event.repeatCount == 0) {
                            centerTriggered = false
                            cancelCenter()
                            val timer = Runnable {
                                centerTriggered = true
                                centerTimer = null
                                Log.e("ChangeSource", "卡片长按 ${POSTER_LONG_PRESS_MS}ms 触发换源")
                                poster()?.let(onLongPress)
                            }
                            centerTimer = timer
                            card.postDelayed(timer, POSTER_LONG_PRESS_MS)
                        }
                    } else {
                        // KeyUp：先停计时；触发过长按 → 这次 KeyUp 必须被吞掉，绝不能再进详情。
                        cancelCenter()
                        if (!centerTriggered) poster()?.let(onClick)
                    }
                    return true
                }

                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        if (event.repeatCount == 0) {
                            cancelDown()
                            val position = bindingAdapterPosition
                            if (position != RecyclerView.NO_POSITION) {
                                val timer = Runnable {
                                    downTimer = null
                                    Log.e("ChangeSource", "卡片长按下键 ${TMDB_LONG_PRESS_MS}ms 触发 TMDB 匹配")
                                    onLongPressTmdb(position)
                                }
                                downTimer = timer
                                card.postDelayed(timer, TMDB_LONG_PRESS_MS)
                            }
                        }
                    } else {
                        // 短按保持原来的「不动」（重构前 onMoveDown = {}），但仍消费掉事件。
                        cancelDown()
                    }
                    return true
                }

                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        val position = bindingAdapterPosition
                        if (position != RecyclerView.NO_POSITION) onMoveUp(position)
                    }
                    // 【诊断 a】UP 被卡片消费并交给 onMoveUp：用来确认「焦点先跳到上面」是不是真由 UP 触发。
                    focusDiag { "a/handleKey UP action=${event.action} pos=${bindingAdapterPosition} -> return true" }
                    return true
                }

                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    // 【LEFT 边界】第 1 张再按 LEFT：直接消费，绝不让事件冒泡到窗口级 FocusFinder。
                    //
                    // 底层链路：卡片返回 false → RecyclerView.focusSearch 在本行找不到候选 →
                    // onFocusSearchFailed 返回 null → RecyclerView 退回 super.focusSearch →
                    // 沿父链到 DecorView，由整个窗口的 FocusFinder 重新找目标，
                    // 于是被左上方的顶栏接住。LEFT 是唯一会命中的方向：窗口右侧没有可聚焦项，
                    // RIGHT 到底时 FocusFinder 返回 null，焦点原地不动。
                    //
                    // 只处理「焦点确实停在 position 0」：NO_POSITION / 其它位置一律继续放行，
                    // 保持原有「一次只走一张」的横向手感；RIGHT / UP / DOWN 完全不动。
                    if (bindingAdapterPosition == 0) {
                        focusDiag {
                            "a/handleKey LEFT 边界消费 action=${event.action} pos=0 -> return true"
                        }
                        return true
                    }
                }
            }
            // 左右放行：交给 RecyclerView 的焦点搜索，最终由 [HeroPosterRecyclerView.scrollToIndex] 居中。
            if (horizontal) {
                // 【诊断 a】放行 = 未消费：紧接着应看到 c/（focusSearch）与 b/（onFocusSearchFailed）。
                focusDiag {
                    "a/handleKey 放行 ${keyDirName(keyCode)} pos=${bindingAdapterPosition} -> return false"
                }
            }
            return false
        }

        private fun cancelCenter() {
            centerTimer?.let(card::removeCallbacks)
            centerTimer = null
        }

        private fun cancelDown() {
            downTimer?.let(card::removeCallbacks)
            downTimer = null
        }
    }
}

/**
 * 海报行：横向 RecyclerView + 自定义 SmoothScroller。
 *
 * 对齐规则（与原 Compose 版逐字一致）：
 * - `clipToPadding = false` + `paddingStart = 24dp`：第一张贴左，滚动时卡片可以画进行首 padding 区；
 * - `paddingEnd = (宽度 - 120dp) / 2`：最后一张也能滚到屏幕正中；
 * - `scrollToIndex(index)`：index < 4 回 position 0；index >= 4 平滑滚到该 position 并居中。
 */
private class HeroPosterRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : RecyclerView(context, attrs) {

    var onPosterFocused: ((Int) -> Unit)? = null
    var onClick: ((HeroPoster) -> Unit)? = null
    var onLongPress: ((HeroPoster) -> Unit)? = null
    var onLongPressTmdb: ((Int) -> Unit)? = null
    var onMoveUp: ((Int) -> Unit)? = null

    private val posterAdapter = HeroPosterAdapter(
        onFocused = { index ->
            // 焦点走到哪一张，就按规则把哪一张滚到位（Leanback 的「第 5 张起居中」）。
            lastFocusIndex = index
            scrollToIndex(index)
            onPosterFocused?.invoke(index)
        },
        onClick = { poster -> onClick?.invoke(poster) },
        onLongPress = { poster -> onLongPress?.invoke(poster) },
        onLongPressTmdb = { index -> onLongPressTmdb?.invoke(index) },
        onMoveUp = { index -> onMoveUp?.invoke(index) },
    )

    // 纯 View 体系里不能用 Compose 的 `.dp` 扩展（它只在 Compose 的 Density 作用域里成立），
    // 这里统一按屏幕密度换算成像素；`HERO_PADDING` / `POSTER_GAP` 仍是 Compose 侧的唯一定义，
    // 通过 `.value`（dp 数值）换算，避免两套常量各写一份。
    private val density: Float = resources.displayMetrics.density
    private val startPaddingPx: Int = (HERO_PADDING.value * density).roundToInt()
    private val cardWidthPx: Int = (HeroPosterCard.CARD_WIDTH_DP * density).roundToInt()
    private val gapPx: Int = (POSTER_GAP.value * density).roundToInt()

    /** 最近一次已经滚到位的下标：Compose 每次重组都会 syncFocus，这里挡住重复滚动。 */
    private var lastFocusIndex = -1

    /**
     * 待补抢焦点的位置（[RecyclerView.NO_POSITION] = 无）。
     *
     * 跨区瞬移定位时，原来带焦点的卡会被回收、焦点会短暂落空；如果不补，
     * 下一次按键就会落到窗口里第一个可聚焦项（顶栏「小房子」）。
     * 这里记下目标位置，在**同一帧布局结束**立刻把焦点交给它，中间没有按键窗口。
     */
    private var pendingFocusPosition = RecyclerView.NO_POSITION

    init {
        layoutManager = HeroPosterLayoutManager(context)
        adapter = posterAdapter
        // 铺满整行、不裁剪 padding：焦点卡可以画到 24dp 行首之外（与原 LazyRow 一致）。
        clipToPadding = false
        // 焦点放大 / 抬升阴影都要画到卡片自身边界之外，这一层不能再裁子 View。
        clipChildren = false
        setHasFixedSize(true)
        itemAnimator = null
        overScrollMode = View.OVER_SCROLL_NEVER
        // 【焦点收口】容器本身永远不抢焦点：只允许卡片可聚焦，焦点不可能停在 RecyclerView 上。
        // FOCUS_AFTER_DESCENDANTS = 先交给子 View（卡片），子 View 都不要时才轮到容器（这里不可聚焦）。
        isFocusable = false
        isFocusableInTouchMode = false
        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        addItemDecoration(HeroPosterGapDecoration(gapPx))
    }

    /**
     * 行尾 padding 必须按**当前宽度**算：Compose 侧是 `fillMaxWidth()`，
     * 宽度 = 屏幕宽度，`(宽 - 120dp) / 2` 才能让最后一张停在正中。
     *
     * 放在 `super.onMeasure` 之前：同一帧就用正确 padding 布局。
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = View.MeasureSpec.getSize(widthMeasureSpec)
        val end = ((width - cardWidthPx) / 2).coerceAtLeast(startPaddingPx)
        if (paddingLeft != startPaddingPx || paddingRight != end) {
            setPadding(startPaddingPx, 0, end, 0)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /** 【诊断】某 View 在本行的 adapter position；不属于本行返回 [RecyclerView.NO_POSITION]。 */
    private fun diagPositionOf(view: View?): Int {
        if (view == null) return RecyclerView.NO_POSITION
        val direct = getChildAdapterPosition(view)
        if (direct != RecyclerView.NO_POSITION) return direct
        return findContainingViewHolder(view)?.bindingAdapterPosition ?: RecyclerView.NO_POSITION
    }

    /** 【诊断】当前视口内的 position 区间，用来判断「目标卡是否真的还在屏幕里」。 */
    private fun diagVisibleRange(): String {
        val manager = layoutManager as? LinearLayoutManager ?: return "NO_LM"
        return "${manager.findFirstVisibleItemPosition()}..${manager.findLastVisibleItemPosition()}"
    }

    /**
     * 【诊断 c】`focusSearch` 纯透传：只记日志，原样返回 `super.focusSearch`。
     *
     * 本类此前没有这个 override；诊断期加这一层，只为看清「RecyclerView 自己找到了谁 /
     * 没找到时把什么交给父链」。语义与原实现完全一致，诊断结束后整段删除。
     */
    override fun focusSearch(focused: View?, direction: Int): View? {
        val result = super.focusSearch(focused, direction)
        focusDiag {
            val asChild = result != null && result.parent === this
            "c/focusSearch dir=${focusDirName(direction)} from=${focused.diagName()}#${diagPositionOf(focused)} " +
                "-> ${result.diagName()} 类=${result?.javaClass?.name ?: "null"} " +
                "是本行子项=$asChild 子项pos=${diagPositionOf(result)}"
        }
        return result
    }

    /**
     * 数据没变就什么都不做：`update` 每次重组都会调用，不能每次都 notifyDataSetChanged。
     *
     * 【重绑后的焦点恢复】`notifyDataSetChanged()` 会把整排卡片重新绑定 / 回收，
     * 带焦点的 View 一旦被摘掉，焦点就空了；下一次按键落到窗口里第一个可聚焦项，
     * 也就是顶栏「小房子」—— 这是「移动卡片时焦点跳到小房子」的主因。
     *
     * 所以在**重绑之前**判断焦点是否真的落在本行的某张卡上，命中才记下位置，
     * 由 [onLayout] 在这一轮布局结束后恢复。写入条件（保守策略：宁可让焦点自然落地，
     * 也绝不主动抢）：
     *   1. 窗口当前确实有 View 持有焦点，且它是本 RecyclerView 的后代；
     *   2. 对应的卡此刻已绑定并挂在窗口上；
     *   3. 取到的 position 在重绑后的 `itemCount` 范围内；
     *   4. 重绑后该 position 上仍是**同一张卡**（按 `posterKey` 比对）。
     */
    fun bindData(posters: List<HeroPoster>) {
        // 必须在 submit(notifyDataSetChanged) **之前**取：重绑之后 holder 与焦点状态都已变。
        // `focusedChild == null` 是 O(1) 快速否定，避免每次重组都做一次窗口级 findFocus()。
        val focusedPosition =
            if (focusedChild == null) RecyclerView.NO_POSITION else focusedItemPosition()
        // 再记一个「这个位置上是哪张卡」的稳定标识：`notifyDataSetChanged()` 是整排重绑，
        // 只校验下标的话，新数据顺序一变就会把焦点恢复到**另一张卡**上。
        // 用 `posterKey`（首页缓存 / override 用的同一个 key，只由站点 + 条目 id 决定，
        // TMDB 横图 / Logo 变了也不会变），不为此新增 stable ID 机制。
        // 来源是 `onBindViewHolder` 写进卡片 `tag` 的 HeroPoster，即「重绑前该卡的原始数据」。
        val focusedKey = if (focusedPosition == RecyclerView.NO_POSITION) {
            null
        } else {
            (findViewHolderForAdapterPosition(focusedPosition)?.itemView?.tag as? HeroPoster)
                ?.posterKey
                ?.takeIf { it.isNotBlank() }
        }
        // 【诊断 d】重绑前：焦点在谁身上、第几张、滚动偏移、新旧条数、当前 pending。
        focusDiag {
            "d/bindData BEFORE focusedChild=${focusedChild.diagName()} " +
                "focusedPos=${diagPositionOf(focusedChild)} focusedKey=$focusedKey " +
                "scroll=${computeHorizontalScrollOffset()} oldCount=${posterAdapter.itemCount} " +
                "newCount=${posters.size} pending=$pendingFocusPosition"
        }
        if (posterAdapter.submit(posters)) {
            lastFocusIndex = -1
            // 数据换了，旧下标不再可信：只有「重绑前焦点确实在本行 + 同一下标仍是同一张卡」
            // 才恢复，否则保持 NO_POSITION，让焦点按正常规则落地。
            val sameCard = focusedKey != null &&
                focusedPosition in 0 until posterAdapter.itemCount &&
                posters[focusedPosition].posterKey == focusedKey
            pendingFocusPosition =
                if (sameCard) focusedPosition else RecyclerView.NO_POSITION
            // 【诊断 d】notifyDataSetChanged 之后：焦点是否还在、滚动是否被保留、pending 写了什么。
            focusDiag {
                "d/bindData AFTER sameCard=$sameCard pending=$pendingFocusPosition " +
                    "focusedChild=${focusedChild.diagName()} focusedPos=${diagPositionOf(focusedChild)} " +
                    "scroll=${computeHorizontalScrollOffset()} itemCount=${posterAdapter.itemCount} " +
                    "视口=${diagVisibleRange()}"
            }
        } else {
            // 【诊断 d】数据没变 → 没有 notifyDataSetChanged，pending 保持不变。
            focusDiag {
                "d/bindData SKIP 数据未变 pending=$pendingFocusPosition " +
                    "scroll=${computeHorizontalScrollOffset()} 视口=${diagVisibleRange()}"
            }
        }
    }

    /**
     * 当前焦点是否真的落在本行的某张卡上：是则返回其 adapter position，
     * 否则返回 [RecyclerView.NO_POSITION]。
     *
     * 只认「窗口焦点 → 本 RecyclerView 的后代」这一条链：焦点在顶栏 / Tab / 弹窗 /
     * 别的页面残留时一律返回 NO_POSITION，避免把无关焦点误判成「重绑丢了」再抢回来。
     */
    private fun focusedItemPosition(): Int {
        val focused = rootView.findFocus() ?: return RecyclerView.NO_POSITION
        var node: View? = focused
        while (node != null && node !== this) node = node.parent as? View
        if (node !== this) return RecyclerView.NO_POSITION
        val holder = findContainingViewHolder(focused) ?: return RecyclerView.NO_POSITION
        if (!holder.itemView.isAttachedToWindow) return RecyclerView.NO_POSITION
        return holder.bindingAdapterPosition
    }

    /** Compose → View：外部焦点下标变了才滚，避免和用户按键抢同一个动画。 */
    fun syncFocus(index: Int) {
        if (index < 0 || index >= posterAdapter.itemCount) return
        if (index == lastFocusIndex) return
        lastFocusIndex = index
        // 焦点不在海报行（例如刚切完分类、焦点还在 Tab 上）时只记状态，不滚动。
        if (focusedChild == null) return
        scrollToIndex(index)
    }

    /**
     * 居中吸附：
     * - index < [POSTER_SCROLL_ANCHOR]：整行回最左，第一张贴左 24dp（已经在最左就完全不动）；
     * - index >= [POSTER_SCROLL_ANCHOR]：平滑滚到该 position，由 [HeroPosterSmoothScroller] 居中。
     */
    fun scrollToIndex(index: Int) {
        if (index < 0 || index >= posterAdapter.itemCount) return
        // 【诊断 f】补：所有 Compose 侧触发的滚动都进这里（含 syncFocus / 焦点变化）。
        focusDiag {
            "f/scrollToIndex index=$index anchor=$POSTER_SCROLL_ANCHOR " +
                "scroll=${computeHorizontalScrollOffset()} 视口=${diagVisibleRange()}"
        }
        if (index < POSTER_SCROLL_ANCHOR) {
            if (computeHorizontalScrollOffset() != 0) smoothScrollToPosition(0)
            return
        }
        smoothScrollToPosition(index)
    }

    /** 切分类后瞬间回到最左：不做动画，避免「Tab 刚选中、下面还在滑」的错位感。 */
    fun scrollToStart() {
        // 【诊断 f】补：切分类会走这里 → 整行瞬移回第 0 张，但**不动焦点**。
        focusDiag {
            "f/scrollToStart BEFORE scroll=${computeHorizontalScrollOffset()} 视口=${diagVisibleRange()} " +
                "焦点=${focusedChild.diagName()} lastFocusIndex=$lastFocusIndex"
        }
        stopScroll()
        pendingFocusPosition = RecyclerView.NO_POSITION
        (layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(0, 0)
        lastFocusIndex = 0
    }

    /**
     * 按 index 抢焦点（Compose 的 FocusRequester 定位不到 AndroidView 里的卡片）。
     *
     * 目标已经在屏幕里 → 当场 requestFocus 并返回 true；
     * 目标在屏幕外 → 按规则瞬移定位（贴左 / 居中），把位置记进 [pendingFocusPosition]，
     * 由 [onLayout] 在这一帧布局结束后立刻补抢焦点，本次返回 false 让 Compose 侧继续重试。
     *
     * 为什么必须补：瞬移会把原来带焦点的卡回收掉，焦点一旦落空，
     * 下一次按键就会落到顶栏第一个可聚焦项（「小房子」）—— 也就是「焦点乱跳」。
     */
    fun focusPoster(index: Int): Boolean {
        if (index < 0 || index >= posterAdapter.itemCount) return false
        // 【诊断 f】补：Compose 侧的显式抢焦（Tab ↓ / 弹窗关闭 / 启动）都从这里进来。
        focusDiag {
            "f/focusPoster index=$index itemCount=${posterAdapter.itemCount} " +
                "当前焦点=${focusedChild.diagName()} scroll=${computeHorizontalScrollOffset()}"
        }
        val attached = findViewHolderForAdapterPosition(index)?.itemView
        if (attached != null && attached.isAttachedToWindow && attached.requestFocus()) {
            pendingFocusPosition = RecyclerView.NO_POSITION
            return true
        }
        pendingFocusPosition = index
        (layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(
            index,
            if (index < POSTER_SCROLL_ANCHOR) 0 else centeredOffset(),
        )
        return false
    }

    /**
     * 布局结束后恢复焦点：`scrollToPositionWithOffset`（瞬移）与 `notifyDataSetChanged()`
     * （重绑）都要「下一帧布局」才生效，而这一帧里原来带焦点的卡可能已经被摘掉。
     * 在 `super.onLayout` 之后目标卡一定已经挂上，这里同步 requestFocus，
     * 不会出现「焦点空了一拍、按键掉到顶栏」的窗口。
     *
     * **零重试**：无论命中与否都立刻清空 [pendingFocusPosition]。
     * - 命中 → 焦点已经补上；
     * - 未命中 → 目标卡确实被移出视口 / 数据已变，这是合理结果；
     *   继续重试只会让后续无关的布局（窗口尺寸变化、弹窗关闭等）把焦点抢到别的卡上。
     */
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        val position = pendingFocusPosition
        if (position == RecyclerView.NO_POSITION) return
        // 先作废再判断：保证任何一条 return 都不会把「待恢复」留到下一次布局。
        pendingFocusPosition = RecyclerView.NO_POSITION
        if (position < 0 || position >= posterAdapter.itemCount) {
            // 【诊断 e】位置越界：直接丢弃。
            focusDiag {
                "e/onLayout 丢弃 pos=$position 越界 itemCount=${posterAdapter.itemCount} " +
                    "当前焦点=${focusedChild.diagName()}"
            }
            return
        }
        val holder = findViewHolderForAdapterPosition(position)
        val target = holder?.itemView
        if (target == null) {
            // 【诊断 e】没命中 holder：目标卡已经不在当前视口（scroll 位置 / 数据变化）。
            focusDiag {
                "e/onLayout 未命中 holder pos=$position 视口=${diagVisibleRange()} " +
                    "itemCount=${posterAdapter.itemCount} scroll=${computeHorizontalScrollOffset()} " +
                    "当前焦点=${focusedChild.diagName()}"
            }
            return
        }
        if (!target.isAttachedToWindow) {
            // 【诊断 e】命中 holder 但没挂窗口：requestFocus 一定失败。
            focusDiag {
                "e/onLayout 命中但未附着 pos=$position target=${target.diagName()} 视口=${diagVisibleRange()}"
            }
            return
        }
        val focused = target.requestFocus()
        // 【诊断 e】恢复结果：requestFocus=false 说明焦点被别处抢走（弹窗 / Compose 焦点请求 / 窗口未聚焦）。
        focusDiag {
            "e/onLayout 恢复 pos=$position target=${target.diagName()} requestFocus=$focused " +
                "之后 focusedChild=${focusedChild.diagName()} pos=${diagPositionOf(focusedChild)} " +
                "视口=${diagVisibleRange()} scroll=${computeHorizontalScrollOffset()}"
        }
    }

    /** 卡片左边缘距 paddingStart 的距离：让卡片中心落在 RecyclerView 的几何中心。 */
    private fun centeredOffset(): Int = (width - cardWidthPx) / 2 - startPaddingPx
}

/**
 * 横向 LayoutManager：`smoothScrollToPosition` 换成 [HeroPosterSmoothScroller]。
 *
 * `RecyclerView.smoothScrollToPosition()` 没有 offset 参数，居中只能由 Scroller 决定；
 * 这也是不能直接用系统 API 做「第 5 张起居中」的原因。
 */
private class HeroPosterLayoutManager(context: Context) :
    LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false) {

    override fun smoothScrollToPosition(
        recyclerView: RecyclerView,
        state: RecyclerView.State?,
        position: Int,
    ) {
        val scroller = HeroPosterSmoothScroller(recyclerView.context).apply {
            targetPosition = position
        }
        startSmoothScroll(scroller)
    }

    /**
     * 焦点卡由 [HeroPosterRecyclerView.scrollToIndex] 统一做居中滚动，
     * 这里屏蔽 RecyclerView 默认的「最小滚动让焦点可见」——
     * 否则一次按键会先跳一下、再平滑滑到中间，正是要消除的「滚动跳跃」。
     *
     * 注意：签名必须和 `RecyclerView.LayoutManager` 一字不差 —— `state` 是 `@NonNull`，
     * 写成 `RecyclerView.State?` 会因「可空性不匹配」直接报 `overrides nothing`。
     * （两参的 `onRequestChildFocus(child, focused)` 是 `ViewGroup` 的方法，
     *  `LayoutManager` 里没有这个重载，不能写在这里。）
     */
    override fun onRequestChildFocus(
        parent: RecyclerView,
        state: RecyclerView.State,
        child: View,
        focused: View?,
    ): Boolean = true

    /**
     * 一次按键只走一张卡。
     *
     * 父类实现会用 `fill()` 把这一侧的卡片补布局出来，但**返回最远端**的那张，
     * 电视上表现就是按一下右键直接跳过好几张（焦点乱跳）。
     * 这里保留它的布局副作用（否则相邻卡还没创建，根本找不到），
     * 但丢掉它返回的视图，只返回紧邻当前卡的那一张。
     *
     * 注意：不能因为父类返回 null 就提前 return —— 它返回 null 只代表「没有更远的目标」，
     * 相邻卡此时可能已经填出来了。
     */
    override fun onFocusSearchFailed(
        focused: View,
        focusDirection: Int,
        recycler: RecyclerView.Recycler,
        state: RecyclerView.State,
    ): View? {
        val fallback = super.onFocusSearchFailed(focused, focusDirection, recycler, state)
        val current = getPosition(focused)
        if (current == RecyclerView.NO_POSITION) {
            // 【诊断 b】拿不到当前卡的 position：直接交父类结果。
            focusDiag {
                "b/onFocusSearchFailed dir=${focusDirName(focusDirection)} pos=NO_POSITION " +
                    "fallback=${fallback.diagName()} fallbackIsFocused=${fallback === focused} " +
                    "-> return ${fallback.diagName()}"
            }
            return fallback
        }
        val step = when (focusDirection) {
            View.FOCUS_RIGHT -> 1
            View.FOCUS_LEFT -> -1
            else -> {
                // 【诊断 b】非左右：不进入「一次只走一张」的逻辑。
                focusDiag {
                    "b/onFocusSearchFailed dir=${focusDirName(focusDirection)} pos=$current " +
                        "fallback=${fallback.diagName()} -> return fallback（非左右）"
                }
                return fallback
            }
        }
        val adjacent = findViewByPosition(current + step)
        val result = if (adjacent != null && adjacent.isFocusable) adjacent else fallback
        // 【诊断 b】边界判定就看这一行：adjacent=null 且 fallback=null → 本行两侧确实没有目标，
        // 返回值会变成 RecyclerView.focusSearch 的 super 分支（见 c/ 日志）。
        focusDiag {
            "b/onFocusSearchFailed dir=${focusDirName(focusDirection)} pos=$current step=$step " +
                "adjacent=${adjacent.diagName()} fallback=${fallback.diagName()} " +
                "fallbackIsFocused=${fallback === focused} " +
                "-> return ${result.diagName()} 视口=${findFirstVisibleItemPosition()}..${findLastVisibleItemPosition()}"
        }
        return result
    }
}

/**
 * 居中吸附的 SmoothScroller。
 *
 * 时间参数按定稿：普通滚动 300ms、末段减速 500ms。
 */
private class HeroPosterSmoothScroller(context: Context) : LinearSmoothScroller(context) {

    /**
     * 末段位移：把目标卡中心对准 **RecyclerView 的几何中心**。
     *
     * 不能复用默认的 `calculateDtToFit(boxStart, boxEnd)`：
     * - 行首 24dp、行尾半屏的 padding 不对称，内容区中心整体偏左；
     * - `getDecoratedRight` 还会把 ItemDecoration 的 12dp 右间距算进去。
     * 这里直接读 View 的未装饰左右边界，误差为 0。
     */
    override fun calculateDxToMakeVisible(view: View, snapPreference: Int): Int {
        val manager = layoutManager ?: return 0
        return manager.width / 2 - (view.left + view.right) / 2
    }

    override fun calculateTimeForScrolling(dx: Int): Int = POSTER_SCROLL_MS

    override fun calculateTimeForDeceleration(dx: Int): Int =
        if (dx == 0) 0 else POSTER_DECELERATION_MS
}

/**
 * 卡片间距：只在**非末项**右侧留 12dp。
 *
 * 末项不加右间距，行尾 padding `(宽 - 120dp) / 2` 才能把它精确停在屏幕正中。
 */
private class HeroPosterGapDecoration(private val gapPx: Int) : RecyclerView.ItemDecoration() {

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State,
    ) {
        val position = parent.getChildAdapterPosition(view)
        val last = (parent.adapter?.itemCount ?: 0) - 1
        outRect.set(0, 0, if (position in 0 until last) gapPx else 0, 0)
    }
}
