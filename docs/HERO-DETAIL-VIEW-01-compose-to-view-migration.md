# HERO-DETAIL-VIEW-01 · 详情页 Compose → View 迁移

- 状态：**Plan A 已实施，未编译**（按红线：本轮不编译，由用户自行构建验证）
- 基线 tag：`recovery/hero-detail-compose-baseline`（commit `615ff5dca`）
- 阶段：
  1. Plan B · 状态机抽离 → 第 1-8 节（已完成，commit `303e12f7d` 前）
  2. 阶段 A 第一步 · 静态 XML 骨架 → 第 9 节（已完成，commit `303e12f7d`）
  3. 阶段 A 第二步 · 纯 View 页面接真实数据 → 第 10 节（本轮）
- 本文件是这次迁移**唯一**的任务文档，不再另建 plan / fix / assessment 文件。

---

## 1. 目标与边界

**目标**：把 `HeroDetailScreen` 里的状态、result 分流、跨源兜底、folder 下探、TMDB 加载、收藏读写、弹窗回焦全部搬进一个普通 Kotlin 类，让 Plan A（纯 View 重写）只剩「摆布局 + 挂生命周期」。

**红线（本轮已遵守）**：

1. UI 一行不改：`HeroDetailScreen` 的渲染树（原 L1354-1716）与所有子 Composable（标题 / 背景 / 遮罩 / 海报 / 演员行 / 演员弹窗 / 按钮行）**逐字未动**。
2. 不改变可见效果：状态仍是同一份 Compose snapshot state，`LaunchedEffect` 的 **key 全部留在 Compose**，重组与取消时序不变。
3. 不编译。

**刻意不做的事**：不删 Compose 依赖、不动 `HeroCommonUi.kt` / `HeroShared.kt`、不碰 `build.gradle`。

---

## 2. 架构（before → after）

### before

```
HeroDetailActivity (ComponentActivity)
└─ setContent { HeroDetailScreen(...) }
   ├─ 20 个 var x by remember { mutableStateOf(...) }      ← 状态
   ├─ 本地 fun requestPlayTarget / updateKeepButtonState / toggleKeep
   ├─ 8 个 LaunchedEffect + 2 个 DisposableEffect          ← 隐式行为
   └─ 渲染树 + 子 Composable
```

### after

```
HeroDetailActivity (ComponentActivity)
└─ setContent { HeroDetailScreen(...) }
   ├─ val state = remember { HeroDetailStateManager(...) }  ← 状态与逻辑的唯一归属
   ├─ var x by state.xSlot                                  ← 同名前缀别名，渲染代码零改动
   ├─ LaunchedEffect(key) { state.xxx() }                   ← 只留壳，key 不变
   └─ 渲染树 + 子 Composable（逐字未动）

HeroDetailStateManager (internal class, 同包)
   ├─ 20 个状态槽（slot + 同名普通属性）
   ├─ requestPlayTarget / onPreSearchReady / onFallbackTimeout / onPlayDialogShown
   ├─ onDetailResult（result 通道 5 级分流）
   ├─ loadTmdb / buildPage
   ├─ updateKeepButtonState / toggleKeep
   └─ restoreFocus / clampActorDialogIndex
```

**为什么用「slot + 同名属性」而不是把 `by` 留在 Composable**：

- `xxxSlot` 给 Compose 侧做 `by` 委托（`var playFlags by state.playFlagsSlot`），读写点与重构前**同名同址**；
- 同名普通属性（`var playFlags by playFlagsSlot`）给状态机内部用，搬过来的逻辑体可以一个字不改；
- 两者指向**同一个** `MutableState`，不存在第二份状态，也不会出现「两处不同步」。

**为什么 effect key 不搬**：`LaunchedEffect(key)` 的语义是「key 变 → 取消上一次协程 → 重跑」。在纯 Kotlin 里手写 `Job.cancel()` + 重新 `launch` 无法保证与组合时序（尤其是「同一帧内 key 变化」）完全一致，属于可见行为变化。所以：**逻辑体搬走，key 与外壳留下**。Plan A 时把外壳换成生命周期回调即可，见第 5 节。

---

## 3. 状态清单（20 项，全部搬到状态机）

| # | 状态 | 类型 | 用途 | Compose 侧别名 |
|---|---|---|---|---|
| 1 | `detailResult` | `Result?` | 详情 / 播放兜底 / folder 三路共用回包 | `state.detailResultSlot` |
| 2 | `tmdbState` | `TmdbDetailState` | TMDB 元数据 Idle/Loading/Ready/Error | `state.tmdbStateSlot` |
| 3 | `showSourceDialog` | `Boolean` | 换源弹窗可见 | `state.showSourceDialogSlot` |
| 4 | `showPlayDialog` | `Boolean` | 选集弹窗可见 | `state.showPlayDialogSlot` |
| 5 | `playFlags` | `List<Flag>` | 选集弹窗实际线路 | `state.playFlagsSlot` |
| 6 | `playVod` | `Vod?` | 选集弹窗标题 / 起播 id | `state.playVodSlot` |
| 7 | `playSiteKey` | `String` | 起播源（本页 / 跨源） | `state.playSiteKeySlot` |
| 8 | `pendingPlayId` | `String?` | 等预搜索目标详情回包 | `state.pendingPlayIdSlot` |
| 9 | `pendingPlaySite` | `Site?` | 兜底目标源 | `state.pendingPlaySiteSlot` |
| 10 | `pendingPlayVod` | `Vod?` | 兜底目标剧 | `state.pendingPlayVodSlot` |
| 11 | `pendingPlayOpen` | `Boolean` | 点播放时等本页详情 | `state.pendingPlayOpenSlot` |
| 12 | `playLoading` | `Boolean` | 播放链路 Loading / 防重入 | `state.playLoadingSlot` |
| 13 | `pendingPlayDrill` | `Int` | folder 下探层数（上限 3） | `state.pendingPlayDrillSlot` |
| 14 | `pendingPlayCategory` | `String?` | 等二级目录回包 | `state.pendingPlayCategorySlot` |
| 15 | `pendingPlayFallback` | `Boolean` | 等预搜索结果 | `state.pendingPlayFallbackSlot` |
| 16 | `autoSwitchNotice` | `Boolean` | 自动换源成功提示 | `state.autoSwitchNoticeSlot` |
| 17 | `actorDialogIndex` | `Int?` | 演员弹窗下标 | `state.actorDialogIndexSlot` |
| 18 | `pendingCastFocus` | `Int` | 关弹窗后回哪个演员 | `state.pendingCastFocusSlot` |
| 19 | `pendingActionFocus` | `Int` | 关弹窗后回哪个按钮 | `state.pendingActionFocusSlot` |
| 20 | `keepCheckedState` | `MutableState<Boolean>` | 收藏按钮态 | `state.keepCheckedState` |

**派生常量**（也搬进状态机，进页后不变）：`ownsResultChannel`、`special`、`emptyHint`、`keepKey`。

**留在 Composable 的纯 UI 状态**：`focusedAction`、`focusedCast`（焦点记忆，只有渲染侧读写）、`actionRequesters`、`castRequesters`（`FocusRequester` 属于 Compose 焦点系统）。

---

## 4. 行为清单（10 个 effect，逐个给出挂载点）

> 说明：此前口头提到的「36 个 LaunchedEffect」是**包含注释与参数类型在内的 36 行命中**；实际 effect 数量是 **8 个 LaunchedEffect + 2 个 DisposableEffect = 10 个**。本轮搬走 8 个 effect 的**逻辑体**，2 个 UI 自持的 effect 原样保留。

### E1 · 预搜索兜底续跑（已搬）

- 位置：原 `HeroDetailActivity.kt:845`
- 壳：`LaunchedEffect(firstSearchVod, firstSearchSite, pendingPlayFallback) { state.onPreSearchReady(firstSearchVod, firstSearchSite) }`
- 触发：预搜索结果到达（`firstSearchVod/Site` 变化）或「播放」刚把 `pendingPlayFallback` 置 true。
- 后果：`pendingPlayFallback` 为 true 且结果有效 → 清标记、`autoSwitchNotice = true`、`requestPlayTarget(vod, site)`（folder 走 `categoryContent`，否则 `detailContent`）。
- **View 挂载**：两个入口调用同一个 `state.onPreSearchReady(...)` —— ① 预搜索观察者回调；② 播放按钮里 `pendingPlayFallback = true` 之后。也可在 `onResume` 兜一次。

### E2 · 兜底 10 秒超时（已搬）

- 位置：原 `:856`
- 壳：`LaunchedEffect(pendingPlayFallback) { state.onFallbackTimeout() }`
- 触发：`pendingPlayFallback` 翻转。
- 后果：仍为 true 时 `delay(10_000)`，再检查；仍为 true → 清标记、`playLoading = false`、`Notify.show(emptyHint)`。
- **View 挂载**：置 true 时 `Handler.postDelayed(10s)` + 代次 token（或 `lifecycleScope.launch { delay(...) }`）；置 false / `onDestroy` 时取消。注意超时后要二次检查标记，不能无条件执行。

### E3 · 自动换源成功提示（已搬）

- 位置：原 `:866`
- 壳：`LaunchedEffect(showPlayDialog, autoSwitchNotice) { state.onPlayDialogShown() }`
- 触发：选集弹窗可见性或 `autoSwitchNotice` 变化。
- 后果：弹窗可见且标记为 true → 清标记 + `Notify.show("已为您自动切换至可用源")`。
- **View 挂载**：`EpisodeViewDialog` 的 `setOnShowListener`（或 `show()` 之后立刻调用）。

### E4 · result 通道观察者（逻辑已搬，观察者注册保留）

- 位置：原 `:871`（`DisposableEffect(siteViewModel, vodId)`）
- 壳：`DisposableEffect(...) { Observer<Result?> { state.onDetailResult(result, latestSearchVod, latestSearchSite) } ... }`
- 触发：进入组合 / `siteViewModel`、`vodId` 变化；离开时 dispose。
- 后果（`onDetailResult` 内部优先级，不可调换）：
  1. `pendingPlayOpen`：本页详情回包 → 有真实 http/m3u8 直接弹选集；没有 → 有预搜索结果就换源，没有就登记等待；
  2. `pendingPlayCategory`：二级目录回包 → 取第一个有效条目；自带线路直接弹选集；仍是 folder 且未超 3 层 → 继续下探；否则提示「该源暂无可用剧集」；
  3. `pendingPlayId`：兜底目标详情 → 有集数弹选集；0 集数且像 folder / 带动作指令 → 下探目录；
  4. `showSourceDialog`：换源弹窗占用通道，本页让路；
  5. `!ownsResultChannel`：本页不发详情请求，一律忽略；
  6. 其余：写 `detailResult`。
- **View 挂载**：`onCreate`/`onStart` `observeForever`，`onDestroy` `removeObserver`。View 版必须自己维护「最新一次预搜索结果」两个字段（替代 `rememberUpdatedState`），并在预搜索回调里更新。

### E5 · TMDB 元数据加载（已搬）

- 位置：原 `:1170`
- 壳：`LaunchedEffect(vodId, siteKey, vod?.name, vod?.year) { state.loadTmdb(vod) }`
- 触发：剧集身份变化（或首个带 name/year 的 Vod 到达）。
- 后果：Key/站点闸门 → 首页预取命中 → 手动/自动匹配缓存 → 搜索匹配 → 详情 + 演员 → 写 `tmdbState`；失败写 `Error`，鉴权失败按「无数据」处理；已 Ready/Loading 时直接返回。
- **View 挂载**：`onCreate` 起一个 `lifecycleScope.launch`；`detailResult` 首次给出 name/year 时再触发一次；`onDestroy` 取消。`loadTmdb` 内部已有 Ready/Loading 守卫，重复调用安全。

### E6 · 收藏态刷新（已搬）

- 位置：原 `:1316`
- 壳：`LaunchedEffect(keepRefreshTick, keepKey) { updateKeepButtonState() }`
- 触发：进页 + 每次 `onResume`（tick 自增）+ key 变化。
- 后果：`Keep.find(keepKey) != null` → 收藏按钮实心/空心。
- **View 挂载**：`onResume()` 直接调用 `state.updateKeepButtonState()`，然后刷按钮。

### E7 · 弹窗关闭后的焦点恢复（已搬）

- 位置：原 `:1322`
- 壳：`LaunchedEffect(showPlayDialog, showSourceDialog, actorDialogIndex) { state.restoreFocus(actionRequesters, castRequesters) }`
- 触发：三个弹窗状态任一变化。
- 后果：全部关闭后 → 优先回 `pendingCastFocus`（5 帧重试）→ 其次回 `pendingActionFocus`（5 帧重试）→ 否则回「播放」（3 帧重试）。`withFrameNanos` 用于等弹窗窗口真正消失、目标重新可聚焦。
- **View 挂载**：三个 Dialog 的 `setOnDismissListener` 里调用；重试循环用 `view.postOnAnimation`（最多 5 次）代替 `withFrameNanos`。首次进页的「默认焦点播放」在 `onCreate` 后 `post` 一次。

### E8 · 演员下标收敛（已搬）

- 位置：原 `:1349`
- 壳：`LaunchedEffect(page.cast.size) { state.clampActorDialogIndex(page.cast.size) }`
- 触发：演员列表长度变化（TMDB 到达替换占位数据）。
- 后果：弹窗开着且下标越界 → 夹到 `0..lastIndex`；列表空 → 关闭弹窗。
- **View 挂载**：演员 Adapter `submitList` 之后调用；返回 null 且弹窗在显示 → dismiss。

### E9 · 选集弹窗生命周期（**留在 UI，未搬**）

- 位置：原 `:1679`（`DisposableEffect(playFlags)`）
- 触发：`showPlayDialog && playFlags.isNotEmpty()`，且 `playFlags` 身份变化。
- 后果：创建并 `show()` 纯 View 的 `EpisodeViewDialog`；dispose 时 `dismiss()`；选中集数 → 关弹窗 + `startEpisode`。
- **不搬理由**：这是 Dialog 窗口的生命周期，不是业务状态；搬到状态机会让「谁负责 dismiss」变得隐晦。
- **View 挂载**：View 版直接在「弹选集」分支里 `dialog.show()`，`onPause`/`onDestroy` dismiss。

### E10 · 演员档案加载（**留在 UI，未搬**）

- 位置：原 `:2039`（`HeroActorDialog` 内部 `LaunchedEffect(actor.id)`）
- 触发：弹窗打开 / 演员变化。
- 后果：`ActorProfileCache` 命中直接显示；未命中 → `loadActorProfile`（当前是 2 秒假数据）→ 写缓存 → 刷新弹窗。
- **不搬理由**：缓存与请求的作用域就是这个弹窗实例；放到 Activity 级会改变「关掉再开是否重新加载」的时机。
- **View 挂载**：演员 Dialog 自己的 `onCreate` 里起协程，`onDismiss` 取消。

---

## 5. Plan A（纯 View）挂载点速查

| 原 effect | View 生命周期 |
|---|---|
| E1 预搜索续跑 | 预搜索回调 + 播放点击后 |
| E2 10 秒超时 | `postDelayed` / `lifecycleScope` + 代次 token |
| E3 自动换源提示 | 选集 Dialog `setOnShowListener` |
| E4 result 观察者 | `onStart` observeForever / `onDestroy` removeObserver |
| E5 TMDB | `lifecycleScope.launch`（详情首次可用时触发，destroy 取消） |
| E6 收藏刷新 | `onResume` |
| E7 回焦 | 三个 Dialog `setOnDismissListener` + `postOnAnimation` 重试 |
| E8 演员下标 | 演员 Adapter `submitList` 后 |
| E9 选集弹窗 | 直接 `show()` / `dismiss()` |
| E10 演员档案 | 演员 Dialog 自身生命周期 |

**结论**：Plan A 不需要重新推导任何业务规则，只需把上表 10 行接到 View 生命周期上，然后把 `HeroDetailScreen` 的渲染树翻译成 XML / 代码布局。状态机文件可以直接复用（把 `mutableStateOf` 换成 `Observable` 或回调）。

---

## 6. 验证记录（静态，未编译）

| 检查 | 结果 |
|---|---|
| 搬走的 `onDetailResult` 观察者体（252 行）与原文件逐行比对（归一化空白后） | **252/252 完全一致** |
| 其余 10 段搬走逻辑（`requestPlayTarget` / 预搜索 / 超时 / 提示 / `buildPage` / `loadTmdb` / `updateKeepButtonState` / `toggleKeep` / `restoreFocus` / `clamp`）逐行包含性检查 | 除 6 行「签名 / 参数声明 / 刻意改写」外**全部命中** |
| `HeroDetailActivity.kt` 大括号配平 | 294 / 294 |
| `HeroDetailStateManager.kt` 大括号配平 | 131 / 131 |
| 残留的旧状态声明（`var x by remember { mutableStateOf }`） | 0（仅剩 `focusedAction` / `focusedCast` 两个纯 UI 焦点记忆） |
| `@@SPLICE` 临时标记 | 0 |
| `git diff --check` | exit 0 |
| 编译 | **未执行（按红线）** |

**可见行为不变的理由**：

1. 状态对象与初值完全相同，Compose 读的仍是同一个 `MutableState`；
2. `LaunchedEffect` 的 key 表达式逐字未改，重组/取消时序不变；
3. 渲染树、子 Composable、dp/sp 常量、焦点请求顺序一行未改；
4. 搬走的逻辑体是原文复制（观察者体已做逐行证明）。

---

## 7. 回滚

- 基线 tag：`recovery/hero-detail-compose-baseline` → `10a273e5676475f7d53769fb373af84daa9b9a40`
- 单文件回滚：`git checkout recovery/hero-detail-compose-baseline -- app/src/main/java/com/fongmi/android/tv/ui/activity/HeroDetailActivity.kt`
- 新增文件可直接删除：`HeroDetailStateManager.kt`、本文件。
- 无数据库 / 资源 / 依赖变更，回滚无副作用。

---

## 8. 下一步

1. 用户编译验证本轮改动（预期：页面表现与基线完全一致）。
2. 通过后提交 + 打 recovery tag。
3. 再开 Plan A：按第 5 节挂载点把 `HeroDetailScreen` 翻译成纯 View，逐步替换 `HeroCommonUi.kt` / `HeroShared.kt`，最后再动 `build.gradle` 的 Compose 依赖。

---

## 9. 阶段 A 第一步：静态骨架（2026-11-12，未编译）

目标：只画纯 View 的静态 XML，不接数据、不写 Activity 业务逻辑；先在电视上核对排版和尺寸。

新增文件：

- `app/src/main/res/layout/activity_hero_detail_view.xml`：主页面骨架。
- `app/src/main/res/values/styles_hero_detail.xml`：按钮 / 演员项样式。
- `app/src/main/res/drawable/bg_hero_detail_*`：背景 / 海报 / 头像 / Logo 占位 + 横向 / 纵向遮罩。
- `app/src/main/res/drawable/shape_hero_detail_*` / `selector_hero_detail_*`：按钮和头像焦点态。
- `app/src/main/res/color/selector_hero_detail_cast_name.xml`：演员名焦点色。
- `app/src/main/res/animator/hero_cast_focus_scale.xml`：头像 1.08 焦点放大。
- `app/src/main/java/com/fongmi/android/tv/ui/activity/HeroDetailViewPreviewActivity.kt` + Manifest 节点：临时预览壳，只有 `setContentView`；确认排版后删除。

关键尺寸（照抄 Compose）：

- 内容区左右 24dp、底部 32dp；标题 36dp / 96dp、最宽 720dp；标题 → 元数据 2dp；元数据 20dp；元数据 → 简介 4dp；简介最宽 440dp、12sp、4 行；credit 上 8dp。
- 按钮 36dp、13sp 加粗、左右 16dp、图标文字 4dp、按钮间距 12dp、4dp 圆角。
- 演员区上 16dp、标题 14sp、标题 → 列表 10dp、项宽 76dp、头像 60dp、项间距 12dp、名字 6dp / 12sp、焦点 2dp 红圈 + 1.08。
- 海报 150×210dp、top 72dp、end 80dp、内边距 4dp、圆角 12dp。
- 横向遮罩 0% 85% 黑 → 35% 透明 → 100% 透明；纵向 0 / 55% 透明 → 100% 90% 黑。

预览：

```bash
adb shell am start -n com.silent.android.webhtv/com.fongmi.android.tv.ui.activity.HeroDetailViewPreviewActivity
```

或在 Android Studio 里把 Run Configuration 的 Activity 选成 `HeroDetailViewPreviewActivity`。

静态验证：

- 18 个新增 / 改动 XML 全部通过 PowerShell `[xml]` 良构性解析。
- 新增资源引用 35 个，除 Manifest 里既有 `Theme.App` / `Theme.Crash`（在 flavor 资源中定义）外全部命中。
- 布局内 `@+id` 无重复；`@id/castItem0`、`@id/actionPlay` 引用均存在。
- `git diff --check` 仅提示 Manifest 的 LF / CRLF 转换，无空白错误。
- 未编译（按红线）。

下一步：

1. 用户电视核对排版 / 尺寸；若有偏差，只改 XML 常量。
2. 确认后删除临时预览壳，接 Plan A 数据 / 焦点逻辑。

---

## 10. 阶段 A 第二步：纯 View 页面接真实数据（2026-11-12，未编译）

目标：把 `HeroDetailActivity` 从 `ComponentActivity + setContent` 改成 `AppCompatActivity + setContentView(R.layout.activity_hero_detail_view)`，用**真实 Intent / 详情接口 / TMDB** 数据渲染，视觉与焦点契约对齐 Compose 版。

### 10.1 架构

```
HeroDetailActivity (AppCompatActivity)
├─ setContentView(activity_hero_detail_view.xml)   ← 25 个 id，代码 findViewById
├─ HeroDetailStateManager                          ← 逻辑唯一归属（未改动）
│   onDetailResult / requestPlayTarget / onPreSearchReady / onFallbackTimeout /
│   onPlayDialogShown / loadTmdb / buildPage / updateKeepButtonState / toggleKeep / restoreFocus
├─ render()  ← 纯函数式：currentPage() → 各个 bindXxx
├─ HeroDetailCastAdapter（新增）→ item_hero_detail_cast.xml
└─ 复用 EpisodeViewDialog / ChangeSourceDialog（原本就是纯 View）
```

**Activity 不再持有任何 Compose 状态**：`focusedAction` / `focusedCast` 用普通 `Int`，焦点请求用 `View.requestFocus()` + `postOnAnimation` 重试，替代 `FocusRequester` / `withFrameNanos`。

### 10.2 文件清单

| 文件 | 动作 | 说明 |
|---|---|---|
| `ui/activity/HeroDetailActivity.kt` | 重写类体，保留 helper 尾部 | 类体改为 View 渲染 + 生命周期；`needsSpecialParse` 之后的数据模型 / 合并函数 / 常量**逐字取自 HEAD**，避免逻辑漂移 |
| `ui/activity/HeroDetailStateManager.kt` | 未改动 | 本轮零改动，仍是唯一逻辑归属 |
| `ui/hero/HeroDetailCastAdapter.kt` | 新增 | 演职员行适配器，焦点下标 + 端点消费 |
| `res/layout/activity_hero_detail_view.xml` | 改为真实数据布局 | 删掉硬编码 demo 演员 / 图标；演员行换成 `RecyclerView` |
| `res/layout/item_hero_detail_cast.xml` | 新增 | 76dp 项宽、60dp 圆头像、12sp 单行名 |
| `res/values/styles_hero_detail.xml` | 微调 | 演员项样式对齐 wrap_content + 76dp |
| `ui/activity/HeroDetailViewPreviewActivity.kt` | 删除 | 临时预览壳已完成使命，连同 Manifest 节点一起删 |
| `AndroidManifest.xml` | 改注释 | 去掉预览壳，详情页注释改为纯 View 入口 |

### 10.3 数据流（与 Compose 版一致）

1. **Intent 首帧** `parseIntent()` → `HeroDetail`：剧名 / 元数据 / 简介 / 海报 / 横图 / Logo / 是否已带线路。
   - 横图只认 `TmdbItem.backdropUrl`（真横图），绝不拿竖图 `pic` 铺满；
   - 真实条目演员列表为空，TMDB 未命中时整行隐藏（只有 adb demo 入口用假数据）。
2. **详情请求**：`inlineVod` 有线路 → 跳过请求；`vodId` 为空 / msearch 临时 ID → 直接空态；否则 `SiteViewModel.detailContent(refresh=true)`。
3. **result 分流**：`observeForever` → `state.onDetailResult(...)`（5 级优先级原样保留）。
4. **TMDB**：`lifecycleScope.launch { state.loadTmdb(vod) }`，`onDestroy` 取消；Ready/Loading 守卫在状态机内。
5. **渲染**：`currentPage() = state.buildPage(initialDetail, detailResult?.vod, tmdbState)`，每次基于新 base 重算，评分不叠加。

### 10.4 焦点契约（逐条对齐 Compose）

| 场景 | 行为 |
|---|---|
| 进页 | `post` 一次 → 焦点落在「播放」 |
| 按钮组 ←/→ | 在 播放 / 换源 / 收藏 之间移动，首末项端点消费 |
| 「播放」按 ↓ | 演员行非空 → 落在上次停留的演员；演员行为空 → **不消费**，交给系统搜索 |
| 演员行 ↑ | 回「播放」 |
| 演员行 ←/→ | 行内横移，首末项端点消费 |
| 演员行 ↓ | 已是最底部，消费 |
| 弹窗关闭 | `pendingCastFocus` → `pendingActionFocus` → 「播放」，`postOnAnimation` 最多 5 次重试 |

### 10.5 刻意的行为差异（有意识，非回归）

1. **没有 Compose 版演员档案弹窗**：点演员时命中 TMDB 人物直接进 `TmdbPersonActivity`；adb demo 入口的假演员只做回焦，不再弹假档案页。理由：真实条目演员来自 TMDB，人物页信息更全；假档案页只为 demo 存在。
2. **marquee 静态**：剧名 `marquee` 只在获得焦点时滚动；纯展示文本无焦点，故长标题省略而不滚动。理由：详情页标题不可聚焦，滚动会与「纯展示」契约冲突。
3. **demo 占位图保留但不再被布局引用**：`bg_hero_detail_demo_backdrop/logo/poster` 仍留在仓库（demo 入口可复用），布局不再硬编码。

### 10.6 静态验证（未编译）

| 检查 | 结果 |
|---|---|
| 布局 / Manifest XML 良构性（`[xml]`） | 通过 |
| 布局 `@+id` 数量 / 唯一性 | 25 / 25 唯一 |
| Activity 里 `R.id.*` 引用（17 个）在布局中存在 | 全部命中 |
| Activity 里 `state.*` 成员（33 个）在状态机中存在 | 全部命中 |
| `HeroDetailActivity.kt` 大括号配平 | 219/219（圆括号 597/603 的差额来自 `// 1)` `// 2)` 这类注释，不是语法问题） |
| 残留 Compose UI 符号（`Composable` / `setContent` / `HeroDetailScreen` / `remember` / `Modifier` / `AsyncImage`） | 0（`setContentView` 命中不算；`LaunchedEffect` / `AsyncImage` 仅出现在注释里） |
| `git diff --check` | 仅 LF→CRLF 提示，无空白错误 |
| 编译 | **未执行（按红线）** |

保留的 Compose 依赖：`RoundedCornerShape` / `Color` / `Shadow` / `Dp` / `dp` 仅用于**保留的常量尾部**（`DETAIL_*` / `POSTER_*` 等），本轮不删依赖。

### 10.6.1 已知遗留（编译告警，不影响功能）

尾部常量里有 63 个 Compose 尺寸常量已经**没有任何引用**（尺寸全部落到了 XML），`focusAction` / `focusCast(List<FocusRequester>, Int)` 两个 Compose 焦点辅助也已无人调用；`HeroDetailStateManager.restoreFocus(actionRequesters, castRequesters)` 同样已被 View 版 `restoreFocus()` 取代。它们只会产生「never used」告警，不影响编译和运行。

**刻意本轮不删**：先把「真实数据 + 焦点行为」验证通过，再在下一阶段（删 Compose 依赖时）连同 `requestDetailFocus` 的 Compose 签名一起清理，避免这次构建同时引入行为改动和清理改动，出问题时分不清原因。


### 10.7 用户验证步骤

```bash
# 1. 编译
./gradlew :app:compileLeanbackArmeabi_v7aDebugKotlin

# 2. demo 入口（无 Intent 参数，整页回落 DEMO_DETAIL）
adb shell am start -n com.silent.android.webhtv/com.fongmi.android.tv.ui.activity.HeroDetailActivity

# 3. 真实入口：从首页海报 / 历史 / 换源进详情
```

电视上逐项核对：排版尺寸、背景 / 海报、标题 / 元数据 / 简介、按钮组焦点态、演员行焦点放大与红圈、播放 → 选集弹窗 → 起播、换源弹窗、收藏切换、弹窗关闭回焦。

### 10.8 回滚

```bash
git checkout recovery/hero-detail-compose-baseline -- \
  app/src/main/java/com/fongmi/android/tv/ui/activity/HeroDetailActivity.kt \
  app/src/main/AndroidManifest.xml \
  app/src/main/res/layout/activity_hero_detail_view.xml \
  app/src/main/res/values/styles_hero_detail.xml
rm app/src/main/java/com/fongmi/android/tv/ui/hero/HeroDetailCastAdapter.kt \
   app/src/main/res/layout/item_hero_detail_cast.xml
```

`HeroDetailStateManager.kt` 与本轮无关，回滚不需要动它。

### 10.9 下一步

1. 用户编译 `:app:compileLeanbackArmeabi_v7aDebugKotlin`，把编译错误原样贴回来（只做窄修复，不扩大范围）。
2. 电视验证第 10.7 节清单；有偏差只改对应常量 / 布局，不改业务逻辑。
3. 通过后提交 + recovery tag（本轮改动尚未提交）。
4. 下一阶段：清理 10.6.1 的 Compose 遗留（常量 / 焦点辅助 / `restoreFocus` 重载），再评估移除 Compose 依赖与 `HeroDetailScreen` 死代码。

### 10.10 工作区丢失与恢复（2026-11-12，已恢复）

本轮收尾时发现工作区里 4 个文件凭空消失（`HeroDetailActivity.kt`、`HeroDetailStateManager.kt`、`activity_hero_detail_view.xml`、`styles_hero_detail.xml`，以及未跟踪的 `item_hero_detail_cast.xml`），`git status` 显示为 `D`。磁盘、回收站、卷影副本均无残留。

恢复来源（全部为**本次会话自身的快照/记录**，不是重新手写）：

- `HeroDetailActivity.kt`：OpenCode 会话快照库 `~/.local/share/opencode/snapshot/<project>/<repo>` 中的 blob `3e74bc59…`，62,436 字符、5 处 CRLF、大括号 219/219 —— 与丢失前的指纹（61,975 字符是更早一版，见下）逐项对齐后确认是**最后一次编辑后**的版本。
- `activity_hero_detail_view.xml`：blob `1e764179…`（14,106 字符、25 个 id、无 `drawableStart`）。
- `styles_hero_detail.xml`：blob `9eae927f…`（3,774 字符）。
- `item_hero_detail_cast.xml`：blob `3e13336f…`（872 字符，根布局 76dp）。
- `HeroDetailStateManager.kt`：本轮未改动，`git checkout HEAD --` 即恢复（内容与会话记录逐字节一致）。
- `HeroDetailCastAdapter.kt`：未受影响，仍在工作区（与会话记录逐字节一致）。

恢复后重新跑了第 10.6 节全部静态检查，结果一致（17 个 `R.id`、25 个布局 id、33 个 `state.*` 成员、XML 良构、样式引用齐全）。

**教训**：本轮改动一直未提交，等于把全部成果押在工作区上。恢复后第一件事应当是编译验证通过即提交 + 打 tag，不要在未提交状态下继续叠加改动。

### 10.11 首次 CI 编译错误修复（2026-11-12，+41 行）

CI（`da07193c0`）编译 `:app:compileLeanbackArmeabi_v7aDebugKotlin` 报 2 个错，且只有这 2 个：

| 位置 | 错误 |
| --- | --- |
| `HeroDetailActivity.kt:194:61` | `Unresolved reference 'hydrateInlineEpisodes'` |
| `HeroDetailActivity.kt:920:41` | `Unresolved reference 'TMDB_BACKDROP_WIDTH'` |

**根因**：把纯 View 版正文与保留的辅助函数尾巴拼接时，被丢弃的那段区域里除了 Compose UI 组件，还夹着两个**非 UI** 声明：

- `private fun Vod.hydrateInlineEpisodes(): Boolean`（含 KDoc，`Vod.isFolderTarget` 之后）
- `private const val TMDB_BACKDROP_WIDTH = "w1920"`（含 KDoc，`PLAY_FALLBACK_WAIT_MS` 之后）

这两处**调用点**（第 194 行内联线路水合、第 920 行首帧横图提档）属于纯 View 版仍在用的逻辑，因此保留了下来，而声明被一起删掉 —— 所以报错恰好成对出现，没有第三种错误。

**修复**：从 `30f3090a5` 逐字节取回这两个声明（含原 KDoc），插回原位置；diff 仅 `+41` 行，无其它改动。文件 63,854 字符、大括号 232/232、5 处 CRLF 保持原样。

**未做**：未跑本地编译（本机无 Gradle 环境），由用户/CI 验证。

### 10.12 演员行末位被切 + 选集翻页崩溃（2026-11-12）

用户反馈两处：① 详情页演员行 11 个头像，最后一个被屏幕右缘切掉；② 选集弹窗里只有线路 1 能左右翻页，线路 2 / 3 一翻页就崩溃（CustomActivityOnCrash 的瓢虫页）。

#### ① 演员行：76dp → 72dp

真机 `wm size = 1920x1080`、`wm density = 320` → 屏宽 960dp，内容区左右各 24dp，可用宽度 **912dp**。

旧版每项 76dp + 项间距 12dp（`HeroHorizontalGapDecoration`，末项不加间距）：

```
11 × 76 + 10 × 12 = 956dp  >  912dp     →  溢出 44dp，最后一位被切
11 × 72 + 10 × 12 = 912dp  =  912dp     →  正好铺满
```

改动：

- `values/styles_hero_detail.xml`：`Widget.HeroDetail.CastItem` 宽度 76dp → 72dp（注释写明算式）；
- `layout/item_hero_detail_cast.xml`：根布局 76dp → 72dp；
- `layout/activity_hero_detail_view.xml`：`castList` 去掉 `paddingEnd="24dp"`。外层 `detailContent` 已有 24dp 右内边距，这条 padding 在「内容正好 912dp」时只会多出 24dp 可滚动量，聚焦末位时整行可能被推走。
- `HeroDetailCastAdapter` 的 KDoc 同步。

头像 60dp、名字 12sp、间距 12dp、焦点 1.08 放大全部不变（放大后 64.8dp 仍在 72dp 项内，左右各溢出 2.4dp 进间距，不会重叠）。

#### ② 选集翻页崩溃：`isListBusy()` 闸门

**真机堆栈**（`adb logcat -b crash`，14:13:21 / 14:14:14 两次复现完全一致）：

```
java.lang.IllegalStateException: Cannot call this method while RecyclerView is computing a layout or scrolling
  androidx.recyclerview.widget.RecyclerView{... #7f0b0282 app:id/episodeRecycler}, layout:GridLayoutManager
  at RecyclerView.assertNotInLayoutOrScroll
  at EpisodeAdapter.submit → notifyDataSetChanged
  at EpisodeViewDialog.refresh
  at EpisodeViewDialog.selectFlag
  at EpisodeViewDialog$ChipAdapter.onFocusChange  (线路 chip)
  at ViewGroup.removeViewInternal → rootViewRequestFocus
  at LinearLayoutManager.detachAndScrapAttachedViews → GridLayoutManager.onLayoutChildren
  at RecyclerView.onMeasure ← 网格正在测量/布局
```

链条：翻页 → `episodeAdapter.submit()` → `notifyDataSetChanged()` → 下一帧网格重新布局 → `detachAndScrapAttachedViews` 移除持焦卡片 → `ViewGroup.removeViewInternal` 调 `rootViewRequestFocus()` 重新找焦点 → 此刻网格还没有子 View，焦点落到**第一个可聚焦 View = 线路 chip 0** → `onFocusChange` → `selectFlag(0)` → `refresh()` → 在正在布局的 RecyclerView 上 `notifyDataSetChanged()` → 崩。

**为什么只有线路 1 正常**：系统塞过来的焦点永远是 chip 0；`selectedFlag == 0` 时被 `onChipFocus` 里的 `index != selectedFlag` 挡掉，只有线路 2 / 3 才会真的走进 `selectFlag`。

修复（`EpisodeViewDialog.kt`）：新增 `isListBusy()`（三个 RecyclerView 的 `isComputingLayout` 取或），在 `selectFlag()` 与 `selectPage()` 入口**忽略**布局过程中的焦点事件。焦点随后由已有的 `requestCellFocus()` 重试循环还给网格（`notifyDataSetChanged` 会让 `findViewHolderForAdapterPosition` 返回 null，重试才会持续到布局结束，这正是必须保留全量刷新的原因，已在 `EpisodeAdapter.submit` 的 KDoc 里写死）。

这里**刻意不推迟一帧**（与 `HeroSearchActivity.render()` 的写法相反）：推迟会把系统塞给 chip 0 的焦点当成用户选择，静默把线路切回第 1 条。

#### 验证与回滚

- 静态：三个 XML 用 `minidom` 解析通过；三个 Kotlin 文件大括号/圆括号配平（110/110、11/11、16/16）；`72×11 + 12×10 = 912 = 960 − 48` 与布局里 `detailContent` 的 24dp 内边距吻合；`git diff --check` 无空白错误。
- 待真机验证：演员行 11 位全部完整显示（末位右边距 24dp）；线路 2 / 3 在网格左右边缘反复翻页不崩、且线路不会被悄悄切回线路 1；线路 1 行为不变。
- 回滚：`git checkout -- app/src/main/res/values/styles_hero_detail.xml app/src/main/res/layout/item_hero_detail_cast.xml app/src/main/res/layout/activity_hero_detail_view.xml app/src/main/java/com/fongmi/android/tv/ui/hero/EpisodeViewDialog.kt app/src/main/java/com/fongmi/android/tv/ui/hero/EpisodeAdapter.kt app/src/main/java/com/fongmi/android/tv/ui/hero/HeroDetailCastAdapter.kt`。

### 10.13 详情页头像红圈 + 首页顶栏 / Tab / 海报行四处对齐（2026-11-12，未编译）

用户报 5 个视觉问题：① 详情页演员头像「溢出红圈」；② 首页顶栏红色下划线偏低；③ 顶栏焦点到「设置」后 → 无处可去，希望环形回绕；④ 分类 Tab 常态黑底是方角，要求跟红色实心胶囊同形；⑤ 海报行静止 7 整卡 + 第 8 张卡边、第 5 张起才滚动、居中两侧各 3 整卡。

真机取样：`wm size = 1920x1080`、`wm density = 320` → 960×540dp（1dp = 2px），内容列左右各 24dp 内边距 → 可用 912dp。

#### ① 演员头像红圈：去掉 drawable 里的 `<inset>`

`Widget.HeroDetail.CastAvatarFrame` 是 60dp 圆，描边走 **foreground**（`selector_hero_detail_cast_border`，画在头像之上），头像走子 View + `clipToOutline`，焦点态由 `hero_cast_focus_scale.xml` 整框放大 1.08 倍。

旧 drawable 套了 `<inset 1dp>`（常态 0.5dp），圆环外径只剩 58dp，放大后 62.6dp；而头像被裁成 60dp 圆、放大后 64.8dp → 头像从红圈外侧露出一圈，看起来就是「溢出红圈」。

去掉 `<inset>` 后：`GradientDrawable` 自己会把形状矩形按半个描边宽内缩，2dp 描边正好落在 0..2dp（直径 56..60dp），外沿 = 头像框边界，放大后 64.8dp 与头像外沿完全重合。常态 1dp 同理（0..1dp）。两个 drawable 的注释都改成了这套几何说明。

#### ② 顶栏下划线：基准 44..46dp / 20dp 宽（放大 1.1 后 = 实测 45..47dp / 22dp）

原版 `ref_docs/1.png` 实测（焦点在「搜索」）：文字墨迹 y 23.5..39dp、红色短横线 y 45..47dp、横线宽 22dp。我们的 2.png 实测：横线 y 51.5..53.5dp、宽 29dp。

关键推断：原版焦点态把**整项**放大 1.1 倍（pivot = 64dp 行心 32dp）。把实测值反解回基准：

```
y：  32 + (44 − 32) × 1.1 = 45.2dp     → 基准 44..46dp  → 放大后 45.2..47.4dp  ≈ 实测 45..47
宽： 20 × 1.1 = 22dp                    → 基准 20dp      → 放大后 22dp        = 实测 22dp
```

同一模型也解释了文字：基准墨迹 24.3..38.4dp → 放大后 23.5..39.0dp = 实测 23.5..39dp。旧实现按 Compose 版在文字与横线之间留了 6dp（基准 50..52dp），放大后 51.8..54dp，比原版低约 7dp；横线按 26dp 画，放大后 28.6dp，比原版宽约 6.6dp。

改动（`HomeViewActivity.kt`）：`TOP_BAR_UNDERLINE_WIDTH_DP` 26 → 20；删掉 `TOP_BAR_UNDERLINE_GAP_DP`（6dp 间距）与横线的 `topMargin`；`TOP_BAR_UNDERLINE_RESERVE_DP` 由「间距 + 横线高 = 8dp」改为「横线高 = 2dp」。竖列结构、`gravity=CENTER`、1.1 倍缩放（作用在整列）全部保留 → 24dp 文字区落在 20..44dp、横线 44..46dp，放大后正好 45.2..47.4dp。

#### ③ 顶栏环形焦点：设置 →（右）→ 小房子 →（左）→ 设置

`HomeChipAdapter` 新增构造参数 `onRightEdge: () -> Unit`，只对 `HomeChipStyle.NAV` 的 item 注册 `setOnKeyListener`：`ACTION_DOWN` + `KEYCODE_DPAD_RIGHT` + `bindingAdapterPosition == itemCount - 1` 时调 `onRightEdge()` 并吞掉事件。`setupRecyclerViews` 传入 `{ homeBtn.requestFocus() }`。

反向在 `setupActions` 里给小房子注册 `setOnKeyListener`：`ACTION_DOWN` + `KEYCODE_DPAD_LEFT` → `focusNavItem(navAdapter.itemCount - 1)`（即「设置」）。`focusNavItem` 已有「holder 没挂上就先 `scrollToPosition` + 下一拍补抢」的逻辑，直接复用。

`dispatchKeyEvent` 只接管海报行的 ←/→（`posterFocused` 为真时），顶栏 / 小房子上的方向键原样落到 View 层，不会被 Activity 吞掉。

#### ④ 分类 Tab 常态底：方角 → 胶囊

`ref_docs/shape_chip_round_normal.xml` 是 `corners radius=28dp`，焦点 / 选中态用的全局胶囊也是 28dp。旧代码常态用 4dp 小圆角，聚焦瞬间会「方角变胶囊」。`TAB_IDLE_RADIUS_DP` 4 → 28：`GradientDrawable` 会把半径夹到 `min(w,h)/2 = 13dp`（26dp 行高），于是三态同形，只是填充色不同。

#### ⑤ 海报行：步进 136dp → 132dp

原版卡宽来自 `ref_docs/xx0.java` 的 `I(Style)`：`(960 − 6×16 − 48) / 7 = 116dp`，加 16dp 间距 → **步进 132dp**；`1.png` 实测卡心 81.8 / 214.2 / 346.8 / 479.2 / 611.8 / 744.8 / 876.5dp，间距恒为 132dp。

本项目海报卡固定 120dp（`HeroPosterCard`，历史页 / 搜索页共用，不能改），旧 `POSTER_GAP_DP = 16` 导致步进 136dp（`2.png` 实测卡心 83.5 / 219.8 / 355.5 / 491.8 / …）。改成 `POSTER_GAP_DP = 12`：

```
步进      = 120 + 12 = 132dp                     （= 原版 116 + 16）
静止      = 24 + 7×120 + 6×12 = 936dp            → 第 8 张起点 948dp，960dp 屏宽露出 12dp 卡边
居中      = 3×132 = 396dp = 480 − 24 − 60        → 焦点卡左沿 420dp，左侧正好 3 整卡贴住 24dp 内边距
第 5 张   = 24 + 4×132 + 60 = 612dp              → 滚动量 612 − 480 = 132dp
前 4 张   = 卡心 84 / 216 / 348 / 480dp          → dx ≤ 0，被 RecyclerView 夹成 0，不滚动
```

`CenterSmoothScroller` 与 `scrollPostersTo` 一行未改：首尾贴边、行首不滚本来就是「视口中心 − 目标中心」+ 系统夹取的自然结果，改步进后自动成立。

#### ⑥ CI 编译失败与修复（2026-11-12，第一次打包）

GitHub Actions `:app:compileLeanbackArmeabi_v7aDebugKotlin` 报 6 条错误，全部指向两处新增的 `setOnKeyListener`：

```
HomeViewActivity.kt:517:34 Argument type mismatch: actual type is 'Function2<View!, Int, Boolean>',
                                but 'Function3<View!, Int, KeyEvent!, Boolean>!' was expected
HomeViewActivity.kt:518:23 Unresolved reference 'action'
HomeViewActivity.kt:518:63 Unresolved reference 'keyCode'
HomeViewActivity.kt:2238/2239/2240 同形（adapter 里那一处）
```

原因：`View.OnKeyListener.onKey(View v, int keyCode, KeyEvent event)` 是**三参数**。写成 `{ _, event -> }` 时 Kotlin 把第二个参数推断成 `Int keyCode`，`event` 就变成了 `Int`，于是 `.action` / `.keyCode` 全部解析失败。

修复：两处都改成 `{ _, _, event -> }`（Kotlin 允许多个 `_` 占位），并在两处注释里写死「三参数」这条约束。资源侧无问题：同一份构建里 `mergeLeanbackArmeabi_v7aDebugResources` 已经通过（两个 drawable 都被资源合并正常处理）。

#### 验证与回滚

- 静态：两个 drawable 用 Python `minidom` 解析通过；`HomeViewActivity.kt` 大括号 / 圆括号 / 方括号配平（314/314、1065/1065、81/81）；`git diff --check` 无空白错误；上表四条几何断言（132 / 936 / 948+12 / 612）全部由脚本复算通过。
- 待真机验证：演员头像焦点态红圈完整包住头像、头像不再露到圈外；顶栏下划线在「搜索」下方 45..47dp、宽约 22dp；「设置」按 → 回到小房子、小房子按 ← 回到「设置」；分类 Tab 常态是胶囊、聚焦不再跳形；海报行静止 7 整卡 + 右侧 12dp 卡边，第 5 张起才滚动、居中时两侧各 3 整卡。
- 回滚：`git checkout -- app/src/main/java/com/fongmi/android/tv/ui/activity/HomeViewActivity.kt app/src/main/res/drawable/shape_hero_detail_cast_border_focused.xml app/src/main/res/drawable/shape_hero_detail_cast_border_normal.xml`。

### 10.14 长按 → 跳 Tab / 行尾翻页 / 小房子红圈 / 演员红圈配色（2026-11-12，未编译）

用户报 4 个问题：① 首页卡片焦点向右**长按**会跳到 Tab 栏，极小几率崩溃但难复现；② 首页卡片到最右端（例如一页 20 张、后面还有）不会下探 / 翻页；③ 首页小房子未聚焦时有一圈红圈，要去掉；④ 详情页演员头像焦点红圈复用全局红框色，现在的纯红太刺眼。

#### ① 长按 → 跳 Tab：整行左右键由 Activity 自己路由（原版 `l2(±1)` 语义）

旧实现只拦「最后一列按 →」和「第 0 张按 ←」，中间列交给系统 `FocusFinder`。两个漏洞：

1. `FocusFinder` 在最后一列找不到「同一光束」的候选时，会退化成**主轴搜索**，把焦点交给**上方**分类 Tab 行里最靠右那一项 —— 长按 → 一路按到行尾，最后一下就是这样跳到 Tab 栏的；
2. 位置解析用 `findFocus()?.let { findContainingViewHolder(it)?.absoluteAdapterPosition } ?: focusedIndex`：holder 正好处在 `notifyItemChanged` / `notifyDataSetChanged` 之后的一帧时 `absoluteAdapterPosition` 是 `NO_POSITION(-1)`，`-1 != items.lastIndex` → **边界判断失效、事件漏给几何搜索**。这就是「极小几率」那一档。

崩溃是同一个问题的下游：焦点落进 Tab 行 → `onTabFocused` 立刻 `tabAdapter.selectedIndex = index`（`notifyItemChanged`）+ `scrollTabToCenter`（`startSmoothScroll`）；这两步如果正撞上 Tab 行自己的 layout，就会抛 `IllegalStateException: Cannot call this method while RecyclerView is computing a layout or scrolling`。

改法（`HomeViewActivity.kt`）：

- 新增 `posterRowPosition()`：焦点在卡片子 View 上 → 向上找所属卡片；焦点是 RecyclerView 自己 → 退回 `focusedIndex`；`NO_POSITION` **原样返回 -1**，绝不伪装成「不是行尾」。
- `dispatchKeyEvent` 把两段「只拦边界」的代码合并成一段：`posterFocused` 时 ←/→ **全部吃掉**，相邻卡片直接 `focusPoster(target)`（= 原版 `l2(±1)`），行首 / 行尾原地不动，位置未知也吃掉等下一帧。事件从此不会漏到 `FocusFinder`，几何跳转与下游崩溃一起消失。
- `onTabFocused` 顶部加 `typeRecycler.isComputingLayout` 判断：布局中一律 `post { onTabFocused(index) }` 推迟一帧，`notifyItemChanged` / `startSmoothScroll` 不再可能撞进 layout。

#### ② 行尾下探（翻页）：`PagingState` = 原版 `T0` / `R0` / `V0` / `U0`

原版 `T1(Result)`（L927-983）与 `o2()`（L1742-1751）是一套完整分页：`T0` 当前 typeId、`R0` 每类已加载页、`V0` 还有下一页、`U0` 加载中；`V0 = page < pageCount || pageCount == 0`（L952），空页 / 整页重复条目则收口（L910 / L938-943）。

本项目此前**完全没有分页**：`homeContent()` 或 `categoryContent(..., "1", ...)` 一次拉完就结束，所以行尾按 → 只会停住。

新增（`HomeViewActivity.kt`）：

```
PagingState(typeId, siteKey, page, hasMore, loading, appending)   // 原版 T0 / - / R0 / V0 / U0 / -
resetPaging(typeId, siteKey)      // 换源 / 切 Tab / 进出目录时整份重开
applyFirstPageMeta(pageCount)     // hasMore = typeId != null && (1 < pageCount || pageCount == 0)
requestNextPage()                 // o2()：categoryContent(key, typeId, page + 1, true, {})
appendPage(list, site, pageCount) // T1 的追加分支：空页 / 全重复 → 收口；否则行尾追加
```

- 触发点两处：`onPosterFocused` 里焦点进到最后 `PAGE_PREFETCH_DISTANCE`（= 2）张时**预取**下一页；`dispatchKeyEvent` 行尾按 → 时兜底再要一次。两条都受 `hasMore` / `loading` 保护，不会重复请求。
- 追加只走 `HeroPosterAdapter.append(next)` → `notifyItemRangeInserted`：已显示的卡片（含 1.1 倍放大的焦点卡）**不重新绑定**，焦点、缩放、Coil 已加载的图、Hero 区都不被打断（用 `submit` 的 `notifyDataSetChanged` 会让整排回池、焦点卡瞬间缩小并重载海报）。
- 去重按 `posterKey`（= 原版按 `vod.getId()` 去重）：整页都重复说明源站没有新内容，直接收口，避免「一直按 → 一直追加同样的 20 张」。
- 目录下探栈 `FolderDrillState` 多存一份 `paging`（原版 `u31.j` 存的就是页码）：出栈返回上一层后还能继续往下翻。快照时清掉 `loading / appending` —— 进目录会 `cancelResult()`，那一页永远不会回来。
- **推荐 Tab 不分页**（`typeId = null`）：站点接口 `Spider.homeContent(boolean)` 没有页码参数，原版 `o2()` 第一条守卫也是 `T0 == null` 直接 return。可分页的是分类 Tab、目录下探，以及「首页只返回分类、自动加载第一个分类」那条路径（那里也补了 `resetPaging(first.typeId, ...)`）。

#### ③ 小房子未聚焦红圈：`shape_home_logo_normal` 改成完全透明

`selector_home_logo` 的默认档原来是 `shape_home_logo_normal` = 1dp `@color/red` 描边 + 透明填充，屏幕上就是一圈红圈。原版 `ref_docs/home_ui.png` 实测小房子周围是纯黑（x=52..100 / y=40..88 全 `#020201`），什么都不画。

改法：`shape_home_logo_normal.xml` 去掉 `<stroke>`，只留 `solid=transparent`；`selector_home_logo.xml` 注释同步。该 drawable 只被 `selector_home_logo` 引用（换源弹窗用的是 `shape_home_logo_normal_white`），互不影响；焦点档 `shape_home_logo_focused`（红渐变实心圆）一行未改。

#### ④ 演员焦点红圈配色：`hero_accent` → `osd_red_end`

`shape_hero_detail_cast_border_focused.xml` 原来描边 `@color/hero_accent`（#FF0000 纯红），在 60dp 头像上又细又亮。改成全局「细线红」`@color/osd_red_end`（#FFE53935）—— `colors.xml` 里 `osd_red_end` 就是首页 Tab 选中态 / 换源弹窗 / 列表边框 / 选择卡片共用的那一支（`osd_focus` 也指向它），详情页这一圈不再出现第二种红。几何（2dp、不套 `<inset>`、放大后 64.8dp 与头像外沿重合）完全不动。

#### 验证与回滚

- 静态：四个 XML 用 Python `minidom` 解析通过（`shape_home_logo_normal` 已无 `stroke`；`shape_hero_detail_cast_border_focused` 的 stroke = `@color/osd_red_end`；两个 selector 各 2 个 item）；`HomeViewActivity.kt` 括号配平（294/294、955/955、15/15），`HeroPosterAdapter.kt`（20/20、72/72、5/5）；`git diff --check` 无空白错误。
- 待真机验证：海报行任意位置长按 → / ← 都只在行内逐张移动，行尾停住且不再跳 Tab 栏、不再崩；分类 Tab / 目录下探滚到最后两张时自动追加下一页（焦点卡不缩小、Hero 不闪），行尾按 → 也能触发；推荐 Tab 行为与以前一致；小房子未聚焦时完全无边框、聚焦仍是红渐变圆；演员头像焦点圈与首页 Tab 选中态同色。
- 回滚：`git checkout -- app/src/main/java/com/fongmi/android/tv/ui/activity/HomeViewActivity.kt app/src/main/java/com/fongmi/android/tv/ui/hero/HeroPosterAdapter.kt app/src/main/res/drawable/shape_home_logo_normal.xml app/src/main/res/drawable/selector_home_logo.xml app/src/main/res/drawable/shape_hero_detail_cast_border_focused.xml app/src/main/res/drawable/selector_hero_detail_cast_border.xml`。

### 10.15 首页 Tab 焦点残影 + TMDB 匹配弹窗联想词环形焦点（2026-11-12，未编译）

用户报 2 个问题：① 首页分类 Tab 焦点从 A 移到 B 时，A 会遗留一个红框，实心胶囊到 B 后红框才消失，像残影；②「换源弹窗」里联想词按到最右不会回到最左，结果卡片按 ↑ 不会回到联想词。

#### ① 残影根因：焦点态同步改，选中态异步改

`HomeChipAdapter` 旧实现把同一个 Tab 的三态分两处画：

- 焦点回调 `setOnFocusChangeListener` → `applyVisual()` → `setBackground()` / `setTextColor()`：**同步**，本帧生效；
- 选中位 `selectedIndex` setter → `notifyItemChanged(old)` + `notifyItemChanged(value)`：**推迟到下一趟 layout**。

焦点 A→B 的顺序是「A 失焦（此刻 `selectedIndex` 还是 A）→ B 得焦 → `selectedIndex` 改成 B（只发 notify）」。A 在 notify 真正落地前一直按「选中未聚焦」画 `bg_global_selected_capsule_border` 细线红框，而 B 的实心胶囊已经同步画好 —— 屏幕上就是「实心胶囊在 B、A 还挂着红框」。`DefaultItemAnimator` 的换绑淡入还会把这个中间态多留一两帧。

#### ① 改法：Tab 三态交给 View 状态机，选中位同步刷 `isSelected`

与原版 `ref_docs/selector_item_round.xml`（focused / selected / normal 三段）同构：

- 新增 `tabBackground(): Drawable` → `StateListDrawable`：`state_focused` → `@drawable/bg_global_focus_capsule`（红渐变实心胶囊）；`state_selected` → `@drawable/bg_global_selected_capsule_border`（同形状细线红框）；默认 → `idleCapsule()`（`home_tab_idle_surface` + 28dp 圆角，仍是胶囊）。
- 新增 `tabTextColors: ColorStateList`：focused / selected → 纯白，默认 → `home_tab_idle_text`（60% 白）。
- `createTabHolder` 里 `setTextColor(tabTextColors)` + `background = tabBackground()`，**一次装好，此后不再改背景**；`applyVisual` 的 `HomeChipStyle.TAB` 分支改成 `Unit`（Tab 不再按事件顺序重画）。
- `selectedIndex` setter 改成同步：新增 `syncSelection()` 遍历宿主 RecyclerView 已附着的子项，把 `isSelected` 立刻刷成 `position == selectedIndex`，**不再发任何 notify**。适配器新增构造参数 `host: RecyclerView?`（`setupRecyclerViews` 传 `typeRecycler`，顶栏 NAV 不传）。
- `onBindViewHolder` 补 `holder.text.isSelected = position == selectedIndex`，覆盖「新绑定 / 回收复用」的子项。

结果：焦点、选中是两个独立状态位，每帧都按 View 当时的真实状态取值，不可能出现「实心胶囊已到 B、A 还是红框」的中间态；顺带也去掉了 `notifyItemChanged` 这个「Tab 行布局中调用即崩」的来源（`onTabFocused` 顶部的 `isComputingLayout` 推迟仍然保留，因为 `scrollTabToCenter` → `startSmoothScroll` 依旧不能在布局中调用）。

#### ② 用户说的「换源弹窗」= TMDB 手动匹配弹窗

代码里只有 `TmdbMatchViewDialog`（首页卡片长按下键打开）同时具备「顶部联想词行 `@id/suggestion`」与「右侧结果卡片 `@id/candidate`」；`ChangeSourceDialog`（长按确定键）用的是 `dialog_source.xml`，其 `keywordRow` 从未被 bind（恒 `gone`），没有任何联想词。所以本轮只改 `TmdbMatchViewDialog`。

#### ② 联想词环形 + 卡片 ↑ 回联想词

- `setupSuggestions()`：`suggestionView.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS)`。默认 `FOCUS_BEFORE_DESCENDANTS` 会把焦点给 RecyclerView 容器本身，容器没有焦点态，看起来就是「按上来了但没有任何高亮」。
- `SuggestionAdapter.onBindViewHolder`：
  - 焦点变化时记录 `lastSuggestionIndex`（结果卡片 ↑ 要回到「刚才那一枚」，不是永远第一枚）；
  - `setOnKeyListener`：最右一枚按 → → `focusSuggestion(0)`；第 0 枚按 ← → `focusSuggestion(count - 1)`；↑ 就地吃掉（联想词行是弹窗最上一行，不能再往外冒）。
- 新增 `focusSuggestion(int index)`：越界夹回 → `scrollToPosition(target)` → 下一拍取 holder 再 `requestFocus()`（目标还没布局出来时 `findViewHolderForAdapterPosition` 是 null，直接抢会表现为「按了没反应」）。联想词为空 / 行被隐藏时退回 `focusKeyboard()`。
- `CandidateAdapter.onBindViewHolder`：卡片 `setOnKeyListener` 把 ↑ 改成 `focusSuggestion(lastSuggestionIndex)`（原版 `g0()` 把卡片的 ↑ 指向键盘）。← / → / ↓ 保持原样：← 仍是 `nextFocusLeftId = @id/searchBtn`（`onChildViewAttachedToWindow` 里设的）。

#### 验证与回滚

- 静态：`HomeViewActivity.kt` 括号配平（331/331、1154/1154、90/90），`TmdbMatchViewDialog.java`（169/169、670/670、10/10）；剥离注释与字符串后无跨行不配平；`git diff --check` 无空白错误；两个全局 Drawable 与 `home_tab_*` 颜色引用均在。
- 待真机验证：Tab 焦点 A→B 时 A 立刻回到深色胶囊、不再残留红框；失焦的选中 Tab 仍是细线红框；焦点回到同一个 Tab 时实心胶囊立刻恢复。TMDB 弹窗里联想词最右按 → 回到最左、最左按 ← 回到最右、↑ 不冒到弹窗外；结果卡片按 ↑ 回到刚才停留的那一枚联想词，联想词为空时 ↑ 仍回键盘。
- 回滚：`git checkout -- app/src/main/java/com/fongmi/android/tv/ui/activity/HomeViewActivity.kt app/src/main/java/com/fongmi/android/tv/ui/dialog/TmdbMatchViewDialog.java`。

### 10.16 TMDB 艺术字语言过滤 / 图床档位 / 预取重构（2026-11-12，未编译）

> ⚠️ **本节结论已被 [10.17](#1017-tmdb-艺术字第二轮纠错尺寸--外文-logo--预取2026-11-12未编译) 推翻**：根因不是 `include_image_language`，改法也不是「`/images` 优先」。保留原文只为记录当时的判断过程。

用户报 3 件事：① 我们的艺术字比原版小一圈（图 1 vs 图 2）；② 原版能给外文片（不只是英文）出原语种艺术字（图 3 / 图 4），我们只有纯文字剧名（图 5 / 图 6）；③ 原版的 TMDB 预取很流畅，我们发涩。

#### ①② 根因：详情接口固定带 `include_image_language`

`TmdbService.detailUrl()` 一直带 `include_image_language=<config.getLanguage()>,null`。TMDB 这个参数只保留**语言码完全相等**的图和 `iso_639_1 = null` 的图：

- 中文片的 logo 绝大多数标 `zh`，而配置是 `zh-CN` → 被整批过滤，只剩 `iso_639_1 = null` 的**替代版**（同一部剧往往还有一张更大的 `zh` 主 logo）→ 图在，但比原版小一圈；
- 外文片（`hu` / `it` / `ja` …）的 logo 全是原语种 → 一张不剩 → 只剩 32sp 文字标题。

原版 `ref_docs/TmdbApi.java` 是另一条链路：`k(id, media)` 单独请求 `/{movie|tv}/{id}/images`，**只带 api_key、不带任何语言过滤**；`r(json)` 先取 `iso_639_1` 以 `zh` 开头的，没有就取第一张有 `file_path` 的。所以原版既能拿到 `zh` 主 logo，也能拿到外文片的原语种 logo。

#### ①② 改法：新增无过滤 `/images`，作为唯一取 logo 入口

- `TmdbService.images(item, config)`：`/{movie|tv}/{id}/images` 只带 api_key，按 `DETAIL_CACHE_TTL`（7 天）落盘，key = `images|<mediaType>|<tmdbId>`。
- `HeroDetailActivity.kt` 新增 `loadTmdbLogo(service, item, detail, config)`：先 `/images`（`extractTmdbLogo` 的 zh 优先规则不变，与原版 `r()` 逐条对齐），失败或为空再退回详情自带的那份。协程取消照抛，其它异常一律当「没有」。
- 首页 `loadTmdbDetail` 与详情页 `HeroDetailStateManager.loadTmdb` 都改走它；详情页把结果经 `TmdbDetailState.Ready.logo` 传给 `mergeTmdb(logo = ...)`，保持「合并函数在组合期 / 主线程执行，绝不发网络请求」这条约束。
- 图床档位：原版 logo 拼的是 `zu1.C()`（**w500**），我们的 `getImageBase()` 是海报档 `w342`。logo 又宽又扁，按 72dp 高度铺开时宽度常远超 342px，w342 会发虚。新增 `tmdbLogoBase()`：只把比 w500 更小的标准档位提到 w500，`w780` / `w1280` / `original` 与用户自定义的非 TMDB 图床原样返回。
- 布局一行未动：`activity_home_view.xml` 的 `heroLogoImg` 与原版 `activity_home_v2.xml` 完全一致（`wrap_content × 72dp`、`adjustViewBounds`、`maxWidth 720dp`、`fitStart`），尺寸差异只来自「选中哪张 logo 文件 + 图床档位」，不在布局。

#### ③ 预取重构：窗口只做匹配，整批去抖

旧实现是 `Map<Int, Job>`，窗口内每张卡各起一条完整的 `fetchTmdbDetailForPosition`（搜索 + 详情 + 演员 + 译文 + 艺术字，3~4 个请求），4 张并发 → 十几条请求同时在跑，焦点那一条反而排在后面，所以「原版顺、我们涩」。

改法：

- 字段换成单个 `tmdbWindowJob: Job?`，焦点一动就整批 `cancel()` 再重建（对齐原版 `v.getAndSet(Runnable)` 的去抖语义）；4 处清理点（`onDestroy` / 换源 / 目录落地 / 换页）同步改成 `tmdbWindowJob?.cancel()`。
- 新增 `prefetchTmdbMatch(position)`：只发 1 个搜索请求。手动 / 自动缓存命中直接 return；命中后 `persistAutoTmdbMatch` 落盘（冷启动直接有横图），并把横图按 `w1920` 静默预下。**详情不再预取**，留给焦点那一条。
- 并发 `PREFETCH_PARALLEL = 2`（分批串行 + 批内并发），比原版 `o93.b` 的 4 线程更保守：焦点请求永远优先拿到连接，预取不许把它挤慢。
- 整条预取链路包 try/catch（`lifecycleScope.launch` 里未捕获异常会直接崩进程），取消照抛。焦点详情仍由 `tmdbRequestJob` 负责，链路与结果落地口一行未改。

#### 验证与回滚

- 静态：4 个文件括号配平（`TmdbService.java` / `HeroDetailActivity.kt` / `HeroDetailStateManager.kt` / `HomeViewActivity.kt`），`tmdbWindowJobs` 已无残留，`PREFETCH_PARALLEL` / `prefetchTmdbMatch` / `loadTmdbLogo` / `imagesCacheKey` 均已就位，`git diff --check` 无空白错误。
- 待真机验证：中文片艺术字与图 1 大小一致（`zh` 主 logo，w500）；外文片（SÁTÁNTANGÓ / La figlia di Ryan 这类）出原语种艺术字，不再只剩文字；快速左右移动焦点时首屏元数据不排队、Hero 不空转，进详情页艺术字同样生效。
- 回滚：`git checkout -- app/src/main/java/com/fongmi/android/tv/service/TmdbService.java app/src/main/java/com/fongmi/android/tv/ui/activity/HeroDetailActivity.kt app/src/main/java/com/fongmi/android/tv/ui/activity/HeroDetailStateManager.kt app/src/main/java/com/fongmi/android/tv/ui/activity/HomeViewActivity.kt`。

### 10.17 TMDB 艺术字第二轮纠错（尺寸 / 外文 Logo / 预取）（2026-11-12，未编译）

10.16 提交后用户实测「所有问题依旧」。这一轮先用**真实接口数据 + 截图像素测量**把三个问题各自钉死，再改。

#### ① 上一轮为什么一点效果都没有

`/images` 端点（`/{movie|tv}/{id}/images`）的返回是 `{ "id": …, "logos": [ … ] }` —— `logos` 在**根级**，没有 `images` 这一层。而 `extractTmdbLogo()` 只认详情结构 `detail["images"]["logos"]`，于是：

- `extractTmdbLogo(images, …)` 对 `/images` 的返回**永远返回 null**；
- `loadTmdbLogo()` 的 `?:` 于是每次都落到详情那一份 —— 与改动前**逐字节等价**；
- 顺带多发了**一条完全无用的 `/images` 请求**（每条一次），把预取也拖慢了。

结论：上一轮只有「图床提到 w500」这一半生效，但它被 72dp 的高度钳位吃掉了（见 ③），所以观感零变化。

#### ① 接口实测（2026-11-12，`api.tmdb.org/3`，key 走原版默认值）

| 条目 | 详情 `append_to_response=images` | `/images`（无过滤） |
| --- | --- | --- |
| tv/297253 伟大的长征 | 1 张：`zh` 847x359 | 3 张：`nl` 846x359、`zh` 1938x377、`zh` 847x359 |
| movie/13912 SÁTÁNTANGÓ | **0 张** | 5 张（`en`/`it`，含 `it` 740x271） |
| movie/14811 La figlia di Ryan | **0 张** | 6 张（`pt`/`en`/`it`） |

补充实测：详情**去掉** `include_image_language` 后结果**一模一样**（同一批 id 都是 1 / 0 / 0 张）。所以「详情固定带语言过滤」不是外文片拿不到 logo 的原因 —— `append_to_response=images` 本身就只回当前语言那一档。**`/images` 是外文片唯一的来源，不是可选项。**

#### ① 改法：详情优先、`/images` 兜底（与原版 `gd3.C()` 完全一致）

原版链路（`ref_docs/dc3.java` L215-292、L351-378、L548-570；`ref_docs/gd3.java` `C()`）是：

1. `dc3.g(id, media)` 拉详情（带 `append_to_response=images`，**不带** `include_image_language`）→ `r(images)` 先 `zh` 前缀、否则第一张有 `file_path` 的；
2. **详情里没有 logo 才**走 `gd3.C()` → `j11` → `dc3.k(id, media)` 请求 `/images` → 再 `r()`。

我们的 `loadTmdbLogo()` 改成同样的顺序：

- `extractTmdbLogo()` 两种结构都吃：先 `detail["images"]["logos"]`，取不到再认根级 `detail["logos"]`（`?:` 链，不抛异常）；
- `loadTmdbLogo()` 先 `extractTmdbLogo(detail)` 命中就直接返回（中文片一条 `/images` 都不发），未命中才请求 `/images`；
- `TmdbService.images()` 的 KDoc 同步改成「兜底来源」，并记下「去掉 `include_image_language` 结果不变」这条实测。

#### ② 尺寸：不是选错图，是布局把高度钳死了

`heroLogoImg` 原来固定 `layout_height="72dp"`（本机 density 320 → 144px），`adjustViewBounds` 只能按这个高度反推宽度：

- 我们的截图实测：logo 金色像素包围盒 `338x142px` = 144px 高、847:359 反推 340px 宽 —— 与 72dp 完全吻合；
- 原版截图实测（同一张 847x359 立体金）：`510x238px`，且左边缘同为 x≈49px（24dp 外边距 = 48px）→ **两台设备 density 相同**，不是设备差异；
- w500 的 847x359 落下来是 `500x212px`（实测 `logo_geom`：内容 100% 填满、无透明边）→ 原版就是**按图片自身像素尺寸**渲染，我们的 72dp 把它压掉了约 1/3。

改法（`activity_home_view.xml`）：`layout_height` 72dp → **`wrap_content`**，新增 `maxHeight="120dp"`（240px）只兜「接近方形」的 logo（如 497x455 → 262x240），正常横版 logo 不受影响；`maxWidth="720dp"`、`adjustViewBounds`、`fitStart` 保持不变。

配套（`HomeViewActivity.bindLogo`）：Coil 请求显式 `size(Size.ORIGINAL)`。`heroLogoImg` 现在是 wrap_content，尺寸由 drawable 决定，若让 Coil 按「当前 view 尺寸」解码，从大 logo 换到小 logo 再换回来时会被上一次的尺寸卡住（越换越小）；w500 的 logo 只有几十 KB，原尺寸解码没有代价。

#### ③ 预取：窗口改回「整条详情」，近优先 + 整批可弃

10.16 把窗口降级成「只做匹配」后仍然发涩：焦点落到邻卡时详情 / 图片还要现发，艺术字和横图必然晚一拍。原版之所以顺，是因为窗口内邻居的**详情已经在内存里**（`gd3.q(name)` / `gd3.r(name)` 同步取）。

- `updateTmdbWindow()`：邻居目标按 `|i - center|` 排序（先 i±1 再 i±2），整批共用 `tmdbWindowJob`，焦点一动整批 `cancel()`；
- 单条预取改成 `prefetchTmdbDetail()` = 直接复用 `fetchTmdbDetailForPosition()`（搜索 → 详情 → 演员 / 译文 → 艺术字 → 横图预下），整链 try/catch，取消照抛；
- 并发仍 `PREFETCH_PARALLEL = 2`（原版 `o93.b` 是 4 线程），中心那张始终由 `tmdbRequestJob` 单独负责，预取不许把它挤慢；
- 预取结果走同一个落地口 `onTmdbDetailReady()`：非焦点条目只写缓存、不重绘，提前拉邻居**不会**让画面闪出别人的艺术字。

#### 验证与回滚

- 静态：`HeroDetailActivity.kt` / `HomeViewActivity.kt` / `HeroDetailStateManager.kt` / `TmdbService.java` 括号配平，`extractTmdbLogo` 同时出现 `images` 与根级 `logos` 两条路径，`loadTmdbLogo` 为「先详情后 `/images`」，`activity_home_view.xml` 的 `heroLogoImg` 已是 `wrap_content + maxHeight 120dp`，`git diff --check` 无空白错误。
- 待真机验证：① 伟大的长征这类中文片艺术字尺寸接近原版（约 500x212px，而不是 338x142px）；② SÁTÁNTANGÓ / La figlia di Ryan 这类外文片出原语种艺术字；③ 左右移动焦点时 Hero 不空转、不闪回文字标题，进详情页艺术字同样生效。
- 回滚：`git checkout -- app/src/main/java/com/fongmi/android/tv/ui/activity/HeroDetailActivity.kt app/src/main/java/com/fongmi/android/tv/ui/activity/HomeViewActivity.kt app/src/main/java/com/fongmi/android/tv/ui/activity/HeroDetailStateManager.kt app/src/main/java/com/fongmi/android/tv/service/TmdbService.java app/src/main/res/layout/activity_home_view.xml`。

### 10.18 TMDB 预取回归：焦点优先 + 可取消去重预取 + DoH 对齐原版（2026-11-13，未编译）

10.17 提交（`0933705ee`）后用户确认「剧名大小、外文剧名修好了」，但紧接着报出**新回归**：

> 预加载反而更慢了，焦点所在的卡片经常拉取不到大海报，焦点在有大海报的卡片停留了 20 秒再移动到下一张卡片依旧没拉取到大海报，这种情况会频繁出现。

#### ① 回归来源就是 10.17 的「窗口整链预取」

10.17 把窗口预取从「只做匹配」升级成「窗口内 4 个邻居的**整条详情链**」（搜索 + 详情 + 演员 + 译文简介 + 艺术字 + **w1920 横图**），并且：

- 每按一次方向键，整批 `cancel()` 后**立刻**重建下一批 —— 但 `HeroImageView.prefetch()` 当时**故意不保存 `Disposable`**，注释写着「焦点走了也让预取写完」；
- 于是每移动一次焦点就留下若干张 **528KB** 的 w1920 下载，它们和焦点那张**共用同一个 `OkHttpClient` 的 `maxRequestsPerHost = 5`**；
- 更要命的是**焦点那张自己也在预取自己**：`fetchTmdbDetailForPosition()` 一落地就 `prefetchBackdropImage(highResBackdrop)`，紧接着 `onTmdbDetailReady → applyHero → heroBg.loadImage()` 又对**同一条 URL** 发一次显示请求 —— 同一条 URL 并发下两次 528KB；
- 连按几下 = 4 条邻居链 × 528KB + 焦点图 ×2，焦点图只能排在最后 → 停留 20 秒也轮不到，频繁复现。

#### ② 实测数据（2026-11-12，PowerShell 直连 TMDB 图床）

| URL | 大小 | 耗时 |
| --- | --- | --- |
| `image.tmdb.org/t/p/w1920/…jpg` | 528 121 B | 1629 ms（含建连） |
| `image.tmdb.org/t/p/w1280/…jpg` | 174 424 B | 177 ms |
| `image.tmdb.org/t/p/w780/…jpg` | 85 766 B | 180 ms |
| `image.tmdb.org/t/p/original/…jpg` | 1 542 984 B | 180 ms |
| `images.tmdb.org/t/p/w1920/…jpg` | 528 121 B | 749 ms |
| `image.tmdb.org/t/p/w1920/…png`（logo 用的那张） | 585 761 B | 1219 ms |

结论：**问题不是档位选大**（原版 backdrop 也是 `w1920`，`zu1.C()` 的 `w500` 直接 `replace("/w500","/w1920")`），而是**并发与排队**：一张 528KB，5 条并发就是 2.6MB，任何一条链路慢下来都会把焦点图压到队尾。所以这一轮**不动 `TMDB_BACKDROP_WIDTH`**，只修排队。

#### ③ 原版为什么流畅（逆向核对）

- `PosterCache`(`je2`)：**自有文件缓存 + 自有下载队列**，`b` 按 `url@tag` 去重、`d` 按页下标登记并**取消滑出窗口的下载**、`s()` 对下载过慢的直接 `Call.cancel()` 重开；
- `gd3.g(list)`：窗口预取走 `o93.b`（**它自己的** 4 线程池），与焦点链路不共用连接池；
- `gd3.h(vod)`：`v.getAndSet(runnable)` **去抖**（新任务顶掉旧任务）+ `u` / `q` 代次号丢弃过期任务 + `a` 按名字去重；
- `TmdbDns`(`x52`)：`lookup` 第一步**先查配置 hosts 映射** → 用户自选 DoH 优先 → 纯 IP 短路 → 非 TMDB 交系统 → 否则系统 + 阿里 + 腾讯三路竞速，`CountDownLatch.await(2s)` 取第一个非空。

#### ④ 改法（对齐原版语义，保留我们的 Coil 单例与 3GB 磁盘缓存）

1. **焦点绝不预取自己**：`fetchTmdbDetailForPosition(position, allowImagePrefetch)` 新增开关，焦点链固定传 `false`（消灭同 URL 双下载）；
2. **预取可取消**：`HeroImageView.prefetch()` 改为返回 `Disposable`（并接收 `ImageRequest.Listener`），`HomeViewActivity` 用 `prefetchInFlight: Map<URL, Disposable>` 记账；`cancelBackdropPrefetch()` 一次性 `dispose()` 全部在途预取（对齐 `je2.d`，我们更狠：焦点一动全取消）；
3. **按 URL 去重**：`prefetchDone`（FIFO 上限 32，对齐 `je2.b`）+ 在途表双重去重；入队前先占位，防止「内存缓存命中 → 回调早于 `enqueue` 返回」把句柄丢掉；
4. **窗口预取挂在大图落地之后**：`bindBackdrop()` 在发焦点大图请求前先 `cancelBackdropPrefetch()` + `tmdbWindowJob?.cancel()`，再把 `scheduleTmdbWindow()` 挂到 `heroBg.loadImage()` 的完成回调上（无横图分支直接排），**焦点大图没上屏就绝不启动邻居的 528KB 下载**；
5. **去抖 + 串行 + 近优先**：`scheduleTmdbWindow()` 先 `delay(PREFETCH_DEBOUNCE_MS = 400ms)`（对齐 `v.getAndSet` 去抖），邻居按 `|i - center|` 排序后**串行**跑（原版有独立 4 线程池，我们和焦点共用同一个 `OkHttpClient`，只能取 1）；`windowTargets()` 在主线程算好下标，IO 线程不再读 `items`；
6. **大图只预取 i±1**：i±2 只备元数据（几十 KB 的 JSON），不提前占带宽；
7. **DoH 对齐原版**：`HeroHttpDns.lookup()` 改为「配置 `hosts` 映射 → 纯 IP 短路 → 非 TMDB 交 `OkHttp.dns()` → 用户自选 DoH 优先 → 系统 + 阿里 + 腾讯 DoH 三路竞速 2s」；为此给 `catvod` 的 `OkDns` 增加只读映射的 `target(hostname)`（**只读表、不发请求**），并保留「用户 DoH 失败/返回空则继续竞速」的兜底（比原版更稳）。

#### 验证与回滚

- 静态（`check_round8.py`）：四个改动文件括号配平；`updateTmdbWindow` / `PREFETCH_PARALLEL` / `chunked(` / `coroutineScope` 全部无残留；`fetchTmdbDetailForPosition(index, allowImagePrefetch = false)` 存在且两处 `prefetchBackdropImage` 都带 `allowImagePrefetch` 守卫；`cancelBackdropPrefetch()` ≥ 6 个调用点（onDestroy / renderHome / renderFolder / exitFolder / requestFocusedTmdbDetail / bindBackdrop）；`heroBg.loadImage(backdrop, position) { scheduleTmdbWindow() }` 存在；`HeroImageView.prefetch` 返回 `Disposable`；`HeroHttpDns` 含 `target(hostname)` / `userDoh` / `IPV4` / `Setting.getDoh()`；`git diff --check` 无空白错误。
- 待真机验证：① 连续快速左右移动焦点 10 次，焦点卡的 w1920 横图应在数秒内出图（不再 20 秒空转）；② 停在一张卡上不动，日志里应只有 1 条该 URL 的图片请求；③ 停稳约 0.4s 后才出现邻居详情请求，移动焦点时这些请求立刻消失；④ 配了自定义 DoH / `hosts` 的环境下 TMDB 图仍能出。
- 回滚：`git checkout -- app/src/main/java/com/fongmi/android/tv/ui/activity/HomeViewActivity.kt app/src/main/java/com/fongmi/android/tv/ui/custom/HeroImageView.java app/src/main/java/com/fongmi/android/tv/net/HeroHttpDns.kt catvod/src/main/java/com/github/catvod/net/OkDns.java`。

### 10.19 播放页轨道面板对齐原版 + 进度条滑块缩小（2026-11-13，已提交 `9b7575f38`）

10.18 提交后（`9bc2da4bb`）用户确认「很好 tmdb 问题解决了」，本轮转入播放页 UI：**轨道弹窗整体照抄原版**（字体大小、焦点/选中的样子），焦点与选中颜色统一改用本项目的渐变红；**播放控制栏进度条圆形滑块缩小**。

#### ① 原版实测（截图 `Screenshot_20261113-102302.png` 像素采样 + `ref_docs/dialog_track.xml`）

| 元素 | 原版值 | 我们改前 |
| --- | --- | --- |
| 「视频轨道 / 音频轨道」标题 | 16sp 白字，paddingTop 24dp / bottom 8dp，左 20dp / 24dp | 14sp，paddingTop 12dp |
| 行文字 | 16sp，行高约 25dp（50px @ density 320） | 14sp，固定 40dp |
| 行文字起点 | 列起点 + 4dp（视频列 x=51px、音频列 x=1019px） | 行内 paddingStart 12dp |
| 焦点行 | 整列宽横向渐变长条：左端 30% 白（`77,77,77`）线性衰减到列尾全透明 | 平铺 `@color/white_30` |
| 选中行（未聚焦） | 无底色，只有红字（实测 `242,103,110`） | `@color/white_20` 底色 + 红渐变字 |
| 内容底边距 | `track_content` paddingBottom 12dp | 24dp |

#### ② 改法（只动 leanback，手机 flavor 与字幕 AI 弹窗完全不受影响）

1. `dialog_track_panel.xml`：双列标题 14sp → **16sp**、paddingTop 12dp → **24dp**（照抄 `track_titles`）；`track_content` paddingBottom 24dp → **12dp**；
2. `adapter_track_panel.xml`：字号 14sp → **16sp**；固定 40dp 行高 → `wrap_content` + 上下 3dp + `includeFontPadding=false`（复现原版约 25dp 紧凑行）；行内 paddingStart 12dp → **4dp**（列内 20dp/24dp 仍由 RecyclerView padding 提供，文字落点与原版一致）；`ellipsize=end` → `marquee`（长轨道名聚焦时可滚动）；
3. `selector_track_panel_item.xml`：焦点态从平铺 `@color/white_30` 换成 **`@drawable/bg_global_focus_solid_red`**（`@color/osd_accent → @color/osd_accent_light`，angle=0，4dp 小圆角），保留原版「整行横向长条」的形状，只把灰渐变换成统一渐变红；选中但未聚焦改为**全透明**（选中只靠红渐变文字表达，与原版一致）；
4. `TrackPanelAdapter.java`：`text.setSelected()` → `text.setActivated()`，避免 `marquee` 在「当前生效但未聚焦」的行上后台一直滚动；选中态红渐变字（`LinearGradient`，`osd_accent → osd_accent_light`）逻辑保持不变；
5. `view_control_seek.xml`：`DefaultTimeBar` 显式声明 `app:scrubber_enabled_size="8dp"`、`app:scrubber_dragged_size="10dp"`（原默认 12dp/16dp，滑块明显缩小，进度条 2dp 不变）。

#### 验证与回滚

- 静态（`check_round9.py`）：5 个 XML 全部可解析；面板标题 16sp ×3（含 header）/paddingTop 24dp ×3；`track_content` paddingBottom 12dp；`adapter_track_panel` 为 16sp + `wrap_content` + 4dp/3dp 内边距 + marquee 且无 40dp/14sp 残留；`selector_track_panel_item` 焦点引用 `bg_global_focus_solid_red` 且无 `white_30`/`white_20`；适配器为 `setActivated` 且无 `setSelected`、`LinearGradient` 保留；`view_control_seek` 含 8dp/10dp scrubber 与 2dp bar；Java 括号配平；`git diff --check` 无空白错误。
- 待真机验证：① 播放页按「轨道」，面板应为左右双列、标题 16sp、行文字 16sp 紧凑排列；② 上下移动焦点，整行出现 `osd_accent → osd_accent_light` 横向渐变红长条、文字纯白；③ 焦点移开后，当前生效轨道只剩红渐变文字、无底色；④ 进度条白色圆形滑块明显小于原版，拖动时略大但仍小于改前。
- 回滚：`git checkout -- app/src/leanback/res/layout/dialog_track_panel.xml app/src/leanback/res/layout/adapter_track_panel.xml app/src/leanback/res/drawable/selector_track_panel_item.xml app/src/leanback/res/color/selector_track_panel_text.xml app/src/leanback/java/com/fongmi/android/tv/ui/adapter/TrackPanelAdapter.java app/src/leanback/res/layout/view_control_seek.xml`。

### 10.20 轨道面板换回逆向遮罩 + 换源弹窗焦点不再回跳小房子（2026-11-13，已提交 `63ee79136`）

10.19 提交 `9b7575f38` 后用户提出两点：**① 轨道弹窗的遮罩换成逆向样式**（我们那块太黑，见截图 `Screenshot_20261113-112754.png`）；**② 换源弹窗出结果后焦点在 Tab 栏第一个站源，按 ↓ 到卡片后有几率回跳左上角小房子**。

#### ① 轨道面板遮罩：原版证据

- 布局：`ref_docs/dialog_track.xml` 根节点 `android:background="@drawable/shape_controller"`、`android:layout_width="match_parent"`，只有**顶部 28dp 圆角**；我们 leanback 已有同名 `shape_controller.xml` = `?attr/colorSurface` 实心 + 28dp 顶圆角（10.19 已核对），但当时根节点用的是自造的 `bg_bottom_panel_scrim`。
- 弹窗层：原版 `rg.V()`（`ref_docs/rg.java` L12-25）只做 `setWindowAnimations(0)` + `clearFlags(2)`，**不碰 dim**；master/fongmi 的 `BaseBottomSheetDialog` 同样不清、不设 dimAmount。
- 像素对照（两张截图都是 1920×1080 @ density 320）：

| 采样点 | 我们（11:27:54） | 原版（10:23:02） |
| --- | --- | --- |
| 面板横向范围 | 居中卡片 x=320..1600px（= 640dp），左右留黑 | 全宽：视频列标题 x=40px（20dp）、音频列 x=1012px（≈ 480dp + 24dp） |
| 面板底色（中轴竖扫） | 顶 `#1C1C1C` → 底 `#030303`（`bg_bottom_panel_scrim` = `#CC222222` 叠加渐变到 `#E6000000`） | 面板区 (960,900)/(960,1000)/(100,1000) 全部 `#000000`（`shape_controller` 在黑色视频上几乎不可见） |
| 视频是否被压暗 | OSD 白字最亮实测 `#ADADAD`（173/255）→ 系统 dim ≈ **0.32** | 无额外压暗 |

结论：「遮罩太黑」不是面板颜色深，而是三件事叠出来的——① Material3 `BottomSheetBehavior` 默认 `maxWidth = material_bottom_sheet_max_width`（640dp）+ `layout_gravity=center_horizontal`，面板变成悬在屏幕中间的窄卡片，两侧全是黑；② 自造 `bg_bottom_panel_scrim` 渐变到底部近黑，面板下半截直接糊死；③ 系统 dim 0.32 又把整屏视频压暗一层。

#### ② 改法（只动 leanback 播放页面板 + 共享基类的一个默认关闭的钩子）

1. `dialog_track_panel.xml` 根背景 `@drawable/bg_bottom_panel_scrim` → **`@drawable/shape_controller`**（原版逐字同值：`?attr/colorSurface` + 28dp 顶部圆角）；
2. `TrackPanelDialog.stableOverlay()` `false` → **`true`**：走 `BaseBottomSheetDialog` 已有的「清 `FLAG_DIM_BEHIND` + `setDimAmount(0f)`」分支，视频不再被压暗（与同播放页的 `TimerDialog` / `DanmakuDialog` 同一套约定）；
3. `BaseBottomSheetDialog` 新增 `protected boolean fullWidth()`（默认 `false`，其它 BottomSheet 弹窗行为一个字节都不变）；`setBehavior()` 里 `fullWidth()` 为真时执行 `behavior.setMaxWidth(Integer.MAX_VALUE)` 解除 640dp 上限（`setMaxWidth` 为 Material 1.14.0 公开 API，已从 AAR 的 `BottomSheetBehavior.class` 反查 `public` 标志确认）；`TrackPanelDialog` 覆写为 `true`，面板变全宽，双列标题回到原版的 20dp / 24dp 起点。

#### ③ 换源弹窗焦点回跳：逆向依据 + 根因

- 原版 `f23.v0(Result)`（`ref_docs/f23.java` L1262-1278）首个结果到达时只做三件事：`siteLayout.setDescendantFocusability(FOCUS_AFTER_DESCENDANTS)`、**`homeBtn.setFocusable(false)` + `setFocusableInTouchMode(false)`**、`siteRecycler.postDelayed(焦点落到第 0 个 Tab, 16ms)`；只有 `I0()`（L601-608）才把 homeBtn 恢复可聚焦。原版结果列表从头到尾**没有** `notifyDataSetChanged()`，追加结果用 `notifyItemInserted`。
- 我们 `render()` 原来**每次**都 `resultAdapter.notifyDataSetChanged()`，而 `searchProgress` 每完成一个源就回调一次（`onSearchProgress → render`）。全量重绑把正在聚焦的卡片整列 detach 再重建，焦点丢失后系统回退到窗口里第一个可聚焦控件 —— 本弹窗就是左上角小房子。这与「在 Tab 上按 ↓ 到卡片后**有几率**回跳」的时序完全吻合：必须恰好撞上一次迟到的结果 / 进度回调，所以是概率性的。
- **不照抄 `homeBtn.setFocusable(false)`**：本工程小房子是「站点多选侧边栏」的唯一入口，结果出来后禁用会让侧边栏彻底进不去（原版没有这个多选能力）。这里改为从根因上消除焦点丢失，小房子保持可聚焦。

#### ④ 改法（`ChangeSourceDialog.kt`）

1. 新增 `renderedResults` 快照，`render()` 改调 `syncResultAdapter()`：只有 `visibleResults` 换了对象才通知适配器；追加（旧列表是新列表前缀）用 `notifyItemRangeInserted`，同数量换源用 `notifyItemRangeChanged`，只有缩表 / 换到条目更少的 Tab 才 `notifyDataSetChanged`。进度回调不再产生任何重绑；
2. 新增 `focusedResultPosition`（结果卡 `setOnFocusChangeListener` 记录，`HeroPosterCard` 自身只覆写 `onFocusChanged`、没有占用该监听器，不冲突）；`syncResultAdapter()` 通知前记 `resultRecycler.hasFocus()`，通知后 `post` 检查，真丢了焦点就 `focusResult(focusedResultPosition)` 原位还焦；
3. `focusFirstResult()` 抽出通用入口 `focusResult(position, attempt)`：任意下标落焦 + 布局未完成时重试一次（`FOCUS_RESULT_RETRY_COUNT = 1`）；`focusFirstResult()` = `focusResult(0)`，Tab 栏 ↓、下探回包后的落焦行为不变。

#### 验证与回滚

- 静态（`check_round10.py`）：`dialog_track_panel` 根背景 = `@drawable/shape_controller` 且不再出现 `bg_bottom_panel_scrim`、10.19 的 16sp / 24dp 标题未被破坏；`shape_controller` 仍是 `?attr/colorSurface` + 28dp；`TrackPanelDialog` 三个开关 `transparent()=true` / `stableOverlay()=true` / `fullWidth()=true`；`BaseBottomSheetDialog` 的 `fullWidth()` 默认 `false` 且接入 `setMaxWidth(Integer.MAX_VALUE)`；`ChangeSourceDialog` 的 `render()` 首行是 `syncResultAdapter()` 且函数体内无 `notifyDataSetChanged`，`syncResultAdapter` / `focusResult` / `FOCUS_RESULT_RETRY_COUNT` / `focusedResultPosition` / 丢焦还原行全部存在；三个改动文件花括号配平；`git diff --check` 无空白错误。
- 待真机验证：① 播放页按「轨道」，面板应**全宽**贴底、顶部 28dp 圆角、`?attr/colorSurface` 底色，视频**不再被压暗**，双列标题回到屏幕左缘 20dp / 中间 24dp 起点；② 轨道上下移动焦点、点选切换音视频轨一切照旧；③ 换源弹窗出结果后在第一个 Tab 上按 ↓，卡片拿到焦点；④ 卡片有焦点时继续有新结果/进度回调进来，焦点应停在原卡片不回跳小房子；⑤ 从卡片按 ↑ 仍回当前 Tab、按确定仍直达选集、下探 folder 与「⬅️ 返回」不受影响。
- 回滚：`git checkout -- app/src/leanback/res/layout/dialog_track_panel.xml app/src/leanback/java/com/fongmi/android/tv/ui/dialog/TrackPanelDialog.java app/src/main/java/com/fongmi/android/tv/ui/dialog/BaseBottomSheetDialog.java app/src/main/java/com/fongmi/android/tv/ui/hero/ChangeSourceDialog.kt`。

### 10.21 轨道面板换成原版「很透明的渐变黑」+ 播放控制栏焦点首尾循环（2026-11-13，已提交 `05957ccc9`）

10.20 提交 `63ee79136` 后用户反馈两点：**① 轨道弹窗呼出后是一整块不透明黑色，很难看；原版是很透明的渐变黑**（用户截不到视频画面，所以看不到原版遮罩的实际颜色）；**② 播放控制栏焦点要能循环：最左再按左回到最右，最右按右回到最左**。

#### ① 轨道面板遮罩：逆向证据链

- `ref_docs/dialog_track.xml` 根节点背景是 `@drawable/shape_controller`（逆向包里没有单独导出这个 shape 文件，只有引用），10.20 因此误以为它等于我们 leanback 里同名的 `?attr/colorSurface` 实心 + 28dp 圆角。
- 真正的原版定义在本仓库历史里可查：`git show 21c05506b:app/src/leanback/res/drawable/shape_controller.xml`（WebHTV 5.4.9 基线，Fongmi 同源资源）就是**渐变黑**：

  ```xml
  <gradient android:angle="270"
      android:endColor="@color/black_50"
      android:startColor="@color/black_20" />
  <corners android:topLeftRadius="8dp" android:topRightRadius="8dp" />
  ```

  `black_20 = #33000000`（20% 黑）、`black_50 = #80000000`（50% 黑），即「顶部 20% 黑 → 底部 50% 黑」的竖向渐变 + 8dp 顶部圆角。这与用户描述的「很透明的渐变黑」完全一致，也和主 res 里现成的 `shape_controller_scrim.xml`（播放覆盖层 / TMDB 详情底部面板在用的同名渐变）逐字相同。
- 结论：10.20 把根背景指到 `shape_controller` 的方向是对的（与逆向布局同名），错的是这个同名资源的内容被改成了 Material3 不透明深色 `?attr/colorSurface`（`#1C1B1F`）。恢复原版渐变即可，不需要新增 drawable。

#### ② 改法

1. `app/src/leanback/res/drawable/shape_controller.xml`：`?attr/colorSurface` 实心 + 28dp → **原版渐变 `black_20 → black_50`（angle=270）+ 8dp 顶部圆角**，并在文件头注释里写明来源（`ref_docs/dialog_track.xml` + `21c05506b` 同名资源）与「不要改回 colorSurface」的原因；
2. `app/src/leanback/res/layout/dialog_track_panel.xml`：只更新头部注释（根背景仍是 `@drawable/shape_controller`，值变成透明渐变黑）；布局结构、16sp / 24dp / 双列、焦点渐变红全部不动；
3. `PlayerControlFocusHelper`：新增 `handleHorizontalWrap(ViewGroup row, KeyEvent event)` —— 仅当方向键是左右、焦点在本行内、且 `focusSearch` 在该方向**没有行内邻居**（返回空或行外控件）时，才把焦点循环到行首/行尾（左→`lastFocusable`、右→`firstFocusable`），并新增与 `firstFocusable` 对称的 `lastFocusable`；行内正常移动仍返回 `false` 交给系统 `focusSearch`，不改变原有顺序；
4. `VideoActivity.dispatchKeyEvent()`：在 `dispatchOsdDownKey` 之后、`PlayerControlFocusHelper.handleKey` 之前调用 `handleHorizontalWrap(mBinding.osdControl.osdActions, event)`，只作用于播放控制栏按钮行（`osdActions` 的 14 个按钮）。焦点落到行尾时 `HorizontalScrollView` 会按标准焦点滚动逻辑把目标按钮滚进可视区。

#### 验证与回滚

- 静态（`check_round11.py`）：`shape_controller.xml` 含 `angle="270"` / `black_20` / `black_50` / 8dp 顶圆角且无 `colorSurface`、无 28dp；`dialog_track_panel.xml` 根背景仍为 `@drawable/shape_controller` 且 10.19 的 16sp / 24dp 标题未被破坏；`PlayerControlFocusHelper` 含 `handleHorizontalWrap` + `lastFocusable` 且 `handleKey` 原逻辑未变；`VideoActivity` 中 `handleHorizontalWrap(mBinding.osdControl.osdActions, event)` 位于 `dispatchOsdDownKey` 与 `handleKey` 之间；Java 花括号配平；`git diff --check` 无空白错误。
- 待真机验证：① 播放页按「轨道」，面板应贴底全宽、**能透出视频画面**，顶部 20% 黑、底部 50% 黑、8dp 圆角；② 面板内上下移动焦点、切换音视频轨照旧；③ 控制栏焦点在最左「上一集」再按左 → 直接跳到最右「片尾」并自动横向滚动到该按钮；④ 最右再按右 → 回到最左「上一集」；⑤ 中间按钮左右移动顺序不变，行内不会误循环；⑥ 片头/片尾上按 ↑↓ 调时间、按 ↓ 收起控制栏等原有行为不受影响。
- 回滚：`git checkout -- app/src/leanback/res/drawable/shape_controller.xml app/src/leanback/res/layout/dialog_track_panel.xml app/src/main/java/com/fongmi/android/tv/ui/helper/PlayerControlFocusHelper.java app/src/leanback/java/com/fongmi/android/tv/ui/activity/VideoActivity.java`。

### 10.22 播放控制栏去掉红框 + Compose 残留终极清理（2026-11-13，未提交）

10.21 提交 `05957ccc9` 后用户提出：**① 直播播放控制栏上的红框要去掉，这个控制栏应该是原版 WebHTV 的样式**；**② 代码库终极瘦身：删除被纯 View 替代的旧 Compose 详情页 / 搜索页 / 换源弹窗 / 选集页 / 侧边栏代码**（只删文件、不动依赖、不编译）。

#### ① 播放控制栏红框：逆向证据

截图里的三处红框分别是：`配置`（焦点态）、`跨类` / `换源`（`setSelected` 选中态，见 `LiveActivity` L264-266）。它们来自我们 28b479a18 / 44a488d34 两轮把全局 chip 皮肤改成红色：

- `shape_chip_focused.xml`：1.5dp `@color/osd_red_end`（#E53935）红框；
- `shape_chip_activated.xml`：1dp `@color/osd_focus`（= `osd_red_end`）红框；
- `selector_chip.xml` 被 `@style/Control` 引用 → 直播 / 点播 / 投屏 / TMDB 详情控制栏全部中招。

原版 WebHTV 同名资源（`git show 5c05ce4e2:app/src/leanback/res/drawable/...`）：

- `shape_chip_focused.xml`：1.5dp `@color/white` **白框**；
- `shape_chip_activated.xml`：**透明**（只有 8dp / 12dp 内边距，无边框）。

改法：不动全局 `selector_chip`（解析线路列表等其它界面仍保留红色焦点），新增控制栏专用 `selector_control_chip.xml`（焦点 → `shape_control_chip_focused.xml` 1.5dp 白框；其余 → `shape_chip_normal` 透明），`@style/Control` 背景改为 `@drawable/selector_control_chip`。直播 / 点播 / 投屏 / TMDB 详情控制栏恢复「焦点白框、选中无框」；跨类 / 换源文字颜色仍由 `@color/text` 的 selected 状态决定，本轮未改。

#### ② Compose 残留排查结论（只读，全局搜 `androidx.compose` / `setContent` / `ComposeView` / 类名）

| 目标 | 结论 |
| --- | --- |
| 搜索页 | `HeroSearchActivity.kt` 已是纯 View（RecyclerView + `SourceSidebar` + XML），没有旧 Compose 实现或旧文件 |
| 换源弹窗 | `ChangeSourceDialog.kt` 纯 View（10.20 刚改过），无 Compose |
| 选集页 | 不存在 `EpisodeScreen.kt`；`EpisodeViewDialog.kt` 纯 View |
| 侧边栏 | `SourceSidebar.kt` 纯 View（Dialog + `dialog_source_sidebar.xml`） |
| 详情页 | `HeroDetailActivity.kt` 纯 View；旧 Compose 详情页已在此前提交删除（`da07193c0` 等） |

清理前仍带 Compose 引用的只有 4 个文件：

- `HeroCommonUi.kt`（3 个 `@Composable` + 2 个主题常量）→ 全局无代码引用，删除；
- `HeroShared.kt` 的 `rememberHeroImageModel`（`@Composable`）→ 全局无引用，删除；
- `HeroDetailActivity.kt` 的 7 条 Compose import + 一批 Compose 类型死常量 / 死函数 → 删除；
- `HeroDetailStateManager.kt`（`mutableStateOf` / `withFrameNanos` / `FocusRequester`）→ **保留**：纯 View 详情页仍在用（`state.xxx` 20+ 处），它不是页面 / UI，删除需要重写状态订阅，超出本轮「只删废弃页面文件」的范围；Compose 依赖本轮也未动。

#### ③ 删除清单（引用证据）

- `app/src/main/java/com/fongmi/android/tv/ui/hero/HeroCommonUi.kt`（整文件 192 行）：`HeroDialogShell` / `HeroSectionTitle` / `HeroHomeLogoButton` / `HeroAccentColor` / `HeroFocusBrush` 全局只出现在注释里，无任何代码引用 → `git rm`。
- `app/src/main/java/com/fongmi/android/tv/ui/hero/HeroShared.kt`：删 `rememberHeroImageModel` + `Composable` / `remember` / `LocalContext` / `ImageRequest` / `crossfade` import + 只服务它的 `HERO_IMAGE_CROSSFADE_MS`；保留 `HeroTmdbData` / `HeroTmdbPrefetchCache` / `HeroLogoCache` / `clearHeroCaches` / `heroTmdbKey` / `tmdbLogoKey` / `upgradeTmdbImage`（仍被首页 / 详情 / 历史 / 收藏 / 播放页 / `FileUtil` 使用）。
- `app/src/main/java/com/fongmi/android/tv/ui/activity/HeroDetailActivity.kt`：删 7 条 Compose import、`focusAction` / `focusCast(List<FocusRequester>, Int)`、`HeroDetailAction` + `DETAIL_ACTIONS`、`HERO_PADDING` 与文末 107 行 Compose 常量块（`CONTENT_BOTTOM_PADDING` / `LOGO_*` / `DETAIL_*` / `CAST_*` / `ACTOR_*` / `POSTER_*` / `DETAIL_ACTION_*`）；`requestDetailFocus` **迁到** `HeroDetailStateManager.kt`（见 ④）；保留 `HeroCast`（`HeroDetail` 字段 + `DEMO_DETAIL` 仍用）、`DEMO_DETAIL`、`TAG_DETAIL`、`DETAIL_ACTION_PLAY/CHANGE_SOURCE/KEEP`、`MAX_PLAY_DRILL` / `PLAY_FALLBACK_WAIT_MS`（被 `HeroDetailStateManager` 使用）。

未删除：`HeroDetailStateManager.kt`（见上）；`app/build.gradle` 的 compose 插件 / BOM / material3 / coil-compose 依赖按红线保留。

Manifest：没有任何 Compose Activity 节点（`HeroHomeActivity` / `HeroHistoryActivity` / `HeroDetailViewPreviewActivity` 早已删除），本轮**未改** `AndroidManifest.xml`。

#### 验证与回滚

- 静态（`check_round12.py`）：`HeroCommonUi.kt` 不存在；`androidx.compose` 只剩 `HeroDetailStateManager.kt`；`HeroShared` 无 Compose 引用且保留核心 API；`HeroDetail` 无 Compose import / 死符号且保留 `HeroCast` / `DEMO_DETAIL` / `TAG_DETAIL` / `DETAIL_ACTION_*` / `MAX_PLAY_DRILL` / `PLAY_FALLBACK_WAIT_MS`；控制栏 `selector_control_chip` 焦点 = 1.5dp 白框、常态 / 选中 = `shape_chip_normal`，`@style/Control` 不再引用 `selector_chip`，全局红色 chip 未被改；3 个 XML 可解析；两个 Kotlin 文件花括号配平；`git diff --check` 无空白错误。**未编译**（按用户要求只出 diff）。
- 待真机验证：① 直播控制栏焦点移到「配置」应为白色细框，不再红框；②「跨类 / 换源」选中时无边框，只保留文字颜色；③ 点播 / 投屏 / TMDB 详情控制栏同样变白框；④ 详情页 / 搜索页 / 换源 / 选集 / 侧边栏功能与视觉不变；⑤ 解析线路列表等其它界面的红色焦点保持不变。
- 回滚：`git checkout -- app/src/leanback/res/values/styles.xml app/src/main/java/com/fongmi/android/tv/ui/activity/HeroDetailActivity.kt app/src/main/java/com/fongmi/android/tv/ui/hero/HeroShared.kt` + `git checkout HEAD -- app/src/main/java/com/fongmi/android/tv/ui/hero/HeroCommonUi.kt`，并删除 `shape_control_chip_focused.xml` / `selector_control_chip.xml`。

#### ④ 打包失败修复：`requestDetailFocus` 仍有调用方（2026-11-13）

CI 在 `:app:compileLeanbackArmeabi_v7aDebugKotlin` 报三处：

```
e: HeroDetailStateManager.kt:688:21 Unresolved reference 'requestDetailFocus'.
e: HeroDetailStateManager.kt:697:21 Unresolved reference 'requestDetailFocus'.
e: HeroDetailStateManager.kt:703:21 Unresolved reference 'requestDetailFocus'.
```

根因：③ 那次「全局无引用」的搜索实际只跑了 `HeroDetailActivity.kt` 单文件，漏掉同包的 `HeroDetailStateManager.restoreFocus()`（三处调用：`castRequesters` / `actionRequesters` / `DETAIL_ACTION_PLAY`）。

改法：`requestDetailFocus` 从 `HeroDetailActivity.kt` **原样迁到** `HeroDetailStateManager.kt`，作为 `private` 成员紧贴 `restoreFocus`，函数体一字未改（`FocusRequester` import 该文件本来就有）。详情页保持零 Compose，状态机自洽。

复检方式换成「先枚举全部被删符号 → 再全局扫所有 `.kt` / `.java`，跳过注释行」：`check_round13.py` 覆盖 25 个被删符号（含 `HeroDialogShell` / `rememberHeroImageModel` / `HeroDetailAction` / `DETAIL_ACTIONS` / `POSTER_SHAPE` / `DETAIL_*` 常量等），除 `requestDetailFocus`（现定义在 StateManager）外**零代码引用**；同时确认 `HeroDetailStateManager` 有 1 处定义 + 3 处调用、`HeroDetailActivity` 无 Compose import 且仍保留 `DETAIL_ACTION_PLAY`。

### 10.23 播放页「字幕」面板照抄逆向 dialog_subtitle（2026-11-13，未提交）

用户要求：**播放控制栏点「字幕」后弹出的面板，按 `ref_docs` 里的逆向数据照抄一份，替换掉现在 WebHTV 的弹窗。**

#### ① 逆向数据在哪里（`ref_docs` 只有索引，本体在本机反编译包）

`ref_docs` 里没有 `dialog_subtitle.xml` / `y63.java` 本体，只有 3 条索引：

- `ref_docs/UI.md` L119：`y63` = SubtitleDialog → `dialog_subtitle`；L143：基类 `vg` = BottomSheet 系；
- `ref_docs/VideoActivity.java` L6434-6438：`U0()` 里 `new y63()` + `setSubtitleView(...)` + `player` + `show(...)`。

真正的原件在本机反编译包（目录名是乱码，PowerShell 直接写全路径会失败，必须先用 `Get-ChildItem -Recurse -Filter <name>` 解析出 `.FullName`，再 `Get-Content -LiteralPath`）：

- 布局：`C:\Users\Administrator\Desktop\fsdownload\…\decomp\resources\res\layout\dialog_subtitle.xml`
- 类：`C:\Users\Administrator\Desktop\fsdownload\…\decomp\sources\defpackage\y63.java`
- 皮肤：`C:\Users\Administrator\Desktop\fsdownload\…\decomp\resources\res\drawable\selector_control_circle.xml`

#### ② 原版规格（逐字）

| 项 | 原版值 |
| --- | --- |
| 根节点 | `LinearLayoutCompat`，`layout_width=match_parent`、`layout_height=wrap_content`、`gravity=center`、`orientation=horizontal`、`padding=24dp` |
| 根背景 | `@drawable/shape_controller`（10.21 恢复的 `black_20 → black_50` 竖向渐变 + 8dp 顶圆角） |
| 按钮 | `large` / `small` / `up` / `down` / `reset` 五个 `AppCompatImageView`，顺序与 id 一致 |
| 按钮样式 | 背景 `@drawable/selector_control_circle`、`padding=8dp`、`focusable` + `focusableInTouchMode`、`app:tint=@color/white`、**按钮之间无 margin** |
| 焦点皮肤 | `selector_control_circle` 的 focused 档 = 居中 30dp `oval`，`@color/red → @color/red_500`，`angle=315` linear 渐变 |
| 常态 / 选中 | `shape_chip_normal`（透明，8dp/12dp 内边距）/ `shape_chip_activated`（细线红框） |
| 步长 | 位置 ±`0.005f`（`SubtitleView.l`）、字号 ±`0.002f`（`SubtitleView.i`，并同步 `j = i / 0.0533f`） |
| 重置 | `subtitle_text_size` / `subtitle_position` 各写 `0.0f`，再把 `i = 0.0533f`、`j = 1.0f`、`l = 0.0f` 后 `c()` 重绘 |
| 落盘 | 每次点击都 `vx0.S(value, "subtitle_position" / "subtitle_text_size")`，随后 `d0()`（`tc2.c.p()`，把设置推给播放器） |
| 窗口（`y63.F()`） | `setBackgroundDrawable(new ColorDrawable(0))`、decorView padding 清零、`width = 屏幕宽`、`height = -2`、`gravity = 80 (BOTTOM)`、`y = 0`，100ms 后再调 `f0()` |
| 底部表（`y63.f0()`） | `design_bottom_sheet` 背景置 0、`layout_gravity = 87 (BOTTOM|CENTER_HORIZONTAL)`、`L(3)` 展开 + `I(true)` / `K = true` / `L = false` 三个标志位 + `l = Integer.MAX_VALUE`（解除 640dp 上限）；并把根 View 宽度改成屏幕宽、递归清零 padding（`e0`） |
| 去重 | `g0()`：当前已有 `y63` 实例就不再弹第二个 |

#### ③ 改法（3 个文件，只动 leanback 播放页）

1. **新增 `app/src/leanback/res/drawable/selector_control_circle.xml`**：与逆向原件逐字一致（30dp 红渐变实心圆 + `shape_chip_activated` / `shape_chip_normal` 三档），文件头注释写明来源与「8dp/12dp 内边距是原版尺寸，不要改小」。
2. **重写 `app/src/leanback/res/layout/dialog_subtitle.xml`**：根节点五个属性、5 个按钮的全部属性与逆向逐字对齐；旧的 `shape_dialog_glass_toolbar` + `selector_subtitle` + 固定 `wrap_content` 全部去掉。
3. **`SubtitleDialog.java`**：
   - `transparent()` 由 `isFull()` 改成 `Util.isLeanback() || isFull()`（原版 `y63.F()` 一律 `new ColorDrawable(0)`，`vg.V()` 里 `c0()` 为真时再把 `design_bottom_sheet` 刷成透明 —— 我们的 `transparent()` 钩子就是这两件事）；
   - 新增 `fullWidth()`（仅 `Util.isLeanback()`），复用 `BaseBottomSheetDialog` 已有钩子执行 `behavior.setMaxWidth(Integer.MAX_VALUE)`，对齐 `y63.f0()` 的 `l = Integer.MAX_VALUE`；
   - **不覆写 `stableOverlay()`**：原版基类 `vg.V()` 只做 `setSoftInputMode(16)` + 全屏时 `addFlags(1024)`，**没有** `clearFlags(FLAG_DIM_BEHIND)`（对比轨道弹窗基类 `rg.V()` 明确清了），所以字幕面板在原版里同样带 Material 默认压暗。我们的默认分支与 `vg.V()` 逐条相同（`ADJUST_RESIZE` + 全屏 `FLAG_FULLSCREEN`），保持默认即可，不为「好看」额外去掉 dim；
   - `onResume()` 分两路：leanback 走 `MATCH_PARENT × WRAP_CONTENT` + `Gravity.BOTTOM|CENTER_HORIZONTAL` + `y = 0`（对齐 `y63.F()`）；手机端仍是原来的 `ResUtil.dp2px(isFull() ? 232 : 216)`。`BaseBottomSheetDialog` 没有额外 padding，`y63.e0()` 的递归清零不需要复刻。
   - 点击行为**一行未改**：`POSITION_STEP = 0.005f`、`TEXT_STEP = 0.002f`、`DEFAULT_TEXT_SIZE = 0.0533f`、`onReset` 写 0 后 `subtitleView.reset()` —— 与原版 `y63.a0()` 的数值和顺序本来就一致；`isNativeSubtitleStyle()`（MPV 原生字幕，`NATIVE_POSITION_STEP = 0.05f`）是本项目既有适配，保留。

#### ④ 唯一偏离：多一枚 `search` 按钮

原版面板只有 5 个按钮。本项目点播页的「手动搜索字幕」入口挂在 `SubtitleDialog.search(...)` 上（`VideoActivity.onSubtitleClick()`、`TmdbDetailActivity` 两处调用方），删掉会丢功能，因此布局末尾多第 6 个 `search`（`ic_action_search`，同一套 `selector_control_circle` 皮肤），并在 `initView()` 里按 `searchAction == null` 置 `GONE`：

- 直播（`LiveActivity`）/ 投屏（`CastActivity`）不传 `searchAction` → 仍是原版 5 个按钮；
- 点播 → 6 个。

布局注释已写明「删掉这一段即 100% 复刻」；若以后把搜索入口并回轨道面板，可一行删除。

#### 验证与回滚

- 静态（`check_round14.py` + `seldiff.py` + `layoutdiff.py`）：两个新 XML 可解析；根节点 `match_parent` / `shape_controller` / `24dp` / `center` / `horizontal` 全部命中且无 `shape_dialog_glass_toolbar` / `selector_subtitle` 残留；6 个按钮的 `selector_control_circle` / `src` / `tint` / `padding` / `focusable` 逐项通过、无 margin；`selector_control_circle.xml` 剥离注释后与逆向原件**逐行 IDENTICAL**（仅 `/>` 前空格差异）；`layoutdiff.py` 证明根属性完全相同、前 5 个子节点属性逐个相同、只多 `search`；`SubtitleDialog.java` 的 `transparent()` / `fullWidth()` 与 `onResume` 分支命中、**无 `stableOverlay()` 覆写**、手机端 `232 : 216` 保留、`onResume` 只有 1 处、括号配平、binding id 全部在布局中；引用资源（`shape_controller` / `shape_chip_*` / `ic_subtitle_*` / `ic_action_search`）与颜色（`red` / `red_500` / `white`）均存在；`git diff --check` 无空白错误。**未编译**（按用户要求只出 diff）。
- 顺带确认：`selector_subtitle.xml` / `shape_subtitle_focused.xml` / `shape_subtitle_pressed.xml` 在本轮后已无任何引用（死资源），本轮**未删**，留作后续瘦身项。
- 待真机验证：① 直播 / 投屏点「字幕」= 原版 5 个圆钮，点播 = 6 个；② 焦点态是 30dp 红渐变实心圆、未聚焦完全透明，按钮之间无空隙；③ 面板全宽贴底、透明渐变黑（顶部 20% 黑 → 底部 50% 黑 + 8dp 顶圆角），面板外仍是 Material 默认压暗（与原版 `vg.V()` 一致；轨道面板不同，那个基类 `rg.V()` 清了 dim）；④ 大小 / 上下 / 重置四个键的步长、重置值、落盘与重启后保持均与以前一致（MPV 原生字幕仍走 `NATIVE_POSITION_STEP`）。
- 回滚：`git checkout -- app/src/leanback/res/layout/dialog_subtitle.xml app/src/main/java/com/fongmi/android/tv/ui/dialog/SubtitleDialog.java`，并删除 `app/src/leanback/res/drawable/selector_control_circle.xml`。

### 10.24 字幕按钮真正弹出的弹窗：轨道列表换成逆向 dialog_track（2026-11-13，未提交）

10.23 提交 `c4013451a` 后用户实测反馈：**点播放控制栏「字幕」，弹出来的还是 WebHTV 原来的字幕弹窗，逆向面板没接上。**

#### ① 根因：字幕按钮打开的不是 `dialog_subtitle`，而是 `dialog_track`

逆向证据链：

- `ref_docs/VideoActivity.java` L282-288：`t2(VideoActivity, View)` 把按钮的 `android:tag` 当轨道类型，`new ph3()`（TrackDialog）并 `g0(activity)` 注册监听；
- `ref_docs/dialog_track.xml`：根节点 `@drawable/shape_controller`，头部有 `choose` / `subtitle` / `offset` 三个 `selector_control_circle` 圆钮，下面同时有双列 `track_content`（视频/音频）和单列 `recycler`（字幕）；
- `ref_docs/VideoActivity.java` L6432-6439：`U0()`（`oh3` 接口，即 TrackDialog 的 `onSubtitleClick`）才 `new y63()` 弹出 5 圆钮面板。

也就是说原版的层级是：**字幕按钮 → 字幕轨道列表（dialog_track）→ 头部「字幕设置」圆钮 → 5 圆钮面板（dialog_subtitle）**。我们上一轮只替换了二级的 `dialog_subtitle`，而一级的字幕列表在 leanback 用的是 `app/src/leanback/res/layout/dialog_track.xml`，还是老的玻璃卡片 + 14sp 胶囊行 + 系统压暗 + 640dp 窄卡 —— 用户点字幕看到的就是它。

本项目对应代码：`VideoActivity` L4914 `osdText → onTrack(3)`；`TrackDialog` 的头部 `subtitle` 图标 → `listener::onSubtitleClick`（L572）→ `SubtitleDialog`。接线本来就与原版一致，缺的是这个列表的皮肤。

#### ② 改法（只动 leanback；手机 flavor 与 AI/搜索入口全部保留）

1. `app/src/leanback/res/layout/dialog_track.xml`（重写）：
   - 根背景 `@drawable/shape_dialog_glass_panel` → **`@drawable/shape_controller`**（透明渐变黑 + 8dp 顶圆角）；
   - 头部内边距 24/24/24/**8**dp（原版 `header` 同值）；
   - `choose` / `search` / `ai` / `realtimeAi` / `aiRegenerate` / `subtitle` / `offset` 七个图标统一换成 **`@drawable/selector_control_circle` + 8dp padding + `app:tint=@color/white` + marginStart 16dp**（原版圆钮皮肤）；`secondary` 文字开关保留；
   - 列表 `maxHeight` 204dp → **160dp**、`paddingBottom=24dp`（原版单列 `recycler` 同值）。
2. `app/src/leanback/res/layout/adapter_track.xml`（重写）：行样式对齐 10.19 的双列轨道行 —— 16sp、`includeFontPadding=false` + 上下 3dp（约 25dp 紧凑行高）、行内 4dp/12dp、焦点 `@drawable/selector_track_panel_item`（统一渐变红长条）、选中红字、普通白字；去掉 `selector_dialog_glass_chip` 与 14sp。
3. 新增 `app/src/leanback/res/color/selector_track_text.xml`：焦点白 / `state_activated` 红（`@color/osd_focus`）/ 普通白。`TrackAdapter` 用的是 `setActivated`，所以必须写 activated 档。
4. `TrackDialog.java`：`transparent()` / `stableOverlay()` 由 `!Util.isLeanback()` 改成 **恒 `true`**（手机端本来就是 true，行为不变），新增 `fullWidth()` = `Util.isLeanback()`。效果：电视上窗口透明、不压暗视频、解除 640dp 上限，与 `TrackPanelDialog` 完全同款；原版 `rg.V()` 同样清 dim。

#### 验证与回滚

- 静态（`check_round15.py`）：三个 XML 可解析；`dialog_track` 根背景 = `shape_controller` 且无 `shape_dialog_glass_panel`、头部 8dp、七个圆钮全部 `selector_control_circle` + 8dp + 白 tint、`maxHeight=160dp`；`adapter_track` 为 16sp + 紧凑行高 + 渐变红焦点 + 红字选中且无 glass chip / 14sp；`selector_track_text` 三档颜色齐全；`TrackDialog` 的 `transparent()/stableOverlay()` 恒 true、`fullWidth()` 仅 leanback、括号配平、所有 `binding.*` id 都在布局中；引用 drawable / 颜色全部存在；`git diff --check` 无空白错误。**未编译**。
- 待真机验证：① 点「字幕」→ 面板应全宽贴底、透明渐变黑、不压暗视频；② 头部一排圆形图标，焦点是 30dp 红渐变实心圆、未聚焦透明；③ 字幕行 16sp 紧凑排列，焦点整行渐变红、当前生效字幕红字；④ 点头部「字幕设置」圆钮 → 5 圆钮面板（10.23 的 `dialog_subtitle`）；⑤ 直播 / 投屏的「音轨 / 视轨」列表同样变全宽透明样式，选项切换与 AI / 搜索入口不受影响。
- 回滚：`git checkout -- app/src/leanback/res/layout/dialog_track.xml app/src/leanback/res/layout/adapter_track.xml app/src/main/java/com/fongmi/android/tv/ui/dialog/TrackDialog.java`，并删除 `app/src/leanback/res/color/selector_track_text.xml`。

### 10.25 字幕列表头部的「偏移设置」弹窗照抄逆向 dialog_offset（2026-11-13，未提交）

10.24 提交 `c28754be8` 后用户提出：**点开字幕设置里，有个「偏移设置」也会弹窗，按逆向数据照抄一个，并接入到咱们的字幕弹窗。**

#### ① 逆向本体这次找到了（之前只找到布局，没找到类）

上一轮只定位到 `mm3.a()` 是 `dialog_offset` 的 ViewBinding 工厂（返回合并后的 `j5`），但**调用方缺失**，所以无法确认弹窗外壳。这一轮补齐：

| 逆向件 | 内容 |
| --- | --- |
| `ref_docs/dialog_offset.xml` | 偏移面板布局（根节点**无 background**） |
| `m52.java` | **真正的 OffsetDialog**：`m52 extends ci`，`Y()` = `mm3.a()`（inflate `dialog_offset`），`Z()` = `min(320dp, 屏宽/2)`，`b0()` = 绑定逻辑 |
| `ci.java` | BaseSheetDialog 系：`V()` 造 `jx2`（Material `SideSheetDialog`）+ `f().g = false`（不可拖拽）；`F()` 把 `R.id.m3_side_sheet` 宽度设为 `Z()`；**没有清窗口背景** |
| `ph3.java` L125-152 / L175-202 / L225-251 | TrackDialog 头部三枚圆钮：`x0.l`（offset）→ `new m52(player, type)`；`x0.j`（choose）→ 选文件；`x0.n`（subtitle）→ `onSubtitleClick`。打开偏移面板后 `ph3Var.Y()` 关掉轨道列表 |
| `m52.b0()` | 音频 `o0(audioSlider, audioValue, T0(38) ? Q0() : 0, → W0(j))`；字幕 `o0(textSlider, textValue, T0(36) ? S0() : 0, → M0(j))`；`reset.setOnClickListener(new g20(z8Var, 4))`；`(type==1 ? audioSection : textSection).setVisibility(VISIBLE)`；`(type==1 ? audioSlider : textSlider).requestFocus()` |
| `g20.java` case 4（Reset） | `slider.setValue(0f)` + `label.setText(z8.O(0f))` + 音轨/字幕各自归零 |
| `z8.java` `O()` / `s0()` / `o0()` | 标签 `String.format(Locale, "%+.1fs", ms/1000f)`；初值**先夹取再按 stepSize 对齐**；拖动回调 `longConsumer.accept(Math.round(snapped))`（不再额外取整） |

结论：**外壳我们本来就一致** —— `OffsetDialog.SideSheet` 的 `getWidth()` 也是 `min(dp2px(320), 屏宽/2)`，`BaseSideSheetDialog` 同样走 `m3_side_sheet` + `setDraggable(false)` 且不清背景。原版 `ci` 不清背景 + 布局根节点无 background ⇒ 面板底色由 **Material3 SideSheet 自己的 surface** 提供。真正不对的是布局里多套了一层 WebHTV 的紫色玻璃卡片，以及字幕滑块的量程/步进。

#### ② 改法（2 个文件）

1. `app/src/leanback/res/layout/dialog_offset.xml`（按 `ref_docs/dialog_offset.xml` 逐字重写）：
   - **删掉根节点 `android:background="@drawable/shape_dialog_glass_panel"`**（原版没有，否则侧边栏里会多一块紫色 22dp 圆角卡片 + 白描边）；
   - 头部保持 `paddingTop 16dp / bottom 8dp / start&end 24dp`、标题 `?attr/textAppearanceHeadlineSmall` + 纯白、Reset 为 `Widget.Material3.Button.TonalButton` + 纯白字；
   - 两个 section 默认 `gone`（由代码按 type 打开），标签行 `24dp / 4dp`、Slider `layout_marginHorizontal 12dp`、`tickVisible=false`；
   - 音频 Slider 不变（step 100、-10000..10000）；**字幕 Slider 改成 step 1000、-300000..300000**（原版 ±300s、1s 一档）；
   - 尾部 24dp 占位 View、去掉 `tools:` 预览属性（原版没有）；
   - 保留原版重复写的 `paddingLeft` + `paddingHorizontal` 等冗余声明（逐字照抄，等价属性，不影响运行）。
2. `app/src/main/java/com/fongmi/android/tv/ui/dialog/OffsetPanel.java`：
   - 新增 `snap(Slider, float)`，逐行对齐逆向 `z8.s0()`：夹到 `[valueFrom, valueTo]` → `Math.round((v - from) / step) * step + from` → 再夹一次。**这是必须的**：字幕滑块 step 从 100 变 1000 后，历史偏移值（如 `-1234ms`）直接 `setValue` 会抛 `Value must be equal to valueFrom plus a multiple of stepSize`；
   - 拖动回调改成 `setter.accept(Math.round(snapped))`，去掉原来的 `OFFSET_STEP_MS = 100` 二次取整（对齐逆向 `longConsumer.accept(Math.round(f))`）；
   - 删除常量 `OFFSET_STEP_MS`；标签格式 `%+.1fs`、Reset 行为（Slider 归零 + 标签 `+0.0s` + 偏移归零）、section 显隐、初始焦点全部保持与 `m52.b0()` 一致。

手机端 `app/src/mobile/res/layout/dialog_offset.xml` **未动**（仍是 `shape_player_child_sheet_panel` + ±10s/0.1s），`OffsetPanel` 的 `snap()` 对 step=100 的旧量程是恒等变换，手机行为不变。

#### ③ 接入点：本来就已经接好

`TrackDialog.onOffset()`（L208-210）= `OffsetDialog.create().player(player).type(type).show(requireActivity())`，与逆向 `ph3.x0.l → new m52(player, type)` 完全对应；入口就是字幕列表头部那枚 `ic_action_offset` 圆钮（`dialog_track.xml` 的 `@+id/offset`）。5 圆钮面板（`dialog_subtitle`）原版**没有**偏移按钮，本轮也不加，避免偏离原版层级。

#### 验证与回滚

- 静态（`check_round16.py`）：leanback `dialog_offset.xml` 可解析、根节点无 `background`、无 `shape_dialog_glass_panel`、头部 16/8dp、标题 `textAppearanceHeadlineSmall` + 白、Reset 为 TonalButton + `danmaku_reset`、音频 `100 / -10000 / 10000`、字幕 `1000 / -300000 / 300000`、两个 section 默认 `gone`、尾部 24dp 占位、无 `tools:`；`OffsetPanel` 有 `snap(`、无 `OFFSET_STEP_MS`、回调为 `Math.round(snapped)`、括号配平；`OffsetPanel` 用到的 7 个 binding id 在 **leanback 与 mobile 两个布局里都存在**（同一份共享 Java）；`OffsetDialog` 的 `SideSheet.getWidth()` 仍为 `min(dp2px(320), 屏宽/2)`；`git diff --check` 无空白错误。**未编译**（按用户要求只出 diff）。
- 待真机验证：① 字幕列表头部点偏移圆钮 → 右侧滑出面板，底色是 Material3 侧边栏 surface（不再是紫色玻璃卡片 + 白描边）；② 标题「偏移设置」+ 右上「重置」按钮，只有一行「字幕偏移」+ 数值 + 滑块；③ 遥控器左右调滑块，数值按 1s 递增（±300s），拖动时 tooltip 显示 `+1.0s`；④ 点「重置」→ 滑块回中、数值 `+0.0s`、字幕立刻回到 0 偏移；⑤ 音轨列表打开时同一弹窗显示「音频偏移」、0.1s 一档（±10s）；⑥ 关闭侧边栏回到播放页，字幕偏移立即生效；⑦ 手机端偏移弹窗仍是底部面板 + 旧量程。
- 回滚：`git checkout -- app/src/leanback/res/layout/dialog_offset.xml app/src/main/java/com/fongmi/android/tv/ui/dialog/OffsetPanel.java`。

### 10.26 收尾三件事：关掉字幕列表 / 偏移弹窗不压暗 / 圆钮聚焦透明（2026-11-13，未提交）

10.25 提交 `b782c415f` 后用户实测反馈三条：

> 「对齐原版关掉字幕列表，偏移弹窗的遮罩太黑了，原版几乎是透明的页对齐原版吧，字幕弹窗焦点也对齐原版聚焦透明。」

#### ① 关掉字幕列表（逆向 `ph3.java` case 0）

`ph3.java` L133-152（轨道列表头部圆钮的 `OnClickListener`）case 0 = 偏移圆钮：

```java
case 0:
    tc2 tc2Var = ph3Var.y0;
    int i3 = ph3Var.z0;
    r7 r7VarL = ph3Var.L();
    zu0 zu0VarE1 = r7VarL.E1();
    Iterator it = zu0VarE1.c.B().iterator();
    while (it.hasNext()) {
        if (((hu0) it.next()) instanceof m52) {
            ph3Var.Y();      // ← 关掉自己（轨道列表）
            break;
        }
    }
    nm3.h(r7VarL);
    new m52(tc2Var, i3).X(zu0VarE1);   // ← 再弹出偏移侧边栏
```

也就是原版**先判断偏移面板是否已经在栈里，在的话先关掉轨道列表，再弹偏移面板**；用户看到的结果就是「点偏移 → 字幕列表消失，只剩右侧偏移面板」。

我们原来只有 `OffsetDialog.create()...show(...)`，没有 `dismiss()`，于是两层叠着。改法（1 行）：

```java
private void onOffset(View view) {
    OffsetDialog.create().player(player).type(type).show(requireActivity());
    // 逆向 ph3.x0.l：先 new m52(...).X(...) 弹出偏移侧边栏，再 ph3Var.Y() 关掉轨道列表
    dismiss();
}
```

顺序保持「先 show 偏移、再 dismiss 自己」，与逆向一致（Fragment 事务各自入栈，先后不影响结果，但保持一致便于对照）。

#### ② 偏移弹窗遮罩：原版几乎是透明的

先确认原版到底压不压暗：

| 检查点 | 结论 |
| --- | --- |
| `ci.java`（`m52` 的基类）`V(Bundle)` | `new jx2(M())` + `jx2Var.f().g = false`（不可拖拽）；**没有** `clearFlags(FLAG_DIM_BEHIND)`、没有 `setDimAmount` |
| 反编译包全量搜 `setDimAmount` / `FLAG_DIM_BEHIND` / `clearFlags(2)` | 只有 4 处命中，全部与侧边栏无关：`hv3.java:44`（`clearFlags(67108864)` = `FLAG_LAYOUT_NO_LIMITS`）、`la2.java:470`（`clearFlags(128)` = `FLAG_FULLSCREEN`）、`rg.java:17` 与 `t40.java:55`（`clearFlags(2)` = 弹幕/轨道面板基类清 dim） |
| `styles.xml` 的 `ThemeOverlay.Material3.SideSheetDialog` | 继承 Material 的 `Base.V14.ThemeOverlay.Material3.SideSheetDialog`，**只设** `windowIsFloating=false` / `windowBackground=transparent` / 动画 / `sideSheetModalStyle`；没有改 `backgroundDimEnabled` 或 `backgroundDimAmount` |
| `m3_side_sheet_dialog.xml` | 只有 `touch_outside` + `m3_side_sheet` 两个子 View，`touch_outside` 无背景色 ⇒ 遮罩由 **SideSheet 的 scrim 层**（`Widget.Material3.SideSheet.Modal` 的 `sideSheetDialogTheme` scrim）负责，而不是 `FLAG_DIM_BEHIND` |

结论：原版**没有额外压暗**，侧边栏外面那层很淡的 scrim 是 Material 组件自带、随滑出动画淡入的那层；而我们这边除了组件自带 scrim，还额外吃了应用主题的 `android:backgroundDimEnabled=true`（`app/src/main/res/values/styles.xml` L183 / L231）叠加的窗口 dim，所以看起来「黑得明显」。

改法与项目既有约定一致 —— `BaseBottomSheetDialog` 早就有 `stableOverlay()` 钩子（`clearFlags(FLAG_DIM_BEHIND)` + `setDimAmount(0f)`，`TimerDialog` / `TrackPanelDialog` / `DanmakuDialog` / `DanmakuSettingDialog` / `TitleDialog` / `TrackDialog` 都在用），给侧边栏基类补同一个钩子：

1. `BaseSideSheetDialog`：新增 `protected boolean stableOverlay() { return false; }`，并在 `onCreateDialog` 拿到 `Window` 之后按需 `clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)` + `setDimAmount(0f)`；
2. `OffsetDialog.SideSheet`：覆写 `stableOverlay()` 返回 `true`。

默认 `false` ⇒ 其它页面（非播放页）的侧边栏行为不变；手机端 `OffsetDialog.BottomSheet` 本来就走 `BaseBottomSheetDialog.stableOverlay()==true`，两端口径统一。

#### ③ 圆钮聚焦透明

`selector_control_circle.xml` 之前按反编译原件逐字照抄，focused 档是「居中 30dp 红渐变实心圆（`@color/red` → `@color/red_500`，angle=315）」。但用户实测原版聚焦**是透明的**，按实测为准：

- 删掉整个 `android:state_focused="true"` 的 `layer-list` 档；
- 只剩两档：`state_selected="true"` → `@drawable/shape_chip_activated`（细线红框）、默认 → `@drawable/shape_chip_normal`（透明）；
- 聚焦时没有 `state_selected`，自然落到默认档 = 透明。

补充说明：运行时 `dl3.d()` / `dl3.s()` 会把这个 selector 换成用 theme_color 生成的实心圆皮肤（`dl3.q(R.drawable.selector_control_circle)`），按静态分析也是实心圆；但既然实测为透明，本轮以实测为准，不改 `dl3` 那套运行时皮肤逻辑（我们项目里没有对应实现，`selector_control_circle` 是纯 XML）。

**影响面**：`selector_control_circle` 被两个布局共用 —— `dialog_subtitle`（5 圆钮 + 我们加的 search）与 `dialog_track`（头部 7 钮），所以两处的聚焦都变透明。若之后要「轨道面板头部保留红圈、字幕面板透明」，需要把 drawable 拆成两份（`selector_control_circle` + `selector_control_circle_plain`），本轮不做。

#### ④ 改动清单（4 个代码文件 + 2 处注释）

| 文件 | 改动 |
| --- | --- |
| `app/src/main/java/com/fongmi/android/tv/ui/dialog/TrackDialog.java` | `onOffset()` 末尾加 `dismiss()`（含逆向注释） |
| `app/src/main/java/com/fongmi/android/tv/ui/dialog/BaseSideSheetDialog.java` | 新增 `stableOverlay()` 钩子 + `onCreateDialog` 里清 `FLAG_DIM_BEHIND` / `setDimAmount(0f)` |
| `app/src/main/java/com/fongmi/android/tv/ui/dialog/OffsetDialog.java` | `SideSheet` 覆写 `stableOverlay()` 返回 `true` |
| `app/src/leanback/res/drawable/selector_control_circle.xml` | 删 focused 红圆档，只剩 selected / 默认两档 |
| `app/src/leanback/res/layout/dialog_subtitle.xml` | 文件头注释改为「聚焦 / 未聚焦都是透明，选中才是细线红框」 |
| `app/src/leanback/res/layout/dialog_track.xml` | 同上 |

`dialog_offset.xml` / `OffsetPanel.java` / 手机端布局**未动**（10.25 已提交，本轮不碰）。

#### 验证与回滚

- 静态（`check_round17.py`）：`TrackDialog.onOffset` 含 `dismiss()`；`BaseSideSheetDialog` 含 `stableOverlay()` 与 `clearFlags`/`setDimAmount(0f)` 且默认 `return false`；`OffsetDialog.SideSheet` 覆写返回 `true`；`selector_control_circle.xml` 无 `state_focused`、无 `layer-list`、无 `@color/red`，且 `state_selected` / `shape_chip_activated` / `shape_chip_normal` 都在；两个布局注释已改；三个 XML 可解析；`git diff --check` 无空白错误。**未编译**（按用户要求只出 diff）。
- 待真机验证：① 字幕列表头部点偏移圆钮 → 字幕列表立刻消失，只剩右侧偏移面板；② 偏移面板外面视频**不压暗**（与原亮度基本一致，只剩 Material 自带的淡 scrim）；③ 关闭偏移面板回到播放页，字幕偏移生效；④ 5 圆钮面板（large / small / up / down / reset）遥控器聚焦时**没有红圈**，图标纯白、背景透明；⑤ 轨道列表头部 7 钮聚焦同样无红圈；⑥ 非播放页的其它侧边栏（若有）仍按默认压暗；⑦ 手机端偏移弹窗行为不变。
- 回滚：`git checkout -- app/src/main/java/com/fongmi/android/tv/ui/dialog/TrackDialog.java app/src/main/java/com/fongmi/android/tv/ui/dialog/BaseSideSheetDialog.java app/src/main/java/com/fongmi/android/tv/ui/dialog/OffsetDialog.java app/src/leanback/res/drawable/selector_control_circle.xml app/src/leanback/res/layout/dialog_subtitle.xml app/src/leanback/res/layout/dialog_track.xml`。

### 10.27 遮罩真凶：AppCompat 主题链在 show() 时把 dim 加回来了；聚焦回退红圆（2026-11-13，未提交）

10.26 提交 `07ad7df56` 后用户实测反馈两条：

> 「修改偏移遮罩还是太黑了不透明，原版几乎是透明看不到在遮罩。顶部按钮焦点现在都看不到了，返回到上一版吧。」

#### ① 聚焦：10.26 的「透明」是错的，回退

透明聚焦在遥控器上等于**没有焦点指示**（纯白图标 + 无背景，看不出停在哪一枚）。10.26 之所以那样改，是拿「用户实测原版聚焦透明」当依据；实测下来这条不成立，按用户要求回退到上一版（`b782c415f` 里的逆向原件）：

- `selector_control_circle.xml` 恢复 focused 档：居中 30dp 红渐变实心圆（`@color/red` → `@color/red_500`，angle=315）；
- `dialog_subtitle.xml` / `dialog_track.xml` 的头部注释恢复「焦点 = 30dp 红渐变实心圆，未聚焦 = 透明」。

三个文件用 `git checkout b782c415f -- <paths>` 整份还原，不手工拼。

#### ② 遮罩为什么「改了还是黑」：清 dim 的时机错了

10.26 的结论（「我们额外吃了应用主题的 `backgroundDimEnabled=true`」）**不对**，真正的原因在 SideSheet 自己的主题链：

```
ThemeOverlay.Material3.SideSheetDialog            (material)
  → Base.V14.ThemeOverlay.Material3.SideSheetDialog   windowIsFloating=false / windowBackground=transparent
  → Base.ThemeOverlay.Material3.Dialog
  → Base.ThemeOverlay.MaterialComponents.Dialog
  → Base.V14.ThemeOverlay.MaterialComponents.Dialog
  → ThemeOverlay.AppCompat.Dialog                 (appcompat)
  → Base.ThemeOverlay.AppCompat.Dialog
  → Base.V7.ThemeOverlay.AppCompat.Dialog         ★ android:backgroundDimEnabled = true
```

（链路由 `material-1.14.0.aar` 与 `appcompat-1.8.0.aar` 的 `res/values/values.xml` 逐层 `parent=` 解析得到。）

`Dialog.show()` 的顺序是：`dispatchOnCreate` → `onStart` → `mDecor = mWindow.getDecorView()`（这里触发 `PhoneWindow.generateLayout()`）→ `addView` → `sendShowMessage()`。`generateLayout()` 末尾会读主题：

```java
if (a.getBoolean(R.styleable.Window_backgroundDimEnabled, mIsFloating)) {
    if ((params.flags & FLAG_DIM_BEHIND) == 0) params.flags |= FLAG_DIM_BEHIND;
    params.dimAmount = a.getFloat(R.styleable.Window_backgroundDimAmount, 0.5f);
}
```

所以 10.26 在 `onCreateDialog` 里 `clearFlags` 之后，`generateLayout()` 又把 `FLAG_DIM_BEHIND` + `dimAmount`（Material3 dialog 主题给的是 `m3_comp_scrim_container_opacity` ≈ 0.32）加了回来 —— 表现就是「改完还是黑」。

改法：**在 `show()` 之后再清一次**。

1. `BaseSideSheetDialog` 抽出 `private void clearDim(Window window)`：取 `getAttributes()`，`flags &= ~FLAG_DIM_BEHIND`、`dimAmount = 0f`，再 `window.setAttributes(params)`（窗口已 attach 时 `Dialog.onWindowAttributesChanged` 会 `updateViewLayout`，立即生效，不用重建 Dialog）。
2. `onCreateDialog` 里保留一次 `clearDim(window)`（避免首帧闪一下），并注册 `dialog.setOnShowListener(...)` 在 show 之后再来一次 —— `OnShowListener` 由 `sendShowMessage()` 在主线程 post，必定晚于 `generateLayout()` 与 `addView`。
3. 只在 `stableOverlay()==true` 时做，默认 `false` 的侧边栏行为不变。

对照逆向：原版 `ci.V()` 只有 `jx2Var.f().g = false`（不可拖拽）+ 全屏时 `window.addFlags(1024)`（`FLAG_FULLSCREEN`），从不加 dim；`y63.F()`（字幕底部面板）也只是 `setBackgroundDrawable(new ColorDrawable(0))` + 清 decor padding。所以「原版几乎没有遮罩」= 原版压根没开 dim。

#### ③ 改动清单（本轮 4 个文件）

| 文件 | 改动 |
| --- | --- |
| `app/src/leanback/res/drawable/selector_control_circle.xml` | 回退到 `b782c415f`（恢复 focused 30dp 红渐变实心圆） |
| `app/src/leanback/res/layout/dialog_subtitle.xml` | 回退注释 |
| `app/src/leanback/res/layout/dialog_track.xml` | 回退注释 |
| `app/src/main/java/com/fongmi/android/tv/ui/dialog/BaseSideSheetDialog.java` | 新增 `clearDim(Window)`；`onCreateDialog` 内 `stableOverlay()` 时先清一次 + `setOnShowListener` 里再清一次 |

`TrackDialog.dismiss()`、`OffsetDialog.SideSheet.stableOverlay()==true` 保留（10.26 已提交，行为正确）。

**同类隐患（本轮不动）**：`BaseBottomSheetDialog` 的 `stableOverlay()` 也是在 `onCreateDialog` 里清 dim，而 `ThemeOverlay.Material3.BottomSheetDialog` 同样挂在 `Base.V7.ThemeOverlay.AppCompat.Dialog` 下面 —— 也就是说底部面板（字幕面板 / 轨道列表 / 定时 / 弹幕设置）大概率也被这层 dim 压着，只是底部面板不像全高侧边栏那样明显。要修得在 `BaseBottomSheetDialog` 的 `setOnShowListener` 里链式再清一次；影响面是全部底部面板，等用户确认侧边栏效果后再单独一轮做。

#### 验证与回滚

- 静态（`check_round18.py`）：`selector_control_circle.xml` 恢复 `state_focused` + `layer-list` + `@color/red`/`red_500` + `30dp` 圆，且仍有 `state_selected` → `shape_chip_activated`；`dialog_subtitle.xml` / `dialog_track.xml` 注释回到「焦点 = 30dp 红渐变实心圆」；`BaseSideSheetDialog` 有 `clearDim(Window)`、`onCreateDialog` 与 `setOnShowListener` 各调一次、`clearDim` 里同时清 flag 与 `dimAmount`、`stableOverlay()` 仍默认 `false`；`OffsetDialog.SideSheet` 仍返回 `true`；`TrackDialog.onOffset` 仍有 `dismiss()`；三个 XML 可解析；`git diff --check` 无空白错误。**未编译**（按用户要求只出 diff）。
- 待真机验证：① 字幕列表头部点偏移圆钮 → 字幕列表消失，只剩右侧偏移面板，**面板外视频保持原亮度、看不到任何遮罩**；② 关闭侧边栏回到播放页，字幕偏移生效；③ 5 圆钮面板与轨道列表头部 7 钮聚焦时**恢复红渐变实心圆**，遥控器能看清焦点位置。
- 回滚：`git checkout -- app/src/main/java/com/fongmi/android/tv/ui/dialog/BaseSideSheetDialog.java app/src/leanback/res/drawable/selector_control_circle.xml app/src/leanback/res/layout/dialog_subtitle.xml app/src/leanback/res/layout/dialog_track.xml`。

### 10.28 偏移面板本体改半透明玻璃：逆向值先查清，再按用户决定偏离（2026-11-13，未提交）

用户要求：**「修改偏移设置的面板，看下逆向数据的偏移面板的透明度照抄。」**

#### ① 先查逆向：面板本体是 100% 不透明，全链无 alpha

逐层翻完反编译包，结论是「照抄 = 不透明」，证据如下：

| 证据 | 值 |
| --- | --- |
| `ref_docs/dialog_offset.xml` 根节点 | 无 `android:background`、无 `android:alpha`（全文 123 行逐行确认） |
| `m52.java` / `ci.java` / `s8.java` / `ce0.java` | 全无 `setAlpha` / `setBackgroundDrawable` / `setDimAmount`；`ci.V()` 只有 `f().g = false` + 全屏 `addFlags(1024)` |
| 原版 `m3_side_sheet_dialog.xml` | `<FrameLayout android:id="@+id/m3_side_sheet" style="?attr/sideSheetModalStyle" app:layout_behavior="@string/side_sheet_behavior"/>`（与我们 Material 1.14.0 的同名布局逐字一致） |
| 面板底色来源 | `sideSheetModalStyle` → `Widget.Material3.SideSheet.Modal` → `backgroundTint=?attr/colorSurfaceContainerLow`（原版）／`@macro/m3_comp_sheet_side_docked_modal_container_color` → `?attr/colorSurfaceContainerLow`（我们 1.14.0，宏展开后完全相同） |
| `colorSurfaceContainerLow` | `m3_sys_color_dark_surface_container_low` → `m3_ref_palette_neutral10` → **`#ff1d1b20`**，原版与我们**逐字节相同** |
| 原版所有带 alpha 的颜色 | 51 个，全部是 Material / Leanback / Exo 的内置色（`black_20` / `mtrl_scrim_color` 等），**没有任何一个是 surface 系列** |
| 落地方式 | `SideSheetBehavior.onLayoutChild` 首次布局 → `child.setBackground(MaterialShapeDrawable)`，`setFillColor(backgroundTint)`（`createMaterialShapeDrawableIfNeeded` 里 `shapeAppearanceModel == null` 才跳过，而 `Widget.Material3.SideSheet.Modal` 自带 `shapeAppearance`，所以一定会设） |

也就是说：**原版偏移面板本身就是一块不透明 `#1D1B20`**。用户记忆里「原版几乎透明」的观感，来自原版**完全没有遮罩**（`ci.V()` 从不设 dim，10.27 已修），而不是面板半透明。

#### ② 用户决定：面板本体做成半透明玻璃

把上面结论摆给用户后，用户选择 **「面板改半透明玻璃（推荐）」**。这是对逆向的**有意偏离**，理由与回滚方式都写进代码注释与本节。

#### ③ 改法：用 ThemeOverlay 覆盖 `sideSheetModalStyle`，不在 layout 之后改 View 背景

第一版实现是「`onStart()` 里 `sheet.post(() -> ViewCompat.setBackgroundTintList(...))`」，写完就发现时机不可靠，弃用：

- 面板背景由 `SideSheetBehavior.onLayoutChild` **首次布局**时 `child.setBackground(materialShapeDrawable)` 落地；
- `View.post()` 的 runnable 排在 `Choreographer` 的 traversal 之前执行（`show()` 的 `scheduleTraversals` 是异步的），很可能在布局前就跑完，随后被 `setBackground` 整块覆盖；
- 就算改成 `addOnLayoutChangeListener` 补刷，也是「先画一帧不透明再变半透明」，会有闪一下。

改成**主题层**解决，从第一帧起就是目标颜色，且完全不碰 View：

| 文件 | 改动 |
| --- | --- |
| `app/src/main/res/values/colors.xml` | 新增 `sheet_panel_bg` = `#B31D1B20`（`#1D1B20` 的 70% 不透明，`#B3` = 179/255），注释写明「有意偏离 + 回滚方法」 |
| `app/src/main/res/values/styles.xml` | 新增 `Widget.WebHTV.SideSheet.Modal.Translucent`（parent `Widget.Material3.SideSheet.Modal`，只覆盖 `backgroundTint`）+ `ThemeOverlay.WebHTV.SideSheetDialog.Translucent`（parent `ThemeOverlay.Material3.SideSheetDialog`，把 `sideSheetModalStyle` 指过去） |
| `app/src/main/java/com/fongmi/android/tv/ui/dialog/BaseSideSheetDialog.java` | `new SideSheetDialog(requireContext())` → `new SideSheetDialog(requireContext(), panelTheme())`；新增 `protected int panelTheme() { return 0; }`（默认 0 = 沿用 `?attr/sideSheetDialogTheme`，其它侧边栏行为一字不变） |
| `app/src/main/java/com/fongmi/android/tv/ui/dialog/OffsetDialog.java` | `SideSheet` 覆写 `panelTheme()` 返回 `R.style.ThemeOverlay_WebHTV_SideSheetDialog_Translucent`；新增 `import com.fongmi.android.tv.R;` |
| `app/src/leanback/res/layout/dialog_offset.xml` | 只改文件头注释（说明 10.28 的半透明来自主题覆盖，布局根节点仍然不许贴 drawable） |

为什么走主题而不是改 View：`SideSheetDialog(Context, theme)` 的 theme 会经 `ContextThemeWrapper` 叠在 Activity 主题上，Material 自己也是把 `?attr/sideSheetDialogTheme` 指向一个 ThemeOverlay 来用的 —— 与框架用法一致，不是偏方。

已验证依赖存在（`material-1.14.0.aar`）：`ThemeOverlay.Material3.SideSheetDialog`、`Base.ThemeOverlay.Material3.SideSheetDialog`、`Widget.Material3.SideSheet.Modal`、`Widget.Material3.SideSheet`、`<attr format="reference" name="sideSheetModalStyle"/>` 全部命中。

范围控制：`DanmakuSettingDialog.SideSheet`（弹幕设置侧边栏）**不覆写** `panelTheme()`，仍是逆向的不透明 surface；手机端走 `OffsetDialog.BottomSheet`，完全不受影响。

#### 验证与回滚

- 静态（`check_round19.py`）：`styles.xml` / `colors.xml` / `dialog_offset.xml` 可解析；`sheet_panel_bg` = `#B31D1B20` 且样式里引用它；两个新样式的 parent 与 `backgroundTint` / `sideSheetModalStyle` 逐项命中；`BaseSideSheetDialog` 用 `panelTheme()` 造 Dialog、默认 `return 0`、无 `applyPanelColor` / `ColorStateList` 残留；`OffsetDialog.SideSheet` 覆写返回 `R.style.ThemeOverlay_WebHTV_SideSheetDialog_Translucent`；全仓无 `panelBackgroundColor` 残留；`dialog_offset.xml` 根节点仍无 `android:background`；10.27 的 `clearDim` + `setOnShowListener` 仍在；括号配平；`git diff --check` 无空白错误。**未编译**（按用户要求只出 diff）。
- 待真机验证：① 字幕列表头部点偏移圆钮 → 右侧面板是**半透明玻璃**（视频从面板后隐约透出，约 70% 不透明），面板外**完全没有遮罩**；② 面板圆角、阴影、宽度（`min(320dp, 屏宽/2)`）与 10.25 一致，没有被主题覆盖改坏；③ 标题「偏移设置」、Reset 按钮、滑块与数值显示颜色/位置不变；④ 弹幕设置侧边栏仍是原来的不透明面板（未覆写 `panelTheme()`）；⑤ 手机端偏移底部面板不变。
- 调深浅：只改 `@color/sheet_panel_bg` 的 alpha（`#B3` → `#CC` 更实、`#99` 更透），不用动 Java。

### 10.29 播放页 AI 入口与设置「AI 服务」页整体下线（2026-11-13，未提交）

用户要求：**「字幕中的原声识别语言 顶部选择音频原语言按钮 ai按钮 全部去掉。然后设置中的ai服务里的所有功能全部去掉后，设置中ai服务按钮页去掉。」**

按字面拆成两件事：① 播放页轨道/字幕面板里的 AI 相关按钮与「原声识别语言」行全部移除；② 设置里的「AI 服务」页整页下线，入口一并移除。底层 AI 服务（`AiConfig` / `AiCompletionClient` / `AiSubtitleTranslationService` / `RealtimeSubtitleController` 等）仍被标题解析、推荐、广告检测、观看报告、字幕手动搜索等调用，**本轮不动**，只摘 UI。

#### ① 播放页：删 3 个 AI 圆钮 + 原声识别语言行

| 文件 | 改动 |
| --- | --- |
| `app/src/leanback/res/layout/dialog_track.xml` | 删 `@+id/ai`、`@+id/realtimeAi`、`@+id/aiRegenerate` 三个圆钮与 `@+id/realtimeLanguage` 行（含 `realtimeLanguageText`）；`@+id/aiStatus` 文本保留 |
| `app/src/mobile/res/layout/dialog_track.xml` | 同上（手机端无 `selector_control_circle`，按钮形态不同，节点语义一致） |
| `app/src/main/java/com/fongmi/android/tv/ui/dialog/TrackDialog.java` | 删 `initEvent` 里 4 个监听；`refreshTrackList` 删 3 处可见性与 `updateRealtimeLanguage()` / `updateRealtimeButton()`；删 `onRealtimeAi` / `showRealtimeModelSelection()` / `onRealtimeLanguage` / `showRealtimeModelSelection(boolean)` / `updateRealtimeLanguage` / `updateRealtimeButton` / `onAiTranslate` / `onAiRegenerate`；删 `realtimeState` 字段与 `onStateChanged` 里的两处刷新；删 `hasAi` / `hasRealtimeAi` / `hasAiRegenerate`；`showAppliedSubtitleTrack` 与 `setAiTranslating` 去掉对已删 View 的引用 |

保留项与理由：

- `@+id/aiStatus` 文本默认 `GONE`、且已无任何入口能触发，保留它是为了不牵动 `showAiStatus` / `showAiProgress` / `updateAiState` / `AiTaskState` 这一整条状态机；真要彻底删是另一轮独立改动。
- `startAiTranslation(...)`、`progressListener`、`onAiTranslated`、`applyTranslatedAsset`、`AiTaskState` 全部保留（翻译服务层仍在，`TrackDialogTest` 也仍在测 `originalSubtitleForRegenerate` / `AiTaskState`）。
- `enableRealtimeAi` / `startRealtimeAi` / `disableIncompatibleAudioModes` 保留但**已无 UI 可达**；它们牵涉音频直通/隧道模式兼容处理，删掉属于播放链路改动，不在本轮授权范围。
- `stopRealtimeAi()`、`RealtimeSubtitleController` 监听、`onStateChanged` 保留：入口没了以后它们只是空转，但删监听会改动字幕/音频管线生命周期，风险大于收益。

#### ② 设置：整页删除「AI 服务」

| 文件 | 改动 |
| --- | --- |
| `app/src/leanback/java/.../ui/activity/SettingAiActivity.java` | 删除文件 |
| `app/src/mobile/java/.../ui/fragment/SettingAiFragment.java` | 删除文件 |
| `app/src/leanback/res/layout/activity_setting_ai.xml` | 删除文件 |
| `app/src/mobile/res/layout/fragment_setting_ai.xml` | 删除文件 |
| `app/src/leanback/res/layout/activity_setting.xml` | 删 `@+id/ai` 入口；`tmdb` 由「两列」改回整行（`match_parent`、去 `layout_weight` / `layout_marginEnd`），`nextFocusRight` 由 `@id/ai` 改为自身 `@id/tmdb`（与其它整行项一致） |
| `app/src/mobile/res/layout/fragment_setting.xml` | 删 `@+id/ai` 入口，`personal` 直接接在 `tmdb` 之后 |
| `app/src/leanback/java/.../ui/activity/SettingActivity.java` | 删 `mBinding.ai.setOnClickListener(this::onAi)` 与 `onAi()` |
| `app/src/mobile/java/.../ui/fragment/SettingFragment.java` | 删 `mBinding.ai.setOnClickListener(this::onAi)` 与 `onAi()`（原来 `getRoot().change(8)`） |
| `app/src/mobile/java/.../ui/activity/HomeActivity.java` | 删 `SettingAiFragment` import、`case 8 -> ...`、`isSettingSubPageVisible()` 里的 `isVisible(8)`；`case 9`（广告）保持原编号，不重排；`restorePosition()` 在 `changeFragment()` 返回 `false`（旧任务栈里残留的位置 8）时回落到设置首页（1），避免恢复出空白页 |
| `app/src/leanback/AndroidManifest.xml` | 删 `.ui.activity.SettingAiActivity` 声明 |

`case 8` 留空而不是把 9 挪到 8：所有位置都是硬编码 `getRoot().change(N)` 与 `mManager.isVisible(N)`，重排编号要同步改 `SettingFragment` / `HomeActivity` 多处，收益为零、回归面反而更大。

#### ③ 同步测试

| 文件 | 改动 |
| --- | --- |
| `app/src/test/java/.../setting/SettingPlaybackDefaultsTest.java` | `aiSettingsLiveUnderDedicatedSettings` → `aiSettingsPageIsRemoved`（断言两个首页布局无 `@+id/ai`、四个 AI 页文件不存在）；`dedicatedSettingsNavigationIsWired` 删 AI 断言并反向断言 `SettingAiFragment` / `onAi` / `SettingAiActivity` 均已消失；`adSettingsLiveUnderTheDedicatedAdPage` 删对已删布局的读取 |
| `app/src/testMobile/java/.../dialog/TrackDialogTest.java` | `aiRegenerateButton_bypassesCacheAndSharesBusyState` → `aiTrackActionsAreRemovedFromTrackDialog`（两个布局不再含 4 个 id、源码不再含对应 `binding.*` 与 handler，翻译管线的 `useCache` 参数仍在）；`realtimeLanguageCanBeChangedFromTrackDialog...` → `realtimeModelSwitchKeepsPlaybackSession`（去掉 UI 断言，保留控制器 `switchModel` / 代际校验断言） |
| `app/src/testMobile/java/.../dialog/RecommendationFeedbackManagementLayoutTest.java` | 删「AI 设置页暴露反馈管理」的 leanback 用例；手机端用例去掉 AI Fragment/布局断言，只留反馈管理弹窗自身断言 |

#### 验证与回滚

- 静态（`check_round20.py`）：两个 `dialog_track.xml`、两个设置布局、`AndroidManifest.xml` 均可解析；两个轨道布局无 `ai` / `realtimeAi` / `aiRegenerate` / `realtimeLanguage`，`aiStatus` 仍在；`TrackDialog.java` 无 `binding.ai` / `binding.realtimeAi` / `binding.aiRegenerate` / `binding.realtimeLanguage` / `realtimeState` / `hasAiRegenerate`；四个 AI 页文件已不存在；`activity_setting.xml` 无 `@+id/ai` 且 `tmdb` 为 `match_parent`、`nextFocusRight="@id/tmdb"`；`SettingActivity` / `SettingFragment` / `HomeActivity` / leanback 清单无 `SettingAi*` 残留；`HomeActivity` 仍有 `case 9` 且无 `case 8`；三个测试文件含新断言；Java 括号配平；`git diff --check` 无空白错误。**未编译**（按用户要求只出 diff，由 GitHub Actions 打包验证）。
- 待真机验证：① 字幕面板头部只剩 `choose` / `search` / `subtitle` / `offset` 四个圆钮（点「字幕」进列表时），无 AI 图标；② 列表上方不再出现「原声识别语言」行，`aiStatus` 文案不出现；③ 音轨/视轨面板头部与之前一致；④ 设置首页「TMDB」变成整行、遥控器左右/上下焦点不丢；⑤ 手机端设置列表 TMDB 之后直接是「个性化」，无「AI 服务」；⑥ leanback 设置页同样无「AI 服务」入口；⑦ 手机端从旧任务栈恢复（原位置 8）时落到设置首页，不是空白页。
- 回滚：`git checkout -- app/src/main/java/com/fongmi/android/tv/ui/dialog/TrackDialog.java app/src/leanback/res/layout/dialog_track.xml app/src/mobile/res/layout/dialog_track.xml app/src/leanback/res/layout/activity_setting.xml app/src/mobile/res/layout/fragment_setting.xml app/src/leanback/java/com/fongmi/android/tv/ui/activity/SettingActivity.java app/src/mobile/java/com/fongmi/android/tv/ui/fragment/SettingFragment.java app/src/mobile/java/com/fongmi/android/tv/ui/activity/HomeActivity.java app/src/leanback/AndroidManifest.xml` 并 `git checkout -- app/src/leanback/java/com/fongmi/android/tv/ui/activity/SettingAiActivity.java app/src/mobile/java/com/fongmi/android/tv/ui/fragment/SettingAiFragment.java app/src/leanback/res/layout/activity_setting_ai.xml app/src/mobile/res/layout/fragment_setting_ai.xml`。
- 回滚：删掉 `OffsetDialog.SideSheet.panelTheme()` 覆写即回到逆向原样；彻底回滚用 `git checkout -- app/src/main/java/com/fongmi/android/tv/ui/dialog/BaseSideSheetDialog.java app/src/main/java/com/fongmi/android/tv/ui/dialog/OffsetDialog.java app/src/main/res/values/styles.xml app/src/main/res/values/colors.xml app/src/leanback/res/layout/dialog_offset.xml`。
