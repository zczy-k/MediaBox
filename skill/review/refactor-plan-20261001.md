---
name: AVBox 渐进式重构 Spec（VM 归一与上帝类收尾专项）
status: 执行中·**结构拆分已收尾**（2026-10-01：V1–V5 已落地并过两轮复核【V5 含 1 处阻断级构造期 NPE 修复】；V5b 继续拆分 + 注释精简已落地（主类 1806→1320+3 协作者）并过**第四轮逻辑复核**（1 处低危语义漂移已修、3 项登记）；**唯余真机走查**；D1/D2/D3/V6 已拍板；本文接手 refactor-plan-20260928.md 的阶段 7 遗留与其未做项中与本专项重叠的部分）
source: 2026-10-01 提名的四问题审查：① 双范式并存（LiveData/StateFlow 各半、viewmodel/ 名不副实）② 上帝类残留（PlaybackController / ComposeVideoController / PlayContainer / ApiConfig）③ VM 持 View 与 static 可变缓存 ④ 业务逻辑写进 Composable。审查结论：四问题全部属实（数字 ±5% 出入见 §2）
---

# 结论摘要

- 四问题按根因归并为三条线：**问题 ③+① 后半同根**（页面级 VM 与站点取数门面的耦合方式——static 缓存 + 换实例 + observeForever 手动配对）；**问题 ④ 独立**（新页面没按既有 VM 规范写）；**问题 ② 是两件事**（播放三件套 = 播放服务化 P0–P5 完成后的结构遗留；ApiConfig = 旧 spec 已登记未做的直播解析链）。
- 动手顺序（风险从低到高）：**V1** ConfigManageViewModel 抽取 → **V2** `playContainerRef` 去引用 → **V3** static 状态外迁 + `viewmodel/` 包改名 → **V4** LiveData 观察侧收口 → **V5** 播放三件套结构拆分 → **V6**（可选）ApiConfig 直播解析链。
- 终态判据：UI 层 Composable 零业务编排；ViewModel 零 View 引用、零 static 可变字段；`observeForever` 全库 ≤1 文件（桥接器内部）；`viewmodel/` 包名实至名归；播放三件套各主文件按簇可单测（不设行数硬指标）。
- 继承旧 spec 三不变：不引入 DI、不重写 Java→Kotlin、不为行数达标硬拆（D2 拍板后 LiveData 分域方案不再涉及任何 Java→Kotlin 重写）。
- 每步独立 commit（全英文小写 + scope）、可独立回滚；每步落地跑 `:app:assembleDebug` + `:app:testDebugUnitTest`。

# 1. 与既有 spec 的关系（先读，避免重复立项）

| 既有文档 | 关系 |
| --- | --- |
| `refactor-plan-20260928.md` | 阶段 1–6 已落地。**阶段 7（PlaybackController）原"不独立立项、并入播放服务化"，现播放服务化 P0–P5 已全部完成而拆分未发生——本 spec 的 V5 正式接手**，拆解依据沿用其登记的 4 状态簇。阶段 5 的 viewmodel/ 11 文件结构（门面 168 行 + 5 Loader + 3 支撑类 + 2 VM）是刻意产物，本 spec 处理的是它的"包名与职责错位"，不是否定拆分本身。 |
| `avbox-playback-service-spec.md` | P0–P5 已落地（2026-09-14 真机回归通过）。`PlaybackController` 2203→2016 行是 P1"整体搬进"的结果（当时未拆）；`PlayContainer` 3074→1551→1407 行只剩"视图 + 控制器 + 挂摘 + 生命周期"。V5 在其**终态结构之上**做簇拆分，不回退任何服务化语义。 |
| `avbox-code-review-spec.md` | 严重度锚点与注释红线（⑪）继续引用；本 spec 不处理注释存量。 |

# 2. 现状基线（2026-10-01 实测）

| 度量 | 值 | 说明 |
| --- | --- | --- |
| `PlaybackController.java` | 2016 行 / ~129 方法 | 含自持 `SourceViewModel` + `playResultObserver`（1039–1040 行）与 1075 行 107 行匿名 `Observer`（旧 spec 阶段 2 遗留，归 V5） |
| `ComposeVideoController.kt` | 1423 行 / 137 fun | `BaseVideoController` 基类 + 5 接口（`PlayerControlApi`/`PlayerActions`/`OnGestureListener`/`OnDoubleTapListener`/`OnTouchListener`） |
| `PlayContainer.java` | 1407 行 | 服务化后剩视图/控制器装配/挂摘；已两轮拆分（1839→1424→1407） |
| `ApiConfig.java` | 1054 行 / 70 方法 | 直播解析链（`parseLive*`/`loadLives`/`loadLiveApi`/`initLiveSettings`）≈420 行未拆 |
| `DetailViewModel.kt` | 959 行 / 62+ 方法 | 14 个 `MutableStateFlow`；`playContainerRef` 7 处使用 |
| `ConfigManagePage.kt` | 953 行 | `ConfigManageScreen` 170→703 行单函数；17 个 `remember`、14 个嵌套业务 fun；全文件零 ViewModel |
| `SourceViewModel.java` | 168 行门面 | 7 个 `MutableLiveData` 通道 + `static sortCache`/`extendCache` + `spThreadPool` 别名 |
| `observeForever` 手动配对 | **9 处 / 5 文件** | `DetailViewModel`(131/217)、`HomeViewModel`(110–112/393，持 3+1 实例)、`PartitionListViewModel`(51/66 两实例)、`SearchViewModel`(302)、`PlaybackController`(1039) |
| `viewmodel/` 包 | 11 文件 | 真正的 ViewModel 仅 2 个（`SourceViewModel`/`SubtitleViewModel`），其余 9 个为 Loader/Resolver/Helper/Parser |

> **V5/V5b 落地后（2026-10-01）**：`PlaybackController` **1320 行**（V5 拆超时/预载/取流观察，V5b 续拆音乐会话/播放器配置/重试换线 + 注释精简）、`PlayContainer` **1337 行**（音轨选择拆出）、`ComposeVideoController` **1214 行**（手势拆出）；共 8 个协作者，逐簇结论见 V5 节。

# 3. 问题清单（映射提法 → 根因）

| # | 提法 | 审查确认 | 根因归属 | 处理阶段 |
| --- | --- | --- | --- | --- |
| ①a | Kotlin VM 用 StateFlow、Java VM 用 LiveData，范式割裂 | 属实（14 vs 7） | 通道与观察桥接无统一边界 | V4 |
| ①b | `observeForever` 那个 Java VM | 属实且面更大：5 文件 9 处手动配对，配对缺失即泄漏 | "换实例防迟到回包"设计（`rebindDetailSource`）倒逼出的手法 | V3/V4 |
| ①c | viewmodel/ 11 文件 9 个名不副实 | 属实；系旧 spec 阶段 5 刻意同包（包级可见性 40 个成员复用） | 包名与职责错位，非结构问题 | V3 |
| ②a | 播放三件套上帝类 | 属实 | 播放服务化 P1"整体搬进"未拆 + Compose 控制器多角色 | V5 |
| ②b | ApiConfig 1054 行 | 属实 | 旧 spec 阶段 4 明示未做（直播解析链） | V6（可选） |
| ③a | `DetailViewModel.kt:79` 持 `PlayContainer` | 属实，7 处使用（含 232–233 行同步读 View 状态） | VM→View 无指令通道，只能直拿引用 | V2 |
| ③b | 页面级 VM 带 `static sortCache` | 属实（+ `static extendCache`、`spThreadPool` 别名） | 换实例设计要求状态逃生 VM 生命周期 → 只能 static | V3（与 ①b 同因果链） |
| ④ | 订阅增删改/切源/黑名单在 UI 作用域 | 属实；加重项：`deleteSelected` 内一次性 executor + `AppBootstrap.retry()` 全局副作用 | 页面未按既有 VM 规范写 | V1 |

# 4. 目标终态（验收清单）

1. `ConfigManagePage.kt` 的 Composable 只含 UI 组合与事件转发；订阅/切源/黑名单/副本清理全在 `ConfigManageViewModel`。
2. 全库 ViewModel（含页面级）**零 View 类型引用**、**零 static 可变字段**（`spThreadPool` 类常量除外，见 D2 说明）。
3. `observeForever` 全库 ≤1 文件（观察桥接器内部，**播放层除外**，见 V4 执行状态里的登记理由）；Kotlin 侧页面 VM 只见 Flow。
4. `com.github.tvbox.osc.viewmodel` 包不复存在——整包改名 `com.github.tvbox.osc.sourcedata`（D1 已拍板）。
5. `PlaybackController` 按簇拆出 ≥3 个可单测协作者，主类 ≤600 行量级（软目标）；107 行匿名 `Observer` 具名化。
6. `PlayContainer` / `ComposeVideoController` 按簇评估，拆或不拆给逐簇结论（不为行数硬拆，V5 出口条件是"每个簇有归属与单测/豁免理由"）。
7. `ApiConfig` 直播链落地或明示豁免（V6，已确认）。

# 5. 渐进式计划

## V1｜ConfigManageViewModel 抽取（风险最低，消问题 ④）

**执行状态（2026-10-01）**：

- 已落地（3 个本地 commit，未推远程）：`b7361b7` 本 spec 立项；`171d65b` VM 抽取（状态 + 逻辑合并一笔，与本节 Commit 行的"或合并一笔"一致）；`1eee293` 审查修复（副本清理脱离 `viewModelScope`）。
- 实测：新增 `ui/page/ConfigManageViewModel.kt`（~360 行）；`ConfigManagePage.kt` **953 → ~700 行**（-294/+37）。按计划留 UI 的：`badgeText`、对话框/面板开关（`addDialogOpen`/`repoSheetOpen`/`mode`）、列表项内联 inUse 判定（AnimatedContent 过渡期 `mIsVod` 语义，勿改成 `isVod`）；入 VM 的：`vodItems`/`liveItems`/`activeUrl`/`liveActiveUrl`/`liveFollow`/`disabledUrls`/`selected`/`manageMode`/`editTarget`/`pendingSwitch`/`toastEvent` + 14 个业务方法 + 8 个支撑函数；`SUBSCRIBE_SPLIT`（原 `SubscribeSplit`）与 `parseSubscribe` 为文件顶层 `internal`。
- **审查轮发现并修复 1 处真实回归**（`1eee293`）：副本清理最初写进 `viewModelScope.launch(Dispatchers.IO)` —— 删源后立即退出页面会取消协程（`removeLocalCopy` 是 `deleteRecursively`），旧实现是 executor 即发即走。改为独立 `copyCleanupScope`（照 `AppBootstrap` 样式），**勿改回 viewModelScope**。
- 登记的可接受差异：① 黑名单二次确认对话框跨旋转保留（原 `remember` 丢失，改善型）；② `toastEvent` 同值连发被 StateFlow conflated 吞掉（与 `DetailViewModel` 同模式，实际被切源去重守卫挡住）；③ 编辑态旋转后仍清（`LaunchedEffect(mode)` 重建触发 `onModeChanged`，与原行为一致）。
- 验证：`assembleDebug` + `testDebugUnitTest` 全绿（**57 类 / 447 例 / 0 失败**）；行集多重集比对 118 条 missing 全部为预期形式转换（`.value` 后缀 / `toastEvent` / `vod` 参数化 / `collectAsState` / 注释形式）；Kotlin 警告零新增；`viewModel()` 依赖有 5+ 处先例。
- 未做：装机走查 —— 设备离线（`adb devices` 空，vivo 未枚举）。走查判据同本节"验证"行。

- **修改**：新增 `ui/page/ConfigManageViewModel.kt`（Kotlin + `MutableStateFlow`，与 `DetailViewModel` 同范式）。
  - 状态入 VM：`vodItems`/`liveItems`/`activeUrl`/`liveActiveUrl`/`liveFollow`/`disabledUrls`/`selected`/`manageMode`/`pendingSwitch`/`editTarget`（`addDialogOpen`/`repoSheetOpen`/`mode` 等纯 UI 开关可留 Composable，逐个判断后在本节登记归属）。
  - 逻辑入 VM：14 个嵌套 fun（`switchToVod`/`switchToLive`/`requestSwitch`/`enableAndSwitch`/`followLiveNow`/`deleteSelected`/`commitAdd`/`commitEdit`/`refreshActiveSnapshot`/`exitManageMode`/`isInUse`/`activeInEitherMode`/`referencedBySubscribes`/`referencedByRepo`）+ 顶层 `loadSubscribes`/`saveSubscribe`/`updateSubscribe`/`applyVodSource`/`applyLiveSource`/`applyLiveFollowVod`。`badgeText` 是纯展示计算，留 UI 侧。
  - `ApiLineSignal` 收集（多仓改写刷新）入 VM。
  - `deleteSelected` 的一次性 `Executors.newSingleThreadExecutor()` → VM 内单一共享 executor 或复用 `SourceHelper` 池语义，杜绝"每次删除 new + shutdown"。
- **风险**：低-中。语义陷阱两处必须保留：`PendingSwitch` 带 `vod` 防 AnimatedContent 过渡期误切（注释已写明）；`disabledUrls`"只在首次组合读一次"的独立 Activity 前提——VM 化后天然成立（VM 随 Activity 重建），但删除/二次确认启用后的本地状态更新逻辑要与 BootGuard 落盘保持一致。
- **验证**：构建 + 单测；真机走查 = 订阅增/删（含"使用中"拦截）/改、切源成功与失败回滚、黑名单二次确认放行、删除后本地副本清理、多仓改写后"使用中"标记刷新。
- **Commit**：状态搬迁一笔、逻辑搬迁一笔（或合并一笔，登记后执行时定）。

## V2｜`playContainerRef` 去引用（消问题 ③a）

**执行状态（2026-10-01）**：

- 已落地（2 个本地 commit，未推远程）：`4459bd6` 指令流 + 逐点语义转换；`a1a2ed6` 审查轮修复（退全屏 `rotating` 回归 + 4 项清理）。
- 实测：新增 `ui/activity/DetailPlaybackCommands.kt`（~82 行：5 个指令 + 事实类型 + 全屏判定纯函数）；`DetailViewModel.kt` 959 → 995 行（增量全是注释与指令发送），**`playContainerRef` 字段与 7 处使用全部消失，文件不再 import `PlayContainer`、不再 import 任何 `androidx.compose.*`**（原 4 条 Compose import 全是死引用，一并清掉；另清 1 条死 `Bundle` import）。
- **审查轮抓到 1 处真回归（已修，`a1a2ed6`）**：初版把全屏判据写成 `if (!requested) return false to false`，即"退全屏一律清 `rotating`"。旧表达式没有这个短路——`requested=false` 时 `landTarget` 恒 false，`rotating` 求值为 `landNow`，**窗口还横着退全屏必须置位**才能让 `fullBox` 保持全屏样直到旋转落地。这正是 2026-09-13 真机验证过的「横屏按返回：画面保持全屏样转回竖屏，不再缩小靠左上跳」（`skill/history/features.md`「全屏/退出全屏旋转过渡修复 A+B」真机验证点②），而 `features.md:2570` 另有一条明确警告「不要为它盲改判据」。初版单测还把错误行为当成旧真值表锁死（**测试写错方向比不写测试更危险**——它给的是虚假的覆盖信心）。修法：判据逐字还原为 `landscapeTarget != facts.landscape`；`exitFullScreen()` 删除，返回键与 `onNewIntent` 改调 `onFullScreenToggleRequested(false, playbackFacts())`（单一决策入口，`requested=false` 时门禁本就不跑，等同旧 `setFullScreen(false)`）；单测扩到 8 格真值表全覆盖，退全屏两格锁为 `true`。
- 审查轮同时确认/登记的点：① 指令流四条主干（通道、事实入参、清晰度回写、面板投影）逐点等价，无乱序可达路径（`StopForSourceSwitch` 与 `ClearSourceSwitchTip` 同序，后者在换源回包结算时才发，中间隔一次网络往返）；② `PlayContainer.scheduler != null` 守卫是纯保护（唯一置空路径同时置空 `mVideoView`）；③ 清晰度回调生命周期安全（容器与 Activity 同生共死、VM 不持 Activity），另在 `hostDestroy` 补 `qualitySelectedListener = null`；④ `PlayerUiState` 每容器一份且 `episodeSheetOpen` 默认 false，故"首次组合不再补发一次 false"无后果；⑤ 不为 `trySend` 失败分支加处理、也不 `close()` 通道（`close()` 会让仍在收集的页面拿 `ClosedReceiveChannelException`，而"组合先销毁、VM 后 cleared"只是时序巧合），理由写进 `sendCommand` 注释。
- **登记为不修（潜在缺陷，非本次引入）**：大屏（sw≥600dp，`orientationPolicyValue()` 为 `UNSPECIFIED`、窗口不旋转）在旧实现下退全屏会置 `rotating=true` 且无 `onConfigurationChanged` 可清 ⇒ 可能停在 `fullBox=true`。新实现逐字保留了这一行为（未借机"修好"），要改须独立立项 + 真机走查，别藏在结构重构里。
- 逐点落地方式（与本节设计的三处差异，均已登记理由）：
  1. **指令通道用带缓冲的 Channel 而非 `SharedFlow`**：指令源存在早于收集器的调用（`DetailActivity.init` 里 `initFromIntent` → `applyTarget` 早于 `setContent`），缓冲保证「先发后收」不丢且顺序 = 旧直调顺序。注意 `StopForSourceSwitch` **没有**自守卫（只有 `mVideoView == null`），其安全性来自"发出点必是用户点击换源"，已写进通道注释：不要把该通道复用到别的页面或加第二个消费者。
  2. **设备事实（方向 + 竖屏视频）改当帧入参**，不再"orientation 走 `AppContextHolder` + `isPortraitVideo` 走状态通道"：状态通道会晚一帧，而 `rotating` 要当帧交给布局（`fullBox = if (rotating) isLandscapeNow else full`）——晚一帧会先按错误形态铺一帧再纠正。事实由页面 `DetailActivity.playbackFacts()` 提供（页面是唯一同时掌握窗口方向与播放层视频尺寸的地方）。**未**引入 `DetailActivity` → VM 的方向回写，避免多一条与 `onConfigurationChanged` 竞态的路径。
  3. **清晰度选中结果改容器回调**（`PlayContainer.OnQualitySelectedListener` → `vm.onQualitySelectionAccepted(position)`），而非"补一条确认通道"：与旧实现读 `selectQuality` 同步返回值同为同帧落地，"能否切"的判定仍留在控制器侧，VM 不新增容器知识。容器侧补了 `scheduler != null` 守卫（旧实现直接解引用；唯一置空路径 `onServiceStopped` 同时置空 `mVideoView`，属纯保护）。回调线程契约写在接口注释上：页面必须在主线程调 `selectQuality`。
  4. 选集面板：`episodeSheet` 仍是 VM 状态，投影改由 VM 在 `showEpisodeSheet`/`dismissEpisodeSheet` 内下发指令（`EpisodeSheet` 里那条 `LaunchedEffect(show)` 删除）。两条入口（页内"全部"按钮、播放器底栏 `PageHost.showEpisodeSheet`）都经 VM 方法，改一处即覆盖。
  5. `setFullScreen` 拆分结果（审查后）：保留单一决策入口 `onFullScreenToggleRequested(requested, facts)`，进/退两条路径都走它。
- **语义单测**：`DetailPlaybackCommandsTest` 8 例，覆盖 `(进/退) × (窗口横竖) × (视频横竖)` 全 8 格。锁的是易被"顺手修正"的真实语义：竖屏视频进全屏**不置** `rotating`（竖屏→竖屏不触发 `onConfigurationChanged`，置位会永不复位）；横屏窗口 + 竖屏视频要置位（转回竖屏）；**退全屏时窗口还横着要置位**（横屏按返回保持全屏样）；退全屏 + 竖屏窗口不置位。
- 已知的**未做**：指令流本身（`Channel` 顺序与缓冲）没有单测 —— `DetailViewModel` 无法在纯 JUnit 下实例化（`mainHandler = Handler(Looper.getMainLooper())`、`App.getInstance()`、`SourceViewModel` 的 `MutableLiveData` 字段初始化器都依赖 Android 运行时，项目只有 `testImplementation(libs.junit)`、无 Robolectric/coroutines-test；`features.md:2624` 记过同一个坑）。行为锁在可测的纯函数上，与 `DetailNavStack`/`DetailFullScreenGate` 的做法一致。
- 验证：`assembleDebug` + `testDebugUnitTest` 全绿（**58 类 / 455 例 / 0 失败** = V1 基线 447 例 + 本次 8 例）；Kotlin 编译零新增警告。
- 未做：装机走查 —— `adb` 不在 PATH，与 V1 同日同因。**走查时请额外确认审查轮修复的那一格**：手机横屏全屏按返回，旋转落地前画面应保持全屏样（不应提前缩成顶部 16:9）。

**原设计记录（本次执行按上述差异落地）**：

- **修改**：`DetailViewModel` 引入播放指令流 `playbackCommands: SharedFlow<DetailPlaybackCommand>`（sealed：`StopForContentSwitch` / `StopForSourceSwitch(tipRes)` / `ClearSourceSwitchTip` / `SetEpisodeSheetOpen(Boolean)` / `SelectQuality(Int)`），`DetailScreen`/`DetailEpisodes` 收集后调 `PlayContainer`；删除 79 行字段与 7 处使用，`DetailViewModel` 不再 import `PlayContainer`。
- **逐点注意**：
  - 775 行 `selectQuality(position)` 返回 `Boolean`（指令 + 确认）：改流式会丢返回值——把"选中结果"改为容器侧回写 VM 状态（`qualitySelected` 已存在，补一条确认通道），或该点暂留方法参数直传（在 VM 暴露 `fun requestQuality(position)`，内部仍发命令、由容器侧写结果）。**不允许**为图省事继续持 View。
  - 232–233 行 `playContainerRef?.resources?.configuration?.orientation` 与 `isPortraitVideo()` 是 VM 同步读 View 状态（反模式本体）：orientation 改从 `AppContextHolder`/`DetailActivity` 传入；`isPortraitVideo` 由容器在视频尺寸就绪时回写 VM 只读状态（新 `StateFlow<Boolean>`），`setFullScreen` 消费它。
- **风险**：中。播放指令从同步调用变异步分发，注意 `applyTarget`(170 行) 的 `stopForContentSwitch` 时序——换片时旧内容必须先停，命令发出到消费在下一帧，验证切集/换片瞬间的画面与声音串扰。
- **验证**：构建 + 单测（指令流补一条 vm 逻辑单测）；真机走查 = 切集、换源（含失败回滚 tip 的出现与消除）、全屏/旋转判定、选集面板打开、清晰度选择。
- **Commit**：一笔。

## V3｜static 状态外迁 + `viewmodel/` 包改名（消问题 ①c + ③b）

**执行状态（2026-10-01）**：

- 已落地（3 个本地 commit，未推远程）：`0bbcb5c` 状态外迁；`e082bcf` 包改名；`<审查轮修复,见下>` 注释/活规范同步。
- **审查轮（2026-10-01）结论：0 真回归**（两轮独立复核，含"逐字节比对改名文件"与"变异矩阵实测测试有效性"）。逐点复核结果：
  - **改名是纯搬迁（字节级证据）**：16 个改名文件 `git cat-file blob` 对比,去掉第 1 行后**逐字节完全相同**(16/16);`git show e082bcf --stat` 全部识别为 rename;行尾/BOM 核对通过(`SearchViewModel.kt` 303 CRLF 保持)。
  - **静态语义未变**：`sortCache` 的上限/access-order/锁/持有者/初始化时机/清理时机一字未变,全库**无任何重新赋值路径**(唯一 mutator 是 `clear()`,唯一出口 `HomeViewModel.reload()`);类初始化无环(`SourceRuntimeState` 只依赖 `AbsSortXml`+`java.util`);`SourceHelper.<clinit>` 只建两个线程池,无可观察差异。
  - **加锁语义**：全库 `sortCache` 访问点仅 3 处(`SortLoader` put/get + `clearRuntimeCache`),**全部在 `synchronized (sortCache)` 内且持锁不做 IO**,无未加锁访问。
  - **测试有效性（变异矩阵实测,非"看起来对"）**：把 access-order 参数改 `false` ⇒ **只有** `sortCacheGetRefreshesRecency` 转红(证明它是 access-order 的唯一守卫);`removeEldestEntry` 改 `false` ⇒ 上限用例转红;去掉 `clearRuntimeCache` 里的 `sortCache.clear()` ⇒ 清空用例转红。三条都是真断言。
  - **非 Java/Kotlin 消费面零命中**：proguard(通配 `-keep class com.github.tvbox.osc.**`)、Manifest、资源 XML、KV 键、EventBus(事件类在 `osc.event`)、Room schema(`app/schemas` 未变)、KSP、`app/src/python`、`app/libs/*`(jar/aar 二进制扫描)、gradle/CI(`.github/workflows`,顺带发现 **CI 只跑 `compileDebugKotlin`/`assembleRelease`,不跑单测**)、跨模块 `player`/`pyramid`/`quickjs`/`libs` 均零引用。
  - **产物级复核**：debug APK dex 内旧包名出现 **0** 次、`sourcedata` 137 次(无残留/重复类)。
- **审查轮修复（本笔 commit）**：
  1. **计划文档证据措辞纠错**(本文件上一版自称"0 insertions / 0 deletions",实测 `--numstat` = 24 文件/25+25 行 —— 改名文件各 1 行 package、包外 8 文件各 1~2 行 import);已改为可复现的准确表述。
  2. **三处同源注释失真**(都因本次搬动产生):`SortLoader` 的"缓存本体由门面持有"、`SourceRuntimeState` 头注释自称含"spider 线程池入口"、`SourceViewModel` 头注释把"线程池"列入已经外迁的状态 —— 池仍在 `SourceHelper.SPIDER_POOL`、没搬;`SourceHelper` 的"门面仍然持有 extendCache"一并改正。
  3. **活规范失真**(按 `SKILL.md`「仍生效的规范写在活规范里」):`avbox-mobile-ui-spec.md` 三处(下拉刷新引用已删的 `SourceViewModel.clearRuntimeCache()`;§6.13/§6 两处把 `viewmodel` 当"崩溃栈白名单外"的示例包名)、`avbox-code-review-spec.md` 两处(模块清单、批次二包名)、`avbox-i18n-spec.md` 四处(R2/R8/R9 红线路径、附录 A.2 清单)。**历史归档不动**(`skill/history/features.md` 与 `skill/review/review-20260928-batch1.md`/`refactor-plan-20260928.md` 的旧包名是当时事实,改写会伪造历史)。编辑器镜像 `.codebuddy/skills/android` 与 `.trae/skills/android` 已按项目约定同步并哈希核对。
- **前置检查（硬约束级）结论**：`com.github.tvbox.osc.viewmodel` 全库只出现在 **16 处 package 声明 + 9 处 import**（其中包外 8 个文件：`DetailViewModel`/`SearchViewModel`/`HomeViewModel`×2/`PartitionListViewModel`/`PlayContainer`/`PlaybackController`/`PreloadCoordinator`/`SubtitleSheets`），**manifest / proguard-rules / KV 值 / 任何字符串与反射面零命中**。spider jar 契约在 `catvod.crawler`/`catvod.bean`,与本包无关 —— 检索后才动手。
- **外迁（`0bbcb5c`）**：新增 `sourcedata/SourceRuntimeState.java`,`sortCache`/`extendCache`/`clearRuntimeCache()` 整段搬入(含 access-order `LinkedHashMap` 与 `removeEldestEntry` 上限 5),`synchronized (sortCache)` 加锁语义逐字保留(`aa5b132`);`SourceViewModel` 构造器改从它取缓存交给各 Loader(构造器签名未变,零波及其他 Loader),`HomeViewModel:150` 改指 `SourceRuntimeState.clearRuntimeCache()`。**门面因此只剩 7 个通道 + 入口方法**,页面级 VM 不再带 static 可变状态(消问题 ③b)。
  - **D3 拍板结果:删别名**。`spThreadPool` 全库只有 1 个调用点(门面自己的 `action()`),已改直引 `SourceHelper.SPIDER_POOL`,别名删除;`SPIDER_POOL` 真身留在 `SourceHelper`(包级可见,不做带 `@Deprecated` 的过渡别名)。
  - 新增 `SourceRuntimeStateTest` 3 例:LRU 上限(第 6 条挤掉最早)、**`get` 也刷新 recency**(access-order 的实证,丢了这一条就退化成插入序)、`clearRuntimeCache` 两条缓存都清空(并断言清空不换新 map 实例)。用例与类同包,直接读写包级字段,不新增任何测试依赖。
  - **已知覆盖缺口(审查轮登记)**:"5 个 Loader 拿到的是同一个 map 实例"这条接线**没有**用例 —— 读它需要 `new SourceViewModel`(构造器初始化 `MutableLiveData`,纯 JVM 单测拿不到 Looper,与 `features.md` 记的同一个坑),而 Loader 没有实例就拿不到字段。改动 `SourceViewModel` 构造器那 5 行传参时须人工确认传的是 `SourceRuntimeState` 的字段本身;已写进测试类 KDoc,不假装已覆盖。
- **改名（`e082bcf`）**：`git mv` 两个目录(主 + 测试,16 文件)+ 原地改写 24 个文件里的包名/import。**不动编码与行尾**(`SearchViewModel.kt` 保持 CRLF,其余 LF;全部无 BOM)。
  - **纯搬迁证明**:`git show e082bcf --numstat` = **24 文件 / 25 insertions / 25 deletions** —— 16 个改名文件各只有 1 行(package 声明)不同、8 个包外文件各只有 1~2 行(import)不同(`HomeViewModel` 2 行),账目恰好对上;`git show e082bcf --stat` 把这些文件全部识别为 rename(`{viewmodel => sourcedata}`),**改名本身零内容改动**。另按 spec §9 卡口做逐行去空白比对:24 个文件各自只有 package/import 一行不同。包内 40 个包级成员的可见性一字未改(这是选"改名而非重拆"的唯一理由)。行尾/编码逐文件核对:除 `SearchViewModel.kt` 保持 CRLF 外均 LF,全部无 BOM。
  - 验证:`assembleDebug` + `testDebugUnitTest` 全绿(**59 类 / 458 例 / 0 失败**,与 V3.1 同数 —— 改名不带行为);审查轮又做了一次**删掉整个 `app/build` 的全量重建**复核(依赖缓存与子模块输出不动,20s),结论相同,排除缓存假绿。
  - **审查轮登记的接受项**:①缓存字段由 `private` 放宽为**包级**(计划要求,`SortLoader` 只用注入引用,当前无滥用),代价是"唯一清理出口"从语言保证降为约定;②`Entry` 裸名(未写 `Map.Entry`)是从旧实现逐字搬来的写法,项目工具链通过,仅对"把该类搬进纯 JVM 编译"的场景有影响;③仓外 spider jar 若按上游类名引用 `SourceViewModel.spThreadPool`/`clearRuntimeCache`,改名+删别名会让其在运行时失配(D3 已明确不留过渡别名;仓内与参考实现之外的来源不在库内,无法检索)。
- 未做:装机走查 —— `adb` 不在 PATH。判据沿用旧 spec 阶段 5:换源后 `sortCache` 清理仍生效(`HomeViewModel.reload()` → 分类与首页推荐应重新取数,不再命中旧源缓存)。
- 遗留说明:包名叫 `sourcedata` 后,`SourceViewModel`/`SubtitleViewModel` 这两个**真 VM** 也在包内(旧 spec 阶段 5 的同包刻意产物,D1 的前提就是整包原样改名)。V4 会在这两个类上继续做观察侧收口,是否把它们移出该包等 V4/V5 后再评估(D4 同类问题)。

**原设计记录（本次执行按上述落地）**：

- **修改（两步，各自独立 commit）**：
  1. **状态外迁**：新增 `viewmodel/SourceRuntimeState.java`（终名随 D1），收编 `sortCache`/`extendCache`/`clearRuntimeCache()` 与 `spThreadPool` 别名的真实持有者角色（`SPIDER_POOL` 已在 `SourceHelper`，只需改调用点直引后删门面别名）。`SortLoader`/`SourceViewModel` 改为通过它存取；`HomeViewModel:150` 的 `SourceViewModel.clearRuntimeCache()` 调用点改指新家。**保留 `aa5b132` 的 access-order 加锁语义**（`synchronized (sortCache)` + 持锁不做 IO）。
  2. **包改名**（D1 已拍板：`sourcedata`）：`com.github.tvbox.osc.viewmodel` → `com.github.tvbox.osc.sourcedata`，**整包原样改名**，包内 40 个包级成员的可见性语义不变（这是改名而非重拆的唯一理由）。
- **前置检查（硬约束级）**：全库字符串检索 `com.github.tvbox.osc.viewmodel` 与各类名（含 `AndroidManifest`、`proguard-rules`、KV 字符串值、jar 契约面）——spider jar 理论上不依赖该包（其契约在 `catvod.crawler`/`catvod.bean`），但必须检索后才能动手。
- **风险**：低（纯机械替换 + 编译验证）。（备选"包内重拆"违反包级可见性前提，已随 D1 拍板驳回。）
- **验证**：构建 + 单测（`SortLoader`/`SourceRuntimeState` 的缓存行为已有用例则迁移，无则补 LRU 上限 5 条 + 清空两条）；真机走查 = 换源后 `sortCache` 清理仍生效（旧 spec 阶段 5 的判据沿用）。
- **Commit**：外迁一笔、改名一笔。

## V4｜LiveData 观察侧收口（消问题 ①a + ①b）

**执行状态（2026-10-01）**：

- 已落地（2 个本地 commit）：桥接器一笔、页面 VM 逐一改造一笔（本次两笔都改完）。
- **实测面修正（计划里的数字是旧的）**：全库 `observeForever` 实际只有 **3 处 / 2 文件**,且都在播放层 —— `PlaybackController.java:1177/1183`（playResult）、`PreloadCoordinator.java:112/166`（preloadResult）。页面 VM 侧只有 `DetailViewModel` 2 处（init 挂 + `rebindDetailSource` 重挂）与 `HomeViewModel` 4 处（3 个 VM 通道 + `PartitionLoader` 内部）,`PartitionListViewModel` 2 处（`actionViewModel` + 匿名 loader）。**`SearchViewModel` 一处都没有** —— 它的搜索结果是走 EventBus `TYPE_SEARCH_RESULT`（本 spec 立项时统计的"9 处 / 5 文件"里那一处早就不存在了,立项数据需以实跑为准的又一例）。`SubtitleSheets.kt:297` 用的是**生命周期感知**的 `observe(lifecycleOwner, …)`（带 `onDispose` 摘除）,不是手动配对,本来就不在收口范围内。
- **终态口径（改写 §4 第 3 条,原表述与播放层现实冲突）**:**页面层**（Kotlin 页面 VM + 组合层）零手动配对，全库唯一 `observeForever` 在桥接器 `LiveDataFlow.kt`；**播放层**（`Player` 包内 Java）保留 `observeForever`,理由见下,归 V5 一并收口。
- **落地内容**：
  1. 新增 `sourcedata/LiveDataFlow.kt`:`LiveData<T>.observeAsFlow()`,用 `callbackFlow` + `awaitClose { removeObserver }`。观察者挂在收集协程上 —— `viewModelScope` 取消即摘除,漏配对从"人肉纪律"变成"结构保证"。
  2. `DetailViewModel`:单实例 + 代次（见下）,删除 `rebindDetailSource`（换实例的手法整体消失）。
  3. `HomeViewModel`:3 个通道（sort/rec/action）改 flow 收集;内部类 `PartitionLoader` 的 `svm.listResult` 改 flow,**收集作用域 = `CoroutineScope(viewModelScope.coroutineContext[Job]!!)`**,`release()` 里 `cancel()`,等价旧的 `removeObserver`。
  4. `PartitionListViewModel`:同样处理 `actionViewModel.actionResult` 与匿名 loader（`loaderScope` 随 `onCleared` → `release()` 取消）。
  5. `SearchViewModel`:无需改动（见上,它本来就没有手动配对）。
- **`rebindDetailSource` → 单实例 + 代次（本阶段核心,已落地）**：
  - `AbsXml` 新增 `detailToken`（非详情通道恒 null）;`SourceViewModel.getDetail(..., Integer requestToken)` → `DetailLoader.getDetail(..., requestToken)` 在**每一条出口**都盖章(爬虫/接口/解析失败/空详情/push 合成);`SourceResultParser.json/xml` 新增带 `detailToken` 的重载,并在解析成功后赋值。`checkPush`/`checkThunder` 原地改写同一个对象,故盖章不会被后处理冲掉。
  - `DetailViewModel.detailRequestToken`:**只在"发起新的内容请求"时自增**（换片/换源/重试,即 `loadDetail`）;**fallback 换候选站不自增**（同一代内多候选,谁先回都算当前,与旧"换实例只在换片/换源时发生"一致）。
  - 判据抽成纯函数 `DetailResponseGuard.isCurrent(requestToken, responseToken)`(null = 无代次信息,不采信),单测 6 例锁行为 —— 含**换源但同 vodId** 那一格:源 A/源 B 命中同一部片时 `sourceKey` 会变而 `vodId` 可能相同,内容比对分不出来,只有代次能分（这正是"换实例"的技术替代）。
- **播放层的 2 处为何不在本阶段收口（登记理由）**:`PlaybackController`(Java,自持 `SourceViewModel`)与 `PreloadCoordinator`(Java,构造器里挂 `preloadResult`)都不是页面 VM,`observeForever` 是它们与门面之间的自然写法;两者**都已有配对清理**(`releaseFetch()` / `destroy()`),不存在 V4 要消的"漏配对即泄漏"形态。V4 若把它们也搬成 flow,就要在 `player` 模块引入协程作用域与生命周期,收益不匹配。归 **V5**(`PlaybackController` 拆簇时一并具名化 + 补配对清理检查),`PreloadCoordinator` 同批评估。
- **验证**：`assembleDebug` + `testDebugUnitTest` 全绿（**60 类 / 465 例 / 0 失败**；代次守备用例 7 条 —— 首版 6 条、审查轮补 2 条后删掉 1 条同义重复）。
- **审查轮（两轮独立复核）抓到 3 处真回归（已修）** —— 全部是本阶段引入、且前两笔审查轮从未出现的类型：
  1. **代次自增时机有洞（高）**：首版把 `nextDetailRequestToken()` 写在 `loadDetail` 的**早退之后**，于是"空 id / `msearch:` 占位 / 源不在当前订阅"这三类换片只改内容不换代 —— 上一代的迟到回包被守卫放行，而同源不同片时 `sourceKey` 比对也拦不住 ⇒ 旧片顶掉新页、并可能按新页的 vodId 写脏历史。**旧实现的 `rebindDetailSource()` 是无条件执行的，所以这是严格退化**。修法:自增提到早退之前，并把判据抽成 `DetailResponseGuard.isUnloadableTarget` + 用例，让这条分支也有测试。
  2. **子作用域丢 Dispatcher（阻断级）**：`CoroutineScope(viewModelScope.coroutineContext[Job]!!)` 只带 Job，`launch` 兜底成 `Dispatchers.Default`，而 `observeForever` 内部有 `assertMainThread` ⇒ 首页首次建 `PartitionLoader` 即抛，分区/推荐永久 Loading。
  3. **`cancel()` 打到父作用域（高）**：同一行复用父 Job，`release()` 的 `cancel()` 会连 `viewModelScope` 一起取消 —— 而 `loadHome()` **每次换源都 release 旧 loader** ⇒ 首页切源后永久 loading。
  - 2/3 修法:`SupervisorJob(parent) + Dispatchers.Main.immediate`（`HomeViewModel.PartitionLoader` 与 `PartitionListViewModel` 各一处），两个坑都写进代码注释（最容易再犯的地方）。
  - **教训（已进 §9 流程）**：这三条都属"单测跑不到、只有读协程语义/路径枚举才能发现"的类型 —— `observeForever` 的主线程断言在纯 JVM 单测里根本不执行，所以"全绿"不能当通过。凡"给页面 VM 造作用域"的改动，必须显式检查**继承了什么 Dispatcher**与**cancel 会打到谁**；凡"判据前有早退"的改动，必须检查**早退分支是否也走了那条判据的更新步骤**。
  - 其余审查发现:测试里 3 例是同义重复（已删/改写为带反例的形态,后又删 1 例与「反向不等」完全同构的"同源不同片"用例——代次判据本身分不出场景,那条注释已并入相邻用例）；`observeAsFlow` 的 `trySend` 静默丢弃与注释口径已订正（并写明播放层是 `observeForever` 的登记例外）；`PushDetailResolver` 的 3 个 post 投的是**被原地改写的同一对象**（代次不会被冲掉，已核实非回归，未加冗余兜底）；活规范 §4.4 与附录 B 坐标已同步。
- **收尾审查轮（第 3 轮,两轮独立复核）**：结论 **0 阻断 / 0 高**，两处"中"已收口 ——
  1. **`rollbackManualSwitch` 改内容不换代次**：两位审查者对严重度判断不同（一位记高、一位记口味），我按代码核到**今天拦得住**（回包 `sourceKey` 是被弃源、而该方法刚把它恢复成上一部的 key，`onDetailResult` 的 `sourceKey` 比对会拦），但那是"靠两个字段恰好不相等"的巧合 ⇒ 已补 `nextDetailRequestToken()`，把"内容改写就得换代"变成与 `loadDetail` 同一条协议。**当前不构成真回归，是防未来的结构性收口。**
  2. **`loadDetailInternal` 读字段取代次**：改为**显式传参**（`loadNextFallbackCandidate` 调用点传 `detailRequestToken`），避免将来在中间插入换代时被静默读错。
  3. `PartitionLoader.observeScope` 是**一次性**作用域（取消后不能再 launch），而 `loaders` 表可能 `getOrPut` 复用同一个 loader ⇒ 已在 `release()` 写明"调用方必须先清表/摘除再 release"的顺序不变量（当前两个调用点都满足）。
- **登记为已知行为变化（非回归,但要说清）**：换片后**不再取消在途详情请求**（`cancelTag("detail")` 只在 fallback 超时与 `destroyEngine`）,旧实现里这些回包投给已摘观察者的旧 LiveData 实例、无人处理,现在会真正投递到页面再由代次守卫丢弃 ⇒ 差别是**推送链路的 `checkPush`(15s 阻塞)与讯雷解析会在已被切走的片上继续跑完**。另有两条同源差异:`detailResult` 在页面存活期始终有活跃观察者（旧实现换实例后旧通道无观察者）;观察者注册从"构造期同步"变成"`launch` 内注册"(靠 `Main.immediate` 仍同步,但保障从语言保证变成 Dispatcher 语义)。
- **登记为不修（既有问题,非本阶段引入）**：`DetailViewModel.retry()` 全库无调用方（死代码,首版提交说明把它当"换代路径"引用属误述）;详情**初始加载没有超时兜底**（只有 fallback 候选有 6s）,某源回调永不返回时会停在 Loading;`cancelDetailTimeout()` 实际是 `removeCallbacksAndMessages(null)`（清光 handler 全部消息,不止超时）。
- 未做：装机走查 —— `adb` 不在 PATH。走查判据（本阶段最关键的一组）：详情页快进快出连点（迟到回包不串片,尤其**换到另一个源但同一部片**）、fallback 自动换站期间旧回包不串、搜索聚合并发、`HomeViewModel` sort/rec/action 三通道互不串扰、首页下拉刷新与分区翻页仍正常。

**原设计记录（本次执行按上述落地）**：

- **终态（D2 已拍板：分域 + 单桥接器）**：**按语言分域**——`sourcedata` 包内 Java 侧继续 LiveData（Java 写 `StateFlow` 无语言便利，重写 9 文件违反"不重写 Java→Kotlin"且收益/风险比不成立）；Kotlin 侧页面 VM（`DetailViewModel`/`HomeViewModel`/`SearchViewModel`/`PartitionListViewModel`）只见 Flow。
- **修改**：
  1. 新增 `viewmodel→sourcedata`（或 `ui/common`）下 Kotlin 桥接扩展 `LiveData<T>.observeAsFlow()``（lifecycle-livedata-ktx 的 `asFlow()` 若依赖可用则直接用，否则手写 callbackFlow 桥）——**全库唯一允许 `observeForever` 的位置**。
  2. 4 个页面 VM 的 9 处 `observeForever`/`removeObserver` 全部换 `flow` 收集（协程作用域随 VM）。`HomeViewModel` 内部 helper 类（373 行 `svm`）与 `PartitionListViewModel` 的 `actionViewModel` 同步处理。
  3. **`rebindDetailSource` 语义转换（本阶段核心）**：换 `SourceViewModel` 实例改为"单实例 + 代次 token"（`detailBuildToken` 已存在，扩展为所有 detail 回包携带 token、VM 侧失配即丢弃）。换实例需求消失后，`static sortCache` 的"实例逃生"动机同步消失（V3 已外迁，两步互为因果闭环）。`searchCaller` 的隔离语义（`searchToken`）核对后同样收敛。
  4. `PlaybackController`（Java，1039 行自持实例）**不改桥接**：它不是页面 VM， LiveData observe 是其自然写法；仅登记其在 V5 拆分时一并具名化 + 补配对清理检查。
- **风险**：中。token 失配丢弃逻辑必须覆盖：换片、fallback 换站、重试三条路径的迟到回包。**这是本 spec 最高语义风险点**（原"换实例"设计就是为隔离迟到回包，等价性判据 = 迟到回包绝不串进新内容）。
- **验证**：构建 + 单测（重点补：token 失配丢弃、token 匹配透传、fallback 链上 token 继承）；真机走查 = 详情快进快出连点（迟到回包不串片）、fallback 自动换站、搜索聚合并发、`HomeViewModel` sort/rec/action 三通道互不串扰。
- **Commit**：桥接器一笔、逐 VM 各一笔。

## V5｜播放三件套结构拆分（消问题 ②a；风险最高，最后做）

**执行状态（2026-10-01）**：已落地（8 个本地 commit：6 笔拆分 + 1 笔审查轮修复 + 1 笔注释纠正；未推远程；全程"只搬位置不改逻辑"，逐簇做归一化多重集比对 + 构建 + 单测）：

| commit | 簇 | 交付与行数 |
| --- | --- | --- |
| `f1e11f7` | ComposeVideoController 手势 | 新增 `player/controller/GestureController.kt`（260 行：`OnGesture`/`OnDoubleTap`/`OnTouch` 三接口实现 + 滑动/长按倍速/触摸守卫）；宿主 1423 → 1214 行 |
| `806ce04` | PlaybackController 超时 | 新增 `player/PlaybackTimeouts.java`（80 行：3 MSG + 2 毫秒常量 + Handler + 投递/撤销）；宿主留 `start*/cancel*` 薄转发与三个 `handle*` 判定 |
| `1efa5ea` | PlaybackController 预载 | 新增 `player/PlaybackPreload.java`（103 行：协调器/就绪回调/评估驱动/起播前结果消费/作废）；宿主留 4 个转发 + `Host` 匿名实现 |
| `5f26c88` | PlaybackController 取流观察 | 新增 `player/PlaybackFetch.java`（239 行：107 行匿名 Observer 具名化为 `handlePlayResult` + 字幕/歌词地址 + 弹幕搜索搬迁）；主类 **2016 → 1806 行** |
| `a97bc5b` | PlayContainer 音轨选择 | 新增 `ui/player/TrackSelectorDelegate.java`（158 行：音/视频轨弹窗 + 200ms 复位 + 代次守卫）；容器 1422 → 1337 行 |
| `aa144b1` | 注释规范化 | 新文件里两条 `BugReview #N:` 前缀去掉（保留其解释）；存量 `BugReview #32`（ComposeVideoController）未动 |
| `c3993a1` | 审查轮修复 | 手势委托改 `lateinit` + 在 `initView` 内创建（构造期 NPE，见审查轮段） |
| `143d380` | 注释纠正 | 订正 `initView` 初始化顺序的错误表述（`ComposeLiveController` + `ComposeVideoController` 各一处） |

**逐簇结论（"拆出 / 留下 + 豁免理由"二选一）**：

- **PlaybackController 侧**：
  - 超时 / 预载 / 取流观察 —— **已拆出**（上表）。
  - 嗅探 —— **已收边，无新类**：WebView/脚本/嗅探链在 P0–P1 已属 `PlayUrlResolver`（794 行，`Host` 回调）；剩余 `webPlayUrl`/`webHeaderMap`/`webUserAgent` 三字段**留主类**（被重试阶梯 4 处、起播入口 3 处、投屏 2 处、`PlayUrlResolver.Host` 与外部页面共享，访问器已是公开面；搬走只增转接）。
  - 进度继承 —— **留下并豁免**：`progressKey`/`progressOwner` 是"会话归属"判定的组成部分（`isStalePlayResult` 的迟到回包守卫、`play()` 的落盘与解除接管、`PlaybackFetch` 的字幕/歌词/弹幕键绑定），`inheritProgress*` 与 `play()` 的 pendingInherit 消费同链；拆出只把字段访问换成跨对象契约，收益 ~90 行、风险中。
  - 主类 600 行软目标**未达**（1806 行）：剩余主体是"会话数据 + 调度状态机 + 音乐会话"，按 spec"不为行数硬拆"以簇归属收口；如后续再拆，候选是"音乐会话/媒体通知"簇（~300 行，spec 未列，需另立）。
- **PlayContainer 侧**：
  - 音轨选择段 —— **已拆出**（`TrackSelectorDelegate`；`isSameTrack` 改包级 static 供字幕轨选择复用）。
  - 挂摘/生命周期段 —— **留下**：服务化挂摘协议的页面侧本体（与 `PlaybackEngine`/`PageHost` 双向，拆出收益为负）。
  - 全屏旋转段 —— **留下**：~40 行薄转发，状态与 `mActivity`/`mController` 同生命周期。
  - 弹幕装配段 —— **留下**：~45 行薄转发（逻辑已在 `DanmuLoadController`）。
  - 控制器装配段 —— **留下**：装配面本体（建控制器/监听器/`surfaceSlot`）。
  - 字幕/歌词装配段（~330 行）—— **留下并豁免**：① 与 `mController` 三个字幕视图及 `mVideoView` 的轨道/cue 接口同体；② 决策链三合一守卫（`subtitleDecisionSeq` 代次 + `isAttached` 页面存活 + `scheduler.progressKey`/`trackMemoryKey` 会话键）是"当前容器的会话事实"，跨对象拆出会变成跨对象契约；③ 服务化后其职责本就是"视图 + 装配"；④ 本文件已两轮拆分（1839→1424→1407→1337），边际收益低于回归风险。
  - 提示层 / 预载 Toast / 投屏 / 媒体会话回调 / 播放控制转发 / 会话接管（D6）—— **留下**：均为薄转发或页面 API 面（各 10–130 行）。
- **ComposeVideoController 侧**：
  - 手势簇 —— **已拆出**（`GestureController`）。
  - `PlayerControlApi`/`PlayerActions` 的 UI 构建与回调转发簇 —— **留下**：30 个 `onXxxClicked` 是 3–8 行"回调转发 + `keepControlsAlive`/`hideBottom` + `fastClickAllowed`"，与 `PlayerUiState`/`VodControlListener`/`uiHandler`/`playerConfig` 全共享；拆出要求宿主暴露这些共享状态，等价于双向引用。

**验证**：每簇 `assembleDebug` + `testDebugUnitTest` 全绿（**468 例 / 0 失败**，与 V4 后基线同数——纯搬移不带行为变化）。逐簇"旧 → 新"归一化多重集比对（去空白 + 去 `host.`/`controller.` 前缀 + 字段读归一），missing 全部为登记的预期形式转换：

- 手势簇 3 = `onTouchEvent` 壳 / `super` 调用留宿主 / `LOG.e` tag 换新类名。
- 超时簇 = 常量值与 13 个调用点逐条映射核对（7×撤销 + 2×清超时 + 2×发超时 + 2×复合入口），非多重集比对。
- 预载簇 4 = 冗余判空归一 + `initFetch`→`ensureFetch` + 两个参数名。
- 取流簇 7 = `st` 字段→方法（`isSwitchStopPending`/`setUserPickedLine`）、字段写→setter（`webPlayUrl`/`currentArtwork`）、`deliverPlayResult`→`deliver`、`cancelPlayRequest` 去 public（含 2 条脚本归一化 artifact）。
- 音轨簇 2 = `isSameTrack` 去 private/加 static + 调用点加类限定。
- ⚠️ 登记的语义等价改写（非回归，但要说清）：`PlaybackPreload.onPlayerState` 与 `PlaybackFetch.handlePlayResult` 里"字段两次读 + 冗余判空"归一为"一次局部读"（调用点与字段替换全在主线程，无并发窗口）。
- 新增协作者的宿主注入沿用两种既有形态：`PlaybackTimeouts.Callback`/`PlaybackPreload.Host` 用接口；`PlaybackFetch`/`TrackSelectorDelegate` 包级持有宿主引用（同 `PlaybackAttemptState` 同包先例）。
- i18n 卡口（`.codebuddy/tools/i18n_gate.py`）复跑：**ui 0 处 + 非 ui 0 处**（新文件未引入未外置文案；`PlaybackFetch` 的 `"歌词"` 带 `// i18n: keep`）。
- **未做**：装机走查——设备离线（`adb devices` 空，与 V1–V4 同因）。走查判据 = `avbox-playback-service-spec.md` §4 的 1–14 全清单，本轮重点覆盖：手势（单击/双击/长按倍速/横滑 seek/亮度音量/预览态横滑）、三处超时（取流超时换线 / 20s 起播超时 / 播完延后撤会话）、预载（命中起播 / 下一集就绪 Toast / 弱网让路）、取流结果（切集换线换源迟到回包丢弃 / 字幕歌词弹幕 / 封面）、音轨与视频轨切换。

**审查轮（独立复核，2026-10-01）**：抓到并修复 **1 处阻断级真回归**，另完成一轮全量等价性复核。

- **【高，已修 `c3993a1`】构造期 NPE**：`ComposeVideoController.gestures` 最初写成属性初始化器 `internal val gestures = GestureController(this)`，而 `BaseVideoController` 的构造器会虚调用 `initView()`（`player/src/.../BaseVideoController.java:89–92`），**属性初始化器在 super 构造之后才执行** ⇒ `initView` 里 `gestures.attach()` 读到 null，播放页一建即崩（编译/单测都发现不了，只有读构造时序或反汇编能发现）。修法：`lateinit var gestures` + 在 `initView` 内创建（同 `ComposeLiveController` 先例）。**字节码实证**（`javap -c` 对 `app/build/tmp/kotlin-classes/debug/...ComposeVideoController.class`）：修复前主构造器 = `invokespecial BaseVideoController.<init>` → `new GestureController` → `putfield gestures`；修复后构造器无 `putfield gestures`，`initView` 体 = `new GestureController` → `setGestures` → `getGestures.attach()`。
  - 顺带用同一份字节码订正了一条既有误述（`143d380`）：Kotlin **不生成**零值属性初始化器（`= null`/`= 0`/`= false`）的 `putfield` —— 所以原实现 `private var gestureDetector: GestureDetector? = null` + `initView` 赋值不会被清掉（这是它一直能工作的原因）；`ComposeLiveController` 注释里"带 = null 初始化器的字段会把 initView 的赋值清掉"不成立，已改为准确表述。
- 复核确认（无问题）：5 个新文件的搬移完整性（逐块对齐删除行）；`PlaybackTimeouts` 的 101/102/103 与 15s/20s、"先 cancel 再 send"、`armPendingCompletionDrop` 用 `sendEmptyMessage` 而非 delayed；`PlaybackPreload.consumeResult` 的"命中即喂/未命中即 drop"顺序；`GestureController` 的 UP/CANCEL→`speedPlayEnd`、`super.onTouchEvent` 留宿主、`speedOld` 跨类读写；`TrackSelectorDelegate` 的 200ms 代次守卫与 `isSameTrack` static 化；`PlaybackController` 的三个匿名 Callback/Host 与 `PlayContainer.trackSelector` 均只延迟读外部字段（不在构造期做快照）；`PlaybackEngine` 的 `initFetch`/`initPreload`/`releaseFetch` 调用点齐全。
- 复核登记的既有问题（非本次引入、未改）：① `PlaybackFetch.handlePlayResult` 的 `view` 由"多次字段读"归一为"入口一次局部读"，理论上若 `publishQuality` 的 EventBus 订阅方在同一栈内换 bridge，结果会打到旧 bridge（实际订阅方是 UI 列表刷新、不换 bridge，且全在主线程 ⇒ 不可达）；② `release()` 沿旧实现不置空 `sourceViewModel`，页面销毁后仍可能对旧会话 svm 发 getPlay（旧行为一致）；③ `TrackSelectorDelegate` 每次点击 `new Handler` 不回收、靠代次守卫拦截（与旧一致）。

**第二轮（按 `SKILL.md` 项目规范复核，2026-10-01）**：逐条对账文档地图 / 注释红线 / 通用规范，发现 2 处"本次引入"与 2 处"既有文档失真"，均已修（构建 468 例绿；`read_lints` 0 诊断）：

- 【中｜本次引入】`TrackSelectorDelegate`（`ui/player` 层）随拆分新增了 3 处注释（类 javadoc / `Host.player()` / `invalidatePendingSwitch()`）——违反"UI 层不新增注释"约定，已删（随代码搬来的诊断注释保留）。`ComposeVideoController` 那句"initView 由父类构造器虚调用"属"不写会再踩"的坑注释，按红线保留。
- 【中｜既有文档】`SKILL.md` 文档地图把播放服务化 spec 标为"**草案,未实施**"，实况是 P0–P5 全部落地（2026-09-14）——"先读这里"的入口失真，已改为落地状态 + 未采集项（§4-6/7 量化埋点与 hprof 复测）。
- 【中｜既有文档】`avbox-mobile-ui-spec.md` 播放容器一节写"`SourceViewModel` 由容器直接持有"，代码里容器只用 `SubtitleViewModel`（`mActivity` 作 ViewModelStoreOwner 就地取用）——已修。
- 【低｜既有，登记不修】`PlaybackFetch.handlePlayResult` 约 105 行（即原匿名 Observer 主体，触 `avbox-code-review-spec.md` 的"方法 >100 行"阈值；本次按"只搬位置"未拆，若要拆另立）；`PlaybackTimeouts` 里"与既有 mHandler 的三条定时消息拆开"沿原文搬运（含过程叙事，按同批"注释精简"惯例处理）。
- 【信息】无新增包级环（`PlaybackFetch`↔`PlaybackController` 为同包类级双向，`GestureController` 同包单向）；`history/` 不补条目 —— V1–V5 的过程记录归 `skill/review/refactor-plan-20261001.md`，符合文档地图对 `skill/review/` 的定义（活规范已同步的只有手势节与 i18n 附录注记）。

**V5b（继续拆分到 ~1300 行，2026-10-01 同日追加）**：主类继续压到 1300 行左右，新增 3 个协作者（各一笔 commit，同样逐簇归一化比对 + 构建 + 单测）：

| commit | 簇 | 交付 |
| --- | --- | --- |
| `40afd50` | 音乐会话/媒体通知 | `player/MusicSessionDelegate.java`：会话/通知状态机、纯音频判定与封面兜底、清晰度切换；主类留 13 个转发 |
| `cf5a28a` | 播放器配置 | `player/PlaybackConfigDelegate.java`：会话起始补全、落库快照剔自动容错态、换集解码刷新、自动态开关 |
| `b65d5a1` | 重试与换线 | `player/PlaybackRetryDelegate.java`：同址重播、硬→软解回退、自动换内核、下一条线路、取流超时/失败/换线超时三入口 |
| `8f05161` | 注释精简 | 主类注释 13 处（过程叙事 4、超两行块压缩 9、失效引用 2）；只动注释（diff 非注释行 0 校验过）；1340 → **1320 行** |

- 主类行数：**1806 → 1339**（V5 五笔 -187；V5b 三笔 -280）。每笔 `assembleDebug` + `testDebugUnitTest` 全绿（468 例）。
- 归一化比对 missing 全为登记的预期转换：局部变量化（`playerCfg`→`cfg`、`view`/`vod` 入口一次读）、可见性（`private`→包级）、javadoc `{@link #x}`→`{@code x}`（跨类引用失效）、一处区头删除。
- 语义等价改写（登记）：`restoreAutoSwitchedDecode`/`trySoftDecodeFallback`/`autoRetry`/`tryNextLine` 等把"字段多次读"归一为"入口一次局部读"；`initPlayerCfg` 改为"局部 cfg 构建完再 `setPlayerCfg`"（原实现"先换引用再逐条填"，最终状态一致；差异仅在构造窗口内被其它线程读到旧对象 —— 主线程独占，不可达）。
- 仍留主类（理由不变）：`webPlayUrl`/`webHeaderMap`/`webUserAgent` 三字段、进度键与 `progressOwner`、投屏地址改写、解析门面转发。若还要更低（<1300）：可把 header 工具三方法搬进 `PlayerHelper`（约 -20）与投屏地址改写独立（约 -25），边际收益已低。
- 注释精简后的复核（2026-10-01 第三轮，按 `SKILL.md`）：diff **非注释行 0**（只动注释）；i18n 卡口 **0 处**（`// i18n: keep` 随代码搬到 delegate 仍生效）；主类 30 个私有成员**无死代码**；`{@link #x}` 引用全有效（主类 7 处 + 8 个拆出类逐个扫描）；构建 + **468 例**单测绿。**修掉 V5b 遗留的 2 处失效注释引用**（`handlePendingCompletionDrop`/`updateMusicSession` 已随簇搬到 delegate），并删/改了 4 处过程叙事（"旧 publishQuality"、"对应原 hostDestroy 的…"、"原 initPlayerCfg 末尾那次调用"、"预载协调器仍归页面"）。
- **逻辑复核（2026-10-01 第四轮，针对 V5b 三簇的跨对象语义，独立复核）**：结论 = **三簇搬迁逻辑等价、可收尾**（无 阻断/高/中 级发现；A 异常路径逐分支等价、B 可变引用的"多次读→一次读"在本调用上下文不可观察、C Host 字段/方法一一对应无错位、D 时序/NPE 与 E 并发路径均安全）。抓到并修掉 **1 处本轮引入的低危语义漂移**（`9602aa7`）：`tryNextLine` 的换线 toast 闭包在原实现里捕获**字段**（`runOnUi` 执行时读最新桥），拆分后变成捕获局部快照 ⇒ 若闭包期间 page↔headless 换桥，会打到已摘除的旧页面；已改回读执行时的 `host.view()`（另两处同类闭包原本就用 `aliveView` 快照，保持不动）。
  - 登记不改（低危/风格/约束）：① `updateMusicSession`/`stopMusicSession` 等"字段多次读→入口一次读"—— 入口全在主线程、换桥只发生在 attach/detach，同一方法执行期内不可达；② 主类 Host 里 `quality()`/`progressKey()` 直返字段、`sourceBean()` 走方法 —— 当前恒等，将来给 getter 加防御时 delegate 会静默旁路；③ `timeouts`(369) 的 Callback 引用后声明的 `music`、`retry` Host 引用后声明的 `fetch`/`music` —— 方法体前向引用合法且运行期安全，但**不得**从字段初始化式/构造期做同步分发（会读到 null）；④ 8 个协作者无单测（播放层依赖 Android 运行时），豁免理由 = 逐簇归一化比对 + 构建/单测 + 真机走查。

**原设计记录（本次执行按上述落地）**：

- **`PlaybackController` 2016 → 主类 ≤600 量级（软目标）**，按旧 spec 阶段 7 登记的簇 + 实测字段分布：
  | 簇 | 证据（行号） | 去向 |
  | --- | --- | --- |
  | 超时 | `MSG_RESOLVE_PLAY_URL_TIMEOUT` 等 3 MSG + 2 常量（437–444） | `PlaybackTimeouts` |
  | 嗅探 | `webPlayUrl`/`webHeaderMap`/`webUserAgent` + WebView 逻辑（1025–1027） | `SniffDelegate` |
  | 预载 | `preloadCoordinator`/`preloadReadyListener`（1614–1615） | 已半独立，正式收边 |
  | 取流观察 | `sourceViewModel`/`playResultObserver` + **1075 行 107 行匿名 Observer**（旧 spec 阶段 2 遗留） | 具名类 + 收口进主类或 `SniffDelegate` |
  | 进度继承 | `progressKey`/`progressOwner`/`inheritProgress*`（81–99） | 评估：若与 D6 同片接管耦合深则留主类并豁免 |
- **`PlayContainer` 1407**：逐簇评估（挂摘/生命周期段、全屏旋转段、弹幕字幕装配段、控制器装配段），**每簇给出"拆出 / 留下并写豁免理由"二选一结论**；已两轮拆分，警惕"为行数硬拆"（服务化后其职责本就是装配面）。
- **`ComposeVideoController` 1423**：先拆手势簇（`OnGestureListener`/`OnDoubleTapListener`/`OnTouchListener` 三个接口实现 → `GestureController` 委托，信号独立性最强）；`PlayerControlApi`/`PlayerActions` 的 UI 构建与回调转发簇拆分与否，在手势簇落地后按耦合实测再定。
- **风险**：高。全程"只搬位置不改逻辑"（播放服务化 R8 同款纪律）；每簇独立 commit，任何一簇回归即独立回滚。
- **验证**：构建 + 单测；真机走查按 `avbox-playback-service-spec.md` §4 的 1–14 全清单（该清单是本阶段回归基线，含 2026-09-21 待测的 11–14 项）。
- **Commit**：每簇一笔，预计 5–8 笔。

## V6｜（已拍板：不做）ApiConfig 直播解析链外迁

- 旧 spec 阶段 4 明示未做（≈420 行：`parseLive*`/`loadLives`/`loadLiveApi`/`initLiveSettings`），理由 = 直播可用性关键路径 + 当时阶段 3 走查未完成。现走查已过，风险敞口收窄，但**直播链路真机回归成本仍在**（清配置重载/多仓/线路切换/hosts 失效）。
- 不做的代价：ApiConfig 停在 ~1054 行，直播链与站源门面继续同文件。
- **已拍板不做（2026-10-01，按建议）**。若直播链后续出现回归需改动该区域，凭"单独排一次直播专项真机回归（判据：切直播源、线路历史、hosts 失效、跟随点播四路径）"的前提可重开。

# 6. 设计决策（D 系；2026-10-01，⏳ 已清零）

| # | 问题 | 建议 | 状态 |
| --- | --- | --- | --- |
| D1 | `viewmodel/` 改名去向 | **整包改名 `com.github.tvbox.osc.sourcedata`**（站点取数与解析域；不并入 `data`，Room 域不混淆）。备选：改 `repository`（违反旧 spec"不引入 Repository 层"的表述直觉，弃）；不改名只加 package-info（未"彻底处理"，弃）。**前提 = 整包原样改名，不做包内重拆**（包级可见性 40 成员是同包复用的根基，重拆 = 把包级成员变 public，阶段 5 已验证该代价模式不可取）。 | ✅ 已拍板（2026-10-01）：按建议 |
| D2 | LiveData 是否全转 StateFlow | **按语言分域 + 单桥接器**（V4）。全库单范式的代价：`sourcedata` 9 个 Java 文件重写（违反旧 spec"不重写 Java→Kotlin"决策）或 Java 写 `StateFlow`（`setValue()` 可用但无语言便利，且 `postValue` 异步主线程语义要手工复刻）。翻案判据：`sourcedata` 包未来整体迁 Kotlin 时一并转。 | ✅ 已拍板（2026-10-01）：按建议 |
| D3 | `spThreadPool` 公开别名的去留 | `SPIDER_POOL` 真身在 `SourceHelper`；别名服务的是外部调用点（V3 时全库检索）。若外部调用点 ≤3 处改直引后删别名；若多则保留别名并登记为"稳定 API"。 | ✅ 已拍板（2026-10-01 执行 V3.1 时定）：外部调用点实际只有 1 处（门面自己的 `action()`），已直引 `SourceHelper.SPIDER_POOL` 并删除别名 |
| D4 | `DetailViewModel` 自身 959 行是否拆 | V2/V3 改完后重估（fallback 换源簇 ~200 行、搜索簇 ~100 行是候选）。**不预设拆分**——它 62 个方法中一半是 VM 本职的事件转发。 | 执行时定 |
| D5 | V1 的 `ConfigManageViewModel` 作用域 | 独立 Activity 专属（`ConfigManageActivity` 壳 3KB），不共享、不挂载 Application 作用域。 | 定案 |

# 7. 明确不做

- 不引入 DI 容器（旧 spec §6 决策继承，翻案判据同彼）。
- 不拆 Gradle module；不动 `player` 模块上游内核（`tv.danmaku.ijk`、`xyz.doikki`）。
- 不为行数达标硬拆播放器状态机与 `PlayContainer` 装配面（V5 的出口条件是"簇有归属与理由"，不是数字）。
- 不把 `playContainerRef` 改 `WeakReference` 糊弄（治标：泄漏概率降但指令通道缺失与同步读 View 状态的原病都在）。
- 注释红线存量（⑪）、包级环剩余 17 组（旧 spec 阶段 6 收尾）、阶段 1b（Live 两簇）不在本 spec 范围。

# 8. 硬约束（违反即驳回）

- **迟到回包隔离语义不可弱化**（V4 第一验收）：换片/fallback/重试三路径的旧回包必须丢弃——"换实例"换"token"后此为全 spec 最高风险点。
- `sortCache` 的 access-order 加锁语义保留（`aa5b132`）：所有读写走 `synchronized (sortCache)`，持锁不做 IO。
- 任何类/包改名前：全库检索类名字符串（含 manifest/proguard/KV 值/jar 契约），不能只改 import（旧 spec §7）。
- KV 复杂键必须在 `KVKeySpec` 登记；`KV.contains` 才是存在性判断。
- BootGuard 白名单是唯一旋钮；黑名单状态的读改走 `BootGuard` 门面，V1 迁移时不得绕过。
- `ConfigParser` 字符集过滤、`VideoParseRuler.clearRule()` 的入口位置（V6 若做）不可动。
- 爬虫/网络阻塞调用在 IO 线程；`spThreadPool` 语义（共享池 + spider 阻塞）不得在迁移中变成每请求新建。
- Compose 状态提升位置正确性：`PendingSwitch` 带 `vod` 的 AnimatedContent 过渡陷阱在 V1 中必须保留。

# 9. 验证与回滚

- 每步落地：`.\gradlew.bat :app:assembleDebug` + `:app:testDebugUnitTest`；以 `BUILD SUCCESSFUL` 与 `test-results` 用例计数为准（**当前基线 58 类 455 例** = V1 执行日 447 + V2 新增 8；09-28 参考值 45 类 376 例，其后 09-29/30 有内核移除与调色等增量提交）。
- 纯搬迁步（V3 改名、V5 各簇）：`git show HEAD:旧文件` 逐行去空白后多重集比对 + 方法级存在性检查（旧 spec 阶段 4/5 验证过的卡口，含 marker 唯一性教训）。
- 语义转换步（V2 指令流、V4 token）：必须先补单测锁行为再改实现（`token 失配丢弃`/`指令有序消费`）。**V2 教训（写进流程）**：改动判据前先按 `git show HEAD^:文件` 把旧表达式抄下来逐格推真值表，再拿真值表写断言；凭直觉写的断言会把"新行为"锁成"旧语义"，给的是虚假覆盖信心（V2 首版即如此，审查轮才抓到）。判据类改动一律先查 `skill/history/features.md` 有没有该判据的真机验证记录与"不要盲改"警告。
- **V4 教训（写进流程）**：三类问题单测与"全绿"都发现不了，只能靠读语义与路径枚举 —— ①**判据前的早退分支**是否也走该判据的更新步骤（V4 首版在 `loadDetail` 早退前漏了换代次）；②**自建 CoroutineScope 继承了什么 Dispatcher**（漏了就是 `Dispatchers.Default`，而 `observeForever`/UI 状态有主线程断言）；③**`cancel()` 会打到哪个 Job**（复用父 Job 就会连 `viewModelScope` 一起杀，而 `loadHome()` 每次换源都会 release）。审查这类改动时，重点不是"用例过没过"，而是"哪些代码路径没有用例能覆盖"。
- **V5 教训（写进流程）**：`View` 子类的 `initView()` 会被父类构造器**虚调用**（dkplayer `BaseVideoController` 构造器第 91 行），此时子类属性初始化器尚未执行 —— 拆出委托/助手时若让"委托字段"用带非零值初始化器的属性持有（如 `val gestures = GestureController(this)`），`initView` 里第一次使用就是 NPE。**判据：凡在 `initView()` 内使用的协作对象，一律 `lateinit` + 在 `initView` 内创建**。另一条字节码实证：Kotlin **不生成**零值（`= null`/`= 0`/`= false`）属性初始化器的 `putfield`，故这类字段在 `initView` 里赋值不会被覆盖（别把 `= null` 当危险写法）。验证手段：`javap -c` 反汇编 `app/build/tmp/kotlin-classes/debug/` 下对应 class，直接看构造器与 `initView` 的指令顺序。
- 真机走查判据按阶段分列（见各节）；V5 绑定播放服务化 §4 全清单。
- 每步一个 commit，可独立回滚；不推远程除非明确许可。

# 10. 优先级与排期

| 阶段 | 消掉 | 估时 | 依赖 |
| --- | --- | --- | --- |
| V1 | ④ | 1 人日 | 无 |
| V2 | ③a | 0.5–1 人日 | 无 |
| V3 | ①c + ③b | 0.5–1 人日 | 无（D1 已拍板） |
| V4 | ①a + ①b | 2 人日 | V3（状态先归位，桥接才干净） |
| V5 | ②a | 3–4 人日 + 真机回归 1.5–2 人日 | 建议在 V1–V4 稳定一周后 |
| V6 | ②b | — | 已拍板不做（重开条件见 V6 节） |

总计核心路径（V1–V5）约 8–10 人日 + 两轮真机回归。建议节奏：V1+V2 一轮 → V3+V4 一轮 → 观察一周 → V5。

# 附录 A. 度量口径（可复现）

- 行数：`Get-ChildItem -Recurse -Include *.java,*.kt -File app\src\main | ForEach-Object { [pscustomobject]@{ Lines=[IO.File]::ReadAllLines($_.FullName).Count; Rel=$_.FullName } } | Sort-Object Lines -Descending`
- 方法数（Java，宽口径含匿名类覆盖）：`Select-String '^\s*(public|private|protected)\s+[\w<>\[\], .]+\s+\w+\('`；Kotlin fun：`Select-String '\bfun\s+\w+'`。**口径提示**：与用户 09-28 提法（129/133/76）存在 ±10% 差异，因宽窄口径不同——引用本 spec 数字时注明口径。
- `observeForever` 面：`Select-String '\.observeForever\('` 全库。
- VM 持 View 引用面：`Select-String 'PlayContainer\?|View\b'` 限 `**/viewmodel/**` 与页面 VM。

# 附录 B. 关键代码坐标（执行时直接定位；**V1–V5 落地后已订正**）

- `DetailViewModel` 播放指令流：`DetailPlaybackCommands.kt`（指令/事实/全屏判定）；收集点 `DetailScreen.kt:73–83`；设备事实 `DetailActivity.playbackFacts()`。
- `DetailViewModel` 详情代次（V4）：`detailRequestToken` 声明与自增（`loadDetail` **早退之前**）、`loadDetailInternal` 沿用当代；判据 `DetailResponseGuard.kt`；回包盖章 `DetailLoader`（每条出口）+ `SourceResultParser.json/xml` 的 5 参重载 + `AbsXml.detailToken`。
- `observeForever` 现存 **3 处 / 2 文件**（全在播放层）：`PlaybackController.java` playResult（挂/摘配对 `releaseFetch()`）、`PreloadCoordinator.java` preloadResult（挂/摘配对 `destroy()`）。页面层其余全部经 `sourcedata/LiveDataFlow.kt` 的 `observeAsFlow()`。
- 运行期状态：`SourceRuntimeState.java` 的 `sortCache`（access-order + 上限 5）/`extendCache`/`clearRuntimeCache()`；唯一清理调用点 `HomeViewModel.reload()`；spider 线程池真身 `SourceHelper.SPIDER_POOL`（门面 `spThreadPool` 别名已删）。
- `ConfigManagePage`：`ConfigManageViewModel`（状态 + 14 方法）；页面留 UI 开关与 `badgeText`。
- `PlaybackController` V5 后结构：超时 → `PlaybackTimeouts.java`（3 MSG/2 常量/Handler 全在彼）；预载 → `PlaybackPreload.java`（含起播前结果消费 `consumeResult`）；取流观察 → `PlaybackFetch.java`（`handlePlayResult` + 字幕/歌词地址 + 弹幕搜索）；进度继承与 `webPlayUrl`/`webHeaderMap`/`webUserAgent` 三字段留主类（豁免理由见 V5 节）。
- `ComposeVideoController` 手势 → `player/controller/GestureController.kt`（`attach()` 在 `initView` 装配、`setOnTouchListener` 挂委托；委托经 `host` 读 `state`/`previewMode`/`curPlayState`/`playerConfig`/`speedOld`/`wrapper`）；`PlayContainer` 音/视频轨 → `ui/player/TrackSelectorDelegate.java`（`isSameTrack` 为包级 static，字幕轨选择复用）。
- `HomeViewModel`：三通道收集器 + `PartitionLoader`（`observeScope` 必须是 `SupervisorJob(parent) + Main.immediate`，见代码注释里的两个坑）。
