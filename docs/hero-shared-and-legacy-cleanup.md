# Hero 公共 API 抽离与旧首页 / 旧历史页删除（hero-shared-and-legacy-cleanup）

## Recovery anchor

- 目标：把旧 Compose 首页 `HeroHomeActivity.kt` 里仍被其它页面引用的公共 API 原样搬到
  `app/src/main/java/com/fongmi/android/tv/ui/hero/HeroShared.kt`，然后删除旧首页与两套旧历史页，
  并保证纯 View 首页（`HomeViewActivity`）、新历史页（`HistoryViewActivity`）、收藏页（`KeepViewActivity`）
  的现有逻辑零改动。
- 验收：全局 0 处 `HeroHomeActivity` / `HistoryActivity` 代码引用；清单无对应节点且 LAUNCHER 仍是
  `HomeViewActivity`；抽离后的符号逻辑与常量值不变。
- 状态：已完成（静态验证通过，未编译）。

## 变更记录

### 模块一：抽离旧首页公共 API

新增 `app/src/main/java/com/fongmi/android/tv/ui/hero/HeroShared.kt`（package `com.fongmi.android.tv.ui.hero`）：

| 符号 | 原位置（`HeroHomeActivity.kt`） | 说明 |
| --- | --- | --- |
| `HeroTmdbData` | L193-223 | 跨页 TMDB 数据模型 |
| `HeroTmdbPrefetchCache` | L225-283 | 内存预取缓存 |
| `HeroLogoCache` | L285-356 | `posterKey → logoUrl` 落盘 + 内存缓存 |
| `clearHeroCaches()` | L358-375 | 「清理缓存」联动入口 |
| `heroTmdbKey()` | L377-383 | `站点 key \| 条目 id` |
| `tmdbLogoKey()` | L385-400 | `tmdb \| 媒体类型 \| tmdbId` |
| `TMDB_IMAGE_WIDTHS` | L2535-2538 | 图片档位表（私有） |
| `upgradeTmdbImage()` | L2540-2562 | 只升不降的档位升级 |
| `rememberHeroImageModel()` | L2718-2738 | 统一 Coil 请求 |
| `HERO_IMAGE_CROSSFADE_MS` | L2867-2868 | 统一淡入时长（私有） |

搬迁方式是「按行切片复制」，注释与常量值逐字保留，仅包名变化（`ui.activity` → `ui.hero`）。
`HeroShared.kt` 最小 import 集：`Context`、`Composable`、`remember`、`LocalContext`、
`SingletonImageLoader`、`ImageRequest`、`crossfade`、`App`、`TmdbItem`、`TmdbPerson`、
`JsonObject`、`ConcurrentHashMap`。

消费方 import 替换（同包消费者此前无需 import，现在必须显式引入）：

- `HomeViewActivity.kt`：`HeroTmdbData`、`HeroTmdbPrefetchCache`、`heroTmdbKey`、`upgradeTmdbImage`
- `HeroDetailActivity.kt`：`HeroTmdbData`、`HeroTmdbPrefetchCache`、`heroTmdbKey`、`upgradeTmdbImage`、`rememberHeroImageModel`
- `HistoryViewActivity.kt`：`HeroLogoCache`、`HeroTmdbPrefetchCache`、`heroTmdbKey`、`tmdbLogoKey`、`upgradeTmdbImage`
- `KeepViewActivity.kt`：`HeroLogoCache`、`HeroTmdbPrefetchCache`、`heroTmdbKey`、`tmdbLogoKey`、`upgradeTmdbImage`
- `leanback VideoActivity.java`：`import com.fongmi.android.tv.ui.hero.HeroLogoCache;`（`HeroLogoCache.INSTANCE.get(...)` 调用点未动）
- `utils/FileUtil.java`：`com.fongmi.android.tv.ui.activity.HeroHomeActivityKt.clearHeroCaches()` →
  `com.fongmi.android.tv.ui.hero.HeroSharedKt.clearHeroCaches()`（Kotlin 顶层函数门面随文件改名）
- `ui/hero/HeroPosterAdapter.kt`：本就在 `ui.hero` 包内，`heroTmdbKey` 不需要 import

`HomeViewActivity.kt` 两处描述 `HeroLogoCache` 是「`HeroHomeActivity.kt` 内 file-private object」的注释已过期，
改为指向 `HeroShared.kt`；`readLogoCache()` 的只读实现与 `LOGO_CACHE_PREFS` 未改动，行为不变。

删除：`app/src/main/java/com/fongmi/android/tv/ui/activity/HeroHomeActivity.kt`（3817 行）。

### 模块二：删除旧历史页

删除文件：

- `app/src/leanback/java/com/fongmi/android/tv/ui/activity/HistoryActivity.java`（157 行）
- `app/src/mobile/java/com/fongmi/android/tv/ui/activity/HistoryActivity.java`（182 行）
- `app/src/leanback/res/layout/activity_history.xml`（仅这两个页面使用）
- `app/src/mobile/res/layout/activity_history.xml`（同上）
- `app/src/mobile/res/menu/menu_history.xml`（仅 mobile `HistoryActivity` 与上面的布局引用）

清单去掉 `<activity android:name=".ui.activity.HistoryActivity" .../>` 节点（leanback / mobile 各一处）。

路由改指新页 `HistoryViewActivity`（Kotlin `companion object` 无 `@JvmStatic`，Java 侧用 `Companion.start`）：

- `leanback HomeActivity.java:964`：`R.string.home_history_button` → `HistoryViewActivity.Companion.start(this)`
- `leanback HomeActivity.java:1106`：`onHomeMenuItem case 4` → `HistoryViewActivity.Companion.start(this)`
- `mobile VodFragment.java:293`：`R.id.history` → `HistoryViewActivity.Companion.start(requireActivity())`（import 同步替换）

测试同步（原先以「读源码字符串」方式断言旧页，删文件后必须改）：

- `GlobalHistoryResumeSourceTest`：断言目标改为 `HistoryViewActivity.kt` 的
  `VideoActivity.startFromHistoryFullscreen(this, history)`，并补两条「两个首页都指向 `HistoryViewActivity`」断言；
  原先「历史页监听 `ConfigEvent` 刷新标签」的段落改为断言新页监听 `RefreshEvent.Type.HISTORY` 刷新行。
  `History.java` 的标签 gating 断言保持不变。
- `FocusRestorerTest`：删除只针对已删 leanback 历史页的
  `leanbackHistoryPageRestoresFocusByItemIdentityAndCancelsOnExit`（24 行）；
  `leanbackHistoryAdapterPassesTheBoundPositionOnDelete`（仍读 `HistoryAdapter.java`）保留。
- `VideoActivityLayoutTest`：两个历史入口测试改为读 `HistoryViewActivity.kt`，
  断言 `VideoActivity.startFromHistoryFullscreen(...)` 为唯一入口；`startFromHistory` 内部的
  详情模式分流 / `startDirect` 参数断言全部保留。
- `HistoryAdapterTest`：去掉对已删 mobile `HistoryActivity` 的读取与 `mobile history page` 走马灯断言
  （新历史页没有滚动走马灯区间驱动），keep 页与适配器本身的断言保留。

### 模块三：残留核查

- `app/src/main/AndroidManifest.xml`：无 `HeroHomeActivity` 节点；`HomeViewActivity` 仍是唯一
  `MAIN` + `LAUNCHER` / `LEANBACK_LAUNCHER`。
- `app/src/{leanback,mobile}/AndroidManifest.xml`：无两个被删页面的节点（仅保留说明性注释）。
- 全仓 `app/src` 内 `HeroHomeActivity` / `HistoryActivity` 仅剩注释性历史说明，无代码引用；
  `HistoryViewActivity` 自身的 KDoc 提到旧页属于历史沿革，保留。

## 验证（本轮不编译，全部为静态）

1. `HeroShared.kt`：300 行，花括号 21/21、圆括号 108/108 配平；文件以单个 LF 结尾。
2. 8 个搬迁符号的消费方静态扫描（剔除注释后按行判定）：除 `ui.hero` 包内文件与
   `FileUtil.java` 的全限定调用外，所有出现处都有 `import com.fongmi.android.tv.ui.hero.<符号>`，
   失败数 0；`com.fongmi.android.tv.ui.activity.<符号>` 旧全限定引用 0 处；重复声明 0 处。
3. 三个 `AndroidManifest.xml` 通过 XML 解析（leanback 27 activities / mobile 14 / main 11），
   调整前后 XML OK。
4. 修改后的 `.kt` / `.java` 花括号配平与 HEAD 版本逐文件对比，无新增失衡；
   `VideoActivityLayoutTest` 的 +4 差异为改动前既有（字符串内的 `{`）。
5. `git grep` 结果：`HistoryActivity` / `menu_history` / `activity_history` 在 `app/src` 中仅剩
   `HistoryViewActivity.kt` 的三处历史注释。

## 已知副作用与待决策（未在本轮改动）

1. **观影报告入口消失**：`ViewingReportActivity` 此前只能从两套旧历史页进入
   （leanback 的 `reportButton`、mobile 的 `menu_history` 里的「观影报告」）。旧页删除后，
   Activity、数据与 AI 分析链路仍在、清单节点仍在，但**没有任何入口**。
   建议后续在 `HistoryViewActivity`（或新首页菜单）补一个入口，属于新增 UI 行为，本轮未做。
2. **历史续播入口差异**：旧页点击记录走 `HistoryResumeCoordinator.open(this, item)`
   （源站失效时自动搜索换源）；新页走 `VideoActivity.startFromHistoryFullscreen(...)`
   （直接按记录里的源启动，失败提示记录缺失）。新首页（`HomeViewActivity`）本来就走新页，
   所以这是既有差异；本轮只是让 legacy 首页与新首页对齐。
3. `HistoryAdapter`（leanback / mobile）与 `FocusRestorer` 删除旧页后已无生产代码使用，
   但仍被测试引用、可正常编译，按「不扩大改动面」保留，后续可单独清理。

## 回滚

单个提交即可整块回滚：`git revert <commit>`。抽离文件与删除文件同属一个逻辑单元，
不涉及资源 ID、清单权限或序列化格式变更，回滚后旧首页 / 旧历史页可原样恢复。

## 下一步

- 若确认观影报告需要保留入口，新建一条独立任务，在 `HistoryViewActivity` 增加菜单/按钮入口。
- 若要继续清理，可评估删除 `HistoryAdapter`（两 flavor）、`ActivityHistoryBinding` 生成物相关资源
  与对应源码测试断言。
