// 【全局 UI 铁律】历史页、搜索页、换源弹窗必须复用此组件。尺寸 120x180，四角 8dp 圆角，严禁 AI 私自发明新尺寸！
package com.fongmi.android.tv.ui.hero

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
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
 * 对外接口只有四个内容字段：[title]、[imageUrl]、[topStartTag]、[bottomStartTag]，
 * 外加历史页的两个可选开关：[siteTag]、[tmdbTag]，以及编辑模式的 [isEditMode]、[onDeleteClick]。
 * 尺寸 / 圆角 / 描边没有任何 setter，构造后不可改。
 */
class HeroPosterCard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    siteTag: String? = null,
    tmdbTag: Boolean = false,
    isEditMode: Boolean = false,
    onDeleteClick: (() -> Unit)? = null,
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

    /**
     * 历史页站点标签（`ref_docs/adapter_history_poster.xml` L44-62 的 `siteGradient` + `siteTag`）。
     *
     * null / 空串 → 渐变与文字一起 GONE（同 `s11.java` L90-98 `TextUtils.isEmpty(siteName)` 分支）。
     * 与 [topStartTag] 互不影响：首页/搜索/换源继续用 [topStartTag]（13sp + `shape_vod_site`），
     * 历史页只用本字段（10sp + 96×96 渐变），两者不会同时显示。
     */
    var siteTag: String? = siteTag
        set(value) {
            field = value
            applySiteTag()
        }

    /**
     * 历史页右上角 TMDB 角标（`adapter_history_poster.xml` L28-43 `tmdbTag`）。
     *
     * 可见性规则由调用方给出（原版是 `s11.b(history)`，见 [HeroHistoryCardTags.isTmdbTagVisible]），
     * 组件只负责画：5sp 粗体白字「TMDB」+ `bg_tmdb_tag`（#99000000、3dp 圆角）。
     */
    var tmdbTag: Boolean = tmdbTag
        set(value) {
            field = value
            applyTmdbTag()
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
     * 站点渐变层（`adapter_history_poster.xml` L44-49 `siteGradient`，96dp × 96dp）。
     *
     * 1:1 照抄 `s11.java` L99-101：
     * ```
     * new GradientDrawable(Orientation.TL_BR, new int[]{dl3.a(), 0, 0});
     * setCornerRadii(new float[]{r, r, 0, 0, 0, 0, 0, 0});   // r = dp(8)
     * ```
     * 只有左上角 8dp 圆角，和海报左上圆角重合。
     * **待确认 #1**：[SITE_GRADIENT_START] 是 `dl3.a()` 的占位值（ref_docs 未给出该色值）。
     */
    private val siteGradient: View = View(context).apply {
        background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(SITE_GRADIENT_START, Color.TRANSPARENT, Color.TRANSPARENT),
        ).apply {
            val radius = dp(CORNER_DP).toFloat()
            cornerRadii = floatArrayOf(radius, radius, 0f, 0f, 0f, 0f, 0f, 0f)
        }
        isFocusable = false
        visibility = View.GONE
    }

    /** 站点标签文字（L50-62）：10sp 粗体白、margin 4dp、阴影 #000000 r6 dx0 dy0。 */
    private val siteTagView: TextView = TextView(context).apply {
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, SITE_TAG_SP)
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(ContextCompat.getColor(context, R.color.white))
        gravity = Gravity.START or Gravity.TOP
        setShadowLayer(
            SITE_TAG_SHADOW_RADIUS,
            0f,
            0f,
            ContextCompat.getColor(context, R.color.black),
        )
        maxLines = 1
        isFocusable = false
        visibility = View.GONE
    }

    /** TMDB 角标（L28-43）：5sp 粗体白「TMDB」+ `bg_tmdb_tag`（#99000000 / 3dp 圆角）。 */
    private val tmdbTagView: TextView = TextView(context).apply {
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, TMDB_TAG_SP)
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(ContextCompat.getColor(context, R.color.white))
        text = TMDB_TAG_TEXT
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(TMDB_TAG_CORNER_DP).toFloat()
            setColor(TMDB_TAG_BACKGROUND)
        }
        // L42-43 paddingStart/End 3dp，L35 paddingTop 0dp，L36 paddingBottom 1dp。
        setPadding(dp(TMDB_TAG_PADDING_H_DP), 0, dp(TMDB_TAG_PADDING_H_DP), dp(TMDB_TAG_PADDING_BOTTOM_DP))
        isFocusable = false
        visibility = View.GONE
    }

    /**
     * 编辑模式遮罩（`ref_docs/adapter_history_poster.xml` L64-76 的 `delete` 层）。
     *
     * 原版是 `ShapeableImageView` + `android:background="@color/black_50"` + `ic_vod_delete`
     * + `scaleType="center"`，四角 `Vod.Grid.Large`（8dp）。这里用 `GradientDrawable` 画 8dp 圆角底
     * （与 [cardBackground] 同一条渲染路径，电视端硬件加速也生效），图标是 56dp 的白色垃圾桶。
     */
    private val deleteOverlay: ImageView = ImageView(context).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(CORNER_DP).toFloat()
            setColor(ContextCompat.getColor(context, R.color.black_50))
        }
        setImageResource(R.drawable.ic_vod_delete)
        scaleType = ImageView.ScaleType.CENTER
        // 不能抢焦点：编辑态必须让卡片本身继续持有焦点，确定键才能落到删除分支。
        isFocusable = false
        isClickable = true
        visibility = View.GONE
    }

    /**
     * 编辑模式：`true` 时海报中心出现「半透明黑底 + 白色垃圾桶」遮罩。
     *
     * 原版是适配器级标志（`s11.java` L27 `boolean i` + `o11.m0()` L383-395 `notifyItemRangeChanged(0, size)`），
     * 即编辑态下**所有**卡片一起显示遮罩，不是只有被长按的那一张。
     */
    var isEditMode: Boolean = isEditMode
        set(value) {
            field = value
            applyEditMode()
        }

    /**
     * 遮罩点击回调（触屏用）。遥控器确定键的长按 / 短按由调用方的 `setOnKeyListener` 处理，
     * 组件本身不接管按键，避免把「进入编辑模式」和「删除」两个语义耦合进卡片。
     */
    var onDeleteClick: (() -> Unit)? = onDeleteClick

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
        // 历史页站点渐变 + 站点文字：96×96 只圆左上角，贴在图片之上、其它标签之下。
        posterFrame.addView(
            siteGradient,
            LayoutParams(dp(SITE_GRADIENT_SIZE_DP), dp(SITE_GRADIENT_SIZE_DP)).apply {
                gravity = Gravity.START or Gravity.TOP
            },
        )
        posterFrame.addView(
            siteTagView,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.START or Gravity.TOP
                setMargins(dp(SITE_TAG_MARGIN_DP), dp(SITE_TAG_MARGIN_DP), 0, 0)
            },
        )
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
        // TMDB 角标：右上角，margin 4dp（L40），最后添加保证盖在渐变与图片之上。
        posterFrame.addView(
            tmdbTagView,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.END or Gravity.TOP
                setMargins(0, dp(TMDB_TAG_MARGIN_DP), dp(TMDB_TAG_MARGIN_DP), 0)
            },
        )
        // 编辑态遮罩：铺满 120×180 海报区，最后添加 → 盖住图片与所有角标
        // （`adapter_history_poster.xml` L64-76 的 `delete` 层同样在 imageWrapper 之后）。
        posterFrame.addView(
            deleteOverlay,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )

        // 剧名区：34dp，紧贴海报下方，高度写死，不吃 weight、不吃 wrap_content。
        addView(
            titleView,
            LayoutParams(LayoutParams.MATCH_PARENT, dp(TITLE_HEIGHT_DP)).apply {
                topMargin = posterHeightPx
                gravity = Gravity.TOP
            },
        )

        // 构造参数（siteTag / tmdbTag / isEditMode）不走 setter，这里补一次初始状态。
        applySiteTag()
        applyTmdbTag()
        applyEditMode()
    }

    /** 编辑模式开关：只切遮罩可见性，尺寸 / 圆角 / 焦点框一律不动。 */
    private fun applyEditMode() {
        deleteOverlay.visibility = if (isEditMode) View.VISIBLE else View.GONE
        // 触屏点遮罩 = 删除（遥控器确定键由调用方 OnKeyListener 处理）。
        deleteOverlay.setOnClickListener { onDeleteClick?.invoke() }
    }

    /** 站点标签与渐变同进同出（`s11.java` L91-98：站点名为空时两者都隐藏）。 */
    private fun applySiteTag() {
        val visible = !siteTag.isNullOrEmpty()
        siteTagView.text = siteTag.orEmpty()
        siteTagView.visibility = if (visible) View.VISIBLE else View.GONE
        siteGradient.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun applyTmdbTag() {
        tmdbTagView.visibility = if (tmdbTag) View.VISIBLE else View.GONE
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

        // ------------------------------------------------------------------
        // 历史页站点标签（`ref_docs/adapter_history_poster.xml` L44-62 + `s11.java` L99-101）
        // ------------------------------------------------------------------

        /** 站点标签字号：10sp（`adapter_history_poster.xml` L51 `android:textSize="10sp"`）。 */
        private const val SITE_TAG_SP = 10f

        /** 站点标签四边外边距：4dp（L58 `android:layout_margin="4dp"`）。 */
        private const val SITE_TAG_MARGIN_DP = 4

        /** 站点标签阴影半径：6，dx/dy 均为 0（L59-62，阴影色 `@color/black`）。 */
        private const val SITE_TAG_SHADOW_RADIUS = 6f

        /** 站点渐变尺寸：96dp × 96dp（L48-49 `siteGradient`）。 */
        private const val SITE_GRADIENT_SIZE_DP = 96

        /**
         * **待确认 #1**：站点渐变起始色 = 原版 `dl3.a()`（`s11.java` L99）。
         * ref_docs 只给出调用点、没有给出色值，这里先取工程既有 `@color/hero_accent`（#FFFF0000，
         * `res/values/colors.xml` L64）作为占位，确认后只改这一行。
         */
        private val SITE_GRADIENT_START = 0xFFFF0000.toInt()

        // ------------------------------------------------------------------
        // 历史页 TMDB 角标（`adapter_history_poster.xml` L28-43 + `bg_tmdb_tag.xml`）
        // ------------------------------------------------------------------

        /** TMDB 角标字号：5sp（L29 `android:textSize="5sp"`）。 */
        private const val TMDB_TAG_SP = 5f

        /** 角标圆角：3dp（`ref_docs/bg_tmdb_tag.xml` `corners android:radius="3dp"`）。 */
        private const val TMDB_TAG_CORNER_DP = 3

        /** 角标底色：#99000000（`ref_docs/bg_tmdb_tag.xml` `solid android:color="#99000000"`）。 */
        private val TMDB_TAG_BACKGROUND = 0x99000000.toInt()

        /** 角标左右内边距：3dp（L42-43 `paddingStart/paddingEnd`）。 */
        private const val TMDB_TAG_PADDING_H_DP = 3

        /** 角标底部内边距：1dp（L36 `paddingBottom`），顶部 0dp（L35）。 */
        private const val TMDB_TAG_PADDING_BOTTOM_DP = 1

        /** 角标外边距：4dp（L40 `android:layout_margin="4dp"`）。 */
        private const val TMDB_TAG_MARGIN_DP = 4

        /**
         * 角标文案（L41 `android:text="@string/home_tmdb_tag"`）。
         * 本工程未收录 `home_tmdb_tag` 字符串资源（`res/values/strings.xml` 无此条目），
         * 因此直接使用资源里那个字面量「TMDB」，不新增 strings.xml 条目。
         */
        private const val TMDB_TAG_TEXT = "TMDB"

        private const val TITLE_SP = 16f
        private const val TOP_TAG_SP = 13f
        private const val BOTTOM_TAG_SP = 14f
        private const val PLACEHOLDER_SP = 36f
    }
}
