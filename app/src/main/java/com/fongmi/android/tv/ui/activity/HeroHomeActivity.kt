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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil3.compose.AsyncImage
import com.fongmi.android.tv.api.SiteApi
import com.fongmi.android.tv.ui.hero.HeroImageLoader
import com.fongmi.android.tv.utils.Notify
import com.fongmi.android.tv.utils.PushParser

/**
 * HeroUI（Compose）首页第一版：顶部控制栏 + 海报网格。
 *
 * 参考来源：
 * - 顶栏结构与排布：`ref_docs/home_ui.png`（房子图标 + 直播/搜索/历史/收藏/推送/设置，未选中白字 60%）。
 * - 卡片缩放、2:3 比例、焦点描边：`ref_docs/VodRectPresenter.java`（见各处行号注释）。
 * - 类/布局索引：`ref_docs/UI.md` §3.2（`os3` = VodRectPresenter，布局 `adapter_vod_rect` + `overlay_focus`）。
 *
 * 焦点连线是本页的核心契约：顶栏任一项按“下”进入海报网格同一视觉列的卡片，
 * 网格首行按“上”回到顶栏上次停留的那一项。
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
    // 顶栏每一项、每张海报各持有一个 FocusRequester：跨区域移动时用显式请求，避免 2D 焦点搜索落空。
    val topBarRequesters = remember { List(TOP_BAR_LABELS.size) { FocusRequester() } }
    val posterRequesters = remember { List(HERO_POSTERS.size) { FocusRequester() } }
    var lastTopBarIndex by remember { mutableIntStateOf(0) }
    var showHomeDialog by remember { mutableStateOf(false) }

    // 网格 → 顶栏：回到用户离开时的那一项，失败再退回“小房子”。
    fun focusTopBar() {
        if (!requestFocus(topBarRequesters, lastTopBarIndex)) requestFocus(topBarRequesters, 0)
    }

    // 顶栏 → 网格：落在当前可见首行与顶栏项相近的视觉列上，避免从“设置”跳到最左侧卡片。
    fun focusPosterFromTopBar(topBarIndex: Int) {
        val rowStart = (gridState.firstVisibleItemIndex / POSTER_COLUMNS) * POSTER_COLUMNS
        val column = (topBarIndex * POSTER_COLUMNS / TOP_BAR_LABELS.size).coerceIn(0, POSTER_COLUMNS - 1)
        val target = (rowStart + column).coerceIn(0, HERO_POSTERS.lastIndex)
        if (!requestFocus(posterRequesters, target)) requestFocus(posterRequesters, gridState.firstVisibleItemIndex)
    }

    // 启动时、以及占位 Dialog 关闭后，焦点都落回“小房子”。
    LaunchedEffect(showHomeDialog) {
        if (showHomeDialog) return@LaunchedEffect
        requestFocus(topBarRequesters, 0)
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        HeroTopBar(
            requesters = topBarRequesters,
            onFocused = { lastTopBarIndex = it },
            onHomeConfirm = { showHomeDialog = true },
            onMoveDown = { index -> focusPosterFromTopBar(index) },
        )
        PosterGrid(
            state = gridState,
            requesters = posterRequesters,
            onClick = onLaunchPlayback,
            onMoveUp = { focusTopBar() },
        )
    }

    if (showHomeDialog) HeroPlaceholderDialog(onDismiss = { showHomeDialog = false })
}

/**
 * 顶部控制栏。
 *
 * 焦点态按产品规范实现：文字纯白 22sp + 下方红色短横线；未选中为纯白 60% 透明度。
 * 整体排布（房子在最左、各项等距、贴顶留白）对照 `ref_docs/home_ui.png`。
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
            .padding(start = TOP_BAR_START_PADDING, end = TOP_BAR_START_PADDING, top = TOP_BAR_TOP_PADDING),
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
                // 顶栏任意一项按“下”都显式交给海报网格，保证边界处不会丢焦点。
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
 * 海报网格。
 *
 * 列数 6：对照 `ref_docs/home_ui.png` 一屏 6 张卡片；间距与左右留白按 1080p 电视观感取值。
 * 图片由 Coil 加载，OkHttp 客户端走 [HeroImageLoader] 里的多路 DoH（`HeroHttpDns`）。
 */
@Composable
private fun PosterGrid(
    state: LazyGridState,
    requesters: List<FocusRequester>,
    onClick: (String) -> Unit,
    onMoveUp: () -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(POSTER_COLUMNS),
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = GRID_SIDE_PADDING, end = GRID_SIDE_PADDING, top = 18.dp, bottom = 36.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        itemsIndexed(items = HERO_POSTERS, key = { _, poster -> poster.id }) { index, poster ->
            PosterCard(
                poster = poster,
                index = index,
                requester = requesters[index],
                onClick = { onClick(poster.title) },
                onMoveUp = onMoveUp,
            )
        }
    }
}

/**
 * 单张海报卡片。
 *
 * 参数全部对齐 `ref_docs/VodRectPresenter.java`：
 * - 焦点缩放：第 39-41 行 `float f = view.hasFocus() ? 1.08f : 1.0f`，用 setScaleX/Y 整体放大。
 *   本项目按产品要求取 **1.05**（原实现 1.08），差异点已在此注明。
 * - 宽高比：第 75 行 `rs3Var.l = (int) (iMin * 1.5f)`，即 高 = 宽 × 1.5 → `aspectRatio(2f / 3f)`。
 * - 描边：第 90-101 行用 `density * 1.0f` 的 overlay 宽度、并把 overlay 四边各外扩 1dp；
 *   Compose 里等价为贴边 `border(2.dp)`，省掉一层 overlay（视觉厚度一致）。
 * - 阴影：`ref_docs/UI.md` §3.2 的 `overlay_focus`/`selector_vod` 在放大时提供浮起感；
 *   Compose 用 `Modifier.shadow(elevation = 18.dp)` 表达同一层级关系，仅在焦点态出现。
 */
@Composable
private fun PosterCard(
    poster: HeroPoster,
    index: Int,
    requester: FocusRequester,
    onClick: () -> Unit,
    onMoveUp: () -> Unit,
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
            // 放大后压住相邻卡片，需要抬高绘制层级。
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .focusRequester(requester)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                // 首行（索引 < 列数）按“上”交还顶栏；其余行保留系统默认的向上换行行为。
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp && index < POSTER_COLUMNS) {
                    onMoveUp()
                    true
                } else {
                    false
                }
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
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
        Text(
            text = poster.title,
            modifier = Modifier.padding(top = 8.dp),
            color = if (focused) Color.White else Color.White.copy(alpha = 0.72f),
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
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

/** 在已挂载的 FocusRequester 列表里安全地请求焦点：未组合的 LazyGrid 项会抛异常，这里降级返回 false。 */
private fun requestFocus(requesters: List<FocusRequester>, index: Int): Boolean {
    val requester = requesters.getOrNull(index) ?: return false
    return runCatching { requester.requestFocus() }.isSuccess
}

private data class HeroPoster(val id: Int, val title: String, val url: String)

/** 假数据：picsum 按 seed 返回稳定图片，不依赖 TMDB API Key 即可验证 Coil 链路。 */
private val HERO_POSTERS: List<HeroPoster> = listOf(
    "后会无期", "尚公主", "小城良方", "嫁金钗", "玉簟秋", "醉梦",
    "生日快乐", "山河故人", "风起洛阳", "南来北往", "雾中灯塔", "长安夜行",
    "春日迟迟", "归途列车", "暗涌", "雪落长街", "青川旧事", "晚风集市",
    "子夜星河", "山海之间", "远方来信", "夏至未至", "孤舟渡口", "光年之外",
).mapIndexed { index, title ->
    HeroPoster(id = index, title = title, url = "https://picsum.photos/seed/hero-poster-$index/400/600")
}

// 顶栏顺序：小房子 | 直播 | 搜索 | 历史 | 收藏 | 推送 | 设置（对照 ref_docs/home_ui.png）。
private val TOP_BAR_LABELS = listOf("首页", "直播", "搜索", "历史", "收藏", "推送", "设置")

private const val POSTER_COLUMNS = 6

// 参考 ref_docs/VodRectPresenter.java 第 39-41 行：原实现 hasFocus() ? 1.08f : 1.0f；本项目按要求取 1.05。
private const val POSTER_FOCUS_SCALE = 1.05f

// 参考 ref_docs/VodRectPresenter.java 第 75 行：height = width * 1.5f。
private val POSTER_ASPECT_RATIO = 2f / 3f

private const val TOP_BAR_FOCUS_SP = 22f
private const val TOP_BAR_IDLE_SP = 20f
private const val TOP_BAR_IDLE_ALPHA = 0.6f

private val TOP_BAR_START_PADDING = 40.dp
private val TOP_BAR_TOP_PADDING = 26.dp
private val TOP_BAR_ITEM_SPACING = 36.dp

private val GRID_SIDE_PADDING = 40.dp
private val POSTER_FOCUS_ELEVATION = 18.dp

// 参考 VodRectPresenter.java 第 90-101 行：overlay 线宽 density*1dp、四边各外扩 1dp，视觉厚度约 2dp。
private val POSTER_FOCUS_BORDER = 2.dp

private val HERO_RED = Color(0xFFE50914)
private val POSTER_SHAPE = RoundedCornerShape(6.dp)
private val POSTER_PLACEHOLDER = Color(0xFF161616)
private val DIALOG_SHAPE = RoundedCornerShape(14.dp)
