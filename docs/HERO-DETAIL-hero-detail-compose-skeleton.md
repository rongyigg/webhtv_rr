# HERO-DETAIL — 详情页 Compose 骨架（假数据阶段）

## 1. 目标与验收标准

用 Compose 复刻原版 TV 详情页的「结构 + 焦点」，数据全部为假数据；**不触碰** `PlayerManager` / `VideoActivity` 等既有播放链路。

验收标准（与需求逐条对应）：

| # | 验收项 | 结果 |
|---|---|---|
| 1 | 全屏背景图 + 横向遮罩（左纯黑 → 右透明）+ 纵向遮罩（下纯黑 → 上透明） | 已实现 |
| 2 | 内容区左侧 padding 严格 24dp、整体贴底 | 已实现 |
| 3 | 右上角浮动小海报 150dp × 210dp | 已实现 |
| 4 | 剧名 32sp 加粗 / 元数据 14sp `#CCFFFFFF` / 简介 12sp `#BBFFFFFF` 最多两行，且均不可聚焦 | 已实现 |
| 5 | 播放/收藏/换源：高 36dp、13sp、间距 12dp、图标在左（24dp / 20dp）、焦点红底白字 | 已实现 |
| 6 | 区块标题 4dp×16dp 红条 + 15sp 加粗；线路列表 34dp；选集列表 140dp 纵向滚动、16sp 居中单行跑马灯、焦点红框 | 已实现 |
| 7 | 默认焦点「播放」；左右在按钮间；↓ 按钮→线路→选集；↑ 反向退回 | 已实现 |
| 8 | 编译通过 | `:app:assembleLeanbackArmeabi_v7aDebug` |

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

> `bg_hero_mask_h` / `bg_hero_mask_v` 两个 drawable 在仓库与 `ref_docs/` 中都不存在，
> 因此遮罩按需求原文实现：横向 `Color.Black → Color.Transparent`、纵向 `Color.Transparent → Color.Black`。

## 3. 方案对比

| 方案 | 结论 | 理由 |
|---|---|---|
| A. 不改（继续用 `VideoActivity` / `TmdbDetailActivity` 的 XML 详情页） | 不采用 | 与已定稿的 HeroUI 视觉体系不一致；首页已迁到 Compose，详情页需要同一套遮罩与字号基线 |
| B. 原样照搬上游 XML（`activity_detail.xml` + Leanback `HorizontalGridView`/`RecyclerView`） | 不采用 | 上游布局深度绑定五模式详情页与 ViewBinding id 体系，直接搬会引入播放链路耦合，违反「不碰 PlayerManager」红线 |
| C. Compose 重写 + 显式焦点交接（本方案） | **采用** | 与 `HeroHomeActivity` 同一套实现范式；纯展示文本天然不可聚焦；焦点流转可逐区显式控制；假数据阶段零播放依赖 |

## 4. 与 `ref_docs/activity_detail.xml` 的逐项对齐结果

| 节点 | XML 值 | 实现值 | 结论 |
|---|---|---|---|
| 内容容器 padding | 左右 24dp、上下 0dp（L54-61） | 同（bottom 已从初版 32dp 改为 0dp） | 已对齐 |
| 剧名 | 32sp bold / 高 36dp / 贴底 / 阴影 #cc000000 2,2,12（L69-81） | 同 | 已对齐 |
| 元数据 | 14sp / #ccffffff / 高 20dp / marginTop 2dp / 阴影 #aa000000 1.5,1.5,8（L90-102） | 同（间距由 8dp→2dp） | 已对齐 |
| 简介 | 12sp / #bbffffff / 高 40dp / marginTop 4dp / 2 行 / 行距+3dp / 占父宽 1/2 / 阴影 #aa000000 1.2,1.2,6（L103-124） | 同（初版为全宽、无固定高，已修正） | 已对齐 |
| 按钮组间距 | marginTop 12dp（L131） | 12dp（初版 20dp） | 已对齐 |
| 按钮尺寸 | 高 36dp、左右 padding 16dp、drawablePadding 4dp、13sp **bold**（L132-188） | 同（初版图标间距 6dp、字重非粗体，已修正） | 已对齐 |
| 按钮间间距 | layout_marginEnd 12dp（L149/L168） | 12dp | 已对齐 |
| 按钮→线路区块 | detailTop marginBottom 6dp + flagLayout marginTop 12dp = 18dp（L68/L204） | 18dp | 已对齐 |
| 区块标题 | 红条 4×16dp + marginEnd 10dp + 15sp bold（L212-223 / L245-256） | 同 | 已对齐 |
| 线路标题文案 | `播放线路`（L223） | 同（初版为「线路」） | 已对齐 |
| 标题→线路列表 | 8dp（L211） | 8dp（初版 10dp） | 已对齐 |
| 线路列表 | 高 34dp、paddingStart 0dp、paddingEnd 24dp（L225-232） | 同 | 已对齐 |
| 线路项字号 | 16sp（`adapter_flag.xml` L3） | 16sp（初版 14sp） | 已对齐 |
| 线路项内边距/圆角/底色 | 12dp 左右、4dp 圆角、`black_20` / `black_40`（`shape_item_*.xml`） | 同 | 已对齐 |
| 线路→选集区块 | flagLayout marginBottom 2dp（L205） | 2dp（初版 18dp） | 已对齐 |
| 选集标题→列表 | 0dp（L244） | 0dp（初版 10dp） | 已对齐 |
| 选集列表 | 高 140dp、paddingStart/End 6dp、纵向滚动（L271-280） | 同（初版无列内 padding、行间距 8dp，已修正） | 已对齐 |
| 选集项 | 16sp / 居中 / 单行 marquee / match_parent（`adapter_episode.xml`） | 同 | 已对齐 |
| 右上小海报 | 150×210dp、marginTop 72dp、marginEnd 80dp、padding 4dp、12dp 圆角、`black_20` 底（L30-48） | 同（初版 24dp/24dp、无内边距、有阴影，已修正） | 已对齐 |

### 有意偏离（需求优先于原版）

| 项 | 原版 | 本实现 | 原因 |
|---|---|---|---|
| 按钮焦点态 | 红色渐变 `shape_detail_*_focused` | 纯红 `HeroAccentColor` | 需求原文「选中时背景变红（HeroAccentColor）」 |
| 线路项焦点态 | `black_40` 底 + 1.5dp 白描边 | `HeroAccentColor` 红底 | **已确认保留**：二开要求按钮/海报/线路焦点统一红色调，在复杂影视背景上更醒目；未选中/已选中底色仍照抄 `black_20`/`black_40` |
| 选集行高 | XML 只有 `wrap_content` 文本行（行高由代码决定） | 固定 34dp | **已确认保留**：固定行高渲染更稳定，140dp 视野精确容纳 4 行 |
| 小海报可见性 | `visibility="gone"`（仅 TMDB 海报态显示） | 始终显示 | 需求原文「右上角放一个浮动小海报」 |

## 5. 关键设计决策

1. **遮罩取值**：横向 `Color.Black → Color.Transparent`、纵向 `Color.Transparent → Color.Black`，端点严格取纯黑/全透明，
   中间不加插值，避免「左不够黑 / 右被压灰」。
2. **图解尺寸写死**：所有 dp/sp 集中在本文件末尾常量区，后续接入真实数据不涉及尺寸改动。
3. **焦点不用 2D 自动搜索**：三组 `FocusRequester`（按钮 / 线路 / 选集）全部显式 `requestFocus`，
   每颗可聚焦项都用 `onPreviewKeyEvent` 抢先消费四方向键，杜绝焦点跑到背景或文本上。
4. **记忆索引**：进入线路/选集时回到上一次停留的项，与首页 Tab 栏的处理一致。
5. **跑马灯只在焦点态开启**（`basicMarquee`），常态省略号收尾，避免整屏文字持续滚动。
6. **选集 ↑ 的边界语义**：列表内上移，到第 1 集再按 ↑ 才退回线路列表；否则 140dp 高的列表无法回看。
   如果产品要求「任意位置 ↑ 都直接退回线路」，只需把 `onMoveUp` 改成固定调用 `focusFlag()`。

## 6. 焦点契约（实现位置）

```
播放 ⇄ 收藏 ⇄ 换源            ← 左右：index ± 1，到端点消费不越界
        │ ↓
     线路列表（34dp）          ← 左右：线路间移动
        │ ↓
     选集列表（140dp，纵向）   ← 上下：集间移动；第 1 集 ↑ 退回线路
```

- 进页面：`LaunchedEffect(Unit)` → `requestDetailFocus(actionRequesters, 0)`。
- 按钮 ↑ / 选集末集 ↓：消费事件，焦点停留原地。
- 纯展示文本（剧名/元数据/简介）没有任何 `focusable` / `clickable` 修饰符。

## 7. 变更文件

| 文件 | 类型 |
|---|---|
| `app/src/main/java/com/fongmi/android/tv/ui/activity/HeroDetailActivity.kt` | 新增（骨架 + 全部常量） |
| `app/src/main/AndroidManifest.xml` | 注册 `HeroDetailActivity`（`exported=true`，供 adb 拉起；不加 launcher intent-filter） |

**未改动**：`HeroHomeActivity.kt`、`VideoActivity`、`PlayerManager` 及任何播放链路。

## 8. 验证

```bash
# 编译（本机需先设 JAVA_HOME / ANDROID_HOME）
./gradlew :app:compileLeanbackArmeabi_v7aDebugKotlin
./gradlew :app:assembleLeanbackArmeabi_v7aDebug

# 拉起页面（本阶段首页未接入口，用显式组件启动）
adb shell am start -n com.silent.android.webhtv/com.fongmi.android.tv.ui.activity.HeroDetailActivity
```

焦点回归清单：默认落在播放 → ←→ 三按钮 → ↓ 线路 → ↓ 选集 → ↑ 线路 → ↑ 播放；
选集内连续 ↓ 到底不越界；任意位置长按方向键不丢焦点。

## 9. 回滚

单文件新增 + 一段 manifest 注册，回滚只需删除该 Activity 文件与 manifest 中的 `<activity>` 节点，
不影响任何既有页面与播放链路。

## 10. 下一步（未开始）

1. 用真实 `Vod` / `Flag` / `Episode` 替换 `DEMO_DETAIL`（只替换数据源，布局不动）。
2. 首页海报卡点击接入 `HeroDetailActivity.start(...)`（当前刻意未改首页行为）。
3. 播放按钮接回既有 `VideoActivity.startDirect(...)` 桥接（与首页同一入口，不改 PlayerManager）。
4. 收藏状态接入 `Keep`，换源接入既有搜索/聚合源入口。
