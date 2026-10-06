package com.github.tvbox.osc.player

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 线路优选编排单测。
 *
 * <p>锁三条口径:①早停是"够好"不是"最好"(第一条达标就不该再付解析代价);
 * ②探测失败必须跳过而不是猜;③合并候选时"没测过的"要接在后面,不能因为缺数据把路堵死。
 */
class LineQualitySelectorTest {

    private fun v(flag: String, h: Int, c: VideoQualityPolicy.Confidence = VideoQualityPolicy.Confidence.MEASURED) =
        VideoQualityPolicy.Variant(1920, h, 0, c, flag)

    @Test
    fun pickFromMemory_returnsBestByRealHeight() {
        val remembered = listOf(v("超清", 720), v("蓝光", 1080), v("高清", 480))
        assertEquals("蓝光", LineQualitySelector.pickFromMemory(remembered, deviceCapHeight = 0))
    }

    @Test
    fun pickFromMemory_respectsDeviceCap() {
        val remembered = listOf(v("蓝光", 2160), v("高清", 1080))
        assertEquals("高清", LineQualitySelector.pickFromMemory(remembered, deviceCapHeight = 1080))
    }

    @Test
    fun pickFromMemory_emptyReturnsNull() {
        assertNull(LineQualitySelector.pickFromMemory(emptyList(), 0))
    }

    @Test
    fun pickWithProbe_stopsAtThreshold() = runBlocking {
        val probed = mutableListOf<String>()
        val picked = LineQualitySelector.pickWithProbe(
            flags = listOf("A", "B", "C"),
            deviceCapHeight = 0,
            thresholdHeight = 1080,
            resolve = { it },
            probe = { url ->
                probed.add(url)
                when (url) {
                    "A" -> VideoQualityPolicy.Variant(1920, 720, 0, VideoQualityPolicy.Confidence.MEASURED)
                    "B" -> VideoQualityPolicy.Variant(1920, 1080, 0, VideoQualityPolicy.Confidence.MEASURED)
                    else -> VideoQualityPolicy.Variant(3840, 2160, 0, VideoQualityPolicy.Confidence.MEASURED)
                }
            },
        )
        assertEquals("B", picked)
        // 达标即停:绝不能为了"找 2160P"再付一条的解析代价
        assertEquals(listOf("A", "B"), probed)
    }

    @Test
    fun pickWithProbe_keepsProbingWhenBelowThreshold() = runBlocking {
        val probed = mutableListOf<String>()
        val picked = LineQualitySelector.pickWithProbe(
            flags = listOf("A", "B", "C"),
            deviceCapHeight = 0,
            thresholdHeight = 1080,
            resolve = { it },
            probe = { url ->
                probed.add(url)
                when (url) {
                    "A" -> VideoQualityPolicy.Variant(1280, 720, 0, VideoQualityPolicy.Confidence.MEASURED)
                    "B" -> null                       // 探测不到,跳过而不是当成"未知"
                    else -> VideoQualityPolicy.Variant(1920, 1080, 0, VideoQualityPolicy.Confidence.MEASURED)
                }
            },
        )
        assertEquals("C", picked)
        assertEquals(listOf("A", "B", "C"), probed)
    }

    @Test
    fun pickWithProbe_resolveFailureSkipsThatLine() = runBlocking {
        val picked = LineQualitySelector.pickWithProbe(
            flags = listOf("A", "B"),
            deviceCapHeight = 0,
            thresholdHeight = 1080,
            resolve = { if (it == "A") null else it },
            probe = { VideoQualityPolicy.Variant(1920, 1080, 0, VideoQualityPolicy.Confidence.MEASURED) },
        )
        assertEquals("B", picked)
    }

    @Test
    fun pickWithProbe_allUnusableReturnsNull() = runBlocking {
        val picked = LineQualitySelector.pickWithProbe(
            flags = listOf("A", "B"),
            deviceCapHeight = 1080,
            thresholdHeight = 1080,
            resolve = { it },
            probe = { VideoQualityPolicy.Variant(3840, 2160, 0, VideoQualityPolicy.Confidence.MEASURED) },
        )
        // 两条都超设备上限 ⇒ 无解,交回调用方走站点原序
        assertNull(picked)
    }

    @Test
    fun pickWithProbe_exceptionIsTreatedAsUnusable() = runBlocking {
        val picked = LineQualitySelector.pickWithProbe(
            flags = listOf("A", "B"),
            deviceCapHeight = 0,
            thresholdHeight = 1080,
            resolve = { if (it == "A") throw RuntimeException("spider boom") else it },
            probe = { VideoQualityPolicy.Variant(1920, 1080, 0, VideoQualityPolicy.Confidence.MEASURED) },
        )
        // 爬虫异常绝不允许冒泡到播放链路
        assertEquals("B", picked)
    }

    @Test
    fun mergeCandidates_measuredFirstThenSiteOrder() {
        val siteOrder = listOf("A", "B", "C", "D")
        val remembered = listOf(
            v("C", 1080),
            v("A", 720),
        )
        val merged = LineQualitySelector.mergeCandidates(siteOrder, remembered, deviceCapHeight = 0, limit = 3)
        // 有实测的按实测排前面(A 720 < C 1080 ⇒ C 先),再接没测过的站点原序
        assertEquals(listOf("C", "A", "B"), merged)
    }

    @Test
    fun mergeCandidates_respectsLimitAndCap() {
        val siteOrder = listOf("A", "B", "C", "D", "E")
        val remembered = listOf(v("A", 2160), v("B", 1080))
        val merged = LineQualitySelector.mergeCandidates(siteOrder, remembered, deviceCapHeight = 1080, limit = 2)
        // A 超设备上限被排除,B 补位;C 是没测过的,按站点序接在后面
        assertEquals(listOf("B", "C"), merged)
    }

    @Test
    fun mergeCandidates_emptyInputReturnsEmpty() {
        assertTrue(LineQualitySelector.mergeCandidates(emptyList(), emptyList(), 0, 3).isEmpty())
        assertTrue(LineQualitySelector.mergeCandidates(listOf("A"), emptyList(), 0, 0).isEmpty())
    }

    @Test
    fun mergeCandidates_noMemoryKeepsSiteOrder() {
        assertEquals(
            listOf("A", "B"),
            LineQualitySelector.mergeCandidates(listOf("A", "B", "C"), emptyList(), 0, 2),
        )
    }

    // ---------------- 降档(卡顿时优先选更低的档) ----------------

    @Test
    fun pickDowngrade_takesClosestLowerHeight() {
        val measured = listOf(
            VideoQualityPolicy.Variant(1920, 480, 0, VideoQualityPolicy.Confidence.MEASURED, "480"),
            VideoQualityPolicy.Variant(1920, 1080, 0, VideoQualityPolicy.Confidence.MEASURED, "1080"),
            VideoQualityPolicy.Variant(3840, 2160, 0, VideoQualityPolicy.Confidence.MEASURED, "2160"),
        )
        // 从 2160 降到 1080(降得最少),不是降到 480
        assertEquals("1080", LineQualitySelector.pickDowngrade(measured, "2160", 2160, emptySet()))
    }

    @Test
    fun pickDowngrade_skipsAlreadyTried() {
        val measured = listOf(
            VideoQualityPolicy.Variant(1920, 1080, 0, VideoQualityPolicy.Confidence.MEASURED, "1080"),
            VideoQualityPolicy.Variant(1280, 720, 0, VideoQualityPolicy.Confidence.MEASURED, "720"),
        )
        assertEquals(
            "720",
            LineQualitySelector.pickDowngrade(measured, "2160", 2160, setOf("1080")),
        )
    }

    @Test
    fun pickDowngrade_unknownCurrentHeightReturnsNull() {
        val measured = listOf(
            VideoQualityPolicy.Variant(1920, 1080, 0, VideoQualityPolicy.Confidence.MEASURED, "1080"),
        )
        // 不知道当前多高 ⇒ 没资格判断"更低",不猜
        assertNull(LineQualitySelector.pickDowngrade(measured, "2160", 0, emptySet()))
    }

    @Test
    fun pickDowngrade_ignoresUnmeasuredCandidates() {
        val measured = listOf(
            VideoQualityPolicy.Variant(flag = "unknown"),                 // 没测到尺寸
            VideoQualityPolicy.Variant(1920, 1080, 0, VideoQualityPolicy.Confidence.MEASURED, "1080"),
        )
        // "unknown" 不能被当成"更低"
        assertEquals("1080", LineQualitySelector.pickDowngrade(measured, "2160", 2160, emptySet()))
    }

    @Test
    fun pickDowngrade_noLowerCandidateReturnsNull() {
        val measured = listOf(
            VideoQualityPolicy.Variant(3840, 2160, 0, VideoQualityPolicy.Confidence.MEASURED, "2160"),
        )
        assertNull(LineQualitySelector.pickDowngrade(measured, "2160", 2160, emptySet()))
    }

    @Test
    fun pickDowngrade_emptyMeasuredReturnsNull() {
        assertNull(LineQualitySelector.pickDowngrade(emptyList(), "2160", 2160, emptySet()))
    }

    // ---------------- 并发探测(直连型线路,无爬虫共享状态) ----------------

    @Test
    fun pickWithProbeParallel_picksHighestMeasured() = runBlocking {
        val order = mutableListOf<String>()
        val picked = LineQualitySelector.pickWithProbeParallel(
            flags = listOf("A", "B", "C"),
            deviceCapHeight = 0,
            resolve = {
                // 并发的证据:三条的 resolve 交错进入,不是串行
                order.add("r$it")
                it
            },
            probe = { url ->
                order.add("p$url")
                when (url) {
                    "A" -> VideoQualityPolicy.Variant(1280, 720, 0, VideoQualityPolicy.Confidence.MEASURED)
                    "B" -> VideoQualityPolicy.Variant(3840, 2160, 0, VideoQualityPolicy.Confidence.MEASURED)
                    else -> VideoQualityPolicy.Variant(1920, 1080, 0, VideoQualityPolicy.Confidence.MEASURED)
                }
            },
        )
        assertEquals("B", picked)
        assertEquals(3, order.count { it.startsWith("p") })
    }

    @Test
    fun pickWithProbeParallel_skipsUnresolvableAndUnmeasurable() = runBlocking {
        val picked = LineQualitySelector.pickWithProbeParallel(
            flags = listOf("A", "B", "C"),
            deviceCapHeight = 0,
            resolve = { if (it == "A") null else it },
            probe = { url ->
                if (url == "B") null else VideoQualityPolicy.Variant(1920, 1080, 0, VideoQualityPolicy.Confidence.MEASURED)
            },
        )
        assertEquals("C", picked)
    }

    @Test
    fun pickWithProbeParallel_respectsDeviceCap() = runBlocking {
        val picked = LineQualitySelector.pickWithProbeParallel(
            flags = listOf("A", "B"),
            deviceCapHeight = 1080,
            resolve = { it },
            probe = { VideoQualityPolicy.Variant(3840, 2160, 0, VideoQualityPolicy.Confidence.MEASURED) },
        )
        assertNull(picked)
    }

    @Test
    fun pickWithProbeParallel_allUnmeasurableReturnsNull() = runBlocking {
        val picked = LineQualitySelector.pickWithProbeParallel(
            flags = listOf("A", "B"),
            deviceCapHeight = 0,
            resolve = { it },
            probe = { null },
        )
        assertNull(picked)
    }

    @Test
    fun pickWithProbeParallel_exceptionInOneCandidateDoesNotKillOthers() = runBlocking {
        val picked = LineQualitySelector.pickWithProbeParallel(
            flags = listOf("A", "B"),
            deviceCapHeight = 0,
            resolve = { if (it == "A") throw RuntimeException("boom") else it },
            probe = { VideoQualityPolicy.Variant(1920, 1080, 0, VideoQualityPolicy.Confidence.MEASURED) },
        )
        assertEquals("B", picked)
    }
}
