// 【全局 UI 铁律】历史页、搜索页、换源弹窗必须复用此组件。尺寸 120x180，四角 8dp 圆角，严禁 AI 私自发明新尺寸！
package com.fongmi.android.tv.ui.hero

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.fongmi.android.tv.R
import kotlin.math.roundToInt

/**
 * 全局唯一海报卡片组件（View 体系，可直接放进 RecyclerView，无需 ComposeView 与 ViewTree owner）。
 *
 * 尺寸与本文件内的常量**全部写死**，外部既不能改宽度、也不能改高度、更不能改圆角：
 *  - 整卡 [CARD_WIDTH_DP] × [CARD_HEIGHT_DP] = 120dp × 214dp（[POSTER_HEIGHT_DP] 180 + [TITLE_HEIGHT_DP] 34）；
 *  - 四角 [CORNER_DP] = 8dp 全部圆角。实现顺序与 Compose 完全对齐：
 *    `Modifier.size(...).graphicsLayer { shape = RoundedCornerShape(8.dp); clip = true }.background(...)`
 *    —— **clip 必须在最外层**，View 侧就是 [draw] 里先 `clipPath(8dp 圆角矩形)` 再 `super.draw()`，
 *    背景、海报、标签、剧名全部落在裁剪内（见 [draw] / [rebuildClipPath]）；
 *    `clipToOutline` 只作为 GPU 路径的冗余保险，不作为唯一手段；
 *  - 焦点描边 [FOCUS_STROKE_DP] = 1.5dp 红色（`@color/red` = #E50914），圆角同为 8dp，
 *    **只画在 120×180 的海报区**，绝不包住下方剧名，见 [drawFocusRing]。
 *
 * 对外接口只有四个内容字段：[title]、[imageUrl]、[topStartTag]、[bottomStartTag]。
 * 尺寸 / 圆角 / 描边没有任何 setter，构造后不可改。
 */
class HeroPosterCard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    /** 剧名：16sp 白色、单行省略、居中、上下 6dp 内边距，落在 34dp 的底部文字区。 */
    var title: String = ""
        set(value) {
            field = value
            titleView.text = value
            placeholder.text = value.take(1)
            emptyTitle = value.isEmpty()
            bindPoster()
        }

    /** 海报地址。为空时走渐变占位 + 剧名首字，绝不显示黑块。 */
    var imageUrl: String = ""
        set(value) {
            field = value
            bindPoster()
        }

    /** 左上角标签：「MM-dd | 来源」，13sp + `shape_vod_site`，单行省略。 */
    var topStartTag: String = ""
        set(value) {
            field = value
            topTag.text = value
            topTag.visibility = if (value.isEmpty()) View.GONE else View.VISIBLE
        }

    /** 左下角标签：「更新至XX集」，14sp + `shape_vod_remark`，单行省略。 */
    var bottomStartTag: String = ""
        set(value) {
            field = value
            bottomTag.text = value
            bottomTag.visibility = if (value.isEmpty()) View.GONE else View.VISIBLE
        }

    private val density: Float = resources.displayMetrics.density

    private val cardWidthPx: Int = dp(CARD_WIDTH_DP)
    private val cardHeightPx: Int = dp(CARD_HEIGHT_DP)
    private val posterHeightPx: Int = dp(POSTER_HEIGHT_DP)
    private val cornerPx: Float = dp(CORNER_DP).toFloat()
    // dp() 返回 Int（画布坐标取整），而描边宽度需要 Float，必须显式转换，
    // 否则 Kotlin 会推断成 Int 并在 `strokeWidth = focusStrokePx` 处报类型不匹配。
    private val focusStrokePx: Float = dp(FOCUS_STROKE_DP).toFloat()

    /**
     * 卡片级唯一裁剪路径：整卡 8dp 圆角矩形。
     *
     * 这就是 Compose 里
     * ```
     * Modifier.size(120.dp, 214.dp)
     *     .graphicsLayer { shape = RoundedCornerShape(8.dp); clip = true }
     * ```
     * 的 View 等价物：**clip 在最外层**，先裁路径再画背景 / 海报 / 标签 / 剧名，
     * 所以任何子 View 都逃不出 8dp 圆角，四角必圆（见 [draw]）。
     */
    private val clipPath = Path()

    private val focusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = focusStrokePx
        color = ContextCompat.getColor(context, R.color.red)
    }
    private val focusRect = RectF()

    private val image: ImageView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setImageResource(R.drawable.shape_source_poster_placeholder)
    }

    private val placeholder: TextView = TextView(context).apply {
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, PLACEHOLDER_SP)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(0xF2FFFFFF.toInt())
        gravity = Gravity.CENTER
        includeFontPadding = false
        isFocusable = false
        visibility = View.GONE
    }

    private val topTag: TextView = TextView(context).apply {
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, TOP_TAG_SP)
        setTextColor(ContextCompat.getColor(context, R.color.white))
        ellipsize = TextUtils.TruncateAt.END
        maxLines = 1
        setBackgroundResource(R.drawable.shape_vod_site)
        // maxWidth 110dp < 海报 120dp：标签永远在左上角内，不会越出海报右边界。
        setMaxWidth(dp(TOP_TAG_MAX_WIDTH_DP))
        isFocusable = false
        visibility = View.GONE
    }

    private val bottomTag: TextView = TextView(context).apply {
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, BOTTOM_TAG_SP)
        setTextColor(ContextCompat.getColor(context, R.color.white))
        ellipsize = TextUtils.TruncateAt.END
        maxLines = 1
        setBackgroundResource(R.drawable.shape_vod_remark)
        isFocusable = false
        visibility = View.GONE
    }

    private val posterFrame: FrameLayout = FrameLayout(context)

    private val titleView: TextView = TextView(context).apply {
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, TITLE_SP)
        setTextColor(ContextCompat.getColor(context, R.color.white))
        gravity = Gravity.CENTER
        ellipsize = TextUtils.TruncateAt.END
        maxLines = 1
        val pad = dp(TITLE_PADDING_DP)
        setPadding(pad, 0, pad, 0)
        isFocusable = false
    }

    private var emptyTitle = true

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        // `setDefaultFocusHighlightEnabled` 是 API 26 才有的方法，minSdk 24 必须判版本。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) defaultFocusHighlightEnabled = false
        stateListAnimator = null
        clipChildren = true
        clipToPadding = true

        // 四角 8dp 全圆。GPU 路径用 outline（顺带保证阴影/裁剪一致），
        // 真正的裁剪由 [draw] 里的 clipPath 强制执行，不依赖 RenderNode 是否缓存了空 outline。
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                // 首帧 outline 可能在 width/height 还是 0 时被查询，
                // 这里用固定尺寸兜底，避免缓存下 0×0 的空 outline（表现为「完全没圆角」）。
                val w = if (view.width > 0) view.width else cardWidthPx
                val h = if (view.height > 0) view.height else cardHeightPx
                outline.setRoundRect(0, 0, w, h, cornerPx)
            }
        }
        setBackgroundColor(ContextCompat.getColor(context, R.color.black_20))

        // 海报区：120 × 180，铺满卡片宽度。
        addView(
            posterFrame,
            LayoutParams(LayoutParams.MATCH_PARENT, posterHeightPx).apply { gravity = Gravity.TOP },
        )
        posterFrame.addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        posterFrame.addView(placeholder, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER })
        // 左上角标签：13sp，maxWidth 110dp < 海报 120dp（在属性初始化里设置），永远不越过海报右边界。
        posterFrame.addView(
            topTag,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.START or Gravity.TOP
            },
        )
        posterFrame.addView(
            bottomTag,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.START or Gravity.BOTTOM
            },
        )

        // 剧名区：34dp，紧贴海报下方，高度写死，不吃 weight、不吃 wrap_content。
        addView(
            titleView,
            LayoutParams(LayoutParams.MATCH_PARENT, dp(TITLE_HEIGHT_DP)).apply {
                topMargin = posterHeightPx
                gravity = Gravity.TOP
            },
        )
    }

    /** 尺寸强制 120×214：外部传入任何 MeasureSpec 都不生效（铁律）。 */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(cardWidthPx, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(cardHeightPx, MeasureSpec.EXACTLY),
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildClipPath(w, h)
        // 尺寸变化后强制重算 outline，别让上一次的空 / 旧尺寸 outline 留在 RenderNode 里。
        invalidateOutline()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        rebuildClipPath(width, height)
    }

    /**
     * **卡片级最外层裁剪**（本次修复的核心）。
     *
     * 等价 Compose：
     * ```
     * Modifier
     *     .size(120.dp, 214.dp)
     *     .graphicsLayer { shape = RoundedCornerShape(8.dp); clip = true }
     *     .background(...)   // 背景在 clip 之内
     * ```
     * 顺序要点：**先 clip，再 super.draw()**。
     * `super.draw()` 会依次画背景 → 子 View（海报、左上 / 左下标签）→ 剧名文字，
     * 全部落在 8dp 圆角路径内，所以四个角一定是圆的，不会被任何子 View 的直角顶破。
     * 焦点红框在 restore 之后画，避免被自身裁剪削掉描边。
     */
    override fun draw(canvas: Canvas) {
        if (clipPath.isEmpty) rebuildClipPath(width, height)
        val save = canvas.save()
        canvas.clipPath(clipPath)
        super.draw(canvas)
        canvas.restore()
        drawFocusRing(canvas)
    }

    /** 重建 8dp 圆角矩形路径：四个角用同一个 radius，不是只圆上方两角。 */
    private fun rebuildClipPath(w: Int, h: Int) {
        clipPath.reset()
        if (w <= 0 || h <= 0) return
        clipPath.addRoundRect(0f, 0f, w.toFloat(), h.toFloat(), cornerPx, cornerPx, Path.Direction.CW)
    }

    /** 焦点红框：8dp 圆角、1.5dp 红描边，只包 120×180 海报区，绝不包剧名。 */
    private fun drawFocusRing(canvas: Canvas) {
        if (!isFocused) return
        val inset = focusStrokePx / 2f
        focusRect.set(inset, inset, width - inset, posterHeightPx - inset)
        canvas.drawRoundRect(focusRect, cornerPx, cornerPx, focusPaint)
    }

    /** 背景是纯色，不会因焦点自动重绘，这里显式重绘一次让红框即时出现 / 消失。 */
    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }

    private fun bindPoster() {
        if (imageUrl.isBlank()) {
            image.setImageResource(R.drawable.shape_source_poster_placeholder)
        } else {
            // TODO(数据层对接)：接入真实海报后由这里统一加载；占位首字仅在无图时显示。
            Glide.with(context.applicationContext).load(imageUrl).centerCrop().into(image)
        }
        placeholder.visibility = if (imageUrl.isBlank() && !emptyTitle) View.VISIBLE else View.GONE
    }

    private fun dp(value: Int): Int = (value * density).roundToInt()

    private fun dp(value: Float): Int = (value * density).roundToInt()

    companion object {

        /** 整卡宽度，写死：120dp。 */
        const val CARD_WIDTH_DP = 120

        /** 海报高度，写死：180dp（宽 × 1.5）。 */
        const val POSTER_HEIGHT_DP = 180

        /** 底部剧名区高度，写死：34dp（`ref_docs/ks2.java` L108 的 `dp(34)`）。 */
        const val TITLE_HEIGHT_DP = 34

        /** 整卡高度 = 海报 + 剧名区 = 214dp。 */
        const val CARD_HEIGHT_DP = POSTER_HEIGHT_DP + TITLE_HEIGHT_DP

        /** 四角圆角，写死：8dp（图片与底板一起被裁，四角全圆）。 */
        const val CORNER_DP = 8

        /** 焦点描边宽度：1.5dp 红色，圆角同为 [CORNER_DP]。 */
        const val FOCUS_STROKE_DP = 1.5f

        /** 左上角标签最大宽度：110dp（< 海报 120dp，保证不越界）。 */
        const val TOP_TAG_MAX_WIDTH_DP = 110

        /** 剧名左右内边距：6dp（`ref_docs/adapter_search.xml` 的 `padding="6dp"`）。 */
        const val TITLE_PADDING_DP = 6

        private const val TITLE_SP = 16f
        private const val TOP_TAG_SP = 13f
        private const val BOTTOM_TAG_SP = 14f
        private const val PLACEHOLDER_SP = 36f
    }
}
