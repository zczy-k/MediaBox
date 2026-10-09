package com.github.tvbox.osc.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DetailNoListPolicy] 纯函数单测:证据记录/淘汰、索引型门槛、证据过期。
 * 存储层([DetailNoListMemory])依赖 KV/MD5,不在 JVM 单测覆盖范围(与 SourceHealthPolicy 同策略)。
 */
class DetailNoListPolicyTest {

    private val day = 24L * 60 * 60 * 1000

    // ==================== record ====================

    @Test
    fun `record 追加本次时间戳`() {
        val strikes = DetailNoListPolicy.record(emptyList(), now = 1000L)
        assertEquals(listOf(1000L), strikes)
    }

    @Test
    fun `record 淘汰窗口外的旧证据`() {
        // now=31 天:1L 距 now 31 天-1ms > 30 天 TTL → 淘汰;now-day(30 天整)在窗口内保留
        val now = 31L * day
        val strikes = DetailNoListPolicy.record(listOf(1L, now - day), now = now)
        assertEquals(listOf(now - day, now), strikes)
    }

    @Test
    fun `record 保留窗口边界内的证据(now - t == TTL 不淘汰)`() {
        // t = now - TTL 恰在边界上(判据 <= TTL 保留),且必须为正 ⇒ now 取 TTL+100
        val now = DetailNoListPolicy.STRIKE_TTL_MS + 100
        val strikes = DetailNoListPolicy.record(listOf(now - DetailNoListPolicy.STRIKE_TTL_MS), now = now)
        assertEquals(listOf(now - DetailNoListPolicy.STRIKE_TTL_MS, now), strikes)
    }

    @Test
    fun `record 上限封顶只留最近 MAX_STRIKES 条`() {
        val now = 1000L
        val old = (1..8L).map { it } // 8 条历史(都在窗口内)
        val strikes = DetailNoListPolicy.record(old, now = now)
        assertEquals(DetailNoListPolicy.MAX_STRIKES, strikes.size)
        // 留下的是"历史 + 本次"的最后 MAX_STRIKES 条(本次也占名额)
        assertEquals((old + now).takeLast(DetailNoListPolicy.MAX_STRIKES), strikes)
    }

    @Test
    fun `record 忽略非正时间戳`() {
        val strikes = DetailNoListPolicy.record(listOf(0L, -5L), now = 1000L)
        assertEquals(listOf(1000L), strikes)
    }

    // ==================== isIndexLike ====================

    @Test
    fun `一票不触发索引型`() {
        val now = 1000L
        assertFalse(DetailNoListPolicy.isIndexLike(listOf(now - 1000), now = now))
    }

    @Test
    fun `窗口内两票触发索引型`() {
        // now 必须大于 day,否则 now-day 为负、被 it>0 守卫过滤(证据口径:非正时间戳无效)
        val now = 2L * day
        assertTrue(DetailNoListPolicy.isIndexLike(listOf(now - day, now - 1), now = now))
    }

    @Test
    fun `窗口外旧票不参与判定`() {
        val now = 40L * day
        // 两票都在 30 天窗口外 → 不触发
        assertFalse(DetailNoListPolicy.isIndexLike(listOf(1L, 2L), now = now))
        // 一票在窗口外、一票在窗口内 → 仍不触发(有效证据只有一票)
        assertFalse(DetailNoListPolicy.isIndexLike(listOf(1L, now - day), now = now))
    }

    @Test
    fun `空列表不触发索引型`() {
        assertFalse(DetailNoListPolicy.isIndexLike(emptyList(), now = 0L))
    }

    // ==================== hasNoFreshStrike ====================

    @Test
    fun `全部过期视为无有效证据`() {
        val now = 40L * day
        assertTrue(DetailNoListPolicy.hasNoFreshStrike(listOf(1L, 2L), now = now))
        assertFalse(DetailNoListPolicy.hasNoFreshStrike(listOf(1L, now - day), now = now))
        assertTrue(DetailNoListPolicy.hasNoFreshStrike(emptyList(), now = now))
    }
}
