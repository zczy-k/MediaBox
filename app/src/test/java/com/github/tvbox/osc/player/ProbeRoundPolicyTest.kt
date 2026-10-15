package com.github.tvbox.osc.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 重复探测的收口判据(2026-10-15 用户定案)。
 *
 * 判松了 = 每次进详情页都把那几条"原理上测不出"的线路重探一遍,烧的是用户带宽;
 * 判紧了 = 一条本来能测的线被永久排除(真机已经因为 30 分钟冷却踩过一次)。
 */
class ProbeRoundPolicyTest {

    /** 他要的口径:探一遍之后,最多再重复两次 ⇒ 一共三遍 */
    @Test
    fun `三遍之后确认探不到`() {
        assertFalse(ProbeRoundPolicy.shouldStop(0))
        assertFalse(ProbeRoundPolicy.shouldStop(1))
        assertFalse(ProbeRoundPolicy.shouldStop(2))
        assertTrue(ProbeRoundPolicy.shouldStop(ProbeRoundPolicy.MAX_ROUNDS))
        assertTrue(ProbeRoundPolicy.shouldStop(99))
        assertEquals(3, ProbeRoundPolicy.MAX_ROUNDS)
    }

    /** 测到任何东西就清零:能探的源不该被"别几条探不出"连累 */
    @Test
    fun `测到新东西就把轮次清零`() {
        assertEquals(0, ProbeRoundPolicy.nextRounds(2, measuredThisRound = 1))
        assertEquals(0, ProbeRoundPolicy.nextRounds(5, measuredThisRound = 3))
    }

    @Test
    fun `一条都没测到才算一轮`() {
        assertEquals(1, ProbeRoundPolicy.nextRounds(0, measuredThisRound = 0))
        assertEquals(3, ProbeRoundPolicy.nextRounds(2, measuredThisRound = 0))
    }

    /** 外部存储里的坏数据(负数)绝不能把探测永久锁死 */
    @Test
    fun `脏的轮数从新的一遍重新算`() {
        assertEquals(1, ProbeRoundPolicy.nextRounds(-7, measuredThisRound = 0))
        assertFalse(ProbeRoundPolicy.shouldStop(-7))
    }

    /** 结论会过期:7 天 —— 站点会修 CDN、地址会变,永久标签是另一种误伤 */
    @Test
    fun `结论有效期短于实测画质记忆`() {
        assertEquals(7L * 24 * 60 * 60 * 1000, ProbeRoundPolicy.TTL_MS)
        // 画质记忆本身 TTL 30 天:这条"探不到"的结论必须比"测到了"的结论先失效
        assertTrue(ProbeRoundPolicy.TTL_MS < 30L * 24 * 60 * 60 * 1000)
    }
}
