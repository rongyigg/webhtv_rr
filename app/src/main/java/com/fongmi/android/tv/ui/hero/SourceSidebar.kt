package com.fongmi.android.tv.ui.hero

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

/**
 * 全局接口源侧边栏（SourceSidebar）。
 *
 * 版式（需求一）：
 * - 从屏幕右侧滑出、靠右对齐，宽 320dp、高度铺满，背景 #1E1E1E；
 * - 顶部「全选 / 全不选」两颗按钮，横向排列，焦点态红底白字；
 * - 列表 [LazyColumn]：每项高 40dp，左侧站点图标 + 名称（如「豆瓣 | 海报」），
 *   右侧圆形勾选框（空心 / 实心），字号 14sp；
 * - 确定键切换勾选；返回键关闭侧边栏，焦点由调用方恢复。
 *
 * 层级：本身是一个全屏透明 Dialog 窗口，只把 320dp 面板贴在右侧。
 * 在换源弹窗之上打开时会自动叠到最上层（后创建的 Dialog 窗口在更上层），
 * 返回键先关闭侧边栏，不会误关下面的换源弹窗。
 *
 * 焦点契约：
 * - 打开落在第 1 条列表项；↑ 回「全选 / 全不选」按钮行；
 * - 按钮行 ←/→ 在「全选 / 全不选」之间移动；↓ 回列表（记住离开时的条目）；
 * - 列表项 ↑/↓ 逐条移动，首条 ↑ 回按钮行，末条 ↓ 消费；←/→ 消费（单列）。
 */
@Composable
internal fun SourceSidebar(
    entries: List<HeroSourceEntry> = HERO_SOURCE_ENTRIES,
    onDismissRequest: () -> Unit,
) {
    val visibleState = remember { MutableTransitionState(false) }
    // started 兜住首帧：MutableTransitionState 初始就是「不可见且静止」，
    // 没有这个标记时下面的关闭监听会在进动画开始前误判为「已关闭」。
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        visibleState.targetState = true
        started = true
    }
    // 滑出动画播完、且当前确实不可见时才真正关闭窗口。
    LaunchedEffect(visibleState.isIdle, visibleState.currentState, started) {
        if (started && visibleState.isIdle && !visibleState.currentState) onDismissRequest()
    }

    Dialog(
        // 返回键：先播滑出动画，再回调宿主关闭。
        onDismissRequest = { visibleState.targetState = false },
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        // 侧边栏只是浮层：把系统 Dialog 的默认 60% 遮罩压到 25%，下面的内容仍然可见。
        val view = LocalView.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.setDimAmount(SIDEBAR_DIM_AMOUNT)
        }
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            AnimatedVisibility(
                visibleState = visibleState,
                enter = slideInHorizontally(animationSpec = tween(SIDEBAR_ANIM_MS)) { width -> width },
                exit = slideOutHorizontally(animationSpec = tween(SIDEBAR_ANIM_MS)) { width -> width },
            ) {
                SourceSidebarPanel(entries = entries)
            }
        }
    }
}

@Composable
private fun SourceSidebarPanel(entries: List<HeroSourceEntry>) {
    // 默认全部勾选：换源时所有源可用；「全不选」再逐个挑。
    var checkedKeys by remember(entries) { mutableStateOf(entries.map { it.key }.toSet()) }
    var focusedTop by remember { mutableIntStateOf(0) }
    var focusedItem by remember { mutableIntStateOf(0) }

    val selectAllRequester = remember { FocusRequester() }
    val selectNoneRequester = remember { FocusRequester() }
    val itemRequesters = remember(entries.size) { List(entries.size) { FocusRequester() } }
    val listState = rememberLazyListState()

    // Lazy 项只有组合出来才能 requestFocus：先直接请求，失败再滚动 + 逐帧重试。
    var pendingItem by remember { mutableIntStateOf(0) }
    var pendingSerial by remember { mutableIntStateOf(0) }

    fun focusTop(index: Int) {
        val target = index.coerceIn(0, 1)
        focusedTop = target
        runCatching {
            if (target == 0) selectAllRequester.requestFocus() else selectNoneRequester.requestFocus()
        }
    }

    fun focusItem(index: Int) {
        if (entries.isEmpty()) return
        val target = index.coerceIn(0, entries.lastIndex)
        focusedItem = target
        pendingItem = target
        pendingSerial++
    }

    LaunchedEffect(pendingItem, pendingSerial, entries.size) {
        if (entries.isEmpty()) return@LaunchedEffect
        val target = pendingItem.coerceIn(0, entries.lastIndex)
        repeat(2) {
            if (requestSourceFocus(itemRequesters, target)) return@LaunchedEffect
            runCatching { listState.scrollToItem(target) }
            withFrameNanos { }
        }
        repeat(SOURCE_FOCUS_RETRY_FRAMES) {
            if (requestSourceFocus(itemRequesters, target)) return@LaunchedEffect
            withFrameNanos { }
        }
    }

    Column(
        modifier = Modifier
            .width(SIDEBAR_WIDTH)
            .fillMaxHeight()
            .background(SIDEBAR_BACKGROUND)
            .border(1.dp, SIDEBAR_BORDER_COLOR)
            .padding(horizontal = SIDEBAR_H_PADDING, vertical = SIDEBAR_V_PADDING),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SIDEBAR_TOP_GAP),
        ) {
            SidebarTopButton(
                label = "全选",
                modifier = Modifier.weight(1f),
                requester = selectAllRequester,
                onFocused = { focusedTop = 0 },
                onConfirm = { checkedKeys = entries.map { it.key }.toSet() },
                onMoveLeft = { focusTop(0) },
                onMoveRight = { focusTop(1) },
                onMoveDown = { focusItem(focusedItem) },
            )
            SidebarTopButton(
                label = "全不选",
                modifier = Modifier.weight(1f),
                requester = selectNoneRequester,
                onFocused = { focusedTop = 1 },
                onConfirm = { checkedKeys = emptySet() },
                onMoveLeft = { focusTop(0) },
                onMoveRight = { focusTop(1) },
                onMoveDown = { focusItem(focusedItem) },
            )
        }
        Spacer(modifier = Modifier.height(SIDEBAR_HEADER_GAP))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(SIDEBAR_DIVIDER_COLOR),
        )
        Spacer(modifier = Modifier.height(SIDEBAR_HEADER_GAP))

        if (entries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "暂无接口源",
                    color = SIDEBAR_HINT_COLOR,
                    fontSize = SIDEBAR_ITEM_SP.sp,
                )
            }
            return@Column
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(SIDEBAR_ITEM_GAP),
        ) {
            itemsIndexed(items = entries, key = { _, entry -> entry.key }) { index, entry ->
                SidebarSourceRow(
                    entry = entry,
                    checked = entry.key in checkedKeys,
                    requester = itemRequesters[index],
                    onFocused = { focusedItem = index },
                    onConfirm = {
                        checkedKeys = if (entry.key in checkedKeys) {
                            checkedKeys - entry.key
                        } else {
                            checkedKeys + entry.key
                        }
                    },
                    onMoveUp = { if (index == 0) focusTop(focusedTop) else focusItem(index - 1) },
                    onMoveDown = { if (index < entries.lastIndex) focusItem(index + 1) },
                )
            }
        }
        Spacer(modifier = Modifier.height(SIDEBAR_HINT_GAP))
        Text(
            text = "返回键关闭",
            modifier = Modifier.fillMaxWidth(),
            color = SIDEBAR_HINT_COLOR,
            fontSize = SIDEBAR_HINT_SP.sp,
        )
    }
}

/** 顶部「全选 / 全不选」：34dp 高、14sp，焦点红底白字，常态深灰底。 */
@Composable
private fun SidebarTopButton(
    label: String,
    modifier: Modifier = Modifier,
    requester: FocusRequester,
    onFocused: () -> Unit,
    onConfirm: () -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
    onMoveDown: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val isFocused by interaction.collectIsFocusedAsState()

    Box(
        modifier = modifier
            .height(SIDEBAR_TOP_HEIGHT)
            .focusRequester(requester)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> {
                        onMoveLeft()
                        true
                    }

                    Key.DirectionRight -> {
                        onMoveRight()
                        true
                    }

                    Key.DirectionDown -> {
                        onMoveDown()
                        true
                    }

                    Key.DirectionUp -> true
                    else -> false
                }
            }
            .clip(SIDEBAR_BUTTON_SHAPE)
            .background(if (isFocused) HeroAccentColor else SIDEBAR_BUTTON_IDLE_BG)
            .clickable(interactionSource = interaction, indication = null, onClick = onConfirm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = SIDEBAR_TOP_SP.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/** 单条接口源：40dp 高，左图标 + 名称，右圆形勾选框；焦点整行红底。 */
@Composable
private fun SidebarSourceRow(
    entry: HeroSourceEntry,
    checked: Boolean,
    requester: FocusRequester,
    onFocused: () -> Unit,
    onConfirm: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val isFocused by interaction.collectIsFocusedAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(SIDEBAR_ITEM_HEIGHT)
            .focusRequester(requester)
            .onFocusChanged { if (it.isFocused) onFocused() }
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

                    Key.DirectionLeft, Key.DirectionRight -> true
                    else -> false
                }
            }
            .clip(SIDEBAR_ROW_SHAPE)
            .background(if (isFocused) HeroAccentColor else SIDEBAR_ROW_IDLE_BG)
            .clickable(interactionSource = interaction, indication = null, onClick = onConfirm)
            .padding(horizontal = SIDEBAR_ROW_H_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SourceGlyph(site = entry.site)
        Spacer(modifier = Modifier.width(SIDEBAR_ICON_GAP))
        Text(
            text = entry.displayName,
            modifier = Modifier.weight(1f),
            color = Color.White,
            fontSize = SIDEBAR_ITEM_SP.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.width(SIDEBAR_ICON_GAP))
        SelectionDot(checked = checked, focused = isFocused)
    }
}

/** 站点图标占位：20dp 圆角方块 + 单字，颜色来自假数据，不依赖额外 drawable。 */
@Composable
private fun SourceGlyph(site: HeroSourceSite) {
    Box(
        modifier = Modifier
            .size(SIDEBAR_GLYPH_SIZE)
            .clip(SIDEBAR_GLYPH_SHAPE)
            .background(site.color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = site.glyph,
            color = Color.White,
            fontSize = SIDEBAR_GLYPH_SP.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/** 圆形勾选框：空心 = 仅描边；实心 = 填充 + 对勾。焦点行上是白底红勾，普通行是红底白勾。 */
@Composable
private fun SelectionDot(checked: Boolean, focused: Boolean) {
    val borderColor = if (focused) Color.White else SIDEBAR_DOT_BORDER
    val fillColor = when {
        checked && focused -> Color.White
        checked -> HeroAccentColor
        else -> Color.Transparent
    }
    val checkColor = if (focused) HeroAccentColor else Color.White

    Box(
        modifier = Modifier
            .size(SIDEBAR_DOT_SIZE)
            .clip(CircleShape)
            .background(fillColor)
            .border(SIDEBAR_DOT_BORDER_WIDTH, borderColor, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (!checked) return@Box
        Canvas(modifier = Modifier.size(SIDEBAR_DOT_CHECK_SIZE)) {
            val stroke = size.width * 0.16f
            drawLine(
                color = checkColor,
                start = Offset(size.width * 0.12f, size.height * 0.52f),
                end = Offset(size.width * 0.42f, size.height * 0.82f),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = checkColor,
                start = Offset(size.width * 0.42f, size.height * 0.82f),
                end = Offset(size.width * 0.9f, size.height * 0.2f),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** 在已挂载的 FocusRequester 列表里安全请求焦点：未组合的 Lazy 项会抛异常，降级返回 false。 */
private fun requestSourceFocus(requesters: List<FocusRequester>, index: Int): Boolean {
    val requester = requesters.getOrNull(index) ?: return false
    return runCatching { requester.requestFocus() }.isSuccess
}

// ---------------------------------------------------------------------------
// SourceSidebar 尺寸（需求一）
// ---------------------------------------------------------------------------
private val SIDEBAR_WIDTH = 320.dp
private val SIDEBAR_BACKGROUND = Color(0xFF1E1E1E)
private val SIDEBAR_BORDER_COLOR = Color(0xFF2E2E2E)
private val SIDEBAR_H_PADDING = 16.dp
private val SIDEBAR_V_PADDING = 18.dp
private val SIDEBAR_HEADER_GAP = 12.dp
private val SIDEBAR_HINT_GAP = 8.dp
private val SIDEBAR_TOP_GAP = 8.dp
private val SIDEBAR_TOP_HEIGHT = 34.dp
private const val SIDEBAR_TOP_SP = 14f
private val SIDEBAR_BUTTON_SHAPE = RoundedCornerShape(6.dp)
private val SIDEBAR_BUTTON_IDLE_BG = Color(0xFF2A2A2A)
private val SIDEBAR_DIVIDER_COLOR = Color(0xFF2E2E2E)
private val SIDEBAR_HINT_COLOR = Color(0x66FFFFFF)
private const val SIDEBAR_HINT_SP = 11f

private val SIDEBAR_ITEM_HEIGHT = 40.dp
private val SIDEBAR_ITEM_GAP = 4.dp
private const val SIDEBAR_ITEM_SP = 14f
private val SIDEBAR_ROW_SHAPE = RoundedCornerShape(6.dp)
private val SIDEBAR_ROW_IDLE_BG = Color(0xFF2A2A2A)
private val SIDEBAR_ROW_H_PADDING = 10.dp

private val SIDEBAR_GLYPH_SIZE = 20.dp
private val SIDEBAR_GLYPH_SHAPE = RoundedCornerShape(4.dp)
private const val SIDEBAR_GLYPH_SP = 10f
private val SIDEBAR_ICON_GAP = 8.dp

private val SIDEBAR_DOT_SIZE = 18.dp
private val SIDEBAR_DOT_BORDER_WIDTH = 2.dp
private val SIDEBAR_DOT_BORDER = Color(0x66FFFFFF)
private val SIDEBAR_DOT_CHECK_SIZE = 10.dp

private const val SIDEBAR_ANIM_MS = 220
private const val SIDEBAR_DIM_AMOUNT = 0.25f
private const val SOURCE_FOCUS_RETRY_FRAMES = 10
