package com.github.tvbox.osc.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [SourceCompletenessPolicy] 纯函数单测:note 先验解析、档位边界、排序分序。 */
class SourceCompletenessPolicyTest {

    // ==================== priorCountFromNote ====================

    @Test
    fun `更新至N集可解析`() {
        assertEquals(30, SourceCompletenessPolicy.priorCountFromNote("更新至30集"))
        assertEquals(45, SourceCompletenessPolicy.priorCountFromNote(" HD 更新至 45 集"))
    }

    @Test
    fun `共N集与N集完结可解析`() {
        assertEquals(50, SourceCompletenessPolicy.priorCountFromNote("共50集"))
        assertEquals(24, SourceCompletenessPolicy.priorCountFromNote("24集全"))
        assertEquals(12, SourceCompletenessPolicy.priorCountFromNote("12集完结"))
    }

    @Test
    fun `第N集是单集序号不是总量,不解析`() {
        assertEquals(0, SourceCompletenessPolicy.priorCountFromNote("第3集"))
        assertEquals(0, SourceCompletenessPolicy.priorCountFromNote("EP05"))
    }

    @Test
    fun `无自述或乱写返回0`() {
        assertEquals(0, SourceCompletenessPolicy.priorCountFromNote(null))
        assertEquals(0, SourceCompletenessPolicy.priorCountFromNote("HD中字"))
        assertEquals(0, SourceCompletenessPolicy.priorCountFromNote("更新至集"))
    }

    // ==================== tier ====================

    private val A50 = 50

    @Test
    fun `权威未知一律不判`() {
        assertEquals(
            SourceCompletenessPolicy.Tier.UNKNOWN_AUTHORITY,
            SourceCompletenessPolicy.tier(30, 0),
        )
    }

    @Test
    fun `权威已知但先验未知为中性档`() {
        assertEquals(
            SourceCompletenessPolicy.Tier.UNKNOWN_SOURCE,
            SourceCompletenessPolicy.tier(0, A50),
        )
    }

    @Test
    fun `完整_轻微_备用三档边界`() {
        assertEquals(
            SourceCompletenessPolicy.Tier.COMPLETE,
            SourceCompletenessPolicy.tier(50, A50),
        )
        assertEquals(
            SourceCompletenessPolicy.Tier.COMPLETE,
            SourceCompletenessPolicy.tier(52, A50),
        )
        // 40/50 = 80% 恰在轻微线内;39/50 = 78% 落入备用
        assertEquals(SourceCompletenessPolicy.Tier.NEAR, SourceCompletenessPolicy.tier(40, A50))
        assertEquals(SourceCompletenessPolicy.Tier.BACKUP, SourceCompletenessPolicy.tier(39, A50))
        assertEquals(SourceCompletenessPolicy.Tier.BACKUP, SourceCompletenessPolicy.tier(1, A50))
    }

    // ==================== missingRange(第三批:面板缺集标注) ====================

    @Test
    fun `落后线路返回缺失区间`() {
        assertEquals(31 to 50, SourceCompletenessPolicy.missingRange(30, 50))
        assertEquals(49 to 50, SourceCompletenessPolicy.missingRange(48, 50))
    }

    @Test
    fun `完整或不可数或权威未知返回null`() {
        assertEquals(null, SourceCompletenessPolicy.missingRange(50, 50))
        assertEquals(null, SourceCompletenessPolicy.missingRange(55, 50))
        assertEquals(null, SourceCompletenessPolicy.missingRange(0, 50))
        assertEquals(null, SourceCompletenessPolicy.missingRange(30, 0))
    }

    // ==================== rankScore ====================

    @Test
    fun `排序分序 完整大于轻微大于未知大于备用`() {
        val complete = SourceCompletenessPolicy.rankScore(
            SourceCompletenessPolicy.Tier.COMPLETE, 50,
        )
        val near = SourceCompletenessPolicy.rankScore(SourceCompletenessPolicy.Tier.NEAR, 45)
        val unknown = SourceCompletenessPolicy.rankScore(
            SourceCompletenessPolicy.Tier.UNKNOWN_SOURCE, 0,
        )
        val backup = SourceCompletenessPolicy.rankScore(SourceCompletenessPolicy.Tier.BACKUP, 45)
        assertTrue(complete > near)
        assertTrue(near > unknown)
        assertTrue(unknown > backup)
    }

    @Test
    fun `同档内集数多者优先(更新越快权重越高)`() {
        val more = SourceCompletenessPolicy.rankScore(
            SourceCompletenessPolicy.Tier.COMPLETE, 55,
        )
        val less = SourceCompletenessPolicy.rankScore(
            SourceCompletenessPolicy.Tier.COMPLETE, 50,
        )
        assertTrue(more > less)
        val nearMore = SourceCompletenessPolicy.rankScore(SourceCompletenessPolicy.Tier.NEAR, 48)
        val nearLess = SourceCompletenessPolicy.rankScore(SourceCompletenessPolicy.Tier.NEAR, 40)
        assertTrue(nearMore > nearLess)
    }

    @Test
    fun `权威未知时所有源同分(排序保持原序不降权)`() {
        val scores = listOf(50, 3, 1).map {
            SourceCompletenessPolicy.rankScore(
                SourceCompletenessPolicy.tier(it, 0), it,
            )
        }
        assertEquals(scores.distinct(), listOf(scores[0]))
    }
}
