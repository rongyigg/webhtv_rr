package com.fongmi.android.tv.ui.activity

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil3.compose.AsyncImage
import com.fongmi.android.tv.api.SiteApi
import com.fongmi.android.tv.ui.hero.HeroImageLoader
import com.fongmi.android.tv.utils.Notify
import com.fongmi.android.tv.utils.PushParser
import kotlinx.coroutines.launch

/**
 * HeroUI（Compose）全屏沉浸式首页。
 *
 * 分层（自下而上）：
 * 1. [HeroBackdrop]：全屏背景大图，随海报焦点用 [Crossfade] 平滑切换。
 * 2. [HeroScrim]：自上而下的黑色渐变遮罩 + 左侧压暗，保证文字可读。
 * 3. [Column]：顶栏 → 左侧剧名信息 → 胶囊分类 Tab → 海报网格。
 *
 * 参考来源：
 * - 整体分层与视觉：`ref_docs/home_ui.png`（背景人像大图 + 上方胶囊分类条 + 底部单排海报墙 + 左侧标题区）。
 * - 卡片缩放、2:3 比例、焦点描边：`ref_docs/VodRectPresenter.java`（见各处行号注释）。
 * - 类/布局索引：`ref_docs/UI.md` §3.2（`os3` = VodRectPresenter，布局 `adapter_vod_rect` + `overlay_focus`）。
 *
 * 焦点契约（三区各自持有 FocusRequester，全部显式交接，不依赖 2D 自动搜索）：
 * TopBar ↓→ Tab，Tab ↑→ TopBar，Tab ↓→ Grid，Grid 首行 ↑→ Tab。
 */
class HeroHomeActivity : ComponentActivity() {

    /** 上一次按返回键的单调时间戳（毫秒）；`null` 表示当前不在“等待第二次确认”的窗口内。 */
    private var lastBackPressedAt: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(AndroidColor.BLACK))
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        installDoubleBackToExit()
        HeroImageLoader.install()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                HeroHomeScreen(onLaunchPlayback = { vodName -> launchPlayback(this, vodName) })
            }
        }
    }

    /**
     * TV 端“双击返回键退出”：
     * 第一次按返回只弹提示并吞掉事件，[EXIT_BACK_INTERVAL_MS] 内再按一次才真正 [finish]。
     * 超过窗口再按，则重新计时并再次提示。
     *
     * 用 [SystemClock.elapsedRealtime] 而不是 `System.currentTimeMillis`：单调时钟不受系统时间跳变影响。
     * 回调挂到 Activity 的 dispatcher 上，生命周期自动跟随，无需在 onDestroy 里手动移除；
     * 占位 Dialog 的返回键由 Dialog 窗口自己消费，不会走到这里。
     */
    private fun installDoubleBackToExit() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val now = SystemClock.elapsedRealtime()
                val last = lastBackPressedAt
                if (last != null && now - last <= EXIT_BACK_INTERVAL_MS) {
                    lastBackPressedAt = null
                    finish()
                    return
                }
                lastBackPressedAt = now
                Notify.show(EXIT_BACK_HINT)
            }
        })
    }

    /**
     * 播放桥接：复用项目现有的 [VideoActivity] + [PushParser] 入口。
     * Intent 与 Bundle 全部由 VideoActivity 内部封装，这里不拼任何 EXTRA key。
     */
    fun launchPlayback(context: Context, vodName: String) {
        val activity = context.findActivity() ?: return
        val push = PushParser.of(DEMO_STREAM_URL, vodName)
        VideoActivity.startDirect(activity, SiteApi.PUSH, push.getId(), push.getName(), null, null)
    }

    private fun Context.findActivity(): Activity? {
        var current: Context = this
        while (true) {
            if (current is Activity) return current
            current = (current as? ContextWrapper)?.baseContext ?: return null
        }
    }

    private companion object {

        // 公开测试流，仅用于确认“点击 → 原有 PlaybackActivity”这条链路；可随时替换为任意可访问直链。
        private const val DEMO_STREAM_URL = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"

        // 双击返回键的确认窗口：2 秒内再按才退出。
        private const val EXIT_BACK_INTERVAL_MS = 2000L
        private const val EXIT_BACK_HINT = "再按一次返回键退出应用"
    }
}

@Composable
private fun HeroHomeScreen(onLaunchPlayback: (String) -> Unit) {
    val gridState = rememberLazyGridState()
    val tabState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // 三个焦点区各自持有一组 FocusRequester：跨区移动用显式 requestFocus，
    // 避免 2D 焦点搜索在“顶栏列宽 ≠ 网格列宽”时落空。
    val topBarRequesters = remember { List(TOP_BAR_LABELS.size) { FocusRequester() } }
    val tabRequesters = remember { List(HERO_TABS.size) { FocusRequester() } }
    val posterRequesters = remember { List(HERO_POSTERS.size) { FocusRequester() } }

    var lastTopBarIndex by remember { mutableIntStateOf(0) }
    var lastTabIndex by remember { mutableIntStateOf(0) }
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var focusedPosterIndex by remember { mutableIntStateOf(0) }
    var showHomeDialog by remember { mutableStateOf(false) }

    // 换分类时用同一批假数据做轮转，视觉上列表确实换了，但不需要第二套资源。
    val posters = remember(selectedTabIndex) { postersForTab(selectedTabIndex) }
    val heroPoster = posters.getOrElse(focusedPosterIndex) { posters[0] }

    // 网格 → 顶栏：回到用户离开时的那一项，失败再退回“小房子”。
    fun focusTopBar() {
        if (!requestFocus(topBarRequesters, lastTopBarIndex)) requestFocus(topBarRequesters, 0)
    }

    // 任意区 → Tab：切换选中分类、把分类条滚到可见、再请求焦点。
    fun focusTab(index: Int) {
        val target = index.coerceIn(0, HERO_TABS.lastIndex)
        lastTabIndex = target
        selectedTabIndex = target
        scope.launch { tabState.animateScrollToItem(target) }
        if (!requestFocus(tabRequesters, target)) requestFocus(tabRequesters, 0)
    }

    // Tab → 网格：分类已把网格滚回顶部，所以只需按“与 Tab 相近的视觉列”落在第一行。
    fun focusGrid(column: Int) {
        val target = column.coerceIn(0, POSTER_COLUMNS - 1).coerceAtMost(posters.lastIndex)
        if (requestFocus(posterRequesters, target)) return
        // 刚切分类时目标卡片可能还没组合：滚回顶部，等一帧后再请求一次，失败就退回 Tab。
        scope.launch {
            gridState.scrollToItem(0)
            withFrameNanos { }
            if (!requestFocus(posterRequesters, target)) focusTab(lastTabIndex)
        }
    }

    // 网格内上下跨行：无论当前停在第几列，都强制落到目标行的第一张卡片（最左侧）。
    // 系统默认的 2D 焦点搜索会记住“上次的列号”，参考图那种整排卡片的观感下会跳到错误位置。
    fun focusPosterRow(fromIndex: Int, delta: Int) {
        val targetRow = fromIndex / POSTER_COLUMNS + delta
        if (targetRow < 0) {
            // 已经在首行还按“上”：交还给分类 Tab。
            focusTab(lastTabIndex)
            return
        }
        val target = targetRow * POSTER_COLUMNS
        // 已经最后一行：向下不再移动，事件已被消费，焦点保持原地。
        if (target > posters.lastIndex) return
        if (requestFocus(posterRequesters, target)) return
        // 目标行还在视口之外（尚未组合）：先滚过去，等一帧再请求焦点。
        scope.launch {
            gridState.scrollToItem(target)
            withFrameNanos { }
            requestFocus(posterRequesters, target)
        }
    }

    // 启动时、以及占位 Dialog 关闭后，焦点都落回“小房子”。
    LaunchedEffect(showHomeDialog) {
        if (showHomeDialog) return@LaunchedEffect
        requestFocus(topBarRequesters, 0)
    }

    // 切分类后网格回到顶部，保证“Tab ↓ 落到第一行”和视觉一致。
    LaunchedEffect(selectedTabIndex) {
        gridState.scrollToItem(0)
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // ---- 底层：全屏背景大图 + 渐变遮罩 ----
        HeroBackdrop(url = heroPoster.backdropUrl)
        HeroScrim()

        // ---- 顶层：顶栏 / 剧名信息 / 分类 Tab / 海报网格 ----
        Column(modifier = Modifier.fillMaxSize()) {
            HeroTopBar(
                requesters = topBarRequesters,
                onFocused = { lastTopBarIndex = it },
                onHomeConfirm = { showHomeDialog = true },
                onMoveDown = { index -> focusTab(tabIndexForTopBar(index)) },
            )
            HeroInfoArea(
                poster = heroPoster,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(HERO_INFO_WEIGHT),
            )
            HeroTabBar(
                state = tabState,
                requesters = tabRequesters,
                selectedIndex = selectedTabIndex,
                onFocused = { index ->
                    lastTabIndex = index
                    selectedTabIndex = index
                },
                onConfirm = { index -> selectedTabIndex = index },
                onMoveUp = { focusTopBar() },
                onMoveDown = { index -> focusGrid(columnForTab(index)) },
                onMoveLeft = { index -> focusTab(index - 1) },
                onMoveRight = { index -> focusTab(index + 1) },
            )
            PosterGrid(
                posters = posters,
                state = gridState,
                requesters = posterRequesters,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(HERO_GRID_WEIGHT),
                onFocused = { index -> focusedPosterIndex = index },
                onClick = onLaunchPlayback,
                onMoveUp = { index -> focusPosterRow(index, -1) },
                onMoveDown = { index -> focusPosterRow(index, 1) },
            )
        }
    }

    if (showHomeDialog) HeroPlaceholderDialog(onDismiss = { showHomeDialog = false })
}

/**
 * 底层背景大图。
 *
 * 用 [Crossfade] 让“海报焦点变化 → 背景切换”是交叉淡入淡出，而不是硬切；
 * 420ms 是电视遥控器连续按键时的可感知下限，再快会显得闪烁。
 */
@Composable
private fun HeroBackdrop(url: String) {
    Crossfade(
        targetState = url,
        animationSpec = tween(durationMillis = HERO_BACKDROP_FADE_MS),
        label = "heroBackdrop",
        modifier = Modifier.fillMaxSize(),
    ) { backdropUrl ->
        AsyncImage(
            model = backdropUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * 渐变遮罩。
 *
 * 竖向：顶部压暗（盖住状态栏区的背景）→ 中段透出背景 → 底部接近纯黑（托住海报网格）。
 * 横向：左侧再压一层，保证剧名/简介在浅色背景上仍然可读。对照 `ref_docs/home_ui.png` 的暗角。
 */
@Composable
private fun HeroScrim() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0.0f to Color.Black.copy(alpha = 0.86f),
                    0.26f to Color.Black.copy(alpha = 0.30f),
                    0.58f to Color.Black.copy(alpha = 0.62f),
                    1.0f to Color.Black.copy(alpha = 0.97f),
                ),
            ),
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.horizontalGradient(
                    0.0f to Color.Black.copy(alpha = 0.78f),
                    0.52f to Color.Black.copy(alpha = 0.16f),
                    1.0f to Color.Transparent,
                ),
            ),
    )
}

/**
 * 顶部控制栏。
 *
 * 焦点态按产品规范实现：文字纯白 22sp + 下方红色短横线；未选中为纯白 60% 透明度。
 * 整体排布（房子在最左、各项等距、贴顶留白）对照 `ref_docs/home_ui.png`。
 * 现在顶栏下方是分类 Tab，所以这里按“下”不再直接进网格，而是先落到 Tab 区。
 */
@Composable
private fun HeroTopBar(
    requesters: List<FocusRequester>,
    onFocused: (Int) -> Unit,
    onHomeConfirm: () -> Unit,
    onMoveDown: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = HERO_SIDE_PADDING, end = HERO_SIDE_PADDING, top = TOP_BAR_TOP_PADDING),
        horizontalArrangement = Arrangement.spacedBy(TOP_BAR_ITEM_SPACING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TOP_BAR_LABELS.forEachIndexed { index, label ->
            HeroTopBarItem(
                label = label,
                isHome = index == 0,
                requester = requesters[index],
                onFocused = { onFocused(index) },
                // 目前只有“小房子”有动作（占位 Dialog）；其余项留待后续接入对应页面。
                onConfirm = { if (index == 0) onHomeConfirm() },
                onMoveDown = { onMoveDown(index) },
            )
        }
    }
}

@Composable
private fun HeroTopBarItem(
    label: String,
    isHome: Boolean,
    requester: FocusRequester,
    onFocused: () -> Unit,
    onConfirm: () -> Unit,
    onMoveDown: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val textColor by animateColorAsState(
        targetValue = if (focused) Color.White else Color.White.copy(alpha = TOP_BAR_IDLE_ALPHA),
        animationSpec = tween(durationMillis = 150),
        label = "topBarText",
    )
    val fontSize by animateFloatAsState(
        targetValue = if (focused) TOP_BAR_FOCUS_SP else TOP_BAR_IDLE_SP,
        animationSpec = tween(durationMillis = 150),
        label = "topBarSize",
    )
    val underlineAlpha by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMillis = 150),
        label = "topBarUnderline",
    )
    val interaction = remember { MutableInteractionSource() }

    Column(
        modifier = Modifier
            .focusRequester(requester)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .onPreviewKeyEvent { event ->
                // 顶栏任意一项按“下”都显式交给分类 Tab，保证边界处不会丢焦点。
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                    onMoveDown()
                    true
                } else {
                    false
                }
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onConfirm)
            .padding(horizontal = 2.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 固定 30dp 内容区，保证焦点态 20sp↔22sp 切换时下划线不会上下跳动。
        Box(modifier = Modifier.height(30.dp), contentAlignment = Alignment.Center) {
            if (isHome) {
                HouseGlyph(color = textColor, modifier = Modifier.size(22.dp))
            } else {
                Text(
                    text = label,
                    color = textColor,
                    fontSize = fontSize.sp,
                    fontWeight = if (focused) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        // 焦点态：红色短横线（长度固定 26dp，仅淡入淡出，避免文字宽度变化导致抖动）。
        Box(
            modifier = Modifier
                .width(26.dp)
                .height(3.dp)
                .alpha(underlineAlpha)
                .background(HERO_RED, RoundedCornerShape(2.dp)),
        )
    }
}

/**
 * 左侧剧名信息层。
 *
 * 内容随海报焦点变化，用 [Crossfade] 与背景图同步过渡（320ms，比背景略快，文字先落位）。
 * 直接叠在背景之上、不加任何卡片底色，保持 `ref_docs/home_ui.png` 的沉浸感。
 */
@Composable
private fun HeroInfoArea(poster: HeroPoster, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.padding(
            start = HERO_SIDE_PADDING,
            end = HERO_SIDE_PADDING,
            bottom = HERO_INFO_BOTTOM_PADDING,
        ),
        contentAlignment = Alignment.BottomStart,
    ) {
        Crossfade(
            targetState = poster,
            animationSpec = tween(durationMillis = HERO_INFO_FADE_MS),
            label = "heroInfo",
        ) { current ->
            Column(modifier = Modifier.fillMaxWidth(HERO_INFO_WIDTH_FRACTION)) {
                Text(
                    text = current.title,
                    color = Color.White,
                    fontSize = HERO_TITLE_SP.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = current.meta,
                    color = Color.White.copy(alpha = 0.76f),
                    fontSize = HERO_META_SP.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = current.synopsis,
                    color = Color.White.copy(alpha = 0.60f),
                    fontSize = HERO_SYNOPSIS_SP.sp,
                    lineHeight = HERO_SYNOPSIS_LINE_HEIGHT_SP.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 分类 Tab 栏（胶囊形状，横向滚动）。
 *
 * 选中项带 3dp 红色描边（`HERO_RED`），未选中为 1dp 半透明白描边 + 62% 白字；
 * 获得焦点的项背景会再压暗一层，确保在亮背景图上也能看出焦点位置。
 * 左右键不交给系统焦点搜索，而是显式 [focusTab]，这样横向滚动与选中态永远同步。
 */
@Composable
private fun HeroTabBar(
    state: LazyListState,
    requesters: List<FocusRequester>,
    selectedIndex: Int,
    onFocused: (Int) -> Unit,
    onConfirm: (Int) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: (Int) -> Unit,
    onMoveLeft: (Int) -> Unit,
    onMoveRight: (Int) -> Unit,
) {
    LazyRow(
        state = state,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            start = HERO_SIDE_PADDING,
            end = HERO_SIDE_PADDING,
            top = 8.dp,
            bottom = 8.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(HERO_TAB_SPACING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HERO_TABS.forEachIndexed { index, label ->
            // 用 item(key = ...) 而不是 itemsIndexed：后者与网格的 itemsIndexed 同名，
            // 两个扩展函数无法同时 import，这里直接用 LazyListScope 的成员函数。
            item(key = label) {
                HeroTabChip(
                    label = label,
                    selected = index == selectedIndex,
                    requester = requesters[index],
                    onFocused = { onFocused(index) },
                    onConfirm = { onConfirm(index) },
                    onMoveUp = onMoveUp,
                    onMoveDown = { onMoveDown(index) },
                    onMoveLeft = { onMoveLeft(index) },
                    onMoveRight = { onMoveRight(index) },
                )
            }
        }
    }
}

@Composable
private fun HeroTabChip(
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
    var focused by remember { mutableStateOf(false) }
    val active = selected || focused
    val borderWidth by animateDpAsState(
        targetValue = if (active) HERO_TAB_FOCUS_BORDER else 1.dp,
        animationSpec = tween(durationMillis = 150),
        label = "tabBorderWidth",
    )
    val borderColor by animateColorAsState(
        targetValue = if (active) HERO_RED else Color.White.copy(alpha = 0.16f),
        animationSpec = tween(durationMillis = 150),
        label = "tabBorderColor",
    )
    val textColor by animateColorAsState(
        targetValue = if (active) Color.White else Color.White.copy(alpha = 0.62f),
        animationSpec = tween(durationMillis = 150),
        label = "tabTextColor",
    )
    val chipBackground by animateColorAsState(
        targetValue = Color.Black.copy(alpha = if (focused) 0.78f else 0.42f),
        animationSpec = tween(durationMillis = 150),
        label = "tabBackground",
    )
    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .focusRequester(requester)
            .onFocusChanged {
                focused = it.isFocused
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
            .clickable(interactionSource = interaction, indication = null, onClick = onConfirm)
            .clip(CircleShape)
            .background(chipBackground)
            .border(width = borderWidth, color = borderColor, shape = CircleShape)
            .padding(horizontal = HERO_TAB_H_PADDING, vertical = HERO_TAB_V_PADDING),
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 15.sp,
            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

/**
 * 海报网格。
 *
 * 列数 6：对照 `ref_docs/home_ui.png` 一屏 6 张卡片；间距与左右留白按 1080p 电视观感取值。
 * 图片由 Coil 加载，OkHttp 客户端走 [HeroImageLoader] 里的多路 DoH（`HeroHttpDns`）。
 */
@Composable
private fun PosterGrid(
    posters: List<HeroPoster>,
    state: LazyGridState,
    requesters: List<FocusRequester>,
    modifier: Modifier = Modifier,
    onFocused: (Int) -> Unit,
    onClick: (String) -> Unit,
    onMoveUp: (Int) -> Unit,
    onMoveDown: (Int) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(POSTER_COLUMNS),
        state = state,
        modifier = modifier,
        contentPadding = PaddingValues(
            start = HERO_SIDE_PADDING,
            end = HERO_SIDE_PADDING,
            top = HERO_GRID_TOP_PADDING,
            bottom = HERO_GRID_BOTTOM_PADDING,
        ),
        horizontalArrangement = Arrangement.spacedBy(HERO_GRID_H_SPACING),
        verticalArrangement = Arrangement.spacedBy(HERO_GRID_V_SPACING),
    ) {
        itemsIndexed(items = posters, key = { _, poster -> poster.id }) { index, poster ->
            PosterCard(
                poster = poster,
                requester = requesters[index],
                onFocused = { onFocused(index) },
                onClick = { onClick(poster.title) },
                // 跨行时把“从哪一张发起”带回上层，由上层统一算目标行的第一列。
                onMoveUp = { onMoveUp(index) },
                onMoveDown = { onMoveDown(index) },
            )
        }
    }
}

/**
 * 单张海报卡片：图片区与文字区完全剥离。
 *
 * 参数全部对齐 `ref_docs/VodRectPresenter.java`：
 * - 焦点缩放：第 39-41 行 `float f = view.hasFocus() ? 1.08f : 1.0f`，用 setScaleX/Y 整体放大。
 *   本项目按产品要求取 **1.05**（原实现 1.08），差异点已在此注明。
 * - 宽高比：第 75 行 `rs3Var.l = (int) (iMin * 1.5f)`，即 高 = 宽 × 1.5 → `aspectRatio(2f / 3f)`。
 * - 描边：第 90-101 行用 `density * 1.0f` 的 overlay 宽度、并把 overlay 四边各外扩 1dp；
 *   Compose 里等价为贴边 `border(2.dp)`，省掉一层 overlay（视觉厚度一致）。
 * - 阴影：`ref_docs/UI.md` §3.2 的 `overlay_focus`/`selector_vod` 在放大时提供浮起感；
 *   Compose 用 `Modifier.shadow(elevation = 18.dp)` 表达同一层级关系，仅在焦点态出现。
 *
 * 与上一版的关键差异：`graphicsLayer` 只挂在图片 Box 上，文字 Box 是固定高度的独立容器，
 * 因此放大时文字既不会跟着变形，也不会因容器被压缩而截断。
 */
@Composable
private fun PosterCard(
    poster: HeroPoster,
    requester: FocusRequester,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) POSTER_FOCUS_SCALE else 1f,
        animationSpec = tween(durationMillis = 160),
        label = "posterScale",
    )
    val elevation by animateDpAsState(
        targetValue = if (focused) POSTER_FOCUS_ELEVATION else 0.dp,
        animationSpec = tween(durationMillis = 160),
        label = "posterElevation",
    )
    val borderWidth by animateDpAsState(
        targetValue = if (focused) POSTER_FOCUS_BORDER else 0.dp,
        animationSpec = tween(durationMillis = 160),
        label = "posterBorder",
    )
    val interaction = remember { MutableInteractionSource() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(requester)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // 上下都自己接管：跨行一律回到目标行第一列，不交给会“记住列号”的系统搜索。
                when (event.key) {
                    Key.DirectionUp -> {
                        onMoveUp()
                        true
                    }

                    Key.DirectionDown -> {
                        onMoveDown()
                        true
                    }

                    else -> false
                }
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 图片区：只有这一层参与缩放、描边与阴影。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .aspectRatio(POSTER_ASPECT_RATIO)
                .shadow(elevation = elevation, shape = POSTER_SHAPE, clip = false)
                .clip(POSTER_SHAPE)
                .background(POSTER_PLACEHOLDER)
                .border(width = borderWidth, color = Color.White, shape = POSTER_SHAPE),
        ) {
            AsyncImage(
                model = poster.url,
                contentDescription = poster.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // 文字区：固定高度 + 单行，图片放大时这里尺寸与字号都不变。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = POSTER_TITLE_GAP)
                .height(POSTER_TITLE_HEIGHT),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = poster.title,
                color = if (focused) Color.White else Color.White.copy(alpha = 0.72f),
                fontSize = POSTER_TITLE_SP.sp,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 空 Dialog 占位：仅用于验证“小房子 + 确定键”的交互链路，后续替换为真正的首页菜单。 */
@Composable
private fun HeroPlaceholderDialog(onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true),
    ) {
        Box(
            modifier = Modifier
                .width(420.dp)
                .height(240.dp)
                .background(Color(0xFF101010), DIALOG_SHAPE)
                .border(1.dp, Color(0xFF2F2F2F), DIALOG_SHAPE),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "占位对话框 · 按返回键关闭",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 14.sp,
            )
        }
    }
}

/** 顶栏最左的“小房子”图标。用 Path 手绘，避免为单个图标引入 material-icons-extended。 */
@Composable
private fun HouseGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val house = Path().apply {
            moveTo(w * 0.5f, 0f)
            lineTo(w, h * 0.46f)
            lineTo(w * 0.82f, h * 0.46f)
            lineTo(w * 0.82f, h)
            lineTo(w * 0.18f, h)
            lineTo(w * 0.18f, h * 0.46f)
            lineTo(0f, h * 0.46f)
            close()
        }
        drawPath(house, color = color)
    }
}

/** 在已挂载的 FocusRequester 列表里安全地请求焦点：未组合的 Lazy 项会抛异常，这里降级返回 false。 */
private fun requestFocus(requesters: List<FocusRequester>, index: Int): Boolean {
    val requester = requesters.getOrNull(index) ?: return false
    return runCatching { requester.requestFocus() }.isSuccess
}

/** 顶栏第 index 项正下方对应的 Tab 序号（顶栏 7 项 → 6 个分类，按比例落位）。 */
private fun tabIndexForTopBar(topBarIndex: Int): Int =
    (topBarIndex * HERO_TABS.size / TOP_BAR_LABELS.size).coerceIn(0, HERO_TABS.lastIndex)

/** 第 index 个 Tab 正下方对应的网格列号（6 个分类 → 6 列，按比例落位）。 */
private fun columnForTab(tabIndex: Int): Int =
    (tabIndex * POSTER_COLUMNS / HERO_TABS.size).coerceIn(0, POSTER_COLUMNS - 1)

/** 分类切换时轮转假数据：列表顺序变了，但资源只有一套，方便先验证交互。 */
private fun postersForTab(tabIndex: Int): List<HeroPoster> {
    val offset = (tabIndex * 2) % HERO_POSTERS.size
    return HERO_POSTERS.drop(offset) + HERO_POSTERS.take(offset)
}

private data class HeroPoster(
    val id: Int,
    val title: String,
    val meta: String,
    val synopsis: String,
    val url: String,
    val backdropUrl: String,
)

private data class HeroSeed(val title: String, val meta: String, val synopsis: String)

/** 假数据：picsum 按 seed 返回稳定图片，不依赖 TMDB API Key 即可验证 Coil 链路。 */
private val HERO_POSTERS: List<HeroPoster> = listOf(
    HeroSeed("后会无期", "2026 · 剧情 · 全 24 集", "三个男人一路向西，在公路尽头重新认识了自己。"),
    HeroSeed("尚公主", "2026 · 古装 · 全 36 集", "和亲路上她换了身份，也换了半个王朝的命运。"),
    HeroSeed("小城良方", "2026 · 都市 · 全 28 集", "一间老药铺，藏着三代人的处方与心事。"),
    HeroSeed("嫁金钗", "2025 · 古装 · 全 40 集", "一支金钗牵出旧案，也牵出她不肯低头的半生。"),
    HeroSeed("玉簟秋", "2025 · 剧情 · 全 32 集", "深宅秋凉，她把每一步都走成了棋。"),
    HeroSeed("醉梦", "2025 · 悬疑 · 全 20 集", "他记得所有梦境，却记不起梦里的那张脸。"),
    HeroSeed("生日快乐", "2026 · 家庭 · 全 16 集", "每年生日，她都会收到一封没有署名的信。"),
    HeroSeed("山河故人", "2025 · 历史 · 全 30 集", "山河依旧，故人散落在不同的年代里。"),
    HeroSeed("风起洛阳", "2026 · 古装 · 全 38 集", "洛阳城的春风里，藏着三段未说出口的告白。"),
    HeroSeed("南来北往", "2025 · 年代 · 全 26 集", "一列绿皮火车，装下了二十年的聚散。"),
    HeroSeed("雾中灯塔", "2026 · 悬疑 · 全 18 集", "灯塔每晚亮起，海面上就多一艘没有名字的船。"),
    HeroSeed("长安夜行", "2025 · 古装 · 全 34 集", "宵禁之后，长安属于另一群人。"),
).mapIndexed { index, seed ->
    HeroPoster(
        id = index,
        title = seed.title,
        meta = seed.meta,
        synopsis = seed.synopsis,
        url = "https://picsum.photos/seed/hero-poster-$index/400/600",
        backdropUrl = "https://picsum.photos/seed/hero-backdrop-$index/1280/720",
    )
}

// 顶栏顺序：小房子 | 直播 | 搜索 | 历史 | 收藏 | 推送 | 设置（对照 ref_docs/home_ui.png）。
private val TOP_BAR_LABELS = listOf("首页", "直播", "搜索", "历史", "收藏", "推送", "设置")

// 分类 Tab 顺序对照 ref_docs/home_ui.png 的胶囊分类条。
private val HERO_TABS = listOf("推荐", "电影", "电视剧", "动漫", "综艺", "纪录片")

private const val POSTER_COLUMNS = 6

// 参考 ref_docs/VodRectPresenter.java 第 39-41 行：原实现 hasFocus() ? 1.08f : 1.0f；本项目按要求取 1.05。
private const val POSTER_FOCUS_SCALE = 1.05f

// 参考 ref_docs/VodRectPresenter.java 第 75 行：height = width * 1.5f。
private val POSTER_ASPECT_RATIO = 2f / 3f

private const val TOP_BAR_FOCUS_SP = 22f
private const val TOP_BAR_IDLE_SP = 20f
private const val TOP_BAR_IDLE_ALPHA = 0.6f

// 信息层与网格的高度分配：1080p 电视（960×540dp）下，6 列 2:3 卡片 + 卡外单行标题约需 245dp，
// 信息层拿剩下的约 175dp，刚好放得下 52sp 剧名 + 18sp 元数据 + 两行 15sp 简介。
private const val HERO_INFO_WEIGHT = 0.68f
private const val HERO_GRID_WEIGHT = 1f
private const val HERO_INFO_WIDTH_FRACTION = 0.42f
private const val HERO_BACKDROP_FADE_MS = 420
private const val HERO_INFO_FADE_MS = 320

// Hero 文案层级（对照 ref_docs/home_ui.png：剧名极大、元数据中等、简介较小）。
private const val HERO_TITLE_SP = 52f
private const val HERO_META_SP = 18f
private const val HERO_SYNOPSIS_SP = 15f
private const val HERO_SYNOPSIS_LINE_HEIGHT_SP = 22f

// 四周留白整体加大：参考图的左右安全边距接近屏宽的 1/15，1080p 下约 64dp。
private val HERO_SIDE_PADDING = 64.dp
private val HERO_INFO_BOTTOM_PADDING = 16.dp

private val TOP_BAR_TOP_PADDING = 28.dp
private val TOP_BAR_ITEM_SPACING = 40.dp

private val HERO_TAB_SPACING = 14.dp
private val HERO_TAB_H_PADDING = 22.dp
private val HERO_TAB_V_PADDING = 9.dp

private val HERO_GRID_TOP_PADDING = 12.dp
private val HERO_GRID_BOTTOM_PADDING = 14.dp
private val HERO_GRID_H_SPACING = 20.dp
private val HERO_GRID_V_SPACING = 20.dp

private val POSTER_FOCUS_ELEVATION = 18.dp

// 参考 VodRectPresenter.java 第 90-101 行：overlay 线宽 density*1dp、四边各外扩 1dp，视觉厚度约 2dp。
private val POSTER_FOCUS_BORDER = 2.dp

// 海报标题：固定高度容器 + 单行，放大或切换都不会改变布局高度。
private val POSTER_TITLE_GAP = 8.dp
private val POSTER_TITLE_HEIGHT = 22.dp
private const val POSTER_TITLE_SP = 14f

// 胶囊 Tab 选中态描边加粗到 3dp，与 1dp 的未选中描边拉开对比。
private val HERO_TAB_FOCUS_BORDER = 3.dp

private val HERO_RED = Color(0xFFE50914)
private val POSTER_SHAPE = RoundedCornerShape(16.dp)
private val POSTER_PLACEHOLDER = Color(0xFF161616)
private val DIALOG_SHAPE = RoundedCornerShape(14.dp)
