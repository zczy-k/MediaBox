package com.github.tvbox.osc.util

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 源权重模型 —— 《源与线路的权重模型：只降权、不封禁》(2026-10-11 设计稿) 的**纯函数判据层**。
 *
 * <p>## 它补的是哪个空白
 * 现有降权(`SourceHealthPolicy.shouldDefer`)是**二值 + 只排到队尾**的落地:
 * `SearchBatchPolicy` 把降权源放进 deferredKeys,首轮只搜 24 个快源,用户看到结果不再滚动,
 * **队尾永不被搜到** —— 这是"没有安全网的事实封禁"(设计稿 §2)。
 * 本模型把"封禁/不封禁"的二值判断换成**带时间衰减的连续权重**,并配一条**保底配额**,
 * 让低权重的源仍有机会被搜到、自证清白。
 *
 * <p>## 三条必须守住的语义
 * <ol>
 *   <li>**只影响"多容易被选中",永远不决定"能不能被选中"** —— 权重只用于排序;</li>
 *   <li>**权重自动向中性回归** —— 不再被搜的源,30 分钟后权重自己涨回来(杜绝"越差越差"死锁);</li>
 *   <li>**证据强度不对称** —— 搜索环节证据可靠(扣分重),播放失败归因最不可靠(扣分轻),
 *       理由是项目已有两次真机误封教训(「天堂」被一条坏地址整源封 6 小时)。</li>
 * </ol>
 *
 * <p>纯逻辑,无 IO / Android 依赖 —— 由 `SourceWeightPolicyTest` 锁行为。
 */
object SourceWeightPolicy {

    /** 中性分:新源 / 无证据。不占优也不吃亏。 */
    const val NEUTRAL = 60

    /** 半衰期 30 分钟(与现有 `SourceHealthPolicy.DEFER_MS` 同口径,覆盖"同一次使用会话") */
    const val HALF_LIFE_MS = 30L * 60 * 1000

    /** 权重上下限 */
    const val MIN_SCORE = 0
    const val MAX_SCORE = 100

    /** 每轮搜索强制纳入的低权重源数(保底配额);相对 328 源是可忽略的成本 */
    const val FLOOR_QUOTA_N = 3

    /** 显示映射:绿/黄/红阈值(仅用于展示,排序用 [scoreOf] 的连续值) */
    const val TIER_GREEN = 55
    const val TIER_YELLOW = 30

    /**
     * 证据类型。**增量按语义强度给**,不按次数。
     *
     * <p>播放类证据刻意轻于搜索类:用户网络抖动 / 解码器不支持 / 单条地址过期都会算进"播放失败",
     * 归因最不可靠。这是从「天堂」误封事故里学到的。
     */
    enum class EvidenceKind {
        /** 搜索超时(源没答话) */
        SEARCH_TIMEOUT,

        /** 搜索请求失败(HTTP 异常 / 解析异常) */
        SEARCH_FAILED,

        /** 播放失败(线路级:换线即上一条线的失败证据) */
        PLAY_FAIL_LINE,

        /** 播放失败(源级:该片所有线路试尽仍不可播) —— 证据强,唯一可作源级播放证据的 */
        PLAY_FAIL_SOURCE,

        /** 成功播放(真的出画面了,最强正向证据) */
        PLAY_SUCCESS,

        /** 源给出应答(含无命中):"源是活的" */
        SEARCH_ANSWERED,
    }

    /** 一条权重记录。存储只存这两个数,有效权重由时间衰减实时算出(不落盘、不后台任务)。 */
    data class Weight(
        /** 落账分 0..100 */
        val score: Int = NEUTRAL,
        /** 落账时刻(ms);<=0 = 无记录 */
        val at: Long = 0L,
    )

    /** 显示三档(只适合显示,不适合排序 —— 见设计稿 §3.1)。 */
    enum class Tier { GREEN, YELLOW, RED }

    /** 某类证据的增量。 */
    fun deltaFor(kind: EvidenceKind): Int = when (kind) {
        EvidenceKind.SEARCH_TIMEOUT -> -25
        EvidenceKind.SEARCH_FAILED -> -25
        EvidenceKind.PLAY_FAIL_LINE -> -15
        EvidenceKind.PLAY_FAIL_SOURCE -> -30
        EvidenceKind.PLAY_SUCCESS -> 40
        EvidenceKind.SEARCH_ANSWERED -> 20
    }

    /**
     * 有效权重(带时间衰减,连续值)。
     *
     * `effective = NEUTRAL + (score - NEUTRAL) * 0.5^((now - at) / HALF_LIFE)`
     *
     * @param now 当前时刻(ms)。`now - at` 为负(时钟回拨 / 手动改时间)时按 0 处理,
     *            避免"未来时间戳"让衰减指数异常 —— 与 `SourceHealthPolicy` 的时钟回拨保护同口径。
     */
    fun effective(weight: Weight?, now: Long): Double {
        if (weight == null || weight.at <= 0L) return NEUTRAL.toDouble()
        val elapsed = (now - weight.at).coerceAtLeast(0L)
        val decay = 0.5.pow(elapsed.toDouble() / HALF_LIFE_MS)
        return NEUTRAL + (weight.score - NEUTRAL) * decay
    }

    /** 有效权重取整 —— 排序/显示的统一读数。 */
    fun scoreOf(weight: Weight?, now: Long): Int = effective(weight, now).roundToInt()

    /** 显示档位。 */
    fun tier(weight: Weight?, now: Long): Tier {
        val s = scoreOf(weight, now)
        return when {
            s >= TIER_GREEN -> Tier.GREEN
            s >= TIER_YELLOW -> Tier.YELLOW
            else -> Tier.RED
        }
    }

    /**
     * 记一次证据 —— 更新时**先把旧分衰减到当下,再叠加本次增量**。
     *
     * <p>不先衰减的话,"很久以前的一次失败"会被当成刚刚发生(设计稿 §3.4)。
     *
     * @return 新的落账记录(score 已 clamp 到 [MIN_SCORE, MAX_SCORE])
     */
    fun record(old: Weight?, kind: EvidenceKind, now: Long): Weight =
        recordDelta(old, deltaFor(kind), now)

    /** 与 [record] 同义,但直接给增量(便于测试与特殊调用方)。 */
    fun recordDelta(old: Weight?, delta: Int, now: Long): Weight {
        val base = if (old == null || old.at <= 0L) NEUTRAL else effective(old, now).roundToInt()
        return Weight((base + delta).coerceIn(MIN_SCORE, MAX_SCORE), now)
    }

    /**
     * **保底配额**:从候选中挑出 N 个"权重最低"的源强制纳入本轮,确保它们仍有机会被搜到。
     *
     * <p>⚠️ 没有这一条,"只降权"就是"没有安全网的封禁"(设计稿 §2/§5.1):
     * 降权源排到 300 源队尾后,首轮 24 个源就出结果,用户不再滚动 ⇒ 队尾永远不发请求。
     *
     * <p>**必须轮换**:权重并列时按"上次被搜时间"升序(最久没搜的优先),
     * 避免每轮都选中同样几个源。
     *
     * @param scoreAt        取第 i 个候选的**有效权重**;缺失按 [NEUTRAL]
     * @param lastSearchedAtAt 取第 i 个候选的"上次被搜时刻"(无记录传 0);越小越优先
     * @param n              配额数;**n &lt;= 0 = 关闭保底(返回空)**;候选数 &lt;= n 时返回全部
     */
    fun <T> floorQuota(
        candidates: List<T>,
        scoreAt: (Int) -> Int,
        lastSearchedAtAt: (Int) -> Long,
        n: Int = FLOOR_QUOTA_N,
    ): List<T> {
        if (candidates.isEmpty() || n <= 0) return emptyList()
        if (candidates.size <= n) return candidates
        return candidates.indices
            .sortedWith(
                compareBy<Int> { scoreAt(it) }
                    .thenBy { lastSearchedAtAt(it) },
            )
            .take(n)
            .map { candidates[it] }
    }
}