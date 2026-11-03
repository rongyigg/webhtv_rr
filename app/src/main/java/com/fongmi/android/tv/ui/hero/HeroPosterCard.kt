// 【全局 UI 铁律】历史页、搜索页、换源弹窗必须复用此组件。尺寸 120x180，四角 8dp 圆角，严禁 AI 私自发明新尺寸！
package com.fongmi.android.tv.ui.hero

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.fongmi.android.tv.R
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.ShapeAppearanceModel
import kotlin.math.roundToInt

/**
 * 全局唯一海报卡片组件（View 体系，可直接放进 RecyclerView，无需 ComposeView 与 ViewTree owner）。
 *
 * 尺寸与本文件内的常量**全部写死**，外部既不能改宽度、也不能改高度、更不能改圆角：
 *  - 整卡 [CARD_WIDTH_DP] × [CARD_HEIGHT_DP] = 120dp × 214dp（[POSTER_HEIGHT_DP] 180 + [TITLE_HEIGHT_DP] 34）；
 *  - 四角 [CORNER_DP] = 8dp 全部圆角，**全部用原生 Drawable / Material 组件实现，不用 canvas.clipPath**：
 *    1. 整卡背景 = `GradientDrawable` + `cornerRadius = 8dp`（四角一次性圆，见 [init]）；
 *    2. 海报 = Material `ShapeableImageView`，**直接套原版 style `@style/Vod.Grid.Large`**
 *       （`res/values/styles.xml` L12-14：`cornerSize = 8dp`，父样式 `Vod.Grid` / `Vod` 提供
 *       `cornerFamily = rounded` → 四角 8dp），等价于 `adapter_history_poster.xml` L27 的
 *       `app:shapeAppearanceOverlay="@style/Vod.Grid.Large"`，见 [image]。
 *    电视端硬件加速会忽略部分画布裁剪（`canvas.clipPath` / `clipToOutline`），
 *    所以这里改走 Drawable 与 Material 的渲染路径，任何 TV 盒子都生效；
 *  - 焦点描边 [FOCUS_STROKE_DP] = 2dp、颜色 [FOCUS_COLOR] = #FFFF0000
 *    （**与首页 `POSTER_FOCUS_BORDER = 2.dp` + `HeroAccentColor = Color(0xFFFF0000)` 完全一致**），
 *    圆角同为 8dp，**只画在 120×180 的海报区**，绝不包住下方剧名，见 [drawFocusRing]。
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
    private val posterWidthPx: Int = dp(CARD_WIDTH_DP)
    private val posterHeightPx: Int = dp(POSTER_HEIGHT_DP)
    private val cornerPx: Float = dp(CORNER_DP).toFloat()
    // dp() 返回 Int（画布坐标取整），而描边宽度需要 Float，必须显式转换，
    // 否则 Kotlin 会推断成 Int 并在 `strokeWidth = focusStrokePx` 处报类型不匹配。
    private val focusStrokePx: Float = dp(FOCUS_STROKE_DP).toFloat()

    /**
     * 卡片背景：`GradientDrawable` + 8dp `cornerRadius`，**四角一次性圆**。
     * 这是整卡圆角的唯一来源，不依赖 `canvas.clipPath` / `clipToOutline` / `outlineProvider`，
     * 电视端硬件加速也照常绘制（纯原生 Drawable 渲染）。
     */
    private val cardBackground: GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(CORNER_DP).toFloat()
        setColor(ContextCompat.getColor(context, R.color.black_20))
    }

    private val focusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = focusStrokePx
        color = FOCUS_COLOR
    }
    private val focusRect = RectF()

    /**
     * 海报：Material `ShapeableImageView`，**圆角由图片自己绘制**（不靠父容器裁剪）。
     *
     * 1:1 照抄原版 `ref_docs/adapter_history_poster.xml` L21-27：
     * ```
     * <com.google.android.material.imageview.ShapeableImageView
     *     android:layout_width="120dp" android:layout_height="180dp"
     *     android:scaleType="centerCrop"
     *     app:shapeAppearanceOverlay="@style/Vod.Grid.Large" />   <!-- 四角 8dp -->
     * ```
     * 代码里没有 `app:` 命名空间，等价写法是把**同一个 style 资源**交给
     * [ShapeAppearanceModel.builder]（不手写 8dp，避免和样式表漂移）：
     * `R.style.Vod_Grid_Large` → `cornerSize = 8dp` + `cornerFamily = rounded` → 四角全圆。
     *
     * 尺寸由父容器 [posterFrame]（120×180）与 [init] 里显式的 LayoutParams 固定；
     * 自身不设 background，圆角外的像素露出卡片 `black_20`（与剧名底板同色）。
     */
    private val image: ShapeableImageView = ShapeableImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setImageResource(R.drawable.shape_source_poster_placeholder)
        // = 原版 app:shapeAppearanceOverlay="@style/Vod.Grid.Large"（四角 8dp，不是只圆上两角）。
        shapeAppearanceModel = ShapeAppearanceModel.builder(
            context,
            R.style.Vod_Grid_Large,
            0,
        ).build()
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

    /**
     * 海报区容器：120 × 180，**普通 FrameLayout，不做任何裁剪**。
     *
     * 圆角由里面的 [image]（`ShapeableImageView`）自己绘制，标签也放在这一层；
     * 卡片整体的四个圆角由 [cardBackground]（`GradientDrawable`）负责。
     * 这里不设置背景、不设置 clip，避免再出现「露出一块底色」或「裁剪不生效」的问题。
     */
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

        // 整卡圆角：只用 GradientDrawable 的 cornerRadius，四条边一次圆完。
        // 不写 clipToOutline / outlineProvider —— 电视端可能忽略，且 outline 会被缓存成空。
        background = cardBackground

        // 海报区：120 × 180，铺满卡片宽度。
        addView(
            posterFrame,
            LayoutParams(LayoutParams.MATCH_PARENT, posterHeightPx).apply { gravity = Gravity.TOP },
        )
        // 图片宽高就是 120×180（照抄原版 adapter_history_poster.xml L21-24 的显式宽高），
        // 不写 MATCH_PARENT：ShapeableImageView 的圆角按自身尺寸裁剪，尺寸必须和原版一致。
        posterFrame.addView(image, LayoutParams(posterWidthPx, posterHeightPx))
        posterFrame.addView(placeholder, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER })
        // 左上角标签：13sp，maxWidth 110dp < 海报 120dp（在属性初始化里设置），永远不越过海报右边界。
        // 贴左上不偏移：`shape_vod_site` 自身左上 8dp 圆角与海报左上圆角重合，不会出现直角挡圆角。
        posterFrame.addView(
            topTag,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.START or Gravity.TOP
            },
        )
        // 左下角标签：`shape_vod_remark` 是**直角**背景（原版就有），必须离底边 8dp，
        // 否则会把海报左下的 8dp 圆角整个盖成直角，视觉上又变回「一直是直角」。
        posterFrame.addView(
            bottomTag,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.START or Gravity.BOTTOM
                bottomMargin = dp(CORNER_DP)
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

    /**
     * **不再做任何画布裁剪**：电视端硬件加速会忽略 `canvas.clipPath` / `clipToOutline`，
     * 那是前几版「一直是直角」的根因。圆角改由 [cardBackground]（`GradientDrawable.cornerRadius`）
     * 与 [image]（`ShapeableImageView`）自行绘制，走原生 Drawable / Material 渲染路径，任何盒子都生效。
     *
     * 这里只负责在内容画完之后补上焦点红框。
     */
    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        drawFocusRing(canvas)
    }

    /** 焦点红框：8dp 圆角、2dp 纯红（#FFFF0000，同首页），只包 120×180 海报区，绝不包剧名。 */
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

        /** 焦点描边宽度：**2dp**，与首页 `POSTER_FOCUS_BORDER = 2.dp` 一致；圆角同为 [CORNER_DP]。 */
        const val FOCUS_STROKE_DP = 2f

        /**
         * 焦点描边颜色：**#FFFF0000**，抄首页 `HeroAccentColor = Color(0xFFFF0000)`
         * （`HeroHomeActivity.kt` L1135），不再使用 `@color/red`（#E50914）。
         */
        private val FOCUS_COLOR = 0xFFFF0000.toInt()

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
