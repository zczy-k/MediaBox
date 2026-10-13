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

    // ---- 换片判定(2026-10-13,P1):只有"片 id 变了"才算换片 ----
    // 上一部内核/会话/待继承位置在换片时必须作废,否则新片加载期间会听到上一部的声音。
    // 换源/换线/换集都**不算**换片 —— 它们复用内核是收益(省一次重建,进度继承也靠它)。

    private fun diffVod(started: String?, target: String?): Boolean =
        KernelReusePolicy.isDifferentVodSwitch(started, target)

    @Test
    fun differentVod_falseForIdleKernelWithoutContent() {
        // 预热/已释放内核没有"上一部":没有可作废的内容(这条保住了"空闲内核复用")
        assertEquals(false, diffVod(null, "src|100|线路1|0"))
        assertEquals(false, diffVod("", "src|100|线路1|0"))
    }

    @Test
    fun differentVod_falseForSameVodDifferentLine() {
        // 手动换线:同片不同线路 ⇒ 不作废(进度继承靠的就是"内核还是这一部")
        assertEquals(false, diffVod("src|100|线路1|0", "src|100|线路2|0"))
    }

    @Test
    fun differentVod_falseForSameVodDifferentEpisode() {
        // 同片同线路换集:内核里那份就是这一部的,无需作废
        assertEquals(false, diffVod("src|100|线路1|0", "src|100|线路1|5"))
    }

    @Test
    fun differentVod_falseForDifferentSourceSameVod() {
        // 换源:源 key 变了但片 id 没变 ⇒ 不作废(换源复用内核是收益,不是"上一部声音")
        assertEquals(false, diffVod("srcA|100|线路1|0", "srcB|100|线路1|0"))
    }

    @Test
    fun differentVod_trueWhenVodIdChanges() {
        // 换片:片 id 变了 ⇒ 必须先作废上一部的内核/会话/待继承位置
        assertEquals(true, diffVod("src|100|线路1|0", "src|200|线路1|0"))
        assertEquals(true, diffVod("srcA|100|线路1|0", "srcB|200|线路1|0"))
    }

    @Test
    fun differentVod_trueWhenTargetKeyUnparsable() {
        // 目标键切不出片 id 段(不该发生):按**换片**处理 —— 方向必须与 isCrossContentSwitch 一致,
        // 判不出就作废。作废的代价只是多重一次内核,而漏作废的代价是"新片里听到上一部的声音"
        //(本次要修的正是这条)。宁可多重建一次,不可留残响。
        assertEquals(true, diffVod("src|100|线路1|0", "no-separator"))
        assertEquals(true, diffVod("src|100|线路1|0", null))
    }

    @Test
    fun differentVod_doesNotPrefixMatchDifferentVodId() {
        // 片 id 前缀相同但不是同一部:必须按整段比对(否则 100 会被当成 1000)
        assertEquals(true, diffVod("src|100|线路1|0", "src|1000|线路1|0"))
    }
}
