package com.fongmi.android.tv.ui.hero

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fongmi.android.tv.R
import com.fongmi.android.tv.bean.Episode
import com.fongmi.android.tv.bean.Flag
import com.fongmi.android.tv.ui.activity.isActionCommand
import com.fongmi.android.tv.utils.Notify

/**
 * 详情页选集弹窗 —— **100% 纯 View**（XML + RecyclerView），彻底替换 Compose 版 `HeroPlayDialog`。
 *
 * 版式（activity_episode_view.xml，逐行对照 Compose L2593-2798）：
 * 全屏遮罩 #8014171F → 面板宽 82% / 16dp 圆角 / 18dp 内边距 →
 * 「播放线路」28dp 线路栏 → 「选集」28dp 控制栏（列数 / 排序 / 分页）→ 固定 4 排网格。
 *
 * 卡片宽度直接由网格的左右 padding 决定：面板 18dp 内边距之外，RecyclerView 再留 24dp，
 * 少列 2 列平分剩余宽度、横向撑满视觉空间；卡片高度按本轮红线固定 38dp，
 * 4 排网格 = 4 × 38 + 3 × 8 = 176dp，背景底板严格等于卡片高度。
 *
 * 逻辑是 Compose 版的**逐行平移**，不是重写：
 *
 * | Compose | 这里 |
 * |---|---|
 * | `lineEpisodes` / `hasPlayableEpisode` | 同名属性，构造时算一次 |
 * | `ascending` / `ordered` / `columns` / `pageSize` / `pageCount` / `pageItems` | [ascending] / [ordered] / [columns] / [pageSize] / [pageCount] / [pageItems] |
 * | `watchedIndex` / `initialIndex` | [initState] 里同一套镜像公式 |
 * | `pageForward` / `pageBackward` / `landOnPage` / `focusCell` / `enterGrid` | 同名方法，公式逐字照抄 |
 * | `handleGridKey` | [handleGridKey]，左/右只在**整列边缘**翻组，上/下永不翻组 |
 * | `selectPage` / `toggleSort` / `toggleColumns` | 同名方法，连 `SharedPreferences` 的 key 都不变 |
 * | 分页按钮 `onFocused → selectPage` | [onGroupFocused]，焦点落上即切组 |
 * | `LaunchedEffect(pendingCell) + FOCUS_RETRY_FRAMES` | [requestCellFocus] 的 10 次重试 |
 * | `DialogProperties(dismissOnBackPress = true)` | 默认 Dialog 行为 + `setCanceledOnTouchOutside(false)` |
 *
 * 三处**有意的差异**（其余一律照抄）：
 * 1. 卡片 38dp / 4dp 圆角 / 右侧垂直居中 Lottie（高度按本轮红线 38dp；Lottie 位置对齐 OSD）；
 * 2. 列数多列 = 8、少列 = 2（需求指定，Compose 版是 4 / 2）→ 组容量 = 4 排 × 列数 = 32 / 8 集；
 * 3. 焦点态走全局胶囊 @drawable/bg_global_focus_capsule、选中态走全局胶囊红框
 *    @drawable/bg_global_selected_capsule_border（需求指定；Compose 版是 4dp 小圆角 + 自绘渐变）。
 *
 * 焦点契约（与 Compose 版一致）：
 * - 打开落在历史集（[lastWatchedEpisodeIndex]），无历史落第 1 集；
 * - 线路栏 ←/→ 在线路间移动、端点消费；↓ 进控制栏；↑ 消费；
 * - 控制栏 ←/→ 在「列数 / 排序 / 分页」之间滑动，端点消费；↓ 进网格（落回同一集）；↑ 回线路栏；
 * - 网格 ←/→ 组内移动，**任意一行**的最左列按 ← 切上一组、最右列按 → 切下一组，
 *   切组时顶部分页栏同步滚到新标签、焦点落到新组对应格；
 *   末行 ↓ 只消费，不会把焦点漏到弹窗外。
 */
internal class EpisodeViewDialog(
    context: Context,
    private val flags: List<Flag>,
    private val lastWatchedEpisodeIndex: Int = -1,
    private val onEpisodeSelected: (Flag, Episode) -> Unit = { _, _ -> },
    private val onDismiss: () -> Unit = {},
) : Dialog(context, R.style.SourceDialogTheme) {

    // ------------------------------------------------------------------
    // 本地偏好：跨剧、退弹窗、杀进程都保持上次的列数与排序（key 与 Compose 版逐字相同）
    // ------------------------------------------------------------------
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var descending = prefs.getBoolean(PREF_DESCENDING, false)
    // 无记录 = 少列（2 列）：首次进入 / 清数据 / 换设备一律 2 列，绝不默认 8 列；
    // 用户手动切到多列会写回 SharedPreferences，之后按记录恢复。
    private var fewColumns = prefs.getBoolean(PREF_FEW_COLUMNS, true)

    /**
     * 动作指令过滤：网盘聚合 / 猫源把「📄 媒体信息 / 🔍 全局搜索 / ➕ 稍后再看」伪装成集数
     * （`📄 媒体信息$msubinfo-db%3A...`）。这类 url 不是播放地址，直接**从选集里剔掉**，
     * 用户看到的每一格都保证能起播。与 Compose 版 `lineEpisodes` 完全一致。
     */
    private val lineEpisodes: List<List<Episode>> = flags.map { flag ->
        flag.episodes.orEmpty().filterNot { it.isActionCommand() }
    }

    /** 全部线路过滤后都没集数 = 这个源只给了动作指令（决定空态文案）。 */
    private val hasPlayableEpisode: Boolean = lineEpisodes.any { it.isNotEmpty() }

    // ------------------------------------------------------------------
    // 状态（与 Compose 版的 remember 变量一一对应）
    // ------------------------------------------------------------------
    /** 当前线路：线路栏「焦点即选中」，网格始终只渲染它的 episodes。 */
    private var selectedFlag = 0

    /** 当前分组。 */
    private var page = 0

    /** 焦点集在「当前组内」的下标；焦点不在网格时保留最后一次有效值。 */
    private var anchorCell = 0

    /** 焦点 / 当前集直接用 `Episode` 对象本身做锚点（不同线路的集数互不干扰）。 */
    private var anchorEpisode: Episode? = null

    /**
     * 正在播放的集：红字 + Lottie 高亮。
     *
     * **只允许由真实起播写入**（[onCellConfirm]）。历史集、首集、换线路后的第一集都**不许**预置，
     * 否则就是「没播放过的集数自己变红」的假数据 Bug：本弹窗没有播放态数据源，
     * 打开时它必须是 null，颜色只由真实播放态或焦点决定。
     */
    private var playingEpisode: Episode? = null

    /** 控制栏下标：0 = 列数、1 = 排序、2 起为分页按钮。 */
    private var focusedControl = CONTROL_COLUMNS

    /** 当前线路的基准表（源站顺序）与排序后的显示表。 */
    private var ordered: List<Episode> = emptyList()

    /** 当前组内的集数。 */
    private var pageItems: List<Episode> = emptyList()

    // ------------------------------------------------------------------
    // 视图
    // ------------------------------------------------------------------
    private lateinit var flagRecycler: RecyclerView
    private lateinit var groupRecycler: RecyclerView
    private lateinit var episodeRecycler: RecyclerView
    private lateinit var episodeEmpty: TextView
    private lateinit var columnsButton: TextView
    private lateinit var sortButton: TextView

    private val episodeAdapter = EpisodeAdapter(
        onCellFocused = { position -> onCellFocused(position) },
        onCellConfirm = { position -> onCellConfirm(position) },
    )

    private val flagAdapter = ChipAdapter(
        onChipFocus = { index -> if (index != selectedFlag) selectFlag(index) },
        onChipClick = { index -> selectFlag(index) },
        onChipKey = { index, keyCode, event -> handleFlagKey(index, keyCode, event) },
    )

    private val groupAdapter = ChipAdapter(
        onChipFocus = { index -> onGroupFocused(index) },
        onChipClick = { index -> selectPage(index) },
        onChipKey = { index, keyCode, event -> handleGroupKey(index, keyCode, event) },
    )

    /**
     * 【最外层按键闸门】长按确定键呼出时手指还按着，弹窗窗口收到的第一批事件就是
     * `repeatCount > 0` 的连发，会让焦点在网格里一路乱跑。
     *
     * 弹窗是独立 Window，Activity 的 `dispatchKeyEvent` 根本看不到这些事件，
     * 所以拦截放在弹窗自己的 `dispatchKeyEvent`（所有子 View 的按键之前）：
     * 1. 弹窗刚建好时闸门是关着的；
     * 2. 抬手（ACTION_UP）→ 开闸；
     * 3. 一次全新的按下（ACTION_DOWN 且 repeatCount == 0）→ 开闸后照常派发；
     * 4. 闸门关着期间，**所有** ACTION_DOWN（含连发）与 ACTION_MULTIPLE 一律吞掉。
     */
    private var blockUntilKeyUp = true

    init {
        setContentView(R.layout.activity_episode_view)
        // 与 Compose `dismissOnClickOutside = false` 对齐：电视端没有触摸，纯防御。
        setCanceledOnTouchOutside(false)
        bindViews()
        initState()
        refresh()
        setOnDismissListener { onDismiss() }
    }

    // ------------------------------------------------------------------
    // 初始化
    // ------------------------------------------------------------------

    private fun bindViews() {
        flagRecycler = findViewById(R.id.flagRecycler)
        groupRecycler = findViewById(R.id.groupRecycler)
        episodeRecycler = findViewById(R.id.episodeRecycler)
        episodeEmpty = findViewById(R.id.episodeEmpty)
        columnsButton = findViewById(R.id.columnsButton)
        sortButton = findViewById(R.id.sortButton)

        // 线路栏：32dp、12dp 间距（Compose `DIALOG_FLAG_GAP`）。
        flagRecycler.layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
        flagRecycler.adapter = flagAdapter
        flagRecycler.itemAnimator = null
        flagRecycler.addItemDecoration(SpacingDecoration(dp(FLAG_GAP_DP)))

        // 分页标签：与控制栏按钮同尺寸，8dp 间距（Compose `DIALOG_CONTROL_GAP`）。
        groupRecycler.layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
        groupRecycler.adapter = groupAdapter
        groupRecycler.itemAnimator = null
        groupRecycler.addItemDecoration(SpacingDecoration(dp(CONTROL_GAP_DP)))

        // 网格：列数随「多列 / 少列」动态变化，行数固定 4 排、高度写死、绝不内部滚动。
        episodeRecycler.layoutManager = GridLayoutManager(context, columns())
        episodeRecycler.adapter = episodeAdapter
        episodeRecycler.itemAnimator = null
        // 列间 16dp（两列之间留白适中）、行间 8dp；总高 4 × 38 + 3 × 8 = 176dp。
        episodeRecycler.addItemDecoration(GridSpacingDecoration(dp(GRID_COLUMN_GAP_DP), dp(GRID_GAP_DP)))

        columnsButton.setOnClickListener { toggleColumns() }
        columnsButton.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) focusedControl = CONTROL_COLUMNS
        }
        columnsButton.setOnKeyListener { _, keyCode, event ->
            handleControlKey(CONTROL_COLUMNS, keyCode, event)
        }

        sortButton.setOnClickListener { toggleSort() }
        sortButton.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) focusedControl = CONTROL_SORT
        }
        sortButton.setOnKeyListener { _, keyCode, event ->
            handleControlKey(CONTROL_SORT, keyCode, event)
        }
    }

    /**
     * 历史落点：与 Compose 版 `watchedIndex` / `initialIndex` 同一套公式。
     *
     * - [lastWatchedEpisodeIndex] 是 **0 基集索引**（0 = 第 1 集），-1 表示新剧；
     * - 越界 / 新剧统一收敛到第 1 集；
     * - 倒序时按 `ascending.lastIndex - watchedIndex` 镜像成显示下标。
     */
    private fun initState() {
        ordered = orderedNow()
        // 基准表 = 当前线路源站顺序（不按集号重排：很多源的集名没有数字）。
        val base = ascending()
        val watched = if (lastWatchedEpisodeIndex in base.indices) lastWatchedEpisodeIndex else 0
        val initialIndex = (if (descending) base.lastIndex - watched else watched).coerceAtLeast(0)
        page = initialIndex / pageSize()
        anchorCell = initialIndex % pageSize()
        anchorEpisode = ordered.getOrNull(initialIndex)
        // 【假数据 Bug 根因】历史集只是「上次看到哪」，不是「正在播放」：这里绝不能把
        // anchorEpisode 当成播放集，否则每次打开都会把第 1 集（或无历史时的首集）标红。
        playingEpisode = null
        pageItems = ordered.drop(page * pageSize()).take(pageSize())
    }

    // ------------------------------------------------------------------
    // 基准表
    // ------------------------------------------------------------------

    private fun ascending(): List<Episode> {
        if (lineEpisodes.isEmpty()) return emptyList()
        return lineEpisodes[selectedFlag.coerceIn(0, lineEpisodes.lastIndex)]
    }

    private fun orderedNow(): List<Episode> = if (descending) ascending().asReversed() else ascending()

    private fun columns(): Int = if (fewColumns) COLUMNS_FEW else COLUMNS_MANY

    private fun pageSize(): Int = GRID_ROWS * columns()

    private fun pageCount(): Int = ((ordered.size + pageSize() - 1) / pageSize()).coerceAtLeast(1)

    private val currentFlag: Flag? get() = flags.getOrNull(selectedFlag)

    /**
     * 【崩溃防线】三个 RecyclerView 是否有一个正在测量 / 布局。
     *
     * 卡片被 detach 时（`notifyDataSetChanged` 后的重新布局）Android 会走
     * `ViewGroup.removeViewInternal → rootViewRequestFocus` 重新找焦点；此刻网格还没有子 View，
     * 焦点会**短暂落到线路 chip 上**并触发它的 `onFocusChange`。
     *
     * 若当场 `selectFlag()` → `refresh()` → `notifyDataSetChanged()`，就会撞上正在布局的
     * RecyclerView，抛 `IllegalStateException: Cannot call this method while RecyclerView is
     * computing a layout or scrolling`（真机堆栈：ChipAdapter.onFocusChange → selectFlag → refresh）。
     *
     * 这也解释了「只有线路 1 正常」：系统重新找焦点时落的是**第一个**可聚焦 View = chip 0，
     * 线路 1 时 `index != selectedFlag` 直接挡掉，其它线路才会真的走进 `selectFlag` 然后崩。
     *
     * 这种焦点是系统在布局过程中塞过来的、不是用户按出来的：直接忽略。焦点随后由
     * [requestCellFocus] 还给网格；用户真实按方向键切线路时网格并不在布局，不会被误伤。
     *
     * 注意**不能**照抄 `HeroSearchActivity.render()` 的「推迟一帧再执行」：那里推迟的是状态重绘，
     * 这里推迟会把「系统塞给 chip 0 的焦点」当成用户选择，静默把线路切回第 1 条。
     */
    private fun isListBusy(): Boolean =
        episodeRecycler.isComputingLayout || groupRecycler.isComputingLayout || flagRecycler.isComputingLayout

    // ------------------------------------------------------------------
    // 刷新（= Compose 重组时的那些 remember 计算）
    // ------------------------------------------------------------------

    /**
     * 重算分组并把状态刷到三个 RecyclerView 上。
     *
     * [rebuildGroups] 只在**焦点不在分页标签上**时传 true（换线路 / 翻转排序 / 切列数）：
     * 分页标签自身持有焦点时重建列表会丢焦点，切组走 [selectPage] 的增量更新。
     */
    private fun refresh(rebuildGroups: Boolean = true) {
        val count = pageCount()
        page = page.coerceIn(0, count - 1)
        pageItems = ordered.drop(page * pageSize()).take(pageSize())

        (episodeRecycler.layoutManager as? GridLayoutManager)?.spanCount = columns()
        episodeAdapter.submit(pageItems, playingEpisode)

        val empty = pageItems.isEmpty()
        episodeRecycler.visibility = if (empty) View.GONE else View.VISIBLE
        episodeEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        episodeEmpty.setText(if (hasPlayableEpisode) R.string.episode_line_empty else R.string.episode_source_empty)

        columnsButton.setText(if (fewColumns) R.string.episode_columns_few else R.string.episode_columns_many)
        // 选中态与网格列数同源：少列（默认排版）失焦时保留细线红框高亮，切到多列立即取消，
        // 避免「文字已经变了、按钮还亮着」的错位。焦点态优先级更高，见 selector_episode_chip_background。
        columnsButton.isSelected = fewColumns
        sortButton.setText(if (descending) R.string.episode_sort_desc else R.string.episode_sort_asc)

        // 线路 chip 文案：`Flag.getShow()`（`show` 非空优先，否则 `flag`），与 Compose 版 `flag.show` 同源。
        // 线路数最多十几条，整批 submit 即可，不做 diff。
        flagAdapter.submit(flags.map { it.show }, selectedFlag)
        syncChipSelection(flagRecycler, selectedFlag)

        if (rebuildGroups) rebuildGroups()
        groupAdapter.selected = page
        // 换线路 / 切列数 / 翻排序会重排分页（标签数量与文案都变），标签列表刚被整片刷新、
        // 滚动位置回到 0，这里把顶部分页栏带回当前组，否则选中的标签可能落在屏幕外。
        groupRecycler.scrollToPosition(page)
        syncChipSelection(groupRecycler, page)
    }

    /** 分页标签文案：`1-32` / `33-64`…；空线路只显示 `0`（别出现「1-0」）。 */
    private fun rebuildGroups() {
        val count = pageCount()
        val labels = List(count) { index ->
            val start = index * pageSize() + 1
            val end = minOf((index + 1) * pageSize(), ordered.size)
            if (ordered.isEmpty()) "0" else "$start-$end"
        }
        groupAdapter.submit(labels, page)
    }

    /** 只同步已渲染标签的选中态，不触发 adapter 重建（避免焦点在标签上时被整片刷新掉）。 */
    private fun syncChipSelection(recycler: RecyclerView, index: Int) {
        for (i in 0 until recycler.childCount) {
            val child = recycler.getChildAt(i)
            child.isSelected = recycler.getChildAdapterPosition(child) == index
        }
    }

    // ------------------------------------------------------------------
    // 线路 / 控制栏 / 分页
    // ------------------------------------------------------------------

    /**
     * 切线路（= Compose `LaunchedEffect(selectedFlag)` 那段）：
     * 集数长度 / 顺序都可能不同，分组与焦点回到新线路第 1 集，当前播放集按新线路重置。
     */
    private fun selectFlag(index: Int) {
        if (index !in flags.indices || index == selectedFlag) return
        // 布局过程中的焦点是系统抢的、不是用户选的（见 [isListBusy]）：忽略掉才既不崩也不串线路。
        if (isListBusy()) return
        selectedFlag = index
        ordered = orderedNow()
        page = 0
        anchorCell = 0
        anchorEpisode = ordered.firstOrNull()
        // 换线路 = 换一套集数对象，旧线路的播放集在新线路上没有对应关系，播放态必须归零；
        // 新线路的第 1 集同样不能继承红字（它只是落点，没播放过）。
        playingEpisode = null
        refresh()
    }

    /** 分页标签落焦：焦点即选中，下方网格立刻刷新（不抢焦点进网格）。 */
    private fun onGroupFocused(index: Int) {
        if (index < 0) return
        focusedControl = CONTROL_GROUP_START + index
        selectPage(index)
    }

    /**
     * 点 / 聚焦分页标签：只换组，焦点留在标签上。
     * 走增量更新（不重建标签列表），否则正在持有焦点的标签会被刷新掉。
     */
    private fun selectPage(target: Int) {
        if (target !in 0 until pageCount()) return
        // 同 [selectFlag]：分页标签在布局过程中被系统塞进焦点时，绝不当场刷新网格。
        if (isListBusy()) return
        val items = ordered.drop(target * pageSize()).take(pageSize())
        if (items.isEmpty()) return
        page = target
        anchorCell = 0
        anchorEpisode = items.first()
        pageItems = items
        episodeAdapter.submit(items, playingEpisode)
        groupAdapter.selected = target
        syncChipSelection(groupRecycler, target)
    }

    /** 排序翻转：按「集对象」重新定位分组；焦点留在按钮上，不把焦点抢进网格。 */
    private fun toggleSort() {
        val current = anchorEpisode
        val next = !descending
        descending = next
        prefs.edit().putBoolean(PREF_DESCENDING, next).apply()
        ordered = orderedNow()
        val index = ordered.indexOfFirst { it === current }
        if (index >= 0) {
            page = index / pageSize()
            anchorCell = index % pageSize()
        }
        refresh()
    }

    /** 列数切换：组容量从 32 ↔ 8 变化，按「全局集下标」重算分组与组内位置。 */
    private fun toggleColumns() {
        // 注意顺序：先用**旧**组容量算出全局集下标，再翻转列数。
        val lastEpisode = (ordered.size - 1).coerceAtLeast(0)
        val episodeIndex = (page * pageSize() + anchorCell).coerceIn(0, lastEpisode)
        fewColumns = !fewColumns
        prefs.edit().putBoolean(PREF_FEW_COLUMNS, fewColumns).apply()
        val newSize = GRID_ROWS * columns()
        val newPageCount = ((ordered.size + newSize - 1) / newSize).coerceAtLeast(1)
        page = (episodeIndex / newSize).coerceIn(0, newPageCount - 1)
        anchorCell = episodeIndex % newSize
        // 焦点留在列数按钮上，等用户按 ↓ 再进网格，避免切列数时焦点乱跳。
        refresh()
    }

    // ------------------------------------------------------------------
    // 网格
    // ------------------------------------------------------------------

    /** 焦点落进网格：把下标收敛到当前组内，越界时夹到首尾。 */
    private fun focusCell(index: Int) {
        if (pageItems.isEmpty()) return
        val target = if (index < 0) 0 else index.coerceAtMost(pageItems.lastIndex)
        anchorCell = target
        anchorEpisode = pageItems[target]
        requestCellFocus(target)
    }

    /** 从控制栏进入网格：优先落回当前集，找不到就落第 1 格（= Compose `enterGrid`）。 */
    private fun enterGrid() {
        if (pageItems.isEmpty()) return
        val index = pageItems.indexOfFirst { it === anchorEpisode }
        focusCell(if (index >= 0) index else 0)
    }

    /** 换组落焦：写入新组状态并把目标格排进请求队列（= Compose `landOnPage`）。 */
    private fun landOnPage(target: Int, items: List<Episode>, index: Int) {
        page = target
        anchorCell = index
        anchorEpisode = items[index]
        pageItems = items
        episodeAdapter.submit(items, playingEpisode)
        groupAdapter.selected = target
        // 边缘翻页时焦点始终留在网格里，用户看不到标签栏，必须把顶部分页栏一起滚到新组，
        // 否则「1-8 → 9-16」切了页，上面高亮的还是旧的（甚至在屏幕外）。
        groupRecycler.scrollToPosition(target)
        syncChipSelection(groupRecycler, target)
        requestCellFocus(index)
    }

    /**
     * 右切下一组：普通行落「新组同一行的第 1 列」；
     * 最后一行落「新组最后一集」（继续往下看，不再从行首重新扫）。
     * 已是最后一组时什么都不做，事件由调用方消费，绝不越界。
     */
    private fun pageForward(row: Int, fromLastRow: Boolean) {
        val target = page + 1
        if (target !in 0 until pageCount()) return
        val items = ordered.drop(target * pageSize()).take(pageSize())
        if (items.isEmpty()) return
        val index = if (fromLastRow) items.lastIndex else (row * columns()).coerceAtMost(items.lastIndex)
        landOnPage(target, items, index)
    }

    /** 左切上一组：落「上一组同一行的最右一列」；该行没有格子时落上一组最后一集。 */
    private fun pageBackward(row: Int) {
        val target = page - 1
        if (target !in 0 until pageCount()) return
        val items = ordered.drop(target * pageSize()).take(pageSize())
        if (items.isEmpty()) return
        val index = if (row > items.lastIndex / columns()) {
            items.lastIndex
        } else {
            (row * columns() + columns() - 1).coerceAtMost(items.lastIndex)
        }
        landOnPage(target, items, index)
    }

    /**
     * 网格方向键：组内上下左右移动 + 「整列」边缘翻页。
     *
     * 翻页判定只看列：任意一行的**最左一列**按 ← = 上一组，**最右一列**按 → = 下一组，
     * 所以不必先把焦点挪到右下角才能翻页；上下键永远不翻页，末行 ↓ 只消费。
     * 公式与 Compose `handleGridKey` 逐字一致，两处差异：
     * 1. 「当前格」取**真实焦点位置**（`findFocus()` + `getChildAdapterPosition`），
     *    不再只信可能过期的 [anchorCell]；
     * 2. 长按连发（`repeatCount > 0`）只做组内移动、**不翻组**：翻组后焦点是异步落位的，
     *    连发事件读到的还是旧焦点，一路按住会连翻好几组。
     */
    private fun handleGridKey(keyCode: Int, firstPress: Boolean = true): Boolean {
        if (pageItems.isEmpty()) return false
        val lastIndex = pageItems.lastIndex
        // 以**真实焦点格**为准：anchorCell 只在落焦时写入，重建列表 / 焦点还在控制栏时会过期，
        // 过期值会把「最左列按 ←」判成组内左移（或反过来），边界翻页就失灵。
        // 取不到焦点格（例如焦点在别处）时才退回 anchorCell。
        val focusedPosition = episodeRecycler.findFocus()
            ?.let { episodeRecycler.getChildAdapterPosition(it) }
            ?: RecyclerView.NO_POSITION
        val base = if (focusedPosition in 0..lastIndex) {
            focusedPosition
        } else {
            anchorCell.coerceIn(0, lastIndex)
        }
        val columns = columns()
        val column = base % columns
        val row = base / columns
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (column != 0) {
                    focusCell(base - 1)
                } else if (firstPress) {
                    // 最左列：整组翻页（首组时 pageBackward 自己会空转，事件仍然消费掉）。
                    pageBackward(row)
                }
                true
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                when {
                    column == columns - 1 || base == lastIndex -> if (firstPress) {
                        pageForward(row, fromLastRow = base == lastIndex)
                    }

                    else -> focusCell(base + 1)
                }
                true
            }

            KeyEvent.KEYCODE_DPAD_UP -> {
                if (row > 0) focusCell(base - columns) else focusControl(focusedControl)
                true
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (base + columns <= lastIndex) focusCell(base + columns)
                true
            }

            else -> false
        }
    }

    // ------------------------------------------------------------------
    // 焦点请求（= Compose 的 FocusRequester + 逐帧重试）
    // ------------------------------------------------------------------

    /**
     * 网格落焦：RecyclerView 的 ViewHolder 要先完成布局才拿得到，所以最多重试
     * [FOCUS_RETRY_COUNT] 次（Compose 版是 `FOCUS_RETRY_FRAMES = 10` 帧）。
     */
    private var pendingCellFocus = -1
    private var cellFocusAttempt = 0

    private val cellFocusRunnable = object : Runnable {
        override fun run() {
            val index = pendingCellFocus
            if (index < 0) return
            val holder = episodeRecycler.findViewHolderForAdapterPosition(index)
            if (holder != null && holder.itemView.requestFocus()) {
                pendingCellFocus = -1
                return
            }
            if (++cellFocusAttempt < FOCUS_RETRY_COUNT) {
                episodeRecycler.postDelayed(this, FOCUS_RETRY_DELAY_MS)
            } else {
                pendingCellFocus = -1
            }
        }
    }

    private fun requestCellFocus(index: Int) {
        pendingCellFocus = index
        cellFocusAttempt = 0
        episodeRecycler.removeCallbacks(cellFocusRunnable)
        episodeRecycler.post(cellFocusRunnable)
    }

    /** 线路栏落焦：先滚进可视区，再点名请求（= ChangeSourceDialog 的 focusSite 同款重试）。 */
    private fun focusFlag(index: Int, attempt: Int = 0) {
        if (index !in flags.indices) return
        if (attempt == 0) flagRecycler.scrollToPosition(index)
        val holder = flagRecycler.findViewHolderForAdapterPosition(index)
        if (holder != null && holder.itemView.requestFocus()) return
        if (attempt < FOCUS_RETRY_COUNT) {
            flagRecycler.postDelayed({ focusFlag(index, attempt + 1) }, FOCUS_RETRY_DELAY_MS)
        }
    }

    /**
     * 控制栏落焦：0 = 列数、1 = 排序、2 起为分页按钮（= Compose `focusControl`）。
     * 越界一律夹到首尾，所以左右键在端点会被消费，焦点不会漏出弹窗。
     */
    private fun focusControl(index: Int) {
        val target = index.coerceIn(0, CONTROL_GROUP_START + pageCount() - 1)
        focusedControl = target
        when (target) {
            CONTROL_COLUMNS -> columnsButton.requestFocus()
            CONTROL_SORT -> sortButton.requestFocus()
            else -> focusGroupChip(target - CONTROL_GROUP_START)
        }
    }

    private fun focusGroupChip(position: Int, attempt: Int = 0) {
        if (attempt == 0) groupRecycler.scrollToPosition(position)
        val holder = groupRecycler.findViewHolderForAdapterPosition(position)
        if (holder != null && holder.itemView.requestFocus()) return
        if (attempt < FOCUS_RETRY_COUNT) {
            groupRecycler.postDelayed({ focusGroupChip(position, attempt + 1) }, FOCUS_RETRY_DELAY_MS)
        }
    }

    // ------------------------------------------------------------------
    // 按键
    // ------------------------------------------------------------------

    /** 线路栏：↑ 消费（上方只有不可聚焦标题）、↓ 进控制栏、←/→ 端点消费。 */
    private fun handleFlagKey(index: Int, keyCode: Int, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || index < 0) return false
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> true
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                focusControl(focusedControl)
                true
            }

            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (index > 0) focusFlag(index - 1)
                true
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (index < flags.lastIndex) focusFlag(index + 1)
                true
            }

            else -> false
        }
    }

    /** 控制栏按钮：↓ 进网格（落回当前集）、↑ 回线路栏、←/→ 在本行滑动（端点消费）。 */
    private fun handleControlKey(controlIndex: Int, keyCode: Int, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                focusFlag(selectedFlag)
                true
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                enterGrid()
                true
            }

            KeyEvent.KEYCODE_DPAD_LEFT -> {
                focusControl(controlIndex - 1)
                true
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                focusControl(controlIndex + 1)
                true
            }

            else -> false
        }
    }

    /** 分页标签：与列数 / 排序按钮同一套方向键规则，只是下标从 2 起。 */
    private fun handleGroupKey(index: Int, keyCode: Int, event: KeyEvent): Boolean {
        if (index < 0) return false
        return handleControlKey(CONTROL_GROUP_START + index, keyCode, event)
    }

    // ------------------------------------------------------------------
    // 选中 / 播放
    // ------------------------------------------------------------------

    private fun onCellFocused(position: Int) {
        if (position < 0) return
        val episode = pageItems.getOrNull(position) ?: return
        anchorCell = position
        anchorEpisode = episode
    }

    private fun onCellConfirm(position: Int) {
        if (position < 0) return
        val episode = pageItems.getOrNull(position) ?: return
        // 【关键防线】动作指令绝不能进起播：本站没有这套动作的分发器，
        // 送进 VideoActivity 只会拿 `msubinfo-...` 当 URL 请求，必然失败。
        if (episode.isActionCommand()) {
            Log.e(TAG, "动作集数不可播放: name=${episode.name} url=${episode.url} flag=${currentFlag?.flag}")
            Notify.show(R.string.episode_action_only)
            return
        }
        val flag = currentFlag ?: return
        playingEpisode = episode
        // 先关弹窗再回调：起播页是另一个页面，弹窗留在背后会闪一下（与 Compose 版调用方一致）。
        dismiss()
        onEpisodeSelected(flag, episode)
    }

    // ------------------------------------------------------------------
    // 窗口 / 生命周期
    // ------------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP) {
            blockUntilKeyUp = false
            return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            blockUntilKeyUp = false
        }
        val swallowing = event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_MULTIPLE
        if (blockUntilKeyUp && swallowing) return true
        // 【网格边缘翻页的唯一拦截点】按键先到窗口（本方法），再由窗口派发给焦点格。
        //
        // 为什么不能挂在 `episodeRecycler.setOnKeyListener` 上：ViewGroup.dispatchKeyEvent
        // 只在**自己持有焦点**时才走 super（才会调用自己的 OnKeyListener），否则直接
        // `mFocused.dispatchKeyEvent(event)` 转发给持有焦点的子 View。网格的 RecyclerView
        // 是 focusable=false、焦点永远在卡片上，所以挂在它身上的 OnKeyListener 一次都不会触发，
        // 这就是之前「最左/最右按左右键不翻页」的根因。与播放页 `dispatchEpisodePanelEdgeKey`
        // 的做法一致：在窗口层按「真实焦点格」判定列边界。
        if (event.action == KeyEvent.ACTION_DOWN && isGridFocused() &&
            handleGridKey(event.keyCode, firstPress = event.repeatCount == 0)
        ) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * 焦点是否落在网格卡片上。
     *
     * 只看卡片：RecyclerView 自己（focusable=false）和线路 / 控制 / 分页三行 chip 都不算，
     * 否则会把 chip 行的方向键也吞进 [handleGridKey]。
     */
    private fun isGridFocused(): Boolean {
        val focused = currentFocus ?: return false
        if (focused === episodeRecycler) return true
        return focused.parent === episodeRecycler
    }

    override fun show() {
        super.show()
        window?.apply {
            // 面板背景由布局里的 shape_dialog_source_shell 提供，窗口本身必须全透明。
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            // 全屏：遮罩由布局自绘 #8014171F（= Compose `DIALOG_SCRIM`），
            // 所以窗口 dim 归零，避免和遮罩叠成两层黑。
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setGravity(Gravity.CENTER)
            setDimAmount(0f)
        }
        blockUntilKeyUp = true
        // 初始精准定位：有历史集落历史集，无历史落第 1 集；
        // 线路为空（只剩动作指令）时退化为落在线路栏，至少保证方向键可用。
        if (pageItems.isEmpty()) focusFlag(selectedFlag) else requestCellFocus(anchorCell)
    }

    // ------------------------------------------------------------------
    // 适配器
    // ------------------------------------------------------------------

    /** 线路 chip / 分页标签共用的小适配器：一条文案 + 一个选中态。 */
    private inner class ChipAdapter(
        private val onChipFocus: (Int) -> Unit,
        private val onChipClick: (Int) -> Unit,
        private val onChipKey: (Int, Int, KeyEvent) -> Boolean,
    ) : RecyclerView.Adapter<ChipAdapter.Holder>() {

        private var labels: List<String> = emptyList()
        var selected: Int = -1

        fun submit(next: List<String>, selectedIndex: Int) {
            // 文案没变（换线路 / 翻排序 / 切列数都会走到这里）时不整片刷新：
            // 正在持有焦点的 chip 被重新绑定会丢焦点，选中态交给外层的 syncChipSelection 增量同步。
            val changed = labels != next
            labels = next
            selected = selectedIndex
            if (changed) notifyDataSetChanged()
        }

        override fun getItemCount(): Int = labels.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.adapter_episode_chip, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.text.text = labels[position]
            holder.itemView.isSelected = position == selected
            holder.itemView.setOnClickListener { onChipClick(holder.bindingAdapterPosition) }
            holder.itemView.setOnKeyListener { _, keyCode, event ->
                onChipKey(holder.bindingAdapterPosition, keyCode, event)
            }
            holder.itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) onChipFocus(holder.bindingAdapterPosition)
            }
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val text: TextView = view.findViewById(R.id.text)
        }
    }

    /** 横向 chip 行间距：只加右侧（与 ChangeSourceDialog 的 SpacingDecoration 同款）。 */
    private class SpacingDecoration(private val spacing: Int) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State,
        ) {
            val position = parent.getChildAdapterPosition(view)
            if (position != RecyclerView.NO_POSITION) outRect.right = spacing
        }
    }

    /**
     * 网格间距：列间 16dp 均分（两列之间正好 16dp），行间 8dp（首行上方不加）。
     * 总高 = 4 × 38dp + 3 × 8dp = 176dp，与布局里写死的网格高度严丝合缝。
     */
    private class GridSpacingDecoration(
        private val columnSpacing: Int,
        private val rowSpacing: Int,
    ) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State,
        ) {
            val position = parent.getChildAdapterPosition(view)
            if (position == RecyclerView.NO_POSITION) return
            val span = (parent.layoutManager as? GridLayoutManager)?.spanCount ?: return
            if (span <= 0) return
            val column = position % span
            val row = position / span
            outRect.left = column * columnSpacing / span
            outRect.right = columnSpacing - (column + 1) * columnSpacing / span
            outRect.top = if (row == 0) 0 else rowSpacing
        }
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private companion object {

        private const val TAG = "EpisodeViewDialog"

        /** 与 Compose 版 `DIALOG_PREFS_NAME` / key 逐字相同：换弹窗实现不丢用户偏好。 */
        private const val PREFS_NAME = "hero_detail_ui"
        private const val PREF_FEW_COLUMNS = "episode_few_columns"
        private const val PREF_DESCENDING = "episode_descending"

        /** 单行控制栏下标：0 = 列数、1 = 排序、2 起为分页按钮。 */
        private const val CONTROL_COLUMNS = 0
        private const val CONTROL_SORT = 1
        private const val CONTROL_GROUP_START = 2

        /** 固定 4 排网格（Compose `EPISODE_GRID_ROWS`）。 */
        private const val GRID_ROWS = 4

        /**
         * 列数：多列 8 / 少列 2（需求指定）。
         *
         * 组容量 = 4 排 × 列数 → 32 集 / 8 集一组，与 Compose 版「固定 4 排、翻组只走边缘按键」一致；
         * Compose 版本身是 4 / 2（16 / 8 集一组），要回到那一档只需把 [COLUMNS_MANY] 改成 4。
         */
        private const val COLUMNS_MANY = 8
        private const val COLUMNS_FEW = 2

        /** 间距（dp）：线路栏 12、控制栏 8、网格行间 8、网格列间 16。 */
        private const val FLAG_GAP_DP = 12
        private const val CONTROL_GAP_DP = 8
        private const val GRID_GAP_DP = 8

        /** 两列之间的水平间距（dp）：16dp，卡片横向撑开后仍留出清晰分栏。 */
        private const val GRID_COLUMN_GAP_DP = 16

        /** 焦点重试：Compose `FOCUS_RETRY_FRAMES = 10`，这里换成 10 次 × 16ms。 */
        private const val FOCUS_RETRY_COUNT = 10
        private const val FOCUS_RETRY_DELAY_MS = 16L
    }
}
