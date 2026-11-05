package com.fongmi.android.tv.ui.hero

import android.app.Dialog
import android.content.Context
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
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fongmi.android.tv.R
import com.fongmi.android.tv.api.config.VodConfig
import com.fongmi.android.tv.bean.Result
import com.fongmi.android.tv.bean.Site
import com.fongmi.android.tv.bean.Vod
import com.fongmi.android.tv.model.SearchProgress
import com.fongmi.android.tv.model.SiteViewModel
import com.fongmi.android.tv.setting.SiteBlockSetting
import com.fongmi.android.tv.setting.SiteHealthStore
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
 * ## 数据（第二阶段：真实换源）
 * - 候选源由 [candidates] 统一给出，规则与 `TmdbDetailActivity.isChangeSourceCandidate` 等价：
 *   `isSearchable()` + `isChangeable()` + 非隐藏 + 未被用户屏蔽 + 不是当前源，最后按
 *   [SiteHealthStore.sortSites] 的站点健康度排序。
 * - 检索走 [SiteViewModel.searchContent] 的 **List 重载**（写 `search` LiveData），
 *   **绝不能用** 4 参单源重载 —— 那个写的是 `result` LiveData，会和首页 `homeContent()` 抢同一条通道。
 * - `quick = false`：`SiteViewModel.searchContent(List)` 在 `quick=true` 时会跳过
 *   `isQuickSearch()==false` 的源（很多源都是这种），换源会整屏「该源暂无此剧」。
 * - Tab 栏是**静态**的（= 传入的候选源），切到哪个才搜哪个；每个源的结果缓存在 [resultsBySite]，
 *   来回切 Tab 不会重复请求。侧边栏里没勾的源**根本不发请求**。
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
    private val onSourceSelected: (Vod, Site) -> Unit,
    private val onClosed: () -> Unit,
) : Dialog(context, R.style.SourceDialogTheme) {

    /** 当前站点栏下标。Tab 是静态的，所以它同时就是 [sites] 的下标。 */
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
     * 用 `containsKey` 而不是单独的 `searchedSites` 集合：`searchContent(List)` 每次都会先
     * `stopSearchLocked()` 取消上一轮在途请求，被取消的源不会有任何回调，
     * 只有「结果已经落到这里」才算真的搜过。
     */
    private val resultsBySite = LinkedHashMap<String, List<Vod>>()

    /**
     * 当前在途请求属于哪个源；`null` = 没有在途请求。
     *
     * `observeForever` 会**立刻重放**上一次搜索的旧值，所以它同时是旧值闸门：
     * 回调里一律 `pendingSiteKey ?: return`，只有我们自己发起的那一轮才被接受。
     */
    private var pendingSiteKey: String? = null

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

    private lateinit var homeBtn: FrameLayout
    private lateinit var siteRecycler: RecyclerView
    private lateinit var resultRecycler: RecyclerView
    private lateinit var empty: TextView
    private lateinit var progress: View
    private lateinit var resultCount: TextView
    private lateinit var siteAdapter: SiteAdapter
    private lateinit var resultAdapter: ResultAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        // 焦点进出结果区时同步站点标签的红框（规则 ②③）。
        // 与 onStop 的移除成对，保证弹窗被复用/重新显示时监听不会丢也不会重复添加。
        window?.decorView?.viewTreeObserver?.addOnGlobalFocusChangeListener(focusListener)
        // 先清在途标记，再挂观察者：observeForever 的首次重放必须被 pendingSiteKey 拦掉。
        pendingSiteKey = null
        viewModel.search.observeForever(searchObserver)
        viewModel.searchProgress.observeForever(progressObserver)
        // 默认焦点落在当前站点标签；必须等 ViewHolder 布局完成再请求，
        // 否则焦点会停在 RecyclerView 上，标签的 focused 红底就不会出现。
        siteRecycler.post {
            siteRecycler.scrollToPosition(selectedSite)
            focusSite(selectedSite)
        }
        // 打开即搜第一个候选源（Tab 是静态的，所以「切到哪个才搜哪个」的第一个就是它）。
        refreshCurrent()
    }

    override fun onStop() {
        window?.decorView?.viewTreeObserver?.removeOnGlobalFocusChangeListener(focusListener)
        viewModel.search.removeObserver(searchObserver)
        viewModel.searchProgress.removeObserver(progressObserver)
        // 关窗即作废待切换的焦点任务，避免弹窗消失后还发一次检索。
        pendingSiteFocus = -1
        siteRecycler.removeCallbacks(siteFocusRunnable)
        pendingSiteKey = null
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
        progress.visibility = if (searching) View.VISIBLE else View.GONE
        resultRecycler.visibility = if (!searching && hasResult) View.VISIBLE else View.GONE
        empty.visibility = if (!searching && !hasResult) View.VISIBLE else View.GONE
        resultCount.visibility = if (searching) View.INVISIBLE else View.VISIBLE
        resultCount.text = context.getString(R.string.play_change_count, visibleResults.size)
    }

    private fun currentSite(): Site? = sites.getOrNull(selectedSite)

    /**
     * 站点栏切源（点击 / 确定键 / 焦点停稳）：切到该源并保证它有结果（没搜过就补搜）。
     *
     * 注意这里**不能** `notifyDataSetChanged()`：焦点即选中时这次调用可能正好发生在
     * 焦点移动过程中，全量重绑会让 RecyclerView 重建子 View、把刚拿到的焦点丢掉。
     * 改为只同步现有子 View 的 `isSelected`（`syncSiteSelection()`）。
     */
    private fun selectSite(position: Int) {
        if (position !in sites.indices) return
        if (position == selectedSite) return
        selectedSite = position
        syncSiteSelection()
        refreshCurrent()
    }

    /** 把 `isSelected` 打到「当前站点」的那一个标签上；焦点态由 drawable 自己管。 */
    private fun syncSiteSelection() {
        for (index in 0 until siteRecycler.childCount) {
            val child = siteRecycler.getChildAt(index)
            val position = siteRecycler.getChildAdapterPosition(child)
            child.isSelected = position == selectedSite
        }
    }

    /**
     * 让「当前选中的站点」与 UI 保持一致：
     * - 该源被侧边栏取消勾选 → 不发请求，直接空态；
     * - 该源已有结果 → 直接渲染缓存；
     * - 该源正在搜 → 保持进度条；
     * - 其余情况 → 发起检索。
     */
    private fun refreshCurrent() {
        val site = currentSite() ?: return
        val key = site.key
        when {
            keyword.isBlank() -> {
                // 【致命日志】关键词为空时根本不会发请求，这里必须留下证据，
                // 否则只会看到整屏「该源暂无此剧」，误以为是源里没有这部剧。
                Log.e(TAG, "换源搜索跳过: keyword 为空，无法检索")
                pendingSiteKey = null
                searching = false
                visibleResults = emptyList()
                render()
            }

            key !in enabledSiteKeys -> {
                pendingSiteKey = null
                searching = false
                viewModel.stopSearch()
                visibleResults = emptyList()
                render()
            }

            pendingSiteKey == key -> Unit

            resultsBySite.containsKey(key) -> {
                searching = false
                visibleResults = resultsBySite[key].orEmpty()
                render()
            }

            else -> startSearch(site)
        }
    }

    /** 只搜一个源：走 List 重载（写 `search`），和首页 `homeContent()` 的 `result` 通道互不干扰。 */
    private fun startSearch(site: Site) {
        pendingSiteKey = site.key
        searching = true
        visibleResults = emptyList()
        render()
        // 【致命日志 1】换源搜索入参：keyword 为空 / 源被 quick 过滤，都会让结果永远为空。
        // `SiteViewModel.searchContent(List)` 在 `quick && !site.isQuickSearch()` 时直接跳过该源，
        // 所以这里同时打印 quickSearch，能一眼看出「是没搜」还是「搜了没结果」。
        Log.e(
            TAG,
            "换源搜索入参: site=${site.key}, keyword=$keyword, quick=$quick, " +
                "name=${site.displayName}, searchable=${site.isSearchable}, " +
                "quickSearch=${site.isQuickSearch}",
        )
        viewModel.searchContent(listOf(site), keyword, quick)
    }

    /**
     * `search` LiveData 回调：**一个源一条结果**。
     *
     * 结果里的每条 [Vod] 已由 `SiteApi.searchContent` 盖过 `setSite(site)`，不需要再补。
     */
    private fun onSearchResult(result: Result?) {
        val siteKey = pendingSiteKey ?: return
        val items = result?.list.orEmpty()
        // 【致命日志 2】结果形状：listSize=0 且 code/msg 正常，说明源确实没搜到；
        // 如果这里根本没有日志，说明请求被 quick 过滤掉了（见 startSearch 的入参日志）。
        Log.e(
            TAG,
            "换源搜索结果: site=$siteKey listSize=${items.size}, " +
                "code=${result?.code}, msg=${result?.msg}",
        )
        if (items.isNotEmpty()) {
            val merged = resultsBySite.getOrElse(siteKey) { emptyList() } + items
            resultsBySite[siteKey] = merged.distinctBy { it.id }
        } else if (!resultsBySite.containsKey(siteKey)) {
            resultsBySite[siteKey] = emptyList()
        }
        if (siteKey == currentSite()?.key) {
            visibleResults = resultsBySite[siteKey].orEmpty()
            render()
        }
    }

    /**
     * `searchProgress` LiveData 回调：只认 [pendingSiteKey] 那一轮，`finished()` 时才收尾。
     *
     * `total == 0` 表示所有源都被 `quick` 过滤掉（`Site.isQuickSearch() == false`），
     * 这种情况不会有任何结果回调，必须在这里手动结束，否则进度条会一直转。
     */
    private fun onSearchProgress(progress: SearchProgress?) {
        if (progress == null || !progress.finished()) return
        val siteKey = pendingSiteKey ?: return
        // 【致命日志 3】收尾：total=0 表示这个源被 quick 过滤（根本没发请求），
        // 不是「源里没有这部剧」。换源现在统一 quick=false，正常情况下 total 应为 1。
        Log.e(
            TAG,
            "换源搜索结束: site=$siteKey current=${progress.current()} total=${progress.total()}",
        )
        pendingSiteKey = null
        searching = false
        // 搜完但没有结果（含失败 / 被 quick 过滤）也记一条空结果，避免每次切回这个 Tab 都重搜。
        if (!resultsBySite.containsKey(siteKey)) resultsBySite[siteKey] = emptyList()
        if (siteKey == currentSite()?.key) {
            visibleResults = resultsBySite[siteKey].orEmpty()
            render()
        }
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

    /** 多选联动：勾选集合 → 允许检索的源集合，然后让当前站点重新对账（该补搜就补搜）。 */
    private fun applySiteFilter(flags: BooleanArray) {
        checkedFlags = flags.copyOf()
        enabledSiteKeys = sites
            .filterIndexed { index, _ -> flags.getOrElse(index) { false } }
            .map { it.key }
            .toSet()
        refreshCurrent()
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

        override fun getItemCount(): Int = sites.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            // 显示名走 Site.getDisplayName()：尊重用户在站点管理里的改名。
            holder.name.text = sites[position].displayName
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
                onSourceSelected(item, site)
                dismiss()
            }
            // 卡片按 ↑ 必须回到**当前选中的站点标签**（《焦点总结》规则 ④的同一条思路）。
            // 弹窗布局里 recycler 的 nextFocusUp 指向整个 siteRecycler，焦点只会落到
            // RecyclerView 容器上，标签本身既不高亮也不滚动，用户看不出自己回到了哪个站点；
            // 所以在这里拦一次，直接点名请求那个标签的焦点。
            holder.card.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    focusSite(selectedSite)
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
         * @param sites 候选源，直接用 [candidates] 的返回值；顺序即站点栏顺序。
         * @param keyword 检索关键词（首页当前这张卡的剧名）。
         * @param quick 是否只搜 quick 源。换源必须传 `false`：`SiteViewModel.searchContent(List)`
         *   在 `quick=true` 时会直接跳过 `isQuickSearch()==false` 的源，表现为整屏「该源暂无此剧」。
         * @param onSourceSelected 结果卡片确定键回调，回传**真实 `(Vod, Site)`**。
         * @param onClosed 关闭回调，调用方在这里把焦点交还给首页卡片。
         */
        fun show(
            context: Context,
            viewModel: SiteViewModel,
            sites: List<Site>,
            keyword: String,
            quick: Boolean = false,
            onSourceSelected: (Vod, Site) -> Unit = { _, _ -> },
            onClosed: () -> Unit = {},
        ): ChangeSourceDialog {
            val dialog = ChangeSourceDialog(
                context, viewModel, sites, keyword, quick, onSourceSelected, onClosed,
            )
            dialog.show()
            return dialog
        }
    }
}
