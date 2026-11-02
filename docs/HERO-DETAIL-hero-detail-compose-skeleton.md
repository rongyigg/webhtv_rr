# HERO-DETAIL — 详情页 Compose（假数据阶段：播放弹窗 + 演职员 + 演员详情骨架）

## 1. 目标与验收标准

用 Compose 复刻 TV 详情页的「结构 + 焦点」，数据全部为假数据；**不触碰** `PlayerManager` / `VideoActivity` 等既有播放链路。

三个阶段累计交付：

| # | 验收项 | 阶段 | 结果 |
|---|---|---|---|
| 1 | 全屏背景 + 横向遮罩（左纯黑 → 右透明）+ 纵向遮罩（下纯黑 → 上透明） | 一 | 已实现 |
| 2 | 内容区左侧 24dp、整体贴底 | 一 | 已实现 |
| 3 | 右上角浮动小海报 150dp × 210dp | 一 | 已实现 |
| 4 | 剧名 32sp 加粗 / 元数据 14sp `#CCFFFFFF` / 简介 12sp `#BBFFFFFF`，均不可聚焦 | 一 | 已实现 |
| 5 | 播放/换源/收藏：高 36dp、13sp、间距 12dp、图标在左（24dp / 20dp）、焦点红底白字 | 一/三 | 已实现 |
| 6 | 主页面移除线路/选集，收进播放弹窗（线路 Tab + 5×4 选集网格 240dp） | 二 | 已实现 |
| 7 | 弹窗焦点：打开吸附历史集、最右列切下一组、返回键回「播放」 | 二 | 已实现 |
| 8 | **主页面从下往上**：剧名/Logo → 元数据 → 简介 → 按钮组 → 演职员，底部 padding 32dp | 三 | 已实现 |
| 9 | **Logo 位**：高 72dp、最大宽 720dp、靠左；无 Logo 时 32sp 文字 + `basicMarquee` | 三 | 已实现 |
| 10 | **简介最多 4 行**、最大宽度 440dp、超出省略 | 三 | 已实现 |
| 11 | **按钮顺序 播放 → 换源 → 收藏**，默认焦点「播放」 | 三 | 已实现 |
| 12 | **演职员可聚焦**：焦点红圈 + 1.08 倍放大；名字 12sp 单行 | 三 | 已实现 |
| 13 | **演员详情弹窗**：点击立即弹出、IO 异步加载、文本缓存、图片交给 Coil | 三 | 已实现 |
| 14 | 编译 + 打包通过 | 三 | `:app:assembleLeanbackArmeabi_v7aDebug` BUILD SUCCESSFUL |

## 2. 证据来源

| 来源 | 用途 | 可信度 |
|---|---|---|
| `ref_docs/activity_detail.xml` | 主布局：遮罩、海报卡、内容区、`heroLogoImg`（L82-89，72dp/最大 720dp/fitStart）、按钮、线路/选集 | 上游逆向资源（权威） |
| `ref_docs/adapter_episode.xml` | 选集项：16sp、居中、单行 marquee | 上游逆向资源（权威） |
| `ref_docs/adapter_flag.xml` | 线路项：16sp、居中、`selector_item` | 上游逆向资源（权威） |
| `app/src/leanback/res/drawable/selector_item.xml` + `shape_item_*.xml` | 线路项三态与 12dp 左右 padding | 仓库内同源资源 |
| `app/src/main/res/drawable/shape_detail_item_normal.xml` | 按钮常态 `white_10` + 4dp 圆角 | 仓库内逆向资源 |
| `app/src/main/res/drawable/shape_poster_card.xml` | 海报卡 12dp 圆角 + `black_20` 底 | 仓库内逆向资源 |
| `HeroHomeActivity.kt` / `HeroImageLoader.kt` | 同套 HeroUI 写法：遮罩、显式焦点交接、Coil3 单例 | 本项目已合入代码 |
| 本次需求原文 | 第三阶段布局与交互的唯一依据；与 XML 冲突处以需求为准 | 用户明确指令 |

## 3. 方案对比

| 方案 | 结论 | 理由 |
|---|---|---|
| A. 继续用 `VideoActivity` / `TmdbDetailActivity` 的 XML 详情页 | 不采用 | 与已定稿的 HeroUI 视觉体系不一致 |
| B. 原样照搬上游 XML（`HorizontalGridView` / `RecyclerView`） | 不采用 | 深度绑定五模式详情页与 ViewBinding id 体系，会引入播放链路耦合 |
| C. Compose 重写 + 显式焦点交接（本方案） | **采用** | 与 `HeroHomeActivity` 同一范式；纯文本天然不可聚焦；焦点逐区显式控制；假数据阶段零播放依赖 |

## 4. 主页面布局（从下往上）

```
Column(align = BottomStart, padding(start/end 24dp, bottom 32dp))
├ 1. 剧名 / Logo
├ 2. 元数据 14sp #ccffffff
├ 3. 简介 12sp #bbffffff，最宽 440dp，最多 4 行
├ 4. 按钮组：播放 | 换源 | 收藏
└ 5. 演职员 LazyRow（最底部）
```

| 节点 | 取值 | 说明 |
|---|---|---|
| 底部留白 | 32dp | 需求新增，防止演职员行贴屏幕边缘 |
| Logo | 高 72dp、最宽 720dp、`ContentScale.Fit` + `CenterStart` | 对应 XML `heroLogoImg` L82-89 |
| 无 Logo 文字 | 32sp bold、高 36dp、`basicMarquee()` | 长剧名滚动，不再省略号截断 |
| 元数据 | 14sp `#ccffffff`、高 20dp、阴影 1.5/1.5/8 | 沿用 XML L90-102 |
| 简介 | 12sp `#bbffffff`、`widthIn(max = 440.dp)`、`maxLines = 4`、行距 +3dp | XML 原为半宽 2 行，按需求放宽 |
| 按钮 | 高 36dp、13sp bold、左右 16dp、图标间距 4dp、按钮间距 12dp | 焦点纯红底 + 纯白文字图标 |
| 间距 | 简介→按钮 12dp、按钮→演职员 16dp | 需求「紧跟在简介下方」 |

> 剧名/元数据/简介均为纯展示：没有任何 `focusable` / `clickable`，遥控器选不中。

## 5. 演职员行

| 项 | 取值 |
|---|---|
| 容器 | `LazyRow`，标题「导演 / 主演」14sp `#ccffffff` + 4×16dp 红色圆角竖条 |
| 卡片 | 宽 76dp；60dp 圆形头像 → 6dp → 12sp 名字居中单行省略 |
| 常态 | 1dp `#66FFFFFF` 浅灰描边 |
| 焦点态 | 2dp `HeroAccentColor` 红圈 + 1.08 倍放大（`animateFloatAsState` 160ms），名字转纯白 |
| 点击 | `clickable` → `onActorClick(actorId, actorName)` 语义（当前实现为 `onConfirm(index)` → 打开演员弹窗） |
| 图片 | Coil `AsyncImage`，picsum 稳定假图，不依赖 TMDB Key |

## 6. 演员详情弹窗与异步架构

### 6.1 交互时序（不阻塞 UI）

```
确定键点击头像
  → 同一帧：pendingCastFocus = index；actorDialogIndex = index
  → Dialog 立即组合：显示头像 + 姓名 + 「加载中…」转圈
  → LaunchedEffect 启动：withContext(Dispatchers.IO) { delay(2000) }  ← 主线程零阻塞
  → 返回后：写缓存 → profile 状态更新 → 显示「演员详情开发中」占位文案
  → 返回键关闭 → actorDialogIndex = null → 焦点回到被点的那位演员
```

- 命中缓存时首帧直接出内容，不显示 Loading（`remember(actor.id)` 初始化即查缓存）。
- `dismissOnClickOutside = true`：TV 上误触空白也能关。

### 6.2 缓存策略（需求核心）

| 数据 | 处理 | 位置 |
|---|---|---|
| 演员文本（简介/生日/出生地） | 进程内 `ConcurrentHashMap` | `ActorProfileCache` |
| 演员头像 | **不手动下载、不落自定义缓存** | Coil 默认 ImageLoader 的磁盘 LRU |
| 参演作品海报 | 同上，交给 Coil | 同上 |

- 换 Room 时只需替换 `ActorProfileCache.get/put` 两个方法，调用点不动。
- 项目里 `HeroImageLoader.install()` 只替换网络栈（OkHttp + DoH），**没有**自定义磁盘缓存；
  因此头像/海报走的是 Coil 默认 LRU（`cacheDir/image_cache`，按可用空间比例上限，满后自动淘汰），
  与需求「交给 Coil 默认缓存管理」一致，无需另配 250MB。

### 6.3 假数据

`loadActorProfile()` 内 `delay(2_000)` 后返回占位档案：
生日 `1988-06-12`、出生地 `中国 · 上海`、简介「演员详情开发中。…」。
接 TMDB 时只改这个函数体（`GET /person/{id}?language=zh-CN`，只取文本字段）。

## 7. 播放弹窗（第二阶段，保持不变）

```
遮罩 0x99000000
└ 面板 82% 宽 · 0xE6141414 · 24dp 圆角 · 1dp 亮边 · 内边距 32dp
  ├ 「播放线路」标题 + 34dp 线路 Tab（焦点红底白字）
  └ 「选集」标题 + 第 N/M 组 + 5 列 × 4 行网格（240dp，格高 46dp，焦点 2dp 红框）
```

焦点：打开吸附 `lastWatchedEpisode`（1 基，0 = 新剧落第 1 集）；网格最右列 → 切下一组落首项，
最左列 ← 切上一组；第 1 行 ↑ 回线路 Tab；线路 ↓ 回网格记忆格；返回键关闭。

## 8. 焦点契约（完整）

```
                 ┌──────── 播放 ⇄ 换源 ⇄ 收藏 ────────┐
                 │  ↑                                    │ ↓
                 └──────────── 演职员 LazyRow ⇄ ──────────┘
                              （行内 ←/→，↓ 消费）
播放 ──确定──▶ 播放弹窗（线路 ⇄ 选集网格）
演员 ──确定──▶ 演员详情弹窗（加载 → 占位文案）
```

| 输入 | 行为 |
|---|---|
| 进页面 | 焦点落「播放」 |
| 按钮 ←/→ | 三颗按钮间移动，端点消费 |
| 按钮 ↓ | 进入演职员行，落在上次停留的演员（初始第 1 位） |
| 按钮 ↑ | 内容区顶部，消费 |
| 演职员 ←/→ | 行内横移，端点消费 |
| 演职员 ↑ | 回「播放」 |
| 演职员 ↓ | 最底部，消费 |
| 关闭播放弹窗 | 焦点回「播放」 |
| 关闭演员弹窗 | 焦点回**刚才点击的那位演员** |

## 9. 有意偏离（需求优先于原版）

| 项 | 原版 | 本实现 | 原因 |
|---|---|---|---|
| 按钮焦点态 | 红色渐变 `shape_detail_*_focused` | 纯红 `HeroAccentColor` | 需求原文「焦点态为红底白字白图标」 |
| 线路项焦点态 | `black_40` + 1.5dp 白描边 | `HeroAccentColor` 红底 | 已确认：二开要求焦点统一红色调 |
| 线路行高 | `wrap_content` | 固定 34dp | 已确认：固定行高渲染更稳定 |
| 选集容器 | 主页 140dp 纵向列表 | 弹窗 240dp 5×4 网格 | 需求二阶段明确要求 |
| 简介 | 半宽 2 行 | 最宽 440dp、4 行 | 需求三阶段明确要求 |
| 按钮顺序 | 播放 / 收藏 / 换源 | 播放 / 换源 / 收藏 | 需求三阶段明确要求 |
| 演职员 | 不可聚焦 | 可聚焦 + 红圈放大 + 点击弹窗 | 需求三阶段明确要求 |

## 10. 变更文件

| 文件 | 类型 |
|---|---|
| `app/src/main/java/com/fongmi/android/tv/ui/activity/HeroDetailActivity.kt` | 主页面布局重排、Logo 位、4 行简介、按钮顺序、演职员可聚焦、演员详情弹窗与缓存 |
| `app/src/main/java/com/fongmi/android/tv/ui/activity/HeroHomeActivity.kt` | （第二阶段）海报卡确定键改为拉起详情页 |
| `app/src/main/AndroidManifest.xml` | （第一阶段）注册 `HeroDetailActivity` |

**未改动**：`VideoActivity`、`PlayerManager` 及任何播放链路。

## 11. 验证

```bash
./gradlew :app:compileLeanbackArmeabi_v7aDebugKotlin
./gradlew :app:assembleLeanbackArmeabi_v7aDebug
# → app/build/outputs/apk/leanbackArmeabi_v7a/debug/app-leanback-armeabi_v7a-debug.apk

adb shell am start -n com.silent.android.webhtv/com.fongmi.android.tv.ui.activity.HeroDetailActivity
```

实机清单：

1. 首页海报确定键 → 详情页；默认焦点「播放」。
2. 简介显示 4 行后省略；标题在无 Logo 时按 32sp 显示。
3. 「播放」↓ → 焦点落到第 1 位演员（红圈 + 放大）；←/→ 横移；↑ 回「播放」。
4. 演员上按确定 → 弹窗**立刻**出现并转圈，2 秒后显示「演员详情开发中」；期间遥控器不卡。
5. 返回键关弹窗 → 焦点回到刚才那位演员。
6. 「播放」确定 → 播放弹窗吸附第 7 集；最右列 → 切第 2 组；返回键回「播放」。

## 12. 回滚

- 本次改动集中在单文件：`git revert` 对应提交即可。
- 整体回退到「主页带线路 + 选集」版本：回到标签 `recovery/hero-detail-entry/*`。

## 13. 下一步（未开始）

1. 用真实 `Vod` / `Flag` / `Episode` / 演职员数据替换 `DEMO_DETAIL`。
2. `loadActorProfile` 接 TMDB `/person/{id}`，`logoUrl` 接 `images.logos`（`iso_639_1 = zh`）。
3. 播放按钮与选集点击接 `VideoActivity.startDirect(...)`（复用 `HeroHomeActivity.launchPlayback`，不改 PlayerManager）。
4. 收藏接 `Keep`，换源接既有搜索/聚合源入口。
5. `lastWatchedEpisode` 接真实历史进度。
