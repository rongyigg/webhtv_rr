package com.fongmi.android.tv.ui.hero

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.fongmi.android.tv.R
import com.fongmi.android.tv.bean.Keep
import com.fongmi.android.tv.utils.ImgUtil
import kotlin.math.roundToInt

/**
 * 收藏页水平卡片适配器（100% 纯 View，布局 = `adapter_keep_poster_view.xml`）。
 *
 * 为什么**不复用** `HeroPosterCard`：收藏页的卡片结构与首页 / 历史页不同 ——
 * 站点名在**海报上方**单独一行（逆向 `adapter_keep_poster.xml` 的 `site`），
 * 海报 120×180，剧名 13sp 跑马灯。`HeroPosterCard` 的站点名是压在海报左上角的
 * 角标、整卡 120×214，直接改它会把三个已验收页面一起改坏，所以这里按逆向结构
 * 新建一个独立布局与适配器。
 *
 * 交互（对应 `ref_docs/KeepPosterPresenter.java` + `pp.java` case 16/17）：
 * - 焦点 → [onFocused]（宿主刷新 Hero 大图 / 艺术字 / 简介）；
 * - 确定键 → [onClick]（编辑态 = 删除，普通态 = 进详情）；
 * - 长按 → [onLongPress]（宿主呼出换源弹窗；原版是进入编辑态，本页编辑态改由 MENU 键触发）；
 * - 编辑态由 [setEditMode] 统一切换，所有卡片一起盖 `ic_vod_delete` 遮罩。
 */
class KeepPosterAdapter(
    private val onFocused: (position: Int) -> Unit,
    private val onClick: (position: Int) -> Unit,
    private val onLongPress: (position: Int) -> Unit,
) : RecyclerView.Adapter<KeepPosterAdapter.ViewHolder>() {

    private val items = mutableListOf<Keep>()

    /**
     * 编辑模式（适配器级开关，对应 `KeepActivity` 的 `e0` / 原版 `KeepPosterPresenter.i`）：
     * 为 `true` 时所有卡片一起显示垃圾桶遮罩，点击卡片 = 删除该条。
     */
    var isEditMode: Boolean = false
        private set

    fun submit(next: List<Keep>) {
        items.clear()
        items.addAll(next)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    fun getItem(position: Int): Keep? = items.getOrNull(position)

    /**
     * 切换编辑模式（逐条对应 `KeepPosterPresenter` 的 `i` + 适配器重绑）：
     * 状态未变直接返回；有数据时 `notifyItemRangeChanged(0, size)` 重绑当前可见卡片。
     */
    fun setEditMode(enable: Boolean) {
        if (isEditMode == enable) return
        isEditMode = enable
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
    }

    /**
     * 删除一条并通知列表。需求指定用 `notifyItemRemoved(position)`：
     * 单条删除不动其它卡片的 ViewHolder，焦点由宿主在回调后重新落到下一张。
     */
    fun removeAt(position: Int) {
        if (position !in items.indices) return
        items.removeAt(position)
        notifyItemRemoved(position)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.adapter_keep_poster_view, parent, false)
        val holder = ViewHolder(view)
        val density = parent.resources.displayMetrics.density

        view.setOnClickListener {
            val position = holder.bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION) onClick(position)
        }
        // 长按 = 换源（`pp.java` case 17 的另一个入口；本页长按语义按需求改为换源）。
        view.setOnLongClickListener {
            val position = holder.bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION) onLongPress(position)
            true
        }
        // 垃圾桶遮罩只在编辑态可见；点它 = 删除这一条（与编辑态确定键同一条路径）。
        holder.delete.setOnClickListener {
            val position = holder.bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION) onClick(position)
        }
        view.setOnFocusChangeListener { _, hasFocus ->
            // 与历史页 / 首页海报行同一套「焦点放大」：只改绘制层，不参与测量。
            view.animate()
                .scaleX(if (hasFocus) FOCUS_SCALE else 1f)
                .scaleY(if (hasFocus) FOCUS_SCALE else 1f)
                .translationZ(if (hasFocus) FOCUS_ELEVATION_DP * density else 0f)
                .withLayer()
                .setDuration(FOCUS_ANIM_MS)
                .start()
            holder.focusRing.visibility = if (hasFocus) View.VISIBLE else View.GONE
            if (!hasFocus) return@setOnFocusChangeListener
            val position = holder.bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION) onFocused(position)
        }

        // 焦点红框：2dp 纯红 #FF0000 + 8dp 圆角，只包 120×180 海报区（与全局海报卡一致）。
        holder.focusRing.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = CORNER_DP * density
            setStroke((FOCUS_STROKE_DP * density).roundToInt(), FOCUS_COLOR)
        }
        // 站点名的「渐变阴影背景」：左黑右透明的横向渐变，文字阴影在 XML 里。
        holder.site.background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(SITE_BACKGROUND_START, Color.TRANSPARENT),
        )
        return holder
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val keep = items[position]
        val siteName = keep.siteName.orEmpty()
        val vodName = keep.vodName.orEmpty()
        // 空站点名用 INVISIBLE 而不是 GONE：保留 18dp，整行卡片高度不跳。
        holder.site.text = siteName
        holder.site.visibility = if (siteName.isEmpty()) View.INVISIBLE else View.VISIBLE
        holder.name.text = vodName
        // 跑马灯必须 selected：TextView 只在 selected / focused 时滚动。
        holder.name.isSelected = true
        holder.delete.visibility = if (isEditMode) View.VISIBLE else View.GONE
        // 竖图走 `ImgUtil`：解析 `@Headers=` / `@Referer=` 指令，失败回落「剧名首字」占位，
        // 与历史页右上角小海报、旧收藏页卡片是同一套加载路径。
        ImgUtil.load(vodName, keep.vodPic.orEmpty(), holder.image, true)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        // 必须清：快速滑动时上一张的 Glide 请求晚到会把图贴到复用卡上。
        ImgUtil.clear(holder.image)
        holder.itemView.animate().cancel()
        holder.itemView.scaleX = 1f
        holder.itemView.scaleY = 1f
        holder.itemView.translationZ = 0f
        holder.focusRing.visibility = View.GONE
        holder.delete.visibility = View.GONE
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {

        val site: TextView = view.findViewById(R.id.keepSite)
        val image: ImageView = view.findViewById(R.id.keepImage)
        val name: TextView = view.findViewById(R.id.keepName)
        val delete: ImageView = view.findViewById(R.id.keepDelete)
        val focusRing: View = view.findViewById(R.id.keepFocusRing)
    }

    private companion object {

        /** 原版 `mm3.f(horizontalGridView, 1.08f)`：焦点行整体放大 1.08。 */
        const val FOCUS_SCALE = 1.08f

        /** 抬升高度（dp），仅用于投阴影，不影响布局。 */
        const val FOCUS_ELEVATION_DP = 16f

        const val FOCUS_ANIM_MS = 150L

        /** 海报圆角 8dp（`@style/Vod.Grid.Large` 同值，焦点框必须与它重合）。 */
        const val CORNER_DP = 8f

        /** 焦点描边 2dp（与首页 / 历史页海报卡完全一致）。 */
        const val FOCUS_STROKE_DP = 2f

        /** 焦点描边颜色 #FFFF0000（首页 `HeroAccentColor` 同值）。 */
        val FOCUS_COLOR = 0xFFFF0000.toInt()

        /** 站点名背景渐变起点：#CC000000（80% 黑），向右渐隐到全透明。 */
        val SITE_BACKGROUND_START = 0xCC000000.toInt()
    }
}
