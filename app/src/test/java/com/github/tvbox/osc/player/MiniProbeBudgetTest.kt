package com.github.tvbox.osc.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 迷你只读通道(P1)预算闸的纯逻辑测试。
 *
 * <p>这些闸的意义是"**默认不发请求**":在实测源超时率 75~83% 的环境里,
 * 任何一条闸失效都等于在用户看片时平白多发网络请求。所以每条闸都要有反例测试钉住。
 */
class MiniProbeBudgetTest {

    // ---------- canStart:整体准入 ----------

    @Test
    fun canStart_allowsWhenNotProbedAndNoTrafficSaver() {
        assertTrue(
            MiniProbeBudget.canStart(
                trafficSaver = false,
                titleAlreadyProbed = false,
            )
        )
    }

    @Test
    fun canStart_rejectsWhenTrafficSaverOn() {
        assertFalse(
            MiniProbeBudget.canStart(
                trafficSaver = true,
                titleAlreadyProbed = false,
            )
        )
    }

    @Test
    fun canStart_rejectsWhenTitleAlreadyProbed() {
        assertFalse(
            MiniProbeBudget.canStart(
                trafficSaver = false,
                titleAlreadyProbed = true,
            )
        )
    }

    /** 两个否决项同时成立也必须拒绝(不能因为"只查了一个"而漏放) */
    @Test
    fun canStart_rejectsWhenBothReasonsApply() {
        assertFalse(
            MiniProbeBudget.canStart(
                trafficSaver = true,
                titleAlreadyProbed = true,
            )
        )
    }

    // ---------- hasWindowQuota:窗口额度 ----------

    @Test
    fun windowQuota_allowsBelowCap() {
        assertTrue(MiniProbeBudget.hasWindowQuota(0))
        assertTrue(MiniProbeBudget.hasWindowQuota(MiniProbeBudget.MAX_SOURCES_PER_WINDOW - 1))
    }

    @Test
    fun windowQuota_rejectsAtCapAndBeyond() {
        assertFalse(MiniProbeBudget.hasWindowQuota(MiniProbeBudget.MAX_SOURCES_PER_WINDOW))
        assertFalse(MiniProbeBudget.hasWindowQuota(MiniProbeBudget.MAX_SOURCES_PER_WINDOW + 10))
    }

    // ---------- inNegativeCooldown:失败冷却 ----------

    @Test
    fun cooldown_falseWhenNeverFailed() {
        assertFalse(MiniProbeBudget.inNegativeCooldown(0L, 1_000_000L))
    }

    @Test
    fun cooldown_trueWithinWindow() {
        val failAt = 1_000_000L
        assertTrue(MiniProbeBudget.inNegativeCooldown(failAt, failAt + 1L))
        assertTrue(
            MiniProbeBudget.inNegativeCooldown(
                failAt, failAt + MiniProbeBudget.NEGATIVE_COOLDOWN_MS - 1L
            )
        )
    }

    @Test
    fun cooldown_falseOnceExpired() {
        val failAt = 1_000_000L
        assertFalse(
            MiniProbeBudget.inNegativeCooldown(
                failAt, failAt + MiniProbeBudget.NEGATIVE_COOLDOWN_MS
            )
        )
    }

    /** 时钟回拨(用户改时间/时区)不能让源被**永久**关在冷却里 */
    @Test
    fun cooldown_falseWhenClockWentBackwards() {
        val failAt = 2_000_000L
        assertFalse(MiniProbeBudget.inNegativeCooldown(failAt, failAt - 60_000L))
    }

    /** 冷却时长本身:半小时量级(过长会让好源也被长期跳过,过短等于没有闸) */
    @Test
    fun cooldownLength_isHalfAnHour() {
        assertEquals(30L * 60 * 1000, MiniProbeBudget.NEGATIVE_COOLDOWN_MS)
    }
}
