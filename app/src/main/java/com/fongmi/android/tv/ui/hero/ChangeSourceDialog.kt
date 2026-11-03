package com.fongmi.android.tv.ui.hero

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
 * - 结果栏横向间距 16dp（f23 `recycler.setHorizontalSpacing(dp(16))`）；
 * - 结果卡获得焦点放大 1.08（f23 `mm3.f(recycler, 1.08f)`）。
 *
 * 站点标签用 `adapter_source_site`，焦点态用 `selector_source_site_chip_background`：
 * **focused → 红底 #E50914 白字胶囊**，normal / selected → 透明底白字。
 * （侧边栏的行用的是另一套：白底黑字，见 [SourceSidebar]。）
 *
 * 结果卡用 `adapter_source_result`（照搬 `ref_docs/adapter_vod_rect.xml`，120×180 + 13sp 标题）。
 *
 * 多选联动：点弹窗内小房子 → [SourceSidebarMode.Multi]，勾选的站点会即时过滤下方结果，
 * 只有被勾选站点的结果才会展示。TODO(数据层对接)：换成真实检索条件。
 *
 * 组件内零网络、零数据层写入；站点切换后的检索与结果卡写库由调用方实现。
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

    override fun onStart() {
        super.onStart()
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

    /** 照搬 f23 的窗口尺寸：宽 0.82×屏幕宽，高 min(360dp, 0.65×屏幕高 dp)。 */
    private fun setupWindow() {
        val metrics = context.resources.displayMetrics
        val width = (metrics.widthPixels * WIDTH_FRACTION).toInt()
        val screenHeightDp = metrics.heightPixels / metrics.density
        val heightDp = min(MAX_HEIGHT_DP, (screenHeightDp * HEIGHT_FRACTION).roundToInt())
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
        resultRecycler.adapter?.notifyDataSetChanged()
        recycleState()
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
            // 第一个站点标签向左回到小房子；其余在站点栏内滚动。
            holder.itemView.nextFocusLeftId = if (position == 0) R.id.homeBtn else View.NO_ID
            holder.itemView.setOnClickListener { selectSite(holder.bindingAdapterPosition) }
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.text)
        }
    }

    private inner class ResultAdapter : RecyclerView.Adapter<ResultAdapter.Holder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = layoutInflater.inflate(R.layout.adapter_source_result, parent, false)
            return Holder(view)
        }

        override fun getItemCount(): Int = visibleResults.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val result = visibleResults[position]
            holder.name.text = "${result.site} · ${result.date}"
            // TODO(数据层对接)：接入真实数据后在这里把海报加载到 holder.image。
            // 占位沿用原版 @color/black_20（adapter_vod_rect 的 image background），不是纯黑块。
            holder.itemView.scaleX = 1f
            holder.itemView.scaleY = 1f
            holder.itemView.setOnFocusChangeListener { view, hasFocus ->
                val scale = if (hasFocus) FOCUS_SCALE else 1f
                view.animate().scaleX(scale).scaleY(scale).setDuration(FOCUS_ANIM_MS).start()
            }
            holder.itemView.setOnClickListener {
                onSourceSelected(result)
                dismiss()
            }
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.name)
            val image: View = view.findViewById(R.id.image)
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

        /** f23：`mm3.f(recycler, 1.08f)`。 */
        private const val FOCUS_SCALE = 1.08f
        private const val FOCUS_ANIM_MS = 150L
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
