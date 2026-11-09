package com.fongmi.android.tv.ui.hero

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.fongmi.android.tv.bean.History
import kotlin.math.roundToInt

/**
 * 历史页水平海报行适配器（100% 纯 View）。
 *
 * 每一格直接 `new HeroPosterCard(context)`：
 * - 尺寸 120×214、8dp 圆角、2dp 红色焦点框、站点角标 / TMDB 角标 / 编辑遮罩全部由卡片自己负责；
 * - 卡片默认走内部 Glide（`ImgUtil.getUrl` 会处理 `@Headers=` / Referer 等指令），
 *   与 `ref_docs/adapter_history_poster.xml` 的 `image` 行为一致；
 * - `onViewRecycled` 必须 `releasePoster()`，否则快速滑动时上一张图会贴到复用卡上。
 *
 * 字段对应 `s11.java` L89-123：
 * - `name`  → [HeroPosterCard.title]；
 * - `image` → [HeroPosterCard.imageUrl]；
 * - `siteTag` / `siteGradient` → [HeroPosterCard.siteTag]（站点名为空时两者一起隐藏）；
 * - `tmdbTag` → [HeroPosterCard.tmdbTag]（`s11.b(history)`，见 [HistoryCardTags]）；
 * - `delete` 覆盖层 / 长按 → [HeroPosterCard.isEditMode] / [HeroPosterCard.onDeleteClick]。
 */
class HistoryPosterAdapter(
    private val onFocused: (position: Int) -> Unit,
    private val onClick: (position: Int) -> Unit,
    private val onLongPress: (position: Int) -> Unit,
) : RecyclerView.Adapter<HistoryPosterAdapter.ViewHolder>() {

    private val items = mutableListOf<History>()

    /**
     * 编辑模式（适配器级开关，对应原版 `s11.i` + `o11.m0`）：
     * 为 `true` 时所有卡片的删除覆盖层一起显示，点击卡片 = 删除该条。
     */
    var isEditMode: Boolean = false
        private set

    fun submit(next: List<History>) {
        items.clear()
        items.addAll(next)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    fun getItem(position: Int): History? = items.getOrNull(position)

    /**
     * 切换编辑模式。逐条对应原版 `o11.m0(z)`：状态未变直接返回；
     * 有数据时 `notifyItemRangeChanged(0, size)` 重绑当前可见卡片。
     */
    fun setEditMode(enable: Boolean) {
        if (isEditMode == enable) return
        isEditMode = enable
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val density = parent.resources.displayMetrics.density
        val card = HeroPosterCard(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(
                (HeroPosterCard.CARD_WIDTH_DP * density).roundToInt(),
                (HeroPosterCard.CARD_HEIGHT_DP * density).roundToInt(),
            )
            isFocusable = true
            isFocusableInTouchMode = true
        }
        val holder = ViewHolder(card)
        card.setOnClickListener {
            val position = holder.bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION) onClick(position)
        }
        // 长按进入编辑模式：原版 `s11` L122 `relativeLayout.setOnLongClickListener(...)`。
        card.setOnLongClickListener {
            val position = holder.bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION) onLongPress(position)
            true
        }
        // 删除图标覆盖层只在编辑态可见；点它 = 删除这一条（与编辑态点卡片走同一条路径）。
        card.onDeleteClick = {
            val position = holder.bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION) onClick(position)
        }
        card.setOnFocusChangeListener { _, hasFocus ->
            // 与首页海报行同一套「焦点放大」：只改绘制层，不参与测量。
            card.animate()
                .scaleX(if (hasFocus) FOCUS_SCALE else 1f)
                .scaleY(if (hasFocus) FOCUS_SCALE else 1f)
                .translationZ(if (hasFocus) FOCUS_ELEVATION_DP * density else 0f)
                .withLayer()
                .setDuration(FOCUS_ANIM_MS)
                .start()
            if (!hasFocus) return@setOnFocusChangeListener
            val position = holder.bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION) onFocused(position)
        }
        return holder
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val history = items[position]
        val card = holder.card
        card.title = history.vodName.orEmpty()
        card.imageUrl = history.vodPic.orEmpty()
        card.siteTag = history.siteName.orEmpty().takeIf { it.isNotEmpty() }
        card.tmdbTag = HistoryCardTags.isTmdbTagVisible(history)
        card.isEditMode = isEditMode
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.card.releasePoster()
        holder.card.animate().cancel()
        holder.card.scaleX = 1f
        holder.card.scaleY = 1f
        holder.card.translationZ = 0f
        holder.card.visibility = View.VISIBLE
    }

    inner class ViewHolder(val card: HeroPosterCard) : RecyclerView.ViewHolder(card)

    private companion object {
        /** 原版 `mm3.f(horizontalGridView, 1.08f)`：焦点行整体放大 1.08。 */
        const val FOCUS_SCALE = 1.08f

        /** 抬升高度（dp），仅用于投阴影，不影响布局。 */
        const val FOCUS_ELEVATION_DP = 16f

        const val FOCUS_ANIM_MS = 150L
    }
}
