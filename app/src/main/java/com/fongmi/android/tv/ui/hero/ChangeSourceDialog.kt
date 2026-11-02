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
 * 站点标签用 `adapter_source_site`（照搬 `ref_docs/adapter_v2_site.xml`），
 * 焦点 / 选中态用 `selector_source_site_item_background`（照搬 `ref_docs/selector_site_item_background.xml`：
 * 白色圆角胶囊 + 透明底），不是自画的灰底或红底。
 *
 * 结果卡用 `adapter_source_result`（照搬 `ref_docs/adapter_vod_rect.xml`，120×180 + 13sp 标题）。
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
        siteRecycler.post {
            val holder = siteRecycler.findViewHolderForAdapterPosition(selectedSite)
            if (holder != null && holder.itemView.requestFocus()) return@post
            siteRecycler.requestFocus()
        }
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
        findViewById<TextView>(R.id.resultCount).text = context.getString(R.string.play_change_count, total)
    }

    private fun bindHome() {
        homeBtn = findViewById(R.id.homeBtn)
        homeBtn.setOnClickListener { openSidebar() }
    }

    private fun bindSiteList() {
        siteRecycler = findViewById(R.id.siteRecycler)
        siteRecycler.layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
        siteRecycler.itemAnimator = null
        siteRecycler.addItemDecoration(SpacingDecoration(dp(SITE_SPACING_DP)))
        siteRecycler.adapter = SiteAdapter()
    }

    private fun bindResultList() {
        resultRecycler = findViewById(R.id.recycler)
        empty = findViewById(R.id.empty)
        resultRecycler.layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
        resultRecycler.itemAnimator = null
        resultRecycler.addItemDecoration(SpacingDecoration(dp(RESULT_SPACING_DP)))
        resultRecycler.adapter = ResultAdapter()
        recycleState()
    }

    private fun recycleState() {
        val hasResult = results.isNotEmpty()
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

    /** 弹窗内小房子：拉起全局接口源侧边栏，层级盖在换源弹窗之上（后创建的窗口在上层）。 */
    private fun openSidebar() {
        SourceSidebar.show(
            context = context,
            entries = HERO_SOURCE_ENTRIES,
            initialChecked = HERO_SOURCE_INITIAL_CHECKED,
            onClosed = { homeBtn.post { homeBtn.requestFocus() } },
        )
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

        override fun getItemCount(): Int = results.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val result = results[position]
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
