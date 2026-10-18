package com.github.tvbox.osc.util

/**
 * "该源只给磁力/种子"按源记忆的**纯判定规则**(无 Android 依赖,可 JVM 单测)。
 *
 * <p>## 为什么要有这份规则
 * 磁力线路对本 App 是**干扰项**:迅雷 SDK 在此环境下任务恒停在 TASK_IDLE
 * (2026-10-18 真机实测:三条磁力全部解析成功、`.torrent` 元数据均落盘,
 * 但 `lastStatus=0` + `waited=0s`,从未进入 TASK_RUNNING),用户点下去必然白等。
 * 详情侧已把磁力条目摘掉(`PushDetailResolver.dropMagnetLines`),但**每次点进同一个磁力源
 * 都要重走一轮详情请求**(实测详情超时 20~45 秒)才走到换源 —— 复访代价纯属浪费。
 *
 * <p>本规则定义"攒够几次证据才把该源按『磁力源』对待"的门槛与证据有效期,
 * 存储与调用方见 [MagnetSourceMemory]。
 *
 * <p>## 证据口径(与 [SourceHealthPolicy] 的失败证据刻意不同)
 * 只认"**本次详情里磁力条目占绝对多数**"这一**应用层行为**:
 * 超时 / 解析失败 / 空 body 都**不是**本规则的证据 —— 那些是网络/健康问题,归
 * [SourceHealthMemory] 管。这里记的是"这个源的服务方式就是只给磁力"的画像,
 * **不封禁、不降权**,只改取数路径(跳过详情,直接走聚合搜索)。
 *
 * <p>## 为什么独立于 [SourceHealthMemory]
 * 与 [DetailNoListPolicy] 同样的分工:那是"源坏了要屏蔽"的**负面台账**(判据是失败证据,
 * 产出封禁态);本规则是"源的交付形态如此"的**行为画像**(判据是回包内容形状,只改路径)。
 * 混在一起会让"该源被屏蔽了吗"和"要不要跳过它的详情"互相污染口径。
 *
 * <p>⚠️ 尤其要注意:磁力源**被摘空**这件事**不能**记成源失败
 * (不调 `recordBoardFailure`) —— 它不是故障,是行为特征。
 */
object MagnetSourcePolicy {

    /**
     * 30 天窗口内攒够几次"该源只给磁力"才按磁力源对待。
     *
     * <p>1 次太少:可能那部片刚好只有磁力源有,而该源其余内容正常。
     * 取值与 [DetailNoListPolicy.STRIKES_TO_INDEX_LIKE] 对齐(2 次)。
     */
    const val STRIKES_TO_MAGNET_ONLY = 2

    /** 单条证据的有效窗口。与 [DetailNoListPolicy.STRIKE_TTL_MS] 同款 30 天:源可能已改版 */
    const val STRIKE_TTL_MS: Long = 30L * 24 * 60 * 60 * 1000

    /** 每源最多保留几条证据(防脏数据把存储写爆),与 [DetailNoListPolicy.MAX_STRIKES] 一致 */
    const val MAX_STRIKES = 5

    /**
     * 磁力条目占比达到多少才算"磁力源"。
     *
     * <p>**不取 100%**:留出"同源混合"的余量 —— 实测某源(avgood)详情里 10 条线路
     * 全是磁力,但也存在"9 条磁力 + 1 条普通线路"这类形态;后者对用户其实仍可用
     * (那 1 条就能播),所以按 80% 判定更贴近"这个源基本没给正常内容"。
     */
    const val MAGNET_RATIO = 0.8

    /**
     * 磁力条目数下限。
     *
     * <p>防"1 条磁力 / 1 条线路 = 100%"这种**小样本误判**:只给一条磁力的源,
     * 更可能是"这部片恰好只有磁力",不足以给它贴"磁力源"标签。
     */
    const val MIN_MAGNET_LINES = 3

    /**
     * 本次详情是否构成一次"磁力源"证据。
     *
     * @param magnetLines 本次详情里被摘掉的磁力/种子条目数
     * @param totalLines  本次详情里摘除前的**全部**可播条目数(含磁力)
     */
    fun isMagnetOnlyEvidence(magnetLines: Int, totalLines: Int): Boolean =
        magnetLines >= MIN_MAGNET_LINES &&
            totalLines > 0 &&
            magnetLines.toDouble() / totalLines >= MAGNET_RATIO

    /**
     * 记一票:淘汰过期证据 + 追加本次时间戳,最多留 [MAX_STRIKES] 条。
     * 纯函数:返回新列表,不改传入值。
     */
    fun record(strikes: List<Long>, now: Long): List<Long> {
        val fresh = strikes.filter { it > 0 && now - it <= STRIKE_TTL_MS }
        return (fresh + now).takeLast(MAX_STRIKES)
    }

    /** 是否达到"按磁力源对待"的门槛(窗口内证据 >= [STRIKES_TO_MAGNET_ONLY]) */
    fun isMagnetOnly(strikes: List<Long>, now: Long): Boolean =
        strikes.count { it > 0 && now - it <= STRIKE_TTL_MS } >= STRIKES_TO_MAGNET_ONLY

    /** 是否已无有效证据(全部过期/为空) —— 存储层据此清键瘦身 */
    fun hasNoFreshStrike(strikes: List<Long>, now: Long): Boolean =
        strikes.none { it > 0 && now - it <= STRIKE_TTL_MS }
}
