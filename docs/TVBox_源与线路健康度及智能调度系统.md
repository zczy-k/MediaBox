# 源与线路健康度及智能调度系统 —— 架构规范文档

> **文档性质**：指导性规范，供自动化 Agent 执行
> **约束等级**：所有阈值、分支、状态转换均为**强制定义**，Agent 不得自行推断或省略
> **术语约定**：本文中"源"= Source（聚合接口），"线路"= Line（某源下某影片某集的具体播放地址），"影片"= Vod

---

## 0. 全局约定

### 0.1 时间单位

| 符号 | 含义 | 单位 |
|------|------|------|
| `now()` | 当前 Unix 毫秒时间戳 | ms |
| `SEC` | 1000 | ms |
| `MIN` | 60 * SEC | ms |
| `HOUR` | 60 * MIN | ms |
| `DAY` | 24 * HOUR | ms |

### 0.2 枚举定义（全局唯一）

```kotlin
enum class FailReason {
    // 网络类
    TIMEOUT,            // 连接/读取超时
    CONNECT_ERROR,      // 无法建立连接
    DNS_ERROR,          // DNS 解析失败
    SSL_ERROR,          // TLS 握手/证书失败
    // 源服务类
    NOT_FOUND,          // HTTP 404
    SERVER_ERROR,       // HTTP 5xx
    AUTH_ERROR,         // HTTP 401/403
    // 内容类
    CONTENT_MISMATCH,   // 内容与预期不符
    // 本地类
    DECODE_ERROR,       // 解码失败
    PLAYER_ERROR,       // 播放器内部错误
    OOM,                // 内存不足
    // 未知
    UNKNOWN
}

enum class BlacklistCategory {
    NONE,               // 未拉黑
    NETWORK_RELATED,    // 网络原因，可被网络恢复平反
    SOURCE_RELATED,     // 源服务原因，需探活确认
    CONTENT_RELATED,    // 内容不符，需人工/特殊处理
    MANUAL              // 用户手动拉黑，不自动恢复
}

enum class PlayScene {
    COLD_START,         // 冷启动（首次起播）
    STEADY,             // 稳态播放
    WEAK_NETWORK,       // 弱网环境
    TV_BIG_SCREEN       // TV 大屏
}

enum class NetworkType {
    UNKNOWN, WIFI, CELLULAR, ETHERNET, OTHER
}

enum class HealthState {
    HEALTHY,            // 健康
    DEGRADED,           // 降权
    BLACKLISTED,        // 拉黑
    OBSERVING           // 观察期
}
```

### 0.3 全局阈值常量表（Agent 必须使用此表，不得修改）

```kotlin
object Thresholds {
    // ===== 网络判定 =====
    const val NETWORK_SIGNAL_WEAK_DBM = -100        // 蜂窝信号弱于 -100dBm 视为弱网
    const val NETWORK_OUTAGE_WINDOW = 30 * SEC      // 网络故障判定窗口
    const val NETWORK_OUTAGE_DISTINCT_SOURCES = 3   // 窗口内 ≥3 个不同源失败 → 网络故障
    const val NETWORK_OUTAGE_TOTAL_FAILS = 5        // 窗口内失败总数 ≥5 → 网络故障

    // ===== 源级拉黑 =====
    const val SOURCE_FAIL_BLACKLIST_5 = 5           // 连续失败 5 次
    const val SOURCE_FAIL_BLACKLIST_5_SPAN = 2 * MIN
    const val SOURCE_FAIL_BLACKLIST_10 = 10         // 连续失败 10 次
    const val SOURCE_FAIL_BLACKLIST_10_SPAN = 5 * MIN
    const val SOURCE_BLACKLIST_DURATION_5 = 30 * MIN
    const val SOURCE_BLACKLIST_DURATION_10 = 24 * HOUR
    const val SOURCE_BLACKLIST_DURATION_404 = 1 * HOUR
    const val SOURCE_BLACKLIST_DURATION_5XX = 30 * MIN
    const val SOURCE_BLACKLIST_DURATION_TIMEOUT = 10 * MIN
    const val SOURCE_BLACKLIST_DURATION_CONNECT = 10 * MIN

    // ===== 线路级拉黑 =====
    const val LINE_FAIL_BLACKLIST = 3               // 连续失败 3 次
    const val LINE_FAIL_BLACKLIST_SPAN = 1 * MIN
    const val LINE_BLACKLIST_DURATION = 1 * HOUR
    const val LINE_BLACKLIST_DURATION_404 = 6 * HOUR

    // ===== 探活 =====
    const val PROBE_TIMEOUT = 5 * SEC
    const val PROBE_POLL_INTERVAL = 2 * MIN         // 全局轮询间隔
    const val PROBE_INTERVAL_MIN = 2 * MIN
    const val PROBE_INTERVAL_MAX = 30 * MIN
    const val PROBE_FAIL_EXTEND_THRESHOLD = 3       // 连续探活失败 3 次延长拉黑

    // ===== 观察期 =====
    const val OBSERVING_DURATION = 10 * MIN
    const val OBSERVING_INIT_SCORE = 0.5
    const val OBSERVING_FAIL_MULTIPLIER = 2         // 观察期失败计数翻倍
    const val OBSERVING_REBLACKLIST_THRESHOLD = 3   // 观察期失败达 3 次重新拉黑

    // ===== 播放态 =====
    const val STALL_DETECT_MS = 2000                // 位置不变 2 秒判定卡顿
    const val STALL_FREQUENT_COUNT = 3              // 1 分钟内卡顿 3 次判定频繁
    const val STALL_FREQUENT_WINDOW = 60 * SEC
    const val SPEED_DEGRADE_PHONE = 500             // 手机端速度降权阈值 Kbps
    const val SPEED_DEGRADE_TV = 1000               // TV 端速度降权阈值 Kbps
    const val RES_DEGRADE_PHONE = 480               // 手机端清晰度降权阈值
    const val RES_DEGRADE_TV = 720                  // TV 端清晰度降权阈值

    // ===== 清理 =====
    const val ZOMBIE_SOURCE_DAYS = 30               // 30 天无成功 → 僵尸源
    const val LINE_HEALTH_RETENTION_DAYS = 90       // 线路健康记录保留 90 天
}
```

---

## 1. 数据模型定义

### 1.1 源级健康度 `SourceHealth`

```kotlin
@Entity(tableName = "source_health")
data class SourceHealth(
    @PrimaryKey
    val sourceKey: String,                          // 源唯一标识，默认 ""，主键

    // ===== 统计字段 =====
    var totalRequests: Int = 0,                     // 总请求次数，默认 0
    var successCount: Int = 0,                      // 成功次数，默认 0
    var failCount: Int = 0,                         // 失败次数，默认 0
    var consecutiveFails: Int = 0,                  // 连续失败次数，默认 0
    var firstFailTime: Long = 0,                    // 本轮连续失败首次时间，默认 0

    // ===== 性能字段（滑动平均，α=0.3）=====
    var avgSearchMs: Long = 0,                      // 搜索平均耗时，默认 0
    var avgDetailMs: Long = 0,                      // 详情平均耗时，默认 0

    // ===== 时间字段 =====
    var lastSuccessTime: Long = 0,                  // 最近成功时间，默认 0
    var lastFailTime: Long = 0,                     // 最近失败时间，默认 0
    var lastUpdateTime: Long = 0,                   // 最近更新时间，默认 0

    // ===== 拉黑字段 =====
    var blacklistUntil: Long = 0,                   // 拉黑截止时间，默认 0（未拉黑）
    var blacklistReason: String = "",               // 拉黑原因（FailReason.name），默认 ""
    var blacklistCategory: BlacklistCategory = BlacklistCategory.NONE, // 拉黑分类，默认 NONE
    var blacklistTime: Long = 0,                    // 拉黑发生时间，默认 0
    var blacklistDuration: Long = 0,                // 本次拉黑时长，默认 0

    // ===== 探活字段 =====
    var probeFailCount: Int = 0,                    // 连续探活失败次数，默认 0
    var lastProbeTime: Long = 0,                    // 上次探活时间，默认 0

    // ===== 观察期字段 =====
    var observingUntil: Long = 0,                   // 观察期截止时间，默认 0
    var observingFailCount: Int = 0,                // 观察期失败次数，默认 0

    // ===== 评分字段 =====
    var healthScore: Double = 0.5,                  // 健康分 [0,1]，默认 0.5

    // ===== 内容字段 =====
    var movieCount: Int = 0                         // 影片数量，默认 0
)
```

**字段用途说明**：

| 字段 | 用途 |
|------|------|
| `consecutiveFails` | 拉黑判定的核心计数，成功时清零 |
| `firstFailTime` | 与 `consecutiveFails` 配合，判定失败是否"跨时间" |
| `blacklistCategory` | 决定恢复策略（网络类可平反，源类需探活） |
| `observingUntil` | 观察期防抖，期内失败惩罚翻倍 |
| `healthScore` | 排序依据，恢复后置 0.5 |

### 1.2 线路级健康度 `LineHealth`

```kotlin
@Entity(tableName = "line_health")
data class LineHealth(
    @PrimaryKey
    val lineId: String,                             // 线路唯一标识 = sourceKey + "_" + vodId + "_" + episodeIndex，默认 ""

    // ===== 统计字段 =====
    var totalPlays: Int = 0,                        // 总播放次数，默认 0
    var successPlays: Int = 0,                      // 成功播放次数，默认 0
    var failPlays: Int = 0,                         // 失败播放次数，默认 0
    var consecutiveFails: Int = 0,                  // 连续失败次数，默认 0
    var firstFailTime: Long = 0,                    // 本轮连续失败首次时间，默认 0

    // ===== 性能字段（滑动平均，α=0.3）=====
    var avgTtfbMs: Long = 0,                        // 首帧平均延迟，默认 0
    var avgSpeedKbps: Long = 0,                     // 平均速度 Kbps，默认 0
    var resolution: Int = 0,                        // 清晰度（如 1080），默认 0

    // ===== 时间字段 =====
    var lastSuccessTime: Long = 0,                  // 最近成功时间，默认 0
    var lastFailTime: Long = 0,                     // 最近失败时间，默认 0

    // ===== 拉黑字段 =====
    var blacklistUntil: Long = 0,                   // 拉黑截止时间，默认 0
    var blacklistReason: String = "",               // 拉黑原因，默认 ""
    var blacklistCategory: BlacklistCategory = BlacklistCategory.NONE, // 拉黑分类，默认 NONE
    var blacklistTime: Long = 0,                    // 拉黑发生时间，默认 0
    var blacklistDuration: Long = 0,                // 本次拉黑时长，默认 0

    // ===== 探活字段 =====
    var probeFailCount: Int = 0,                    // 连续探活失败次数，默认 0
    var lastProbeTime: Long = 0,                    // 上次探活时间，默认 0

    // ===== 观察期字段 =====
    var observingUntil: Long = 0,                   // 观察期截止时间，默认 0
    var observingFailCount: Int = 0,                // 观察期失败次数，默认 0

    // ===== 失败原因统计 =====
    var timeoutCount: Int = 0,                      // 超时次数，默认 0
    var notFoundCount: Int = 0,                     // 404 次数，默认 0
    var decodeErrorCount: Int = 0,                  // 解码失败次数，默认 0
    var contentMismatchCount: Int = 0,              // 内容不符次数，默认 0

    // ===== 评分字段 =====
    var qualityScore: Double = 0.5                  // 质量分 [0,1]，默认 0.5
)
```

### 1.3 失败事件聚合器 `FailEvent`

```kotlin
data class FailEvent(
    val sourceKey: String,                          // 源标识
    val lineId: String?,                            // 线路标识（源级失败为 null）
    val reason: FailReason,                         // 失败原因
    val timestamp: Long                             // 发生时间
)
```

### 1.4 数据库操作接口（DAO 契约）

```kotlin
interface SourceHealthDao {
    fun get(sourceKey: String): SourceHealth?
    fun upsert(h: SourceHealth)
    fun getAll(): List<SourceHealth>
    fun getBlacklisted(): List<SourceHealth>        // blacklistUntil > now()
    fun getBlacklistedByCategory(c: BlacklistCategory): List<SourceHealth>
    fun getBlacklistedSince(time: Long): List<SourceHealth>
    fun resetExpiredBlacklist(now: Long)
    fun deleteOlderThan(time: Long)
}

interface LineHealthDao {
    fun get(lineId: String): LineHealth?
    fun upsert(h: LineHealth)
    fun getAll(): List<LineHealth>
    fun getBlacklisted(): List<LineHealth>
    fun getBlacklistedByCategory(c: BlacklistCategory): List<LineHealth>
    fun getBlacklistedSince(time: Long): List<LineHealth>
    fun resetExpiredBlacklist(now: Long)
    fun deleteOlderThan(time: Long)
}
```

---

## 2. 失效归因与分级拉黑策略

### 2.1 失败原因判定规则（精确映射）

```kotlin
object FailReasonMapper {
    /**
     * 从异常映射到 FailReason
     * 判定顺序：从上到下，命中即返回
     */
    fun map(e: Throwable): FailReason = when {
        e is SocketTimeoutException -> FailReason.TIMEOUT
        e is UnknownHostException -> FailReason.DNS_ERROR
        e is SSLException -> FailReason.SSL_ERROR
        e is ConnectException -> FailReason.CONNECT_ERROR
        e is HttpException -> when (e.code()) {
            404 -> FailReason.NOT_FOUND
            401, 403 -> FailReason.AUTH_ERROR
            in 500..599 -> FailReason.SERVER_ERROR
            else -> FailReason.UNKNOWN
        }
        e is MediaCodecException -> FailReason.DECODE_ERROR
        e is OutOfMemoryError -> FailReason.OOM
        e is PlaybackException -> FailReason.PLAYER_ERROR
        else -> FailReason.UNKNOWN
    }
}
```

### 2.2 归因分类规则（决定是否记录 + 恢复策略）

```kotlin
object FailAttribution {
    /**
     * 是否应归咎于源（决定是否记录失败）
     * 返回 true 才进入记录流程
     */
    fun shouldBlameSource(reason: FailReason): Boolean = when (reason) {
        FailReason.NOT_FOUND,
        FailReason.SERVER_ERROR,
        FailReason.AUTH_ERROR,
        FailReason.CONTENT_MISMATCH -> true
        // 网络类、本地类、未知类一律不归咎于源
        else -> false
    }

    /**
     * 拉黑分类（决定恢复策略）
     */
    fun categorize(reason: FailReason): BlacklistCategory = when (reason) {
        FailReason.TIMEOUT,
        FailReason.CONNECT_ERROR,
        FailReason.DNS_ERROR,
        FailReason.SSL_ERROR -> BlacklistCategory.NETWORK_RELATED

        FailReason.NOT_FOUND,
        FailReason.SERVER_ERROR,
        FailReason.AUTH_ERROR -> BlacklistCategory.SOURCE_RELATED

        FailReason.CONTENT_MISMATCH -> BlacklistCategory.CONTENT_RELATED

        else -> BlacklistCategory.SOURCE_RELATED
    }
}
```

### 2.3 网络可靠性判定（记录前置门）

```kotlin
object NetworkGate {
    /**
     * 网络是否"健康到可以归因给源"
     * 返回 false 时，任何失败都不记录
     */
    fun isReliable(cm: ConnectivityManager): Boolean {
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false

        // 条件1：有 INTERNET 能力
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        // 条件2：系统已验证可上网
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return false
        // 条件3：传输类型合法
        val hasTransport = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                           caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                           caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        if (!hasTransport) return false
        // 条件4：蜂窝信号不弱
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            val signal = caps.signalStrength  // API 29+
            if (signal != null && signal < Thresholds.NETWORK_SIGNAL_WEAK_DBM) return false
        }
        return true
    }
}
```

### 2.4 网络故障期判定（聚合器）

```kotlin
class FailureAggregator {
    private val events = mutableListOf<FailEvent>()

    fun record(e: FailEvent) {
        events.add(e)
        val cutoff = now() - Thresholds.NETWORK_OUTAGE_WINDOW
        events.removeAll { it.timestamp < cutoff }
    }

    /**
     * 是否处于网络故障期
     * 判定：窗口内不同源数 ≥3 或 失败总数 ≥5
     */
    fun isNetworkOutage(): Boolean {
        val cutoff = now() - Thresholds.NETWORK_OUTAGE_WINDOW
        val recent = events.filter { it.timestamp >= cutoff }
        val distinctSources = recent.map { it.sourceKey }.distinct().size
        return distinctSources >= Thresholds.NETWORK_OUTAGE_DISTINCT_SOURCES ||
               recent.size >= Thresholds.NETWORK_OUTAGE_TOTAL_FAILS
    }
}
```

### 2.5 源级失败记录流程（精确分支）

```kotlin
class SourceHealthManager(
    private val dao: SourceHealthDao,
    private val cm: ConnectivityManager,
    private val aggregator: FailureAggregator
) {
    /**
     * 记录源级失败
     * 执行顺序严格如下，任一分支 return 即终止
     */
    fun recordFail(sourceKey: String, reason: FailReason) {
        // 分支1：网络不可靠 → 不记录
        if (!NetworkGate.isReliable(cm)) return

        // 分支2：原因不归咎于源 → 仅进聚合器，不记录
        if (!FailAttribution.shouldBlameSource(reason)) {
            aggregator.record(FailEvent(sourceKey, null, reason, now()))
            return
        }

        // 分支3：处于网络故障期 → 不记录
        if (aggregator.isNetworkOutage()) return

        // 分支4：正式记录
        doRecordFail(sourceKey, reason)
    }

    private fun doRecordFail(sourceKey: String, reason: FailReason) {
        val h = dao.get(sourceKey) ?: SourceHealth(sourceKey)
        val t = now()

        h.totalRequests++
        h.failCount++
        h.consecutiveFails++
        h.lastFailTime = t
        if (h.firstFailTime == 0L) h.firstFailTime = t

        val span = t - h.firstFailTime

        // 拉黑判定（优先级从上到下）
        when {
            // 规则A：连续失败 ≥10 且跨度 ≥5min → 拉黑 24h
            h.consecutiveFails >= Thresholds.SOURCE_FAIL_BLACKLIST_10 &&
            span >= Thresholds.SOURCE_FAIL_BLACKLIST_10_SPAN -> {
                blacklist(h, reason, Thresholds.SOURCE_BLACKLIST_DURATION_10)
            }
            // 规则B：连续失败 ≥5 且跨度 ≥2min → 拉黑 30min
            h.consecutiveFails >= Thresholds.SOURCE_FAIL_BLACKLIST_5 &&
            span >= Thresholds.SOURCE_FAIL_BLACKLIST_5_SPAN -> {
                blacklist(h, reason, Thresholds.SOURCE_BLACKLIST_DURATION_5)
            }
            // 规则C：404 → 拉黑 1h
            reason == FailReason.NOT_FOUND -> {
                blacklist(h, reason, Thresholds.SOURCE_BLACKLIST_DURATION_404)
            }
            // 规则D：5xx → 拉黑 30min
            reason == FailReason.SERVER_ERROR -> {
                blacklist(h, reason, Thresholds.SOURCE_BLACKLIST_DURATION_5XX)
            }
            // 规则E：其他 → 仅降权，不拉黑
            else -> {
                h.healthScore = (h.healthScore * 0.8).coerceAtLeast(0.0)
            }
        }
        dao.upsert(h)
    }

    private fun blacklist(h: SourceHealth, reason: FailReason, duration: Long) {
        val t = now()
        h.blacklistUntil = t + duration
        h.blacklistReason = reason.name
        h.blacklistCategory = FailAttribution.categorize(reason)
        h.blacklistTime = t
        h.blacklistDuration = duration
        h.healthScore = 0.0
    }

    /**
     * 记录源级成功
     */
    fun recordSuccess(sourceKey: String, searchMs: Long) {
        val h = dao.get(sourceKey) ?: SourceHealth(sourceKey)
        h.totalRequests++
        h.successCount++
        h.consecutiveFails = 0
        h.firstFailTime = 0
        h.lastSuccessTime = now()
        h.avgSearchMs = movingAvg(h.avgSearchMs, searchMs, 0.3)
        // 观察期内成功，累计观察期成功（用于退出观察期）
        if (now() < h.observingUntil) {
            h.observingFailCount = 0
        }
        dao.upsert(h)
    }

    private fun movingAvg(old: Long, new: Long, alpha: Double): Long {
        if (old == 0L) return new
        return (old * (1 - alpha) + new * alpha).toLong()
    }
}
```

### 2.6 线路级失败记录流程

```kotlin
class LineHealthManager(
    private val dao: LineHealthDao,
    private val cm: ConnectivityManager,
    private val aggregator: FailureAggregator
) {
    fun recordPlayFail(lineId: String, sourceKey: String, reason: FailReason) {
        // 分支1：网络不可靠 → 不记录
        if (!NetworkGate.isReliable(cm)) return

        // 分支2：原因不归咎于源 → 仅进聚合器
        if (!FailAttribution.shouldBlameSource(reason)) {
            aggregator.record(FailEvent(sourceKey, lineId, reason, now()))
            return
        }

        // 分支3：网络故障期 → 不记录
        if (aggregator.isNetworkOutage()) return

        doRecordPlayFail(lineId, reason)
    }

    private fun doRecordPlayFail(lineId: String, reason: FailReason) {
        val h = dao.get(lineId) ?: LineHealth(lineId)
        val t = now()

        h.totalPlays++
        h.failPlays++
        h.consecutiveFails++
        h.lastFailTime = t
        if (h.firstFailTime == 0L) h.firstFailTime = t

        // 失败原因统计
        when (reason) {
            FailReason.TIMEOUT -> h.timeoutCount++
            FailReason.NOT_FOUND -> h.notFoundCount++
            FailReason.DECODE_ERROR -> h.decodeErrorCount++
            FailReason.CONTENT_MISMATCH -> h.contentMismatchCount++
            else -> {}
        }

        val span = t - h.firstFailTime

        when {
            // 规则A：连续失败 ≥3 且跨度 ≥1min → 拉黑 1h
            h.consecutiveFails >= Thresholds.LINE_FAIL_BLACKLIST &&
            span >= Thresholds.LINE_FAIL_BLACKLIST_SPAN -> {
                blacklist(h, reason, Thresholds.LINE_BLACKLIST_DURATION)
            }
            // 规则B：404 → 拉黑 6h
            reason == FailReason.NOT_FOUND -> {
                blacklist(h, reason, Thresholds.LINE_BLACKLIST_DURATION_404)
            }
            // 规则C：其他 → 降权
            else -> {
                h.qualityScore = (h.qualityScore * 0.8).coerceAtLeast(0.0)
            }
        }
        dao.upsert(h)
    }

    private fun blacklist(h: LineHealth, reason: FailReason, duration: Long) {
        val t = now()
        h.blacklistUntil = t + duration
        h.blacklistReason = reason.name
        h.blacklistCategory = FailAttribution.categorize(reason)
        h.blacklistTime = t
        h.blacklistDuration = duration
        h.qualityScore = 0.0
    }

    fun recordPlaySuccess(lineId: String, ttfb: Long, speedKbps: Long) {
        val h = dao.get(lineId) ?: LineHealth(lineId)
        h.totalPlays++
        h.successPlays++
        h.consecutiveFails = 0
        h.firstFailTime = 0
        h.lastSuccessTime = now()
        h.avgTtfbMs = movingAvg(h.avgTtfbMs, ttfb, 0.3)
        h.avgSpeedKbps = movingAvg(h.avgSpeedKbps, speedKbps, 0.3)
        if (now() < h.observingUntil) h.observingFailCount = 0
        dao.upsert(h)
    }

    private fun movingAvg(old: Long, new: Long, alpha: Double): Long {
        if (old == 0L) return new
        return (old * (1 - alpha) + new * alpha).toLong()
    }
}
```

### 2.7 拉黑时长参数总表（Agent 必须严格使用）

| 归因 | 对象 | 触发条件 | 拉黑时长 | 分类 |
|------|------|----------|----------|------|
| 连续失败 | 源 | ≥5 次且跨度 ≥2min | 30 min | 按原因 |
| 连续失败 | 源 | ≥10 次且跨度 ≥5min | 24 h | 按原因 |
| NOT_FOUND | 源 | 单次 | 1 h | SOURCE_RELATED |
| SERVER_ERROR | 源 | 单次 | 30 min | SOURCE_RELATED |
| TIMEOUT | 源 | 单次 | 10 min | NETWORK_RELATED |
| CONNECT_ERROR | 源 | 单次 | 10 min | NETWORK_RELATED |
| 连续失败 | 线路 | ≥3 次且跨度 ≥1min | 1 h | 按原因 |
| NOT_FOUND | 线路 | 单次 | 6 h | SOURCE_RELATED |

### 2.8 状态机定义

```
源/线路状态机：

    ┌──────────┐
    │ HEALTHY  │◀────────────────────────────┐
    └────┬─────┘                             │
         │ 单次失败（未达拉黑条件）              │ 观察期稳定
         ▼                                   │
    ┌──────────┐                             │
    │ DEGRADED │──── 达到拉黑条件 ────┐        │
    └────┬─────┘                    │        │
         │ 成功                      ▼        │
         │                     ┌──────────┐  │
         └────────────────────▶│BLACKLISTED│ │
                               └────┬─────┘  │
                                    │        │
                          恢复触发（L1/L2/L3/L4）
                                    │        │
                                    ▼        │
                               ┌──────────┐  │
                               │OBSERVING │──┘
                               └────┬─────┘
                                    │ 观察期失败 ≥3
                                    ▼
                               ┌──────────┐
                               │BLACKLISTED│（时长翻倍）
                               └──────────┘
```

**状态转换规则（精确）**：

| 当前状态 | 事件 | 目标状态 | 附加动作 |
|----------|------|----------|----------|
| HEALTHY | 单次失败未达拉黑 | DEGRADED | healthScore *= 0.8 |
| HEALTHY | 达到拉黑条件 | BLACKLISTED | 设置 blacklistUntil |
| DEGRADED | 成功 | HEALTHY | consecutiveFails=0 |
| DEGRADED | 达到拉黑条件 | BLACKLISTED | 设置 blacklistUntil |
| BLACKLISTED | 恢复触发 | OBSERVING | observingUntil=now+10min, score=0.5 |
| OBSERVING | 成功 | HEALTHY | observingFailCount=0 |
| OBSERVING | 失败 | OBSERVING | observingFailCount += 2 |
| OBSERVING | observingFailCount ≥3 | BLACKLISTED | 时长翻倍 |
| OBSERVING | 观察期到期且无失败 | HEALTHY | 正常 |

---

## 3. 误拉黑防护与恢复机制

### 3.1 四层恢复体系总览

| 层级 | 触发 | 恢复速度 | 覆盖范围 | 恢复对象 |
|------|------|----------|----------|----------|
| L1 | 网络状态变化 | 秒级 | 所有 NETWORK_RELATED | 源 + 线路 |
| L2 | 定时探活 | 分钟级 | 所有 BLACKLISTED | 源 + 线路 |
| L3 | 用户手动 | 即时 | 指定对象 | 源 + 线路 |
| L4 | 自然过期 | 兜底 | 所有 BLACKLISTED | 源 + 线路 |

### 3.2 L1：网络状态监听平反

```kotlin
class NetworkRecoveryHandler(
    private val sourceDao: SourceHealthDao,
    private val lineDao: LineHealthDao,
    private val scope: CoroutineScope
) {
    private var lastReliable = true
    private var lastType = NetworkType.UNKNOWN

    fun start(context: Context) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val reliable = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                val type = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
                    else -> NetworkType.OTHER
                }

                // 触发1：不可靠 → 可靠
                if (!lastReliable && reliable) {
                    scope.launch { onNetworkRecovered() }
                }
                // 触发2：网络类型切换（且可靠）
                if (lastType != NetworkType.UNKNOWN && lastType != type && reliable) {
                    scope.launch { onNetworkSwitched() }
                }
                lastReliable = reliable
                lastType = type
            }

            override fun onLost(network: Network) {
                lastReliable = false
            }
        })
    }

    /**
     * 网络恢复：批量平反所有 NETWORK_RELATED
     */
    private suspend fun onNetworkRecovered() {
        sourceDao.getBlacklistedByCategory(BlacklistCategory.NETWORK_RELATED)
            .forEach { recover(it, "网络恢复"); sourceDao.upsert(it) }
        lineDao.getBlacklistedByCategory(BlacklistCategory.NETWORK_RELATED)
            .forEach { recoverLine(it, "网络恢复"); lineDao.upsert(it) }
    }

    /**
     * 网络切换：平反最近 5 分钟内 NETWORK_RELATED
     */
    private suspend fun onNetworkSwitched() {
        val cutoff = now() - 5 * MIN
        sourceDao.getBlacklistedSince(cutoff)
            .filter { it.blacklistCategory == BlacklistCategory.NETWORK_RELATED }
            .forEach { recover(it, "网络切换"); sourceDao.upsert(it) }
        lineDao.getBlacklistedSince(cutoff)
            .filter { it.blacklistCategory == BlacklistCategory.NETWORK_RELATED }
            .forEach { recoverLine(it, "网络切换"); lineDao.upsert(it) }
    }

    private fun recover(h: SourceHealth, reason: String) {
        h.blacklistUntil = 0
        h.blacklistReason = ""
        h.blacklistCategory = BlacklistCategory.NONE
        h.consecutiveFails = 0
        h.firstFailTime = 0
        h.probeFailCount = 0
        h.observingUntil = now() + Thresholds.OBSERVING_DURATION
        h.observingFailCount = 0
        h.healthScore = Thresholds.OBSERVING_INIT_SCORE
    }

    private fun recoverLine(h: LineHealth, reason: String) {
        h.blacklistUntil = 0
        h.blacklistReason = ""
        h.blacklistCategory = BlacklistCategory.NONE
        h.consecutiveFails = 0
        h.firstFailTime = 0
        h.probeFailCount = 0
        h.observingUntil = now() + Thresholds.OBSERVING_DURATION
        h.observingFailCount = 0
        h.qualityScore = Thresholds.OBSERVING_INIT_SCORE
    }
}
```

### 3.3 L2：定时渐进式探活

#### 3.3.1 探活接口调用规范

```kotlin
interface Source {
    val key: String
    /**
     * 轻量探活接口
     * 约束：
     *   1. 必须是幂等 GET 请求
     *   2. 响应体 < 10KB
     *   3. 不依赖用户输入
     *   4. 超时由调用方控制（5s）
     * 返回：成功返回任意值，失败抛异常
     */
    suspend fun ping()
}
```

**探活接口选择优先级**（Agent 按序选择第一个可用的）：
1. 源提供的专用 `ping` / `health` 接口
2. 源首页 / 分类列表接口
3. 固定关键词搜索接口（如搜索"测试"）

**禁止**：使用详情接口、播放接口作为探活（过重）。

#### 3.3.2 渐进式探活间隔算法

```kotlin
object ProbeIntervalCalculator {
    /**
     * 计算探活间隔
     * 公式：interval = clamp(blacklistDuration / 4, MIN, MAX)
     * 其中 MIN = 2min, MAX = 30min
     */
    fun interval(blacklistDuration: Long): Long {
        return (blacklistDuration / 4)
            .coerceIn(Thresholds.PROBE_INTERVAL_MIN, Thresholds.PROBE_INTERVAL_MAX)
    }

    /**
     * 判断是否该探活
     */
    fun shouldProbe(h: SourceHealth, now: Long): Boolean {
        if (h.blacklistUntil <= now) return false          // 已过期，走 L4
        if (h.blacklistCategory == BlacklistCategory.MANUAL) return false  // 手动拉黑不探活
        val interval = interval(h.blacklistDuration)
        return now - h.lastProbeTime >= interval
    }
}
```

**渐进式探活时间表**（以拉黑 1h 为例）：

| 拉黑后时间 | 探活间隔 | 累计探活次数 |
|-----------|----------|-------------|
| 0 ~ 15 min | 2 min | ~7 |
| 15 ~ 30 min | 5 min | ~3 |
| 30 ~ 60 min | 15 min | ~2 |

#### 3.3.3 探活执行器

```kotlin
class BlacklistProber(
    private val sourceDao: SourceHealthDao,
    private val lineDao: LineHealthDao,
    private val sources: List<Source>,
    private val scope: CoroutineScope
) {
    fun start() {
        scope.launch {
            while (isActive) {
                try {
                    probeSources()
                    probeLines()
                } catch (e: Exception) {
                    Log.e("Prober", "探活异常", e)
                }
                delay(Thresholds.PROBE_POLL_INTERVAL)
            }
        }
    }

    private suspend fun probeSources() {
        val t = now()
        sourceDao.getBlacklisted().forEach { h ->
            if (!ProbeIntervalCalculator.shouldProbe(h, t)) return@forEach
            val source = sources.find { it.key == h.sourceKey } ?: return@forEach

            val alive = try {
                withTimeout(Thresholds.PROBE_TIMEOUT) { source.ping() }
                true
            } catch (e: Exception) { false }

            h.lastProbeTime = t
            if (alive) {
                recoverSource(h)
                sourceDao.upsert(h)
            } else {
                h.probeFailCount++
                if (h.probeFailCount >= Thresholds.PROBE_FAIL_EXTEND_THRESHOLD) {
                    // 连续探活失败，延长拉黑
                    h.blacklistUntil = t + h.blacklistDuration
                    h.blacklistTime = t
                    h.probeFailCount = 0
                }
                sourceDao.upsert(h)
            }
        }
    }

    private suspend fun probeLines() {
        // 线路探活：通过源重新解析该线路地址并 HEAD 请求
        // 实现略（依赖具体源实现），逻辑同 probeSources
    }

    private fun recoverSource(h: SourceHealth) {
        h.blacklistUntil = 0
        h.blacklistReason = ""
        h.blacklistCategory = BlacklistCategory.NONE
        h.consecutiveFails = 0
        h.firstFailTime = 0
        h.probeFailCount = 0
        h.observingUntil = now() + Thresholds.OBSERVING_DURATION
        h.observingFailCount = 0
        h.healthScore = Thresholds.OBSERVING_INIT_SCORE
    }
}
```

### 3.4 L3：用户手动重置

```kotlin
class ManualRecovery(
    private val sourceDao: SourceHealthDao,
    private val sources: List<Source>
) {
    /**
     * 手动重试：先探活，成功才恢复
     * 返回：是否恢复成功
     */
    suspend fun retry(sourceKey: String): Boolean {
        val source = sources.find { it.key == sourceKey } ?: return false
        val alive = try {
            withTimeout(Thresholds.PROBE_TIMEOUT) { source.ping() }
            true
        } catch (e: Exception) { false }

        if (alive) {
            val h = sourceDao.get(sourceKey) ?: return false
            reset(h)
            sourceDao.upsert(h)
        }
        return alive
    }

    /**
     * 手动解封：不探活，直接恢复
     */
    suspend fun forceUnblacklist(sourceKey: String) {
        val h = sourceDao.get(sourceKey) ?: return
        reset(h)
        sourceDao.upsert(h)
    }

    private fun reset(h: SourceHealth) {
        h.blacklistUntil = 0
        h.blacklistReason = ""
        h.blacklistCategory = BlacklistCategory.NONE
        h.consecutiveFails = 0
        h.firstFailTime = 0
        h.probeFailCount = 0
        h.observingUntil = now() + Thresholds.OBSERVING_DURATION
        h.observingFailCount = 0
        h.healthScore = Thresholds.OBSERVING_INIT_SCORE
    }
}
```

### 3.5 L4：自然过期解封

```kotlin
object NaturalExpiry {
    /**
     * 惰性清理：每次读取源列表前调用
     */
    suspend fun cleanup(sourceDao: SourceHealthDao, lineDao: LineHealthDao) {
        val t = now()
        sourceDao.getBlacklisted().forEach { h ->
            if (t >= h.blacklistUntil) {
                h.blacklistUntil = 0
                h.blacklistReason = ""
                h.blacklistCategory = BlacklistCategory.NONE
                h.consecutiveFails = 0
                h.firstFailTime = 0
                h.probeFailCount = 0
                h.observingUntil = t + Thresholds.OBSERVING_DURATION
                h.observingFailCount = 0
                h.healthScore = Thresholds.OBSERVING_INIT_SCORE
                sourceDao.upsert(h)
            }
        }
        // 线路同理
    }
}
```

### 3.6 观察期防抖机制

```kotlin
object ObservingGuard {
    /**
     * 观察期内的失败处理
     * 返回：是否触发重新拉黑
     */
    fun onFail(h: SourceHealth, reason: FailReason): Boolean {
        val t = now()
        if (t >= h.observingUntil) return false  // 不在观察期

        // 观察期失败计数翻倍
        h.observingFailCount += Thresholds.OBSERVING_FAIL_MULTIPLIER

        if (h.observingFailCount >= Thresholds.OBSERVING_REBLACKLIST_THRESHOLD) {
            // 重新拉黑，时长翻倍
            val newDuration = h.blacklistDuration * 2
            h.blacklistUntil = t + newDuration
            h.blacklistReason = reason.name
            h.blacklistCategory = FailAttribution.categorize(reason)
            h.blacklistTime = t
            h.blacklistDuration = newDuration
            h.healthScore = 0.0
            h.observingUntil = 0
            h.observingFailCount = 0
            return true
        }
        return false
    }

    /**
     * 观察期内的成功处理
     */
    fun onSuccess(h: SourceHealth) {
        if (now() < h.observingUntil) {
            h.observingFailCount = 0
        }
    }
}
```

### 3.7 恢复流程总图

```
BLACKLISTED
    │
    ├─ L1 网络恢复/切换 ──▶ 批量平反 NETWORK_RELATED ──┐
    │                                                  │
    ├─ L2 定时探活 ──▶ 探活成功 ──────────────────────┤
    │                                                  │
    ├─ L3 用户手动 ──▶ 探活成功/强制解封 ─────────────┤
    │                                                  │
    └─ L4 自然过期 ──▶ 时间到 ────────────────────────┤
                                                       ▼
                                                  OBSERVING
                                                       │
                                          ┌────────────┴────────────┐
                                          │                         │
                                     观察期成功                  观察期失败
                                          │                         │
                                          ▼                         ▼
                                      HEALTHY              observingFailCount += 2
                                                                    │
                                                          ┌─────────┴─────────┐
                                                          │                   │
                                                     < 3 次               ≥ 3 次
                                                          │                   │
                                                          ▼                   ▼
                                                     继续观察          BLACKLISTED（时长翻倍）
```

---

## 4. 动态质量评分模型

### 4.1 源级健康分公式

```kotlin
object SourceScorer {
    /**
     * 源健康分 [0, 1]
     * 公式：score = (successRate * 0.6 + latencyScore * 0.4) * freshness
     * 约束：不可用（拉黑中）返回 0.0
     */
    fun score(h: SourceHealth): Double {
        if (!isAvailable(h)) return 0.0

        val successRate = h.successCount.toDouble() / (h.totalRequests + 1)
        val latencyScore = (1.0 - h.avgSearchMs / 5000.0).coerceIn(0.0, 1.0)
        val freshness = if (now() - h.lastSuccessTime < DAY) 1.0 else 0.5

        return (successRate * 0.6 + latencyScore * 0.4) * freshness
    }

    fun isAvailable(h: SourceHealth): Boolean {
        val t = now()
        return t >= h.blacklistUntil && h.consecutiveFails < 5
    }
}
```

### 4.2 线路质量分公式

```kotlin
object LineScorer {
    /**
     * 线路质量分 [0, 1]
     * 公式：score = successRate*0.4 + speedScore*0.25 + latencyScore*0.15 + resScore*0.2
     */
    fun score(h: LineHealth): Double {
        if (!isAvailable(h)) return 0.0

        val successRate = h.successPlays.toDouble() / (h.totalPlays + 1)
        val speedScore = (h.avgSpeedKbps / 10000.0).coerceIn(0.0, 1.0)
        val latencyScore = (1.0 - h.avgTtfbMs / 5000.0).coerceIn(0.0, 1.0)
        val resScore = (h.resolution / 1080.0).coerceIn(0.0, 1.0)

        return successRate * 0.4 + speedScore * 0.25 + latencyScore * 0.15 + resScore * 0.2
    }

    fun isAvailable(h: LineHealth): Boolean {
        val t = now()
        return t >= h.blacklistUntil && h.consecutiveFails < 3
    }
}
```

### 4.3 场景权重表（Agent 必须严格使用）

```kotlin
data class SceneWeights(
    val quality: Double,    // 清晰度权重
    val speed: Double,      // 速度权重
    val health: Double      // 健康度权重
) {
    init {
        require(abs(quality + speed + health - 1.0) < 1e-6) {
            "权重之和必须为 1.0"
        }
    }
}

object SceneWeightTable {
    val TABLE: Map<PlayScene, SceneWeights> = mapOf(
        PlayScene.COLD_START    to SceneWeights(quality = 0.2, speed = 0.6, health = 0.2),
        PlayScene.STEADY        to SceneWeights(quality = 0.5, speed = 0.3, health = 0.2),
        PlayScene.WEAK_NETWORK  to SceneWeights(quality = 0.1, speed = 0.7, health = 0.2),
        PlayScene.TV_BIG_SCREEN to SceneWeights(quality = 0.6, speed = 0.2, health = 0.2)
    )
}
```

### 4.4 综合排序分公式

```kotlin
object LineRanker {
    /**
     * 线路综合排序分
     * 公式：
     *   resScore     = clamp(resolution / 1080, 0, 1)
     *   speedScore   = clamp(speedKbps / 10000, 0, 1)
     *   latencyScore = clamp(1 - ttfbMs / 5000, 0, 1)
     *   speedCombined = speedScore * 0.6 + latencyScore * 0.4
     *   healthScore  = LineHealth.qualityScore（无记录时 0.5）
     *   final = w.quality * resScore + w.speed * speedCombined + w.health * healthScore
     */
    fun rank(
        line: UnifiedLine,
        health: LineHealth?,
        scene: PlayScene
    ): Double {
        if (health != null && !LineScorer.isAvailable(health)) return 0.0

        val w = SceneWeightTable.TABLE[scene] ?: SceneWeightTable.TABLE[PlayScene.STEADY]!!

        val resScore = (line.resolution / 1080.0).coerceIn(0.0, 1.0)
        val speedScore = (line.speedKbps / 10000.0).coerceIn(0.0, 1.0)
        val latencyScore = (1.0 - line.ttfbMs / 5000.0).coerceIn(0.0, 1.0)
        val speedCombined = speedScore * 0.6 + latencyScore * 0.4
        val healthScore = health?.qualityScore ?: 0.5

        return w.quality * resScore + w.speed * speedCombined + w.health * healthScore
    }

    /**
     * 排序：降序，分数相同按 lineId 字典序升序（保证稳定）
     */
    fun sort(
        lines: List<UnifiedLine>,
        healthMap: Map<String, LineHealth>,
        scene: PlayScene
    ): List<UnifiedLine> {
        return lines
            .filter { LineScorer.isAvailable(healthMap[it.id] ?: LineHealth(it.id)) }
            .sortedWith(
                compareByDescending<UnifiedLine> { rank(it, healthMap[it.id], scene) }
                    .thenBy { it.id }
            )
    }
}
```

### 4.5 场景判定规则

```kotlin
object SceneDetector {
    /**
     * 判定当前播放场景
     * 判定顺序：从上到下，命中即返回
     */
    fun detect(
        isFirstPlay: Boolean,
        networkType: NetworkType,
        signalDbm: Int?,
        isTv: Boolean
    ): PlayScene = when {
        isTv -> PlayScene.TV_BIG_SCREEN
        isFirstPlay -> PlayScene.COLD_START
        networkType == NetworkType.CELLULAR &&
            signalDbm != null && signalDbm < Thresholds.NETWORK_SIGNAL_WEAK_DBM -> PlayScene.WEAK_NETWORK
        else -> PlayScene.STEADY
    }
}
```

### 4.6 降权规则（不拉黑但降分）

```kotlin
object DegradeRules {
    /**
     * 应用降权规则
     * 在计算 rank 前调用，返回调整后的分数
     */
    fun apply(
        baseScore: Double,
        line: UnifiedLine,
        isTv: Boolean
    ): Double {
        var score = baseScore
        val speedThreshold = if (isTv) Thresholds.SPEED_DEGRADE_TV else Thresholds.SPEED_DEGRADE_PHONE
        val resThreshold = if (isTv) Thresholds.RES_DEGRADE_TV else Thresholds.RES_DEGRADE_PHONE

        // 速度过低 → 降权 50%
        if (line.speedKbps < speedThreshold) score *= 0.5
        // 清晰度过低 → 降权 30%
        if (line.resolution < resThreshold) score *= 0.7

        return score
    }
}
```

---

## 5. 播放态实时干预机制

### 5.1 卡顿检测

```kotlin
class StallDetector(
    private val player: ExoPlayer,
    private val onStall: () -> Unit
) {
    private var lastPosition = 0L
    private var stallStartTime = 0L
    private val stallTimestamps = mutableListOf<Long>()

    /**
     * 每 500ms 调用一次
     */
    fun onTick() {
        val pos = player.currentPosition
        val playing = player.isPlaying
        val t = now()

        if (playing && pos == lastPosition) {
            if (stallStartTime == 0L) {
                stallStartTime = t
            } else if (t - stallStartTime >= Thresholds.STALL_DETECT_MS) {
                // 判定卡顿
                stallTimestamps.add(t)
                onStall()
                stallStartTime = 0L
            }
        } else {
            stallStartTime = 0L
        }
        lastPosition = pos
    }

    /**
     * 是否频繁卡顿
     * 判定：窗口内卡顿次数 ≥3
     */
    fun isFrequentStall(): Boolean {
        val cutoff = now() - Thresholds.STALL_FREQUENT_WINDOW
        stallTimestamps.removeAll { it < cutoff }
        return stallTimestamps.size >= Thresholds.STALL_FREQUENT_COUNT
    }

    fun reset() {
        stallTimestamps.clear()
        stallStartTime = 0L
    }
}
```

### 5.2 播放失败自动切换流程

```kotlin
class PlaybackErrorHandler(
    private val player: ExoPlayer,
    private val lineHealthManager: LineHealthManager,
    private val controller: PlaybackController
) {
    /**
     * 播放器错误回调
     * 执行顺序严格如下
     */
    fun onPlayerError(error: PlaybackException) {
        val current = controller.currentLine ?: return
        val reason = FailReasonMapper.map(error)

        // 步骤1：记录失败
        lineHealthManager.recordPlayFail(current.id, current.sourceKey, reason)

        // 步骤2：查找下一条可用线路
        val next = controller.findNextAvailableLine()

        // 步骤3：分支处理
        if (next != null) {
            controller.switchToLine(next, SwitchReason.ERROR)
        } else {
            controller.onNoAvailableLine()
        }
    }
}
```

### 5.3 频繁卡顿降级策略

```kotlin
class StallRecoveryHandler(
    private val controller: PlaybackController,
    private val lineHealthManager: LineHealthManager
) {
    /**
     * 频繁卡顿处理
     * 执行顺序严格如下，命中即返回
     */
    suspend fun handleFrequentStall() {
        val current = controller.currentLine ?: return

        // 策略1：同线路降码率
        val lower = controller.findLowerQualitySameLine(current)
        if (lower != null) {
            controller.switchToLine(lower, SwitchReason.DEGRADE)
            return
        }

        // 策略2：跨源找更稳的线路
        val stable = controller.pool.linesForEpisode(current.episodeIndex)
            .filter { it.id != current.id }
            .filter { it.probeSuccess }
            .filter { it.speedKbps > current.speedKbps * 1.5 }
            .maxByOrNull { it.healthScore }

        if (stable != null) {
            controller.switchToLine(stable, SwitchReason.STALL)
            return
        }

        // 策略3：无可用，提示用户
        controller.onNoAvailableLine()
    }
}
```

### 5.4 跨源无缝切换流程

```kotlin
class PlaybackController(
    private val player: ExoPlayer,
    val pool: LinePool,
    private val lineHealthManager: LineHealthManager
) {
    var currentLine: UnifiedLine? = null
        private set

    /**
     * 切换到指定线路
     * 执行顺序严格如下
     */
    fun switchToLine(target: UnifiedLine, reason: SwitchReason) {
        val from = currentLine

        // 步骤1：记录切换前位置
        val position = player.currentPosition
        val wasPlaying = player.isPlaying

        // 步骤2：暂停当前播放
        player.pause()

        // 步骤3：构建新媒体源
        val mediaItem = buildMediaItem(target, position)

        // 步骤4：设置媒体源并准备
        player.setMediaItem(mediaItem)
        player.prepare()

        // 步骤5：监听首帧就绪，恢复播放
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    player.seekTo(position)
                    if (wasPlaying) player.play()
                    player.removeListener(this)
                }
            }
        })

        // 步骤6：更新当前线路
        currentLine = target

        // 步骤7：记录切换事件
        Log.i("Switch", "线路切换: ${from?.id} → ${target.id}, 原因: $reason")
    }

    /**
     * 查找下一条可用线路
     * 规则：从池中按 rank 降序，取第一条非当前且可用的
     */
    fun findNextAvailableLine(): UnifiedLine? {
        val current = currentLine ?: return null
        val candidates = pool.linesForEpisode(current.episodeIndex)
            .filter { it.id != current.id }
            .filter { LineScorer.isAvailable(lineHealthManager.get(it.id) ?: LineHealth(it.id)) }
        return candidates.maxByOrNull { it.healthScore }
    }

    fun onNoAvailableLine() {
        // 通知 UI 层展示错误
    }
}

enum class SwitchReason {
    ERROR,      // 播放错误
    STALL,      // 卡顿
    DEGRADE,    // 降级
    MANUAL      // 用户手动
}
```

### 5.5 播放态干预参数总表

| 参数 | 值 | 说明 |
|------|-----|------|
| 卡顿检测周期 | 500 ms | onTick 调用间隔 |
| 卡顿判定阈值 | 2000 ms | 位置不变时长 |
| 频繁卡顿窗口 | 60 s | 统计窗口 |
| 频繁卡顿次数 | 3 | 触发降级 |
| 降级速度倍数 | 1.5x | 跨源切换要求 |
| 切换位置保持 | 是 | 记录并恢复 position |

---

## 6. 系统集成与调度总流程

### 6.1 源选择流程

```
用户发起搜索/播放
    │
    ▼
┌─────────────────────┐
│ 1. 惰性清理过期拉黑   │  NaturalExpiry.cleanup()
└──────────┬──────────┘
           ▼
┌─────────────────────┐
│ 2. 过滤可用源         │  isAvailable() == true
└──────────┬──────────┘
           ▼
┌─────────────────────┐
│ 3. 按健康分降序排序   │  SourceScorer.score()
└──────────┬──────────┘
           ▼
┌─────────────────────┐
│ 4. 并发请求 Top N 源  │  N = 5（可配置）
└──────────┬──────────┘
           ▼
┌─────────────────────┐
│ 5. 记录成功/失败      │  recordSuccess / recordFail
└─────────────────────┘
```

### 6.2 线路选择流程

```
获取到某影片的线路列表
    │
    ▼
┌─────────────────────┐
│ 1. 判定播放场景       │  SceneDetector.detect()
└──────────┬──────────┘
           ▼
┌─────────────────────┐
│ 2. 过滤可用线路       │  LineScorer.isAvailable()
└──────────┬──────────┘
           ▼
┌─────────────────────┐
│ 3. 计算综合排序分     │  LineRanker.rank()
└──────────┬──────────┘
           ▼
┌─────────────────────┐
│ 4. 应用降权规则       │  DegradeRules.apply()
└──────────┬──────────┘
           ▼
┌─────────────────────┐
│ 5. 降序排序取 Top 1   │  LineRanker.sort()
└──────────┬──────────┘
           ▼
┌─────────────────────┐
│ 6. 起播              │
└─────────────────────┘
```

### 6.3 后台任务调度表

| 任务 | 周期 | 说明 |
|------|------|------|
| 探活轮询 | 2 min | BlacklistProber.start() |
| 卡顿检测 | 500 ms | StallDetector.onTick() |
| 惰性清理 | 每次读取 | NaturalExpiry.cleanup() |
| 每日清理 | 1 day | 僵尸源标记 + 过期记录删除 |

### 6.4 每日清理任务

```kotlin
class DailyCleaner(
    private val sourceDao: SourceHealthDao,
    private val lineDao: LineHealthDao
) {
    suspend fun run() {
        val t = now()

        // 1. 标记僵尸源（30 天无成功）
        sourceDao.getAll().forEach { h ->
            val daysSinceSuccess = (t - h.lastSuccessTime) / DAY
            if (daysSinceSuccess > Thresholds.ZOMBIE_SOURCE_DAYS) {
                h.healthScore = 0.0
                sourceDao.upsert(h)
            }
        }

        // 2. 删除 90 天前的线路健康记录
        lineDao.deleteOlderThan(t - Thresholds.LINE_HEALTH_RETENTION_DAYS * DAY)

        // 3. 重置过期拉黑
        sourceDao.resetExpiredBlacklist(t)
        lineDao.resetExpiredBlacklist(t)
    }
}
```

---

## 7. Agent 执行约束（强制）

1. **阈值不可改**：所有 `Thresholds` 常量必须原样使用，不得调整。
2. **分支顺序不可变**：所有 `when` / `if-else` 分支的判定顺序必须与文档一致。
3. **状态转换不可跳**：状态机转换必须严格按 2.8 节表格执行。
4. **归因不可省**：任何失败记录前必须经过 `NetworkGate` → `FailAttribution` → `FailureAggregator` 三道门。
5. **恢复必进观察期**：任何恢复操作后必须设置 `observingUntil`，不得直接置为 HEALTHY。
6. **评分公式不可改**：4.1 / 4.2 / 4.4 的公式必须原样实现。
7. **场景权重不可改**：4.3 的权重表必须原样使用。
8. **探活接口约束**：必须满足 3.3.1 的 4 条约束。
9. **切换保持位置**：5.4 的切换流程必须记录并恢复播放位置。
10. **日志必打**：所有拉黑、恢复、切换操作必须打日志，格式：`[模块] 动作: 对象, 原因`。

---

## 8. 附录：完整常量速查

| 常量 | 值 |
|------|-----|
| NETWORK_OUTAGE_WINDOW | 30 s |
| NETWORK_OUTAGE_DISTINCT_SOURCES | 3 |
| NETWORK_OUTAGE_TOTAL_FAILS | 5 |
| SOURCE_FAIL_BLACKLIST_5 | 5 |
| SOURCE_FAIL_BLACKLIST_5_SPAN | 2 min |
| SOURCE_FAIL_BLACKLIST_10 | 10 |
| SOURCE_FAIL_BLACKLIST_10_SPAN | 5 min |
| SOURCE_BLACKLIST_DURATION_5 | 30 min |
| SOURCE_BLACKLIST_DURATION_10 | 24 h |
| SOURCE_BLACKLIST_DURATION_404 | 1 h |
| SOURCE_BLACKLIST_DURATION_5XX | 30 min |
| SOURCE_BLACKLIST_DURATION_TIMEOUT | 10 min |
| SOURCE_BLACKLIST_DURATION_CONNECT | 10 min |
| LINE_FAIL_BLACKLIST | 3 |
| LINE_FAIL_BLACKLIST_SPAN | 1 min |
| LINE_BLACKLIST_DURATION | 1 h |
| LINE_BLACKLIST_DURATION_404 | 6 h |
| PROBE_TIMEOUT | 5 s |
| PROBE_POLL_INTERVAL | 2 min |
| PROBE_INTERVAL_MIN | 2 min |
| PROBE_INTERVAL_MAX | 30 min |
| PROBE_FAIL_EXTEND_THRESHOLD | 3 |
| OBSERVING_DURATION | 10 min |
| OBSERVING_INIT_SCORE | 0.5 |
| OBSERVING_FAIL_MULTIPLIER | 2 |
| OBSERVING_REBLACKLIST_THRESHOLD | 3 |
| STALL_DETECT_MS | 2000 ms |
| STALL_FREQUENT_COUNT | 3 |
| STALL_FREQUENT_WINDOW | 60 s |
| SPEED_DEGRADE_PHONE | 500 Kbps |
| SPEED_DEGRADE_TV | 1000 Kbps |
| RES_DEGRADE_PHONE | 480 |
| RES_DEGRADE_TV | 720 |
| ZOMBIE_SOURCE_DAYS | 30 |
| LINE_HEALTH_RETENTION_DAYS | 90 |

---

**文档结束。Agent 可据此直接实现，无需额外推断。**