---
name: AVBox 渐进式重构 Spec（架构与规模专项）
status: 执行中（2026-09-28：阶段 1–2（含 2b）、3–6 已落地；阶段 6.3 经实测驳回；阶段 1b 待决策；阶段 7 随播放服务化 spec）
source: 2026-09-28 按 `skill/avbox-code-review-spec.md` 的「架构与分层 / 类规模与函数 / 代码写法规范」三节做的范围审查，加派生分析（拆分下限、包级环、DI 决策）
---

# 结论摘要

- **不引入 DI 容器**（判据见 §6）；**不拆 Gradle module**；**不重写 Java→Kotlin**；**不动 `player` 模块上游内核**。
- 拆分目标：单文件 150–350 行、主类 ≤500 行、单方法 ≤100 行；全量做完 app 约 290 → 320–340 个文件，最大文件从 2023 行降到 ~500 行。
- 动手顺序：UI 段落外提 → 匿名块具名化 → 重复模板 helper → ApiConfig 实现外迁 → SourceViewModel 分段 → 解环 → PlaybackController（并入播放服务化 spec，不独立立项）。
- 每步独立提交、可独立回滚；每步落地跑 `:app:assembleDebug` + `:app:testDebugUnitTest`。

# 1. 现状度量（基线，口径见附录 A）

| 指标 | 实测 | 说明 |
| --- | --- | --- |
| 文件 > 500 行 | **36 个**（其中 15 个 > 800） | PlaybackController 2023 / SourceViewModel 1946 / PlayContainer 1839 / ApiConfig 1794 / ComposeVideoController.kt 1426 / LivePlayActivity.kt 1279 |
| 方法 > 100 行 | **17 处**（±10 行估算） | getSort 250 / DetailContent 198 / initView 157 / getList 145 / play(boolean) 129 / Thunder.play 123 / initFetch 111 / RemoteServer.serve 111 … |
| 巨型匿名类/内联块 >80 行 | 6+ 处 | PlayContainer.viewBridge 277 / PlaybackEngine.HeadlessView 235 / ProtectedInitJar.Dex 180 / Composable 内联块 150+ |
| 重复模板 `newSingleThreadExecutor + submit + get(timeout) + shutdown` | **19 处 / 10 文件** | SourceViewModel 占 6 处；超时口径 4 种（30s / 20s / 6s / playTimeoutSeconds） |
| 门面被依赖 | `ApiConfig.get()` 31 文件 110 处；`.getInstance()` 116 处 / 50 文件；静态单例入口 7 个 | KV 门面 50 文件 238 处属设计预期，不计异常 |
| 包级环（双向依赖） | **24 组** | 其中真层间环 3 组、`osc.util` 枢纽环 12 组、UI 内部 4 组、可不管 5 组 |

# 2. 问题清单（仅三节范围内；严重度按 `avbox-code-review-spec.md` 锚点）

| # | 问题 | 严重度 | 引入维度 | 位置 |
| --- | --- | --- | --- | --- |
| ① | `SourceViewModel` 1946 行六职责同处一类 | 中 | 既有 | `app/src/main/java/com/github/tvbox/osc/viewmodel/SourceViewModel.java:238` |
| ② | `PlaybackController` 2023 行：会话/超时/换线/嗅探/预载/进度耦合 | 中 | 既有 | `app/src/main/java/com/github/tvbox/osc/player/PlaybackController.java:1360` |
| ③ | `ApiConfig` 门面失控（配置+站源+spider+代理+预热同类，110 处调用） | 中 | 既有 | `app/src/main/java/com/github/tvbox/osc/api/ApiConfig.java:182` |
| ④ | 默认配置以 3135 字符单行字面量硬编码（含 57 条广告域名） | 中 | 既有 | `app/src/main/java/com/github/tvbox/osc/api/ApiConfig.java:941` |
| ⑤ | 包级循环依赖（业务↔UI、业务↔VM、数据↔缓存、util 枢纽） | 中 | 既有 | `player/PlaybackController.java:21`、`PlaybackEngine.java:16`、`PlaybackViewBridge.java:6`、`data/AppDataBase.java:6` |
| ⑥ | 超长方法 17 处（无单一职责） | 低 | 既有 | 见附录 A 表 |
| ⑦ | 巨型匿名类/内联块 | 低 | 既有 | `ui/player/PlayContainer.java:165`、`player/PlaybackEngine.java:631` |
| ⑧ | 重复模板 19 处 + 超时口径分散 4 种 | 低 | 既有 | `viewmodel/SourceViewModel.java:283/511/645/779/1053/1596` |
| ⑨ | UI 直触数据源（Compose 页/Activity 直接调 Room、KV） | 低-中 | 既有 | `ui/page/CollectPage.kt:116`、`ui/components/VodCardMenu.kt:32`、`ui/page/HistoryPage.kt:149` |
| ⑩ | UI 层业务编排（并发/超时/IO 切换写在 Activity） | 低 | 既有 | `ui/activity/SearchActivity.kt:317` |
| ⑪ | 注释红线存量（日期注释 160 处/47 文件、`BugReview #` 23 处） | 低 | 既有 | 抽样 `viewmodel/SourceViewModel.java:1431` |
| ⑫ | `sortCache` 竞争面被"主线程入口挪后台"改动放大（读者变 3 个池线程） | 低 | 既有被放大 | `viewmodel/SourceViewModel.java:119` |

# 3. 可拆下限（"能拆多小"）

| 文件 | 可拆到 | 依据 |
| --- | --- | --- |
| SourceViewModel 1946 | 5–7 文件，各 150–350（门面 ~250） | 仅 9 个字段、6 个职责簇 |
| PlayContainer 1839 | 4–6 文件，各 250–450 | 15 个匿名块先具名化，字段属 4 簇 |
| ApiConfig 1794 | 门面 ≤200 + 4 文件各 150–350 | 36 文件 188 处引用 ⇒ 签名不能动，只外迁实现 |
| ComposeVideoController.kt 1426 | 5–7 文件，各 150–300 | 6 字段 / 139 方法 ⇒ 多为 UI 构建与回调转发 |
| LivePlayActivity 1279 | Activity ≤250 + 4–6 个 Composable 文件 | 字段仅 4 个，搬迁零状态成本 |
| SearchActivity 939 | 3–4 文件 | `search()` 67 行编排外提 |
| PlaybackController 2023 | 4–5 文件，主类 400–500 | 28 字段 = 4 状态簇；归播放服务化 spec |

- ⚠️ **更正（2026-09-28 实施时）**：上表 LivePlayActivity 一行的「字段仅 4 个 / Activity ≤250」不成立 —— 该文件的 Compose UI 段 2026-09-15 就已外提为 `LiveScreens.kt`，现存 1073 行是**有状态**的频道列表簇、播放会话簇与装配代码；压缩需另立「阶段 1b」（中风险，见 §4 阶段 1 执行状态）。

- ⚠️ **更正（2026-09-28 实施时，第二条）**：上表 PlayContainer 一行的「15 个匿名块」不成立 —— 全库扫描（Java `new X() {` + Kotlin `object : X {`）后 >60 行的匿名块共 8 处，>80 行 4 处；PlayContainer 只有 2 处（`viewBridge` 277 + `VodControlListener` 133）。且这两个适配器共依赖宿主约 20 个 private 成员，远超本表下方「每多拆一个文件暴露 2–4 个包级成员」的硬地板 ⇒ 阶段 2 先做同文件具名化；**文件边界外提已作为阶段 2b 由已确认完成**（`PlayContainer` 1843 → 1424 行，代价 = 23 个成员降为包级，暴露面限 `ui.player` 包；见 §4 阶段 2 执行状态）。

**三条硬地板**：① Activity/Service/Application 各留一个；② 静态门面签名（`ApiConfig.get()`、KV）必须保留；③ Java `private` 状态跨文件需降级或构造传递——经验值每多拆一个文件新增暴露 2–4 个包级成员，**低于 ~120 行/文件即进入碎片化**，目标区间取 150–350。

# 4. 渐进式重构计划

## 阶段 1｜UI 段落外提（风险最低）

**执行状态（2026-09-28）**：

- 已落地（4 个本地 commit，未推远程）：detail / history / search 三项按预期达成（`0fbb714` / `076acec` / `4dcbb07`）；live 只完成「浮层与信息条」外提（`82f56c5`），`LivePlayActivity` 1279 → **1073**，未达 ≤250 —— 原因见 §3 更正。
- 未做（建议另立 **阶段 1b**）：Live 的**频道列表簇**（列表/密码/展开/设置项数据，约 330 行 → `LiveChannelListController`）与**播放会话簇**（切台/超时换源/回看/接管/直播源头，约 400 行 → `LivePlaybackSession`）。这两簇是**有状态**搬迁（不是本阶段设定的"无状态"前提），风险中，需单独立项 + 直播链路走查。
- 验证：每步 `assembleDebug` + `testDebugUnitTest`（44 类 / 364 用例）全绿；逐行等价性脚本 0 丢失 / 0 新增；已装机待走查。
- 事实同步：`skill/avbox-mobile-ui-spec.md` §2（文件布局 + 单测基线）已更新；实施过程归档见 `skill/history/features.md` 2026-09-28 条目。

- 目标：Activity 只留生命周期与装配。
- 修改内容：`LivePlayActivity` 的 UI 段外提到独立 Composable 文件；`DetailScreens`/`HistoryPage` 分段；`SearchActivity.search()` 的编排挪到 `SearchCoordinator`/ViewModel。
- 涉及文件：`app/src/main/java/com/github/tvbox/osc/ui/activity/LivePlayActivity.kt`、`ui/activity/DetailScreens.kt`、`ui/page/HistoryPage.kt`、`ui/activity/SearchActivity.kt`（新增若干 `*Screen*.kt`）。
- 风险：低（无状态搬迁）；须保持 Compose 状态提升位置正确。
- 收益：LivePlayActivity ≤250 行、DetailScreens 拆 3 文件各 ≤300、HistoryPage ≤300。
- Commit：每文件一个；验证：构建 + 单测（含 `DetailFullScreenGateTest`、`LiveChannelNavigatorTest` 等既有用例）。

## 阶段 2｜匿名块具名化

**执行状态（2026-09-28）**：

- 已落地（3 个本地 commit，未推远程）：`PlayContainer` 的 `viewBridge`(277) 与 `VodControlListener`(133) → 同文件具名内部类 `ViewBridge` / `ControlListener`；`Thunder.java:116` 的 109 行匿名 `Runnable` → 具名嵌套类 `ParseTask`（`parse()` 117 → 9 行）；`BottomSheet.kt:367` 的 154 行内联块 → 具名 Composable `SheetSurface`（`SheetOverlay` 226 → 约 90 行）。
- **口径更正**：本列表里的 `PlaybackEngine.HeadlessView`(235) 与 `ProtectedInitJar.Dex`(180) 核对后**本就是具名类**（内部类/静态嵌套类），无需处理；全库 >80 行匿名块实际只有上述 3 处 + `PlaybackController.java:1075` 的 107 行匿名 `Observer` —— 后者按阶段 7 归属本阶段未动。
- **文件边界外提已在同日作为「阶段 2b」完成（commit `54c8f07`，已确认）**：两个适配器 → `ui/player/PlayContainerViewBridge.java`(302 行) 与 `PlayContainerControlListener.java`(156 行)，各持 `container` 引用；`PlayContainer` 1843 → **1424 行**（−419）。代价 = **23 个成员由 `private` 降为包级**（字段 7 + 方法 16）——对本 § 硬地板「每文件 2–4 个」的一次明示例外；因暴露面仅限 `ui.player` 包（包内另两个文件不使用这些成员）且由已确认，接受。
- 验证：`assembleDebug` + `testDebugUnitTest`（44 类 / 364 用例）全绿；逐行等价性脚本 0 丢失 / 0 新增（PlayContainer 仅 2 行包装差、Thunder 仅 `}});` 一行、BottomSheet 仅调用点与签名行）；`adb install` 被设备侧拒绝（`INSTALL_FAILED_ABORTED`），走查待已确认安装。
- 归档见 `skill/history/features.md` 2026-09-28 阶段 2 条目。

- 目标：消掉 >80 行匿名类/内联块。
- 修改内容：`PlayContainer.java:165` 的 `viewBridge`(277) → `PlayContainerViewBridge`；`PlaybackEngine.java:631 HeadlessView`(235) → 具名类；`ProtectedInitJar.java:263 Dex`(180)；`BottomSheet.kt:367` 等 150+ 行内联 Composable → 具名函数。
- 风险：低-中（匿名类捕获变量需构造传参）。
- 收益：PlayContainer 1839 → ~1200；可单测化。
- Commit：每个具名化一个 commit。

## 阶段 3｜重复模板 helper + 超时口径统一（收益最高）

**执行状态（2026-09-28）**：

- **口径更正（第三条）**：本节的「19 处模板 / 10 文件」是 `Select-String 'Executors\.newSingleThreadExecutor\(\)'` 的**字面出现次数**，不是模板实例数。逐处核对后真正符合「一次性 executor + `submit` + `get(timeout)` + `shutdown`」的只有 **6 处**（`SourceViewModel` getSort / getList / getHomeRecList / getDetail / getPlay + `LiveProxyLoader` 直播 py/js 分支）；`SourceViewModel` 第 6 处（推送代理）是 `CountDownLatch.await(15, …)`，与 `Callable` 不同形。
- 已落地两笔（本地 commit，未推远程）：`1026fe5` 抽模板（`util/BoundedCall.kt` + 3 例单测，各点原超时值一字未动）；`a034ac7` 超时口径统一（站点数据请求取 `SourceBean.getPlayTimeoutSeconds()`，显式覆盖保留 6s / 10s / 120s / `liveConnectTimeoutSeconds`）。口径由 4 种收敛为 2 种（站点级默认 + 显式覆盖）。
- **本节「涉及文件」余下不改**，逐条理由：`JsSpider` 的 3 处有界等待必须落在 QuickJS 专属单线程（线程亲和），且超时不能 cancel，已有私有 `submitAndWait`；`ApiConfig.warmOneSource` 是预热队列「独占线程 + 单项 10s 不打断」语义，换一次性线程会变成并发预热；`SourceViewModel.getFixUrl` 是共享池 `spThreadPool` 上的有界等待（超时回退原值，非"新建-等待-销毁"）；`SpiderLoader`（jar 装载 / 弹幕搜索）、`PlayUrlResolver`（解析池）、`Thunder`（解析池）、`DanmuLoadController`（弹幕解析）、`ConfigManagePage.kt`（副本清理）、`LOG`（落盘）的 `newSingleThreadExecutor()` 均为长生命周期池或投递即走，全库无 `get(timeout)`。
- 数值统一的实际变化：getSort 30→15、getHomeRecList 20→15、getDetail（非 fallback）30→15、`getFixUrl` 默认 20→15；getList / getPlay 本就取站点值不变。**慢源的必然表现（走查判据补充）**：首页 sort 15s 落空态且不再命中 `HomeViewModel` 的 20s 看门狗（挂死源失去「Error + 重试」态）；详情 15s 落 `handleEmptyDetail(null)` ⇒ 可能**自动换站**；`getFixUrl` 超时返回**原始 extend** 而非空；jar 首次装载（下载+init 都在 `getCSP` 内）>15s 时首页/详情需重试一次。慢源若回归：把该源配置 `timeout` 调到 30–60，或单独 revert `a034ac7`。
- 验证：`assembleDebug` + `testDebugUnitTest`（45 类 / 367 用例 / 0 失败）；已装机（1.1.6）；走查未做。
- 归档见 `skill/history/features.md` 2026-09-28 阶段 3 条目。

- 目标：19 处模板收敛为 1 个有界调用工具。
- 修改内容：新增 `app/src/main/java/com/github/tvbox/osc/util/BoundedCall.kt`（Kotlin object，`@JvmStatic fun <T> call(task: Callable<T>, timeoutMs: Long, tag: String): T?`，超时返回 null 并记日志；新代码用 Kotlin，Java 侧经 `@JvmStatic` 调用）；**先只抽模板、保留各点原超时值**；数值统一作为独立 commit。
- 涉及文件：`viewmodel/SourceViewModel.java`（6 处）、`api/SpiderLoader.java`、`player/PlayUrlResolver.java`、`util/thunder/Thunder.java`、`ui/activity/LiveProxyLoader.kt`、`player/danmu/DanmuLoadController.java`、`api/ApiConfig.java`、`catvod/crawler/js/JsSpider.java`、`ui/page/ConfigManagePage.kt`。
- 风险：中（超时值改动会波及慢源可用性 ⇒ 与抽模板分两个 commit）。
- 收益：模板 19 → 1；口径从 4 种 → 2 种（站点级默认 + 显式覆盖）。
- 验证：构建 + 单测；真机走查换源/详情/取流不回归。

## 阶段 4｜ApiConfig 实现外迁 + 硬编码配置下沉

**执行状态（2026-09-28）**：

- 已落地（6 个本地 commit，未推远程）：`a352eaf` 默认配置下沉（`app/src/main/assets/default_config.json`，3635B，57 条广告 + ijk 硬/软两档原样）；`d9c0b0f` `ProxyEntry`（141 行）；`7510f95` `WarmQueue`（104 行）；`d817b49` `ConfigLoader`（445 行，加载编排 + 本地源判断 + 仓分流）；`25f442f` `ConfigApplier`（187 行，`parseJson` 的 rules/doh/ads/parses 段外提）；`703487f` 默认 IJK 档位外迁。
- 实测：ApiConfig **1794 → 1089 行**（−705）；每笔用「旧文件（`git show HEAD:`）逐行去空白 + 多重集比对」卡口，差异全部为 `owner.`/`ApiConfig.` 前缀、可见性与注释；`assembleDebug` + `testDebugUnitTest`（45 类 / 367 例）全绿；APK 内 assets 与源文件逐字一致；已装机。
- **口径更正（第四条）**：本节「门面 ≤200 行」不成立 —— 静态门面签名不能动（31 文件 110 处 `ApiConfig.get()`），40 余个门面方法 + 字段声明本身 ≈400 行；要压到 200 需把状态一并外迁、让每个 getter 变纯转发（高风险大重排，违反 §5「不为行数达标硬拆」）。实际可达口径 = 1089 行 + 5 个职责文件。
- 封装代价：门面 **7 个成员**降为包级（`parseJson`/`parseLiveConfigContent`/`hasLiveConfigResult`/`clearLiveConfigResult`/`str`/`localFileBase` + `loadedLiveConfigUrl`），暴露面限 `api` 包；`ProxyEntry`/`WarmQueue` 构造注入 `SpiderLoader`、`ConfigApplier` 全走返回值 —— 三者零降级（对比阶段 2b 的 23 个）。
- **未做**：`SourceRepository`（getSource/getSwitchSourceBeanList/getCSP 合计 26 行，搬迁净减 ~14 行却需降级 `sourceBeanList`/`mHomeSource`，收益/暴露比不成立；`getSource` 还带 `push_agent` 合成条目特例）；直播解析链（`parseLive*`/`loadLives`/`loadLiveApi`/`initLiveSettings` ≈420 行）属直播可用性关键路径且阶段 3 走查未完成，建议单独立项。
- 硬约束核对：`VideoParseRuler.clearRule()` 仍在 `parseJson` 入口与 `ConfigApplier.applyHostRules` 的 rules 分支各 1 处（与改前逐字一致）；`ApiConfig.FindResult` 与 `ConfigParser` 未动。实施过程归档见 `skill/history/features.md` 2026-09-28 阶段 4 条目。

- 目标：门面 ≤200 行；默认配置出代码。
- 修改内容：外迁 `ConfigLoader`(loadConfig/loadLiveConfig/parseJson)、`SourceRepository`(getSource/getSwitchSourceBeanList/getCSP)、`WarmQueue`(warmSearchSpiders/warmOneSource)、`ProxyEntry`(proxyInvoke/proxyDirect)；`ApiConfig.java:941` 的 3135 字符默认配置 → `app/src/main/assets/default_config.json`（含广告域名与 ijk 默认参数）。
- 风险：中-高（配置解析是高危链路：`VideoParseRuler.clearRule()` 必须留在 `parseJson` 入口）。**任何类/包重命名前必须全库检索类名与 jar 契约（spider jar 按类名/包名加载）**。
- 收益：ApiConfig 1794 → 门面 ≤200 + 4 文件；默认配置改动不再碰代码。
- Commit：外迁每个类一个 commit；配置下沉单独一个。

## 阶段 5｜SourceViewModel 六职责分段

**执行状态（2026-09-28）**：

- 已落地（12 个本地 commit，未推远程）：`39342b0` 共享取数支撑（`SourceHelper`，199 行）→ `abeed50` 推送详情后处理（`PushDetailResolver`，263）→ `22c6b3d` 解析与分投（`SourceResultParser`，242）→ `44a95fd` push:// 解析（`PushUrlParser`，117）→ `d036b7e` 列表取数（`ListLoader`，258）→ `3688b14` 首页取数（`SortLoader`，349）→ `47b4b59` 详情取数（`DetailLoader`，202）→ `1beef65` 搜索取数（`SearchLoader`，156）→ `7b5d66f` 取流（`PlayLoader`，328）→ `aa5b132` `sortCache` 加锁（⑫）→ `c7d88ba` 清掉重复的 `checkThunder` 委托 → `6a52f96` 收紧可见性 + 修注释口径。
- 实测：`SourceViewModel` **1873 → 168 行**；9 个同包新文件共 2114 行（原文件搬迁后的净增 = 类声明/构造/import/javadoc）。
- **口径更正（第五条）**：本节的「6 个同包 sibling」实际落成 **5 个 Loader + `PushUrlParser`（6 个按计划）＋ 3 个支撑类**（`SourceHelper`/`SourceResultParser`/`PushDetailResolver`）。多出的 3 个不是可选拆分：`xml`/`json`/`sortXml`/`sortJson` 的解析-分投层被 5 个 Loader 共用，`push`/迅雷的详情后处理被解析层与门面共用，`siteGet`/`absXml`/`getFixUrl` 被 6 处共用 —— 留在门面等于再造一个 ~430 行的门面类，与「门面只做转发」的目标相反。同包内以包级静态方法复用是 Java 无 `friend` 时的可达解。
- **第二条口径更正**：计划的「2 个线程池留在门面、经构造传入 sibling」改为**池声明落 `SourceHelper`，门面保留同名公开别名** `public static final ExecutorService spThreadPool = SourceHelper.SPIDER_POOL;`（签名不变）。原因：构造注入两个 `ExecutorService` 要给 7 个类各加两个参数，而静态别名零成本；若池留在门面、sibling 直接引用 `SourceViewModel.spThreadPool`，反而造出 sibling → 门面的文件级环。
- **门面 API 逐条核对**：改前 8 个 public 字段（7 个结果通道 + `spThreadPool`）与 13 个 public 方法（`getSort`×2 / `getList` / `getDetail`×2 / `action` / `getSearch`×2 / `getPlay` / `getPlayForPreload` / `cancelPlayRequest` / `checkThunder` / `clearRuntimeCache`，另有构造器）**逐条保留**：`public` 行集比对后唯一差异 = `spThreadPool` 的初始化表达式（池实例本身同一个）。匿名类内的 `public` 成员随匿名类一起搬入 sibling，不计门面 API。
- 封装代价：门面自身 **0 降级**（对比阶段 2b 的 23、阶段 4 的 7）；代价转为「新增 sibling 之间」的包级成员 **40 个**（`SourceHelper` 10 / `PushUrlParser` 9 / `SourceResultParser` 7 / `ListLoader` 3 / `SortLoader` 2 / `DetailLoader` 2 / `SearchLoader` 2 / `PlayLoader` 3 / `PushDetailResolver` 2），暴露面限 `viewmodel` 包。这是拆分的固有代价，记录在案。
- **顺带加锁（⑫）**：`sortCache` 是 access-order `LinkedHashMap`（`get` 也改结构）而读写它的有多个池线程；`SortLoader` 的 `get`/`put` 与门面 `clearRuntimeCache` 现在都走 `synchronized (sortCache)`，持锁期间不做 IO。
- **顺带删除**：两处注释掉的死代码（`absXml` 里的旧循环 7 行、`json` 里的测试 JSON 17 行）= 24 行，其余搬运逐字保留（含 `// i18n: keep` 与日期注释 —— 注释红线存量仍按 ⑪ 留给专项）。
- 验证（每笔落地跑一次）：`assembleDebug` + `testDebugUnitTest`（**45 类 / 367 用例 / 0 失败**）；每笔用「旧文件 `git show HEAD:` 逐行去空白 + 多重集比对」，并做「67 个方法声明的存在性」与「public 行集」双卡口；累计口径下 111 条 missing 行全部为调用点前缀/可见性/新增类声明与上述 24 行死注释，无语句丢失。
- 实施踩坑（供后续阶段避开）：① 用脚本切「区域」时必须**先切片、后改可见性**，我曾把改过的切片再拿去 `str.replace` 删除原文，替换静默失败（Python 的 `replace` 不报错），导致门面里同时存在定义与 `owner.` 前缀调用；② 结束标记必须在全库查重（`checkThunder` 委托恰好落在两个切片边界上，被带进了 `SourceResultParser`，成为死代码后才发现）。两道卡口（编译 + public 行集 + 行集比对）把这两个问题都拦住了。
- 走查判据（未做，属真机环节）：t4 源首页/详情/起播不卡；换源后 sortCache 清理仍生效；推送链接与磁力（迅雷）两条特殊详情路径不回归。
- 归档见 `skill/history/features.md` 2026-09-28 阶段 5 条目。
- **复查与修复轮（2026-09-28）**：阶段 5+6 的对抗性复查 + 修复记录见 `skill/review/review-20260928-batch1.md`；其中 F6 补齐把 `getSort`/`getPlayPrepared`/`getList`/`getDetail`/`getSearch` 五个超长方法按 type 分流拆完（附录 A 的「方法 >100 行」36 → 31），F7 补了 9 条取数链路单测（单测基线 367 → 376）。

- 目标：1946 → 门面 ~250 + 6 个同包 sibling 各 120–350。
- 修改内容：`SortLoader` / `ListLoader` / `DetailLoader` / `PlayLoader` / `SearchLoader` / `PushUrlParser`；6 个 LiveData 通道、2 个线程池、`extendCache`/`sortCache` 留在门面，经构造传入 sibling。
- 风险：中（结果通道与 seq 归属必须保持；上一轮"主线程入口挪后台"的守卫不得丢；**不得**把 `sortCache` 竞争面进一步扩大，建议本阶段顺带加锁）。
- 收益：单文件 ≤350；新增取数流程不再改 1946 行文件。
- Commit：每个 Loader 一个。

## 阶段 6｜解环（依赖方向专项）

**执行状态（2026-09-28）**：5 个子步落地、1 个子步经实测**驳回**（6.3）；环数 **25 → 17**（度量口径见本节末），edges 226 → 215。

| 子步 | commit | 做法 | 环数 |
| --- | --- | --- | --- |
| 6.1 | `e624522` | `ui.player.PreloadCoordinator` **整体搬进 `player`**（它不是页面资源：由引擎创建、随引擎存活、除所在包外零 UI 依赖）；`PlaybackController` 用 `view.showTip(...)` 取代 `PlayerTipBridge.setTip(...)`；`PlaybackEngine` 的 `PlayContainer` 只留 javadoc 引用（`@link`→`@code`） | 25 → 24 |
| 6.2 | `457ce63` | `SubtitleFilePicker` → `util`；`RemoteTVBox` → `util`（顺带 `EpisodeMatcher` → `util` 并转 public：`SubtitleFilePicker` 依赖它，而它是包级私有，`player` 包不该为一次跨包使用扩大公开面） | 24 → 23 |
| 6.3 | **未做（驳回）** | 见下「6.3 驳回依据」 | — |
| 6.4 | `b79faea` | 实体 + DAO + `AppDataBase` 归 `data`，`cache` 整包并入 `data`（`CacheManager`/`RoomDataManger` 与 `AppDataManager` 互相引用，只挪一半环仍在） | 23 → 22 |
| 6.5 | `ccfa18b` | 新增 `util.AppContextHolder`（零依赖，`App.onCreate` 注入一次），把 util/data/server/catvod 里 **纯 Context 用法**的 `App.getInstance()` 全量替换（16 个文件 43 处） | 22 → 20 |
| 6.6 | `4aa5120` | `ui.components` 不再引用 activity/page/navbar：搜索设置面板改回调（`onSelectionChanged`）、卡片菜单改回调（`onSearchSimilar`）、玻璃带边距常量下沉 `ui.theme` | 20 → 17 |

**6.3 驳回依据（实测，非猜测）**：写了模拟器把「util 根下 55 个文件按有无向上依赖拆 `util.core` / `util.integration`」直接重算包级环 —— **环数 23 → 23（不降）**，edges 222 → 247，需改写 **361 行 import**。原因是结构性的：把类搬进新包只让「节点改名」，不切断任何双向边（`X ⇄ util` 变 `X ⇄ util.integration`）；要真正消环，必须让那 25 个胶水文件**不再向上 import**（`DefaultConfig`→`ApiConfig`、`PlayerHelper`→`player.*`、`SearchHelper`→`ApiConfig`、`HistoryWriter`→`RoomDataManger`…），那是 §6「构造器注入 + 接口化解环」的活，不是包移动能做的。另外计划里「`util.core`（零反向依赖：LOG/MD5/文本）」的前提在本库不成立：**`LOG` 自己 import `base.App`，`MD5` 同**，真正的零出边文件只有 30 个且多为冷门工具。故本子步按「不为达标硬拆」跳过，改动留在 6.5 的 `AppContextHolder`（把 util→base 的 10 条边降到 2 条）。

**剩余 17 组环的构成（逐条可解释）**：
- **`util` 枢纽 9 组**（api 54/5、base 13/2、bean 10/10、data 7/2、player 50/6、player.danmu 6/1、player.thirdparty 1/4、server 8/4、net 3/1）：上表已证「包移动无法消」，只能逐类把 util 胶水的向上 import 反转（构造注入/传参/接口），属独立工程（§6 替代方案）。
- **父子里程/同族互引 4 组**（`crawler ⇄ crawler.js`、`subtitle.format ⇄ subtitle.model`、`util ⇄ util.kv`、`util ⇄ util.parser`）：按附录 B 保留。
- **`crawler ⇄ osc.util`**：唯一来源 = `util/Proxy.java` 用 `SpiderDebug.log(...)` 走爬虫调试通道（4 处）；改成 `LOG` 会丢调试通道语义，且 `SpiderDebug` 是爬虫 jar 的公开面，不能挪包。
- **`osc.base ⇄ osc.server`、`osc.base ⇄ osc.util`**：残留 = `App.vodInfo` / `getDashData()` 这条**隐式通道**（`PlaybackProgress`/`WatchProgressStore`/`RemoteServer` 读它）。`PlaybackSession` 的注释已写明该通道归 `avbox-playback-service-spec.md` 的 P 系列收口，此处不预判其迁移方式。
- **`osc.api ⇄ osc.server`（1/1）**：`ApiConfig` 与 `RemoteServer` 各 1 处互引（远端配置/局域网服务），需按能力拆分，本阶段不动。

**度量口径**（可复现）：文件所在包剥离 `com.github.tvbox.` / `com.github.catvod.` 后取前 3 段为节点，按 import 建有向边，双向即成环（java+kt 共 317 文件、215 边）。与附录 A 的原始口径同族（本工具在阶段 6 起点测得 25 组，与计划的 24 组相差 1 组，差异来自 catvod 包与 Kotlin import 的计入）。

| 子步 | 内容 | 消掉的环 |
| --- | --- | --- |
| 6.1 | `player → ui.player` 的 3 个具体类接口化（`PreloadCoordinator`/`PlayerTipBridge`/`PlayContainer`），参照已有 `PlaybackViewBridge`；UI→业务方向保留 | `player ⇄ ui.player` |
| 6.2 | `player.thirdparty.RemoteTVBox`、`player.SubtitleFilePicker` 移出 `osc.player`（→ `player.ext` 或 `util`） | `player ⇄ viewmodel` |
| 6.3 | `osc.util` 拆 `util.core`（零反向依赖：LOG/MD5/文本）与 `util.integration`（PlayerHelper/DefaultConfig 等胶水） | util 枢纽 6–8 组 |
| 6.4 | Room 三件套归包（实体 + DAO + `data/AppDataBase`） | `cache ⇄ data` |
| 6.5 | `base` 的 context 依赖改 `AppContextHolder` 或传参 | `base ⇄ data/server`、`catvod.crawler ⇄ base` |
| 6.6 | `ui.components` 不得引用 `ui.activity`/`ui.page`/`ui.navbar` | UI 内部 4 组 |

- 目标：24 组环 → ≤6（保留 `catvod.crawler ⇄ catvod.crawler.js`、`subtitle.format ⇄ model`、`util.core ⇄ util.kv` 等正常父子互引）。
- 风险：中-高（包移动 diff 大）；每个子步独立提交，逐包验证。
- 收益：环数、`import` 交叉数可数下降；`osc.util` 被引用面从 116 文件收窄。

## 阶段 7｜PlaybackController（不独立立项）

并入 `skill/avbox-playback-service-spec.md` 的 P0–P5；本 spec 只登记拆解依据（28 字段 = 会话/超时/嗅探解析/进度 4 个状态簇，可拆 `PlaybackTimeouts`/`PlaybackProgressBridge`/`SniffDelegate` 各 250–450 行）。

# 5. 明确不做

- 不引入 DI 容器（§6）；不拆 Gradle module / feature 模块；不重写 Java→Kotlin；不动 `player` 模块上游内核（`tv.danmaku.ijk`、`xyz.doikki`）；不为"行数达标"硬拆播放器状态机；不把 UI 数据访问改成引入 Repository 层的大改（只在各页 ViewModel 内收敛）。

# 6. DI 决策记录

- **决定：不引入**（与 `avbox-code-review-spec.md` 既定约定一致）。
- 依据：本项目痛点是"全局状态访问 + 依赖方向 + 巨型类"，容器只能显式化装配；`ApiConfig.get()` 31 文件 110 处、`.getInstance()` 116 处的改造量换不来环数与行数的下降；spider jar 为运行期反射装配，容器不参与；测试侧 44 个文件 0 处 Mockito/Robolectric，替身收益当前≈0；180 个 Java 存量文件与容器写法并存会违反「禁止新旧写法混用」。
- 依赖侧已就位但不构成引入理由：`app/build.gradle.kts:131-133` 的 `cdi.api`/`javax.inject`/`javax.annotation` 是 `compileOnly`，服务对象是动态爬虫 jar，宿主源码 0 处 `@Inject`；KSP 已接入（Room 3）。
- 替代：`AppGraph`（Kotlin `object`，只装配 5–10 个核心协作者，`App.onCreate` 装配一次）+ 构造器注入 + 接口化解环。
- **翻案判据（命中其一再评估）**：① 拆 module；② 需要 ≥3 种作用域且手写装配开始出错；③ 测试引入 Mockito/Robolectric；④「每新增 1 个业务类需要改的装配点」中位数 ≥3。届时选型：Hilt（编译期校验、生命周期集成好，适配播放服务化）／Koin（构建零成本，但运行期缺绑定）／AppGraph（零依赖，作用域靠自己守）。

# 7. 硬约束（动手前必对照，违反即驳回）

- Exo 帧率匹配保持关闭（`disableFrameRateMatching()`）。
- 规则表只能在 `parseJson` 入口 `clear`（`VideoParseRuler.clearRule()`）。
- 配置驱动 header 必须过 `ConfigParser` 字符集过滤。
- BootGuard 的 `IGNORABLE_FRAME_PREFIXES` 白名单是唯一旋钮。
- KV 复杂键必须在 `util/kv/KVKeySpec` 登记；`KV.contains` 才是存在性判断。
- 爬虫/网络等阻塞调用必须在 IO 线程，禁止阻塞主线程。
- 依赖裁剪必须顾及动态加载的爬虫 jar（gson/okhttp/zxing 等由宿主提供）。
- **类/包重命名前**：全库检索类名（含字符串字面量里的类名）并确认 spider jar / 动态加载契约，不能只改 import。

# 8. 验证与回滚

- 每步落地：`.\gradlew.bat :app:assembleDebug` + `:app:testDebugUnitTest`；日志落盘再读（Git Bash grep 对它零命中），以 `BUILD SUCCESSFUL` 与 `app/build/test-results/testDebugUnitTest/*.xml` 用例计数为准（当前基线 364 用例）。
- 触及配置解析/字段取值/规则：必须补单测锁口径（范例 `ConfigParserTest`、`HeaderGuardTest`）。
- 每步一个 commit（全英文小写 + scope），可独立回滚；不推远程除非明确许可。
- 真机走查判据：阶段 1 → 直播页/详情页返回与全屏切换正常；阶段 3 → 换源、详情、取流不回归；阶段 4 → 换源成功/失败两条路径规则不真空；阶段 5 → t4 源首页/详情/起播不卡；阶段 6 → 各页功能与播放链路不回退。

# 9. 优先级

- **P0**：无。
- **P1**：阶段 3（重复模板，收益最高）→ 阶段 4 的配置下沉 → `sortCache` 加锁（⑫）。
- **P2**：阶段 1、2、5、6；阶段 7 随播放服务化 spec 排期。

# 附录 A. 度量口径（可复现）

- 文件行数（精确）：`Get-ChildItem -Recurse -Include *.java,*.kt -File app\src\main | ForEach-Object { [pscustomobject]@{ Lines=[IO.File]::ReadAllLines($_.FullName).Count; Rel=$_.FullName } } | Sort-Object Lines -Descending`
- 方法行数（**估算 ±10 行**）：花括号配对脚本；多行签名与字符串/注释里的花括号会漏检或漂移，须人工核对 Top 项（已知脚本漏 `getPlayPrepared` 148、`checkPush` 129，已核对为真）。
- 重复模板：`Select-String -Pattern 'Executors\.newSingleThreadExecutor\(\)' | Group-Object Path`。
- 门面依赖数：把上条命令的模式换成 `ApiConfig\.get\(\)` / `\.getInstance\(\)`。
- 包级环：按"文件所在包(p3) → import 目标包(p3)"建有向边（231 条边 / 39 包），双向即成环；**只含编译期依赖，EventBus/反射/KV 键等隐式耦合不计入**（真实耦合面更大）。

# 附录 B. 已核对豁免（不计发现）

- `catvod/crawler/js/Trans.java:48-49` 简繁字表（2610 字符×2，i18n R6 要求保留）。
- `util/LocalIPAddress.java:112` IPv6 正则（1209 字符）。
- `util/kv/KVKeySpec.java:59` 131 行静态登记块（KV Spec 约定）。
- `bean/AbsJson.java:66` 1363 字符示例 JSON（位于注释内，可作低级清理，不必单列）。
- `osc.util ⇄ osc.util.kv/parser`、`catvod.crawler ⇄ catvod.crawler.js`、`subtitle.format ⇄ subtitle.model`：正常父子/同族互引，不处理。
