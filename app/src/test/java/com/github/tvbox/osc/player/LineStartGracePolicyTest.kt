package com.github.tvbox.osc.player

import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.doikki.videoplayer.player.VideoView

/**
 * 换线后等起播的宽限判据。判反的两个方向都坏:
 * 该宽限时不宽限 = 高分辨率线路永远播不上(用户报的现象);
 * 不该宽限时乱宽限 = 死线也要等满预算,比原来更糟。
 */
class LineStartGracePolicyTest {

    private val spent = 0L
    private val hasBudget = 10_000L
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
     * 真机踩过的形状:三拍都停在 14。只问"缓冲大于 0"会把它当慢线白等,
     * 而它其实一分钟都没进新的字节 —— 增量判据必须立刻切,并且**这算线路的失败证据**。
     */
    @Test
    fun `读数冻住不动等于死线,不花预算`() {
        assertEquals(
            LineStartGraceDecision.SWITCH_NOW,
            LineStartGracePolicy.decide(false, VideoView.STATE_BUFFERING, 14, 14, hasBudget),
        )
        // 读数回退(换了个流重新计)同样不是"在推进"
        assertEquals(
            LineStartGraceDecision.SWITCH_NOW,
            LineStartGracePolicy.decide(false, VideoView.STATE_PREPARING, 8, 14, hasBudget),
        )
        // 冻住的分支即便预算充足也走 SWITCH_NOW(该记失败就记,不给它宽限)
        assertEquals(
            LineStartGraceDecision.SWITCH_NOW,
            LineStartGracePolicy.decide(false, VideoView.STATE_BUFFERING, 14, 14, 99_000L),
        )
    }

    @Test
    fun `明确报错立刻换线,不宽限`() {
        assertEquals(
            LineStartGraceDecision.SWITCH_NOW,
            LineStartGracePolicy.decide(false, VideoView.STATE_ERROR, 40, 12, hasBudget),
        )
    }

    @Test
    fun `读数取不到一律照旧立刻切`() {
        // 恒 0(连不上的地址报 PREPARING)、-1(时长未知的直播读不到百分比)、状态未知
        // ⇒ 都不宽限:判据不确定时绝不比原来更宽容
        assertEquals(
            LineStartGraceDecision.SWITCH_NOW,
            LineStartGracePolicy.decide(false, VideoView.STATE_PREPARING, 0, noBaseline, hasBudget),
        )
        assertEquals(
            LineStartGraceDecision.SWITCH_NOW,
            LineStartGracePolicy.decide(false, VideoView.STATE_BUFFERING, -1, 20, hasBudget),
        )
        assertEquals(LineStartGraceDecision.SWITCH_NOW,
            LineStartGracePolicy.decide(false, -1, 30, 10, hasBudget))
        assertEquals(
            LineStartGraceDecision.SWITCH_NOW,
            LineStartGracePolicy.decide(false, VideoView.STATE_IDLE, 30, 10, hasBudget),
        )
    }

    @Test
    fun `预算花完仍在涨才放弃,且这次不算线路的失败证据`() {
        assertEquals(
            LineStartGraceDecision.SWITCH_WAS_PROGRESSING,
            LineStartGracePolicy.decide(false, VideoView.STATE_BUFFERING, 22, 18, spent),
        )
        // 基线那一拍预算也没了 ⇒ 同样算"没等到",而不是"它坏了"
        assertEquals(
            LineStartGraceDecision.SWITCH_WAS_PROGRESSING,
            LineStartGracePolicy.decide(false, VideoView.STATE_PREPARING, 5, noBaseline, spent),
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
     * 预算是**承诺的上限**,不是建议:剩余不足一个复查周期时必须等到底再判,
     * 否则会出现"预算 12 秒却等到 15 秒"。
     */
    @Test
    fun `复查间隔不越过预算尾巴`() {
        assertEquals(5_000L, LineStartGracePolicy.nextCheckMs(12_000L))
        assertEquals(2_000L, LineStartGracePolicy.nextCheckMs(2_000L))
        assertEquals(0L, LineStartGracePolicy.nextCheckMs(0L))
        assertEquals(0L, LineStartGracePolicy.nextCheckMs(-3_000L))
    }

    /**
     * 重复 arm 不该续命:预算是**绝对截止时刻**,第一次 arm 之后再来 arm 不覆盖。
     * 这里断言的是判据本身只看"还剩多少",不知道也不关心被 arm 过几次 ——
     * 所以同一轮里第二次拿到同样的 remaining 只会得到同样的结论,不会多等。
     */
    @Test
    fun `同一轮重复检查不会因为再 arm 一次就多等`() {
        val remaining = 4_000L
        val first = LineStartGracePolicy.decide(false, VideoView.STATE_BUFFERING, 20, 15, remaining)
        val second = LineStartGracePolicy.decide(false, VideoView.STATE_BUFFERING, 21, 20, remaining)
        assertEquals(first, second)
        assertEquals(4_000L, LineStartGracePolicy.nextCheckMs(remaining))
    }
}
