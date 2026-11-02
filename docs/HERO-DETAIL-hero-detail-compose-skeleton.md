# HERO-DETAIL — 详情页 Compose（假数据阶段：播放弹窗 + 演职员区）

## 1. 目标与验收标准

用 Compose 复刻 TV 详情页的「结构 + 焦点」，数据全部为假数据；**不触碰** `PlayerManager` / `VideoActivity` 等既有播放链路。

本任务分两阶段，第二阶段（本次）把「线路 + 选集」从主页整体搬进播放弹窗，并补上 Netflix 风格的演职员区：

| # | 验收项 | 结果 |
|---|---|---|
| 1 | 全屏背景 + 横向遮罩（左纯黑 → 右透明）+ 纵向遮罩（下纯黑 → 上透明） | 已实现 |
| 2 | 内容区左侧 padding 严格 24dp、整体贴底 | 已实现 |
| 3 | 右上角浮动小海报 150dp × 210dp | 已实现 |
| 4 | 剧名 32sp 加粗 / 元数据 14sp `#CCFFFFFF` / 简介 12sp `#BBFFFFFF` 最多两行，且均不可聚焦 | 已实现 |
| 5 | 播放/收藏/换源：高 36dp、13sp、间距 12dp、图标在左（24dp / 20dp）、焦点红底白字 | 已实现 |
| 6 | **主页面彻底移除「播放线路」「选集」两块**，只留背景 + 海报 + 文字 + 演职员 + 按钮 | 已实现 |
| 7 | **「播放」弹出半透明深色大圆角弹窗**：顶部线路 Tab、下方 5 列 × 4 行选集网格，高度 240dp | 已实现 |
| 8 | **弹窗焦点**：打开即进入弹窗；`lastWatchedEpisode` 吸附历史集；最右侧→下一组第一项；返回键关闭并回到「播放」 | 已实现 |
| 9 | **演职员区**：标题「导演 / 主演」，`LazyRow` 10 人，60dp 圆形头像 + 浅灰描边 + 12sp 单行省略，**不可聚焦** | 已实现 |
| 10 | 编译 + 打包通过 | `:app:assembleLeanbackArmeabi_v7aDebug` BUILD SUCCESSFUL |

## 2. 证据来源

| 来源 | 用途 | 可信度 |
|---|---|---|
| `ref_docs/activity_detail.xml` | 主布局：遮罩、右上小海报、内容区、按钮、线路/选集区块的全部尺寸与字号 | 上游逆向资源（权威） |
| `ref_docs/adapter_episode.xml` | 选集项：16sp、居中、单行 marquee、match_parent 宽 | 上游逆向资源（权威） |
| `ref_docs/adapter_flag.xml` | 线路项：16sp、居中、selector_item 焦点背景 | 上游逆向资源（权威） |
| `app/src/leanback/res/drawable/selector_item.xml` + `shape_item_*.xml` | 线路项三态：normal `black_20` / selected `black_40` / focused `black_40`+1.5dp 白描边、4dp 圆角、12dp 左右 padding | 仓库内同源资源 |
| `app/src/main/res/drawable/shape_detail_item_normal.xml` / `shape_detail_play_*.xml` | 按钮常态 `white_10` + 4dp 圆角、焦点红渐变 | 仓库内逆向资源 |
| `app/src/main/res/drawable/shape_poster_card.xml` | 海报卡 12dp 圆角 + `black_20` 底 | 仓库内逆向资源 |
| `HeroHomeActivity.kt` | 同套 HeroUI 的 Compose 写法：遮罩、显式焦点交接、`basicMarquee` | 本项目已合入代码 |
| 本次需求原文（弹窗 / 演职员） | 第二阶段布局与交互的唯一依据；与 XML 冲突时以需求为准 | 用户明确指令 |

> `bg_hero_mask_h` / `bg_hero_mask_v` 两个 drawable 在仓库与 `ref_docs/` 中都不存在，
> 因此遮罩按需求原文实现：横向 `Color.Black → Color.Transparent`、纵向 `Color.Transparent → Color.Black`。

## 3. 方案对比

| 方案 | 结论 | 理由 |
|---|---|---|
| A. 不改（继续用 `VideoActivity` / `TmdbDetailActivity` 的 XML 详情页） | 不采用 | 与已定稿的 HeroUI 视觉体系不一致；首页已迁到 Compose，详情页需要同一套遮罩与字号基线 |
| B. 原样照搬上游 XML（`activity_detail.xml` + Leanback `HorizontalGridView`/`RecyclerView`） | 不采用 | 上游布局深度绑定五模式详情页与 ViewBinding id 体系，直接搬会引入播放链路耦合，违反「不碰 PlayerManager」红线 |
| C. Compose 重写 + 显式焦点交接 + XML 顶部区块留在主页、下部区块收进弹窗（本方案） | **采用** | 与 `HeroHomeActivity` 同一套实现范式；纯展示文本天然不可聚焦；焦点流转可逐区显式控制；假数据阶段零播放依赖 |

## 4. 与 `ref_docs/activity_detail.xml` 的逐项对齐结果

### 4.1 主页面（保持不动部分）

| 节点 | XML 值 | 实现值 | 结论 |
|---|---|---|---|
| 内容容器 padding | 左右 24dp、上下 0dp（L54-61） | 同 | 已对齐 |
| 剧名 | 32sp bold / 高 36dp / 贴底 / 阴影 #cc000000 2,2,12（L69-81） | 同 | 已对齐 |
| 元数据 | 14sp / #ccffffff / 高 20dp / marginTop 2dp / 阴影 #aa000000 1.5,1.5,8（L90-102） | 同 | 已对齐 |
| 简介 | 12sp / #bbffffff / 高 40dp / marginTop 4dp / 2 行 / 行距+3dp / 占父宽 1/2 / 阴影 #aa000000 1.2,1.2,6（L103-124） | 同 | 已对齐 |
| 按钮组间距 | marginTop 12dp（L131） | 同 | 已对齐 |
| 按钮尺寸 | 高 36dp、左右 padding 16dp、drawablePadding 4dp、13sp **bold**（L132-188） | 同 | 已对齐 |
| 按钮间间距 | layout_marginEnd 12dp（L149/L168） | 12dp | 已对齐 |
| 右上小海报 | 150×210dp、marginTop 72dp、marginEnd 80dp、padding 4dp、12dp 圆角、`black_20` 底（L30-48） | 同 | 已对齐 |

### 4.2 搬进播放弹窗部分（数值沿用 XML）

| 节点 | XML 值 | 弹窗实现 |
|---|---|---|
| 区块标题 | 红条 4×16dp + marginEnd 10dp + 15sp bold（L212-223 / L245-256） | 同（`HeroDetailSectionTitle`） |
| 线路标题文案 | `播放线路`（L223） | 同 |
| 线路项 | 高 34dp、16sp、12dp 左右 padding、4dp 圆角（`adapter_flag.xml` + `shape_item_*.xml`） | 同 |
| 线路项底色 | 常态 `black_20` / 已选 `black_40` | 同 |
| 选集项字号 | 16sp、居中、单行（`adapter_episode.xml`） | 同（16sp 居中，焦点态跑马灯） |

### 4.3 有意偏离（需求优先于原版）

| 项 | 原版 | 本实现 | 原因 |
|---|---|---|---|
| 按钮焦点态 | 红色渐变 `shape_detail_*_focused` | 纯红 `HeroAccentColor` | 需求原文「选中时背景变红（HeroAccentColor）」 |
| 线路项焦点态 | `black_40` 底 + 1.5dp 白描边 | `HeroAccentColor` 红底 | **已确认保留**：二开要求按钮/海报/线路焦点统一红色调；未选中/已选中底色仍照抄 `black_20`/`black_40` |
| 线路行高 | XML 只有 `wrap_content` | 固定 34dp | **已确认保留**：固定行高渲染更稳定 |
| 选集容器 | 主页 140dp 纵向列表（L271-280） | 弹窗内 240dp、5 列 × 4 行的 `LazyVerticalGrid` | 本次需求明确要求「做成一屏可见的网格弹窗」，属需求覆盖而非待确认偏离 |
| 选集行高 | XML 只有 `wrap_content` 文本行 | 固定 46dp | 固定行高保证 240dp 精确容纳 4 行（16 + 46×4 + 8×3 + 16 = 240） |
| 小海报可见性 | `visibility="gone"`（仅 TMDB 海报态显示） | 始终显示 | 需求原文「右上角放一个浮动小海报」 |

## 5. 播放弹窗 `HeroPlayDialog`

### 5.1 结构与尺寸

```
遮罩 0x99000000（全屏，让底图透出形成层次）
└ 面板：宽 82% / 深色 0xE6141414 / 24dp 圆角 / 1dp 亮边 0x1FFFFFFF / 内边距 32dp
  ├ 「播放线路」标题（红条 + 15sp bold）
  ├ 线路 Tab 行：高 34dp，间距 12dp，横向滚动
  ├ 间距 20dp
  ├ 「选集」标题 + 右侧「第 N / M 组」页码（12sp 0x99FFFFFF）
  └ 选集网格：LazyVerticalGrid 5 列、高 240dp、上下 padding 16dp、间距 8dp
       └ 格子：高 46dp、6dp 圆角、常态 0x14FFFFFF、焦点 0x1FFFFFFF 底 + 2dp 红框
```

- 一页 20 集（5×4），页码随分组变化；总页数 = `ceil(集数 / 20)`，至少 1 页。
- 当前播放集用红字标出（`playingEpisode`），与焦点红框区分：焦点看边框，播放看字色。

### 5.2 焦点契约

| 输入 | 行为 |
|---|---|
| 打开弹窗 | 强制进入弹窗，落 `lastWatchedEpisode`：1 基集号 → 页 = `(n-1)/20`、格 = `(n-1)%20`；`lastWatchedEpisode = 0`（新剧）→ 第 1 集 |
| 网格内 ←/→ | 同一行内移动；最右列再 → 切下一组并落**新组第一项**；最左列再 ← 切上一组 |
| 网格内 ↑/↓ | 行间移动；第 1 行 ↑ 回线路 Tab（停在记忆中的那条） |
| 网格 ↓ 到底 | 消费事件，焦点停留原地 |
| 线路 ←/→ | 线路间移动，端点消费 |
| 线路 ↑ | 消费（无处可去，不掉出弹窗） |
| 线路 ↓ | 回选集网格，停在离开时那一格 |
| 返回键 | 关闭弹窗；主页面 `LaunchedEffect(showPlayDialog)` 把焦点还给「播放」 |
| 点击面板外 | 不关闭（`dismissOnClickOutside = false`，避免误触丢焦点） |

> 弹窗是独立 Window，首帧可能还没拿到 window focus，因此焦点请求做了逐帧重试（最多 5 帧）后才放弃。

### 5.3 历史集吸附示例

`DEMO_DETAIL.lastWatchedEpisode = 7` → 第 7 集 = 第 1 页第 7 格，弹窗一开焦点就在那儿。
接真实数据时，把 `Vod` 里的历史进度（1 基）填进来即可；没有历史传 `0`。

## 6. 演职员区 `HeroCastSection`

| 项 | 取值 |
|---|---|
| 位置 | 简介下方、按钮组上方（简介 → 18dp → 演职员 → 18dp → 按钮） |
| 标题 | 「导演 / 主演」，14sp `#ccffffff`，前方 4dp × 16dp 红色圆角竖条（与区块标题同一 `HeroDetailSectionTitle`，只是字号 14sp） |
| 容器 | `LazyRow`，10 个假数据，间距 12dp，右侧 contentPadding 24dp 让最后一位能贴边滑出 |
| 卡片 | 宽 76dp；60dp × 60dp 圆形头像（`CircleShape` + 1dp `#66FFFFFF` 浅灰描边 + `#2A2A2A` 底）→ 6dp → 12sp 名字居中单行省略 |
| 图片 | Coil `AsyncImage`，picsum 稳定假图（`?seed=hero-cast-N`），不依赖 TMDB Key |
| 焦点 | **整块不可聚焦**：没有任何 `focusable` / `clickable` 修饰符，默认焦点永远在「播放」，↑/↓ 不会被它截走 |

> 需求给了「可以横向滚动但不抢默认焦点」的备选。这里选不可聚焦：TV 端不聚焦的 `LazyRow` 无法用遥控器滚动，
> 但 10 人 × 76dp + 间距 ≈ 868dp，在 960dp 宽的电视上基本一屏放得下；若后续要支持几十位演职员，
> 再加 `focusable` 并把它接进 ↑/↓ 链路即可（改动只在本区块内）。

## 7. 完整焦点契约（主页面）

```
播放 ⇄ 收藏 ⇄ 换源            ← 左右：index ± 1，到端点消费不越界
        │ ↓ / 确定
     [播放弹窗]              ← 内部焦点独立，见 §5.2
        │ 返回键
      播放（焦点还原）
```

- 进页面：`LaunchedEffect(showPlayDialog)` → `requestDetailFocus(actionRequesters, 0)`（逐帧重试）。
- 按钮 ↑/↓ 直接消费：主页面已无其他可聚焦区域，不消费会让焦点搜索落到别处。
- 纯展示文本（剧名/元数据/简介/演职员）没有任何 `focusable` / `clickable` 修饰符。

## 8. 关键设计决策

1. **遮罩取值**：横向 `Color.Black → Color.Transparent`、纵向 `Color.Transparent → Color.Black`，端点严格取纯黑/全透明，中间不加插值。
2. **尺寸写死**：所有 dp/sp 集中在本文件末尾常量区，接真实数据不涉及尺寸改动。
3. **焦点不用 2D 自动搜索**：按钮 / 线路 / 选集格三组 `FocusRequester` 全部显式 `requestFocus`，每颗可聚焦项用 `onPreviewKeyEvent` 抢先消费四方向键。
4. **分组翻页而非连续滚动**：网格只渲染当前页 20 格，`FocusRequester` 数量固定为 20，避免长列表下 requester 列表随集数膨胀。
5. **弹窗独立 Window**：`Dialog(usePlatformDefaultWidth = false)` 让面板能占到 82% 宽；遮罩让底图透出，形成「半透明」而非死黑。
6. **跑马灯只在焦点态开启**（`basicMarquee`），常态省略号收尾。

## 9. 变更文件

| 文件 | 类型 |
|---|---|
| `app/src/main/java/com/fongmi/android/tv/ui/activity/HeroDetailActivity.kt` | 主页面删线路/选集、新增演职员区、新增播放弹窗；入口 `start(context, title)` |
| `app/src/main/java/com/fongmi/android/tv/ui/activity/HeroHomeActivity.kt` | 海报卡确定键由「直接起播」改为「拉起详情页」 |
| `app/src/main/AndroidManifest.xml` | 注册 `HeroDetailActivity`（`exported=true`，供 adb 拉起；不加 launcher intent-filter） |

**未改动**：`VideoActivity`、`PlayerManager` 及任何播放链路。
`HeroHomeActivity.launchPlayback(...)` 播放桥接**刻意保留未删**：下一阶段详情页的「播放」按钮要复用它。

## 10. 验证

```bash
# 编译 + 打包（本机需先设 JAVA_HOME / ANDROID_HOME）
./gradlew :app:compileLeanbackArmeabi_v7aDebugKotlin
./gradlew :app:assembleLeanbackArmeabi_v7aDebug
# → app/build/outputs/apk/leanbackArmeabi_v7a/debug/app-leanback-armeabi_v7a-debug.apk

# 拉起页面
adb shell am start -n com.silent.android.webhtv/com.fongmi.android.tv.ui.activity.HeroDetailActivity
```

实机清单：

1. 首页海报确定键 → 详情页，标题为所点剧名。
2. 默认焦点在「播放」，←→ 在三颗按钮间，↑/↓ 不丢焦点。
3. 确定「播放」→ 弹窗出现，焦点直接落在第 7 集（`DEMO_DETAIL.lastWatchedEpisode` 演示值）。
4. 网格最右侧 → 切到第 2 组且焦点在第 1 格；最左侧 ← 回到第 1 组。
5. 第 1 行 ↑ → 线路 Tab；线路 ↓ → 回到离开的那一格。
6. 返回键 → 弹窗关闭，焦点回到「播放」。
7. 演职员头像正常加载，遥控器无法把焦点移到头像或名字上。

## 11. 回滚

单文件新增 + 一段 manifest 注册，回滚只需删除该 Activity 文件与 manifest 中的 `<activity>` 节点，不影响任何既有页面与播放链路。
若只想回退本次弹窗/演职员改造，回退到 `recovery/hero-detail-entry/*` 标签即恢复「主页带线路 + 选集」的上一版。

## 12. 下一步（未开始）

1. 用真实 `Vod` / `Flag` / `Episode` / 演职员数据替换 `DEMO_DETAIL`（只换数据源，布局不动）。
2. 弹窗里点选集 / 播放按钮接回既有 `VideoActivity.startDirect(...)` 桥接（复用 `HeroHomeActivity.launchPlayback`，不改 PlayerManager）。
3. 收藏状态接入 `Keep`，换源接入既有搜索/聚合源入口。
4. `lastWatchedEpisode` 接真实历史进度（`History` / `Keep`），实现「老剧吸附」的真实闭环。
