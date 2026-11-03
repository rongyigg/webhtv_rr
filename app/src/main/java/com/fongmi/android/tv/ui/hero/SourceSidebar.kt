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
 * 侧边栏的两种使用场景。
 *
 * - [Single]：首页小房子呼出。不显示勾选圈与「全选 / 全不选」，确定键 = 切到该接口源并关闭。
 * - [Multi]：换源弹窗内小房子呼出。显示勾选圈与「全选 / 全不选」，
 *   确定键 = 勾选 / 取消勾选（不关闭），勾选结果交给调用方过滤搜索结果。
 */
internal enum class SourceSidebarMode {
    Single,
    Multi,
}

/**
 * 全局接口源侧边栏（View 体系，独立 Dialog 窗口）。
 *
 * 结构与尺寸照搬 `ref_docs/dialog_search_site.xml`（顶部「全选 / 全不选」）与
 * `ref_docs/adapter_site.xml`（40dp 行高、18sp 文字、24dp 勾选圈）。
 *
 * 颜色按需求固定：
 * - 行 focused → 白底（`shape_source_site_item_focused`，取自 ref_docs：inset 8dp/4dp + 28dp 圆角）+ 黑字；
 * - 行 normal → 透明底（`shape_source_chip_round_normal` 无 solid）+ 白字；
 * - 勾选圈 → `selector_check_circle` 的四态（实心 / 空心 / 焦点态）。
 *
 * 行布局的 `@id/item` 就是根节点，因此 `ViewHolder.itemView` 与画背景的 View 是同一个，
 * `isSelected` / 焦点态一定作用在可见的背景上（旧版是「外层容器 + 内层 item」两层，状态打在外层，永远不显示）。
 *
 * 交互：
 * - [SourceSidebarMode.Single]：确定键 → `onSiteSelected(index)` 并关闭；
 * - [SourceSidebarMode.Multi]：确定键 → 切换该条勾选，`onCheckedChanged` 返回快照，不关闭。
 * - 两种模式都是返回键关闭，`onClosed` 里由调用方把焦点交还给打开它的按钮。
 *
 * 组件内零网络、零数据层写入，数据全部由外部传入。
 */
internal class SourceSidebar private constructor(
    context: Context,
    private val entries: List<HeroSourceEntry>,
    private val mode: SourceSidebarMode,
    initialChecked: BooleanArray,
    private val currentIndex: Int,
    private val onCheckedChanged: (BooleanArray) -> Unit,
    private val onSiteSelected: (Int) -> Unit,
    private val onClosed: () -> Unit,
) : Dialog(context, R.style.SourceDialogTheme) {

    private val checked = BooleanArray(entries.size)

    private lateinit var list: RecyclerView
    private lateinit var selectAll: View
    private lateinit var selectNone: View
    private lateinit var adapter: Adapter

    private val isMulti: Boolean get() = mode == SourceSidebarMode.Multi

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
        // 默认焦点：单选落在当前接口源，多选落在第 1 条。
        // 必须等 ViewHolder 真正布局完成再 requestFocus，否则焦点会留在 RecyclerView 上，
        // 行背景的 focused 态就永远不会出现（旧版「无焦点提示」的原因）。
        val target = if (isMulti) 0 else currentIndex.coerceIn(0, (entries.size - 1).coerceAtLeast(0))
        list.post {
            list.scrollToPosition(target)
            focusRow(target)
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
        // 单选模式不需要批量勾选入口，整行隐藏。
        findViewById<View>(R.id.bulkBar).visibility = if (isMulti) View.VISIBLE else View.GONE

        selectAll = findViewById(R.id.all)
        selectNone = findViewById(R.id.none)
        selectAll.nextFocusDownId = R.id.recycler
        selectNone.nextFocusDownId = R.id.recycler

        list = findViewById(R.id.recycler)
        list.layoutManager = LinearLayoutManager(context)
        list.itemAnimator = null
        // 列表本身不接收焦点，焦点始终落在行上，行才会绘制白底黑字。
        list.isFocusable = false
        list.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        adapter = Adapter()
        list.adapter = adapter
    }

    private fun bindBulkActions() {
        selectAll.setOnClickListener { setAll(true) }
        selectNone.setOnClickListener { setAll(false) }
    }

    /** 布局可能尚未完成、ViewHolder 还取不到，这里重试几次，保证焦点一定落在行上。 */
    private fun focusRow(position: Int, attempt: Int = 0) {
        val holder = list.findViewHolderForAdapterPosition(position)
        if (holder != null && holder.itemView.requestFocus()) return
        if (attempt < FOCUS_RETRY_COUNT) {
            list.postDelayed({ focusRow(position, attempt + 1) }, FOCUS_RETRY_DELAY_MS)
            return
        }
        // 兜底：极端情况下仍取不到 ViewHolder，至少让方向键能进入列表。
        list.isFocusable = true
        list.requestFocus()
    }

    /** 确定键：单选 = 选站并关闭；多选 = 勾选 / 取消勾选。 */
    private fun onConfirm(position: Int) {
        if (position !in entries.indices) return
        if (isMulti) {
            toggle(position)
        } else {
            onSiteSelected(position)
            dismiss()
        }
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
        if (!isMulti) return
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
            // 单选模式隐藏勾选圈；多选模式按会话内的勾选状态显示实心 / 空心。
            holder.check.visibility = if (isMulti) View.VISIBLE else View.GONE
            holder.check.isSelected = checked[position]
            // 单选：当前接口源保持选中；多选：选中态与勾选一致。
            holder.itemView.isSelected = if (isMulti) checked[position] else position == currentIndex
            holder.itemView.nextFocusUpId = if (isMulti && position == 0) R.id.all else View.NO_ID
            holder.itemView.setOnClickListener { onConfirm(holder.bindingAdapterPosition) }
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
        private const val FOCUS_RETRY_COUNT = 6
        private const val FOCUS_RETRY_DELAY_MS = 50L

        /**
         * 打开全局接口源侧边栏。
         *
         * @param entries 侧边栏条目，顺序即显示顺序。
         * @param mode 单选（首页小房子）或多选（换源弹窗内小房子）。
         * @param initialChecked 多选模式的初始勾选状态，长度不足时其余按未选中处理。
         * @param currentIndex 单选模式下当前接口源的下标，同时是该模式下的默认焦点。
         * @param onCheckedChanged 多选模式勾选变更回调，返回快照数组。
         * @param onSiteSelected 单选模式确定键回调，返回所选下标。
         * @param onClosed 关闭回调，调用方在这里把焦点交还给打开它的按钮。
         */
        fun show(
            context: Context,
            entries: List<HeroSourceEntry>,
            mode: SourceSidebarMode,
            initialChecked: BooleanArray = BooleanArray(entries.size),
            currentIndex: Int = 0,
            onCheckedChanged: (BooleanArray) -> Unit = {},
            onSiteSelected: (Int) -> Unit = {},
            onClosed: () -> Unit = {},
        ): SourceSidebar {
            val dialog = SourceSidebar(
                context, entries, mode, initialChecked, currentIndex,
                onCheckedChanged, onSiteSelected, onClosed,
            )
            dialog.show()
            return dialog
        }
    }
}
