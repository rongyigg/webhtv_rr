# HERO-SOURCE · 换源弹窗 + 全局接口源侧边栏（View 体系重写）

本阶段把上一版 Compose 实现整体废弃，改为 View 体系：
`ChangeSourceDialog` 与 `SourceSidebar` 都是独立 `Dialog`，直接 inflate 逆向布局，
视觉参数全部来自 `ref_docs/`，不再自造外壳、圆角、渐变占位或放大动画。

## 1. 目标与验收标准

1. 首页「小房子」确定键 → 右侧滑出全局接口源侧边栏；返回键关闭，焦点回小房子。
2. 详情页「换源」→ 换源弹窗；关闭后焦点回「换源」而不是默认「播放」。
3. 换源弹窗内「小房子」→ 同一个侧边栏，层级盖在换源弹窗之上；关闭后焦点回该小房子。
4. 侧边栏：宽 320dp、靠右、铺满高、深灰底；顶部全选/全不选；列表 40dp 行高、18sp、勾选圈四态。
5. 换源弹窗：12dp 圆角 `#f214171f`；红底徽章 4dp 圆角 `#e50914`；站点标签 39dp/14sp；
   结果区横向卡片；小房子 26dp + 16dp 图标。
6. 不新增依赖；不改首页/详情页 layout、样式、焦点链；组件内零网络、零数据层写入。

## 2. 视觉参数 → 逆向来源对照

| 落地值 | 逆向来源 |
| --- | --- |
| 弹窗 12dp 圆角 + `#f214171f`，内边距 18dp / paddingBottom 0 | `ref_docs/dialog_source.xml` L4-6 + 本任务给定的 `shape_dialog_source` |
| 徽章 4dp 圆角 `#e50914`、11sp 粗体、高 24dp | `ref_docs/dialog_source.xml` L19-30 + `shape_tmdb_badge` |
| 统计文案 11sp `#59ffffff` | `dialog_source.xml` L43-49 |
| 站点栏高 32dp、小房子 26dp / 图标 16dp | `dialog_source.xml` L51-78 |
| 站点标签 39dp / 14sp、选中与焦点用胶囊底 + 文字变色 | `ref_docs/adapter_v2_site.xml` L14-25；`selector_site_item_background` / `selector_site_item_text` 的 source 化副本 |
| 结果卡片 120×174 + 13sp `#ccffffff` 标题 | `ref_docs/adapter_vod_rect.xml`（f23 结果 Presenter 的 rect 版式） |
| 分隔线 1dp 渐变 | `ref_docs/shape_line_tapered.xml` |
| 侧边栏行高 40dp / 18sp / 勾选圈 24dp | `ref_docs/adapter_site.xml` L14-39 |
| 勾选圈四态（focused / selected） | `ref_docs/selector_check_circle.xml` + `shape_check_circle_*` |
| 侧边栏顶部按钮 12sp + 16dp 勾选圈 | `ref_docs/dialog_search_site.xml` L10-71 |

## 3. 命名冲突处理（不动既有蓝色样式）

工程 `app/src/leanback/res/drawable/` 已存在 `shape_site_item_focused`（#2F6FED）、
`shape_chip_round_activated`、`shape_chip_round_normal`，供既有站点管理界面使用。
因此换源体系一律加 `source` 前缀，现有资源零改动：

- `shape_source_site_item_focused`（#E50914 / 6dp）
- `shape_source_chip_round_activated`（#33FFFFFF / 6dp）
- `shape_source_chip_round_normal`（#1AFFFFFF / 6dp）
- `selector_source_site_item_background` / `selector_source_site_item_text`
- `selector_source_result` / `shape_source_result_focused`（结果卡焦点红框 #FF0000）

## 4. 架构决策：为什么不用 Leanback

`libs.leanback` 只在 `leanbackImplementation` 里，而 Hero 首页/详情页位于 `main` 源集，
`main` 还必须能编译 `mobile` 变体。因此：

- `ChangeSourceDialog` 的 `siteRecycler` / `recycler` / `keywordRow` 统一改为
  `RecyclerView` + 横向 `LinearLayoutManager`；
- 布局里不再出现 `HorizontalGridView`、`TypingDotsView`（改用 `ProgressBar`）；
- 焦点链用 `nextFocusUp/Down/Left/Right` 属性 + `nextFocus*Id` 代码设置实现，
  不依赖 Leanback 的 `focusOutEnd/focusOutFront`。

验证：`:app:compileLeanbackArmeabi_v7aDebugKotlin` 与 `:app:compileMobileArmeabi_v7aDebugKotlin` 均通过。

## 5. 焦点契约

换源弹窗：

- 打开 → 当前站点标签；站点标签首个 LEFT → 小房子；RIGHT 在站点内滚动。
- 站点 ↓ → 结果卡片（item 的 `nextFocusDown=@id/recycler`）。
- 结果 ↑ → 站点栏（item 的 `nextFocusUp=@id/siteRecycler`）。
- 结果确定 → `onSourceSelected` 后关闭；返回键 → 关闭。
- 小房子确定 → 拉起 `SourceSidebar`（后创建的 Dialog 窗口天然在弹窗之上）；
  侧边栏关闭 → `homeBtn.post { requestFocus() }`。

侧边栏：

- 打开 → 列表第 1 条；顶部按钮 ↓ → 列表；列表第 1 条 ↑ → 「全选」。
- 左右键在列表内被消费（`setOnKeyListener`），不会掉出侧边栏。
- 确定键切换勾选；顶部按钮批量全选/全不选；返回键关闭 → `onClosed` 交还焦点。

详情页：`showSourceDialog` 只作为「弹窗是否打开」的闸门，
关闭时置 false，由既有 `LaunchedEffect` 把焦点送回 `pendingActionFocus`（换源按钮）。

## 6. 文件清单

新增资源：

- `res/drawable/`：`shape_dialog_source`、`shape_tmdb_badge`、`selector_home_logo_source`、
  `shape_home_logo_source_focused`、`shape_home_logo_normal_white`、
  `shape_source_site_item_focused`、`shape_source_chip_round_activated|normal`、
  `selector_source_site_item_background|text`、`selector_check_circle`、
  `shape_check_circle_on|off|on_focused|off_focused`、`shape_line_tapered`、
  `selector_source_result`、`shape_source_result_focused`
- `res/layout/`：`dialog_source`、`dialog_source_sidebar`、
  `adapter_source_site`、`adapter_source_result`、`adapter_source_sidebar_item`
- `res/anim/`：`source_sidebar_in`、`source_sidebar_out`
- `values/colors.xml`：`source_sidebar_background`（#1E1E1E，新增）
- `values/styles.xml`：`SourceDialogTheme`、`SourceSidebarAnimation`
- `values/strings.xml`（+ zh-rCN / zh-rTW）：`play_change_result`、`play_change_empty`、
  `play_change_count`、`source_sidebar_select_all`、`source_sidebar_select_none`
  （`play_change` 三语已存在，未重复添加）

改写：

- `ui/hero/ChangeSourceDialog.kt`：View Dialog，inflate `R.layout.dialog_source`
- `ui/hero/SourceSidebar.kt`：View Dialog，inflate `R.layout.dialog_source_sidebar`
- `ui/hero/HeroSourceDemo.kt`：纯 Kotlin 假数据（去掉 Compose Color）

入口（只加回调，不动 layout / 焦点链）：

- `HeroHomeActivity.kt`：小房子 `onHomeConfirm` 调 `SourceSidebar.show(...)`；
  删除 Compose 侧边栏状态与覆盖层，初始焦点仍落小房子。
- `HeroDetailActivity.kt`：「换源」分支调 `ChangeSourceDialog.show(...)`；删除 Compose 弹窗块。

保留：

- `HeroCommonUi.kt` 整份保留（`HeroDialogShell` 仍被选集/演员弹窗使用，删除会违反「选集页零改动」）。
- `HeroHomeLogoButton` 仍是首页 Compose 组件；换源弹窗内的小房子是 `dialog_source.xml` 里独立实现的 View。

## 7. 验证

- `:app:compileLeanbackArmeabi_v7aDebugKotlin` → BUILD SUCCESSFUL（2026-11-02）
- `:app:compileMobileArmeabi_v7aDebugKotlin` → BUILD SUCCESSFUL（同日，验证 main 源集未引入 Leanback 依赖）

## 8. 已知限制 / 风险

1. `shape_home_logo_normal_white` 按指令写成纯白实心：`ic_home_logo` 本身是白色，
   常态下图标与圆底同色，视觉上只看到白色圆点。若需要图标可见，把该 shape 的
   `solid` 改成 `#1AFFFFFF`（逆向原值 `white_10`）即可，其余不动。
2. 顶部「全选/全不选」的焦点态用 source 红色胶囊（`selector_source_site_item_background`），
   因为逆向的 `overlay_focus` 布局不在本工程资源里，无法直接复用。
3. 站点切换后的结果刷新、结果卡确定键的写库仍是 `TODO(数据层对接)`；
   侧边栏勾选状态只保存在本次会话。
4. 结果卡片暂无海报地址，只显示「站点 · 日期」；接入真实数据后补图片加载。
5. `keywordRow` 按定稿保持 `gone`，只保留 id；`progress` 默认隐藏，三态结构完整保留。

## 9. 回滚

删除本次新增的 source 系列 drawable/layout/anim 与
`ChangeSourceDialog` / `SourceSidebar` / `HeroSourceDemo` 的改写，
把两个 Activity 的入口回调还原为上一版 Compose 调用即可；
不改动既有蓝色站点样式与其他页面，回滚面仅限本文件清单。
