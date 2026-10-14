package com.github.tvbox.osc.ui.activity

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 线路标签口径单测。
 *
 * <p>锁两条:①没测到必须返回空串(宁缺勿假,不能编一个清晰度出来);
 * ②实测高度**向下取档**,绝不把 1080 说成 1440。
 */
class LineLabelPolicyTest {

    @Test
    fun unmeasuredReturnsEmptySoCallerShowsIndexOnly() {
        assertEquals("", LineLabelPolicy.qualitySuffix(0))
        assertEquals("", LineLabelPolicy.qualitySuffix(-1))
    }

    @Test
    fun standardHeightsMapToTheirTier() {
        assertEquals("2160P", LineLabelPolicy.qualitySuffix(2160))
        assertEquals("1440P", LineLabelPolicy.qualitySuffix(1440))
        assertEquals("1080P", LineLabelPolicy.qualitySuffix(1080))
        assertEquals("720P", LineLabelPolicy.qualitySuffix(720))
        assertEquals("480P", LineLabelPolicy.qualitySuffix(480))
    }

    @Test
    fun nonStandardHeightsRoundDownAndNeverOverstate() {
        // 1088 是 1080 档(不能报 1440P),1441 是 1440 档,719 只能算 576 档;
        // 1079 不足 1080 ⇒ 只能报 720 档(2026-10-10 修正:原期望 "1080P" 与"向下取档"自相矛盾)
        assertEquals("1080P", LineLabelPolicy.qualitySuffix(1088))
        assertEquals("1440P", LineLabelPolicy.qualitySuffix(1441))
        assertEquals("576P", LineLabelPolicy.qualitySuffix(719))
        assertEquals("720P", LineLabelPolicy.qualitySuffix(1079))
    }

    @Test
    fun belowLowestTierReportsActualHeight() {
        assertEquals("240P", LineLabelPolicy.qualitySuffix(240))
        assertEquals("180P", LineLabelPolicy.qualitySuffix(180))
    }

    // ==================== healthSuffix(2026-10-14 追加式) ====================

    @Test
    fun healthSuffix_emptyWhenNoData() {
        // 没数据(未失败、未测速、未成功) ⇒ 空串,不编
        assertEquals("", LineLabelPolicy.healthSuffix(failStreak = 0, resolveMs = 0L, playedAt = 0L))
    }

    @Test
    fun healthSuffix_unstableWinsOverOthers() {
        // 连续失败达可疑门槛(2) ⇒ "不稳定",优先级高于速度
        assertEquals(
            LineLabelPolicy.UNSTABLE,
            LineLabelPolicy.healthSuffix(failStreak = 2, resolveMs = 9000L, playedAt = 123L),
        )
    }

    @Test
    fun healthSuffix_slowWhenNoFailures() {
        // 无失败但取流 ≥5s ⇒ "起播慢"
        assertEquals(
            LineLabelPolicy.SLOW,
            LineLabelPolicy.healthSuffix(failStreak = 0, resolveMs = 5000L, playedAt = 123L),
        )
        // 4999ms 未达门槛 ⇒ 落到"稳定"(已成功过)
        assertEquals(
            LineLabelPolicy.STABLE,
            LineLabelPolicy.healthSuffix(failStreak = 0, resolveMs = 4999L, playedAt = 123L),
        )
    }

    @Test
    fun healthSuffix_stableWhenPlayedAndHealthy() {
        assertEquals(
            LineLabelPolicy.STABLE,
            LineLabelPolicy.healthSuffix(failStreak = 0, resolveMs = 800L, playedAt = 123L),
        )
    }

    @Test
    fun healthSuffix_failOnceStillStableIfPlayed() {
        // 单次失败(failStreak=1 < 门槛 2)不算"不稳定"
        assertEquals(
            LineLabelPolicy.STABLE,
            LineLabelPolicy.healthSuffix(failStreak = 1, resolveMs = 500L, playedAt = 123L),
        )
    }
}
