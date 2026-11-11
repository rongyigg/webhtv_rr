package com.fongmi.android.tv.ui.hero

import android.graphics.Rect
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil3.dispose
import coil3.load
import coil3.request.crossfade
import coil3.request.error
import com.fongmi.android.tv.R
import com.fongmi.android.tv.bean.Vod
import kotlin.math.roundToInt

/**
 * 搜索页（纯 View）四个适配器：虚拟键盘 / 历史·热搜·建议胶囊 / 站点 Tab / 结果卡。
 *
 * 全部集中在本文件，`HeroSearchActivity` 只负责业务与焦点编排。
 * 尺寸、字号、圆角一律写在各 `adapter_search_*.xml` 里，这里只做绑定与状态切换，
 * 避免「同一个值在代码和布局里各写一份」。
 */

// ---------------------------------------------------------------------------
// 键盘：键位表
// ---------------------------------------------------------------------------

/**
 * 一个键位。
 *
 * 与 Compose 版 `HERO_KEYS` 逐项对应（`ref_docs/xd1.java` L27）：
 * 第一行 `(占位, <, >, ⌫, 键盘, 房子, A)`，之后 B-Z、0-9，共 42 格 = 6 行 × 7 列。
 */
internal sealed interface SearchKey {

    /** 左上角空格：不显示、不可聚焦。 */
    data object Placeholder : SearchKey

    data class Text(val value: String) : SearchKey

    data class Icon(val iconRes: Int, val action: SearchKeyAction) : SearchKey
}

internal enum class SearchKeyAction {
    CursorLeft,
    CursorRight,
    Backspace,

    /** 原版是键盘布局切换；本页没有符号页，语义改为「清空输入」。 */
    Keyboard,

    /** 全局接口源侧边栏。 */
    House,
}

internal val SEARCH_KEYS: List<SearchKey> = buildList {
    add(SearchKey.Placeholder)
    add(SearchKey.Icon(R.drawable.ic_keyboard_left, SearchKeyAction.CursorLeft))
    add(SearchKey.Icon(R.drawable.ic_keyboard_right, SearchKeyAction.CursorRight))
    add(SearchKey.Icon(R.drawable.ic_keyboard_back, SearchKeyAction.Backspace))
    add(SearchKey.Icon(R.drawable.ic_keyboard, SearchKeyAction.Keyboard))
    add(SearchKey.Icon(R.drawable.ic_setting_home, SearchKeyAction.House))
    ('A'..'Z').forEach { add(SearchKey.Text(it.toString())) }
    ('0'..'9').forEach { add(SearchKey.Text(it.toString())) }
}

internal const val SEARCH_KEY_COLUMNS = 7
internal const val SEARCH_KEY_ROWS = 6
internal const val SEARCH_PLACEHOLDER_INDEX = 0
internal const val SEARCH_FIRST_KEY_INDEX = 1
internal const val SEARCH_HOUSE_KEY_INDEX = 5
internal const val SEARCH_KEYWORD_MAX_LENGTH = 19

/**
 * 虚拟键盘适配器。
 *
 * 焦点移动完全交给 [onKey]（Activity 显式按下标请求焦点），
 * 这样「最右列 → 右侧胶囊」「最后一行 ↓ → 站点 Tab」这类跨区规则只需要写一份。
 */
internal class SearchKeyAdapter(
    private val onKey: (position: Int, keyCode: Int) -> Boolean,
    private val onClick: (position: Int) -> Unit,
) : RecyclerView.Adapter<SearchKeyAdapter.Holder>() {

    private companion object {
        const val TYPE_TEXT = 0
        const val TYPE_ICON = 1
        const val TYPE_PLACEHOLDER = 2
    }

    override fun getItemCount(): Int = SEARCH_KEYS.size

    override fun getItemViewType(position: Int): Int = when (SEARCH_KEYS[position]) {
        is SearchKey.Placeholder -> TYPE_PLACEHOLDER
        is SearchKey.Icon -> TYPE_ICON
        is SearchKey.Text -> TYPE_TEXT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val inflater = LayoutInflater.from(parent.context)
        val layout = if (viewType == TYPE_ICON) {
            R.layout.adapter_search_key_icon
        } else {
            R.layout.adapter_search_key_text
        }
        return Holder(inflater.inflate(layout, parent, false))
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val view = holder.itemView
        when (val key = SEARCH_KEYS[position]) {
            is SearchKey.Placeholder -> {
                // 空格：既没有背景也不参与焦点，位置由 GridLayoutManager 自然留出。
                // `setOnClickListener` 会把 clickable 重新置 true，所以这里直接提前返回。
                view.isFocusable = false
                view.isFocusableInTouchMode = false
                view.isClickable = false
                view.background = null
                view.setOnClickListener(null)
                view.setOnKeyListener(null)
                (view as? TextView)?.text = ""
                return
            }

            is SearchKey.Icon -> {
                view.isFocusable = true
                view.isFocusableInTouchMode = true
                view.isClickable = true
                view.setBackgroundResource(R.drawable.selector_search_key)
                (view as ImageView).setImageResource(key.iconRes)
            }

            is SearchKey.Text -> {
                view.isFocusable = true
                view.isFocusableInTouchMode = true
                view.isClickable = true
                view.setBackgroundResource(R.drawable.selector_search_key)
                (view as TextView).text = key.value
            }
        }
        view.setOnClickListener { onClick(holder.bindingAdapterPosition) }
        view.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) {
                false
            } else {
                onKey(holder.bindingAdapterPosition, keyCode)
            }
        }
    }

    /** 把焦点送到第 [index] 个键（越界自动收敛）。 */
    fun focusKey(recycler: RecyclerView, index: Int): Boolean {
        val target = index.coerceIn(0, SEARCH_KEYS.lastIndex)
        recycler.scrollToPosition(target)
        val view = recycler.layoutManager?.findViewByPosition(target) ?: return false
        return view.requestFocus()
    }

    internal class Holder(view: View) : RecyclerView.ViewHolder(view)
}

// ---------------------------------------------------------------------------
// 历史 / 热搜 / 建议：胶囊
// ---------------------------------------------------------------------------

/**
 * 胶囊适配器（历史、热词、建议三处共用）。
 *
 * [onDown] 在「下方已经没有同一区块的下一行」时回调，由 Activity 决定落站点 Tab；
 * 左右移动先交给 `focusSearch` 的几何搜索（同一行下一个 / 上一行末尾都能命中），
 * 这样 Flexbox 自动换行后不需要手算行列。
 */
internal class SearchChipAdapter(
    private val onClick: (String) -> Unit,
    private val onDown: () -> Boolean,
) : RecyclerView.Adapter<SearchChipAdapter.Holder>() {

    private val items = mutableListOf<String>()

    fun submit(list: List<String>) {
        if (items == list) return
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.adapter_search_chip, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val text = items[position]
        holder.text.text = text
        holder.itemView.setOnClickListener { onClick(text) }
        holder.itemView.setOnKeyListener { view, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                -> move(view, keyCode)

                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    // 先走几何搜索：同一区块的下一行；没有下一行才交给 Activity 落站点 Tab。
                    if (move(view, keyCode)) true else onDown()
                }

                else -> false
            }
        }
    }

    private fun move(view: View, keyCode: Int): Boolean {
        val direction = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
            else -> View.FOCUS_DOWN
        }
        val next = view.focusSearch(direction) ?: return false
        // 容器本身（RecyclerView）不是合法落点：交给 Activity 决定跨区焦点，
        // 否则焦点会停在列表容器上，方向键再按就变成「滚动列表」。
        if (next === view || next is RecyclerView) return false
        return next.requestFocus()
    }

    /** 第一枚胶囊的 View（键盘最右列 / 站点 Tab ↑ 的落点）。 */
    fun firstView(recycler: RecyclerView): View? {
        if (items.isEmpty()) return null
        recycler.scrollToPosition(0)
        return recycler.layoutManager?.findViewByPosition(0)
    }

    internal class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.text)
    }
}

// ---------------------------------------------------------------------------
// 站点 Tab
// ---------------------------------------------------------------------------

/**
 * 站点 Tab 适配器。
 *
 * 三态（见 `selector_search_site_tab.xml`）：
 * 1. 聚焦 → 实心红渐变；
 * 2. 失焦 + 当前站点 + 焦点仍在结果区 → 红框 + 弱底（[submit] 的 `focused`）；
 * 3. 其余 → 半透明黑底。
 *
 * 选中缩放 1.08 / 140ms 与 `dz2.java` L45-47 一致。
 */
internal class SearchSiteAdapter(
    private val onFocused: (Int) -> Unit,
    private val onConfirm: (Int) -> Unit,
    private val onKey: (position: Int, keyCode: Int) -> Boolean,
) : RecyclerView.Adapter<SearchSiteAdapter.Holder>() {

    private val labels = mutableListOf<String>()
    private var selected = 0
    private var moduleFocused = false

    fun submit(list: List<String>, selectedIndex: Int, focused: Boolean) {
        val oldSelected = selected
        val sameContent = labels == list
        labels.clear()
        labels.addAll(list)
        selected = selectedIndex
        moduleFocused = focused
        when {
            // 只有「当前站点」与「焦点是否在结果区」会改变三态背景，其余 Tab 不必重绘。
            sameContent && oldSelected == selectedIndex -> {
                if (selected in labels.indices) notifyItemChanged(selected)
            }

            sameContent -> {
                if (oldSelected in labels.indices) notifyItemChanged(oldSelected)
                if (selected in labels.indices) notifyItemChanged(selected)
            }

            else -> notifyDataSetChanged()
        }
    }

    fun selectedIndex(): Int = selected

    override fun getItemCount(): Int = labels.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.adapter_search_site_tab, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val text = holder.text
        text.text = labels[position]
        text.isSelected = position == selected && moduleFocused
        val target = if (position == selected) 1.08f else 1f
        if (text.scaleX != target) {
            text.animate()
                .scaleX(target)
                .scaleY(target)
                .setDuration(140L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } else {
            text.animate().cancel()
        }
        text.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) onFocused(position) }
        text.setOnClickListener { onConfirm(holder.bindingAdapterPosition) }
        text.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) {
                false
            } else {
                onKey(holder.bindingAdapterPosition, keyCode)
            }
        }
    }

    /** 把焦点送到第 [index] 个 Tab（越界自动收敛）。 */
    fun focusTab(recycler: RecyclerView, index: Int): Boolean {
        if (labels.isEmpty()) return false
        val target = index.coerceIn(0, labels.lastIndex)
        recycler.scrollToPosition(target)
        val view = recycler.layoutManager?.findViewByPosition(target) ?: return false
        return view.requestFocus()
    }

    internal class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view as TextView
    }
}

// ---------------------------------------------------------------------------
// 结果卡
// ---------------------------------------------------------------------------

/**
 * 搜索结果卡适配器（原版 `ks2.java` + `adapter_search.xml`）。
 *
 * 字段映射：`vod.name` → 剧名；`vod.siteName` → 左上站点标签；`vod.remarks` → 左下集数标签；
 * `vod.pic` → 海报。图片走 Coil View 扩展（与首页 / 换源弹窗同一条链路），
 * `onViewRecycled` 里 `dispose()` 掉在途请求，避免快速滑动时旧图盖到新卡上。
 */
internal class SearchResultAdapter(
    private val onOpen: (Vod) -> Unit,
    private val onKey: (position: Int, keyCode: Int) -> Boolean,
) : RecyclerView.Adapter<SearchResultAdapter.Holder>() {

    private val items = mutableListOf<Vod>()

    fun submit(list: List<Vod>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.adapter_search_result, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val vod = items[position]
        holder.name.text = vod.name
        holder.site.text = vod.siteName
        holder.site.visibility = if (vod.siteName.isBlank()) View.GONE else View.VISIBLE
        holder.remark.text = vod.remarks
        holder.remark.visibility = if (vod.remarks.isBlank()) View.GONE else View.VISIBLE
        bindImage(holder.image, vod.pic)
        holder.itemView.setOnClickListener { onOpen(vod) }
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) {
                false
            } else {
                onKey(holder.bindingAdapterPosition, keyCode)
            }
        }
    }

    override fun onViewRecycled(holder: Holder) {
        holder.image.dispose()
        holder.image.tag = null
        holder.itemView.setOnKeyListener(null)
    }

    private fun bindImage(image: ImageView, url: String) {
        if (image.tag == url) return
        image.dispose()
        image.tag = url
        image.setImageDrawable(null)
        if (url.isBlank()) return
        image.load(url) {
            crossfade(false)
            error(R.drawable.shape_source_poster_placeholder)
        }
    }

    /** 把焦点送到第 [index] 张卡（越界自动收敛）。 */
    fun focusCard(recycler: RecyclerView, index: Int): Boolean {
        if (items.isEmpty()) return false
        val target = index.coerceIn(0, items.lastIndex)
        recycler.scrollToPosition(target)
        val view = recycler.layoutManager?.findViewByPosition(target) ?: return false
        return view.requestFocus()
    }

    internal class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.image)
        val site: TextView = view.findViewById(R.id.site)
        val remark: TextView = view.findViewById(R.id.remark)
        val name: TextView = view.findViewById(R.id.name)
    }
}

// ---------------------------------------------------------------------------
// 间距
// ---------------------------------------------------------------------------

/**
 * 横向等间距装饰：只在第 2 项及以后加 `left`，首项保持 0，
 * 等价于原版 leanback `setHorizontalSpacing(...)`（首尾不会多出半格间距）。
 */
internal class SearchGapDecoration(private val gapDp: Int) : RecyclerView.ItemDecoration() {

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State,
    ) {
        val position = parent.getChildAdapterPosition(view)
        if (position <= 0) return
        outRect.left = (gapDp * view.resources.displayMetrics.density).roundToInt()
    }
}
