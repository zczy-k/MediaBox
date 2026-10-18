package com.github.tvbox.osc.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「这个源答过话、但这部片它没有」这条结论的判据。
 *
 * 判错的两边都坏:该短路时不短路 = 重进同一部片把几百个源各烧一次超时额度;
 * 不该短路时短路 = 把一个只是"这次没答话/这次没搜到"的源长期挡在换源候选之外。
 */
class NoHitSourcePolicyTest {

    /** TTL 必须与命中缓存同尺度:两边是同一件事的两种结果,不能一边还有效一边已作废 */
    @Test
    fun `有效期与命中缓存同尺度`() {
        assertEquals(24L * 60 * 60 * 1000, NoHitSourcePolicy.TTL_MS)
    }

    @Test
    fun `过期与时间倒挂都算没记过`() {
        val now = 1_000_000_000_000L
        assertTrue(NoHitSourcePolicy.isFresh(now - 1000L, now))
        assertTrue(NoHitSourcePolicy.isFresh(now - NoHitSourcePolicy.TTL_MS, now))
        assertFalse("刚好超一天要作废", NoHitSourcePolicy.isFresh(now - NoHitSourcePolicy.TTL_MS - 1L, now))
        assertFalse("没记过(0)", NoHitSourcePolicy.isFresh(0L, now))
        assertFalse("脏数据(负)", NoHitSourcePolicy.isFresh(-5L, now))
        // 用户改过系统时钟 ⇒ 未来时刻的证据不可信
        assertFalse("时刻在未来", NoHitSourcePolicy.isFresh(now + 10_000L, now))
    }

    /** 用户主动那一轮绝不短路 —— 他的意图就是"还要继续找" */
    @Test
    fun `手动换源不吃短路`() {
        assertTrue(NoHitSourcePolicy.allowShortCircuit(userInitiated = false, fullPoolPass = false))
        assertFalse(NoHitSourcePolicy.allowShortCircuit(userInitiated = true, fullPoolPass = false))
        assertFalse(NoHitSourcePolicy.allowShortCircuit(userInitiated = false, fullPoolPass = true))
        assertFalse(NoHitSourcePolicy.allowShortCircuit(userInitiated = true, fullPoolPass = true))
    }

    @Test
    fun `并集去重且新记的排在后面`() {
        val merged = NoHitSourcePolicy.merge(listOf("a", "b"), listOf("b", "c", "", "  "))
        assertEquals(listOf("a", "b", "c"), merged)
    }

    /**
     * 超上限丢**最旧**那端:刚记下的那条恰恰最该挡住重问,
     * 丢最新的等于把"这次确实问过"的证据先扔掉。
     */
    @Test
    fun `超上限时丢最旧的源`() {
        val existing = (1..NoHitSourcePolicy.MAX_SOURCES_PER_TITLE).map { "old$it" }
        val merged = NoHitSourcePolicy.merge(existing, listOf("brand-new"))
        assertEquals(NoHitSourcePolicy.MAX_SOURCES_PER_TITLE, merged.size)
        assertTrue("最新的必须在名单里", merged.contains("brand-new"))
        assertFalse("最旧的被挤掉", merged.contains("old1"))
    }

    @Test
    fun `空名单不假装有过结论`() {
        assertEquals(emptyList<String>(), NoHitSourcePolicy.merge(emptyList(), emptyList()))
    }

    // ==================== shouldBypassShortCircuit(2026-10-18) ====================

    /**
     * 跳过比例过大时整轮放弃短路。真机《怦然心动》:源池 317、已知零命中 259 =
     * 只问 58 个(18%),而那是热门片 —— 短路把机会先掐掉了。
     * 它否定的已不是"重复劳动",而是"这部片到底有没有"这个结论的可信度。
     */
    @Test
    fun `跳过比例过高时绕过短路`() {
        // 真机现场:259/317 = 82%
        assertTrue(NoHitSourcePolicy.shouldBypassShortCircuit(259, 317))
        // 恰好在阈值上(70%)
        assertTrue(NoHitSourcePolicy.shouldBypassShortCircuit(70, 100))
    }

    @Test
    fun `跳过比例正常时不绕过`() {
        // 60% 仍属"省掉大部分重复"的合理区间
        assertFalse(NoHitSourcePolicy.shouldBypassShortCircuit(60, 100))
        assertFalse(NoHitSourcePolicy.shouldBypassShortCircuit(0, 317))
    }

    @Test
    fun `阈值与边界`() {
        assertEquals(70, NoHitSourcePolicy.BYPASS_RATIO_PERCENT)
        // 空池/负数一律不绕过(没有"跳过太多"可言,也避免除零)
        assertFalse(NoHitSourcePolicy.shouldBypassShortCircuit(0, 0))
        assertFalse(NoHitSourcePolicy.shouldBypassShortCircuit(5, 0))
        assertFalse(NoHitSourcePolicy.shouldBypassShortCircuit(-1, 100))
        // 全部跳过 ⇒ 必然绕过
        assertTrue(NoHitSourcePolicy.shouldBypassShortCircuit(100, 100))
    }

    @Test
    fun `小订阅不被同一阈值误伤`() {
        // 判据是**比例**而非绝对数:池子本来只有 30 个源时,
        // "跳过 21 个"同样算比例过高(该重问),而"跳过 10 个"不该绕
        assertTrue(NoHitSourcePolicy.shouldBypassShortCircuit(21, 30))
        assertFalse(NoHitSourcePolicy.shouldBypassShortCircuit(10, 30))
    }
}
