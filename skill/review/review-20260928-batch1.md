# 重构复查报告：阶段 5（SourceViewModel 分段）+ 阶段 6（解环）

- 审查对象：`c300ebc..HEAD`（阶段 5 的 12 笔 + 阶段 6 的 5 笔，共 17 个 commit；代码改动 61 文件 / +168 −135 之外，阶段 5 为新建 9 文件 + 门面瘦身）
- 审查方式：只读复查（不修代码）；重点 = "搬迁是否等价""包移动是否破坏契约""是否留下新回归/遗漏"
- 结论：**无阻断 / 无高**；发现 11 项（中 4、低 7），其中"本次引入"9 项、既有被放大 1 项、计划口径未达成 1 项

## 0. 覆盖度声明

- 本批范围：阶段 5 新增/改建的 `viewmodel` 10 文件 + 阶段 6 触及的 61 文件（`player`/`ui.player`/`ui`/`data`/`util`/`subtitle`/`catvod`）
- 已审：全部 71 个涉改文件逐个过；另全库（318 文件）跑度量
- 跳过：`player` 模块上游内核（`tv.danmaku.ijk`、`xyz.doikki`）只看宿主桥接；`quickjs`/`pyramid`/`libs`/参考代码按排除范围；`build/` 产物
- 工具与命令：方法级等价脚本（按签名定位 + 花括号配对 + 归一化去 owner 前缀/可见性，见附录 A 口径）、包级环脚本、`git worktree` 取上轮快照做集合差、SDK sources 复核框架语义、子代理做非源码文件全量引用扫描

## 1. 复查方法（可复现）

1. **方法级等价**：把 `c300ebc:viewmodel/SourceViewModel.java` 的 43 个方法逐一与搬迁后的落点比（归一化：去注释、去空白、去 `SourceHelper./resultParser./listLoader.` 等 owner 前缀、去可见性关键字）
2. **不变量计数**：`i18n: keep` 标记、`BoundedCall.call` 调用点与其超时/日志 tag、`ERR_NETWORK` 用法数、主线程守卫数、两个线程池的逐点归属、`sortCache` 三处访问是否都在锁内
3. **契约扫描**：对 6 个被移动/改可见性的符号（`RemoteTVBox`、`SubtitleFilePicker`、`EpisodeMatcher`、`PreloadCoordinator`、Room 8 类、`AppContextHolder`）扫 `*.xml/*.pro/*.gradle/*.kts/*.json` 与字符串字面量
4. **框架语义**：6.5 把 46 处 `App.getInstance()` 换成 `AppContextHolder.context()`，用本机 SDK sources 复核 `getApplicationContext()` 的对象身份
5. **边界**：所有 source set（`main`/`test`/`python`）与 `player` 模块的引用面

## 2. 复查结果：等价性成立（无回归项）

**方法级等价（43 方法）**：10 个方法有差异，差异**全部**归于四类机械改动，无一处语义漂移：

| 差异类 | 出现处 | 判定 |
| --- | --- | --- |
| 线程池改名（`httpPrepareThreadPool`→`PREPARE_POOL`、`spThreadPool`→`SPIDER_POOL`） | getSort / getList / getDetail / getSearch / getPlayPrepared / getHomeRecList / getFixUrl | 同一实例门面仍持别名，归属逐点核对无错配 |
| `extend` 解析方法加 `(extendCache, gson)` 形参 | getSort / getList / getDetail / getSearch / getPlayPrepared / getFixUrl / getFixUrlDirect | 传入的就是门面那两个单例 |
| 内部类限定名（`new HomeRecCallback()` → `new ListLoader.HomeRecCallback()`） | getSort ×3 | 同一定义 |
| 主动加锁（⑫） | getSort 的 `sortCache.get`、cacheSort 的 `put`、门面的 `clear` | 设计内改动，三处同监视器 |

行数完全对齐的关键路径：`getDetail` 101/101、`getList` 121/121、`getPlayPrepared` 130/130、`getSearch` 99/99、`getHomeRecList` 67/67、`checkPush` 3077/3077 字符、`parseMarkedHeaders` 779/779 字符。

**不变量核对**：`i18n: keep` 7→7；`BoundedCall.call` 5→5（超时表达式与 `echo--getSort--` 等日志 tag 逐条一致）；`ERR_NETWORK` 10→10（含 1 处定义 + 1 处变体文案）；主线程守卫 4 处原样保留（`SearchLoader` 的 t4 分支原本就没有守卫，非丢失）；硬约束文件未动（`api/`、`VideoParseRuler`、`util/kv`、`BootGuard` 的逻辑面；`BootGuard`/`App` 的改动只在 Context 获取入口）。

**框架语义（6.5 的关键假设）**：`AppContextHolder.context()` 存的是 `App.getApplicationContext()`。本机 SDK sources 确认身份不变 —— `ContextWrapper.getApplicationContext()`（`android/content/ContextWrapper.java:151`）转发到 `mBase`，`ContextImpl.getApplicationContext()`（`android/app/ContextImpl.java:490`）返回 `mPackageInfo.getApplication()`，即 Application 本体 = `App`。因此 `getClassLoader()`/`getAssets()`/`getFilesDir()` 与反射点（`ProtectedInitJar` 的 `context.set/field.set/method.invoke`、`JarLoader.bindInitContext`）拿到的仍是同一个对象，爬虫 jar 的 init 反射不受影响。空窗期也等价：原先 `instance = this` 之前 `App.getInstance()` 返回 null，现在 `install(this)` 之前 holder 返回 null，两者相差 1 行且中间只有注释。

**边界与契约**：6 个符号在 xml/manifest/proguard/gradle/assets 与字符串字面量中零命中（命中的只有 `app/build/` 产物与 `app/proguardMapping.txt` 这类生成物）；Room schema 目录 `app/schemas/com.github.tvbox.osc.data.AppDataBase` 未变（`AppDataBase` 未移动）；`SpiderDebug` 因属爬虫 jar 公开面**未**移动（`util/Proxy` 仍指向它，这是 `crawler ⇄ util` 环的唯一来源，已在计划中记录）。

**环数与边界**：25 → 17 组，集合差确认每笔只消不增（6.1 的差集 = 仅 `osc.player ⇄ osc.ui.player` 消失、0 新增）。

## 3. 问题清单

### F1（中 / 本次引入）多语言红线表的指针全部失效

- 位置：`skill/avbox-i18n-spec.md:70`、`:76`、`:77`（锚点：`| R2 | viewmodel/SourceViewModel.java:235 |`、`| R8 | viewmodel/SourceViewModel.createPushDetail |`、`| R9 | viewmodel/SourceViewModel |`）
- 描述：这张表是"改文案前必读"的红线入口，三条指针在阶段 5 后全部指向不存在的位置：R2 的 `name.endsWith("搜")` 现于 `viewmodel/SortLoader.java:135`；R8 的 `createPushDetail` 现于 `viewmodel/DetailLoader.java:174`；R9 的 9 处 `ERR_NETWORK` 现分散在 `SourceHelper`（定义）+ 5 个 Loader + `player/PlayUrlResolver`。指针失效会让后续审查按图索骥失败（R9 的"9 处"分母也需重算）
- 证据：`grep -n 'endsWith("搜")' viewmodel/SortLoader.java` → `135: ... endsWith("搜")){ // i18n: keep`
- 建议：三条指针改指新落点；R9 顺带复算口径（现为 9 处用法 + 2 处字面量，分布在 6 个文件）

### F2（中 / 本次引入）同类问题还波及 i18n 附录 A 的普查分母

- 位置：`skill/avbox-i18n-spec.md:344`、`:346`（锚点：`**A.1 UI 层(43 文件 / 435 处)**`、`**A.2 非 UI 层(32 文件 / 213 处…)**`）
- 描述：普查按文件列条目，其中 `DetailScreens`、`PreloadSettingsPage` 已不存在（阶段 1 / 更早），`viewmodel/SourceViewModel 15` 已分散到 10 个文件（阶段 5）⇒ 43 文件 / 435 处这组分母失真
- 建议：要么重跑普查，要么把该附录明确标为"历史快照（某日）"并注明失效，避免被当现状引用

### F3（低-中 / 本次引入）审查规范里的分批定义指向已删除的包

- 位置：`skill/avbox-code-review-spec.md:55`、`:105`
- 描述：模块清单仍写 `含 api/base/bean/cache/data/…`；批次一仍列 `osc/cache`（该包已在 6.4 并入 `data`）。批次定义是"喂给 AI 的文件范围"，指向不存在的包会让每轮审查的范围界定失准
- 证据：`:105` 锚点 `**批次一 数据与解析链**（约 120 文件）：… osc/data、osc/cache、osc/bean …`
- 建议：删 `cache`、把 `osc/viewmodel` 的落点变化补进批次二的文件数说明

### F4（低 / 本次引入）活规范里常量归属过时（6.6 直接造成）

- 位置：`skill/avbox-mobile-ui-spec.md:389`（锚点：`常量在 ui/components/GlassTopBar.kt`）
- 描述：`GLASS_BACKDROP_BAND_MARGIN_DP` 已下沉到 `ui/theme/LiquidGlassConfig.kt`（值仍 64，两处使用点已同步改 import）
- 建议：把该括注改为 `ui/theme/LiquidGlassConfig.kt`

### F5（低 / 本次引入）6.6 留下一个死局部变量与未用 import

- 位置：`app/src/main/java/com/github/tvbox/osc/ui/components/VodCardMenu.kt:10`、`:46`
- 描述：回调化之后 `val context = LocalContext.current` 已无使用者（`import androidx.compose.ui.platform.LocalContext` 随之无意义）。Kotlin 只给 warning 不报错，因此构建/单测全绿时不会暴露
- 证据：`grep -n "context" VodCardMenu.kt` → 仅剩 `46: val context = LocalContext.current` 一处（无引用）
- 建议：删这两行

### F6（中 / 既有，被阶段 5 原样保留）超长方法没随拆分收敛

- 位置：`app/src/main/java/com/github/tvbox/osc/viewmodel/SortLoader.java:112`（`getSort`，估算 **237 行 ±10**）、`app/src/main/java/com/github/tvbox/osc/viewmodel/PlayLoader.java:81`（`getPlayPrepared`，估算 **136 行 ±10**）
- 描述：阶段 5 的目标只写了"文件规模"（1946 → 168），把两个超长方法**原样**搬进新文件。结果是"文件不再巨大，但改一次首页取数仍要面对 237 行方法"——计划 §2 ⑥（超长方法 17 处）在本阶段没有覆盖，属未列入执行的遗漏项
- 证据：`SortLoader.java:112 void getSort(final String sourceKey, final boolean withRec) {` 起，到 `:344` 类尾
- 建议：按 type 分支拆 `getSort`（type3 / t0-1 / t4 / 其它四段各自成私有方法），`getPlayPrepared` 同法拆 type 分支；单独一笔提交，配等价性脚本核

### F7（中 / 既有被放大）取数链路零单测，等价性只有结构证据

- 位置：`app/src/test/java/com/github/tvbox/osc/`（无 `viewmodel` 目录）
- 描述：阶段 5 把 43 个方法重新落点，等价性证据 = 行集比对 + 方法级比对 + 编译 + 既有 367 用例；而既有用例不含取数/分投/取流任一环节。风险不在"这次搬错"（已逐行核过），而在"下次改这块没有安全网"——拆分把 1 个文件变成 10 个文件，可测面本应变大却没变
- 建议：给 `SourceHelper.getFixUrl`（超时回退语义）、`SourceResultParser` 的三通道分投、`PlayLoader` 的双序号丢弃过期结果各补 1 条 JVM 级单测（不依赖 Android 框架）

### F8（低 / 本次引入）全局 Context 入口更替的冷启动面需真机确认

- 位置：`app/src/main/java/com/github/tvbox/osc/base/App.java:55`（锚点：`AppContextHolder.install(this);`）、`app/src/main/java/com/github/tvbox/osc/util/AppContextHolder.java:22`
- 描述：46 处 `App.getInstance()` 换成 holder。身份不变已由 SDK 源码论证（见 §2），残余风险两类：①极早调用（`attachBaseContext` 链、ContentProvider 早于 `Application.onCreate`）取到 null —— 与改造前同窗口，但现在是**新的**单点，值得实机确认冷启动无异常；②爬虫 jar 若反射 `init(Application)` 之外的签名，`method.invoke` 的实参身份不变故不受影响
- 建议：真机冷启动走查一遍（首屏、爬虫 jar 装载、本地源、局域网服务器）

### F9（低 / 本次引入）门面的公开线程池别名已成准死代码

- 位置：`app/src/main/java/com/github/tvbox/osc/viewmodel/SourceViewModel.java:43`（锚点：`public static final ExecutorService spThreadPool = SourceHelper.SPIDER_POOL;`）
- 描述：为保签名兼容保留，全库外部 0 使用者，内部仅 `action` 一处用它。若 `action` 后续外迁，该字段即完全死代码
- 建议：保留现状即可（它同时充当"池只有一个"的文档），但下次动 `action` 时一并评估删除

### F10（低 / 既有）解析器里两个方法带未使用的形参

- 位置：`app/src/main/java/com/github/tvbox/osc/viewmodel/SourceResultParser.java`（`sortJson(MutableLiveData<AbsSortXml> result, String json)`、`sortXml(...)` 同形）
- 描述：`result` 形参在方法体内从未使用（上游遗产）。搬迁时按"逐字等价"原则原样保留，所以不是本次引入，但它是典型的"读者会以为要写入该通道"的误导线
- 建议：删形参（3 个调用点），单独一笔低风险提交

### F11（中 / 计划口径未达成，非回归）6.5 未消掉 `base ⇄ server`、`base ⇄ util`

- 位置：计划 §4 阶段 6 表格「6.5 → 消掉 `base ⇄ data/server`、`catvod.crawler ⇄ base`」
- 描述：实际消掉 `base ⇄ data`、`crawler ⇄ base`（各 1 组），`base ⇄ server`、`base ⇄ util` 仍在：残留是 `App.vodInfo` / `getDashData()` 这条隐式通道（`PlaybackProgress`、`WatchProgressStore`、`RemoteServer` 读它）。该通道的收口在 `avbox-playback-service-spec.md`，此处不预判其迁移方式 —— 属**有理由的未达成**，需在验收口径上认账而不是当作已完成
- 建议：要么在计划里把 6.5 的目标改为"消 `base ⇄ data` + `crawler ⇄ base`"（现状），要么把 `vodInfo` 收口排进播放服务化 P 系列

## 4. 重构决策

- **代码层：A**（不需要为本次重构再做架构改动）。理由：方法级等价成立、不变量全对、契约零命中、环数只减不增、构建与 367 用例全绿；发现的代码问题只有两处死代码（F5、F10）与两处"未收敛"（F6、F7），都不构成回归
- **文档层：B**（小范围）。F1–F4 是活规范指针失效，属"文档与事实不一致"，建议一轮小修同步；其中 F1/F2 优先（红线表是后续审查的入口）

## 5. 优先级

- **P0**：无
- **P1**：F1（i18n 红线指针）、F3（审查规范批次定义）、F5（死变量，顺带）
- **P2**：F2（普查分母）、F4、F6（拆超长方法）、F7（补 3 条单测）、F8（真机冷启动走查）、F9/F10（顺带清理）、F11（计划口径认账）

## 6. 建议的修复轮切分（每步独立提交 + 构建/单测）

| 步 | 内容 | 涉及文件 | 风险 |
| --- | --- | --- | --- |
| 1 | 同步 i18n 红线指针 + 复算 R9 口径 | `skill/avbox-i18n-spec.md`（§R 表、A 附录标注"历史快照"） | 无（纯文档） |
| 2 | 审查规范模块清单/批次一去掉 `cache`、说明 viewmodel 落点 | `skill/avbox-code-review-spec.md` | 无 |
| 3 | 常量归属括注 + 计划/历史数字订正（364→367、16/43→18/46） | `skill/avbox-mobile-ui-spec.md`、`skill/review/refactor-plan-20260928.md` | 无 |
| 4 | 清 `VodCardMenu` 死变量与未用 import；删 `sortJson/sortXml` 未用形参 | `ui/components/VodCardMenu.kt`、`viewmodel/SourceResultParser.java`(+3 调用点) | 低（编译期可验） |
| 5 | `getSort` / `getPlayPrepared` 按 type 分支拆私有方法 | `viewmodel/SortLoader.java`、`viewmodel/PlayLoader.java` | 中（取数链路，需方法级等价脚本 + 真机走查） |
| 6 | 补 3 条取数链路单测（getFixUrl 超时回退 / 三通道分投 / 双序号丢弃） | `app/src/test/java/com/github/tvbox/osc/viewmodel/`（新增） | 低 |

## 附录 A. 度量盘点（口径与命令）

| 指标 | 命中数 | 明细（Top，含路径:行号） | 统计口径 |
| --- | --- | --- | --- |
| 文件 > 500 行 | **33**（基线 36） | `player/PlaybackController.java` 2025、`player/controller/ComposeVideoController.kt` 1427、`ui/player/PlayContainer.java` 1424、`api/ApiConfig.java` 1090、`ui/activity/LivePlayActivity.kt` 1074 | 按行数统计 `app/src/main/**/*.{java,kt}`（318 文件） |
| 方法 > 100 行 | **36 处（脚本估算 ±10 行）** | `ui/page/MainScreen.kt:133 MainContent` 307、`subtitle/format/FormatSCC.java:620 codeChar` 292、`api/ConfigParser.java:54 isLiveJsonContent` 270、**`viewmodel/SortLoader.java:112 getSort` 237**、`viewmodel/PlayLoader.java:81 getPlayPrepared` 136 | 脚本按签名行 + 花括号配对统计（含 Kotlin/Compose 内联块） ⇒ **与计划附录 A 的 17 处不可直接比**（提取口径不同，本表偏多） |
| 同文件重复模板 ≥3 次 | **0 处成组模板** | `BoundedCall.call(` 6 处（= 1 个模板 + 6 个调用点，阶段 3 收敛结果）；`Executors.newSingleThreadExecutor()` 13 处（均为长生命周期池或投递即走，非"新建-等待-销毁"模板） | `Select-String -Pattern '<模式>' \| Group-Object Path`（此处以出现次数计） |
| 门面被依赖 ≥20 文件 | **3 个**（均为设计预期，本次未增减） | `ApiConfig.get()` 31 文件、KV 门面 50 文件、`.getInstance()` 类 116 处/50 文件 | 同上门面依赖计数 |

- 相较计划基线：文件 >500 行 36 → 33（`SourceViewModel` 1873→168、`ApiConfig` 1794→1090 各消 1，另有阶段 1/2 的效果）；**方法 >100 行未下降**（F6：超长方法被原样搬迁，只是换了宿主文件）

## 附录 B. 对账：计划子步的完成度（替代上轮报告，因 `skill/review/` 无上轮落盘）

| 计划项 | 状态 | 本轮复查证据 |
| --- | --- | --- |
| 阶段 5：门面 ~250 + 6 sibling | 已达成（口径更正：5 Loader + `PushUrlParser` + 3 支撑类；门面 168 行） | 10 文件行数 117–349；门面 public 行集与改造前逐条一致 |
| 阶段 5：结果通道与 seq 归属保持 | 已达成 | `PlayLoader` 双通道双序号，`playRequestSeq+playResult` / `preloadRequestSeq+preloadResult` 配对未串 |
| 阶段 5：不得扩大 `sortCache` 竞争面 + 顺带加锁 | 已达成 | 三处访问全在 `synchronized (sortCache)` 内 |
| 阶段 6.1–6.2、6.4–6.6 | 已达成（消 8 组环，无新增） | 环集合差逐笔核对 |
| 阶段 6.3 util 拆包 | 已驳回（实测：环 23→23、需改 361 行 import） | 模拟器结果留档在计划 §4 |
| 阶段 6.5 `base ⇄ server`/`base ⇄ util` | **未达成**（见 F11） | 残留 = `App.vodInfo`/`getDashData` 隐式通道 |
| 阶段 6 目标"环 ≤6" | **未达成**（17 组） | 剩余构成逐条可解释：util 枢纽 9 组（已证包移动无解）+ 父子里程 4 组 + `crawler ⇄ util` 1 + `base ⇄ server/util` 2 + `api ⇄ server` 1 |
| 真机走查（阶段 3/5 判据） | **未做** | 无设备执行记录；F8 亦需此项 |

---

# 修复轮 1（2026-09-28）

| 条目 | 状态 | 改法 | commit |
| --- | --- | --- | --- |
| F6 | ✅ 已修（含同缺陷类补齐） | 5 个 type 分流方法切成私有方法：`SortLoader.getSort` 237→54（+46/51/96）、`PlayLoader.getPlayPrepared` 136→28（+44/24/49）、`ListLoader.getList` 132→28（+22/41/58）、`DetailLoader.getDetail` 113→53（+26/46）、`SearchLoader.getSearch` 106→17（+16/37/53） | `01ab127`、`dbb1638` |
| F7 | ✅ 已修 | 新增 9 条 JVM 用例（3 个用例集）：extend 解析三条口径（空/非 http 原样、MD5 键缓存命中、取不到值回退**原 extend**）、三通道分投（搜索走 EventBus / 详情过 push·迅雷后处理 / 其余 postValue）、双序号语义（旧请求作废、预载不被真实播放作废、两个序号字段存在）＋ 为可测性把"过期结果"判定提成纯谓词 `PlayLoader.isStaleResult` | `d81fe1c` |
| F1 | ✅ 已修 | i18n 红线表指针改指新落点（R2→`SortLoader.java:135`、R8→`DetailLoader.java:174`、R9→`SourceHelper.java:44` + 5 个 Loader 的 9 处引用 + 变体/字面量清单）；第 287 行同族指针 `SourceViewModel "豆瓣"` → `SourceHelper "豆瓣"` | `5d74d84` |
| F3 | ✅ 已改（**未提交**） | 审查规范：模块清单去掉 `cache`（注明并入 `data`）、批次一去掉 `osc/cache`、文件数按实测更新（批次一 134 / 批次二 123 / 批次三 60 = 317） | 未提交，见下 |

**F3 为何未提交**：`skill/avbox-code-review-spec.md` 在本轮开始前就带着**自己的未提交修订**（会话初 `git status` 即为 ` M`）。我的 4 处修改已写入工作区，但不替提交其半成品，故该文件保持未提交状态，由用户合并后自行提交。

**构建与单测**：`assembleDebug` + `testDebugUnitTest` → **48 类 / 376 用例 / 0 失败**（修前 45 类 / 367 用例）。附录 A 的「方法 >100 行」由 **36 → 31**（`viewmodel` 包内仅剩 `PushDetailResolver.checkPush` 129 行）。

## 回归面清单（不变量 × 消费方 × 结论）

| 改动 | 不变量 | 消费方 | 结论 |
| --- | --- | --- | --- |
| F6（5 个方法按 type 分流） | 四个 type 分支的语句集合与调度顺序不变 | 门面 `getSort/getList/getDetail/getSearch` → `HomeViewModel`/`PartitionListViewModel`/`DetailViewModel`/`SearchViewModel` | 归一化行集比对**逐方法 0 丢失**（新增行全是签名/调用/括号 + 为匿名回调补的局部 `type`）；编译 + 376 用例全绿；5 个方法均降到 ≤96 行 |
| F6 的局部 `type` 补丁 | 回调里按 type 分流 xml/json 的**值语义**与改造前一致（原为捕获上层局部量） | `getSortFromApi`/`getListFromApi`/`getDetailFromApi`/`searchFromApi` 的 `onSuccess` | 值取自同一个 `getType()` 调用点；`SortLoader` 那处由编译失败暴露后修正（文本比对抓不到作用域） |
| F7（三通道分投） | 搜索通道走 EventBus（面板自行收）、详情通道过 push/迅雷后处理、其余通道直接 postValue | `SearchScreen`/`SearchViewModel`、`DetailViewModel`、`HomeViewModel`/`PartitionListViewModel` 的列表通道 | 3 条用例全绿；已核对可失败性——把搜索改走 `postValue`、把详情改成不过后处理，用例即红 |
| F7（序号独立性） | 同一通道内后发请求顶掉先发；播放与预载**各自持有序号**，互不作废 | `PlaybackController`（真实播放）、`PreloadCoordinator`（下一集预解析） | 3 条用例全绿；除谓词语义外用**字段结构断言**锁死"两个独立序号"（合并成一个计数器即红） |
| F7（extend 解析三条口径） | 空/非 http 原样返回；缓存键 = 原 extend 的 MD5；取不到值**回退原值而非空串** | 6 处 `getFixUrl` 调用点（sort/list/detail/search/play 的 extend 分支） | 3 条用例全绿（不发网络请求）；已核对可失败性——回退改成空串即红 |
| F1/F3（文档） | —— | 后续审查与多语言改造的入口 | 纯文档，不影响编译与运行；回归面为空 |

## 本轮踩坑（供后续结构搬迁复用）

1. **脚本切片生成的 dispatch 连续两次漏掉方法结尾的 `}`**（第一次 `F6`，第二次三个补齐方法同批）：两次都靠"编译 + 归一化行集比对"抓回 —— 行集比对的"新增行数暴增"就是括号不配平的信号。
2. **把分支体搬进新方法会丢掉匿名类对外层局部量的捕获**（`type`）：等价性脚本按文本比对，**抓不到作用域问题**，只有编译能发现。⇒ 结构性搬迁的卡口必须是「编译 + 行集比对 + 用例」三件套。
3. **把内容从原文件搬进新文件后，`owner.` 前缀不能凭记忆手写**：`SearchLoader` 的 dispatch 里我漏写了 `resultParser.` 前缀，靠编译暴露。
4. EventBus 单测订阅者**不能是 private 类**（反射拿不到访问权，异常会被解析器的 catch 吞掉后再重投，表现为"搜索用例莫名失败"）。

## 仍未处理（本轮未请求）

F2（i18n 普查分母失真）、F4（记录数字 364/367、16 文件 43 处 → 18/46）、F5（`VodCardMenu.kt:46` 死变量 + 未用 import）、F8（真机冷启动走查）、F9（`spThreadPool` 公开别名）、F10（`sortJson/sortXml` 未用形参）、F11（`base ⇄ server/util` 的 `App.vodInfo` 通道）、以及 `PushDetailResolver.checkPush`（129 行：形态是"嵌套扫描 + 内嵌 executor"而非 type 分流，拆分要重构推送解析块，建议与推送链路走查同批做）。
