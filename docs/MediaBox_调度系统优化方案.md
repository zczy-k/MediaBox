# MediaBox 播放调度系统优化方案

> 对照物：《源与线路健康度及智能调度系统 —— 架构规范文档》（`docs/TVBox_源与线路健康度及智能调度系统.md`）
> 分析基线：HEAD 实际代码（`app/src/main/java/com/github/tvbox/osc/...`）+ 项目既有设计文档（`.codeartsdoer/specs/mediabox_spec/`）
> 日期：2026-10-13

---

## 一、结论先行

MediaBox 在"源健康 / 线路健康 / 画质调度 / 卡顿干预"上已实现文档约 70~80% 的理念，且部分更细（大量真机取证、纯函数可单测、匿名化铁律）。但存在 3 处结构性缺口，恰好命中最初的痛点（搜索慢、起播慢、**升清不能跨源**）：

| 缺口 | 文档对应章节 | 项目真实证据 |
|---|---|---|
| ① 无"统一线路池"，跨源只能**提示**不能**自动择优** | 统一线路池 / 跨源无缝切换 | `LineQualitySelector.pickUpgrade` 只吃单源记忆；`CrossSourceHintPolicy` 仅返回提示布尔 |
| ② 无**统一动态质量评分**（速度/TTFB/成功率 + 场景权重） | 动态质量评分模型 | `VideoQualityPolicy.score` 只有 清晰度S×bpp×可信度；`LineHealthPolicy.orderScore` 只有 −100/−50 粗粒度 |
| ③ **四层恢复**只落地一层（自然过期） | 误拉黑防护与恢复 | 源级只有到点自动解封；无网络平反、无主动探活、无观察期 |

---

## 二、现状盘点（对照文档 5 大模块）

| 文档模块 | 项目现状 | 落点（file:line） | 判定 |
|---|---|---|---|
| 1 数据模型（源级+线路级健康度） | 源级/线路级/画质**三套独立**，无"线路池"实体 | `SourceHealthState`、`LineHealthPolicy.State`、`VideoQualityPolicy.Variant` | 缺统一池 |
| 2 失效归因与分级拉黑 | 源级有分级封禁 + 连续超时快判 + 归因分类 | `SourceHealthPolicy.shouldBan:179`、`recordFail:234` | 基本达成，弱网闸待补 |
| 3 误拉黑防护与四层恢复 | 仅"自然过期"+雪崩回滚 | `SourceHealthPolicy.normalize:193`；无 L1/L2/L3 | 缺 3 层 |
| 4 动态质量评分 | 画质为王的排序，无速度/成功率维度 | `VideoQualityPolicy.score:147`、`LineQualitySelector.pickUpgrade:192` | 缺综合评分 |
| 5 播放态实时干预 | 位置推进量卡顿检测 + 降档→换线→换源 | `PlaybackQualityWatchdog:142`、`PlaybackRetryDelegate.handlePlaybackTooSlow:725` | 缺跨源无缝 + ABR |

**已有亮点（优化时不要推翻）**：源级雪崩保险丝（`SourceHealthMemory.kt:66`）、`PLAY_FAILED` 已退出源级判据、线路"只降权不屏蔽"（`LineHealthPolicy.kt:123`）、画质切换滞回与额度（`QualityGovernor`）、探测预算四道闸（`MiniProbeBudget`）。

---

## 三、优化点清单

### P0-1　建立"跨源统一线路池"，实现跨源自动升清（核心缺口）

- **现状问题**：升档只在**源内**发生——`PlaybackRetryDelegate.handleNetworkPlentiful` 用 `VideoQualityMemory.lookupAll(vod.sourceKey, vod.id, lineFlags)`（`PlaybackRetryDelegate.java:610`），候选被 `sourceKey` 锁死。跨源能力仅剩 `CrossSourceHintPolicy.shouldHint`（`CrossSourceHintPolicy.kt:29`）→ 弹一句提示 → 用户手动点 `DetailViewModel.switchSource`。源A线路只有 480P、源B有 1080P 时，系统看不到源B。
- **优化理由**：文档核心理念是把"源"从**播放边界**降级为**线路属性**（影片 → 聚合所有源线路 → 统一池 → 选最优）。当前"源"仍是边界，源内升清到顶即停、跨源只能靠昂贵的整源切换。
- **落地方案**：
  1. 新增 `LinePoolBuilder`（纯逻辑 + 注入式 IO）：以 `(sourceKey, vodId, episodeIndex)` 聚合候选线路，产出 `UnifiedLine(sourceKey, flag, episodeIndex, url, quality, health)`；不阻塞起播，边播边异步填充（复用 `MiniProbeGate/Budget`）。
  2. 起播后升档决策改为读**池内同集跨源候选**：`pickUpgrade` 增加 `pool` 入参，源内无更高档时向上跨源择优——**先落地跨源升清**，复用 `QualityGovernor` 全部门控（θ/ΔS/额度/回滚窗口）。
  3. 跨源切换走**轻量换线**而非整源切换：复用 `switchLineTo` 的进度继承与集名匹配（`PlaybackRetryDelegate.java:463`）。
  4. episodeIndex 对齐：按集名归一（`EpisodeMatcher.isSameEpisode`，参见 `PlaybackAttemptState.pendingInheritAppliesTo:114`）。
- **风险/约束**：跨源解析需跑爬虫（同类 Spider 共享静态状态、不能并发）→ 只对**直连型/已热**候选跨源；`MiniProbeGate` 已排除 type3/索引型，直接复用。
- **依赖**：P0-2（评分）、P1-1（安全网）。

### P0-2　统一动态质量评分模型（含速度/TTFB/成功率 + 场景权重）

- **现状问题**：`VideoQualityPolicy.score`（`:147`）只答"谁更清晰"；"快不快/稳不稳"散落在 `LineHealthPolicy.orderScore`（`:115`，仅三档）。没有一处把 清晰度/速度/TTFB/成功率 按场景加权。
- **优化理由**：文档主张"起播阶段速度优先、稳态清晰度优先、弱网速度优先"，权重随 网络×设备 变化。项目 `DeviceCapability.QualityMode`（`:96`）只区分起播前探测深度，判据阈值写死 θ=1.08/1.03（`QualityGovernor.kt:41`）。
- **落地方案**：
  1. 新增纯函数 `QualityScore.compute(variant, health, scene, caps)`：融合 实测清晰度 S、bpp、取流耗时 `resolveMs`、历史成功率、设备上限。
  2. 引入场景枚举（`COLD_START / STEADY / WEAK_NETWORK / TV_BIG_SCREEN`）+ 权重表（文档 §1.1 推荐值：WiFi+TV 清晰 0.65、4G+手机 速度 0.70、弱网 速度 0.90）。
  3. 场景判定：`NetworkType`（`ConnectivityManager`）+ `DeviceCapability.isTelevision`。
  4. 接入保持"**先分档、档内不变**"：`LineQualitySelector` 三个纯函数只在外部加一层场景权重，不动 `pickDowngrade` 三条硬约束与 `pickUpgrade` 一次到位规则。

### P0-3　补齐"四层恢复体系"（网络平反 / 主动探活 / 观察期）

- **现状问题**：文档要求四层恢复，项目只有 L4（自然过期）：`SourceHealthPolicy.normalize`（`:193`）只在到点解封，网络恢复不主动平反；线路级 `SUSPECT_TTL_MS=15min` 纯 TTL；没有轻量探活；无观察期防抖。
- **落地方案**：
  1. **L1 网络恢复平反**：注册 `NetworkCallback`，`onAvailable` 时把"网络类归因"的封禁/可疑立即解封。
  2. **L2 定时渐进式探活**：新增 `SourceProbeWorker`（2min 起、指数退避至 30min），探活成功即解封并进入观察期。
  3. **观察期防抖**：解封后写 `observingUntil`，观察期失败计数×2 权重、达 3 次即回黑。
  4. **L3 手动重置**：复用 `unblockAll`（`SourceHealthMemory.kt:370`）。

### P1-1　把"降权"从"队尾"升级为"连续权重 + 保底配额"（落地已有设计稿）

- **现状问题**：项目《源与线路的权重模型》已指出——"排到队尾"= 没有安全网的"事实封禁"：首轮只搜 24 个快源（`SearchBatchPolicy.FAST_ROUND_SOURCE_LIMIT:29`），降权源排到 328 源队尾后用户不再滚动，队尾永不被搜到。该设计稿状态为"未实现"。
- **落地方案**（照设计稿 §9 分期）：
  1. 新建 `SourceWeightMemory`：每条只存 `{score, at}`，`effectiveScore = 60 + (score−60)·0.5^((now−at)/30min)`。
  2. **保底配额**：每轮强制搜 N=3 个最低权重源且轮换（按上次被搜时间升序）。
  3. 接入 `SearchBatchPolicy.splitFastRoundSources` / `DetailViewModel.sortFallbackCandidatesByCompleteness`。

### P1-2　补强"失效归因分级"与弱网闸

把归因从"布尔 noNetwork"升级为 `FailReason` 四分类（网络/源/内容/本地），仅在 `SOURCE_RELATED` 时计入 streak/封禁；网络类与本地类只记证据、永不累计。

### P1-3　播放态干预：卡顿优先走"码率自适应"，再换线/换源

卡顿当前唯一动作是"换线路降档"（`PlaybackRetryDelegate.tryDowngradeLine:437`）。可在同一 HLS master 多想码率变体内做 ABR（`VideoQualityPolicy.parseHlsMaster:193` 是现成基础），避免为降一档整条线路重载。

### P2-1　探测/记忆层收敛（项目自识别，尚未动）

`health-pipeline-fixes` §4 列出的结构冗余：6 条画质探测路径写同一份 `VideoQualityMemory`、3 张内存负冷却表 + 2 张落盘 TTL、4 套可用性记忆职责交叉。以"改动驱动"渐进收敛。

### P2-2　搜索/起播可观测与场景阈值

① 空态暴露统计（"已搜 312 源：命中 0 / 无此片 4 / 超时 300"）；② 本批超时过半则下一批单源超时 8s→4~5s。

---

## 四、实施路线图

| 阶段 | 内容 | 优先级 | 依赖 |
|---|---|---|---|
| 第一期 | P0-2 统一评分 + 场景权重（纯函数，零回归面） | 高 | 无 |
| 第一期 | P1-1 保底配额 + 连续权重（项目设计稿已就绪） | 高 | 无 |
| 第二期 | P0-1 跨源线路池 → 先做"跨源升清" | 高 | P0-2、P1-1 |
| 第二期 | P0-3 四层恢复（网络平反 + 主动探活） | 中高 | P1-2 归因分类 |
| 第三期 | P1-3 ABR 码率自适应；P2-1 探测器收敛 | 中 | P0-1 |
| 第三期 | P2-2 可观测 + 弱网超时自适应 | 低 | 无 |

**总原则**：所有判据层保持**纯函数 + JVM 单测**；每项接入都遵守项目已验证的四条铁律——**只降权不屏蔽 / 先分档档内不变 / 拿不准不降权 / 宁漏不误杀**。

---

## 五、验收口径

1. 跨源升清：日志能回答"为什么跨源切/没切"（复用 `QualityGovernor.rejectReason` 机器可读标识，`QualityGovernor.kt:102`）。
2. 评分：`echo-score cur=… pool=… scene=… rank=…`。
3. 恢复：`echo-recover layer=L1/L2/L3/L4 key=… reason=…`。
4. 保底配额：`echo-weight floor n=3 keys=…`。
5. 真机验证要点：30 分钟后权重自动回升（"不会死锁"的唯一证据）；弱网恢复后源可用数回升。