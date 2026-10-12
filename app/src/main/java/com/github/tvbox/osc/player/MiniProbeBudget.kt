package com.github.tvbox.osc.player

/**
 * 迷你只读通道(P1)的**资源预算**:允许探,但不允许"探个没完"。
 *
 * <p>## 为什么要有预算
 * P1 每次探测 = 一次详情请求(+ 可能一次直链探测)。候选可达几十个,一旦放开,
 * 就会在用户正看片时抢带宽/抢线程 —— 正是用户此前反复强调的"不稳定"来源。
 * 所以除了类型过滤([MiniProbeGate]),还要有**串行 + 间隔 + 冷却 + 每片一次**四道闸。
 *
 * <p>## 四道闸的口径
 * <ul>
 *   <li><b>每会话每片一次</b>:同一部片探测过一次就不再探(避免换集/重扫时的重复开销);</li>
 *   <li><b>串行 ≤ [MAX_SOURCES_PER_WINDOW] 个 / [WINDOW_MS]</b>:不并发轰炸,均匀铺开;</li>
 *   <li><b>负结果冷却 [NEGATIVE_COOLDOWN_MS]</b>:探了没拿到的源,半小时内不再试
 *       (实测超时率高,不给失败源反复机会);</li>
 *   <li><b>资源前提</b>:未开「流量节省」—— 开了则整体不做(与画质升档同一口径)。</li>
 * </ul>
 *
 * <p>⚠️ 这里**刻意不设**"网络是否富余"这一条。它听起来很有用,但详情页 VM
 * **观测不到**它(该信号只存在于播放内核 PlaybackQualityWatchdog 内部:
 * `goodStreak` 与 `bufferedAheadMs()` 都是 host 私有)。若为了"看起来有保护"
 * 而传一个硬编码 true,那就是**空闸门** —— 比不做更糟:后来的人会以为这条已经被守住了。
 *
 * <p>纯逻辑(状态由调用方持有),可直接 JVM 单测。
 */
object MiniProbeBudget {

    /** 一个时间窗内最多探几个源 */
    const val MAX_SOURCES_PER_WINDOW = 5

    /** 时间窗长度:5 个 / 30 秒,即最密每 6 秒一个 */
    const val WINDOW_MS = 30_000L

    /** 负结果(探了没拿到画质)冷却时长 */
    const val NEGATIVE_COOLDOWN_MS = 30L * 60 * 1000

    /**
     * 整体是否允许开始探测。
     *
     * @param trafficSaver 「流量节省」是否开启;开启则一律不探(与画质升档同一口径)
     * @param titleAlreadyProbed 本会话该片是否已探过(每片只探一次)
     */
    @JvmStatic
    fun canStart(
        trafficSaver: Boolean,
        titleAlreadyProbed: Boolean,
    ): Boolean {
        if (trafficSaver) return false
        return !titleAlreadyProbed
    }

    /**
     * 窗口内是否还有额度。
     *
     * @param probesInWindow 当前窗口内已发起的探测次数
     */
    @JvmStatic
    fun hasWindowQuota(probesInWindow: Int): Boolean = probesInWindow < MAX_SOURCES_PER_WINDOW

    /**
     * 某个源此刻是否处于负结果冷却中。
     *
     * @param lastFailureAtMs 上次探测失败的时间戳(0 = 从未失败)
     * @param nowMs 当前时间
     */
    @JvmStatic
    fun inNegativeCooldown(lastFailureAtMs: Long, nowMs: Long): Boolean {
        if (lastFailureAtMs <= 0L) return false
        if (nowMs < lastFailureAtMs) return false
        return nowMs - lastFailureAtMs < NEGATIVE_COOLDOWN_MS
    }
}
