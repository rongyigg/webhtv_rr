package com.fongmi.android.tv.ui.hero

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil3.dispose
import coil3.load
import coil3.request.crossfade
import coil3.request.error
import coil3.request.placeholder
import com.fongmi.android.tv.R
import com.fongmi.android.tv.bean.Site
import com.fongmi.android.tv.bean.Vod

/**
 * 首页一张海报卡的展示数据（纯 View 体系）。
 *
 * 字段与 `HeroHomeActivity` 里的私有 `HeroPoster` 一一对应，但**不能直接复用那个 data class**：
 * 它声明在 `HeroHomeActivity.kt` 内部且为 `private`，外部文件不可见，而本步的红线是
 * 不修改 `HeroHomeActivity.kt`。所以这里复制同一份结构，避免为了复用去动已有首页。
 *
 * 前六个字段只服务 UI；[vod] / [site] 是真实数据本体，点确定键时直接交给详情页，
 * 不再按剧名二次搜索（剧名可能重名，`Vod.id` 才是站点内唯一键）。
 */
data class HomePosterItem(
    /** 站点内条目身份：`heroTmdbKey(site.key, vod.id)`，与首页缓存 / TMDB override 同一套键。 */
    val posterKey: String,
    val title: String,
    val meta: String,
    val synopsis: String,
    /** 竖版海报（源站 `vod.pic`；手动匹配且源站没图时回落 TMDB 竖图）。 */
    val posterUrl: String,
    /**
     * **只装真横图**（TMDB `backdropUrl` 非空时提档到 w1920）。
     *
     * 空串 = 这张卡没有横图：首页大图必须为空，竖图只留在右上角 posterCard / 海报行卡片里，
     * 绝不把竖图拉伸铺满全屏（对应原版 `HomeActivityV2.E2(pos)` 的 if-else）。
     */
    val backdropUrl: String,
    /** 艺术字 Logo；null / 空串 = 用 32sp 文字标题。 */
    val logoUrl: String? = null,
    val vod: Vod,
    val site: Site,
)

/**
 * 首页海报行 Adapter（View 体系）。
 *
 * 直接复用全局唯一海报卡 [HeroPosterCard]（120dp × 214dp、8dp 圆角、2dp 红色焦点描边），
 * 因此**不需要新的 item 布局 XML**：卡片内部全部是代码创建的 Material / 原生 View。
 *
 * 与 `HeroHomeActivity` 内嵌的原生 Adapter 保持逐条一致：
 * - 图片走 Coil 的 View 扩展（300ms 淡入），卡片置 `externalImageLoading = true`，自身不发 Glide 请求；
 * - `onViewRecycled` 里 `dispose()` 取消在途请求，避免快速滑动时「上一张图晚到、贴到复用的卡上」；
 * - 焦点获得时整卡 1.1x 缩放 + 抬升阴影（150ms），只改绘制层，不参与测量。
 *
 * 唯一新增的职责：[onFocused] 把**当前卡片的 [Vod] 交给 Activity**，由 Activity 去渲染 Hero 区。
 */
class HeroPosterAdapter(
    private val onFocused: (position: Int, vod: Vod) -> Unit,
    private val onClick: (HomePosterItem) -> Unit,
) : RecyclerView.Adapter<HeroPosterAdapter.ViewHolder>() {

    private var items: List<HomePosterItem> = emptyList()

    /**
     * 提交新列表，返回 `true` 表示发生了**整排刷新**（长度 / 顺序变了，holder 会回池）。
     *
     * 与首页 Compose 版同样的精准刷新策略：同一批条目只是字段变化时走
     * `notifyItemChanged`，正在放大的焦点卡不会被回收重置。
     */
    fun submit(next: List<HomePosterItem>): Boolean {
        if (next == items) return false
        val previous = items
        items = next
        val sameShape = previous.size == next.size &&
            previous.indices.all { previous[it].posterKey == next[it].posterKey }
        if (!sameShape) {
            notifyDataSetChanged()
            return true
        }
        for (index in next.indices) {
            if (previous[index] != next[index]) notifyItemChanged(index)
        }
        return false
    }

    fun getItem(position: Int): HomePosterItem? = items.getOrNull(position)

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val card = HeroPosterCard(parent.context).apply {
            // 首页剧名 13sp（adapter_vod_rect L46）；宽高 / 圆角 / 焦点红框全部写死在卡片里。
            titleTextSizeSp = POSTER_TITLE_SP
            // 图片交给 Coil：卡片自己不再发 Glide 请求，也不会画「剧名首字」盖住海报。
            externalImageLoading = true
        }
        val holder = ViewHolder(card)
        val density = card.resources.displayMetrics.density
        card.setOnClickListener {
            getItem(holder.bindingAdapterPosition)?.let(onClick)
        }
        card.setOnFocusChangeListener { _, hasFocus ->
            // 【焦点放大】原生 View 版：整张卡 1.1x + 抬升，150ms 收尾。
            card.animate()
                .scaleX(if (hasFocus) POSTER_FOCUS_SCALE else 1f)
                .scaleY(if (hasFocus) POSTER_FOCUS_SCALE else 1f)
                .translationZ(if (hasFocus) POSTER_FOCUS_ELEVATION_DP * density else 0f)
                // 动画期间提成硬件层：每帧只做一次纹理变换，电视盒子上不掉帧。
                .withLayer()
                .setDuration(POSTER_FOCUS_ANIM_MS)
                .start()
            if (!hasFocus) return@setOnFocusChangeListener
            val position = holder.bindingAdapterPosition
            val item = items.getOrNull(position) ?: return@setOnFocusChangeListener
            // 【核心契约】把这张卡的真实 Vod 交给 Activity：Hero 区由 Activity 同步渲染。
            onFocused(position, item.vod)
        }
        return holder
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val card = holder.card
        // tag 只是排查用的旁路信息，不参与点击定位（点击一律按 bindingAdapterPosition 取）。
        card.tag = item
        // 先 title、再 imageUrl：externalImageLoading = true 时两个 setter 只维护占位状态。
        card.title = item.title
        card.imageUrl = item.posterUrl
        // 【串图防护】Coil 的 crossfade 以 View 当前 drawable 为起点：同一个 holder 换成另一条数据时，
        // 上一张卡的海报会一直留在画面里，直到新请求成功。换 URL 时先取消在途请求、清掉旧图；
        // 同 URL 的精准刷新保留原图，避免闪白。
        val posterView = card.posterImageView
        val posterUrl = item.posterUrl
        val bound = posterView.tag as? BoundPosterThumb
        if (bound == null || bound.posterKey != item.posterKey || bound.url != posterUrl) {
            posterView.dispose()
            posterView.tag = BoundPosterThumb(item.posterKey, posterUrl)
            if (posterUrl.isBlank()) {
                // 空地址没有可加载的内容：直接回占位图，绝不复用上一张卡的图。
                card.releasePoster()
            } else {
                posterView.setImageDrawable(null)
            }
        }
        if (posterUrl.isNotBlank()) {
            posterView.load(posterUrl) {
                crossfade(POSTER_CARD_CROSSFADE_MS)
                // 403 防盗链 / 404 / 解码失败时回灰底占位卡，淡入期间也不会出现空白帧。
                placeholder(R.drawable.shape_source_poster_placeholder)
                error(R.drawable.shape_source_poster_placeholder)
            }
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        // 取消在途的 Coil 请求：请求晚到时会把上一张图贴到复用的卡上（串图 / 错位）。
        holder.card.posterImageView.dispose()
        holder.card.posterImageView.tag = null
        holder.card.releasePoster()
        holder.card.tag = null
        // 复位焦点动画：被回收的卡可能还停在 1.1x / 抬升状态，不复位会带到下一条数据上。
        holder.card.animate().cancel()
        holder.card.scaleX = 1f
        holder.card.scaleY = 1f
        holder.card.translationZ = 0f
    }

    inner class ViewHolder(val card: HeroPosterCard) : RecyclerView.ViewHolder(card)

    /**
     * 卡片绑定标记：只比对 URL 不够，不同条目完全可能拿到同一个地址（空串 / 站点占位图），
     * 所以同时记条目身份（`posterKey`）与 URL，任一变化都视为换了图。
     */
    private data class BoundPosterThumb(val posterKey: String, val url: String)

    private companion object {
        /** 首页剧名 13sp（`ref_docs/adapter_vod_rect.xml` L46）。 */
        private const val POSTER_TITLE_SP = 13f

        /** 卡片图片淡入 300ms（首页其它大图仍用 450ms）。 */
        private const val POSTER_CARD_CROSSFADE_MS = 300

        /** 焦点卡放大倍数（只影响绘制，不影响布局）。 */
        private const val POSTER_FOCUS_SCALE = 1.1f

        /** 焦点放大 / 阴影动画时长。 */
        private const val POSTER_FOCUS_ANIM_MS = 150L

        /** 焦点卡抬升高度（dp），配合放大投一层影子。 */
        private const val POSTER_FOCUS_ELEVATION_DP = 18f
    }
}

/**
 * 横向列表的项间距：只在**非末项**右侧留 [gapPx]。
 *
 * 海报行末项不加间距，行尾 padding `(宽 - 120dp) / 2` 才能把它精确停在屏幕正中；
 * 顶栏 / Tab 行复用同一个装饰器，只是间距不同。
 */
class HeroHorizontalGapDecoration(private val gapPx: Int) : RecyclerView.ItemDecoration() {

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State,
    ) {
        val position = parent.getChildAdapterPosition(view)
        val last = (parent.adapter?.itemCount ?: 0) - 1
        outRect.set(0, 0, if (position in 0 until last) gapPx else 0, 0)
    }
}
