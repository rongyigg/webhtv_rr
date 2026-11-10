# 手动 TMDB 匹配弹窗：纯 View 重构

## 目标

把「手动 TMDB 匹配」弹窗从 Compose（`app/src/main/java/com/fongmi/android/tv/ui/hero/TmdbMatchDialog.kt`）
改为纯 View 实现：XML 布局 + RecyclerView 虚拟键盘 + 标准 `AlertDialog`，并保持与逆向 App
一致的版式、正则清洗与跨区焦点。

## 证据（best-practice / 逆向对照）

| 来源 | 位置 | 用途 |
|---|---|---|
| 逆向布局 | `ref_docs/dialog_tmdb_match.xml` | 217dp 输入区 + 6 列键盘、210dp 结果区、联想词行、季数胶囊 |
| 逆向弹窗逻辑 | `ref_docs/pc3.java` / `ref_docs/TmdbMatchDialog.java` | `k0()` 正则清洗链、`f0()` 去重、`g0()` 卡片 `nextFocusLeftId=R.id.searchBtn`、`d()/`dim 0.82 |
| 逆向键盘适配器 | `ref_docs/ub2.java` / `ref_docs/TmdbKeyboardAdapter.java` | 4 功能键 + 26 字母、6 列、30dp 方键、`selector_tmdb_key` |
| 逆向卡片 | `ref_docs/adapter_tmdb_match.xml` | 110dp 卡片 / 102×153 海报 / 12sp 标题 / 10sp 副标题 |
| 现有本地实现 | `ui/dialog/TmdbSearchDialog.java` | 已有的 View 对话框惯例（MaterialAlertDialogBuilder + LightDialog 主题） |
| 现有本地数据层 | `ui/helper/TmdbMatcher`、`service/TmdbService`、`utils/ImgUtil` | 搜索与图片加载复用，避免另写一套 |

## 决策

- **不改数据层**：`TmdbMatcher.search(keyword, vod)` 在后台线程调用，失败只记日志，绝不抛到 UI。
- **不用 leanback `HorizontalGridView`**：`androidx.leanback` 只在 `leanbackImplementation` 依赖里，
  弹窗位于 `main` 源集，因此结果区用 `RecyclerView` + 横向 `LinearLayoutManager`，
  但 `g0()` 的焦点规则（子 View `nextFocusLeftId = searchBtn`）逐字保留。
- **不用 Compose**：弹窗本体是 XML + View；两个 Compose 首页只保留 10 行桥接
  （`ui/dialog/TmdbMatchViewHost.kt`），它不渲染任何 Compose UI，只负责在 composition 挂载时弹出 View 对话框。
- **键盘**：4 个功能键用「确认 / 左移 / 右移 / 退格」（逆向第 1 个是扫码键，本工程没有该资源），
  加 26 字母正好 30 键 = 5×6，与逆向 `spanCount="6"` 对齐。
- **输入框禁系统键盘**：`setShowSoftInputOnFocus(false)` + 窗口 `FLAG_ALT_FOCUSABLE_IM` +
  `SOFT_INPUT_STATE_ALWAYS_HIDDEN` 三重保险，不依赖 XML 属性（不同 aapt 版本支持不一）。

## 验收标准

1. 打开即聚焦输入框，输入框↓进键盘第一排，第一排↑回输入框。
2. 键盘最右列按右 → 右侧第一张海报；海报按左 → 放大镜按钮；海报按上 → 键盘。
3. 顶部联想词 = `k0()` 结果（清洗名 + 「清洗名 年份」），点击即搜索。
4. 放大镜 / 键盘确认键 / 输入框回车都触发 TMDB 搜索；`TmdbConfig` 未配置时显示「先配置 TMDB」。
5. 全程不弹系统软键盘。

## 验证

- 只做静态校验（用户要求不编译）：新增 23 个 XML 全部通过 `System.Xml` 解析；
  两个新 Java 类花括号/圆括号平衡；布局/资源名与既有资源无跨源集覆盖冲突（`ic_keyboard_*` 为
  main 新增副本，leanback 侧同名文件按 flavor 覆盖规则优先，内容一致）。
- 真机/编译验证留给用户。

## 回滚

单一提交，回滚 = `git revert <commit>`；或把两个 Activity 的导入与调用换回
`com.fongmi.android.tv.ui.hero.TmdbMatchDialog`（Compose 版仍保留在仓库里，未删除）。

## 状态

- [x] 布局 / 资源 / 键盘适配器 / 弹窗核心逻辑
- [x] 首页（`HomeViewActivity`）与 Hero 首页（`HeroHomeActivity`）接线
- [x] 第 2 轮视觉与交互修复（见下）
- [ ] 播放页 `VideoActivity` 的重匹配入口（目前仍是 `TmdbSearchDialog` 列表版）——下一阶段
- [ ] 编译 + 真机焦点回归

## 第 2 轮：视觉 / 交互 / 建议复用修复

| 项 | 改动 | 位置 |
|---|---|---|
| 遮罩 | `setDimAmount(0.82f)` → `0.55f`，透出背后画面 | `TmdbMatchViewDialog.configureWindow()` |
| 防抖高度 | 顶栏固定 `36dp`、胶囊固定 `28dp`（+上下 4dp 边距）、RSS 高度 `match_parent`；建议从 0 条变 10 条弹窗高度不变 | `dialog_tmdb_match_view.xml` / `adapter_tmdb_suggestion.xml` |
| 对齐 | 键盘 6×30dp+5×5dp=205dp 居中于 217dp → B 键 181~211dp（中心 196dp）；放大镜 32dp + `marginEnd 5dp` → 180~212dp（中心 196dp），输入框同步压短 5dp | `dialog_tmdb_match_view.xml` |
| 键盘底色 | 默认态改 `@color/white_20`（即 `#33FFFFFF`）+ 4dp 圆角方块 | `shape_tmdb_key_normal.xml` |
| 二维码键 | 第 1 个功能键图标 `ic_keyboard_search` → 新增 `ic_qr`（描边方框 + 数据点，不依赖 fillType） | `TmdbKeyboardAdapter.ICONS[0]` |
| 建议复用 | `TextWatcher` 停顿 260ms → `SearchSuggest.iqiyiUrl/tencentUrl` 发请求 → `parseIqiyi/parseTencent` 解析 → `merge` 合并去重；本地 `k0()` 结果置顶，最多 10 条；序号 + `dialog.isShowing()` 双重过期保护 | `TmdbMatchViewDialog`（`scheduleSuggestions/requestSuggestions/applySuggestions/refreshSuggestions`） |
| 全局红 | 键盘键、放大镜、建议胶囊焦点态分别引用 `@drawable/bg_global_focus_solid_red` / `bg_global_focus_capsule`（leanback 原件字节级复制到 main）；删除 `shape_tmdb_key_focused` / `shape_tmdb_search_focused` / `shape_tmdb_suggestion_focused` 三个私有纯红形状 | `selector_tmdb_key/search/suggestion` |
| 去硬编码红 | `shape_red_bar` 与 `shape_tmdb_badge` 的 `#e50914` → `@color/osd_accent`（同值，全工程零像素变化） | 两个共享 drawable |

不改动项（红线 3）：`TmdbKeyboardAdapter.dispatchKey()` 的跨区焦点拦截、`candidate.addOnChildAttachStateChangeListener` 里 `setNextFocusLeftId(R.id.searchBtn)`、`nextFocusUp/Down` 一律未动。

第 2 轮验证：新增/修改 XML 全部 `System.Xml` 解析通过；三个 selector 焦点项均指向全局 drawable；
被删的三个 shape 无任何残留引用；`TmdbMatchViewDialog` / `TmdbKeyboardAdapter` 括号平衡。

## 第 3 轮：最终视觉定稿

| 项 | 改动 | 位置 |
|---|---|---|
| 去遮罩 | `setDimAmount(0.55f)` → `0f` + `clearFlags(FLAG_DIM_BEHIND)`，弹窗外围（顶栏 / 背景海报）零压暗 | `TmdbMatchViewDialog.configureWindow()` |
| 弹窗垫底 | 外壳底色 `@color/panel_scrim_end`（#E6000000）→ 新增 `@color/panel_dialog_solid` **#F2222222**，95% 深灰、12dp 圆角 | `shape_tmdb_match_shell.xml` / `values/colors.xml` |
| 卡片间距 | 新增 `CandidateSpacing` ItemDecoration：右边距 12dp、最后一张不留；卡片 110dp → 108dp（海报 102×153 → 100×150） | `TmdbMatchViewDialog.setupCandidate()` / `adapter_tmdb_match_card.xml` |
| 密度算账 | 960dp 逻辑宽 → 弹窗 787dp → 右侧可视 512dp；4×(108+12)=480 完整 4 张，第 5 张起点 480、露出 32dp ≈ 1/3 张 | 同上 |
| 焦点框 | 卡片根布局 `android:foreground="@drawable/selector_vod"`（首页 leanback 海报卡的同一资源，1.5dp `@color/osd_focus` + 8dp 圆角）；未聚焦不绘制；1.08 缩放动画不变 | `adapter_tmdb_match_card.xml` + main 复制 `selector_vod.xml` / `shape_vod_focused.xml` |

不动项（红线 3）：键盘适配器（含二维码键、`selector_tmdb_key`）、联想词去抖与 `SearchSuggest` 拉取、`dispatchKey()` 跨区拦截、`setNextFocusLeftId(R.id.searchBtn)`、`nextFocusUp/Down` 全部未改。

第 3 轮验证：新增/修改 XML 解析通过；`selector_vod` 与 leanback 原件字节一致；`panel_dialog_solid` 已定义；
`TmdbMatchViewDialog` 括号平衡；改动全部落在声明路径。

## 第 4 轮：严格照抄 pc3.java（红框位置 / 8dp 间距 / 按键拦截）

| 项 | 改动 | 位置 |
|---|---|---|
| 红框位置 | 根 `LinearLayout` 移走 `android:foreground`，改挂到 `@id/imageWrapper`（保留 `duplicateParentState="true"`）；红框只贴海报，不再框住标题 / 年份 | `adapter_tmdb_match_card.xml` |
| 防裁切 | `candidate.setClipChildren(false)` + `setClipToPadding(false)`；XML 同步 `clipChildren="false"`，`paddingStart` 2dp → 8dp | `TmdbMatchViewDialog.setupCandidate()` / `dialog_tmdb_match_view.xml` |
| 卡片间距 | `CandidateSpacing(dp(12))` → `dp(8)`，照抄逆向 `b0()` 的 `setHorizontalSpacing(g2.b(8))` | `TmdbMatchViewDialog.setupCandidate()` |
| 放大动作 | 去掉 `animate().setDuration(120)`，改 `applyCardFocus()` 直接 `setScaleX/Y(1.08f)` + `setTranslationZ(5dp)`；`onChildViewAttachedToWindow` 补 `g0()` 的 `if (childAt.hasFocus())` 初始化 | `TmdbMatchViewDialog` |
| 按键拦截 | `show()` 里 `dialog.setOnKeyListener(...)`（照抄逆向 `F()` 的 `new n40(this, 3)`）：返回键 / 方向键 / 确认键 / 音量键放行，其余按键一律吃掉 | `TmdbMatchViewDialog.show()` |
| 按键兜底 | `dispatchKeyEvent` 顶部 `if (tmdbMatchHost != null) return true` | `HomeViewActivity.dispatchKeyEvent` |

未移植项：`F()` 里的 `d0(0.82f)`（全屏 dim 0.82）与第 3 轮「弹窗外围不许变暗」红线冲突，dim 保持 0，只移植 `setOnKeyListener`。

n40 说明：`n40` 在 R8 后没有独立类（`ref_docs` 无原件），按唯一可观测行为复刻；若无条件 `return true`，方向键会被一并吞掉，弹窗内键盘与候选卡片将完全无法操作，因此保留导航键放行。

第 4 轮验证：两个 XML 解析通过；`foreground` 只出现在 `imageWrapper`；间距 8dp / `paddingStart` 8dp / 两个 `clip* = false` 均已就位；Java 括号平衡；改动全部落在声明路径。

## 第 5 轮：终局收尾（抢焦点 + 删旧 Compose 弹窗）

| 项 | 改动 | 位置 |
|---|---|---|
| 抢焦点 | `show()` 之后 `dialog.getWindow().getDecorView().requestFocus()`；确认窗口只有 `FLAG_ALT_FOCUSABLE_IM`（仅屏蔽输入法），**没有** `FLAG_NOT_FOCUSABLE` | `TmdbMatchViewDialog.show()` |
| 首页拦截 | `isTmdbShowing`：呼出前置 true，dismiss 回调置 false；`dispatchKeyEvent` 第一行 `if (isTmdbShowing) return true` | `HomeViewActivity` / `HeroHomeActivity` |
| 直调弹窗 | 删除 0×0 `ComposeView` + `setContent` 桥接，改 `TmdbMatchViewDialog.create(this).keyword().vod().listener().dismissListener().show()` | `HomeViewActivity.showTmdbManualMatch()` |
| Hero 直调 | 删掉 `if (showTmdbDialog) { TmdbMatchViewHost(...) }`，改在 `LaunchedEffect(tmdbPoster.posterKey)` 里直接建弹窗 | `HeroHomeActivity.HeroHomeScreen` |
| 生命周期 | 两个 Activity 的 `onDestroy` 先 `dismiss()` 再复位标志，避免 WindowLeaked 与残留吞键 | 两个 Activity |
| 删除文件 | `ui/hero/TmdbMatchDialog.kt`（旧 Compose 弹窗）、`ui/dialog/TmdbMatchViewHost.kt`（桥接） | — |

`HeroHomeActivity` 的两个弹窗状态（`tmdbMatchDialog` / `isTmdbShowing`）放在**文件级 private**：`HeroHomeScreen` 是顶层 Composable，拿不到 Activity 的 `this`，而 `private` 成员对顶层函数不可见；文件级只在本文件可见，等价于本 Activity 的私有字段。
`Context.findActivity()` 同步从成员扩展提升为文件级私有函数，供 Composable 与 `launchPlayback` 共用。

未改动（红线 1）：`dialog_tmdb_match_view.xml` / `adapter_tmdb_match_card.xml` / `TmdbKeyboardAdapter` / 联想词逻辑 / `candidate` 的焦点流转一律零改动。

保留说明：`ui/hero/HeroKeyboard.kt` 仍被 `HeroSearchActivity` 使用，未删除；其中一处 KDoc 仍提到类名 `TmdbMatchDialog`，仅是注释，不影响编译。

第 5 轮验证：全仓 `TmdbMatchViewHost` 零引用；`ui.hero.TmdbMatchDialog` 零引用；两个 Kotlin 文件花括号 314/314、594/594；`TmdbMatchViewDialog` 花括号 139/139；删除的两个文件已由 git 记录为删除；改动全部落在声明路径。

## 第 6 轮：HomeView 纯 View 首页按键穿透的根因排查与修复

| 项 | 改动 | 位置 |
|---|---|---|
| 诊断日志 | `Log.d("KeyTest", "HomeViewActivity dispatchKeyEvent: ${event.keyCode}, isTmdbShowing=$isTmdbShowing")` | `HomeViewActivity.dispatchKeyEvent` 第一行 |
| 标志竞态 | 新增 `tmdbDialogSerial`：每次呼出自增；dismiss 回调代号不匹配直接丢弃 | `HomeViewActivity.showTmdbManualMatch()` |
| Window 焦点位 | `clearFlags(FLAG_NOT_FOCUSABLE \| FLAG_NOT_TOUCH_MODAL)`，再 `setFlags(FLAG_ALT_FOCUSABLE_IM, FLAG_ALT_FOCUSABLE_IM)`（掩码只改这一位） | `TmdbMatchViewDialog.configureWindow()` |
| Dialog 级拦截 | 返回键 `dismiss()` + 返回 true；方向 / 确认 / 回车 / 音量键放行；其余一律吞 | `TmdbMatchViewDialog.show()` |
| 根布局兜底 | `root.setOnKeyListener` → `handleRootKey()`：方向键用 `focusSearch` 判定，只有该方向已无弹窗内目标时才吞；确认 / 回车就地吞掉 | `TmdbMatchViewDialog.show()` |

**根因**：`Dialog.dismiss()` 的 dismiss 回调是 Handler 异步消息（`Dialog.sendDismissMessage()` 内部 `sendToTarget()`）。`showTmdbManualMatch()` 先 `dismiss()` 旧窗、再置 `isTmdbShowing = true`，旧窗的迟到回调随后才执行，把新弹窗的标志复位成 `false`、`tmdbMatchDialog` 置空 —— 新弹窗整个生命周期 Activity 都不再拦截，按键直接落到海报列表。代号校验就是修这个竞态。

**兜底的边界**：`dispatchKeyEvent` 只对"派给 Activity 窗口"的按键生效；`Dialog` 是独立 Window，一旦它的 DecorView 拿到焦点，按键走 `Dialog.dispatchKeyEvent → Dialog.OnKeyListener → Window.superDispatchKeyEvent`，根本不经过 Activity。所以 Activity 只兜 `show()` 前后的窗口切换空窗期，弹窗内部的拦截必须由 Dialog 自己完成。

**RecyclerView 排查结论**：`HomeViewActivity` 的 `recycler` 没有任何 `setOnKeyListener` / `setOnKeyDown` 覆写，Adapter 也不消费按键；`descendantFocusability` 只控制焦点可达性。因此不存在"RecyclerView 私自截胡"，无需改动（红线 2）。

第 6 轮验证：`HomeViewActivity` 花括号 315/315、圆括号 1063/1063；`TmdbMatchViewDialog` 花括号 144/144、圆括号 570/570；`tmdbDialogSerial` 只在 `showTmdbManualMatch()` 内读写；未编译（按用户要求只出 diff）。

## 第 7 轮：抢焦点加固、KeyTest 日志清理、旧入口下架

| 项 | 改动 | 位置 |
|---|---|---|
| 根布局可聚焦 | 最外层 `LinearLayout` 增加 `android:focusable="true"` / `android:focusableInTouchMode="true"` | `dialog_tmdb_match_view.xml` |
| 强制夺焦 | `show()` 之后 `clearFlags(FLAG_NOT_FOCUSABLE)` + `DecorView.setFocusableInTouchMode(true)` + `requestFocus()` | `TmdbMatchViewDialog.show()` |
| 延迟二次抢焦 | 100ms 后 DecorView 与 `keyword` 各再 `requestFocus()` 一次；关窗时移除任务 | `TmdbMatchViewDialog.scheduleRefocus()` |
| 输入法 | 仍由 `FLAG_ALT_FOCUSABLE_IM` + `SOFT_INPUT_STATE_ALWAYS_HIDDEN` 屏蔽 | `configureWindow()` |
| 调试日志 | 删除全部 `Log.d("KeyTest", ...)`（6 处） | `HomeViewActivity` |
| 旧入口 | 主清单删除 `.ui.activity.HeroHomeActivity` 条目（无 intent-filter、无任何启动方），契约测试改为断言 `HomeViewActivity` 为唯一 LAUNCHER 且清单不再含 HeroHomeActivity | `AndroidManifest.xml` / `AppBrandingContractTest` |

**未删除（红线 2 核查结论）**：`HeroHomeActivity.kt` 与 `HistoryActivity` 本轮不删。
- `HeroHomeActivity.kt` 仍承载共享 API：`HeroTmdbData` / `HeroTmdbPrefetchCache` / `HeroLogoCache` / `heroTmdbKey` / `tmdbLogoKey` / `upgradeTmdbImage` / `rememberHeroImageModel` / `clearHeroCaches`，被 `HomeViewActivity`、`HeroDetailActivity`、`HistoryViewActivity`、`KeepViewActivity`、leanback `VideoActivity`、`FileUtil` 直接引用；文件内还有约 3300 行 Compose 首页实现。删除必须先抽出共享 API（改文件名会连带改 Java 侧 `HeroHomeActivityKt.clearHeroCaches()`），属于跨模块重构，需要一次真实编译验证。
- `HistoryActivity`（leanback + mobile）仍是 flavor 首页 `HomeActivity` 与 mobile `VodFragment` 的历史页；`HomeActivity` 又被 `VideoActivity` / `LiveActivity` / `TmdbDetailActivity` 的 `isTaskRoot()` 回退分支启动，仍然可达。`HistoryViewActivity` 只接管了 `HomeViewActivity` 这条入口。

第 7 轮验证：两个 XML `[xml]` 解析通过；`HomeViewActivity` 花括号 306/306、圆括号 1056/1056；`TmdbMatchViewDialog` 花括号 154/154、圆括号 609/609；`"KeyTest"` 字符串零残留；主清单无 `HeroHomeActivity`；未编译（按用户要求只出 diff）。

## 第 8 轮：新旧 Dialog 的 Window 配置逐行对比 + `addView` 前定稿 + 三层焦点日志

### ① 旧 Compose 版 `TmdbMatchDialog` 到底配了什么

结论：**它自己什么都没配。** 除 `usePlatformDefaultWidth = false` 外没有任何 Window / Flag 代码，全部来自 Compose UI 的 `DialogWrapper`。

| 证据 | 位置 |
|---|---|
| `Dialog(onDismissRequest = ..., properties = DialogProperties(usePlatformDefaultWidth = false))` | `edf413c68^:app/src/main/java/com/fongmi/android/tv/ui/hero/TmdbMatchDialog.kt:290-292` |
| `window.requestFeature(FEATURE_NO_TITLE)`、`setBackgroundDrawableResource(android.R.color.transparent)`、`WindowCompat.setDecorFitsSystemWindows(window, true)`、`window.setGravity(Gravity.CENTER)` | `androidx.compose.ui:ui-android:1.10.4` → `androidMain/androidx/compose/ui/window/AndroidDialog.android.kt:496-506` |
| `window.setSoftInputMode(SOFT_INPUT_ADJUST_UNSPECIFIED)`（`decorFitsSystemWindows=true` 分支） | 同上 `:638-646` |
| 主题 `R.style.DialogWindowTheme` 的定义只有 `<item name="android:windowClipToOutline">false</item>`（无 parent、无 `windowIsFloating`、无 dim） | `ui-android-1.10.4.aar!/res/values/values.xml` |
| 全文没有 `FLAG_NOT_FOCUSABLE`、`FLAG_ALT_FOCUSABLE_IM`、`TYPE_APPLICATION_OVERLAY`、`setDimAmount` | 同上（整份文件 grep 无命中） |
| 焦点是"下一帧再抢"，不是靠 Flag：`LaunchedEffect(Unit) { withFrameNanos { }; requestFocus(inputRequester); ... }` | `TmdbMatchDialog.kt:280-284` |

也就是说：旧弹窗之所以能拦按键，靠的是 **① 全屏非浮动窗口（`windowIsFloating` 未设 → false）+ ② 所有窗口属性在 `show()` 前一次性带上去 + ③ 过一帧后由弹窗内真实可聚焦子节点抢焦点**。

### ② 逐行对比

| 配置点 | 旧 Compose（生效值） | 新 View（本轮前） | 新 View（本轮后） |
|---|---|---|---|
| Dialog 主题 | Compose `DialogWindowTheme`（仅 `windowClipToOutline=false`） | `Theme_WebHTV_LightDialog`（parent `Theme.Material3.Light.Dialog.Alert`） | 不变 |
| `windowIsFloating` | false（主题未声明） | true（Material Alert 默认） | 不变（未改主题，避免动内部版式） |
| `FEATURE_NO_TITLE` | 显式 `requestFeature` | AlertDialog 自带 | 不变 |
| 背景 | `android.R.color.transparent` | `ColorDrawable(Color.TRANSPARENT)` | 不变 |
| 调暗 | 无 dim | `setDimAmount(0f)` + `clearFlags(FLAG_DIM_BEHIND)` | 不变 |
| `FLAG_NOT_FOCUSABLE` | 从未出现 | `clearFlags`（**`show()` 之后**） | `configureWindow()` 提前到 `show()` **之前**，进入时即无该位 |
| `FLAG_NOT_TOUCH_MODAL` | 从未出现 | `clearFlags`（show 之后） | 同上，提前 |
| `FLAG_ALT_FOCUSABLE_IM` | 无 | 设置 | 保留（用户要求；语义只是"不要输入法、仍要按键焦点"） |
| `softInputMode` | `ADJUST_UNSPECIFIED` | `STATE_ALWAYS_HIDDEN` | `STATE_ALWAYS_HIDDEN \| ADJUST_NOTHING`（不许 IME 触发 relayout） |
| `gravity` | `CENTER`（Compose 显式） | 主题默认 | 显式 `Gravity.CENTER` |
| 宽度 | 内容 `fillMaxWidth(0.82)` | `setLayout(0.82w, wrap)`（show 之后） | `setLayout` 提前到 `show()` 之前 |
| 焦点请求 | 一帧后 `requestFocus(真实子 View)` | `decorView.requestFocus()` + `keyword.post{}` | 相同 + 100ms 二次抢焦 + 三层日志 |
| 窗口类型 | `TYPE_APPLICATION`（默认） | `TYPE_APPLICATION`（默认） | 不变；**不引入** `TYPE_APPLICATION_OVERLAY`（旧版没有，且需要 `SYSTEM_ALERT_WINDOW`、会脱离 Activity token 并破坏 IME 目标） |

### ③ 为什么旧版拿得到焦点、新版报 `no window has focus`

1. `Dialog.dispatchKeyEvent()` 先查 `mOnKeyListener`（即 `setOnKeyListener` 那段），再走 `mWindow.superDispatchKeyEvent`。所以"按下就吞"能生效的前提只有一个：**按键被投递到 Dialog 的窗口**。
2. `ViewRootImpl` 在 `decorView.hasWindowFocus() == false` 时直接丢弃按键 —— 系统日志里的 `no window has focus` 就是这条。而窗口焦点是 WMS 在 `addView`（`show()`）那一刻依据 `WindowManager.LayoutParams` 决定的；`show()` 之后再 `clearFlags` 只会产生一次 `updateViewLayout`，**不保证重新授焦**。
3. 旧版：全屏非浮动窗口 + 属性在 `show()` 前定稿 + 一帧后真实子 View 抢焦点 → 窗口一次就拿到焦点，`n40` 必然收到按键。
4. 新版（本轮前）：Material **浮动**窗口，先以主题默认属性 `addView`，随后 `setLayout` / `clearFlags` / `setSoftInputMode` 连续改写触发多次 relayout；同时 `DecorView` 在被设为 `focusableInTouchMode` 之前 `requestFocus()` 直接返回 false，弹窗窗口内一个 View 都没持焦点。
5. 于是 Activity 窗口一直是 focused window：`HomeViewActivity.dispatchKeyEvent` 能带着 `isTmdbShowing=true` 继续打印，而弹窗的 `ViewRootImpl` 侧留下 `no window has focus` —— 与观察到的现象完全吻合。

### 本轮改动

| 项 | 改动 | 位置 |
|---|---|---|
| 属性定稿时机 | `configureWindow()` 从 `show()` 之后移到之前，窗口以最终属性 `addView` | `TmdbMatchViewDialog.show()` |
| 软键盘 | `STATE_ALWAYS_HIDDEN \| ADJUST_NOTHING`，输入法状态变化不再 relayout | `configureWindow()` |
| 居中 | 显式 `window.setGravity(Gravity.CENTER)`（照抄 Compose） | `configureWindow()` |
| 日志 1 | `DialogTest: show: flags=0x...`（`0x8` 必须没有、`0x20000` 应有） | `show()` |
| 日志 2 | `DialogTest: Dialog received: <key> action=<n>`（出现在 `Dialog` 级 `setOnKeyListener`） | `show()` |
| 日志 3 | `DialogTest: refocus: windowFocused=? focused=? keywordRequestFocus=? keywordHasFocus=?`（100ms 后） | `scheduleRefocus()` |

### 判定方法（下一轮真机一次运行即可定性）

- 只有 `show: flags`、没有 `Dialog received` → 按键仍投递在 Activity 窗口：`windowFocused=false` 时是 WMS/窗口层问题（下一步改主题为全屏非浮动，照抄 `DialogWindowTheme`）；`windowFocused=true` 但 `focused=null` 是视图层问题（让 `keyboard` 首键或根布局先持焦点）。
- `Dialog received` 出现但方向键仍漏到底层 → 是 `Dialog` 级 listener 的返回值问题，不再是焦点问题。
- 日志定位完成后应整体删除（`git show` 可随时取回），避免长期占用 logcat。

### 第 8 轮验证

`TmdbMatchViewDialog.java` 花括号 154/154、圆括号 622/622；`import android.view.Gravity` 与 `Log` 均被使用；`DialogTest` 日志 3 处；`configureWindow()` 调用点（idx 8452）在 `dialog.show()`（idx 8479）之前；未编译（按用户要求只出 diff）。
