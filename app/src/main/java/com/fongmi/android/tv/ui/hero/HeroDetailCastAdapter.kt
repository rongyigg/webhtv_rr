package com.fongmi.android.tv.ui.hero

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil3.dispose
import coil3.load
import coil3.request.crossfade
import coil3.request.error
import com.fongmi.android.tv.R
import com.fongmi.android.tv.ui.activity.HeroCast

/**
 * 详情页演职员行适配器（纯 View）。
 *
 * 每一格就是 `item_hero_detail_cast.xml`：72dp 宽、60dp 圆头像、12sp 单行名字；
 * 焦点态（2dp 红圈 + 1.08 放大）由样式里的 selector / stateListAnimator 负责，
 * 这里只维护「焦点下标」与方向键边界，与 Compose 版 `HeroCastItem` 一致：
 * - ↑ 回按钮组「播放」；↓ 已是最底部，直接消费；
 * - ←/→ 行内横移，首/末项在端点消费，不把焦点漏到别的区域。
 */
internal class HeroDetailCastAdapter(
    private val onFocused: (Int) -> Unit,
    private val onClick: (Int) -> Unit,
    private val onMoveUp: () -> Unit,
) : RecyclerView.Adapter<HeroDetailCastAdapter.Holder>() {

    private val items = mutableListOf<HeroCast>()

    fun submit(next: List<HeroCast>) {
        items.clear()
        items.addAll(next)
        notifyDataSetChanged()
    }

    fun getItem(position: Int): HeroCast? = items.getOrNull(position)

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_hero_detail_cast, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.name.text = item.name
        bindAvatar(holder.avatar, item.avatarUrl)
        holder.itemView.setOnClickListener {
            val index = holder.bindingAdapterPosition
            if (index != RecyclerView.NO_POSITION) onClick(index)
        }
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) {
                false
            } else {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        onMoveUp()
                        true
                    }

                    // 演职员行是最底部区域：↓ 无处可去，直接消费。
                    KeyEvent.KEYCODE_DPAD_DOWN -> true

                    // 首项 ← / 末项 → 在端点消费，避免焦点跑到左侧空白或跳区。
                    KeyEvent.KEYCODE_DPAD_LEFT -> holder.bindingAdapterPosition == 0
                    KeyEvent.KEYCODE_DPAD_RIGHT -> holder.bindingAdapterPosition == itemCount - 1
                    else -> false
                }
            }
        }
        holder.itemView.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                val index = holder.bindingAdapterPosition
                if (index != RecyclerView.NO_POSITION) onFocused(index)
            }
        }
    }

    override fun onViewRecycled(holder: Holder) {
        holder.avatar.dispose()
        holder.avatar.tag = null
        holder.itemView.setOnKeyListener(null)
        holder.itemView.setOnFocusChangeListener(null)
        holder.itemView.setOnClickListener(null)
    }

    /** 头像地址没变就不重载，避免复用格子时闪一下；加载失败回落本地渐变占位。 */
    private fun bindAvatar(image: ImageView, url: String) {
        if (image.tag == url) return
        image.dispose()
        image.tag = url
        image.setImageDrawable(null)
        if (url.isBlank()) return
        image.load(url) {
            crossfade(false)
            error(R.drawable.bg_hero_detail_demo_avatar)
        }
    }

    class Holder(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val avatar: ImageView = view.findViewById(R.id.castAvatar)
        val name: TextView = view.findViewById(R.id.castName)
    }
}
