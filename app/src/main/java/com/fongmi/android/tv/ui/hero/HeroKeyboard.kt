package com.fongmi.android.tv.ui.hero

import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.fongmi.android.tv.R

/**
 * 虚拟键盘组件库（Compose 体系）。
 *
 * 本文件全部内容**从 `HeroSearchActivity.kt` 原样抽出**（只把 `private` 改成 `internal`，
 * 尺寸 / 颜色 / 焦点逻辑一个字节都没改），目的只有一个：
 * 搜索页（[com.fongmi.android.tv.ui.activity.HeroSearchActivity]）和
 * TMDB 手动匹配弹窗（[TmdbMatchDialog]）共用同一套键盘，不复制第二份。
 *
 * 结构与尺寸的出处（逆向 XML / 源码）：
 * - 键 **40dp × 40dp**：`adapter_keyboard_text.xml` L5-6、`adapter_keyboard_icon.xml` L7-8；
 * - 键间距 **8dp**：`CustomKeyboard.java` L25 `new SpaceItemDecoration(7, 8)`；
 * - 键盘容器 `wrap_content`：`activity_search.xml` L59-60 → 宽度由 7×40 + 6×8 = 328dp 自然撑出；
 * - 输入框行：mic 36dp + 12dp + 输入框 200dp + 12dp + 搜索按钮 40dp；
 * - 输入框：24sp、字距 0.02、2dp 红框、13dp 圆角、高 40dp（`activity_search.xml` L37/L53）；
 * - 42 格 = 第一行 `(占位, <, >, ⌫, 键盘, 房子, A)` + B-Z + 0-9，最右列是 A / H / O / V / 2 / 9。
 *   小房子是**可选**的：`hideHouseKey = true` 时索引 [HOUSE_KEY_INDEX] 变成不可聚焦空槽
 *   （TMDB 匹配弹窗用，那里没有换源侧边栏），格子宽度保留，列对齐不变。
 *
 * 焦点契约（与原搜索页完全一致）：
 * - 输入框 ↓ / 放大镜 ↓ → 键盘第一行；键盘内四向移动由 [FocusRequester] 精确控制；
 * - **键盘最右列按 → 不消费、返回 false**，交给 Compose 原生 2D 焦点搜索落到右侧控件；
 * - **键盘最后一行按 ↓ 返回 false**，由调用方决定落点（搜索页 → 站点 Tab，弹窗 → 结果卡）。
 */

// ---------------------------------------------------------------------------
// 键盘区：输入框行 + 6 行 × 7 列虚拟键盘
// ---------------------------------------------------------------------------

/**
 * 输入框行 + 虚拟键盘。
 *
 * 尺寸全部来自逆向数据，没有一条是估计的：
 * - 键：**40dp × 40dp**（`adapter_keyboard_text.xml` L5-6、`adapter_keyboard_icon.xml` L7-8）；
 * - 间距：**8dp**（`CustomKeyboard.java` L25 `new SpaceItemDecoration(7, 8)`；`SpaceItemDecoration`
 *   L22 用 `ResUtil.dp2px(8)` 换算，单位是 dp；L31-35 相邻两格的 right+left 恒等于 8dp，
 *   L35 行间距 `outRect.top = spacing` 同样是 8dp）；
 * - 容器：`wrap_content`（`activity_search.xml` L59-60），**原版没有固定宽度**，
 *   宽度是 7×40dp + 6×8dp = 328dp 算出来的；
 * - 与输入框行的间距：**8dp**（`activity_search.xml` L61 `android:layout_marginTop="8dp"`）。
 *
 * 尺寸：
 * - 宽高都由内容自然撑出：`wrapContentWidth()`，**不写死宽度、不 fillMaxHeight、不加滚动**；
 *   最宽的子项是 6 行键盘 7×40 + 6×8 = 328dp，输入框行是 36 + 12 + 200 + 12 + 40 = 300dp；
 * - 高度 = 输入行 40 + 8 + 6×40 + 5×8 = 328dp。
 *
 * 缩放：本函数内部一律写原版基准值（40dp / 8dp），**不感知缩放**。
 * 需要适配矮视口时由调用方在外面套一层 `LocalDensity`（搜索页就是这么做的），
 * 于是这里所有 dp 会整体等比变化，而键间距、键宽比例保持原版一致。
 */
@Composable
internal fun KeyboardSection(
    field: TextFieldValue,
    inputRequester: FocusRequester,
    searchRequester: FocusRequester,
    keyRequesters: List<FocusRequester>,
    onInputDown: () -> Unit,
    onSearch: () -> Unit,
    onSearchDown: () -> Unit,
    onSearchLeft: () -> Unit,
    onKeyClick: (HeroKey) -> Unit,
    onKeyMove: (Int, Key) -> Boolean,
    /**
     * 是否隐藏第一行的「小房子」键（索引 [HOUSE_KEY_INDEX]）。
     *
     * 默认 `false` = 搜索页原样（那里点小房子会打开换源侧边栏）。
     * TMDB 手动匹配弹窗没有侧边栏，小房子无行为，传 `true` 把它变成不可聚焦空槽；
     * 调用方需要在自己的 `onKeyMove` 里跳过 [HOUSE_KEY_INDEX]。
     */
    hideHouseKey: Boolean = false,
    /**
     * 输入框行下方的可选插槽（TMDB 手动匹配用来放联想词胶囊）。
     *
     * 默认 `null` → 搜索页完全不变；弹窗传入时插在输入框与键盘之间。插槽处在本函数的
     * 缩放子树内 —— 调用方看到的 dp/sp 都是缩放前的布局值，与键盘按键同一坐标系。
     */
    belowInput: (@Composable () -> Unit)? = null,
) {
    Column(
        // 宽度完全由内容撑开（最宽的是键盘 7×40 + 6×8 = 328dp），绝不写死、绝不拉伸。
        // 左边距由调用方的 Spacer 让出 —— 它必须在缩放子树外面，否则缩放后对不齐 Tab/海报。
        modifier = Modifier.wrapContentWidth(),
        verticalArrangement = Arrangement.spacedBy(KEY_SPACING),
    ) {
        // 输入框行：mic 36dp + 12dp + 输入框 200dp + 12dp + 搜索 40dp，整行高 40dp。
        // 输入框写死 200dp、**不使用 weight(1f)**：左列已是 wrapContentWidth，
        // 加权子项会把整行撑到父级最大宽度，左列就会被拉成整屏宽、按键显得粗大。
        Row(verticalAlignment = Alignment.CenterVertically) {
            HeroGlyphIcon(glyph = HeroGlyph.Mic, modifier = Modifier.size(MIC_SIZE))
            Spacer(modifier = Modifier.width(MIC_MARGIN_END))
            HeroSearchInput(
                requester = inputRequester,
                value = field,
                onMoveDown = onInputDown,
                modifier = Modifier.width(INPUT_WIDTH),
            )
            Spacer(modifier = Modifier.width(SEARCH_BUTTON_MARGIN_START))
            HeroSearchSubmit(
                requester = searchRequester,
                onConfirm = onSearch,
                onMoveDown = onSearchDown,
                onMoveLeft = onSearchLeft,
            )
        }

        // 输入框行下方的可选插槽。为空时（搜索页）不产生任何布局节点，间距也不会多出来。
        if (belowInput != null) {
            belowInput()
        }

        // 键盘：6 行 × 7 列 = 42 格，每格 40dp 写死，行/列间距 8dp（SpaceItemDecoration(7, 8)）。
        Column(verticalArrangement = Arrangement.spacedBy(KEY_SPACING)) {
            for (row in 0 until KEY_ROWS) {
                Row(horizontalArrangement = Arrangement.spacedBy(KEY_SPACING)) {
                    for (column in 0 until KEY_COLUMNS) {
                        val index = row * KEY_COLUMNS + column
                        when {
                            index == PLACEHOLDER_INDEX -> HeroKeyPlaceholder()
                            // 隐藏小房子时保留这一格的宽度：A 仍在最右列，B..H 与它上下对齐，
                            // 只是这一格不可聚焦、不画任何东西。
                            hideHouseKey && index == HOUSE_KEY_INDEX -> HeroKeyBlank()
                            else -> KeyboardKey(
                                key = HERO_KEYS[index],
                                requester = keyRequesters[index],
                                onMove = { key -> onKeyMove(index, key) },
                                onKey = { onKeyClick(HERO_KEYS[index]) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 单个按键：**严格 40 × 40dp**（`adapter_keyboard_text.xml` L10），4dp 圆角（`shape_keyboard_normal.xml`）、16sp 白字。
 *
 * 焦点反馈（TV 端核心）：
 * - 常态 `0x33FFFFFF`（半透明白）；**焦点 = 首页 Tab 同源的渐变红**（[HeroFocusBrush] 的两端色标）、文字纯白；
 * - 背景用 90ms 的 `animateFloatAsState` 把常态底色分别插值到渐变两端，遥控器连按时红底跟着焦点"滑"过去，不闪不跳；
 * - `collectIsFocusedAsState()` 是主信号，`onFocusChanged` 只做兜底（极端机型上交互源可能不派发），
 *   两者取或，绝不会出现「焦点到了但按键还是灰的」。
 * - 只挂一个焦点节点（`clickable` 自带 focusable），再叠 `focusable()` 会分裂焦点、红底会丢。
 */
@Composable
internal fun KeyboardKey(
    key: HeroKey,
    requester: FocusRequester,
    onMove: (Key) -> Boolean,
    onKey: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val isFocused by interaction.collectIsFocusedAsState()
    var focusFlag by remember { mutableStateOf(false) }
    val focused = isFocused || focusFlag
    // 红底跟随焦点：90ms 过渡，够快（TV 不能拖）又不会硬切闪屏。
    // 焦点色不是纯色：把常态底色分别插值到首页 Tab 渐变的两端（[HeroFocusBrush] 同源色标）。
    val focusProgress by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMillis = KEY_FOCUS_FADE_MS),
        label = "keyFocusBg",
    )
    val focusStart = colorResource(R.color.home_tab_focus_start)
    val focusEnd = colorResource(R.color.home_tab_focus_end)
    val bgBrush = Brush.horizontalGradient(
        0.0f to lerp(KEY_BG_NORMAL, focusStart, focusProgress),
        1.0f to lerp(KEY_BG_NORMAL, focusEnd, focusProgress),
    )

    Box(
        modifier = Modifier
            .size(KEY_SIZE)                       // 40dp 写死，任何区域都不会被拉伸
            .clip(KEY_SHAPE)                      // 4dp 圆角
            .background(bgBrush, KEY_SHAPE)       // 焦点 = 渐变红底
            .focusRequester(requester)
            .onFocusChanged { focusFlag = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    // 遥控器 OK / 回车：直接触发，不依赖 clickable 内部映射。
                    Key.DirectionCenter, Key.Enter -> {
                        onKey()
                        true
                    }

                    else -> onMove(event.key)
                }
            }
            // 单一焦点目标：clickable 自身提供 focusable + 点击 + FocusInteraction，
            // 再叠一个 focusable 会产生两个焦点节点，反而可能丢掉红底。
            .clickable(interactionSource = interaction, indication = null, onClick = onKey),
        contentAlignment = Alignment.Center,
    ) {
        when (key) {
            is HeroKey.Text -> Text(
                text = key.value,
                // 两种态都是纯白：红底白字、灰底白字，对比度都够。
                color = Color.White,
                fontSize = KEY_TEXT_SP.sp,
                maxLines = 1,
            )

            is HeroKey.Action -> HeroGlyphIcon(
                glyph = key.glyph,
                modifier = Modifier.size(KEY_ICON_SIZE),
            )

            else -> Unit
        }
    }
}

/** 第一行第 1 格占位（原版 `ic_keyboard_remote` 的 4 个方块）：不聚焦、无动作。 */
@Composable
internal fun HeroKeyPlaceholder() {
    Box(
        modifier = Modifier
            .size(KEY_SIZE)
            .clip(KEY_SHAPE)
            .background(KEY_BG_NORMAL, KEY_SHAPE),
        contentAlignment = Alignment.Center,
    ) {
        HeroGlyphIcon(glyph = HeroGlyph.Grid, modifier = Modifier.size(KEY_ICON_SIZE))
    }
}

/**
 * 隐藏某个键之后留下的空槽：与普通键同尺寸、**不画任何东西、不可聚焦**。
 *
 * 只用于 [KeyboardSection] 的 `hideHouseKey = true` 分支，目的是让第一行继续占满 7 列，
 * 后面的 A 仍然落在最右列、和 B..H 上下对齐。
 */
@Composable
internal fun HeroKeyBlank() {
    Box(modifier = Modifier.size(KEY_SIZE))
}

// ---------------------------------------------------------------------------
// 搜索输入框 + 放大镜按钮
// ---------------------------------------------------------------------------

/**
 * 只读搜索框（禁用系统输入法）。
 *
 * - 文字 24sp、字距 0.02（`activity_search.xml` L37/L53），hint 白（L39）；
 * - 焦点态 2dp 红框（`selector_tmdb_input.xml` 的 focused 态 + `topbar_search_page.png`）；
 * - 高度 40dp（= 键盘键高，和 mic / 放大镜横向一条线），圆角 13dp。
 */
@Composable
internal fun HeroSearchInput(
    requester: FocusRequester,
    value: TextFieldValue,
    onMoveDown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .height(INPUT_HEIGHT)                 // 高度锁定 40dp，不随字体/父级拉伸
            .clip(INPUT_SHAPE)
            .background(Color.Transparent, INPUT_SHAPE)
            .border(
                width = INPUT_STROKE,
                color = if (focused) ACCENT_RED else Color.Transparent,
                shape = INPUT_SHAPE,
            )
            .padding(horizontal = INPUT_PADDING_H),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = { /* readOnly：内容只由虚拟键盘写入 */ },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(requester)
                .onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        // 输入框 ↓ → 键盘第一行（L45 nextFocusDown=keyboard）。
                        Key.DirectionDown -> {
                            onMoveDown()
                            true
                        }

                        Key.DirectionUp -> true

                        else -> false
                    }
                },
            readOnly = true,
            singleLine = true,
            textStyle = TextStyle(
                color = Color.White,
                fontSize = INPUT_TEXT_SP.sp,
                letterSpacing = INPUT_LETTER_SPACING.em,
            ),
            cursorBrush = SolidColor(Color.White),
            interactionSource = interaction,
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.text.isEmpty()) {
                        Text(
                            text = stringResource(R.string.search_keyword),
                            color = Color.White,
                            fontSize = INPUT_TEXT_SP.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
            },
        )
    }
}

/** 放大镜按钮：40dp 圆（与输入框/键盘键同高），配色复用键盘键两态。 */
@Composable
internal fun HeroSearchSubmit(
    requester: FocusRequester,
    onConfirm: () -> Unit,
    onMoveDown: () -> Unit,
    onMoveLeft: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val isFocused by interaction.collectIsFocusedAsState()
    var focusFlag by remember { mutableStateOf(false) }
    val focused = isFocused || focusFlag

    Box(
        modifier = Modifier
            .size(SEARCH_BUTTON_SIZE)
            .clip(CircleShape)
            .background(if (focused) HeroFocusBrush else SolidColor(KEY_BG_NORMAL), CircleShape)
            .focusRequester(requester)
            .onFocusChanged { focusFlag = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionCenter, Key.Enter -> {
                        onConfirm()
                        true
                    }

                    Key.DirectionDown -> {
                        onMoveDown()
                        true
                    }

                    Key.DirectionLeft -> {
                        onMoveLeft()
                        true
                    }

                    else -> false
                }
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onConfirm),
        contentAlignment = Alignment.Center,
    ) {
        HeroGlyphIcon(glyph = HeroGlyph.Search, modifier = Modifier.size(KEY_ICON_SIZE))
    }
}

// ---------------------------------------------------------------------------
// 图标（main 源集没有 mic / search / keyboard 矢量，用 Canvas 画，不新增资源文件）
// ---------------------------------------------------------------------------

internal enum class HeroGlyph { Mic, Search, ArrowLeft, ArrowRight, Backspace, Keyboard, House, Grid }

/** 图标统一按 24dp 视觉基准取线宽 0.085 × 边长。 */
@Composable
internal fun HeroGlyphIcon(
    glyph: HeroGlyph,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
) {
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val stroke = Stroke(
            width = width * GLYPH_STROKE_RATIO,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        when (glyph) {
            HeroGlyph.Mic -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(width * 0.375f, height * 0.06f),
                    size = Size(width * 0.25f, height * 0.44f),
                    cornerRadius = CornerRadius(width * 0.125f, width * 0.125f),
                    style = stroke,
                )
                drawArc(
                    color = tint,
                    startAngle = 0f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(width * 0.22f, height * 0.28f),
                    size = Size(width * 0.56f, height * 0.42f),
                    style = stroke,
                )
                drawLine(
                    color = tint,
                    start = Offset(width * 0.5f, height * 0.70f),
                    end = Offset(width * 0.5f, height * 0.88f),
                    strokeWidth = stroke.width,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = tint,
                    start = Offset(width * 0.34f, height * 0.90f),
                    end = Offset(width * 0.66f, height * 0.90f),
                    strokeWidth = stroke.width,
                    cap = StrokeCap.Round,
                )
            }

            HeroGlyph.Search -> {
                drawCircle(
                    color = tint,
                    radius = width * 0.27f,
                    center = Offset(width * 0.44f, height * 0.42f),
                    style = stroke,
                )
                drawLine(
                    color = tint,
                    start = Offset(width * 0.63f, height * 0.61f),
                    end = Offset(width * 0.86f, height * 0.84f),
                    strokeWidth = stroke.width,
                    cap = StrokeCap.Round,
                )
            }

            HeroGlyph.ArrowLeft -> {
                val path = Path().apply {
                    moveTo(width * 0.62f, height * 0.22f)
                    lineTo(width * 0.36f, height * 0.50f)
                    lineTo(width * 0.62f, height * 0.78f)
                }
                drawPath(path = path, color = tint, style = stroke)
            }

            HeroGlyph.ArrowRight -> {
                val path = Path().apply {
                    moveTo(width * 0.38f, height * 0.22f)
                    lineTo(width * 0.64f, height * 0.50f)
                    lineTo(width * 0.38f, height * 0.78f)
                }
                drawPath(path = path, color = tint, style = stroke)
            }

            HeroGlyph.Backspace -> {
                val outline = Path().apply {
                    moveTo(width * 0.32f, height * 0.20f)
                    lineTo(width * 0.88f, height * 0.20f)
                    lineTo(width * 0.88f, height * 0.80f)
                    lineTo(width * 0.32f, height * 0.80f)
                    lineTo(width * 0.10f, height * 0.50f)
                    close()
                }
                drawPath(path = outline, color = tint, style = stroke)
                drawLine(
                    color = tint,
                    start = Offset(width * 0.44f, height * 0.38f),
                    end = Offset(width * 0.70f, height * 0.62f),
                    strokeWidth = stroke.width,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = tint,
                    start = Offset(width * 0.70f, height * 0.38f),
                    end = Offset(width * 0.44f, height * 0.62f),
                    strokeWidth = stroke.width,
                    cap = StrokeCap.Round,
                )
            }

            HeroGlyph.Keyboard -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(width * 0.08f, height * 0.26f),
                    size = Size(width * 0.84f, height * 0.48f),
                    cornerRadius = CornerRadius(width * 0.06f, width * 0.06f),
                    style = stroke,
                )
                for (row in 0 until 3) {
                    for (column in 0 until 4) {
                        drawCircle(
                            color = tint,
                            radius = width * 0.035f,
                            center = Offset(
                                width * (0.20f + column * 0.16f + row * 0.04f),
                                height * (0.38f + row * 0.12f),
                            ),
                        )
                    }
                }
                drawLine(
                    color = tint,
                    start = Offset(width * 0.30f, height * 0.64f),
                    end = Offset(width * 0.70f, height * 0.64f),
                    strokeWidth = stroke.width,
                    cap = StrokeCap.Round,
                )
            }

            HeroGlyph.House -> {
                val roof = Path().apply {
                    moveTo(width * 0.12f, height * 0.46f)
                    lineTo(width * 0.50f, height * 0.14f)
                    lineTo(width * 0.88f, height * 0.46f)
                }
                drawPath(path = roof, color = tint, style = stroke)
                val body = Path().apply {
                    moveTo(width * 0.20f, height * 0.42f)
                    lineTo(width * 0.20f, height * 0.88f)
                    lineTo(width * 0.80f, height * 0.88f)
                    lineTo(width * 0.80f, height * 0.42f)
                }
                drawPath(path = body, color = tint, style = stroke)
            }

            HeroGlyph.Grid -> {
                val cell = width * 0.28f
                listOf(0.16f to 0.16f, 0.56f to 0.16f, 0.16f to 0.56f, 0.56f to 0.56f).forEach { (x, y) ->
                    drawRoundRect(
                        color = tint,
                        topLeft = Offset(width * x, height * y),
                        size = Size(cell, cell),
                        cornerRadius = CornerRadius(width * 0.05f, width * 0.05f),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 海报 View 注册表（Compose ↔ Android View 焦点交接）
// ---------------------------------------------------------------------------

/**
 * [HeroPosterCard] 是 Android View，Compose 的 [FocusRequester] 定位不到它，
 * 因此登记「下标 → View」，跨区移动时直接 `requestFocus()`，卡片自带的红框才会亮。
 */
internal class HeroPosterViewRegistry {

    private val views = HashMap<Int, HeroPosterCard>()

    fun bind(index: Int, card: HeroPosterCard) {
        views[index] = card
    }

    fun unbind(index: Int, card: HeroPosterCard) {
        if (views[index] === card) views.remove(index)
    }

    fun requestFocus(index: Int): Boolean {
        val card = views[index] ?: return false
        if (!card.isAttachedToWindow) return false
        // 【防崩溃】Android View 在这一帧刚被回收/分离时 requestFocus 仍可能抛
        // IllegalStateException，焦点失败只是体验降级，绝不允许冒泡到 Recomposer。
        return runCatching { card.requestFocus() }
            .onFailure { Log.e(TAG_FOCUS, "焦点请求失败: $it") }
            .getOrDefault(false)
    }
}

/** 在已挂载的 FocusRequester 列表里安全请求焦点：未组合的 Lazy 项会抛异常，这里降级返回 false。 */
internal fun requestFocus(requesters: List<FocusRequester>, index: Int, tag: String = TAG_FOCUS): Boolean {
    val requester = requesters.getOrNull(index) ?: return false
    return requestFocus(requester, tag)
}

/**
 * 【防崩溃】`FocusRequester.requestFocus()` 在目标节点本帧被销毁/尚未挂载时会抛
 * `IllegalStateException`（"FocusRequester is not initialized" 等）。
 *
 * 电视端的热搜词是「先读缓存、再被网络结果覆盖」的两段式更新：用户点中的胶囊
 * 可能在同一帧内被移出组合，此时任何焦点请求都属于**可失败的体验动作**，
 * 因此统一在这里吞掉异常并记日志，绝不让它把整个界面带崩。
 */
internal fun requestFocus(requester: FocusRequester, tag: String = TAG_FOCUS): Boolean =
    runCatching { requester.requestFocus() }
        .onFailure { Log.e(tag, "焦点请求失败: $it") }
        .getOrDefault(false)

/** 焦点相关日志统一 TAG（搜索页调用处可传入 `HeroSearch`）。 */
internal const val TAG_FOCUS: String = "HeroFocus"

// ---------------------------------------------------------------------------
// 键盘数据（结构同 xp.java 的 a=7 分支）
// ---------------------------------------------------------------------------

internal sealed class HeroKey {

    /** 第一行第一格占位（原版 `ic_keyboard_remote`）：不聚焦、无动作。 */
    data object Placeholder : HeroKey()

    data class Text(val value: String) : HeroKey()

    data class Action(val glyph: HeroGlyph, val action: HeroKeyAction) : HeroKey()
}

internal enum class HeroKeyAction { CursorLeft, CursorRight, Backspace, Keyboard, House }

/**
 * 42 格 = 第一行 `(占位, <, >, ⌫, 键盘, 房子, A)` + B-Z + 0-9
 * （`xd1.java` L27/L28：6 个图标键 + 36 个字符键）。
 *
 * 下标与格子一一对应：0 占位、1 `<`、2 `>`、3 `⌫`、4 键盘、5 房子、6 `A` … 41 `9`，
 * 所以最右列正好是 A / H / O / V / 2 / 9。
 */
internal val HERO_KEYS: List<HeroKey> = buildList {
    add(HeroKey.Placeholder)
    add(HeroKey.Action(HeroGlyph.ArrowLeft, HeroKeyAction.CursorLeft))
    add(HeroKey.Action(HeroGlyph.ArrowRight, HeroKeyAction.CursorRight))
    add(HeroKey.Action(HeroGlyph.Backspace, HeroKeyAction.Backspace))
    add(HeroKey.Action(HeroGlyph.Keyboard, HeroKeyAction.Keyboard))
    add(HeroKey.Action(HeroGlyph.House, HeroKeyAction.House))
    ('A'..'Z').forEach { add(HeroKey.Text(it.toString())) }
    ('0'..'9').forEach { add(HeroKey.Text(it.toString())) }
}

// ---------------------------------------------------------------------------
// 尺寸常量（每一条都能在 ref_docs 里找到出处，见对应注释）
// ---------------------------------------------------------------------------

// 键盘（逐条对应逆向数据，不是估值）：
//   键 40dp × 40dp   adapter_keyboard_text.xml L5-6 / adapter_keyboard_icon.xml L7-8
//   间距 8dp        CustomKeyboard.java L25 `new SpaceItemDecoration(7, 8)`（SpaceItemDecoration L22 dp2px）
//   容器 wrap_content activity_search.xml L59-60 → 左列用 wrapContentWidth()，宽度由 7×40 + 6×8 = 328dp 自然撑出
internal val KEY_SIZE = 40.dp
internal val KEY_SPACING = 8.dp

// 输入框行：mic 36dp（图标本体 24dp）+ 间距 12dp；输入框固定 200dp、不拉伸；搜索按钮 40dp。
internal val MIC_SIZE = 36.dp
internal val MIC_MARGIN_END = 12.dp
internal val INPUT_WIDTH = 200.dp
internal val SEARCH_BUTTON_SIZE = 40.dp
internal val SEARCH_BUTTON_MARGIN_START = 12.dp
internal val KEY_ICON_SIZE = 24.dp

// 输入框：24sp、字距 0.02（L37/L53）；红框 2dp、圆角 13dp；高度 40dp（= 键盘键高，横向一条线）。
internal const val INPUT_TEXT_SP = 24f
internal const val INPUT_LETTER_SPACING = 0.02f
internal val INPUT_HEIGHT = 40.dp
internal val INPUT_STROKE = 2.dp
internal val INPUT_SHAPE = RoundedCornerShape(13.dp)
internal val INPUT_PADDING_H = 12.dp

// 键盘键：40dp / 16sp / 4dp 圆角（adapter_keyboard_text.xml）。
internal val KEY_SHAPE = RoundedCornerShape(4.dp)
internal const val KEY_TEXT_SP = 16f
internal val KEY_BG_NORMAL = Color(0x33FFFFFF)
internal const val KEY_FOCUS_FADE_MS = 90            // 红底跟随焦点的过渡时长（TV 要快）

// 红色：@color/red = #e50914（shape_red_bar.xml L4）。输入框焦点红框用它。
internal val ACCENT_RED = Color(0xFFE50914)

// 输入上限 19 字（CustomKeyboard.onTextClick L33）。
internal const val KEYWORD_MAX_LENGTH = 19

// 键盘网格：42 格 = 6 行 × 7 列；0 占位、5 房子。
internal const val KEY_COLUMNS = 7
internal const val KEY_ROWS = 6
internal const val PLACEHOLDER_INDEX = 0
internal const val FIRST_KEY_INDEX = 1
internal const val HOUSE_KEY_INDEX = 5

// 图标线稿粗细（24dp 基准）。
internal const val GLYPH_STROKE_RATIO = 0.085f
