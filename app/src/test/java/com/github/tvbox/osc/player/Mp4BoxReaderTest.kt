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
        val (w, h) = Mp4BoxReader.readVideoSize(fullFile(1920, 1080, 0), 10_000)!!
        assertEquals(1920, w)
        assertEquals(1080, h)
    }

    @Test
    fun readsVersion1Tkhd() {
        val (w, h) = Mp4BoxReader.readVideoSize(fullFile(3840, 2160, 1), 10_000)!!
        assertEquals(3840, w)
        assertEquals(2160, h)
    }

    @Test
    fun reads64BitBoxSize() {
        val out = ByteArrayOutputStream()
        out.write(ftyp())
        out.write(box("moov", box("trak", tkhd(1280, 720, 0)), use64BitSize = true))
        val (w, h) = Mp4BoxReader.readVideoSize(out.toByteArray(), 10_000)!!
        assertEquals(1280, w)
        assertEquals(720, h)
    }

    @Test
    fun moovAbsent_returnsNull() {
        // 只有 ftyp + mdat:moov 在文件尾(非 faststart),探测不到 → 静默降级
        val out = ByteArrayOutputStream()
        out.write(ftyp())
        out.write(box("mdat", ByteArray(64)))
        assertNull(Mp4BoxReader.readVideoSize(out.toByteArray(), 10_000))
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
        assertNull(Mp4BoxReader.readVideoSize(out.toByteArray(), 10_000))
    }

    @Test
    fun declaredLengthIsRespected() {
        // length 小于真实内容:只能读到 ftyp,应报 null 而不是越界
        val full = fullFile(1920, 1080, 0)
        assertNull(Mp4BoxReader.readVideoSize(full, 8))
    }
}
