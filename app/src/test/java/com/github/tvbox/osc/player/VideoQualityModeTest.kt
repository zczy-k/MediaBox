package com.github.tvbox.osc.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「画质策略」四档的口径锁。
 *
 * <p>`stored()` / `current()` 依赖 KV(真机 MMKV),不在纯单测范围;这里锁的是 enum 常量本身的口径 ——
 * 尤其 **ordinal 即 KV 协议值**(0=画质优先 1=自动 2=速度优先 3=省流画质),换序/改名都会让老用户
 * 读到脏档位,必须先改这里再动 KV。设置页的**显示顺序**与 ordinal 不同(按代价从高到低),
 * 那层映射写在 PlaySettingsPage 的 ladder 里,不要误改枚举顺序。
 */
class VideoQualityModeTest {

    @Test
    fun ordinalIsTheKvProtocol() {
        assertEquals(0, DeviceCapability.QualityMode.QUALITY_FIRST.ordinal)
        assertEquals(1, DeviceCapability.QualityMode.AUTO.ordinal)
        assertEquals(2, DeviceCapability.QualityMode.SPEED_FIRST.ordinal)
        // 省流画质是 2026-10-14 **追加**的第四档:必须排在末尾,前面三档的 ordinal 一个都不能动
        assertEquals(3, DeviceCapability.QualityMode.DATA_SAVER.ordinal)
    }

    @Test
    fun defaultIsAuto_matchingKvKeySpecRegistration() {
        // 与 KVKeySpec 里 register(VIDEO_QUALITY_MODE, 1) 的默认值一致
        assertEquals(1, DeviceCapability.QualityMode.AUTO.ordinal)
    }

    @Test
    fun onlyQualityFirstBlocksStartForProbing() {
        // 2026-10-14 定案:自动档不再为画质等首帧(探测后置到起播后的有界扫描)
        assertTrue(DeviceCapability.QualityMode.QUALITY_FIRST.probeBlocksStart)
        DeviceCapability.QualityMode.values()
            .filter { it != DeviceCapability.QualityMode.QUALITY_FIRST }
            .forEach { assertFalse("$it 不该拖首帧", it.probeBlocksStart) }
    }

    @Test
    fun speedFirstSkipsProbeEntirely() {
        val mode = DeviceCapability.QualityMode.SPEED_FIRST
        assertFalse(mode.shouldProbeOnFirstWatch)
        assertFalse(mode.probeBlocksStart)
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
        // 自动档预算约束:用户口径"探测不可感知地拖慢起播"(≤1s)—— 现在干脆是 0
        assertTrue(DeviceCapability.QualityMode.AUTO.probeBudgetMs <= 1000L)
    }
}
