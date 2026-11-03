package com.fongmi.android.tv.ui.hero

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
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
import com.fongmi.android.tv.utils.HistoryProgressFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
fun HistoryScreen(
    viewModel: HistoryViewModel,
    modifier: Modifier = Modifier,
    /**
     * Mock 开关：`true` 时允许在真实历史为空的情况下注入 [MockHistory]。
     *
     * 默认 `false` → 永远走真实数据。真实历史非空时即使为 `true` 也仍显示真实数据。
     * 由 `HeroHistoryActivity` 从 Intent extra 读入（缺省取 `BuildConfig.DEBUG`）。
     */
    forceMock: Boolean = false,
) {
    val state = viewModel.state
    val context = LocalContext.current

    // 编辑模式：长按确定键进入，卡片中心出现垃圾桶遮罩；再按一次确定键才真正删除。
    //
    // 原版是**适配器级**标志（`o11.m0()` L383-395：`this.g0 = z` + `this.f0.i = z` +
    // `notifyItemRangeChanged(0, size)`），所以编辑态下所有卡片一起显示垃圾桶，
    // 而不是只有被长按的那一张。删除目标 = 编辑态下按下确定键的那一张卡（`pp` case 14）。
    var isEditMode by remember { mutableStateOf(false) }
    // 需要把 D-pad 焦点抢到第几张卡；-1 = 不需要。
    var pendingFocus by remember { mutableIntStateOf(-1) }
    // 删除后要求重新落焦点（删掉的是当前聚焦卡，View 随列表项一起销毁）。
    var refocusAfterDelete by remember { mutableStateOf(false) }

    val resumeFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val contentAlpha = remember { Animatable(0f) }

    // ---- 临时：UI 层假数据（FORCE_FAKE_RENDER）------------------------------
    // 假列表用 mutableStateListOf，长按删除才能真正从列表里消失。
    val fakeItems = remember { mutableStateListOf<History>().apply { addAll(fakeHistoryItems()) } }
    // 假数据模式下自己维护选中项：ViewModel 的列表还是空的，select() 拿不到东西。
    var fakeSelected by remember { mutableIntStateOf(0) }

    // 渲染用的状态：假数据模式把 items / hero / selectedIndex 整体换掉，
    // 下游 LazyRow、左侧信息区、进度区拿到的就是这一份，签名都不用改。
    val renderState = if (FORCE_FAKE_RENDER) {
        val safeIndex = if (fakeItems.isEmpty()) -1 else fakeSelected.coerceIn(0, fakeItems.size - 1)
        state.copy(
            items = fakeItems.toList(),
            selectedIndex = safeIndex,
            loading = false,
            loaded = true,
            empty = fakeItems.isEmpty(),
            hero = buildFakeHero(fakeItems.getOrNull(safeIndex)),
        )
    } else {
        state
    }

    // ---- 编辑模式三件事：进入 / 退出 / 确认删除 ------------------------------
    // 都是普通局部函数（非 @Composable），只在按键回调里被调用，不参与重组。

    fun enterEditMode() {
        if (isEditMode) return
        isEditMode = true
        Log.d(LOG_TAG_EDIT, "进入编辑模式，遮罩卡片数 = ${renderState.items.size}")
    }

    fun exitEditMode() {
        if (!isEditMode) return
        isEditMode = false
        Log.d(LOG_TAG_EDIT, "退出编辑模式（未删除）")
    }

    fun confirmDelete(index: Int) {
        val items = renderState.items
        if (index !in items.indices) {
            exitEditMode()
            return
        }
        Log.d(LOG_TAG_EDIT, "删除第 $index 条：${items[index].vodName}")
        if (FORCE_FAKE_RENDER) {
            // 假数据只在内存里，直接移除；选中项回退到删除位置（同 `m11` case 1 L39 的公式）。
            if (index in fakeItems.indices) fakeItems.removeAt(index)
            fakeSelected = if (fakeItems.isEmpty()) -1 else index.coerceAtMost(fakeItems.size - 1)
        } else {
            viewModel.deleteAt(index)
        }
        isEditMode = false
        refocusAfterDelete = true
    }
    // -----------------------------------------------------------------------

    // 排查用日志：确认 LazyRow 真的拿到 6 条。`adb logcat -s MockDebug`
    LaunchedEffect(renderState.items.size) {
        Log.d(LOG_TAG_FAKE, "当前渲染的数据量: ${renderState.items.size}")
    }
    // -----------------------------------------------------------------------

    // 首次装载（对应 `o11.a0()` + `m11` case 3）。
    LaunchedEffect(forceMock) { viewModel.load(forceMock) }

    // 数据到达：内容 alpha 0→1，100ms（对应 `posterContent` 从 gone 到可见的淡入）。
    LaunchedEffect(renderState.loaded) {
        if (renderState.loaded) contentAlpha.animateTo(1f, tween(CONTENT_FADE_MS))
    }

    // 进页默认焦点：第一张卡（对应 `m11` case 1/2 的 `recycler.post { focusPoster(0) }`）。
    LaunchedEffect(renderState.loaded) {
        if (renderState.loaded && renderState.items.isNotEmpty()) pendingFocus = 0
    }

    // 删除后把焦点还给「回退后的选中项」。
    LaunchedEffect(renderState.selectedIndex, renderState.items.size) {
        if (refocusAfterDelete && renderState.items.isNotEmpty()) {
            refocusAfterDelete = false
            pendingFocus = renderState.selectedIndex.coerceAtLeast(0)
        }
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black)
                // 返回键退出编辑模式。卡片自己的 OnKeyListener 也有 BACK 分支，
                // 这里兜底「焦点不在卡片上」（例如刚落到续播按钮）的情况。
                .onPreviewKeyEvent { event ->
                    if (isEditMode && event.type == KeyEventType.KeyDown && event.key == Key.Back) {
                        exitEditMode()
                        true
                    } else {
                        false
                    }
                },
        ) {

            // ---------------------------------------------------------------
            // 1) 背景层（自下而上）
            // ---------------------------------------------------------------
            HeroBackdrop(url = renderState.hero.backdropUrl)

            // maskLeft / maskBottom 在 XML 里初始 gone，`o11.H()` L163-164 建视图时置为 visible。
            if (renderState.loaded) {
                HeroMaskLeft()
                HeroMaskBottom()
            }

            // ---------------------------------------------------------------
            // 2) 左上角 pageSite（XML L32-47）：13sp bold 白 / 26dp / 12dp 内边距 / 24dp 外边距
            // ---------------------------------------------------------------
            if (renderState.loaded && renderState.hero.pageSite.isNotEmpty()) {
                PageSite(text = renderState.hero.pageSite, modifier = Modifier.align(Alignment.TopStart))
            }

            // ---------------------------------------------------------------
            // 3) 右上角 posterCard（XML L48-66）：150×210 / 12dp 圆角 / 4dp 内边距 / 72dp 顶 / 80dp 右
            //    仅在背景大图为空时显示（`o11.q0` L527-535）。
            // ---------------------------------------------------------------
            if (renderState.loaded && renderState.hero.backdropUrl.isEmpty() && renderState.hero.posterUrl.isNotEmpty()) {
                PosterFallback(
                    url = renderState.hero.posterUrl,
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            }

            // ---------------------------------------------------------------
            // 4) 加载 / 空态（ProgressLayout 语义：`view_progress` / `view_empty`）
            // ---------------------------------------------------------------
            if (!renderState.loaded) {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else if (renderState.items.isEmpty()) {
                HistoryEmptyState(modifier = Modifier.align(Alignment.Center))
            }

            // ---------------------------------------------------------------
            // 5) 底部内容区（XML L72-239 `posterContent`，alignParentBottom）
            // ---------------------------------------------------------------
            if (renderState.loaded && renderState.items.isNotEmpty()) {
                PosterContent(
                    state = renderState,
                    contentAlpha = contentAlpha.value,
                    listState = listState,
                    resumeFocus = resumeFocus,
                    pendingFocus = pendingFocus,
                    onFocusConsumed = { pendingFocus = -1 },
                    onCardFocused = { index ->
                        if (FORCE_FAKE_RENDER) {
                            // 假数据模式：选中项在 UI 层自己维护，不走 ViewModel。
                            fakeSelected = index
                        } else if (state.selectedIndex != index) {
                            viewModel.select(index)
                        }
                        // 焦点滚动对齐到起点（`o11.H()` L173 `setFocusScrollStrategy(0)`）。
                        scope.launch { listState.scrollToItem(index) }
                    },
                    // `findActivity()` 可能返回 null（TV 上 Context 不保证是 Activity），
                    // 取不到就静默不响应，不用 `!!` 强解包。
                    onCardClick = { item ->
                        context.findActivity()?.let { viewModel.openResume(it, item) }
                    },
                    isEditMode = isEditMode,
                    onEnterEditMode = { enterEditMode() },
                    onConfirmDelete = { index -> confirmDelete(index) },
                    onExitEditMode = { exitEditMode() },
                    onCardUp = {
                        runCatching { resumeFocus.requestFocus() }
                    },
                    // 「查看详情」必须显式传当前项：`openDetail(activity)` 的默认参数取的是
                    // ViewModel 的 `state.selectedItem`，假数据模式下那是空的 → `open()` 直接 return。
                    onResumeClick = {
                        val currentItem = renderState.selectedItem
                        val activity = context.findActivity()
                        Log.e(LOG_TAG_DETAIL, "查看详情被点击 activity=$activity currentItem=${currentItem?.vodName}")
                        if (activity == null) {
                            Log.e(LOG_TAG_DETAIL, "findActivity() 返回 null，无法打开详情页")
                        } else if (currentItem == null) {
                            Log.e(LOG_TAG_DETAIL, "currentItem 为 null，无法打开详情页")
                        } else {
                            viewModel.openDetail(activity, currentItem)
                        }
                    },
                    onResumeDown = { pendingFocus = renderState.selectedIndex.coerceAtLeast(0) },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }

            // 删除确认不再走弹窗：长按进入编辑模式 → 卡片中心垃圾桶 → 再按确定键直接删除
            // （原版 `pp` case 14 L361-372：`history.delete()` + 从列表移除，全程没有 Dialog）。
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
    isEditMode: Boolean,
    onFocusConsumed: () -> Unit,
    onCardFocused: (Int) -> Unit,
    onCardClick: (History) -> Unit,
    onEnterEditMode: () -> Unit,
    onConfirmDelete: (Int) -> Unit,
    onExitEditMode: () -> Unit,
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
            isEditMode = isEditMode,
            onFocusConsumed = onFocusConsumed,
            onCardFocused = onCardFocused,
            onCardClick = onCardClick,
            onEnterEditMode = onEnterEditMode,
            onConfirmDelete = onConfirmDelete,
            onExitEditMode = onExitEditMode,
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
    isEditMode: Boolean,
    onFocusConsumed: () -> Unit,
    onCardFocused: (Int) -> Unit,
    onCardClick: (History) -> Unit,
    onEnterEditMode: () -> Unit,
    onConfirmDelete: (Int) -> Unit,
    onExitEditMode: () -> Unit,
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
                        isEditMode = isEditMode,
                        onFocusConsumed = onFocusConsumed,
                        onFocused = onCardFocused,
                        onClick = { onCardClick(item) },
                        onEnterEditMode = onEnterEditMode,
                        onConfirmDelete = { onConfirmDelete(index) },
                        onExitEditMode = onExitEditMode,
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
 * - `delete` 遮罩 → [HeroPosterCard.isEditMode]（`s11` L130-131 + `adapter_history_poster.xml` L64-76）。
 *
 * **长按必须手写按键**：`HeroPosterCard` 是 View（`FrameLayout`），没有 Compose 的 `Modifier`，
 * 所以这里用 `setOnKeyListener` 手动接 `DPAD_CENTER` 的 ACTION_DOWN / ACTION_UP + 500ms 计时，
 * 不用 `combinedClickable`，也不用 `setOnLongClickListener`（遥控器长按不走 View 的 long-click 计时）。
 */
@Composable
private fun HistoryCard(
    item: History,
    index: Int,
    pendingFocus: Int,
    isEditMode: Boolean,
    onFocusConsumed: () -> Unit,
    onFocused: (Int) -> Unit,
    onClick: () -> Unit,
    onEnterEditMode: () -> Unit,
    onConfirmDelete: () -> Unit,
    onExitEditMode: () -> Unit,
    onUp: () -> Unit,
) {
    val cardRef = remember { mutableStateOf<HeroPosterCard?>(null) }
    val keyScope = rememberCoroutineScope()
    // 长按计时器：DOWN 起 500ms Job，UP 取消。`fired` 记录本轮是否已经进了编辑模式，
    // 这样「长按松手」不会被同一轮的 UP 误判成「编辑态单击删除」。
    val longPress = remember { LongPressState() }

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

            // 编辑态遮罩：原版是适配器级标志，编辑态下所有卡片一起显示。
            if (card.isEditMode != isEditMode) card.isEditMode = isEditMode
            // 触屏点遮罩 = 删除；遥控器确定键走下面的 OnKeyListener。
            card.onDeleteClick = onConfirmDelete

            card.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) onFocused(index)
            }
            card.setOnClickListener {
                if (isEditMode) onConfirmDelete() else onClick()
            }
            card.setOnKeyListener { view, keyCode, event ->
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER -> handleCenterKey(
                        view = view,
                        event = event,
                        isEditMode = isEditMode,
                        longPress = longPress,
                        scope = keyScope,
                        onEnterEditMode = onEnterEditMode,
                        onConfirmDelete = onConfirmDelete,
                        onClick = onClick,
                    )

                    // ↑ → btnResume（XML `btnResume.nextFocusDown = posterRecycler` 的反向）。
                    // 编辑态不跳走：焦点离开卡片就按不到删除了。
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            if (!isEditMode) onUp()
                            true
                        } else {
                            false
                        }
                    }

                    // 返回键退出编辑模式（焦点在卡片上时优先走这里）。
                    KeyEvent.KEYCODE_BACK -> {
                        if (isEditMode && event.action == KeyEvent.ACTION_DOWN) {
                            onExitEditMode()
                            true
                        } else {
                            false
                        }
                    }

                    else -> false
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

/**
 * 确定键（遥控器 OK）的 DOWN / UP 分流，复刻原版「长按进编辑模式 → 再按一次才删除」。
 *
 * - **DOWN**：普通态起 500ms 长按计时（`repeatCount > 0` 不重置）；编辑态不再计时。
 * - **UP**：取消计时。本轮已经触发长按 → 什么都不做（松手不删除）；
 *   编辑态短按 → [onConfirmDelete]；普通态短按 → [onClick]。
 *
 * 返回 `true` 表示消费事件，避免 View 再把 DPAD_CENTER 当成普通 click（否则会同时触发续播）。
 */
private fun handleCenterKey(
    view: View,
    event: KeyEvent,
    isEditMode: Boolean,
    longPress: LongPressState,
    scope: CoroutineScope,
    onEnterEditMode: () -> Unit,
    onConfirmDelete: () -> Unit,
    onClick: () -> Unit,
): Boolean = when (event.action) {
    KeyEvent.ACTION_DOWN -> {
        if (event.repeatCount == 0 && !isEditMode) {
            longPress.job?.cancel()
            longPress.fired = false
            longPress.job = scope.launch {
                delay(LONG_PRESS_EDIT_MS)
                longPress.fired = true
                onEnterEditMode()
                // 遮罩出现后焦点可能被系统抢走，拉回当前卡片。
                view.post { view.requestFocus() }
            }
        }
        true
    }

    KeyEvent.ACTION_UP -> {
        longPress.job?.cancel()
        longPress.job = null
        val fired = longPress.fired
        longPress.fired = false
        when {
            // 长按刚进编辑模式：松手不删除，等下一次确定键。
            fired -> Unit
            isEditMode -> onConfirmDelete()
            else -> onClick()
        }
        true
    }

    else -> false
}

/** 长按计时的可变状态：必须 `remember` 住，否则重组会丢掉正在跑的 [Job]。 */
private class LongPressState {
    var job: Job? = null
    var fired: Boolean = false
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

/** 「查看详情」排查日志：`adb logcat -s HistoryDebug`。 */
private const val LOG_TAG_DETAIL = "HistoryDebug"

/** 编辑模式日志：`adb logcat -s HistoryEdit`。 */
private const val LOG_TAG_EDIT = "HistoryEdit"

/**
 * 长按进编辑模式的判定时长：500ms。
 *
 * 遥控器上不能用 `ViewConfiguration.getLongPressTimeout()`（那是触屏语义，D-pad 的 DOWN/UP
 * 不经过 `View` 的 long-click 计时），所以这里显式写死，DOWN 起计时、UP 取消。
 */
private const val LONG_PRESS_EDIT_MS = 500L

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

// ---------------------------------------------------------------------------
// 临时：UI 层暴力假数据渲染（排查卡片 / 焦点联动用，正式逻辑不依赖它）
// ---------------------------------------------------------------------------

/**
 * `true` = 完全绕过 ViewModel，直接用本地假列表渲染。
 *
 * **不能用 `BuildConfig.DEBUG` 当这个开关** —— release 包里 `DEBUG == false`，
 * 一旦发版就又会退回空态，等于没改。这里用常量，任何 variant 都生效。
 *
 * 排查完把这里改成 `false` 即可，其余代码不用动。
 */
private const val FORCE_FAKE_RENDER = true

private const val LOG_TAG_FAKE = "MockDebug"

/**
 * 6 条假数据：不设图片 URL（避免 TV 上等网络卡死）。
 *
 * `key` 必须写：`History.getKey()` 标了 `@NonNull`，而 `LazyRow` 的 `itemsIndexed(key = ...)`
 * 和 `History.getSiteKey()` 都直接读它，为 null 会当场崩。
 */
private fun fakeHistoryItems(): List<History> = List(6) { i ->
    History().apply {
        key = "fake@@@${i + 1}"
        vodName = "测试片名_${i + 1}"
        vodRemarks = "更新至第${i + 1}集"
        vodPic = ""
        wallPic = ""
        vodFlag = ""
        episodeUrl = ""
        // 奇偶交替：奇数条带进度，用来验证进度条与「集数」文案；偶数条无进度。
        position = if (i % 2 == 0) 0L else (i + 1) * 60_000L
        duration = if (i % 2 == 0) 0L else 2_700_000L
    }
}

/**
 * 从假 [History] 直接算左侧 Hero 信息。
 *
 * 等价于 `HistoryViewModel.buildHero`，但完全在 UI 层算，**不碰 ViewModel**
 * （ViewModel 的列表还是空的，`select()` 拿不到数据，所以这里必须自己算，
 * 否则焦点移动时左侧信息、进度条会一直是空的）。
 */
private fun buildFakeHero(item: History?): HeroInfo {
    if (item == null) return HeroInfo()
    val name = item.vodName.orEmpty()
    val remarks = item.vodRemarks.orEmpty()
    val showProgress = item.position > 0 && item.duration > 0
    return HeroInfo(
        name = name,
        meta = HistoryMetaText.build(item),
        desc = "",
        pageSite = remarks,
        episode = if (showProgress) HistoryEpisodeLabel.resolve(remarks, name) else "",
        progressText = if (showProgress) {
            "${HistoryProgressFormatter.format(item.position, item.duration)} / " +
                HistoryProgressFormatter.format(item.duration, item.duration)
        } else {
            ""
        },
        progress = if (showProgress) {
            (item.position.toFloat() / item.duration.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        },
        showProgress = showProgress,
        backdropUrl = "",
        posterUrl = "",
        logoUrl = "",
    )
}
