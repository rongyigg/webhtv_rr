# HERO-HISTORY · HeroUI 历史页（View 体系）

> 本文件是该任务唯一的持久记录（AGENTS.md §8：一个任务只有一个 `docs/<TASK-ID>-<slug>.md`）。
> 后续修复、验证、决策变更全部追加到这里，不要再建 `plan` / `implementation` / `fix` 之类的平行文档。

## 1. 完成句与状态

**完成句**：进入 HeroUI 首页顶栏的「历史」后，打开一个与 `ref_docs/history_page.png` 同构的历史页 ——
全屏背景 + 左侧剧名/元数据/两行简介 + 「查看详情」与进度条 + 一整行 120×214 海报卡，
卡片复用全局唯一组件 `HeroPosterCard`，焦点移动即时刷新左侧信息与背景。

**状态**：代码已落地（2026-11-03），**未编译、未打包、未提交**（用户要求只保存代码，自行打推送包）。

## 2. 真值来源（不得私自改）

| 区块 | 真值文件 / 行号 | 采纳的参数 |
| --- | --- | --- |
| 页面骨架与节点顺序 | `ref_docs/fragment_history.xml` L1-241 | `heroBg`(L13-19) → `maskLeft`(L20-25) → `maskBottom`(L26-31) → 贴底 `posterContent`(L72-240) |
| Hero 信息区 | 同上 L81-146 | 左右 24dp、底 22dp；剧名 32sp/粗体/高 36dp/贴底/阴影 `#cc000000` 2/2/12；元数据 14sp/`#ccffffff`/高 20dp/marginTop 2dp/阴影 `#aa000000` 1.5/1.5/8；简介 12sp/`#bbffffff`/高 40dp/2 行/行距 3dp/宽 = 父宽一半（`weightSum=2` + `weight=1`）/阴影 `#aa000000` 1.2/1.2/6 |
| 操作区 | 同上 L147-220 | 「查看详情」高 32dp、14sp、白字、`drawablePadding 4dp`；进度组宽 200dp、距按钮 20dp；`progressEpisode` 12sp/`#ccffffff`/单行、`progressTotal` 12sp/`#ccffffff`/marginStart 12dp、`progressBar` 高 2dp/marginTop 2dp |
| 海报行 | 同上 L221-238 | 整行高 215dp、左右各 24dp、`clipChildren=false`、`clipToPadding=false` |
| 卡片 | `ref_docs/adapter_history_poster.xml` + `history_page.png` | 120×180 海报（`ShapeableImageView`、`centerCrop`、`Vod.Grid.Large` = 8dp）、剧名 13sp/`#ccffffff`/marginTop 8dp、左上 `siteTag`、右上 `tmdbTag`、删除遮罩 `delete` |
| 卡片数据映射 | `ref_docs/HistoryPosterPresenter.java` L57-126 | 宽度 = `min(style.rect()[0], 150dp)`、高 = 宽 × 1.5 → 120×180；`name` = `getVodName()`；`siteTag` = `getSiteName()` |
| 进度文案 | `ref_docs/history_page.png` | 当前集显示 `E02`；已看/总时长显示 `27:15 / 47:34`；顶部 chip 显示 `02. 恐吓信突袭 录像藏秘密(4.98 GB)` |
| 遮罩色标 | 首页 `HeroHomeActivity.kt` `HeroMaskLeft()` L381-394 / `HeroMaskBottom()` L396-411 | 横：0% 黑 78% → 52% 黑 16% → 100% 全透明；纵：0% 黑 86% → 26% 黑 30% → 58% 黑 62% → 100% 黑 97% |
| 卡片组件 | `ui/hero/HeroPosterCard.kt` | 120×214、四角 8dp、2dp `#FFFF0000` 焦点框（只包 120×180）、34dp 剧名栏；字段 `title` / `imageUrl` / `topStartTag` / `bottomStartTag` |

## 3. 实施决策

### 3.1 为什么是 View + RecyclerView，而不是 Compose

- `HeroPosterCard` 是 `FrameLayout` 子类（View 体系）。Compose 里只能塞 `AndroidView`，
  而 Compose 焦点系统与 View 焦点系统互不接管，混用会出现「焦点在卡上但 Compose 认为焦点在别处」的死角。
- 原版历史页本身就是 View（`fragment_history.xml` + leanback `HorizontalGridView`）。
- `main` 源集禁止 `androidx.leanback`（AGENTS.md），所以 `HorizontalGridView` →
  `RecyclerView` + `LinearLayoutManager(HORIZONTAL)`，这是唯一等价的 View 方案。

### 3.2 卡片只用一个组件

历史页**不**复刻 `adapter_history_poster.xml`，直接 `HeroPosterCard(context)` 作为 itemView：

| 原版节点 | 本工程映射 |
| --- | --- |
| `@id/image`（120×180） | `HeroPosterCard` 内部 `ShapeableImageView`（左上/右上 8dp、左下/右下 0f） |
| `@id/name`（13sp marquee） | `HeroPosterCard.title`（16sp 单行省略，铁律写死在组件里） |
| `@id/siteTag`（10sp 白字 + 阴影） | `HeroPosterCard.topStartTag`（13sp + `shape_vod_site`） |
| `@id/tmdbTag` / `@id/delete` / `@id/siteGradient` | 暂未实现（见 §5 差异清单） |
| 焦点 overlay `selector_vod` | `HeroPosterCard` 自绘的 2dp `#FFFF0000` + 8dp 圆角（只包海报） |

### 3.3 遮罩为什么自绘

首页遮罩是 Compose `Brush.horizontalGradient / verticalGradient`，纵向有 **4 个色标**；
`GradientDrawable` 最多支持 3 个。为了色标数量、位置、透明度与首页完全一致，
`HeroMaskView` 用 `LinearGradient` + `Paint` 在 `onSizeChanged` 里建 shader 自绘，
左 / 纵两个子类把色标数组写死（十六进制值与 `Color.Black.copy(alpha = …)` 逐位对应）。

### 3.4 新增资源（全部有真值，不是发明）

| 资源 | 依据 |
| --- | --- |
| `@color/hero_accent` = `#FFFF0000` | 首页 `HeroAccentColor = Color(0xFFFF0000)`（L1135）、`HeroPosterCard.FOCUS_COLOR` |
| `@drawable/shape_hero_resume_normal` | `black_50` 底 + 16dp 圆角（= 32dp 高一半，沿用首页 Tab「圆角 = 高度一半」规则；13dp/26dp）+ 12dp 横向内边距（沿用 `HERO_TAB_H_PADDING = 12dp`） |
| `@drawable/shape_hero_resume_focused` | 同上几何，填充换成 `@color/hero_accent` |
| `@drawable/selector_hero_resume` | focused / pressed → 红色胶囊；normal → `black_50` 胶囊 |
| `@drawable/progress_hero_history` | 2dp 条：轨道 `@color/white_10`、进度 `@color/white`、1dp 圆角（高度一半） |
| `@string/hero_history_view_detail` / `hero_history_episode` / `hero_history_progress` | 原版 `@string/history_view_detail` 缺失；`E%1$02d` 与 `%1$s / %2$s` 照 `history_page.png` 文案 |

## 4. 文件清单

新增：

- `app/src/main/java/com/fongmi/android/tv/ui/activity/HeroHistoryActivity.kt`
  （Activity + `PosterAdapter` + `SpacingDecoration` + `HeroMaskView` / `HeroMaskLeftView` / `HeroMaskBottomView`）
- `app/src/main/java/com/fongmi/android/tv/ui/hero/HeroHistoryDemo.kt`（`HeroHistoryItem` + 6 条 Mock）
- `app/src/main/res/layout/activity_hero_history.xml`
- `app/src/main/res/drawable/progress_hero_history.xml`
- `app/src/main/res/drawable/shape_hero_resume_normal.xml`
- `app/src/main/res/drawable/shape_hero_resume_focused.xml`
- `app/src/main/res/drawable/selector_hero_resume.xml`

修改：

- `app/src/main/AndroidManifest.xml`：新增 `.ui.activity.HeroHistoryActivity`（`exported=false`、`sensorLandscape`、`@style/Theme.App`）
- `app/src/main/java/com/fongmi/android/tv/ui/activity/HeroHomeActivity.kt`：顶栏「历史」的 `onConfirm` 由空实现改为 `HeroHistoryActivity.start(context)`
- `app/src/main/res/values/colors.xml`：新增 `hero_accent`
- `app/src/main/res/values/strings.xml` / `values-zh-rCN/strings.xml`：新增三条 `hero_history_*`

## 5. 与真值的差异（明确未做，不是遗漏）

1. **页顶 `pageSite` chip**（`fragment_history.xml` L32-47，`shape_config_name`）：
   main 源集没有 `shape_config_name`，其填充色无法从逆向产物确认，故本期不做；
   顶部这行留给「站点 / 当前集信息」后续按真值补。
2. **右上 `posterCard` 预览卡**（L48-66，150×210）：原版默认 `gone`，本期不做。
3. **卡片 `tmdbTag` / 删除遮罩 `delete` / `siteGradient`**：
   需要 TMDB 状态与长按删除数据流，等数据层接入后再加（届时仍必须走 `HeroPosterCard`，不得另建卡片）。
4. **顶栏**：历史页没有顶栏（原版也没有），返回用遥控器返回键回首页；
   若后续要求「历史页保留顶栏」，再按 `HeroTopBar` 的真值补一套 View 版。
5. **数据层**：全部来自 `HeroHistoryDemo.kt` 的 Mock（picsum 稳定图）。
   TODO 已写进文件头：接 `History` 表时只换列表，页面与卡片不动。

## 6. 焦点约定

- 进入页面：`recycler.post { focusPoster(0) }`，并带 `FOCUS_RETRY_*` 重试兜底（同 `ChangeSourceDialog`）→ 焦点落在第 1 张卡。
- 卡片 ←/→：`LinearLayoutManager` 自动移动，焦点变化触发 `applyHeroInfo`（左侧文字 + 背景 + 进度全部换成本条）。
- 卡片 ↑：`HeroPosterCard.nextFocusUpId = R.id.btnResume`（创建 Holder 时写死）。
- 「查看详情」↓：`android:nextFocusDown="@+id/recycler"`；点击 → `HeroDetailActivity.start(this, title)`。
- 「查看详情」↑：不动（本页没有顶栏）。
- 卡片 ↓：停在原地（海报行已是最底行，与首页一致）。

## 7. 验证记录

已做（静态、零编译）：

- 新布局 / 新 drawable / 改动的 `colors.xml` / `strings.xml` / `AndroidManifest.xml` 全部通过 XML 解析。
- `HeroHistoryActivity.kt` 花括号 33/33、圆括号 128/128、方括号 7/7；
  `HeroHistoryDemo.kt` 圆括号 10/10、方括号 16/16；`HeroHomeActivity.kt` 138/138、376/376、28/28。
- 布局里引用的 id / string / drawable / color 与代码中的 `R.id.*`、`R.string.*` 逐一比对，全部存在。
- `HeroPosterCard.kt` 复检：无 `clipPath` / `Path` / `Outline` / `ViewOutlineProvider` / `clipToOutline` / `PosterClipLayout`
  残留，圆角走 `GradientDrawable.cornerRadius` + `ShapeableImageView`。

未做（用户明确禁止）：编译、打包、安装、commit、push。

## 8. 回滚

1. 删除 §4 的 7 个新增文件；
2. `AndroidManifest.xml` 删除 `HeroHistoryActivity` 节点；
3. `HeroHomeActivity.kt` 顶栏 `onConfirm` 恢复为 `{ }`，并删掉 `TOP_BAR_HISTORY_LABEL`；
4. 删除 `colors.xml` 的 `hero_accent` 与三条 `hero_history_*` 字符串。

## 9. Recovery anchor

- 目标：历史页可用、卡片与首页/换源弹窗 100% 同一组件。
- 当前：代码已全部落盘，静态检查通过，等用户打包验证。
- 下一步（唯一）：用户在实机验证「历史页圆角 + 焦点红框 + 焦点切换刷新左侧信息」；
  若报错，把编译日志贴回，按报错点做最小修复；通过后再讨论 §5 的差异项（优先 `pageSite` chip、顶栏、真实数据）。
