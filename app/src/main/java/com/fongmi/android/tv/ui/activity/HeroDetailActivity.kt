package com.fongmi.android.tv.ui.activity

import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil3.compose.AsyncImage
import com.fongmi.android.tv.R
import com.fongmi.android.tv.ui.hero.HeroImageLoader

/**
 * HeroUI（Compose）详情页。
 *
 * 本阶段仍是假数据骨架，不触碰 PlayerManager / VideoActivity 等既有播放链路：
 * 按钮点击只做 UI 反馈，没有任何播放器调用。
 *
 * 主页面（自下而上）：
 * 1. [HeroDetailBackdrop]：全屏背景大图。
 * 2. [HeroDetailMaskHorizontal]：左侧纯黑 → 右侧透明的横向遮罩（`bg_hero_mask_h`）。
 * 3. [HeroDetailMaskVertical]：底部纯黑 → 顶部透明的纵向遮罩（`bg_hero_mask_v`）。
 * 4. 右上角浮动小海报 150dp × 210dp、marginTop 72dp、marginEnd 80dp、内边距 4dp。
 * 5. 左下内容区：剧名 / 元数据 / 简介 / **演职员** / 按钮组。
 *    线路与选集已整体移出主页面，收进 [HeroPlayDialog]。
 *
 * 尺寸/字号/边距仍逐条对照 `ref_docs/activity_detail.xml`（下文简称 XML，行号即该文件行号）：
 * - 内容容器：左右 padding 24dp、paddingBottom 0dp、整体贴底（L54-61）。
 * - 剧名：32sp 粗体 / 固定高 36dp / 贴底 / 阴影 #cc000000 2,2,12（L69-81）。
 * - 元数据：14sp / #ccffffff / 固定高 20dp / marginTop 2dp / 阴影 #aa000000 1.5,1.5,8（L90-102）。
 * - 简介：12sp / #bbffffff / 固定高 40dp / marginTop 4dp / maxLines 2 / 行距 +3dp / 占父宽一半（L103-124）。
 * - 按钮组：高 36dp、13sp 粗体、左右 padding 16dp、图标文字间距 4dp、按钮间距 12dp（L125-189）。
 *
 * 焦点契约：
 * - 主页面只有按钮组可聚焦，默认落在「播放」；↑/↓ 无处可去，直接消费，不会掉到演职员或文本上。
 * - 点「播放」→ [HeroPlayDialog]：焦点强制进入弹窗并落到 `lastWatchedEpisode`（无历史则第 1 集）。
 * - 返回键关闭弹窗后，焦点回到「播放」。
 */
class HeroDetailActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(AndroidColor.BLACK))
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        HeroImageLoader.install()
        // 首页只传剧名过来，其余字段继续走假数据；剧名为空时回落到默认假数据。
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { DEMO_DETAIL.title }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                HeroDetailScreen(detail = DEMO_DETAIL.copy(title = title))
            }
        }
    }

    companion object {

        private const val EXTRA_TITLE = "extra_title"

        /**
         * 统一入口：首页海报卡点击后调用。
         * 当前只有剧名是真实值（来自首页卡片），其余字段仍是 [DEMO_DETAIL] 假数据；
         * 页面也可用 adb 直接拉起验证：
         * `adb shell am start -n com.silent.android.webhtv/com.fongmi.android.tv.ui.activity.HeroDetailActivity`
         */
        fun start(context: Context, title: String) {
            context.startActivity(
                Intent(context, HeroDetailActivity::class.java).putExtra(EXTRA_TITLE, title),
            )
        }
    }
}

@Composable
private fun HeroDetailScreen(detail: HeroDetail) {
    // 主页面只剩一组可聚焦按钮：跨区移动全部显式 requestFocus，不依赖 2D 焦点搜索。
    val actionRequesters = remember { List(DETAIL_ACTIONS.size) { FocusRequester() } }
    var focusedAction by remember { mutableIntStateOf(0) }
    var showPlayDialog by remember { mutableStateOf(false) }

    // 首次进入落「播放」；弹窗关闭（含返回键）后也回到「播放」。
    LaunchedEffect(showPlayDialog) {
        if (showPlayDialog) return@LaunchedEffect
        repeat(3) {
            if (requestDetailFocus(actionRequesters, DETAIL_ACTION_PLAY)) return@LaunchedEffect
            withFrameNanos { }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // ---- 底层：全屏背景 + 两层遮罩（顺序：背景 → 横向遮罩 → 纵向遮罩）----
        HeroDetailBackdrop(url = detail.backdropUrl)
        HeroDetailMaskHorizontal()
        HeroDetailMaskVertical()

        // ---- 右上角浮动小海报：150dp × 210dp（XML posterCard L30-48）----
        HeroDetailPoster(
            url = detail.posterUrl,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = POSTER_TOP_GAP, end = POSTER_END_GAP),
        )

        // ---- 左下内容区：整体贴底，左右 padding 各 24dp、paddingBottom 0dp（XML L54-61）----
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = HERO_PADDING, end = HERO_PADDING),
        ) {
            // 纯展示文本：不挂 focusable / clickable，遥控器无法选中。
            // 剧名固定 36dp、文字贴底（XML L69-81）。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(DETAIL_TITLE_HEIGHT),
                contentAlignment = Alignment.BottomStart,
            ) {
                Text(
                    text = detail.title,
                    color = Color.White,
                    fontSize = DETAIL_TITLE_SP.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(shadow = DETAIL_TITLE_SHADOW),
                )
            }
            Spacer(modifier = Modifier.height(DETAIL_TITLE_META_GAP))
            // 元数据固定 20dp、marginTop 2dp、文字贴底（XML L90-102）。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(DETAIL_META_HEIGHT),
                contentAlignment = Alignment.BottomStart,
            ) {
                Text(
                    text = detail.meta,
                    color = DETAIL_META_COLOR,
                    fontSize = DETAIL_META_SP.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(shadow = DETAIL_META_SHADOW),
                )
            }
            Spacer(modifier = Modifier.height(DETAIL_META_DESC_GAP))
            // 简介：marginTop 4dp、固定 40dp、maxLines 2、行距 +3dp；
            // 外层 weightSum=2 + 子项 weight=1 → 实际只占父宽的一半（XML L103-124）。
            Text(
                text = detail.synopsis,
                modifier = Modifier
                    .fillMaxWidth(DETAIL_DESC_WIDTH_FRACTION)
                    .height(DETAIL_DESC_HEIGHT),
                color = DETAIL_DESC_COLOR,
                fontSize = DETAIL_DESC_SP.sp,
                lineHeight = DETAIL_DESC_LINE_HEIGHT_SP.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(shadow = DETAIL_DESC_SHADOW),
            )

            Spacer(modifier = Modifier.height(DETAIL_CAST_TOP_GAP))
            HeroCastSection(cast = detail.cast)
            Spacer(modifier = Modifier.height(DETAIL_ACTIONS_TOP_GAP))
            HeroDetailActionRow(
                requesters = actionRequesters,
                onFocused = { index -> focusedAction = index },
                // 「播放」拉起播放弹窗（线路 + 选集），其余按钮本阶段仍是占位。
                onConfirm = { index -> if (index == DETAIL_ACTION_PLAY) showPlayDialog = true },
                onMoveLeft = { index -> if (index > 0) focusAction(actionRequesters, index - 1) { focusedAction = it } },
                onMoveRight = { index ->
                    if (index < actionRequesters.lastIndex) focusAction(actionRequesters, index + 1) { focusedAction = it }
                },
            )
        }
    }

    if (showPlayDialog) {
        HeroPlayDialog(detail = detail, onDismiss = { showPlayDialog = false })
    }
}

/** 全屏背景大图：固定假数据，本阶段不随线路/选集变化。 */
@Composable
private fun HeroDetailBackdrop(url: String) {
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * 横向遮罩（原版 `bg_hero_mask_h`）：左侧纯黑 → 右侧透明。
 *
 * 严格使用 `Color.Black` → `Color.Transparent` 两个端点，保证左侧足够暗、右侧海报不被压灰；
 * 中间不做额外插值，避免出现「左不够黑、右又被灰掉」的折中效果。
 */
@Composable
private fun HeroDetailMaskHorizontal() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.horizontalGradient(
                    0.0f to Color.Black,
                    1.0f to Color.Transparent,
                ),
            ),
    )
}

/** 纵向遮罩（原版 `bg_hero_mask_v`）：底部纯黑 → 顶部透明，托住左下角信息区。 */
@Composable
private fun HeroDetailMaskVertical() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0.0f to Color.Transparent,
                    1.0f to Color.Black,
                ),
            ),
    )
}

/**
 * 右上角浮动小海报：150dp × 210dp，marginTop 72dp、marginEnd 80dp、内边距 4dp
 * （XML `posterCard` L30-48：`shape_poster_card` 圆角 12dp + `black_20` 底 + `clipToOutline`）。
 * 只做视觉锚点，不参与焦点流转（不挂 focusable）。
 */
@Composable
private fun HeroDetailPoster(url: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(width = POSTER_WIDTH, height = POSTER_HEIGHT)
            .clip(POSTER_SHAPE)
            .background(POSTER_BACKGROUND)
            .padding(POSTER_INNER_PADDING),
    ) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * 演职员区（Netflix 风格）：标题「导演 / 主演」+ 10 个圆形头像。
 *
 * **刻意不可聚焦**：整块没有任何 focusable / clickable 修饰符，默认焦点永远留在按钮组。
 * 头像 60dp 圆形、1dp 浅灰描边、下方 12sp 名字单行省略。
 */
@Composable
private fun HeroCastSection(cast: List<HeroCast>) {
    HeroDetailSectionTitle(text = "导演 / 主演", fontSize = CAST_TITLE_SP.sp)
    Spacer(modifier = Modifier.height(CAST_TITLE_GAP))
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(CAST_ITEM_GAP),
        // 列表铺到屏幕右缘，最后一位演员可以贴边滑出，不做硬性截断。
        contentPadding = PaddingValues(end = HERO_PADDING),
    ) {
        itemsIndexed(items = cast, key = { _, item -> item.name }) { _, item ->
            HeroCastItem(cast = item)
        }
    }
}

@Composable
private fun HeroCastItem(cast: HeroCast) {
    Column(
        modifier = Modifier.width(CAST_ITEM_WIDTH),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 圆形头像：先 clip 再 border，描边画在图片之上，1dp 也看得见。
        Box(
            modifier = Modifier
                .size(CAST_AVATAR_SIZE)
                .clip(CircleShape)
                .background(CAST_AVATAR_BACKGROUND)
                .border(CAST_AVATAR_BORDER, CAST_AVATAR_BORDER_COLOR, CircleShape),
        ) {
            AsyncImage(
                model = cast.avatarUrl,
                contentDescription = cast.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(modifier = Modifier.height(CAST_NAME_GAP))
        Text(
            text = cast.name,
            modifier = Modifier.fillMaxWidth(),
            color = CAST_NAME_COLOR,
            fontSize = CAST_NAME_SP.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** 按钮组：播放 / 收藏 / 换源，横向排列、间距 12dp。 */
@Composable
private fun HeroDetailActionRow(
    requesters: List<FocusRequester>,
    onFocused: (Int) -> Unit,
    onConfirm: (Int) -> Unit,
    onMoveLeft: (Int) -> Unit,
    onMoveRight: (Int) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(DETAIL_ACTION_GAP)) {
        DETAIL_ACTIONS.forEachIndexed { index, action ->
            HeroDetailActionButton(
                action = action,
                requester = requesters[index],
                onFocused = { onFocused(index) },
                onConfirm = { onConfirm(index) },
                onMoveLeft = { onMoveLeft(index) },
                onMoveRight = { onMoveRight(index) },
            )
        }
    }
}

/**
 * 单颗按钮：高 36dp、字号 13sp、图标在左文字在右。
 *
 * 焦点态按需求取 [HeroAccentColor] 纯红底 + 纯白文字/图标；
 * 常态沿用原版 `shape_detail_item_normal` 的 `white_10`（`#1AFFFFFF`）底 + 4dp 圆角。
 *
 * 上下键直接消费：主页面下方已经没有其他可聚焦区域，不消费会让焦点搜索落到别处。
 */
@Composable
private fun HeroDetailActionButton(
    action: HeroDetailAction,
    requester: FocusRequester,
    onFocused: () -> Unit,
    onConfirm: () -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val isFocused by interaction.collectIsFocusedAsState()

    Row(
        modifier = Modifier
            .height(DETAIL_ACTION_HEIGHT)
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

                    Key.DirectionUp, Key.DirectionDown -> true

                    else -> false
                }
            }
            .clip(DETAIL_ACTION_SHAPE)
            .background(if (isFocused) HeroAccentColor else DETAIL_ACTION_IDLE_BACKGROUND)
            .clickable(interactionSource = interaction, indication = null, onClick = onConfirm)
            .padding(horizontal = DETAIL_ACTION_H_PADDING),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DETAIL_ACTION_ICON_GAP),
    ) {
        Image(
            painter = painterResource(id = action.iconRes),
            contentDescription = null,
            colorFilter = ColorFilter.tint(Color.White),
            modifier = Modifier.size(action.iconSize),
        )
        Text(
            text = action.label,
            color = Color.White,
            fontSize = DETAIL_ACTION_SP.sp,
            // XML L134：textStyle="bold"，三颗按钮全部加粗。
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/** 区块标题：前方 4dp × 16dp 红色小竖条 + [fontSize] 加粗文字。 */
@Composable
private fun HeroDetailSectionTitle(text: String, fontSize: TextUnit = SECTION_TITLE_SP.sp) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .width(SECTION_BAR_WIDTH)
                .height(SECTION_BAR_HEIGHT)
                .background(HeroAccentColor, SECTION_BAR_SHAPE),
        )
        Spacer(modifier = Modifier.width(SECTION_BAR_GAP))
        Text(
            text = text,
            color = Color.White,
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------------------
// 播放弹窗：线路 Tab + 选集网格
// ---------------------------------------------------------------------------

/**
 * 播放弹窗：半透明深色 + 24dp 大圆角。
 *
 * 焦点契约：
 * - 打开即强制进入弹窗，并落到 `lastWatchedEpisode`（1 基，无历史时落第 1 集）。
 * - 线路 Tab 与选集网格双向：Tab ↓ 回网格、网格第 1 行 ↑ 回 Tab。
 * - 网格最右列按右键切下一组，焦点落新组第一项；最左列按左键切上一组。
 * - 返回键由 Dialog 自己消费并回调 [onDismiss]，主页面随即把焦点还给「播放」。
 */
@Composable
private fun HeroPlayDialog(detail: HeroDetail, onDismiss: () -> Unit) {
    val pageCount = ((detail.episodes.size + EPISODE_PAGE_SIZE - 1) / EPISODE_PAGE_SIZE).coerceAtLeast(1)
    val initialEpisode = (detail.lastWatchedEpisode - 1).coerceIn(0, detail.episodes.lastIndex.coerceAtLeast(0))
    val initialPage = initialEpisode / EPISODE_PAGE_SIZE
    val initialCell = initialEpisode % EPISODE_PAGE_SIZE

    var page by remember { mutableIntStateOf(initialPage) }
    var focusedCell by remember { mutableIntStateOf(initialCell) }
    var focusedFlag by remember { mutableIntStateOf(0) }
    var playingEpisode by remember { mutableIntStateOf(initialEpisode) }
    // 换组/首次打开后要点亮的格子下标，由 page 的 LaunchedEffect 消费。
    var pendingCell by remember { mutableIntStateOf(initialCell) }

    val flagRequesters = remember { List(detail.flags.size) { FocusRequester() } }
    val cellRequesters = remember { List(EPISODE_PAGE_SIZE) { FocusRequester() } }

    val pageItems = remember(page, detail.episodes) {
        detail.episodes.drop(page * EPISODE_PAGE_SIZE).take(EPISODE_PAGE_SIZE)
    }

    // 弹窗是独立窗口：首帧可能还没拿到 window focus，逐帧重试到焦点真正落下为止。
    LaunchedEffect(page) {
        val target = pendingCell.coerceIn(0, pageItems.lastIndex.coerceAtLeast(0))
        repeat(5) {
            if (requestDetailFocus(cellRequesters, target)) {
                focusedCell = target
                return@LaunchedEffect
            }
            withFrameNanos { }
        }
    }

    fun focusCell(index: Int) {
        val target = index.coerceIn(0, pageItems.lastIndex.coerceAtLeast(0))
        if (requestDetailFocus(cellRequesters, target)) focusedCell = target
    }

    fun focusFlag(index: Int) {
        val target = index.coerceIn(0, flagRequesters.lastIndex.coerceAtLeast(0))
        if (requestDetailFocus(flagRequesters, target)) focusedFlag = target
    }

    // 切组：先把 pendingCell 归零，等 page 变化后的 LaunchedEffect 把焦点放到新组第一项。
    fun switchPage(targetPage: Int) {
        if (targetPage !in 0 until pageCount) return
        focusedCell = 0
        pendingCell = 0
        page = targetPage
    }

    fun handleCellKey(cellIndex: Int, key: Key) {
        val column = cellIndex % EPISODE_COLUMNS
        val lastIndex = pageItems.lastIndex
        when (key) {
            Key.DirectionLeft ->
                if (column > 0) focusCell(cellIndex - 1) else switchPage(page - 1)

            Key.DirectionRight ->
                if (column == EPISODE_COLUMNS - 1) {
                    switchPage(page + 1)
                } else if (cellIndex < lastIndex) {
                    focusCell(cellIndex + 1)
                }

            Key.DirectionUp ->
                if (cellIndex >= EPISODE_COLUMNS) focusCell(cellIndex - EPISODE_COLUMNS) else focusFlag(focusedFlag)

            Key.DirectionDown ->
                if (cellIndex + EPISODE_COLUMNS <= lastIndex) focusCell(cellIndex + EPISODE_COLUMNS)
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
        // 全屏遮罩 + 居中的深色面板；遮罩让底图继续透出来一点，形成「半透明」层次。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(DIALOG_SCRIM),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(DIALOG_WIDTH_FRACTION)
                    .clip(DIALOG_SHAPE)
                    .background(DIALOG_BACKGROUND)
                    .border(DIALOG_BORDER, DIALOG_BORDER_COLOR, DIALOG_SHAPE)
                    .padding(DIALOG_PADDING),
            ) {
                HeroDetailSectionTitle(text = "播放线路")
                Spacer(modifier = Modifier.height(DIALOG_TITLE_GAP))
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(DETAIL_FLAG_ROW_HEIGHT),
                    horizontalArrangement = Arrangement.spacedBy(DETAIL_FLAG_GAP),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    itemsIndexed(items = detail.flags, key = { index, _ -> index }) { index, label ->
                        HeroDetailFlagItem(
                            label = label,
                            selected = index == 0,
                            requester = flagRequesters[index],
                            onFocused = { focusedFlag = index },
                            onConfirm = { },
                            // Tab ↑ 无处可去，直接消费。
                            onMoveUp = { },
                            // Tab ↓ 回选集网格，停在离开时的那一格。
                            onMoveDown = { focusCell(focusedCell) },
                            onMoveLeft = { if (index > 0) focusFlag(index - 1) },
                            onMoveRight = { if (index < detail.flags.lastIndex) focusFlag(index + 1) },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(DIALOG_SECTION_GAP))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HeroDetailSectionTitle(text = "选集")
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "第 ${page + 1} / $pageCount 组",
                        color = DIALOG_PAGE_INDICATOR_COLOR,
                        fontSize = DIALOG_PAGE_INDICATOR_SP.sp,
                        maxLines = 1,
                    )
                }
                Spacer(modifier = Modifier.height(DIALOG_TITLE_GAP))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(EPISODE_COLUMNS),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(DIALOG_GRID_HEIGHT),
                    contentPadding = PaddingValues(vertical = DIALOG_GRID_VERTICAL_PADDING),
                    horizontalArrangement = Arrangement.spacedBy(DIALOG_GRID_GAP),
                    verticalArrangement = Arrangement.spacedBy(DIALOG_GRID_GAP),
                ) {
                    pageItems.forEachIndexed { index, label ->
                        item(key = page * EPISODE_PAGE_SIZE + index) {
                            HeroEpisodeCell(
                                label = label,
                                playing = page * EPISODE_PAGE_SIZE + index == playingEpisode,
                                requester = cellRequesters[index],
                                onFocused = { focusedCell = index },
                                onConfirm = { playingEpisode = page * EPISODE_PAGE_SIZE + index },
                                onMove = { key -> handleCellKey(index, key) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 线路项：焦点/选中红底白字，常态 `white_10`。 */
@Composable
private fun HeroDetailFlagItem(
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
    val active = selected || isFocused

    Box(
        modifier = Modifier
            .height(DETAIL_FLAG_ROW_HEIGHT)
            .focusRequester(requester)
            .onFocusChanged {
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
            .clip(DETAIL_FLAG_SHAPE)
            .background(
                when {
                    isFocused -> HeroAccentColor
                    selected -> DETAIL_FLAG_SELECTED_BACKGROUND
                    else -> DETAIL_FLAG_IDLE_BACKGROUND
                },
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onConfirm)
            .padding(horizontal = DETAIL_FLAG_H_PADDING),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            // `adapter_flag.xml` L3：textSize=16sp、gravity=center。
            color = Color.White,
            fontSize = DETAIL_FLAG_SP.sp,
            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

/**
 * 选集格子：焦点态红框，当前播放集用红字区分。
 * 四方向键全部在本组件内消费，交给 `onMove` 统一做边界与跨组处理。
 */
@Composable
private fun HeroEpisodeCell(
    label: String,
    playing: Boolean,
    requester: FocusRequester,
    onFocused: () -> Unit,
    onConfirm: () -> Unit,
    onMove: (Key) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val isFocused by interaction.collectIsFocusedAsState()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(EPISODE_CELL_HEIGHT)
            .focusRequester(requester)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight -> {
                        onMove(event.key)
                        true
                    }

                    else -> false
                }
            }
            .clip(EPISODE_CELL_SHAPE)
            .background(if (isFocused) EPISODE_CELL_FOCUS_BACKGROUND else EPISODE_CELL_BACKGROUND)
            .then(
                if (isFocused) {
                    Modifier.border(EPISODE_CELL_BORDER, HeroAccentColor, EPISODE_CELL_SHAPE)
                } else {
                    Modifier
                },
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onConfirm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = EPISODE_CELL_H_PADDING)
                .then(if (isFocused) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier),
            color = if (playing) HeroAccentColor else Color.White,
            fontSize = EPISODE_CELL_SP.sp,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 在已挂载的 FocusRequester 列表里安全请求焦点：未组合的 Lazy 项会抛异常，这里降级返回 false。
 */
private fun requestDetailFocus(requesters: List<FocusRequester>, index: Int): Boolean {
    val requester = requesters.getOrNull(index) ?: return false
    return runCatching { requester.requestFocus() }.isSuccess
}

/** 按钮组的左右移动：统一走这里，成功后才更新记忆索引。 */
private fun focusAction(requesters: List<FocusRequester>, index: Int, onFocused: (Int) -> Unit) {
    if (requestDetailFocus(requesters, index)) onFocused(index)
}

/** 按钮组定义：图标、图标尺寸、文案全部集中在这里，新增按钮只改这个列表。 */
private data class HeroDetailAction(
    val label: String,
    val iconRes: Int,
    val iconSize: Dp,
)

/** 演职员假数据：姓名 + 头像地址（picsum 稳定图，不依赖 TMDB）。 */
private data class HeroCast(val name: String, val avatarUrl: String)

private data class HeroDetail(
    val title: String,
    val meta: String,
    val synopsis: String,
    val posterUrl: String,
    val backdropUrl: String,
    val cast: List<HeroCast>,
    val flags: List<String>,
    val episodes: List<String>,
    /** 历史播放进度（1 基集号）；0 表示新剧，焦点落第 1 集。 */
    val lastWatchedEpisode: Int,
)

/** 假数据：picsum 按 seed 返回稳定图片，不依赖 TMDB Key 即可验证 Coil 链路。 */
private val DEMO_DETAIL = HeroDetail(
    title = "后会无期",
    meta = "2026 · 剧情 · 全 24 集 · 9.1",
    synopsis = "三个男人一路向西，在公路尽头重新认识了自己。他们带着各自的秘密出发，" +
        "却在无人区里把话说得比过去十年都清楚。",
    posterUrl = "https://picsum.photos/seed/hero-detail-poster/300/420",
    backdropUrl = "https://picsum.photos/seed/hero-detail-backdrop/1280/720",
    cast = listOf(
        "李海潮", "陈屿", "林晚舟", "周野", "苏见月",
        "顾云深", "沈青禾", "许知白", "叶南", "宋一诺",
    ).mapIndexed { index, name ->
        HeroCast(name = name, avatarUrl = "https://picsum.photos/seed/hero-cast-$index/120/120")
    },
    flags = listOf("线路一", "线路二", "线路三"),
    episodes = (1..24).map { "第 $it 集" },
    // 演示「老剧吸附历史集」：第 7 集。新剧传 0 即可落回第 1 集。
    lastWatchedEpisode = 7,
)

private val DETAIL_ACTIONS = listOf(
    HeroDetailAction(label = "播放", iconRes = R.drawable.ic_widget_play, iconSize = 24.dp),
    HeroDetailAction(label = "收藏", iconRes = R.drawable.ic_detail_keep_off, iconSize = 20.dp),
    HeroDetailAction(label = "换源", iconRes = R.drawable.ic_detail_change, iconSize = 20.dp),
)

/** 全局左侧基准线：内容区左侧 padding 严格 24dp（原版详情页同值）。 */
private val HERO_PADDING = 24.dp

/** 主色：焦点/选中态红底、选集红框、区块红色小竖条统一使用。 */
private val HeroAccentColor = Color(0xFFFF0000)

/** 「播放」在按钮组里的固定下标。 */
private const val DETAIL_ACTION_PLAY = 0

// ---------------------------------------------------------------------------
// 左下内容区（activity_detail.xml detailTop L62-190）
// ---------------------------------------------------------------------------
private const val DETAIL_TITLE_SP = 32f
private val DETAIL_TITLE_HEIGHT = 36.dp
private val DETAIL_TITLE_META_GAP = 2.dp
private val DETAIL_META_HEIGHT = 20.dp
private const val DETAIL_META_SP = 14f
private val DETAIL_META_COLOR = Color(0xCCFFFFFF)
private val DETAIL_META_DESC_GAP = 4.dp
private val DETAIL_DESC_HEIGHT = 40.dp
private const val DETAIL_DESC_WIDTH_FRACTION = 0.5f
private const val DETAIL_DESC_SP = 12f
private const val DETAIL_DESC_LINE_HEIGHT_SP = 17f
private val DETAIL_DESC_COLOR = Color(0xBBFFFFFF)
private val DETAIL_ACTIONS_TOP_GAP = 18.dp
private val DETAIL_CAST_TOP_GAP = 18.dp

// XML 阴影参数是 px 值，Compose 的 Shadow 同样按 px 解释，因此直接照抄。
private val DETAIL_TITLE_SHADOW = Shadow(
    color = Color(0xCC000000),
    offset = Offset(2f, 2f),
    blurRadius = 12f,
)
private val DETAIL_META_SHADOW = Shadow(
    color = Color(0xAA000000),
    offset = Offset(1.5f, 1.5f),
    blurRadius = 8f,
)
private val DETAIL_DESC_SHADOW = Shadow(
    color = Color(0xAA000000),
    offset = Offset(1.2f, 1.2f),
    blurRadius = 6f,
)

// ---------------------------------------------------------------------------
// 演职员区（Netflix 风格）：60dp 圆形头像 + 12sp 名字，不可聚焦
// ---------------------------------------------------------------------------
private const val CAST_TITLE_SP = 14f
private val CAST_TITLE_GAP = 10.dp
private val CAST_AVATAR_SIZE = 60.dp
private val CAST_AVATAR_BORDER = 1.dp
private val CAST_AVATAR_BORDER_COLOR = Color(0x66FFFFFF)
private val CAST_AVATAR_BACKGROUND = Color(0xFF2A2A2A)
private val CAST_NAME_GAP = 6.dp
private const val CAST_NAME_SP = 12f
private val CAST_NAME_COLOR = Color(0xCCFFFFFF)
private val CAST_ITEM_WIDTH = 76.dp
private val CAST_ITEM_GAP = 12.dp

// ---------------------------------------------------------------------------
// 右上浮动海报 150dp × 210dp（activity_detail.xml posterCard L30-48）
// ---------------------------------------------------------------------------
private val POSTER_WIDTH = 150.dp
private val POSTER_HEIGHT = 210.dp
private val POSTER_TOP_GAP = 72.dp
private val POSTER_END_GAP = 80.dp
private val POSTER_INNER_PADDING = 4.dp
private val POSTER_SHAPE = RoundedCornerShape(12.dp)
private val POSTER_BACKGROUND = Color(0x33000000) // black_20

// ---------------------------------------------------------------------------
// 按钮组：高 36dp / 字号 13sp 加粗 / 间距 12dp（L125-189）
// ---------------------------------------------------------------------------
private val DETAIL_ACTION_HEIGHT = 36.dp
private const val DETAIL_ACTION_SP = 13f
private val DETAIL_ACTION_GAP = 12.dp
private val DETAIL_ACTION_ICON_GAP = 4.dp
private val DETAIL_ACTION_H_PADDING = 16.dp
private val DETAIL_ACTION_SHAPE = RoundedCornerShape(4.dp)
private val DETAIL_ACTION_IDLE_BACKGROUND = Color(0x1AFFFFFF) // white_10

// ---------------------------------------------------------------------------
// 区块标题：4dp × 16dp 红色小竖条 + 15sp 加粗
// ---------------------------------------------------------------------------
private val SECTION_BAR_WIDTH = 4.dp
private val SECTION_BAR_HEIGHT = 16.dp
private val SECTION_BAR_SHAPE = RoundedCornerShape(2.dp)
private val SECTION_BAR_GAP = 10.dp
private const val SECTION_TITLE_SP = 15f

// ---------------------------------------------------------------------------
// 弹窗内线路 Tab（activity_detail.xml L225-232 + adapter_flag.xml）
// ---------------------------------------------------------------------------
private val DETAIL_FLAG_ROW_HEIGHT = 34.dp
private val DETAIL_FLAG_GAP = 12.dp
private val DETAIL_FLAG_H_PADDING = 12.dp
private val DETAIL_FLAG_SHAPE = RoundedCornerShape(4.dp)
private const val DETAIL_FLAG_SP = 16f
private val DETAIL_FLAG_IDLE_BACKGROUND = Color(0x33000000)     // black_20
private val DETAIL_FLAG_SELECTED_BACKGROUND = Color(0x66000000) // black_40

// ---------------------------------------------------------------------------
// 播放弹窗
// ---------------------------------------------------------------------------
private const val DIALOG_WIDTH_FRACTION = 0.82f
private val DIALOG_SCRIM = Color(0x99000000)
private val DIALOG_SHAPE = RoundedCornerShape(24.dp)
private val DIALOG_BACKGROUND = Color(0xE6141414)   // 半透明深色面板
private val DIALOG_BORDER = 1.dp
private val DIALOG_BORDER_COLOR = Color(0x1FFFFFFF)
private val DIALOG_PADDING = 32.dp
private val DIALOG_TITLE_GAP = 10.dp
private val DIALOG_SECTION_GAP = 20.dp
private val DIALOG_PAGE_INDICATOR_COLOR = Color(0x99FFFFFF)
private const val DIALOG_PAGE_INDICATOR_SP = 12f

// ---------------------------------------------------------------------------
// 选集网格：5 列 × 4 行 = 20 集/组；240dp 高度正好容纳 4 行（16+46×4+8×3+16 = 240）
// ---------------------------------------------------------------------------
private const val EPISODE_COLUMNS = 5
private const val EPISODE_PAGE_SIZE = EPISODE_COLUMNS * 4
private val DIALOG_GRID_HEIGHT = 240.dp
private val DIALOG_GRID_VERTICAL_PADDING = 16.dp
private val DIALOG_GRID_GAP = 8.dp
private val EPISODE_CELL_HEIGHT = 46.dp
private val EPISODE_CELL_SHAPE = RoundedCornerShape(6.dp)
private val EPISODE_CELL_BORDER = 2.dp
private val EPISODE_CELL_H_PADDING = 8.dp
private val EPISODE_CELL_BACKGROUND = Color(0x14FFFFFF)
private val EPISODE_CELL_FOCUS_BACKGROUND = Color(0x1FFFFFFF)
private const val EPISODE_CELL_SP = 16f
