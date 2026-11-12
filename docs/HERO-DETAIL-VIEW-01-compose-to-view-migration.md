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

