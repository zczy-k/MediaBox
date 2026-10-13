package com.github.tvbox.osc.player

/**
 * 播放失败记忆的**判据层**(纯函数,可 JVM 单测)—— 《源与线路健康度及智能调度系统》"播放态实时干预"
 * 的补充,专治 2026-10-13 真机暴露的**换源死循环**(问题 17)。
 *
 * <p>## 它补的是哪个空白
 * 真机取证(千与千寻):一条 404 死链被 `autoRetry replay same url` 反复重播,换源链在所有候选源之间
 * **无限循环**(换 A 失败→换 B 失败→重搜→又切回 A),用户看到的是"卡死、没有任何举动"。根因:
 * <ol>
 *   <li>**没有"本会话这个源放不出来"的记忆** —— [PlaybackAttemptState.triedLineFlags] 只管线路,
 *       且会被 60s 窗口的 `resetAutoRetryLadder` 清空;换源链的 `usedSourceKeys` 不回退,但
 *       **跨会话重进**后又会重新踩同一个坏源;</li>
 *   <li>**没有失败终态** —— 所有候选源都失败后仍继续重搜,永不给出"暂无可用片源"。</li>
 * </ol>
 *
 * <p>## 与既有健康台账的分工(关键,不可混淆)
 * <ul>
 *   <li>[com.github.tvbox.osc.util.SourceHealthMemory] / [LineHealthMemory] 是**跨会话、落盘、
 *       影响搜索排序**的长期台账 —— 对"播放失败"刻意**不记**(PLAY_FAILED 已退出源级判据,
 *       避免一次坏地址整源被封 6 小时的误伤);</li>
 *   <li>本判据是**会话内、不落盘、只影响"本次换源链怎么走"**的短期记忆 —— 它**不封禁任何源**,
 *       只是让换源链"这一轮别再切回刚刚放不出来的那个源",避免死循环。</li>
 * </ul>
 * 两者正交:长期台账管"优先级",短期记忆管"这一轮别原地打转"。
 *
 * <p>## TTL 为什么必须有
 * 失败可能是**瞬时**的(源站抖动、网络闪断)。若会话内永久拉黑,B 源抖一下就被本片永久排除,
 * 反而让"其实能看的源"再也轮不到。[TTL_MS] 之后自动遗忘,给它重试机会 —— 与"只降权不封禁"同源思想。
 *
 * <p>纯逻辑,无 IO / Android 依赖 —— 由 `PlaybackFailurePolicyTest` 锁行为。
 */
object PlaybackFailurePolicy {

    /**
     * 短期失败记忆的存活时长(ms)。
     *
     * <p>取 5 分钟:与"换源链一轮重搜"的时间尺度(每次重搜 30~60s)匹配 —— 足以覆盖"这一部片的
     * 一轮自动换源",又不会把瞬时抖动记成永久。超过即自动遗忘(见 [isFailed])。
     */
    const val TTL_MS = 5L * 60 * 1000

    /**
     * 该 (源, 片) 组合在 [now] 时是否仍处于"本会话放不出来"状态。
     *
     * @param failedAt 失败记录时刻;0/负 = 无记录
     * @param now      当前时刻(ms)
     */
    @JvmStatic
    fun isFailed(failedAt: Long, now: Long): Boolean {
        if (failedAt <= 0L) return false
        // 时钟回拨保护:now < failedAt 时按"刚失败"处理(与 SourceHealthPolicy 同口径)
        val elapsed = (now - failedAt).coerceAtLeast(0L)
        return elapsed < TTL_MS
    }

    /**
     * 换源链是否还有"**本会话未失败过**"的候选源可切。
     *
     * <p>用于**失败终态**判定(问题 17b):当所有候选源都在短期记忆里挂着失败 ⇒ 没有可切的了,
     * 应进入 `enterEmpty`("暂无可用片源")并**停止重搜循环**,而不是继续无限换源。
     *
     * @param candidateKeys 候选源 key 列表
     * @param failedAtOf    取某源失败时刻的查询函数(0 = 未失败)
     * @param now           当前时刻(ms)
     * @return true = 全部候选都失败过(无可切)
     */
    @JvmStatic
    fun allCandidatesFailed(
        candidateKeys: List<String>,
        failedAtOf: (String) -> Long,
        now: Long,
    ): Boolean {
        if (candidateKeys.isEmpty()) return false
        // 只要有一个"未失败"或"失败已过期"的候选 ⇒ 还有得试
        return candidateKeys.none { !isFailed(failedAtOf(it), now) }
    }
}