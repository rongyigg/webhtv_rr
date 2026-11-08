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
import com.fongmi.android.tv.bean.TmdbMatchCache
import com.fongmi.android.tv.bean.Vod
import com.fongmi.android.tv.impl.Callback
import com.fongmi.android.tv.model.SiteViewModel
import com.fongmi.android.tv.setting.Setting
import com.fongmi.android.tv.ui.custom.HeroImageView
import com.fongmi.android.tv.ui.custom.ProgressLayout
import com.fongmi.android.tv.ui.hero.HeroHorizontalGapDecoration
import com.fongmi.android.tv.ui.hero.HeroImageLoader
import com.fongmi.android.tv.ui.hero.HeroPosterAdapter
import com.fongmi.android.tv.ui.hero.HeroPosterCard
import com.fongmi.android.tv.ui.hero.HomePosterItem
import com.fongmi.android.tv.utils.Util
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 首页纯 View 版（第二步）：把 `activity_home_view.xml` 这套骨架真正接上数据与焦点。
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
 * 数据来源（本步刻意**不**发 TMDB 网络请求）：
 * - 首页列表：`SiteViewModel.homeContent()`（与 Compose 首页同一条 LiveData 通道）；
 * - 横图 / 评分 / 年份 / 手动词名：`Setting.getTmdbMatchCache()`（磁盘匹配缓存，整页只读一次）；
 * - 艺术字 Logo：进程内 `HeroTmdbPrefetchCache` → 磁盘 `hero_logo_cache`（与 `HeroLogoCache` 同一份 prefs）；
 * - 简介：进程内详情缓存里的译文优先，否则用源站 `Vod.content`。
 *
 * 与 `HeroHomeActivity` 的差异（本步范围）：
 * - 不复用 `BaseActivity`：它只存在于 `leanback` / `mobile` 两个 flavor 源集，`main` 源集在编译期看不到它；
 *   这里按 `HeroHomeActivity` 的做法继承 `AppCompatActivity`，并补上等价的
 *   `Setting.wrapDisplay`（界面缩放）+ 沉浸式全屏。
 * - 顶栏 / 分类 Tab 的跳转、换源弹窗、TMDB 手动匹配、详情页入口都留到后续步骤；
 *   本步只保证「首页能正确地显示出来」。
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
     */
    private fun applyHero(index: Int) {
        val item = items.getOrNull(index) ?: return
        focusedIndex = index
        heroNameText.text = item.title
        // 空态时这 3 个文案是 INVISIBLE；回到内容态必须在同一帧显式恢复，
        // 否则 `ProgressLayout.showContent()` 之后 Hero 区只有一张大图、没有文字。
        heroMetaText.text = item.meta
        heroMetaText.visibility = View.VISIBLE
        heroDescText.text = item.synopsis
        heroDescText.visibility = View.VISIBLE
        bindLogo(item)
        bindBackdrop(item, index)
    }

    /**
     * 艺术字 Logo：有图 → 整行换成 Logo（隐藏文字）；没有 / 加载失败 → 32sp 文字。
     *
     * 加载期间保持文字隐藏：冷启动命中磁盘缓存时通常不到一帧；
     * 失败才回落文字，避免「先闪一行文字、再换成艺术字」。
     */
    private fun bindLogo(item: HomePosterItem) {
        val url = item.logoUrl.orEmpty()
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
     */
    private fun bindBackdrop(item: HomePosterItem, position: Int) {
        val backdrop = item.backdropUrl
        if (backdrop.isNotBlank()) {
            posterCard.visibility = View.GONE
            heroBg.visibility = View.VISIBLE
            HeroImageView.setSharedUrl(backdrop)
            heroBg.loadImage(backdrop, position, null)
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
            posterCardImg.dispose()
            posterCardImg.setImageResource(R.drawable.shape_source_poster_placeholder)
            return
        }
        posterCardImg.load(url) {
            crossfade(POSTER_CARD_CROSSFADE_MS)
            placeholder(R.drawable.shape_source_poster_placeholder)
            error(R.drawable.shape_source_poster_placeholder)
        }
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
    }

    /** 卡片确定键：详情页入口留到后续步骤（本步只负责首页渲染，不新增页面跳转）。 */
    private fun onPosterClicked(item: HomePosterItem) {
        Log.d(TAG, "海报确定键: ${item.title}（详情页入口待接入）")
    }

    private fun onTabFocused(index: Int) {
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
