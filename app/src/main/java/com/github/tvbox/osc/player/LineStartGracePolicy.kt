package com.github.tvbox.osc.player

import xyz.doikki.videoplayer.player.VideoView

/**
 * 换线后等起播的宽限判据(2026-10-15)。
 *
 * <p>## 补的空白
 * 原来只有一个 7 秒秒表:到点先看"是不是已经起播",没起播就换下一条线。
 * 问题是**"还在正常加载"恰好落在既不算起播、也不算错误的空档里** —— 高码率线路首帧
 * 本来就要更久(1080p/4K 拉满缓冲经常 8~15 秒),于是"网速已经起来了、正要播"被掐掉换线。
 * 把 20s→10s→7s 一路砍下来的理由是"等满等于守着已经坏掉的线路干等",那句话没错,
 * 但它默认了"没起来 = 坏了",而这里真正该问的是"**还在不在动**"。
 *
 * <p>## 判据
 * 用两个内核无关的事实,不用秒表猜:
 * <ul>
 *   <li>状态是 PREPARING/BUFFERING ⇒ 内核还在干活(不是错误、也不是闲在那儿);</li>
 *   <li>已缓冲百分比**在涨** ⇒ 数据真的在进来。⚠️ 这里要的是**增量**而不是"大于 0":
 *       media3 的 bufferedPercentage 是"已缓冲占时长的百分比"、整数粒度,40 分钟的片子
 *       1% 就是 24 秒缓冲,一个"活着但极慢"的加载可以连续几拍读回同一个数字。
 *       真机就踩了这个(15:58:37 / :42 / :47 三拍都停在 14)—— 只问"大于 0"等于白等 15 秒。</li>
 * </ul>
 * 读数不涨就是冻住 ⇒ 立刻换线,且**这算这条线的失败证据**(它十几秒没交付一个字节的增长,
 * 与"高分辨率首帧本来就晚"是两回事)。取不到读数(-1)、恒 0(时长未知的直播)、状态未知
 * 一律照旧立刻切 —— 判据不确定时绝不比原来更宽容,否则就变成"死线也要等满 17 秒"那种更糟的体验。
 *
 * <p>## 预算是封顶而不是许可
 * 总预算由 [LineHealthPolicy.startBudgetMs] 按**这条线路自己的历史首帧耗时**算出来
 * (没有足够历史就是原来的 17 秒:7s 首查 + 2×5s 宽限)。超过预算还在推进的,不是"慢",
 * 是我们对这条线没办法了:让位换线,同时把证据记成"我没等到"而不是"它死了"
 * (见 [LineStartGraceDecision.gaveUpWhileProgressing])。
 *
 * <p>⚠️ 预算换成**绝对截止时刻**而不是"剩余次数",有两个原因:
 * ①`startSwitchLinePlayTimeout` 会被多个入口重复调用(汇合点 `playUrl` 与页面桥各一次,
 * 同址重播还会再来),按次数发额度等于每次重播都续一次命 —— "封顶"就变成"最长不确定";
 * ②预算要能随线路历史变长变短,次数表达不了 12 秒这种数值。
 * 现在截止时刻只在本轮第一次 arm 时算一次,之后任何重复 arm 都不覆盖。
 */
object LineStartGracePolicy {

    /** 宽限期的复查间隔:不是一把新秒表,只是"再问一次还在不在动" */
    const val CHECK_MS = 5_000L

    /**
     * @param started 已经起播(调用方自己会取消等待,这里给 false)
     * @param state dkplayer 的播放状态(`VideoView.STATE_*`);取不到传 -1
     * @param bufferedPercent 当前已缓冲百分比;取不到传 -1
     * @param lastBufferedPercent 上一次检查时的同一读数;本轮还没有基线时传 -1
     * @param remainingMs 本轮预算还剩多少(见 [LineHealthPolicy.startBudgetMs]);<=0 = 已经花完
     */
    @JvmStatic
    fun decide(
        started: Boolean,
        state: Int,
        bufferedPercent: Int,
        lastBufferedPercent: Int,
        remainingMs: Long,
    ): LineStartGraceDecision {
        if (started) return LineStartGraceDecision.STARTED
        if (state == VideoView.STATE_ERROR) return LineStartGraceDecision.SWITCH_NOW
        val working = (state == VideoView.STATE_PREPARING || state == VideoView.STATE_BUFFERING) &&
            bufferedPercent > 0
        if (!working) return LineStartGraceDecision.SWITCH_NOW
        // 第一拍只立基线:此刻没有可比的历史,把它当"在推进"是唯一不猜的选法
        if (lastBufferedPercent < 0) return waitOrGiveUp(remainingMs)
        // 读数没涨(含恒 0 之外的整数粒度停滞)就是"冻住";冻住的线路不该继续花预算
        if (bufferedPercent <= lastBufferedPercent) return LineStartGraceDecision.SWITCH_NOW
        return waitOrGiveUp(remainingMs)
    }

    private fun waitOrGiveUp(remainingMs: Long): LineStartGraceDecision =
        if (remainingMs > 0L) LineStartGraceDecision.WAIT_MORE else LineStartGraceDecision.SWITCH_WAS_PROGRESSING

    /**
     * 下一次复查隔多久:最多 [CHECK_MS],但**剩得不足一个周期就等到底** ——
     * 不然会出现"预算 12 秒、实际等到 15 秒"那种越过上界的情况。
     */
    @JvmStatic
    fun nextCheckMs(remainingMs: Long): Long =
        if (remainingMs <= 0L) 0L else minOf(CHECK_MS, remainingMs)
}

enum class LineStartGraceDecision {
    /** 已经起播:取消等待,什么都不用做 */
    STARTED,

    /** 没有"还在进数据"的证据(错误或状态/缓冲量取不到)⇒ 照旧立刻换线 */
    SWITCH_NOW,

    /** 还在进数据且预算没用完 ⇒ 再等一个复查周期 */
    WAIT_MORE,

    /** 还在进数据但预算用完 ⇒ 换线，但这次不算这条线路的失败证据 */
    SWITCH_WAS_PROGRESSING;

    /** 放弃时是否应抑制"连续失败"累计(见 LineHealthRecorder 的 suppressCount 口径) */
    fun gaveUpWhileProgressing(): Boolean = this == SWITCH_WAS_PROGRESSING
}
