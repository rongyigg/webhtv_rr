package com.fongmi.android.tv.ui.activity

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import com.fongmi.android.tv.api.config.VodConfig
import com.fongmi.android.tv.bean.Flag
import com.fongmi.android.tv.bean.Keep
import com.fongmi.android.tv.bean.Result
import com.fongmi.android.tv.bean.Site
import com.fongmi.android.tv.bean.TmdbConfig
import com.fongmi.android.tv.bean.Vod
import com.fongmi.android.tv.db.AppDatabase
import com.fongmi.android.tv.event.RefreshEvent
import com.fongmi.android.tv.model.SiteViewModel
import com.fongmi.android.tv.service.TmdbService
import com.fongmi.android.tv.setting.Setting
import com.fongmi.android.tv.setting.TmdbSitePolicy
import com.fongmi.android.tv.ui.helper.TmdbMatcher
import com.fongmi.android.tv.ui.hero.HeroTmdbPrefetchCache
import com.fongmi.android.tv.ui.hero.heroTmdbKey
import com.fongmi.android.tv.utils.ImgUtil
import com.fongmi.android.tv.utils.Notify
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 详情页状态机（纯逻辑，**不含任何 UI / 布局代码**）。
 *
 * 重构前这些状态与副作用体散在 `HeroDetailScreen` 的 Composable 里（Plan B 第 1 步已抽出）。
 * 抽出的边界刻意定在「逻辑」而不是「生命周期」：
 *
 * - **状态**：全部用 Compose `mutableStateOf` / `mutableIntStateOf` 持有。Compose 侧仍然
 *   `by` 委托同一份 state 读写，因此重组触发点、首帧初值与重构前**逐字节等价**。
 * - **副作用体**：观察者分流、跨源兜底、folder 下探、TMDB 加载、收藏读写、弹窗关闭后的
 *   焦点恢复都在这里；Compose 侧只留一层 `LaunchedEffect(key) { manager.xxx() }` 的壳。
 *   **key 必须留在 Compose**：`LaunchedEffect` 的 key 变化 = 取消上一次 + 重跑，这个时序
 *   语义在纯 Kotlin 里手写 `Job` 取消无法保证完全一致，挪走就是可见行为变化。
 * - **留在 UI 子件的**：`EpisodeViewDialog` 的 `DisposableEffect(playFlags)`（Dialog 窗口
 *   生命周期）与 `HeroActorDialog` 的 `LaunchedEffect(actor.id)`（弹窗实例自持缓存）。
 *   它们的作用域就是那个弹窗，搬到 Activity 级反而会改变缓存/关闭时机，见行为清单。
 *
 * 因此本文件可以直接被将来的纯 View 详情页复用：把 `mutableStateOf` 换成
 * `Observable` / 回调即可，业务判断一行不用重写。
 *
 * @param inlineVod 传入 Vod 自带线路时（非空）表示跳过网络请求直接渲染。
 */
internal class HeroDetailStateManager(
    private val activity: Activity,
    private val siteViewModel: SiteViewModel,
    private val vodId: String,
    private val siteKey: String,
    private val siteName: String,
    inlineVod: Vod?,
) {

    // ------------------------------------------------------------------
    // 状态槽
    //
    // 每个状态都成对出现：
    // - `xxxSlot`：给 Compose 侧 `by` 委托（`var x by state.xxxSlot`），读写即快照读写；
    // - `xxx`：给本类内部当普通属性用，让搬过来的逻辑体保持原样（少改一个字就少一个 bug）。
    // ------------------------------------------------------------------
    val detailResultSlot = mutableStateOf(inlineVod?.let { Result.vod(it) })
    var detailResult by detailResultSlot

    val tmdbStateSlot = mutableStateOf<TmdbDetailState>(TmdbDetailState.Idle)
    var tmdbState by tmdbStateSlot

    // ---- 弹窗 / 跨源播放状态 ----
    val showSourceDialogSlot = mutableStateOf(false)
    var showSourceDialog by showSourceDialogSlot

    val showPlayDialogSlot = mutableStateOf(false)
    var showPlayDialog by showPlayDialogSlot

    /** 选集弹窗实际渲染的线路：默认取本页详情；跨源兜底时换成那个源的线路。 */
    val playFlagsSlot = mutableStateOf<List<Flag>>(emptyList())
    var playFlags by playFlagsSlot

    val playVodSlot = mutableStateOf<Vod?>(null)
    var playVod by playVodSlot

    /** 选集弹窗起播用的源 key：默认本页源，跨源兜底时是预搜索命中的那个源。 */
    val playSiteKeySlot = mutableStateOf(siteKey)
    var playSiteKey by playSiteKeySlot

    /** 非 null = 正在等「播放兜底」那个源的详情回包；回包一到直接弹选集。 */
    val pendingPlayIdSlot = mutableStateOf<String?>(null)
    var pendingPlayId by pendingPlayIdSlot

    val pendingPlaySiteSlot = mutableStateOf<Site?>(null)
    var pendingPlaySite by pendingPlaySiteSlot

    val pendingPlayVodSlot = mutableStateOf<Vod?>(null)
    var pendingPlayVod by pendingPlayVodSlot

    /** true = 用户点了「播放」但当前没有线路，正在等**本页**详情回包后自动弹选集。 */
    val pendingPlayOpenSlot = mutableStateOf(false)
    var pendingPlayOpen by pendingPlayOpenSlot

    /** 选集数据加载中：点过「播放」到回包之间显示加载圈，并挡掉重复请求。 */
    val playLoadingSlot = mutableStateOf(false)
    var playLoading by playLoadingSlot

    /** 播放目标无真实集数时的 folder 下探层数（detail → category → detail …），到上限就停。 */
    val pendingPlayDrillSlot = mutableIntStateOf(0)
    var pendingPlayDrill by pendingPlayDrillSlot

    /** 非 null = 正在等 folder 的二级目录回包（`categoryContent`），回包后继续拉子条目详情。 */
    val pendingPlayCategorySlot = mutableStateOf<String?>(null)
    var pendingPlayCategory by pendingPlayCategorySlot

    /** true = 当前源不可播、预搜索结果还没到：显示 Loading，结果一到自动继续（见 LaunchedEffect）。 */
    val pendingPlayFallbackSlot = mutableStateOf(false)
    var pendingPlayFallback by pendingPlayFallbackSlot

    /** true = 这次播放来自「跨源兜底」：选集弹窗真正拿到真实剧集后提示用户已自动换源。 */
    val autoSwitchNoticeSlot = mutableStateOf(false)
    var autoSwitchNotice by autoSwitchNoticeSlot

    // ---- 弹窗焦点记忆 ----
    /** 正在查看的演员下标；非 null 时演员详情弹窗可见。 */
    val actorDialogIndexSlot = mutableStateOf<Int?>(null)
    var actorDialogIndex by actorDialogIndexSlot

    /** 弹窗关闭后要回去的演员下标；-1 表示「没有待恢复的演员焦点」。 */
    val pendingCastFocusSlot = mutableIntStateOf(-1)
    var pendingCastFocus by pendingCastFocusSlot

    /** 弹窗关闭后要回去的按钮下标；-1 表示「没有待恢复的按钮焦点」。 */
    val pendingActionFocusSlot = mutableIntStateOf(-1)
    var pendingActionFocus by pendingActionFocusSlot

    // ---- 收藏 ----
    /** 按钮当前是否已收藏：唯一数据源是 Keep 表，不用内存标记猜。 */
    val keepCheckedState = mutableStateOf(false)

    // ------------------------------------------------------------------
    // 派生常量（进页后不会变）
    // ------------------------------------------------------------------
    /** 本页是否真的拥有 `result` 通道：只有「自己发过详情请求」的页面才有资格消费它。 */
    val ownsResultChannel = vodId.isNotBlank() && !needsSpecialParse(vodId) && inlineVod == null

    /** 需要特殊解析的临时 ID（`atvp_detail:` 等）：本页不发详情请求。 */
    val special = needsSpecialParse(vodId)

    /** 空态文案：result 观察者（播放兜底也失败）和页面正文都要用。 */
    val emptyHint = when {
        vodId.isBlank() -> "缺少剧集 ID，无法加载剧集"
        special -> "该源需要特殊解析，暂不支持直接播放"
        else -> "该资源暂无可用剧集"
    }

    /**
     * 收藏唯一键，与 `TmdbDetailActivity.getHistoryKey()` 完全一致：
     * siteKey@@@vodId@@@cid（`AppDatabase.SYMBOL` = "@@@"）。
     */
    val keepKey = siteKey + AppDatabase.SYMBOL + vodId + AppDatabase.SYMBOL + VodConfig.getCid()

    // ------------------------------------------------------------------
    // 播放目标 / 预搜索兜底
    // ------------------------------------------------------------------
    /**
     * 「播放目标」统一入口：当前源没有真实集数时，改拉预搜索第一个结果（按钮上显示的那个源）。
     *
     * **folder 必须先分流**：`atvp_detail:` / `vod_tag=folder` 的 id 是目录壳，
     * 拿去调 `detailContent` 会被插件当成上游详情主键 → 500。
     * 所以这类目标直接走 `categoryContent`，由观察者的 `pendingPlayCategory` 分支接管，
     * 逐级下探到真正的剧集再弹选集。
     */
    fun requestPlayTarget(vod: Vod, site: Site) {
        playLoading = true
        pendingPlayOpen = false
        pendingPlayId = null
        pendingPlaySite = site
        pendingPlayVod = vod
        pendingPlayDrill = 0
        if (vod.isFolderTarget()) {
            // 目录壳：绝不调 detailContent，直接拉二级目录。
            pendingPlayCategory = vod.id
            Log.e("HeroDetail", "准备下探目录: site=${site.key}, id=${vod.id}")
            Log.e(
                TAG_DETAIL,
                "播放目标是 folder，直接拉目录: site=${site.key} folder=${vod.id}",
            )
            siteViewModel.categoryContent(site.key, vod.id, "1", true, hashMapOf())
        } else {
            pendingPlayCategory = null
            siteViewModel.detailContent(site.key, vod.id)
        }
    }

    /**
     * 预搜索是异步的：当前源不可播、结果还没到时先登记等待，结果一到自动继续下探 / 拉详情。
     * 对应 `LaunchedEffect(firstSearchVod, firstSearchSite, pendingPlayFallback)`。
     */
    fun onPreSearchReady(vod: Vod?, site: Site?) {
        if (!pendingPlayFallback) return
        if (vod == null || site == null || vod.id.isBlank()) return
        pendingPlayFallback = false
        autoSwitchNotice = true
        Log.e(TAG_DETAIL, "预搜索结果到达，自动切换: site=${site.key} id=${vod.id}")
        requestPlayTarget(vod, site)
    }

    /**
     * 兜底等待不能无限转圈：预搜索完全没有结果时给一次明确反馈。
     * 对应 `LaunchedEffect(pendingPlayFallback)`（含 10 秒超时）。
     */
    suspend fun onFallbackTimeout() {
        if (!pendingPlayFallback) return
        delay(PLAY_FALLBACK_WAIT_MS)
        if (!pendingPlayFallback) return
        pendingPlayFallback = false
        playLoading = false
        Log.e(TAG_DETAIL, "等待预搜索超时: 仍没有可用源")
        Notify.show(emptyHint)
    }

    /**
     * 跨源兜底成功：选集弹窗真正拿到真实剧集后再提示，避免网络失败时误报。
     * 对应 `LaunchedEffect(showPlayDialog, autoSwitchNotice)`。
     */
    fun onPlayDialogShown() {
        if (!showPlayDialog || !autoSwitchNotice) return
        autoSwitchNotice = false
        Notify.show("已为您自动切换至可用源")
    }

    // ------------------------------------------------------------------
    // `SiteViewModel.result` 观察者：详情 / 播放兜底 / folder 下探三路共用
    // ------------------------------------------------------------------
    /**
     * `result` 通道分流（顺序即优先级，不能调换）：
     *
     * 0. `pendingPlayOpen`：点「播放」时本页详情还没回 → 回包直接决定弹选集还是换源；
     * 1. `pendingPlayCategory`：folder 二级目录回来了 → 取第一个有效条目继续；
     * 2. `pendingPlayId`：预搜索目标的详情回来了 → 有集数弹选集，没有则下探目录；
     * 3. `showSourceDialog`：换源弹窗占用通道，本页必须让路；
     * 4. `!ownsResultChannel`：本页不发详情请求，任何 result 都是别人的；
     * 5. 其余：本页详情回包，直接更新 `detailResult`。
     *
     * @param searchVod / @param searchSite 由 Compose 侧 `rememberUpdatedState` 的**当前值**传入
     * （观察者只创建一次，直接捕获函数参数会永远拿到首帧的 null）。
     */
    fun onDetailResult(result: Result?, searchVod: Vod?, searchSite: Site?) {
        if (result == null) return
        // 0) 「播放」正在等**本页**详情：拿到线路直接弹选集；仍没有线路就退到预搜索的源。
        if (pendingPlayOpen) {
            pendingPlayOpen = false
            playLoading = false
            val incoming = result.vod
            val incomingFlags = incoming.flags
            Log.e(
                TAG_DETAIL,
                "播放等待详情返回: id=${incoming.id} flags=${incomingFlags.size} " +
                    "集数=${incomingFlags.sumOf { it.episodes.orEmpty().size }} code=${result.code}",
            )
            val previousVod = detailResult?.vod
            detailResult = result
            // 只有「存在真实 http/m3u8 地址」才算当前源能播：动作项 / 伪集数 / 空地址都不算。
            if (incomingFlags.any { it.hasRealVideoUrl() }) {
                playFlags = incomingFlags
                // 详情回包没带剧名时退回当前页已有的 Vod，绝不让弹窗丢标题。
                playVod = incoming.takeIf { it.name.isNotBlank() } ?: previousVod
                playSiteKey = siteKey
                // 本页自己就能播：清掉可能残留的「已自动换源」提示标记。
                autoSwitchNotice = false
                showPlayDialog = true
                return
            }
            // 本页只有动作指令 / 伪集数 / 没有集数：改拉预搜索第一个结果（按钮上显示的那个源）。
            val fallbackVod = searchVod
            val fallbackSite = searchSite
            if (fallbackVod != null && fallbackSite != null && fallbackVod.id.isNotBlank()) {
                Log.e(
                    TAG_DETAIL,
                    "准备拉取换源目标详情: site=${searchVod?.site?.key}, id=${searchVod?.id}",
                )
                autoSwitchNotice = true
                Notify.show("当前源不可播，正在切换可用源…")
                requestPlayTarget(fallbackVod, fallbackSite)
            } else {
                // 预搜索还没回来：登记等待，结果一到由 LaunchedEffect 自动继续。
                pendingPlayFallback = true
                playLoading = true
                Log.e(TAG_DETAIL, "播放等待详情无真实视频链接，等待预搜索结果…")
            }
            return
        }
        // 1) folder 下探：`categoryContent` 的二级目录回来了 → 选第一个条目继续拉详情。
        //    分类接口和详情共用 `result` 通道，所以必须先于普通详情分支判断。
        val pendingFolder = pendingPlayCategory
        if (pendingFolder != null) {
            pendingPlayCategory = null
            val targetSite = pendingPlaySite
            // 分类接口不会给每条结果盖 site（原版整页共用一个 key），这里补上。
            val items = result.list.map { child ->
                if (child.site == null && targetSite != null) child.apply { site = targetSite } else child
            }
            Log.e("HeroDetail", "下探结果: listSize=${result.list.size}")
            Log.e(
                TAG_DETAIL,
                "预搜索源目录返回: folder=$pendingFolder listSize=${items.size} " +
                    "code=${result.code} msg=${result.msg}",
            )
            // 跳过动作伪条目，并排除「刚展开的这一个 folder」自己（防自环，层数上限是最后一道闸）。
            // 按需求取**第一个**有效条目：是剧集就补详情弹选集，还是目录就继续下探。
            val child = items.firstOrNull {
                it.id.isNotBlank() && it.id != pendingFolder && !it.isActionItem()
            }
            if (child == null || targetSite == null) {
                playLoading = false
                Notify.show("该源暂无可用剧集")
                pendingPlaySite = null
                pendingPlayVod = null
                pendingPlayDrill = 0
                return
            }
            pendingPlayVod = child
            pendingPlayDrill++
            // 目录条目自带真实线路（部分聚合源）→ 直接弹选集，不再多发一轮详情。
            if (child.flags.any { it.hasPlayableEpisode() }) {
                playLoading = false
                playFlags = child.flags
                playVod = child
                playSiteKey = targetSite.key
                showPlayDialog = true
                pendingPlaySite = null
                pendingPlayVod = null
                pendingPlayDrill = 0
                return
            }
            if (child.isFolderTarget()) {
                // folder 套 folder：层数用完就停，绝不死循环。
                if (pendingPlayDrill >= MAX_PLAY_DRILL) {
                    playLoading = false
                    Notify.show("目录层级过深，暂不支持播放")
                    pendingPlaySite = null
                    pendingPlayVod = null
                    pendingPlayDrill = 0
                    return
                }
                pendingPlayCategory = child.id
                Log.e(
                    TAG_DETAIL,
                    "目录条目仍是 folder，继续下探: id=${child.id} depth=$pendingPlayDrill",
                )
                siteViewModel.categoryContent(targetSite.key, child.id, "1", true, hashMapOf())
            } else {
                pendingPlayId = child.id
                Log.e(
                    TAG_DETAIL,
                    "目录条目补详情: site=${targetSite.key} id=${child.id} depth=$pendingPlayDrill",
                )
                siteViewModel.detailContent(targetSite.key, child.id)
            }
            return
        }
        // 2) 播放目标（预搜索第一个源 / 其 folder 子条目）的详情回来了：
        //    有真实集数 → 直接弹选集；没有 → 用 `categoryContent` 下探二级目录。
        val pendingId = pendingPlayId
        if (pendingId != null) {
            pendingPlayId = null
            val incoming = result.vod
            val incomingFlags = incoming.flags
            // 需求指定的排查日志：先看这条详情到底有没有 flags / episodes。
            Log.e(
                "HeroDetail",
                "预搜索源详情返回: flagsSize=${result?.vod?.flags?.size}, " +
                    "episodesSize=${result?.vod?.flags?.firstOrNull()?.episodes?.size}",
            )
            Log.e(
                TAG_DETAIL,
                "播放目标详情: id=${incoming.id} flags=${incomingFlags.size} " +
                    "真实集数=${incomingFlags.sumOf { flag -> flag.episodes.orEmpty().count { !it.isActionCommand() } }} " +
                    "code=${result.code} depth=$pendingPlayDrill",
            )
            val targetSite = pendingPlaySite
            if (incomingFlags.any { it.hasPlayableEpisode() }) {
                playLoading = false
                playFlags = incomingFlags
                // 详情回包没带剧名 / 主键时退回发起请求的那条 Vod：跨源起播只认请求时的 id。
                playVod = incoming.takeIf { it.name.isNotBlank() && it.id.isNotBlank() }
                    ?: pendingPlayVod
                    ?: incoming
                playSiteKey = targetSite?.key ?: siteKey
                showPlayDialog = true
                pendingPlaySite = null
                pendingPlayVod = null
                pendingPlayDrill = 0
                return
            }
            // 0 集数：只有「看起来是 folder」或「详情带动作指令（网盘聚合源）」才下探；
            // 普通源真的没有剧集时绝不去拉分类页，否则可能把别的剧当成选集播出去。
            // 回包可能丢掉 folder 壳 id（日志里 id 直接为空），所以把「发起请求的那条」也算上。
            val looksLikeFolder = incoming.isFolderTarget() ||
                pendingPlayVod?.isFolderTarget() == true
            val hasActionEpisodes = incomingFlags.any { flag ->
                flag.episodes.orEmpty().any { it.isActionCommand() }
            }
            if (targetSite != null && pendingPlayDrill < MAX_PLAY_DRILL &&
                (looksLikeFolder || hasActionEpisodes)
            ) {
                pendingPlayDrill++
                pendingPlayCategory = pendingId
                playLoading = true
                Log.e(
                    TAG_DETAIL,
                    "预搜索源无集数，下探目录: site=${targetSite.key} folder=$pendingId " +
                        "depth=$pendingPlayDrill",
                )
                siteViewModel.categoryContent(targetSite.key, pendingId, "1", true, hashMapOf())
            } else {
                playLoading = false
                Notify.show("该源暂无可用剧集")
                pendingPlaySite = null
                pendingPlayVod = null
                pendingPlayDrill = 0
            }
            return
        }
        // 3) 换源弹窗打开期间，`result` 通道归弹窗（folder 下探 / 卡片补详情）。
        //    本页必须让路：目录结果没有 flags，会把详情页误判成「该资源暂无可用剧集」。
        if (showSourceDialog) {
            Log.d(TAG_DETAIL, "换源弹窗打开中，详情页忽略 result 通道")
            return
        }
        // 4) 本页不发详情请求的页面：任何 result 都是别人的（弹窗 / 其它页面），一律不接。
        if (!ownsResultChannel) {
            Log.d(TAG_DETAIL, "本页不拥有 result 通道，忽略: site=$siteKey id=$vodId")
            return
        }
        // 【排查日志 1】Result 形状：listSize=0 说明请求失败 / 接口没返回数据，
        // listSize>0 才轮到「flags 为什么是空」的问题。
        Log.d(
            TAG_DETAIL,
            "获取到详情: listSize=${result.list.size}, code=${result.code}, msg=${result.msg}",
        )
        val incoming = result.vod
        // 【致命日志】详情请求回来的线路 / 集数：
        // flagsSize=0 → 接口没给播放字段；flagsSize>0 但 episodesSize=0 → 有线路没拆出集。
        Log.e(
            TAG_DETAIL,
            "请求回来的 flagsSize=${incoming.flags.size}, " +
                "episodesSize=${incoming.flags.firstOrNull()?.episodes?.size ?: 0}",
        )
        Log.d(
            TAG_DETAIL,
            "Vod详情: name=${incoming.name}, id=${incoming.id}, flagsSize=${incoming.flags.size}",
        )
        // 【排查日志 2】播放字段：`Vod.setFlags()` 就是靠这两个字段生成线路的；
        // 这里为空说明接口压根没给播放数据（或字段名不对），与选集弹窗无关。
        Log.d(
            TAG_DETAIL,
            "Vod播放字段: playFrom=[${incoming.playFrom}] playUrlLen=${incoming.playUrl.length}",
        )
        if (incoming.playUrl.isNotBlank()) {
            Log.d(TAG_DETAIL, "Vod播放字段(前 800 字): ${incoming.playUrl.take(800)}")
        }
        if (incoming.flags.isNotEmpty()) {
            val first = incoming.flags[0]
            val playable = incoming.flags.count { !it.episodes.isNullOrEmpty() }
            Log.d(
                TAG_DETAIL,
                "第一条线路: flag=${first.flag}, show=${first.show}, " +
                    "episodesSize=${first.episodes.orEmpty().size}, " +
                    "可播放线路=$playable/${incoming.flags.size}",
            )
        } else {
            Log.e(TAG_DETAIL, "【严重】未解析出任何线路，请检查请求 key 和 id 是否正确！")
        }
        // 【排查日志 3】动作指令集数：网盘聚合 / 猫源会把「媒体信息 / 全局搜索 / 稍后再看」
        // 伪装成集数（`📄 媒体信息$msubinfo-...`）。本站没有这套动作的分发器，
        // 它们会被灰显、点了只提示不起播 —— 这里先把「到底有几条」落成日志，
        // 否则用户只会看到「点集数没反应」，分不清是解析失败还是本来就不可播。
        val actionEpisodes = incoming.flags.flatMap { flag ->
            flag.episodes.orEmpty().filter { it.isActionCommand() }
        }
        Log.e(
            TAG_DETAIL,
            "动作指令集数: count=${actionEpisodes.size}, " +
                "totalEpisodes=${incoming.flags.sumOf { it.episodes.orEmpty().size }}",
        )
        actionEpisodes.forEach { episode ->
            Log.e(
                TAG_DETAIL,
                "  动作项: name=${episode.getName()} url=${episode.getUrl()}",
            )
        }
        // 详情结果只来自本页的 `detailContent`（换源弹窗写的是 `search` 通道）。
        // 即使源站返回的 id 与请求 id 不完全一致，只要结果到了就必须接收：
        // 这里再按 id 丢弃，会把一次成功的详情请求误判成「无可用剧集」。
        if (incoming.id.isNotBlank() && incoming.id != vodId) {
            Log.w(TAG_DETAIL, "详情 id 与请求不一致，仍然接收: resultId=${incoming.id} 期望=$vodId")
        }
        detailResult = result
    }

    // ------------------------------------------------------------------
    // TMDB 元数据（阶段 1：展示 + 读 TmdbMatchCache）
    // ------------------------------------------------------------------
    /**
     * 对应 `LaunchedEffect(vodId, siteKey, vod?.name, vod?.year)`。
     *
     * 为什么独立成协程而不是塞进 `detailResult` 的观察者：
     * 1. 观察者在主线程回调，且播放兜底 / folder 下探都会让它重入，TMDB 的同步 OkHttp 请求会被触发多次；
     * 2. 协程随 key 变化自动取消、离开页面随组合销毁自动停止，不必手写摘除。
     * 闸门：没有 Key / 本页源被排除 / 没有真实 vodId（旧 adb 入口）时**一次网络都不发**。
     */
    suspend fun loadTmdb(vod: Vod?) {
        // 已经拿到（或正在拉）TMDB 数据时不再重发：folder 下探 / 预搜索回包都会让 `detailResult` 变化，
        // 但剧集身份没变；不守卫会把已 Ready 的元数据覆盖成 Error。
        if (tmdbState is TmdbDetailState.Ready || tmdbState is TmdbDetailState.Loading) return
        val current = vod ?: return
        val config = TmdbConfig.effectiveCurrent()
        if (!config.isReady() || vodId.isBlank() || !TmdbSitePolicy.isEnabled(config, siteKey, vodId)) {
            tmdbState = TmdbDetailState.Idle
            return
        }
        if (activity.isFinishing) return

        // 首页预拉取命中：详情 / 演员已经在内存里，直接出结果，一条网络请求都不发。
        // 这是消除「点进详情页先白等一轮搜索 + 详情」的关键；未命中时下面照旧走「缓存 → 搜索 → 详情」。
        // 站点开关与 Key 的闸门在上面已经过完，所以这里不会让被排除的源蹭到 TMDB 数据。
        val prefetched = HeroTmdbPrefetchCache.take(heroTmdbKey(siteKey, vodId))
        if (prefetched != null) {
            tmdbState = TmdbDetailState.Ready(
                item = prefetched.item,
                // 缓存只收完整数据（`put` 校验过 detail != null），这里兜空对象只为满足非空类型。
                detail = prefetched.detail ?: JsonObject(),
                persons = prefetched.cast,
                manual = prefetched.manual,
            )
            Log.d(
                TAG_DETAIL,
                "tmdb 命中首页预取 manual=${prefetched.manual} id=${prefetched.item.tmdbId} persons=${prefetched.cast.size}",
            )
            return
        }

        tmdbState = TmdbDetailState.Loading
        val ready = withContext(Dispatchers.IO) {
            try {
                val service = TmdbService()
                // 1) 先读手动匹配缓存，再读自动匹配缓存（与 TmdbUIAdapter.getCachedMatch 语义一致）。
                val cache = Setting.getTmdbMatchCache()
                val manual = cache.findManual(siteKey, vodId, current.name)
                // 2) 缓存未命中才搜索：详情页要的是「单条最佳」，不是候选列表。
                val item = manual
                    ?: cache.find(siteKey, vodId, current.name)
                    ?: TmdbMatcher(service, config).searchAndMatch(current.name, current)
                    ?: return@withContext null
                // 3) 详情与演员必须来自同一份 JSON（append_to_response 已在服务端拼好，且带 7 天磁盘缓存）。
                val detail = service.detail(item, config)
                TmdbDetailState.Ready(item, detail, service.cast(detail, config), manual != null)
            } catch (e: CancellationException) {
                // 页面已退出 / key 已变化：原样抛出，交给协程取消机制。
                throw e
            } catch (e: TmdbService.AuthException) {
                // Key 失效 / 被拒：不崩页，按「无 TMDB 数据」处理。
                Log.w(TAG_DETAIL, "tmdb 鉴权失败: ${e.message}")
                null
            } catch (e: Exception) {
                Log.w(TAG_DETAIL, "tmdb 加载失败: ${e.message}")
                null
            }
        }
        if (ready == null) {
            tmdbState = TmdbDetailState.Error("")
            Log.d(TAG_DETAIL, "tmdb 无可用数据（未命中或请求失败）")
            return
        }
        tmdbState = ready
        Log.d(
            TAG_DETAIL,
            "tmdb ready manual=${ready.manual} id=${ready.item.tmdbId} persons=${ready.persons.size}",
        )
    }

    // ------------------------------------------------------------------
    // 收藏（本地 Keep 表）
    // ------------------------------------------------------------------
    /**
     * 按数据库真实状态刷新收藏按钮（原版 `updateKeepState()`）。
     *
     * 主线程同步读：AppDatabase 已开 `allowMainThreadQueries()`，原版
     * `DetailActivity` / `TmdbDetailActivity` 也是直接同步读 Keep。
     */
    fun updateKeepButtonState() {
        keepCheckedState.value = Keep.find(keepKey) != null
    }

    /**
     * 收藏 / 取消收藏（照抄原版 `DetailActivity.onKeep()`）：有则删、无则建，最后刷新按钮态。
     *
     * 入库字段只用 Keep 表真实存在的列（`key / siteName / vodName / vodPic / createTime / type / cid`）：
     * 本仓库没有 `vodRemarks` 字段，不为一条收藏功能改 Room schema。
     */
    fun toggleKeep(context: Context, vod: Vod?, page: HeroDetail) {
        if (vodId.isBlank()) {
            // 旧 adb 入口 / 聚合临时条目没有真实主键：拼出来的 key 是脏数据，直接拒绝。
            Notify.show("缺少剧集 ID，无法收藏")
            return
        }
        val existing = Keep.find(keepKey)
        if (existing != null) {
            existing.delete()
            Notify.show("已取消收藏")
        } else {
            // 收藏卡（`KeepPosterAdapter`）只显示站名 / 剧名 / 海报：
            // 剧名与海报优先真实详情 `Vod`，空则回落首帧 Intent 数据（口径同 `TmdbDetailActivity.createKeep()`）。
            val keep = Keep()
            keep.key = keepKey
            keep.cid = VodConfig.getCid()
            keep.vodPic = vod?.pic.orEmpty().ifBlank { page.posterUrl }
            keep.vodName = vod?.name.orEmpty().ifBlank { page.title }
            // 站名要单独存：历史页 / 收藏页入口不带 `EXTRA_SITE_NAME`，只能回查站点显示名。
            keep.siteName = siteName.ifBlank { VodConfig.get().getSite(siteKey).displayName }
            keep.createTime = System.currentTimeMillis()
            keep.type = 0
            keep.save()
            // 可选：收藏卡用的是 Glide（`ImgUtil`），这里用同一引擎预热海报，进收藏页首帧就有图。
            ImgUtil.preload(context, keep.vodPic)
            Notify.show("已加入收藏")
        }
        // 点击后立刻改按钮（不等 onResume），再通知收藏页重读数据库。
        updateKeepButtonState()
        RefreshEvent.keep()
    }

    // ------------------------------------------------------------------
    // 页面数据合并（Intent 首帧 + 详情 + TMDB）
    // ------------------------------------------------------------------
    /**
     * 把「Intent 首帧 / 详情回包 / TMDB 元数据」合并成一份可渲染的 [HeroDetail]。
     *
     * `tmdbState` 是 key：TMDB 到达后在这层**基于新 base 重新合并**，绝不叠加在上一次 page 上
     *（否则评分会被追加两次）。key 变化也会让 `cast` / `meta` 与演员下标同步刷新。
     */
    fun buildPage(detail: HeroDetail, vod: Vod?, tmdbState: TmdbDetailState): HeroDetail {
        val base = detail.mergeDetail(vod, siteName)
        val ready = tmdbState as? TmdbDetailState.Ready ?: return base
        return base.mergeTmdb(
            item = ready.item,
            detail = ready.detail,
            persons = ready.persons,
            config = TmdbConfig.effectiveCurrent(),
            manualHit = ready.manual,
        )
    }

    // ------------------------------------------------------------------
    // 弹窗关闭后的焦点恢复
    // ------------------------------------------------------------------
    /**
     * 三个弹窗都关闭后才抢焦点：
     * - 刚关掉演员弹窗 → 回到被点的那位演员（需求：焦点必须回到刚才点击的头像）；
     * - 刚关掉换源弹窗 → 回到「换源」按钮（需求三.3）；
     * - 否则（首次进入 / 关闭播放弹窗）→ 回到「播放」。
     *
     * 对应 `LaunchedEffect(showPlayDialog, showSourceDialog, actorDialogIndex)`。
     * 用 `withFrameNanos` 逐帧重试，是因为弹窗刚关的那一帧 Lazy 项 / 按钮可能还没恢复可聚焦。
     */
    suspend fun restoreFocus(
        actionRequesters: List<FocusRequester>,
        castRequesters: List<FocusRequester>,
    ) {
        if (showPlayDialog || showSourceDialog || actorDialogIndex != null) return
        val castTarget = pendingCastFocus
        pendingCastFocus = -1
        if (castTarget >= 0) {
            repeat(5) {
                if (requestDetailFocus(castRequesters, castTarget)) return
                withFrameNanos { }
            }
            return
        }
        val actionTarget = pendingActionFocus
        pendingActionFocus = -1
        if (actionTarget >= 0) {
            repeat(5) {
                if (requestDetailFocus(actionRequesters, actionTarget)) return
                withFrameNanos { }
            }
            return
        }
        repeat(3) {
            if (requestDetailFocus(actionRequesters, DETAIL_ACTION_PLAY)) return
            withFrameNanos { }
        }
    }

    /**
     * TMDB 演员列表整体替换占位数据后，旧的演员下标可能越界：夹回合法范围，空列表直接关闭弹窗。
     *（`HeroActorDialog` 消费处另有 getOrNull 兜底，避免「重组先于本效果」的那一帧越界。）
     *
     * 对应 `LaunchedEffect(page.cast.size)`。
     */
    fun clampActorDialogIndex(castSize: Int) {
        val index = actorDialogIndex ?: return
        actorDialogIndex = if (castSize == 0) null else index.coerceIn(0, castSize - 1)
    }
}
