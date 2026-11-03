# HERO-HISTORY：Compose 历史页复刻记录

> 任务：在 Compose 体系下新建历史页 `HistoryScreen` + `HistoryState/HistoryViewModel`，
> 严格按 `ref_docs` 逆向事实复刻原版历史 Fragment（`o11.java`），卡片复用全局 `HeroPosterCard`。
> 本次**未执行任何编译 / 打包 / commit / push**，所有结论均为静态核对结果。

---

## 1. 交付物

| # | 文件 | 类型 | 说明 |
| --- | --- | --- | --- |
| 1 | `app/src/main/java/com/fongmi/android/tv/ui/hero/HistoryScreen.kt` | 新增 | 页面本体（Compose） |
| 2 | `app/src/main/java/com/fongmi/android/tv/ui/hero/HistoryViewModel.kt` | 新增 | `HistoryViewModel` / `HistoryUiState` / `HeroInfo` + 4 个纯函数工具对象 |
| 3 | `app/src/main/java/com/fongmi/android/tv/ui/hero/HeroPosterCard.kt` | 改动 | 仅追加 `siteTag` / `tmdbTag` 两个可选参数（默认值保持旧行为） |
| 4 | `app/src/main/java/com/fongmi/android/tv/ui/activity/HeroHistoryActivity.kt` | 改写 | 由 View 宿主改为 `ComponentActivity` + `setContent { HistoryScreen(...) }`；`start(context)` 签名不变；文件尾部三个 Mask View 类原样保留（仍被 `res/layout/activity_hero_history.xml` 引用） |
| 5 | `app/src/main/res/drawable/ic_arrow_down_circle.xml` | 新增 | 唯一新增资源：逐字节复制 `ref_docs/ic_arrow_down_circle.xml`（本仓库原本缺失该图标） |
| 6 | `docs/HERO-HISTORY-compose-history-screen.md` | 新增 | 本文件 |

未改动：`PlayerManager`、`PlaybackActivity`、catvod 数据层、`ds3`、`Config`、`History` bean、
首页 `HeroHomeActivity`、`ChangeSourceDialog`、`SourceSidebar`。

---

## 2. 页面结构对照表（`ref_docs/fragment_history.xml` → Compose）

| XML 节点 / 行号 | 真值 | Compose 实现 | 备注 |
| --- | --- | --- | --- |
| 根 `FrameLayout` L2-5 | match×match，`clipChildren=false` | `HistoryScreen` 根 `Box` | Compose 默认不裁剪 |
| `heroBg` L13-19 | 全屏，centerCrop，初始 gone | `HeroBackdrop()` | Coil 加载，1920×1080 语义 |
| `maskLeft` L20-25 + `bg_hero_mask_h.xml` | angle=0，`#ff000000` → 中点 `#00000000` → 右端 `#00000000` | `HeroMaskLeft()` | `Brush.horizontalGradient(0→黑, 0.5→透明, 1→透明)` |
| `maskBottom` L26-31 + `bg_hero_mask_v.xml` | angle=90（**底部起**），`#ff000000` → 中点 `#00000000` → 顶端 `#00000000` | `HeroMaskBottom()` | `Brush.verticalGradient(0→透明, 0.5→透明, 1→黑)` |
| 遮罩可见时机 | XML 初始 gone；`o11.H()` L163-164 建视图时置 visible | `state.loaded` 为 true 后渲染 | 与 `H()` 同一时机 |
| `pageSite` L32-47 | 13sp bold 白 / 高 26dp / paddingH 12dp / margin 24dp / `shape_config_name`（4dp 圆角 + `@color/red`）/ maxLines 1 / gone | `PageSite()` | `@color/red` = `#E50914`（`res/values/colors.xml` L56） |
| `posterCard` L48-66 | 150×210 / `shape_poster_card`（`black_20` `#33000000` + 12dp）/ padding 4dp / marginTop 72dp / marginEnd 80dp / clipToOutline / gone | `PosterFallback()` | 仅背景大图为空时显示（`o11.q0` L527-535） |
| `posterCardImg` L60-65 | match×match，centerCrop | `AsyncImage` | — |
| `posterContent` L72-80 | vertical，贴底，初始 gone | `PosterContent()` | 数据就绪后 alpha 0→1（100ms） |
| 信息组 L81-89 | marginBottom 8dp / paddingH 24dp | `HeroInfoGroup()` | — |
| `heroNameText` L90-102 | 32sp bold 白 / 高 36dp / gravity bottom / 阴影 `#cc000000` 2/2/12 / invisible | `HeroInfoGroup` 第一段 | 跑马灯：`basicMarquee(iterations = Int.MAX_VALUE)`，`widthIn(max = 屏宽 × 0.6)`（`nm3.i`） |
| `heroLogoImg` L103-110 | wrap×72dp / fitStart / adjustViewBounds / maxWidth 720dp / gone | `HeroInfoGroup` 第二段 | 本工程无 Logo 数据源 → 恒不显示，剧名文字常显 |
| `heroMetaText` L111-123 | 14sp `#ccffffff` / 高 20dp / marginTop 2dp / 阴影 `#aa000000` 1.5/1.5/8 / invisible | `HeroInfoGroup` 第三段 | 文本 = `HistoryMetaText.build()`（移植 `o11.b0`） |
| `heroDescText` L130-144 | 12sp `#bbffffff` / 高 40dp / maxLines 2 / ellipsize end / lineSpacingExtra 3dp / shadow `#aa000000` 1.2/1.2/6 | `HeroInfoGroup` 第四段 | 本工程无 `gd3.s` 来源 → 恒空（待确认 #7） |
| `resumeRow` L147-157 | 垂直居中 / marginBottom 22dp / paddingH 24dp | `ResumeRow()` | — |
| `btnResume` L158-173 | 14sp / 高 32dp / `selector_item_round_resume` / `ic_arrow_down_circle` 14dp / drawablePadding 4dp / `@string/history_view_detail` / focusable / invisible / nextFocusUp=homeBtn / nextFocusDown=posterRecycler | `ResumeButton()` | 文案用本仓库既有 `R.string.hero_history_view_detail`（中英均有） |
| `progressGroup` L174-182 | 宽 200dp / marginStart 20dp / gone | `ProgressGroup()` | 仅 `position>0 && duration>0` |
| `progressEpisode` L187-199 | 12sp `#ccffffff` / 单行 / weight 1 / 阴影 `#aa000000` 1/1/4 | `ProgressGroup` 上行 | 跑马灯 |
| `progressTotal` L200-212 | 12sp `#ccffffff` / marginStart 12dp / maxLines 1 | `ProgressGroup` 上行 | `nm3.j(pos) + " / " + nm3.j(dur)`（待确认 #2） |
| `progressBar` L214-218 | 高 2dp / marginTop 2dp | `HistoryProgressIndicator()` | `HistoryProgressBar.java` L36-65 复刻：轨道纯白 + 圆角=高/2，进度宽 `min(w, max(ratio*w, h))`，渐变 `dl3.a()→dl3.l()`（待确认 #1） |
| 列表容器 L221-225 | FrameLayout，高 215dp | `PosterRow()` 外层 `Box` | — |
| `posterRecycler` L226-237 | 高 215dp / paddingH 24dp / clipChildren=false / focusOutEnd+focusOutFront | `LazyRow` | 间距 16dp（`o11.H()` L171） |

### 卡片（`adapter_history_poster.xml` → `HeroPosterCard`）

| XML 节点 / 行号 | 真值 | `HeroPosterCard` 现状 |
| --- | --- | --- |
| `image` L21-27 | 120×180 / `black_20` / `@style/Vod.Grid.Large`（8dp 圆角） | 120×180 + `ShapeableImageView` + 同款 style ✅ |
| `tmdbTag` L28-43 | 5sp bold 白「TMDB」/ `bg_tmdb_tag`（`#99000000` + 3dp）/ paddingStart/End 3dp / paddingBottom 1dp / margin 4dp / gone | 本次新增 `tmdbTag: Boolean` ✅ |
| `siteGradient` L44-49 | 96×96 / `start|top` / gone | 本次新增（`GradientDrawable` TL_BR，仅左上 8dp 圆角）✅ |
| `siteTag` L50-62 | 10sp bold 白 / margin 4dp / shadow `@color/black` r6 / gone | 本次新增 `siteTag: String?` ✅ |
| `delete` 遮罩 L64-76 | `black_50` + `ic_vod_delete`，编辑态才显示 | `HeroPosterCard.isEditMode`：8dp 圆角 `black_50` + 居中 56dp `ic_vod_delete`（本次新增）✅ |
| `name` L77-89 | 13sp `#ccffffff` / marginTop 8dp / ellipsize marquee / 居中 | 组件既有：**16sp** + 34dp 文字区 + `basicMarquee`（差异见 §5） |

---

## 3. 交互对照表（`o11.java` / `pp.java` / `g20.java` / `m11.java` → ViewModel）

| 原版 | 行号 | 本工程实现 |
| --- | --- | --- |
| `a0()`（重载列表） | `o11` L238-246 | `HistoryViewModel.load()` / `reload()` |
| `m11` case 3（后台读库） | `m11` L56-59 | `Task.execute { History.getForDisplay() }` + `App.post {}` |
| `p0(i, z)`（选中联动） | `o11` L457-511 | `select(index)` → `buildHero(history)` |
| `s0(history)`（pageSite） | `o11` L687-695 | `HeroInfo.pageSite = vodRemarks`，空则隐藏 |
| `r0(history)`（进度区） | `o11` L561-685 | `HistoryEpisodeLabel.resolve()` + `HistoryProgressFormatter` |
| `b0(history)`（元数据） | `o11` L93-146 | `HistoryMetaText.build()` |
| `q0(i)`（背景/预热） | `o11` L513-555 | `HeroBackdrop`（Coil）+ `HeroImagePrefetch` |
| `k0(i)`（±2 窗口） | `o11` L356-368 | `prefetch(index)`（±2） |
| `pp` case 14（点击） | `pp` L355-390 | `openResume()` / `openDetail()` 三步分支 |
| `pp` case 14（编辑态删除） | `pp` L361-372 | `confirmDelete(index)`：编辑态下按确定键 → `deleteAt(index)`（假数据模式直接内存移除） |
| `g20` case 2（btnResume） | `g20` L104-119 | `openDetail(activity, renderState.selectedItem)` |
| `m11` case 1（删除后选中回退） | `m11` L39 | `index.coerceAtMost(remaining.size - 1)` |
| `onRefreshEvent`（`zj2.a==3`） | `o11` L413-455 | `HeroHistoryActivity.onRefreshEvent` → `reload()` |
| `H()` 焦点策略 0 + 缩放 1.08 | `o11` L173-174 | `LazyRow` + `scrollToItem(index)`；焦点项 `scale 1.08` |
| `c0()` 长按 TMDB 匹配 | `o11` L248-294 | **不复刻** |
| `m0()` 编辑模式 | `o11` L383-395 | `isEditMode`（全局）+ `HeroPosterCard.isEditMode` 遮罩；长按确定键 500ms 进入，BACK 退出 |
| `b11` 卡片长按（触屏） | `s11` L122 | **不复刻**（遥控器长按走 `OnKeyListener`；触屏长按不再进编辑态） |
| `m11` case 9 站点备注队列 | `m11` | **不复刻** |

---

## 4. 入口映射表（原版混淆符号 → 本仓库既有公开入口）

| 原版调用 | 本工程入口 | 依据 |
| --- | --- | --- |
| `ds3.o()`（当前配置 id） | `VodConfig.getCid()` | `VodConfig.java` L73 |
| `ds3.v(config, callback)` | `VodConfig.selectConfig(Config, Callback)` | `VodConfig.java` L97-100；`Callback` = `com.fongmi.android.tv.impl.Callback` |
| `Config.find(cid)` | `Config.find(int)` | `Config.java` L105 |
| `CollectActivity.c2(context, vodName)` | `SearchActivity.directFromHistory(activity, history)` | `CollectActivity` 只在 leanback 源集；`SearchActivity.directFromHistory` 两 flavor 都有（mobile L59 / leanback L102），且与 `HistoryResumeCoordinator.open` L48-50 兜底分支同一入口 |
| `VideoActivity.q3(...)`（续播） | `VideoActivity.startFromHistory(activity, history)` | mobile L703-710 / leanback L730-737，两 flavor 同签名 |
| `DetailActivity.q2(...)`（详情） | `TmdbDetailActivity.startFromHistory(activity, history)` | `TmdbDetailActivity.java` L544-547，main 源集，两 flavor 通用 |
| `history.delete()` | `History.delete()` | `History.java` L1177-1179（bean 自身方法，不碰 catvod） |
| `History.get()` / 排序 | `History.getForDisplay()` | `History.java` L201-206（已含全局历史聚合 + 展示排序，Compose 侧不再二次排序） |
| `gd3.l(vodName)`（背景） | `history.wallPic` | 字段存在（`History.java` L62） |
| `gd3.t(vodName)` / `vodPic`（海报） | `history.vodPic` | `History.java` L60 |
| `gd3.q(vodName)`（Logo） | 无来源 → 恒空 | 见待确认 #9 |
| `gd3.s(vodName)`（简介） | 无来源 → 恒空 | 见待确认 #7 |
| `gd3.w / n / m / r` | `year` / `typeName` / `vodRemarks` / `mediaType` | `History.java` L855 / L823 / L664 / L871 |
| `gd3.v`（总集数） | 无字段 → 恒空（`全N集` 分支跳过） | — |
| `gd3.g(...)`（批量预取） | `HeroImagePrefetch`（仅 Coil 预热，不发 TMDB 请求） | 本工程无等价 TMDB 接口 |
| `je2.d(i-2, i+2)` | `prefetch(index)` ±2 | 同上 |
| `nm3.j(ms)` | `HistoryProgressFormatter.format()` | `utils/HistoryProgressFormatter.java` L10-19 |

---

## 5. `HeroPosterCard` 改动说明与「零影响」证据

### 5.1 改动内容（只有追加，没有改旧逻辑）

1. **构造器**：在 `defStyleAttr` 之后追加两个默认参数，并保留 `@JvmOverloads`
   ```kotlin
   class HeroPosterCard @JvmOverloads constructor(
       context: Context,
       attrs: AttributeSet? = null,
       defStyleAttr: Int = 0,
       siteTag: String? = null,   // 新增，默认 null
       tmdbTag: Boolean = false,  // 新增，默认 false
   ) : FrameLayout(context, attrs, defStyleAttr)
   ```
2. **两个属性**：`var siteTag: String?`、`var tmdbTag: Boolean`，setter 只切换
   `siteTagView` / `siteGradient` / `tmdbTagView` 的可见性与文本。
3. **三个子 View**：`siteGradient`（96×96，`GradientDrawable` TL_BR，仅左上 8dp 圆角）、
   `siteTagView`（10sp bold 白、margin 4dp、阴影 r6）、`tmdbTagView`（5sp bold「TMDB」、
   `#99000000`、3dp 圆角、paddingStart/End 3dp、paddingBottom 1dp、margin 4dp）。
4. 三个子 View 默认 `GONE`，`applySiteTag()` / `applyTmdbTag()` 在 `init` 末尾按默认值再同步一次。

### 5.2 零影响证据

| 证据 | 结论 |
| --- | --- |
| `@JvmOverloads` 生成的旧重载 `(Context)` / `(Context, AttributeSet)` / `(Context, AttributeSet, Int)` 全部保留 | 既有 Java/Kotlin 调用与 XML 反射构造都不受影响 |
| 全仓库 `HeroPosterCard(` 调用点只有 2 处：`ChangeSourceDialog.kt:252`（`HeroPosterCard(parent.context)`）与本次新增 `HistoryScreen.kt` | 唯一既有调用方用的是 1 参构造，走默认值 |
| 默认 `siteTag = null` → `applySiteTag()` 令 `siteGradient` 与 `siteTagView` 都 `GONE` | 首页 / 搜索 / 换源的卡片渲染与改动前完全一致 |
| 默认 `tmdbTag = false` → `tmdbTagView` `GONE` | 同上 |
| 默认 `isEditMode = false` → `deleteOverlay` `GONE`（`applyEditMode()` 在 `init` 末尾同步） | 同上 |
| 默认 `onDeleteClick = null` → 遮罩点击是空操作 | 同上 |
| 新增子 View 只 `addView` 到 `posterFrame`（120×180 内），不改 `onMeasure` / `draw` / `drawFocusRing` / `title` / `imageUrl` / `topStartTag` / `bottomStartTag` | 尺寸、圆角、焦点红框、文字区逻辑零改动 |
| `topStartTag`（13sp + `shape_vod_site`）与 `siteTag`（10sp + 渐变）互不影响、可同时存在，但历史页只设置 `siteTag`，其它页面只设置 `topStartTag` | 不会出现双标签叠加 |

---

## 6. 编辑模式删除说明（无弹窗）

> 本次已**移除**原来的 `AlertDialog` 删除确认：原版 `pp` case 14 L361-372 是「编辑态下直接 `history.delete()` + 从列表移除」，全程没有弹窗。

### 6.1 按键时序（`HeroPosterCard` 是 View，没有 Compose `Modifier`）

`HeroPosterCard` 是 `FrameLayout`，所以长按判定写在 `HistoryCard` 的 `setOnKeyListener` 里，
手动接 `KEYCODE_DPAD_CENTER` / `KEYCODE_ENTER` / `KEYCODE_NUMPAD_ENTER`：

| 事件 | 行为 |
| --- | --- |
| `ACTION_DOWN`（`repeatCount == 0`，非编辑态） | 起 500ms `Job`（`delay`），到点 `fired = true` + `onEnterEditMode()` + `view.requestFocus()` |
| `ACTION_DOWN`（编辑态） | 不重新计时 |
| `ACTION_UP`（本轮已触发长按） | 取消 Job，**不删除**（松手不消失、也不误删） |
| `ACTION_UP`（编辑态短按） | `confirmDelete(index)` |
| `ACTION_UP`（普通态短按） | `onClick()` → 续播 |
| `KEYCODE_BACK`（编辑态） | `onExitEditMode()`，仅退出不删除 |
| `KEYCODE_DPAD_UP`（编辑态） | 消费掉，焦点不离开卡片 |

`OnKeyListener` 对 DOWN / UP 都返回 `true`，`View` 不会再把它当成普通 click（否则会同时触发续播）。
**没有**使用 `combinedClickable`，也**没有**使用 `setOnLongClickListener`。

### 6.2 遮罩与删除

| 项 | 实现 |
| --- | --- |
| 触发 | 长按确定键 500ms → `isEditMode = true` |
| 遮罩 | `HeroPosterCard.isEditMode` → 120×180 海报区叠加 8dp 圆角 `black_50` + 居中 56dp `ic_vod_delete` |
| 显示范围 | **所有卡片**（原版 `s11.java` L119 `setVisibility(s11Var.i ? 0 : 8)` 读的是适配器级标志 `i`，`o11.m0()` 用 `notifyItemRangeChanged(0, size)` 刷新全部） |
| 删除动作 | 编辑态按确定键 → `viewModel.deleteAt(index)` → `History.delete()`（bean 自身方法，`History.java` L1177）在 `Task.execute` 中执行 |
| 列表更新 | 主线程移除该项：`state.items - index`，同步 `empty` / `loaded` |
| 选中回退 | `select(index.coerceAtMost(remaining.size - 1))`（同 `m11` case 1 L39 的公式） |
| 空列表 | `clearSelection()` + 显示空态（`posterContent` 整体隐藏，与 `ProgressLayout.EMPTY` 一致） |
| 焦点 | 删除后 `pendingFocus = state.selectedIndex`，等新卡片挂到窗口再 `requestFocus()` |
| 触屏 | 编辑态点遮罩（`HeroPosterCard.onDeleteClick`）= 删除；触屏长按不再进入编辑态（`b11` 未复刻） |

`ic_vod_delete.xml` 本仓库原本缺失，已按 `ref_docs/ic_vod_delete.xml` 逐字节新增（56dp、白色）。

---

## 7. 与首页卡的差异（历史卡 vs 首页卡）

| 项 | 首页 `HeroPosterCard`（定稿） | 历史页原版 `adapter_history_poster.xml` | 本次处理 |
| --- | --- | --- | --- |
| 卡片宽度 | 固定 120dp | 动态 `min(xx0.I()[0], 150dp)`（`s11.java` L60 `Math.min(...)`） | 固定 120dp（用户明确要求「与定稿完全一致」），差异记录在此 |
| 卡片高度 | 120×180 海报 + 34dp 文字区 = 214dp | 图 120×180 + 13sp 剧名（wrap） | 沿用 214dp |
| 剧名字号 | 16sp | 13sp `#ccffffff` | 沿用 16sp（组件定稿值） |
| 剧名位置 | 固定 34dp 文字区、上下 6dp、居中 | marginTop 8dp、居中、marquee | 沿用组件 |
| 左上标签 | `topStartTag` 13sp + `shape_vod_site` | `siteTag` 10sp + 96×96 渐变 | 新增 `siteTag`，首页不设置 |
| 右上角标 | 无 | `tmdbTag` 5sp「TMDB」+ `#99000000` | 新增 `tmdbTag` |
| 删除遮罩 | 无 | `delete`（`black_50` + `ic_vod_delete`） | 已复刻：`isEditMode` 时叠加（默认关闭） |
| 焦点 | 2dp `#FFFF0000` 只包海报 | `selector_vod` → `shape_vod_focused`（值未知） | 沿用组件 2dp `#FFFF0000`（待确认 #4） |
| 行内缩放 | 无 | `mm3.f(recyclerView, 1.08f)` | 焦点项 `scale 1.08` |

---

## 8. 待确认清单（实现时按此编号停下列出，未猜数值）

| # | 项 | 现状 | 位置 |
| --- | --- | --- | --- |
| 1 | `dl3.a()` / `dl3.l()` 色值（站点渐变起始色、进度条渐变两端） | 三处都钉成工程既有 `hero_accent` `#FFFF0000` | `HeroPosterCard.SITE_GRADIENT_START`、`HistoryScreen.PROGRESS_GRADIENT_START/END` |
| 2 | `pm3.M` 时间格式（`nm3.j` 输出） | 用 `HistoryProgressFormatter`（mm:ss / h:mm:ss） | `HistoryViewModel.progressText` |
| 3 | `shape_item_round_focused/_activated/_normal` 三态、`CustomTypeView` 内边距、`selector_type_text` 文本色 | 三态用 `white_10` / `hero_accent`；内边距 16dp；文本纯白 | `HistoryScreen.ResumeButton` + 常量区 |
| 4 | `shape_vod_focused` 描边宽度/颜色（`overlay_focus` + `selector_vod`） | 沿用定稿 2dp `#FFFF0000` | `HeroPosterCard.drawFocusRing`（未改） |
| 5 | `view_empty.xml` 的空态图标与文案 | 复用本仓库 `view_empty.xml`（Lottie 200×224 + `@string/error_empty`） | `HistoryScreen.HistoryEmptyState` |
| 6 | `History.get()` 的排序规则 | 直接用 `History.getForDisplay()`（已含展示排序），Compose 侧不重排 | `HistoryViewModel.loadInternal` |
| 7 | `heroDescText` 的 `lineSpacingExtra="3dp"` 在 Compose 无对应属性 | `lineHeight = 18.sp` 近似；该行恒空不会绘制 | `HistoryScreen.HERO_DESC_LINE_HEIGHT` |
| 8 | ~~删除确认框文案~~ | **已失效**：删除确认框整段移除，不再有弹窗文案 | — |
| 9 | `tmdbTag` 可见性 | 原版 `s11.b` = `gd3.t` + (`gd3.z` 或 `gd3.p`)；本工程近似为「剧名 + 海报 + 背景都非空」 | `HistoryViewModel.HistoryCardTags` |
| 10 | 空列表时的默认焦点 | 规格写「列表空 → 焦点给 btnResume」，但原版 `ProgressLayout.EMPTY` 会把 `posterContent`（含 btnResume）整体隐藏，二者冲突；本次按原版隐藏内容、空态无焦点 | `HistoryScreen` 空态分支 |

补充说明（非待确认，已核实）：
- `pageSite` 底色 `@color/red` 在 `ref_docs/colors.xml` 里没有条目，取本仓库 `res/values/colors.xml` L56 `#E50914`。
- `home_tmdb_tag` 字符串资源在本仓库不存在，`HeroPosterCard` 内直接使用字面量「TMDB」（不新增 strings 条目）。
- `ic_arrow_down_circle.xml` 在本仓库原本缺失，已按 `ref_docs` 逐字节新增。

---

## 9. 未实现清单

1. **长按触发 TMDB 匹配**（`o11.c0()` L248-294 → `pc3.i0`）：不复刻。
2. **`KEYCODE_MENU` 切换编辑模式**（`o11.c0()` L252-255 `m0(!g0)`）：不复刻；编辑模式改由「长按确定键 500ms」进入、BACK 退出（卡片遮罩本身已实现）。
3. **`m11` case 9 站点备注队列**（`o11.l0()` / `u0` / `t0`）：不复刻。
4. **旧版网格 `HistoryActivity`**（leanback/mobile 源集）：不做，只做 `o11` 对应页。
5. **`gd3.h(...)` 元数据写入 / `je2.e(...)` 图片下载缓存**：本工程无等价 TMDB 接口，改为纯 Coil 加载 + 预热。
6. **`heroLogoImg`（Logo 图）**：无数据源，恒不显示。
7. **`heroDescText`（简介）**：无数据源，恒不显示。
8. **`History.get()` 二次排序**：交给 `History.getForDisplay()`。

---

## 10. 验证状态（未编译，静态核对）

| 检查 | 结果 |
| --- | --- |
| `git status --short` 只含本文档声明的路径 | ✅（3 个代码/资源文件 + 1 个文档） |
| 所有 dp / sp / 颜色 / 时长均有 `ref_docs` 出处或标注待确认 | ✅ |
| 卡片未内联重写、只 `import` 全局 `HeroPosterCard` | ✅ |
| 未调用/修改被禁文件（PlayerManager、PlaybackActivity、catvod、`ds3`、`Config`、`History`） | ✅ |
| 点击分支未跳过「Config 判空 → 切源」前两步 | ✅（`HistoryViewModel.open`） |
| 无 `AlertDialog` / `TextButton` / 任何弹窗删除确认 | ✅（`grep` 结果 0） |
| 无 `combinedClickable` / `setOnLongClickListener` 处理遥控器长按 | ✅（长按在 `setOnKeyListener` 里手写 DOWN/UP + 500ms `Job`） |
| 「查看详情」显式传 `renderState.selectedItem` 并打 `Log.e("HistoryDebug", …)` | ✅（`openDetail(activity)` 的默认参数取 ViewModel 选中项，假数据模式下为空 → 会静默 return） |
| **编译** | ❌ 按任务要求未执行，存在未验证点：Compose 焦点在 `AndroidView` 子 View 上的接管、`LazyRow` 对 1.08 缩放的裁剪行为、Coil `SingletonImageLoader` 预热调用、`KEYCODE_BACK` 是否先到 `OnKeyListener` |
