package com.github.tvbox.osc.player

import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.doikki.videoplayer.player.VideoView

/**
 * 换线后等起播的宽限判据。判反的两个方向都坏:
 * 该宽限时不宽限 = 高码率线路永远播不上(用户报的现象);
 * 不该宽限时乱宽限 = 死线也要等满 17 秒才换,比原来更糟。
 */
class LineStartGracePolicyTest {

    private val noBudget = 0
    private val hasBudget = LineStartGracePolicy.MAX_CHECKS
    private val noBaseline = -1

    @Test
    fun `已起播就不该换线`() {
        assertEquals(
            LineStartGraceDecision.STARTED,
            LineStartGracePolicy.decide(true, VideoView.STATE_PLAYING, 0, noBaseline, hasBudget),
        )
    }

    @Test
    fun `缓冲在长就再等一次,第一拍只立基线`() {
        // 第一拍没有可比的历史:唯一不猜的选法是再等一拍
        assertEquals(
            LineStartGraceDecision.WAIT_MORE,
            LineStartGracePolicy.decide(false, VideoView.STATE_PREPARING, 12, noBaseline, hasBudget),
        )
        // 12 → 20 在长 ⇒ 继续等
        assertEquals(
            LineStartGraceDecision.WAIT_MORE,
            LineStartGracePolicy.decide(false, VideoView.STATE_BUFFERING, 20, 12, hasBudget),
        )
    }

    /**
     * 真机踩过的形状:15:58:37 / :42 / :47 三拍都停在 14。只问"缓冲大于 0"会把它当慢线白等 15 秒,
     * 而它其实一分钟都没进新的字节 —— 增量判据必须把它立刻切掉。
     */
    @Test
    fun `读数冻住不动等于死线,不再花预算`() {
        assertEquals(
            LineStartGraceDecision.SWITCH_NOW,
            LineStartGracePolicy.decide(false, VideoView.STATE_BUFFERING, 14, 14, hasBudget),
        )
        // 回退(换了个流重新计)同样不是"在推进"
        assertEquals(
            LineStartGraceDecision.SWITCH_NOW,
            LineStartGracePolicy.decide(false, VideoView.STATE_PREPARING, 8, 14, hasBudget),
        )
    }

    @Test
    fun `明确报错立刻换线,不宽限`() {
        // 错误态即使缓冲还在涨也不该等
        assertEquals(
            LineStartGraceDecision.SWITCH_NOW,
            LineStartGracePolicy.decide(false, VideoView.STATE_ERROR, 40, 12, hasBudget),
        )
    }

    @Test
    fun `缓冲读数取不到一律照旧立刻切`() {
        // 连不上的地址报 PREPARING 但百分比恒 0;时长未知的直播读不到百分比(-1)
        // ⇒ 都不宽限:判据不确定时绝不比原来更宽容
        assertEquals(
            LineStartGraceDecision.SWITCH_NOW,
            LineStartGracePolicy.decide(false, VideoView.STATE_PREPARING, 0, noBaseline, hasBudget),
        )
        assertEquals(
            LineStartGraceDecision.SWITCH_NOW,
            LineStartGracePolicy.decide(false, VideoView.STATE_BUFFERING, -1, 20, hasBudget),
        )
        assertEquals(
            LineStartGraceDecision.SWITCH_NOW,
            LineStartGracePolicy.decide(false, -1, 30, 10, hasBudget),
        )
        assertEquals(
            LineStartGraceDecision.SWITCH_NOW,
            LineStartGracePolicy.decide(false, VideoView.STATE_IDLE, 30, 10, hasBudget),
        )
    }

    @Test
    fun `预算用完仍在涨才放弃,且这次不算线路的失败证据`() {
        assertEquals(
            LineStartGraceDecision.SWITCH_WAS_PROGRESSING,
            LineStartGracePolicy.decide(false, VideoView.STATE_BUFFERING, 22, 18, noBudget),
        )
        // 冻住的分支即便预算为零也走 SWITCH_NOW(该记失败就记)
        assertEquals(
            LineStartGraceDecision.SWITCH_NOW,
            LineStartGracePolicy.decide(false, VideoView.STATE_PREPARING, 14, 14, noBudget),
        )
    }

    @Test
    fun `只有宽限后仍在涨才抑制失败累计`() {
        assertEquals(true, LineStartGraceDecision.SWITCH_WAS_PROGRESSING.gaveUpWhileProgressing())
        assertEquals(false, LineStartGraceDecision.SWITCH_NOW.gaveUpWhileProgressing())
        assertEquals(false, LineStartGraceDecision.WAIT_MORE.gaveUpWhileProgressing())
        assertEquals(false, LineStartGraceDecision.STARTED.gaveUpWhileProgressing())
    }

    /**
     * 额度**只在 beginNewPlay 发**,不在每次 arm 发:arm 有多个入口(汇合点 playUrl、页面桥,
     * 同址重播还会再来),在那里发等于每次重播都续一次命 ⇒ "17 秒封顶"变成"最长不确定"。
     */
    @Test
    fun `重复检查最多花掉两次,总封顶 17 秒`() {
        assertEquals(2, LineStartGracePolicy.MAX_CHECKS)
        assertEquals(5_000L, LineStartGracePolicy.CHECK_MS)
        var left = LineStartGracePolicy.MAX_CHECKS
        var last = noBaseline
        var checks = 0
        while (LineStartGracePolicy.decide(false, VideoView.STATE_BUFFERING, 10 + checks, last, left)
            == LineStartGraceDecision.WAIT_MORE
        ) {
            last = 10 + checks
            left--
            checks++
        }
        assertEquals(2, checks)
        assertEquals(
            LineStartGraceDecision.SWITCH_WAS_PROGRESSING,
            LineStartGracePolicy.decide(false, VideoView.STATE_BUFFERING, 13, last, left),
        )
    }
}
