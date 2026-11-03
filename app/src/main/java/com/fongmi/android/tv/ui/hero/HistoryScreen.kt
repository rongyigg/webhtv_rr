package com.fongmi.android.tv.ui.hero

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.compose.AsyncImage
import com.fongmi.android.tv.R
import com.fongmi.android.tv.bean.History
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * HeroUI 历史页（Compose）。
 *
 * 逐节点复刻 `ref_docs/fragment_history.xml`（布局树）+ `ref_docs/o11.java`（交互），
 * 卡片复用全局唯一组件 [HeroPosterCard]（本页不内联任何卡片布局）。
 *
 * 层次（与 XML 完全同序）：
 * 1. `heroBg`（全屏 centerCrop，Coil 加载 1920×1080，交叉淡入见 [HeroBackdrop]）；
 * 2. `maskLeft` / `maskBottom`（`bg_hero_mask_h` / `bg_hero_mask_v`，初始 gone）；
 * 3. `pageSite`（左上角站点角标）；
 * 4. `posterCard`（右上角 150×210 回退海报，仅背景大图为空时出现）；
 * 5. `posterContent`（底部信息区 + 续播行 + 215dp 海报行）。
 *
 * 所有 dp / sp / 颜色 / 时长都标注了 `ref_docs` 出处；查不到的一律进
 * `docs/HERO-HISTORY-compose-history-screen.md` 的「待确认清单」，代码里保留占位常量。
 */
@Composable
fun HistoryScreen(viewModel: HistoryViewModel, modifier: Modifier = Modifier) {
    val state = viewModel.state
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    // 删除确认框当前指向的下标；-1 = 不显示。
    var deleteIndex by remember { mutableIntStateOf(-1) }
    // 需要把 D-pad 焦点抢到第几张卡；-1 = 不需要。
    var pendingFocus by remember { mutableIntStateOf(-1) }
    // 删除后要求重新落焦点（删掉的是当前聚焦卡，View 随列表项一起销毁）。
    var refocusAfterDelete by remember { mutableStateOf(false) }

    val resumeFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val contentAlpha = remember { Animatable(0f) }

    // 首次装载（对应 `o11.a0()` + `m11` case 3）。
    LaunchedEffect(Unit) { viewModel.load() }

    // 数据到达：内容 alpha 0→1，100ms（对应 `posterContent` 从 gone 到可见的淡入）。
    LaunchedEffect(state.loaded) {
        if (state.loaded) contentAlpha.animateTo(1f, tween(CONTENT_FADE_MS))
    }

    // 进页默认焦点：第一张卡（对应 `m11` case 1/2 的 `recycler.post { focusPoster(0) }`）。
    LaunchedEffect(state.loaded) {
        if (state.loaded && state.items.isNotEmpty()) pendingFocus = 0
    }

    // 删除后把焦点还给「回退后的选中项」。
    LaunchedEffect(state.selectedIndex, state.items.size) {
        if (refocusAfterDelete && state.items.isNotEmpty()) {
            refocusAfterDelete = false
            pendingFocus = state.selectedIndex.coerceAtLeast(0)
        }
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(modifier = modifier.fillMaxSize().background(Color.Black)) {

            // ---------------------------------------------------------------
            // 1) 背景层（自下而上）
            // ---------------------------------------------------------------
            HeroBackdrop(url = state.hero.backdropUrl)

            // maskLeft / maskBottom 在 XML 里初始 gone，`o11.H()` L163-164 建视图时置为 visible。
            if (state.loaded) {
                HeroMaskLeft()
                HeroMaskBottom()
            }

            // ---------------------------------------------------------------
            // 2) 左上角 pageSite（XML L32-47）：13sp bold 白 / 26dp / 12dp 内边距 / 24dp 外边距
            // ---------------------------------------------------------------
            if (state.loaded && state.hero.pageSite.isNotEmpty()) {
                PageSite(text = state.hero.pageSite, modifier = Modifier.align(Alignment.TopStart))
            }

            // ---------------------------------------------------------------
            // 3) 右上角 posterCard（XML L48-66）：150×210 / 12dp 圆角 / 4dp 内边距 / 72dp 顶 / 80dp 右
            //    仅在背景大图为空时显示（`o11.q0` L527-535）。
            // ---------------------------------------------------------------
            if (state.loaded && state.hero.backdropUrl.isEmpty() && state.hero.posterUrl.isNotEmpty()) {
                PosterFallback(
                    url = state.hero.posterUrl,
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            }

            // ---------------------------------------------------------------
            // 4) 加载 / 空态（ProgressLayout 语义：`view_progress` / `view_empty`）
            // ---------------------------------------------------------------
            if (!state.loaded) {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else if (state.items.isEmpty()) {
                HistoryEmptyState(modifier = Modifier.align(Alignment.Center))
            }

            // ---------------------------------------------------------------
            // 5) 底部内容区（XML L72-239 `posterContent`，alignParentBottom）
            // ---------------------------------------------------------------
            if (state.loaded && state.items.isNotEmpty()) {
                PosterContent(
                    state = state,
                    contentAlpha = contentAlpha.value,
                    listState = listState,
                    resumeFocus = resumeFocus,
                    pendingFocus = pendingFocus,
                    onFocusConsumed = { pendingFocus = -1 },
                    onCardFocused = { index ->
                        if (state.selectedIndex != index) viewModel.select(index)
                        // 焦点滚动对齐到起点（`o11.H()` L173 `setFocusScrollStrategy(0)`）。
                        scope.launch { listState.scrollToItem(index) }
                    },
                    onCardClick = { item -> viewModel.openResume(activity, item) },
                    onCardLongClick = { index -> deleteIndex = index },
                    onCardUp = {
                        runCatching { resumeFocus.requestFocus() }
                    },
                    onResumeClick = { viewModel.openDetail(activity) },
                    onResumeDown = { pendingFocus = state.selectedIndex.coerceAtLeast(0) },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }

            // ---------------------------------------------------------------
            // 6) 删除确认框（长按卡片；文案为建议值，见文档待确认清单）
            // ---------------------------------------------------------------
            val deleting = state.items.getOrNull(deleteIndex)
            if (deleting != null) {
                AlertDialog(
                    onDismissRequest = { deleteIndex = -1 },
                    title = { Text(text = DELETE_TITLE) },
                    text = { Text(text = deleteMessage(deleting.vodName.orEmpty())) },
                    confirmButton = {
                        TextButton(onClick = {
                            val index = deleteIndex
                            deleteIndex = -1
                            if (index >= 0) {
                                viewModel.deleteAt(index)
                                refocusAfterDelete = true
                            }
                        }) {
                            Text(text = DELETE_CONFIRM)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { deleteIndex = -1 }) {
                            Text(text = DELETE_CANCEL)
                        }
                    },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 背景层
// ---------------------------------------------------------------------------

/**
 * 全屏背景大图（XML L13-19 `heroBg`，`HeroImageView` 等价物）。
 *
 * `HeroImageView.java` L125-126 固定加载 1920×1080；L114-175 `f()` 的交叉淡入为
 * scale 1.03→1.0 + alpha 0→1、450ms、DecelerateInterpolator，且**开始前延迟 220ms**
 * （`o11.p0` L504-506 非首次切换延迟 200ms，这里是 HeroImageView 自身的 220ms 起始延迟）。
 */
@Composable
private fun HeroBackdrop(url: String) {
    if (url.isEmpty()) return
    val alpha = remember { Animatable(0f) }
    val scale = remember { Animatable(BACKDROP_START_SCALE) }
    LaunchedEffect(url) {
        alpha.snapTo(0f)
        scale.snapTo(BACKDROP_START_SCALE)
        delay(BACKDROP_START_DELAY_MS)
        coroutineScope {
            launch { alpha.animateTo(1f, tween(BACKDROP_FADE_MS, easing = LinearOutSlowInEasing)) }
            launch { scale.animateTo(1f, tween(BACKDROP_FADE_MS, easing = LinearOutSlowInEasing)) }
        }
    }
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                this.alpha = alpha.value
                scaleX = scale.value
                scaleY = scale.value
            },
    )
}

/**
 * 横向遮罩（XML L20-25 `maskLeft` → `bg_hero_mask_h.xml`）：
 * angle=0，左端 `#ff000000` → 中点 `#00000000` → 右端 `#00000000`。
 */
@Composable
private fun HeroMaskLeft() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.horizontalGradient(
                    0.0f to Color(0xFF000000),
                    0.5f to Color.Transparent,
                    1.0f to Color.Transparent,
                ),
            ),
    )
}

/**
 * 纵向遮罩（XML L26-31 `maskBottom` → `bg_hero_mask_v.xml`）：
 * angle=90 表示**从底部往顶部**，即底端 `#ff000000` → 中点 `#00000000` → 顶端 `#00000000`。
 */
@Composable
private fun HeroMaskBottom() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0.0f to Color.Transparent,
                    0.5f to Color.Transparent,
                    1.0f to Color(0xFF000000),
                ),
            ),
    )
}

// ---------------------------------------------------------------------------
// 角标 / 回退海报
// ---------------------------------------------------------------------------

/**
 * 左上角站点角标（XML L32-47 `pageSite` + `shape_config_name.xml`）。
 *
 * 13sp bold 白 / 高 26dp / 左右 12dp 内边距 / 四角 4dp / 24dp 外边距 / 单行。
 * 底色取工程既有 `@color/red`（`res/values/colors.xml` L56 = `#E50914`）——
 * `ref_docs/colors.xml` 没有 `red` 条目，数值来自本仓库既有资源，不是新造色。
 */
@Composable
private fun PageSite(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(PAGE_SITE_MARGIN)
            .height(PAGE_SITE_HEIGHT)
            .clip(PAGE_SITE_SHAPE)
            .background(colorResource(id = R.color.red))
            .padding(horizontal = PAGE_SITE_PADDING_H),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = PAGE_SITE_SP.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            softWrap = false,
        )
    }
}

/**
 * 右上角回退海报（XML L48-66 `posterCard`）。
 *
 * 150×210 / `shape_poster_card`（`black_20` = `#33000000`、12dp 圆角）/ 4dp 内边距 /
 * marginTop 72dp / marginEnd 80dp / `clipToOutline`。内部 `posterCardImg` 铺满 centerCrop。
 */
@Composable
private fun PosterFallback(url: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(top = POSTER_CARD_TOP, end = POSTER_CARD_END)
            .size(POSTER_CARD_WIDTH, POSTER_CARD_HEIGHT)
            .clip(POSTER_CARD_SHAPE)
            .background(POSTER_CARD_BACKGROUND)
            .padding(POSTER_CARD_PADDING),
    ) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .clip(POSTER_CARD_SHAPE),
        )
    }
}

// ---------------------------------------------------------------------------
// 底部内容区
// ---------------------------------------------------------------------------

/**
 * `posterContent`（XML L72-239）：信息组 → 续播行 → 215dp 海报行，整体贴底。
 */
@Composable
private fun PosterContent(
    state: HistoryUiState,
    contentAlpha: Float,
    listState: LazyListState,
    resumeFocus: FocusRequester,
    pendingFocus: Int,
    onFocusConsumed: () -> Unit,
    onCardFocused: (Int) -> Unit,
    onCardClick: (History) -> Unit,
    onCardLongClick: (Int) -> Unit,
    onCardUp: () -> Unit,
    onResumeClick: () -> Unit,
    onResumeDown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = contentAlpha },
    ) {
        HeroInfoGroup(hero = state.hero)

        ResumeRow(
            hero = state.hero,
            resumeFocus = resumeFocus,
            onResumeClick = onResumeClick,
            onResumeDown = onResumeDown,
        )

        PosterRow(
            state = state,
            listState = listState,
            pendingFocus = pendingFocus,
            onFocusConsumed = onFocusConsumed,
            onCardFocused = onCardFocused,
            onCardClick = onCardClick,
            onCardLongClick = onCardLongClick,
            onCardUp = onCardUp,
        )
    }
}

/**
 * 信息组（XML L81-146）。
 *
 * - `heroNameText`：32sp bold 白 / 高 36dp / gravity bottom / 阴影 `#cc000000` 2/2/12；
 *   超宽跑马灯阈值与最大宽度都是「屏宽 × 0.6」（`nm3.i`）。
 * - `heroLogoImg`：72dp / fitStart / maxWidth 720dp（本工程无 Logo 数据源，恒不显示）。
 * - `heroMetaText`：14sp `#ccffffff` / 高 20dp / marginTop 2dp / 阴影 `#aa000000` 1.5/1.5/8。
 * - `heroDescText`：12sp `#bbffffff` / 高 40dp / maxLines 2 / ellipsize end / lineSpacingExtra 3dp。
 */
@Composable
private fun HeroInfoGroup(hero: HeroInfo) {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val nameMaxWidth = screenWidth * HERO_NAME_WIDTH_RATIO
    val nameShadow = rememberTextShadow(Color(0xCC000000), 2.dp, 2.dp, 12.dp)
    val metaShadow = rememberTextShadow(Color(0xAA000000), 1.5.dp, 1.5.dp, 8.dp)
    val descShadow = rememberTextShadow(Color(0xAA000000), 1.2.dp, 1.2.dp, 6.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = INFO_BOTTOM)
            .padding(horizontal = INFO_PADDING_H),
    ) {
        // heroNameText：`invisible` 语义 = 占位但不绘制，这里用固定高度 Box 保持版式。
        Box(
            modifier = Modifier
                .height(HERO_NAME_HEIGHT)
                .widthIn(max = nameMaxWidth),
            contentAlignment = Alignment.BottomStart,
        ) {
            if (hero.name.isNotEmpty()) {
                Text(
                    text = hero.name,
                    color = Color.White,
                    fontSize = HERO_NAME_SP.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    style = TextStyle(shadow = nameShadow),
                    modifier = Modifier
                        .widthIn(max = nameMaxWidth)
                        .basicMarquee(iterations = Int.MAX_VALUE),
                )
            }
        }

        // heroLogoImg：`gd3.q(vodName)` 在本工程无等价入口 → 恒空 → 剧名文字常显（见待确认清单）。
        if (hero.logoUrl.isNotEmpty()) {
            AsyncImage(
                model = hero.logoUrl,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                alignment = Alignment.CenterStart,
                modifier = Modifier
                    .height(LOGO_HEIGHT)
                    .widthIn(max = LOGO_MAX_WIDTH),
            )
        }

        Box(
            modifier = Modifier
                .padding(top = HERO_META_TOP)
                .height(HERO_META_HEIGHT),
            contentAlignment = Alignment.BottomStart,
        ) {
            if (hero.meta.isNotEmpty()) {
                Text(
                    text = hero.meta,
                    color = HERO_META_COLOR,
                    fontSize = HERO_META_SP.sp,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    style = TextStyle(shadow = metaShadow),
                )
            }
        }

        Box(
            modifier = Modifier
                .padding(top = DESC_TOP)
                .fillMaxWidth(),
        ) {
            if (hero.desc.isNotEmpty()) {
                Text(
                    text = hero.desc,
                    color = HERO_DESC_COLOR,
                    fontSize = HERO_DESC_SP.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    // XML `lineSpacingExtra="3dp"`；Compose 无同名属性，按行高近似（待确认 #7）。
                    style = TextStyle(shadow = descShadow, lineHeight = HERO_DESC_LINE_HEIGHT),
                    modifier = Modifier.height(HERO_DESC_HEIGHT),
                )
            } else {
                Box(modifier = Modifier.height(HERO_DESC_HEIGHT))
            }
        }
    }
}

/**
 * 续播行（XML L147-220 `resumeRow`）：marginBottom 22dp / 左右 24dp / 垂直居中。
 *
 * `btnResume` 14sp、高 32dp、`selector_item_round_resume` + `ic_arrow_down_circle` 14dp、
 * drawablePadding 4dp、文案 `@string/history_view_detail`。
 * `progressGroup` 宽 200dp、marginStart 20dp，仅 `position > 0 && duration > 0` 时可见。
 */
@Composable
private fun ResumeRow(
    hero: HeroInfo,
    resumeFocus: FocusRequester,
    onResumeClick: () -> Unit,
    onResumeDown: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = RESUME_BOTTOM)
            .padding(horizontal = INFO_PADDING_H),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ResumeButton(
            focusRequester = resumeFocus,
            onClick = onResumeClick,
            onMoveDown = onResumeDown,
        )

        if (hero.showProgress) {
            ProgressGroup(
                hero = hero,
                modifier = Modifier
                    .padding(start = PROGRESS_GROUP_START)
                    .width(PROGRESS_GROUP_WIDTH),
            )
        }
    }
}

/**
 * 「查看详情」按钮。
 *
 * 背景三态 `selector_item_round_resume` → `shape_item_round_focused/_activated/_normal`
 * 在本仓库与 `ref_docs` 里都没有具体数值（待确认 #3），这里沿用本工程 Hero 体系已有的
 * 按钮 token：常态 `white_10`（`#1AFFFFFF`）、焦点 `hero_accent`（`#FFFF0000`），
 * 与 `HeroDetailActivity.HeroDetailActionButton` L802 完全同一套写法。
 */
@Composable
private fun ResumeButton(
    focusRequester: FocusRequester,
    onClick: () -> Unit,
    onMoveDown: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val isFocused by interaction.collectIsFocusedAsState()

    Row(
        modifier = Modifier
            .height(RESUME_HEIGHT)
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionDown -> {
                        onMoveDown()
                        true
                    }
                    // XML `nextFocusUp="@+id/homeBtn"`：本页没有顶栏，向上直接消费。
                    Key.DirectionUp -> true
                    else -> false
                }
            }
            .clip(RESUME_SHAPE)
            .background(if (isFocused) RESUME_BACKGROUND_FOCUSED else RESUME_BACKGROUND_IDLE)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = RESUME_PADDING_H),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RESUME_ICON_GAP),
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_arrow_down_circle),
            contentDescription = null,
            modifier = Modifier.size(RESUME_ICON),
        )
        Text(
            text = stringResource(id = R.string.hero_history_view_detail),
            color = Color.White,
            fontSize = RESUME_SP.sp,
            maxLines = 1,
        )
    }
}

/**
 * 进度组（XML L174-219）。
 *
 * 上行：`progressEpisode`（12sp `#ccffffff`、跑马灯、weight 1）+
 * `progressTotal`（12sp `#ccffffff`、marginStart 12dp、`nm3.j(pos) + " / " + nm3.j(dur)`）。
 * 下行：`progressBar` 高 2dp、marginTop 2dp。
 */
@Composable
private fun ProgressGroup(hero: HeroInfo, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = hero.episode,
                color = PROGRESS_TEXT_COLOR,
                fontSize = PROGRESS_TEXT_SP.sp,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                modifier = Modifier
                    .weight(1f)
                    .basicMarquee(iterations = Int.MAX_VALUE),
            )
            Text(
                text = hero.progressText,
                color = PROGRESS_TEXT_COLOR,
                fontSize = PROGRESS_TEXT_SP.sp,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(start = PROGRESS_TOTAL_START),
            )
        }
        HistoryProgressIndicator(
            progress = hero.progress,
            modifier = Modifier
                .padding(top = PROGRESS_BAR_TOP)
                .fillMaxWidth()
                .height(PROGRESS_BAR_HEIGHT),
        )
    }
}

/**
 * `HistoryProgressBar.java` L36-65 的 Compose 复刻：
 * - 轨道：`RectF(0, 0, width, height)` + 圆角 = 高/2，画笔颜色 `-1` = 纯白；
 * - 进度：宽度 = `min(width, max(ratio * width, height))`（非零时至少一个圆点大小），
 *   圆角同轨道，`LinearGradient(0, 0, w, h, dl3.a(), dl3.l())`。
 *
 * **待确认 #1**：`dl3.a()` / `dl3.l()` 两个色值在 `ref_docs` 里只有调用点、没有定义，
 * 这里两端都先钉成工程既有 `hero_accent`（`#FFFF0000`），确认后只改这两个常量。
 */
@Composable
private fun HistoryProgressIndicator(progress: Float, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val radius = size.height / 2f
        drawRoundRect(
            color = Color.White,
            cornerRadius = CornerRadius(radius, radius),
        )
        if (progress <= 0f) return@Canvas
        val barWidth = min(size.width, max(progress * size.width, size.height))
        drawRoundRect(
            brush = Brush.horizontalGradient(
                colors = listOf(PROGRESS_GRADIENT_START, PROGRESS_GRADIENT_END),
                startX = 0f,
                endX = barWidth,
            ),
            size = Size(barWidth, size.height),
            cornerRadius = CornerRadius(radius, radius),
        )
    }
}

/**
 * 215dp 海报行（XML L221-238）。
 *
 * `HorizontalGridView` 的等价物是 [LazyRow]：高 215dp、左右 padding 24dp、间距 16dp
 * （`o11.H()` L171 `setHorizontalSpacing(dp(16))`），整行缩放 1.08
 * （`o11.H()` L174 `mm3.f(recyclerView, 1.08f)`）。
 */
@Composable
private fun PosterRow(
    state: HistoryUiState,
    listState: LazyListState,
    pendingFocus: Int,
    onFocusConsumed: () -> Unit,
    onCardFocused: (Int) -> Unit,
    onCardClick: (History) -> Unit,
    onCardLongClick: (Int) -> Unit,
    onCardUp: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth().height(POSTER_ROW_HEIGHT)) {
        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = POSTER_ROW_PADDING_H),
            horizontalArrangement = Arrangement.spacedBy(POSTER_ROW_ITEM_GAP),
        ) {
            itemsIndexed(
                items = state.items,
                key = { _, item -> item.key },
            ) { index, item ->
                val focused = index == state.selectedIndex
                Box(
                    modifier = Modifier
                        .graphicsLayer {
                            scaleX = if (focused) FOCUS_SCALE else 1f
                            scaleY = if (focused) FOCUS_SCALE else 1f
                        }
                        .width(HeroPosterCard.CARD_WIDTH_DP.dp)
                        .height(HeroPosterCard.CARD_HEIGHT_DP.dp),
                ) {
                    HistoryCard(
                        item = item,
                        index = index,
                        pendingFocus = pendingFocus,
                        onFocusConsumed = onFocusConsumed,
                        onFocused = onCardFocused,
                        onClick = { onCardClick(item) },
                        onLongClick = { onCardLongClick(index) },
                        onUp = onCardUp,
                    )
                }
            }
        }
    }
}

/**
 * 单张历史卡：**只做数据绑定**，布局、尺寸、圆角、焦点红框全部来自 [HeroPosterCard]。
 *
 * 字段对应 `s11.java` L89-123：
 * - `name` → [HeroPosterCard.title]；
 * - `image` → [HeroPosterCard.imageUrl]（`j71.e` 加载，这里由组件内 Glide 负责）；
 * - `siteTag` + `siteGradient` → [HeroPosterCard.siteTag]（`s11` L90-118，站点名为空时两者都隐藏）；
 * - `tmdbTag` → [HeroPosterCard.tmdbTag]（`s11.b(history)`，见 [HistoryCardTags]）；
 * - `delete` 遮罩 → 不复刻（编辑模式不在本次范围，见「未实现清单」）。
 */
@Composable
private fun HistoryCard(
    item: History,
    index: Int,
    pendingFocus: Int,
    onFocusConsumed: () -> Unit,
    onFocused: (Int) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onUp: () -> Unit,
) {
    val cardRef = remember { mutableStateOf<HeroPosterCard?>(null) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            HeroPosterCard(context).also { cardRef.value = it }
        },
        update = { card ->
            // 只在值变化时写，避免每次重组都触发 HeroPosterCard 内部的 Glide 重载。
            val name = item.vodName.orEmpty()
            if (card.title != name) card.title = name
            val poster = item.vodPic.orEmpty()
            if (card.imageUrl != poster) card.imageUrl = poster
            val site = item.siteName.orEmpty().ifEmpty { null }
            if (card.siteTag != site) card.siteTag = site
            val tmdb = HistoryCardTags.isTmdbTagVisible(item)
            if (card.tmdbTag != tmdb) card.tmdbTag = tmdb
            // 历史卡没有备注节点（`adapter_history_poster.xml` 无 bottomTag），保持隐藏。
            card.bottomStartTag = ""
            card.topStartTag = ""
            card.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) onFocused(index)
            }
            card.setOnClickListener { onClick() }
            card.setOnLongClickListener {
                onLongClick()
                true
            }
            card.setOnKeyListener { _, keyCode, event ->
                // ↑ → btnResume（XML `btnResume.nextFocusDown = posterRecycler` 的反向）。
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    onUp()
                    true
                } else {
                    false
                }
            }
        },
    )

    // 进页 / 删除后落焦点：等卡片挂到窗口再 requestFocus，失败不重试（Compose 会重组）。
    LaunchedEffect(pendingFocus, index) {
        if (pendingFocus != index) return@LaunchedEffect
        val card = cardRef.value ?: return@LaunchedEffect
        card.post {
            if (card.requestFocus()) onFocusConsumed()
        }
    }
}

// ---------------------------------------------------------------------------
// 空态
// ---------------------------------------------------------------------------

/**
 * 空态（`ProgressLayout.showEmpty()` 的等价物）。
 *
 * 复用本仓库既有 `view_empty.xml`（`res/layout/view_empty.xml`，两个 flavor 都有）：
 * Lottie 200dp × 224dp + 16sp 白字 `@string/error_empty`。
 * `ref_docs/view_empty.xml` 未收录 → 具体图标/文案已进待确认清单 #5。
 */
@Composable
private fun HistoryEmptyState(modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            val root = FrameLayout(context)
            LayoutInflater.from(context).inflate(R.layout.view_empty, root, false)
        },
    )
}

// ---------------------------------------------------------------------------
// 工具
// ---------------------------------------------------------------------------

/** `TextStyle.shadow` 的 offset / blur 都是像素，这里统一把 dp 换算成 px。 */
@Composable
private fun rememberTextShadow(color: Color, dx: Dp, dy: Dp, radius: Dp): Shadow {
    val density = LocalDensity.current
    return remember(color, dx, dy, radius, density) {
        with(density) {
            Shadow(
                color = color,
                offset = Offset(dx.toPx(), dy.toPx()),
                blurRadius = radius.toPx(),
            )
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (true) {
        if (current is Activity) return current
        current = (current as? ContextWrapper)?.baseContext ?: return null
    }
}

/** 删除确认框文案（建议值，未在原版资源里找到对应条目 → 待确认 #8）。 */
private const val DELETE_TITLE = "删除记录"
private const val DELETE_CONFIRM = "删除"
private const val DELETE_CANCEL = "取消"

private fun deleteMessage(vodName: String): String = "确定删除《$vodName》的观看记录？"

// ---------------------------------------------------------------------------
// 尺寸 / 颜色 / 时长常量（全部标注 ref_docs 出处）
// ---------------------------------------------------------------------------

/** `pageSite` 高 26dp（XML L44）。 */
private val PAGE_SITE_HEIGHT = 26.dp

/** `pageSite` 外边距 24dp（XML L45 `layout_margin`）。 */
private val PAGE_SITE_MARGIN = 24.dp

/** `pageSite` 左右内边距 12dp（XML L40-41 / L47）。 */
private val PAGE_SITE_PADDING_H = 12.dp

/** `pageSite` 字号 13sp（XML L33）。 */
private const val PAGE_SITE_SP = 13f

/** `shape_config_name.xml` 圆角 4dp。 */
private val PAGE_SITE_SHAPE = RoundedCornerShape(4.dp)

/** `posterCard` 150×210（XML L55-56）。 */
private val POSTER_CARD_WIDTH = 150.dp
private val POSTER_CARD_HEIGHT = 210.dp

/** `posterCard` marginTop 72dp / marginEnd 80dp（XML L57-58）。 */
private val POSTER_CARD_TOP = 72.dp
private val POSTER_CARD_END = 80.dp

/** `posterCard` 内边距 4dp（XML L52）。 */
private val POSTER_CARD_PADDING = 4.dp

/** `shape_poster_card.xml`：`black_20` + 12dp 圆角。 */
private val POSTER_CARD_SHAPE = RoundedCornerShape(12.dp)
private val POSTER_CARD_BACKGROUND = Color(0x33000000)

/** 信息组：marginBottom 8dp / 左右 24dp（XML L87-89）。 */
private val INFO_BOTTOM = 8.dp
private val INFO_PADDING_H = 24.dp

/** `heroNameText` 32sp / 高 36dp（XML L91-98）。 */
private const val HERO_NAME_SP = 32f
private val HERO_NAME_HEIGHT = 36.dp

/** 跑马灯阈值 = 屏宽 × 0.6（XML L96-102 + `nm3.i`）。 */
private const val HERO_NAME_WIDTH_RATIO = 0.6f

/** `heroLogoImg` 高 72dp / maxWidth 720dp（XML L107-110）。 */
private val LOGO_HEIGHT = 72.dp
private val LOGO_MAX_WIDTH = 720.dp

/** `heroMetaText` 14sp `#ccffffff` / 高 20dp / marginTop 2dp（XML L112-119）。 */
private const val HERO_META_SP = 14f
private val HERO_META_COLOR = Color(0xCCFFFFFF)
private val HERO_META_HEIGHT = 20.dp
private val HERO_META_TOP = 2.dp

/** `heroDescText` 12sp `#bbffffff` / 高 40dp / maxLines 2（XML L130-144）。 */
private const val HERO_DESC_SP = 12f
private val HERO_DESC_COLOR = Color(0xBBFFFFFF)
private val HERO_DESC_HEIGHT = 40.dp
private val DESC_TOP = 4.dp

/**
 * `lineSpacingExtra="3dp"` 的 Compose 近似：12sp 默认行高约 15sp，+3dp ≈ 18sp。
 * 该行在本工程恒为空（`gd3.s` 无数据源），实际不会绘制 → 待确认 #7。
 */
private val HERO_DESC_LINE_HEIGHT = 18.sp

/** `resumeRow` marginBottom 22dp（XML L155）。 */
private val RESUME_BOTTOM = 22.dp

/** `btnResume` 高 32dp / 14sp（XML L159 / L170）。 */
private val RESUME_HEIGHT = 32.dp
private const val RESUME_SP = 14f

/** `ic_arrow_down_circle` 14dp + `drawablePadding` 4dp（XML L172-173）。 */
private val RESUME_ICON = 14.dp
private val RESUME_ICON_GAP = 4.dp

/** 按钮左右内边距：`CustomTypeView` 自带 padding 未收录 → 占位 16dp（待确认 #3）。 */
private val RESUME_PADDING_H = 16.dp

/** `shape_item_round_*` 未收录 → 4dp 占位（待确认 #3）。 */
private val RESUME_SHAPE = RoundedCornerShape(4.dp)

/** 常态 `white_10` / 焦点 `hero_accent`（工程既有 token，同 HeroDetailActionButton）。 */
private val RESUME_BACKGROUND_IDLE = Color(0x1AFFFFFF)
private val RESUME_BACKGROUND_FOCUSED = Color(0xFFFF0000)

/** `progressGroup` 宽 200dp / marginStart 20dp（XML L180-182）。 */
private val PROGRESS_GROUP_WIDTH = 200.dp
private val PROGRESS_GROUP_START = 20.dp

/** `progressEpisode` / `progressTotal` 12sp `#ccffffff`，间隔 12dp（XML L188-212）。 */
private const val PROGRESS_TEXT_SP = 12f
private val PROGRESS_TEXT_COLOR = Color(0xCCFFFFFF)
private val PROGRESS_TOTAL_START = 12.dp

/** `progressBar` 高 2dp / marginTop 2dp（XML L216-218）。 */
private val PROGRESS_BAR_HEIGHT = 2.dp
private val PROGRESS_BAR_TOP = 2.dp

/** **待确认 #1**：`dl3.a()` / `dl3.l()` 占位（工程既有 `hero_accent`）。 */
private val PROGRESS_GRADIENT_START = Color(0xFFFF0000)
private val PROGRESS_GRADIENT_END = Color(0xFFFF0000)

/** 海报行：高 215dp / 左右 24dp / 间距 16dp（XML L225-237 + `o11.H()` L171）。 */
private val POSTER_ROW_HEIGHT = 215.dp
private val POSTER_ROW_PADDING_H = 24.dp
private val POSTER_ROW_ITEM_GAP = 16.dp

/** 焦点项缩放 1.08（`o11.H()` L174 `mm3.f(recyclerView, 1.08f)`）。 */
private const val FOCUS_SCALE = 1.08f

/** 背景淡入：scale 1.03→1.0、alpha 0→1、450ms、延迟 220ms（`HeroImageView.java` L114-175）。 */
private const val BACKDROP_START_SCALE = 1.03f
private const val BACKDROP_FADE_MS = 450
private const val BACKDROP_START_DELAY_MS = 220L

/** 内容淡入 100ms（`posterContent` 从 gone → visible）。 */
private const val CONTENT_FADE_MS = 100
