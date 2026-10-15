package com.github.tvbox.osc.player

/**
 * 同一个「源|片」的画质深探**跑几遍就该收手**(2026-10-15 用户定案)。
 *
 * <p>他要的口径很具体:"当前源所有线路被探测一遍之后,最多再重复探测两次;
 * 如果探测不到,那就确认应该是真的探测不到。" 这条不是省流量,而是**止住一种死循环**:
 * 真机见过《狮子王》那个源 4 条线路里 3 条永远测不出(一条是加密分片列表、原理上测不了),
 * 而每次进详情页都会把它们重新排进计划 —— 花的是用户带宽,攒不出任何新信息。
 *
 * <p>计数按"一整遍"而不是"一次请求":一遍之内每条未测线路都会被打(含阶梯重试),
 * 这一遍没测出任何东西才算一轮。测到了就清零 —— 它证明这个源是能探的,
 * 之前只是那几条不行。
 *
 * <p>结论会过期([TTL_MS]):线路地址、CDN 与站点鉴权都会变,
 * "确认真的探不到"不该变成永久标签 —— 与站点冷却只放内存是同一个哲学
 * (见 [ProbeHostMemory] 的 KDoc),差别只在这份证据过期得慢得多。
 */
object ProbeRoundPolicy {

    /** 首探一遍 + 最多再重复两遍 */
    const val MAX_ROUNDS = 3

    /** 结论有效期:7 天后重新给一次机会 */
    const val TTL_MS = 7L * 24 * 60 * 60 * 1000

    /** 这一遍还要不要排进计划 */
    @JvmStatic
    fun shouldStop(rounds: Int): Boolean = rounds >= MAX_ROUNDS

    /**
     * 一遍跑完后的轮次:测到任何东西就清零,一条都没测到才算一轮。
     *
     * <p>负数/异常入参一律当 0 轮处理(调用方拿的是外部存储,坏数据不该把探测永久锁死)。
     */
    @JvmStatic
    fun nextRounds(rounds: Int, measuredThisRound: Int): Int {
        if (measuredThisRound > 0) return 0
        if (rounds < 0) return 1
        return rounds + 1
    }
}
