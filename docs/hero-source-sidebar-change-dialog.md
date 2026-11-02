# HERO-SOURCE — 换源弹窗 + 全局接口源侧边栏（假数据阶段）

任务 ID：`hero-source-sidebar-change-dialog`
日期：2026-11-02
分支：`main`
基线：`ce2e73e50`（选集弹窗历史集参数注入）

## 1. 目标与验收标准

用 Compose 完成 HeroUI 的最后两块 UI：首页可拉起的「接口源侧边栏」与详情页「换源」弹窗。
本阶段只做 UI、复用关系与焦点流转，全部使用假数据；**不触碰** `PlayerManager` / `VideoActivity`
/SiteApi / 真实站点配置等既有链路。

| # | 验收项 | 状态 |
|---|---|---|
| 1 | 全局 `SourceSidebar`：右侧滑出、靠右、宽 320dp、高度铺满、背景 `#1E1E1E` | 已实现 |
| 2 | 侧边栏顶部「全选 / 全不选」横向按钮，焦点态红底白字 | 已实现 |
| 3 | 侧边栏 `LazyColumn`：项高 40dp，左图标 + 名称（如「豆瓣 \| 海报」），右侧圆形勾选框，14sp | 已实现 |
| 4 | 侧边栏确定键切换勾选；返回键关闭；宿主恢复焦点 | 已实现 |
| 5 | 首页小房子确定键 → 拉起 `SourceSidebar`（替换原占位 Dialog） | 已实现 |
| 6 | 换源弹窗外壳复用选集弹窗：`#E6000000` + 16dp 圆角 + 18dp 内边距 | 已实现 |
| 7 | 换源弹窗顶部：红底「换源」徽章 + 灰色「424个条目」 | 已实现 |
| 8 | 站点栏：26dp 小房子 + 横向滚动站点标签（39dp / 14sp） | 已实现 |
| 9 | 结果区：`LazyRow` 横向卡片，红绿渐变占位 + 日期 + 来源名 + 剧名 | 已实现 |
| 10 | 换源弹窗默认焦点在站点；↓ 进结果卡片；↑ 回站点 | 已实现 |
| 11 | 换源弹窗内小房子确定键 → `SourceSidebar` 盖在弹窗之上 | 已实现 |
| 12 | 详情页「换源」按钮打开弹窗；返回关闭后焦点回「换源」 | 已实现 |
| 13 | 小房子抽成全局公共 Compose 组件，首页与换源弹窗共用同一份实现 | 已实现 |
| 14 | Kotlin 编译通过 | `:app:compileLeanbackArmeabi_v7aDebugKotlin` BUILD SUCCESSFUL |

## 2. 证据来源

| 来源 | 用途 |
|---|---|
| `ref_docs/dialog_source.xml` | 换源弹窗外壳（18dp 内边距）、顶部徽章、`homeBtn` 26dp + 16dp 图标、`siteRecycler`、结果区结构 |
| `ref_docs/adapter_v2_site.xml` | 站点标签高度 39dp、14sp、居中单行 |
| `ref_docs/adapter_vod_rect.xml` | 结果卡片圆角 / 标题字号参考（按换源场景改成横向卡片） |
| `HeroDetailActivity.kt`（本次重构前） | `HeroDialogShell`、红竖条标题、`HeroAccentColor`、`ic_home_logo` 焦点态样式 |
| `HeroHomeActivity.kt`（本次重构前） | 首页小房子 32dp / 28dp / 20dp、红底 + 2dp 白圈 + 1.1 倍放大 |
| 需求原文（本消息） | 尺寸、焦点契约与复用关系的唯一依据；与 XML 冲突处以需求为准 |

## 3. 复用关系（本次的核心约束）

```
HeroCommonUi.kt
├ HeroAccentColor                      ← 首页 / 详情页 / 弹窗 / 侧边栏统一主色
├ HeroDialogShell                      ← 选集弹窗 + 演员弹窗 + 换源弹窗共用外壳
├ HeroSectionTitle                     ← 红竖条区块标题（选集 / 换源结果共用）
└ HeroHomeLogoButton                   ← 首页小房子 + 换源弹窗小房子共用
        ├ 首页：32dp 容器 / 28dp 圆 / 20dp 图标 / ↓ 进 Tab
        └ 换源弹窗：26dp 容器 / 26dp 圆 / 16dp 图标 / ↓ 进结果 / 确定拉起侧边栏
```

`HeroHomeLogoButton` 的两种尺寸只是参数不同，视觉状态完全一致：
透明底 + 纯白 `ic_home_logo`；焦点态 `HeroAccentColor` 红底 + 2dp 纯白圆环 + 1.1 倍放大。
`HeroDetailActivity` 只是删除了私有实现、改为导入公共组件，选集弹窗与演员弹窗的视觉不变。

## 4. 接口源侧边栏（SourceSidebar）

```
全屏透明 Dialog（默认遮罩压到 25%）
└ Box(fillMaxSize, 右侧对齐)
  └ AnimatedVisibility：从右侧滑入 / 滑出（220ms）
    └ Column 320dp × 铺满 · #1E1E1E · 四周 1dp #2E2E2E 描边
      ├ Row：全选 | 全不选（34dp / 14sp，焦点红底白字）
      ├ 12dp
      ├ 1dp 分隔线
      ├ 12dp
      ├ LazyColumn（weight 1）
      │   └ 40dp 项：20dp 单字图标 + 8dp + 名称 14sp + 18dp 圆形勾选框
      └ 「返回键关闭」11sp 40% 白
```

| 元素 | 取值 |
|---|---|
| 面板宽度 | 320dp，`Alignment.CenterEnd` |
| 面板背景 | `#1E1E1E` |
| 顶部按钮 | 34dp 高、14sp、常态 `#2A2A2A`、焦点 `HeroAccentColor` |
| 列表项 | 40dp 高、圆角 6dp、常态 `#2A2A2A`、焦点红底 |
| 站点名称 | 14sp 白色，如「豆瓣 \| 海报」 |
| 勾选框 | 18dp 圆形；空心 = 2dp 半透明白描边；实心 = 红底白勾；焦点行 = 白底红勾 |
| 勾选数据 | 默认全选；确定键逐条切换；全选 / 全不选批量设置 |

焦点契约：

| 位置 | 按键 | 行为 |
|---|---|---|
| 打开 | — | 焦点落第 1 条列表项 |
| 列表首条 | ↑ | 回「全选 / 全不选」按钮行（记住上次那颗） |
| 按钮行 | ←/→ | 在「全选 / 全不选」间移动 |
| 按钮行 | ↓ | 回列表（记住离开时的条目） |
| 列表项 | ↑/↓ | 逐条移动；末条 ↓ 消费 |
| 列表项 | ←/→ | 消费（单列） |
| 任意 | 确定 | 切换勾选 / 执行全选或全不选 |
| 任意 | 返回 | 滑出动画结束后关闭，宿主恢复焦点 |

侧边栏实现为独立 `Dialog` 窗口（`usePlatformDefaultWidth = false`，系统遮罩 25%）。
换源弹窗内再次拉起时，新窗口自然叠在换源弹窗之上；返回键先关闭侧边栏，不会误关下层弹窗。

## 5. 换源弹窗（ChangeSourceDialog）

```
遮罩 0x99000000
└ HeroDialogShell：82% 宽 · #E6000000 · 16dp 圆角 · 18dp 内边距
  ├ 顶部栏 32dp：[换源] 24dp 红底徽章 11sp + 右侧「424个条目」11sp 59% 白
  ├ 8dp
  ├ 站点栏 39dp：小房子 26dp + 6dp + LazyRow 站点标签（39dp / 14sp）
  ├ 12dp
  ├ ▍换源结果（13sp 加粗，不可聚焦）
  ├ 8dp
  └ LazyRow：190dp × 118dp 结果卡片（红绿渐变 + 日期 + 来源名 + 剧名）
```

| 元素 | 取值 |
|---|---|
| 外壳 | 复用 `HeroDialogShell`（与选集弹窗同一实现） |
| 宽度 | 屏幕 82% |
| 徽章 | 24dp 高、4dp 圆角、`HeroAccentColor` 底、11sp 粗体白字 |
| 统计文字 | 11sp、`#59FFFFFF`、文案 `424个条目` |
| 小房子 | `HeroHomeLogoButton`，26dp 容器 / 26dp 圆 / 16dp 图标 |
| 站点标签 | 39dp 高、14sp、常态 `#1AFFFFFF`、当前站点 `#33FFFFFF`、焦点红底白字 |
| 结果卡片 | 190dp × 118dp、8dp 圆角、红绿线性渐变、底部黑色压暗 |
| 卡片信息 | 左上日期 10sp 胶囊；底部来源名 11sp + 剧名 13sp 粗体（最多 2 行） |
| 卡片焦点 | 2dp `HeroAccentColor` 边框；当前选中的卡片 2dp 白框 |

焦点契约：

| 位置 | 按键 | 行为 |
|---|---|---|
| 打开 | — | 焦点落第 1 个站点标签 |
| 站点标签 | ←/→ | 相邻标签移动；第 1 个继续 ← 落到小房子；末尾 → 消费 |
| 小房子 | → | 回当前站点标签（没有则第 1 个） |
| 小房子 | ← / ↑ | 消费 |
| 站点栏任意项 | ↓ | 进结果卡片（记住上次停留的卡片） |
| 结果卡片 | ←/→ | 横向移动，两端消费 |
| 结果卡片 | ↑ | 回站点栏；上次在小房子则回小房子，否则回刚才的标签 |
| 结果卡片 | ↓ | 消费 |
| 小房子 | 确定 | 拉起 `SourceSidebar`（盖在本弹窗之上） |
| 任意 | 返回 | 关闭换源弹窗；详情页焦点回「换源」按钮 |

结果卡片确定键当前只做「选中」高亮（白框），不触发真实换源；接真实数据后在这里回调
`onSourceSelected(result)` 即可。

## 6. 假数据

`HeroSourceDemo.kt`（新增，替换数据源时只改此文件）：

| 数据 | 内容 |
|---|---|
| `HERO_SOURCE_SITES` | 豆瓣 / TMDB / 爱奇艺 / 腾讯视频 / 优酷 / 哔哩哔哩 / 芒果TV / 1905电影网 |
| `HERO_SOURCE_ENTRIES` | 每个站点 ×（海报 / 剧照）= 16 条侧边栏条目，显示为「豆瓣 \| 海报」 |
| `HERO_SOURCE_RESULTS` | 10 张结果卡（日期 + 来源名 + 两组红绿渐变色） |
| `HERO_SOURCE_RESULT_TOTAL` | 424，用于顶部统计文案 |

剧名不写进假数据：由 `ChangeSourceDialog(detailTitle = detail.title)` 注入，
与详情页标题保持一致。

## 7. 变更文件

| 文件 | 类型 |
|---|---|
| `app/src/main/java/com/fongmi/android/tv/ui/hero/HeroCommonUi.kt` | 新增：全局主色、弹窗外壳、区块标题、公共小房子按钮 |
| `app/src/main/java/com/fongmi/android/tv/ui/hero/HeroSourceDemo.kt` | 新增：接口源 / 换源结果假数据 |
| `app/src/main/java/com/fongmi/android/tv/ui/hero/SourceSidebar.kt` | 新增：全局接口源侧边栏 |
| `app/src/main/java/com/fongmi/android/tv/ui/hero/ChangeSourceDialog.kt` | 新增：换源弹窗 |
| `HeroDetailActivity.kt` | 删除已迁出的私有外壳 / 标题 / 主色；`换源` 按钮打开弹窗并恢复焦点 |
| `HeroHomeActivity.kt` | 小房子改用公共组件；占位 Dialog 替换为 `SourceSidebar` |

**未改动**：`VideoActivity`、`PlayerManager`、`SiteApi`、播放链路与真实站点配置。

## 8. 验证

```bash
./gradlew :app:compileLeanbackArmeabi_v7aDebugKotlin
# → BUILD SUCCESSFUL（2026-11-02）
```

实机检查清单：

1. 首页焦点在小房子 → 确定 → 右侧 320dp 侧边栏滑出，焦点在第 1 条「豆瓣 | 海报」。
2. 侧边栏 ↓ 逐条移动；确定切换勾选（红底白勾）；↑ 回「全选 / 全不选」；确定可批量勾选 / 清空。
3. 返回键 → 侧边栏滑出关闭 → 焦点回首页小房子。
4. 进入详情页 → 焦点移到「换源」→ 确定 → 换源弹窗；焦点在第 1 个站点标签。
5. 站点 ←/→ 移动，第 1 个再 ← 到小房子（红底白圈）；小房子 → 回站点。
6. 站点 ↓ → 结果卡片；卡片 ←/→ 滚动；↑ 回刚才的站点（或小房子）。
7. 弹窗内小房子确定 → 侧边栏盖在换源弹窗之上；返回先关侧边栏，焦点回小房子；
   再返回关换源弹窗，焦点回详情页「换源」按钮。
8. 打开选集弹窗 / 演员弹窗，确认迁出外壳与标题后视觉、焦点无变化。

## 9. 已知限制与下一步

1. 结果卡片「确定」只做选中高亮，不触发真实换源；`SourceSidebar` 的勾选状态只在本次会话内保存。
2. 顶部统计固定 424，来自假数据常量；接真实搜索后换成结果 count。
3. 侧边栏条目与站点标签目前共用同一套假站点；接 `SiteApi` 后分别改为：
   接口源列表（站点 + 资源类型）与换源站点列表（`Site` / `Flag`）。
4. 结果卡仍为红绿渐变占位，接真实数据后换成 `Vod` 海报 + `AsyncImage`。
5. 首页顶栏其他项（直播 / 搜索 / 历史 …）仍是占位；换源弹窗的「结果点击」接入播放链路
   也留待后续阶段。

## 10. 回滚

- 本次改动集中在 4 个新增文件 + 2 个 Hero Activity + 本文件：`git revert` 对应提交即可。
- 恢复首页小房子占位 Dialog / 详情页旧私有外壳：回到 `recovery/hero-playdialog-history-param/20261102121308-ce2e73e50e7f`。
- 本次完成后的回滚锚点见提交后的 `recovery/hero-source-sidebar-change-dialog/<timestamp>` 标签。
