package com.fongmi.android.tv.ui.hero

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import androidx.appcompat.widget.AppCompatImageView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fongmi.android.tv.R

/**
 * 全局接口源侧边栏（View 体系，独立 Dialog 窗口）。
 *
 * 视觉来源（ref_docs）：
 * - 结构：`dialog_search_site.xml`（顶部全选/全不选 → 渐变分隔线 → 列表）。
 * - 顶部按钮：12sp + 16dp 勾选圈，焦点态用 source 系列红色胶囊（`selector_source_site_item_background`）。
 * - 列表项：`adapter_site.xml` 的真实值 —— 40dp 行高、18sp 文字、24dp 勾选圈。
 * - 勾选圈：`selector_check_circle`（focused/selected 四态）。
 * - 背景：`@color/source_sidebar_background`（#1E1E1E）。
 *
 * 行为：
 * - 宽 320dp、靠右、高度铺满，右侧滑入滑出（`SourceSidebarAnimation`）。
 * - 确定键切换勾选；顶部「全选 / 全不选」批量切换。
 * - 返回键关闭，关闭后通过 [onClosed] 把焦点交还给打开它的按钮。
 * - 组件内零网络、零数据层写入，数据全部由外部传入。
 */
internal class SourceSidebar private constructor(
    context: Context,
    private val entries: List<HeroSourceEntry>,
    initialChecked: BooleanArray,
    private val onCheckedChanged: (BooleanArray) -> Unit,
    private val onClosed: () -> Unit,
) : Dialog(context, R.style.SourceDialogTheme) {

    private val checked = BooleanArray(entries.size)

    private lateinit var list: RecyclerView
    private lateinit var selectAll: View
    private lateinit var selectNone: View
    private lateinit var adapter: Adapter

    init {
        val size = minOf(initialChecked.size, checked.size)
        initialChecked.copyInto(checked, endIndex = size)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_source_sidebar)
        setupWindow()
        bindViews()
        bindBulkActions()
        syncBulkState()
        setOnDismissListener { onClosed() }
    }

    override fun onStart() {
        super.onStart()
        // 默认焦点落在列表第 1 条；布局完成后再请求，避免取不到 ViewHolder。
        list.post {
            val first = list.findViewHolderForAdapterPosition(0)?.itemView
            if (first != null && first.requestFocus()) return@post
            list.requestFocus()
        }
    }

    private fun setupWindow() {
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(dp(SIDEBAR_WIDTH_DP), ViewGroup.LayoutParams.MATCH_PARENT)
            setGravity(Gravity.END)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(SIDEBAR_DIM)
            setWindowAnimations(R.style.SourceSidebarAnimation)
        }
    }

    private fun bindViews() {
        selectAll = findViewById(R.id.sourceSelectAll)
        selectNone = findViewById(R.id.sourceSelectNone)
        selectAll.nextFocusDownId = R.id.sourceList
        selectNone.nextFocusDownId = R.id.sourceList

        list = findViewById(R.id.sourceList)
        list.layoutManager = LinearLayoutManager(context)
        list.itemAnimator = null
        list.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        adapter = Adapter()
        list.adapter = adapter
    }

    private fun bindBulkActions() {
        selectAll.setOnClickListener { setAll(true) }
        selectNone.setOnClickListener { setAll(false) }
    }

    private fun toggle(position: Int) {
        if (position !in checked.indices) return
        checked[position] = !checked[position]
        adapter.notifyItemChanged(position)
        syncBulkState()
        onCheckedChanged(checked.copyOf())
    }

    private fun setAll(value: Boolean) {
        for (index in checked.indices) checked[index] = value
        adapter.notifyDataSetChanged()
        syncBulkState()
        onCheckedChanged(checked.copyOf())
    }

    private fun syncBulkState() {
        selectAll.isSelected = checked.isNotEmpty() && checked.all { it }
        selectNone.isSelected = checked.none { it }
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private inner class Adapter : RecyclerView.Adapter<Adapter.Holder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = layoutInflater.inflate(R.layout.adapter_source_sidebar_item, parent, false)
            return Holder(view)
        }

        override fun getItemCount(): Int = entries.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val itemView = holder.itemView
            holder.name.text = entries[position].label
            itemView.isSelected = checked[position]
            itemView.nextFocusUpId = if (position == 0) R.id.sourceSelectAll else View.NO_ID
            itemView.setOnClickListener { toggle(holder.bindingAdapterPosition) }
            // 左右键在列表内不跳出侧边栏。
            itemView.setOnKeyListener { _, keyCode, event ->
                event.action == KeyEvent.ACTION_DOWN &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
            }
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val name: android.widget.TextView = view.findViewById(R.id.sourceName)
            val icon: AppCompatImageView = view.findViewById(R.id.sourceIcon)
            val check: ImageView = view.findViewById(R.id.sourceCheck)
        }
    }

    companion object {

        private const val SIDEBAR_WIDTH_DP = 320
        private const val SIDEBAR_DIM = 0.25f

        /**
         * 打开全局接口源侧边栏。
         *
         * @param entries 侧边栏条目（站点 + 资源类型），顺序即显示顺序。
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
