package com.github.tvbox.osc.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「画质选项」三档的口径锁。
 *
 * <p>`current()` 依赖 KV(真机 MMKV),不在纯单测范围;这里锁的是 enum 常量本身的口径 ——
 * 尤其 **ordinal 即 KV 协议值**(0=画质优先 1=自动 2=速度优先),换序/改名都会让老用户
 * 读到脏档位,必须先改这里再动 KV。
 */
class VideoQualityModeTest {

    @Test
    fun ordinalIsTheKvProtocol() {
        assertEquals(0, DeviceCapability.QualityMode.QUALITY_FIRST.ordinal)
        assertEquals(1, DeviceCapability.QualityMode.AUTO.ordinal)
        assertEquals(2, DeviceCapability.QualityMode.SPEED_FIRST.ordinal)
    }

    @Test
    fun defaultIsAuto_matchingKvKeySpecRegistration() {
        // 与 KVKeySpec 里 register(VIDEO_QUALITY_MODE, 1) 的默认值一致
        assertEquals(1, DeviceCapability.QualityMode.AUTO.ordinal)
    }

    @Test
    fun speedFirstSkipsProbeEntirely() {
        val mode = DeviceCapability.QualityMode.SPEED_FIRST
        assertFalse(mode.shouldProbeOnFirstWatch)
        assertEquals(0L, mode.probeBudgetMs)
        assertEquals(0, mode.probeLines)
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
        // 自动档预算约束:用户口径"探测不可感知地拖慢起播"(≤1s)
        assertTrue(DeviceCapability.QualityMode.AUTO.probeBudgetMs <= 1000L)
    }
}
