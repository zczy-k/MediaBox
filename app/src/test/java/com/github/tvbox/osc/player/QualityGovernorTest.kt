package com.github.tvbox.osc.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自适应画质调度器(2026-10-10 第一批)门控逻辑:
 * 档位阶梯、升档门槛(画质档位/稳定期/额度/会话锁)、回滚窗口、升档选线。
 */
class QualityGovernorTest {

    // ==================== tierOf ====================

    @Test
    fun `档位阶梯边界`() {
        assertEquals(-1, QualityGovernor.tierOf(0))
        assertEquals(0, QualityGovernor.tierOf(480))
        assertEquals(1, QualityGovernor.tierOf(481))
        assertEquals(1, QualityGovernor.tierOf(720))
        assertEquals(2, QualityGovernor.tierOf(721))
        assertEquals(2, QualityGovernor.tierOf(1080))
        assertEquals(3, QualityGovernor.tierOf(1081))
        assertEquals(3, QualityGovernor.tierOf(1440))
        assertEquals(4, QualityGovernor.tierOf(1441))
        assertEquals(4, QualityGovernor.tierOf(2160))
    }

    // ==================== canUpgrade ====================

    @Test
    fun `SPEED_FIRST 永不升档`() {
        assertFalse(
            QualityGovernor.canUpgrade(DeviceCapability.QualityMode.SPEED_FIRST, 480, 1080, 120_000L, 0, -1),
        )
    }

    @Test
    fun `AUTO 只在档差大于等于2时升`() {
        // 480→720 差 1 档:AUTO 不升
        assertFalse(QualityGovernor.canUpgrade(DeviceCapability.QualityMode.AUTO, 480, 720, 120_000L, 0, -1))
        // 480→1080 差 2 档:升
        assertTrue(QualityGovernor.canUpgrade(DeviceCapability.QualityMode.AUTO, 480, 1080, 120_000L, 0, -1))
        // QUALITY_FIRST 差 1 档就升
        assertTrue(QualityGovernor.canUpgrade(DeviceCapability.QualityMode.QUALITY_FIRST, 480, 720, 120_000L, 0, -1))
    }

    @Test
    fun `稳定期未满不升`() {
        assertFalse(QualityGovernor.canUpgrade(DeviceCapability.QualityMode.QUALITY_FIRST, 480, 1080, QualityGovernor.MIN_WATCH_MS - 1, 0, -1))
        // -1 = 起播标记未到达,同样不升
        assertFalse(QualityGovernor.canUpgrade(DeviceCapability.QualityMode.QUALITY_FIRST, 480, 1080, -1L, 0, -1))
        assertTrue(QualityGovernor.canUpgrade(DeviceCapability.QualityMode.QUALITY_FIRST, 480, 1080, QualityGovernor.MIN_WATCH_MS, 0, -1))
    }

    @Test
    fun `每集额度用完不升`() {
        assertFalse(
            QualityGovernor.canUpgrade(DeviceCapability.QualityMode.QUALITY_FIRST, 480, 1080, 120_000L, QualityGovernor.MAX_UPGRADES_PER_EPISODE, -1),
        )
        assertTrue(QualityGovernor.canUpgrade(DeviceCapability.QualityMode.QUALITY_FIRST, 480, 1080, 120_000L, 1, -1))
    }

    @Test
    fun `会话锁高于目标档时不升`() {
        // 回滚后锁在 720 档(1):1080(2) 被拒
        assertFalse(QualityGovernor.canUpgrade(DeviceCapability.QualityMode.QUALITY_FIRST, 480, 1080, 120_000L, 0, 1))
        // 未锁(-1)不拦
        assertTrue(QualityGovernor.canUpgrade(DeviceCapability.QualityMode.QUALITY_FIRST, 480, 1080, 120_000L, 0, -1))
    }

    @Test
    fun `目标不高于当前_或高度未知_不升`() {
        assertFalse(QualityGovernor.canUpgrade(DeviceCapability.QualityMode.QUALITY_FIRST, 1080, 1080, 120_000L, 0, -1))
        assertFalse(QualityGovernor.canUpgrade(DeviceCapability.QualityMode.QUALITY_FIRST, 1080, 720, 120_000L, 0, -1))
        assertFalse(QualityGovernor.canUpgrade(DeviceCapability.QualityMode.QUALITY_FIRST, 0, 1080, 120_000L, 0, -1))
        assertFalse(QualityGovernor.canUpgrade(DeviceCapability.QualityMode.QUALITY_FIRST, 1080, 0, 120_000L, 0, -1))
    }

    // ==================== 流量节省(见《选线机制设计》附录 F) ====================

    @Test
    fun `流量节省开启时任何模式都不升档`() {
        // 它是最外层门:先于档位/档差/稳定期/额度/会话锁 —— 开启即拒绝一切向上动作
        DeviceCapability.QualityMode.values().forEach { mode ->
            assertFalse(
                QualityGovernor.canUpgrade(mode, 480, 2160, 600_000L, 0, -1, trafficSaver = true),
            )
        }
    }

    @Test
    fun `流量节省关闭时门控行为与旧版一致`() {
        assertTrue(
            QualityGovernor.canUpgrade(
                DeviceCapability.QualityMode.QUALITY_FIRST, 480, 1080, 120_000L, 0, -1,
                trafficSaver = false,
            ),
        )
        assertFalse(
            QualityGovernor.canUpgrade(
                DeviceCapability.QualityMode.SPEED_FIRST, 480, 1080, 120_000L, 0, -1,
                trafficSaver = false,
            ),
        )
    }

    @Test
    fun `省略流量节省参数时按关闭处理_既有调用点行为不被静默改变`() {
        assertTrue(QualityGovernor.canUpgrade(DeviceCapability.QualityMode.QUALITY_FIRST, 480, 1080, 120_000L, 0, -1))
    }

    // ==================== inRollbackWindow ====================

    @Test
    fun `回滚窗口判定`() {
        // 无升档在途
        assertFalse(QualityGovernor.inRollbackWindow(1_000_000L, 0L))
        val at = 1_000_000L
        assertTrue(QualityGovernor.inRollbackWindow(at + 1, at))
        assertTrue(QualityGovernor.inRollbackWindow(at + QualityGovernor.ROLLBACK_WINDOW_MS, at))
        assertFalse(QualityGovernor.inRollbackWindow(at + QualityGovernor.ROLLBACK_WINDOW_MS + 1, at))
    }

    // ==================== pickUpgrade(LineQualitySelector) ====================

    private fun v(flag: String, height: Int) =
        VideoQualityPolicy.Variant(width = height * 16, height = height, bitrate = 0, flag = flag)

    @Test
    fun `升档选最近一档_排除已试与当前`() {
        val measured = listOf(v("a", 480), v("b", 720), v("c", 1080), v("d", 2160))
        // 当前 480:最近的高档是 720(单步走),不是 2160
        assertEquals("b", LineQualitySelector.pickUpgrade(measured, "a", 480, emptySet()))
        // b 已试(比如升过又回滚):跳到 1080
        assertEquals("c", LineQualitySelector.pickUpgrade(measured, "a", 480, setOf("b")))
        // 全部试过:null
        assertNull(LineQualitySelector.pickUpgrade(measured, "a", 480, setOf("b", "c", "d")))
        // 当前高度未知:null
        assertNull(LineQualitySelector.pickUpgrade(measured, "a", 0, emptySet()))
    }

    @Test
    fun `没有更高档时返回null`() {
        val measured = listOf(v("a", 1080), v("b", 720))
        assertNull(LineQualitySelector.pickUpgrade(measured, "a", 1080, emptySet()))
    }
}
