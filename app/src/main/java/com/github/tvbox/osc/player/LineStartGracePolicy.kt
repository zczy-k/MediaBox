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
 *   <li>{@code bufferedPercent > 0} ⇒ **真的有字节进来并已被解析成缓冲** ——
 *       这条比状态更硬:一个永远连不上的地址会一直报 PREPARING 而缓冲百分比始终 0。</li>
 * </ul>
 * 两者同时成立才宽限;任何一个取不到(含 -1/0、状态未知)一律**照旧立刻切** ——
 * 判据不确定时绝不比原来更宽容,否则就变成"死线也要等满 17 秒"那种更糟的体验。
 *
 * <p>## 预算是封顶而不是许可
 * 宽限最多 [MAX_CHECKS] 次(7s + 2×5s = 17s)。超过这个还在推进的,不是"慢",
 * 是我们对这条线没办法了:让位换线,同时把证据记成"我没等到"而不是"它死了"
 * (见 [LineStartGraceDecision.gaveUpWhileProgressing])。
 */
object LineStartGracePolicy {

    /** 宽限期的复查间隔:不是一把新秒表,只是"再问一次还在不在动" */
    const val CHECK_MS = 5_000L

    /** 最多宽限几次(总预算 = 7s + CHECK_MS × 本值) */
    const val MAX_CHECKS = 2

    /**
     * @param started 已经起播(调用方自己会取消等待,这里给 false)
     * @param state dkplayer 的播放状态(`VideoView.STATE_*`);取不到传 -1
     * @param bufferedPercent 已缓冲百分比;取不到传 -1
     * @param checksLeft 还剩几次宽限
     */
    @JvmStatic
    fun decide(started: Boolean, state: Int, bufferedPercent: Int, checksLeft: Int): LineStartGraceDecision {
        if (started) return LineStartGraceDecision.STARTED
        if (state == VideoView.STATE_ERROR) return LineStartGraceDecision.SWITCH_NOW
        val progressing = (state == VideoView.STATE_PREPARING || state == VideoView.STATE_BUFFERING) &&
            bufferedPercent > 0
        if (!progressing) return LineStartGraceDecision.SWITCH_NOW
        return if (checksLeft > 0) LineStartGraceDecision.WAIT_MORE else LineStartGraceDecision.SWITCH_WAS_PROGRESSING
    }
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
