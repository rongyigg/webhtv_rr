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
import com.fongmi.android.tv.bean.Site
import com.google.android.material.checkbox.MaterialCheckBox

/**
 * 侧边栏的两种使用场景。
 *
 * - [Single]：首页小房子呼出。不显示勾选圈与「全选 / 全不选」，确定键 = 切到该接口源并关闭。
 * - [Multi]：换源弹窗内小房子呼出。显示勾选圈与「全选 / 全不选」，
 *   确定键 = 勾选 / 取消勾选（不关闭），勾选结果交给调用方限定**允许搜索**的源。
 */
internal enum class SourceSidebarMode {
    Single,
    Multi,
}

/**
 * 全局接口源侧边栏（View 体系，独立 Dialog 窗口）。
 *
 * 结构与尺寸照搬逆向原版 `dialog_site.xml` / `adapter_site.xml` / `s82.java`
 * （本工程落地文件：`dialog_source_sidebar.xml` = 需求中的 `activity_site_view.xml`，
 * `adapter_source_sidebar_item.xml` = 需求中的 `adapter_site_view.xml`，本类 = 需求中的 `s82.java`）：
 * 40dp 行高、20dp 勾选圈、顶部「全选 / 全不选」。
 *
 * 视觉按逆向原版极简风固定：
 * - 行 focused → 90% 白胶囊（`shape_site_item_focused_white`，inset 8dp/4dp + 4dp 小圆角）+ 黑字；
 * - 行 normal → 透明底 + 白字；
 * - 行 selected → 透明底 + 1dp 半透明白细框（当前接口源 / 全选态，不再用红框）；
 * - 勾选圈 → `selector_site_item_check` 四态：未勾选白色空心圆，已勾选白色实心圆 + 对勾；
 *   行聚焦白胶囊上自动切黑色空心 / 黑色实心 + 白勾，避免白圈消失；
 * - 行底 1dp `shape_line_tapered` 下划线，左右各 24dp。
 *
 * 行布局的 `@id/item` 就是根节点，因此 `ViewHolder.itemView` 与画背景的 View 是同一个，
 * `isSelected` / 焦点态一定作用在可见的背景上（旧版是「外层容器 + 内层 item」两层，状态打在外层，永远不显示）。
 *
 * 数据来自 [HeroSourceEntry]，条目里装的是**真实 [Site]**：
 * - [SourceSidebarMode.Single]：确定键 → `onSiteSelected(site)` 并关闭；
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
    private val currentSiteKey: String,
    private val onCheckedChanged: (BooleanArray) -> Unit,
    private val onSiteSelected: (Site) -> Unit,
    private val onClosed: () -> Unit,
) : Dialog(context, R.style.SourceDialogTheme) {

    private val checked = BooleanArray(entries.size)

    /**
     * 单选模式下「当前接口源」的下标。
     *
     * 用 key 而不是下标传进来：真实站点列表会随配置导入 / 屏蔽 / 排序变化，
     * 下标在两次读取之间并不可靠，key 才是稳定标识（与 `Vod.getSiteKey()` 同一套）。
     * 找不到（例如当前源已被隐藏）时退回第 0 条。
     */
    private val currentIndex: Int
        get() = entries.indexOfFirst { it.key == currentSiteKey }.coerceAtLeast(0)

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
            // 宽度由布局固定 280dp（顶部两个等宽按钮需要确定的父宽度才能严格对称），高度铺满右侧。
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

    /** 确定键：单选 = **先销毁窗口再选站**；多选 = 勾选 / 取消勾选。 */
    private fun onConfirm(position: Int) {
        if (position !in entries.indices) return
        if (isMulti) {
            toggle(position)
        } else {
            // 【顺序红线】必须先把窗口销毁，再回调：
            // 回调里会 `setHome` + 重拉首页（同步重绘 Hero / 列表 / 进度层），
            // 只要窗口还在，它就会一直盖在新首页上面（dim + 全高面板 = 鸠占鹊巢）。
            // `dismiss()` 是真正的窗口销毁（不走 addView，无需 removeView），并且会触发
            // `setOnDismissListener → onClosed()`，焦点交还由调用方在 `onClosed` 里完成。
            dismiss()
            onSiteSelected(entries[position].site)
        }
    }

    private fun toggle(position: Int) {
        if (position !in checked.indices) return
        checked[position] = !checked[position]
        adapter.notifyItemChanged(position)
        syncBulkState()
        onCheckedChanged(checked.copyOf())
    }

    /** 全选 / 全不选：遍历所有条目统一改状态，再整表刷新。 */
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
            // 单选模式隐藏勾选圈时必须保留占位（INVISIBLE 而不是 GONE），
            // 否则文字区会因少了 20dp 圈 + 8dp 间距而变宽，两种模式的焦点块宽度不一致。
            holder.check.visibility = if (isMulti) View.VISIBLE else View.INVISIBLE
            holder.check.isChecked = checked[position]
            // 禁用状态下 MaterialCheckBox 仍可能播放勾选动画；直接跳到终态，复用时不会闪一下。
            holder.check.jumpDrawablesToCurrentState()
            // 单选：当前接口源保持选中；多选：勾选状态只由右侧圆圈表达，不再给整行加白框。
            holder.itemView.isSelected = !isMulti && position == currentIndex
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
            val check: MaterialCheckBox = view.findViewById(R.id.check)
        }
    }

    companion object {

        private const val SIDEBAR_DIM = 0.25f
        private const val FOCUS_RETRY_COUNT = 6
        private const val FOCUS_RETRY_DELAY_MS = 50L

        /**
         * 打开全局接口源侧边栏。
         *
         * @param entries 侧边栏条目（内部持有真实 [Site]），顺序即显示顺序。
         * @param mode 单选（首页小房子）或多选（换源弹窗内小房子）。
         * @param initialChecked 多选模式的初始勾选状态，长度不足时其余按未选中处理。
         * @param currentSiteKey 单选模式下当前接口源的 key（`Site.getKey()`），
         *   既是选中态来源也是该模式下的默认焦点；找不到时退回第 0 条。
         * @param onCheckedChanged 多选模式勾选变更回调，返回快照数组（下标对应 [entries]）。
         * @param onSiteSelected 单选模式确定键回调，回传**真实 [Site] 对象**。
         * @param onClosed 关闭回调，调用方在这里把焦点交还给打开它的按钮。
         */
        fun show(
            context: Context,
            entries: List<HeroSourceEntry>,
            mode: SourceSidebarMode,
            initialChecked: BooleanArray = BooleanArray(entries.size),
            currentSiteKey: String = "",
            onCheckedChanged: (BooleanArray) -> Unit = {},
            onSiteSelected: (Site) -> Unit = {},
            onClosed: () -> Unit = {},
        ): SourceSidebar {
            val dialog = SourceSidebar(
                context, entries, mode, initialChecked, currentSiteKey,
                onCheckedChanged, onSiteSelected, onClosed,
            )
            dialog.show()
            return dialog
        }
    }
}
