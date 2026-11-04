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
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.draw.clipToBounds
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
import com.fongmi.android.tv.R
import com.fongmi.android.tv.ui.hero.HERO_SOURCE_ENTRIES
import com.fongmi.android.tv.ui.hero.HeroImageLoader
import com.fongmi.android.tv.ui.hero.HeroPosterCard
import com.fongmi.android.tv.ui.hero.SourceSidebar
import com.fongmi.android.tv.ui.hero.SourceSidebarMode
import kotlinx.coroutines.launch

/**
 * HeroUI（Compose）搜索页。
 *
 * 版式基准 = 原版截图 + `ref_docs/activity_search.xml`：**上下两层，不做左右分栏**。
 * ```
 * Column(fillMaxSize + padding(start/end=24dp, top=24dp))       // L2-11
 *   ├─ Row(fillMaxWidth + height(324dp))                          // L12-16：上半区（内容定高）
 *   │    ├─ KeyboardSection(width=328dp)                          // L17-65：输入行 36 + 8 + 键盘 280
 *   │    └─ RightContentSection(weight=1f, fillMaxHeight)         // L66-143：历史贴顶 / 建议贴底
 *   ├─ Spacer(12dp)                                               // L151 marginTop
 *   └─ if (hasInput) ResultSection(fillMaxWidth + weight(1f))     // L145-207：下半区
 *        ├─ LazyRow(height=36dp, contentPadding=24dp)             // 站点 Tab
 *        └─ Box(height=215dp) → LazyRow(fillMaxSize)              // 海报行，整条完整可见
 * ```
 *
 * 三条硬约束（历史 bug 的根因所在）：
 * 1. 根节点只有 [Column]；上半区高度 = 键盘内容高度 [TOP_SECTION_HEIGHT]（324dp，720dp 屏上不到半屏），
 *    下半区 `weight(1f)` 拿满剩余高度，两者顺序排列，**物理上不可能互相覆盖**。
 * 2. 上半区 `Row` 额外 `clipToBounds()`；左列**不做 verticalScroll**（TV 端滚动会丢遥控器焦点）。
 *    键盘 6 行按键完整撑开，末排（WXYZ / 3456789）底边与右侧「热搜 / 建议」底边严格对齐。
 * 3. 键盘用 `Column + Row` 手摆，绝不用 LazyGrid：键 `size(40.dp)`、间距 **8dp**（逆向
 *    `SpaceItemDecoration(7, 8)`），7 列 = 7×40 + 6×8 = 328dp，任何分辨率下都不会被拉伸。
 *    720dp 屏总预算：24 + 324 + 12 + (36 + 12 + 215) = 623dp ≤ 720dp（余 97dp）。
 *
 * 键盘第一行与 `ref_docs/xd1.java` L27 一致：`(占位, <, >, ⌫, 键盘, 房子, A)`，之后 B-Z、0-9，
 * 共 42 格 = 6 行 × 7 列，最右列正是 A / H / O / V / 2 / 9（与 `topbar_search_page.png` 一致）。
 *
 * 焦点契约（全部显式 [FocusRequester]）：
 * 输入框 ↓ → 键盘第一行；键盘内四向移动；**最右列 → 右侧第一排第一个胶囊**；
 * **该胶囊 ← → 回到键盘上次停留的键**；胶囊 ↓ → 站点 Tab；Tab ↓ → 首张海报；
 * 海报 ←→ 行内移动、↑ → 站点 Tab；确定键 → [HeroDetailActivity.start]。
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
                    // 卡片点击只走这一个真实入口：不碰播放器，也不碰 catvod 数据层。
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

    // 输入内容用 TextFieldValue 维护：readOnly 只吃自己的虚拟键盘，光标由键盘 < / > 驱动。
    var field by remember { mutableStateOf(TextFieldValue("")) }
    var history by remember { mutableStateOf(HERO_HISTORY.toList()) }
    var selectedSite by remember { mutableIntStateOf(0) }
    var focusedSite by remember { mutableIntStateOf(0) }
    // 键盘上次停留的键：右侧胶囊按 ← 回到这里，而不是永远回第一个键。
    var lastKeyIndex by remember { mutableIntStateOf(FIRST_KEY_INDEX) }
    // 结果区是否持有焦点：输入被清空导致结果区整体消失时，用它把焦点安全送回输入框。
    var resultsFocused by remember { mutableStateOf(false) }

    val keyword = field.text
    val hasInput = keyword.trim().isNotEmpty()
    // 三态：无输入 → 「历史 + 热搜」；有输入 → 「历史 + 建议」。
    val wordTitle = if (hasInput) TITLE_SUGGEST else TITLE_HOT
    val words = remember(keyword) {
        if (hasInput) suggestionsFor(keyword) else HERO_HOT
    }

    val inputRequester = remember { FocusRequester() }
    val searchRequester = remember { FocusRequester() }
    val keyRequesters = remember { List(HERO_KEYS.size) { FocusRequester() } }
    val historyRequesters = remember(history) { List(history.size) { FocusRequester() } }
    val wordRequesters = remember(words) { List(words.size) { FocusRequester() } }
    val siteRequesters = remember { List(HERO_SITE_TABS.size) { FocusRequester() } }
    // 「右侧第一排第一个胶囊」的固定落点：键盘最右列 → 这里；站点 Tab ↑ → 也回这里。
    val rightFirstRequester = remember { FocusRequester() }
    // 海报卡是 Android View（HeroPosterCard），Compose 的 FocusRequester 摸不到它，
    // 用一张「下标 → View」的登记表跨区交接焦点，卡片自带的 2dp 红描边才会亮。
    val posterRegistry = remember { HeroPosterViewRegistry() }

    val siteState = rememberLazyListState()
    val posterState = rememberLazyListState()

    // 焦点回到输入框/键盘 = 离开结果区，清掉标记，避免之后清空输入时把焦点从键盘抢走。
    fun focusInput() {
        resultsFocused = false
        requestFocus(inputRequester)
    }

    fun focusKeyboard(index: Int) {
        resultsFocused = false
        val target = index.coerceIn(FIRST_KEY_INDEX, HERO_KEYS.lastIndex)
        lastKeyIndex = target
        if (!requestFocus(keyRequesters, target)) requestFocus(keyRequesters, FIRST_KEY_INDEX)
    }

    /** 键盘最右列 → 右侧第一排第一个胶囊（历史优先，其次热搜/建议）。 */
    fun focusRightFirstChip() {
        runCatching { rightFirstRequester.requestFocus() }
    }

    fun focusSite(index: Int) {
        if (!hasInput) {
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
        if (!hasInput) return
        val target = index.coerceIn(0, HERO_RESULTS.lastIndex)
        scope.launch {
            posterState.animateScrollToItem(if (target >= POSTER_SCROLL_ANCHOR) target else 0)
            withFrameNanos { }
            if (!posterRegistry.requestFocus(target)) {
                withFrameNanos { }
                posterRegistry.requestFocus(target)
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

    /** 与 `CustomKeyboard.onTextClick` L31-36 一致：总长不超过 19，插入到光标处。 */
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

    /** 顶部放大镜：记一条历史，焦点交给结果区（结果区随输入实时出现，没有额外搜索态）。 */
    fun confirmSearch() {
        val text = keyword.trim()
        if (text.isEmpty()) {
            focusInput()
            return
        }
        recordHistory(text)
        focusSite(0)
    }

    /**
     * 小房子键 → 全局接口源侧边栏（复用 [SourceSidebar]，绝不自造 Dialog）。
     *
     * 按需求用 [SourceSidebarMode.Multi]：带勾选圈与「全选 / 全不选」，
     * 确定键 = 切换勾选（不关闭），返回键关闭后由 `onClosed` 把焦点交还房子键。
     */
    fun openSourceSidebar() {
        SourceSidebar.show(
            context = context,
            entries = HERO_SOURCE_ENTRIES,
            mode = SourceSidebarMode.Multi,
            initialChecked = BooleanArray(HERO_SOURCE_ENTRIES.size) { true },
            onCheckedChanged = { },
            onSiteSelected = { },
            onClosed = {
                // Dialog 关闭后当前窗口尚未拿到焦点，延时一帧再 requestFocus 才不会被丢。
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
                // 原版这个位置是「键盘布局切换键」；本页没有符号键盘页，改为清空输入。
                HeroKeyAction.Keyboard -> field = TextFieldValue("")
                HeroKeyAction.House -> openSourceSidebar()
            }

            // 占位格不聚焦，由 HeroKeyPlaceholder 单独渲染，走不到这里。
            else -> Unit
        }
    }

    fun onKeyMove(index: Int, key: Key): Boolean {
        val row = index / KEY_COLUMNS
        val column = index % KEY_COLUMNS
        return when (key) {
            Key.DirectionLeft -> {
                // 最左列（含第一行占位格右侧的 `<`）不再左移，就地消费。
                if (column > 0 && index - 1 > PLACEHOLDER_INDEX) requestFocus(keyRequesters, index - 1)
                true
            }

            Key.DirectionRight -> {
                // 最右列 = A / H / O / V / 2 / 9：拦截并跳到右侧第一排第一个胶囊。
                if (column == KEY_COLUMNS - 1) {
                    focusRightFirstChip()
                } else {
                    requestFocus(keyRequesters, index + 1)
                }
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
    LaunchedEffect(hasInput) {
        if (!hasInput && resultsFocused) {
            resultsFocused = false
            withFrameNanos { }
            focusInput()
        }
    }

    // ------------------------------------------------------------------
    // 骨架 1：上下结构。根节点只有 Column，没有 Box 叠加。
    // ------------------------------------------------------------------
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0.0f to Color(0xFF0B1119),
                    0.55f to Color(0xFF101823),
                    1.0f to Color.Black,
                ),
            )
            .padding(start = ROOT_PADDING_H, end = ROOT_PADDING_H, top = ROOT_PADDING_TOP),
    ) {
        // 上半区：高度由键盘自身内容决定（输入行 36 + 8 + 6×40 + 5×8 = 324dp），
        // 只占屏幕约 45%，绝不吃 weight、绝不会被下半区挤压。
        // 右侧历史/建议 fillMaxHeight + SpaceBetween 贴着同一条底线 → 末排按键与「建议」底边对齐。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(TOP_SECTION_HEIGHT)
                .clipToBounds(),
        ) {
            KeyboardSection(
                field = field,
                inputRequester = inputRequester,
                searchRequester = searchRequester,
                keyRequesters = keyRequesters,
                onInputDown = { focusKeyboard(FIRST_KEY_INDEX) },
                onSearch = { confirmSearch() },
                onSearchDown = { focusKeyboard(FIRST_KEY_INDEX) },
                onSearchLeft = { focusInput() },
                onKeyFocused = { index -> lastKeyIndex = index },
                onKeyClick = { key -> onKeyClick(key) },
                onKeyMove = { index, key -> onKeyMove(index, key) },
            )

            Spacer(modifier = Modifier.width(LEFT_COLUMN_MARGIN_END))

            RightContentSection(
                history = history,
                words = words,
                wordTitle = wordTitle,
                hasInput = hasInput,
                historyRequesters = historyRequesters,
                wordRequesters = wordRequesters,
                rightFirstRequester = rightFirstRequester,
                onWordClick = { text ->
                    setKeyword(text)
                    recordHistory(text)
                },
                onLeftEdge = { focusKeyboard(lastKeyIndex) },
                onDownToResults = { focusSite(0) },
            )
        }

        Spacer(modifier = Modifier.height(RESULT_SECTION_TOP_GAP))

        // 下半区：weight(1f) 吃掉上半区之外的全部高度（720dp 屏上约 360dp），
        // 内部固定 36dp Tab 行 + 12dp 间隔 + 215dp 海报带 = 263dp，整条海报永远完整可见。
        if (hasInput) {
            ResultSection(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                siteState = siteState,
                posterState = posterState,
                selectedSite = selectedSite,
                siteRequesters = siteRequesters,
                posterRegistry = posterRegistry,
                onSiteFocused = { index ->
                    focusedSite = index
                    resultsFocused = true
                },
                onSiteConfirm = { index ->
                    selectedSite = index
                    focusedSite = index
                },
                onSiteUp = { focusRightFirstChip() },
                onSiteDown = { focusPoster(0) },
                onSiteLeft = { index -> focusSite(index - 1) },
                onSiteRight = { index -> focusSite(index + 1) },
                onPosterFocused = { resultsFocused = true },
                onPosterOpen = { title ->
                    recordHistory(keyword)
                    onOpenDetail(title)
                },
                onPosterUp = { focusSite(focusedSite) },
                onPosterLeft = { index -> if (index > 0) focusPoster(index - 1) },
                onPosterRight = { index ->
                    if (index < HERO_RESULTS.lastIndex) focusPoster(index + 1)
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 骨架 2：左侧键盘区（328dp = 7×40 + 6×8，Column + Row 手摆，绝不用 LazyGrid）
// ---------------------------------------------------------------------------

/**
 * 左列：输入框行 + 虚拟键盘。
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
 * 高度不写死：`wrapContentHeight()` 由 6 行按键撑开（6×40 + 5×8 = 280dp），
 * 加输入框行 36dp 与 8dp 间隔 = 324dp = [TOP_SECTION_HEIGHT]；父级 `Row` 用的就是这个高度，
 * 所以最后一排（WXYZ / 3456789）绝不会被裁，且与右侧「热搜 / 建议」底边齐平。
 */
@Composable
private fun KeyboardSection(
    field: TextFieldValue,
    inputRequester: FocusRequester,
    searchRequester: FocusRequester,
    keyRequesters: List<FocusRequester>,
    onInputDown: () -> Unit,
    onSearch: () -> Unit,
    onSearchDown: () -> Unit,
    onSearchLeft: () -> Unit,
    onKeyFocused: (Int) -> Unit,
    onKeyClick: (HeroKey) -> Unit,
    onKeyMove: (Int, Key) -> Boolean,
) {
    Column(
        modifier = Modifier
            .width(LEFT_COLUMN_WIDTH)
            // 高度不写死：不再 fillMaxHeight，由 6 行按键自然撑开，
            // 最后一排不会被父级的固定高度压掉。
            .wrapContentHeight(),
        verticalArrangement = Arrangement.spacedBy(KEY_SPACING),
    ) {
        // 输入框行：mic 36dp + 12dp + 只读输入框(weight 1f) + 12dp + 搜索 36dp（整行 36dp）
        Row(verticalAlignment = Alignment.CenterVertically) {
            HeroGlyphIcon(glyph = HeroGlyph.Mic, modifier = Modifier.size(MIC_SIZE))
            Spacer(modifier = Modifier.width(MIC_MARGIN_END))
            HeroSearchInput(
                requester = inputRequester,
                value = field,
                onMoveDown = onInputDown,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(SEARCH_BUTTON_MARGIN_START))
            HeroSearchSubmit(
                requester = searchRequester,
                onConfirm = onSearch,
                onMoveDown = onSearchDown,
                onMoveLeft = onSearchLeft,
            )
        }

        // 键盘：6 行 × 7 列 = 42 格，每格 40dp 写死，行/列间距 8dp（SpaceItemDecoration(7, 8)）。
        Column(verticalArrangement = Arrangement.spacedBy(KEY_SPACING)) {
            for (row in 0 until KEY_ROWS) {
                Row(horizontalArrangement = Arrangement.spacedBy(KEY_SPACING)) {
                    for (column in 0 until KEY_COLUMNS) {
                        val index = row * KEY_COLUMNS + column
                        if (index == PLACEHOLDER_INDEX) {
                            HeroKeyPlaceholder()
                        } else {
                            KeyboardKey(
                                key = HERO_KEYS[index],
                                requester = keyRequesters[index],
                                onFocused = { onKeyFocused(index) },
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
 * 单个按键：**严格 40 × 40dp**（`adapter_keyboard_text.xml` L10），4dp 圆角、16sp 白字。
 *
 * 焦点反馈（本版重点）：
 * - 常态 `0x33FFFFFF`（半透明白），焦点 **纯红 `0xFFFF0000`**，方向键在键盘内移动时一定看得见；
 * - 蓝色/白色描边都不加在底色上：红底之外只叠 1.5dp 白描边，保证纯红仍是最强信号；
 * - `collectIsFocusedAsState()` 是主信号，`onFocusChanged` 只做兜底（极端机型上交互源可能不派发），
 *   两者取或，绝不会出现「焦点移动了但没有背景」。
 */
@Composable
private fun KeyboardKey(
    key: HeroKey,
    requester: FocusRequester,
    onFocused: () -> Unit,
    onMove: (Key) -> Boolean,
    onKey: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val isFocused by interaction.collectIsFocusedAsState()
    var focusFlag by remember { mutableStateOf(false) }
    val focused = isFocused || focusFlag
    val bgColor = if (focused) KEY_BG_FOCUSED else KEY_BG_NORMAL

    Box(
        modifier = Modifier
            .size(KEY_SIZE)                       // 40dp 写死，任何区域都不会被拉伸
            .clip(KEY_SHAPE)                      // 4dp
            .background(bgColor, KEY_SHAPE)       // 红底焦点
            .focusRequester(requester)
            .onFocusChanged {
                focusFlag = it.isFocused
                if (it.isFocused) onFocused()
            }
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

// ---------------------------------------------------------------------------
// 左侧：搜索输入框 + 放大镜按钮
// ---------------------------------------------------------------------------

/**
 * 只读搜索框（禁用系统输入法）。
 *
 * - 文字 24sp、字距 0.02（`activity_search.xml` L37/L53），hint 白（L39）；
 * - 焦点态 2dp 红框（`selector_tmdb_input.xml` 的 focused 态 + `topbar_search_page.png`）；
 * - 高度压到 36dp（与 mic 同高，原版 24sp 文字放得下），圆角 13dp。
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

/** 放大镜按钮：36dp 圆（与输入框、mic 同高，压缩上半区），配色复用键盘键两态。 */
@Composable
private fun HeroSearchSubmit(
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
            .background(if (focused) KEY_BG_FOCUSED else KEY_BG_NORMAL, CircleShape)
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
// 右侧：历史 + 热搜/建议
// ---------------------------------------------------------------------------

/**
 * 右列：历史 + 热搜（无输入）/ 建议（有输入）。
 *
 * 自己是 `RowScope` 扩展，所以 `weight(1f)` 是合法的。
 * `fillMaxHeight()` + `Arrangement.SpaceBetween`：整列高度 = 上半区高度（[TOP_SECTION_HEIGHT]），
 * 「历史」贴顶、最后一块（热搜 / 建议）贴底 → **它的底边和键盘最后一排按键的底边在同一条水平线**。
 * 因此这里不能再挂 `verticalScroll`（滚动后底边会漂移，对齐就失效了）。
 */
@Composable
private fun RowScope.RightContentSection(
    history: List<String>,
    words: List<String>,
    wordTitle: String,
    hasInput: Boolean,
    historyRequesters: List<FocusRequester>,
    wordRequesters: List<FocusRequester>,
    rightFirstRequester: FocusRequester,
    onWordClick: (String) -> Unit,
    onLeftEdge: () -> Unit,
    onDownToResults: () -> Unit,
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight(),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        HeroWordSection(
            title = TITLE_HISTORY,
            words = history,
            requesters = historyRequesters,
            // 历史非空时，「右侧第一排第一个」就是历史第一条。
            firstRowRequester = if (history.isNotEmpty()) rightFirstRequester else null,
            isFirstRowSection = history.isNotEmpty(),
            resultsVisible = hasInput,
            onWordClick = onWordClick,
            onLeftEdge = onLeftEdge,
            onDownToResults = onDownToResults,
        )
        if (words.isNotEmpty()) {
            HeroWordSection(
                title = wordTitle,
                words = words,
                requesters = wordRequesters,
                firstRowRequester = if (history.isEmpty()) rightFirstRequester else null,
                isFirstRowSection = history.isEmpty(),
                resultsVisible = hasInput,
                onWordClick = onWordClick,
                onLeftEdge = onLeftEdge,
                onDownToResults = onDownToResults,
            )
        }
    }
}

/**
 * 区块标题：4dp × 16dp 红条（`shape_red_bar.xml`：#e50914、2dp 圆角）+ 20sp 粗体白字，
 * 条与文字间距 10dp，整行下方 8dp（`activity_search.xml` L92/L109）。
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
 * 历史 / 热搜 / 建议区：`FlowRow`，胶囊 16sp、内边距 14dp×8dp、28dp 圆角、外边距 6dp
 * （`adapter_search_word.xml` L3/L12 + `shape_item_round_*.xml`）。
 * 整体左移 6dp 抵消首尾外边距，对应 XML 的 `layout_marginStart="-6dp"`。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeroWordSection(
    title: String,
    words: List<String>,
    requesters: List<FocusRequester>,
    // 只有「第一排第一个胶囊」拿得到它：键盘最右列直接打到这里。
    firstRowRequester: FocusRequester?,
    isFirstRowSection: Boolean,
    resultsVisible: Boolean,
    onWordClick: (String) -> Unit,
    onLeftEdge: () -> Unit,
    onDownToResults: () -> Unit,
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
                val isFirstChip = isFirstRowSection && index == 0
                HeroWordChip(
                    text = word,
                    requester = if (isFirstChip) {
                        firstRowRequester ?: requesters.getOrNull(index)
                    } else {
                        requesters.getOrNull(index)
                    },
                    isFirstChip = isFirstChip,
                    resultsVisible = resultsVisible,
                    onLeftEdge = onLeftEdge,
                    onDownToResults = onDownToResults,
                    onClick = { onWordClick(word) },
                    modifier = Modifier.padding(CHIP_MARGIN),
                )
            }
        }
    }
}

/**
 * 胶囊标签：**不铺纯黑**，底色沿用 `selector_item_round.xml` 的半透明黑；
 * 焦点态额外加 **1.5dp 纯红描边**（本版要求的焦点反馈），保证在暗背景上一眼可见。
 */
@Composable
private fun HeroWordChip(
    text: String,
    requester: FocusRequester?,
    isFirstChip: Boolean,
    resultsVisible: Boolean,
    onLeftEdge: () -> Unit,
    onDownToResults: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val isFocused by interaction.collectIsFocusedAsState()
    var focusFlag by remember { mutableStateOf(false) }
    val focused = isFocused || focusFlag

    Box(
        modifier = modifier
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .clip(CHIP_SHAPE)
            .background(if (focused) CHIP_BG_FOCUSED else CHIP_BG_NORMAL, CHIP_SHAPE)
            .then(if (focused) Modifier.border(CHIP_STROKE, FOCUS_RED, CHIP_SHAPE) else Modifier)
            .onFocusChanged { focusFlag = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionCenter, Key.Enter -> {
                        onClick()
                        true
                    }

                    // 第一排第一个胶囊 ← → 退回键盘（回到上次停留的键）。
                    Key.DirectionLeft -> if (isFirstChip) {
                        onLeftEdge()
                        true
                    } else {
                        false
                    }

                    // 胶囊 ↓ → 站点 Tab（结果区存在时）。
                    Key.DirectionDown -> if (resultsVisible) {
                        onDownToResults()
                        true
                    } else {
                        false
                    }

                    else -> false
                }
            }
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
// 骨架 3：下半区（站点 Tab + 海报行）
// ---------------------------------------------------------------------------

/**
 * 结果区（下半区）：`fillMaxWidth + weight(1f)`，占满上半区之外的全部高度
 * （720dp 屏约 360dp），内部内容固定 36dp Tab 行 + 12dp 间隔 + 215dp 海报带 = 263dp，
 * 所以整条海报永远完整显示，不会被裁掉一截。
 *
 * 两条 LazyRow 的 `contentPadding` 取 [RESULT_CONTENT_PADDING] = 24dp：
 * 站点 Tab 与海报行各自再让出 24dp 呼吸空间，第一个「全部」不会贴着屏幕边缘，
 * 焦点态的 1.08 放大与红描边也完整落在可视区内。
 *
 * 关于 `clipToPadding(false)`：这是 View 的 `ViewGroup` 参数；Compose 的 `LazyRow` 只按
 * 自身外框裁剪、**不按 contentPadding 裁剪**，所以这里的 24dp 内容内边距天然等价于
 * View 侧 `padding + clipToPadding=false` 的效果（原版 `activity_search.xml` L154-156/L170-171
 * 正是 `paddingBottom=2dp` + `clipChildren=false` + `clipToPadding=false`）。
 */
@Composable
private fun ResultSection(
    modifier: Modifier = Modifier,
    siteState: LazyListState,
    posterState: LazyListState,
    selectedSite: Int,
    siteRequesters: List<FocusRequester>,
    posterRegistry: HeroPosterViewRegistry,
    onSiteFocused: (Int) -> Unit,
    onSiteConfirm: (Int) -> Unit,
    onSiteUp: () -> Unit,
    onSiteDown: () -> Unit,
    onSiteLeft: (Int) -> Unit,
    onSiteRight: (Int) -> Unit,
    onPosterFocused: () -> Unit,
    onPosterOpen: (String) -> Unit,
    onPosterUp: (Int) -> Unit,
    onPosterLeft: (Int) -> Unit,
    onPosterRight: (Int) -> Unit,
) {
    Column(modifier = modifier) {
        // 站点 Tab：行高 36dp，胶囊本体 26dp（adapter_type.xml L10）垂直居中。
        LazyRow(
            state = siteState,
            modifier = Modifier
                .fillMaxWidth()
                .height(SITE_ROW_HEIGHT),
            contentPadding = PaddingValues(
                start = RESULT_CONTENT_PADDING,
                end = RESULT_CONTENT_PADDING,
            ),
            horizontalArrangement = Arrangement.spacedBy(SITE_ITEM_SPACING),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            itemsIndexed(items = HERO_SITE_TABS) { index, label ->
                HeroSiteTab(
                    label = label,
                    selected = index == selectedSite,
                    requester = siteRequesters[index],
                    onFocused = { onSiteFocused(index) },
                    onConfirm = { onSiteConfirm(index) },
                    onMoveUp = onSiteUp,
                    onMoveDown = onSiteDown,
                    onMoveLeft = { onSiteLeft(index) },
                    onMoveRight = { onSiteRight(index) },
                )
            }
        }

        Spacer(modifier = Modifier.height(SITE_POSTER_GAP))

        // 海报行：高度锁死 215dp（activity_search.xml L167）。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(POSTER_AREA_HEIGHT),
        ) {
            LazyRow(
                state = posterState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = RESULT_CONTENT_PADDING,
                    end = RESULT_CONTENT_PADDING,
                ),
                horizontalArrangement = Arrangement.spacedBy(POSTER_ITEM_SPACING),
            ) {
                itemsIndexed(items = HERO_RESULTS) { index, item ->
                    // 【红线】HeroPosterCard 自己 onMeasure 死锁 120×214dp，
                    // 这里绝不加 Modifier.width()/size()，直接放进去。
                    AndroidView(
                        factory = { context ->
                            HeroPosterCard(context).also { card -> posterRegistry.bind(index, card) }
                        },
                        update = { card ->
                            // 只在值变化时写，避免每次重组都触发 HeroPosterCard 内部的图片重载。
                            if (card.title != item.title) card.title = item.title
                            if (card.topStartTag != item.topTag) card.topStartTag = item.topTag
                            if (card.bottomStartTag != item.bottomTag) card.bottomStartTag = item.bottomTag
                            if (card.imageUrl != item.imageUrl) card.imageUrl = item.imageUrl

                            card.setOnFocusChangeListener { _, hasFocus ->
                                if (hasFocus) onPosterFocused()
                            }
                            card.setOnClickListener { onPosterOpen(item.title) }
                            card.setOnKeyListener { _, keyCode, event ->
                                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                                when (keyCode) {
                                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                                        onPosterLeft(index)
                                        true
                                    }

                                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                        onPosterRight(index)
                                        true
                                    }

                                    KeyEvent.KEYCODE_DPAD_UP -> {
                                        onPosterUp(index)
                                        true
                                    }

                                    else -> false
                                }
                            }
                        },
                        onRelease = { card -> posterRegistry.unbind(index, card) },
                    )
                }
            }
        }
    }
}

/**
 * 站点胶囊：26dp 高、13sp、13dp 圆角（`adapter_type.xml` L3/L10）；
 * 选中放大 1.08 / 140ms（`dz2.java` L45-47）；焦点态红描边 + 提亮底色。
 */
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
    val interaction = remember { MutableInteractionSource() }
    val isFocused by interaction.collectIsFocusedAsState()
    var focusFlag by remember { mutableStateOf(false) }
    val focused = isFocused || focusFlag
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
            .clip(SITE_TAB_SHAPE)
            .background(
                when {
                    focused -> CHIP_BG_FOCUSED
                    selected -> CHIP_BG_SELECTED
                    else -> CHIP_BG_NORMAL
                },
                SITE_TAB_SHAPE,
            )
            .then(if (focused) Modifier.border(CHIP_STROKE, FOCUS_RED, SITE_TAB_SHAPE) else Modifier)
            .onFocusChanged {
                focusFlag = it.isFocused
                if (it.isFocused) onFocused()
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionCenter, Key.Enter -> {
                        onConfirm()
                        true
                    }

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

// ---------------------------------------------------------------------------
// 图标（main 源集没有 mic / search / keyboard 矢量，用 Canvas 画，不新增资源文件）
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

/** 在已挂载的 FocusRequester 列表里安全请求焦点：未组合的 Lazy 项会抛异常，这里降级返回 false。 */
private fun requestFocus(requesters: List<FocusRequester>, index: Int): Boolean {
    val requester = requesters.getOrNull(index) ?: return false
    return requestFocus(requester)
}

private fun requestFocus(requester: FocusRequester): Boolean =
    runCatching { requester.requestFocus() }.isSuccess

// ---------------------------------------------------------------------------
// 假数据（数据层未接，先验证结构与焦点；结构同 xp.java 的 a=7 分支：纯字符串列表）
// ---------------------------------------------------------------------------

private data class HeroSearchResult(
    val title: String,
    val topTag: String,
    val bottomTag: String,
    val imageUrl: String,
)

private sealed class HeroKey {

    /** 第一行第一格占位（原版 `ic_keyboard_remote`）：不聚焦、无动作。 */
    data object Placeholder : HeroKey()

    data class Text(val value: String) : HeroKey()

    data class Action(val glyph: HeroGlyph, val action: HeroKeyAction) : HeroKey()
}

private enum class HeroKeyAction { CursorLeft, CursorRight, Backspace, Keyboard, House }

/**
 * 42 格 = 第一行 `(占位, <, >, ⌫, 键盘, 房子, A)` + B-Z + 0-9
 * （`xd1.java` L27/L28：6 个图标键 + 36 个字符键）。
 *
 * 下标与格子一一对应：0 占位、1 `<`、2 `>`、3 `⌫`、4 键盘、5 房子、6 `A` … 41 `9`，
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

/** 建议词：先走精确假数据表，其余关键字从热搜 / 历史 / 结果标题做包含匹配。 */
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

// 根容器：activity_search.xml L4/L10/L11 → paddingTop 24dp、paddingStart/End 24dp。
private val ROOT_PADDING_H = 24.dp
private val ROOT_PADDING_TOP = 24.dp

// 键盘（逐条对应逆向数据，不是估值）：
//   键 40dp × 40dp   adapter_keyboard_text.xml L5-6 / adapter_keyboard_icon.xml L7-8
//   间距 8dp        CustomKeyboard.java L25 `new SpaceItemDecoration(7, 8)`（SpaceItemDecoration L22 dp2px）
//   容器 wrap_content activity_search.xml L59-60，原版无固定宽度；宽度 = 7×40 + 6×8 = 328dp
private val KEY_SIZE = 40.dp
private val KEY_SPACING = 8.dp
private val LEFT_COLUMN_WIDTH = 328.dp
private val LEFT_COLUMN_MARGIN_END = 24.dp          // activity_search.xml L21

// 输入框行：mic 36dp + 间距 12dp；搜索按钮与输入框同高 36dp（压缩上半区高度）；图标 24dp。
private val MIC_SIZE = 36.dp
private val MIC_MARGIN_END = 12.dp
private val SEARCH_BUTTON_SIZE = 36.dp
private val SEARCH_BUTTON_MARGIN_START = 12.dp
private val KEY_ICON_SIZE = 24.dp

// 输入框：24sp、字距 0.02（L37/L53）；红框 2dp、圆角 13dp；高度压到 36dp 与 mic 对齐。
private const val INPUT_TEXT_SP = 24f
private const val INPUT_LETTER_SPACING = 0.02f
private val INPUT_HEIGHT = 36.dp
private val INPUT_STROKE = 2.dp
private val INPUT_SHAPE = RoundedCornerShape(13.dp)
private val INPUT_PADDING_H = 12.dp

// 上半区总高 = 输入行 36 + 8(activity_search.xml L61 的 marginTop) + 6×40 + 5×8 = 324dp。
// 720dp 屏上 24 + 324 = 348dp ≈ 48%，不超过半屏；下半区 weight(1f) 拿满剩下的 372dp。
private val TOP_SECTION_HEIGHT = INPUT_HEIGHT + KEY_SPACING + KEY_SIZE * 6 + KEY_SPACING * 5

// 键盘键：40dp / 16sp / 4dp 圆角（adapter_keyboard_text.xml）。
private val KEY_SHAPE = RoundedCornerShape(4.dp)
private const val KEY_TEXT_SP = 16f
private val KEY_BG_NORMAL = Color(0x33FFFFFF)
private val KEY_BG_FOCUSED = Color(0xFFFF0000)

// 焦点红（键盘红底、胶囊/Tab 描边共用）。
private val FOCUS_RED = Color(0xFFFF0000)

// 区块标题：20sp 粗体白 + 4×16dp 红条（shape_red_bar.xml：#e50914、2dp 圆角）+ 10dp 间距。
private const val SECTION_TITLE_SP = 20f
private val SECTION_BAR_WIDTH = 4.dp
private val SECTION_BAR_HEIGHT = 16.dp
private val SECTION_BAR_MARGIN_END = 10.dp
private val SECTION_BAR_SHAPE = RoundedCornerShape(2.dp)
private val SECTION_TITLE_BOTTOM_GAP = 8.dp

// 胶囊：16sp、14dp×8dp 内边距、28dp 圆角、6dp 外边距（adapter_search_word.xml + shape_item_round_*）。
private const val CHIP_TEXT_SP = 16f
private val CHIP_PADDING_H = 14.dp
private val CHIP_PADDING_V = 8.dp
private val CHIP_MARGIN = 6.dp
private val CHIP_SHAPE = RoundedCornerShape(percent = 50)
private val CHIP_STROKE = 1.5.dp
private val CHIP_BG_NORMAL = Color(0x33000000)      // black_20（半透明，不是纯黑）
private val CHIP_BG_FOCUSED = Color(0x66000000)     // black_40 + 红描边
private val CHIP_BG_SELECTED = Color(0x66000000)    // activated

// 站点 Tab：13sp / 26dp（adapter_type.xml）+ 行高 36dp + 12dp 间距 + 24dp 内容内边距。
private const val SITE_TAB_SP = 13f
private val SITE_TAB_HEIGHT = 26.dp
private val SITE_TAB_SHAPE = RoundedCornerShape(13.dp)
private const val SITE_SELECTED_SCALE = 1.08f
private const val SITE_SCALE_DURATION_MS = 140
private val SITE_ROW_HEIGHT = 36.dp
private val SITE_ITEM_SPACING = 12.dp
private val SITE_POSTER_GAP = 12.dp
private val RESULT_CONTENT_PADDING = 24.dp          // 站点 Tab / 海报行统一 24dp 呼吸空间，第一个「全部」不再贴边
private val RESULT_SECTION_TOP_GAP = 12.dp         // activity_search.xml L151

// 海报行：215dp（activity_search.xml L167）+ 12dp 间距。
private val POSTER_AREA_HEIGHT = 215.dp
private val POSTER_ITEM_SPACING = 12.dp
private const val POSTER_SCROLL_ANCHOR = 4

// 红色：@color/red = #e50914（shape_red_bar.xml L4）。
private val ACCENT_RED = Color(0xFFE50914)

// 输入上限 19 字（CustomKeyboard.onTextClick L33）；历史最多 9 条。
private const val KEYWORD_MAX_LENGTH = 19
private const val HISTORY_LIMIT = 9

// 键盘网格：42 格 = 6 行 × 7 列；0 占位、5 房子。
private const val KEY_COLUMNS = 7
private const val KEY_ROWS = 6
private const val PLACEHOLDER_INDEX = 0
private const val FIRST_KEY_INDEX = 1
private const val HOUSE_KEY_INDEX = 5

// 图标线稿粗细（24dp 基准）。
private const val GLYPH_STROKE_RATIO = 0.085f

// 区块标题文案（与 topbar_search_page.png 一致）。
private const val TITLE_HISTORY = "历史"
private const val TITLE_HOT = "热搜"
private const val TITLE_SUGGEST = "建议"
