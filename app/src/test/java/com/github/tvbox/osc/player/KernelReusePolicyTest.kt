package com.github.tvbox.osc.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** KernelReusePolicy 单测:锁住"这次起播要不要复用内核"唯一判定的真值表 */
class KernelReusePolicyTest {

    private fun decide(
        kernelPresent: Boolean = true,
        rebuildRequired: Boolean = false,
        dedicatedPath: Boolean = false,
        reuseAllowed: Boolean = true,
    ): KernelDecision = KernelReusePolicy.decide(kernelPresent, rebuildRequired, dedicatedPath, reuseAllowed)

    @Test
    fun rebuild_whenKernelMissing() {
        assertEquals(KernelDecision.REBUILD, decide(kernelPresent = false))
    }

    @Test
    fun rebuild_whenRebuildRequiredEvenIfReuseAllowed() {
        assertEquals(KernelDecision.REBUILD, decide(rebuildRequired = true))
    }

    @Test
    fun rebuild_whenDedicatedPathEvenIfReuseAllowed() {
        assertEquals(KernelDecision.REBUILD, decide(dedicatedPath = true))
    }

    @Test
    fun rebuild_whenReuseNotAllowed() {
        assertEquals(KernelDecision.REBUILD, decide(reuseAllowed = false))
    }

    @Test
    fun reuse_onlyWhenAllGatesPass() {
        assertEquals(KernelDecision.REUSE, decide())
    }

    // ---- 跨内容复用:提示语/进度落盘口径的分辨(2026-10-02 一期"总闸"新增) ----

    private fun cross(started: String?, target: String?): Boolean =
        KernelReusePolicy.isCrossContentSwitch(started, target)

    @Test
    fun crossContent_falseForIdleKernelWithoutContent() {
        // 预热建的空闲内核没有内容语义:不算跨内容(既有"空闲内核复用"口径不变)
        assertEquals(false, cross(null, "src|100|线路1|0"))
        assertEquals(false, cross("", "src|100|线路1|0"))
    }

    @Test
    fun crossContent_falseForSameVodSameLineNextEpisode() {
        assertEquals(false, cross("src|100|线路1|0", "src|100|线路1|1"))
        assertEquals(false, cross("src|100|线路1|1", "src|100|线路1|0"))
    }

    @Test
    fun crossContent_falseForSameVodEvenIfTargetIsLastEpisode() {
        assertEquals(false, cross("src|100|线路1|0", "src|100|线路1|9"))
    }

    @Test
    fun crossContent_trueForDifferentVod() {
        assertEquals(true, cross("src|100|线路1|0", "src|200|线路1|0"))
    }

    @Test
    fun crossContent_trueForSameVodDifferentLine() {
        // 手动换线:同片不同线路 ⇒ 归属键前缀不匹配
        assertEquals(true, cross("src|100|线路1|0", "src|100|线路2|0"))
    }

    @Test
    fun crossContent_trueForDifferentSource() {
        // 换源:源 key 变了
        assertEquals(true, cross("srcA|100|线路1|0", "srcB|100|线路1|0"))
    }

    @Test
    fun crossContent_trueWhenTargetKeyUnparsable() {
        // 判不出"同片同线路"时一律按换内容处理(宁可多显示一次"正在获取播放信息")
        assertEquals(true, cross("src|100|线路1|0", "no-separator"))
        assertEquals(true, cross("src|100|线路1|0", null))
    }

    @Test
    fun crossContent_doesNotPrefixMatchDifferentVod() {
        // 片 id 前缀相同但不是同一部:必须按整段比对,不能 startsWith
        assertEquals(true, cross("src|100|线路1|0", "src|1000|线路1|0"))
    }
}
