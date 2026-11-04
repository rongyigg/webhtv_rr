package com.fongmi.android.tv.ui.activity

import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.fongmi.android.tv.R
import com.fongmi.android.tv.setting.Setting
import com.fongmi.android.tv.ui.hero.ACCENT_RED
import com.fongmi.android.tv.ui.hero.FIRST_KEY_INDEX
import com.fongmi.android.tv.ui.hero.HERO_KEYS
import com.fongmi.android.tv.ui.hero.HERO_SOURCE_ENTRIES
import com.fongmi.android.tv.ui.hero.HOUSE_KEY_INDEX
import com.fongmi.android.tv.ui.hero.HeroFocusBrush
import com.fongmi.android.tv.ui.hero.HeroImageLoader
import com.fongmi.android.tv.ui.hero.HeroKey
import com.fongmi.android.tv.ui.hero.HeroKeyAction
import com.fongmi.android.tv.ui.hero.HeroPosterCard
import com.fongmi.android.tv.ui.hero.HeroPosterViewRegistry
import com.fongmi.android.tv.ui.hero.INPUT_HEIGHT
import com.fongmi.android.tv.ui.hero.KEY_COLUMNS
import com.fongmi.android.tv.ui.hero.KEY_ROWS
import com.fongmi.android.tv.ui.hero.KEY_SIZE
import com.fongmi.android.tv.ui.hero.KEY_SPACING
import com.fongmi.android.tv.ui.hero.KEYWORD_MAX_LENGTH
import com.fongmi.android.tv.ui.hero.KeyboardSection
import com.fongmi.android.tv.ui.hero.PLACEHOLDER_INDEX
import com.fongmi.android.tv.ui.hero.SourceSidebar
import com.fongmi.android.tv.ui.hero.SourceSidebarMode
import com.fongmi.android.tv.ui.hero.requestFocus
import kotlinx.coroutines.launch

/**
 * HeroUI（Compose）搜索页。
 *
 * 版式基准 = 原版截图 + `ref_docs/activity_search.xml`：**上下弹性布局，一屏直排**。
 * ```
 * BoxWithConstraints(fillMaxSize)                                   // 只用来量一次视口高度
 * └─ Column(fillMaxSize + padding(top=24dp))                        // 唯一布局根，无横向 padding、无整体滚动
 *   ├─ Row(fillMaxWidth + weight(1f))                                // L12-16：上半区
 *   │    ├─ KeyboardSection(start=24 + wrapContentWidth + 等比缩放)    // L17-65：输入行 40 + 8 + 键盘 280
 *   │    └─ RightContentColumn(weight(1f) + end=24 + verticalScroll) // L66-143：历史 + 热搜 / 建议
 *   └─ if (hasInput) ResultSection(fillMaxWidth + wrapContentHeight + padding(top=12dp))
 *        ├─ LazyRow(height=32dp, contentPadding(24, 24), 间距 8dp)     // L152-161：站点 Tab
 *        ├─ Spacer(8dp)
 *        └─ Box(fillMaxWidth + height=215dp)                          // L162-167：海报容器
 *             └─ LazyRow(fillMaxSize, contentPadding(24, 24),间距 12dp)
 *                  └─ AndroidView(HeroPosterCard 120×214dp，绝不加尺寸 Modifier)
 * ```
 *
 * 三条硬约束：
 * 1. **根节点没有横向 padding**。24dp 左边距由键盘前面的一个 `Spacer(24dp)` 让出
 *    （且必须在缩放子树之外，缩放后才对得齐），右列自己 `padding(end=24dp)`，
 *    两条 `LazyRow` 用 `contentPadding(24, 24)`。
 *    Compose 的 LazyRow 按自身外框裁剪，只有它的滚动区够宽，第一项「全部」在焦点放大
 *    1.08 时才不会被切边 —— 这正是原版 `clipChildren=false + clipToPadding=false` 的等价写法。
 * 2. **上下半区彻底分离**：上半区 `weight(1f)`、下半区 `wrapContentHeight()`（绝不给 weight），
 *    两者顺序排列，物理上不可能互相覆盖。左列**只按内容自适应**（`wrapContentWidth`、无
 *    `fillMaxHeight`、无滚动），右列内容超长时才在自身 `verticalScroll` 里滚。
 * 3. 海报区高度由**外层 `Box(height=215dp)`** 决定（原版 FrameLayout 就是这么写的），
 *    卡片自身 `onMeasure` 是 120×214dp，215 = 214 + 1dp 余量；`AndroidView` 里不加任何尺寸 Modifier。
 *
 * 键盘尺寸恒定：缩放系数按「结果区存在」这一最坏情形一次性算出
 * （`(视口高 - 24 - 267) / 328`），**与 `hasInput` 无关**，所以清空输入不会让键盘突然变大。
 *
 * 键盘第一行与 `ref_docs/xd1.java` L27 一致：`(占位, <, >, ⌫, 键盘, 房子, A)`，之后 B-Z、0-9，
 * 共 42 格 = 6 行 × 7 列，最右列正是 A / H / O / V / 2 / 9（与 `topbar_search_page.png` 一致）。
 *
 * 焦点契约：
 * - 输入框 ↓ / 放大镜 ↓ → 键盘第一行；键盘内四向移动由 [FocusRequester] 精确控制；
 * - **键盘最右列按 → 不消费、返回 false**，交给 Compose 原生 2D 焦点搜索，
 *   按几何位置自动落到右侧「同一排」的第一个胶囊；
 * - **键盘最后一行按 ↓** → 有结果区时按所在列落到站点 Tab，无结果区时交还原生搜索；
 * - **胶囊按 ← / ↓ 都不消费**，交原生 2D 搜索：← 回键盘最右列；
 *   ↓ 先落同一区块的下一行（如「建议」第二排），再落下一个区块 / 站点 Tab；
 *   Tab ↓ → 首张海报；海报 ←→ 行内移动、↑ → 站点 Tab；确定键 → [HeroDetailActivity.start]。
 *
 * 外观 / 焦点态（严格按《焦点总结》的三态）：
 * - 背景 = 首页大图极小尺寸解码后放大（等价高斯模糊）+ 半透明黑遮罩，同原版
 *   `SearchActivityV2` 的 `heroBitmap` 模糊 + `0x33000000` 蒙层；
 * - **聚焦**（胶囊 / 站点 Tab）→ 实心红渐变色块 #E50914 → #F44336 → 玫瑰粉 + 纯白字，无描边；
 * - **失焦但仍选中、且焦点还在结果区** → 站点 Tab 挂红框描边 + 弱底，提示下方属于哪个站点；
 * - **焦点离开结果区**（回键盘 / 顶栏）→ 无红底无红框，选中态暂时休眠。
 */
class HeroSearchActivity : ComponentActivity() {

    /**
     * 【关键】与全站其它页面共用同一套「设计画布」。
     *
     * `ui/base/BaseActivity` 用 AutoSize 把屏幕固定成 960×540dp，再按「界面缩放」设置
     * 换算（`designW = 960 / factor`、`designH = 540 / factor`）；换算到 density 上，
     * 等价于 `density = 系统 density × factor`，正是 [Setting.wrapUiScale] 做的事。
     *
     * 本页是 Compose 的 [ComponentActivity]，不走 BaseActivity。以前没有套用这一步，
     * 于是用原始 density 渲染：用户把界面缩放调成 0.85 时，其它页面按 635dp 高的画布排版，
     * 本页只有 540dp —— 固定内容（24 + 键盘 328 + 结果区 267 = 619dp）装不下，
     * 键盘就被压到结果区下面，同时整体比其它页面大 1/0.85 ≈ 1.18 倍，看起来又粗又大。
     *
     * 套用之后：density 与全站一致 → dp 视口变回 635dp → 键盘完整显示、字号与其它页相同。
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Setting.wrapDisplay(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(AndroidColor.BLACK))
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        HeroImageLoader.install()
        // 首页带过来的那张大图：本页把它当磨砂底图（原版就是把首页 heroBitmap 模糊后当背景）。
        val backdropUrl = intent?.getStringExtra(EXTRA_BACKDROP)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                HeroSearchScreen(
                    backdropUrl = backdropUrl,
                    // 卡片点击只走这一个真实入口：不碰播放器，也不碰 catvod 数据层。
                    onOpenDetail = { title -> HeroDetailActivity.start(this, title) },
                )
            }
        }
    }

    companion object {

        private const val EXTRA_BACKDROP = "hero_backdrop"

        /** 统一入口：首页顶栏「搜索」调用，并把首页当前那张大图一起带过来做磨砂背景。 */
        fun start(context: Context, backdropUrl: String? = null) {
            context.startActivity(
                Intent(context, HeroSearchActivity::class.java).putExtra(EXTRA_BACKDROP, backdropUrl),
            )
        }
    }
}

@Composable
private fun HeroSearchScreen(
    backdropUrl: String?,
    onOpenDetail: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 输入内容用 TextFieldValue 维护：readOnly 只吃自己的虚拟键盘，光标由键盘 < / > 驱动。
    var field by remember { mutableStateOf(TextFieldValue("")) }
    var history by remember { mutableStateOf(HERO_HISTORY.toList()) }
    var selectedSite by remember { mutableIntStateOf(0) }
    var focusedSite by remember { mutableIntStateOf(0) }
    // 结果区是否持有焦点：输入被清空导致结果区整体消失时，用它把焦点安全送回输入框。
    var resultsFocused by remember { mutableStateOf(false) }

    val keyword = field.text
    val hasInput = keyword.trim().isNotEmpty()
    // 三态：无输入 → 「历史 + 热搜」；有输入 → 「历史 + 建议」。
    val wordTitle = if (hasInput) TITLE_SUGGEST else TITLE_HOT
    val words = remember(keyword) {
        if (hasInput) suggestionsFor(keyword) else HERO_HOT
    }

    val inputRequester = remember { FocusRequester() }
    val searchRequester = remember { FocusRequester() }
    val keyRequesters = remember { List(HERO_KEYS.size) { FocusRequester() } }
    val historyRequesters = remember(history) { List(history.size) { FocusRequester() } }
    val wordRequesters = remember(words) { List(words.size) { FocusRequester() } }
    val siteRequesters = remember { List(HERO_SITE_TABS.size) { FocusRequester() } }
    // 「右侧第一排第一个胶囊」的固定落点：键盘最右列 → 这里；站点 Tab ↑ → 也回这里。
    val rightFirstRequester = remember { FocusRequester() }
    // 海报卡是 Android View（HeroPosterCard），Compose 的 FocusRequester 摸不到它，
    // 用一张「下标 → View」的登记表跨区交接焦点，卡片自带的 2dp 红描边才会亮。
    val posterRegistry = remember { HeroPosterViewRegistry() }

    val siteState = rememberLazyListState()
    val posterState = rememberLazyListState()
    // 右列兜底滚动（对应原版 activity_search.xml L66-73 的 NestedScrollView）。
    // 只在内容超过可视高度时才会真正滚动；720dp 屏上默认静止。
    val rightScroll = rememberScrollState()

    // 焦点回到输入框/键盘 = 离开结果区，清掉标记，避免之后清空输入时把焦点从键盘抢走。
    fun focusInput() {
        resultsFocused = false
        requestFocus(inputRequester)
    }

    fun focusKeyboard(index: Int) {
        resultsFocused = false
        val target = index.coerceIn(FIRST_KEY_INDEX, HERO_KEYS.lastIndex)
        if (!requestFocus(keyRequesters, target)) requestFocus(keyRequesters, FIRST_KEY_INDEX)
    }

    /** 站点 Tab ↑ / 「第一排第一个胶囊」的固定落点：右侧历史第一条（没有历史就是热搜第一条）。 */
    fun focusRightFirstChip() {
        runCatching { rightFirstRequester.requestFocus() }
    }

    fun focusSite(index: Int) {
        if (!hasInput) {
            focusInput()
            return
        }
        val target = index.coerceIn(0, HERO_SITE_TABS.lastIndex)
        focusedSite = target
        scope.launch {
            siteState.animateScrollToItem(target)
            withFrameNanos { }
            requestFocus(siteRequesters, target)
        }
    }

    fun focusPoster(index: Int) {
        if (!hasInput) return
        val target = index.coerceIn(0, HERO_RESULTS.lastIndex)
        scope.launch {
            posterState.animateScrollToItem(if (target >= POSTER_SCROLL_ANCHOR) target else 0)
            withFrameNanos { }
            if (!posterRegistry.requestFocus(target)) {
                withFrameNanos { }
                posterRegistry.requestFocus(target)
            }
        }
    }

    fun setKeyword(text: String) {
        field = TextFieldValue(text = text, selection = TextRange(text.length))
    }

    /** 历史最多 9 条，重复词提到最前（结构同 `xp.java` 的 a=7 分支：纯字符串列表）。 */
    fun recordHistory(text: String) {
        val value = text.trim()
        if (value.isEmpty()) return
        history = (listOf(value) + history.filterNot { it == value }).take(HISTORY_LIMIT)
    }

    /** 与 `CustomKeyboard.onTextClick` L31-36 一致：总长不超过 19，插入到光标处。 */
    fun insert(text: String) {
        val value = field
        if (value.text.length >= KEYWORD_MAX_LENGTH) return
        val start = minOf(value.selection.start, value.selection.end).coerceIn(0, value.text.length)
        val end = maxOf(value.selection.start, value.selection.end).coerceIn(0, value.text.length)
        val merged = value.text.replaceRange(start, end, text)
        if (merged.length > KEYWORD_MAX_LENGTH) return
        field = TextFieldValue(text = merged, selection = TextRange(start + text.length))
    }

    fun backspace() {
        val value = field
        val start = minOf(value.selection.start, value.selection.end).coerceIn(0, value.text.length)
        val end = maxOf(value.selection.start, value.selection.end).coerceIn(0, value.text.length)
        if (start != end) {
            field = TextFieldValue(value.text.removeRange(start, end), TextRange(start))
            return
        }
        if (start == 0) return
        field = TextFieldValue(value.text.removeRange(start - 1, start), TextRange(start - 1))
    }

    fun moveCaret(delta: Int) {
        val caret = (field.selection.start + delta).coerceIn(0, field.text.length)
        field = field.copy(selection = TextRange(caret))
    }

    /** 顶部放大镜：记一条历史，焦点交给结果区（结果区随输入实时出现，没有额外搜索态）。 */
    fun confirmSearch() {
        val text = keyword.trim()
        if (text.isEmpty()) {
            focusInput()
            return
        }
        recordHistory(text)
        focusSite(0)
    }

    /**
     * 小房子键 → 全局接口源侧边栏（复用 [SourceSidebar]，绝不自造 Dialog）。
     *
     * 按需求用 [SourceSidebarMode.Multi]：带勾选圈与「全选 / 全不选」，
     * 确定键 = 切换勾选（不关闭），返回键关闭后由 `onClosed` 把焦点交还房子键。
     */
    fun openSourceSidebar() {
        SourceSidebar.show(
            context = context,
            entries = HERO_SOURCE_ENTRIES,
            mode = SourceSidebarMode.Multi,
            initialChecked = BooleanArray(HERO_SOURCE_ENTRIES.size) { true },
            onCheckedChanged = { },
            onSiteSelected = { },
            onClosed = {
                // Dialog 关闭后当前窗口尚未拿到焦点，延时一帧再 requestFocus 才不会被丢。
                scope.launch {
                    withFrameNanos { }
                    if (!requestFocus(keyRequesters, HOUSE_KEY_INDEX)) {
                        withFrameNanos { }
                        requestFocus(keyRequesters, HOUSE_KEY_INDEX)
                    }
                }
            },
        )
    }

    fun onKeyClick(key: HeroKey) {
        when (key) {
            is HeroKey.Text -> insert(key.value)

            is HeroKey.Action -> when (key.action) {
                HeroKeyAction.CursorLeft -> moveCaret(-1)
                HeroKeyAction.CursorRight -> moveCaret(1)
                HeroKeyAction.Backspace -> backspace()
                // 原版这个位置是「键盘布局切换键」；本页没有符号键盘页，改为清空输入。
                HeroKeyAction.Keyboard -> field = TextFieldValue("")
                HeroKeyAction.House -> openSourceSidebar()
            }

            // 占位格不聚焦，由 HeroKeyPlaceholder 单独渲染，走不到这里。
            else -> Unit
        }
    }

    fun onKeyMove(index: Int, key: Key): Boolean {
        val row = index / KEY_COLUMNS
        val column = index % KEY_COLUMNS
        return when (key) {
            Key.DirectionLeft -> {
                // 最左列（含第一行占位格右侧的 `<`）不再左移，就地消费。
                if (column > 0 && index - 1 > PLACEHOLDER_INDEX) requestFocus(keyRequesters, index - 1)
                true
            }

            Key.DirectionRight -> {
                // 最右列 = A / H / O / V / 2 / 9：**不拦截，返回 false 放行**。
                // Compose 原生 2D 焦点搜索会按几何位置自动选中右侧「对应排」的第一个胶囊，
                // 比手写「永远跳到第一排第一个」精确得多。
                if (column == KEY_COLUMNS - 1) {
                    false
                } else {
                    requestFocus(keyRequesters, index + 1)
                    true
                }
            }

            Key.DirectionUp -> {
                if (row > 0) requestFocus(keyRequesters, index - KEY_COLUMNS) else focusInput()
                true
            }

            Key.DirectionDown -> {
                when {
                    row < KEY_ROWS - 1 -> {
                        requestFocus(keyRequesters, index + KEY_COLUMNS)
                        true
                    }
                    // 最后一行（3–9）继续下按：有结果区就按「所在列」落到站点 Tab
                    // （Tab 不足 7 个时收敛到最后一项），没有结果区就放行给原生 2D 搜索。
                    hasInput -> {
                        focusSite(column.coerceAtMost(HERO_SITE_TABS.lastIndex))
                        true
                    }

                    else -> false
                }
            }

            else -> false
        }
    }

    // 进入页面默认焦点在输入框（原版 SearchActivity.onResume L612 keyword.requestFocus()）。
    LaunchedEffect(Unit) {
        withFrameNanos { }
        requestFocus(inputRequester)
    }

    // 输入被清空 → 结果区整块消失，此时把焦点从已销毁的结果区送回输入框。
    LaunchedEffect(hasInput) {
        if (!hasInput && resultsFocused) {
            resultsFocused = false
            withFrameNanos { }
            focusInput()
        }
    }

    // ------------------------------------------------------------------
    // 骨架 1：上下结构。根节点只有 Column：没有 Box 叠加、没有整体滚动。
    //
    // 外面这层 BoxWithConstraints 只干一件事：量出视口高度，算出键盘的**恒定**缩放系数。
    //   上半区可用高度 = 视口高 - 顶部 24dp - 结果区固定 267dp
    // 注意式子里没有 hasInput —— 有结果、没结果都按同一基准缩放，
    // 所以键盘尺寸恒定，不会再出现「清空输入键盘变大、一搜索键盘变小」的跳变。
    // ------------------------------------------------------------------
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val keyboardBaseDensity = LocalDensity.current
        // 是否让出下半区只影响「空出来多少空白」，不影响键盘大小：永远用最坏情形做基准。
        // 下界取 0 而不是「可读性下限」：宁可极小，也绝不允许键盘高度超过上半区而盖住海报。
        val keyboardBandHeight = maxHeight - ROOT_PADDING_TOP - RESULT_BAND_HEIGHT
        val keyboardScale = (keyboardBandHeight / KEYBOARD_NATURAL_HEIGHT).coerceIn(0f, 1f)

        // 背景层：首页大图 → 极小尺寸解码（等价高斯模糊）→ 半透明黑遮罩，
        // 原版 SearchActivityV2 的 U1() + setBackgroundColor(0x33000000) 就是这个结构。
        HeroSearchBackground(backdropUrl)

        Column(
            modifier = Modifier
                .fillMaxSize()
                // 【标准 1】外层不放 start/end padding：横向的 24dp 缓冲区交给各子节点自己处理
                // （左列前面一个 24dp Spacer、两条 LazyRow 用 contentPadding 补两端），
                // 这样 LazyRow 的滚动区就是整屏宽度，第一项（「全部」）左右都有 24dp 可裁缓冲。
                .padding(top = ROOT_PADDING_TOP),
        ) {
            // 上半区：weight(1f) 吃掉下半区固定高度之外的全部空间。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                // 左列 24dp 左边距：放在缩放子树**外面**，缩放时才有和两条 LazyRow 一致的 24dp。
                Spacer(modifier = Modifier.width(ROOT_PADDING_H))

                // 左列：按 keyboardScale 等比缩放。
                //   keyboardScale == 1f → 完全等于原版「键 40dp、间距 8dp」，一个像素都不动；
                //   视口不够高        → 整棵键盘子树按比例缩小，6 行 + 输入行永远完整，绝不压到下半区。
                // 走 LocalDensity 而不是 graphicsLayer：文字按新密度重新排版，不会发虚；
                // 只作用于键盘子树，右侧胶囊与海报卡（HeroPosterCard 按 Activity density 自绘
                // 120×214dp）完全不受影响。
                CompositionLocalProvider(
                    LocalDensity provides Density(
                        keyboardBaseDensity.density * keyboardScale,
                        keyboardBaseDensity.fontScale,
                    ),
                ) {
                    KeyboardSection(
                        field = field,
                        inputRequester = inputRequester,
                        searchRequester = searchRequester,
                        keyRequesters = keyRequesters,
                        onInputDown = { focusKeyboard(FIRST_KEY_INDEX) },
                        onSearch = { confirmSearch() },
                        onSearchDown = { focusKeyboard(FIRST_KEY_INDEX) },
                        onSearchLeft = { focusInput() },
                        onKeyClick = { key -> onKeyClick(key) },
                        onKeyMove = { index, key -> onKeyMove(index, key) },
                    )
                }

                Spacer(modifier = Modifier.width(LEFT_COLUMN_MARGIN_END))

                // 右列：历史 / 热搜（无输入）或 建议（有输入）。
                // 右端 24dp 对应 activity_search.xml L11 paddingEnd="24dp"：外层已放开 padding，
                // 这里自己补回来，否则最后一枚胶囊会贴死在屏幕右边缘。
                RightContentColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(end = ROOT_PADDING_H),
                    scroll = rightScroll,
                    history = history,
                    words = words,
                    wordTitle = wordTitle,
                    hasInput = hasInput,
                    historyRequesters = historyRequesters,
                    wordRequesters = wordRequesters,
                    rightFirstRequester = rightFirstRequester,
                    onWordClick = { text ->
                        setKeyword(text)
                        recordHistory(text)
                    },
                    // 胶囊 ↓ 到底时的兜底落点：**回到刚才高亮的站点 Tab**（《焦点总结》第 4 条），
                    // 而不是盲目落到第一个。
                    onDownToResults = { focusSite(focusedSite) },
                )
            }

            // 下半区：【标准 3】独立 Column，只按内容量高（12dp 间距 + 32dp Tab 行 + 8dp + 215dp 海报 Box），
            // 不吃 weight、不 fillMaxSize，所以永远挤压不到上半区键盘。
            if (hasInput) {
                ResultSection(
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight()
                        .padding(top = RESULT_SECTION_TOP_GAP),
                    siteState = siteState,
                    posterState = posterState,
                    selectedSite = selectedSite,
                    resultModuleFocused = resultsFocused,
                    siteRequesters = siteRequesters,
                    posterRegistry = posterRegistry,
                    onSiteFocused = { index ->
                        focusedSite = index
                        resultsFocused = true
                    },
                    onSiteConfirm = { index ->
                        selectedSite = index
                        focusedSite = index
                    },
                    onSiteUp = { focusRightFirstChip() },
                    onSiteDown = { focusPoster(0) },
                    onSiteLeft = { index -> focusSite(index - 1) },
                    onSiteRight = { index -> focusSite(index + 1) },
                    onPosterFocused = { resultsFocused = true },
                    onPosterOpen = { title ->
                        recordHistory(keyword)
                        onOpenDetail(title)
                    },
                    onPosterUp = { focusSite(focusedSite) },
                    onPosterLeft = { index -> if (index > 0) focusPoster(index - 1) },
                    onPosterRight = { index ->
                        if (index < HERO_RESULTS.lastIndex) focusPoster(index + 1)
                    },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 背景层：首页大图 → 磨砂 → 半透明黑遮罩
// ---------------------------------------------------------------------------

/**
 * 本页背景，结构对齐原版 `SearchActivityV2`：
 *
 * ```java
 * view.setBackgroundResource(R.drawable.bg_hero_mask_v);   // 纵向压黑
 * view2.setBackgroundColor(855638016);                    // 0x33000000，20% 黑
 * U1();  // 把首页传进来的 heroBitmap 丢到后台线程模糊，再 setImageBitmap 铺满
 * ```
 *
 * 结论：**原版是「高斯模糊的首页大图 + 半透明黑遮罩」，不是纯粹的半透明蒙版**——
 * 图2 里能看见海报的色块轮廓却完全看不出细节，正是模糊后的结果；
 * 如果只是半透明蒙版，下面的海报和文字会是清晰的。
 *
 * 这里做**真正的模糊**，不依赖 API 级别：
 * 1. Coil 把图解码到 [BACKDROP_SAMPLE_WIDTH]×[BACKDROP_SAMPLE_HEIGHT]（160×90）；
 * 2. [HeroImageLoader.blurredBackdrop] 在这张小图上跑分离式盒式模糊（两趟 ≈ 高斯）；
 * 3. 放大铺满整屏时双线性插值再做一次平滑。
 *
 * 之前只做「解码到 48×27 再拉伸」：等价于 40 倍最近邻放大，横向残影会变成一条条硬色带
 * （图3 就是那个样子），既不像模糊也压不住背景，反而更干扰看字。
 * 纯 Kotlin 模糊在所有 Android 版本上表现一致，不需要 `RenderEffect`（API 31+）
 * 或已废弃的 RenderScript，绘制成本仍然只是一张小图。
 *
 * 没有大图时（例如从别处直接打开本页）退回原来的纯色渐变。
 */
@Composable
private fun HeroSearchBackground(backdropUrl: String?) {
    val context = LocalContext.current
    // 模糊底图是异步算出来的：算完之前先显示纯黑，避免闪一下未模糊的原图。
    var backdrop by remember(backdropUrl) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(backdropUrl) {
        backdrop = null
        if (backdropUrl.isNullOrBlank()) return@LaunchedEffect
        backdrop = HeroImageLoader.blurredBackdrop(
            context = context,
            url = backdropUrl,
            width = BACKDROP_SAMPLE_WIDTH,
            height = BACKDROP_SAMPLE_HEIGHT,
            blurRadius = BACKDROP_BLUR_RADIUS,
        )?.asImageBitmap()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        val bitmap = backdrop
        if (bitmap == null) {
            Box(modifier = Modifier.fillMaxSize().background(FALLBACK_BACKGROUND))
        } else {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // ① 整体压黑：原版 0x33000000（20%）在真机白色字幕上根本不够，
            //    这里用 60% —— 保留色块与氛围，但压在底下的内容不会跟文字抢注意力（图2 的观感）。
            Box(modifier = Modifier.fillMaxSize().background(BACKDROP_SCRIM))
            // ② 底部压黑：海报行下方是白字标题，必须有深底托住。
            Box(modifier = Modifier.fillMaxSize().background(BACKDROP_BOTTOM_FADE))
        }
    }
}

// ---------------------------------------------------------------------------
// 骨架 2：左侧键盘区（328dp = 7×40 + 6×8）—— 实现已抽到 ui/hero/HeroKeyboard.kt，
// 搜索页与 TMDB 匹配弹窗共用同一个 internal KeyboardSection，本文件不再保留第二份。
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// 右侧：历史 + 热搜/建议
// ---------------------------------------------------------------------------

/**
 * 右列：历史 + 热搜（无输入）/ 建议（有输入）。
 *
 * `verticalScroll` 只是兜底（对应原版 `activity_search.xml` L66-73 的 `NestedScrollView`）：
 * 720dp 屏上两块内容约 200dp，远小于上半区可视高度，正常状态静止不动；
 * 只有极端字体放大或历史胶囊极多时才会真正滚动。
 *
 * 站点 Tab 与海报行在**下半区**（[ResultSection]），不在这里，所以右列滚动永远碰不到它们。
 */
@Composable
private fun RightContentColumn(
    modifier: Modifier = Modifier,
    scroll: ScrollState,
    history: List<String>,
    words: List<String>,
    wordTitle: String,
    hasInput: Boolean,
    historyRequesters: List<FocusRequester>,
    wordRequesters: List<FocusRequester>,
    rightFirstRequester: FocusRequester,
    onWordClick: (String) -> Unit,
    onDownToResults: () -> Unit,
) {
    Column(modifier = modifier.verticalScroll(scroll)) {
        // a. 历史
        HeroWordSection(
            title = TITLE_HISTORY,
            words = history,
            requesters = historyRequesters,
            // 历史非空时，「右侧第一排第一个」就是历史第一条。
            firstRowRequester = if (history.isNotEmpty()) rightFirstRequester else null,
            isFirstRowSection = history.isNotEmpty(),
            resultsVisible = hasInput,
            onWordClick = onWordClick,
            onDownToResults = onDownToResults,
        )

        // b. 建议（有输入）/ 热搜（无输入）
        if (words.isNotEmpty()) {
            Spacer(modifier = Modifier.height(RIGHT_SECTION_GAP))
            HeroWordSection(
                title = wordTitle,
                words = words,
                requesters = wordRequesters,
                firstRowRequester = if (history.isEmpty()) rightFirstRequester else null,
                isFirstRowSection = history.isEmpty(),
                resultsVisible = hasInput,
                onWordClick = onWordClick,
                onDownToResults = onDownToResults,
            )
        }
    }
}

/**
 * 区块标题：4dp × 16dp 红条（`shape_red_bar.xml`：#e50914、2dp 圆角）+ 20sp 粗体白字，
 * 条与文字间距 10dp，整行下方 8dp（`activity_search.xml` L92/L109）。
 */
@Composable
private fun HeroSectionTitle(title: String) {
    Row(
        modifier = Modifier.padding(bottom = SECTION_TITLE_BOTTOM_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(SECTION_BAR_WIDTH)
                .height(SECTION_BAR_HEIGHT)
                .background(ACCENT_RED, SECTION_BAR_SHAPE),
        )
        Spacer(modifier = Modifier.width(SECTION_BAR_MARGIN_END))
        Text(
            text = title,
            color = Color.White,
            fontSize = SECTION_TITLE_SP.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/**
 * 历史 / 热搜 / 建议区：`FlowRow`，胶囊 16sp、内边距 6dp×2dp（紧贴文字的小药丸，同
 * `shape_chip_round_normal.xml`）、12dp 圆角、外边距 6dp。
 * 整体左移 6dp 抵消首尾外边距，对应 XML 的 `layout_marginStart="-6dp"`。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeroWordSection(
    title: String,
    words: List<String>,
    requesters: List<FocusRequester>,
    // 只有「第一排第一个胶囊」拿得到它：键盘最右列直接打到这里。
    firstRowRequester: FocusRequester?,
    isFirstRowSection: Boolean,
    resultsVisible: Boolean,
    onWordClick: (String) -> Unit,
    onDownToResults: () -> Unit,
) {
    if (words.isEmpty()) return

    Column(modifier = Modifier.fillMaxWidth()) {
        HeroSectionTitle(title)
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .offset(x = -CHIP_MARGIN, y = -CHIP_MARGIN),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            words.forEachIndexed { index, word ->
                val isFirstChip = isFirstRowSection && index == 0
                HeroWordChip(
                    text = word,
                    requester = if (isFirstChip) {
                        firstRowRequester ?: requesters.getOrNull(index)
                    } else {
                        requesters.getOrNull(index)
                    },
                    resultsVisible = resultsVisible,
                    onDownToResults = onDownToResults,
                    onClick = { onWordClick(word) },
                    modifier = Modifier.padding(CHIP_MARGIN),
                )
            }
        }
    }
}

/**
 * 胶囊标签：**不铺纯黑**，底色沿用 `selector_item_round.xml` 的半透明黑（`#33000000`）；
 * 焦点态是原版 `shape_item_round_focused` 的**红色渐变色块**
 * （#E50914 → #F44336 → 玫瑰粉），整块实心、无描边。
 *
 * 底色药丸紧贴文字：横向 6dp、纵向 2dp（`shape_chip_round_normal.xml` 只声明了左右 6dp），
 * 不做大块 padding —— 大块 padding 会让半透明底看起来比文字宽一圈。
 */
@Composable
private fun HeroWordChip(
    text: String,
    requester: FocusRequester?,
    resultsVisible: Boolean,
    onDownToResults: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val isFocused by interaction.collectIsFocusedAsState()
    var focusFlag by remember { mutableStateOf(false) }
    val focused = isFocused || focusFlag
    val focusManager = LocalFocusManager.current

    Box(
        modifier = modifier
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .clip(CHIP_SHAPE)
            // 焦点态 = **红色渐变色块**（左深右浅：#E50914 → #F44336 → 玫瑰粉），
            // 整块实心变红、无描边 —— 与原版截图里「开心锤锤」的焦点样式一致。
            .background(
                brush = if (focused) HeroFocusBrush else SolidColor(CHIP_BG_NORMAL),
                shape = CHIP_SHAPE,
            )
            .onFocusChanged { focusFlag = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionCenter, Key.Enter -> {
                        onClick()
                        true
                    }

                    // ←/→ 一律不拦截：交给 Compose 原生 2D 焦点搜索，
                    // 它会按几何位置自动把焦点交给键盘最右列（高度最接近的那一排）。
                    //
                    // ↓ 同样**先走原生 2D 搜索**：它会先落到同一区块的下一行，
                    // 已经是最后一行时再落到下一个区块/站点 Tab。
                    // 以前的写法直接跳到站点 Tab，于是「建议」第二行的胶囊永远进不去。
                    Key.DirectionDown -> {
                        if (focusManager.moveFocus(FocusDirection.Down)) {
                            true
                        } else if (resultsVisible) {
                            // 原生搜索不到目标（例如区块刚好被清空）：兜底落到站点 Tab。
                            onDownToResults()
                            true
                        } else {
                            false
                        }
                    }

                    else -> false
                }
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = CHIP_PADDING_H, vertical = CHIP_PADDING_V),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = CHIP_TEXT_SP.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------------------
// 骨架 3：下半区（站点 Tab + 海报行）
// ---------------------------------------------------------------------------

/**
 * 下半区结果区（对应 `activity_search.xml` L145-207 的 `resultSection`）：
 * 独立 `Column(fillMaxWidth + wrapContentHeight + padding(top = 12dp))`，高度只由内容决定，
 * 不吃 weight、不 fillMaxSize，**永远挤压不到上半区键盘**。
 * ```
 * Column(wrapContentHeight)                       // L145-151：match_parent 宽 + wrap 高 + marginTop 12
 *   ├─ LazyRow(height = 32dp)                     // L152-161：siteRecycler，32dp 行高
 *   ├─ Spacer(8dp)                                // Tab 行与海报区之间 8dp
 *   └─ Box(fillMaxWidth + height = 215dp)         // L162-167：FrameLayout height="215dp"
 *        └─ LazyRow(fillMaxSize)                  // L168-175：recycler match_parent
 * ```
 *
 * 【标准 1】两条 `LazyRow` 的 `contentPadding` 都是 `start = 24dp, end = 24dp`。
 * Compose 的 `LazyRow` 会按**自身外框**裁剪超出边界的子项，而外层根节点现在没有横向 padding，
 * LazyRow 的滚动区等于整屏宽度；首项左侧 24dp 的 contentPadding 就是那块裁剪缓冲区，
 * 「全部」在焦点放大 1.08 时也不会被切边。等价于原版 `clipChildren=false +
 * clipToPadding=false + 根 paddingStart=24dp` 的组合效果（L6-7 / L10-11 / L155-156 / L170-171）。
 *
 * 海报区**【标准 3】高度 215dp 由外层 `Box` 写死**（原版 FrameLayout 就是这么写的），
 * 卡片自身 `onMeasure` 是 120×214dp，215 = 214 + 1dp 余量，绝不会被切；
 * `AndroidView` 里**绝不加任何尺寸 Modifier**。
 */
@Composable
private fun ResultSection(
    modifier: Modifier = Modifier,
    siteState: LazyListState,
    posterState: LazyListState,
    selectedSite: Int,
    // 结果区（Tab 行 / 海报）当前是否持有焦点 —— 站点 Tab 三态里的第 2 态要用。
    resultModuleFocused: Boolean,
    siteRequesters: List<FocusRequester>,
    posterRegistry: HeroPosterViewRegistry,
    onSiteFocused: (Int) -> Unit,
    onSiteConfirm: (Int) -> Unit,
    onSiteUp: () -> Unit,
    onSiteDown: () -> Unit,
    onSiteLeft: (Int) -> Unit,
    onSiteRight: (Int) -> Unit,
    onPosterFocused: () -> Unit,
    onPosterOpen: (String) -> Unit,
    onPosterUp: (Int) -> Unit,
    onPosterLeft: (Int) -> Unit,
    onPosterRight: (Int) -> Unit,
) {
    Column(modifier = modifier) {
        // 站点 Tab：行高 36dp（原版 siteRecycler wrap_content = 36dp），胶囊本体 26dp 垂直居中。
        LazyRow(
            state = siteState,
            modifier = Modifier
                .fillMaxWidth()
                .height(SITE_ROW_HEIGHT),
            contentPadding = PaddingValues(
                start = RESULT_CONTENT_PADDING,
                end = RESULT_CONTENT_PADDING,
            ),
            horizontalArrangement = Arrangement.spacedBy(SITE_ITEM_SPACING),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            itemsIndexed(items = HERO_SITE_TABS) { index, label ->
                HeroSiteTab(
                    label = label,
                    selected = index == selectedSite,
                    resultModuleFocused = resultModuleFocused,
                    requester = siteRequesters[index],
                    onFocused = { onSiteFocused(index) },
                    onConfirm = { onSiteConfirm(index) },
                    onMoveUp = onSiteUp,
                    onMoveDown = onSiteDown,
                    onMoveLeft = { onSiteLeft(index) },
                    onMoveRight = { onSiteRight(index) },
                )
            }
        }

        // Tab 行与海报 Box 之间 8dp。
        Spacer(modifier = Modifier.height(SITE_POSTER_GAP))

        // 【标准 3】海报容器：高度 215dp（`activity_search.xml` L162-167 的 FrameLayout
        // height="215dp"），内部 LazyRow 填满它。卡片自身 onMeasure 是 120×214dp，
        // 215 = 214 + 1dp 余量，横向滚动交给 LazyRow。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(POSTER_BOX_HEIGHT),
        ) {
            LazyRow(
                state = posterState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = RESULT_CONTENT_PADDING,
                    end = RESULT_CONTENT_PADDING,
                ),
                horizontalArrangement = Arrangement.spacedBy(POSTER_ITEM_SPACING),
            ) {
                itemsIndexed(items = HERO_RESULTS) { index, item ->
                    // 【红线】HeroPosterCard 自己 onMeasure 死锁 120×214dp，
                    // 这里绝不加 Modifier.size()/width()/height()，直接放进去。
                    AndroidView(
                        factory = { context ->
                            HeroPosterCard(context).also { card -> posterRegistry.bind(index, card) }
                        },
                        update = { card ->
                            // 只在值变化时写，避免每次重组都触发 HeroPosterCard 内部的图片重载。
                            if (card.title != item.title) card.title = item.title
                            if (card.topStartTag != item.topTag) card.topStartTag = item.topTag
                            if (card.bottomStartTag != item.bottomTag) card.bottomStartTag = item.bottomTag
                            if (card.imageUrl != item.imageUrl) card.imageUrl = item.imageUrl

                            card.setOnFocusChangeListener { _, hasFocus ->
                                if (hasFocus) onPosterFocused()
                            }
                            card.setOnClickListener { onPosterOpen(item.title) }
                            card.setOnKeyListener { _, keyCode, event ->
                                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                                when (keyCode) {
                                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                                        onPosterLeft(index)
                                        true
                                    }

                                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                        onPosterRight(index)
                                        true
                                    }

                                    KeyEvent.KEYCODE_DPAD_UP -> {
                                        onPosterUp(index)
                                        true
                                    }

                                    else -> false
                                }
                            }
                        },
                        onRelease = { card -> posterRegistry.unbind(index, card) },
                    )
                }
            }
        }
    }
}

/**
 * 站点胶囊：26dp 高、16sp、13dp 圆角（`adapter_type.xml` L3/L10，字号按需求统一到 16sp）；
 * 选中放大 1.08 / 140ms（`dz2.java` L45-47）。
 *
 * 三态严格按《焦点总结》，与首页分类 Tab 同一套规则：
 * 1. **聚焦**（不论是否选中）→ 实心红渐变色块 + 纯白字，无描边（图1 的焦点样式）；
 * 2. **失焦 + 当前站点 + 焦点还在下方结果区** → 红框描边 + 弱底，
 *    提示「下方这批海报属于哪个站点」；
 * 3. **焦点离开结果区**（回键盘 / 顶栏）→ 半透明普通底，无红底无红框，选中态暂时休眠。
 *
 * 第 2 态必须依赖 [resultModuleFocused]：只看 `selected` 会在焦点回到键盘时
 * 仍然挂一圈红框，正是《焦点总结》第 3 条要消掉的「多出来的红框」。
 */
@Composable
private fun HeroSiteTab(
    label: String,
    selected: Boolean,
    resultModuleFocused: Boolean,
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
    var focusFlag by remember { mutableStateOf(false) }
    val focused = isFocused || focusFlag
    // 三态里的第 2 态：失焦、仍是当前站点、且焦点还在结果区。
    val selectedRing = selected && !focused && resultModuleFocused
    val scale by animateFloatAsState(
        targetValue = if (selected) SITE_SELECTED_SCALE else 1f,
        animationSpec = tween(durationMillis = SITE_SCALE_DURATION_MS),
        label = "siteTabScale",
    )

    Box(
        modifier = Modifier
            .scale(scale)
            .height(SITE_TAB_HEIGHT)
            .focusRequester(requester)
            .clip(SITE_TAB_SHAPE)
            // 焦点态 = 红色渐变色块，和胶囊共用同一支 HeroFocusBrush，全页焦点色一致。
            .background(
                brush = if (focused) HeroFocusBrush else SolidColor(CHIP_BG_NORMAL),
                shape = SITE_TAB_SHAPE,
            )
            // 第 2 态：只有「失焦 + 选中 + 焦点仍在结果区」才挂红框。
            .then(
                if (selectedRing) {
                    Modifier.border(CHIP_STROKE, FOCUS_RED, SITE_TAB_SHAPE)
                } else {
                    Modifier
                },
            )
            .onFocusChanged {
                focusFlag = it.isFocused
                if (it.isFocused) onFocused()
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionCenter, Key.Enter -> {
                        onConfirm()
                        true
                    }

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
            // 站点 Tab 的左右内边距**独立于胶囊**：胶囊这次收窄到 6dp，Tab 保持原尺寸不变。
            .padding(horizontal = SITE_TAB_PADDING_H),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = SITE_TAB_SP.sp,
            fontWeight = if (focused || selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------------------
// 假数据（数据层未接，先验证结构与焦点；结构同 xp.java 的 a=7 分支：纯字符串列表）
// ---------------------------------------------------------------------------

private data class HeroSearchResult(
    val title: String,
    val topTag: String,
    val bottomTag: String,
    val imageUrl: String,
)

private val HERO_HISTORY: List<String> = listOf(
    "凡人修仙传",
    "http://127.0.0.1:8877/proxy?do=appSignature",
)

private val HERO_HOT: List<String> = listOf(
    "开心锤锤",
    "战狼2",
    "小猪佩奇全集",
    "抓特务",
    "新猫和老鼠 第四季",
    "汪汪队立大功全集",
    "熊出没全集",
)

/** 建议词：先走精确假数据表，其余关键字从热搜 / 历史 / 结果标题做包含匹配。 */
private val HERO_SUGGESTIONS: Map<String, List<String>> = mapOf(
    "凡人" to listOf("凡人修仙传第一季", "凡人修仙传第二季", "凡人修仙传国漫"),
)

private val HERO_SITE_TABS: List<String> = listOf("全部", "指南", "花卷", "木偶", "觅盘", "玩偶", "盘搜")

private val HERO_RESULTS: List<HeroSearchResult> = listOf(
    HeroSearchResult("凡人修仙传", "4K HDR", "更新至197集", "https://picsum.photos/seed/hero-search-0/400/600"),
    HeroSearchResult("凡人修仙传第一季", "独播", "全30集", "https://picsum.photos/seed/hero-search-1/400/600"),
    HeroSearchResult("凡人修仙传第二季", "1080P", "全24集", "https://picsum.photos/seed/hero-search-2/400/600"),
    HeroSearchResult("凡人修仙传国漫", "会员", "全120集", "https://picsum.photos/seed/hero-search-3/400/600"),
    HeroSearchResult("凡人修仙传第三季", "HDR", "更新至 12 集", "https://picsum.photos/seed/hero-search-4/400/600"),
    HeroSearchResult("战狼2", "4K", "电影", "https://picsum.photos/seed/hero-search-5/400/600"),
    HeroSearchResult("开心锤锤", "热播", "更新至 300 集", "https://picsum.photos/seed/hero-search-6/400/600"),
)

private fun suggestionsFor(keyword: String): List<String> {
    if (keyword.isBlank()) return emptyList()
    HERO_SUGGESTIONS[keyword]?.let { return it }
    return (HERO_HOT + HERO_HISTORY + HERO_RESULTS.map { it.title })
        .filter { it.contains(keyword, ignoreCase = true) }
        .distinct()
}

// ---------------------------------------------------------------------------
// 尺寸常量（每一条都能在 ref_docs 里找到出处，见对应注释）
// ---------------------------------------------------------------------------

// 背景：首页大图先解码到 160×90，再做盒式模糊（两趟 ≈ 高斯），最后放大铺满。
// 160×90 足够保留「大面积色块 / 明暗分布」，又小到模糊一帧就能算完（见 HeroSearchBackground）。
private const val BACKDROP_SAMPLE_WIDTH = 160
private const val BACKDROP_SAMPLE_HEIGHT = 90
// 模糊半径（单位 = 采样图上的像素）。8 × (1920/160 ≈ 12) ≈ 屏幕上 96px 的模糊，符合图2 的观感。
private const val BACKDROP_BLUR_RADIUS = 8
private val BACKDROP_SCRIM = Color(0x99000000)      // 60% 黑：压住背景细节，只留氛围色块
private val BACKDROP_BOTTOM_FADE = Brush.verticalGradient(
    0.0f to Color.Transparent,
    0.55f to Color.Black.copy(alpha = 0.35f),
    1.0f to Color.Black.copy(alpha = 0.92f),
)
// 没有大图时的兜底：原来的深蓝黑渐变。
private val FALLBACK_BACKGROUND = Brush.verticalGradient(
    0.0f to Color(0xFF0B1119),
    0.55f to Color(0xFF101823),
    1.0f to Color.Black,
)

// 根容器：activity_search.xml L4 → paddingTop 24dp；横向 24dp 不再放在根上（标准 1），
// 而是拆给各子节点：键盘自己 padding(start=24)、右列自己 padding(end=24)、
// 两条 LazyRow 用 contentPadding(24, 24) —— 只有 LazyRow 自己的滚动区够宽，焦点放大才不被裁。
private val ROOT_PADDING_H = 24.dp
private val ROOT_PADDING_TOP = 24.dp

// 键盘 / 输入框的全部尺寸常量已抽到 `ui/hero/HeroKeyboard.kt`（internal），
// 搜索页与 TMDB 匹配弹窗共用同一份；这里只留本页布局自己的边距与缩放基准。
private val LEFT_COLUMN_MARGIN_END = 24.dp          // activity_search.xml L21

// 左列内容自然高度 = 输入行 40 + 8(activity_search.xml L61 marginTop) + 6×40 + 5×8 = 328dp，
// 宽度 = 7×40 + 6×8 = 328dp；两者都由内容撑出（wrapContentWidth），不再写死、不再 fillMaxHeight。
// 上半区可用高度不足这个值时，按 maxHeight / 该值 等比缩小整棵键盘子树（见 HeroSearchScreen）。
private val KEYBOARD_NATURAL_HEIGHT = INPUT_HEIGHT + KEY_SPACING + KEY_SIZE * 6 + KEY_SPACING * 5

// 焦点红（胶囊 / 站点 Tab 描边共用；键盘红底同色，已随键盘抽到 HeroKeyboard.kt）。
private val FOCUS_RED = Color(0xFFFF0000)

// 区块标题：20sp 粗体白 + 4×20dp 红条（shape_red_bar.xml：#e50914、2dp 圆角）+ 10dp 间距。
private const val SECTION_TITLE_SP = 20f
private val SECTION_BAR_WIDTH = 4.dp
private val SECTION_BAR_HEIGHT = 20.dp
private val SECTION_BAR_MARGIN_END = 10.dp
private val SECTION_BAR_SHAPE = RoundedCornerShape(2.dp)
private val SECTION_TITLE_BOTTOM_GAP = 8.dp

// 胶囊：16sp；内边距严格照 `adapter_search_word.xml` + `shape_chip_round_normal.xml`
// （shape 只声明 paddingStart/End = 6dp、上下 0dp），所以**横向 6dp、纵向只留 2dp 呼吸**，
// 底色就是紧贴文字的一圈小药丸；外边距 6dp 与原版 layout_margin 一致。
private const val CHIP_TEXT_SP = 16f
private val CHIP_PADDING_H = 6.dp
private val CHIP_PADDING_V = 2.dp
private val CHIP_MARGIN = 6.dp
private val CHIP_SHAPE = RoundedCornerShape(12.dp)
private val CHIP_STROKE = 1.5.dp
private val CHIP_BG_NORMAL = Color(0x33000000)      // black_20（半透明，不是纯黑）

// 站点 Tab：16sp（与胶囊标签同字号）+ 行高 32dp（activity_search.xml L152-161）+ 间距 8dp。
private const val SITE_TAB_SP = 16f
private val SITE_TAB_HEIGHT = 26.dp
private val SITE_TAB_SHAPE = RoundedCornerShape(13.dp)
// Tab 自己的左右内边距（12dp），不跟随胶囊收窄，保持图1里的块宽。
private val SITE_TAB_PADDING_H = 12.dp
private const val SITE_SELECTED_SCALE = 1.08f
private const val SITE_SCALE_DURATION_MS = 140
private val SITE_ROW_HEIGHT = 32.dp
private val SITE_ITEM_SPACING = 8.dp
// Tab 行与海报 Box 之间的 8dp。
private val SITE_POSTER_GAP = 8.dp
// 两条 LazyRow 的内容内边距：start/end 都留 24dp（标准 1）。
// 外层根节点已不再有横向 padding，LazyRow 的滚动区就是整屏宽度，
// 首项左侧的 24dp 就是焦点放大 1.08 时的裁剪缓冲区，「全部」不会被切边。
private val RESULT_CONTENT_PADDING = 24.dp
private val RESULT_SECTION_TOP_GAP = 12.dp         // activity_search.xml L151
private val RIGHT_SECTION_GAP = 16.dp              // 「历史」与「建议 / 热搜」之间的紧凑间隔

// 海报：外层 Box 高 215dp（activity_search.xml L162-167 FrameLayout height="215dp"），
// 卡片自身 onMeasure = 120×214dp，卡间距 12dp。
private val POSTER_BOX_HEIGHT = 215.dp
private val POSTER_ITEM_SPACING = 12.dp
private const val POSTER_SCROLL_ANCHOR = 4

// 下半区（结果区）固定占用的总高度 = 12(顶部间距) + 32(Tab 行) + 8(Tab/海报间距) + 215(海报) = 267dp。
// 键盘缩放系数**永远按「结果区存在」这一最坏情形**计算，所以有无搜索结果时按键大小完全一致，
// 不会出现「没结果时大、有结果时小」的跳变。
private val RESULT_BAND_HEIGHT = RESULT_SECTION_TOP_GAP + SITE_ROW_HEIGHT + SITE_POSTER_GAP + POSTER_BOX_HEIGHT

// 红色 @color/red = #E50914 与键盘常量一起抽到 HeroKeyboard.kt（internal ACCENT_RED）。

// 焦点红块统一用全局 [HeroFocusBrush]（HeroCommonUi）：#E50914 → #F44336 → 玫瑰粉，
// 与首页分类 Tab、播放线路、换源弹窗站点标签共用同一支渐变，不再各自维护一份色标。

// 历史最多 9 条（输入上限 19 字随键盘常量一起抽到 HeroKeyboard.kt）。
private const val HISTORY_LIMIT = 9

// 区块标题文案（与 topbar_search_page.png 一致）。
private const val TITLE_HISTORY = "历史"
private const val TITLE_HOT = "热搜"
private const val TITLE_SUGGEST = "建议"
