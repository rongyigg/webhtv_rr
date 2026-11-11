package com.fongmi.android.tv.ui.hero

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.lottie.LottieAnimationView
import com.fongmi.android.tv.R
import com.fongmi.android.tv.bean.Episode
import com.fongmi.android.tv.ui.activity.isActionCommand

/**
 * 选集网格的纯 View 适配器（替换 Compose 版 `HeroEpisodeCell` + `LazyVerticalGrid` 的 item 段）。
 *
 * 与 Compose 版逐条对应：
 * - `label = episode.getDisplayName()` → [Holder.bind] 的 `text.text`（desc + name，TMDB 刮削后是 S01E02 标题）；
 * - `playing = episode === playingEpisode` → **引用相等**判定，不同线路的集数互不干扰，
 *   命中后：文字变红 + 右下角 Lottie 均衡器动画（原版 `adapter_episode.xml` 同款）；
 * - `actionOnly = episode.isActionCommand()` → 更暗底色 + 灰字（列表已在弹窗层过滤，这里是兜底）；
 * - 方向键**不在这里处理**：与 Compose 版一致，统一由网格层（RecyclerView 的 OnKeyListener）
 *   接管，保证「边缘整列翻组」的判断只有一处。
 */
internal class EpisodeAdapter(
    private val onCellFocused: (Int) -> Unit,
    private val onCellConfirm: (Int) -> Unit,
) : RecyclerView.Adapter<EpisodeAdapter.Holder>() {

    private var items: List<Episode> = emptyList()
    private var playing: Episode? = null

    /** 换组 / 换线路 / 翻转排序都整批替换，最多 32 项，直接全量刷新即可。 */
    fun submit(list: List<Episode>, playingEpisode: Episode?) {
        items = list
        playing = playingEpisode
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.adapter_episode_view, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val episode = items[position]
        holder.bind(episode, episode === playing, episode.isActionCommand())
        // 用 bindingAdapterPosition 而不是闭包里的 position：整批替换后旧下标会失效。
        holder.itemView.setOnClickListener { onCellConfirm(holder.bindingAdapterPosition) }
        holder.itemView.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) onCellFocused(holder.bindingAdapterPosition)
        }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {

        private val cell: View = view
        private val text: TextView = view.findViewById(R.id.text)
        private val lottie: LottieAnimationView = view.findViewById(R.id.lottie)

        fun bind(episode: Episode, playing: Boolean, actionOnly: Boolean) {
            text.text = episode.displayName
            text.setTextColor(
                when {
                    actionOnly -> ACTION_TEXT
                    playing -> PLAYING_TEXT
                    else -> Color.WHITE
                },
            )
            // 当前播放集：右下角均衡器动画（负边距压在格子角上，父级 clipChildren=false）。
            lottie.visibility = if (playing && !actionOnly) View.VISIBLE else View.GONE
            if (lottie.visibility == View.VISIBLE) lottie.playAnimation() else lottie.cancelAnimation()
            // 背景三态见 selector_episode_cell_background：
            // selected → 细线红框（当前播放集）；activated → 动作指令的更暗底色。
            cell.isSelected = playing && !actionOnly
            cell.isActivated = actionOnly
            // 必须放在 cell.isSelected 之后：ViewGroup.setSelected 会把状态下发给所有子 View，
            // 先设 root 再设 text 才能让 text 自己保持 selected（跑马灯只在 selected 时滚动）。
            text.isSelected = true
        }
    }

    private companion object {

        /** 动作指令伪集数的文字色：0x66FFFFFF，与 Compose `EPISODE_CELL_ACTION_TEXT` 同值。 */
        const val ACTION_TEXT = 0x66FFFFFF

        /** 当前播放集文字色：#FF0000，与 Compose `HeroAccentColor` 同值。 */
        const val PLAYING_TEXT = -0x10000
    }
}
