package com.fongmi.android.tv.ui.activity

import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil3.compose.AsyncImage
import com.fongmi.android.tv.R
import com.fongmi.android.tv.ui.hero.HeroImageLoader
import com.fongmi.android.tv.ui.hero.HeroPosterCard
import kotlinx.coroutines.launch

/**
 * HeroUI（Compose）搜索页。
 *
 * 结构与尺寸全部取自 `ref_docs/activity_search.xml`，不按屏幕反推、不目测调参：
 * - 根容器左右各 24dp、顶部 24dp、底部 0dp（L4-11）。
 * - 左侧列 `wrap_content` + `layout_marginEnd=24dp`（L17-21）；宽度由 7 列键盘决定：
 *   7 × 40dp + 6 × 8dp = 328dp（`ref_docs/adapter_keyboard_text.xml` L10 的 40dp +
 *   `CustomKeyboard.java` L25 `new SpaceItemDecoration(7, 8)` 的 8dp 列间距）。
 * - 键盘与输入框之间 8dp（L61），键盘 6 行 × 7 列，共 42 键
 *   （`ref_docs/xd1.java` L27-38：6 个图标键 + A-Z + 0-9，正好 42 = 6 行 × 7 列）。
 * - 右侧列 `weight(1f)`：历史 / 热搜（建议）流式胶囊 + 结果区（站点 Tab + 215dp 海报行）。
 *
 * 胶囊/按键三态严格按逆向 drawable（`app/src/leanback/res/drawable` 内的原始资源）：
 * - 键盘键：40dp、16sp 白字、4dp 圆角；常态 `black_20` #33000000，焦点 `black_30` #4D000000
 *   + 1.5dp 白色描边（`shape_keyboard_normal.xml` / `shape_keyboard_focused.xml`）。
 * - 历史 / 热搜 / 建议 / 站点胶囊：16sp（adapter_search_word.xml L3）、
 *   左右 14dp + 上下 8dp 内边距、28dp 圆角（`shape_item_round_normal.xml`）；
 *   常态 `black_20`，焦点 `black_40` #66000000 + 1.5dp 白描边，选中 `black_40`。
 * - 站点 Tab：26dp 高、13sp、13dp 圆角（ref_docs/adapter_type.xml L3/L7-8 → 28dp 圆角被高度裁半），
 *   选中放大 1.08 倍、140ms（`dz2.java` L45-47 / L114-120）。
 *
 * 焦点契约（全部显式 [FocusRequester]，不依赖 2D 自动搜索）：
 * 输入框 ↓ → 键盘第 1 行；键盘内部四向移动（行首/行尾/最后一行就地消费）；
 * 键盘「搜索」→ 右侧站点 Tab；站点 Tab ↓ → 第 1 张海报；海报 ↔ 行内左右移动；
 * 海报 ↑ → 站点 Tab；卡片确定键 → [HeroDetailActivity.start]。
 */
class HeroSearchActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(AndroidColor.BLACK))
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        HeroImageLoader.install()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                HeroSearchScreen(
                    // 卡片点击只走这一个真实入口，不碰播放器与 catvod 数据层。
                    onOpenDetail = { title -> HeroDetailActivity.start(this, title) },
                    onClose = { finish() },
                )
            }
        }
    }

    companion object {

        /** 统一入口：首页顶栏「搜索」调用。 */
        fun start(context: Context) {
            context.startActivity(Intent(context, HeroSearchActivity::class.java))
        }
    }
}

@Composable
private fun HeroSearchScreen(
    onOpenDetail: (String) -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    // 输入框内容用 TextFieldValue 维护：readOnly 只吃我们自己的虚拟键盘，光标位置也由键盘左右键驱动。
    var field by remember { mutableStateOf(TextFieldValue("")) }
    var history by remember { mutableStateOf(HERO_HISTORY.toList()) }
    var selectedSite by remember { mutableIntStateOf(0) }
    var focusedSite by remember { mutableIntStateOf(0) }
    // 按过「搜索」后即使清空输入框也保留结果区：否则清空瞬间结果消失，视觉上像页面被重置。
    var searched by remember { mutableStateOf(false) }

    val keyword = field.text
    val showResults = keyword.trim().isNotEmpty() || searched
    val suggestions = remember(keyword) { suggestionsFor(keyword) }

    val inputRequester = remember { FocusRequester() }
    val submitRequester = remember { FocusRequester() }
    val keyRequesters = remember { List(HERO_KEYS.size) { FocusRequester() } }
    val siteRequesters = remember { List(HERO_SITE_TABS.size) { FocusRequester() } }
    // 海报卡是 Android View（HeroPosterCard），焦点必须直接交给 View，
    // 这样卡片自带的 2dp 红色描边才会亮起（见 HeroPosterCard#drawFocusRing）。
    val posterViews = remember { HeroPosterViewRegistry() }

    val siteState = rememberLazyListState()
    val posterState = rememberLazyListState()
    val contentScroll = rememberScrollState()

    fun focusInput() {
        requestFocus(inputRequester)
    }

    fun focusKeyboard(index: Int = 0) {
        val target = index.coerceIn(0, HERO_KEYS.lastIndex)
        if (!requestFocus(keyRequesters, target)) requestFocus(keyRequesters, 0)
    }

    fun focusSite(index: Int = focusedSite) {
        val target = index.coerceIn(0, HERO_SITE_TABS.lastIndex)
        focusedSite = target
        scope.launch {
            siteState.animateScrollToItem(target)
            withFrameNanos { }
            requestFocus(siteRequesters, target)
        }
    }

    fun focusPoster(index: Int) {
        val target = index.coerceIn(0, HERO_RESULTS.lastIndex)
        scope.launch {
            // index 0..3 停靠在行首，index 4 起把目标卡滚到最左侧（与首页同一套靠左吸附规则）。
            val anchor = if (target >= POSTER_SCROLL_ANCHOR) target else 0
            posterState.animateScrollToItem(anchor)
            withFrameNanos { }
            if (!posterViews.requestFocus(target)) {
                withFrameNanos { }
                posterViews.requestFocus(target)
            }
        }
    }

    fun setKeyword(text: String) {
        field = TextFieldValue(text = text, selection = TextRange(text.length))
    }

    /** 与 `CustomKeyboard.onTextClick` L31-36 一致：不超过 19 个字符，插入到光标位置。 */
    fun insert(text: String) {
        val value = field
        if (value.text.length >= KEYWORD_MAX_LENGTH) return
        val start = minOf(value.selection.start, value.selection.end).coerceIn(0, value.text.length)
        val end = maxOf(value.selection.start, value.selection.end).coerceIn(0, value.text.length)
        val merged = value.text.replaceRange(start, end, text)
        if (merged.length > KEYWORD_MAX_LENGTH) return
        val caret = start + text.length
        field = TextFieldValue(text = merged, selection = TextRange(caret))
    }

    fun backspace() {
        val value = field
        val start = minOf(value.selection.start, value.selection.end).coerceIn(0, value.text.length)
        val end = maxOf(value.selection.start, value.selection.end).coerceIn(0, value.text.length)
        if (start != end) {
            field = TextFieldValue(
                text = value.text.removeRange(start, end),
                selection = TextRange(start),
            )
            return
        }
        if (start == 0) return
        field = TextFieldValue(
            text = value.text.removeRange(start - 1, start),
            selection = TextRange(start - 1),
        )
    }

    fun moveCaret(delta: Int) {
        val caret = (field.selection.start + delta).coerceIn(0, field.text.length)
        field = field.copy(selection = TextRange(caret))
    }

    /** 搜索：写一条历史（最多 9 条）→ 焦点交给站点 Tab，Tab 尚未组合时兜底给第一张海报。 */
    fun submit() {
        val text = field.text.trim()
        if (text.isEmpty()) return
        searched = true
        history = (listOf(text) + history.filterNot { it == text }).take(HISTORY_LIMIT)
        scope.launch {
            withFrameNanos { }
            contentScroll.animateScrollTo(contentScroll.maxValue)
            withFrameNanos { }
            if (!requestFocus(siteRequesters, focusedSite)) focusPoster(0)
        }
    }

    fun onKeyClick(key: HeroKey) {
        when (key) {
            is HeroKey.Text -> insert(key.value)

            is HeroKey.Action -> when (key.action) {
                HeroKeyAction.Clear -> {
                    field = TextFieldValue("")
                    searched = false
                }

                HeroKeyAction.Left -> moveCaret(-1)
                HeroKeyAction.Right -> moveCaret(1)
                HeroKeyAction.Backspace -> backspace()
                HeroKeyAction.Search -> submit()
                HeroKeyAction.Home -> onClose()
            }
        }
    }

    fun onKeyMove(index: Int, key: Key): Boolean {
        val row = index / KEY_COLUMNS
        val column = index % KEY_COLUMNS
        return when (key) {
            Key.DirectionLeft -> {
                if (column > 0) requestFocus(keyRequesters, index - 1)
                true
            }

            Key.DirectionRight -> {
                if (column < KEY_COLUMNS - 1) requestFocus(keyRequesters, index + 1)
                true
            }

            Key.DirectionUp -> {
                if (row > 0) requestFocus(keyRequesters, index - KEY_COLUMNS) else focusInput()
                true
            }

            Key.DirectionDown -> {
                if (row < KEY_ROWS - 1) requestFocus(keyRequesters, index + KEY_COLUMNS)
                true
            }

            else -> false
        }
    }

    // 进入页面默认焦点在输入框（原版 SearchActivity.onResume L612 keyword.requestFocus()）。
    LaunchedEffect(Unit) {
        withFrameNanos { }
        requestFocus(inputRequester)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // ---- 背景：复用首页一张暗色背景图 + 黑色渐变遮罩（保证键盘/文字在亮背景上可读）----
        AsyncImage(
            model = HERO_BACKDROP,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Black.copy(alpha = 0.88f),
                        0.30f to Color.Black.copy(alpha = 0.45f),
                        0.65f to Color.Black.copy(alpha = 0.78f),
                        1.0f to Color.Black.copy(alpha = 0.97f),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0.0f to Color.Black.copy(alpha = 0.55f),
                        0.60f to Color.Black.copy(alpha = 0.10f),
                        1.0f to Color.Transparent,
                    ),
                ),
        )

        // ---- 根 Row：左右两列（activity_search.xml L12-144）----
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = ROOT_PADDING_H)
                .padding(top = ROOT_PADDING_TOP),
        ) {
            // ---------------- 左列：输入框 + 虚拟键盘 ----------------
            Column(modifier = Modifier.width(LEFT_COLUMN_WIDTH)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Mic 36dp + marginEnd 12dp（L32-35）。语音能力未接，只做展示，不参与焦点。
                    HeroGlyphIcon(
                        glyph = HeroGlyph.Mic,
                        modifier = Modifier.size(MIC_SIZE),
                    )
                    Spacer(modifier = Modifier.width(MIC_MARGIN_END))
                    HeroSearchInput(
                        requester = inputRequester,
                        value = field,
                        onMoveDown = { focusKeyboard(0) },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(SEARCH_BUTTON_MARGIN_START))
                    HeroSearchSubmit(
                        requester = submitRequester,
                        onConfirm = { submit() },
                        onMoveDown = { focusKeyboard(0) },
                        onMoveLeft = { focusInput() },
                    )
                }
                // 键盘 marginTop 8dp（L61）。
                Spacer(modifier = Modifier.height(KEYBOARD_TOP_GAP))
                HeroKeyboard(
                    requesters = keyRequesters,
                    onClick = { key -> onKeyClick(key) },
                    onMove = { index, key -> onKeyMove(index, key) },
                )
            }

            Spacer(modifier = Modifier.width(LEFT_COLUMN_MARGIN_END))

            // ---------------- 右列：历史 / 热搜（建议）/ 结果 ----------------
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(contentScroll),
            ) {
                HeroWordSection(
                    title = TITLE_HISTORY,
                    words = history,
                    onWordClick = { text ->
                        setKeyword(text)
                        submit()
                    },
                )
                if (keyword.isBlank()) {
                    HeroWordSection(
                        title = TITLE_HOT,
                        words = HERO_HOT,
                        onWordClick = { text ->
                            setKeyword(text)
                            submit()
                        },
                    )
                } else if (suggestions.isNotEmpty()) {
                    HeroWordSection(
                        title = TITLE_SUGGEST,
                        words = suggestions,
                        onWordClick = { text ->
                            setKeyword(text)
                            submit()
                        },
                    )
                }

                if (showResults) {
                    // resultSection marginTop 12dp（L151）。
                    Spacer(modifier = Modifier.height(RESULT_SECTION_TOP_GAP))
                    HeroSiteTabs(
                        state = siteState,
                        selected = selectedSite,
                        requesters = siteRequesters,
                        onFocused = { index -> focusedSite = index },
                        onConfirm = { index ->
                            selectedSite = index
                            focusedSite = index
                        },
                        onMoveUp = { focusInput() },
                        onMoveDown = { focusPoster(0) },
                        onMoveLeft = { index -> focusSite(index - 1) },
                        onMoveRight = { index -> focusSite(index + 1) },
                    )
                    // siteRecycler marginBottom 10dp（L159）。
                    Spacer(modifier = Modifier.height(SITE_ROW_BOTTOM_GAP))
                    HeroResultRow(
                        state = posterState,
                        registry = posterViews,
                        onFocused = { },
                        onClick = onOpenDetail,
                        // 海报 ↑ 回到站点 Tab：落在当前焦点停留的那一项，而不是海报的下标。
                        onMoveUp = { focusSite() },
                        onMoveDown = { },
                        onMoveLeft = { index -> if (index > 0) focusPoster(index - 1) },
                        onMoveRight = { index ->
                            if (index < HERO_RESULTS.lastIndex) focusPoster(index + 1)
                        },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 左侧：搜索输入框
// ---------------------------------------------------------------------------

/**
 * 只读搜索框（禁用系统输入法）。
 *
 * - 文字 24sp、字距 0.02（activity_search.xml L37、L53）。
 * - 焦点态红框：`selector_tmdb_input.xml` 的红色描边样式；描边 2dp、颜色 #E50914
 *   （`shape_red_bar.xml` / `colors.xml` 的 red），圆角 13dp（参考截图输入框的圆角，
 *   与站点 Tab 26dp 高度的半圆半径同值）。
 */
@Composable
private fun HeroSearchInput(
    requester: FocusRequester,
    value: TextFieldValue,
    onMoveDown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .heightIn(min = INPUT_MIN_HEIGHT)
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
                        // 输入框 ↓ 进入键盘第一行（L45 nextFocusDown=keyboard）。
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
                        // textColorHint=@color/white（L39）。
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

/** 顶部搜索按钮：40dp 圆形 + 24dp 放大镜（与 40dp 键盘键同尺寸，见 adapter_keyboard_text.xml L10）。 */
@Composable
private fun HeroSearchSubmit(
    requester: FocusRequester,
    onConfirm: () -> Unit,
    onMoveDown: () -> Unit,
    onMoveLeft: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .size(SEARCH_BUTTON_SIZE)
            .focusRequester(requester)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
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
            .clip(CircleShape)
            .background(if (focused) KEY_BG_FOCUSED else KEY_BG_NORMAL, CircleShape)
            .then(if (focused) Modifier.border(KEY_STROKE, Color.White, CircleShape) else Modifier)
            .clickable(interactionSource = interaction, indication = null, onClick = onConfirm),
        contentAlignment = Alignment.Center,
    ) {
        HeroGlyphIcon(glyph = HeroGlyph.Search, modifier = Modifier.size(SEARCH_ICON_SIZE))
    }
}

// ---------------------------------------------------------------------------
// 左侧：虚拟键盘（7 列 × 6 行 = 42 键）
// ---------------------------------------------------------------------------

/**
 * 虚拟键盘：`ref_docs/xd1.java` L27-38 的 42 项 + `CustomKeyboard.java` L25 的 8dp 间距。
 *
 * 图标键按原版顺序的前 4 个保留功能语义（清空 / 光标左 / 光标右 / 退格），
 * 后两个换成「搜索」「返回首页」——本页没有远程输入与中英切换，空键比缺搜索键更难用。
 */
@Composable
private fun HeroKeyboard(
    requesters: List<FocusRequester>,
    onClick: (HeroKey) -> Unit,
    onMove: (Int, Key) -> Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(KEY_SPACING)) {
        for (row in 0 until KEY_ROWS) {
            Row(horizontalArrangement = Arrangement.spacedBy(KEY_SPACING)) {
                for (column in 0 until KEY_COLUMNS) {
                    val index = row * KEY_COLUMNS + column
                    HeroKeyCell(
                        key = HERO_KEYS[index],
                        requester = requesters[index],
                        onMove = { key -> onMove(index, key) },
                        onClick = { onClick(HERO_KEYS[index]) },
                    )
                }
            }
        }
    }
}

/** 单键：40×40dp、16sp 白字、4dp 圆角，焦点态 = black_30 + 1.5dp 白描边。 */
@Composable
private fun HeroKeyCell(
    key: HeroKey,
    requester: FocusRequester,
    onMove: (Key) -> Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .size(KEY_SIZE)
            .focusRequester(requester)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                event.type == KeyEventType.KeyDown && onMove(event.key)
            }
            .clip(KEY_SHAPE)
            .background(if (focused) KEY_BG_FOCUSED else KEY_BG_NORMAL, KEY_SHAPE)
            .then(if (focused) Modifier.border(KEY_STROKE, Color.White, KEY_SHAPE) else Modifier)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when (key) {
            is HeroKey.Text -> Text(
                text = key.value,
                color = Color.White,
                fontSize = KEY_TEXT_SP.sp,
                maxLines = 1,
            )

            is HeroKey.Action -> HeroGlyphIcon(
                glyph = key.glyph,
                modifier = Modifier.size(KEY_ICON_SIZE),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 右侧：区块标题 + 流式胶囊
// ---------------------------------------------------------------------------

/**
 * 区块标题：`SearchActivityV2.T1(TextView)` L531-559 的真实参数 ——
 * 20sp 粗体、#CCFFFFFF、前方 4dp × 16dp 红色竖条（shape_red_bar：#E50914 / 2dp 圆角）、
 * 竖条与文字间距 10dp、整行保留原 TextView 的 8dp bottomMargin（activity_search.xml L92/L109）。
 */
@Composable
private fun HeroSectionTitle(title: String) {
    Row(
        modifier = Modifier.padding(bottom = SECTION_TITLE_BOTTOM_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(SECTION_BAR_WIDTH)
                .height(SECTION_BAR_HEIGHT)
                .background(ACCENT_RED, SECTION_BAR_SHAPE),
        )
        Spacer(modifier = Modifier.width(SECTION_BAR_MARGIN_END))
        Text(
            text = title,
            color = SECTION_TITLE_COLOR,
            fontSize = SECTION_TITLE_SP.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/**
 * 历史 / 热搜 / 建议区：流式布局，每个胶囊 16sp、左右 14dp + 上下 8dp、28dp 圆角，
 * 并且各带 6dp 外边距（adapter_search_word.xml L7/L12 + shape_item_round_normal.xml L7-13）。
 * 列表整体左移 6dp，用负外边距抵消首尾 6dp —— 对应 XML L101/L118 的 `layout_marginStart="-6dp"`。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeroWordSection(
    title: String,
    words: List<String>,
    onWordClick: (String) -> Unit,
) {
    if (words.isEmpty()) return

    Column(modifier = Modifier.fillMaxWidth()) {
        HeroSectionTitle(title)
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .offset(x = -CHIP_MARGIN, y = -CHIP_MARGIN),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            words.forEach { word ->
                HeroWordChip(
                    text = word,
                    onClick = { onWordClick(word) },
                    modifier = Modifier.padding(CHIP_MARGIN),
                )
            }
        }
    }
}

@Composable
private fun HeroWordChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(CHIP_SHAPE)
            .background(if (focused) CHIP_BG_FOCUSED else CHIP_BG_NORMAL, CHIP_SHAPE)
            .then(if (focused) Modifier.border(CHIP_STROKE, Color.White, CHIP_SHAPE) else Modifier)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = CHIP_PADDING_H, vertical = CHIP_PADDING_V),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = CHIP_TEXT_SP.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------------------
// 右侧：站点 Tab + 海报行
// ---------------------------------------------------------------------------

@Composable
private fun HeroSiteTabs(
    state: LazyListState,
    selected: Int,
    requesters: List<FocusRequester>,
    onFocused: (Int) -> Unit,
    onConfirm: (Int) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onMoveLeft: (Int) -> Unit,
    onMoveRight: (Int) -> Unit,
) {
    LazyRow(
        state = state,
        modifier = Modifier
            .fillMaxWidth()
            // siteRecycler paddingBottom=2dp（L154）。
            .padding(bottom = SITE_ROW_BOTTOM_PADDING),
        horizontalArrangement = Arrangement.spacedBy(SITE_ITEM_SPACING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(items = HERO_SITE_TABS) { index, label ->
            HeroSiteTab(
                label = label,
                selected = index == selected,
                requester = requesters[index],
                onFocused = { onFocused(index) },
                onConfirm = { onConfirm(index) },
                onMoveUp = onMoveUp,
                onMoveDown = onMoveDown,
                onMoveLeft = { onMoveLeft(index) },
                onMoveRight = { onMoveRight(index) },
            )
        }
    }
}

/** 站点胶囊：高 26dp、13sp、13dp 圆角；选中态放大 1.08（dz2.java L45-47、L114-120）。 */
@Composable
private fun HeroSiteTab(
    label: String,
    selected: Boolean,
    requester: FocusRequester,
    onFocused: () -> Unit,
    onConfirm: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val scale by animateFloatAsState(
        targetValue = if (selected) SITE_SELECTED_SCALE else 1f,
        animationSpec = tween(durationMillis = SITE_SCALE_DURATION_MS),
        label = "siteTabScale",
    )

    Box(
        modifier = Modifier
            .scale(scale)
            .height(SITE_TAB_HEIGHT)
            .focusRequester(requester)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionUp -> {
                        onMoveUp()
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

                    Key.DirectionRight -> {
                        onMoveRight()
                        true
                    }

                    else -> false
                }
            }
            .clip(SITE_TAB_SHAPE)
            .background(
                when {
                    focused -> CHIP_BG_FOCUSED
                    selected -> CHIP_BG_FOCUSED
                    else -> CHIP_BG_NORMAL
                },
                SITE_TAB_SHAPE,
            )
            .then(if (focused) Modifier.border(CHIP_STROKE, Color.White, SITE_TAB_SHAPE) else Modifier)
            .clickable(interactionSource = interaction, indication = null, onClick = onConfirm)
            .padding(horizontal = CHIP_PADDING_H),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = SITE_TAB_SP.sp,
            fontWeight = if (focused || selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

/**
 * 215dp 海报行（activity_search.xml L167）+ 16dp 间距（`SearchActivityV2` L425）。
 *
 * 卡片只用全局唯一组件 [HeroPosterCard]（120 × 214dp，尺寸由组件自己锁死），
 * 这里通过 [AndroidView] 承载它，并把方向键交给组件自己画出来的红色描边。
 */
@Composable
private fun HeroResultRow(
    state: LazyListState,
    registry: HeroPosterViewRegistry,
    onFocused: (Int) -> Unit,
    onClick: (String) -> Unit,
    onMoveUp: (Int) -> Unit,
    onMoveDown: (Int) -> Unit,
    onMoveLeft: (Int) -> Unit,
    onMoveRight: (Int) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(POSTER_AREA_HEIGHT),
    ) {
        LazyRow(
            state = state,
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(POSTER_ITEM_SPACING),
        ) {
            itemsIndexed(items = HERO_RESULTS) { index, item ->
                AndroidView(
                    modifier = Modifier
                        .width(HeroPosterCard.CARD_WIDTH_DP.dp)
                        .height(HeroPosterCard.CARD_HEIGHT_DP.dp),
                    factory = { context ->
                        HeroPosterCard(context).also { card -> registry.bind(index, card) }
                    },
                    update = { card ->
                        // 只在值变化时写，避免每次重组都触发 HeroPosterCard 内部的 Glide 重载。
                        if (card.title != item.title) card.title = item.title
                        if (card.topStartTag != item.topTag) card.topStartTag = item.topTag
                        if (card.bottomStartTag != item.bottomTag) card.bottomStartTag = item.bottomTag
                        if (card.imageUrl != item.imageUrl) card.imageUrl = item.imageUrl

                        card.setOnFocusChangeListener { _, hasFocus ->
                            if (hasFocus) onFocused(index)
                        }
                        card.setOnClickListener { onClick(item.title) }
                        card.setOnKeyListener { _, keyCode, event ->
                            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                            when (keyCode) {
                                KeyEvent.KEYCODE_DPAD_LEFT -> {
                                    onMoveLeft(index)
                                    true
                                }

                                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                    onMoveRight(index)
                                    true
                                }

                                KeyEvent.KEYCODE_DPAD_UP -> {
                                    onMoveUp(index)
                                    true
                                }

                                KeyEvent.KEYCODE_DPAD_DOWN -> {
                                    onMoveDown(index)
                                    true
                                }

                                else -> false
                            }
                        }
                    },
                    onRelease = { card -> registry.unbind(index, card) },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 图标（本项目 main 源集没有 mic / search / keyboard 矢量，用 Canvas 画，避免新增资源文件）
// ---------------------------------------------------------------------------

private enum class HeroGlyph { Mic, Search, Clear, ArrowLeft, ArrowRight, Backspace, Home }

/** 统一 24dp / 36dp 线稿图标；线宽按 24dp 视觉基准取 0.085 × 边长。 */
@Composable
private fun HeroGlyphIcon(
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

            HeroGlyph.Clear -> {
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
                    start = Offset(width * 0.48f, height * 0.38f),
                    end = Offset(width * 0.70f, height * 0.62f),
                    strokeWidth = stroke.width,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = tint,
                    start = Offset(width * 0.70f, height * 0.38f),
                    end = Offset(width * 0.48f, height * 0.62f),
                    strokeWidth = stroke.width,
                    cap = StrokeCap.Round,
                )
            }

            HeroGlyph.Home -> {
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
        }
    }
}

// ---------------------------------------------------------------------------
// 海报 View 注册表（Compose ↔ Android View 焦点交接）
// ---------------------------------------------------------------------------

/**
 * LazyRow 里的 [HeroPosterCard] 是 Android View，Compose 的 [FocusRequester] 无法直接定位它，
 * 因此把「下标 → 已创建的 View」登记起来，跨区移动时直接调用 `requestFocus()`，
 * 让卡片自己的红色描边生效。
 */
private class HeroPosterViewRegistry {

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
        return card.requestFocus()
    }
}

/** 在已挂载的 FocusRequester 列表里安全地请求焦点：未组合的 Lazy 项会抛异常，这里降级返回 false。 */
private fun requestFocus(requesters: List<FocusRequester>, index: Int): Boolean {
    val requester = requesters.getOrNull(index) ?: return false
    return requestFocus(requester)
}

private fun requestFocus(requester: FocusRequester): Boolean =
    runCatching { requester.requestFocus() }.isSuccess

// ---------------------------------------------------------------------------
// 假数据（数据层未接，先验证结构与焦点；结构参考 xp.java 的 a=7 分支：纯字符串列表）
// ---------------------------------------------------------------------------

private data class HeroSearchResult(
    val title: String,
    val topTag: String,
    val bottomTag: String,
    val imageUrl: String,
)

private sealed class HeroKey {

    data class Text(val value: String) : HeroKey()

    data class Action(val glyph: HeroGlyph, val action: HeroKeyAction) : HeroKey()
}

private enum class HeroKeyAction { Clear, Left, Right, Backspace, Search, Home }

/** 42 键 = 6 个功能键 + A-Z + 0-9（xd1.java L27-30 / L37-38 的排布，正好铺满 7 列 × 6 行）。 */
private val HERO_KEYS: List<HeroKey> = buildList {
    add(HeroKey.Action(HeroGlyph.Clear, HeroKeyAction.Clear))
    add(HeroKey.Action(HeroGlyph.ArrowLeft, HeroKeyAction.Left))
    add(HeroKey.Action(HeroGlyph.ArrowRight, HeroKeyAction.Right))
    add(HeroKey.Action(HeroGlyph.Backspace, HeroKeyAction.Backspace))
    add(HeroKey.Action(HeroGlyph.Search, HeroKeyAction.Search))
    add(HeroKey.Action(HeroGlyph.Home, HeroKeyAction.Home))
    ('A'..'Z').forEach { add(HeroKey.Text(it.toString())) }
    ('0'..'9').forEach { add(HeroKey.Text(it.toString())) }
}

/** 复用首页第一张背景图（HeroHomeActivity 的 picsum 假数据），不额外引入新资源。 */
private const val HERO_BACKDROP = "https://picsum.photos/seed/hero-backdrop-0/1280/720"

/** 历史记录：结构同 xp.java 的 a=7 分支（字符串列表），最多展示 9 条。 */
private val HERO_HISTORY: List<String> = listOf(
    "凡人修仙传",
    "http://127.0.0.1:8877/proxy?do=appSignature",
)

private val HERO_HOT: List<String> = listOf(
    "开心锤锤",
    "战狼2",
    "小猪佩奇全集",
    "抓特务",
    "新猫和老鼠 第四季",
    "汪汪队立大功全集",
    "熊出没全集",
    "新闻联播",
    "功夫女足",
)

private val HERO_SUGGESTIONS: Map<String, List<String>> = mapOf(
    "凡人" to listOf(
        "凡人修仙传",
        "凡人修仙传第一季",
        "凡人修仙传第二季",
        "凡人修仙传国漫",
        "凡人修仙传动漫版全集",
        "凡人修仙传第三季",
    ),
)

private val HERO_SITE_TABS: List<String> = listOf("全部", "指南", "花卷", "木偶", "觅盘", "玩偶", "盘搜")

private val HERO_RESULTS: List<HeroSearchResult> = listOf(
    HeroSearchResult("凡人修仙传", "4K HDR", "更新至197集", "https://picsum.photos/seed/hero-search-0/400/600"),
    HeroSearchResult("凡人修仙传第一季", "独播", "全 30 集", "https://picsum.photos/seed/hero-search-1/400/600"),
    HeroSearchResult("凡人修仙传第二季", "1080P", "全 24 集", "https://picsum.photos/seed/hero-search-2/400/600"),
    HeroSearchResult("凡人修仙传动漫版全集", "会员", "全 120 集", "https://picsum.photos/seed/hero-search-3/400/600"),
    HeroSearchResult("凡人修仙传第三季", "HDR", "更新至 12 集", "https://picsum.photos/seed/hero-search-4/400/600"),
    HeroSearchResult("战狼2", "4K", "电影", "https://picsum.photos/seed/hero-search-5/400/600"),
    HeroSearchResult("开心锤锤", "热播", "更新至 300 集", "https://picsum.photos/seed/hero-search-6/400/600"),
)

/** 建议词：先走「凡人」这一组精确假数据，其余关键字从热搜 / 历史里做包含匹配。 */
private fun suggestionsFor(keyword: String): List<String> {
    if (keyword.isBlank()) return emptyList()
    HERO_SUGGESTIONS[keyword]?.let { return it }
    return (HERO_HOT + HERO_HISTORY + HERO_RESULTS.map { it.title })
        .filter { it.contains(keyword, ignoreCase = true) }
        .distinct()
}

// ---------------------------------------------------------------------------
// 尺寸常量（每一条都能在 ref_docs 里找到出处，见对应注释）
// ---------------------------------------------------------------------------

// 根容器：activity_search.xml L4/L10/L11 → paddingTop 24dp、paddingStart/End 24dp、paddingBottom 0dp。
private val ROOT_PADDING_H = 24.dp
private val ROOT_PADDING_TOP = 24.dp

// 左列：7 × 40dp + 6 × 8dp = 328dp（adapter_keyboard_text.xml L10 + CustomKeyboard.java L25）。
private val KEY_SIZE = 40.dp
private val KEY_SPACING = 8.dp
private val LEFT_COLUMN_WIDTH = 328.dp
private val LEFT_COLUMN_MARGIN_END = 24.dp          // activity_search.xml L21

// 顶部行：mic 36dp + marginEnd 12dp（L32-35）；键盘 marginTop 8dp（L61）。
private val MIC_SIZE = 36.dp
private val MIC_MARGIN_END = 12.dp
private val KEYBOARD_TOP_GAP = 8.dp

// 搜索按钮：与 40dp 键盘键同尺寸；图标沿用 24dp 键盘图标尺寸（adapter_keyboard_icon.xml L7-8）。
private val SEARCH_BUTTON_SIZE = 40.dp
private val SEARCH_BUTTON_MARGIN_START = 12.dp
private val SEARCH_ICON_SIZE = 24.dp
private val KEY_ICON_SIZE = 24.dp

// 输入框：文字 24sp、字距 0.02（L37、L53）；红框描边 2dp / 圆角 13dp（selector_tmdb_input 红色描边样式）。
private const val INPUT_TEXT_SP = 24f
private const val INPUT_LETTER_SPACING = 0.02f
private val INPUT_MIN_HEIGHT = 40.dp
private val INPUT_STROKE = 2.dp
private val INPUT_SHAPE = RoundedCornerShape(13.dp)
private val INPUT_PADDING_H = 12.dp

// 键盘键三态：shape_keyboard_normal.xml / shape_keyboard_focused.xml。
private val KEY_SHAPE = RoundedCornerShape(4.dp)
private const val KEY_TEXT_SP = 16f
private val KEY_STROKE = 1.5.dp
private val KEY_BG_NORMAL = Color(0x33000000)        // @color/black_20
private val KEY_BG_FOCUSED = Color(0x4D000000)       // @color/black_30

// 区块标题：T1() 的 20sp 粗体 + white_80；红条 4 × 16dp、间距 10dp（shape_red_bar.xml：2dp 圆角 / #E50914）。
private const val SECTION_TITLE_SP = 20f
private val SECTION_TITLE_COLOR = Color(0xCCFFFFFF) // @color/white_80
private val SECTION_BAR_WIDTH = 4.dp
private val SECTION_BAR_HEIGHT = 16.dp
private val SECTION_BAR_MARGIN_END = 10.dp
private val SECTION_BAR_SHAPE = RoundedCornerShape(2.dp)
private val SECTION_TITLE_BOTTOM_GAP = 8.dp         // activity_search.xml L92 / L109

// 流式胶囊：adapter_search_word.xml L3/L12 + shape_item_round_normal.xml L7-13。
private const val CHIP_TEXT_SP = 16f
private val CHIP_PADDING_H = 14.dp
private val CHIP_PADDING_V = 8.dp
private val CHIP_MARGIN = 6.dp
private val CHIP_SHAPE = RoundedCornerShape(percent = 50) // radius 28dp ≥ 高度一半时就是胶囊
private val CHIP_STROKE = 1.5.dp
private val CHIP_BG_NORMAL = Color(0x33000000)       // @color/black_20
private val CHIP_BG_FOCUSED = Color(0x66000000)      // @color/black_40

// 站点 Tab：adapter_type.xml L3/L7-8（13sp / 26dp）+ dz2.java L45-47（选中 1.08 倍、140ms）。
private const val SITE_TAB_SP = 13f
private val SITE_TAB_HEIGHT = 26.dp
private val SITE_TAB_SHAPE = RoundedCornerShape(13.dp)
private const val SITE_SELECTED_SCALE = 1.08f
private const val SITE_SCALE_DURATION_MS = 140
private val SITE_ITEM_SPACING = 12.dp                // SearchActivityV2 L408 setHorizontalSpacing(dp(12))
private val SITE_ROW_BOTTOM_PADDING = 2.dp           // activity_search.xml L154
private val SITE_ROW_BOTTOM_GAP = 10.dp              // activity_search.xml L159
private val RESULT_SECTION_TOP_GAP = 12.dp           // activity_search.xml L151

// 海报行：activity_search.xml L167 → 215dp；卡片间距 16dp（SearchActivityV2 L425）。
private val POSTER_AREA_HEIGHT = 215.dp
private val POSTER_ITEM_SPACING = 16.dp
private const val POSTER_SCROLL_ANCHOR = 4

// 红色：@color/red = #E50914（shape_red_bar.xml L4）。
private val ACCENT_RED = Color(0xFFE50914)

// 输入长度上限：CustomKeyboard.onTextClick L33（> 19 直接返回）；历史最多 9 条（需求约定）。
private const val KEYWORD_MAX_LENGTH = 19
private const val HISTORY_LIMIT = 9

// 键盘网格：xd1.java 的 42 项 = 6 行 × 7 列。
private const val KEY_COLUMNS = 7
private const val KEY_ROWS = 6

// 图标线稿粗细（24dp 基准）。
private const val GLYPH_STROKE_RATIO = 0.085f

// 文本标题（search_record / search_hot / search_suggest，中文文案与首页顶栏一致）。
private const val TITLE_HISTORY = "历史"
private const val TITLE_HOT = "热搜"
private const val TITLE_SUGGEST = "建议"
