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
- [ ] 播放页 `VideoActivity` 的重匹配入口（目前仍是 `TmdbSearchDialog` 列表版）——下一阶段
- [ ] 编译 + 真机焦点回归
