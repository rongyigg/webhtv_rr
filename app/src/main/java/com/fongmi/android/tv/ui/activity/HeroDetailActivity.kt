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
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.itemsIndexed
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
 * - 点「播放」→ [HeroPlayDialog]：焦点强制进入弹窗并落到 `lastWatchedEpisode`（无历史则第 1 集）。
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
    // 两组可聚焦区域：按钮组 + 演职员行。跨区移动全部显式 requestFocus，不依赖 2D 焦点搜索。
    val actionRequesters = remember { List(DETAIL_ACTIONS.size) { FocusRequester() } }
    val castRequesters = remember(detail.cast.size) { List(detail.cast.size) { FocusRequester() } }
    var focusedAction by remember { mutableIntStateOf(DETAIL_ACTION_PLAY) }
    var focusedCast by remember { mutableIntStateOf(0) }
    var showPlayDialog by remember { mutableStateOf(false) }
    // 正在查看的演员下标；非 null 时演员详情弹窗可见。
    var actorDialogIndex by remember { mutableStateOf<Int?>(null) }
    // 弹窗关闭后要回去的演员下标；-1 表示「没有待恢复的演员焦点」。
    var pendingCastFocus by remember { mutableIntStateOf(-1) }

    // 两个弹窗都关闭后才抢焦点：
    // - 刚关掉演员弹窗 → 回到被点的那位演员（需求：焦点必须回到刚才点击的头像）；
    // - 否则（首次进入 / 关闭播放弹窗）→ 回到「播放」。
    LaunchedEffect(showPlayDialog, actorDialogIndex) {
        if (showPlayDialog || actorDialogIndex != null) return@LaunchedEffect
        val castTarget = pendingCastFocus
        pendingCastFocus = -1
        if (castTarget >= 0) {
            repeat(5) {
                if (requestDetailFocus(castRequesters, castTarget)) return@LaunchedEffect
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
                // 「播放」拉起播放弹窗（线路 + 选集），其余按钮本阶段仍是占位。
                onConfirm = { index -> if (index == DETAIL_ACTION_PLAY) showPlayDialog = true },
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
        HeroPlayDialog(detail = detail, onDismiss = { showPlayDialog = false })
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
    HeroDetailSectionTitle(text = "导演 / 主演", fontSize = CAST_TITLE_SP.sp)
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
 * 焦点态按需求取 [HeroAccentColor] 纯红底 + 纯白文字/图标；
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
// 选集弹窗：统一外壳 + 顶部栏 + 控制栏 + 动态网格
// ---------------------------------------------------------------------------

/**
 * 选集弹窗：统一外壳 + 顶部栏 + 播放线路 + 选集标题行（两个 Toggle）+ 动态网格。
 *
 * 外壳严格对齐 `ref_docs/dialog_source.xml`：`#E6000000`、16dp 圆角、18dp 内边距；
 * 顶部栏 32dp（红底白字「选集」徽章 24dp / 11sp + 12sp 剧名跑马灯）。
 *
 * 两个分区标题统一走 [HeroDetailSectionTitle]：4dp × 16dp 红竖条 + 8dp 间距 + 15sp 加粗。
 * 「播放线路」「选集」标题与「第 x / y 组」都是纯展示文本，没有任何 focusable / clickable。
 *
 * 焦点契约：
 * - 打开落在历史集（`lastWatchedEpisode`）；新剧落第 1 集。
 * - 线路栏 ←/→ 在站点间移动，端点消费；↓ 进控制栏；↑ 消费。
 * - 控制栏（排序 / 列数两个 Toggle）←/→ 互移；↓ 进网格第一集；↑ 回线路栏。
 * - 网格第 1 行 ↑ 回控制栏；最右列 → 切下一组第一集；最左列 ← 切上一组最后一集。
 * - 切排序 / 切列数后按集号重新定位，焦点不漂移。
 * - 返回键关闭弹窗，主页面把焦点还给「播放」。
 */
@Composable
private fun HeroPlayDialog(detail: HeroDetail, onDismiss: () -> Unit) {
    // 排序只有「正序 / 倒序」两态：默认正序，点一次整体翻转。
    var descending by remember { mutableStateOf(false) }
    // 列数只有「多列(4) / 少列(2)」两态：默认多列。
    var fewColumns by remember { mutableStateOf(false) }

    val ordered = remember(descending, detail.episodes) {
        val ascending = detail.episodes.sortedBy { it.number }
        if (descending) ascending.asReversed() else ascending
    }
    val columns = if (fewColumns) EPISODE_COLUMNS_FEW else EPISODE_COLUMNS_MANY
    // 分组与列数解耦：永远每 20 集一组（1-20 / 21-40 / 41-50），列数只改每行几格。
    val groupCount = ((ordered.size + EPISODE_GROUP_SIZE - 1) / EPISODE_GROUP_SIZE).coerceAtLeast(1)

    // 记忆用「集号」而不是下标：切排序 / 切列数后仍能找回同一集。
    val initialIndex = remember {
        ordered.indexOfFirst { it.number == detail.lastWatchedEpisode }.takeIf { it >= 0 } ?: 0
    }
    val initialNumber = ordered.getOrNull(initialIndex)?.number ?: 0
    var currentNumber by remember { mutableIntStateOf(initialNumber) }
    var playingNumber by remember { mutableIntStateOf(initialNumber) }
    var group by remember { mutableIntStateOf(initialIndex / EPISODE_GROUP_SIZE) }
    var selectedFlag by remember { mutableIntStateOf(0) }
    var focusedFlag by remember { mutableIntStateOf(0) }
    var focusedToggle by remember { mutableIntStateOf(TOGGLE_SORT) }

    val groupItems = remember(ordered, group) {
        ordered.drop(group * EPISODE_GROUP_SIZE).take(EPISODE_GROUP_SIZE)
    }
    val pageLabel = "第 ${group + 1} / $groupCount 组"

    // 待落焦的组内下标；-1 = 不抢焦点（例如焦点还停在 Toggle 上）。
    // serial 让「同一个下标连续请求两次」也能重新触发落焦。
    var pendingCell by remember { mutableIntStateOf(initialIndex % EPISODE_GROUP_SIZE) }
    var pendingSerial by remember { mutableIntStateOf(0) }

    val flagRequesters = remember { List(detail.flags.size) { FocusRequester() } }
    val toggleRequesters = remember { List(TOGGLE_INDEX_COUNT) { FocusRequester() } }
    // 网格 item 用「组内下标」做 key：切组时节点原样复用，焦点不会被整片销毁重建成空档。
    val cellRequesters = remember { List(EPISODE_GROUP_SIZE) { FocusRequester() } }
    val gridState = remember { LazyGridState() }

    // 滚动 + 落焦：Lazy 项要先组合出来 `requestFocus` 才拿得到节点，所以逐帧重试。
    LaunchedEffect(group, columns, descending, pendingCell, pendingSerial) {
        val target = pendingCell
        if (target < 0 || target !in groupItems.indices) return@LaunchedEffect
        revealCell(gridState, target, columns)
        repeat(FOCUS_RETRY_FRAMES) {
            if (requestDetailFocus(cellRequesters, target)) {
                currentNumber = groupItems[target].number
                pendingCell = -1
                return@LaunchedEffect
            }
            withFrameNanos { }
        }
    }

    fun focusFlag(index: Int) {
        if (requestDetailFocus(flagRequesters, index)) focusedFlag = index
    }

    fun focusToggle(index: Int) {
        if (requestDetailFocus(toggleRequesters, index)) focusedToggle = index
    }

    /** 网格内移动：只登记目标，真正的滚动 / 落焦交给上面的 LaunchedEffect。 */
    fun moveCell(index: Int) {
        if (groupItems.isEmpty()) return
        val target = index.coerceIn(0, groupItems.lastIndex)
        currentNumber = groupItems[target].number
        pendingCell = target
        pendingSerial++
    }

    /** 跨组：`toLast` = true 落该组最后一集（左切），false 落第一集（右切）。 */
    fun switchGroup(targetGroup: Int, toLast: Boolean) {
        if (targetGroup !in 0 until groupCount) return
        val items = ordered.drop(targetGroup * EPISODE_GROUP_SIZE).take(EPISODE_GROUP_SIZE)
        if (items.isEmpty()) return
        val target = if (toLast) items.lastIndex else 0
        group = targetGroup
        currentNumber = items[target].number
        pendingCell = target
        pendingSerial++
    }

    /** 排序翻转：按集号重新定位分组；焦点留在 Toggle 上，不把焦点抢进网格。 */
    fun toggleSort() {
        val next = !descending
        val reordered = detail.episodes.sortedBy { it.number }.let { if (next) it.asReversed() else it }
        val index = reordered.indexOfFirst { it.number == currentNumber }
        descending = next
        if (index >= 0) {
            group = index / EPISODE_GROUP_SIZE
            pendingCell = -1
        }
    }

    /** 列数切换：分组不变，只重算每行格数。 */
    fun toggleColumns() {
        fewColumns = !fewColumns
        pendingCell = -1
    }

    fun handleCellKey(cellIndex: Int, key: Key) {
        // 连按时焦点可能还没落到上一格：以「待落焦格」为基准，按键不丢。
        val lastIndex = groupItems.lastIndex
        val base = if (pendingCell in 0..lastIndex) pendingCell else cellIndex
        val column = base % columns
        when (key) {
            // 第一集（第 1 行第 1 列）按左 = 上一组最后一集；其余行首按左消费，不乱跳组。
            Key.DirectionLeft -> when {
                base == 0 -> switchGroup(group - 1, toLast = true)
                column > 0 -> moveCell(base - 1)
            }

            // 最后一集（末行末列）按右 = 下一组第一集；其余行尾按右消费，绝不越界。
            Key.DirectionRight -> when {
                base == lastIndex -> switchGroup(group + 1, toLast = false)
                column < columns - 1 -> moveCell(base + 1)
            }

            Key.DirectionUp ->
                if (base >= columns) moveCell(base - columns) else focusToggle(focusedToggle)

            Key.DirectionDown ->
                if (base + columns <= lastIndex) moveCell(base + columns)
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
        // 全屏遮罩 + 居中面板；遮罩让底图继续透出来一点，形成「半透明」层次。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(DIALOG_SCRIM),
            contentAlignment = Alignment.Center,
        ) {
            HeroDialogShell(modifier = Modifier.fillMaxWidth(DIALOG_WIDTH_FRACTION)) {
                // ---- 顶部栏：红色「选集」徽章 + 剧名跑马灯（dialog_source.xml L11-50）----
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(DIALOG_TOP_BAR_HEIGHT),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .height(DIALOG_BADGE_HEIGHT)
                            .clip(DIALOG_BADGE_SHAPE)
                            .background(HeroAccentColor)
                            .padding(horizontal = DIALOG_BADGE_H_PADDING),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "选集",
                            color = Color.White,
                            fontSize = DIALOG_BADGE_SP.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                    }
                    Spacer(modifier = Modifier.width(DIALOG_BADGE_GAP))
                    // 剧名过长滚动显示，绝不生硬截断。
                    Text(
                        text = detail.title,
                        modifier = Modifier
                            .weight(1f)
                            .basicMarquee(),
                        color = DIALOG_TITLE_COLOR,
                        fontSize = DIALOG_TITLE_SP.sp,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                Spacer(modifier = Modifier.height(DIALOG_TOP_BAR_GAP))

                // ---- 播放线路：4dp 红竖条标题 + 39dp 横排（标题纯展示，不可聚焦）----
                HeroDetailSectionTitle(text = "播放线路", fontSize = DIALOG_SECTION_TITLE_SP.sp)
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
                            requester = flagRequesters[index],
                            onFocused = { focusedFlag = index },
                            onConfirm = { selectedFlag = index },
                            // 线路栏 ↑ 上方只有不可聚焦的顶部栏，直接消费。
                            onMoveUp = { },
                            onMoveDown = { focusToggle(focusedToggle) },
                            onMoveLeft = { if (index > 0) focusFlag(index - 1) },
                            onMoveRight = { if (index < detail.flags.lastIndex) focusFlag(index + 1) },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(DIALOG_SECTION_GAP))

                // ---- 选集标题行：红竖条 + 标题 + 组标（均不可聚焦）+ 右侧两个 Toggle ----
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(DIALOG_TOGGLE_HEIGHT),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HeroDetailSectionTitle(text = "选集", fontSize = DIALOG_SECTION_TITLE_SP.sp)
                    Spacer(modifier = Modifier.width(DIALOG_TOGGLE_GAP))
                    // 纯展示：不允许获得焦点。
                    Text(
                        text = pageLabel,
                        color = DIALOG_HINT_COLOR,
                        fontSize = DIALOG_HINT_SP.sp,
                        maxLines = 1,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    HeroToggleButton(
                        label = if (descending) "倒序" else "正序",
                        requester = toggleRequesters[TOGGLE_SORT],
                        onFocused = { focusedToggle = TOGGLE_SORT },
                        onConfirm = { toggleSort() },
                        onMoveUp = { focusFlag(focusedFlag) },
                        onMoveDown = { moveCell(0) },
                        onMoveLeft = { },
                        onMoveRight = { focusToggle(TOGGLE_COLUMNS) },
                    )
                    Spacer(modifier = Modifier.width(DIALOG_TOGGLE_GAP))
                    HeroToggleButton(
                        label = if (fewColumns) "少列" else "多列",
                        requester = toggleRequesters[TOGGLE_COLUMNS],
                        onFocused = { focusedToggle = TOGGLE_COLUMNS },
                        onConfirm = { toggleColumns() },
                        onMoveUp = { focusFlag(focusedFlag) },
                        onMoveDown = { moveCell(0) },
                        onMoveLeft = { focusToggle(TOGGLE_SORT) },
                        onMoveRight = { },
                    )
                }
                Spacer(modifier = Modifier.height(DIALOG_CONTROL_GAP))

                // ---- 动态网格：多列 4 列 / 少列 2 列；每 20 集一组，240dp 内可上下滚动 ----
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    state = gridState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(DIALOG_GRID_HEIGHT),
                    contentPadding = PaddingValues(vertical = DIALOG_GRID_VERTICAL_PADDING),
                    horizontalArrangement = Arrangement.spacedBy(DIALOG_GRID_GAP),
                    verticalArrangement = Arrangement.spacedBy(DIALOG_GRID_GAP),
                ) {
                    groupItems.forEachIndexed { index, episode ->
                        item(key = index) {
                            HeroEpisodeCell(
                                label = episode.label,
                                playing = episode.number == playingNumber,
                                requester = cellRequesters[index],
                                onFocused = { currentNumber = episode.number },
                                onConfirm = { playingNumber = episode.number },
                                onMove = { key -> handleCellKey(index, key) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 统一弹窗外壳（`ref_docs/dialog_source.xml`）：深色半透明 `#E6000000` + 16dp 圆角 + 18dp 内边距。
 * 选集弹窗与演员详情弹窗都套这一层，保证所有弹窗视觉一致。
 */
@Composable
private fun HeroDialogShell(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(DIALOG_SHELL_SHAPE)
            .background(DIALOG_SHELL_BACKGROUND)
            .padding(DIALOG_SHELL_PADDING),
        content = content,
    )
}

/**
 * 让格子在 240dp 视口里可见：只在目标行越界时滚动，避免每按一次方向键整屏跳动。
 * [EPISODE_VISIBLE_ROWS] 对应 240dp 高 / 34dp 格子 + 8dp 间距的整数行。
 */
private suspend fun revealCell(state: LazyGridState, index: Int, columns: Int) {
    val row = index / columns
    val firstRow = state.firstVisibleItemIndex / columns
    val targetFirstRow = when {
        row < firstRow -> row
        row >= firstRow + EPISODE_VISIBLE_ROWS -> row - EPISODE_VISIBLE_ROWS + 1
        else -> firstRow
    }
    if (targetFirstRow == firstRow) return
    runCatching { state.scrollToItem(targetFirstRow.coerceAtLeast(0) * columns) }
    withFrameNanos { }
}

/**
 * 播放线路项：39dp 高 / 14sp。
 * 常态深灰底白字，选中略亮（记住当前线路），焦点红底白字。
 */
@Composable
private fun HeroFlagChip(
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
            .background(
                when {
                    isFocused -> HeroAccentColor
                    selected -> DIALOG_FLAG_SELECTED_BG
                    else -> DIALOG_FLAG_IDLE_BG
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
 * 控制栏 Toggle：32dp 高 / 14sp，单按钮双态（文案即当前状态）。
 * 未选中深灰底白字，焦点红底白字。
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
) {
    val interaction = remember { MutableInteractionSource() }
    val isFocused by interaction.collectIsFocusedAsState()

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
            .background(if (isFocused) HeroAccentColor else DIALOG_TOGGLE_IDLE_BG)
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
 * 选集格子：高 34dp，焦点态红框，当前播放集用红字区分。
 * 四方向键全部在本组件内消费，交给 `onMove` 统一做边界、翻组与回控制栏。
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

/**
 * 演职员行移动：只负责请求焦点。
 * 记忆索引由每个条目的 `onFocusChanged` 维护（Lazy 项未组合时 request 会失败，忽略即可）。
 */
private fun focusCast(requesters: List<FocusRequester>, index: Int) {
    requestDetailFocus(requesters, index)
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
    /** 历史播放进度（1 基集号）；0 表示新剧，焦点落第 1 集。 */
    val lastWatchedEpisode: Int,
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
    // 20 集一组 → 1-20 / 21-40 / 41-50，最后一组只有 10 集（右切越界重点）。
    episodes = (1..50).map { number ->
        HeroEpisode(
            number = number,
            label = if (number == 15) "第15集_超长名字测试跑马灯效果" else "第${number}集",
        )
    },
    // 默认吸附第 15 集：一进弹窗就能看到超长名字的跑马灯 + 第 1 组第 4 行。
    lastWatchedEpisode = 15,
)

/** 按钮顺序按需求固定为 播放 | 换源 | 收藏。 */
private val DETAIL_ACTIONS = listOf(
    HeroDetailAction(label = "播放", iconRes = R.drawable.ic_widget_play, iconSize = 24.dp),
    HeroDetailAction(label = "换源", iconRes = R.drawable.ic_detail_change, iconSize = 20.dp),
    HeroDetailAction(label = "收藏", iconRes = R.drawable.ic_detail_keep_off, iconSize = 20.dp),
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
// 区块标题：4dp × 16dp 红色小竖条 + 15sp 加粗
// ---------------------------------------------------------------------------
private val SECTION_BAR_WIDTH = 4.dp
private val SECTION_BAR_HEIGHT = 16.dp
private val SECTION_BAR_SHAPE = RoundedCornerShape(2.dp)
private val SECTION_BAR_GAP = 8.dp
private const val SECTION_TITLE_SP = 15f

// ---------------------------------------------------------------------------
// 统一弹窗外壳（ref_docs/dialog_source.xml：padding 18dp、深色半透明、16dp 圆角）
// ---------------------------------------------------------------------------
private const val DIALOG_WIDTH_FRACTION = 0.82f
private val DIALOG_SCRIM = Color(0x99000000)
private val DIALOG_SHELL_BACKGROUND = Color(0xE6000000)
private val DIALOG_SHELL_SHAPE = RoundedCornerShape(16.dp)
private val DIALOG_SHELL_PADDING = 18.dp

// 顶部栏：32dp 行高 / 8dp 下间距；徽章 24dp、11sp；剧名 12sp 跑马灯（dialog_source.xml L11-50）
private val DIALOG_TOP_BAR_HEIGHT = 32.dp
private val DIALOG_TOP_BAR_GAP = 8.dp
private val DIALOG_BADGE_HEIGHT = 24.dp
private val DIALOG_BADGE_SHAPE = RoundedCornerShape(4.dp)
private val DIALOG_BADGE_H_PADDING = 10.dp
private const val DIALOG_BADGE_SP = 11f
private val DIALOG_BADGE_GAP = 12.dp
private const val DIALOG_TITLE_SP = 12f
private val DIALOG_TITLE_COLOR = Color(0x99FFFFFF)

// 分区间距：标题下 8dp、区块之间 16dp、标题行到网格 8dp
private val DIALOG_TITLE_GAP = 8.dp
private val DIALOG_SECTION_GAP = 16.dp
private val DIALOG_CONTROL_GAP = 8.dp
private const val DIALOG_SECTION_TITLE_SP = 15f
private const val DIALOG_HINT_SP = 11f
private val DIALOG_HINT_COLOR = Color(0x59FFFFFF)

// 播放线路：39dp 高 / 14sp；深灰底白字，焦点红底白字
private val DIALOG_FLAG_ROW_HEIGHT = 39.dp
private val DIALOG_FLAG_GAP = 12.dp
private val DIALOG_FLAG_H_PADDING = 14.dp
private val DIALOG_FLAG_SHAPE = RoundedCornerShape(4.dp)
private const val DIALOG_FLAG_SP = 14f
private val DIALOG_FLAG_IDLE_BG = Color(0x1AFFFFFF)
private val DIALOG_FLAG_SELECTED_BG = Color(0x33FFFFFF)

// 控制栏 Toggle：32dp 高 / 14sp；深灰底白字，焦点红底白字
private val DIALOG_TOGGLE_HEIGHT = 32.dp
private val DIALOG_TOGGLE_GAP = 8.dp
private val DIALOG_TOGGLE_H_PADDING = 14.dp
private val DIALOG_TOGGLE_SHAPE = RoundedCornerShape(4.dp)
private const val DIALOG_TOGGLE_SP = 14f
private val DIALOG_TOGGLE_IDLE_BG = Color(0x33FFFFFF)

/** 控制栏两个 Toggle 的下标。 */
private const val TOGGLE_SORT = 0
private const val TOGGLE_COLUMNS = 1
private const val TOGGLE_INDEX_COUNT = 2

/** Lazy 项从请求焦点到真正拿到节点要几帧，多给几帧兜住首帧未 attach。 */
private const val FOCUS_RETRY_FRAMES = 10

// ---------------------------------------------------------------------------
// 选集网格：高 240dp、格高 34dp；每 20 集一组（与列数解耦），多列 4 列 / 少列 2 列
// 240 = 16(上) + 34×5 + 8×4 + 6 余量 → 正好 5 整行不裁切
// ---------------------------------------------------------------------------
private val DIALOG_GRID_HEIGHT = 240.dp
private val DIALOG_GRID_VERTICAL_PADDING = 16.dp
private val DIALOG_GRID_GAP = 8.dp
private const val EPISODE_GROUP_SIZE = 20
private const val EPISODE_VISIBLE_ROWS = 5
private const val EPISODE_COLUMNS_MANY = 4
private const val EPISODE_COLUMNS_FEW = 2
private val EPISODE_CELL_HEIGHT = 34.dp
private val EPISODE_CELL_SHAPE = RoundedCornerShape(6.dp)
private val EPISODE_CELL_BORDER = 2.dp
private val EPISODE_CELL_H_PADDING = 8.dp
private val EPISODE_CELL_BACKGROUND = Color(0x14FFFFFF)
private val EPISODE_CELL_FOCUS_BACKGROUND = Color(0x1FFFFFFF)
private const val EPISODE_CELL_SP = 16f
