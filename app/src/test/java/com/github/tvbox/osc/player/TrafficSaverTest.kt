package com.github.tvbox.osc.player

import com.github.tvbox.osc.player.DeviceCapability.QualityMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「省流画质」档的迁移与语义锁(2026-10-14 重写)。
 *
 * <p>旧版这里锁的是"流量节省开启 ⇒ 生效档位归一到自动档"。那把独立开关先被并成第四档、
 * 又在三档定案时与「速度优先」合并成同一档(ordinal 2)—— 因为两个独立开关有四种组合,
 * 其中"流量节省 + 画质优先"用户点了得不到他期待的结果,只剩一条轴才解释得清。
 *
 * <p>现在必须钉住三件事:
 * <ol>
 *   <li>老存量的 `traffic_saver=true` 迁移到省流档 [QualityMode.SPEED_FIRST],不会悄悄变成别的档;
 *       设置页每次写档位都会把那个旧键清成 false(见 PlaySettingsPage.onSelect),
 *       所以迁移不会把用户的新选择永久按住;</li>
 *   <li>省流档 = **允许扫描填实测记忆** + **禁止一切向上切换**:
 *       拒绝向上由 DeviceCapability.trafficSaverOn()(现在是档位的派生视图)传给 QualityGovernor;</li>
 *   <li>三档在"为画质付多少代价"上严格单调,防止后人把某档调成和另一档重复。</li>
 * </ol>
 */
class TrafficSaverTest {

    @Test
    fun `旧开关存量真值迁移成省流档`() {
        QualityMode.values().forEach { stored ->
            assertEquals(
                "traffic_saver=true 时应生效省流档(stored=$stored)",
                QualityMode.SPEED_FIRST,
                QualityMode.resolveMode(stored, legacySaverOn = true),
            )
        }
    }

    @Test
    fun `未存旧开关时原样保留用户档位`() {
        QualityMode.values().forEach { stored ->
            assertEquals(stored, QualityMode.resolveMode(stored, legacySaverOn = false))
        }
    }

    @Test
    fun `省流档不主动做任何画质动作`() {
        val saver = QualityMode.SPEED_FIRST
        assertFalse("省流档不该后台扫描", saver.shouldProbeOnFirstWatch)
        assertFalse("省流档绝不能拖首帧", saver.probeBlocksStart)
        assertEquals(0L, saver.probeBudgetMs)
        assertEquals(0, saver.probeLines)
    }

    @Test
    fun `三档代价严格单调`() {
        // 画质优先(等首帧 + 扫 + 可升档) > 自动(不等 + 扫 + 可升档) > 省流(什么都不主动做)
        assertTrue(QualityMode.QUALITY_FIRST.probeBlocksStart)
        assertFalse(QualityMode.AUTO.probeBlocksStart)
        assertFalse(QualityMode.SPEED_FIRST.probeBlocksStart)
        assertTrue(QualityMode.QUALITY_FIRST.shouldProbeOnFirstWatch)
        assertTrue(QualityMode.AUTO.shouldProbeOnFirstWatch)
        assertFalse(QualityMode.SPEED_FIRST.shouldProbeOnFirstWatch)
    }
}
