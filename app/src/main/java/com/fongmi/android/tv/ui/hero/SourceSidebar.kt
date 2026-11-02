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
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fongmi.android.tv.R

/**
 * 全局接口源侧边栏（View 体系，独立 Dialog 窗口）。
 *
 * 结构与全部尺寸直接照搬 `ref_docs/dialog_search_site.xml`（原版站点选择面板）：
 * 顶部「全选 / 全不选」（12sp + 16dp 勾选圈，行高 39dp）→ 1dp 渐隐分隔线 → 站点列表。
 * 列表行照搬 `ref_docs/adapter_site.xml`：40dp 行高、18sp 文字、24dp 勾选圈、底部渐隐分隔线。
 *
 * 焦点态照搬 `ref_docs/selector_site_item_background.xml`：
 * focused → `shape_source_site_item_focused`（白色圆角胶囊，inset 8dp/4dp）；
 * selected → `shape_source_chip_round_activated`；常态 → `shape_source_chip_round_normal`（纯透明、无底色）。
 * 勾选圈照搬 `ref_docs/selector_check_circle.xml` 的四态。
 *
 * 与 ref 的差异（因为本工程缺资源 / 宿主改为独立 Dialog）：
 * - `selector_search_action_background` 缺失 → 用上面同源的白胶囊选择器；
 * - `check_circle` + `selector_site_item_check` 缺失 → 用 `selector_check_circle` 的 ImageView；
 * - 原版由 BaseSheetDialog 的 sheet 容器提供背景 → 这里用 `shape_dialog_source`；
 * - 原版贴底整宽 → 这里靠右、宽度由内容决定（窗口 WRAP_CONTENT，不写死 dp）。
 *
 * 行为：确定键切换勾选；顶部「全选 / 全不选」批量切换；返回键关闭并把焦点交还 [onClosed]。
 * 组件内零网络、零数据层写入，数据全部由外部传入。
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
            // 宽度交给布局内容决定（ref 的面板本身没有固定宽度），高度铺满右侧。
            setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setGravity(Gravity.END)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(SIDEBAR_DIM)
            setWindowAnimations(R.style.SourceSidebarAnimation)
        }
    }

    private fun bindViews() {
        selectAll = findViewById(R.id.all)
        selectNone = findViewById(R.id.none)
        selectAll.nextFocusDownId = R.id.recycler
        selectNone.nextFocusDownId = R.id.recycler

        list = findViewById(R.id.recycler)
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
            holder.itemView.nextFocusUpId = if (position == 0) R.id.all else View.NO_ID
            // 勾选圈用 duplicateParentState 跟随行状态，这里只需要行本身的点击与选中态。
            holder.itemView.setOnClickListener { toggle(holder.bindingAdapterPosition) }
            // 左右键在列表内被消费，不会把焦点甩出侧边栏。
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

        private const val SIDEBAR_DIM = 0.25f

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
