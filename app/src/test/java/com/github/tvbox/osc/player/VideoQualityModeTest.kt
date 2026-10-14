package com.github.tvbox.osc.player

import com.github.tvbox.osc.player.DeviceCapability.QualityMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「画质策略」三档的口径锁。
 *
 * <p>三档 = 画质优先 / 自动画质 / 省流画质。2026-10-14 定案封顶三档:
 * 曾经有过第四档(以及独立的「流量节省」开关),但"扫了不切"和"什么都不做"对用户是**同一个可观察
 * 行为**(都不换线),不该占两个档位 —— 档位要按用户看得见的差别切,不按内部账目切。
 *
 * <p>这里锁死的是**ordinal 即 KV 协议值**(0=画质优先 1=自动 2=省流),换序或再加档都会让老用户
 * 读到脏档位;要动必须先动这里。
 */
class VideoQualityModeTest {

    @Test
    fun ordinalIsTheKvProtocol() {
        assertEquals(0, DeviceCapability.QualityMode.QUALITY_FIRST.ordinal)
        assertEquals(1, DeviceCapability.QualityMode.AUTO.ordinal)
        // 省流画质沿用历史上"速度优先"的存储位 2 —— 两档合并后老用户零迁移
        assertEquals(2, DeviceCapability.QualityMode.SPEED_FIRST.ordinal)
    }

    @Test
    fun threeModesOnly_noFourthModeSneaksBackIn() {
        // 三档封顶:再加一档必须先回答"用户能看见的差别是什么",看不见就别加
        assertEquals(3, DeviceCapability.QualityMode.values().size)
    }

    @Test
    fun defaultIsAuto_matchingKvKeySpecRegistration() {
        // 与 KVKeySpec 里 register(VIDEO_QUALITY_MODE, 1) 的默认值一致
        assertEquals(1, DeviceCapability.QualityMode.AUTO.ordinal)
    }

    @Test
    fun onlyQualityFirstBlocksStartForProbing() {
        // 2026-10-14 定案:自动档/省流档都不为画质等首帧(探测后置到起播后的有界扫描)
        assertTrue(DeviceCapability.QualityMode.QUALITY_FIRST.probeBlocksStart)
        DeviceCapability.QualityMode.values()
            .filter { it != DeviceCapability.QualityMode.QUALITY_FIRST }
            .forEach { assertFalse("$it 不该拖首帧", it.probeBlocksStart) }
    }

    @Test
    fun speedFirstDoesNothingExtraForQuality() {
        val saver = DeviceCapability.QualityMode.SPEED_FIRST
        // 省流档:不扫描、不等首帧、不换线 —— "不主动做任何画质动作"就是它对用户的定义
        assertFalse(saver.shouldProbeOnFirstWatch)
        assertFalse(saver.probeBlocksStart)
        assertEquals(0L, saver.probeBudgetMs)
        assertEquals(0, saver.probeLines)
    }

    @Test
    fun qualityFirstPaysMoreThanAuto() {
        val qf = DeviceCapability.QualityMode.QUALITY_FIRST
        val auto = DeviceCapability.QualityMode.AUTO
        assertTrue(qf.shouldProbeOnFirstWatch)
        assertTrue(qf.probeBudgetMs > auto.probeBudgetMs)
        assertTrue(qf.probeLines > auto.probeLines)
    }

    @Test
    fun autoBudgetStaysWithinUnnoticeableWindow() {
        // 自动档预算约束:用户口径"探测不可感知地拖慢起播"(≤1s)—— 现在干脆是 0
        assertTrue(DeviceCapability.QualityMode.AUTO.probeBudgetMs <= 1000L)
    }
}
