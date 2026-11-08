package com.fongmi.android.tv.ui.hero

import android.util.Log
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fongmi.android.tv.R
import com.fongmi.android.tv.bean.TmdbConfig
import com.fongmi.android.tv.bean.TmdbItem
import com.fongmi.android.tv.bean.Vod
import com.fongmi.android.tv.service.TmdbService
import com.fongmi.android.tv.ui.helper.TmdbMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * TMDB 手动匹配弹窗（Compose，**真实搜索**）。
 *
 * 版式基准 = `ref_docs/dialog_tmdb_match.xml`（原版 `TmdbMatchDialog` / `pc3`）：
 * ```
 * LinearLayout(padding 18dp, wrap_content)                       // = [HeroDialogShell]
 * ├─ Row(heightIn 24dp, marginBottom 12dp)                       // L7-45：顶部行
 * │    ├─ TextView 11sp bold 白 + 4dp 圆角徽标 + paddingH 10dp    // L13-24 tmdbBadge
 * │    ├─ RecyclerView @id/suggestion（联想词胶囊，marginStart 12dp）// L25-34
 * │    └─ (seasonTag：原版默认 gone，暂未启用)                     // L35-44
 * └─ Row                                                         // L46-159
 *      ├─ Column(键盘 328dp = 7×40 + 6×8)                         // L50-99：输入行 + 键盘
 *      └─ Column(weight 1f, marginStart 18dp)                     // L100-158：结果标题 + 结果区
 *           ├─ Row：3×14dp 红条 + 13sp 标题 + 11sp 计数            // L106-130
 *           └─ Box(height = 卡高 214dp)：空态文案 / LazyRow        // L131-157
 * ```
 *
 * 四条硬约束：
 * 1. **结果卡只允许用全局唯一组件 [HeroPosterCard]**（120×214dp、四角 8dp 全圆、
 *    2dp 纯红焦点框只包 120×180 海报、34dp 剧名栏）。`AndroidView` 里**绝不加任何尺寸 Modifier**，
 *    容器高度直接取 [HeroPosterCard.CARD_HEIGHT_DP]，否则会把 34dp 剧名栏裁掉。
 *    唯一允许改的是剧名字号：原版这张卡是 12sp（`adapter_tmdb_match.xml` L59），
 *    用 [HeroPosterCard.titleTextSizeSp] 调成 [TMDB_RESULT_CARD_TITLE_SP]，不碰全局默认值。
 * 2. **键盘不复制第二份**：直接复用 `ui/hero/HeroKeyboard.kt` 的 [KeyboardSection]
 *    （输入框行 + 6 行 × 7 列键位），再在弹窗内部用 `CompositionLocalProvider(LocalDensity)`
 *    把整棵键盘子树等比缩到原版左列的 217dp 宽（[TMDB_KEYBOARD_SCALE]），不改共享组件的常量。
 *    弹窗没有换源侧边栏，所以 `hideHouseKey = true` 把无行为的小房子键隐藏成空槽。
 * 3. **真实网络搜索**：放大镜按钮 / 打开弹窗都走 `TmdbMatcher.search(keyword, vod)`，与首页、
 *    详情页自动匹配共用同一套「清理剧名 + 按标题相似度 / 年份 / 语区 / 评分排序」规则；
 *    不再有任何本地假数据。没配 Key 时不发请求，直接提示先配置 TMDB。
 * 4. **联想词**：位置与原版一致 —— 顶部徽标**右侧**的一排胶囊，不在输入框下面；
 *    词表在弹窗初始化时生成一次（原始词 / [TmdbMatcher.cleanVideoName] 清洗词 / 清洗词 + 年份），
 *    点一下就把词写进输入框并立刻真实搜索。整行在键盘缩放子树之外，14sp 就是最终视觉字号。
 *
 * 焦点契约：
 * - 进弹窗 → 输入框；输入框 ↑ → 联想词行（有联想词时，交原生 2D 焦点搜索）；
 *   输入框 ↓ → 键盘第一行；
 * - 联想词 ←/→ 在本行内横移、↓ → 输入框（对应原版 `nextFocusDown="@id/keyword"`）；
 * - 键盘最后一行 ↓ → 结果区第一张卡；结果卡 ↑ → 键盘第一行；
 * - **结果卡 ← 到第一张再往左 → 放大镜**（否则焦点会卡在第一张卡上出不来）；
 * - 键盘最右列 → 不消费（交原生 2D 焦点搜索）；返回键 → 关闭弹窗，焦点由首页还回长按的那张卡。
 */
@Composable
internal fun TmdbMatchDialog(
    initialKeyword: String,
    // 当前海报对应的源站条目：`TmdbMatcher` 用它取年份 / 季号 / 备注给候选排序。
    vod: Vod?,
    onConfirm: (TmdbItem) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    // 输入内容用 TextFieldValue 维护：输入框 readOnly，只吃弹窗自己的虚拟键盘，
    // 光标由键盘 < / > 驱动（与搜索页完全同一套交互）。
    var field by remember {
        mutableStateOf(TextFieldValue(initialKeyword, TextRange(initialKeyword.length)))
    }
    var results by remember { mutableStateOf<List<TmdbItem>>(emptyList()) }
    // `searching`：请求在途（显示「搜索中」而不是「未找到」）；`needKey`：没配 Key 的专用提示。
    var searching by remember { mutableStateOf(false) }
    var needKey by remember { mutableStateOf(false) }
    // 联想词（原版 `TmdbMatchDialog.k0()`）：顶部徽标右侧的一排圆角胶囊，点一下直接搜。
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    // 只用来跑 `cleanVideoName` 的纯正则清洗（去季集 / 清晰度 / 压制标记），不持有任何网络状态。
    val matcher = remember { TmdbMatcher(TmdbService(), TmdbConfig.effectiveCurrent()) }

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
     * 在弹窗初始化时生成一次联想词（严格对照逆向 `TmdbMatchDialog.k0()` L220-246）。
     *
     * 只吃**进来时的原始剧名** `initialKeyword`，不跟随输入框变化 —— 这是原版行为：
     * 联想词是「这部剧还能怎么搜」的快捷入口，用户开始手打之后就不该再乱跳。
     *
     * 三个变体，按顺序去重（原版 `f0()` 是逐条全等去重）：
     * 1. 原始词：源站名有时就是 TMDB 官方名，清洗反而会改坏它；
     * 2. [TmdbMatcher.cleanVideoName] 清洗结果：去掉 `S01E03` / `4K` / `国语` / 压制标记；
     * 3. 清洗结果 + 年份：原版用剧名里的 `(2019)` / `2019`，源站给 `vod.year` 时优先用它。
     */
    LaunchedEffect(initialKeyword, vod) {
        suggestions = buildTmdbSuggestions(initialKeyword, vod, matcher)
    }

    /**
     * 放大镜按钮 / 打开弹窗：走 [TmdbMatcher.search] 的**真实网络搜索**。
     *
     * 空关键字不发请求；搜索中重复点按直接忽略（避免连点让早到的旧结果盖掉新结果）。
     * 没配置 TMDB 时一条请求都不发，只把空态文案换成「请先配置 TMDB」。
     */
    fun applyFilter() {
        val keyword = field.text.trim()
        if (keyword.isEmpty() || searching) return
        scope.launch {
            val config = TmdbConfig.effectiveCurrent()
            if (!config.isReady()) {
                results = emptyList()
                needKey = true
                return@launch
            }
            needKey = false
            searching = true
            // 搜索包含网络 + 磁盘缓存读写，必须离开主线程；取消时原样抛出，交给协程机制。
            val found = withContext(Dispatchers.IO) {
                try {
                    TmdbMatcher(TmdbService(), config).search(keyword, vod)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG_TMDB_DIALOG, "tmdb 手动匹配搜索失败: $keyword | ${e.message}")
                    emptyList<TmdbItem>()
                }
            }
            searching = false
            results = found
            // 结果集变了就把列表拉回最左，避免停留在已不存在的位置。
            if (found.isNotEmpty()) resultState.scrollToItem(0)
        }
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
                // 小房子键在弹窗里已被 hideHouseKey 隐藏（索引 5 变成空槽），
                // 这个分支只为让 when 覆盖完整，永远不会被触发。
                HeroKeyAction.House -> Unit
            }

            // 占位格不聚焦，由 HeroKeyPlaceholder 单独渲染，走不到这里。
            else -> Unit
        }
    }

    /**
     * 索引 [HOUSE_KEY_INDEX]（小房子）在弹窗里是隐藏空槽，四向移动经过它时必须再跨一格：
     * 否则 `requestFocus` 会落到一个没有组合的 FocusRequester 上，焦点卡在原地不动。
     */
    fun skipHiddenKey(target: Int, step: Int): Int =
        if (target == HOUSE_KEY_INDEX) target + step else target

    fun onKeyMove(index: Int, key: Key): Boolean {
        val row = index / KEY_COLUMNS
        val column = index % KEY_COLUMNS
        return when (key) {
            Key.DirectionLeft -> {
                // 最左列（含第一行占位格右侧的 `<`）不再左移，就地消费。
                // 从 A 左移要跨过隐藏的小房子，落到「键盘布局」键。
                val target = skipHiddenKey(index - 1, -1)
                if (column > 0 && target > PLACEHOLDER_INDEX) requestFocus(keyRequesters, target)
                true
            }

            Key.DirectionRight -> {
                // 最右列 = A / H / O / V / 2 / 9：**不拦截，返回 false 放行**给原生 2D 焦点搜索。
                if (column == KEY_COLUMNS - 1) {
                    false
                } else {
                    requestFocus(keyRequesters, skipHiddenKey(index + 1, 1))
                    true
                }
            }

            Key.DirectionUp -> {
                if (row > 0) {
                    requestFocus(keyRequesters, skipHiddenKey(index - KEY_COLUMNS, -1))
                } else {
                    focusInput()
                }
                true
            }

            Key.DirectionDown -> {
                if (row < KEY_ROWS - 1) {
                    requestFocus(keyRequesters, skipHiddenKey(index + KEY_COLUMNS, 1))
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
        // 打开即按当前剧名真实搜一次：长按进来第一屏就是真实候选，不用再按放大镜。
        // 没配 Key / 关键字为空时 applyFilter 自己会跳过，不会白发请求。
        if (field.text.isNotBlank()) applyFilter()
    }

    // 【原版同款 W 锁 · 弹窗侧】长按打开时手指还按着，连发会让焦点在胶囊 / 结果卡 / 键盘之间乱跑。
    // 弹窗是独立 Window，Activity 的 `dispatchKeyEvent` 看不到这些事件，只能在弹窗根节点拦：
    // 从打开起、到收到那次真实的 `ACTION_UP`（或下一次全新按下 `repeatCount == 0`）为止，
    // 所有连发一律吞掉。松开即恢复，正常「按住下键连续翻结果」不受影响。
    val swallowRepeat = remember { mutableStateOf(true) }

    Dialog(
        onDismissRequest = { onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        HeroDialogShell(
            modifier = Modifier
                // 宽度 = 屏幕 × 0.82（原版 f23 `c0(0.82f)`）；高度按内容 wrap，
                // 与 dialog_tmdb_match.xml 的 layout_height="wrap_content" 一致。
                .fillMaxWidth(TMDB_DIALOG_WIDTH_FRACTION)
                .wrapContentHeight()
                // 连发锁：preview 阶段先于所有子节点（胶囊 / 结果卡 / 键盘）拿到按键。
                .onPreviewKeyEvent { event ->
                    val action = event.nativeKeyEvent.action
                    when {
                        action == KeyEvent.ACTION_UP -> {
                            swallowRepeat.value = false
                            false
                        }
                        action == KeyEvent.ACTION_DOWN && event.nativeKeyEvent.repeatCount == 0 -> {
                            swallowRepeat.value = false
                            false
                        }
                        swallowRepeat.value && action == KeyEvent.ACTION_DOWN -> true
                        else -> false
                    }
                },
        ) {
            // ---- 顶部行：11sp 徽标 + 右侧联想词胶囊（原版 dialog_tmdb_match.xml L7-45）----
            // 行高按内容 wrap（heightIn 只兜住徽标的 24dp）：胶囊比徽标高，写死 24dp 会把它们裁掉。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = TMDB_HEADER_HEIGHT),
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
                // 原版 L36 `layout_marginStart="12dp"`，且 RecyclerView 吃掉剩余宽度（weight=1f）。
                // 没有联想词时整块（连同上方的 12dp 间距）都不出现，弹窗高度与改动前完全一致。
                if (suggestions.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(TMDB_SUGGESTION_GAP_FROM_BADGE))
                    TmdbSuggestionRow(
                        suggestions = suggestions,
                        onMoveDown = { focusInput() },
                        onPick = { suggestion ->
                            // 点一下 = 把词写进输入框并立刻真实搜索（原版 setOnClickListener → search）。
                            field = TextFieldValue(
                                text = suggestion,
                                selection = TextRange(suggestion.length),
                            )
                            applyFilter()
                        },
                    )
                }
                // TODO(季度匹配)：原版 seasonTag（第 N 季，`@string/home_tmdb_match_season`）
                //  在 dialog_tmdb_match.xml L40 默认 visibility="gone"，第一段保持隐藏。
            }
            Spacer(modifier = Modifier.height(TMDB_HEADER_GAP))

            Row(modifier = Modifier.fillMaxWidth()) {
                // ---- 左列：复用搜索页键盘（输入框行 + 6×7 键位）----
                // 原版 dialog_tmdb_match.xml L50-99 的左列只有 217dp 宽，而复用的 [KeyboardSection]
                // 是搜索页基准（7×40 + 6×8 = 328dp）。这里用 LocalDensity 把**整棵键盘子树**
                // 等比缩到 217/328 ≈ 0.66（键 40dp→26.4dp、间距 8dp→5.3dp、字号 16sp→10.6sp），
                // 与搜索页共用同一份实现，**不改 HeroKeyboard.kt 里的任何常量**。
                // 缩放只改变 dp/sp 的换算，焦点逻辑（FocusRequester / onPreviewKeyEvent）完全不受影响。
                val baseDensity = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(
                        density = baseDensity.density * TMDB_KEYBOARD_SCALE,
                        fontScale = baseDensity.fontScale,
                    ),
                ) {
                    Column {
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
                            // 弹窗没有换源侧边栏，小房子键点了没反应，直接隐藏（索引 5 变空槽）。
                            hideHouseKey = true,
                        )
                    }
                }

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
                            text = stringResource(R.string.home_tmdb_match_count, results.size),
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
                                text = when {
                                    // 首次搜索还没结果时显示「搜索中」，避免一闪「未找到」再出结果。
                                    searching -> stringResource(R.string.detail_tmdb_searching)
                                    needKey -> stringResource(R.string.detail_tmdb_need_key)
                                    else -> stringResource(R.string.home_tmdb_match_empty)
                                },
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
                            // key 必须带 mediaType：TMDB 的电影 / 剧集 id 来自两套独立序列，
                            // 同一个数字可能同时是「某部电影」和「某部剧」，只用 tmdbId 会撞 key。
                            itemsIndexed(items = results, key = { _, item -> "${item.mediaType}:${item.tmdbId}" }) { index, item ->
                                // 类型标签必须走 R.string（红线：不写裸字面量），
                                // 又因为它要在非 @Composable 的 update 里用，所以在组合期先取出来。
                                val typeTag = stringResource(
                                    if (item.mediaType.equals(TMDB_MEDIA_TV, ignoreCase = true)) {
                                        R.string.home_tmdb_match_type_tv
                                    } else {
                                        R.string.home_tmdb_match_type_movie
                                    },
                                )
                                // 年份从真实 `subtitle`（「电影 · 2014-05-23 · 评分 7.8」）里取，组合期算一次。
                                val year = tmdbMatchYear(item)
                                // 【红线】HeroPosterCard 自己 onMeasure 死锁 120×214dp，
                                // 这里绝不加 Modifier.size()/width()/height()，直接放进去。
                                AndroidView(
                                    factory = { context ->
                                        HeroPosterCard(context).also { card ->
                                            // 原版这张卡的剧名就是 12sp（`adapter_tmdb_match.xml` L59），
                                            // 而全局海报卡基准是 16sp，直接复用会明显偏大、盖过左侧键盘。
                                            card.titleTextSizeSp = TMDB_RESULT_CARD_TITLE_SP
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
                                        if (card.imageUrl != item.posterUrl) card.imageUrl = item.posterUrl
                                        if (card.topStartTag != typeTag) card.topStartTag = typeTag
                                        if (card.bottomStartTag != year) card.bottomStartTag = year
                                        if (!card.tmdbTag) card.tmdbTag = true
                                        card.setOnClickListener {
                                            // 回调真实 TmdbItem：调用方直接拿 tmdbId / mediaType / 海报 / 横图，
                                            // 不再从一份精简模型手工拼 TmdbItem。
                                            onConfirm(item)
                                            onDismiss()
                                        }
                                        // 卡片 ↑ 回键盘第一行；←/→ 在结果行内移动（卡是 Android View，
                                        // Compose 原生 2D 搜索摸不到兄弟卡，所以这里显式交接）。
                                        // 第一张卡再往左：回到放大镜（原版结果区 nextFocusLeft 指回搜索列），
                                        // 否则焦点会卡在第一张卡上出不来。
                                        card.setOnKeyListener { _, keyCode, event ->
                                            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                                            when (keyCode) {
                                                KeyEvent.KEYCODE_DPAD_UP -> {
                                                    focusKeyboard(FIRST_KEY_INDEX)
                                                    true
                                                }

                                                KeyEvent.KEYCODE_DPAD_LEFT -> {
                                                    if (index > 0) focusResult(index - 1) else requestFocus(searchRequester)
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
 * 顶部徽标右侧的联想词胶囊行（原版 `dialog_tmdb_match.xml` L25-34 `@id/suggestion`）。
 *
 * - `weight(1f)` 吃掉徽标右边剩下的宽度，超宽时横向滚动；
 * - 单行高由胶囊撑开，不需要写死高度（调用方在空列表时整行不渲染）。
 */
@Composable
private fun RowScope.TmdbSuggestionRow(
    suggestions: List<String>,
    onMoveDown: () -> Unit,
    onPick: (String) -> Unit,
) {
    LazyRow(
        modifier = Modifier.weight(1f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TMDB_SUGGESTION_GAP),
        // 原版每颗胶囊自带 `layout_margin="4dp"`，用 contentPadding 还原首尾那 4dp，
        // 同时给焦点红框留出绘制位置（否则第一颗的框会被行边界裁掉一条）。
        contentPadding = PaddingValues(horizontal = TMDB_SUGGESTION_EDGE_PADDING),
    ) {
        itemsIndexed(items = suggestions) { _, suggestion ->
            TmdbSuggestionChip(
                text = suggestion,
                onMoveDown = onMoveDown,
                onPick = { onPick(suggestion) },
            )
        }
    }
}

/**
 * 单颗联想词胶囊：14sp 白字 + 28dp 圆角半透明白底（`adapter_tmdb_suggestion.xml`
 * + `selector_item_round`），焦点态加红框。
 *
 * 遥控器路径：胶囊在输入框**上方**，按 ↑ 从输入框可以落到这一行（Compose 2D 焦点搜索），
 * 按 ↓ 显式回到输入框（对应原版 `suggestion` 的 `nextFocusDown="@id/keyword"`）。
 */
@Composable
private fun TmdbSuggestionChip(
    text: String,
    onMoveDown: () -> Unit,
    onPick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Text(
        text = text,
        color = Color.White,
        fontSize = TMDB_SUGGESTION_SP.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(TMDB_SUGGESTION_SHAPE)
            .background(
                if (focused) TMDB_SUGGESTION_BACKGROUND_FOCUSED else TMDB_SUGGESTION_BACKGROUND,
            )
            .then(
                if (focused) {
                    Modifier.border(TMDB_SUGGESTION_FOCUS_BORDER, ACCENT_RED, TMDB_SUGGESTION_SHAPE)
                } else {
                    Modifier
                },
            )
            // ↓ 回输入框：胶囊和输入框之间隔着 12dp 间距与一行键盘，交给原生 2D 搜索不可靠。
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                    onMoveDown()
                    true
                } else {
                    false
                }
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onPick)
            .padding(horizontal = TMDB_SUGGESTION_PADDING_H, vertical = TMDB_SUGGESTION_PADDING_V),
    )
}

/**
 * 结果卡右下角的年份：真实 [TmdbItem.subtitle] 形如「电影 · 2014-05-23 · 评分 7.8」，
 * 只取第一个 4 位年份；没有日期（未上映 / 未定档）时留空，卡片自动不显示这枚标签。
 */
private fun tmdbMatchYear(item: TmdbItem): String =
    TMDB_YEAR_PATTERN.find(item.subtitle)?.value.orEmpty()

private val TMDB_YEAR_PATTERN = Regex("(?:19|20)\\d{2}")

/** 弹窗日志标签（搜索失败只记一条 warn，不弹 Toast 打扰遥控器操作）。 */
private const val TAG_TMDB_DIALOG = "HeroTmdbMatch"

/** TMDB `media_type` 里的剧集标识（其余一律按电影处理）。 */
private const val TMDB_MEDIA_TV = "tv"

/**
 * 联想词生成（原版 `TmdbMatchDialog.k0()` L220-246 的 Kotlin 等价物）。
 *
 * 纯正则、零网络：只在弹窗初始化时跑一次，开销可以忽略。
 * 去重是逐条全等（原版 `f0()` 的行为），不是大小写无关 —— 与逆向实现保持一致。
 */
private fun buildTmdbSuggestions(keyword: String, vod: Vod?, matcher: TmdbMatcher): List<String> {
    val raw = keyword.trim()
    if (raw.isEmpty()) return emptyList()
    val result = mutableListOf<String>()
    fun add(value: String) {
        val text = value.trim()
        if (text.isNotEmpty() && result.none { it == text }) result.add(text)
    }
    // 1. 原始词。
    add(raw)
    // 2. 清洗后的词（去季集 / 清晰度 / 压制标记）。
    val cleanName = matcher.cleanVideoName(raw)
    add(cleanName)
    // 3. 清洗词 + 年份：原版从剧名里抓 `(2019)`，源站给了 `vod.year` 时以它为准。
    val year = vod?.year?.trim().orEmpty().ifBlank { TMDB_YEAR_PATTERN.find(raw)?.value.orEmpty() }
    if (year.isNotBlank() && cleanName.isNotBlank()) add("$cleanName $year")
    return result
}

// ---------------------------------------------------------------------------
// 尺寸 / 颜色常量（逐条对应逆向 XML 或本工程既有常量，没有一条是目测调出来的）
// ---------------------------------------------------------------------------

/** 弹窗宽度 = 屏幕宽 × 0.82（原版 f23 `c0(0.82f)`，与换源弹窗同一比例）。 */
private const val TMDB_DIALOG_WIDTH_FRACTION = 0.82f

/**
 * 左列键盘的等比缩放系数。
 *
 * 原版 `dialog_tmdb_match.xml` L50-99 的左列宽 **217dp**；复用的 [KeyboardSection] 是搜索页基准
 * **328dp**（7×40 + 6×8）。217 / 328 ≈ 0.66 —— 缩放后键 26.4dp、间距 5.3dp、左列 217dp，
 * 高度约 216dp，和右侧「标题行 + 214dp 结果卡」基本齐平，弹窗恢复原版的扁长比例。
 */
private const val TMDB_KEYBOARD_SCALE = 0.66f

/**
 * 结果卡剧名字号 **12sp**。
 *
 * 全局海报卡 [HeroPosterCard] 的剧名基准是 16sp（`adapter_search.xml` L56），
 * 但原版 TMDB 匹配结果卡是更小的一号卡（110dp 宽 / 102×153 海报），剧名只有 12sp
 * （`adapter_tmdb_match.xml` L59）。这里按原版取值，和左列缩小后的键盘（键 10.6sp）视觉齐平。
 */
private const val TMDB_RESULT_CARD_TITLE_SP = 12f

/**
 * 顶部行最小高 24dp（`dialog_tmdb_match.xml` L21 `tmdbBadge` layout_height="24dp"）。
 *
 * 只是下限：右侧联想词胶囊（14sp + 12dp 内边距）比徽标高，写死 24dp 会把胶囊裁掉。
 */
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

/**
 * 联想词胶囊（`adapter_tmdb_suggestion.xml`：14sp 白字、`selector_item_round` 圆角底、
 * `layout_margin="4dp"`、单行）。整行在弹窗顶部、**不在**键盘缩放子树里，
 * 所以这里的 dp / sp 就是最终视觉尺寸，不能再按 0.66 放大。
 */
private val TMDB_SUGGESTION_GAP_FROM_BADGE = 12.dp   // dialog_tmdb_match.xml L36 layout_marginStart
private val TMDB_SUGGESTION_GAP = 8.dp
private val TMDB_SUGGESTION_EDGE_PADDING = 4.dp     // 胶囊自带的 layout_margin="4dp"
private val TMDB_SUGGESTION_SHAPE = RoundedCornerShape(28.dp)  // shape_item_round corners 28dp
private val TMDB_SUGGESTION_BACKGROUND = Color(0x33FFFFFF)
private val TMDB_SUGGESTION_BACKGROUND_FOCUSED = Color(0x66FFFFFF)
private val TMDB_SUGGESTION_FOCUS_BORDER = 2.dp
private const val TMDB_SUGGESTION_SP = 14f
private val TMDB_SUGGESTION_PADDING_H = 12.dp
private val TMDB_SUGGESTION_PADDING_V = 6.dp
