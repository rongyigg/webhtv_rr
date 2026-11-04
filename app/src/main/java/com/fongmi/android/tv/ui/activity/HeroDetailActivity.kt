package com.fongmi.android.tv.ui.activity

import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.scale
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
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil3.compose.AsyncImage
import com.fongmi.android.tv.R
import com.fongmi.android.tv.ui.hero.ChangeSourceDialog
import com.fongmi.android.tv.ui.hero.HERO_SOURCE_RESULTS
import com.fongmi.android.tv.ui.hero.HERO_SOURCE_RESULT_TOTAL
import com.fongmi.android.tv.ui.hero.HERO_SOURCE_SITES
import com.fongmi.android.tv.ui.hero.HeroAccentColor
import com.fongmi.android.tv.ui.hero.HeroDialogShell
import com.fongmi.android.tv.ui.hero.HeroFocusBrush
import com.fongmi.android.tv.ui.hero.HeroImageLoader
import com.fongmi.android.tv.ui.hero.HeroSectionTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * HeroUI（Compose）详情页。
 *
 * 本阶段仍是假数据骨架，不触碰 PlayerManager / VideoActivity 等既有播放链路：
 * 按钮点击只做 UI 反馈，没有任何播放器调用。
 *
 * 主页面（从下往上）：
 * 1. [HeroDetailBackdrop]：全屏背景大图。
 * 2. [HeroDetailMaskHorizontal]：左侧纯黑 → 右侧透明的横向遮罩（`bg_hero_mask_h`）。
 * 3. [HeroDetailMaskVertical]：底部纯黑 → 顶部透明的纵向遮罩（`bg_hero_mask_v`）。
 * 4. 右上角浮动小海报 150dp × 210dp、marginTop 72dp、marginEnd 80dp、内边距 4dp。
 * 5. 左下内容区（整体贴底、paddingBottom 32dp）：剧名/Logo → 元数据 → 简介 → 按钮组 → **演职员行**。
 *    线路与选集已整体移出主页面，收进 [HeroPlayDialog]。
 *
 * 尺寸/字号/边距逐条对照 `ref_docs/activity_detail.xml`（下文简称 XML，行号即该文件行号），
 * 本次需求（Logo 位、简介 4 行、按钮顺序、演职员可聚焦）与 XML 冲突处以需求为准：
 * - 内容容器：左右 padding 24dp、paddingBottom 32dp、整体贴底。
 * - 剧名：有 Logo → 高 72dp / 最宽 720dp / 等比靠左（XML `heroLogoImg` L82-89）；
 *   无 Logo → 32sp 粗体 / 高 36dp / 贴底 / `basicMarquee()` 跑马灯防溢出 / 阴影 #cc000000 2,2,12（L69-81）。
 * - 元数据：14sp / #ccffffff / 固定高 20dp / marginTop 2dp / 阴影 #aa000000 1.5,1.5,8（L90-102）。
 * - 简介：12sp / #bbffffff / 最宽 440dp / 最多 4 行 / 行距 +3dp（XML 原为半宽 2 行，按需求放宽）。
 * - 按钮组：播放 / 换源 / 收藏，高 36dp、13sp 粗体、左右 padding 16dp、图标文字间距 4dp、按钮间距 12dp（L125-189）。
 * - 演职员：`LazyRow`，60dp 圆形头像 + 12sp 单行名字；焦点态红圈 + 1.08 倍放大。
 *
 * 焦点契约：
 * - 默认落在「播放」；←/→ 在按钮间移动。
 * - 「播放」按 ↓ → 演职员行（落在上次停留的演员）；演员行 ↑ → 回到「播放」；行内 ←/→ 横移，↓ 消费。
 * - 点「播放」→ [HeroPlayDialog]：按 `lastWatchedEpisodeIndex` 强制吸附历史集（-1 落第 1 集），
 *   并把该集所在分组自动切好。
 * - 点某位演员 → [HeroActorDialog]（立即弹出、数据异步加载）；关闭后焦点**回到被点的那位演员**。
 * - 纯展示文本（剧名/元数据/简介）没有任何 `focusable` / `clickable` 修饰符。
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
    val context = LocalContext.current
    // 两组可聚焦区域：按钮组 + 演职员行。跨区移动全部显式 requestFocus，不依赖 2D 焦点搜索。
    val actionRequesters = remember { List(DETAIL_ACTIONS.size) { FocusRequester() } }
    val castRequesters = remember(detail.cast.size) { List(detail.cast.size) { FocusRequester() } }
    var focusedAction by remember { mutableIntStateOf(DETAIL_ACTION_PLAY) }
    var focusedCast by remember { mutableIntStateOf(0) }
    var showPlayDialog by remember { mutableStateOf(false) }
    var showSourceDialog by remember { mutableStateOf(false) }
    // 正在查看的演员下标；非 null 时演员详情弹窗可见。
    var actorDialogIndex by remember { mutableStateOf<Int?>(null) }
    // 弹窗关闭后要回去的演员下标；-1 表示「没有待恢复的演员焦点」。
    var pendingCastFocus by remember { mutableIntStateOf(-1) }
    // 弹窗关闭后要回去的按钮下标；-1 表示「没有待恢复的按钮焦点」。
    // 换源弹窗关闭时必须回到「换源」，而不是默认的「播放」。
    var pendingActionFocus by remember { mutableIntStateOf(-1) }

    // 三个弹窗都关闭后才抢焦点：
    // - 刚关掉演员弹窗 → 回到被点的那位演员（需求：焦点必须回到刚才点击的头像）；
    // - 刚关掉换源弹窗 → 回到「换源」按钮（需求三.3）；
    // - 否则（首次进入 / 关闭播放弹窗）→ 回到「播放」。
    LaunchedEffect(showPlayDialog, showSourceDialog, actorDialogIndex) {
        if (showPlayDialog || showSourceDialog || actorDialogIndex != null) return@LaunchedEffect
        val castTarget = pendingCastFocus
        pendingCastFocus = -1
        if (castTarget >= 0) {
            repeat(5) {
                if (requestDetailFocus(castRequesters, castTarget)) return@LaunchedEffect
                withFrameNanos { }
            }
            return@LaunchedEffect
        }
        val actionTarget = pendingActionFocus
        pendingActionFocus = -1
        if (actionTarget >= 0) {
            repeat(5) {
                if (requestDetailFocus(actionRequesters, actionTarget)) return@LaunchedEffect
                withFrameNanos { }
            }
            return@LaunchedEffect
        }
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

        // ---- 左下内容区：整体贴底，左右 24dp、底部 32dp 防贴屏幕边缘 ----
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(
                    start = HERO_PADDING,
                    end = HERO_PADDING,
                    bottom = CONTENT_BOTTOM_PADDING,
                ),
        ) {
            // 1. 剧名：有 Logo 显示艺术字，没有则 32sp 文字跑马灯（纯展示，不可聚焦）。
            HeroDetailTitle(detail = detail)
            Spacer(modifier = Modifier.height(DETAIL_TITLE_META_GAP))

            // 2. 元数据固定 20dp、文字贴底（XML L90-102）。
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

            // 3. 简介：最宽 440dp、最多 4 行、超出省略（纯展示，不可聚焦）。
            Text(
                text = detail.synopsis,
                modifier = Modifier.widthIn(max = DETAIL_DESC_MAX_WIDTH),
                color = DETAIL_DESC_COLOR,
                fontSize = DETAIL_DESC_SP.sp,
                lineHeight = DETAIL_DESC_LINE_HEIGHT_SP.sp,
                maxLines = DETAIL_DESC_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(shadow = DETAIL_DESC_SHADOW),
            )

            // 4. 按钮组：紧跟在简介下方，默认焦点「播放」。
            Spacer(modifier = Modifier.height(DETAIL_ACTIONS_TOP_GAP))
            HeroDetailActionRow(
                requesters = actionRequesters,
                onFocused = { index -> focusedAction = index },
                // 「播放」拉起选集弹窗；「换源」拉起换源弹窗（收藏仍是占位）。
                onConfirm = { index ->
                    when (index) {
                        DETAIL_ACTION_PLAY -> showPlayDialog = true
                        DETAIL_ACTION_CHANGE_SOURCE -> {
                            pendingActionFocus = DETAIL_ACTION_CHANGE_SOURCE
                            showSourceDialog = true
                            // 换源弹窗改为 View 体系独立 Dialog；showSourceDialog 只用于关闭后的焦点交还。
                            ChangeSourceDialog.show(
                                context = context,
                                sites = HERO_SOURCE_SITES,
                                currentSite = 0,
                                results = HERO_SOURCE_RESULTS,
                                total = HERO_SOURCE_RESULT_TOTAL,
                                onClosed = { showSourceDialog = false },
                            )
                        }
                    }
                },
                onMoveLeft = { index -> if (index > 0) focusAction(actionRequesters, index - 1) { focusedAction = it } },
                onMoveRight = { index ->
                    if (index < actionRequesters.lastIndex) focusAction(actionRequesters, index + 1) { focusedAction = it }
                },
                // ↓ 进入最底部的演职员行，落在上次停留的那位演员。
                onMoveDown = { focusCast(castRequesters, focusedCast) },
            )

            // 5. 演职员行（最底部）：可横向浏览、可点击，↑ 回按钮组。
            Spacer(modifier = Modifier.height(DETAIL_CAST_TOP_GAP))
            HeroCastSection(
                cast = detail.cast,
                requesters = castRequesters,
                onFocused = { index -> focusedCast = index },
                // 点击瞬间先记下「要回到哪个头像」，再同步打开弹窗（不等网络）。
                onConfirm = { index ->
                    pendingCastFocus = index
                    actorDialogIndex = index
                },
                onMoveUp = { focusAction(actionRequesters, DETAIL_ACTION_PLAY) { focusedAction = it } },
                onMoveLeft = { index -> if (index > 0) focusCast(castRequesters, index - 1) },
                onMoveRight = { index ->
                    if (index < castRequesters.lastIndex) focusCast(castRequesters, index + 1)
                },
            )
        }
    }

    if (showPlayDialog) {
        HeroPlayDialog(
            detail = detail,
            // 假数据阶段：手动传 14 = 第 15 集，模拟「上次看到第 15 集」；
            // 接入本地库后改成 History 里的真实索引（-1 表示没看过）。
            lastWatchedEpisodeIndex = DEMO_LAST_WATCHED_INDEX,
            // 预留接口：这里就是未来写 History 的地方（参数量纲 = 0 基升序索引）。
            // TODO(接入历史库)：History.push(detail.title, index)，目前只占位、不改播放链路。
            onEpisodeSelected = { _ -> },
            onDismiss = { showPlayDialog = false },
        )
    }

    // 演员详情：点击即弹出，数据在弹窗内部异步加载。
    actorDialogIndex?.let { index ->
        HeroActorDialog(
            actor = detail.cast[index],
            onDismiss = { actorDialogIndex = null },
        )
    }
}

/**
 * 剧名区：兼容 TMDB 艺术字 Logo。
 *
 * - 有 Logo：高 72dp、最大宽 720dp、等比缩放、靠左（XML `heroLogoImg` L82-89，`scaleType=fitStart`）。
 * - 无 Logo：32sp 粗体文字 + [basicMarquee]，长剧名持续滚动而不是被省略号吞掉。
 * 两种情况都不挂 `focusable` / `clickable`，遥控器选不中。
 */
@Composable
private fun HeroDetailTitle(detail: HeroDetail) {
    val logo = detail.logoUrl
    if (!logo.isNullOrBlank()) {
        AsyncImage(
            model = logo,
            contentDescription = detail.title,
            contentScale = ContentScale.Fit,
            // 等比缩放后仍靠左，避免窄 Logo 在 720dp 的盒子里居中。
            alignment = Alignment.CenterStart,
            modifier = Modifier
                .height(LOGO_HEIGHT)
                .widthIn(max = LOGO_MAX_WIDTH),
        )
        return
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(DETAIL_TITLE_HEIGHT),
        contentAlignment = Alignment.BottomStart,
    ) {
        Text(
            text = detail.title,
            modifier = Modifier
                .fillMaxWidth()
                .basicMarquee(),
            color = Color.White,
            fontSize = DETAIL_TITLE_SP.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            style = TextStyle(shadow = DETAIL_TITLE_SHADOW),
        )
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
 * 演职员行（Netflix 风格）：标题「导演 / 主演」+ 横向 `LazyRow`。
 *
 * 与上一版不同，这里**可聚焦**：按钮组 ↓ 进入本行，行内 ←/→ 横移，↑ 回「播放」。
 * 头像 60dp 圆形，焦点态加 2dp 红圈并放大到 1.08 倍；名字 12sp 单行省略。
 */
@Composable
private fun HeroCastSection(
    cast: List<HeroCast>,
    requesters: List<FocusRequester>,
    onFocused: (Int) -> Unit,
    onConfirm: (Int) -> Unit,
    onMoveUp: () -> Unit,
    onMoveLeft: (Int) -> Unit,
    onMoveRight: (Int) -> Unit,
) {
    HeroSectionTitle(text = "导演 / 主演", fontSize = CAST_TITLE_SP.sp)
    Spacer(modifier = Modifier.height(CAST_TITLE_GAP))
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(CAST_ITEM_GAP),
        // 列表铺到屏幕右缘，最后一位演员可以贴边滑出，不做硬性截断。
        contentPadding = PaddingValues(end = HERO_PADDING),
    ) {
        itemsIndexed(items = cast, key = { _, item -> item.id }) { index, item ->
            HeroCastItem(
                cast = item,
                requester = requesters[index],
                onFocused = { onFocused(index) },
                onConfirm = { onConfirm(index) },
                onMoveUp = onMoveUp,
                onMoveLeft = { onMoveLeft(index) },
                onMoveRight = { onMoveRight(index) },
            )
        }
    }
}

@Composable
private fun HeroCastItem(
    cast: HeroCast,
    requester: FocusRequester,
    onFocused: () -> Unit,
    onConfirm: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val isFocused by interaction.collectIsFocusedAsState()
    // 焦点态轻微放大，配合红圈做选中反馈；160ms 过渡，避免 TV 上硬切。
    val scale by animateFloatAsState(
        targetValue = if (isFocused) CAST_FOCUS_SCALE else 1f,
        label = "castScale",
    )

    Column(
        modifier = Modifier
            .width(CAST_ITEM_WIDTH)
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

                    // 演职员行是最底部区域，↓ 无处可去，直接消费。
                    Key.DirectionDown -> true

                    else -> false
                }
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onConfirm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 圆形头像：先 clip 再 border，描边画在图片之上；焦点态换成 2dp 红圈。
        Box(
            modifier = Modifier
                .size(CAST_AVATAR_SIZE)
                .scale(scale)
                .clip(CircleShape)
                .background(CAST_AVATAR_BACKGROUND)
                .border(
                    width = if (isFocused) CAST_FOCUS_BORDER else CAST_AVATAR_BORDER,
                    color = if (isFocused) HeroAccentColor else CAST_AVATAR_BORDER_COLOR,
                    shape = CircleShape,
                ),
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
            color = if (isFocused) Color.White else CAST_NAME_COLOR,
            fontSize = CAST_NAME_SP.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

// ---------------------------------------------------------------------------
// 演员详情：点击即弹窗，文本走轻量缓存，图片全部交给 Coil
// ---------------------------------------------------------------------------

/**
 * 演员详情弹窗（骨架）。
 *
 * **不阻塞 UI**：点击时只是把 [HeroDetailScreen] 里的 `actorDialogIndex` 置位，
 * Dialog 当帧组合、立刻显示「加载中」，遥控器不会卡。
 * 真正的数据请求在 [LaunchedEffect] 里切到 [Dispatchers.IO]；接 TMDB 时只改 [loadActorProfile]。
 *
 * 缓存策略：
 * - **只缓存文本**（简介 / 生日 / 出生地）→ [ActorProfileCache]，进程内 Map，轻量、无磁盘 IO。
 * - **头像与作品海报一律不落自定义缓存**，全交给 Coil 默认 LRU 磁盘缓存（满 250MB 自动淘汰），
 *   这里只通过 `AsyncImage(model = url)` 走默认 ImageLoader，不写任何下载/保存逻辑。
 */
@Composable
private fun HeroActorDialog(actor: HeroCast, onDismiss: () -> Unit) {
    // 命中缓存时首帧就有内容；未命中则显示加载态。
    var profile by remember(actor.id) { mutableStateOf(ActorProfileCache.get(actor.id)) }

    LaunchedEffect(actor.id) {
        if (profile != null) return@LaunchedEffect
        val loaded = loadActorProfile(actor)
        ActorProfileCache.put(actor.id, loaded)
        profile = loaded
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false,
        ),
    ) {
        HeroDialogShell(modifier = Modifier.fillMaxWidth(ACTOR_DIALOG_WIDTH_FRACTION)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = actor.avatarUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(ACTOR_DIALOG_AVATAR)
                        .clip(CircleShape)
                        .background(CAST_AVATAR_BACKGROUND),
                )
                Spacer(modifier = Modifier.width(ACTOR_DIALOG_AVATAR_GAP))
                Column {
                    Text(
                        text = actor.name,
                        color = Color.White,
                        fontSize = ACTOR_DIALOG_NAME_SP.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "演员档案",
                        color = ACTOR_DIALOG_HINT_COLOR,
                        fontSize = ACTOR_DIALOG_HINT_SP.sp,
                        maxLines = 1,
                    )
                }
            }
            Spacer(modifier = Modifier.height(ACTOR_DIALOG_HEADER_GAP))
            val loaded = profile
            if (loaded == null) {
                // Loading 态：2 秒假延迟期间显示，绝不占用主线程。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(ACTOR_DIALOG_SPINNER),
                        strokeWidth = 2.dp,
                        color = HeroAccentColor,
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "加载中…",
                        color = ACTOR_DIALOG_HINT_COLOR,
                        fontSize = ACTOR_DIALOG_BODY_SP.sp,
                    )
                }
            } else {
                Text(
                    text = loaded.summary,
                    color = Color.White,
                    fontSize = ACTOR_DIALOG_BODY_SP.sp,
                    lineHeight = ACTOR_DIALOG_BODY_LINE_HEIGHT.sp,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "${loaded.birthday} · ${loaded.place}",
                    color = ACTOR_DIALOG_HINT_COLOR,
                    fontSize = ACTOR_DIALOG_HINT_SP.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 取演员文本档案：当前是 2 秒假延迟 + 占位文案，接口接好后替换函数体即可。
 *
 * 真实实现建议：`GET /person/{person_id}?language=zh-CN`，只取 `biography`/`birthday`/`place_of_birth`；
 * 头像（`profile_path`）与作品海报**不要**在这里下载，直接拼成 URL 交给 Coil。
 */
private suspend fun loadActorProfile(actor: HeroCast): ActorProfile = withContext(Dispatchers.IO) {
    delay(ACTOR_PROFILE_FAKE_DELAY_MS)
    ActorProfile(
        name = actor.name,
        birthday = "1988-06-12",
        place = "中国 · 上海",
        summary = "演员详情开发中。\n\n这里将展示 TMDB 返回的演员简介：出生地、生日、代表作与个人履历。",
    )
}

/** 演员文本档案：只放文本字段，图片 URL 由调用方直接喂给 Coil，不参与缓存。 */
private data class ActorProfile(
    val name: String,
    val birthday: String,
    val place: String,
    val summary: String,
)

/**
 * 轻量文本缓存：进程内 Map，够用且零依赖。
 *
 * 换成 Room 时只需实现同样的 `get` / `put` 两个方法，调用点不动。
 * **绝不缓存图片**：Coil 自己的磁盘 LRU 已经处理了淘汰，重复缓存只会白白占空间。
 */
private object ActorProfileCache {

    private val store = java.util.concurrent.ConcurrentHashMap<String, ActorProfile>()

    fun get(actorId: String): ActorProfile? = store[actorId]

    fun put(actorId: String, profile: ActorProfile) {
        store[actorId] = profile
    }
}

/** 按钮组：播放 / 换源 / 收藏，横向排列、间距 12dp。 */
@Composable
private fun HeroDetailActionRow(
    requesters: List<FocusRequester>,
    onFocused: (Int) -> Unit,
    onConfirm: (Int) -> Unit,
    onMoveLeft: (Int) -> Unit,
    onMoveRight: (Int) -> Unit,
    onMoveDown: (Int) -> Unit,
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
                onMoveDown = { onMoveDown(index) },
            )
        }
    }
}

/**
 * 单颗按钮：高 36dp、字号 13sp、图标在左文字在右。
 *
 * 焦点态按需求取 [HeroFocusBrush] 红渐变色块 + 纯白文字/图标（实心块全局统一渐变）；
 * 常态沿用原版 `shape_detail_item_normal` 的 `white_10`（`#1AFFFFFF`）底 + 4dp 圆角。
 *
 * 方向键：←/→ 在按钮间移动，↓ 进入演职员行，↑ 已到内容区顶部、直接消费。
 */
@Composable
private fun HeroDetailActionButton(
    action: HeroDetailAction,
    requester: FocusRequester,
    onFocused: () -> Unit,
    onConfirm: () -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
    onMoveDown: () -> Unit,
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

                    Key.DirectionDown -> {
                        onMoveDown()
                        true
                    }

                    Key.DirectionUp -> true

                    else -> false
                }
            }
            .clip(DETAIL_ACTION_SHAPE)
            // 焦点态 = 全局红渐变色块（原先是纯红 solid，现统一到 [HeroFocusBrush]）。
            .then(
                if (isFocused) {
                    Modifier.background(HeroFocusBrush, DETAIL_ACTION_SHAPE)
                } else {
                    Modifier.background(DETAIL_ACTION_IDLE_BACKGROUND, DETAIL_ACTION_SHAPE)
                },
            )
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

// ---------------------------------------------------------------------------
// 选集弹窗：外壳 + 线路栏 + 单行控制栏 + 固定 4 排网格（零内部滚动）
// ---------------------------------------------------------------------------

/**
 * 选集弹窗（终稿）。
 *
 * 版式自上而下（尺寸逐条对照 `ref_docs/`）：
 * 1. 外壳 [HeroDialogShell]：`#E6000000` 深色半透明 + 16dp 大圆角 + 18dp 内边距。
 * 2. 「播放线路」：4dp × 16dp 红竖条 + 15sp 加粗标题（不可聚焦），下方 32dp / 14sp 线路按钮。
 *    —— 顶部原来的「选集徽章 + 剧名」整行已删除，弹窗第一行就是播放线路。
 * 3. 「选集」控制栏：**单行** Row，依次为红竖条 + 15sp「选集」（不可聚焦）、少列/多列、
 *    正序/倒序、分组按钮；按钮统一 32dp 高 / 14sp，全部可聚焦，←/→ 在本行内滑动。
 * 4. 选集网格：固定 [EPISODE_GRID_ROWS] 排，高度 = 排数 × 34dp + 间距，`userScrollEnabled = false`
 *    禁止内部滚动；卡片 34dp / 16sp 居中 + `basicMarquee()` 防溢出。
 *
 * 分组是「4 排 × 当前列数」的动态分页（多列 16 集一组 / 少列 8 集一组），
 * 所以不管剧集总数是 50 还是几百集，网格永远只有 4 排，翻页全靠边缘按键，绝不滚动。
 *
 * 历史记忆（本阶段用参数注入，接库后只换数据来源、不动这里的逻辑）：
 * - [lastWatchedEpisodeIndex] 是**0 基集索引**（14 = 第 15 集），-1 表示新剧。
 * - 新剧：焦点落第 1 集；老剧：分组先自动切到该集所在组，再把焦点落到该集本身。
 * - 用户按确定键时回调 [onEpisodeSelected]，参数量纲与 [lastWatchedEpisodeIndex] 完全一致。
 *
 * 焦点契约：
 * - 打开落在历史集（`lastWatchedEpisodeIndex`）；新剧落第 1 集。
 * - 线路栏 ←/→ 在站点间移动，端点消费；↓ 进控制栏；↑ 消费。
 * - 控制栏 ←/→ 在「列数 / 排序 / 分组」之间滑动；↓ 进网格（尽量落回同一集）；↑ 回线路栏。
 * - 网格组内 ←/→/↑/↓ 正常移动；**任意一行**的最右一列按 → 切下一组（普通行落同行第 1 列，
 *   最后一行落新组最后一集），**任意一行**的最左一列按 ← 切上一组（落上一组同行最右一列）。
 * - 列数 / 排序写进 SharedPreferences，下次打开弹窗沿用上次选择；返回键关闭弹窗。
 */
@Composable
private fun HeroPlayDialog(
    detail: HeroDetail,
    lastWatchedEpisodeIndex: Int = -1,
    onEpisodeSelected: (Int) -> Unit = {},
    onDismiss: () -> Unit,
) {
    // 列数 / 排序本地持久化：换剧、关弹窗、杀进程后再进来仍是上次的选择。
    val context = LocalContext.current
    val prefs = remember(context) {
        context.getSharedPreferences(DIALOG_PREFS_NAME, Context.MODE_PRIVATE)
    }
    // 排序只有「正序 / 倒序」两态：默认正序，点一次整体翻转。
    var descending by remember { mutableStateOf(prefs.getBoolean(DIALOG_PREF_DESCENDING, false)) }
    // 列数只有「多列(4) / 少列(2)」两态：默认多列。
    var fewColumns by remember { mutableStateOf(prefs.getBoolean(DIALOG_PREF_FEW_COLUMNS, false)) }

    // 集号升序基准表：历史索引、排序镜像都以它为坐标原点，和外部的 History 完全对齐。
    val ascending = remember(detail.episodes) { detail.episodes.sortedBy { it.number } }
    val ordered = remember(descending, ascending) { if (descending) ascending.asReversed() else ascending }
    val columns = if (fewColumns) EPISODE_COLUMNS_FEW else EPISODE_COLUMNS_MANY
    // 组容量 = 4 排 × 当前列数：多列 16 集 / 少列 8 集，永远刚好填满 4 排。
    val pageSize = EPISODE_GRID_ROWS * columns
    val pageCount = ((ordered.size + pageSize - 1) / pageSize).coerceAtLeast(1)

    // 历史索引 → 升序下标；-1（新剧）或越界统一收敛到第 1 集。
    val watchedIndex = remember(lastWatchedEpisodeIndex, ascending) {
        if (lastWatchedEpisodeIndex in ascending.indices) lastWatchedEpisodeIndex else 0
    }
    // 升序下标 → 当前排序下的显示下标（倒序时整体镜像），据此算初始分组。
    val initialIndex = remember(watchedIndex, descending, ascending) {
        if (descending) ascending.lastIndex - watchedIndex else watchedIndex
    }
    val initialNumber = remember(ordered, initialIndex) { ordered.getOrNull(initialIndex)?.number ?: 0 }

    var page by remember { mutableIntStateOf(initialIndex / pageSize) }
    /** 焦点集在「当前组内」的下标；焦点不在网格时保留最后一次有效值。 */
    var anchorCell by remember { mutableIntStateOf(initialIndex % pageSize) }
    var anchorNumber by remember { mutableIntStateOf(initialNumber) }
    var playingNumber by remember { mutableIntStateOf(initialNumber) }
    var selectedFlag by remember { mutableIntStateOf(0) }
    var focusedFlag by remember { mutableIntStateOf(0) }
    // 焦点是否还在「线路 / 控制栏 / 选集网格」的下半区。
    // 只在线路栏上时为 false；一旦进入控制栏或网格就为 true ——
    // 此时当前线路、当前分组都要留下红框（《焦点总结》规则 ②），让用户始终知道在放哪条线路、哪一组。
    var contentFocused by remember { mutableStateOf(false) }
    /** 控制栏下标：0 = 列数、1 = 排序、2 起为分组按钮。 */
    var focusedControl by remember { mutableIntStateOf(CONTROL_COLUMNS) }

    val pageItems = remember(ordered, page, pageSize) {
        ordered.drop(page * pageSize).take(pageSize)
    }

    // 待落焦格 / 待落焦按钮：-1 = 不抢焦点；serial 让同一目标连续请求也能重新触发。
    var pendingCell by remember { mutableIntStateOf(initialIndex % pageSize) }
    var cellSerial by remember { mutableIntStateOf(0) }
    var pendingControl by remember { mutableIntStateOf(-1) }
    var controlSerial by remember { mutableIntStateOf(0) }

    val flagRequesters = remember { List(detail.flags.size) { FocusRequester() } }
    val columnsRequester = remember { FocusRequester() }
    val sortRequester = remember { FocusRequester() }
    // 分组按钮走 LazyRow（剧集多时分组会溢出），requesters 随页数重建。
    val groupRequesters = remember(pageCount) { List(pageCount) { FocusRequester() } }
    // 网格 item 用「组内下标」做 key：切组时节点原样复用，焦点不会被整片销毁重建成空档。
    val cellRequesters = remember(pageSize) { List(pageSize) { FocusRequester() } }
    val groupListState = rememberLazyListState()

    fun focusFlag(index: Int) {
        if (requestDetailFocus(flagRequesters, index)) focusedFlag = index
    }

    /** 控制栏落焦：登记目标，真正的滚动 / 请求交给下面的 LaunchedEffect（越界时夹到首尾）。 */
    fun focusControl(index: Int) {
        val target = index.coerceIn(0, CONTROL_GROUP_START + pageCount - 1)
        focusedControl = target
        pendingControl = target
        controlSerial++
    }

    /** 组内落焦：把下标收敛到当前组内，越界时夹到首尾。 */
    fun focusCell(index: Int) {
        if (pageItems.isEmpty()) return
        val target = if (index < 0) 0 else index.coerceAtMost(pageItems.lastIndex)
        anchorCell = target
        anchorNumber = pageItems[target].number
        pendingCell = target
        cellSerial++
    }

    /** 换组落焦：写入新组状态并把目标格排进请求队列。 */
    fun landOnPage(target: Int, items: List<HeroEpisode>, index: Int) {
        page = target
        anchorCell = index
        anchorNumber = items[index].number
        pendingCell = index
        cellSerial++
    }

    /**
     * 右切下一组：普通行落「新组同一行的第 1 列」；
     * 最后一行落「新组最后一集」（继续往下看，不再从行首重新扫）。
     * 已是最后一组时什么都不做，事件由调用方消费，绝不越界。
     */
    fun pageForward(row: Int, fromLastRow: Boolean) {
        val target = page + 1
        if (target !in 0 until pageCount) return
        val items = ordered.drop(target * pageSize).take(pageSize)
        if (items.isEmpty()) return
        val index = if (fromLastRow) items.lastIndex else (row * columns).coerceAtMost(items.lastIndex)
        landOnPage(target, items, index)
    }

    /** 左切上一组：落「上一组同一行的最右一列」；该行没有格子时落上一组最后一集。 */
    fun pageBackward(row: Int) {
        val target = page - 1
        if (target !in 0 until pageCount) return
        val items = ordered.drop(target * pageSize).take(pageSize)
        if (items.isEmpty()) return
        val index = if (row > items.lastIndex / columns) {
            items.lastIndex
        } else {
            (row * columns + columns - 1).coerceAtMost(items.lastIndex)
        }
        landOnPage(target, items, index)
    }

    /** 从控制栏进入网格：优先落回当前集，找不到就落第 1 格。 */
    fun enterGrid() {
        focusCell(pageItems.indexOfFirst { it.number == anchorNumber })
    }

    /** 点分组按钮：只换组，焦点留在按钮上（不抢进网格）。 */
    fun selectPage(target: Int) {
        if (target !in 0 until pageCount) return
        val items = ordered.drop(target * pageSize).take(pageSize)
        if (items.isEmpty()) return
        page = target
        anchorCell = 0
        anchorNumber = items.first().number
        pendingCell = -1
    }

    /** 排序翻转：按集号重新定位分组；焦点留在 Toggle 上，不把焦点抢进网格。 */
    fun toggleSort() {
        val number = anchorNumber
        val next = !descending
        val reordered = detail.episodes.sortedBy { it.number }.let { if (next) it.asReversed() else it }
        descending = next
        prefs.edit().putBoolean(DIALOG_PREF_DESCENDING, next).apply()
        val index = reordered.indexOfFirst { it.number == number }
        if (index >= 0) {
            page = index / pageSize
            anchorCell = index % pageSize
        }
        pendingCell = -1
    }

    /** 列数切换：组容量从 16 ↔ 8 变化，按「全局集下标」重算分组与组内位置。 */
    fun toggleColumns() {
        val lastEpisode = (ordered.size - 1).coerceAtLeast(0)
        val episodeIndex = (page * pageSize + anchorCell).coerceIn(0, lastEpisode)
        fewColumns = !fewColumns
        prefs.edit().putBoolean(DIALOG_PREF_FEW_COLUMNS, fewColumns).apply()
        val newSize = EPISODE_GRID_ROWS * (if (fewColumns) EPISODE_COLUMNS_FEW else EPISODE_COLUMNS_MANY)
        val newPageCount = ((ordered.size + newSize - 1) / newSize).coerceAtLeast(1)
        page = (episodeIndex / newSize).coerceIn(0, newPageCount - 1)
        anchorCell = episodeIndex % newSize
        // 焦点留在列数按钮上，等用户按 ↓ 再进网格，避免切列数时焦点乱跳。
        pendingCell = -1
    }

    /**
     * 网格方向键：组内上下左右移动 + 「整列」边缘翻页。
     *
     * 翻页判定只看列：任意一行的**最左一列**按 ← = 上一组，**最右一列**按 → = 下一组，
     * 所以不必先把焦点挪到右下角才能翻页；两端的落点见 [pageForward] / [pageBackward]。
     * 上下键永远不翻页，末行 ↓ 只消费。
     */
    fun handleGridKey(key: Key): Boolean {
        if (pageItems.isEmpty()) return false
        val lastIndex = pageItems.lastIndex
        val base = anchorCell.coerceIn(0, lastIndex)
        val column = base % columns
        val row = base / columns
        val lastRow = lastIndex / columns
        return when (key) {
            Key.DirectionLeft -> {
                if (column == 0) pageBackward(row) else focusCell(base - 1)
                true
            }

            Key.DirectionRight -> {
                when {
                    column == columns - 1 -> pageForward(row, fromLastRow = row == lastRow)
                    base == lastIndex -> pageForward(row, fromLastRow = true)
                    else -> focusCell(base + 1)
                }
                true
            }

            Key.DirectionUp -> {
                if (row > 0) focusCell(base - columns) else focusControl(focusedControl)
                true
            }

            Key.DirectionDown -> {
                if (base + columns <= lastIndex) focusCell(base + columns)
                true
            }

            else -> false
        }
    }

    // 网格落焦：Lazy 项要先组合出来 `requestFocus` 才拿得到节点，所以逐帧重试。
    LaunchedEffect(page, pageSize, pendingCell, cellSerial) {
        val target = pendingCell
        if (target < 0 || target !in pageItems.indices) return@LaunchedEffect
        repeat(FOCUS_RETRY_FRAMES) {
            if (requestDetailFocus(cellRequesters, target)) {
                pendingCell = -1
                return@LaunchedEffect
            }
            withFrameNanos { }
        }
    }

    // 控制栏落焦：分组按钮在 LazyRow 里，先滚到可视区再请求焦点。
    LaunchedEffect(pageCount, pendingControl, controlSerial) {
        val target = pendingControl
        if (target < 0) return@LaunchedEffect
        if (target >= CONTROL_GROUP_START) {
            runCatching { groupListState.scrollToItem(target - CONTROL_GROUP_START) }
        }
        repeat(FOCUS_RETRY_FRAMES) {
            if (requestControlFocus(columnsRequester, sortRequester, groupRequesters, target)) {
                pendingControl = -1
                return@LaunchedEffect
            }
            withFrameNanos { }
        }
    }

    // 打开弹窗时把「历史集所在分组」按钮滚进可视区，和网格自动切组保持一致。
    LaunchedEffect(Unit) {
        runCatching { groupListState.scrollToItem(page.coerceIn(0, (pageCount - 1).coerceAtLeast(0))) }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        // 全屏遮罩 + 居中面板；遮罩让底图继续透出来一点，形成「半透明」层次。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(DIALOG_SCRIM),
            contentAlignment = Alignment.Center,
        ) {
            HeroDialogShell(modifier = Modifier.fillMaxWidth(DIALOG_WIDTH_FRACTION)) {
                // ---- 播放线路：4dp 红竖条标题 + 32dp 横排（标题纯展示，不可聚焦）----
                HeroSectionTitle(text = "播放线路", fontSize = DIALOG_SECTION_TITLE_SP.sp)
                Spacer(modifier = Modifier.height(DIALOG_TITLE_GAP))
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(DIALOG_FLAG_ROW_HEIGHT),
                    horizontalArrangement = Arrangement.spacedBy(DIALOG_FLAG_GAP),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    itemsIndexed(items = detail.flags, key = { index, _ -> index }) { index, label ->
                        HeroFlagChip(
                            label = label,
                            selected = index == selectedFlag,
                            moduleFocused = contentFocused,
                            requester = flagRequesters[index],
                            // 焦点回到线路栏 = 暂时离开下半区：红框收起，只留当前项的焦点块。
                            onFocused = {
                                focusedFlag = index
                                contentFocused = false
                            },
                            onConfirm = { selectedFlag = index },
                            // 线路栏 ↑ 上方只有不可聚焦的分区标题，直接消费。
                            onMoveUp = { },
                            onMoveDown = { focusControl(focusedControl) },
                            onMoveLeft = { if (index > 0) focusFlag(index - 1) },
                            onMoveRight = { if (index < detail.flags.lastIndex) focusFlag(index + 1) },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(DIALOG_SECTION_GAP))

                // ---- 选集单行控制栏：标题（不可聚焦）+ 列数 + 排序 + 分组，按钮 32dp / 14sp ----
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(DIALOG_TOGGLE_HEIGHT),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HeroSectionTitle(text = "选集", fontSize = DIALOG_SECTION_TITLE_SP.sp)
                    Spacer(modifier = Modifier.width(DIALOG_CONTROL_GAP))
                    HeroToggleButton(
                        label = if (fewColumns) "少列" else "多列",
                        moduleFocused = contentFocused,
                        requester = columnsRequester,
                        onFocused = {
                            focusedControl = CONTROL_COLUMNS
                            contentFocused = true
                        },
                        onConfirm = { toggleColumns() },
                        onMoveUp = { focusFlag(focusedFlag) },
                        onMoveDown = { enterGrid() },
                        onMoveLeft = { focusControl(CONTROL_COLUMNS - 1) },
                        onMoveRight = { focusControl(CONTROL_COLUMNS + 1) },
                    )
                    Spacer(modifier = Modifier.width(DIALOG_CONTROL_GAP))
                    HeroToggleButton(
                        label = if (descending) "倒序" else "正序",
                        moduleFocused = contentFocused,
                        requester = sortRequester,
                        onFocused = {
                            focusedControl = CONTROL_SORT
                            contentFocused = true
                        },
                        onConfirm = { toggleSort() },
                        onMoveUp = { focusFlag(focusedFlag) },
                        onMoveDown = { enterGrid() },
                        onMoveLeft = { focusControl(CONTROL_SORT - 1) },
                        onMoveRight = { focusControl(CONTROL_SORT + 1) },
                    )
                    Spacer(modifier = Modifier.width(DIALOG_CONTROL_GAP))
                    // 分组按钮：剧集多时一行放不下，LazyRow 会把焦点按钮滚进可视区。
                    LazyRow(
                        state = groupListState,
                        modifier = Modifier
                            .weight(1f)
                            .height(DIALOG_TOGGLE_HEIGHT),
                        horizontalArrangement = Arrangement.spacedBy(DIALOG_CONTROL_GAP),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        items(count = pageCount, key = { index -> index }) { index ->
                            val start = index * pageSize + 1
                            val end = minOf((index + 1) * pageSize, ordered.size)
                            val controlIndex = CONTROL_GROUP_START + index
                            HeroToggleButton(
                                label = "$start-$end",
                                selected = index == page,
                                moduleFocused = contentFocused,
                                requester = groupRequesters[index],
                                onFocused = {
                                    focusedControl = controlIndex
                                    contentFocused = true
                                },
                                onConfirm = { selectPage(index) },
                                onMoveUp = { focusFlag(focusedFlag) },
                                onMoveDown = { enterGrid() },
                                onMoveLeft = { focusControl(controlIndex - 1) },
                                onMoveRight = { focusControl(controlIndex + 1) },
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(DIALOG_CONTROL_GAP))

                // ---- 固定 4 排网格：高度写死、userScrollEnabled = false，翻组只走边缘按键 ----
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    userScrollEnabled = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(EPISODE_GRID_HEIGHT)
                        // 边界切组统一在网格层拦截：格子本身不处理方向键。
                        .onKeyEvent { event ->
                            event.type == KeyEventType.KeyDown && handleGridKey(event.key)
                        },
                    horizontalArrangement = Arrangement.spacedBy(EPISODE_GRID_GAP),
                    verticalArrangement = Arrangement.spacedBy(EPISODE_GRID_GAP),
                ) {
                    pageItems.forEachIndexed { index, episode ->
                        item(key = index) {
                            HeroEpisodeCell(
                                label = episode.label,
                                playing = episode.number == playingNumber,
                                requester = cellRequesters[index],
                                onFocused = {
                                    anchorCell = index
                                    anchorNumber = episode.number
                                    // 焦点进网格：线路 / 分组保持红框（规则 ②）。
                                    contentFocused = true
                                },
                                onConfirm = {
                                    playingNumber = episode.number
                                    // 显示下标 → 升序绝对索引，作为历史记录的稳定键。
                                    val orderedIndex = page * pageSize + index
                                    val episodeIndex =
                                        if (descending) ordered.lastIndex - orderedIndex else orderedIndex
                                    // TODO(接入历史库)：这里换成原版的记录接口，例如
                                    //  History.push(detail.title, episodeIndex)
                                    onEpisodeSelected(episodeIndex)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 播放线路项：32dp 高 / 14sp（`ref_docs/adapter_v2_site.xml` 的 39dp 按需求压到 32dp）。
 *
 * 三态（《焦点总结》）：
 * - 聚焦 → [HeroFocusBrush] 红渐变色块 + 白字；
 * - 失焦但仍是当前线路、且焦点在下半区 → 红框描边 + 弱底（规则 ②）；
 * - 其余 → 常态深灰底白字（规则 ③）。
 */
@Composable
private fun HeroFlagChip(
    label: String,
    selected: Boolean,
    moduleFocused: Boolean,
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
    val selectedRing = selected && !isFocused && moduleFocused

    Box(
        modifier = Modifier
            .height(DIALOG_FLAG_ROW_HEIGHT)
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
            .clip(DIALOG_FLAG_SHAPE)
            // 底色只表达「是否是当前线路」：当前线路弱亮，其余深灰。
            // 焦点态下面再盖一层不透明红渐变，完全盖住这个底色。
            .background(if (selected) DIALOG_FLAG_SELECTED_BG else DIALOG_FLAG_IDLE_BG)
            // 焦点态用全局红渐变整块填充（实心块统一色）。
            .then(
                if (isFocused) {
                    Modifier.background(HeroFocusBrush, DIALOG_FLAG_SHAPE)
                } else {
                    Modifier
                },
            )
            .then(
                if (selectedRing) {
                    Modifier.border(DIALOG_FLAG_RING_STROKE, HeroAccentColor, DIALOG_FLAG_SHAPE)
                } else {
                    Modifier
                },
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onConfirm)
            .padding(horizontal = DIALOG_FLAG_H_PADDING),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = DIALOG_FLAG_SP.sp,
            fontWeight = if (selected || isFocused) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

/**
 * 控制栏按钮：32dp 高 / 14sp（列数、排序、分组三处共用）。
 *
 * 与线路按钮同一套三态：聚焦 → 红渐变色块；失焦但仍选中且焦点在下半区 → 红框；
 * 其余 → 深灰底白字（规则 ②③）。
 */
@Composable
private fun HeroToggleButton(
    label: String,
    requester: FocusRequester,
    onFocused: () -> Unit,
    onConfirm: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
    selected: Boolean = false,
    moduleFocused: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val isFocused by interaction.collectIsFocusedAsState()
    val selectedRing = selected && !isFocused && moduleFocused

    Box(
        modifier = Modifier
            .height(DIALOG_TOGGLE_HEIGHT)
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
            .clip(DIALOG_TOGGLE_SHAPE)
            .background(if (selected) DIALOG_TOGGLE_SELECTED_BG else DIALOG_TOGGLE_IDLE_BG)
            .then(
                if (isFocused) {
                    Modifier.background(HeroFocusBrush, DIALOG_TOGGLE_SHAPE)
                } else {
                    Modifier
                },
            )
            .then(
                if (selectedRing) {
                    Modifier.border(DIALOG_TOGGLE_RING_STROKE, HeroAccentColor, DIALOG_TOGGLE_SHAPE)
                } else {
                    Modifier
                },
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onConfirm)
            .padding(horizontal = DIALOG_TOGGLE_H_PADDING),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = DIALOG_TOGGLE_SP.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/**
 * 选集格子：高 34dp、16sp 居中、单行 `basicMarquee()` 防溢出，焦点态红框，当前播放集红字。
 * 方向键不在这里处理：统一交给网格层的 `onKeyEvent`，保证边界切组判断只有一处。
 */
@Composable
private fun HeroEpisodeCell(
    label: String,
    playing: Boolean,
    requester: FocusRequester,
    onFocused: () -> Unit,
    onConfirm: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val isFocused by interaction.collectIsFocusedAsState()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(EPISODE_CELL_HEIGHT)
            .focusRequester(requester)
            .onFocusChanged { if (it.isFocused) onFocused() }
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
                // 超长剧集名不省略号硬截断，跑马灯滚动显示（adapter_episode.xml 的 marquee）。
                .basicMarquee(iterations = Int.MAX_VALUE),
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

/**
 * 演职员行移动：只负责请求焦点。
 * 记忆索引由每个条目的 `onFocusChanged` 维护（Lazy 项未组合时 request 会失败，忽略即可）。
 */
private fun focusCast(requesters: List<FocusRequester>, index: Int) {
    requestDetailFocus(requesters, index)
}

/**
 * 控制栏下标 → 焦点：0 = 列数，1 = 排序，2 起为分组按钮。
 * 未组合的分组按钮（LazyRow 里滑出可视区）会抛异常，这里降级返回 false 由调用方重试。
 */
private fun requestControlFocus(
    columnsRequester: FocusRequester,
    sortRequester: FocusRequester,
    groupRequesters: List<FocusRequester>,
    index: Int,
): Boolean = when (index) {
    CONTROL_COLUMNS -> runCatching { columnsRequester.requestFocus() }.isSuccess
    CONTROL_SORT -> runCatching { sortRequester.requestFocus() }.isSuccess
    else -> requestDetailFocus(groupRequesters, index - CONTROL_GROUP_START)
}

/** 按钮组定义：图标、图标尺寸、文案全部集中在这里，新增按钮只改这个列表。 */
private data class HeroDetailAction(
    val label: String,
    val iconRes: Int,
    val iconSize: Dp,
)

/** 演职员：TMDB person id + 姓名 + 头像地址（picsum 稳定图，不依赖 TMDB）。 */
private data class HeroCast(val id: String, val name: String, val avatarUrl: String)

/**
 * 单集：`number` 是稳定集号（排序、历史进度都按它对齐），`label` 是显示文案。
 * 用集号而不是列表下标做记忆，切排序 / 切列数后焦点不会漂移。
 */
private data class HeroEpisode(val number: Int, val label: String)

private data class HeroDetail(
    val title: String,
    val meta: String,
    val synopsis: String,
    val posterUrl: String,
    val backdropUrl: String,
    /** TMDB 艺术字 Logo；null / 空串时回落 32sp 纯文字标题。 */
    val logoUrl: String?,
    val cast: List<HeroCast>,
    val flags: List<String>,
    val episodes: List<HeroEpisode>,
)

/** 假数据：picsum 按 seed 返回稳定图片，不依赖 TMDB Key 即可验证 Coil 链路。 */
private val DEMO_DETAIL = HeroDetail(
    title = "后会无期",
    meta = "2026 · 剧情 · 全 50 集 · 9.1",
    // 故意写长，用来验证「简介最多 4 行 + 省略号」以及 440dp 的换行宽度。
    synopsis = "三个男人一路向西，在公路尽头重新认识了自己。他们带着各自的秘密出发，" +
        "却在无人区里把话说得比过去十年都清楚。有人在加油站丢了钱包，有人在沙漠里捡回一条狗，" +
        "还有人把最想说的话留到了最后一句。",
    posterUrl = "https://picsum.photos/seed/hero-detail-poster/300/420",
    backdropUrl = "https://picsum.photos/seed/hero-detail-backdrop/1280/720",
    // 演示「无 Logo」分支：显示 32sp 文字 + 跑马灯。
    // 要验证 Logo 分支，把下面换成 TMDB 的 logo 地址（`https://image.tmdb.org/t/p/w500/xxx.png`）即可。
    logoUrl = null,
    cast = listOf(
        "李海潮", "陈屿", "林晚舟", "周野", "苏见月",
        "顾云深", "沈青禾", "许知白", "叶南", "宋一诺",
    ).mapIndexed { index, name ->
        HeroCast(
            id = "hero-cast-$index",
            name = name,
            avatarUrl = "https://picsum.photos/seed/hero-cast-$index/120/120",
        )
    },
    flags = listOf("线路一", "线路二", "线路三"),
    // 极限测试数据：整 50 集，第 15 集故意用超长名字验证格子内跑马灯；
    // 分组按「4 排 × 列数」动态切：多列 1-16 / 17-32 / 33-48 / 49-50（末组只有 2 集，右切越界重点），
    // 少列 1-8 / 9-16 / … / 49-50，两组都覆盖「不满一组」的边界。
    episodes = (1..50).map { number ->
        HeroEpisode(
            number = number,
            label = if (number == 15) "第15集_超长名字测试跑马灯效果" else "第${number}集",
        )
    },
    // 历史进度改由弹窗参数注入（见 DEMO_LAST_WATCHED_INDEX），详情数据本身不再携带进度。
)

/**
 * 假数据阶段的历史进度：14 = 第 15 集（0 基索引）。
 * 接入本地库后换成 History 查询结果，-1 表示没看过、焦点落第 1 集。
 */
private const val DEMO_LAST_WATCHED_INDEX = 14

/** 按钮顺序按需求固定为 播放 | 换源 | 收藏。 */
private val DETAIL_ACTIONS = listOf(
    HeroDetailAction(label = "播放", iconRes = R.drawable.ic_widget_play, iconSize = 24.dp),
    HeroDetailAction(label = "换源", iconRes = R.drawable.ic_detail_change, iconSize = 20.dp),
    HeroDetailAction(label = "收藏", iconRes = R.drawable.ic_detail_keep_off, iconSize = 20.dp),
)

/** 全局左侧基准线：内容区左侧 padding 严格 24dp（原版详情页同值）。 */
private val HERO_PADDING = 24.dp

/** 「播放」在按钮组里的固定下标。 */
private const val DETAIL_ACTION_PLAY = 0

/** 「换源」在按钮组里的固定下标（关闭换源弹窗后焦点必须回到这里）。 */
private const val DETAIL_ACTION_CHANGE_SOURCE = 1

// ---------------------------------------------------------------------------
// 左下内容区（activity_detail.xml detailTop L62-190）
// ---------------------------------------------------------------------------
/** 内容区底部留白：防止最后一行演职员贴到屏幕边缘。 */
private val CONTENT_BOTTOM_PADDING = 32.dp
/** TMDB 艺术字 Logo：高 72dp、最宽 720dp（XML heroLogoImg L82-89）。 */
private val LOGO_HEIGHT = 72.dp
private val LOGO_MAX_WIDTH = 720.dp
private const val DETAIL_TITLE_SP = 32f
private val DETAIL_TITLE_HEIGHT = 36.dp
private val DETAIL_TITLE_META_GAP = 2.dp
private val DETAIL_META_HEIGHT = 20.dp
private const val DETAIL_META_SP = 14f
private val DETAIL_META_COLOR = Color(0xCCFFFFFF)
private val DETAIL_META_DESC_GAP = 4.dp
/** 简介最大宽度 440dp：每行约 30 个汉字，4 行刚好不喧宾夺主。 */
private val DETAIL_DESC_MAX_WIDTH = 440.dp
private const val DETAIL_DESC_MAX_LINES = 4
private const val DETAIL_DESC_SP = 12f
private const val DETAIL_DESC_LINE_HEIGHT_SP = 17f
private val DETAIL_DESC_COLOR = Color(0xBBFFFFFF)
private val DETAIL_ACTIONS_TOP_GAP = 12.dp
private val DETAIL_CAST_TOP_GAP = 16.dp

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
// 演职员区（Netflix 风格）：60dp 圆形头像 + 12sp 名字，可聚焦
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

/** 焦点态：2dp 红圈 + 1.08 倍放大。 */
private val CAST_FOCUS_BORDER = 2.dp
private const val CAST_FOCUS_SCALE = 1.08f

// ---------------------------------------------------------------------------
// 演员详情弹窗（轻量骨架）
// ---------------------------------------------------------------------------
private const val ACTOR_DIALOG_WIDTH_FRACTION = 0.5f
private val ACTOR_DIALOG_AVATAR = 56.dp
private val ACTOR_DIALOG_AVATAR_GAP = 12.dp
private val ACTOR_DIALOG_HEADER_GAP = 16.dp
private val ACTOR_DIALOG_SPINNER = 18.dp
private const val ACTOR_DIALOG_NAME_SP = 18f
private const val ACTOR_DIALOG_BODY_SP = 14f
private const val ACTOR_DIALOG_BODY_LINE_HEIGHT = 22f
private const val ACTOR_DIALOG_HINT_SP = 12f
private val ACTOR_DIALOG_HINT_COLOR = Color(0x99FFFFFF)

/** 假接口延迟：2 秒，用来验证 Loading 态与「不阻塞遥控器」。 */
private const val ACTOR_PROFILE_FAKE_DELAY_MS = 2_000L

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
// 统一弹窗外壳：外壳实现与常量已抽到 HeroCommonUi.HeroDialogShell（选集 / 换源共用）
// ---------------------------------------------------------------------------
private const val DIALOG_WIDTH_FRACTION = 0.82f
private val DIALOG_SCRIM = Color(0x99000000)

// 选集弹窗本地偏好（SharedPreferences）：跨剧、退弹窗、杀进程都保持上次的列数与排序
private const val DIALOG_PREFS_NAME = "hero_detail_ui"
private const val DIALOG_PREF_FEW_COLUMNS = "episode_few_columns"
private const val DIALOG_PREF_DESCENDING = "episode_descending"

// 顶部「选集徽章 + 剧名」整行已按需求删除，弹窗第一行直接是「播放线路」

// 分区间距：标题下 8dp、区块之间 16dp、控制栏到网格 8dp
private val DIALOG_TITLE_GAP = 8.dp
private val DIALOG_SECTION_GAP = 16.dp
private val DIALOG_CONTROL_GAP = 8.dp
private const val DIALOG_SECTION_TITLE_SP = 15f

// 播放线路：32dp 高 / 14sp；深灰底白字，焦点红底白字
private val DIALOG_FLAG_ROW_HEIGHT = 32.dp
private val DIALOG_FLAG_GAP = 12.dp
private val DIALOG_FLAG_H_PADDING = 14.dp
private val DIALOG_FLAG_SHAPE = RoundedCornerShape(4.dp)
private const val DIALOG_FLAG_SP = 14f
private val DIALOG_FLAG_IDLE_BG = Color(0x1AFFFFFF)
private val DIALOG_FLAG_SELECTED_BG = Color(0x33FFFFFF)
// 规则 ②：失焦但仍选中时补的 1.5dp 红框，宽度与首页分类 Tab / 搜索页胶囊一致。
private val DIALOG_FLAG_RING_STROKE = 1.5.dp

// 控制栏按钮（列数 / 排序 / 分组）：32dp 高 / 14sp；深灰底白字，焦点红底白字
private val DIALOG_TOGGLE_HEIGHT = 32.dp
private val DIALOG_TOGGLE_H_PADDING = 14.dp
private val DIALOG_TOGGLE_SHAPE = RoundedCornerShape(4.dp)
private const val DIALOG_TOGGLE_SP = 14f
private val DIALOG_TOGGLE_IDLE_BG = Color(0x1AFFFFFF)
private val DIALOG_TOGGLE_SELECTED_BG = Color(0x33FFFFFF)
private val DIALOG_TOGGLE_RING_STROKE = 1.5.dp

/** 单行控制栏下标：0 = 列数、1 = 排序、2 起为分组按钮。 */
private const val CONTROL_COLUMNS = 0
private const val CONTROL_SORT = 1
private const val CONTROL_GROUP_START = 2

/** Lazy 项从请求焦点到真正拿到节点要几帧，多给几帧兜住首帧未 attach。 */
private const val FOCUS_RETRY_FRAMES = 10

// ---------------------------------------------------------------------------
// 选集网格：固定 4 排、格高 34dp、间距 8dp，高度写死且禁止内部滚动
// 组容量 = 4 排 × 当前列数 → 多列 16 集 / 少列 8 集，翻组只靠边缘按键
// ---------------------------------------------------------------------------
private const val EPISODE_GRID_ROWS = 4
private val EPISODE_GRID_GAP = 8.dp
private val EPISODE_CELL_HEIGHT = 34.dp

/** 固定 4 排的总高度：4 × 34dp + 3 × 8dp = 160dp，一行都不许裁切。 */
private val EPISODE_GRID_HEIGHT =
    EPISODE_CELL_HEIGHT * EPISODE_GRID_ROWS + EPISODE_GRID_GAP * (EPISODE_GRID_ROWS - 1)
private const val EPISODE_COLUMNS_MANY = 4
private const val EPISODE_COLUMNS_FEW = 2
private val EPISODE_CELL_SHAPE = RoundedCornerShape(6.dp)
private val EPISODE_CELL_BORDER = 2.dp
private val EPISODE_CELL_H_PADDING = 8.dp
private val EPISODE_CELL_BACKGROUND = Color(0x14FFFFFF)
private val EPISODE_CELL_FOCUS_BACKGROUND = Color(0x1FFFFFFF)
private const val EPISODE_CELL_SP = 16f
