package com.fongmi.android.tv.ui.activity

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.fongmi.android.tv.R
import com.fongmi.android.tv.ui.hero.HERO_HISTORY_ITEMS
import com.fongmi.android.tv.ui.hero.HeroHistoryItem
import com.fongmi.android.tv.ui.hero.HeroPosterCard
import java.util.Locale
import kotlin.math.roundToInt

/**
 * HeroUI 历史页（View 体系，不使用任何 Compose / leanback）。
 *
 * 结构与真值一一对应 `ref_docs/fragment_history.xml`：
 * - 底层：全屏 `heroBg` 背景大图（L13-19）+ `maskLeft`（L20-25）+ `maskBottom`（L26-31）；
 * - 信息层：`heroNameText` 32sp / `heroMetaText` 14sp / `heroDescText` 12sp（L90-145）；
 * - 操作层：`btnResume`「查看详情」32dp（L158-173）+ 进度组 200dp
 *   （`progressEpisode` + `progressTotal` + 2dp `progressBar`，L174-219）；
 * - 海报行：整行 215dp、左右 24dp（L221-238）。
 *
 * 与原版的两处**必要差异**：
 * 1. 原版 `HorizontalGridView`（leanback）改为 `RecyclerView` + `LinearLayoutManager(HORIZONTAL)`，
 *    因为 main 源集禁止 import `androidx.leanback`（AGENTS.md）；
 * 2. 卡片不复刻 `adapter_history_poster.xml`，而是全局唯一组件 [HeroPosterCard]
 *    （120×214、四角 8dp、2dp #FFFF0000 焦点框、34dp 剧名栏）；历史页只负责塞内容字段。
 *    `siteTag` → [HeroPosterCard.topStartTag]，剧名 → [HeroPosterCard.title]，
 *    备注字段保留但不显示（原版历史卡也没有备注）。
 *
 * 焦点约定：
 * - 进入页面焦点落在第 1 张卡（与首页「↓ 进海报行落第一张」一致）；
 * - 卡片 ←/→ 由 RecyclerView 自己处理，焦点变化即时刷新左侧 Hero 信息与背景；
 * - 「查看详情」↓ 进海报行，海报行 ↑ 回按钮（XML 里 `nextFocusDown/Up` 已互相指好）；
 * - 卡片确定键 → [HeroDetailActivity]（只传剧名，详情页数据仍是它自己的 Mock）。
 *
 * TODO(数据层对接)：`HERO_HISTORY_ITEMS` 换成真实 `History` 表；卡片确定键写入「最近观看」；
 *   背景/海报接真实地址（当前是 picsum 稳定链接）。
 */
class HeroHistoryActivity : AppCompatActivity() {

    private lateinit var backdrop: ImageView
    private lateinit var heroName: TextView
    private lateinit var heroMeta: TextView
    private lateinit var heroDesc: TextView
    private lateinit var progressEpisode: TextView
    private lateinit var progressTotal: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnResume: TextView
    private lateinit var recycler: RecyclerView

    /** 当前左侧 Hero 信息展示的是第几条历史；卡片焦点一变就跟着换。 */
    private var focusedIndex: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContentView(R.layout.activity_hero_history)
        bindViews()
        bindPosterRow()
        bindResumeButton()
        applyHeroInfo(0)
        recycler.post { focusPoster(0) }
    }

    private fun bindViews() {
        backdrop = findViewById(R.id.backdrop)
        heroName = findViewById(R.id.heroName)
        heroMeta = findViewById(R.id.heroMeta)
        heroDesc = findViewById(R.id.heroDesc)
        progressEpisode = findViewById(R.id.progressEpisode)
        progressTotal = findViewById(R.id.progressTotal)
        progressBar = findViewById(R.id.progressBar)
        btnResume = findViewById(R.id.btnResume)
        recycler = findViewById(R.id.recycler)
    }

    private fun bindPosterRow() {
        recycler.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        recycler.adapter = PosterAdapter()
        // f23 用 setHorizontalSpacing，RecyclerView 侧等价物是 ItemDecoration。
        recycler.addItemDecoration(SpacingDecoration(dp(POSTER_GAP_DP)))
        recycler.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
    }

    private fun bindResumeButton() {
        btnResume.setOnClickListener { openDetail(HERO_HISTORY_ITEMS.getOrNull(focusedIndex)) }
    }

    /** 左侧 Hero 信息 + 背景大图随焦点切换，等价原版 `onBindViewHolder` 里的 heroInfo 刷新。 */
    private fun applyHeroInfo(index: Int) {
        val item = HERO_HISTORY_ITEMS.getOrNull(index) ?: return
        focusedIndex = index
        heroName.text = item.title
        heroMeta.text = item.meta
        heroDesc.text = item.synopsis
        progressEpisode.text = getString(R.string.hero_history_episode, item.episode)
        progressTotal.text = getString(
            R.string.hero_history_progress,
            formatDuration(item.positionMs),
            formatDuration(item.durationMs),
        )
        progressBar.max = PROGRESS_MAX
        progressBar.progress = progressPercent(item)
        loadBackdrop(item.backdrop)
    }

    private fun progressPercent(item: HeroHistoryItem): Int {
        if (item.durationMs <= 0L) return 0
        return ((item.positionMs * PROGRESS_MAX) / item.durationMs).toInt().coerceIn(0, PROGRESS_MAX)
    }

    /** `history_page.png` 的进度文案是「27:15 / 47:34」，即 mm:ss / mm:ss。 */
    private fun formatDuration(millis: Long): String {
        val totalSeconds = (millis / 1000L).coerceAtLeast(0L)
        return String.format(Locale.US, "%02d:%02d", totalSeconds / 60L, totalSeconds % 60L)
    }

    private fun loadBackdrop(url: String) {
        if (url.isBlank()) {
            backdrop.setImageDrawable(null)
            return
        }
        Glide.with(applicationContext).load(url).centerCrop().into(backdrop)
    }

    private fun openDetail(item: HeroHistoryItem?) {
        item ?: return
        HeroDetailActivity.start(this, item.title)
    }

    /** 首帧可能还没完成布局，重试几次保证焦点落在第一张卡上（与 ChangeSourceDialog 同一套兜底）。 */
    private fun focusPoster(index: Int, attempt: Int = 0) {
        val holder = recycler.findViewHolderForAdapterPosition(index)
        if (holder != null && holder.itemView.requestFocus()) return
        if (attempt < FOCUS_RETRY_COUNT) {
            recycler.postDelayed({ focusPoster(index, attempt + 1) }, FOCUS_RETRY_DELAY_MS)
            return
        }
        recycler.requestFocus()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private inner class PosterAdapter : RecyclerView.Adapter<PosterAdapter.Holder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            // ↑ 固定回「查看详情」按钮（对应 ref_docs/fragment_history.xml 把 btnResume / posterRecycler
            // 互相 nextFocus 指好的做法），避免 2D 焦点搜索在行边角落到空处。
            val card = HeroPosterCard(parent.context).apply { nextFocusUpId = R.id.btnResume }
            return Holder(card)
        }

        override fun getItemCount(): Int = HERO_HISTORY_ITEMS.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = HERO_HISTORY_ITEMS[position]
            holder.card.title = item.title
            holder.card.imageUrl = item.poster
            // adapter_history_poster.xml 的 siteTag（L50-62）→ 全局卡片的左上标签。
            holder.card.topStartTag = item.site
            // 原版历史卡没有备注节点，这里保持隐藏。
            holder.card.bottomStartTag = ""
            holder.card.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) return@setOnFocusChangeListener
                val index = holder.bindingAdapterPosition
                if (index != RecyclerView.NO_POSITION) applyHeroInfo(index)
            }
            holder.card.setOnClickListener { openDetail(item) }
        }

        inner class Holder(val card: HeroPosterCard) : RecyclerView.ViewHolder(card)
    }

    /** 与 f23 的 `setHorizontalSpacing` 等价：只在卡片之间留间距，行尾不留多余空隙。 */
    private class SpacingDecoration(private val spacing: Int) : RecyclerView.ItemDecoration() {

        override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
            val position = parent.getChildAdapterPosition(view)
            if (position == RecyclerView.NO_POSITION) return
            val last = (parent.adapter?.itemCount ?: 0) - 1
            if (position != last) outRect.right = spacing
        }
    }

    companion object {

        /** 卡片间距：与首页海报行 `POSTER_GAP = 12.dp` 同一个值。 */
        private const val POSTER_GAP_DP = 12

        /** 进度条满值：与 `activity_hero_history.xml` 里 `android:max="1000"` 一致。 */
        private const val PROGRESS_MAX = 1000

        private const val FOCUS_RETRY_COUNT = 8
        private const val FOCUS_RETRY_DELAY_MS = 32L

        fun start(context: Context) {
            context.startActivity(Intent(context, HeroHistoryActivity::class.java))
        }
    }
}

/**
 * Hero 页面遮罩基类：把首页 Compose 的 `Brush.horizontalGradient / verticalGradient`
 * 原样搬到 View 侧。`GradientDrawable` 最多只支持 3 个色标，而首页纵向遮罩有 4 个，
 * 所以这里直接用 [LinearGradient] + [Paint] 自绘，色标数量、位置、透明度与 Compose 完全一致。
 */
internal abstract class HeroMaskView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        // 纯自绘 View，必须显式允许 onDraw。
        setWillNotDraw(false)
    }

    /** 由子类给出方向与色标；尺寸变化后重建（等比拉伸到整屏）。 */
    protected abstract fun createShader(width: Float, height: Float): Shader

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        paint.shader = createShader(w.toFloat(), h.toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }
}

/**
 * 横向遮罩（首页 `HeroMaskLeft`，XML `maskLeft` / `bg_hero_mask_h`）：
 * 左侧 78% 黑 → 52% 处 16% 黑 → 右侧完全透明，保证左侧剧名/简介在浅色背景上仍可读。
 */
internal class HeroMaskLeftView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : HeroMaskView(context, attrs, defStyleAttr) {

    override fun createShader(width: Float, height: Float): Shader = LinearGradient(
        0f, 0f, width, 0f,
        intArrayOf(0xC7000000.toInt(), 0x29000000, 0x00000000),
        floatArrayOf(0f, 0.52f, 1f),
        Shader.TileMode.CLAMP,
    )
}

/**
 * 纵向遮罩（首页 `HeroMaskBottom`，XML `maskBottom` / `bg_hero_mask_v`）：
 * 顶部 86% 黑 → 26% 处 30% → 58% 处 62% → 底部 97%，四个色标一个不少。
 */
internal class HeroMaskBottomView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : HeroMaskView(context, attrs, defStyleAttr) {

    override fun createShader(width: Float, height: Float): Shader = LinearGradient(
        0f, 0f, 0f, height,
        intArrayOf(0xDB000000.toInt(), 0x4D000000, 0x9E000000.toInt(), 0xF7000000.toInt()),
        floatArrayOf(0f, 0.26f, 0.58f, 1f),
        Shader.TileMode.CLAMP,
    )
}
