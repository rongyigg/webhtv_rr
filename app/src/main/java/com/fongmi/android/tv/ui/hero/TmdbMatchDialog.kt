package com.fongmi.android.tv.ui.hero

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fongmi.android.tv.R
import kotlinx.coroutines.launch

/**
 * TMDB 手动匹配弹窗（Compose，**第一段：骨架 + 假结果**）。
 *
 * 版式基准 = `ref_docs/dialog_tmdb_match.xml`（原版 `TmdbMatchDialog` / `pc3`）：
 * ```
 * LinearLayout(padding 18dp, wrap_content)                       // = [HeroDialogShell]
 * ├─ Row(height 24dp, marginBottom 12dp)                         // L7-45：顶部行
 * │    ├─ TextView 11sp bold 白 + 4dp 圆角徽标 + paddingH 10dp    // L13-24 tmdbBadge
 * │    ├─ (suggestion：第一段不做联想，占位)                       // L25-34
 * │    └─ (seasonTag：原版默认 gone，第一段隐藏)                   // L35-44
 * └─ Row                                                         // L46-159
 *      ├─ Column(键盘 328dp = 7×40 + 6×8)                         // L50-99：输入行 40 + 键盘
 *      └─ Column(weight 1f, marginStart 18dp)                     // L100-158：结果标题 + 结果区
 *           ├─ Row：3×14dp 红条 + 13sp 标题 + 11sp 计数            // L106-130
 *           └─ Box(height = 卡高 214dp)：空态文案 / LazyRow        // L131-157
 * ```
 *
 * 三条硬约束：
 * 1. **结果卡只允许用全局唯一组件 [HeroPosterCard]**（120×214dp、四角 8dp 全圆、
 *    2dp 纯红焦点框只包 120×180 海报、34dp 剧名栏）。`AndroidView` 里**绝不加任何尺寸 Modifier**，
 *    容器高度直接取 [HeroPosterCard.CARD_HEIGHT_DP]，否则会把 34dp 剧名栏裁掉。
 * 2. **键盘不复制第二份**：直接复用 `ui/hero/HeroKeyboard.kt` 的 [KeyboardSection]
 *    （输入框行 + 6 行 × 7 列键位），左列宽度由键盘自然撑到 328dp，不写死原版的 217dp。
 * 3. **第一段零网络**：结果全部来自 [TMDB_MOCK_RESULTS]，放大镜按钮只按关键字过滤这份假数据。
 *    TODO(第二段)：`TmdbApi.searchMulti(keyword)` 真实请求 + Gson 解析 + API Key 输入。
 *
 * 焦点契约：
 * - 进弹窗 → 输入框；输入框 / 放大镜 ↓ → 键盘第一行；
 * - 键盘最后一行 ↓ → 结果区第一张卡；结果卡 ↑ → 键盘第一行；
 * - 键盘最右列 → 不消费（交原生 2D 焦点搜索）；返回键 → 关闭弹窗，焦点由首页还回长按的那张卡。
 */
@Composable
internal fun TmdbMatchDialog(
    initialKeyword: String,
    onConfirm: (TmdbMatchItem) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    // 输入内容用 TextFieldValue 维护：输入框 readOnly，只吃弹窗自己的虚拟键盘，
    // 光标由键盘 < / > 驱动（与搜索页完全同一套交互）。
    var field by remember {
        mutableStateOf(TextFieldValue(initialKeyword, TextRange(initialKeyword.length)))
    }
    var results by remember { mutableStateOf(TMDB_MOCK_RESULTS) }
    var resultCount by remember { mutableIntStateOf(TMDB_MOCK_RESULTS.size) }

    val inputRequester = remember { FocusRequester() }
    val searchRequester = remember { FocusRequester() }
    val keyRequesters = remember { List(HERO_KEYS.size) { FocusRequester() } }
    // 结果卡是 Android View，Compose 的 FocusRequester 摸不到它，
    // 用登记表做「下标 → View」的焦点交接，卡片自带的 2dp 红描边才会亮。
    val resultRegistry = remember { HeroPosterViewRegistry() }
    val resultState = rememberLazyListState()

    fun focusInput() {
        requestFocus(inputRequester)
    }

    fun focusKeyboard(index: Int) {
        val target = index.coerceIn(FIRST_KEY_INDEX, HERO_KEYS.lastIndex)
        if (!requestFocus(keyRequesters, target)) requestFocus(keyRequesters, FIRST_KEY_INDEX)
    }

    fun focusResult(index: Int) {
        if (results.isEmpty()) return
        val target = index.coerceIn(0, results.lastIndex)
        scope.launch {
            resultState.animateScrollToItem(target)
            withFrameNanos { }
            if (!resultRegistry.requestFocus(target)) {
                withFrameNanos { }
                resultRegistry.requestFocus(target)
            }
        }
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

    /**
     * 放大镜按钮：第一段只过滤本地假数据，**不发任何网络请求**。
     * 关键字为空 = 恢复全部；否则按剧名包含匹配（忽略大小写）。
     */
    fun applyFilter() {
        val keyword = field.text.trim()
        if (keyword.isEmpty()) {
            results = TMDB_MOCK_RESULTS
            resultCount = TMDB_MOCK_RESULTS.size
        } else {
            val filtered = TMDB_MOCK_RESULTS.filter { it.title.contains(keyword, ignoreCase = true) }
            results = filtered
            resultCount = filtered.size
        }
        // 结果集变了就把列表拉回最左，避免停留在已不存在的位置。
        scope.launch { resultState.scrollToItem(0) }
        // TODO(第二段)：这里改为 TmdbApi.searchMulti(keyword) 真实请求。
    }

    fun onKeyClick(key: HeroKey) {
        when (key) {
            is HeroKey.Text -> insert(key.value)

            is HeroKey.Action -> when (key.action) {
                HeroKeyAction.CursorLeft -> moveCaret(-1)
                HeroKeyAction.CursorRight -> moveCaret(1)
                HeroKeyAction.Backspace -> backspace()
                // 原版这个位置是「键盘布局切换键」；本弹窗没有符号键盘页，改为清空输入。
                HeroKeyAction.Keyboard -> field = TextFieldValue("")
                // TODO(季度匹配)：TMDB 弹窗没有侧边栏，小房子键第一段不接任何行为。
                HeroKeyAction.House -> Unit
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
                // 最右列 = A / H / O / V / 2 / 9：**不拦截，返回 false 放行**给原生 2D 焦点搜索。
                if (column == KEY_COLUMNS - 1) {
                    false
                } else {
                    requestFocus(keyRequesters, index + 1)
                    true
                }
            }

            Key.DirectionUp -> {
                if (row > 0) requestFocus(keyRequesters, index - KEY_COLUMNS) else focusInput()
                true
            }

            Key.DirectionDown -> {
                if (row < KEY_ROWS - 1) {
                    requestFocus(keyRequesters, index + KEY_COLUMNS)
                } else {
                    // 最后一行继续下按：落到结果区第一张卡（XML L92 keyboard nextFocusDown=candidate）。
                    focusResult(0)
                }
                true
            }

            else -> false
        }
    }

    // 进弹窗焦点落输入框（原版 TmdbMatchDialog 打开后同样是 keyword 先拿到焦点）。
    LaunchedEffect(Unit) {
        withFrameNanos { }
        requestFocus(inputRequester)
    }

    Dialog(
        onDismissRequest = { onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        HeroDialogShell(
            modifier = Modifier
                // 宽度 = 屏幕 × 0.82（原版 f23 `c0(0.82f)`）；高度按内容 wrap，
                // 与 dialog_tmdb_match.xml 的 layout_height="wrap_content" 一致。
                .fillMaxWidth(TMDB_DIALOG_WIDTH_FRACTION)
                .wrapContentHeight(),
        ) {
            // ---- 顶部行：11sp 徽标（+ 预留的联想 / 季度标签位）----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(TMDB_HEADER_HEIGHT),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .height(TMDB_BADGE_HEIGHT)
                        .clip(TMDB_BADGE_SHAPE)
                        .background(TMDB_BADGE_BACKGROUND, TMDB_BADGE_SHAPE)
                        .padding(horizontal = TMDB_BADGE_PADDING_H),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.home_tmdb_match),
                        color = Color.White,
                        fontSize = TMDB_BADGE_SP.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                // TODO(季度匹配)：原版 seasonTag（第 N 季，`@string/home_tmdb_match_season`）
                //  在 dialog_tmdb_match.xml L40 默认 visibility="gone"，第一段保持隐藏。
            }
            Spacer(modifier = Modifier.height(TMDB_HEADER_GAP))

            Row(modifier = Modifier.fillMaxWidth()) {
                // ---- 左列：复用搜索页键盘（输入框行 + 6×7 键位），宽度由键盘撑到 328dp ----
                KeyboardSection(
                    field = field,
                    inputRequester = inputRequester,
                    searchRequester = searchRequester,
                    keyRequesters = keyRequesters,
                    onInputDown = { focusKeyboard(FIRST_KEY_INDEX) },
                    onSearch = { applyFilter() },
                    onSearchDown = { focusKeyboard(FIRST_KEY_INDEX) },
                    onSearchLeft = { focusInput() },
                    onKeyClick = { key -> onKeyClick(key) },
                    onKeyMove = { index, key -> onKeyMove(index, key) },
                )

                // ---- 右列：结果标题 + 结果卡横向列表 ----
                Column(
                    modifier = Modifier
                        .weight(1f)
                        // 原版 L105 layout_marginStart="18dp"
                        .padding(start = TMDB_RESULT_MARGIN_START),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // L111-114：3dp × 14dp 红条（shape_red_bar.xml：#E50914、1.5dp 圆角）
                        Box(
                            modifier = Modifier
                                .width(TMDB_RESULT_BAR_WIDTH)
                                .height(TMDB_RESULT_BAR_HEIGHT)
                                .background(ACCENT_RED, TMDB_RESULT_BAR_SHAPE),
                        )
                        Spacer(modifier = Modifier.width(TMDB_RESULT_TITLE_GAP))
                        Text(
                            text = stringResource(R.string.home_tmdb_match_result),
                            color = TMDB_RESULT_TITLE_COLOR,
                            fontSize = TMDB_RESULT_TITLE_SP.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                        Spacer(modifier = Modifier.width(TMDB_RESULT_COUNT_GAP))
                        Text(
                            text = stringResource(R.string.home_tmdb_match_count, resultCount),
                            color = TMDB_RESULT_COUNT_COLOR,
                            fontSize = TMDB_RESULT_COUNT_SP.sp,
                            maxLines = 1,
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(TMDB_RESULT_BOX_HEIGHT),
                    ) {
                        if (results.isEmpty()) {
                            Text(
                                text = stringResource(R.string.home_tmdb_match_empty),
                                color = TMDB_RESULT_COUNT_COLOR,
                                fontSize = TMDB_EMPTY_SP.sp,
                                modifier = Modifier.align(Alignment.Center),
                            )
                        }
                        LazyRow(
                            state = resultState,
                            modifier = Modifier.fillMaxSize(),
                            // 原版 L153-154 paddingStart/End = 2dp（clipToPadding=false）。
                            contentPadding = PaddingValues(horizontal = TMDB_RESULT_PADDING_H),
                            horizontalArrangement = Arrangement.spacedBy(TMDB_RESULT_ITEM_SPACING),
                        ) {
                            itemsIndexed(items = results, key = { _, item -> item.id }) { index, item ->
                                // 类型标签必须走 R.string（红线：不写裸字面量），
                                // 又因为它要在非 @Composable 的 update 里用，所以在组合期先取出来。
                                val typeTag = stringResource(
                                    if (item.mediaType == TMDB_MEDIA_TV) {
                                        R.string.home_tmdb_match_type_tv
                                    } else {
                                        R.string.home_tmdb_match_type_movie
                                    },
                                )
                                // 【红线】HeroPosterCard 自己 onMeasure 死锁 120×214dp，
                                // 这里绝不加 Modifier.size()/width()/height()，直接放进去。
                                AndroidView(
                                    factory = { context ->
                                        HeroPosterCard(context).also { card ->
                                            resultRegistry.bind(index, card)
                                        }
                                    },
                                    update = { card ->
                                        // 过滤后同一张卡的 index 会变（factory 只在首次组合时跑一次），
                                        // 所以每次 update 都把「当前 index → View」重新登记一遍，
                                        // 否则键盘 ↓ 落到结果区时可能找到已经挪位的旧下标。
                                        resultRegistry.bind(index, card)
                                        // 只在值变化时写，避免每次重组都触发卡片内部的图片重载。
                                        if (card.title != item.title) card.title = item.title
                                        if (card.imageUrl != item.poster) card.imageUrl = item.poster
                                        if (card.topStartTag != typeTag) card.topStartTag = typeTag
                                        if (card.bottomStartTag != item.year) card.bottomStartTag = item.year
                                        if (!card.tmdbTag) card.tmdbTag = true
                                        card.setOnClickListener {
                                            onConfirm(item)
                                            onDismiss()
                                        }
                                        // 卡片 ↑ 回键盘第一行；←/→ 在结果行内移动（卡是 Android View，
                                        // Compose 原生 2D 搜索摸不到兄弟卡，所以这里显式交接）。
                                        card.setOnKeyListener { _, keyCode, event ->
                                            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                                            when (keyCode) {
                                                KeyEvent.KEYCODE_DPAD_UP -> {
                                                    focusKeyboard(FIRST_KEY_INDEX)
                                                    true
                                                }

                                                KeyEvent.KEYCODE_DPAD_LEFT -> {
                                                    if (index > 0) focusResult(index - 1)
                                                    true
                                                }

                                                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                                    if (index < results.lastIndex) focusResult(index + 1)
                                                    true
                                                }

                                                else -> false
                                            }
                                        }
                                    },
                                    onRelease = { card -> resultRegistry.unbind(index, card) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 一条 TMDB 匹配结果（`ref_docs/TmdbMatchItem.java` 的字段子集，第一段只保留展示需要的部分）。
 *
 * [mediaType] 取 `"movie"` / `"tv"`，与 TMDB `search/multi` 的 `media_type` 一致，
 * 展示时映射成左上角标签 TV / MOV。
 */
internal data class TmdbMatchItem(
    val id: Int,
    val title: String,
    val year: String,
    val poster: String,
    val mediaType: String,   // "movie" / "tv"
    val genres: String,
    val rating: String,
)

/**
 * 第一段假数据（**不接 TMDB 网络**，仅用于验证版式与焦点）。
 * 图片走 picsum 稳定 seed，和首页 / 搜索页的 mock 海报同一套路。
 */
private val TMDB_MOCK_RESULTS: List<TmdbMatchItem> = listOf(
    TmdbMatchItem(1, "后会无期", "2014", "https://picsum.photos/seed/tmdb-0/400/600", "movie", "剧情 / 公路", "7.8"),
    TmdbMatchItem(2, "后会无期", "2026", "https://picsum.photos/seed/tmdb-1/400/600", "tv", "剧情 / 都市", "8.1"),
    TmdbMatchItem(3, "后会无期 特别篇", "2026", "https://picsum.photos/seed/tmdb-2/400/600", "tv", "剧情", "7.2"),
    TmdbMatchItem(4, "尚公主", "2026", "https://picsum.photos/seed/tmdb-3/400/600", "tv", "古装 / 爱情", "8.4"),
    TmdbMatchItem(5, "小城良方", "2026", "https://picsum.photos/seed/tmdb-4/400/600", "tv", "都市 / 家庭", "7.9"),
    TmdbMatchItem(6, "嫁金钗", "2025", "https://picsum.photos/seed/tmdb-5/400/600", "tv", "古装", "7.5"),
    TmdbMatchItem(7, "玉簟秋", "2025", "https://picsum.photos/seed/tmdb-6/400/600", "tv", "剧情", "8.0"),
    TmdbMatchItem(8, "醉梦", "2025", "https://picsum.photos/seed/tmdb-7/400/600", "movie", "悬疑", "7.6"),
)

/** TMDB `media_type` 里的剧集标识（其余一律按电影处理）。 */
private const val TMDB_MEDIA_TV = "tv"

// ---------------------------------------------------------------------------
// 尺寸 / 颜色常量（逐条对应逆向 XML 或本工程既有常量，没有一条是目测调出来的）
// ---------------------------------------------------------------------------

/** 弹窗宽度 = 屏幕宽 × 0.82（原版 f23 `c0(0.82f)`，与换源弹窗同一比例）。 */
private const val TMDB_DIALOG_WIDTH_FRACTION = 0.82f

/** 顶部行高 24dp（dialog_tmdb_match.xml L21 `tmdbBadge` layout_height="24dp"）。 */
private val TMDB_HEADER_HEIGHT = 24.dp

/** 顶部行与正文之间 12dp（L12 `layout_marginBottom="12dp"`）。 */
private val TMDB_HEADER_GAP = 12.dp

/** 徽标：11sp 粗体白字（L14-16），左右内边距 10dp（L23-24）。 */
private const val TMDB_BADGE_SP = 11f
private val TMDB_BADGE_HEIGHT = 24.dp
private val TMDB_BADGE_PADDING_H = 10.dp
private val TMDB_BADGE_SHAPE = RoundedCornerShape(4.dp)      // shape_tmdb_badge.xml corners 4dp
private val TMDB_BADGE_BACKGROUND = Color(0x33FFFFFF)        // 与键盘键常态底色同一支半透明白

/** 右列与键盘之间 18dp（L105 `layout_marginStart="18dp"`）。 */
private val TMDB_RESULT_MARGIN_START = 18.dp

/** 结果标题行：3×14dp 红条（L111-114）+ 8dp 间距 + 13sp 粗体 #99ffffff（L115-122）。 */
private val TMDB_RESULT_BAR_WIDTH = 3.dp
private val TMDB_RESULT_BAR_HEIGHT = 14.dp
private val TMDB_RESULT_BAR_SHAPE = RoundedCornerShape(1.5.dp)
private val TMDB_RESULT_TITLE_GAP = 8.dp
private const val TMDB_RESULT_TITLE_SP = 13f
private val TMDB_RESULT_TITLE_COLOR = Color(0x99FFFFFF)

/** 计数文案：11sp #59ffffff（L123-129）。 */
private val TMDB_RESULT_COUNT_GAP = 8.dp
private const val TMDB_RESULT_COUNT_SP = 11f
private val TMDB_RESULT_COUNT_COLOR = Color(0x59FFFFFF)

/** 空态文案：14sp #59ffffff 居中（L135-145）。 */
private const val TMDB_EMPTY_SP = 14f

/**
 * 结果区高度 = 卡片整高（[HeroPosterCard.CARD_HEIGHT_DP] = 214dp）。
 *
 * 原版 FrameLayout 是 210dp（L131-134），因为原卡片更矮；本工程的结果卡固定 120×214dp，
 * 容器必须至少 214dp，否则 34dp 剧名栏会被裁掉一截。
 */
private val TMDB_RESULT_BOX_HEIGHT = HeroPosterCard.CARD_HEIGHT_DP.dp

/** 结果行左右内边距 2dp（L153-154）；卡间距 12dp（与本工程其它海报行一致）。 */
private val TMDB_RESULT_PADDING_H = 2.dp
private val TMDB_RESULT_ITEM_SPACING = 12.dp
