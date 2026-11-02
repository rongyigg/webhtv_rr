package com.fongmi.android.tv.ui.hero

import android.app.Dialog
import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fongmi.android.tv.R
import com.fongmi.android.tv.ui.custom.CustomRecyclerView
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 全局接口源侧边栏（View 体系，独立 Dialog）。
 *
 * 布局照搬 `ref_docs/dialog_site.xml`（原版 SiteDialog / `kz2`）：
 * 根横向 `padding 24dp` + `CustomRecyclerView`（weight 1、wrap_content 高、`app:maxHeight=480dp`），
 * 列表行照搬 `ref_docs/adapter_site.xml`（40dp 行高、18sp 文字、24dp 勾选圈）。
 *
 * 数值全部照搬 `kz2.java`：
 * - 窗口宽度 = 屏幕宽 × `((cols-1)*0.2 + 0.4)`，单列（`site_mode` 默认 0）即 **0.4 × 屏宽**；
 * - 列表高度 = `(min(max(1, ceil(条目数/cols)), 4) * 73 - 16) dp`，同时设为 maxHeight；
 *   73 = 行高 40 + 底部分隔线 16 + 1dp 线，与 16dp 间距装饰器一致，因此最多显示 4 行；
 * - 列表用 `GridLayoutManager(context, cols)`，条目间距 16dp（`h23(cols, 16)`）；
 * - `itemAnimator = null`、`itemViewCacheSize = 6`（原版的 `hasFixedSize(true)` 不复用，见 [bindList]）。
 *
 * 焦点 / 选中态照搬 `ref_docs/selector_site_item_background.xml` 与
 * `ref_docs/selector_site_item_text.xml`（焦点白色胶囊、常态透明）。
 *
 * 与 ref 的差异（均在 `dialog_source_sidebar.xml` 头部注明）：
 * - 原版根布局无背景（由 MaterialAlertDialog 的 surface 提供）→ 用 `shape_dialog_source`；
 * - 原版右侧 `@id/action` 图标列整列删除：默认 gone，且 3 个资源本工程没有、4 个只在 leanback/mobile；
 * - 行内勾选圈用 `selector_check_circle` 的 ImageView 替代缺失的 `check_circle` + `selector_site_item_check`。
 *
 * 交互沿用 Hero 需求：确定键切换该条目勾选（多选），勾选快照通过 [onCheckedChanged] 回调；
 * 返回键关闭并把焦点交还 [onClosed]。组件内零网络、零数据层写入。
 */
internal class SourceSidebar private constructor(
    context: Context,
    private val entries: List<HeroSourceEntry>,
    initialChecked: BooleanArray,
    private val onCheckedChanged: (BooleanArray) -> Unit,
    private val onClosed: () -> Unit,
) : Dialog(context, R.style.SourceDialogTheme) {

    private val checked = BooleanArray(entries.size)

    private lateinit var list: CustomRecyclerView
    private lateinit var adapter: Adapter

    init {
        val size = minOf(initialChecked.size, checked.size)
        initialChecked.copyInto(checked, endIndex = size)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_source_sidebar)
        setupWindow()
        bindList()
        setOnDismissListener { onClosed() }
    }

    override fun onStart() {
        super.onStart()
        list.post {
            val first = list.findViewHolderForAdapterPosition(0)?.itemView
            if (first != null && first.requestFocus()) return@post
            list.requestFocus()
        }
    }

    /** kz2：`d0(((cols-1)*0.2f)+0.4f)` + `layoutParams.width = 同一比例 × 屏宽`。 */
    private fun setupWindow() {
        val metrics = context.resources.displayMetrics
        val width = (metrics.widthPixels * widthFraction()).toInt()
        window?.apply {
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(DIM_AMOUNT)
        }
        // 原版同时把根布局宽度写成同一比例（kz2 b0() 里的 layoutParams.width）。
        val root = findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0) ?: return
        root.layoutParams = root.layoutParams.apply { this.width = width }
    }

    /** kz2：单列 `site_mode=0` → 0.4；三列 `site_mode=1` → 0.8。 */
    private fun widthFraction(): Float = ((COLUMNS - 1) * 0.2f) + 0.4f

    private fun bindList() {
        list = findViewById(R.id.recycler)
        // 原版 kz2 调用 setHasFixedSize(true)，但原版列表高度是运行时算出的固定高度；
        // 这里 dialog_source_sidebar 的列表是 android:layout_height="wrap_content"，
        // 两者并存会触发 Lint `InvalidSetHasFixedSize`（CI 失败），故不设置该标志。
        list.itemAnimator = null
        list.setItemViewCacheSize(6)
        list.layoutManager = GridLayoutManager(context, COLUMNS)
        list.addItemDecoration(SpacingDecoration(dp(ITEM_SPACING_DP)))
        adapter = Adapter()
        list.adapter = adapter
        // kz2：高度 = (min(max(1, ceil(n/cols)), 4) * 73 - 16) dp，同时作为 maxHeight。
        val rows = min(maxOf(1, (entries.size + COLUMNS - 1) / COLUMNS), MAX_ROWS)
        val height = dp(rows * ROW_UNIT_DP - ROW_UNIT_GAP_DP)
        list.setMaxHeight(height)
        list.layoutParams = list.layoutParams.apply { this.height = height }
    }

    private fun toggle(position: Int) {
        if (position !in checked.indices) return
        checked[position] = !checked[position]
        adapter.notifyItemChanged(position)
        onCheckedChanged(checked.copyOf())
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()

    /** kz2 的 `h23(cols, 16)`：多列时补右间距，每行补下间距。 */
    private class SpacingDecoration(private val spacing: Int) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
            val position = parent.getChildAdapterPosition(view)
            if (position == RecyclerView.NO_POSITION) return
            val total = state.itemCount
            val column = position % COLUMNS
            if (column != COLUMNS - 1 && position != total - 1) outRect.right = spacing
            if (position + COLUMNS < total) outRect.bottom = spacing
        }
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.Holder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = layoutInflater.inflate(R.layout.adapter_source_sidebar_item, parent, false)
            return Holder(view)
        }

        override fun getItemCount(): Int = entries.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.name.text = entries[position].label
            holder.check.isSelected = checked[position]
            holder.itemView.isSelected = checked[position]
            holder.itemView.setOnClickListener { toggle(holder.bindingAdapterPosition) }
            // 左右键在列表内被消费，不会把焦点甩出面板。
            holder.itemView.setOnKeyListener { _, keyCode, event ->
                event.action == KeyEvent.ACTION_DOWN &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
            }
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.text)
            val check: View = view.findViewById(R.id.check)
        }
    }

    companion object {

        /** kz2：`site_mode` 默认 0 → 单列。 */
        private const val COLUMNS = 1

        /** kz2：`Math.min(..., 4) * 73 - 16`。 */
        private const val MAX_ROWS = 4
        private const val ROW_UNIT_DP = 73
        private const val ROW_UNIT_GAP_DP = 16

        /** kz2：`h23(cols, 16)`。 */
        private const val ITEM_SPACING_DP = 16

        private const val DIM_AMOUNT = 0.3f

        /**
         * 打开全局接口源侧边栏。
         *
         * @param entries 侧边栏条目，顺序即显示顺序。
         * @param initialChecked 初始勾选状态，长度不足时其余按未选中处理。
         * @param onCheckedChanged 勾选变更回调，返回快照数组。
         * @param onClosed 关闭回调，调用方在这里把焦点交还给打开它的按钮。
         */
        fun show(
            context: Context,
            entries: List<HeroSourceEntry>,
            initialChecked: BooleanArray,
            onCheckedChanged: (BooleanArray) -> Unit = {},
            onClosed: () -> Unit = {},
        ): SourceSidebar {
            val dialog = SourceSidebar(context, entries, initialChecked, onCheckedChanged, onClosed)
            dialog.show()
            return dialog
        }
    }
}
