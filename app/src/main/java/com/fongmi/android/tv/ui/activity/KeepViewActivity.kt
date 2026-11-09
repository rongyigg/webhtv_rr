package com.fongmi.android.tv.ui.activity

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.dispose
import coil3.load
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.fongmi.android.tv.App
import com.fongmi.android.tv.R
import com.fongmi.android.tv.bean.Keep
import com.fongmi.android.tv.bean.TmdbItem
import com.fongmi.android.tv.bean.TmdbMatchCache
import com.fongmi.android.tv.event.RefreshEvent
import com.fongmi.android.tv.setting.Setting
import com.fongmi.android.tv.ui.custom.HeroImageView
import com.fongmi.android.tv.ui.custom.ProgressLayout
import com.fongmi.android.tv.ui.hero.ChangeSourceDialog
import com.fongmi.android.tv.ui.hero.HeroHorizontalGapDecoration
import com.fongmi.android.tv.ui.hero.HeroImageLoader
import com.fongmi.android.tv.ui.hero.HeroImagePrefetch
import com.fongmi.android.tv.ui.hero.KeepPosterAdapter
import com.fongmi.android.tv.utils.ImgUtil
import com.fongmi.android.tv.utils.Task
import com.fongmi.android.tv.utils.Util
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

/**
 * 收藏页（100% 纯 View，替代 leanback / mobile 两个 flavor 里的旧 `KeepActivity` 列表）。
 *
 * 结构、渲染、焦点逻辑与已验收的 [HistoryViewActivity] 同一套（同布局骨架 + 同一个
 * `HeroImageView` / `ProgressLayout` / TMDB 缓存链），差别只有三处：
 * 1. 数据源是 Room 的 `Keep` 表（[Keep.getVod]，`type = 0` 且已按 `createTime DESC`）；
 * 2. 卡片不复用 `HeroPosterCard`：收藏页要求「站点名在海报上方、海报 120×180、剧名 13sp 跑马灯」，
 *    见 `adapter_keep_poster_view.xml` + [KeepPosterAdapter]；
 * 3. 交互按需求复刻逆向 `KeepPosterPresenter` / `pp.java` case 16-18：
 *    - 焦点卡片 → Hero 大图 / 艺术字 / 简介联动（TMDB 缓存命中才换）；
 *    - **长按确定键** → 换源弹窗（[ChangeSourceDialog]，候选源排除当前接口源）；
 *    - **MENU 键** → 编辑模式（所有卡片盖垃圾桶遮罩），返回键退出；
 *    - 编辑模式确定键 = 删除（`Keep.delete()` → `notifyItemRemoved` → 焦点落到下一张）。
 *
 * 本文件不引用任何 Compose 类型。
 */
class KeepViewActivity : AppCompatActivity() {

    private lateinit var heroBg: HeroImageView
    private lateinit var maskLeft: View
    private lateinit var maskBottom: View
    private lateinit var pageSite: TextView
    private lateinit var posterCard: FrameLayout
    private lateinit var posterCardImg: ImageView
    private lateinit var heroNameText: TextView
    private lateinit var heroLogoImg: ImageView
    private lateinit var heroMetaText: TextView
    private lateinit var heroDescText: TextView
    private lateinit var progressLayout: ProgressLayout
    private lateinit var keepRecycler: RecyclerView

    private lateinit var posterAdapter: KeepPosterAdapter

    private var selectedIndex = -1
    private var tmdbCache: TmdbMatchCache? = null

    /** 编辑模式（需求：MENU 进入，返回键退出）。 */
    private var isEditMode = false

    private var currentLogoUrl = ""

    /** 上一次真正交给 `ImgUtil` 的竖图地址；`null` = 还没加载过。 */
    private var currentPosterUrl: String? = null

    /** 读库回包序号：快速切换 / 退出时丢弃过期结果。 */
    private var loadToken = 0

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Setting.wrapDisplay(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 图片引擎先就绪：HeroImageView / 卡片 / posterCard 都走同一个 Coil 单例。
        HeroImageLoader.install()
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        WindowCompat.setDecorFitsSystemWindows(window, false)
        Util.hideSystemUI(this)

        setContentView(R.layout.activity_keep_view)
        bindViews()
        setupUi()
        load(keepSelection = false)
    }

    override fun onStart() {
        super.onStart()
        if (!EventBus.getDefault().isRegistered(this)) EventBus.getDefault().register(this)
    }

    override fun onStop() {
        if (EventBus.getDefault().isRegistered(this)) EventBus.getDefault().unregister(this)
        super.onStop()
    }

    override fun onDestroy() {
        loadToken++
        heroLogoImg.dispose()
        // posterCard 走 Glide（`ImgUtil`），按视图生命周期释放。
        ImgUtil.clear(posterCardImg)
        super.onDestroy()
    }

    /**
     * 编辑态返回键只退出编辑模式，不退出页面（`KeepActivity.onBackInvoked` 同语义）。
     *
     * 工程已打开 `enableOnBackInvokedCallback`，但本页没有注册任何启用的
     * `OnBackPressedCallback`；`OnBackPressedDispatcher` 只有在存在启用回调时才向系统
     * 注册 `OnBackInvokedCallback`，所以系统返回键仍然走 `Activity.onBackPressed()` 虚分派。
     */
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (isEditMode) {
            exitEditMode()
            return
        }
        super.onBackPressed()
    }

    /**
     * MENU 键 = 进入 / 退出编辑模式。
     *
     * 逆向原版是「长按卡片进入编辑态」，但本页长按已按需求改为「换源」，
     * 所以编辑态改由遥控器菜单键触发；再按一次菜单键等价于返回键（退出编辑态）。
     * `repeatCount == 0` 保证按住不放只切一次。
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.repeatCount == 0) toggleEditMode()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /** 收藏数据变更（详情页收藏 / 取消收藏）→ 重新读库（`RefreshEvent.Type.KEEP`）。 */
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onRefreshEvent(event: RefreshEvent) {
        if (event.type == RefreshEvent.Type.KEEP) load(keepSelection = true)
    }

    // ------------------------------------------------------------------
    // 初始化
    // ------------------------------------------------------------------

    private fun bindViews() {
        heroBg = findViewById(R.id.heroBg)
        maskLeft = findViewById(R.id.maskLeft)
        maskBottom = findViewById(R.id.maskBottom)
        pageSite = findViewById(R.id.pageSite)
        posterCard = findViewById(R.id.posterCard)
        posterCardImg = findViewById(R.id.posterCardImg)
        heroNameText = findViewById(R.id.heroNameText)
        heroLogoImg = findViewById(R.id.heroLogoImg)
        heroMetaText = findViewById(R.id.heroMetaText)
        heroDescText = findViewById(R.id.heroDescText)
        progressLayout = findViewById(R.id.progressLayout)
        keepRecycler = findViewById(R.id.keepRecycler)
    }

    private fun setupUi() {
        // 遮罩与大图在视图创建时置为可见（无数据时上层是纯黑）。
        maskLeft.visibility = View.VISIBLE
        maskBottom.visibility = View.VISIBLE
        pageSite.visibility = View.GONE
        posterCard.visibility = View.GONE
        heroBg.visibility = View.GONE
        heroNameText.visibility = View.INVISIBLE
        heroLogoImg.visibility = View.GONE
        heroMetaText.visibility = View.INVISIBLE
        heroDescText.visibility = View.INVISIBLE

        // 剧名超宽以跑马灯展示，最大宽度 = 屏宽 × 0.6（与历史页同一规则）。
        heroNameText.isSingleLine = true
        heroNameText.ellipsize = TextUtils.TruncateAt.MARQUEE
        heroNameText.marqueeRepeatLimit = -1
        heroNameText.isSelected = true
        heroNameText.maxWidth = (resources.displayMetrics.widthPixels * HERO_NAME_WIDTH_RATIO).toInt()

        posterAdapter = KeepPosterAdapter(
            onFocused = { position -> select(position) },
            // 编辑态确定键 = 删除；普通态 = 进详情（`pp.java` case 17/18）。
            onClick = { position -> if (isEditMode) deleteAt(position) else openDetail(position) },
            // 长按确定键 = 换源（需求 3.2）。
            onLongPress = { position -> changeSource(position) },
        )
        keepRecycler.apply {
            layoutManager = LinearLayoutManager(this@KeepViewActivity, RecyclerView.HORIZONTAL, false)
            adapter = posterAdapter
            itemAnimator = null
            setHasFixedSize(true)
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            clipChildren = false
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            addItemDecoration(HeroHorizontalGapDecoration(dp(16)))
        }

        progressLayout.showProgress()
    }

    // ------------------------------------------------------------------
    // 数据
    // ------------------------------------------------------------------

    /**
     * 读库并刷新列表。
     *
     * 数据唯一来源是真实 Room 收藏表：`Keep.getVod()` 的 SQL 已经是
     * `WHERE type = 0 ORDER BY createTime DESC`，这里再排一次是防 DAO 被改坏（幂等）。
     */
    private fun load(keepSelection: Boolean) {
        val token = ++loadToken
        val previousKey = if (keepSelection) selectedKeep()?.key else null
        val hadFocus = currentFocus != null
        Task.execute {
            val cache = runCatching { Setting.getTmdbMatchCache() }.getOrNull()
            val loaded = runCatching {
                Keep.getVod().sortedByDescending { it.createTime }
            }.getOrNull() ?: emptyList()
            App.post {
                if (token != loadToken) return@post
                tmdbCache = cache
                posterAdapter.submit(loaded)
                if (loaded.isEmpty()) {
                    exitEditMode()
                    clearSelection()
                    progressLayout.showEmpty()
                    return@post
                }
                val index = when {
                    previousKey == null -> 0
                    else -> loaded.indexOfFirst { it.key == previousKey }.takeIf { it >= 0 } ?: 0
                }
                select(index)
                progressLayout.showContent()
                if (!hadFocus) keepRecycler.post { focusPoster(index) }
            }
        }
    }

    private fun selectedKeep(): Keep? = posterAdapter.getItem(selectedIndex)

    /** 选中第 [index] 条：Hero 文本 / 艺术字 / 横图一次性换齐。 */
    private fun select(index: Int) {
        val keep = posterAdapter.getItem(index) ?: return
        selectedIndex = index
        bindHero(keep, index)
        prefetch(index)
    }

    private fun clearSelection() {
        selectedIndex = -1
        currentLogoUrl = ""
        currentPosterUrl = null
        HeroImageView.setSharedUrl("")
        heroBg.reset()
        heroBg.visibility = View.GONE
        posterCard.visibility = View.GONE
        pageSite.visibility = View.GONE
        heroNameText.visibility = View.INVISIBLE
        heroLogoImg.visibility = View.GONE
        heroMetaText.visibility = View.INVISIBLE
        heroDescText.visibility = View.INVISIBLE
    }

    // ------------------------------------------------------------------
    // 编辑模式 / 删除（需求 3.3 / 3.4）
    // ------------------------------------------------------------------

    private fun toggleEditMode() {
        if (isEditMode) exitEditMode() else enterEditMode()
    }

    /** 进入编辑模式：所有卡片一起显示垃圾桶遮罩（适配器级开关）。 */
    private fun enterEditMode() {
        if (isEditMode || posterAdapter.itemCount == 0) return
        isEditMode = true
        posterAdapter.setEditMode(true)
    }

    /** 退出编辑模式：只改状态与遮罩可见性，不动列表、不动焦点。 */
    private fun exitEditMode() {
        if (!isEditMode) return
        isEditMode = false
        posterAdapter.setEditMode(false)
    }

    /**
     * 编辑态确定键 = 删除这一条（`pp.java` case 17 → `KeepActivity.e2` → `keep.delete()`）。
     *
     * 删除后按需求 `notifyItemRemoved(position)`，焦点落到原位置的下一张
     * （删掉最后一张时回落到新的最后一张）；列表被删空则退出编辑态并进空态。
     */
    private fun deleteAt(position: Int) {
        val keep = posterAdapter.getItem(position) ?: return
        keep.delete()
        posterAdapter.removeAt(position)
        if (posterAdapter.itemCount == 0) {
            exitEditMode()
            clearSelection()
            progressLayout.showEmpty()
            return
        }
        val next = position.coerceIn(0, posterAdapter.itemCount - 1)
        select(next)
        keepRecycler.post { focusPoster(next) }
    }

    // ------------------------------------------------------------------
    // Hero 渲染
    // ------------------------------------------------------------------

    private fun bindHero(keep: Keep, position: Int) {
        val name = keep.vodName.orEmpty()
        val siteName = keep.siteName.orEmpty()

        // 左上角站点角标：当前条目所属接口源，空串直接 GONE。
        pageSite.text = siteName
        pageSite.visibility = if (siteName.isEmpty()) View.GONE else View.VISIBLE

        heroNameText.text = name

        // 元数据行：TMDB 副标题（「电影 · 2024 · 评分 8.6」）优先，未命中时回落站点名。
        val item = tmdbItem(keep)
        val meta = item?.subtitle.orEmpty().ifBlank { siteName }
        heroMetaText.text = meta
        heroMetaText.visibility = if (meta.isEmpty()) View.INVISIBLE else View.VISIBLE

        // 简介：TMDB 译文；XML 里写死 maxLines=2 + ellipsize=end。
        val desc = item?.overview.orEmpty()
        heroDescText.text = desc
        heroDescText.visibility = if (desc.isEmpty()) View.INVISIBLE else View.VISIBLE

        bindLogo(keep, name)
        bindBackdrop(keep, position)
    }

    /**
     * 艺术字绑定：命中缓存 → 隐藏剧名、显示 `heroLogoImg`；
     * 未命中或加载失败 → 回落 32sp 文字标题（与历史页同一段逻辑）。
     */
    private fun bindLogo(keep: Keep, name: String) {
        val url = tmdbLogo(keep)
        if (url.isEmpty()) {
            currentLogoUrl = ""
            heroLogoImg.dispose()
            heroLogoImg.visibility = View.GONE
            heroNameText.text = name
            heroNameText.visibility = if (name.isEmpty()) View.INVISIBLE else View.VISIBLE
            return
        }
        // 同一张 Logo 已经画在屏幕上：异步重绘不再闪一次淡入。
        if (url == currentLogoUrl && heroLogoImg.visibility == View.VISIBLE) return
        currentLogoUrl = url
        heroNameText.visibility = View.GONE
        heroLogoImg.visibility = View.VISIBLE
        heroLogoImg.load(url) {
            crossfade(LOGO_CROSSFADE_MS)
            listener(object : ImageRequest.Listener {
                override fun onError(request: ImageRequest, result: ErrorResult) {
                    // 焦点可能已经移走：只处理「当前仍在显示这张 Logo」的失败。
                    if (currentLogoUrl == url) {
                        currentLogoUrl = ""
                        heroLogoImg.visibility = View.GONE
                        heroNameText.text = name
                        heroNameText.visibility = if (name.isEmpty()) View.INVISIBLE else View.VISIBLE
                    }
                }
            })
        }
    }

    /**
     * 大图 / 竖图二选一（与历史页同规则）：
     * - 有 TMDB 横图 → `heroBg.loadImage()`，同时隐藏右上角 posterCard；
     * - 没有横图 → `heroBg.reset()`，显示 posterCard 并加载 `vodPic`。
     *
     * 横图**只认真横图**：`TmdbItem.backdropUrl` 为空就是空，绝不拿竖图拉伸铺满全屏。
     */
    private fun bindBackdrop(keep: Keep, position: Int) {
        val backdrop = tmdbBackdrop(keep)
        if (backdrop.isNotEmpty()) {
            posterCard.visibility = View.GONE
            HeroImageView.setSharedUrl(backdrop)
            heroBg.visibility = View.VISIBLE
            heroBg.loadImage(backdrop, position, null)
            return
        }
        HeroImageView.setSharedUrl("")
        heroBg.reset()
        heroBg.visibility = View.GONE
        posterCard.visibility = View.VISIBLE
        loadPosterCard(keep)
    }

    /** 右上角竖图：走 `ImgUtil`（解析图片指令 + 失败回落剧名首字），与卡片同一套。 */
    private fun loadPosterCard(keep: Keep) {
        val url = keep.vodPic.orEmpty()
        if (url == currentPosterUrl) return
        currentPosterUrl = url
        if (url.isBlank()) {
            ImgUtil.clear(posterCardImg)
            posterCardImg.setImageResource(R.drawable.shape_source_poster_placeholder)
            return
        }
        ImgUtil.load(keep.vodName.orEmpty(), url, posterCardImg, true)
    }

    // ------------------------------------------------------------------
    // TMDB 查询（与历史页同一条本地缓存链）
    // ------------------------------------------------------------------

    private fun tmdbItem(keep: Keep): TmdbItem? {
        val cache = tmdbCache ?: return null
        val siteKey = keepSiteKey(keep)
        val vodId = keepVodId(keep)
        if (siteKey.isEmpty() || vodId.isEmpty()) return null
        // `find` 内部已经「手动匹配优先」（`findEntry` → `findManualEntry`）。
        return runCatching {
            cache.find(siteKey, vodId, keep.vodName.orEmpty())
        }.getOrNull()
    }

    /** 横图地址：命中后提档到 w1920（与首页 `highResBackdrop` 同一条 `upgradeTmdbImage`）。 */
    private fun tmdbBackdrop(keep: Keep): String {
        val url = tmdbItem(keep)?.backdropUrl.orEmpty()
        if (url.isEmpty()) return ""
        return runCatching { upgradeTmdbImage(url, TMDB_BACKDROP_WIDTH) }.getOrDefault(url)
    }

    /**
     * 艺术字：三级同步查找（全是内存查表，焦点切换不会卡 IO）：
     * 1. 进程内详情 [HeroTmdbPrefetchCache]：最新，且一定带 Logo；
     * 2. 磁盘 `hero_logo_cache`（[HeroLogoCache]，key = siteKey|vodId）；
     * 3. 跨源键 `tmdb|mediaType|tmdbId`：换源后 vodId 变了仍能命中。
     */
    private fun tmdbLogo(keep: Keep): String {
        val siteKey = keepSiteKey(keep)
        if (siteKey.isEmpty()) return ""
        val vodId = keepVodId(keep)
        val posterKey = heroTmdbKey(siteKey, vodId.ifBlank { keep.vodName.orEmpty() })
        HeroTmdbPrefetchCache.peek(posterKey)?.logoUrl?.takeIf { it.isNotBlank() }?.let { return it }
        HeroLogoCache.get(posterKey).takeIf { it.isNotBlank() }?.let { return it }
        return HeroLogoCache.get(tmdbLogoKey(tmdbItem(keep)))
    }

    /**
     * `Keep.key` = `siteKey#vodId`：`getSiteKey()` / `getVodId()` 直接按分隔符切分，
     * 脏数据（没有分隔符）会抛 `ArrayIndexOutOfBounds`，所以两处都兜住。
     */
    private fun keepSiteKey(keep: Keep): String = runCatching { keep.siteKey }.getOrNull().orEmpty()

    private fun keepVodId(keep: Keep): String = runCatching { keep.vodId }.getOrNull().orEmpty()

    /** 预热 ±2 项的海报与横图（与历史页同一个窗口）。 */
    private fun prefetch(index: Int) {
        for (i in (index - PREFETCH_RANGE)..(index + PREFETCH_RANGE)) {
            val keep = posterAdapter.getItem(i) ?: continue
            keep.vodPic.orEmpty().takeIf { it.isNotEmpty() }?.let { HeroImagePrefetch.poster(it) }
            tmdbBackdrop(keep).takeIf { it.isNotEmpty() }?.let { HeroImagePrefetch.backdrop(it) }
        }
    }

    // ------------------------------------------------------------------
    // 交互：详情 / 换源
    // ------------------------------------------------------------------

    /**
     * 确定键（普通态）：`pp.java` case 18 → `DetailActivity.q2(...)`。
     * 收藏条目只存了 siteKey / vodId / 名称 / 海报，备注与横图留空，由详情页自己补。
     */
    @Suppress("DEPRECATION")
    private fun openDetail(position: Int) {
        val keep = posterAdapter.getItem(position) ?: return
        if (isFinishing || isDestroyed) return
        HeroDetailActivity.start(
            this,
            keepSiteKey(keep),
            keepVodId(keep),
            keep.vodName.orEmpty(),
            keep.vodPic.orEmpty(),
            "",
            "",
            noAnimation = true,
        )
        overridePendingTransition(0, 0)
    }

    /**
     * 长按确定键：换源弹窗（[ChangeSourceDialog]）。
     *
     * 候选源用 `ChangeSourceDialog.candidates(currentSiteKey)` —— 内部已经排除当前源、
     * 隐藏源、用户屏蔽源与非可搜索源，并按站点健康度排序。关键词就是这张收藏的剧名。
     * 弹窗内选中结果后会直接进详情页（与原版换源后的 `DetailActivity.q2` 同一条路径）。
     */
    @Suppress("DEPRECATION")
    private fun changeSource(position: Int) {
        val keep = posterAdapter.getItem(position) ?: return
        if (isFinishing || isDestroyed) return
        val keyword = keep.vodName.orEmpty()
        if (keyword.isBlank()) return
        val currentKey = keepSiteKey(keep)
        val sites = runCatching { ChangeSourceDialog.candidates(currentKey) }.getOrDefault(emptyList())
        ChangeSourceDialog.show(
            context = this,
            sites = sites,
            keyword = keyword,
            // 换源要尽量多搜出结果：quick=true 会跳过 quickSearch=0 的源。
            quick = false,
            onSourceSelected = { vod, site ->
                HeroDetailActivity.start(this, vod, site, noAnimation = true)
                overridePendingTransition(0, 0)
            },
            onClosed = {
                // 弹窗关闭后把焦点还给当前卡片，避免焦点停在已销毁的 Dialog 窗口里。
                keepRecycler.post { focusPoster(selectedIndex) }
            },
        )
    }

    // ------------------------------------------------------------------
    // 焦点
    // ------------------------------------------------------------------

    private fun focusPoster(index: Int) {
        if (index !in 0 until posterAdapter.itemCount) return
        val manager = keepRecycler.layoutManager as? LinearLayoutManager ?: return
        keepRecycler.post {
            manager.scrollToPositionWithOffset(index, 0)
            keepRecycler.post {
                keepRecycler.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus()
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {

        /** 剧名最大宽度 = 屏宽 × 0.6（与历史页 `nm3.i` 同一规则）。 */
        private const val HERO_NAME_WIDTH_RATIO = 0.6f

        private const val TMDB_BACKDROP_WIDTH = "w1920"
        private const val LOGO_CROSSFADE_MS = 200
        private const val PREFETCH_RANGE = 2

        /** `@JvmStatic`：`HomeWebBridge.java` 是 Java 调用方，需要静态入口。 */
        @JvmStatic
        fun start(context: Context) {
            context.startActivity(
                Intent(context, KeepViewActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION),
            )
        }
    }
}
