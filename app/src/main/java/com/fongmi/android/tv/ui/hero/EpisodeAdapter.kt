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
 *   命中后：文字变红 + 右侧垂直居中 Lottie 均衡器动画（位置对齐播放页 OSD 选集卡）。
 *   播放态只认**真实起播**写入的对象（`EpisodeViewDialog.onCellConfirm`），
 *   历史集 / 首集 / 换线路后的第一集都不许预置，否则就是「假数据红字」；
 * - 文字颜色四态：焦点纯白 → 动作指令半透明白 → 真实播放集红 → 常态白，
 *   焦点态背景是实心红渐变，任何非白字都会糊掉，所以焦点优先级最高；
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

    /**
     * 换组 / 换线路 / 翻转排序都整批替换，最多 32 项，直接全量刷新即可。
     *
     * 这里**必须**保持 `notifyDataSetChanged`：它会让
     * [RecyclerView.findViewHolderForAdapterPosition] 返回 null，从而让
     * [EpisodeViewDialog.requestCellFocus] 的重试循环一直重试到布局结束、把焦点还给网格。
     * 换成 `notifyItemRangeChanged` 会立刻拿到旧 ViewHolder、请求焦点「成功」而不再重试，
     * 卡片随后在重新布局中被 detach，焦点就再也回不到网格。
     */
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
            // 焦点态是实心红底，文字必须当场刷成纯白；失焦后再回到真实播放态 / 动作指令 / 常态色。
            holder.applyTextColor(hasFocus)
            holder.applyMarquee(hasFocus)
            if (hasFocus) onCellFocused(holder.bindingAdapterPosition)
        }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {

        private val cell: View = view
        private val text: TextView = view.findViewById(R.id.text)
        private val lottie: LottieAnimationView = view.findViewById(R.id.lottie)

        private var playing = false
        private var actionOnly = false

        fun bind(episode: Episode, playing: Boolean, actionOnly: Boolean) {
            this.playing = playing
            this.actionOnly = actionOnly
            text.text = episode.displayName
            // 重新绑定可能发生在「格子正持有焦点」时（整批替换后焦点不丢），所以要读当前焦点态。
            applyTextColor(cell.isFocused)
            // 当前播放集：右侧垂直居中均衡器动画（与 OSD 同位置，38dp 卡内不越界）。
            lottie.visibility = if (playing && !actionOnly) View.VISIBLE else View.GONE
            if (lottie.visibility == View.VISIBLE) lottie.playAnimation() else lottie.cancelAnimation()
            // 背景三态见 selector_episode_cell_background：
            // selected → 细线红框（当前播放集）；activated → 动作指令的更暗底色。
            cell.isSelected = playing && !actionOnly
            cell.isActivated = actionOnly
            // 必须放在 cell.isSelected 之后：ViewGroup.setSelected 会把状态下发给所有子 View，
            // 再按焦点覆盖一次，跑马灯才只在**焦点卡片**上滚动；失焦时 ellipsize=end 截断。
            applyMarquee(cell.isFocused)
        }

        /**
         * 文字颜色四态，优先级从高到低：
         * 1. 焦点 → 纯白（背景是实心红渐变，红字 / 灰字在上面都看不清）；
         * 2. 动作指令 → 半透明白；
         * 3. 真实播放集 → 红字（原版设计，只在**真正起播过**时出现）；
         * 4. 其余 → 纯白。
         *
         * 焦点态不写死在 XML 的 textColor 里：cell 的焦点态不会自动下发给子 TextView
         * （只有 selected / activated 会 dispatch），所以必须由这里显式重刷。
         */
        fun applyTextColor(focused: Boolean) {
            text.setTextColor(
                when {
                    focused -> Color.WHITE
                    actionOnly -> ACTION_TEXT
                    playing -> PLAYING_TEXT
                    else -> Color.WHITE
                },
            )
        }

        /** 焦点卡片才开跑马灯；失焦恢复 ellipsize=end 截断，避免整屏文字同时滚动。 */
        fun applyMarquee(focused: Boolean) {
            text.isSelected = focused
        }
    }

    private companion object {

        /** 动作指令伪集数的文字色：0x66FFFFFF，与 Compose `EPISODE_CELL_ACTION_TEXT` 同值。 */
        const val ACTION_TEXT = 0x66FFFFFF

        /** 当前播放集文字色：#FF0000，与 Compose `HeroAccentColor` 同值。 */
        const val PLAYING_TEXT = -0x10000
    }
}
