package com.fongmi.android.tv.ui.hero

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fongmi.android.tv.R
import com.fongmi.android.tv.api.config.VodConfig
import com.fongmi.android.tv.bean.Flag
import com.fongmi.android.tv.bean.Result
import com.fongmi.android.tv.bean.Site
import com.fongmi.android.tv.bean.Vod
import com.fongmi.android.tv.model.SearchProgress
import com.fongmi.android.tv.model.SiteViewModel
import com.fongmi.android.tv.setting.SiteBlockSetting
import com.fongmi.android.tv.setting.SiteHealthStore
import com.fongmi.android.tv.ui.activity.HeroPlayDialog
import com.fongmi.android.tv.ui.activity.startEpisode
import com.fongmi.android.tv.utils.Notify
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 换源弹窗（View 体系，独立 Dialog，不使用任何 Compose 外壳）。
 *
 * ## 布局
 * 直接照搬 `ref_docs/dialog_source.xml`（原版 SourceDialog / f23），窗口尺寸与间距照搬 `ref_docs/f23.java`：
 * - 窗口宽 = 屏幕宽 × 0.82（f23 `c0(0.82f)`）；
 * - 窗口高 = min(360dp, 屏幕高 dp × 0.65)（f23 `Math.min(360, Math.round(h / density * 0.65f))`）；
 * - 站点栏横向间距 12dp（f23 `siteRecycler.setHorizontalSpacing(dp(12))`）；
 * - 结果栏横向间距 16dp（f23 `recycler.setHorizontalSpacing(dp(16))`）。
 *
 * 站点标签用 `adapter_source_site`，焦点态用 `selector_source_site_chip_background`：
 * **focused → 红渐变色块 + 白字**（与搜索页胶囊、首页分类 Tab 同一支渐变），
 * **失焦但仍是当前站点、且焦点在下方结果区 → 2dp 红框 + 透明底**（《焦点总结》规则 ②），
 * **焦点离开结果区（回到小房子 / 标签栏）→ 透明底**（规则 ③）。
 * （侧边栏的行用的是另一套：白底黑字，见 [SourceSidebar]。）
 *
 * 结果卡**只允许**用全局唯一组件 [HeroPosterCard]（120dp × 214dp、四角 8dp 全圆、
 * 2dp 纯红 #FF0000 焦点框只包 120×180 海报、34dp 剧名栏），弹窗内部不再手写任何卡片尺寸。
 * 卡片内容映射照抄 `ref_docs/adapter_search.xml` / `ks2.java`：
 * `topStartTag` = `Vod.getSiteName()`（13sp）、`bottomStartTag` = `Vod.getRemarks()`（14sp）、
 * `title` = `Vod.getName()`（16sp）。
 *
 * ## 数据（全局并发搜索，与原版 WebHTV 点卡片进搜索一致）
 * - 候选源由 [candidates] 统一给出，规则与 `TmdbDetailActivity.isChangeSourceCandidate` 等价：
 *   `isSearchable()` + `isChangeable()` + 非隐藏 + 未被用户屏蔽 + 不是当前源，最后按
 *   [SiteHealthStore.sortSites] 的站点健康度排序。
 * - 检索走 [SiteViewModel.searchContent] 的 **List 重载**（写 `search` LiveData），
 *   **绝不能用** 4 参单源重载 —— 那个写的是 `result` LiveData，会和首页 `homeContent()` 抢同一条通道。
 * - **一次性并发搜全部启用源**（`VideoActivity.startSearch` 同款，`quick = true`），
 *   不再「切到哪个 Tab 才搜哪个」；哪个源先回结果，哪个源就先出现在顶部 Tab 里。
 * - Tab 是**动态**的：只有出过结果的源才有 Tab（空结果拿不到 siteKey，无法归因）。
 *   每个源的结果缓存在 [resultsBySite]，来回切 Tab 只换缓存、不再发请求。
 * - 结果里每条 [Vod] 都已由 `SiteApi.searchContent` 盖过 `setSite(site)`，
 *   所以确定键回传的 `(vod, site)` 是同一来源，不需要再按剧名二次搜索。
 *
 * ## 生命周期
 * `SiteViewModel` 由 Activity 作用域持有（构造函数里就建了两个单线程 executor，绝不能按弹窗 new）。
 * 弹窗只在 [onStart] 挂 `observeForever`、[onStop] 摘掉并 [SiteViewModel.stopSearch]，
 * 关闭即取消在途请求。
 */
internal class ChangeSourceDialog private constructor(
    context: Context,
    private val viewModel: SiteViewModel,
    /** 候选源（已过滤 + 已按健康度排序）：既是站点栏，也是侧边栏的全量条目。 */
    private val sites: List<Site>,
    private val keyword: String,
    private val quick: Boolean,
    /** 宿主页预搜索的现成结果：非空时弹窗首帧直接铺开，不再重复搜一轮。 */
    private val initialResults: List<Vod>,
    private val onSourceSelected: (Vod, Site) -> Unit,
    private val onClosed: () -> Unit,
) : Dialog(context, R.style.SourceDialogTheme) {

    /** 当前站点栏下标（[tabSites] 的下标；Tab 是动态的，只有出过结果的源才会在里面）。 */
    private var selectedSite: Int = 0

    /**
     * 焦点是否停在下方结果区。
     *
     * 《焦点总结》规则 ②③ 的分界线：还在结果区 → 当前站点标签留红框（知道这批结果属于哪个站点）；
     * 焦点回到小房子或站点栏 → 红框收起，不留多余的红色干扰。
     */
    private var contentFocused = false

    /** 全局焦点监听：View 体系里判断「焦点是否在结果 RecyclerView 内」最可靠的方式。 */
    private val focusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
        setContentFocused(newFocus != null && isDescendantOf(resultRecycler, newFocus))
    }

    /** 当前展示的结果。永远只属于 [selectedSite] 这一个源。 */
    private var visibleResults: List<Vod> = emptyList()

    /** 是否有请求在途（决定进度条 / 空态 / 列表三选一）。 */
    private var searching = false

    /** 侧边栏勾选快照，关闭再打开时保持上次状态；默认全选。 */
    private var checkedFlags: BooleanArray = BooleanArray(sites.size) { true }

    /** 允许发起检索的源 key 集合：侧边栏没勾的源连请求都不发。 */
    private var enabledSiteKeys: Set<String> = sites.map { it.key }.toSet()

    /**
     * 每个源搜到过的结果（含空结果），避免来回切 Tab 时重复请求。
     *
     * 全局并发搜索下每个源只会回调一次，这里就是「按 siteKey 归档的结果表」，
     * 切 Tab 只是换一份缓存渲染，绝不会再发请求。
     */
    private val resultsBySite = LinkedHashMap<String, List<Vod>>()

    /**
     * 顶部站点栏：**只包含已经返回结果的源**，按结果到达顺序追加。
     *
     * 与原版 `VideoActivity.startSearch` 一致：弹窗一打开就把全部候选源并发丢进
     * `searchContent(List)`，哪个源先回结果哪个先出 Tab，不再「切到哪个才搜哪个」。
     * 0 条命中的源不会出现在这里（`search` 回调里空结果拿不到 siteKey，无法归因）。
     */
    private val tabSites = ArrayList<Site>()

    /** 最近一次进度（只用于头部「Searching… x/y」）。 */
    private var progressInfo: SearchProgress? = null

    /**
     * `observeForever` 会**同步重放**上一次搜索的旧值；注册瞬间先把它拦掉。
     *
     * 真正的搜索结果都是 `App.post` 异步回来的，`decorView.post` 清标志一定排在它们前面，
     * 所以这里既不会吞掉本轮结果，也不会把上一轮的旧结果当成新结果。
     */
    private var ignoreReplay = false

    /**
     * 「焦点即选中」的防抖目标：焦点停稳后要切到的站点下标；-1 = 没有待切换项。
     *
     * 电视端按住 ←/→ 时焦点会连续跳格，如果每次 `onFocusChange` 都立刻 `selectSite`，
     * 滑过 5 个源就是 5 次 `searchContent`（还会互相 `stopSearchLocked` 取消）。
     * 这里只记最后落点，停稳 [SITE_FOCUS_DEBOUNCE_MS] 后再切。
     */
    private var pendingSiteFocus: Int = -1

    /**
     * 防抖任务：停稳后切到 [pendingSiteFocus]。
     *
     * 用「目标标签此刻是否真的还持有焦点」做最后校验，而不是在 `onFocusChange(false)` 里取消：
     * 焦点在子 View 之间转移时，系统派发 gain / loss 的先后顺序并不固定，
     * 依赖顺序去取消容易把刚排上的任务误删。这里只认最终持有焦点的那个标签。
     */
    private val siteFocusRunnable = Runnable {
        val target = pendingSiteFocus
        pendingSiteFocus = -1
        if (target >= 0) {
            val holder = siteRecycler.findViewHolderForAdapterPosition(target)
            if (holder?.itemView?.hasFocus() == true) selectSite(target)
        }
    }

    private val searchObserver = Observer<Result> { result -> onSearchResult(result) }

    private val progressObserver = Observer<SearchProgress> { progress -> onSearchProgress(progress) }

    /** `result` 通道（详情 / 分类共用）：只在弹窗自己发过请求时消费，见 [onResult]。 */
    private val resultObserver = Observer<Result?> { result -> onResult(result) }

    // ------------------------------------------------------------------
    // folder 下探（弹窗内展开目录，不跳详情页）
    // ------------------------------------------------------------------
    /** 下探栈：每层记录「从哪个源、哪个 folder 条目」展开；返回键逐级出栈。 */
    private val drillStack = ArrayList<DrillEntry>()

    /** 当前层目录结果；只在 [drillStack] 非空时有意义。 */
    private var categoryResults: List<Vod> = emptyList()

    /** 等 `result` 回包的目录 id；null = 没有在途的目录请求。 */
    private var pendingCategoryId: String? = null

    /** 等 `result` 回包的详情 id（卡片补线路）；null = 没有在途的详情请求。 */
    private var pendingDetailId: String? = null

    /** 补详情时记住是谁：回包后要按这个源起播。 */
    private var pendingPlaySite: Site? = null
    private var pendingPlayVod: Vod? = null

    // ------------------------------------------------------------------
    // 选集层（Compose `HeroPlayDialog`）
    // ------------------------------------------------------------------
    /**
     * 选集层要渲染的数据。用 `mutableStateOf` 而不是普通字段：
     * ComposeView 里的组合要跟着它重组。
     */
    private val playState = mutableStateOf<PlayRequest?>(null)

    /**
     * 选集层的 ComposeView 宿主。
     *
     * **挂到 Activity 的 `android.R.id.content` 上**，而不是挂到 Dialog 窗口：
     * 本弹窗是 `Dialog(context, theme)` 直接构造的，窗口的 decorView 没有 ViewTree
     * lifecycle / viewModelStore / savedState owner，Compose 组合会直接抛
     * `IllegalStateException: ViewTreeLifecycleOwner not found`；
     * Activity 的内容视图自带这三个 owner，复用它最稳。
     * `HeroPlayDialog` 自己又是一个 Compose Dialog 窗口，所以会盖在换源弹窗之上。
     */
    private var playHost: ComposeView? = null

    /** 是否已经跑过首轮数据（预搜索直出 / 全局并发搜索）。 */
    private var started = false

    private lateinit var homeBtn: FrameLayout
    private lateinit var siteRecycler: RecyclerView
    private lateinit var resultRecycler: RecyclerView
    private lateinit var empty: TextView
    private lateinit var progress: View
    private lateinit var resultCount: TextView
    private lateinit var backBtn: TextView
    private lateinit var siteLayout: View
    private lateinit var siteAdapter: SiteAdapter
    private lateinit var resultAdapter: ResultAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 【排查日志】能走到这里 = Dialog 已经构造成功，接下来只剩「窗口有没有显示」。
        Log.e(TAG, "弹窗 onCreate: sites=${sites.size}, keyword=[$keyword], quick=$quick")
        setContentView(R.layout.dialog_source)
        setupWindow()
        bindHeader()
        bindHome()
        bindSiteList()
        bindResultList()
        setOnDismissListener { onClosed() }
    }

    override fun onStart() {
        super.onStart()
        // 【排查日志】onStart = 窗口已经真正显示；没有这一行说明 show() 之前就失败了。
        Log.e(TAG, "弹窗 onStart: 候选源=${sites.size}, 已有 Tab=${tabSites.size}")
        // 焦点进出结果区时同步站点标签的红框（规则 ②③）。
        // 与 onStop 的移除成对，保证弹窗被复用/重新显示时监听不会丢也不会重复添加。
        window?.decorView?.viewTreeObserver?.addOnGlobalFocusChangeListener(focusListener)
        // 先立「忽略重放」标志再挂观察者：observeForever 的首次同步重放必须被拦掉。
        ignoreReplay = true
        viewModel.search.observeForever(searchObserver)
        viewModel.searchProgress.observeForever(progressObserver)
        // 详情 / 分类共用 `result` 通道：只认弹窗自己发出的请求（见 onResult 的闸门）。
        viewModel.result.observeForever(resultObserver)
        window?.decorView?.post { ignoreReplay = false }
        // 兜底：decorView 还没挂上时 post 可能不执行，用站点栏再挂一次（同一主线程队列，顺序无所谓）。
        siteRecycler.post { ignoreReplay = false }
        // 打开即**全局并发**检索：一次性把所有候选源丢进去，谁先回结果谁先出 Tab。
        // 宿主页已经预搜索过（[initialResults] 非空）时首帧直接铺开，跳过这一轮重复网络请求；
        // 之后（例如从起播页返回）再 onStart 才走正常的整轮重搜。
        if (!started) {
            started = true
            if (initialResults.isNotEmpty()) {
                renderPreloaded(initialResults)
            } else {
                startGlobalSearch()
            }
        } else {
            startGlobalSearch()
        }
        // 默认焦点落在第一个站点标签；必须等 ViewHolder 布局完成再请求，
        // 否则焦点会停在 RecyclerView 上，标签的 focused 红底就不会出现。
        siteRecycler.post {
            siteRecycler.scrollToPosition(selectedSite)
            focusSite(selectedSite)
        }
    }

    override fun onStop() {
        window?.decorView?.viewTreeObserver?.removeOnGlobalFocusChangeListener(focusListener)
        viewModel.search.removeObserver(searchObserver)
        viewModel.searchProgress.removeObserver(progressObserver)
        viewModel.result.removeObserver(resultObserver)
        // 关窗即作废待切换的焦点任务，避免弹窗消失后还发一次检索。
        pendingSiteFocus = -1
        siteRecycler.removeCallbacks(siteFocusRunnable)
        // 还在途的目录 / 详情回包一律不再消费：弹窗关了就没有 UI 承接它们。
        pendingCategoryId = null
        pendingDetailId = null
        ignoreReplay = false
        // 关闭弹窗就取消在途检索：蜘蛛实现可能忽略中断，stopSearch 至少让旧一轮结果不再回到 UI。
        viewModel.stopSearch()
        super.onStop()
    }

    /** 布局可能尚未完成，重试几次保证焦点落在标签上。 */
    private fun focusSite(position: Int, attempt: Int = 0) {
        // 从结果卡片按 ↑ 回来时目标标签可能已被横向滚出可视区，
        // 先把它滚进来，否则 findViewHolderForAdapterPosition 会一直取不到。
        if (attempt == 0) siteRecycler.scrollToPosition(position)
        val holder = siteRecycler.findViewHolderForAdapterPosition(position)
        if (holder != null && holder.itemView.requestFocus()) return
        if (attempt < FOCUS_RETRY_COUNT) {
            siteRecycler.postDelayed({ focusSite(position, attempt + 1) }, FOCUS_RETRY_DELAY_MS)
            return
        }
        // 兜底：仍取不到 ViewHolder 时至少让方向键能进入站点栏。
        siteRecycler.requestFocus()
    }

    /**
     * 窗口尺寸。
     *
     * 宽照搬 f23：`c0(0.82f)` → 屏幕宽 × 0.82。
     * 高先取 f23 的 `min(360dp, 屏高 × 0.65)`，再按内容高度兜底：
     * f23 的公式在 480dp 高的屏上只有 312dp，扣掉头部 / 站点栏 / 结果标题后，
     * 结果区不足 218dp（214dp 卡片 + 4dp 顶部内边距），会把卡片底部的剧名裁掉。
     * 所以最终高 = max(f23 公式, 内容所需)，并且至少给屏幕上下各留 [WINDOW_MARGIN_DP]。
     */
    private fun setupWindow() {
        val metrics = context.resources.displayMetrics
        val width = (metrics.widthPixels * WIDTH_FRACTION).toInt()
        val screenHeightDp = metrics.heightPixels / metrics.density
        val baseDp = min(MAX_HEIGHT_DP, (screenHeightDp * HEIGHT_FRACTION).roundToInt())
        val neededDp = DIALOG_CHROME_DP + RESULT_ROW_HEIGHT_DP
        val heightDp = min((screenHeightDp - WINDOW_MARGIN_DP).roundToInt(), maxOf(baseDp, neededDp))
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(width, (heightDp * metrics.density).toInt())
            setGravity(Gravity.CENTER)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(DIM_AMOUNT)
        }
    }

    private fun bindHeader() {
        findViewById<TextView>(R.id.badge).setText(R.string.play_change)
        resultCount = findViewById(R.id.resultCount)
        // 「⬅️ 返回」只在 folder 下探时出现：点一下退到上一级目录 / 回到搜索结果。
        backBtn = findViewById(R.id.backBtn)
        backBtn.setOnClickListener { exitDrillDown() }
        siteLayout = findViewById(R.id.siteLayout)
    }

    private fun bindHome() {
        homeBtn = findViewById(R.id.homeBtn)
        homeBtn.setOnClickListener { openSidebar() }
    }

    private fun bindSiteList() {
        siteRecycler = findViewById(R.id.siteRecycler)
        siteRecycler.layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
        siteRecycler.itemAnimator = null
        siteRecycler.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        siteRecycler.addItemDecoration(SpacingDecoration(dp(SITE_SPACING_DP)))
        siteAdapter = SiteAdapter()
        siteRecycler.adapter = siteAdapter
    }

    private fun bindResultList() {
        resultRecycler = findViewById(R.id.recycler)
        empty = findViewById(R.id.empty)
        progress = findViewById(R.id.progress)
        // 空态文案按「这个源没搜到这部剧」来写，不能沿用候选源为空的
        // 「暂无可换源」——那会把「搜不到」误导成「没有源可用」。
        empty.text = EMPTY_RESULT_TEXT
        resultRecycler.layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
        resultRecycler.itemAnimator = null
        resultRecycler.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        resultRecycler.addItemDecoration(SpacingDecoration(dp(RESULT_SPACING_DP)))
        resultAdapter = ResultAdapter()
        resultRecycler.adapter = resultAdapter
        render()
    }

    /**
     * 进度条 / 空态 / 结果列表三选一，并把数量写回头部。
     *
     * 三者的可见性只由 [searching] 与 [visibleResults] 决定，任何数据变化都只改这两个字段再调本方法，
     * 避免「隐藏了列表但忘了收空态」这类状态残留。
     */
    private fun render() {
        resultAdapter.notifyDataSetChanged()
        val hasResult = visibleResults.isNotEmpty()
        // 进度条只在「当前 Tab 还没有内容」时显示：已经有卡片时再盖一个转圈会挡住海报。
        progress.visibility = if (searching && !hasResult) View.VISIBLE else View.GONE
        resultRecycler.visibility = if (hasResult) View.VISIBLE else View.GONE
        empty.visibility = if (!searching && !hasResult) View.VISIBLE else View.GONE
        // 下探时说的是「目录」，不能沿用「该源暂无此剧」。
        empty.text = if (drillActive()) EMPTY_FOLDER_TEXT else EMPTY_RESULT_TEXT
        val info = progressInfo
        resultCount.visibility = View.VISIBLE
        resultCount.text = if (searching && info != null && info.total() > 0) {
            context.getString(R.string.search_loading) + " ${info.current()}/${info.total()}"
        } else {
            context.getString(R.string.play_change_count, visibleResults.size)
        }
    }

    private fun currentSite(): Site? = tabSites.getOrNull(selectedSite)

    /**
     * 站点栏切源（点击 / 确定键 / 焦点停稳）：**只换一份缓存渲染，不再发请求**。
     *
     * 全局并发搜索已经把每个源都搜过了，切 Tab 只是把 [resultsBySite] 里对应那一份拿出来。
     *
     * 注意这里**不能** `notifyDataSetChanged()`：焦点即选中时这次调用可能正好发生在
     * 焦点移动过程中，全量重绑会让 RecyclerView 重建子 View、把刚拿到的焦点丢掉。
     * 改为只同步现有子 View 的 `isSelected`（`syncSiteSelection()`）。
     */
    private fun selectSite(position: Int) {
        if (position !in tabSites.indices) return
        if (position == selectedSite) return
        selectedSite = position
        syncSiteSelection()
        showCurrentResults()
    }

    /** 把 `isSelected` 打到「当前站点」的那一个标签上；焦点态由 drawable 自己管。 */
    private fun syncSiteSelection() {
        for (index in 0 until siteRecycler.childCount) {
            val child = siteRecycler.getChildAt(index)
            val position = siteRecycler.getChildAdapterPosition(child)
            child.isSelected = position == selectedSite
        }
    }

    /** 把当前 Tab 的缓存结果铺到下方列表（找不到 = 该源还没回结果 = 空）。 */
    private fun showCurrentResults() {
        visibleResults = currentSite()?.let { resultsBySite[it.key] }.orEmpty()
        render()
    }

    /**
     * 全局并发检索：**一次性**把所有启用中的候选源丢给 `searchContent(List)`。
     *
     * 与原版 `VideoActivity.startSearch` / `TmdbDetailActivity.startInlineSourceSearch` 完全一致：
     * 不是一个个 Tab 懒加载，而是所有源同时搜；哪个源先回结果，哪个源就先长出 Tab。
     * 弹窗打开、侧边栏勾选变化都走这里（重搜必须整轮重来，Tab 是动态的，不能只增不减）。
     */
    private fun startGlobalSearch() {
        // 清空上一轮：Tab 从 0 个重新长出来；下探层级也一并退出（目录结果不属于新的一轮检索）。
        tabSites.clear()
        resultsBySite.clear()
        drillStack.clear()
        categoryResults = emptyList()
        pendingCategoryId = null
        pendingDetailId = null
        selectedSite = 0
        visibleResults = emptyList()
        progressInfo = null
        siteAdapter.notifyDataSetChanged()
        syncDrillUi()
        val targets = sites.filter { it.key in enabledSiteKeys }
        // 【致命日志】入参为空 = 不会有任何结果，必须留下证据；
        // quickSearch=false 的源会被 `SearchTask` 直接跳过，也在这里一并打出来。
        Log.e(
            TAG,
            "发起全局并发搜索: keyword=[$keyword], quick=$quick, " +
                "候选源=${sites.size}, 启用=${targets.size}, " +
                "quickSearch=false=${targets.count { !it.isQuickSearch }}, " +
                "keys=${targets.joinToString { it.key }}",
        )
        if (keyword.isBlank() || targets.isEmpty()) {
            // 【致命日志】这里 return = 一个请求都不会发，必须把原因写清楚，
            // 否则界面只会停在空态，看起来像「弹窗坏了」。
            Log.e(
                TAG,
                "全局搜索跳过: keyword空=${keyword.isBlank()}, 启用源=${targets.size}, " +
                    "候选源=${sites.size}, 勾选=${enabledSiteKeys.size}",
            )
            searching = false
            render()
            return
        }
        searching = true
        render()
        Log.e(TAG, "调用 searchContent(List): 源=${targets.size}, keyword=[$keyword], quick=$quick")
        viewModel.searchContent(targets, keyword, quick)
    }

    /**
     * 用宿主页预搜索的结果直接铺开（跳过弹窗内的首轮检索）。
     *
     * 结果已经按源归档：Tab 顺序沿用候选源 [sites] 的顺序（与站点栏、健康度排序一致），
     * 不在候选源里的源追加到末尾 —— 用户仍然能看到它、点它。
     * 空结果（预搜索还在途 / 一个都没搜到）不会走到这里，调用方会退回 [startGlobalSearch]。
     */
    private fun renderPreloaded(items: List<Vod>) {
        tabSites.clear()
        resultsBySite.clear()
        drillStack.clear()
        categoryResults = emptyList()
        pendingCategoryId = null
        pendingDetailId = null
        selectedSite = 0
        visibleResults = emptyList()
        progressInfo = null
        searching = false
        val order = sites.map { it.key }
        items
            .mapNotNull { vod -> vod.site?.let { site -> site to vod } }
            .filter { (_, vod) -> vod.id.isNotBlank() }
            .groupBy { (site, _) -> site.key }
            .toList()
            // 按候选源顺序排；不在候选源里的（order.indexOf = -1）统一排到最后。
            .sortedBy { (key, _) -> order.indexOf(key).let { if (it < 0) Int.MAX_VALUE else it } }
            .forEach { (key, pairs) ->
                tabSites.add(pairs.first().first)
                resultsBySite[key] = pairs.map { it.second }.distinctBy { it.id }
            }
        visibleResults = tabSites.firstOrNull()?.let { resultsBySite[it.key] }.orEmpty()
        siteAdapter.notifyDataSetChanged()
        syncDrillUi()
        render()
        Log.e(
            TAG,
            "预搜索直出: keyword=[$keyword], 源=${tabSites.size}, " +
                "首屏条目=${visibleResults.size}, keys=${tabSites.joinToString { it.key }}",
        )
        if (tabSites.isNotEmpty()) siteRecycler.post { focusSite(0) }
    }

    /**
     * `search` LiveData 回调：**一个源一条结果**。
     *
     * 结果里的每条 [Vod] 已由 `SiteApi.searchContent` 盖过 `setSite(site)`，
     * 所以这里用第一条的 `site.key` 归因，并把这个源**动态加进顶部 Tab**。
     * 空结果拿不到 siteKey（`Result` 不带源信息），无法归因，直接跳过 —— 它本来也没有卡可展示。
     */
    private fun onSearchResult(result: Result?) {
        if (ignoreReplay || result == null) return
        val items = result.list
        val site = items.firstOrNull()?.site
        if (site == null) {
            Log.e(TAG, "收到结果但无法归因（空结果或没有 site 标记）: listSize=${items.size}")
            return
        }
        val siteKey = site.key
        // 迟到结果（侧边栏刚取消勾选 / 上一轮遗留）一律丢弃。
        if (siteKey !in enabledSiteKeys) {
            Log.e(TAG, "收到结果但该源已不在启用列表，丢弃: site=$siteKey listSize=${items.size}")
            return
        }
        Log.e(
            TAG,
            "收到结果: site=$siteKey, listSize=${items.size}, " +
                "code=${result.code}, msg=${result.msg}",
        )
        val merged = resultsBySite.getOrElse(siteKey) { emptyList() } + items
        resultsBySite[siteKey] = merged.distinctBy { it.id }
        val existing = tabSites.indexOfFirst { it.key == siteKey }
        if (existing < 0) {
            tabSites.add(site)
            // 用插入通知而不是全量重绑：结果是一个个回来的，全量重绑会把焦点打飞。
            siteAdapter.notifyItemInserted(tabSites.lastIndex)
            Log.e(
                TAG,
                "新增 Tab: index=${tabSites.lastIndex}, site=$siteKey(${site.displayName}), " +
                    "已出结果的源=${tabSites.size}",
            )
            if (tabSites.size == 1) {
                // 第一个 Tab 出现：把它设为当前 Tab 并接管焦点（此刻焦点通常还停在空站点栏上）。
                selectedSite = 0
                visibleResults = resultsBySite[siteKey].orEmpty()
                val focused = window?.decorView?.findFocus()
                if (focused == null || focused === siteRecycler) {
                    siteRecycler.post { focusSite(0) }
                }
            }
        }
        if (currentSite()?.key == siteKey && !drillActive()) {
            // 下探期间列表展示的是目录结果，绝不能被迟到的搜索结果覆盖。
            visibleResults = resultsBySite[siteKey].orEmpty()
        }
        render()
    }

    /**
     * `searchProgress` LiveData 回调：全局并发检索的收尾。
     *
     * `total == 0` 表示所有源都被 `quick` 过滤掉（`Site.isQuickSearch() == false`），
     * 这种情况不会有任何结果回调，必须在这里手动结束，否则进度条会一直转。
     */
    private fun onSearchProgress(progress: SearchProgress?) {
        if (progress == null) return
        progressInfo = progress
        if (progress.finished()) {
            searching = false
            Log.e(
                TAG,
                "全局搜索结束: current=${progress.current()} total=${progress.total()} " +
                    "出结果的源=${tabSites.size}(${tabSites.joinToString { it.key }})",
            )
        }
        render()
    }

    // ------------------------------------------------------------------
    // `result` 通道：folder 下探 / 卡片补详情（只认弹窗自己发出的请求）
    // ------------------------------------------------------------------

    /**
     * `result` 通道回调（详情 / 分类共用一条 LiveData）。
     *
     * **必须做闸门**：宿主 Activity 自己也可能在拉详情 / 首页数据，
     * 那些结果与弹窗无关，绝不能拿来当目录或选集数据。
     * 只认 [pendingDetailId] / [pendingCategoryId] 非空时回来的那一条。
     */
    private fun onResult(result: Result?) {
        if (result == null) return
        // ---- 优先级 1：卡片补详情 → 直达选集 ----
        val requestedId = pendingDetailId
        if (requestedId != null) {
            pendingDetailId = null
            val incoming = result.vod
            val flags = incoming.flags
            Log.e(
                TAG,
                "卡片补详情返回: id=${incoming.id} flags=${flags.size} " +
                    "集数=${flags.sumOf { it.episodes.orEmpty().size }} code=${result.code} msg=${result.msg}",
            )
            if (flags.isEmpty()) {
                Notify.show("该剧暂无可用线路")
                return
            }
            // 详情结果比搜索结果完整：剧名 / 海报 / 线路都以它为准；
            // 起播 id 用**当初请求的那个**（详情回包偶尔不带 id，不能因此丢掉播放能力）。
            val fallback = pendingPlayVod
            showPlay(
                flags = flags,
                vod = incoming.takeIf { it.name.isNotBlank() } ?: fallback,
                siteKey = pendingPlaySite?.key.orEmpty(),
                vodId = requestedId,
            )
            pendingPlayVod = null
            pendingPlaySite = null
            return
        }
        // ---- 优先级 2：folder 下探的目录结果 ----
        val folderId = pendingCategoryId ?: return
        pendingCategoryId = null
        val current = drillStack.lastOrNull() ?: return
        // 分类接口不会给每条结果盖 site（原版整页共用一个 key），这里补上：
        // 卡片左上角的站点标签、点开后的选集弹窗都要靠它。
        val items = result.list.map { vod ->
            if (vod.site == null) vod.apply { site = current.site } else vod
        }
        categoryResults = items
        visibleResults = items
        searching = false
        Log.e(
            TAG,
            "下探结果: folder=$folderId depth=${drillStack.size} " +
                "listSize=${items.size} code=${result.code} msg=${result.msg}",
        )
        render()
        if (items.isNotEmpty()) focusFirstResult() else backBtn.post { backBtn.requestFocus() }
    }

    /** 当前是否处于 folder 下探（有层级 = 展示目录而不是搜索结果）。 */
    private fun drillActive(): Boolean = drillStack.isNotEmpty()

    /**
     * 展开一个 folder 条目：压栈 + 用**分类接口**拉目录（`t=vod.id`），留在弹窗内。
     *
     * 与原版 `CollectActivity.onItemClick → VodActivity.start(..., Result.folder(item))` 语义一致，
     * 区别只是落点从「原版分类页」换成弹窗内的海报行。
     */
    private fun enterDrillDown(vod: Vod, site: Site) {
        drillStack.add(DrillEntry(site, vod))
        categoryResults = emptyList()
        visibleResults = emptyList()
        searching = true
        pendingCategoryId = vod.id
        Log.e(
            TAG,
            "下探 folder: site=${site.key} id=${vod.id} name=${vod.name} " +
                "depth=${drillStack.size} api=${site.api}",
        )
        syncDrillUi()
        render()
        viewModel.categoryContent(site.key, vod.id, "1", true, hashMapOf())
    }

    /**
     * 返回上一级：出栈。
     *
     * - 还有上级目录 → 重新展开上级（分类结果本来就会被下级覆盖，必须重新请求）；
     * - 已经到顶 → 回到搜索结果（[resultsBySite] 从未被改动，直接换缓存渲染）。
     */
    private fun exitDrillDown() {
        if (drillStack.isEmpty()) return
        drillStack.removeAt(drillStack.lastIndex)
        categoryResults = emptyList()
        pendingCategoryId = null
        Log.e(TAG, "返回上一级: 剩余层级=${drillStack.size}")
        val parent = drillStack.lastOrNull()
        if (parent == null) {
            searching = false
            syncDrillUi()
            showCurrentResults()
            siteRecycler.post { focusSite(selectedSite) }
        } else {
            visibleResults = emptyList()
            searching = true
            pendingCategoryId = parent.vod.id
            render()
            viewModel.categoryContent(parent.site.key, parent.vod.id, "1", true, hashMapOf())
        }
    }

    /** 同步「下探态」的外壳：返回按钮 / 站点栏的显隐。 */
    private fun syncDrillUi() {
        val active = drillActive()
        backBtn.visibility = if (active) View.VISIBLE else View.GONE
        siteLayout.visibility = if (active) View.GONE else View.VISIBLE
        if (active) backBtn.post { if (drillActive() && !backBtn.hasFocus()) backBtn.requestFocus() }
    }

    /** 目录结果到达后把焦点落到第一张海报；ViewHolder 还没布局好时再补一次。 */
    private fun focusFirstResult() {
        resultRecycler.post {
            val holder = resultRecycler.findViewHolderForAdapterPosition(0)
            if (holder != null) {
                holder.itemView.requestFocus()
            } else {
                resultRecycler.post {
                    resultRecycler.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 结果卡点击：folder 留在弹窗内下探，普通剧集直达选集
    // ------------------------------------------------------------------

    /**
     * 结果卡确定键：**都不跳详情页**。
     *
     * - folder 条目（`vod_tag=folder` / `atvp_detail:` 前缀）→ 弹窗内下探目录；
     * - 普通剧集 → 拉一次 `detailContent` 拿线路，然后弹 [HeroPlayDialog]。
     */
    private fun openResult(vod: Vod, site: Site) {
        if (vod.isDrillDownFolder()) {
            enterDrillDown(vod, site)
            return
        }
        if (vod.id.isBlank()) {
            Notify.show("缺少剧集 ID，无法选集")
            return
        }
        // 分类 / 聚合结果常常自带线路：直接弹选集，不再多发一次详情请求。
        val inline = vod.flags
        if (inline.isNotEmpty()) {
            Log.e(
                TAG,
                "卡片直达选集: id=${vod.id} site=${site.key} flags=${inline.size} " +
                    "集数=${inline.sumOf { it.episodes.orEmpty().size }}",
            )
            showPlay(inline, vod, site.key, vod.id)
            return
        }
        pendingDetailId = vod.id
        pendingPlayVod = vod
        pendingPlaySite = site
        Log.e(TAG, "卡片无线路，拉详情后直达选集: id=${vod.id} site=${site.key}")
        viewModel.detailContent(site.key, vod.id)
    }

    // ------------------------------------------------------------------
    // 选集层（Compose `HeroPlayDialog`，挂在 Activity 内容视图上）
    // ------------------------------------------------------------------

    /** 渲染选集层：第一次调用时创建 ComposeView 并挂到 Activity 的 `android.R.id.content`。 */
    private fun showPlay(flags: List<Flag>, vod: Vod?, siteKey: String, vodId: String) {
        if (flags.isEmpty()) return
        if (ensurePlayHost() == null) return
        playState.value = PlayRequest(flags = flags, vod = vod, siteKey = siteKey, vodId = vodId)
    }

    private fun ensurePlayHost(): ComposeView? {
        playHost?.let { return it }
        val activity = context.findHostActivity()
        if (activity == null) {
            Log.e(TAG, "没有 Activity，无法显示选集层")
            Notify.show("无法打开选集：缺少 Activity")
            return null
        }
        val view = ComposeView(activity)
        // Activity 销毁时自动销毁组合，避免把 composition 留在已经没用的 View 上。
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        // 0×0：这里只负责承载组合，真正的 UI 是 HeroPlayDialog 自己的 Dialog 窗口。
        view.layoutParams = ViewGroup.LayoutParams(0, 0)
        view.setContent {
            val request = playState.value
            if (request != null) {
                HeroPlayDialog(
                    flags = request.flags,
                    // 换源 / 搜索场景还没接历史库：-1 = 新剧，焦点落第 1 集。
                    lastWatchedEpisodeIndex = -1,
                    onEpisodeSelected = { flag, episode ->
                        playState.value = null
                        startEpisode(
                            activity,
                            request.siteKey,
                            request.vod,
                            request.vodId,
                            flag,
                            episode,
                        )
                    },
                    onDismiss = { playState.value = null },
                )
            }
        }
        (activity.findViewById<ViewGroup>(android.R.id.content))?.addView(view)
        playHost = view
        return view
    }

    override fun dismiss() {
        super.dismiss()
        // 选集层挂在宿主 Activity 上，必须随弹窗一起摘掉，绝不留一个 0×0 的组合在页面上。
        playState.value = null
        playHost?.let { view -> (view.parent as? ViewGroup)?.removeView(view) }
        playHost = null
    }

    /** 返回键：有下探层级先退一级，到顶才关弹窗。 */
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (drillActive()) {
            exitDrillDown()
            return
        }
        super.onBackPressed()
    }

    /** 当前焦点是否还在下方结果区。只在状态真的变化时刷新标签背景，避免无谓重绘。 */
    private fun setContentFocused(value: Boolean) {
        if (contentFocused == value) return
        contentFocused = value
        syncSiteActivation()
    }

    /**
     * 把 `isActivated` 打到「当前站点且焦点在结果区」的那一个标签上。
     * drawable 的 `state_activated` 项据此显示红框（shape_source_site_chip_selected）。
     */
    private fun syncSiteActivation() {
        for (index in 0 until siteRecycler.childCount) {
            val child = siteRecycler.getChildAt(index)
            val position = siteRecycler.getChildAdapterPosition(child)
            child.isActivated = contentFocused && position == selectedSite
        }
    }

    private fun isDescendantOf(parent: View, child: View): Boolean {
        var current: View? = child
        while (current != null) {
            if (current === parent) return true
            current = current.parent as? View
        }
        return false
    }

    /**
     * 弹窗内小房子：拉起多选侧边栏（层级盖在换源弹窗之上，后创建的窗口在上层）。
     *
     * 条目用**全量候选源**（[sites]）：取消勾选的源仍然留在侧边栏里，才能再勾回来。
     * 勾选结果只限定「允许发请求的源」，不改变站点栏的静态 Tab。
     */
    private fun openSidebar() {
        SourceSidebar.show(
            context = context,
            entries = HeroSourceEntry.of(sites),
            mode = SourceSidebarMode.Multi,
            initialChecked = checkedFlags,
            onCheckedChanged = { applySiteFilter(it) },
            onClosed = { homeBtn.post { homeBtn.requestFocus() } },
        )
    }

    /** 多选联动：勾选集合 → 允许检索的源集合，然后整轮重搜（全局并发，Tab 动态长出来）。 */
    private fun applySiteFilter(flags: BooleanArray) {
        checkedFlags = flags.copyOf()
        enabledSiteKeys = sites
            .filterIndexed { index, _ -> flags.getOrElse(index) { false } }
            .map { it.key }
            .toSet()
        startGlobalSearch()
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()

    /** f23 用 `setHorizontalSpacing` 控制条目间距；RecyclerView 用等价的 ItemDecoration。 */
    private class SpacingDecoration(private val spacing: Int) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: android.graphics.Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State,
        ) {
            val position = parent.getChildAdapterPosition(view)
            if (position != RecyclerView.NO_POSITION) outRect.right = spacing
        }
    }

    private inner class SiteAdapter : RecyclerView.Adapter<SiteAdapter.Holder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = layoutInflater.inflate(R.layout.adapter_source_site, parent, false)
            return Holder(view)
        }

        override fun getItemCount(): Int = tabSites.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            // 显示名走 Site.getDisplayName()：尊重用户在站点管理里的改名。
            holder.name.text = tabSites[position].displayName
            holder.itemView.isSelected = position == selectedSite
            // 规则 ②：当前站点 + 焦点在结果区 → 红框（drawable 的 state_activated 分支）。
            holder.itemView.isActivated = contentFocused && position == selectedSite
            // 第一个站点标签向左回到小房子；其余在站点栏内滚动。
            holder.itemView.nextFocusLeftId = if (position == 0) R.id.homeBtn else View.NO_ID
            holder.itemView.setOnClickListener { selectSite(holder.bindingAdapterPosition) }
            // 【焦点即选中】焦点停稳即切源：电视端不用再按确定键。
            // - 停稳 [SITE_FOCUS_DEBOUNCE_MS] 才真正 `selectSite`，快速滑过多个源不会连发请求；
            // - 每次 gain 都重排任务（覆盖上一个），真正执行时再校验「该标签仍持有焦点」；
            // - 监听器在每次 bind 时重设，旧监听器被覆盖，不会累积。
            holder.itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    pendingSiteFocus = holder.bindingAdapterPosition
                    siteRecycler.removeCallbacks(siteFocusRunnable)
                    siteRecycler.postDelayed(siteFocusRunnable, SITE_FOCUS_DEBOUNCE_MS)
                }
            }
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.text)
        }
    }

    /**
     * 结果卡适配器。
     *
     * 卡片本体是全局唯一组件 [HeroPosterCard]：尺寸 120×214、四角 8dp 全圆、
     * 2dp 纯红 #FF0000 焦点框只包 120×180 海报——**全部写死在组件内部**，
     * 这里只塞四个内容字段，不允许再 inflate 布局或改宽高。
     */
    private inner class ResultAdapter : RecyclerView.Adapter<ResultAdapter.Holder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            return Holder(HeroPosterCard(parent.context))
        }

        override fun getItemCount(): Int = visibleResults.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val vod = visibleResults[position]
            // 字段与逆向 adapter_search.xml / ks2.java 一一对应：
            // title          → 16sp 剧名（ks2 L74 `setText(vod.getName())`）
            // bottomStartTag → 14sp 备注（ks2 L78-79 `setText(vod.getRemarks())`）
            // topStartTag    → 13sp 左上角标签（ks2 L77 `setText(vod.getSiteName())`），
            //                  本工程按需求只显示来源，不再拼日期；
            //                  组件内 maxWidth 110dp（< 海报 120dp）+ 单行省略，永远不越出海报。
            holder.card.title = vod.name
            holder.card.imageUrl = vod.pic
            holder.card.topStartTag = vod.siteName
            holder.card.bottomStartTag = vod.remarks
            holder.card.setOnClickListener {
                // 用 getOrNull 而不是闭包里的 position：结果列表可能已被新的搜索替换。
                val item = visibleResults.getOrNull(position) ?: return@setOnClickListener
                // vod 已由 SiteApi 盖过 site；万一第三方蜘蛛自己丢了 site，退回当前 Tab 的源。
                val site = item.site ?: currentSite() ?: return@setOnClickListener
                // 点卡片**不再跳详情页**：folder 就地展开目录，普通剧集直接弹选集。
                openResult(item, site)
            }
            // 卡片按 ↑ 必须回到**当前选中的站点标签**（《焦点总结》规则 ④的同一条思路）。
            // 弹窗布局里 recycler 的 nextFocusUp 指向整个 siteRecycler，焦点只会落到
            // RecyclerView 容器上，标签本身既不高亮也不滚动，用户看不出自己回到了哪个站点；
            // 所以在这里拦一次，直接点名请求那个标签的焦点。
            // 下探期间站点栏是隐藏的：↑ 改成回到「⬅️ 返回」，否则焦点会卡死在卡片上。
            holder.card.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    if (drillActive()) backBtn.requestFocus() else focusSite(selectedSite)
                    true
                } else {
                    false
                }
            }
        }

        inner class Holder(val card: HeroPosterCard) : RecyclerView.ViewHolder(card)
    }

    companion object {

        private const val TAG = "ChangeSourceDialog"

        /** 结果区空态：当前源没搜到这部剧。 */
        private const val EMPTY_RESULT_TEXT = "该源暂无此剧"

        /** 结果区空态：folder 下探后目录为空（不能说「暂无此剧」）。 */
        private const val EMPTY_FOLDER_TEXT = "该目录暂无内容"

        /** f23：`c0(0.82f)`，窗口宽 = 屏幕宽 × 0.82。 */
        private const val WIDTH_FRACTION = 0.82f

        /** f23：`Math.min(360, round(screenHeightDp * 0.65f))`。 */
        private const val MAX_HEIGHT_DP = 360
        private const val HEIGHT_FRACTION = 0.65f

        /** f23：`siteRecycler.setHorizontalSpacing(dp(12))`。 */
        private const val SITE_SPACING_DP = 12

        /** f23：`recycler.setHorizontalSpacing(dp(16))`。 */
        private const val RESULT_SPACING_DP = 16

        /**
         * 结果卡整高，**直接取组件常量**，不允许在弹窗里重复写 214：
         * [HeroPosterCard.CARD_HEIGHT_DP] = 海报 180dp + 剧名栏 34dp。
         */
        private const val RESULT_CARD_HEIGHT_DP = HeroPosterCard.CARD_HEIGHT_DP

        /** `@id/recycler` 的 paddingTop（4dp）。 */
        private const val RESULT_ROW_PADDING_DP = 4

        /** 结果区最小高度 = 卡片 214dp + `@id/recycler` 的 paddingTop 4dp = 218dp。 */
        private const val RESULT_ROW_HEIGHT_DP = RESULT_CARD_HEIGHT_DP + RESULT_ROW_PADDING_DP

        /** 弹窗其余固定高度：root paddingTop 18 + 头部 40 + 站点栏 40 + 结果标题行 20 + 8。 */
        private const val DIALOG_CHROME_DP = 126

        /** 窗口上下至少留出的屏幕边距，避免顶到屏幕边缘。 */
        private const val WINDOW_MARGIN_DP = 16

        private const val DIM_AMOUNT = 0.6f

        /** 初始焦点重试：ViewHolder 未布局完成时，焦点会停在 RecyclerView 上，标签就没有红底。 */
        private const val FOCUS_RETRY_COUNT = 6
        private const val FOCUS_RETRY_DELAY_MS = 50L

        /**
         * 「焦点即选中」的请求防抖窗口（毫秒）。
         *
         * 按住 ←/→ 时焦点约每 50ms 跳一格，停留 160ms 才认为用户「停在这个源上」，
         * 期间滑过的源一个请求都不发；视觉上焦点红底仍然即时跟随（纯 View 状态）。
         */
        private const val SITE_FOCUS_DEBOUNCE_MS = 160L

        /**
         * 换源候选源：**与 `TmdbDetailActivity.isChangeSourceCandidate`（L11654）等价的规则**。
         *
         * 1. [SiteBlockSetting.filter]`(sites, false)` 先去掉隐藏源（`isHide`）和用户屏蔽源；
         * 2. `isSearchable()` —— 该源支持搜索，否则换源检索永远拿不到结果；
         * 3. `isChangeable()` —— 站点允许被换源（部分源明确禁止）；
         * 4. 排除 key 为空的坏配置；
         * 5. 排除 [currentSiteKey]（当前正在看的源）与 [excludedSiteKeys]（本次会话已知坏源）；
         * 6. 最后交给 [SiteHealthStore.sortSites]，按站点健康度排序（关闭健康度排序时原序返回）。
         *
         * @param currentSiteKey 当前源 key（`Site.getKey()`），空串表示不排除任何源。
         * @param excludedSiteKeys 额外排除的源 key（例如本次会话里请求失败过的源）。
         */
        fun candidates(
            currentSiteKey: String,
            excludedSiteKeys: Set<String> = emptySet(),
        ): List<Site> {
            val items = ArrayList<Site>()
            for (site in SiteBlockSetting.filter(VodConfig.get().getSites(), false)) {
                if (site.isEmpty || !site.isSearchable || !site.isChangeable) continue
                val key = site.key
                if (key.isEmpty() || key == currentSiteKey || key in excludedSiteKeys) continue
                items.add(site)
            }
            SiteHealthStore.sortSites(items)
            return items
        }

        /**
         * 打开换源弹窗。
         *
         * @param viewModel Activity 作用域的 [SiteViewModel]（**必须复用**，不能在这里 new）。
         * @param sites 候选源，直接用 [candidates] 的返回值；为空时自动退回「所有可搜索源」。
         * @param keyword 检索关键词（首页当前这张卡的剧名）。
         * @param quick 是否只搜 quick 源。换源默认 `false`：`quick=true` 时 `SearchTask` 会跳过
         *   `quickSearch=0` 的源（很多源都是这种），表现为整屏「该源暂无此剧」。
         *   原版 `VideoActivity.startSearch` 用 `true`，但换源场景为了多搜出结果先走 `false`。
         * @param initialResults 宿主页预搜索的现成结果（详情页进页就在后台搜了一轮）。
         *   非空时弹窗首帧直接铺开，**跳过弹窗内的首轮检索**；空则照常全局并发搜索。
         * @param onSourceSelected 保留给调用方的切换提示回调（结果卡点击已改为弹窗内直达选集）。
         * @param onClosed 关闭回调，调用方在这里把焦点交还给首页卡片。
         */
        fun show(
            context: Context,
            viewModel: SiteViewModel,
            sites: List<Site>,
            keyword: String,
            quick: Boolean = false,
            initialResults: List<Vod> = emptyList(),
            onSourceSelected: (Vod, Site) -> Unit = { _, _ -> },
            onClosed: () -> Unit = {},
        ): ChangeSourceDialog {
            // 【致命日志 + 兜底】候选源为空时不再静默失败：退回「所有可搜索源」，
            // 让弹窗至少能搜出东西，同时把情况打进日志。
            val searchSites = if (sites.isEmpty()) {
                Log.e(TAG, "候选源为空，退回全部可搜索源")
                VodConfig.get().sites.filter { it.isSearchable }
            } else {
                sites
            }
            Log.e(
                "ChangeSource",
                "呼出换源弹窗: sites=${searchSites.size}, keyword=[$keyword], quick=$quick, " +
                    "预搜索结果=${initialResults.size}",
            )
            val dialog = ChangeSourceDialog(
                context, viewModel, searchSites, keyword, quick, initialResults,
                onSourceSelected, onClosed,
            )
            dialog.show()
            return dialog
        }
    }
}

// ---------------------------------------------------------------------------
// 弹窗内的顶层辅助声明
// ---------------------------------------------------------------------------

/** 选集层要渲染的数据：线路 + 这部剧 + 起播源 key + 起播用的剧集 id。 */
private data class PlayRequest(
    val flags: List<Flag>,
    val vod: Vod?,
    val siteKey: String,
    val vodId: String,
)

/** 下探栈的一层：从哪个源的哪个 folder 条目展开。 */
private data class DrillEntry(val site: Site, val vod: Vod)

/**
 * 从任意 Context 找到承载窗口的 Activity。
 *
 * 弹窗用的是 `Dialog(context, theme)`，`context` 可能是 Activity，也可能是
 * ContextThemeWrapper；选集层的 ComposeView 必须挂到 Activity 的 content 上（那里才有
 * ViewTreeLifecycleOwner / ViewModelStore / SavedStateRegistry），所以要逐层拆包装。
 */
private fun Context.findHostActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}

/** 网盘聚合插件的 folder 壳前缀（与 `Vod.isFolder()` 的 `vod_tag=folder` 等价）。 */
private const val ATVP_FOLDER_PREFIX = "atvp_detail:"

/**
 * folder 判定：原版在点击处就分流到分类页，而搜索结果可能丢掉 `vod_tag=folder`，
 * 所以这里连 `atvp_detail:` 前缀一起认（与 `HeroSearchActivity` / `HeroEntryRoute` 一致）。
 */
private fun Vod.isDrillDownFolder(): Boolean =
    isFolder || id.startsWith(ATVP_FOLDER_PREFIX, ignoreCase = true)
