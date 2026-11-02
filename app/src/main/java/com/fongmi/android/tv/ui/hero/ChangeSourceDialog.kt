package com.fongmi.android.tv.ui.hero

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * 换源弹窗（ChangeSourceDialog）。
 *
 * 结构严格对照 `ref_docs/dialog_source.xml`，并复用选集弹窗的外壳：
 *
 * ```
 * 遮罩 0x99000000
 * └ HeroDialogShell：82% 宽 · #E6000000 · 16dp 圆角 · 18dp 内边距（与选集弹窗同一份实现）
 *   ├ 顶部栏 32dp：[换源] 红底徽章 24dp / 11sp + 右侧灰色「424个条目」11sp
 *   ├ 8dp
 *   ├ 站点栏 39dp：[小房子 26dp] + LazyRow 站点标签（14sp，可横向滚动）
 *   ├ 12dp
 *   ├ ▍换源结果（13sp 加粗，不可聚焦）
 *   ├ 8dp
 *   └ LazyRow：横向结果卡片（红绿渐变占位 + 日期 + 来源名 + 剧名）
 * ```
 *
 * 复用关系：
 * - 外壳 [HeroDialogShell]、区块标题 [HeroSectionTitle]、主色 [HeroAccentColor] 与选集弹窗共用；
 * - 「小房子」直接复用 [HeroHomeLogoButton]（26dp 容器 / 26dp 圆 / 16dp `ic_home_logo`），
 *   与首页顶栏是同一份实现，焦点态（红底 + 2dp 白圈）完全一致；
 * - 弹窗内小房子按确定键拉起 [SourceSidebar]，侧边栏是独立 Dialog 窗口，层级盖在弹窗之上。
 *
 * 焦点契约（需求二.5 / 三.2）：
 * - 打开落在站点栏第 1 个标签；
 * - 站点 ←/→ 在标签间移动，第 1 个继续 ← 落到小房子；小房子 → 回第 1 个标签；
 * - 站点 ↓（含小房子 ↓）进结果卡片（记住上次停留的卡片）；
 * - 结果卡片 ←/→ 横移，↑ 回站点栏（回到刚才停留的标签；若上次在小房子则回小房子），↓ 消费；
 * - 返回键关闭弹窗，宿主把焦点交还详情页「换源」按钮。
 */
@Composable
internal fun ChangeSourceDialog(
    detailTitle: String,
    sites: List<HeroSourceSite> = HERO_SOURCE_SITES,
    results: List<HeroSourceResult> = HERO_SOURCE_RESULTS,
    onDismiss: () -> Unit,
) {
    var selectedSite by remember { mutableIntStateOf(0) }
    // -1 = 小房子；其余 = 站点标签下标。结果卡片按 ↑ 时回到这里。
    var focusedSite by remember { mutableIntStateOf(0) }
    var selectedResult by remember { mutableIntStateOf(-1) }
    var focusedResult by remember { mutableIntStateOf(0) }
    var showSidebar by remember { mutableStateOf(false) }

    val houseRequester = remember { FocusRequester() }
    val siteRequesters = remember(sites.size) { List(sites.size) { FocusRequester() } }
    val resultRequesters = remember(results.size) { List(results.size) { FocusRequester() } }
    val siteListState = rememberLazyListState()
    val resultListState = rememberLazyListState()

    // 初始 pendingSite = 0：首帧就把焦点送到第 1 个站点标签。
    var pendingSite by remember { mutableIntStateOf(0) }
    var siteSerial by remember { mutableIntStateOf(0) }
    // 结果卡片初始不抢焦点：-1 表示「从未进入结果区」。
    var pendingResult by remember { mutableIntStateOf(-1) }
    var resultSerial by remember { mutableIntStateOf(0) }
    // 侧边栏关闭后回到小房子：serial 从 0 开始，首次组合不抢焦点。
    var houseFocusSerial by remember { mutableIntStateOf(0) }

    fun focusHouse() {
        focusedSite = -1
        runCatching { houseRequester.requestFocus() }
    }

    fun focusSite(index: Int) {
        if (sites.isEmpty()) {
            focusHouse()
            return
        }
        val target = index.coerceIn(0, sites.lastIndex)
        focusedSite = target
        pendingSite = target
        siteSerial++
    }

    fun focusResult(index: Int) {
        if (results.isEmpty()) return
        val target = index.coerceIn(0, results.lastIndex)
        focusedResult = target
        pendingResult = target
        resultSerial++
    }

    fun returnToSiteRow() {
        if (focusedSite < 0) focusHouse() else focusSite(focusedSite)
    }

    // 站点标签在 LazyRow 里：先直接请求，未组合时滚动到目标再逐帧重试。
    LaunchedEffect(pendingSite, siteSerial, sites.size) {
        if (sites.isEmpty()) return@LaunchedEffect
        val target = pendingSite.coerceIn(0, sites.lastIndex)
        repeat(2) {
            if (requestChangeFocus(siteRequesters, target)) return@LaunchedEffect
            runCatching { siteListState.scrollToItem(target) }
            withFrameNanos { }
        }
        repeat(CHANGE_FOCUS_RETRY_FRAMES) {
            if (requestChangeFocus(siteRequesters, target)) return@LaunchedEffect
            withFrameNanos { }
        }
    }

    LaunchedEffect(pendingResult, resultSerial, results.size) {
        if (pendingResult < 0 || results.isEmpty()) return@LaunchedEffect
        val target = pendingResult.coerceIn(0, results.lastIndex)
        repeat(2) {
            if (requestChangeFocus(resultRequesters, target)) return@LaunchedEffect
            runCatching { resultListState.scrollToItem(target) }
            withFrameNanos { }
        }
        repeat(CHANGE_FOCUS_RETRY_FRAMES) {
            if (requestChangeFocus(resultRequesters, target)) return@LaunchedEffect
            withFrameNanos { }
        }
    }

    LaunchedEffect(houseFocusSerial) {
        if (houseFocusSerial == 0) return@LaunchedEffect
        repeat(CHANGE_FOCUS_RETRY_FRAMES) {
            if (runCatching { houseRequester.requestFocus() }.isSuccess) return@LaunchedEffect
            withFrameNanos { }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(CHANGE_DIALOG_SCRIM),
            contentAlignment = Alignment.Center,
        ) {
            HeroDialogShell(modifier = Modifier.fillMaxWidth(CHANGE_DIALOG_WIDTH_FRACTION)) {
                // ---- 顶部栏：红底「换源」徽章 + 右侧灰色统计 ----
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(CHANGE_HEADER_HEIGHT),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .height(CHANGE_BADGE_HEIGHT)
                            .clip(CHANGE_BADGE_SHAPE)
                            .background(HeroAccentColor)
                            .padding(horizontal = CHANGE_BADGE_H_PADDING),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "换源",
                            color = Color.White,
                            fontSize = CHANGE_BADGE_SP.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "${HERO_SOURCE_RESULT_TOTAL}个条目",
                        color = CHANGE_COUNT_COLOR,
                        fontSize = CHANGE_COUNT_SP.sp,
                        maxLines = 1,
                    )
                }
                Spacer(modifier = Modifier.height(CHANGE_HEADER_GAP))

                // ---- 站点栏：小房子 + 横向滚动站点标签（39dp / 14sp，参考 adapter_v2_site）----
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(CHANGE_SITE_ROW_HEIGHT),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HeroHomeLogoButton(
                        requester = houseRequester,
                        containerSize = CHANGE_HOME_SIZE,
                        circleSize = CHANGE_HOME_SIZE,
                        iconSize = CHANGE_HOME_ICON_SIZE,
                        onFocused = { focusedSite = -1 },
                        onConfirm = { showSidebar = true },
                        onDirection = { key ->
                            when (key) {
                                Key.DirectionRight -> {
                                    focusSite(focusedSite.coerceAtLeast(0))
                                    true
                                }

                                Key.DirectionDown -> {
                                    focusResult(focusedResult)
                                    true
                                }

                                Key.DirectionLeft, Key.DirectionUp -> true
                                else -> false
                            }
                        },
                    )
                    Spacer(modifier = Modifier.width(CHANGE_HOME_GAP))
                    LazyRow(
                        state = siteListState,
                        modifier = Modifier
                            .weight(1f)
                            .height(CHANGE_SITE_ROW_HEIGHT),
                        horizontalArrangement = Arrangement.spacedBy(CHANGE_SITE_GAP),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        itemsIndexed(items = sites, key = { _, site -> site.id }) { index, site ->
                            ChangeSourceSiteTag(
                                label = site.name,
                                selected = index == selectedSite,
                                requester = siteRequesters[index],
                                onFocused = { focusedSite = index },
                                onConfirm = { selectedSite = index },
                                onMoveUp = { },
                                onMoveDown = { focusResult(focusedResult) },
                                onMoveLeft = { if (index == 0) focusHouse() else focusSite(index - 1) },
                                onMoveRight = { if (index < sites.lastIndex) focusSite(index + 1) },
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(CHANGE_SECTION_GAP))

                // ---- 结果区标题（红竖条 3×14dp + 13sp 加粗，不可聚焦）----
                HeroSectionTitle(text = "换源结果", fontSize = CHANGE_RESULT_TITLE_SP.sp)
                Spacer(modifier = Modifier.height(CHANGE_RESULT_TITLE_GAP))

                // ---- 结果卡片：横向滚动，红绿渐变占位 ----
                LazyRow(
                    state = resultListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(CHANGE_RESULT_CARD_HEIGHT),
                    horizontalArrangement = Arrangement.spacedBy(CHANGE_RESULT_GAP),
                ) {
                    itemsIndexed(items = results, key = { _, result -> result.id }) { index, result ->
                        ChangeSourceResultCard(
                            date = result.date,
                            source = result.source,
                            title = detailTitle,
                            gradientStart = result.gradientStart,
                            gradientEnd = result.gradientEnd,
                            selected = index == selectedResult,
                            requester = resultRequesters[index],
                            onFocused = { focusedResult = index },
                            onConfirm = { selectedResult = index },
                            onMoveUp = { returnToSiteRow() },
                            onMoveLeft = { if (index > 0) focusResult(index - 1) },
                            onMoveRight = { if (index < results.lastIndex) focusResult(index + 1) },
                        )
                    }
                }
            }
        }
    }

    // 侧边栏盖在换源弹窗之上；关闭后焦点回到弹窗内的小房子。
    if (showSidebar) {
        SourceSidebar(
            entries = HERO_SOURCE_ENTRIES,
            onDismissRequest = {
                showSidebar = false
                houseFocusSerial++
            },
        )
    }
}

/** 站点标签：39dp 高 / 14sp，常态深灰底，当前站点略亮，焦点红底白字。 */
@Composable
private fun ChangeSourceSiteTag(
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

    Box(
        modifier = Modifier
            .height(CHANGE_SITE_ROW_HEIGHT)
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
            .clip(CHANGE_SITE_SHAPE)
            .background(
                when {
                    isFocused -> HeroAccentColor
                    selected -> CHANGE_SITE_SELECTED_BG
                    else -> CHANGE_SITE_IDLE_BG
                },
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onConfirm)
            .padding(horizontal = CHANGE_SITE_H_PADDING),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = CHANGE_SITE_SP.sp,
            fontWeight = if (selected || isFocused) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

/**
 * 结果卡片：190dp × 118dp，红绿渐变占位 + 顶部日期 + 底部「来源名 / 剧名」。
 * 焦点红框、当前选中的结果白框；方向键全部由本组件消费，边界处不越界。
 */
@Composable
private fun ChangeSourceResultCard(
    date: String,
    source: String,
    title: String,
    gradientStart: Color,
    gradientEnd: Color,
    selected: Boolean,
    requester: FocusRequester,
    onFocused: () -> Unit,
    onConfirm: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val isFocused by interaction.collectIsFocusedAsState()
    val borderColor = when {
        isFocused -> HeroAccentColor
        selected -> Color.White
        else -> CHANGE_RESULT_BORDER
    }
    val borderWidth = if (isFocused || selected) 2.dp else 1.dp

    Box(
        modifier = Modifier
            .width(CHANGE_RESULT_CARD_WIDTH)
            .height(CHANGE_RESULT_CARD_HEIGHT)
            .focusRequester(requester)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionUp -> {
                        onMoveUp()
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

                    Key.DirectionDown -> true
                    else -> false
                }
            }
            .clip(CHANGE_RESULT_SHAPE)
            .background(Brush.linearGradient(listOf(gradientStart, gradientEnd)))
            .border(borderWidth, borderColor, CHANGE_RESULT_SHAPE)
            .clickable(interactionSource = interaction, indication = null, onClick = onConfirm),
    ) {
        // 底部压暗，保证来源名 / 剧名在渐变底上始终可读。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.45f to Color.Transparent,
                        1f to CHANGE_RESULT_SCRIM,
                    ),
                ),
        )
        // 顶部日期胶囊。
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(CHANGE_RESULT_PADDING)
                .clip(CHANGE_DATE_SHAPE)
                .background(CHANGE_DATE_BG)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(
                text = date,
                color = CHANGE_DATE_COLOR,
                fontSize = CHANGE_DATE_SP.sp,
                maxLines = 1,
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(CHANGE_RESULT_PADDING),
        ) {
            Text(
                text = source,
                color = CHANGE_RESULT_SOURCE_COLOR,
                fontSize = CHANGE_RESULT_SOURCE_SP.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = title,
                color = Color.White,
                fontSize = CHANGE_RESULT_TITLE_SP.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 在已挂载的 FocusRequester 列表里安全请求焦点：未组合的 Lazy 项会抛异常，降级返回 false。 */
private fun requestChangeFocus(requesters: List<FocusRequester>, index: Int): Boolean {
    val requester = requesters.getOrNull(index) ?: return false
    return runCatching { requester.requestFocus() }.isSuccess
}

// ---------------------------------------------------------------------------
// 换源弹窗尺寸（ref_docs/dialog_source.xml + 需求二）
// ---------------------------------------------------------------------------
private const val CHANGE_DIALOG_WIDTH_FRACTION = 0.82f
private val CHANGE_DIALOG_SCRIM = Color(0x99000000)

private val CHANGE_HEADER_HEIGHT = 32.dp
private val CHANGE_BADGE_HEIGHT = 24.dp
private val CHANGE_BADGE_SHAPE = RoundedCornerShape(4.dp)
private val CHANGE_BADGE_H_PADDING = 10.dp
private const val CHANGE_BADGE_SP = 11f
private const val CHANGE_COUNT_SP = 11f
private val CHANGE_COUNT_COLOR = Color(0x59FFFFFF)
private val CHANGE_HEADER_GAP = 8.dp

private val CHANGE_HOME_SIZE = 26.dp
private val CHANGE_HOME_ICON_SIZE = 16.dp
private val CHANGE_HOME_GAP = 6.dp
private val CHANGE_SITE_ROW_HEIGHT = 39.dp
private val CHANGE_SITE_GAP = 4.dp
private val CHANGE_SITE_H_PADDING = 12.dp
private val CHANGE_SITE_SHAPE = RoundedCornerShape(4.dp)
private const val CHANGE_SITE_SP = 14f
private val CHANGE_SITE_IDLE_BG = Color(0x1AFFFFFF)
private val CHANGE_SITE_SELECTED_BG = Color(0x33FFFFFF)

private val CHANGE_SECTION_GAP = 12.dp
private const val CHANGE_RESULT_TITLE_SP = 13f
private val CHANGE_RESULT_TITLE_GAP = 8.dp

private val CHANGE_RESULT_CARD_WIDTH = 190.dp
private val CHANGE_RESULT_CARD_HEIGHT = 118.dp
private val CHANGE_RESULT_GAP = 12.dp
private val CHANGE_RESULT_SHAPE = RoundedCornerShape(8.dp)
private val CHANGE_RESULT_BORDER = Color(0x1FFFFFFF)
private val CHANGE_RESULT_PADDING = 10.dp
private val CHANGE_RESULT_SCRIM = Color(0xD9000000)
private const val CHANGE_RESULT_SOURCE_SP = 11f
private val CHANGE_RESULT_SOURCE_COLOR = Color(0xCCFFFFFF)

private val CHANGE_DATE_SHAPE = RoundedCornerShape(4.dp)
private val CHANGE_DATE_BG = Color(0x99000000)
private val CHANGE_DATE_COLOR = Color(0xE6FFFFFF)
private const val CHANGE_DATE_SP = 10f

private const val CHANGE_FOCUS_RETRY_FRAMES = 10
