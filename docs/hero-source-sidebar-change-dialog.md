# HERO-SOURCE · 换源弹窗 + 接口源侧边栏（照搬 ref_docs 逆向源码）

上一版凭手写 dp 自造布局，出现三个可见缺陷：侧边栏过宽、站点标签灰底连成一条、首页小房子颜色不对。
本版改为**逐项照搬 `ref_docs/` 逆向源码**；侧边栏在补齐 `dialog_site.xml` + `kz2.java` 后，
宽度 / 高度 / 间距 / 行高 / 勾选圈全部换成原版真值。

## 1. 目标与验收标准

1. 首页「小房子」确定键 → 拉起源码 SiteDialog 形态的接口源面板；返回键关闭，焦点回小房子。
2. 详情页「换源」→ 换源弹窗；关闭后焦点回「换源」而不是默认「播放」。
3. 换源弹窗内「小房子」→ 同一个接口源面板，层级在弹窗之上；关闭后焦点回该小房子。
4. 站点标签：焦点 = 白色圆角胶囊（`shape_site_item_focused`），常态 = 纯透明，不再出现灰底条。
5. 首页小房子：常态 = `white_10` 椭圆，焦点 = 红色椭圆。
6. 不新增依赖；不改首页/详情页 layout、样式与焦点链；组件内零网络、零数据层写入。

## 2. 视觉参数 → ref_docs 对应关系

| 落地文件 | 来源 | 说明 |
| --- | --- | --- |
| `layout/dialog_source.xml` | `ref_docs/dialog_source.xml` | 根 padding 18dp / paddingBottom 0、`shape_dialog_source`、头 32dp、siteLayout 32dp、homeBtn 26dp + 16dp 图标、3dp 红条 + 13sp 标题 + 16dp 内边距，逐字保留 |
| `layout/adapter_source_site.xml` | `ref_docs/adapter_v2_site.xml` | 14sp、`selector_source_site_item_text`、`selector_source_site_item_background` 原样 |
| `layout/adapter_source_result.xml` | `ref_docs/adapter_vod_rect.xml` | imageWrapper + 120×180 + `@style/Vod.Grid.Large` + 13sp `#ccffffff` + `@color/black_20` 占位 |
| `layout/dialog_source_sidebar.xml` | `ref_docs/dialog_site.xml` | 根横向 `padding 24dp`、`wrap_content` 高、`CustomRecyclerView`（weight 1、`wrap_content` 高、`app:maxHeight=480dp`） |
| `layout/adapter_source_sidebar_item.xml` | `ref_docs/adapter_site.xml` | 行高 40dp、18sp 文字（0dp + weight 1）、24dp 勾选圈（marginStart 12dp）、行底 1dp 渐隐线（24dp 边距 / 16dp 上边距） |
| 面板窗口尺寸 | `ref_docs/kz2.java` | 宽 = `((cols-1)*0.2+0.4)` × 屏宽（单列 0.4）；高 = `(min(max(1,ceil(n/cols)),4)*73 - 16)dp` 且设为 `maxHeight`；`GridLayoutManager(cols)` + 16dp 间距；`itemAnimator=null` / `hasFixedSize(true)` / `itemViewCacheSize=6` / `dim 0.3` |
| 弹窗窗口尺寸 | `ref_docs/f23.java` | 宽 `0.82 ×` 屏宽、高 `min(360dp, 0.65 ×` 屏高`)`；站点间距 12dp、结果间距 16dp、结果卡焦点缩放 1.08 |
| `drawable/shape_source_site_item_focused.xml` | `ref_docs/shape_site_item_focused.xml` | inset 8/4dp + 白底 + 28dp 圆角（焦点胶囊） |
| `drawable/shape_source_chip_round_{normal,activated}.xml` | `ref_docs/shape_chip_round_*.xml` | 只有 28dp 圆角 + 6dp 横向 padding，**无 solid**（上一版的灰底/红底就是这里多加的） |
| `color/selector_source_site_item_text.xml` | `ref_docs/selector_site_item_text.xml` | focused → black，否则 white |
| `drawable/selector_source_site_item_background.xml` | `ref_docs/selector_site_item_background.xml` | focused → 白胶囊，selected → activated，默认 → normal |
| `drawable/selector_check_circle.xml` + `shape_check_circle_*` | `ref_docs/selector_check_circle.xml` + `shape_check_circle_*` | 勾选圈四态 |
| `drawable/shape_home_logo_normal_white.xml` | `ref_docs/shape_home_logo_normal_white.xml` | **oval + `@color/white_10`**（上一版误做成 13dp 圆角矩形 + 纯白/半透明白） |
| `drawable/shape_home_logo_source_focused.xml` | `ref_docs/selector_home_logo_source.xml` 的 focused 分支（其 shape 未随包提供） | 红色椭圆 `#e50914`，与 normal 同为 oval |

## 3. 必需的替换（每处都在对应文件头部注释里写明）

1. **Leanback → RecyclerView**：`libs.leanback` 只在 `leanbackImplementation`，而这两个弹窗位于 `main`
   （`main` 还要编 `mobile` 变体），因此 `HorizontalGridView` → `RecyclerView`，
   `app:focusOutEnd/focusOutFront` 移除；`setHorizontalSpacing` 用等价 `ItemDecoration` 实现。
2. **`TypingDotsView` → `ProgressBar`**：工程内无 `com.fongmi.android.tv.ui.custom.TypingDotsView`。
3. **`adapter_vod_rect` 的 `tmdbTag` 删除**：依赖本工程不存在的 `@drawable/bg_tmdb_tag` 与
   `@string/home_tmdb_tag`，且原版默认 `gone`。
4. **`siteLayout` 由 `invisible` 改 `visible`**：原版该行由代码控制显隐，Hero 版要求常显站点栏。
5. **`dialog_site` 右侧 `@id/action` 图标列删除**：它默认 `gone`；其依赖的
   `selector_config_delete_background`、`selector_config_item_delete`、`ic_site_sort` 本工程不存在，
   `ic_site_search` / `ic_site_change` / `ic_site_select` / `ic_site_cancel` 只在 leanback 与 mobile 源集，
   `main` 布局引用会破坏另一个变体的编译。
6. **`adapter_site` 勾选圈替换**：`check_circle` + `selector_site_item_check` 本工程不存在，
   改用同源的 `selector_check_circle` + `AppCompatImageView`（24dp / marginStart 12dp 不变）。
7. **侧边栏面板背景**：原版由 MaterialAlertDialog 的 surface 提供，独立 Dialog 改用 `shape_dialog_source`。
8. **侧边栏不再有「全选 / 全不选」**：这一行属于 `dialog_search_site.xml`（搜索页面板），
   `dialog_site.xml` 没有。行点击 = 切换该条目勾选（Hero 多选语义）。
9. **`shape_home_logo_source_focused` 不在 ref_docs 内**，按红色椭圆实现。

## 4. 焦点契约

换源弹窗：

- 打开 → 当前站点标签；站点标签首个 LEFT → 小房子，其余在站点栏内滚动。
- 站点 ↓ → 结果卡片（`@id/recycler`）；结果 ↑ → 站点栏。
- 结果确定 → `onSourceSelected` 后关闭；返回键 → 关闭。
- 小房子确定 → 接口源面板；关闭 → `homeBtn.requestFocus()`。

侧边栏：

- 打开 → 列表第 1 条；确定键切换勾选；返回键关闭 → `onClosed` 交还焦点。
- 左右键在列表内被消费，不会把焦点甩出面板。

详情页：`showSourceDialog` 只作为「弹窗是否打开」的闸门，关闭时置 false，由既有 `LaunchedEffect`
把焦点送回 `pendingActionFocus`（换源按钮）。

## 5. 文件清单

新增（main 源集）：

- `layout/`：`dialog_source`、`dialog_source_sidebar`、`adapter_source_site`、`adapter_source_result`、
  `adapter_source_sidebar_item`
- `drawable/`：`shape_dialog_source`、`shape_tmdb_badge`、`selector_home_logo_source`、
  `shape_home_logo_source_focused`、`shape_home_logo_normal_white`、
  `shape_source_site_item_focused`、`shape_source_chip_round_activated|normal`、
  `selector_source_site_item_background`、`selector_check_circle`、
  `shape_check_circle_on|off|on_focused|off_focused`、`shape_line_tapered`
- `color/selector_source_site_item_text.xml`
- `anim/source_sidebar_in|out`、`values/styles.xml` 的 `SourceDialogTheme`（`SourceSidebarAnimation` 现已无引用，保留备用）
- `values/strings.xml`（+ zh-rCN / zh-rTW）：`play_change_result`、`play_change_empty`、
  `play_change_count`、`source_sidebar_select_all|none`（后者当前无引用，保留备用）

改写：

- `ui/hero/ChangeSourceDialog.kt`：独立 Dialog + `R.layout.dialog_source`，窗口/间距/缩放照搬 f23
- `ui/hero/SourceSidebar.kt`：独立 Dialog + `R.layout.dialog_source_sidebar`，窗口/高度/列数照搬 kz2
- `ui/hero/HeroSourceDemo.kt`：纯 Kotlin 假数据

入口（只加回调）：

- `HeroHomeActivity.kt`：小房子 → `SourceSidebar.show(...)`，关闭回焦小房子
- `HeroDetailActivity.kt`：「换源」→ `ChangeSourceDialog.show(...)`，关闭回焦「换源」

保留：`HeroCommonUi.kt` 整份（`HeroDialogShell` 仍供选集 / 演员弹窗）。

## 6. 验证

- `:app:assembleLeanbackArmeabi_v7aDebug` → BUILD SUCCESSFUL
- `:app:assembleMobileArmeabi_v7aDebug` → BUILD SUCCESSFUL
  （两个变体同时验证 `main` 未引入 Leanback；2026-11-02，AAPT 资源链接通过）

## 7. 已知限制 / 风险

1. 接口源面板不再有「全选 / 全不选」（`dialog_site.xml` 没有该行，见 §3.8）；
   若要恢复，需要按 `dialog_search_site.xml` 再加一行。
2. 面板形态为源码 SiteDialog 的居中面板（`Gravity.CENTER`、0.4×屏宽、最多 4 行），不再是贴右整高侧栏；
   这是 `dialog_site.xml` + `kz2.java` 的原版形态，滑入动画因此不再使用。
3. `shape_home_logo_source_focused` 不在 ref_docs 内，按红色椭圆实现；如与原版截图不符需再对一次。
4. 站点切换刷新结果、结果卡确定键写库、海报加载仍是 `TODO(数据层对接)`；勾选只存会话内。
5. `keywordRow` 按 ref 保留 id 且 `gone`；`progress` 为三态之一，默认隐藏。

## 8. 回滚

删除本文件 §5 列出的新增资源与两个 Dialog 的改写，把两个 Activity 的入口回调还原即可；
不触碰既有蓝色站点样式、`HeroDialogShell` 与其它页面，回滚面仅限 §5 清单。
