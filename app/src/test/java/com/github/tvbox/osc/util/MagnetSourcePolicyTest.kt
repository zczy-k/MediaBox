package com.github.tvbox.osc.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MagnetSourcePolicy] 纯函数单测:证据记录/淘汰、磁力源门槛、占比判据、证据过期。
 *
 * <p>存储层([MagnetSourceMemory])依赖 KV/MD5,不在 JVM 单测覆盖范围
 * (与 [DetailNoListPolicyTest] / SourceHealthPolicy 同策略)。
 *
 * <p>⚠️ 占比判据是本类的核心,边界用例必须覆盖:
 * 80% 恰好命中、79.9% 不命中、条数下限 3 恰好命中、2 条即使 100% 也不命中。
 */
class MagnetSourcePolicyTest {

    private val day = 24L * 60 * 60 * 1000

    // ==================== isMagnetOnlyEvidence:占比 ====================

    @Test
    fun `恰好 80 百分比命中`() {
        // 4/5 = 80% 恰在门槛上(判据 >= MAGNET_RATIO)⇒ 命中
        assertTrue(MagnetSourcePolicy.isMagnetOnlyEvidence(magnetLines = 4, totalLines = 5))
    }

    @Test
    fun `低于 80 百分比不命中`() {
        // 3/4 = 75% < 80% ⇒ 不命中(即使条数够)
        assertFalse(MagnetSourcePolicy.isMagnetOnlyEvidence(magnetLines = 3, totalLines = 4))
    }

    @Test
    fun `全部是磁力命中`() {
        // 实测 avgood 就是这种:10 条线路全是磁力
        assertTrue(MagnetSourcePolicy.isMagnetOnlyEvidence(magnetLines = 10, totalLines = 10))
    }

    @Test
    fun `九磁力加一正常仍命中`() {
        // 9/10 = 90% >= 80% ⇒ 命中。
        // 这一例是刻意的:该源基本不给正常内容,"那条正常线路"很可能是偶发,
        // 不足以否定它是磁力源。
        assertTrue(MagnetSourcePolicy.isMagnetOnlyEvidence(magnetLines = 9, totalLines = 10))
    }

    // ==================== isMagnetOnlyEvidence:条数下限 ====================

    @Test
    fun `两条磁力即使百分之百也不命中`() {
        // 2/2 = 100% 但 magnetLines(2) < MIN_MAGNET_LINES(3) ⇒ 不命中。
        // 小样本误判防线:只给两条磁力的源,更可能"这部片恰好只有磁力"。
        assertFalse(MagnetSourcePolicy.isMagnetOnlyEvidence(magnetLines = 2, totalLines = 2))
    }

    @Test
    fun `恰好三条全磁力命中`() {
        // 3/3 = 100% 且 magnetLines(3) >= 3 ⇒ 命中(条数下限的边界)
        assertTrue(MagnetSourcePolicy.isMagnetOnlyEvidence(magnetLines = 3, totalLines = 3))
    }

    @Test
    fun `总数为零不命中`() {
        // 防除零:totalLines=0 时不得命中,也不得崩
        assertFalse(MagnetSourcePolicy.isMagnetOnlyEvidence(magnetLines = 0, totalLines = 0))
        assertFalse(MagnetSourcePolicy.isMagnetOnlyEvidence(magnetLines = 3, totalLines = 0))
    }

    @Test
    fun `无磁力不命中`() {
        // 正常源:一条磁力都没有 ⇒ 不该被贴磁力源标签
        assertFalse(MagnetSourcePolicy.isMagnetOnlyEvidence(magnetLines = 0, totalLines = 20))
    }

    // ==================== record ====================

    @Test
    fun `record 追加本次时间戳`() {
        val strikes = MagnetSourcePolicy.record(emptyList(), now = 1000L)
        assertEquals(listOf(1000L), strikes)
    }

    @Test
    fun `record 淘汰窗口外的旧证据`() {
        // now=31 天:1L 距 now 已超 30 天 TTL → 淘汰;now-day(30 天整)在窗口内保留
        val now = 31L * day
        val strikes = MagnetSourcePolicy.record(listOf(1L, now - day), now = now)
        assertEquals(listOf(now - day, now), strikes)
    }

    @Test
    fun `record 保留窗口边界内的证据`() {
        // t = now - TTL 恰在边界(判据 <= TTL 保留),且必须为正 ⇒ now 取 TTL+100
        val now = MagnetSourcePolicy.STRIKE_TTL_MS + 100
        val strikes = MagnetSourcePolicy.record(
            listOf(now - MagnetSourcePolicy.STRIKE_TTL_MS),
            now = now,
        )
        assertEquals(listOf(now - MagnetSourcePolicy.STRIKE_TTL_MS, now), strikes)
    }

    @Test
    fun `record 上限封顶只留最近 MAX_STRIKES 条`() {
        val now = 1000L
        val old = (1..8L).map { it } // 8 条历史(都在窗口内)
        val strikes = MagnetSourcePolicy.record(old, now = now)
        assertEquals(MagnetSourcePolicy.MAX_STRIKES, strikes.size)
        assertEquals((old + now).takeLast(MagnetSourcePolicy.MAX_STRIKES), strikes)
    }

    @Test
    fun `record 忽略非正时间戳`() {
        val strikes = MagnetSourcePolicy.record(listOf(0L, -5L), now = 1000L)
        assertEquals(listOf(1000L), strikes)
    }

    // ==================== isMagnetOnly ====================

    @Test
    fun `一票不触发磁力源`() {
        val now = 1000L
        assertFalse(MagnetSourcePolicy.isMagnetOnly(listOf(now - 1000), now = now))
    }

    @Test
    fun `窗口内两票触发磁力源`() {
        // now 必须大于 day,否则 now-day 为负、被 it>0 守卫过滤
        val now = 2L * day
        assertTrue(MagnetSourcePolicy.isMagnetOnly(listOf(now - day, now - 1), now = now))
    }

    @Test
    fun `窗口外旧票不参与判定`() {
        val now = 40L * day
        assertFalse(MagnetSourcePolicy.isMagnetOnly(listOf(1L, 2L), now = now))
        // 一票窗口外、一票窗口内 ⇒ 有效证据只有一票,不触发
        assertFalse(MagnetSourcePolicy.isMagnetOnly(listOf(1L, now - day), now = now))
    }

    @Test
    fun `空列表不触发磁力源`() {
        assertFalse(MagnetSourcePolicy.isMagnetOnly(emptyList(), now = 0L))
    }

    // ==================== hasNoFreshStrike ====================

    @Test
    fun `全部过期视为无有效证据`() {
        val now = 40L * day
        assertTrue(MagnetSourcePolicy.hasNoFreshStrike(listOf(1L, 2L), now = now))
        assertFalse(MagnetSourcePolicy.hasNoFreshStrike(listOf(1L, now - day), now = now))
        assertTrue(MagnetSourcePolicy.hasNoFreshStrike(emptyList(), now = now))
    }

    // ==================== 与 DetailNoListPolicy 的口径一致性 ====================

    @Test
    fun `门槛与 TTL 与索引型画像对齐`() {
        // 两份规则刻意取同款参数(改一处忘了另一处会让"画像"行为不一致)
        assertEquals(DetailNoListPolicy.STRIKES_TO_INDEX_LIKE, MagnetSourcePolicy.STRIKES_TO_MAGNET_ONLY)
        assertEquals(DetailNoListPolicy.STRIKE_TTL_MS, MagnetSourcePolicy.STRIKE_TTL_MS)
        assertEquals(DetailNoListPolicy.MAX_STRIKES, MagnetSourcePolicy.MAX_STRIKES)
    }
}
