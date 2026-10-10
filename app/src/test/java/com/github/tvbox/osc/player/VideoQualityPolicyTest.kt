package com.github.tvbox.osc.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 线路画质优选策略单测。
 *
 * <p>锁的是三条最容易回归的口径:①分辨率权重必须压过每像素码率(否则 2160P 低码率会输给 1080P 高码率,
 * 与"优先高画质"的目标相反);②超出设备上限的档要**排除**而不是排后;③探测不到的一律降级,不给优先权。
 */
class VideoQualityPolicyTest {

    private fun v(
        w: Int,
        h: Int,
        bw: Int = 0,
        c: VideoQualityPolicy.Confidence = VideoQualityPolicy.Confidence.MEASURED,
        flag: String = "",
    ) = VideoQualityPolicy.Variant(w, h, bw, c, flag)

    @Test
    fun unknownSizeScoresZero_mainKey() {
        val unknown = VideoQualityPolicy.Variant()
        assertFalse(unknown.known)
        assertEquals(0L, VideoQualityPolicy.score(unknown))
    }

    @Test
    fun higherResolutionWins_evenAgainstMuchHigherBitrate() {
        // 2160P 低码率 vs 1080P 高码率:分辨率优先,这是"优先高画质"的产品口径
        val uhdLowBitrate = v(3840, 2160, bw = 4_000_000)
        val fhdHighBitrate = v(1920, 1080, bw = 20_000_000)
        assertTrue(VideoQualityPolicy.score(uhdLowBitrate) > VideoQualityPolicy.score(fhdHighBitrate))
    }

    @Test
    fun sameResolution_prefersHigherBitsPerPixel() {
        val low = v(1920, 1080, bw = 2_000_000)
        val high = v(1920, 1080, bw = 8_000_000)
        assertTrue(VideoQualityPolicy.score(high) > VideoQualityPolicy.score(low))
    }

    @Test
    fun bitsPerPixel_isZeroWhenDataMissing() {
        assertEquals(0, VideoQualityPolicy.bitsPerPixel(v(1920, 1080, bw = 0)))
        assertEquals(0, VideoQualityPolicy.bitsPerPixel(VideoQualityPolicy.Variant()))
        // 1920*1080 = 2073600;8_000_000 / 2073600 = 3
        assertEquals(3, VideoQualityPolicy.bitsPerPixel(v(1920, 1080, bw = 8_000_000)))
    }

    @Test
    fun confidenceOnlyBreaksExactTies() {
        val declared = v(1920, 1080, c = VideoQualityPolicy.Confidence.DECLARED)
        val measured = v(1920, 1080, c = VideoQualityPolicy.Confidence.MEASURED)
        assertTrue(VideoQualityPolicy.score(measured) > VideoQualityPolicy.score(declared))
        // 但实测低一档仍然输给声明高一档:可信度只破同分
        assertTrue(VideoQualityPolicy.score(v(3840, 2160, c = VideoQualityPolicy.Confidence.DECLARED))
                > VideoQualityPolicy.score(measured))
    }

    @Test
    fun pickBest_excludesAboveDeviceCap() {
        val candidates = listOf(v(3840, 2160), v(1920, 1080))
        assertEquals(1080, VideoQualityPolicy.pickBest(candidates, deviceCapHeight = 1080)?.height)
    }

    @Test
    fun pickBest_returnsNullWhenAllExcluded() {
        val candidates = listOf(v(3840, 2160), v(2560, 1440))
        assertNull(VideoQualityPolicy.pickBest(candidates, deviceCapHeight = 1080))
    }

    @Test
    fun pickBest_zeroCapMeansNoLimit() {
        val candidates = listOf(v(1920, 1080), v(3840, 2160))
        assertEquals(2160, VideoQualityPolicy.pickBest(candidates, deviceCapHeight = 0)?.height)
    }

    @Test
    fun pickBest_keepsInputOrderOnTie() {
        val a = v(1920, 1080, flag = "A")
        val b = v(1920, 1080, flag = "B")
        assertEquals("A", VideoQualityPolicy.pickBest(listOf(a, b), deviceCapHeight = 0)?.flag)
    }

    @Test
    fun pickBest_unknownNeverBeatsKnown() {
        val unknown = VideoQualityPolicy.Variant(flag = "unknown")
        assertEquals(1080, VideoQualityPolicy.pickBest(listOf(unknown, v(1920, 1080)), 0)?.height)
    }

    @Test
    fun goodEnough_isThresholdNotMaximum() {
        assertTrue(VideoQualityPolicy.goodEnough(v(1920, 1080), 1080))
        assertFalse(VideoQualityPolicy.goodEnough(v(1280, 720), 1080))
        assertFalse(VideoQualityPolicy.goodEnough(null, 1080))
        // 未测到尺寸不算"够好",否则会在探测失败时误早停
        assertFalse(VideoQualityPolicy.goodEnough(VideoQualityPolicy.Variant(), 1080))
    }

    @Test
    fun parseHlsMaster_takesHighestVariant() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720,CODECS="avc1.64001f,mp4a.40.2"
            720/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=6000000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2"
            1080/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=12000000,RESOLUTION=3840x2160,CODECS="hvc1.2.4.L153.B0"
            2160/index.m3u8
        """.trimIndent()
        val best = VideoQualityPolicy.parseHlsMaster(master)
        assertNotNull(best)
        assertEquals(2160, best!!.height)
        assertEquals(12000000, best.bitrate)
        assertEquals(VideoQualityPolicy.Confidence.DECLARED, best.confidence)
    }

    @Test
    fun parseHlsMaster_singleRatePlaylistReturnsNull() {
        // 单码率 playlist 没有 STREAM-INF,探测不到 → 静默降级,不能瞎猜
        val media = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXTINF:6.0,
            seg1.ts
        """.trimIndent()
        assertNull(VideoQualityPolicy.parseHlsMaster(media))
    }

    @Test
    fun parseHlsMaster_garbageReturnsNull() {
        assertNull(VideoQualityPolicy.parseHlsMaster(null))
        assertNull(VideoQualityPolicy.parseHlsMaster(""))
        assertNull(VideoQualityPolicy.parseHlsMaster("<html>404</html>"))
    }

    @Test
    fun parseHlsMaster_skipsVariantWithoutResolution() {
        val master = """
            #EXT-X-STREAM-INF:BANDWIDTH=9000000,CODECS="avc1.640028"
            unknown/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720
            720/index.m3u8
        """.trimIndent()
        val best = VideoQualityPolicy.parseHlsMaster(master)
        assertNotNull(best)
        assertEquals(720, best!!.height)
    }

    // ==================== 度量层:等效清晰度 S / 天花板 / 达标锚 ====================

    @Test
    fun sharpness_isGeometricMean() {
        assertEquals(1440, VideoQualityPolicy.sharpness(v(1920, 1080)))
        assertEquals(960, VideoQualityPolicy.sharpness(v(1280, 720)))
        assertEquals(139, VideoQualityPolicy.sharpness(v(160, 120)))
        // 未知尺寸 ⇒ 0(含义是"还没测到",不是"没有画面")
        assertEquals(0, VideoQualityPolicy.sharpness(VideoQualityPolicy.Variant()))
    }

    @Test
    fun sharpness_letterboxIsNotPenalized() {
        // 宽银幕 1080p(1920×800)按高度只有 800 会被误判成"未达 1080";按 S 明确高于 720p
        assertTrue(VideoQualityPolicy.sharpness(v(1920, 800)) > VideoQualityPolicy.sharpness(v(1280, 720)))
        // 1280×534 与 1728×720:旧"档位"口径(向上取整)把两者归成同一档 ⇒ 死区;按 S 前者明显更低
        assertTrue(VideoQualityPolicy.sharpness(v(1728, 720)) > VideoQualityPolicy.sharpness(v(1280, 534)))
    }

    @Test
    fun isAtCeiling_usesLevelNotEquality() {
        // 非标宽高也算 4K 级 —— 否则会永远认为"上面还有",无限追高
        assertTrue(VideoQualityPolicy.isAtCeiling(v(3840, 1600)))
        assertTrue(VideoQualityPolicy.isAtCeiling(v(3840, 2160)))
        assertFalse(VideoQualityPolicy.isAtCeiling(v(2560, 1440)))
        assertFalse(VideoQualityPolicy.isAtCeiling(null))
        assertFalse(VideoQualityPolicy.isAtCeiling(VideoQualityPolicy.Variant()))
    }

    @Test
    fun meetsAnchor_1080pLevel_noDeviceDiscount() {
        // 宽度达标,含宽银幕 1080p(宽 1920 但高只有 800 —— 它就是 1080P 级)
        assertTrue(VideoQualityPolicy.meetsAnchor(v(1920, 800), VideoQualityPolicy.ANCHOR_WIDTH))
        assertTrue(VideoQualityPolicy.meetsAnchor(v(1920, 1080), VideoQualityPolicy.ANCHOR_WIDTH))
        // 高度兜底:非标准宽高,宽度不足但高度达标
        assertTrue(VideoQualityPolicy.meetsAnchor(v(1600, 1200), VideoQualityPolicy.ANCHOR_WIDTH))
        // 720p 级**不算**达标 —— 手机不再打折(锚曾是 1280,真机因此被锁在低画质)
        assertFalse(VideoQualityPolicy.meetsAnchor(v(1280, 720), VideoQualityPolicy.ANCHOR_WIDTH))
        // 真机案例:1280×534 曾被判"已达锚"⇒ 自动画质档不补救。必须判未达标
        assertFalse(VideoQualityPolicy.meetsAnchor(v(1280, 534), VideoQualityPolicy.ANCHOR_WIDTH))
        // anchorWidth ≤ 0 ⇒ 视为"无锚",一律通过
        assertTrue(VideoQualityPolicy.meetsAnchor(VideoQualityPolicy.Variant(), 0))
    }

    @Test
    fun measured_factoryBuildsMeasuredVariant() {
        val m = VideoQualityPolicy.measured(1920, 1080, 5_000_000)
        assertEquals(1920, m.width)
        assertEquals(1080, m.height)
        assertEquals(5_000_000, m.bitrate)
        assertEquals(VideoQualityPolicy.Confidence.MEASURED, m.confidence)
    }

    @Test
    fun score_primaryKeyIsSharpness_notHeightOrFlag() {
        // 同高度、不同宽度:旧口径(主键=高度)会判平局,按 S 则更宽的那条胜出
        assertTrue(VideoQualityPolicy.score(v(1728, 720)) > VideoQualityPolicy.score(v(1280, 720)))
        // 宽银幕 1080p 压过 720p
        assertTrue(VideoQualityPolicy.score(v(1920, 800)) > VideoQualityPolicy.score(v(1280, 720)))
    }
}
