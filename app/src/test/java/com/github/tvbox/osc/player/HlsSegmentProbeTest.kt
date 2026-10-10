package com.github.tvbox.osc.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [HlsSegmentProbe] 单测(2026-10-10)。
 *
 * <p>核心用例 `parseTsH264Size` 用的是**真机实测的字节**:从"天堂/一个不眠的诞生"首个 TS 分片
 * (`571a558145d2/0000000.ts`)里逐包解出来的真实 SPS —— profile=100(High) level=4.0, **1914x798**。
 * 也就是说这条用例断言的是"能不能从分片里还原出真实分辨率",而不是自造的假数据。
 */
class HlsSegmentProbeTest {

    // ==================== isMediaPlaylist ====================

    @Test
    fun `isMediaPlaylist 子列表为真`() {
        val media = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:6
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXTINF:3.12,
            0000000.ts
            #EXTINF:3.12,
            0000001.ts
        """.trimIndent()
        assertTrue(HlsSegmentProbe.isMediaPlaylist(media))
    }

    @Test
    fun `isMediaPlaylist master为假`() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=9000000,RESOLUTION=1920x1040
            1080/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720
            720/index.m3u8
        """.trimIndent()
        assertFalse(HlsSegmentProbe.isMediaPlaylist(master))
    }

    @Test
    fun `isMediaPlaylist 空与HTML为假`() {
        assertFalse(HlsSegmentProbe.isMediaPlaylist(null))
        assertFalse(HlsSegmentProbe.isMediaPlaylist(""))
        assertFalse(HlsSegmentProbe.isMediaPlaylist("<html>404</html>"))
    }

    // ==================== isEncrypted ====================

    @Test
    fun `isEncrypted AES128为真`() {
        val media = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin"
            #EXTINF:3.12,
            0000000.ts
        """.trimIndent()
        assertTrue(HlsSegmentProbe.isEncrypted(media))
    }

    @Test
    fun `isEncrypted METHOD等于NONE为假`() {
        val media = """
            #EXTM3U
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:3.12,
            0000000.ts
        """.trimIndent()
        assertFalse(HlsSegmentProbe.isEncrypted(media))
    }

    @Test
    fun `isEncrypted 无KEY为假`() {
        assertFalse(HlsSegmentProbe.isEncrypted("#EXTM3U\n#EXTINF:3.12,\n0000000.ts"))
        assertFalse(HlsSegmentProbe.isEncrypted(null))
    }

    // ==================== URL 解析 ====================

    @Test
    fun `firstSegmentUri 相对路径按目录解析`() {
        val media = "#EXTM3U\n#EXTINF:3.12,\n0000000.ts"
        assertEquals(
            "https://fengbao12.com/video/abc/571a558145d2/0000000.ts",
            HlsSegmentProbe.firstSegmentUri(media, "https://fengbao12.com/video/abc/571a558145d2/index.m3u8"),
        )
    }

    @Test
    fun `firstSegmentUri 带query的playlist地址不污染拼接`() {
        val media = "#EXTINF:3.12,\nseg0.ts"
        assertEquals(
            "https://h.com/a/seg0.ts",
            HlsSegmentProbe.firstSegmentUri(media, "https://h.com/a/index.m3u8?token=xyz&t=1"),
        )
    }

    @Test
    fun `firstSegmentUri 绝对地址原样返回`() {
        val media = "#EXTINF:3.12,\nhttps://cdn.example.com/x/0000000.ts"
        assertEquals(
            "https://cdn.example.com/x/0000000.ts",
            HlsSegmentProbe.firstSegmentUri(media, "https://h.com/a/index.m3u8"),
        )
    }

    @Test
    fun `firstSegmentUri 根路径按origin解析`() {
        val media = "#EXTINF:3.12,\n/root/seg.ts"
        assertEquals(
            "https://h.com/root/seg.ts",
            HlsSegmentProbe.firstSegmentUri(media, "https://h.com/a/b/index.m3u8"),
        )
    }

    @Test
    fun `firstSegmentUri 跳过注释与空行`() {
        val media = "#EXTM3U\n\n#EXT-X-VERSION:3\n#EXTINF:6,\n\n0000005.ts\n#EXTINF:6,\n0000006.ts"
        assertEquals(
            "https://h.com/v/0000005.ts",
            HlsSegmentProbe.firstSegmentUri(media, "https://h.com/v/index.m3u8"),
        )
    }

    @Test
    fun `mapInitUri 取出EXT-X-MAP的URI`() {
        val media = "#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:6,\nseg1.m4s"
        assertEquals(
            "https://h.com/v/init.mp4",
            HlsSegmentProbe.mapInitUri(media, "https://h.com/v/index.m3u8"),
        )
    }

    @Test
    fun `mapInitUri 无MAP返回null`() {
        assertNull(HlsSegmentProbe.mapInitUri("#EXTINF:6,\na.ts", "https://h.com/v/index.m3u8"))
        assertNull(HlsSegmentProbe.mapInitUri(null, "https://h.com/v/index.m3u8"))
    }

    // ==================== parseTsH264Size(真机字节) ====================

    /**
     * 真机实测的 SPS NAL(27 字节,含 NAL header 0x67):
     * `profile=100 level=4.0` ⇒ **1914x798**。
     *
     * <p>注意它含 `00 00 03` 的 emulation-prevention 字节,所以这条用例同时覆盖了
     * [HlsSegmentProbe] 里的去转义逻辑(少去一个字节,后面 Exp-Golomb 全错位)。
     */
    private val realSpsNal = byteArrayOf(
        0x67.toByte(), 0x64.toByte(), 0x00.toByte(), 0x28.toByte(), 0xAC.toByte(),
        0xD9.toByte(), 0x40.toByte(), 0x78.toByte(), 0x06.toByte(), 0x5A.toByte(),
        0x6A.toByte(), 0x02.toByte(), 0x02.toByte(), 0x02.toByte(), 0x80.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x03.toByte(), 0x00.toByte(), 0x80.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x19.toByte(), 0x07.toByte(), 0x8C.toByte(),
        0x18.toByte(), 0xCB.toByte(),
    )

    /** 按 188 定长造一个 TS 包(AFC=1 纯载荷,CC 固定 0) */
    private fun tsPacket(pid: Int, pusi: Boolean, payload: ByteArray): ByteArray {
        val p = ByteArray(188) { 0xFF.toByte() }
        p[0] = 0x47
        p[1] = ((if (pusi) 0x40 else 0) or ((pid shr 8) and 0x1F)).toByte()
        p[2] = (pid and 0xFF).toByte()
        p[3] = 0x10
        System.arraycopy(payload, 0, p, 4, minOf(payload.size, 184))
        return p
    }

    /** PAT:program_number=1 → PMT PID = 0x1000(与真机一致) */
    private val patPayload = byteArrayOf(
        0x00,                         // pointer_field
        0x00, 0xB0.toByte(), 0x0D,    // table_id=0, section_length=13
        0x00, 0x01,                   // transport_stream_id
        0xC1.toByte(),                // version/current_next
        0x00, 0x00,                   // section_number / last
        0x00, 0x01,                   // program_number = 1
        0xF0.toByte(), 0x00,          // PMT PID = 0x1000
        0x00, 0x00, 0x00, 0x00,       // CRC(本解析器不校验)
    )

    /** PMT:H.264 视频流 PID = 0x100(与真机一致) */
    private val pmtPayload = byteArrayOf(
        0x00,                         // pointer_field
        0x02, 0xB0.toByte(), 0x12,    // table_id=2, section_length=18
        0x00, 0x01,                   // program_number
        0xC1.toByte(),                // version
        0x00, 0x00,                   // section / last
        0xE1.toByte(), 0x00,          // PCR_PID = 0x100
        0xF0.toByte(), 0x00,          // program_info_length = 0
        0x1B, 0xE1.toByte(), 0x00,    // stream_type=0x1B(H.264), PID=0x100
        0xF0.toByte(), 0x00,          // ES_info_length = 0
        0x00, 0x00, 0x00, 0x00,       // CRC
    )

    /** 视频包:简化 PES 头 + Annex-B SPS(4 字节起始码) */
    private fun videoPacketWithSps(): ByteArray {
        val payload = byteArrayOf(
            0x00, 0x00, 0x01, 0xE0.toByte(), 0x00, 0x00, 0x80.toByte(), 0x80.toByte(), 0x05,
            0x00, 0x00, 0x00, 0x01,
        ) + realSpsNal
        return tsPacket(0x100, true, payload)
    }

    @Test
    fun `parseTsH264Size 从真实SPS解出1914x798`() {
        val data = patPayload.let { tsPacket(0x0000, false, it) } +
            pmtPayload.let { tsPacket(0x1000, false, it) } +
            videoPacketWithSps()
        val size = HlsSegmentProbe.parseTsH264Size(data, data.size)
        assertEquals("宽", 1914, size!![0])
        assertEquals("高", 798, size[1])
    }

    @Test
    fun `parseTsH264Size 只有PAT和PMT时返回null`() {
        val data = tsPacket(0x0000, false, patPayload) + tsPacket(0x1000, false, pmtPayload)
        // 视频包还没出现 ⇒ 拼不出 payload,不该硬凑一个尺寸出来
        assertNull(HlsSegmentProbe.parseTsH264Size(data, data.size))
    }

    @Test
    fun `parseTsH264Size 全零数据返回null`() {
        val data = ByteArray(188 * 4)
        assertNull(HlsSegmentProbe.parseTsH264Size(data, data.size))
    }

    @Test
    fun `parseTsH264Size 长度不足返回null`() {
        val data = ByteArray(100)
        assertNull(HlsSegmentProbe.parseTsH264Size(data, data.size))
    }

    @Test
    fun `parseTsH264Size 视频流里没有SPS时返回null`() {
        val noSps = byteArrayOf(0x00, 0x00, 0x01, 0xE0.toByte(), 0x00, 0x00, 0x80.toByte())
        val data = tsPacket(0x0000, false, patPayload) +
            tsPacket(0x1000, false, pmtPayload) +
            tsPacket(0x100, true, noSps)
        assertNull(HlsSegmentProbe.parseTsH264Size(data, data.size))
    }

    @Test
    fun `parseTsH264Size 非H264的PMT不误判`() {
        // 把 stream_type 改成 0x02(MPEG-2),视频包内容照旧 —— 不该被当成 H.264 去解 SPS
        val pmtMpeg2 = pmtPayload.copyOf()
        pmtMpeg2[13] = 0x02
        val data = tsPacket(0x0000, false, patPayload) +
            tsPacket(0x1000, false, pmtMpeg2) +
            videoPacketWithSps()
        assertNull(HlsSegmentProbe.parseTsH264Size(data, data.size))
    }
}
