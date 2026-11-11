package com.fongmi.android.tv.ui.activity

import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fongmi.android.tv.App
import com.fongmi.android.tv.R
import com.fongmi.android.tv.api.config.VodConfig
import com.fongmi.android.tv.bean.Flag
import com.fongmi.android.tv.bean.Result
import com.fongmi.android.tv.bean.Site
import com.fongmi.android.tv.bean.Vod
import com.fongmi.android.tv.bean.Word
import com.fongmi.android.tv.model.SearchProgress
import com.fongmi.android.tv.model.SiteViewModel
import com.fongmi.android.tv.setting.Setting
import com.fongmi.android.tv.setting.SiteBlockSetting
import com.fongmi.android.tv.ui.hero.EpisodeViewDialog
import com.fongmi.android.tv.ui.hero.HeroImageLoader
import com.fongmi.android.tv.ui.hero.HeroSourceEntry
import com.fongmi.android.tv.ui.hero.SEARCH_FIRST_KEY_INDEX
import com.fongmi.android.tv.ui.hero.SEARCH_HOUSE_KEY_INDEX
import com.fongmi.android.tv.ui.hero.SEARCH_KEY_COLUMNS
import com.fongmi.android.tv.ui.hero.SEARCH_KEY_ROWS
import com.fongmi.android.tv.ui.hero.SEARCH_KEYWORD_MAX_LENGTH
import com.fongmi.android.tv.ui.hero.SEARCH_KEYS
import com.fongmi.android.tv.ui.hero.SEARCH_PLACEHOLDER_INDEX
import com.fongmi.android.tv.ui.hero.SearchChipAdapter
import com.fongmi.android.tv.ui.hero.SearchGapDecoration
import com.fongmi.android.tv.ui.hero.SearchKey
import com.fongmi.android.tv.ui.hero.SearchKeyAction
import com.fongmi.android.tv.ui.hero.SearchKeyAdapter
import com.fongmi.android.tv.ui.hero.SearchResultAdapter
import com.fongmi.android.tv.ui.hero.SearchSiteAdapter
import com.fongmi.android.tv.ui.hero.SourceSelectionStore
import com.fongmi.android.tv.ui.hero.SourceSidebar
import com.fongmi.android.tv.ui.hero.SourceSidebarMode
import com.fongmi.android.tv.utils.Notify
import com.fongmi.android.tv.utils.SearchSuggest
import com.fongmi.android.tv.utils.Util
import com.github.catvod.net.OkHttp
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayoutManager
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * HeroUI 搜索页（**纯 View 体系**）。
 *
 * 版式 = 原版 `ref_docs/activity_search.xml` + 两张原版截图（1920×1080，1dp = 2px）：
 * ```
 * FrameLayout                                    // 背景：模糊大图 + 60% 黑 + 底部渐隐
 * └─ LinearLayout(paddingTop 24 / 左右 24)
 *    ├─ LinearLayout(weight 1, 横向)              // 上半区
 *    │   ├─ 左列 242dp + marginEnd 24dp
 *    │   │   ├─ 输入行 32dp：mic 36 + 12 + 输入框(weight 1) + 6 + 搜索键 32
 *    │   │   └─ 键盘 7×6 格，每格 34.57×35dp（键背景 inset 2.5dp → 可见键 30dp）
 *    │   └─ 右列（NestedScrollView）：历史（2 行）+ 热搜 / 建议（3 行）
 *    └─ resultSection(marginTop 12dp, GONE)
 *        ├─ 站点 Tab 行 38dp（26dp 胶囊 + 2dp + 10dp）
 *        └─ 海报区 215dp（117×215dp 卡片，间距 16dp）
 * ```
 * 上半区 540 - 24 - 12 - 38 - 215 = 251dp；左列 32 + 8 + 210 = 250dp，刚好塞满。
 *
 * 业务与焦点契约（与上一版 Compose 页逐条一致）：
 * - 历史：`Setting.getKeyword()/putKeyword()`，去重 → 置顶 → 最多 9 条，改动立刻落盘；
 * - 热搜：先用 `Setting.getHot()` 缓存秒出，再异步刷 360kan 排行榜；
 * - 建议：输入变化后 250ms 防抖请求爱奇艺联想，空结果不清空上一次的词；
 * - 搜索：`SiteViewModel.searchContent(勾选源, 关键词, false)`，**按站点累加**结果，
 *   站点 Tab 完全由「返回过内容的站点」推导（第一项「全部」）；
 * - folder 条目留在本页下钻（`categoryContent(t=id)`），返回键先出栈；
 * - 点卡片直达选集：有 `flags` 直接弹 [EpisodeViewDialog]，没有才补一次详情；
 * - 焦点：输入框 ↓ → 键盘首键；键盘最右列 → 放行给系统 2D 搜索落到右侧胶囊；
 *   键盘末行 ↓ → 站点 Tab；胶囊 ←/→/↓ 走几何搜索、无下一行才落站点 Tab；
 *   站点 Tab ↑ → 右侧首枚胶囊、↓ → 首张海报、←/→ 显式换站；海报 ←/→ 行内移动、↑ → 站点 Tab。
 */
class HeroSearchActivity : AppCompatActivity() {

    // ------------------------------------------------------------------
    // 数据
    // ------------------------------------------------------------------

    /**
     * 与首页 / 历史页同一个 ViewModel 类，但 Activity 作用域各持一份：
     * 本页写 `search`（搜索）与 `result`（详情 / 分类），首页写自己的 `result`，互不干扰。
     */
    private val siteViewModel: SiteViewModel by lazy {
        ViewModelProvider(this)[SiteViewModel::class.java]
    }

    // ---- 历史 / 热词 / 建议 ----
    private var history: List<String> = emptyList()
    private var hotWords: List<String> = emptyList()
    private var suggestWords: List<String> = emptyList()
    private var hotRequested = false
    private var suggestJob: Job? = null

    // ---- 搜索状态 ----
    private var searchResults: List<Vod> = emptyList()
    private var searchedKeyword = ""
    private var searching = false
    private var searchProgress: SearchProgress? = null
    private var selectedSite = 0
    private var focusedSite = 0

    /** 结果区（站点 Tab / 海报）当前是否持有焦点：站点 Tab 三态里的第 2 态要用。 */
    private var resultsFocused = false

    // ---- 下钻 ----
    private val drillStack = mutableListOf<DrillEntry>()
    private var drillResults: List<Vod> = emptyList()
    private var drillLoading = false

    // ---- 直达选集 ----
    private var episodeDialog: EpisodeViewDialog? = null
    private var playFlags: List<Flag> = emptyList()
    private var playVod: Vod? = null
    private var playSite: Site? = null
    private var pendingPlayId: String? = null

    // ------------------------------------------------------------------
    // 视图
    // ------------------------------------------------------------------

    private lateinit var backdrop: ImageView
    private lateinit var scrim: View
    private lateinit var bottomFade: View
    private lateinit var input: AppCompatEditText
    private lateinit var searchButton: ImageView
    private lateinit var keyboardRecycler: RecyclerView
    private lateinit var historySection: View
    private lateinit var historyRecycler: RecyclerView
    private lateinit var wordSection: View
    private lateinit var wordTitle: TextView
    private lateinit var wordRecycler: RecyclerView
    private lateinit var resultSection: View
    private lateinit var siteRecycler: RecyclerView
    private lateinit var posterRecycler: RecyclerView
    private lateinit var posterEmpty: TextView

    private lateinit var keyAdapter: SearchKeyAdapter
    private lateinit var historyAdapter: SearchChipAdapter
    private lateinit var wordAdapter: SearchChipAdapter
    private lateinit var siteAdapter: SearchSiteAdapter
    private lateinit var resultAdapter: SearchResultAdapter

    private val globalFocusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
        val inResults = newFocus != null &&
            (isDescendant(siteRecycler, newFocus) || isDescendant(posterRecycler, newFocus))
        if (inResults != resultsFocused) {
            resultsFocused = inResults
            refreshSiteTabs()
        }
    }

    /** `render()` / 站点 Tab 刷新被 RecyclerView 布局挡回时的合并标记，避免同一帧反复 post。 */
    private var renderPending = false
    private var siteTabsPending = false

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /** 与全站共用同一套「设计画布」（960×540dp × 界面缩放）。 */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Setting.wrapDisplay(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HeroImageLoader.install()
        window.setBackgroundDrawable(ColorDrawable(AndroidColor.BLACK))
        WindowCompat.setDecorFitsSystemWindows(window, false)
        Util.hideSystemUI(this)
        setContentView(R.layout.activity_search_view)
        bindViews()
        setupLists()
        setupInput()
        observeViewModel()
        loadHistory()
        loadHotWords()
        loadBackdrop(intent?.getStringExtra(EXTRA_BACKDROP))
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener(globalFocusListener)
        // 进入页面默认焦点在输入框（原版 SearchActivity.onResume `keyword.requestFocus()`）。
        window.decorView.post { focusInput() }
    }

    override fun onResume() {
        super.onResume()
        // 从选集弹窗 / 播放页回来时，系统可能把焦点留空：补一次输入框焦点。
        if (window.decorView.findFocus() == null) input.post { focusInput() }
    }

    override fun onDestroy() {
        window.decorView.viewTreeObserver.removeOnGlobalFocusChangeListener(globalFocusListener)
        suggestJob?.cancel()
        episodeDialog?.dismiss()
        episodeDialog = null
        super.onDestroy()
    }

    /** 下钻层级存在时，返回键先退回上一级，而不是直接退出搜索页。 */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (drillStack.isNotEmpty()) {
            exitDrillDown()
            return
        }
        super.onBackPressed()
    }

    // ------------------------------------------------------------------
    // 初始化
    // ------------------------------------------------------------------

    private fun bindViews() {
        backdrop = findViewById(R.id.backdrop)
        scrim = findViewById(R.id.scrim)
        bottomFade = findViewById(R.id.bottomFade)
        input = findViewById(R.id.keyword)
        searchButton = findViewById(R.id.searchButton)
        keyboardRecycler = findViewById(R.id.keyboard)
        historySection = findViewById(R.id.historySection)
        historyRecycler = findViewById(R.id.historyRecycler)
        wordSection = findViewById(R.id.wordSection)
        wordTitle = findViewById(R.id.wordTitle)
        wordRecycler = findViewById(R.id.wordRecycler)
        resultSection = findViewById(R.id.resultSection)
        siteRecycler = findViewById(R.id.siteRecycler)
        posterRecycler = findViewById(R.id.posterRecycler)
        posterEmpty = findViewById(R.id.posterEmpty)
    }

    private fun setupLists() {
        // ---- 键盘：7 列网格，每格宽度由 GridLayoutManager 均分 242dp ----
        keyAdapter = SearchKeyAdapter(onKey = ::onKeyMove, onClick = ::onKeyClick)
        keyboardRecycler.layoutManager = GridLayoutManager(this, SEARCH_KEY_COLUMNS)
        keyboardRecycler.adapter = keyAdapter
        keyboardRecycler.itemAnimator = null

        // ---- 历史 / 热搜 / 建议：Flexbox 自动换行 ----
        historyAdapter = SearchChipAdapter(onClick = ::requestSearch, onDown = ::focusResultsFromChips)
        historyRecycler.layoutManager = flexboxManager()
        historyRecycler.adapter = historyAdapter
        historyRecycler.itemAnimator = null

        wordAdapter = SearchChipAdapter(onClick = ::requestSearch, onDown = ::focusResultsFromChips)
        wordRecycler.layoutManager = flexboxManager()
        wordRecycler.adapter = wordAdapter
        wordRecycler.itemAnimator = null

        // ---- 站点 Tab：横向，12dp 间距（原版 dz2 `setHorizontalSpacing(12dp)`）----
        siteAdapter = SearchSiteAdapter(
            onFocused = ::onSiteFocused,
            onConfirm = ::onSiteConfirm,
            onKey = ::onSiteKey,
        )
        siteRecycler.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        siteRecycler.adapter = siteAdapter
        siteRecycler.itemAnimator = null
        siteRecycler.addItemDecoration(SearchGapDecoration(SITE_ITEM_GAP_DP))

        // ---- 结果卡：横向，16dp 间距 ----
        resultAdapter = SearchResultAdapter(
            onOpen = ::openResult,
            onKey = ::onPosterKey,
        )
        posterRecycler.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        posterRecycler.adapter = resultAdapter
        posterRecycler.itemAnimator = null
        posterRecycler.addItemDecoration(SearchGapDecoration(POSTER_ITEM_GAP_DP))
    }

    private fun flexboxManager() = FlexboxLayoutManager(this).apply {
        flexDirection = FlexDirection.ROW
        flexWrap = FlexWrap.WRAP
    }

    private fun setupInput() {
        input.filters = arrayOf(InputFilter.LengthFilter(SEARCH_KEYWORD_MAX_LENGTH))
        // 电视端不弹系统输入法：文字只由页面内的虚拟键盘写入。
        input.showSoftInputOnFocus = false
        input.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    focusKeyboard(SEARCH_FIRST_KEY_INDEX)
                    true
                }

                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                -> {
                    confirmSearch()
                    true
                }

                else -> false
            }
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                confirmSearch()
                true
            } else {
                false
            }
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = onInputChanged()
        })

        searchButton.setOnClickListener { confirmSearch() }
        searchButton.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    focusKeyboard(SEARCH_FIRST_KEY_INDEX)
                    true
                }

                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    focusInput()
                    true
                }

                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                -> {
                    confirmSearch()
                    true
                }

                else -> false
            }
        }
    }

    // ------------------------------------------------------------------
    // 输入框 / 虚拟键盘
    // ------------------------------------------------------------------

    private fun onInputChanged() {
        val text = input.text.toString()
        val hasInput = text.trim().isNotEmpty()
        if (hasInput) {
            fetchSuggest(text.trim())
        } else {
            suggestJob?.cancel()
            suggestWords = emptyList()
            if (resultsFocused) {
                resultsFocused = false
                render()
                focusInput()
            }
        }
        refreshWordSection()
        render()
    }

    /** 与 `CustomKeyboard.onTextClick` L31-36 一致：总长不超过 19，插入到光标处。 */
    private fun insert(text: String) {
        val editable = input.text ?: return
        if (editable.length >= SEARCH_KEYWORD_MAX_LENGTH) return
        val start = minOf(input.selectionStart, input.selectionEnd).coerceIn(0, editable.length)
        val end = maxOf(input.selectionStart, input.selectionEnd).coerceIn(0, editable.length)
        if (editable.length - (end - start) + text.length > SEARCH_KEYWORD_MAX_LENGTH) return
        editable.replace(start, end, text)
        input.setSelection((start + text.length).coerceIn(0, editable.length))
    }

    private fun backspace() {
        val editable = input.text ?: return
        val start = minOf(input.selectionStart, input.selectionEnd).coerceIn(0, editable.length)
        val end = maxOf(input.selectionStart, input.selectionEnd).coerceIn(0, editable.length)
        if (start != end) {
            editable.delete(start, end)
            input.setSelection(start)
            return
        }
        if (start == 0) return
        editable.delete(start - 1, start)
        input.setSelection(start - 1)
    }

    private fun moveCaret(delta: Int) {
        val length = input.text?.length ?: return
        input.setSelection((input.selectionStart + delta).coerceIn(0, length))
    }

    private fun onKeyClick(position: Int) {
        when (val key = SEARCH_KEYS.getOrNull(position)) {
            is SearchKey.Text -> insert(key.value)

            is SearchKey.Icon -> when (key.action) {
                SearchKeyAction.CursorLeft -> moveCaret(-1)
                SearchKeyAction.CursorRight -> moveCaret(1)
                SearchKeyAction.Backspace -> backspace()
                // 原版这个位置是「键盘布局切换键」；本页没有符号键盘页，改为清空输入。
                SearchKeyAction.Keyboard -> input.setText("")
                SearchKeyAction.House -> openSourceSidebar()
            }

            else -> Unit
        }
    }

    /**
     * 键盘四向移动。
     *
     * 最右列按 → **不消费、返回 false**：交给系统 `performFocusNavigation` 的几何搜索，
     * 自动落到右侧「同一排」的第一枚胶囊（比手写「永远跳第一排第一个」精确）。
     * 最后一行按 ↓：有结果区时按所在列落到站点 Tab，没有结果区就放行。
     */
    private fun onKeyMove(position: Int, keyCode: Int): Boolean {
        if (position !in SEARCH_KEYS.indices) return false
        val row = position / SEARCH_KEY_COLUMNS
        val column = position % SEARCH_KEY_COLUMNS
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (column > 0 && position - 1 > SEARCH_PLACEHOLDER_INDEX) {
                    focusKeyboard(position - 1)
                }
                true
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (column == SEARCH_KEY_COLUMNS - 1) {
                    false
                } else {
                    focusKeyboard(position + 1)
                    true
                }
            }

            KeyEvent.KEYCODE_DPAD_UP -> {
                if (row > 0) focusKeyboard(position - SEARCH_KEY_COLUMNS) else focusInput()
                true
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                when {
                    row < SEARCH_KEY_ROWS - 1 -> {
                        focusKeyboard(position + SEARCH_KEY_COLUMNS)
                        true
                    }

                    input.text.toString().trim().isNotEmpty() -> {
                        focusSite(column)
                        true
                    }

                    else -> false
                }
            }

            else -> false
        }
    }

    private fun focusInput() {
        if (!input.requestFocus()) window.decorView.post { input.requestFocus() }
    }

    private fun focusKeyboard(index: Int) {
        val target = index.coerceIn(SEARCH_FIRST_KEY_INDEX, SEARCH_KEYS.lastIndex)
        if (!keyAdapter.focusKey(keyboardRecycler, target)) {
            keyboardRecycler.post { keyAdapter.focusKey(keyboardRecycler, target) }
        }
    }

    // ------------------------------------------------------------------
    // 历史 / 热搜 / 建议
    // ------------------------------------------------------------------

    private fun loadHistory() {
        history = readHistory()
        historyAdapter.submit(history)
        historySection.visibility = if (history.isEmpty()) View.GONE else View.VISIBLE
        updateWordGap()
    }

    /**
     * 记一条历史：**去重 → 提到最前 → 最多 9 条 → 立刻持久化**。
     *
     * 与 `xp.java` 的 `a(String)` 逐行对应：
     * `list.remove(str); list.add(0, str); if (size > 9) remove(9); vx0.S(json, "keyword")`。
     */
    private fun recordHistory(text: String) {
        val value = text.trim()
        if (value.isEmpty()) return
        val next = (listOf(value) + history.filterNot { it == value }).take(HISTORY_LIMIT)
        if (next == history) return
        history = next
        Setting.putKeyword(App.gson().toJson(next))
        historyAdapter.submit(history)
        historySection.visibility = View.VISIBLE
        updateWordGap()
    }

    /** 「历史」为空时，热搜 / 建议区不需要额外的 16dp 间隔。 */
    private fun updateWordGap() {
        val params = wordSection.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val gap = if (history.isEmpty()) 0 else dp(RIGHT_SECTION_GAP_DP)
        if (params.topMargin == gap) return
        params.topMargin = gap
        wordSection.layoutParams = params
    }

    private fun refreshWordSection() {
        val hasInput = input.text.toString().trim().isNotEmpty()
        val words = if (hasInput) suggestWords else hotWords
        wordTitle.text = if (hasInput) TITLE_SUGGEST else TITLE_HOT
        wordSection.visibility = if (words.isEmpty()) View.GONE else View.VISIBLE
        wordAdapter.submit(words)
    }

    private fun loadHotWords() {
        // 先用本地缓存秒出（同 `X1()` 的两步），再异步刷一次网络。
        hotWords = parseWords(Setting.getHot())
        refreshWordSection()
        if (input.text.toString().trim().isNotEmpty() || hotRequested) return
        hotRequested = true
        lifecycleScope.launch {
            val body = try {
                withContext(Dispatchers.IO) {
                    OkHttp.string(HOT_URL, mapOf("Referer" to HOT_REFERER))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG_SEARCH, "热搜请求失败: $e", e)
                return@launch
            }
            // 请求返回时用户可能已经开始输入：热搜区不再展示，直接丢弃这次结果。
            if (input.text.toString().trim().isNotEmpty()) return@launch
            val fresh = try {
                parseWords(body)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG_SEARCH, "热搜解析失败: $e", e)
                return@launch
            }
            if (fresh.isEmpty()) {
                Log.w(TAG_SEARCH, "热搜为空，保留缓存 ${hotWords.size} 条")
                return@launch
            }
            hotWords = fresh
            // 与 `vx0.S(..., "hot")` 一致：原始 JSON 存下来，下次进页面秒出。
            runCatching { Setting.putHot(body) }
            refreshWordSection()
            Log.d(TAG_SEARCH, "热搜更新: ${fresh.size} 条")
        }
    }

    /** 建议词：输入变化后 250ms 防抖（电视键盘逐字输入，避免每按一个字母打一次接口）。 */
    private fun fetchSuggest(text: String) {
        suggestJob?.cancel()
        suggestJob = lifecycleScope.launch {
            delay(SUGGEST_DEBOUNCE_MS)
            val body = try {
                withContext(Dispatchers.IO) { OkHttp.string(SearchSuggest.iqiyiUrl(text)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG_SEARCH, "建议请求失败: $text $e", e)
                return@launch
            }
            // 返回时输入已经变了：这批建议已经过期，不写界面。
            if (input.text.toString().trim() != text) return@launch
            val fresh = try {
                parseWords(body)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG_SEARCH, "建议解析失败: $text $e", e)
                return@launch
            }
            // 原版容错：拿不到就不动列表，不要让「建议」整块消失。
            if (fresh.isEmpty()) return@launch
            suggestWords = fresh
            refreshWordSection()
            Log.d(TAG_SEARCH, "建议更新: $text -> ${fresh.size} 条")
        }
    }

    // ------------------------------------------------------------------
    // 搜索
    // ------------------------------------------------------------------

    private fun confirmSearch() = requestSearch(input.text.toString())

    /**
     * 统一的「请求搜索」入口：同步更新输入框、历史、结果区，再发请求。
     *
     * 纯 View 体系没有 Compose 的「重组 / 节点销毁」问题，不需要 50ms settle 延时：
     * 点击回调里直接改状态是安全的，结果区也在同一个消息循环里完成显示。
     */
    private fun requestSearch(text: String) {
        val value = text.trim()
        if (value.isEmpty()) {
            focusInput()
            return
        }
        if (input.text.toString() != value) {
            input.setText(value)
            input.setSelection(value.length)
        }
        recordHistory(value)
        performSearch(value, focusSite = true)
    }

    private fun performSearch(text: String, focusSite: Boolean) {
        // 1. 清空上一轮结果 + 进入加载态（原版 S0 里那一长串 clear）。
        searchResults = emptyList()
        drillStack.clear()
        drillResults = emptyList()
        drillLoading = false
        searchedKeyword = text
        selectedSite = 0
        focusedSite = 0
        searching = true
        searchProgress = null
        render()

        // 2. 收集可搜索站点：与 `f2()` 一致只过滤 isSearchable()，再按侧边栏勾选裁剪。
        val searchable = VodConfig.get().sites.filter { it.isSearchable() }
        val enabledKeys = SourceSelectionStore.checkedSites(this, searchable).map { it.key }.toSet()
        val sites = searchable.filter { it.key in enabledKeys }
        Log.d(
            TAG_SEARCH,
            "开始搜索: keyword=$text 可搜索站点=${searchable.size}, " +
                "勾选=${sites.size}, 跳过=${searchable.size - sites.size}",
        )
        if (sites.isEmpty()) {
            // 一个都没勾：不发请求，直接收掉加载态。
            Log.e(TAG_SEARCH, "搜索跳过: 侧边栏没有勾选任何源")
            searching = false
            searchProgress = SearchProgress.of(0, 0)
            render()
            return
        }

        // 3. 发起搜索（quick = false，与原版一致）。
        siteViewModel.searchContent(sites, text, false)

        // 4. 结果区刚显示出来，等一次布局再把焦点落到「全部」。
        if (focusSite) siteRecycler.post { focusSite(0) }
    }

    private fun openResult(vod: Vod) {
        // 与 `ks2.java` 一致：点开前把当前关键词记进历史。
        recordHistory(input.text.toString())
        val site = vod.site ?: drillStack.lastOrNull()?.site ?: VodConfig.get().home
        // folder 条目（网盘聚合壳）留在本页展开目录；其余直达选集。
        if (vod.isDrillDownFolder()) openDrillDown(vod, site) else openPlayDialog(vod, site)
    }

    // ------------------------------------------------------------------
    // 下钻 / 选集
    // ------------------------------------------------------------------

    /**
     * 打开一个 folder 条目：压栈 + 用**分类接口**展开目录（`t=vod.id`）。
     *
     * 与原版 `CollectActivity.onItemClick → VodActivity.start(..., Result.folder(item))` 语义一致，
     * 区别只是落点从「原版分类页」换成「本页的下方海报行」。
     */
    private fun openDrillDown(vod: Vod, site: Site) {
        drillStack.add(DrillEntry(site, vod))
        drillResults = emptyList()
        drillLoading = true
        selectedSite = 0
        focusedSite = 0
        render()
        Log.e(
            TAG_SEARCH,
            "下钻 folder: site=${site.key} id=${vod.id} name=${vod.name} " +
                "depth=${drillStack.size} api=${site.api}",
        )
        siteViewModel.categoryContent(site.key, vod.id, "1", true, hashMapOf<String, String>())
    }

    /** 返回上一级：还有上级就重新展开上级，到顶就恢复原搜索结果（`searchResults` 从未被改动）。 */
    private fun exitDrillDown() {
        if (drillStack.isEmpty()) return
        drillStack.removeAt(drillStack.lastIndex)
        drillResults = emptyList()
        selectedSite = 0
        focusedSite = 0
        val parent = drillStack.lastOrNull()
        if (parent == null) {
            drillLoading = false
            render()
            siteRecycler.post { focusSite(0) }
        } else {
            drillLoading = true
            render()
            siteViewModel.categoryContent(parent.site.key, parent.vod.id, "1", true, hashMapOf<String, String>())
        }
    }

    /**
     * 点卡片直达选集：**不再先跳详情页**。
     *
     * - `vod.flags` 非空（下钻 / 分类结果常常直接带线路）→ 立刻弹 [EpisodeViewDialog]；
     * - 没有 flags → 先请求一次 `detailContent`，结果回来后由 `result` 观察者直接弹选集。
     */
    private fun openPlayDialog(vod: Vod, site: Site) {
        if (vod.id.isBlank()) {
            Notify.show("缺少剧集 ID，无法选集")
            return
        }
        playVod = vod
        playSite = site
        val inline = vod.flags
        if (inline.isNotEmpty()) {
            playFlags = inline
            pendingPlayId = null
            showPlayDialog()
            Log.e(
                TAG_SEARCH,
                "点卡片直达选集: id=${vod.id} site=${site.key} flags=${inline.size} " +
                    "集数=${inline.sumOf { it.episodes.orEmpty().size }}",
            )
        } else {
            playFlags = emptyList()
            pendingPlayId = vod.id
            Log.e(TAG_SEARCH, "点卡片无线路，请求详情后直达选集: id=${vod.id} site=${site.key}")
            siteViewModel.detailContent(site.key, vod.id)
        }
    }

    private fun showPlayDialog() {
        if (playFlags.isEmpty()) return
        episodeDialog?.dismiss()
        val vod = playVod
        val site = playSite
        val dialog = EpisodeViewDialog(
            context = this,
            flags = playFlags,
            // 搜索页还没有接历史库：-1 = 新剧，焦点落第 1 集。
            lastWatchedEpisodeIndex = -1,
            onEpisodeSelected = { flag, episode ->
                if (site == null) {
                    Notify.show("缺少站点信息，无法起播")
                } else {
                    startEpisode(this, site.key, vod, vod?.id.orEmpty(), flag, episode)
                }
            },
            onDismiss = { episodeDialog = null },
        )
        episodeDialog = dialog
        dialog.show()
    }

    // ------------------------------------------------------------------
    // 侧边栏
    // ------------------------------------------------------------------

    /**
     * 小房子键 → 全局接口源侧边栏（复用 [SourceSidebar]）。
     *
     * [SourceSidebarMode.Multi]：带勾选圈与「全选 / 全不选」；确定键切换勾选（不关闭），
     * 返回键关闭后由 `onClosed` 把焦点交还房子键。
     */
    private fun openSourceSidebar() {
        val sites = SiteBlockSetting.filter(VodConfig.get().sites, false)
        var selectionChanged = false
        SourceSidebar.show(
            context = this,
            entries = HeroSourceEntry.of(sites),
            mode = SourceSidebarMode.Multi,
            initialChecked = SourceSelectionStore.checkedFlags(this, sites),
            onCheckedChanged = { flags ->
                SourceSelectionStore.save(this, sites, flags)
                selectionChanged = true
            },
            onSiteSelected = { },
            onClosed = {
                // 勾选变了且已经搜过：按新勾选立刻重搜一轮（不再把焦点抢回站点 Tab）。
                if (selectionChanged && searchedKeyword.isNotBlank()) {
                    selectionChanged = false
                    performSearch(searchedKeyword, focusSite = false)
                }
                // Dialog 关闭后当前窗口尚未拿到焦点，等一帧再 requestFocus 才不会被丢。
                keyboardRecycler.post { focusKeyboard(SEARCH_HOUSE_KEY_INDEX) }
            },
        )
    }

    // ------------------------------------------------------------------
    // 结果区：站点 Tab + 海报
    // ------------------------------------------------------------------

    private fun siteLabels(): List<String> {
        if (drillStack.isNotEmpty()) return listOf(DRILL_BACK_LABEL)
        val keyword = input.text.toString().trim()
        if (keyword.isEmpty() || keyword != searchedKeyword) return listOf(TITLE_ALL)
        val sites = searchResults.mapNotNull { it.site }.distinctBy { it.key }
        return listOf(TITLE_ALL) + sites.map { it.displayName }
    }

    private fun visibleResults(): List<Vod> {
        if (drillStack.isNotEmpty()) return drillResults
        val keyword = input.text.toString().trim()
        if (keyword.isEmpty() || keyword != searchedKeyword) return emptyList()
        val sites = searchResults.mapNotNull { it.site }.distinctBy { it.key }
        val site = sites.getOrNull(selectedSite - 1) ?: return searchResults
        return searchResults.filter { it.site?.key == site.key }
    }

    /** 结果区唯一的重绘入口：站点 Tab 文案 / 选中态、海报列表、空态提示、整块显隐。 */
    private fun render() {
        // 焦点回调（`onSiteFocused`）可能落在 RecyclerView 布局/滚动过程中，此时调用
        // notifyDataSetChanged 会抛 IllegalStateException：统一挪到下一帧，并合并同一帧的多次请求。
        if (siteRecycler.isComputingLayout || posterRecycler.isComputingLayout) {
            if (renderPending) return
            renderPending = true
            siteRecycler.post {
                renderPending = false
                render()
            }
            return
        }
        val hasInput = input.text.toString().trim().isNotEmpty()
        val labels = siteLabels()
        if (selectedSite > labels.lastIndex) selectedSite = labels.lastIndex.coerceAtLeast(0)
        if (focusedSite > labels.lastIndex) focusedSite = labels.lastIndex.coerceAtLeast(0)
        val items = visibleResults()
        refreshSiteTabs()
        resultAdapter.submit(items)
        posterEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        posterEmpty.text = emptyHint()
        resultSection.visibility = if (hasInput) View.VISIBLE else View.GONE
    }

    /**
     * 站点 Tab 的唯一刷新入口。
     *
     * 三态背景里的第 2 态（选中但失焦的红框）由 [resultsFocused] 决定，所以全局焦点哨兵
     * 只改这一个开关，刷新走这里，避免每次焦点变化都重绘海报行。
     */
    private fun refreshSiteTabs() {
        if (siteRecycler.isComputingLayout) {
            if (siteTabsPending) return
            siteTabsPending = true
            siteRecycler.post {
                siteTabsPending = false
                refreshSiteTabs()
            }
            return
        }
        siteAdapter.submit(siteLabels(), selectedSite, resultsFocused)
    }

    private fun emptyHint(): String {
        if (drillStack.isNotEmpty()) {
            return if (drillLoading) "正在加载目录…" else "该目录暂无内容"
        }
        val keyword = input.text.toString().trim()
        if (keyword.isEmpty() || keyword != searchedKeyword) return "输入关键词后按搜索键"
        if (searching) {
            val progress = searchProgress
            return if (progress != null && progress.total() > 0) {
                "搜索中 ${progress.current()}/${progress.total()}"
            } else {
                "搜索中…"
            }
        }
        return if (searchProgress != null) "没有找到相关内容" else "输入关键词后按搜索键"
    }

    /** 站点 Tab 落焦点；焦点即选中，下方海报立刻切到该站点。 */
    private fun focusSite(index: Int) {
        if (input.text.toString().trim().isEmpty()) {
            focusInput()
            return
        }
        if (siteAdapter.itemCount == 0) return
        val target = index.coerceIn(0, siteAdapter.itemCount - 1)
        selectedSite = target
        focusedSite = target
        render()
        siteRecycler.post {
            if (!siteAdapter.focusTab(siteRecycler, target)) {
                siteRecycler.post { siteAdapter.focusTab(siteRecycler, target) }
            }
        }
    }

    private fun onSiteFocused(index: Int) {
        focusedSite = index
        resultsFocused = true
        if (selectedSite != index) {
            selectedSite = index
            render()
        } else {
            refreshSiteTabs()
        }
    }

    private fun onSiteConfirm(index: Int) {
        if (drillStack.isNotEmpty()) {
            // 下钻时 Tab 行只有「⬅️ 返回上一级」这一项，确定键 = 出栈。
            exitDrillDown()
            return
        }
        selectedSite = index
        focusedSite = index
        render()
    }

    private fun onSiteKey(position: Int, keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                focusRightFirstChip()
                true
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                focusPoster(0)
                true
            }

            KeyEvent.KEYCODE_DPAD_LEFT -> {
                focusSite(position - 1)
                true
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                focusSite(position + 1)
                true
            }

            else -> false
        }
    }

    /** 站点 Tab ↑ / 键盘最右列 → 右侧第一枚胶囊（没有历史就是热搜 / 建议第一条）。 */
    private fun focusRightFirstChip() {
        val recycler = if (history.isNotEmpty()) historyRecycler else wordRecycler
        val adapter = if (history.isNotEmpty()) historyAdapter else wordAdapter
        recycler.post {
            val view = adapter.firstView(recycler) ?: return@post
            view.requestFocus()
        }
    }

    /** 胶囊 ↓ 到底时的兜底落点：回到刚才高亮的站点 Tab（而不是盲目落到第一个）。 */
    private fun focusResultsFromChips(): Boolean {
        if (input.text.toString().trim().isEmpty()) return false
        focusSite(focusedSite)
        return true
    }

    private fun focusPoster(index: Int) {
        if (resultAdapter.itemCount == 0) return
        val target = index.coerceIn(0, resultAdapter.itemCount - 1)
        posterRecycler.post {
            if (!resultAdapter.focusCard(posterRecycler, target)) {
                posterRecycler.post { resultAdapter.focusCard(posterRecycler, target) }
            }
        }
    }

    private fun onPosterKey(position: Int, keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (position > 0) focusPoster(position - 1)
                true
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (position < resultAdapter.itemCount - 1) focusPoster(position + 1)
                true
            }

            KeyEvent.KEYCODE_DPAD_UP -> {
                focusSite(focusedSite)
                true
            }

            else -> false
        }
    }

    // ------------------------------------------------------------------
    // 数据观察
    // ------------------------------------------------------------------

    private fun observeViewModel() {
        // 搜索结果：`searchContent(List<Site>, ...)` 是**每个站点各回一条 Result**，
        // 所以必须累加；同一站点重复回调时按「站点 key + vod id」去重。
        siteViewModel.search.observe(this) { result: Result? ->
            if (result == null) return@observe
            val incoming = result.list
            if (incoming.isEmpty()) return@observe
            val merged = (searchResults + incoming).distinctBy { it.site?.key to it.id }
            searchResults = merged
            render()
            Log.d(
                TAG_SEARCH,
                "收到结果: site=${incoming.firstOrNull()?.site?.key} 本批=${incoming.size} 累计=${merged.size}",
            )
        }

        // 进度：`current/total` 用来做「搜索中 x/y」，`finished` 一到就退出加载态。
        siteViewModel.searchProgress.observe(this) { progress: SearchProgress? ->
            searchProgress = progress
            if (progress != null && progress.finished()) {
                searching = false
                Log.d(TAG_SEARCH, "搜索完成: ${progress.current()}/${progress.total()} 累计=${searchResults.size}")
            }
            render()
        }

        // `result` 通道（详情 / 分类共用）：优先处理「补详情后直达选集」，否则当作下钻目录结果。
        siteViewModel.result.observe(this) { result: Result? ->
            if (result == null) return@observe
            if (pendingPlayId != null) {
                val incoming = result.vod
                val flags = incoming?.flags.orEmpty()
                Log.e(
                    TAG_SEARCH,
                    "详情返回(直达选集): id=${incoming?.id} flags=${flags.size} " +
                        "集数=${flags.sumOf { it.episodes.orEmpty().size }} code=${result.code} msg=${result.msg}",
                )
                pendingPlayId = null
                if (flags.isEmpty()) {
                    Notify.show("该剧暂无可用线路")
                } else {
                    // 详情结果比搜索结果完整：剧名 / 海报 / 线路都以它为准。
                    if (incoming != null && incoming.name.isNotBlank()) playVod = incoming
                    playFlags = flags
                    showPlayDialog()
                }
                return@observe
            }
            if (drillStack.isEmpty()) return@observe
            val current = drillStack.last().site
            // 分类接口不会给每条结果盖 site（原版整页共用一个 key），这里补上：
            // 卡片左上角的站点标签、点开后的选集弹窗都要靠它。
            drillResults = result.list.map { vod ->
                if (vod.site == null) vod.apply { site = current } else vod
            }
            drillLoading = false
            render()
            Log.e(
                TAG_SEARCH,
                "下钻结果: depth=${drillStack.size} id=${drillStack.last().vod.id} " +
                    "listSize=${result.list.size} code=${result.code} msg=${result.msg}",
            )
            // 目录结果到达后把焦点落到第一张海报；空目录 / 失败则落到「⬅️ 返回上一级」。
            posterRecycler.post {
                if (drillResults.isNotEmpty()) focusPoster(0) else focusSite(0)
            }
        }
    }

    // ------------------------------------------------------------------
    // 背景
    // ------------------------------------------------------------------

    /**
     * 背景 = 首页大图 → 160×90 解码 → 盒式模糊（两趟 ≈ 高斯）→ 放大铺满，
     * 再叠 60% 黑遮罩 + 底部渐隐。没有大图时保留 XML 里的深蓝黑渐变兜底。
     */
    private fun loadBackdrop(url: String?) {
        val backdropUrl = url?.takeIf { it.isNotBlank() } ?: return
        lifecycleScope.launch {
            val bitmap = HeroImageLoader.blurredBackdrop(
                context = this@HeroSearchActivity,
                url = backdropUrl,
                width = BACKDROP_SAMPLE_WIDTH,
                height = BACKDROP_SAMPLE_HEIGHT,
                blurRadius = BACKDROP_BLUR_RADIUS,
            ) ?: return@launch
            backdrop.setImageBitmap(bitmap)
            backdrop.visibility = View.VISIBLE
            scrim.visibility = View.VISIBLE
            bottomFade.visibility = View.VISIBLE
        }
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun isDescendant(parent: View, child: View): Boolean {
        var node: View? = child
        while (node != null) {
            if (node === parent) return true
            node = node.parent as? View
        }
        return false
    }

    /**
     * 读历史：`Prefers("keyword")` 里存的是 JSON 字符串数组。
     * 任何脏数据（旧版本写坏的、手工改过的）都退化成空列表，绝不让搜索页因为历史记录崩掉。
     */
    private fun readHistory(): List<String> {
        val raw = Setting.getKeyword()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            App.gson().fromJson<List<String>>(raw, object : TypeToken<List<String>>() {}.type)
        }.getOrNull()
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?.take(HISTORY_LIMIT)
            ?: emptyList()
    }

    /**
     * 解析热词 / 建议词：两个接口的外层结构恰好都能被 [Word] 吃掉
     * （360kan `data[].title`、爱奇艺 `data[].name`，`Word.Data` 上就是 title/name 双名）。
     */
    private fun parseWords(json: String): List<String> {
        if (json.isBlank()) return emptyList()
        return Word.objectFrom(json).data
            .map { it.title.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private fun Vod.isDrillDownFolder(): Boolean =
        isFolder || id.startsWith(ATVP_FOLDER_PREFIX, ignoreCase = true)

    /**
     * 下钻栈的一层：folder 条目 + 它所属的源。
     *
     * 存整个 [Site] 而不是 key：请求要原样带上 `api / type / ext`，
     * 只留 key 再回查 `VodConfig` 在「临时站点」场景下会查成空 Site。
     */
    private data class DrillEntry(val site: Site, val vod: Vod)

    companion object {

        private const val EXTRA_BACKDROP = "hero_backdrop"

        private const val TAG_SEARCH = "HeroSearch"

        // 历史最多 9 条（输入上限 19 字在 SearchViewAdapters 里）。
        private const val HISTORY_LIMIT = 9

        // 区块标题文案（与 topbar_search_page.png 一致）。
        private const val TITLE_HOT = "热搜"
        private const val TITLE_SUGGEST = "建议"

        // 结果区第一个站点 Tab：聚合所有源的结果（同原版 `dz2` 里第一项「全部」）。
        private const val TITLE_ALL = "全部"

        /** 下钻时站点 Tab 行里唯一的按钮文案（确定键 = 出栈，等价于返回键）。 */
        private const val DRILL_BACK_LABEL = "⬅️ 返回上一级"

        /** 网盘聚合插件的 folder 壳前缀（与 `Vod.isFolder()` 的 `vod_tag=folder` 等价）。 */
        private const val ATVP_FOLDER_PREFIX = "atvp_detail:"

        // 热搜：360kan 排行榜（`SearchActivityV2.X1()` 的原地址与原 Referer，一字不改）。
        private const val HOT_URL = "https://api.web.360kan.com/v1/rank?cat=1"
        private const val HOT_REFERER = "https://www.360kan.com/rank/general"

        // 建议词防抖：电视键盘逐字输入，250ms 内的连续按键只打一次接口。
        private const val SUGGEST_DEBOUNCE_MS = 250L

        // 背景：160×90 采样 + 半径 8 的盒式模糊（≈ 屏幕上 96px 的模糊）。
        private const val BACKDROP_SAMPLE_WIDTH = 160
        private const val BACKDROP_SAMPLE_HEIGHT = 90
        private const val BACKDROP_BLUR_RADIUS = 8

        // 右列「历史」与「热搜 / 建议」之间的间隔。
        private const val RIGHT_SECTION_GAP_DP = 16

        // 站点 Tab 间距 12dp（原版 dz2 `setHorizontalSpacing(12dp)`）；结果卡间距 16dp。
        private const val SITE_ITEM_GAP_DP = 12
        private const val POSTER_ITEM_GAP_DP = 16

        /** 统一入口：首页顶栏「搜索」调用，并把首页当前那张大图一起带过来做磨砂背景。 */
        fun start(context: Context, backdropUrl: String? = null) {
            context.startActivity(
                Intent(context, HeroSearchActivity::class.java).putExtra(EXTRA_BACKDROP, backdropUrl),
            )
        }
    }
}
