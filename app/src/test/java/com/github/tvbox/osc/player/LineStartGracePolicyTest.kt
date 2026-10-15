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

    @Test
    fun `已起播就不该换线`() {
        assertEquals(
            LineStartGraceDecision.STARTED,
            LineStartGracePolicy.decide(true, VideoView.STATE_PLAYING, 0, hasBudget),
        )
    }

    @Test
    fun `还在准备且真进了数据就再等一次`() {
        assertEquals(
            LineStartGraceDecision.WAIT_MORE,
            LineStartGracePolicy.decide(false, VideoView.STATE_PREPARING, 3, hasBudget),
        )
        assertEquals(
            LineStartGraceDecision.WAIT_MORE,
            LineStartGracePolicy.decide(false, VideoView.STATE_BUFFERING, 12, hasBudget),
        )
    }

    @Test
    fun `明确报错立刻换线,不宽限`() {
        // 错误态即使缓冲百分比残留也不该等
        assertEquals(
            LineStartGraceDecision.SWITCH_NOW,
            LineStartGracePolicy.decide(false, VideoView.STATE_ERROR, 40, hasBudget),
        )
    }

    @Test
    fun `状态在动但一个字节都没进来等于死线`() {
        // 连不上的地址会一直报 PREPARING;缓冲量取不到(-1)或为 0 都不算"在推进"
        assertEquals(
            LineStartGraceDecision.SWITCH_NOW,
            LineStartGracePolicy.decide(false, VideoView.STATE_PREPARING, 0, hasBudget),
        )
        assertEquals(
            LineStartGraceDecision.SWITCH_NOW,
            LineStartGracePolicy.decide(false, VideoView.STATE_PREPARING, -1, hasBudget),
        )
        assertEquals(
            LineStartGraceDecision.SWITCH_NOW,
            LineStartGracePolicy.decide(false, -1, 30, hasBudget),
        )
        assertEquals(
            LineStartGraceDecision.SWITCH_NOW,
            LineStartGracePolicy.decide(false, VideoView.STATE_IDLE, 30, hasBudget),
        )
    }

    @Test
    fun `预算用完仍放弃,但这次不算线路的失败证据`() {
        assertEquals(
            LineStartGraceDecision.SWITCH_WAS_PROGRESSING,
            LineStartGracePolicy.decide(false, VideoView.STATE_BUFFERING, 8, noBudget),
        )
        // 没在推进的分支即便预算为零也走 SWITCH_NOW(该记失败就记)
        assertEquals(
            LineStartGraceDecision.SWITCH_NOW,
            LineStartGracePolicy.decide(false, VideoView.STATE_PREPARING, 0, noBudget),
        )
    }

    @Test
    fun `只有宽限后放弃才抑制失败累计`() {
        assertEquals(true, LineStartGraceDecision.SWITCH_WAS_PROGRESSING.gaveUpWhileProgressing())
        assertEquals(false, LineStartGraceDecision.SWITCH_NOW.gaveUpWhileProgressing())
        assertEquals(false, LineStartGraceDecision.WAIT_MORE.gaveUpWhileProgressing())
        assertEquals(false, LineStartGraceDecision.STARTED.gaveUpWhileProgressing())
    }

    @Test
    fun `总预算封顶在 17 秒左右`() {
        // 首查 7s + 2 次 5s 复查
        assertEquals(5_000L, LineStartGracePolicy.CHECK_MS)
        assertEquals(2, LineStartGracePolicy.MAX_CHECKS)
    }
}
