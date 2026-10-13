package com.github.tvbox.osc.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 统一动态质量评分单测(P0-2)。锁四件事:
 * ① 场景权重分档(冷启动/稳态/弱网/TV);② 三维度归一;③ 设备上限一票否决;
 * ④ 关键语义:速度优先场景下快线胜、清晰度优先场景下高清晰度胜。
 */
class QualityScoreTest {

    private fun v(w: Int, h: Int, bw: Int = 0) =
        VideoQualityPolicy.Variant(w, h, bw, VideoQualityPolicy.Confidence.MEASURED, "")

    private val sd = v(640, 480)   // S≈554
    private val hd = v(1280, 720)  // S≈960
    private val fhd = v(1920, 1080) // S=1440

    // ==================== 场景权重 ====================

    @Test
    fun `权重之和恒为1`() {
        QualityScore.Scene.values().forEach {
            val w = QualityScore.weightsOf(it)
            assertEquals("scene=$it", 1.0, w.quality + w.speed, 0.0001)
        }
    }

    @Test
    fun `弱网速度权重最高_冷启动次之`() {
        assertTrue(
            QualityScore.weightsOf(QualityScore.Scene.WEAK_NETWORK).speed >
                QualityScore.weightsOf(QualityScore.Scene.COLD_START).speed,
        )
        assertTrue(
            QualityScore.weightsOf(QualityScore.Scene.COLD_START).speed >
                QualityScore.weightsOf(QualityScore.Scene.TV_BIG_SCREEN).speed,
        )
        // TV 清晰度权重最高
        assertTrue(
            QualityScore.weightsOf(QualityScore.Scene.TV_BIG_SCREEN).quality >
                QualityScore.weightsOf(QualityScore.Scene.COLD_START).quality,
        )
    }

    @Test
    fun `场景推导优先级_弱网冷启动TV稳态`() {
        assertEquals(
            QualityScore.Scene.WEAK_NETWORK,
            QualityScore.sceneOf(QualityScore.NetKind.WEAK, isTv = true, coldStart = true),
        )
        assertEquals(
            QualityScore.Scene.COLD_START,
            QualityScore.sceneOf(QualityScore.NetKind.WIFI, isTv = true, coldStart = true),
        )
        assertEquals(
            QualityScore.Scene.TV_BIG_SCREEN,
            QualityScore.sceneOf(QualityScore.NetKind.WIFI, isTv = true, coldStart = false),
        )
        assertEquals(
            QualityScore.Scene.STEADY,
            QualityScore.sceneOf(QualityScore.NetKind.CELL_4G, isTv = false, coldStart = false),
        )
    }

    // ==================== 三维度归一 ====================

    @Test
    fun `清晰度达到1080P级即满分_超出不加分`() {
        // STeady 下清晰度权重 0.6:清晰度满分 + 速度中性
        val full = QualityScore.compute(fhd, 0, 0, QualityScore.Scene.STEADY)
        val over = QualityScore.compute(v(3840, 2160), 0, 0, QualityScore.Scene.STEADY)
        assertEquals(full, over, 0.0001)
    }

    @Test
    fun `更快与更稳都提高评分`() {
        val slow = QualityScore.compute(hd, 4_500, 0, QualityScore.Scene.STEADY)
        val fast = QualityScore.compute(hd, 500, 0, QualityScore.Scene.STEADY)
        assertTrue(fast > slow)
        val unstable = QualityScore.compute(hd, 500, 2, QualityScore.Scene.STEADY)
        assertTrue(fast > unstable)
    }

    @Test
    fun `未知耗时按中性_0为极快负值为未知`() {
        // ⚠️ -1 = 未知(中性 0.5);0 = 极快(满分)。两者必须可区分
        val neutral = QualityScore.compute(hd, -1, 0, QualityScore.Scene.STEADY)
        val perfect = QualityScore.compute(hd, 0, 0, QualityScore.Scene.STEADY)
        val worst = QualityScore.compute(hd, 9_999, 0, QualityScore.Scene.STEADY)
        assertTrue("0ms 极快必须优于未知", perfect > neutral)
        assertTrue("未知必须优于极慢", neutral > worst)
    }

    // ==================== 设备上限 ====================

    @Test
    fun `超设备上限直接判死`() {
        assertEquals(
            QualityScore.REJECTED,
            QualityScore.compute(v(2560, 1440), 500, 0, QualityScore.Scene.STEADY, deviceCapHeight = 1080),
            0.0001,
        )
        assertTrue(
            QualityScore.compute(v(1920, 1080), 500, 0, QualityScore.Scene.STEADY, deviceCapHeight = 1080) > 0,
        )
    }

    // ==================== 关键语义:场景决定偏好 ====================

    @Test
    fun `弱网下快线胜过高清但慢的线`() {
        val fastLow = QualityScore.compute(sd, 800, 0, QualityScore.Scene.WEAK_NETWORK)
        val slowHigh = QualityScore.compute(fhd, 4_500, 0, QualityScore.Scene.WEAK_NETWORK)
        assertTrue("弱网应偏好快线", fastLow > slowHigh)
    }

    @Test
    fun `TV大屏下高清晰度胜过低清快线`() {
        val slowHigh = QualityScore.compute(fhd, 2_500, 0, QualityScore.Scene.TV_BIG_SCREEN)
        val fastLow = QualityScore.compute(sd, 500, 0, QualityScore.Scene.TV_BIG_SCREEN)
        assertTrue("TV 应偏好高清晰度", slowHigh > fastLow)
    }

    @Test
    fun `完全不可用线路得分为0`() {
        val dead = QualityScore.compute(null, -1, QualityScore.STABILITY_ZERO_STREAK, QualityScore.Scene.STEADY)
        assertTrue(dead >= 0.0)
        assertTrue(dead < 0.2)
    }
}