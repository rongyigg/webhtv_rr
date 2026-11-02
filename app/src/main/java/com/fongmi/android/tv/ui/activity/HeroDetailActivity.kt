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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil3.compose.AsyncImage
import com.fongmi.android.tv.R
import com.fongmi.android.tv.ui.hero.HeroImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * HeroUI（Compose）详情页骨架。
 *
 * 本阶段只做「结构 + 焦点」，数据全部是 [DEMO_DETAIL] 假数据，不触碰 PlayerManager / VideoActivity
 * 等既有播放链路：按钮点击只保留回调占位，没有任何 Intent 或播放器调用。
 *
 * 尺寸/字号/边距逐条对照 `ref_docs/activity_detail.xml`（下文简称 XML，行号即该文件行号）：
 * - 内容容器：左右 padding 24dp、paddingBottom 0dp、整体贴底（L54-61）。
 * - 剧名：32sp 粗体 / 固定高 36dp / 贴底 / 阴影 #cc000000 2,2,12（L69-81）。
 * - 元数据：14sp / #ccffffff / 固定高 20dp / marginTop 2dp / 阴影 #aa000000 1.5,1.5,8（L90-102）。
 * - 简介：12sp / #bbffffff / 固定高 40dp / marginTop 4dp / maxLines 2 / 行距 +3dp / 占父宽一半 / 阴影 #aa000000 1.2,1.2,6（L103-124）。
 * - 按钮组：marginTop 12dp、高 36dp、13sp 粗体、左右 padding 16dp、图标文字间距 4dp、按钮间距 12dp（L125-189）。
 * - 线路区：marginTop 12dp、标题行 marginBottom 8dp、列表高 34dp（L199-233）。
 * - 选集区：与线路区间距 2dp、标题行 marginBottom 0dp、列表高 140dp、列表左右 padding 6dp（L234-280）。
 *
 * 分层（自下而上）：
 * 1. [HeroDetailBackdrop]：全屏背景大图（XML `heroBg`）。
 * 2. [HeroDetailMaskHorizontal]：左侧纯黑 → 右侧透明的横向遮罩（`bg_hero_mask_h`）。
 * 3. [HeroDetailMaskVertical]：底部纯黑 → 顶部透明的纵向遮罩（`bg_hero_mask_v`）。
 * 4. 右上角浮动小海报 150dp × 210dp、marginTop 72dp、marginEnd 80dp、内边距 4dp（XML `posterCard` L30-48）。
 * 5. 左下角内容区：剧名 / 元数据 / 简介 / 按钮组 / 线路 / 选集。
 *
 * 两处**有意偏离**原版，按本次需求执行：
 * - 原版按钮焦点态是红色渐变（`shape_detail_play_focused` / `shape_detail_item_focused`），
 *   本页统一取纯红 [HeroAccentColor]，文字与图标纯白。
 * - 原版线路项焦点态是 `black_40` 底 + 1.5dp 白描边（`selector_item` → `shape_item_focused`），
 *   本页改为 [HeroAccentColor] 红底，与按钮、选集的红色焦点语言保持一致；
 *   未选中/已选中底色仍照抄 `black_20` / `black_40`。
 *
 * 焦点契约（三组可聚焦区，全部显式交接，不依赖 2D 自动搜索）：
 * `播放 ⇄ 收藏 ⇄ 换源` → ↓ → `线路` → ↓ → `选集`；↑ 按相反顺序退回。
 * 进入线路/选集时落回各自**记忆中的**上一个焦点项；进入新区域不会从第一项重新数。
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
    val scope = rememberCoroutineScope()

    // 四个可聚焦区各自持有 FocusRequester：跨区移动一律显式 requestFocus，
    // 避免 2D 焦点搜索在「按钮宽度 ≠ 线路标签宽度」时落到文本或空白处。
    val actionRequesters = remember { List(DETAIL_ACTIONS.size) { FocusRequester() } }
    val flagState = rememberLazyListState()
    val flagRequesters = remember { List(detail.flags.size) { FocusRequester() } }
    val episodeState = rememberLazyListState()
    val episodeRequesters = remember { List(detail.episodes.size) { FocusRequester() } }

    // 记忆索引：从按钮 ↓ 进线路、从线路 ↓ 进选集时，都落回上一次停留的那一项。
    var focusedAction by remember { mutableIntStateOf(0) }
    var focusedFlag by remember { mutableIntStateOf(0) }
    var focusedEpisode by remember { mutableIntStateOf(0) }
    var selectedFlag by remember { mutableIntStateOf(0) }

    fun focusAction(index: Int = focusedAction) {
        val target = index.coerceIn(0, actionRequesters.lastIndex)
        if (requestDetailFocus(actionRequesters, target)) focusedAction = target
    }

    fun focusFlag(index: Int = focusedFlag) {
        if (detail.flags.isEmpty()) return
        val target = index.coerceIn(0, detail.flags.lastIndex)
        focusLazyItem(scope, flagState, flagRequesters, target) { focusedFlag = target }
    }

    fun focusEpisode(index: Int = focusedEpisode) {
        if (detail.episodes.isEmpty()) return
        val target = index.coerceIn(0, detail.episodes.lastIndex)
        focusLazyItem(scope, episodeState, episodeRequesters, target) { focusedEpisode = target }
    }

    // 进页面默认焦点落在「播放」，在此之前不存在任何可聚焦的文本节点。
    LaunchedEffect(Unit) {
        requestDetailFocus(actionRequesters, 0)
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

        // ---- 左下角内容区：整体贴底，左右 padding 各 24dp、paddingBottom 0dp（XML L54-61）----
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

            Spacer(modifier = Modifier.height(DETAIL_ACTIONS_TOP_GAP))
            HeroDetailActionRow(
                requesters = actionRequesters,
                onFocused = { index -> focusedAction = index },
                onMoveUp = { },
                // 按钮 ↓：进入线路列表，落回上一次聚焦的线路。
                onMoveDown = { focusFlag() },
                onMoveLeft = { index -> if (index > 0) focusAction(index - 1) },
                onMoveRight = { index -> if (index < actionRequesters.lastIndex) focusAction(index + 1) },
            )

            Spacer(modifier = Modifier.height(DETAIL_SECTION_TOP_GAP))
            HeroDetailSectionTitle(text = "播放线路")
            Spacer(modifier = Modifier.height(DETAIL_FLAG_TITLE_GAP))
            HeroDetailFlagRow(
                flags = detail.flags,
                state = flagState,
                requesters = flagRequesters,
                selectedIndex = selectedFlag,
                onFocused = { index -> focusedFlag = index },
                onConfirm = { index -> selectedFlag = index },
                // 线路 ↑：退回按钮组（停在离开时的那颗按钮）。
                onMoveUp = { focusAction() },
                // 线路 ↓：进入选集列表。
                onMoveDown = { focusEpisode() },
                onMoveLeft = { index -> if (index > 0) focusFlag(index - 1) },
                onMoveRight = { index -> if (index < detail.flags.lastIndex) focusFlag(index + 1) },
            )

            // flagLayout marginBottom=2dp（XML L205）。
            Spacer(modifier = Modifier.height(DETAIL_SECTION_GAP))
            HeroDetailSectionTitle(text = "选集")
            // 选集标题与列表之间没有额外间距（XML L244 marginBottom=0dp）。
            Spacer(modifier = Modifier.height(DETAIL_EPISODE_TITLE_GAP))
            HeroDetailEpisodeColumn(
                episodes = detail.episodes,
                state = episodeState,
                requesters = episodeRequesters,
                onFocused = { index -> focusedEpisode = index },
                // 选集 ↑：在第一集时退回线路，其余情况在列表内上移。
                onMoveUp = { index -> if (index > 0) focusEpisode(index - 1) else focusFlag() },
                // 选集 ↓：列表内下移，到底后停在原地。
                onMoveDown = { index -> if (index < detail.episodes.lastIndex) focusEpisode(index + 1) },
            )
        }
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

/** 按钮组：播放 / 收藏 / 换源，横向排列、间距 12dp。 */
@Composable
private fun HeroDetailActionRow(
    requesters: List<FocusRequester>,
    onFocused: (Int) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onMoveLeft: (Int) -> Unit,
    onMoveRight: (Int) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(DETAIL_ACTION_GAP)) {
        DETAIL_ACTIONS.forEachIndexed { index, action ->
            HeroDetailActionButton(
                action = action,
                requester = requesters[index],
                onFocused = { onFocused(index) },
                // 骨架阶段：只保留点击占位，不接播放/收藏/换源逻辑。
                onConfirm = { },
                onMoveUp = onMoveUp,
                onMoveDown = onMoveDown,
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
 */
@Composable
private fun HeroDetailActionButton(
    action: HeroDetailAction,
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

    Row(
        modifier = Modifier
            .height(DETAIL_ACTION_HEIGHT)
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

/** 区块标题：前方 4dp × 16dp 红色小竖条 + 15sp 加粗文字。 */
@Composable
private fun HeroDetailSectionTitle(text: String) {
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
            fontSize = SECTION_TITLE_SP.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/** 线路列表：整行高 34dp，横向排列；焦点态红底白字，已选线路常亮红色。 */
@Composable
private fun HeroDetailFlagRow(
    flags: List<String>,
    state: LazyListState,
    requesters: List<FocusRequester>,
    selectedIndex: Int,
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
            .height(DETAIL_FLAG_ROW_HEIGHT),
        // XML L231-232：paddingStart 0dp、paddingEnd 24dp（父级已有 24dp，右侧合计留白 48dp）。
        contentPadding = PaddingValues(end = HERO_PADDING),
        horizontalArrangement = Arrangement.spacedBy(DETAIL_FLAG_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(items = flags, key = { index, _ -> index }) { index, label ->
            HeroDetailFlagItem(
                label = label,
                selected = index == selectedIndex,
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

    Box(
        modifier = Modifier
            .height(DETAIL_FLAG_ROW_HEIGHT)
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
            // XML adapter_flag.xml L3：textSize=16sp、gravity=center。
            color = Color.White,
            fontSize = DETAIL_FLAG_SP.sp,
            fontWeight = if (selected || isFocused) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

/**
 * 选集列表：固定高 140dp、纵向滚动、单列。
 * 文字 16sp 居中单行，聚焦时整行加 [HeroAccentColor] 红框 + 跑马灯。
 */
@Composable
private fun HeroDetailEpisodeColumn(
    episodes: List<String>,
    state: LazyListState,
    requesters: List<FocusRequester>,
    onFocused: (Int) -> Unit,
    onMoveUp: (Int) -> Unit,
    onMoveDown: (Int) -> Unit,
) {
    LazyColumn(
        state = state,
        modifier = Modifier
            .fillMaxWidth()
            .height(DETAIL_EPISODE_AREA_HEIGHT),
        // XML L279-280：RecyclerView paddingStart/End = 6dp。
        contentPadding = PaddingValues(horizontal = DETAIL_EPISODE_LIST_PADDING),
    ) {
        itemsIndexed(items = episodes, key = { index, _ -> index }) { index, label ->
            HeroDetailEpisodeItem(
                label = label,
                requester = requesters[index],
                onFocused = { onFocused(index) },
                onConfirm = { },
                onMoveUp = { onMoveUp(index) },
                onMoveDown = { onMoveDown(index) },
            )
        }
    }
}

@Composable
private fun HeroDetailEpisodeItem(
    label: String,
    requester: FocusRequester,
    onFocused: () -> Unit,
    onConfirm: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val isFocused by interaction.collectIsFocusedAsState()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(DETAIL_EPISODE_ITEM_HEIGHT)
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

                    // 单列纵向列表：左右不离开本列，避免焦点跳到背景或海报上。
                    Key.DirectionLeft, Key.DirectionRight -> true

                    else -> false
                }
            }
            .clip(DETAIL_EPISODE_SHAPE)
            .then(
                if (isFocused) {
                    Modifier.border(DETAIL_EPISODE_BORDER, HeroAccentColor, DETAIL_EPISODE_SHAPE)
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
                .padding(horizontal = DETAIL_EPISODE_H_PADDING)
                .then(if (isFocused) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier),
            color = Color.White,
            fontSize = DETAIL_EPISODE_SP.sp,
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

/**
 * 聚焦 Lazy 列表里的指定项：
 * 已组合时直接请求焦点（Compose 自带 bring-into-view 负责最小滚动）；
 * 目标项被 Lazy 回收时，先滚过去、等一帧再请求焦点。
 */
private fun focusLazyItem(
    scope: CoroutineScope,
    state: LazyListState,
    requesters: List<FocusRequester>,
    index: Int,
    onFocused: () -> Unit,
) {
    if (requestDetailFocus(requesters, index)) {
        onFocused()
        return
    }
    scope.launch {
        state.scrollToItem(index)
        withFrameNanos { }
        if (requestDetailFocus(requesters, index)) onFocused()
    }
}

/** 按钮组定义：图标、图标尺寸、文案全部集中在这里，新增按钮只改这个列表。 */
private data class HeroDetailAction(
    val label: String,
    val iconRes: Int,
    val iconSize: Dp,
)

private data class HeroDetail(
    val title: String,
    val meta: String,
    val synopsis: String,
    val posterUrl: String,
    val backdropUrl: String,
    val flags: List<String>,
    val episodes: List<String>,
)

/** 假数据：picsum 按 seed 返回稳定图片，不依赖 TMDB Key 即可验证 Coil 链路。 */
private val DEMO_DETAIL = HeroDetail(
    title = "后会无期",
    meta = "2026 · 剧情 · 全 24 集 · 9.1",
    synopsis = "三个男人一路向西，在公路尽头重新认识了自己。他们带着各自的秘密出发，" +
        "却在无人区里把话说得比过去十年都清楚。",
    posterUrl = "https://picsum.photos/seed/hero-detail-poster/300/420",
    backdropUrl = "https://picsum.photos/seed/hero-detail-backdrop/1280/720",
    flags = listOf("线路一", "线路二", "线路三"),
    episodes = (1..24).map { "第 $it 集" },
)

private val DETAIL_ACTIONS = listOf(
    HeroDetailAction(label = "播放", iconRes = R.drawable.ic_widget_play, iconSize = 24.dp),
    HeroDetailAction(label = "收藏", iconRes = R.drawable.ic_detail_keep_off, iconSize = 20.dp),
    HeroDetailAction(label = "换源", iconRes = R.drawable.ic_detail_change, iconSize = 20.dp),
)

/** 全局左侧基准线：需求指定内容区左侧 padding 严格 24dp（原版详情页同值）。 */
private val HERO_PADDING = 24.dp

/** 主色：焦点/选中态红底、选集红框、区块红色小竖条统一使用。 */
private val HeroAccentColor = Color(0xFFFF0000)

// ---------------------------------------------------------------------------
// 左下内容区（activity_detail.xml detailTop L62-190）
// ---------------------------------------------------------------------------
private const val DETAIL_TITLE_SP = 32f                    // L70
private val DETAIL_TITLE_HEIGHT = 36.dp                    // L77
private val DETAIL_TITLE_META_GAP = 2.dp                   // L98 layout_marginTop
private val DETAIL_META_HEIGHT = 20.dp                     // L97
private const val DETAIL_META_SP = 14f                     // L91
private val DETAIL_META_COLOR = Color(0xCCFFFFFF)          // L92
private val DETAIL_META_DESC_GAP = 4.dp                    // L107
private val DETAIL_DESC_HEIGHT = 40.dp                     // L116
private const val DETAIL_DESC_WIDTH_FRACTION = 0.5f        // L108 weightSum=2 + L122 weight=1
private const val DETAIL_DESC_SP = 12f                     // L110
private const val DETAIL_DESC_LINE_HEIGHT_SP = 17f         // L123 lineSpacingExtra=3dp
private val DETAIL_DESC_COLOR = Color(0xBBFFFFFF)          // L111
private val DETAIL_ACTIONS_TOP_GAP = 12.dp                 // L131
private val DETAIL_SECTION_TOP_GAP = 18.dp                 // L68 detailTop marginBottom 6dp + L204 flagLayout marginTop 12dp
private val DETAIL_FLAG_TITLE_GAP = 8.dp                   // L211 标题行 marginBottom
private val DETAIL_SECTION_GAP = 2.dp                      // L205 flagLayout marginBottom
private val DETAIL_EPISODE_TITLE_GAP = 0.dp                // L244 marginBottom=0dp

// XML 阴影参数是 px 值，Compose 的 Shadow 同样按 px 解释，因此直接照抄。
private val DETAIL_TITLE_SHADOW = Shadow(
    color = Color(0xCC000000),                              // L78
    offset = Offset(2f, 2f),                                // L79-80
    blurRadius = 12f,                                       // L81
)
private val DETAIL_META_SHADOW = Shadow(
    color = Color(0xAA000000),                              // L99
    offset = Offset(1.5f, 1.5f),                            // L100-101
    blurRadius = 8f,                                        // L102
)
private val DETAIL_DESC_SHADOW = Shadow(
    color = Color(0xAA000000),                              // L118
    offset = Offset(1.2f, 1.2f),                            // L119-120
    blurRadius = 6f,                                        // L121
)

// ---------------------------------------------------------------------------
// 右上浮动海报 150dp × 210dp（activity_detail.xml posterCard L30-48）
// ---------------------------------------------------------------------------
private val POSTER_WIDTH = 150.dp                          // L37
private val POSTER_HEIGHT = 210.dp                         // L38
private val POSTER_TOP_GAP = 72.dp                         // L39 layout_marginTop
private val POSTER_END_GAP = 80.dp                         // L40 layout_marginEnd
private val POSTER_INNER_PADDING = 4.dp                    // L34 padding
private val POSTER_SHAPE = RoundedCornerShape(12.dp)       // shape_poster_card.xml
private val POSTER_BACKGROUND = Color(0x33000000)          // black_20

// ---------------------------------------------------------------------------
// 按钮组：高 36dp / 字号 13sp 加粗 / 间距 12dp（L125-189）
// ---------------------------------------------------------------------------
private val DETAIL_ACTION_HEIGHT = 36.dp                   // L144
private const val DETAIL_ACTION_SP = 13f                   // L133
private val DETAIL_ACTION_GAP = 12.dp                      // L149 layout_marginEnd
private val DETAIL_ACTION_ICON_GAP = 4.dp                  // L146 drawablePadding
private val DETAIL_ACTION_H_PADDING = 16.dp                // L147-148
private val DETAIL_ACTION_SHAPE = RoundedCornerShape(4.dp)  // shape_detail_item_normal.xml
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
// 线路列表：高 34dp（activity_detail.xml L225-232 + adapter_flag.xml）
// ---------------------------------------------------------------------------
private val DETAIL_FLAG_ROW_HEIGHT = 34.dp                 // L230
private val DETAIL_FLAG_GAP = 12.dp
private val DETAIL_FLAG_H_PADDING = 12.dp                  // shape_item_*.xml padding left/right
private val DETAIL_FLAG_SHAPE = RoundedCornerShape(4.dp)    // shape_item_*.xml corners
private const val DETAIL_FLAG_SP = 16f                     // adapter_flag.xml L3
private val DETAIL_FLAG_IDLE_BACKGROUND = Color(0x33000000)     // black_20 → shape_item_normal
private val DETAIL_FLAG_SELECTED_BACKGROUND = Color(0x66000000) // black_40 → shape_item_activated

// ---------------------------------------------------------------------------
// 选集列表：高 140dp / 纵向滚动 / 16sp（activity_detail.xml L271-280 + adapter_episode.xml）
// ---------------------------------------------------------------------------
private val DETAIL_EPISODE_AREA_HEIGHT = 140.dp            // L278
private val DETAIL_EPISODE_LIST_PADDING = 6.dp             // L279-280 paddingStart/End
private val DETAIL_EPISODE_ITEM_HEIGHT = 34.dp             // 行高由代码决定，XML 未给出
private val DETAIL_EPISODE_H_PADDING = 12.dp               // 跑马灯左右留白
private val DETAIL_EPISODE_SHAPE = RoundedCornerShape(4.dp)
private val DETAIL_EPISODE_BORDER = 2.dp
private const val DETAIL_EPISODE_SP = 16f                  // adapter_episode.xml L12
