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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
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
 * 本文件的每一个尺寸都直接取自 `ref_docs/activity_home_v2.xml` 的真实节点参数，
 * 不再有“按屏幕反推”或“目测调参”的数值。行号注释指向该 XML。
 *
 * 分层（自下而上，与 XML 的子节点顺序一一对应）：
 * 1. [HeroBackdrop]：全屏背景大图（XML `heroBg`），随海报焦点用 [Crossfade] 平滑切换。
 * 2. [HeroMaskLeft]：横向黑色渐变（XML `maskLeft` / `bg_hero_mask_h`）。
 * 3. [HeroMaskBottom]：纵向黑色渐变（XML `maskBottom` / `bg_hero_mask_v`）。
 * 4. 内容 Column（XML `bottomContent` + `navBar`）：顶栏 → 弹性留白 → 左侧剧名信息 → 分类 Tab → 海报区。
 *
 * 关键参数（XML 行号）：
 * - 全局左右边距 24dp：`navBar` L208-209、`heroInfo` L69-70、`typeRecycler` L138-139、`recycler` L157-158。
 * - 顶栏高 64dp：L207。
 * - 剧名 32sp 粗体 / 固定高 36dp / 阴影 #cc000000 2,2,12：L72-83。
 * - 元数据 14sp / #ccffffff / marginTop 2dp / 高 20dp：L93-104。
 * - 简介 12sp / #bbffffff / 高 40dp / maxLines 2 / lineSpacingExtra 3dp：L112-125。
 * - 信息区 marginBottom 22dp：L68；Tab 栏 marginBottom 12dp：L137；海报区高 215dp：L147。
 * - 卡片 120×180dp / 8dp 圆角 / 标题 marginTop 8dp + 13sp：`ref_docs/adapter_vod_rect.xml` L24-27、L45-57。
 *
 * 焦点契约（三区各自持有 FocusRequester，全部显式交接，不依赖 2D 自动搜索）：
 * - 进入 Tab 栏（无论从 TopBar ↓ 还是从海报行 ↑）：落到**记忆中的**上一次选中 Tab。
 * - 进入 TopBar 或海报行（从别的排切入）：一律落到各自最左侧的第一项。
 * - 海报行 ←/→ 行内移动；仅当焦点到达第 5 张（index = 4）时把该段推到视觉最左侧，
 *   index 0..3 不滚动、左侧不露出上一张的边；海报行 ↓ 留在原地（已在最底行）。
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

    // Tab 栏的记忆索引：从第一排按“下”或从第三排按“上”进入时，都落回这一项。
    var rememberedTabIndex by remember { mutableIntStateOf(0) }
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var focusedPosterIndex by remember { mutableIntStateOf(0) }
    var showHomeDialog by remember { mutableStateOf(false) }

    // 换分类时用同一批假数据做轮转，视觉上列表确实换了，但不需要第二套资源。
    val posters = remember(selectedTabIndex) { postersForTab(selectedTabIndex) }
    val heroPoster = posters.getOrElse(focusedPosterIndex) { posters[0] }

    // 任意一排 → 顶栏：默认落在最左侧的第一项（小房子），不记忆上次位置。
    fun focusTopBar() {
        requestFocus(topBarRequesters, 0)
    }

    // 任意一排 → Tab：落在“记忆中的上一次选中项”，而不是第一项。
    fun focusTab(index: Int = rememberedTabIndex) {
        val target = index.coerceIn(0, HERO_TABS.lastIndex)
        rememberedTabIndex = target
        selectedTabIndex = target
        scope.launch { tabState.animateScrollToItem(target) }
        if (!requestFocus(tabRequesters, target)) requestFocus(tabRequesters, 0)
    }

    // → 海报行：从别的排切入时用默认参数 index = 0，永远落在最左侧的第一张。
    fun focusPoster(index: Int = 0) {
        val target = index.coerceIn(0, posters.lastIndex)
        // 靠左吸附：焦点在 index 0..3 时锚点恒为 0，列表不动，右侧露出第 8 张的边；
        // 焦点到 index 4 及以后，锚点 = 焦点索引 → 被选中的卡正好停在 24dp 基准线上，
        // 上一张被挤到 24dp 左侧、在屏幕最左缘出血露出一截。
        val anchor = if (target >= POSTER_SCROLL_ANCHOR) target else 0
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
        // ---- 底层：全屏背景大图 + 横向/纵向遮罩（XML 顺序：heroBg → maskLeft → maskBottom）----
        HeroBackdrop(url = heroPoster.backdropUrl)
        HeroMaskLeft()
        HeroMaskBottom()

        // ---- 顶层：与 XML 的 bottomContent + navBar 同构 ----
        // 横向边距不在这里统一设置：XML 里 navBar / heroInfo / typeRecycler / recycler
        // 各自持有 24dp 的 paddingStart|End，这里同样由各区自己加，保证与 XML 一一对应。
        Column(modifier = Modifier.fillMaxSize()) {
            HeroTopBar(
                requesters = topBarRequesters,
                onHomeConfirm = { showHomeDialog = true },
                // 顶栏 ↓ 进入 Tab 栏：落到记忆中的 Tab，而不是与顶栏项横向对齐的那一个。
                onMoveDown = { focusTab() },
            )
            // XML 里 bottomContent 用 layout_alignParentBottom 贴底；Compose 用弹性留白表达同一语义。
            Spacer(modifier = Modifier.weight(1f))
            HeroInfoArea(poster = heroPoster)
            HeroTabBar(
                state = tabState,
                requesters = tabRequesters,
                selectedIndex = selectedTabIndex,
                onFocused = { index ->
                    rememberedTabIndex = index
                    selectedTabIndex = index
                },
                onConfirm = { index -> selectedTabIndex = index },
                // Tab ↑ 回顶栏、↓ 进海报行：两边都默认落在各自最左侧的第一项。
                onMoveUp = { focusTopBar() },
                onMoveDown = { focusPoster() },
                onMoveLeft = { index -> focusTab(index - 1) },
                onMoveRight = { index -> focusTab(index + 1) },
            )
            PosterArea(
                posters = posters,
                state = posterState,
                requesters = posterRequesters,
                onFocused = { index -> focusedPosterIndex = index },
                onClick = onLaunchPlayback,
                // 海报行 ↑ 回 Tab 栏：落到记忆中的 Tab。
                onMoveUp = { focusTab() },
                onMoveDown = { },
                onMoveLeft = { index -> focusPosterHorizontal(index, -1) },
                onMoveRight = { index -> focusPosterHorizontal(index, 1) },
            )
        }
    }

    if (showHomeDialog) HeroPlaceholderDialog(onDismiss = { showHomeDialog = false })
}

/**
 * 底层背景大图（XML `heroBg`）。
 *
 * 用 [Crossfade] 让“海报焦点变化 → 背景切换”是交叉淡入淡出，而不是硬切；
 * 300ms 与左侧文字的 320ms 呼应：背景略慢半拍，注意力先落在文字上。
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

/** 横向遮罩（XML `maskLeft`，drawable `bg_hero_mask_h`）：左侧压暗，保证剧名/简介在浅色背景上可读。 */
@Composable
private fun HeroMaskLeft() {
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

/** 纵向遮罩（XML `maskBottom`，drawable `bg_hero_mask_v`）：顶部压暗顶栏、底部接近纯黑托住海报区。 */
@Composable
private fun HeroMaskBottom() {
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
}

/**
 * 顶部导航栏（XML `navBar`，L199-262）：
 * - 高 64dp（L207），左右 padding 各 24dp（L208-209），内容垂直居中（L200 `gravity=center_vertical`）。
 * - `homeBtn` 28dp（L219-220），`navRecycler` 距它 20dp（L259 `layout_marginStart=20dp`）。
 *
 * 文字样式与第二排 Tab 栏完全一致（同为 [HERO_LABEL_SP]）：未选中纯白 60% 透明度，
 * 焦点态纯白 + 平滑放大 1.1 倍 + 下方红色短横线（[HeroAccentColor]）。
 */
@Composable
private fun HeroTopBar(
    requesters: List<FocusRequester>,
    onHomeConfirm: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(NAV_BAR_HEIGHT)
            .padding(horizontal = HERO_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // homeBtn：只有“小房子”有动作（占位 Dialog），其余项留待后续接入对应页面。
        HeroTopBarItem(
            label = TOP_BAR_LABELS[0],
            isHome = true,
            requester = requesters[0],
            onConfirm = onHomeConfirm,
            onMoveDown = onMoveDown,
        )
        Row(
            modifier = Modifier.padding(start = NAV_ITEMS_START_GAP),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TOP_BAR_ITEM_SPACING),
        ) {
            for (index in 1 until TOP_BAR_LABELS.size) {
                HeroTopBarItem(
                    label = TOP_BAR_LABELS[index],
                    isHome = false,
                    requester = requesters[index],
                    onConfirm = { },
                    onMoveDown = onMoveDown,
                )
            }
        }
    }
}

@Composable
private fun HeroTopBarItem(
    label: String,
    isHome: Boolean,
    requester: FocusRequester,
    onConfirm: () -> Unit,
    onMoveDown: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val textColor by animateColorAsState(
        targetValue = if (focused) Color.White else Color.White.copy(alpha = TOP_BAR_IDLE_ALPHA),
        animationSpec = tween(durationMillis = 150),
        label = "topBarText",
    )
    // 焦点态不再改字号（那样会顶动下划线），改为整体平滑放大 1.1 倍。
    val focusScale by animateFloatAsState(
        targetValue = if (focused) TOP_BAR_FOCUS_SCALE else 1f,
        animationSpec = tween(durationMillis = TOP_BAR_ANIM_MS),
        label = "topBarScale",
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
            .onFocusChanged { focused = it.isFocused }
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
            // 不加左右 padding：顶栏第一项的左边线必须与 Tab / 海报的 24dp 基准线完全重合。
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // homeBtn 是 28dp 方块（XML L219-220）；文字项固定 30dp 内容区。
        // 字号固定为 [HERO_LABEL_SP]（与 Tab 栏同号），放大只走 graphicsLayer，不参与布局，下划线不会跳动。
        Box(
            modifier = Modifier.height(if (isHome) NAV_HOME_SIZE else TOP_BAR_TEXT_BOX),
            contentAlignment = Alignment.Center,
        ) {
            if (isHome) {
                HouseGlyph(
                    color = textColor,
                    modifier = Modifier
                        .size(NAV_HOME_GLYPH_SIZE)
                        .graphicsLayer {
                            scaleX = focusScale
                            scaleY = focusScale
                        },
                )
            } else {
                Text(
                    text = label,
                    modifier = Modifier.graphicsLayer {
                        scaleX = focusScale
                        scaleY = focusScale
                    },
                    color = textColor,
                    fontSize = HERO_LABEL_SP.sp,
                    fontWeight = if (focused) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        // 焦点态指示器：2dp 高红色短横线（长度固定 26dp，仅淡入淡出，避免文字宽度变化导致抖动）。
        Box(
            modifier = Modifier
                .width(26.dp)
                .height(2.dp)
                .alpha(underlineAlpha)
                .background(HeroAccentColor, RoundedCornerShape(2.dp)),
        )
    }
}

/**
 * 左侧剧名信息层（XML `heroInfo`，L61-127）。
 *
 * 节点参数逐条对应：
 * - 容器：`layout_marginBottom=22dp`（L68）+ `paddingStart/End=24dp`（L69-70）。
 * - 剧名：32sp 粗体、固定高 36dp、`gravity=bottom`、阴影 #cc000000 dx2 dy2 radius12（L72-83）。
 * - 元数据：14sp、#ccffffff、高 20dp、marginTop 2dp、阴影 #aa000000 1.5/1.5/8（L93-104）。
 * - 简介：12sp、#bbffffff、高 40dp、maxLines 2、lineSpacingExtra 3dp、
 *   外层横向 LinearLayout `weightSum=2` 而子项 `weight=1` → 宽度取一半（L105-125）。
 *
 * 内容随海报焦点变化，用 [AnimatedContent] 做“淡入 + 轻微上滑 / 淡出 + 轻微上移”，
 * 320ms 比背景略快，文字先落位，避免整屏同时变化显得迟滞。
 */
@Composable
private fun HeroInfoArea(poster: HeroPoster) {
    AnimatedContent(
        targetState = poster,
        transitionSpec = {
            // 进场：淡入 + 从下方 1/6 高度滑入；退场：淡出 + 向上 1/6 高度滑出。
            val enter = fadeIn(tween(HERO_INFO_FADE_MS)) +
                slideInVertically(tween(HERO_INFO_FADE_MS)) { height -> height / 6 }
            val exit = fadeOut(tween(HERO_INFO_FADE_MS)) +
                slideOutVertically(tween(HERO_INFO_FADE_MS)) { height -> -height / 6 }
            enter togetherWith exit
        },
        contentAlignment = Alignment.BottomStart,
        label = "heroInfo",
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HERO_PADDING)
            .padding(bottom = HERO_INFO_BOTTOM_GAP),
    ) { current ->
        Column(modifier = Modifier.fillMaxWidth()) {
            // 剧名：固定 36dp，文字贴底。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(HERO_TITLE_HEIGHT),
                contentAlignment = Alignment.BottomStart,
            ) {
                Text(
                    text = current.title,
                    color = Color.White,
                    fontSize = HERO_TITLE_SP.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(shadow = HERO_TITLE_SHADOW),
                )
            }
            Spacer(modifier = Modifier.height(HERO_META_TOP_GAP))
            // 元数据：固定 20dp，文字贴底。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(HERO_META_HEIGHT),
                contentAlignment = Alignment.BottomStart,
            ) {
                Text(
                    text = current.meta,
                    color = HERO_META_COLOR,
                    fontSize = HERO_META_SP.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(shadow = HERO_META_SHADOW),
                )
            }
            Spacer(modifier = Modifier.height(HERO_DESC_TOP_GAP))
            // 简介：外层 weightSum=2 / 子项 weight=1 → 实际占父宽的一半。
            Text(
                text = current.synopsis,
                modifier = Modifier
                    .fillMaxWidth(HERO_DESC_WIDTH_FRACTION)
                    .height(HERO_DESC_HEIGHT),
                color = HERO_DESC_COLOR,
                fontSize = HERO_DESC_SP.sp,
                lineHeight = HERO_DESC_LINE_HEIGHT_SP.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(shadow = HERO_DESC_SHADOW),
            )
        }
    }
}

/**
 * 分类 Tab 栏（XML `typeRecycler`，L128-141）：
 * - 左右 padding 24dp（L138-139）、`paddingBottom=2dp`（L130）、`layout_marginBottom=12dp`（L137）。
 *
 * 胶囊样式严格对照 `ref_docs/tab_ui.png`（无描边，纯色填充）：
 * - 选中：HeroAccentColor 纯红底 + 纯白字。
 * - 未选中：Color(0x33FFFFFF) 深灰半透明底 + 纯白字。
 * - 形状为胶囊（[HERO_TAB_SHAPE]，24dp 圆角，高度不足时自动收成半圆）。
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
        modifier = Modifier
            .fillMaxWidth()
            // XML L137：Tab 栏与海报区之间 12dp。
            .padding(bottom = HERO_TAB_BOTTOM_GAP),
        contentPadding = PaddingValues(
            start = HERO_PADDING,
            end = HERO_PADDING,
            bottom = HERO_TAB_BOTTOM_PADDING,
        ),
        horizontalArrangement = Arrangement.spacedBy(HERO_TAB_SPACING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HERO_TABS.forEachIndexed { index, label ->
            // 用 item(key = ...) 而不是 itemsIndexed：后者与海报行的 itemsIndexed 同名，
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
    // 选中纯红、未选中深灰半透明；两者都是无描边的实心底，文字一律纯白。
    val chipBackground by animateColorAsState(
        targetValue = if (active) HeroAccentColor else HERO_TAB_IDLE_BACKGROUND,
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
            // 高度锁死 26dp + 13dp 圆角 = 完美半圆胶囊；左右 12dp、上下 0dp。
            .height(HERO_TAB_HEIGHT)
            .clip(HERO_TAB_SHAPE)
            .background(chipBackground)
            .padding(horizontal = HERO_TAB_H_PADDING, vertical = HERO_TAB_V_PADDING),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = HERO_LABEL_SP.sp,
            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

/**
 * 海报区（XML `posterArea`，L142-191）：整块固定高 215dp（L147），内部 recycler 同为 215dp（L156）。
 *
 * 卡片尺寸严格照抄 `ref_docs/adapter_vod_rect.xml`：120dp × 180dp、8dp 圆角、间距 12dp。
 * 列表**靠左吸附**，不做任何居中/对称换算：
 * - `contentPadding = (start 24dp, end 0dp)`，LazyRow 自身铺满整行且不加外层 padding，
 *   于是它的裁剪边界就是屏幕边缘 —— 等价于 Android 的 `clipToPadding=false`：
 *   静止时第一张卡正好压在 24dp 基准线上；滚动后卡片可以一路出血到 x = 0。
 * - 焦点在 index 0..3 时锚点恒为 0，列表不动，右侧自然露出第 8 张卡的边。
 * - 焦点到 index 4 及以后，锚点 = 焦点索引，被选中的卡正好停在 24dp 基准线上，
 *   上一张则被挤到 24dp 左侧、在屏幕最左缘露出一截（出血贴边）。
 */
@Composable
private fun PosterArea(
    posters: List<HeroPoster>,
    state: LazyListState,
    requesters: List<FocusRequester>,
    onFocused: (Int) -> Unit,
    onClick: (String) -> Unit,
    onMoveUp: (Int) -> Unit,
    onMoveDown: (Int) -> Unit,
    onMoveLeft: (Int) -> Unit,
    onMoveRight: (Int) -> Unit,
) {
    LazyRow(
        state = state,
        // 铺满整行、无外层 padding：LazyRow 的裁剪边界 = 屏幕边缘（Compose 等价于 clipToPadding=false）。
        modifier = Modifier
            .fillMaxWidth()
            .height(POSTER_AREA_HEIGHT),
        contentPadding = PaddingValues(start = HERO_PADDING, end = 0.dp),
        horizontalArrangement = Arrangement.spacedBy(POSTER_GAP),
    ) {
        itemsIndexed(items = posters, key = { _, poster -> poster.id }) { index, poster ->
            PosterCard(
                poster = poster,
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

/**
 * 单张海报卡片：图片区与文字区完全剥离。
 *
 * 尺寸与样式全部对齐参考文件（`ref_docs/adapter_vod_rect.xml`）：
 * - 图片：120dp × 180dp（L24-25），固定尺寸，严格 2:3；圆角 8dp（L27 `Vod.Grid.Large`）。
 * - 标题：13sp（L46）+ marginTop 8dp（L53）+ `#ccffffff`（L47）+ marquee（L48），
 *   这里用 13sp + 8dp 间距 + [basicMarquee]（仅焦点态跑马灯，未聚焦时省略号收尾）。
 * - 焦点缩放：`ref_docs/VodRectPresenter.java` 第 39-41 行 `1.08f`，本项目按产品要求取 1.1。
 * - 描边/阴影：VodRectPresenter 第 90-101 行的 overlay 视觉厚度约 2dp，Compose 用贴边 `border(2.dp)`
 *   加焦点态 `shadow(18.dp)` 表达同一层级。
 *
 * `graphicsLayer` 只挂在图片 Box 上：文字容器字号不变，放大时既不变形也不会被挤压截断。
 */
@Composable
private fun PosterCard(
    poster: HeroPoster,
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
        animationSpec = spring(
            dampingRatio = POSTER_SPRING_DAMPING,
            stiffness = POSTER_SPRING_STIFFNESS,
        ),
        label = "posterScale",
    )
    val elevation by animateDpAsState(
        targetValue = if (focused) POSTER_FOCUS_ELEVATION else 0.dp,
        animationSpec = spring(
            dampingRatio = POSTER_SPRING_DAMPING,
            stiffness = POSTER_SPRING_STIFFNESS,
        ),
        label = "posterElevation",
    )
    val borderWidth by animateDpAsState(
        targetValue = if (focused) POSTER_FOCUS_BORDER else 0.dp,
        animationSpec = spring(
            dampingRatio = POSTER_SPRING_DAMPING,
            stiffness = POSTER_SPRING_STIFFNESS,
        ),
        label = "posterBorder",
    )
    val interaction = remember { MutableInteractionSource() }

    Column(
        modifier = Modifier
            .width(POSTER_CARD_WIDTH)
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
        // 图片区：只有这一层参与缩放、描边与阴影。120dp × 180dp 是 adapter_vod_rect 的固定值。
        Box(
            modifier = Modifier
                .width(POSTER_CARD_WIDTH)
                .height(POSTER_CARD_HEIGHT)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .shadow(elevation = elevation, shape = POSTER_SHAPE, clip = false)
                .clip(POSTER_SHAPE)
                .background(POSTER_PLACEHOLDER)
                // 焦点边框统一用主题红（HeroAccentColor），不再是白色。
                .border(width = borderWidth, color = HeroAccentColor, shape = POSTER_SHAPE),
        ) {
            AsyncImage(
                model = poster.url,
                contentDescription = poster.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // 文字区：marginTop 8dp + 13sp 单行，图片放大时这里尺寸与字号都不变。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = POSTER_TITLE_GAP),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = poster.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (focused) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier),
                color = if (focused) Color.White else POSTER_TITLE_COLOR,
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

// ---------------------------------------------------------------------------
// 全局边距：activity_home_v2.xml 里 navBar(L208-209) / heroInfo(L69-70) /
// typeRecycler(L138-139) / recycler(L157-158) 的 paddingStart|End 全部是 24dp。
// ---------------------------------------------------------------------------
private val HERO_PADDING = 24.dp

// ---------------------------------------------------------------------------
// 顶部导航 navBar（L199-262）
// ---------------------------------------------------------------------------
private val NAV_BAR_HEIGHT = 64.dp          // L207 layout_height
private val NAV_HOME_SIZE = 28.dp           // L219-220 homeBtn
private val NAV_HOME_GLYPH_SIZE = 20.dp     // 28dp 按钮内留 4dp 呼吸
private val NAV_ITEMS_START_GAP = 20.dp     // L259 navRecycler layout_marginStart
private val TOP_BAR_TEXT_BOX = 20.dp        // 文字项固定内容区（13sp 的贴合高度）
private const val TOP_BAR_FOCUS_SCALE = 1.1f // 焦点态整体放大 1.1 倍（只走 graphicsLayer，不动布局）
private const val TOP_BAR_ANIM_MS = 150
private const val TOP_BAR_IDLE_ALPHA = 0.6f
private val TOP_BAR_ITEM_SPACING = 40.dp    // navRecycler 项间距（adapter_nav 职责，沿用既有视觉）

// ---------------------------------------------------------------------------
// 左侧 Hero 信息区 heroInfo（L61-127）
// ---------------------------------------------------------------------------
private val HERO_INFO_BOTTOM_GAP = 22.dp    // L68 layout_marginBottom
private val HERO_TITLE_HEIGHT = 36.dp       // L79 layout_height
private const val HERO_TITLE_SP = 32f       // L72 textSize
private val HERO_META_TOP_GAP = 2.dp        // L100 layout_marginTop
private val HERO_META_HEIGHT = 20.dp        // L99 layout_height
private const val HERO_META_SP = 14f        // L93 textSize
private val HERO_DESC_TOP_GAP = 4.dp        // L109 layout_marginTop
private val HERO_DESC_HEIGHT = 40.dp        // L118 layout_height
private const val HERO_DESC_SP = 12f        // L112 textSize
private const val HERO_DESC_WIDTH_FRACTION = 0.5f // L110 weightSum=2 + L124 weight=1
private const val HERO_BACKDROP_FADE_MS = 300
private const val HERO_INFO_FADE_MS = 320

// 简介 lineSpacingExtra=3dp（L125）。Android 的 12sp 默认行高 ≈ 14sp，加 3dp → 17sp。
private const val HERO_DESC_LINE_HEIGHT_SP = 17f

// XML 阴影参数是 px 值，Compose 的 Shadow 同样按 px 解释，因此直接照抄。
private val HERO_TITLE_SHADOW = Shadow(
    color = Color(0xCC000000),
    offset = Offset(2f, 2f),
    blurRadius = 12f,
)
private val HERO_META_SHADOW = Shadow(
    color = Color(0xAA000000),
    offset = Offset(1.5f, 1.5f),
    blurRadius = 8f,
)
private val HERO_DESC_SHADOW = Shadow(
    color = Color(0xAA000000),
    offset = Offset(1.2f, 1.2f),
    blurRadius = 6f,
)

private val HERO_META_COLOR = Color(0xCCFFFFFF)  // L94
private val HERO_DESC_COLOR = Color(0xBBFFFFFF)  // L113

// ---------------------------------------------------------------------------
// 分类 Tab 栏 typeRecycler（L128-141）
// ---------------------------------------------------------------------------
private val HERO_TAB_BOTTOM_GAP = 12.dp     // L137 layout_marginBottom
private val HERO_TAB_BOTTOM_PADDING = 2.dp  // L130 paddingBottom
private val HERO_TAB_SPACING = 16.dp        // Tab 之间间距
private val HERO_TAB_HEIGHT = 26.dp         // 胶囊高度，严格 26dp
private val HERO_TAB_H_PADDING = 12.dp      // 左右 12dp
private val HERO_TAB_V_PADDING = 0.dp       // 上下 0dp（高度由 HERO_TAB_HEIGHT 锁死）
private val HERO_TAB_SHAPE = RoundedCornerShape(13.dp) // 13dp = 高度的一半，完美半圆胶囊
private val HERO_TAB_IDLE_BACKGROUND = Color(0x33FFFFFF) // 未选中：深灰半透明，无描边

// ---------------------------------------------------------------------------
// 海报区 posterArea（L142-191）+ 卡片 adapter_vod_rect（L24-27、L45-57）
// ---------------------------------------------------------------------------
private val POSTER_AREA_HEIGHT = 215.dp     // L147 / L156
private val POSTER_TITLE_GAP = 8.dp         // adapter_vod_rect L53 layout_marginTop
private const val POSTER_TITLE_SP = 13f     // adapter_vod_rect L46 textSize
private val POSTER_TITLE_COLOR = Color(0xCCFFFFFF) // adapter_vod_rect L47

// 卡片尺寸严格照抄 adapter_vod_rect.xml（L24-25），不再按屏幕反推。
private val POSTER_CARD_WIDTH = 120.dp
private val POSTER_CARD_HEIGHT = 180.dp
private val POSTER_GAP = 12.dp

// 焦点进入第 5 张（index = 4）起，被选中的卡靠左吸附到 24dp 基准线；index 0..3 一律停在行首。
private const val POSTER_SCROLL_ANCHOR = 4

// 参考 ref_docs/VodRectPresenter.java 第 39-41 行：原实现 hasFocus() ? 1.08f : 1.0f；本项目按定稿取 1.1。
private const val POSTER_FOCUS_SCALE = 1.1f

// 焦点缩放的弹簧参数：dampingRatio 0.8（略有回弹但不过冲）+ stiffness 400（电视遥控器连按时跟手）。
private const val POSTER_SPRING_DAMPING = 0.8f
private const val POSTER_SPRING_STIFFNESS = 400f

private val POSTER_FOCUS_ELEVATION = 18.dp

// 参考 VodRectPresenter.java 第 90-101 行：overlay 线宽 density*1dp、四边各外扩 1dp，视觉厚度约 2dp。
private val POSTER_FOCUS_BORDER = 2.dp

// styles.xml:12-14 —— `Vod.Grid.Large` 的 cornerSize 精确值就是 8dp（父样式 `Vod.Grid` 同为 8dp 圆角）。
private val POSTER_SHAPE = RoundedCornerShape(8.dp)
private val POSTER_PLACEHOLDER = Color(0xFF161616)
private val DIALOG_SHAPE = RoundedCornerShape(14.dp)

// ---------------------------------------------------------------------------
// 全局主题色：顶栏下划线、Tab 选中底、海报焦点边框统一使用这一个红。
// ---------------------------------------------------------------------------
private val HeroAccentColor = Color(0xFFFF0000)

// 第一排（TopBar）与第二排（Tab）共用的字号，保证两排视觉统一。
private const val HERO_LABEL_SP = 13f
