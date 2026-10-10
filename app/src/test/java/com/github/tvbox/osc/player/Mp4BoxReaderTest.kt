package com.github.tvbox.osc.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * MP4 `moov/trak/tkhd` 解析单测。
 *
 * <p>为什么必须测:真实探测里这是**唯一完全可信的画质来源**(文件里写死的尺寸,站点改不了)。
 * 解析错了的两种后果都很糟 —— 报不出尺寸等于白探测,报错尺寸会把用户引到错误的档位上。
 * 这里用手工构造的最小字节流覆盖 version 0 / version 1 / 64 位 size / moov 不在范围内四条路径。
 */
class Mp4BoxReaderTest {

    private fun u32(v: Long): ByteArray = byteArrayOf(
        ((v ushr 24) and 0xFF).toByte(), ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte(),
    )

    private fun box(type: String, body: ByteArray, use64BitSize: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        if (use64BitSize) {
            out.write(u32(1L))
            out.write(type.toByteArray(Charsets.ISO_8859_1))
            out.write(u32(0L)); out.write(u32((16 + body.size).toLong()))
        } else {
            out.write(u32((8 + body.size).toLong()))
            out.write(type.toByteArray(Charsets.ISO_8859_1))
        }
        out.write(body)
        return out.toByteArray()
    }

    /** `tkhd` body:尾部固定是 16.16 定点的 width / height */
    private fun tkhd(width: Int, height: Int, version: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(version)                       // version
        out.write(byteArrayOf(0, 0, 0))          // flags
        if (version == 1) {
            repeat(8) { out.write(0) }          // creation
            repeat(8) { out.write(0) }          // modification
            out.write(u32(1L))                  // track_ID
            out.write(u32(0L))                  // reserved
            repeat(8) { out.write(0) }          // duration
        } else {
            out.write(u32(0L)); out.write(u32(0L)); out.write(u32(1L)); out.write(u32(0L)); out.write(u32(0L))
        }
        repeat(8) { out.write(0) }              // reserved
        repeat(8) { out.write(0) }              // layer / alternate_group / volume / reserved
        repeat(36) { out.write(0) }             // matrix
        out.write(u32((width.toLong() shl 16))) // width 16.16
        out.write(u32((height.toLong() shl 16)))
        return box("tkhd", out.toByteArray())
    }

    private fun ftyp(): ByteArray = box("ftyp", ByteArray(16))

    private fun fullFile(width: Int, height: Int, version: Int = 0): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(ftyp())
        out.write(box("moov", box("trak", tkhd(width, height, version))))
        return out.toByteArray()
    }

    @Test
    fun readsVersion0Tkhd() {
        // 2026-10-10 修正:length 的契约是"buffer 里有效字节数"(生产调用传 buffer.size),
        // 不能传 10000 —— 会先命中 length > buf.size 的越界守卫,null 来自守卫而非解析结果。
        val data = fullFile(1920, 1080, 0)
        val (w, h) = Mp4BoxReader.readVideoSize(data, data.size)!!
        assertEquals(1920, w)
        assertEquals(1080, h)
    }

    @Test
    fun readsVersion1Tkhd() {
        val data = fullFile(3840, 2160, 1)
        val (w, h) = Mp4BoxReader.readVideoSize(data, data.size)!!
        assertEquals(3840, w)
        assertEquals(2160, h)
    }

    @Test
    fun reads64BitBoxSize() {
        val out = ByteArrayOutputStream()
        out.write(ftyp())
        out.write(box("moov", box("trak", tkhd(1280, 720, 0)), use64BitSize = true))
        val data = out.toByteArray()
        val (w, h) = Mp4BoxReader.readVideoSize(data, data.size)!!
        assertEquals(1280, w)
        assertEquals(720, h)
    }

    @Test
    fun moovAbsent_returnsNull() {
        // 只有 ftyp + mdat:moov 在文件尾(非 faststart),探测不到 → 静默降级
        val out = ByteArrayOutputStream()
        out.write(ftyp())
        out.write(box("mdat", ByteArray(64)))
        val data = out.toByteArray()
        assertNull(Mp4BoxReader.readVideoSize(data, data.size))
    }

    @Test
    fun truncatedBuffer_returnsNull() {
        val full = fullFile(1920, 1080, 0)
        // 只喂到 moov 之前
        assertNull(Mp4BoxReader.readVideoSize(full, ftyp().size + 4))
    }

    @Test
    fun notMp4_returnsNull() {
        val html = "<html>404 not found</html>".toByteArray()
        assertNull(Mp4BoxReader.readVideoSize(html, html.size))
    }

    @Test
    fun emptyOrTinyInput_returnsNull() {
        assertNull(Mp4BoxReader.readVideoSize(ByteArray(0), 0))
        assertNull(Mp4BoxReader.readVideoSize(byteArrayOf(1, 2, 3), 3))
    }

    @Test
    fun zeroSize_tkhd_returnsNull() {
        // 0x0 的宽高不是合法视频,必须报"探测不到"而不是让 0 参与排序
        val out = ByteArrayOutputStream()
        out.write(ftyp())
        out.write(box("moov", box("trak", tkhd(0, 0, 0))))
        val data = out.toByteArray()
        assertNull(Mp4BoxReader.readVideoSize(data, data.size))
    }

    @Test
    fun declaredLengthIsRespected() {
        // length 小于真实内容:只能读到 ftyp,应报 null 而不是越界
        val full = fullFile(1920, 1080, 0)
        assertNull(Mp4BoxReader.readVideoSize(full, 8))
    }

    // ==================== stsd 回退与"多 trak"回归(2026-10-10) ====================

    /**
     * 视觉采样条目:视觉条目的 body 布局 = `6(reserved)+2(dataRefIdx)` + `2+2+12` = 24,
     * 紧跟 width(uint16) / height(uint16)。
     */
    private fun visualEntry(type: String, width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        repeat(6) { out.write(0) }                 // reserved
        out.write(byteArrayOf(0, 1))               // data_reference_index
        repeat(2 + 2 + 12) { out.write(0) }        // pre_defined / reserved / pre_defined[3]
        out.write(byteArrayOf(((width ushr 8) and 0xFF).toByte(), (width and 0xFF).toByte()))
        out.write(byteArrayOf(((height ushr 8) and 0xFF).toByte(), (height and 0xFF).toByte()))
        repeat(4 + 4 + 4 + 2 + 32 + 2 + 2) { out.write(0) }   // 其余字段,解析不读,仅补长度
        return box(type, out.toByteArray())
    }

    private fun stsd(entry: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0, 0, 0))         // version + flags
        out.write(u32(1L))                         // entry_count
        out.write(entry)
        return box("stsd", out.toByteArray())
    }

    private fun trakWithStsd(entry: ByteArray): ByteArray =
        box("trak", box("mdia", box("minf", box("stbl", stsd(entry)))))

    /**
     * **回归护栏**:音频轨排在视频轨之前。
     *
     * <p>音频轨也有 `tkhd`,其宽高恒为 **0**,解析必然返回 null。这里锁住的是
     * "**一个 box 解不出不等于整个搜索失败**":必须继续找下去,直到视频轨。
     * （现实现天然满足 —— `visit` 返回 null 只表示"不是我要的"；
     * 这条用例的价值是防止以后有人把 `visit` 改回"命中即返回"。）
     */
    @Test
    fun audioTrakBeforeVideoTrak_stillReadsVideoSize() {
        val out = ByteArrayOutputStream()
        out.write(ftyp())
        out.write(
            box(
                "moov",
                ByteArrayOutputStream().apply {
                    write(box("trak", tkhd(0, 0, 0)))        // 音频轨:宽高 0
                    write(box("trak", tkhd(1728, 720, 0)))   // 视频轨:真值
                }.toByteArray(),
            ),
        )
        val data = out.toByteArray()
        val (w, h) = Mp4BoxReader.readVideoSize(data, data.size)!!
        assertEquals(1728, w)
        assertEquals(720, h)
    }

    /** **真机回归(核心)**:`tkhd` 宽高为 0,必须回退到 `stsd` 的视觉采样条目。
     *
     *  <p>实据:ixigua 一条线路,内核从容器报出 1728x720(能播),探测却 `probe-no-size` ——
     *  `tkhd` 偏移已按规范逐字节核对无误,故只剩"tkhd 里没尺寸、真值在采样条目"这一种解释。
     */
    @Test
    fun zeroTkhd_fallsBackToStsdEntry() {
        val out = ByteArrayOutputStream()
        out.write(ftyp())
        out.write(
            box(
                "moov",
                ByteArrayOutputStream().apply {
                    write(box("trak", tkhd(0, 0, 0)))
                    write(trakWithStsd(visualEntry("avc1", 1728, 720)))
                }.toByteArray(),
            ),
        )
        val data = out.toByteArray()
        val (w, h) = Mp4BoxReader.readVideoSize(data, data.size)!!
        assertEquals(1728, w)
        assertEquals(720, h)
    }

    /** 完全没有 `tkhd`(只有 `stsd`)时也要能出尺寸。 */
    @Test
    fun noTkhd_readsFromStsd() {
        val out = ByteArrayOutputStream()
        out.write(ftyp())
        out.write(box("moov", trakWithStsd(visualEntry("hev1", 1920, 800))))
        val data = out.toByteArray()
        val (w, h) = Mp4BoxReader.readVideoSize(data, data.size)!!
        assertEquals(1920, w)
        assertEquals(800, h)
    }

    /** `tkhd` 优先:`tkhd` 有合法尺寸时,不被 `stsd` 覆盖(两者理论上一致,但以显示尺寸为准)。 */
    @Test
    fun tkhdWinsOverStsd() {
        val out = ByteArrayOutputStream()
        out.write(ftyp())
        out.write(
            box(
                "moov",
                ByteArrayOutputStream().apply {
                    write(box("trak", tkhd(1920, 1080, 0)))
                    write(trakWithStsd(visualEntry("avc1", 1280, 720)))
                }.toByteArray(),
            ),
        )
        val data = out.toByteArray()
        val (w, h) = Mp4BoxReader.readVideoSize(data, data.size)!!
        assertEquals(1920, w)
        assertEquals(1080, h)
    }

    /**
     * 音频条目**不得**被当成宽高:同一偏移在音频条目里是别的字段。
     * 白名单外的类型必须跳过,宁可不报尺寸也不能报错尺寸。
     */
    @Test
    fun audioEntryInStsd_isIgnored() {
        val out = ByteArrayOutputStream()
        out.write(ftyp())
        out.write(
            box(
                "moov",
                ByteArrayOutputStream().apply {
                    write(box("trak", tkhd(0, 0, 0)))
                    write(trakWithStsd(visualEntry("mp4a", 1728, 720)))
                }.toByteArray(),
            ),
        )
        val data = out.toByteArray()
        assertNull(Mp4BoxReader.readVideoSize(data, data.size))
    }

    /** 排障辅助:能认出 MP4 并列出顶层 box,供失败日志使用。 */
    @Test
    fun diagnostics_looksLikeMp4AndTopLevelTypes() {
        val data = fullFile(1920, 1080, 0)
        org.junit.Assert.assertTrue(Mp4BoxReader.looksLikeMp4(data, data.size))
        // ftyp = 8+16 = 24;moov = 8 + trak(8+ tkhd(8+84)) = 8+100 = 108
        org.junit.Assert.assertEquals("ftyp:24,moov:108", Mp4BoxReader.topLevelTypes(data, data.size))
        val html = "<html>".toByteArray()
        org.junit.Assert.assertFalse(Mp4BoxReader.looksLikeMp4(html, html.size))
    }
}
