package com.fongmi.android.tv.ui.hero

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fongmi.android.tv.R
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 换源弹窗（View 体系，独立 Dialog，不使用任何 Compose 外壳）。
 *
 * 布局直接照搬 `ref_docs/dialog_source.xml`（原版 SourceDialog / f23），
 * 窗口尺寸与间距照搬 `ref_docs/f23.java`：
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
 * `topStartTag` = `MM-dd | 来源`（13sp）、`bottomStartTag` = 备注（14sp）、`title` = 剧名（16sp）。
 *
 * 多选联动：点弹窗内小房子 → [SourceSidebarMode.Multi]，勾选的站点会即时过滤下方结果，
 * 只有被勾选站点的结果才会展示。TODO(数据层对接)：换成真实检索条件。
 *
 * 组件内零数据层写入；站点切换后的检索与结果卡写库由调用方实现。
 * 海报只在 [HeroPosterCard.imageUrl] 非空时才由组件用 Glide 加载（Mock 阶段恒为空，走渐变占位）。
 * TODO(数据层对接)：站点切换刷新、结果卡确定键写库、海报地址。
 */
internal class ChangeSourceDialog private constructor(
    context: Context,
    private val sites: List<String>,
    currentSite: Int,
    private val results: List<HeroSourceResult>,
    private val total: Int,
    private val onSourceSelected: (HeroSourceResult) -> Unit,
    private val onClosed: () -> Unit,
) : Dialog(context, R.style.SourceDialogTheme) {

    private var selectedSite: Int = currentSite.coerceIn(0, (sites.size - 1).coerceAtLeast(0))

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

    /** 全部结果（不过滤）与当前展示结果。多选侧边栏的勾选只影响后者。 */
    private var visibleResults: List<HeroSourceResult> = results

    /** 多选侧边栏的勾选快照，关闭再打开时保持上次状态。 */
    private var checkedFlags: BooleanArray = HERO_SOURCE_INITIAL_CHECKED.copyOf()

    private lateinit var homeBtn: FrameLayout
    private lateinit var siteRecycler: RecyclerView
    private lateinit var resultRecycler: RecyclerView
    private lateinit var empty: TextView

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

    override fun onStop() {
        window?.decorView?.viewTreeObserver?.removeOnGlobalFocusChangeListener(focusListener)
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        // 焦点进出结果区时同步站点标签的红框（规则 ②③）。
        // 与 onStop 的移除成对，保证弹窗被复用/重新显示时监听不会丢也不会重复添加。
        window?.decorView?.viewTreeObserver?.addOnGlobalFocusChangeListener(focusListener)
        // 默认焦点落在当前站点标签；必须等 ViewHolder 布局完成再请求，
        // 否则焦点会停在 RecyclerView 上，标签的 focused 红底就不会出现。
        siteRecycler.post {
            siteRecycler.scrollToPosition(selectedSite)
            focusSite(selectedSite)
        }
    }

    /** 布局可能尚未完成，重试几次保证焦点落在标签上。 */
    private fun focusSite(position: Int, attempt: Int = 0) {
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
        updateCount()
    }

    /** 顶部统计：未过滤时沿用外部传入的总数，过滤后显示实际命中数。 */
    private fun updateCount() {
        val count = if (visibleResults.size == results.size) total else visibleResults.size
        findViewById<TextView>(R.id.resultCount).text = context.getString(R.string.play_change_count, count)
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
        siteRecycler.adapter = SiteAdapter()
    }

    private fun bindResultList() {
        resultRecycler = findViewById(R.id.recycler)
        empty = findViewById(R.id.empty)
        resultRecycler.layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
        resultRecycler.itemAnimator = null
        resultRecycler.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        resultRecycler.addItemDecoration(SpacingDecoration(dp(RESULT_SPACING_DP)))
        resultRecycler.adapter = ResultAdapter()
        recycleState()
    }

    private fun recycleState() {
        val hasResult = visibleResults.isNotEmpty()
        findViewById<View>(R.id.progress).visibility = View.GONE
        resultRecycler.visibility = if (hasResult) View.VISIBLE else View.GONE
        empty.visibility = if (hasResult) View.GONE else View.VISIBLE
    }

    private fun selectSite(position: Int) {
        if (position == selectedSite) return
        selectedSite = position
        syncSiteActivation()
        resultRecycler.adapter?.notifyDataSetChanged()
        recycleState()
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
     * 勾选结果即时过滤下方结果：只有被勾选站点的结果才会展示。
     */
    private fun openSidebar() {
        SourceSidebar.show(
            context = context,
            entries = HERO_SOURCE_ENTRIES,
            mode = SourceSidebarMode.Multi,
            initialChecked = checkedFlags,
            onCheckedChanged = { applySiteFilter(it) },
            onClosed = { homeBtn.post { homeBtn.requestFocus() } },
        )
    }

    /** 多选联动（Mock）：勾选的站点名与 [HeroSourceResult.site] 求交集后刷新列表。 */
    private fun applySiteFilter(flags: BooleanArray) {
        checkedFlags = flags.copyOf()
        val allowed = HERO_SOURCE_ENTRIES
            .filterIndexed { index, _ -> index < checkedFlags.size && checkedFlags[index] }
            .map { it.site }
            .toSet()
        visibleResults = results.filter { it.site in allowed }
        resultRecycler.adapter?.notifyDataSetChanged()
        recycleState()
        updateCount()
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
            holder.name.text = sites[position]
            holder.itemView.isSelected = position == selectedSite
            // 规则 ②：当前站点 + 焦点在结果区 → 红框（drawable 的 state_activated 分支）。
            holder.itemView.isActivated = contentFocused && position == selectedSite
            // 第一个站点标签向左回到小房子；其余在站点栏内滚动。
            holder.itemView.nextFocusLeftId = if (position == 0) R.id.homeBtn else View.NO_ID
            holder.itemView.setOnClickListener { selectSite(holder.bindingAdapterPosition) }
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
            val result = visibleResults[position]
            // 字段与逆向 adapter_search.xml / ks2.java 一一对应：
            // title          → 16sp 剧名（ks2 L74 `setText(vod.getName())`）
            // bottomStartTag → 14sp 备注（ks2 L78-79 `setText(vod.getRemarks())`）
            // topStartTag    → 13sp 左上角标签（ks2 L77 `setText(vod.getSiteName())`），
            //                  本工程按需求显示「日期 | 来源」，日期截短成 MM-dd（如 `10-28 | 豆瓣`），
            //                  组件内 maxWidth 110dp（< 海报 120dp）+ 单行省略，永远不越出海报。
            holder.card.title = result.name
            holder.card.imageUrl = result.poster
            holder.card.topStartTag = "${shortDate(result.date)} | ${result.site}"
            holder.card.bottomStartTag = result.remark
            holder.card.setOnClickListener {
                onSourceSelected(result)
                dismiss()
            }
        }

        inner class Holder(val card: HeroPosterCard) : RecyclerView.ViewHolder(card)

        /** `2026-10-28` → `10-28`；不是三段日期（如已经是 `10-28`）就原样返回。 */
        private fun shortDate(date: String): String {
            val parts = date.split('-')
            return if (parts.size == 3) "${parts[1]}-${parts[2]}" else date
        }
    }

    companion object {

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
         * 打开换源弹窗。
         *
         * @param sites 站点栏标签，顺序即显示顺序。
         * @param currentSite 当前站点下标，越界时收敛到 0。
         * @param results 换源结果卡片。
         * @param total 顶部统计数字（与卡片数量解耦）；默认等于结果数量。
         * @param onSourceSelected 结果卡片确定键回调。
         * @param onClosed 关闭回调，调用方在这里把焦点交还给详情页「换源」按钮。
         */
        fun show(
            context: Context,
            sites: List<String>,
            currentSite: Int,
            results: List<HeroSourceResult>,
            total: Int = results.size,
            onSourceSelected: (HeroSourceResult) -> Unit = {},
            onClosed: () -> Unit = {},
        ): ChangeSourceDialog {
            val dialog = ChangeSourceDialog(context, sites, currentSite, results, total, onSourceSelected, onClosed)
            dialog.show()
            return dialog
        }
    }
}
