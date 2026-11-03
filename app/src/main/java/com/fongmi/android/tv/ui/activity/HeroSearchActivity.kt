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
import androidx.compose.ui.platform.LocalContext
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
import com.fongmi.android.tv.ui.hero.HERO_SOURCE_ENTRIES
import com.fongmi.android.tv.ui.hero.HeroImageLoader
import com.fongmi.android.tv.ui.hero.HeroPosterCard
import com.fongmi.android.tv.ui.hero.SourceSidebar
import com.fongmi.android.tv.ui.hero.SourceSidebarMode
import com.fongmi.android.tv.utils.Notify
import kotlinx.coroutines.launch

/**
 * HeroUI（Compose）搜索页。
 *
 * 版式严格按 `ref_docs/activity_search.xml` 的**上下结构**（L2-11 根 LinearLayout vertical）：
 * ```
 * Column(paddingTop=24dp, paddingStart/End=24dp, paddingBottom=0)
 *   ├─ Row(weight=1f)                        // L12-16：上半区，左右两列
 *   │    ├─ Column(wrap_content)             // L17-65：输入框 + 键盘
 *   │    │    ├─ Row: mic 36dp + 输入框(+ 搜索按钮，见 topbar_search_page.png)
 *   │    │    └─ 键盘：7 列 × 6 行，40dp 键 + 8dp 间距
 *   │    └─ Column(weight=1f, 可滚动)        // L66-143：历史 + 热搜（有输入时替换为建议）
 *   └─ Column(paddingTop=12dp)               // L145-207：下半区结果区，仅输入框有文字时出现
 *        ├─ 站点 Tab：LazyRow（26dp 高胶囊）
 *        └─ 海报行：215dp 的 LazyRow（AndroidView 包 HeroPosterCard）
 * ```
 *
 * 键盘第一行与反编译的 `ref_docs/xd1.java` L27 完全一致：
 * `(占位, <, >, ⌫, 键盘, 房子, A)` → 之后 B-Z、0-9，合计 42 项 = 6 行 × 7 列，
 * 因此最右列正是 A / H / O / V / 2 / 9（与设计稿 `topbar_search_page.png` 一致）。
 *
 * 所有尺寸、颜色、圆角都取自 `ref_docs` 与仓库内的原始 drawable，没有目测调参：
 * - 键盘键：40dp、16sp 白字、4dp 圆角（`adapter_keyboard_text.xml` L3/L10）；
 *   常态 `black_20` #33000000，焦点 `black_30` #4D000000 + 1.5dp 白描边
 *   （`selector_keyboard.xml` → `shape_keyboard_normal/focused.xml`）。
 * - 胶囊（历史 / 热搜 / 建议 / 站点 Tab）：16sp / 13sp、`selector_item_round.xml` →
 *   `shape_item_round_normal`（#33000000 半透明，不是实心黑底）/ `focused`（#66000000 + 1.5dp 白描边）
 *   / `activated`（#66000000）；圆角 28dp、内边距 14dp×8dp、外边距 6dp（`adapter_search_word.xml` L7-12）。
 * - 站点 Tab：26dp 高、13sp（`adapter_type.xml` L3/L10），选中放大 1.08 倍 / 140ms（`dz2.java` L45-47）。
 * - 区块标题：红色竖条 4dp × 16dp（`shape_red_bar.xml`：#e50914、2dp 圆角）+ 20sp 粗体白字。
 *
 * 焦点契约（全部显式 [FocusRequester]，不依赖 2D 自动搜索）：
 * 输入框 ↓ → 键盘第 1 行；键盘内部四向移动；
 * **键盘最右列（A/H/O/V/2/9）→ 右侧第一排第一个胶囊（历史优先，其次热搜/建议）**；
 * **该胶囊 ← → 退回键盘上次停留的键**；胶囊 ↓ → 站点 Tab；Tab ↓ → 首张海报；
 * 海报 ↔ 行内左右移动、↑ → 站点 Tab；卡片确定键 → [HeroDetailActivity.start]。
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
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 输入内容用 TextFieldValue 维护：readOnly 只吃我们自己的虚拟键盘，光标由键盘 < / > 驱动。
    var field by remember { mutableStateOf(TextFieldValue("")) }
    var history by remember { mutableStateOf(HERO_HISTORY.toList()) }
    var selectedSite by remember { mutableIntStateOf(0) }
    var focusedSite by remember { mutableIntStateOf(0) }
    // 键盘上次停留的键：右侧胶囊按 ← 时回到这里，而不是永远回第一个键。
    var lastKeyIndex by remember { mutableIntStateOf(FIRST_ACTION_KEY_INDEX) }
    // 结果区当前是否持有焦点：文字被清空导致结果区消失时，用它把焦点安全送回输入框。
    var resultsFocused by remember { mutableStateOf(false) }

    val keyword = field.text
    val hasKeyword = keyword.trim().isNotEmpty()
    // 有文字 → 「历史 + 建议」；无文字 → 「历史 + 热搜」（需求三态）。
    val wordList = remember(keyword) {
        if (hasKeyword) suggestionsFor(keyword) else HERO_HOT
    }

    val inputRequester = remember { FocusRequester() }
    val searchRequester = remember { FocusRequester() }
    val keyRequesters = remember { List(HERO_KEYS.size) { FocusRequester() } }
    val historyRequesters = remember(history) { List(history.size) { FocusRequester() } }
    val wordRequesters = remember(wordList) { List(wordList.size) { FocusRequester() } }
    val siteRequesters = remember { List(HERO_SITE_TABS.size) { FocusRequester() } }
    // 海报卡是 Android View（HeroPosterCard），焦点必须直接交给 View，
    // 这样卡片自带的 2dp 红色描边才会亮起（见 HeroPosterCard#drawFocusRing）。
    val posterViews = remember { HeroPosterViewRegistry() }

    val rightScroll = rememberScrollState()
    val siteState = rememberLazyListState()
    val posterState = rememberLazyListState()

    fun focusInput() = requestFocus(inputRequester)

    fun focusKeyboard(index: Int) {
        val target = index.coerceIn(FIRST_ACTION_KEY_INDEX, HERO_KEYS.lastIndex)
        lastKeyIndex = target
        if (!requestFocus(keyRequesters, target)) requestFocus(keyRequesters, FIRST_ACTION_KEY_INDEX)
    }

    /** 键盘最右列 →「右侧第一排第一个元素」：有历史就是历史第一个，没有就是热搜/建议第一个。 */
    fun focusFirstChip() {
        val ok = if (history.isNotEmpty()) requestFocus(historyRequesters, 0) else false
        if (!ok) requestFocus(wordRequesters, 0)
    }

    fun focusSite(index: Int) {
        if (!hasKeyword) {
            focusInput()
            return
        }
        val target = index.coerceIn(0, HERO_SITE_TABS.lastIndex)
        focusedSite = target
        scope.launch {
            siteState.animateScrollToItem(target)
            withFrameNanos { }
            requestFocus(siteRequesters, target)
        }
    }

    fun focusPoster(index: Int) {
        if (!hasKeyword) return
        val target = index.coerceIn(0, HERO_RESULTS.lastIndex)
        scope.launch {
            // index 0..3 停靠在行首，index 4 起把目标卡滚到最左侧（与首页同一套靠左吸附规则）。
            posterState.animateScrollToItem(if (target >= POSTER_SCROLL_ANCHOR) target else 0)
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

    /** 历史最多 9 条，重复词提到最前（结构同 `xp.java` 的 a=7 分支：纯字符串列表）。 */
    fun recordHistory(text: String) {
        val value = text.trim()
        if (value.isEmpty()) return
        history = (listOf(value) + history.filterNot { it == value }).take(HISTORY_LIMIT)
    }

    /** 与 `CustomKeyboard.onTextClick` L31-36 一致：不超过 19 个字符，插入到光标位置。 */
    fun insert(text: String) {
        val value = field
        if (value.text.length >= KEYWORD_MAX_LENGTH) return
        val start = minOf(value.selection.start, value.selection.end).coerceIn(0, value.text.length)
        val end = maxOf(value.selection.start, value.selection.end).coerceIn(0, value.text.length)
        val merged = value.text.replaceRange(start, end, text)
        if (merged.length > KEYWORD_MAX_LENGTH) return
        field = TextFieldValue(text = merged, selection = TextRange(start + text.length))
    }

    fun backspace() {
        val value = field
        val start = minOf(value.selection.start, value.selection.end).coerceIn(0, value.text.length)
        val end = maxOf(value.selection.start, value.selection.end).coerceIn(0, value.text.length)
        if (start != end) {
            field = TextFieldValue(value.text.removeRange(start, end), TextRange(start))
            return
        }
        if (start == 0) return
        field = TextFieldValue(value.text.removeRange(start - 1, start), TextRange(start - 1))
    }

    fun moveCaret(delta: Int) {
        val caret = (field.selection.start + delta).coerceIn(0, field.text.length)
        field = field.copy(selection = TextRange(caret))
    }

    /** 顶部放大镜：记一条历史并把焦点交给结果区（结果区随输入实时出现，不需要额外的搜索态）。 */
    fun confirmSearch() {
        val text = keyword.trim()
        if (text.isEmpty()) {
            focusInput()
            return
        }
        recordHistory(text)
        if (hasKeyword) focusSite(0) else focusInput()
    }

    /**
     * 键盘「房子」键 → 全局接口源侧边栏（复用 [SourceSidebar]，绝不自造 Dialog）。
     *
     * `onClosed` 里延时一帧再把焦点交还「房子」键：Dialog 关闭后当前窗口尚未拿到焦点，
     * 立刻 requestFocus 会被丢掉（首页同一处也是这个处理）。
     */
    fun openSourceSidebar() {
        SourceSidebar.show(
            context = context,
            entries = HERO_SOURCE_ENTRIES,
            mode = SourceSidebarMode.Single,
            onSiteSelected = { index ->
                Notify.show(
                    context.getString(R.string.source_sidebar_switched, HERO_SOURCE_ENTRIES[index].label)
                )
            },
            onClosed = {
                scope.launch {
                    withFrameNanos { }
                    if (!requestFocus(keyRequesters, HOUSE_KEY_INDEX)) {
                        withFrameNanos { }
                        requestFocus(keyRequesters, HOUSE_KEY_INDEX)
                    }
                }
            },
        )
    }

    fun onKeyClick(key: HeroKey) {
        when (key) {
            is HeroKey.Text -> insert(key.value)

            is HeroKey.Action -> when (key.action) {
                HeroKeyAction.CursorLeft -> moveCaret(-1)
                HeroKeyAction.CursorRight -> moveCaret(1)
                HeroKeyAction.Backspace -> backspace()
                // 原版这个位置是「键盘布局切换键」；本页没有符号键盘页，改为清空输入（空键更难用）。
                HeroKeyAction.Keyboard -> field = TextFieldValue("")
                HeroKeyAction.House -> openSourceSidebar()
            }

            // 占位格不聚焦，这里只是把 when 补完整。
            else -> Unit
        }
    }

    fun onKeyMove(index: Int, key: Key): Boolean {
        val row = index / KEY_COLUMNS
        val column = index % KEY_COLUMNS
        return when (key) {
            Key.DirectionLeft -> {
                // 最左列（含第 1 行的占位格）不再左移，就地消费。
                if (column > 0 && index - 1 > PLACEHOLDER_INDEX) requestFocus(keyRequesters, index - 1)
                true
            }

            Key.DirectionRight -> {
                // 最右列 = A / H / O / V / 2 / 9：跳到右侧第一排第一个胶囊（核心四）。
                if (column == KEY_COLUMNS - 1) focusFirstChip() else requestFocus(keyRequesters, index + 1)
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

    // 输入被清空 → 结果区整块消失，此时把焦点从已销毁的结果区送回输入框。
    LaunchedEffect(hasKeyword) {
        if (!hasKeyword && resultsFocused) {
            resultsFocused = false
            withFrameNanos { }
            focusInput()
        }
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

        // ---- 根 Column：上（输入 + 键盘 + 胶囊）下（结果区）结构，见类注释 ----
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = ROOT_PADDING_H, end = ROOT_PADDING_H, top = ROOT_PADDING_TOP),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                // ================= 左上：输入框 + 键盘（activity_search.xml L17-65） =================
                Column(modifier = Modifier.width(LEFT_COLUMN_WIDTH)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Mic 36dp + marginEnd 12dp（L32-35）。语音能力未接，只做展示，不参与焦点。
                        HeroGlyphIcon(glyph = HeroGlyph.Mic, modifier = Modifier.size(MIC_SIZE))
                        Spacer(modifier = Modifier.width(MIC_MARGIN_END))
                        HeroSearchInput(
                            requester = inputRequester,
                            value = field,
                            onMoveDown = { focusKeyboard(FIRST_ACTION_KEY_INDEX) },
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(modifier = Modifier.width(SEARCH_BUTTON_MARGIN_START))
                        // 设计稿 topbar_search_page.png 的放大镜按钮：40dp 圆形，与键盘键同尺寸。
                        HeroSearchSubmit(
                            requester = searchRequester,
                            onConfirm = { confirmSearch() },
                            onMoveDown = { focusKeyboard(FIRST_ACTION_KEY_INDEX) },
                            onMoveLeft = { focusInput() },
                        )
                    }
                    // 键盘 marginTop 8dp（L61）。
                    Spacer(modifier = Modifier.height(KEYBOARD_TOP_GAP))
                    HeroKeyboard(
                        requesters = keyRequesters,
                        onFocusKey = { index -> lastKeyIndex = index },
                        onClick = { key -> onKeyClick(key) },
                        onMove = { index, key -> onKeyMove(index, key) },
                    )
                }

                Spacer(modifier = Modifier.width(LEFT_COLUMN_MARGIN_END))

                // ================= 右上：历史 + 热搜/建议（L66-143，可滚动） =================
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rightScroll),
                ) {
                    HeroWordSection(
                        title = TITLE_HISTORY,
                        words = history,
                        requesters = historyRequesters,
                        // 历史永远排在第一排；为空时第一排由热搜/建议承担。
                        isFirstRowSection = history.isNotEmpty(),
                        resultsVisible = hasKeyword,
                        onWordClick = { text ->
                            setKeyword(text)
                            recordHistory(text)
                        },
                        onLeftEdge = { focusKeyboard(lastKeyIndex) },
                        onMoveDownToResults = { focusSite(0) },
                    )
                    if (wordList.isNotEmpty()) {
                        HeroWordSection(
                            title = if (hasKeyword) TITLE_SUGGEST else TITLE_HOT,
                            words = wordList,
                            requesters = wordRequesters,
                            isFirstRowSection = history.isEmpty(),
                            resultsVisible = hasKeyword,
                            onWordClick = { text ->
                                setKeyword(text)
                                recordHistory(text)
                            },
                            onLeftEdge = { focusKeyboard(lastKeyIndex) },
                            onMoveDownToResults = { focusSite(0) },
                        )
                    }
                }
            }

            // ================= 下半区：结果区（L145-207，仅输入框有文字时出现） =================
            if (hasKeyword) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = RESULT_SECTION_TOP_GAP),
                ) {
                    HeroSiteTabs(
                        state = siteState,
                        selected = selectedSite,
                        requesters = siteRequesters,
                        onFocused = { index ->
                            focusedSite = index
                            resultsFocused = true
                        },
                        onConfirm = { index ->
                            selectedSite = index
                            focusedSite = index
                        },
                        onMoveUp = { focusFirstChip() },
                        onMoveDown = { focusPoster(0) },
                        onMoveLeft = { index -> focusSite(index - 1) },
                        onMoveRight = { index -> focusSite(index + 1) },
                    )
                    // siteRecycler marginBottom 10dp（L159）。
                    Spacer(modifier = Modifier.height(SITE_ROW_BOTTOM_GAP))
                    HeroResultRow(
                        state = posterState,
                        registry = posterViews,
                        onFocused = { resultsFocused = true },
                        onOpenDetail = { title ->
                            recordHistory(keyword)
                            onOpenDetail(title)
                        },
                        // 海报 ↑ 回到站点 Tab：落在当前焦点停留的那一项，而不是海报的下标。
                        onMoveUp = { focusSite(focusedSite) },
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
 * - 文字 24sp、字距 0.02（activity_search.xml L37、L53），hint 色 = white（L39）。
 * - 焦点态红色描边：`selector_tmdb_input.xml` 的 focused 态（仓库里只有选择器，没有对应 shape），
 *   这里按设计稿 `topbar_search_page.png` 的描边取 2dp、颜色 `#e50914`
 *   （`shape_red_bar.xml` L4 的红色）、圆角 13dp，高度与 40dp 键盘键对齐。
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
            .heightIn(min = INPUT_HEIGHT)
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

/** 顶部放大镜按钮：40dp 圆（与键盘键同尺寸），配色复用键盘键三态。 */
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
        HeroGlyphIcon(glyph = HeroGlyph.Search, modifier = Modifier.size(KEY_ICON_SIZE))
    }
}

// ---------------------------------------------------------------------------
// 左侧：虚拟键盘（7 列 × 6 行 = 42 键）
// ---------------------------------------------------------------------------

/**
 * 虚拟键盘：`ref_docs/xd1.java` L27-38 —— 6 个图标键 + A-Z + 0-9 = 42 项，正好 6 行 × 7 列；
 * 列间距 8dp（`CustomKeyboard.java` L25 `new SpaceItemDecoration(7, 8)`）。
 *
 * 第一行是 `(占位, <, >, ⌫, 键盘, 房子, A)`：第 0 格是占位（原版 `ic_keyboard_remote`），
 * 只作为视觉占位、不参与焦点；「房子」直接调 [SourceSidebar]。
 */
@Composable
private fun HeroKeyboard(
    requesters: List<FocusRequester>,
    onFocusKey: (Int) -> Unit,
    onClick: (HeroKey) -> Unit,
    onMove: (Int, Key) -> Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(KEY_SPACING)) {
        for (row in 0 until KEY_ROWS) {
            Row(horizontalArrangement = Arrangement.spacedBy(KEY_SPACING)) {
                for (column in 0 until KEY_COLUMNS) {
                    val index = row * KEY_COLUMNS + column
                    if (index == PLACEHOLDER_INDEX) {
                        HeroKeyPlaceholder()
                    } else {
                        HeroKeyCell(
                            key = HERO_KEYS[index],
                            requester = requesters[index],
                            onFocused = { onFocusKey(index) },
                            onMove = { key -> onMove(index, key) },
                            onClick = { onClick(HERO_KEYS[index]) },
                        )
                    }
                }
            }
        }
    }
}

/** 第 1 行第 1 格的占位：原版是 `ic_keyboard_remote`（4 个方块），不聚焦、无动作。 */
@Composable
private fun HeroKeyPlaceholder() {
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
 * 单键：40×40dp、16sp 白字、4dp 圆角。
 * 焦点态 = `shape_keyboard_focused`：`black_30` 底 + 1.5dp 白描边（原版 `selector_keyboard`）。
 */
@Composable
private fun HeroKeyCell(
    key: HeroKey,
    requester: FocusRequester,
    onFocused: () -> Unit,
    onMove: (Key) -> Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .size(KEY_SIZE)
            .focusRequester(requester)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
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

            // 占位格由 HeroKeyPlaceholder 单独渲染，不会走到这里。
            else -> Unit
        }
    }
}

// ---------------------------------------------------------------------------
// 右侧：区块标题 + 流式胶囊
// ---------------------------------------------------------------------------

/**
 * 区块标题：4dp × 16dp 红色竖条（`shape_red_bar.xml`：#e50914、2dp 圆角）+ 20sp 粗体白字。
 * 竖条与文字间距 10dp，整行下方 8dp（activity_search.xml L92 / L109 的 layout_marginBottom）。
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
            color = Color.White,
            fontSize = SECTION_TITLE_SP.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/**
 * 历史 / 热搜 / 建议区：`FlowRow`，胶囊 16sp、左右 14dp + 上下 8dp、28dp 圆角、6dp 外边距
 * （`adapter_search_word.xml` L3/L12 + `shape_item_round_normal.xml` L7-13）。
 * 列表整体左移 6dp，用负外边距抵消首尾 6dp —— 对应 XML L101/L118 的 `layout_marginStart="-6dp"`。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeroWordSection(
    title: String,
    words: List<String>,
    requesters: List<FocusRequester>,
    isFirstRowSection: Boolean,
    resultsVisible: Boolean,
    onWordClick: (String) -> Unit,
    onLeftEdge: () -> Unit,
    onMoveDownToResults: () -> Unit,
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
            words.forEachIndexed { index, word ->
                HeroWordChip(
                    text = word,
                    requester = requesters.getOrNull(index),
                    // 只在「第一排第一个」位置上拦截 ← / ↓，其余交给 Compose 的二维焦点搜索。
                    isFirstChip = isFirstRowSection && index == 0,
                    resultsVisible = resultsVisible,
                    onLeftEdge = onLeftEdge,
                    onMoveDownToResults = onMoveDownToResults,
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
    requester: FocusRequester?,
    isFirstChip: Boolean,
    resultsVisible: Boolean,
    onLeftEdge: () -> Unit,
    onMoveDownToResults: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    // 第一排第一个胶囊 ← → 退回键盘（核心四）。
                    Key.DirectionLeft -> {
                        if (isFirstChip) {
                            onLeftEdge()
                            true
                        } else {
                            false
                        }
                    }

                    // 胶囊 ↓ → 站点 Tab（结果区存在时）。
                    Key.DirectionDown -> {
                        if (resultsVisible) {
                            onMoveDownToResults()
                            true
                        } else {
                            false
                        }
                    }

                    else -> false
                }
            }
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
                    selected -> CHIP_BG_SELECTED
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
 * 215dp 海报行（activity_search.xml L162-206）+ 16dp 间距（`SearchActivityV2` L425）。
 *
 * 卡片只用全局唯一组件 [HeroPosterCard]（120 × 214dp，尺寸由组件自己锁死），
 * 这里通过 [AndroidView] 承载它，并把方向键交给组件自己画出来的红色描边。
 */
@Composable
private fun HeroResultRow(
    state: LazyListState,
    registry: HeroPosterViewRegistry,
    onFocused: () -> Unit,
    onOpenDetail: (String) -> Unit,
    onMoveUp: (Int) -> Unit,
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
                            if (hasFocus) onFocused()
                        }
                        card.setOnClickListener { onOpenDetail(item.title) }
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

private enum class HeroGlyph { Mic, Search, ArrowLeft, ArrowRight, Backspace, Keyboard, House, Grid }

/** 图标统一按 24dp 视觉基准取线宽 0.085 × 边长。 */
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
                // 三排按键点 + 空格条，比例参考设计稿里的键盘图标。
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

    /** 第 1 行第 1 格的占位（原版 `ic_keyboard_remote`）：不聚焦、无动作，只占位。 */
    data object Placeholder : HeroKey()

    data class Text(val value: String) : HeroKey()

    data class Action(val glyph: HeroGlyph, val action: HeroKeyAction) : HeroKey()
}

private enum class HeroKeyAction { CursorLeft, CursorRight, Backspace, Keyboard, House }

/**
 * 42 键 = 第 1 行 `(占位, <, >, ⌫, 键盘, 房子, A)` + B-Z + 0-9
 * （`xd1.java` L27/L28 的排布：6 个图标键 + 36 个字符键）。
 *
 * 下标与键盘格子一一对应：0 占位、1 `<`、2 `>`、3 `⌫`、4 键盘、5 房子、6 `A` … 41 `9`，
 * 所以最右列正好是 A / H / O / V / 2 / 9。
 */
private val HERO_KEYS: List<HeroKey> = buildList {
    add(HeroKey.Placeholder)
    add(HeroKey.Action(HeroGlyph.ArrowLeft, HeroKeyAction.CursorLeft))
    add(HeroKey.Action(HeroGlyph.ArrowRight, HeroKeyAction.CursorRight))
    add(HeroKey.Action(HeroGlyph.Backspace, HeroKeyAction.Backspace))
    add(HeroKey.Action(HeroGlyph.Keyboard, HeroKeyAction.Keyboard))
    add(HeroKey.Action(HeroGlyph.House, HeroKeyAction.House))
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
)

/** 建议词：先走精确假数据表，其余关键字从热搜 / 历史 / 结果标题里做包含匹配。 */
private val HERO_SUGGESTIONS: Map<String, List<String>> = mapOf(
    "凡人" to listOf("凡人修仙传第一季", "凡人修仙传第二季", "凡人修仙传国漫"),
)

private val HERO_SITE_TABS: List<String> = listOf("全部", "指南", "花卷", "木偶", "觅盘", "玩偶", "盘搜")

private val HERO_RESULTS: List<HeroSearchResult> = listOf(
    HeroSearchResult("凡人修仙传", "4K HDR", "更新至197集", "https://picsum.photos/seed/hero-search-0/400/600"),
    HeroSearchResult("凡人修仙传第一季", "独播", "全30集", "https://picsum.photos/seed/hero-search-1/400/600"),
    HeroSearchResult("凡人修仙传第二季", "1080P", "全24集", "https://picsum.photos/seed/hero-search-2/400/600"),
    HeroSearchResult("凡人修仙传国漫", "会员", "全120集", "https://picsum.photos/seed/hero-search-3/400/600"),
    HeroSearchResult("凡人修仙传第三季", "HDR", "更新至 12 集", "https://picsum.photos/seed/hero-search-4/400/600"),
    HeroSearchResult("战狼2", "4K", "电影", "https://picsum.photos/seed/hero-search-5/400/600"),
    HeroSearchResult("开心锤锤", "热播", "更新至 300 集", "https://picsum.photos/seed/hero-search-6/400/600"),
)

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

// 搜索按钮：40dp 圆形（与键盘键同尺寸），图标 24dp（adapter_keyboard_icon.xml L7-8）。
private val SEARCH_BUTTON_SIZE = 40.dp
private val SEARCH_BUTTON_MARGIN_START = 12.dp
private val KEY_ICON_SIZE = 24.dp

// 输入框：文字 24sp、字距 0.02（L37、L53）；红框描边 2dp / 圆角 13dp
// （selector_tmdb_input.xml 的 focused 态 + 设计稿 topbar_search_page.png）。
private const val INPUT_TEXT_SP = 24f
private const val INPUT_LETTER_SPACING = 0.02f
private val INPUT_HEIGHT = 40.dp
private val INPUT_STROKE = 2.dp
private val INPUT_SHAPE = RoundedCornerShape(13.dp)
private val INPUT_PADDING_H = 12.dp

// 键盘键三态：selector_keyboard.xml → shape_keyboard_normal/focused.xml。
private val KEY_SHAPE = RoundedCornerShape(4.dp)
private const val KEY_TEXT_SP = 16f
private val KEY_STROKE = 1.5.dp
private val KEY_BG_NORMAL = Color(0x33000000)        // @color/black_20
private val KEY_BG_FOCUSED = Color(0x4D000000)       // @color/black_30

// 区块标题：20sp 粗体白字 + 4dp × 16dp 红条、间距 10dp（shape_red_bar.xml：2dp 圆角 / #e50914）。
private const val SECTION_TITLE_SP = 20f
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
private val CHIP_BG_NORMAL = Color(0x33000000)       // @color/black_20（半透明，不是实心黑底）
private val CHIP_BG_FOCUSED = Color(0x66000000)      // @color/black_40 + 白描边
private val CHIP_BG_SELECTED = Color(0x66000000)     // @color/black_40（activated）

// 站点 Tab：adapter_type.xml L3/L10（13sp / 26dp）+ dz2.java L45-47（选中 1.08 倍、140ms）。
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

// 红色：@color/red = #e50914（shape_red_bar.xml L4）。
private val ACCENT_RED = Color(0xFFE50914)

// 输入长度上限：CustomKeyboard.onTextClick L33（> 19 直接返回）；历史最多 9 条。
private const val KEYWORD_MAX_LENGTH = 19
private const val HISTORY_LIMIT = 9

// 键盘网格：xd1.java 的 42 项 = 6 行 × 7 列；下标 0 为占位格，5 为「房子」。
private const val KEY_COLUMNS = 7
private const val KEY_ROWS = 6
private const val PLACEHOLDER_INDEX = 0
private const val FIRST_ACTION_KEY_INDEX = 1
private const val HOUSE_KEY_INDEX = 5

// 图标线稿粗细（24dp 基准）。
private const val GLYPH_STROKE_RATIO = 0.085f

// 区块标题文案（与 topbar_search_page.png 一致）。
private const val TITLE_HISTORY = "历史"
private const val TITLE_HOT = "热搜"
private const val TITLE_SUGGEST = "建议"
