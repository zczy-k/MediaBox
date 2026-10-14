package com.github.tvbox.osc.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 源级评分单测。锁四件事:
 * ① 无数据→中性(不惩罚新源);② 属性分合成;③ 有线路时才混合;④ 健康惩罚与上限优先聚合。
 */
class SourceQualityScoreTest {

    // ==================== attributeScore ====================

    @Test
    fun `全无数据时属性分为中性`() {
        assertEquals(SourceQualityScore.NEUTRAL, SourceQualityScore.attributeScore(-1, -1), 0.0001)
    }

    @Test
    fun `表现分与完整度分各占一半`() {
        // 表现 100(归一 100)、完整度无 → 中性 60 ⇒ 0.5*100 + 0.5*60 = 80
        assertEquals(80.0, SourceQualityScore.attributeScore(100, -1), 0.0001)
        // 表现无(60)、完整度 3000(归一 100) ⇒ 0.5*60 + 0.5*100 = 80
        assertEquals(80.0, SourceQualityScore.attributeScore(-1, 3000), 0.0001)
    }

    @Test
    fun `完整度按3000归一_未知1500得50`() {
        // 表现无(60)、完整度 1500 ⇒ 0.5*60 + 0.5*50 = 55
        assertEquals(55.0, SourceQualityScore.attributeScore(-1, 1500), 0.0001)
    }

    @Test
    fun `表现分越界视为无记录`() {
        // weightScore=200(非法) ⇒ 按中性 60;完整度无 ⇒ 60
        assertEquals(SourceQualityScore.NEUTRAL, SourceQualityScore.attributeScore(200, -1), 0.0001)
        assertEquals(SourceQualityScore.NEUTRAL, SourceQualityScore.attributeScore(-5, -1), 0.0001)
    }

    // ==================== lineAggregate ====================

    @Test
    fun `线路聚合上限优先`() {
        // max=0.8 avg=0.65 ⇒ (0.6*0.8 + 0.4*0.65)*100 = 74
        assertEquals(74.0, SourceQualityScore.lineAggregate(listOf(0.8, 0.5))!!, 0.0001)
    }

    @Test
    fun `空或无有效线路返回null`() {
        assertNull(SourceQualityScore.lineAggregate(emptyList()))
        assertNull(SourceQualityScore.lineAggregate(listOf(QualityScore.REJECTED)))
    }

    @Test
    fun `REJECTED被排除但0分线路保留`() {
        // REJECTED(-1) 排除;0.5 保留 ⇒ (0.6*0.5+0.4*0.5)*100 = 50
        assertEquals(50.0, SourceQualityScore.lineAggregate(listOf(QualityScore.REJECTED, 0.5))!!, 0.0001)
        // 0 分(不可用)是合法值,保留
        assertEquals(0.0, SourceQualityScore.lineAggregate(listOf(0.0))!!, 0.0001)
    }

    // ==================== score ====================

    @Test
    fun `全无信号时为中性`() {
        assertEquals(SourceQualityScore.NEUTRAL, SourceQualityScore.score(SourceQualityScore.Signals()), 0.0001)
    }

    @Test
    fun `有线路线索时与属性分对半混合`() {
        // attr=60(无记录), line=74 ⇒ 0.5*74 + 0.5*60 = 67
        val s = SourceQualityScore.Signals(
            weightScore = -1,
            completenessRank = -1,
            lineScores = listOf(0.8, 0.5),
        )
        assertEquals(67.0, SourceQualityScore.score(s), 0.0001)
    }

    @Test
    fun `无线路线索时纯用属性分`() {
        val s = SourceQualityScore.Signals(weightScore = 100, completenessRank = 3000)
        // attr = 0.5*100 + 0.5*100 = 100
        assertEquals(100.0, SourceQualityScore.score(s), 0.0001)
    }

    @Test
    fun `封禁直接0分`() {
        val s = SourceQualityScore.Signals(weightScore = 100, completenessRank = 3000, blocked = true)
        assertEquals(0.0, SourceQualityScore.score(s), 0.0001)
    }

    @Test
    fun `降权打对折`() {
        val s = SourceQualityScore.Signals(weightScore = -1, completenessRank = -1, penalized = true)
        // raw=60 ⇒ 60*0.5 = 30
        assertEquals(30.0, SourceQualityScore.score(s), 0.0001)
    }

    @Test
    fun `结果裁剪到0至100`() {
        val high = SourceQualityScore.Signals(weightScore = 100, completenessRank = 3000)
        assertTrue(SourceQualityScore.score(high) <= 100.0)
        val low = SourceQualityScore.Signals(weightScore = 0, completenessRank = 0)
        assertTrue(SourceQualityScore.score(low) >= 0.0)
    }

    @Test
    fun `高表现源优于低表现源`() {
        val good = SourceQualityScore.Signals(weightScore = 90, completenessRank = 3000)
        val bad = SourceQualityScore.Signals(weightScore = 30, completenessRank = -1)
        assertTrue(SourceQualityScore.score(good) > SourceQualityScore.score(bad))
    }
}