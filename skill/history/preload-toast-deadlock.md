# 预载完成死锁排查：拖动进度条后「下一集已就绪」toast 概率不出现

状态：已按方案 A（磁盘预缓存）实施，待真机验证（2026-09-27）；根因已确证（media3 1.11.1 源码取证）。

## 现象

后：正常播放时预载完成 toast 会弹；**拖动进度条后，预载成功的 toast 有概率不再出现**，且无任何错误提示。

## 结论（TL;DR）

拖动进度条触发的"清空 → 冷却 → 重新预载"链路本身没有断点（`STATE_BUFFERED` 补枪 → 10s 冷却重试 → `evaluate` 守卫链均正常，见 `PlaybackController.onPlayerStateForPreload` 注释）。**断点在 media3 内部：预载目标"时长"与内存闸门"字节"互不收敛——当目标时长对应的数据量超过 32MB 字节上限时，预载线程陷入无回调的静默死循环，`onCompleted` 永不触发**。toast 只是这个死锁最外层的表象；同样不来的还有 UI 依赖的"预载就绪"信号本身。

## 证据链

### 1. app 层链路已排除

- 拖动 → `STATE_BUFFERING` → `onMainPlayerBuffering()`（清数据 + 冷却）→ 缓冲结束 `STATE_BUFFERED` → `scheduleEvaluate` → 冷却后重新 `getPlayForPreload` → `PreloadManagerHolder.preload()` 重新发起。各守卫（`preloadedKey` 已清、`requestToken` 在途跳过、`gaveUpKey` 语义不变）均验证无断点。
- toast 侧 `PlayContainer.showPreloadReady()` 无去重/节流，每次调用必弹。
- 故"toast 不出现" = `PreloadManagerListener.onCompleted` 未触发。

### 2. media3 1.11.1 源码（Gradle cache 的 sources jar 解包取证）

**完成判定只认时长** — `DefaultPreloadManager$PreloadMediaSourceControl.onContinueLoadingRequested`：
`onCompleted` 仅当 `bufferedDurationUs >= targetPreloadStatus.durationMs`（本项目 = 预载时长设置，默认 60s、上限 120s）。另一条无条件完成路径是 `bufferedPositionUs == TIME_END_OF_SOURCE`（加载到片尾）。

**放行与否只看字节** — `DefaultLoadControl.shouldContinueLoading`：
```java
boolean targetBufferSizeReached =
    getTotalBufferBytesAllocated(playerId) >= getTargetBufferBytes(playerId);
if (playerId.equals(PlayerId.PRELOAD)) {
    return !targetBufferSizeReached;   // 只看 32MB；时长/水位/播放状态一概不看
}
```
（非 PRELOAD 分支的水位/`prioritizeTimeOverSizeThresholds` 逻辑完全不适用于预载。）

**死锁形态** — `PreloadMediaSource$PreloadMediaPeriodCallback.maybeContinueLoading`：
字节满 → `shouldContinueLoading` 返回 false → 100ms 重试 ×10 → `onLoadingUnableToContinue` → 本项目恒只预载 1 项，`BasePreloadManager.getMediaSourceHolderToClear()` 因 `indexToPreload(0) >= indexToClear(0)` 返回 **null** → 方法返回 false，**无任何恢复手段** → 无限 100ms 空转。既不 `onCompleted` 也不 `onError`，无日志、无通知。

## 撞顶阈值量化

| 预载时长设置 | 撞顶码率 |
| --- | --- |
| 60s（默认） | > 4.27 Mbps |
| 120s（上限） | > 2.13 Mbps |

1080p 源普遍 3–8 Mbps，相当比例的源必然撞顶。已载入的 32MB 数据仍留在内存 SampleQueue，切集注入仍然可用——**功能"半有效"，唯独完成信号永远不来**。

## 为什么表现为"拖动进度条后、概率不出现"

- 低码率源（60s 数据 < 32MB）：两轮都正常，toast 出现（弱网下只是迟到，可能被当作没出现）。
- 高码率源（60s 数据 > 32MB）：第一轮就静默卡死，没有参照；**拖动进度条是预期的动作**，第二轮又不弹，于是归因到"拖动后"。
- "概率"来自用户混用不同码率的源；次要因子：下一集带历史进度（`startPosMs > 0`）不改变字节上限、弱网下 toast 迟到。

## 修复选项

| 方案 | 做法 | 优点 | 缺点 |
| --- | --- | --- | --- |
| **A（治本，已采纳 2026-09-27）** | `TargetPreloadStatusControl` 改返回 `specifiedRangeCached(start, range)`，走 1.11 新增 `PreCacheHelper` 磁盘预缓存：数据流式写 SimpleCache，不占 SampleQueue、不受 32MB 闸门约束，完成后 `onPreCacheCompleted → onCompleted`。命中复用已实现的 `isPreloadTargetUrl` 读盘链路 | 内存占用趋近 0；与二期磁盘缓存方向一致 | 需给 manager `setCache(SimpleCache)` + **带站点 headers 的 DataSource.Factory**：PreCacheHelper 走 builder 的工厂，不吃 `ExoMediaSourceHelper` 把 headers 放 requestMetadata.extras 的约定，不桥接会 403；同时失去 SampleQueue 内存无缝接管，起播改为读盘 |
| **B（治标）** | `TARGET_BUFFER_BYTES` 32MB → 64MB | 一行改动 | 只覆盖 60s@8Mbps；120s + 高码率仍撞；内存压力翻倍（对 2GB 以下盒子是当初降 32MB 的原因） |
| **C（自适应）** | 按实测码率把 `sRangeMs` 折算到 32MB 以内 | 参数不变、自动适配 | 码率采样时机难以干净，复杂度最高 |

## 实施方案（A，2026-09-27）

改动落在 `ExoMediaSourceHelper`（player 模块）、`PreloadManagerHolder`、`ExoPlayer`（app 模块）：

- **状态与接入**：`TargetPreloadStatusControl` → `specifiedRangeCached(sStartPosMs, sRangeMs)`；manager builder 增加 `setCache(共享 SimpleCache)`（cached 状态不注入 Cache，开始预缓存时会直接抛）与 `setDataSourceFactory(PreloadDataSourceFactory)`；删掉 32MB `DefaultLoadControl`（cached 状态不灌 SampleQueue，内存水位已无意义）。
- **桥接 1（headers，方案已预告）**：media3 只认 builder 级（全局）DataSource.Factory，站点 headers 是 per-item 的。做法：`preload()` 时快照 `sPreloadHeaders`，`PreloadDataSourceFactory.createDataSource()` 在建源时用快照拼 OkHttp 源（恒只预载 1 项 + 下载源在任务创建时才构建，快照即当前项）。
- **桥接 2（cache key，实施期新发现）**：预缓存写盘由 media3 内部 `CacheWriter` 落 key（取 `CacheDataSource.getCacheKeyFactory()`，PreCacheHelper 建工厂时不给注入点），key 恒为**默认 key（分片 uri）**；而本项目播放侧自 2026-09-13 起用「uri + headers 后缀」防跨线路串缓存 ⇒ 不处理会永远 miss。做法：新增 `getPreloadTargetMediaSource()`，**预载目标读盘回落默认 key**；为不误读他线路数据，`sPreloadTargets` 由「url 集合」升级为「url → 预载时 headers 签名」，签名一致（确证同一份数据）才走默认 key，否则退回后缀 key 链路（宁可 miss）。
- **桥接 3（mimeType，实施期新发现）**：预缓存走 `DownloadHelper`，其内部用 `DefaultMediaSourceFactory`，类型只按 `uri/mimeType` 推断，**读不到 `TVBox-Format` 头部约定** ⇒ 无 `.m3u8` 特征的 HLS 源会被当进度流下载（只下到清单文本）。做法：预载用 `ExoMediaSourceHelper.buildPreloadMediaItem()`，按既有推断显式带 mimeType。
- **移除内存接管**：`tryAcquire` / `confirmTaken` / `sPendingItem` / `ExoPlayer.prepareAsync()` 覆盖全删 —— cached 状态不产出可交接的内存源，留着反而可能用 DownloadHelper 重写过的（已丢 extras 的）updated 源覆盖正确的读盘源。
- **生命周期语义（media3 约束，需知悉）**：`remove/reset/release` 都会 `preCacheHelper.release(removeCachedContent=true)` **删掉已缓存数据**。故预缓存完成不主动移除条目（磁盘数据要留给播放读盘）；清数据只发生在既有失效点（拖动/缓冲 `clearAll`、退出页 `release()`），届时磁盘数据一并清掉 —— 要跨页面保留得另做。
- **拖动/缓冲的让路策略（2026-09-27 调优）**：`onMainPlayerBuffering` 不再立即 `clearAll`，改为延迟 4s 的 `bufferingYield`（正片恢复播放即撤销）——短暂缓冲不打断在途下载、已下字节保留；持续缓冲 >4s 才让路。`BUFFERING_COOLDOWN_MS` 10s→5s。目标未变且已完成时，拖动后由 `PreloadManagerHolder.replayReadyIfCompleted()` 重放 toast（数据未失效，不重下）。改这条链路前先读 `history/features.md` 的 2026-09-27「拖动进度条后要等十几二十秒」条目。

## 旁证发现：共享 SimpleCache 此前没有任何写入方

全仓检索 `CacheDataSink` / `setCacheWriteDataSinkFactory` / `CacheWriter` 均为 0 命中：播放侧 `getCacheDataSourceFactory` 建的 `CacheDataSource.Factory` 未设写 sink（media3 默认即只读）。即「边播边缓存」设置与旧版"预载落盘 → 读盘兜底"此前都是空转；本次 `PreCacheHelper` 是该缓存的第一个写入方。属独立缺陷，本次未处理。

## 真机验证判据

- 根因判据（改动前）：撞顶源 logcat `echo-preload-start` 之后**永远等不到** `echo-preload-complete`，也无 `echo-preload-error`；反复拖动皆如此。
- 修复后：
  1. 高码率源（60s 预载下 > 8 Mbps）拖动进度条后 `echo-preload-complete` 反复到达、toast 必现；
  2. 切到该集时出现 `echo-preload-disk-source`（走读盘），起播无网络冷启动；
  3. 全程无 `echo-preload-error`；HLS 源（含无 `.m3u8` 特征、靠 `TVBox-Format` 头部约定的）需单独覆盖；
  4. 无 headers 的站点同样应命中（签名为空串，两端一致）。

## 关联结论

- 与既有结论不冲突：`PreloadManagerHolder.preloadLooper` 常驻不退出（looper 硬校验）、拖动后恢复链路（`PlaybackController` "不补枪则拖一次进度条就永久停摆"补丁）均正常。
- 审查教训：预载链路的正确性要验证到 `onCompleted` 为止——"任务会重新发起"不等于"任务会完成"；A 之后判据再前移一档：**数据是否真的落盘、播放是否真的读盘命中**。
