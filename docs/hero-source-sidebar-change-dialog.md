# HERO-SOURCE · 换源弹窗 + 接口源侧边栏（照搬 ref_docs 逆向源码）

上一版凭手写 dp 自造布局，出现三个可见缺陷：侧边栏过宽、站点标签灰底连成一条、首页小房子颜色不对。
本版改为**逐项照搬 `ref_docs/` 逆向源码**，只保留因编译约束与工程缺资源所必需的替换，并在文件内逐条注明。

## 1. 目标与验收标准

1. 首页「小房子」确定键 → 右侧滑出接口源侧边栏；返回键关闭，焦点回小房子。
2. 详情页「换源」→ 换源弹窗；关闭后焦点回「换源」而不是默认「播放」。
3. 换源弹窗内「小房子」→ 同一个侧边栏，层级在弹窗之上；关闭后焦点回该小房子。
4. 站点标签：焦点 = **红底 #E50914 + 白字胶囊**，常态 = 透明 + 白字。
   侧边栏行：焦点 = **白底 #FFFFFF + 黑字圆角胶囊**，常态 = 透明 + 白字。
   （两个组件各用一套 selector，互不影响，见 §3.7）
5. 首页小房子：常态 = `white_10` 椭圆，焦点 = 红色椭圆。
6. 不新增依赖；不改首页/详情页 layout、样式与焦点链；组件内零网络、零数据层写入。

## 2. 视觉参数 → ref_docs 对应关系

| 落地文件 | 来源 | 说明 |
| --- | --- | --- |
| `layout/dialog_source.xml` | `ref_docs/dialog_source.xml` | 根 padding 18dp / paddingBottom 0、`shape_dialog_source`、头 32dp、siteLayout 32dp、homeBtn 26dp + 16dp 图标、结果区 3dp 红条 + 13sp 标题 + 16dp 内边距，全部逐字保留 |
| `layout/adapter_source_site.xml` | `ref_docs/adapter_v2_site.xml` | 14sp + 白字；**根节点即标签本体**（合并原版内外两层，否则 adapter 的 isSelected 落在不画背景的外层）；背景用 `selector_source_site_chip_background`（focused → 红底胶囊） |
| `ui/hero/HeroPosterCard.kt`（**全局唯一卡片组件**） | `ref_docs/adapter_search.xml` + `ks2.java` + `adapter_vod_rect.xml` + 首页 `PosterCard` + 用户截图 | 尺寸在组件内部**写死**，外部无 setter：整卡 `120dp × 214dp`、海报 `120×180`（= 宽 × 1.5，`VodRectPresenter` L75）、剧名栏 `34dp`（`ks2` L108 `dp(34)`）。**四角全部 8dp 圆角，用原生 Drawable / Material 实现、不用画布裁剪**：整卡背景是 `GradientDrawable`（`cornerRadius = 8dp` + `black_20`），海报是 `ShapeableImageView` 直接套原版 style `@style/Vod.Grid.Large`（`cornerSize = 8dp` → **四角全 8dp**，图片显式 120×180），旧的 `PosterClipLayout` / `clipPath` / `clipToOutline` / `outlineProvider` 全部删除。焦点 = **2dp 纯红 #FFFF0000 + 8dp 圆角，只包 120×180 海报区，绝不包剧名**（同首页 `POSTER_FOCUS_BORDER = 2.dp` + `HeroAccentColor`，`drawFocusRing` 画在 `0..width / 0..180dp`）。对外只有四个内容字段：`title`（16sp + `padding 6dp` + 单行省略）、`imageUrl`、`topStartTag`（13sp + `shape_vod_site` = `bg_site` #F44336 α0.7 / mobile `colorErrorContainer` α0.8，左上+右上+右下 8dp，内边距 4dp；`maxWidth` 96dp → **110dp** 以容纳「日期 \| 来源」，仍 < 海报 120dp；日期显示为 `MM-dd`，超长走 `ellipsize=end`）、`bottomStartTag`（14sp + `shape_vod_remark` = `bg_remark` #177535 α0.7 / mobile `colorSecondaryContainer` α0.8，右 4dp，内边距 4dp）；剧名底板复用 `@color/black_20`（#33000000） |
| `layout/dialog_source_sidebar.xml` | `ref_docs/dialog_search_site.xml` | 全选/全不选 39dp（`@id/bulkBar`，单选模式代码隐藏）、12sp 文字 + 16dp 勾选圈、站点列表 |
| `layout/adapter_source_sidebar_item.xml` | `ref_docs/adapter_site.xml` | 40dp 行高、18sp 文字、24dp 勾选圈；**根节点即行本体**（同上，否则行选中态不生效） |
| `drawable/shape_source_site_item_focused.xml` | `ref_docs/shape_site_item_focused.xml` | inset 8/4dp + 白底 + 28dp 圆角（焦点胶囊） |
| `drawable/shape_source_chip_round_{normal,activated}.xml` | `ref_docs/shape_chip_round_*.xml` | 只有 28dp 圆角 + 6dp 横向 padding，**无 solid**（上一版的灰底/红底就是这里多加的） |
| `color/selector_source_site_item_text.xml` | `ref_docs/selector_site_item_text.xml` | focused→black，否则 white |
| `drawable/selector_source_site_item_background.xml` | `ref_docs/selector_site_item_background.xml` | focused→白胶囊，selected→activated，默认→normal |
| `drawable/selector_check_circle.xml` + `shape_check_circle_*` | `ref_docs/selector_check_circle.xml`（四态顺序原样） | 四态改为**正圆**（`android:shape="oval"`）：勾选 = 红底实心、未勾选 = 空心描边；焦点态单独加深/加白描边，保证在白底行上也可见 |
| `drawable/shape_home_logo_normal_white.xml` | `ref_docs/shape_home_logo_normal_white.xml` | **oval + `@color/white_10`**（上一版误做成 13dp 圆角矩形 + 纯白/半透明白） |
| `drawable/shape_home_logo_source_focused.xml` | `ref_docs/selector_home_logo_source.xml` 的 focused 分支（其 shape 未随包提供） | 红色椭圆 `#e50914`，与 `shape_home_logo_normal_white` 同为 oval |
| 窗口尺寸 / 间距 / 缩放 | `ref_docs/f23.java` | 宽 `0.82×`屏宽；高 = `max(min(360dp, 0.65×屏高), 126 + 218)`（126 = 弹窗固定内容，218 = 卡片 214 + recycler 顶部 4dp），并至少给屏幕留 16dp —— f23 原公式在 480dp 高的屏上只有 312dp，会裁掉卡片底部剧名；站点间距 12dp、结果间距 16dp；f23 里的结果卡焦点缩放 `1.08` **未采用**：整卡 214dp × 1.08 = 231dp 会超出结果区，反而把剧名切掉，焦点改用海报上的 2dp 纯红框（#FFFF0000，同首页 `POSTER_FOCUS_BORDER` + `HeroAccentColor`） |

## 3. 必需的替换（每处都在对应文件头部注释里写明）

1. **Leanback → RecyclerView**：`libs.leanback` 只在 `leanbackImplementation`，而这两个弹窗位于 `main`
   （`main` 还要编 `mobile` 变体），因此 `HorizontalGridView` → `RecyclerView`，
   `app:focusOutEnd/focusOutFront` 一并移除；`setHorizontalSpacing` 用等价 `ItemDecoration` 实现。
2. **`TypingDotsView` → `ProgressBar`**：工程内无 `com.fongmi.android.tv.ui.custom.TypingDotsView`。
3. **`adapter_vod_rect` 的 `tmdbTag` 删除**：依赖本工程不存在的 `@drawable/bg_tmdb_tag` 与
   `@string/home_tmdb_tag`，且原版默认 `gone`。
4. **`siteLayout` 由 `invisible` 改 `visible`**：原版该行由代码控制显隐，Hero 版要求常显站点栏。
5. **侧边栏缺资源**：`selector_search_action_background`、`check_circle`、`selector_site_item_check`
   在本工程都不存在，改用同源的白胶囊选择器与 `selector_check_circle`；
   原版面板背景由 BaseSheetDialog 的 sheet 容器提供，独立 Dialog 改用 `shape_dialog_source`。
6. **侧边栏宽度不再写死**：原版是贴底整宽 sheet（根 `match_parent`），Hero 要右侧栏；
   因此根布局与行宽改 `wrap_content`，窗口 `WRAP_CONTENT × MATCH_PARENT` + `Gravity.END`，
   面板宽度完全由内容（18sp 站点名 + 24dp 勾选圈）决定。
   代价：`shape_line_tapered` 的两条分隔线（`match_parent` 宽的 1dp View）会反过来把面板撑到整屏，
   故删除；这也是唯一牺牲的视觉元素。
7. **两套焦点配色分离（2026-11-03 需求）**：换源弹窗与侧边栏原来是同一个
   `selector_source_site_item_background`（focused → 白底黑字）。现拆成两套：
   - 弹窗：`selector_source_site_chip_background` + `shape_source_site_chip_focused`
     → focused = **红底 #E50914 + 白字**；文字固定 `@color/white`；
   - 侧边栏：继续用 `selector_source_site_item_background` + `color/selector_source_site_item_text`
     → focused = **白底 #FFFFFF + 黑字**（inset 8dp/4dp + 28dp 圆角，ref 原值）。
8. **初始焦点可能落在 RecyclerView 上导致「没有焦点提示」**：`onStart` 里
   `findViewHolderForAdapterPosition(...)` 在布局完成前返回 null 时会退化为 `list.requestFocus()`，
   焦点停在列表上，行背景的 focused 态永远不出现。
   现改为 `scrollToPosition` + 最多 6 次 / 50ms 的 `focusRow` 重试，并把
   `descendantFocusability` 设为 `FOCUS_AFTER_DESCENDANTS`。
9. **adapter 的 `itemView` 必须就是画背景的 View**：原版 `adapter_v2_site` / `adapter_site` 是
   「外层容器 + 内层 `@id/item`」两层，背景与 `focusable` 在内层，而 adapter 设置
   `itemView.isSelected` 落在外层 → 选中态永远不绘制。现把 id / background / focusable 合并到根节点。

## 3.1 单 / 多选模式（`SourceSidebarMode`）

| 模式 | 入口 | UI | 确定键 |
| --- | --- | --- | --- |
| `Single` | 首页小房子 | 隐藏勾选圈、隐藏「全选 / 全不选」整行（`@id/bulkBar` GONE） | `onSiteSelected(index)` 后关闭 |
| `Multi` | 换源弹窗内小房子 | 显示勾选圈（实心 / 空心）与「全选 / 全不选」 | 切换该条勾选，`onCheckedChanged` 返回快照，不关闭 |

多选结果联动（Mock）：`ChangeSourceDialog.applySiteFilter(flags)` 把勾选的
`HeroSourceEntry.site` 与 `HeroSourceResult.site` 求交集，`visibleResults` 随之刷新，
顶部统计在过滤后改为实际命中数；一条都没勾时走空态（`@id/empty`）。

## 4. 焦点契约

换源弹窗：

- 打开 → 当前站点标签（红底白字）；站点标签首个 LEFT → 小房子，其余在站点栏内滚动。
- 站点 ↓ → 结果卡片（`@id/recycler` 的 `nextFocusDown`）；结果 ↑ → 站点栏。
- 结果确定 → `onSourceSelected` 后关闭；返回键 → 关闭。
- 小房子确定 → `SourceSidebar`（Multi，后创建的窗口在弹窗之上）；关闭 → `homeBtn.requestFocus()`。

### 3.2 底色与宽度（2026-11-03 微调）

- **弹窗底色统一**：换源弹窗改用 `drawable/shape_dialog_source_shell.xml`
  （`#E6000000` + 16dp 圆角），与选集弹窗 `HeroDialogShell`
  （`HeroCommonUi.kt`：`DIALOG_SHELL_BACKGROUND = Color(0xE6000000)`、`RoundedCornerShape(16.dp)`、18dp 内边距）完全一致；
  原来的 `shape_dialog_source`（`#f214171f` + 12dp 圆角）偏蓝灰，已不再用于换源弹窗。
- **侧边栏宽度不塌缩**：单选模式隐藏勾选圈改用 `View.INVISIBLE`（保留 24dp 圈 + 12dp 间距的占位），
  不用 `GONE`，保证单选 / 多选两种模式下面板宽度与白底焦点块宽度完全一致。

### 3.3 全局唯一卡片组件（2026-11-03 铁律）

`ui/hero/HeroPosterCard.kt` 顶部写死：

```
// 【全局 UI 铁律】历史页、搜索页、换源弹窗必须复用此组件。尺寸 120x180，四角 8dp 圆角，严禁 AI 私自发明新尺寸！
```

- **尺寸唯一**：`CARD_WIDTH_DP = 120`、`POSTER_HEIGHT_DP = 180`、`TITLE_HEIGHT_DP = 34`、
  `CARD_HEIGHT_DP = 214`；`onMeasure` 用 `EXACTLY` 强制 120×214 覆盖任何外部 `MeasureSpec`，
  因此不会被 `RecyclerView` / `weight` / `wrap_content` 拉伸或压扁。
  `ChangeSourceDialog` 的窗口高度常量直接引用 `HeroPosterCard.CARD_HEIGHT_DP`，不在弹窗里重复 214。
- **圆角唯一（原生 Drawable / Material 渲染，不用画布裁剪）**：`CORNER_DP = 8`，与首页
  `HeroHomeActivity.kt` `POSTER_SHAPE = RoundedCornerShape(8.dp)`（L1129）一致。
  实机多轮仍显示直角后判定：电视端硬件加速对 `canvas.clipPath` / `clipToOutline` 不可靠，
  因此**彻底废弃画布裁剪**，改由 View 自身绘制圆角：
  1. 整卡背景 = `GradientDrawable` + `cornerRadius = dp(8)` + `black_20`，`init` 里 `background = cardBackground`，
     四个角一次圆完，纯原生 Drawable，任何盒子都生效；
  2. 海报 = Material `ShapeableImageView`，**直接套原版 style `@style/Vod.Grid.Large`**
     （`res/values/styles.xml` L12-14：`cornerSize = 8dp`，父样式 `Vod.Grid` / `Vod` 提供
     `cornerFamily = rounded` → **四角全 8dp**），等价于 `adapter_history_poster.xml` L27 的
     `app:shapeAppearanceOverlay="@style/Vod.Grid.Large"`；代码用
     `ShapeAppearanceModel.builder(context, R.style.Vod_Grid_Large, 0)`，不手写 8dp，避免与样式表漂移；
     图片 LayoutParams 显式 `120dp × 180dp`（照抄原版 `adapter_history_poster.xml` L21-24），
     图片**自身不套任何背景 / 裁剪**；
  3. 海报区容器 `posterFrame` 退回普通 `FrameLayout`（只承载图片、占位首字与两个标签），
     `PosterClipLayout` 私有类已删除，`draw()` 里只剩 `super.draw(canvas)` + `drawFocusRing(canvas)`，
     全文件**不再出现** `clipPath` / `Path` / `Outline` / `ViewOutlineProvider` / `clipToOutline`；
  4. 覆盖在图片上的标签不得遮挡圆角：左上 `topTag` 贴边（`shape_vod_site` 自身左上 8dp 圆角与海报重合），
     左下 `bottomTag` 的直角背景 `shape_vod_remark` 加 `bottomMargin = 8dp`，不压住海报左下圆角。
  底层 `@style/Vod.Grid`（只圆上方两角，用于原版搜索卡）**不再参与**本组件。
- **焦点唯一（参数与首页完全一致）**：`FOCUS_STROKE_DP = 2f`、`FOCUS_COLOR = 0xFFFF0000`，
  对应首页 `POSTER_FOCUS_BORDER = 2.dp`（L1126）+ `HeroAccentColor = Color(0xFFFF0000)`（L1135）；
  不再使用 `@color/red`（#E50914）。8dp 圆角描边，矩形上下界 `0..120dp / 0..180dp`，只包海报、绝不包剧名。
- **内容唯一**：只有 `title` / `imageUrl` / `topStartTag` / `bottomStartTag` 四个字段，
  尺寸、圆角、描边没有任何 setter。
- **minSdk 24 兼容**：`View.setDefaultFocusHighlightEnabled` 是 API 26 方法，组件里已按
  `Build.VERSION.SDK_INT >= O` 判版本，避免 API 24/25 设备 `NoSuchMethodError`。
- **待办**：历史页（`HeroHistoryActivity`）、搜索页的卡片后续也换成该组件；本轮只落地换源弹窗。

侧边栏：

- 打开 → 单选落在当前接口源，多选落在第 1 条（白底黑字可见）。
- 单选：确定键 = 切换接口源并关闭；多选：确定键 = 勾选 / 取消勾选，不关闭。
- 多选：顶部「全选/全不选」↓ → 列表；列表第 1 条 ↑ → 「全选」。
- 左右键在列表内被消费，不掉出侧边栏；返回键关闭 → `onClosed` 交还焦点。

详情页：`showSourceDialog` 只作为「弹窗是否打开」的闸门，关闭时置 false，由既有 `LaunchedEffect`
把焦点送回 `pendingActionFocus`（换源按钮）。

## 5. 文件清单

新增（main 源集）：
- `ui/hero/HeroPosterCard.kt`：**全局唯一海报卡片组件**（历史页 / 搜索页 / 换源弹窗必须复用）。
  文件顶部写死铁律注释；尺寸、圆角、焦点描边全部私有常量，无对外 setter。
  组件内部用代码绘制，不再依赖任何 `adapter_source_result` 布局。
- `layout/`：`dialog_source`、`dialog_source_sidebar`、`adapter_source_site`、
  `adapter_source_sidebar_item`
- `drawable/`：`shape_dialog_source`、`shape_tmdb_badge`、`selector_home_logo_source`、
  `shape_home_logo_source_focused`、`shape_home_logo_normal_white`、
  `shape_source_site_item_focused`、`shape_source_chip_round_activated|normal`、
  `selector_source_site_item_background`、`selector_source_site_chip_background`、
  `shape_source_site_chip_focused`、`selector_check_circle`、`shape_check_circle_on|off|
  on_focused|off_focused`、`shape_line_tapered`（当前无引用，保留备用）
- `color/selector_source_site_item_text.xml`
- `anim/source_sidebar_in|out`、`values/styles.xml` 的 `SourceDialogTheme` / `SourceSidebarAnimation`
- `values/strings.xml`（+ zh-rCN / zh-rTW）：`play_change_result`、`play_change_empty`、
  `play_change_count`、`source_sidebar_select_all|none`、`source_sidebar_switched`

已删除：
- `layout/adapter_source_result.xml`：卡片改为 `HeroPosterCard` 后不再被任何文件引用（纯死资源）。
  随之失去引用的 `shape_source_vod_name` / `selector_source_card_foreground` /
  `shape_source_card_focused` 三个 main 等价 drawable **暂时保留**（无引用但无副作用，
  由用户决定是否一并删除）。

改写：
- `ui/hero/ChangeSourceDialog.kt`：独立 Dialog + `R.layout.dialog_source`，窗口尺寸/间距照搬 f23，
  站点标签红底白字，多选侧边栏勾选即时过滤结果；结果卡**只调用 `HeroPosterCard`**
  （`title` / `imageUrl` / `topStartTag` = `MM-dd | 来源` / `bottomStartTag` = 备注），
  弹窗里不再 inflate 卡片布局、也不再出现任何卡片宽高数值
- `ui/hero/SourceSidebar.kt`：独立 Dialog + `R.layout.dialog_source_sidebar`，宽度由内容决定，
  `SourceSidebarMode.Single|Multi` 两种模式
- `ui/hero/HeroSourceDemo.kt`：纯 Kotlin 假数据（`HeroSourceEntry` 增加 `site` 字段供过滤）

入口（只加回调）：
- `HeroHomeActivity.kt`：小房子 → `SourceSidebar.show(mode = Single, onSiteSelected = ...)`，关闭回焦小房子
- `HeroDetailActivity.kt`：「换源」→ `ChangeSourceDialog.show(...)`，关闭回焦「换源」

保留：`HeroCommonUi.kt` 整份（`HeroDialogShell` 仍供选集 / 演员弹窗）。

## 6. 验证

- `:app:assembleLeanbackArmeabi_v7aDebug` → BUILD SUCCESSFUL
- `:app:assembleMobileArmeabi_v7aDebug` → BUILD SUCCESSFUL
  （两个变体同时验证 `main` 未引入 Leanback；2026-11-02，AAPT 资源链接通过）

## 7. 已知限制 / 风险

1. 侧边栏删除两条 `shape_line_tapered` 分隔线（原因见 §3.6），这是与 ref 的唯一视觉差异。
2. `shape_home_logo_source_focused` 不在 ref_docs 内，按红色椭圆实现；若原版另有取值需要再对一次截图。
3. 站点切换刷新结果、结果卡确定键写库、海报加载仍是 `TODO(数据层对接)`；侧边栏勾选只存会话内。
4. `keywordRow` 按 ref 保留 id 且 `gone`；`progress` 为三态之一，默认隐藏。
5. 结果卡暂无海报地址，`HeroPosterCard` 走渐变占位 + 剧名首字；接入真实数据时只填 `imageUrl`。

## 8. 回滚

删除本文件 §5 列出的新增资源与两个 Dialog 的改写，把两个 Activity 的入口回调还原即可；
不触碰既有蓝色站点样式、`HeroDialogShell` 与其它页面，回滚面仅限 §5 清单。
