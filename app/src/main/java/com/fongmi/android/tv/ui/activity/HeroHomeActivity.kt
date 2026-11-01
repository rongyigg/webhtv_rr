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
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.unit.Dp
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
 * 3. [Column]：顶栏 → 左侧剧名信息 → 胶囊分类 Tab → 单行海报墙（一屏 7 张半）。
 *
 * 参考来源：
 * - 整体分层与视觉：`ref_docs/home_ui.png`（背景人像大图 + 上方胶囊分类条 + 底部单排海报墙 + 左侧标题区）。
 * - 卡片缩放、2:3 比例、焦点描边：`ref_docs/VodRectPresenter.java`（见各处行号注释）。
 * - 类/布局索引：`ref_docs/UI.md` §3.2（`os3` = VodRectPresenter，布局 `adapter_vod_rect` + `overlay_focus`）。
 *
 * 焦点契约（三区各自持有 FocusRequester，全部显式交接，不依赖 2D 自动搜索）：
 * TopBar ↓→ Tab，Tab ↑→ TopBar，Tab ↓→ 海报行（前 6 张按横向位置落位），
 * 海报行 ←/→ 行内移动（第 5 张起整行左移一张），海报行 ↑→ Tab，↓ 留在原地（已在最底行）。
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
    val posterState = rememberLazyListState()
    val tabState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // 三个焦点区各自持有一组 FocusRequester：跨区移动用显式 requestFocus，
    // 避免 2D 焦点搜索在“顶栏项宽 ≠ 卡片宽”时落空。
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

    // 海报行 → 顶栏：回到用户离开时的那一项，失败再退回“小房子”。
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

    // Tab → 海报行：按“与 Tab 相近的横向位置”落到对应卡片上。
    fun focusPoster(index: Int) {
        val target = index.coerceIn(0, posters.lastIndex)
        // 焦点卡片停在第 5 个槽位（0-based 4）：索引 0..4 时行不动，索引 ≥ 5 起每按一次右键左移一张。
        val anchor = (target - POSTER_FOCUS_SLOT).coerceAtLeast(0)
        if (requestFocus(posterRequesters, target)) {
            if (posterState.firstVisibleItemIndex == anchor && posterState.firstVisibleItemScrollOffset == 0) return
            scope.launch { posterState.animateScrollToItem(anchor) }
            return
        }
        // 目标卡片还没组合（例如刚切分类就要跳到靠后的卡片）：先滚过去，等一帧再请求焦点。
        scope.launch {
            posterState.scrollToItem(anchor)
            withFrameNanos { }
            requestFocus(posterRequesters, target)
        }
    }

    // 海报行内左右移动：到行首/行尾时事件已被消费，焦点留在原地，不会掉出海报区。
    fun focusPosterHorizontal(fromIndex: Int, delta: Int) {
        val target = fromIndex + delta
        if (target !in posters.indices) return
        focusPoster(target)
    }

    // 海报行 → 分类 Tab：回到用户离开时的那一项。
    fun focusTabFromPoster() = focusTab(lastTabIndex)

    // 启动时、以及占位 Dialog 关闭后，焦点都落回“小房子”。
    LaunchedEffect(showHomeDialog) {
        if (showHomeDialog) return@LaunchedEffect
        requestFocus(topBarRequesters, 0)
    }

    // 切分类后海报行回到最左，保证“Tab ↓ 落到第一张”和视觉一致。
    LaunchedEffect(selectedTabIndex) {
        posterState.scrollToItem(0)
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // ---- 底层：全屏背景大图 + 渐变遮罩 ----
        HeroBackdrop(url = heroPoster.backdropUrl)
        HeroScrim()

        // ---- 顶层：顶栏 / 剧名信息 / 分类 Tab / 海报行 ----
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
                    .weight(1f),
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
                onMoveDown = { index -> focusPoster(columnForTab(index)) },
                onMoveLeft = { index -> focusTab(index - 1) },
                onMoveRight = { index -> focusTab(index + 1) },
            )
            PosterRow(
                posters = posters,
                state = posterState,
                requesters = posterRequesters,
                onFocused = { index -> focusedPosterIndex = index },
                onClick = onLaunchPlayback,
                onMoveUp = { focusTabFromPoster() },
                onMoveDown = { },
                onMoveLeft = { index -> focusPosterHorizontal(index, -1) },
                onMoveRight = { index -> focusPosterHorizontal(index, 1) },
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
 * 海报行。
 *
 * 一屏 7 张半：卡片宽度按“(可用宽度 − 起始留白) ÷ 7.5 − 间距”反推，960dp 设计宽（1080p 电视 @ xhdpi）下
 * 得到约 119dp ≈ `ref_docs/adapter_vod_rect.xml` 的 120dp（2:3 由 [POSTER_ASPECT_RATIO] 锁定为 180dp 高）；
 * 右侧不留边距，所以第 8 张会自然露出半张。
 *
 * 图片由 Coil 加载，OkHttp 客户端走 [HeroImageLoader] 里的多路 DoH（`HeroHttpDns`）。
 */
@Composable
private fun PosterRow(
    posters: List<HeroPoster>,
    state: LazyListState,
    requesters: List<FocusRequester>,
    modifier: Modifier = Modifier,
    onFocused: (Int) -> Unit,
    onClick: (String) -> Unit,
    onMoveUp: (Int) -> Unit,
    onMoveDown: (Int) -> Unit,
    onMoveLeft: (Int) -> Unit,
    onMoveRight: (Int) -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val cardWidth = ((maxWidth - POSTER_ROW_START_PADDING) / POSTER_VISIBLE_COUNT - POSTER_GAP)
            .coerceAtLeast(POSTER_REFERENCE_WIDTH * 0.75f)
        LazyRow(
            state = state,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(
                start = POSTER_ROW_START_PADDING,
                top = POSTER_ROW_TOP_PADDING,
                bottom = POSTER_ROW_BOTTOM_PADDING,
            ),
            horizontalArrangement = Arrangement.spacedBy(POSTER_GAP),
        ) {
            itemsIndexed(items = posters, key = { _, poster -> poster.id }) { index, poster ->
                PosterCard(
                    poster = poster,
                    width = cardWidth,
                    requester = requesters[index],
                    onFocused = { onFocused(index) },
                    onClick = { onClick(poster.title) },
                    // 把“从哪一张发起”带回上层：滚动锚点与左右边界都在上层统一处理。
                    onMoveUp = { onMoveUp(index) },
                    onMoveDown = { onMoveDown(index) },
                    onMoveLeft = { onMoveLeft(index) },
                    onMoveRight = { onMoveRight(index) },
                )
            }
        }
    }
}

/**
 * 单张海报卡片：图片区与文字区完全剥离。
 *
 * 尺寸与样式全部对齐参考文件：
 * - 图片：`ref_docs/adapter_vod_rect.xml` 第 24-25 行 `layout_width=120dp / layout_height=180dp`，
 *   即严格 2:3；这里用 [POSTER_ASPECT_RATIO] 锁死，宽度由 [PosterRow] 反推。
 * - 圆角：`shapeAppearanceOverlay=@style/Vod.Grid.Large`（XML 第 30 行），该样式在
 *   `app/src/main/res/values/styles.xml:12-14` 定义 `cornerSize=8dp` → [POSTER_SHAPE] = 8dp。
 * - 标题：XML 第 34-40 行 `textSize=13sp` + `layout_marginTop=8dp` + `ellipsize=marquee`，
 *   这里用 13sp + 8dp 间距 + [basicMarquee]（仅焦点态跑马灯，未聚焦时省略号收尾）。
 * - 焦点缩放：`ref_docs/VodRectPresenter.java` 第 39-41 行 `1.08f`，本项目按产品要求取 1.05。
 * - 描边/阴影：VodRectPresenter 第 90-101 行的 overlay 视觉厚度约 2dp，Compose 用贴边 `border(2.dp)`
 *   加焦点态 `shadow(18.dp)` 表达同一层级。
 *
 * `graphicsLayer` 只挂在图片 Box 上：文字容器高度固定、字号不变，放大时既不变形也不会被挤压截断。
 */
@Composable
private fun PosterCard(
    poster: HeroPoster,
    width: Dp,
    requester: FocusRequester,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
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
            .width(width)
            .focusRequester(requester)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // 四个方向都由卡片自己接管：左右决定行滚动锚点，上回分类条、下留在原地。
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
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (focused) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier),
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

/** 第 index 个 Tab 正下方对应的海报卡片序号（6 个分类 → 行首 6 张卡片，按比例落位）。 */
private fun columnForTab(tabIndex: Int): Int =
    (tabIndex * POSTER_TAB_SPAN / HERO_TABS.size).coerceIn(0, POSTER_TAB_SPAN - 1)

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

// 参考 ref_docs/adapter_vod_rect.xml 第 24-25 行：ShapeableImageView 120dp × 180dp（严格 2:3）。
// 参考图一屏 7 张半：960dp 设计宽（1080p 电视 @ xhdpi）下 7.5 × (120 + 8) = 960dp，
// 所以卡片宽度按“可用宽度 ÷ 7.5 − 间距”反推 —— 在 960dp 上正好还原 120dp，同时任何密度都能保持 7 张半。
private val POSTER_REFERENCE_WIDTH = 120.dp
private const val POSTER_VISIBLE_COUNT = 7.5f
private val POSTER_GAP = 8.dp

// 焦点卡片停在第 5 个槽位（0-based 4）：索引 0..4 时行不动，索引 ≥ 5 起每按一次右键整行左移一张。
private const val POSTER_FOCUS_SLOT = 4

// 顶栏/分类条都是 6 个分类 → 海报行前 6 张卡片，Tab ↓ 按横向位置落位。
private const val POSTER_TAB_SPAN = 6

// 参考 ref_docs/VodRectPresenter.java 第 39-41 行：原实现 hasFocus() ? 1.08f : 1.0f；本项目按要求取 1.05。
private const val POSTER_FOCUS_SCALE = 1.05f

// 参考 ref_docs/VodRectPresenter.java 第 75 行：height = width * 1.5f。
private val POSTER_ASPECT_RATIO = 2f / 3f

private const val TOP_BAR_FOCUS_SP = 22f
private const val TOP_BAR_IDLE_SP = 20f
private const val TOP_BAR_IDLE_ALPHA = 0.6f

// 海报行（单行横向）：信息层吃掉顶栏 / 分类条 / 海报行之外的全部高度，
// 960×540dp 下海报行约 120×180dp 卡片 + 8dp 间距 + 20dp 标题 + 上下留白 ≈ 234dp。
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

// 海报行内边距：左侧 8dp 给首张卡片的放大描边留出余量（放大后左右各溢出约 3dp），
// 右侧保持 0，第 8 张才会露出半张；卡片宽度按同一公式自动收窄（7 张半的关系保持不变）。
private val POSTER_ROW_START_PADDING = 8.dp
private val POSTER_ROW_TOP_PADDING = 10.dp
private val POSTER_ROW_BOTTOM_PADDING = 16.dp

private val POSTER_FOCUS_ELEVATION = 18.dp

// 参考 VodRectPresenter.java 第 90-101 行：overlay 线宽 density*1dp、四边各外扩 1dp，视觉厚度约 2dp。
private val POSTER_FOCUS_BORDER = 2.dp

// 海报标题：ref_docs/adapter_vod_rect.xml 第 34-40 行 —— 13sp + 8dp marginTop + marquee。
// 固定高度容器保证放大或切换时整行高度不变。
private val POSTER_TITLE_GAP = 8.dp
private val POSTER_TITLE_HEIGHT = 20.dp
private const val POSTER_TITLE_SP = 13f

// 胶囊 Tab 选中态描边加粗到 3dp，与 1dp 的未选中描边拉开对比。
private val HERO_TAB_FOCUS_BORDER = 3.dp

private val HERO_RED = Color(0xFFE50914)

// styles.xml:12-14 —— `Vod.Grid.Large` 的 cornerSize 精确值就是 8dp（父样式 `Vod.Grid` 同为 8dp 圆角）。
private val POSTER_SHAPE = RoundedCornerShape(8.dp)
private val POSTER_PLACEHOLDER = Color(0xFF161616)
private val DIALOG_SHAPE = RoundedCornerShape(14.dp)
