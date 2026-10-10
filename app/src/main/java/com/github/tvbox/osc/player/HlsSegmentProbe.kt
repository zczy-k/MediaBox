package com.github.tvbox.osc.player

import java.io.ByteArrayOutputStream

/**
 * HLS **分片内探**（2026-10-10）。
 *
 * <p>要解决的问题：分辨率只写在 HLS 的 **master playlist**（`#EXT-X-STREAM-INF:RESOLUTION=`）里，
 * 而大量片源的线路地址直接指向 **media playlist**（只有 `#EXT-X-TARGETDURATION` + `#EXTINF` 分片列表）。
 * [VideoQualityPolicy.parseHlsMaster] 对后者必然返回 null ⇒ 全部落进 `probe-no-size`
 * ⇒ 对"整源都是 HLS 子列表"的片源，自动升档**完全失效**（真机实测：9 条线路探出 0 条）。
 *
 * <p>但真实分辨率一直躺在**首个分片**里：SPS 携带宽高，而 MPEG-TS 打包时 SPS 就在第一个视频包里
 * —— 真机实测该分片 `profile=100 level=4.0 1914x798`，**SPS 位于视频 payload 第 29 字节，
 * 读 96KB 足够**（比读 master playlist 还便宜）。
 *
 * <p>**H.264 与 H.265/HEVC 都支持**（2026-10-11 起）：两者共用同一条 TS 解复用，
 * 差别在 SPS 的找法与语法（见 [parseTsHevcSize]）。入口用 [parseTsVideoSize] 做
 * **内容嗅探**——依次尝试两种 SPS，谁解得出用谁，不信任 PMT/URL 的声明。
 *
 * <p>本文件是**纯解析**：无网络、无 Android 依赖，可 JVM 单测。网络那一步留在
 * [VideoQualityProbe]（它持有 OkHttp client 与读取预算）。
 */
object HlsSegmentProbe {

    /** MPEG-TS 固定包长 */
    private const val TS_PACKET = 188

    private const val TS_SYNC = 0x47

    /** NAL unit type：SPS（H.264） */
    private const val NAL_SPS = 7

    /** PMT 中 H.264 的 stream_type */
    private const val STREAM_TYPE_H264 = 0x1B

    /** PMT 中 H.265/HEVC 的 stream_type（2026-10-11 起：与 H.264 同样进分片内探） */
    private const val STREAM_TYPE_HEVC = 0x24

    /**
     * HEVC 的 NAL 头是 **2 字节**（H.264 是 1 字节）：`forbidden(1) + type(6) + layer_id(6) + tid+1(3)`。
     * SPS 的 type=33 ⇒ 第一字节 = `33 << 1 = 0x42`（layer_id 高位为 0 时）；
     * 第二字节的低 3 位是 `temporal_id_plus1`，规范要求 ≥ 1。
     * 按位匹配而不是硬编码 0x42 0x01：容忍 layer_id ≠ 0 的少见流。
     */
    private const val HEVC_NAL_SPS_MASK = 0x7E
    private const val HEVC_NAL_SPS_PATTERN = 0x42

    /** 拼视频 payload 的封顶：SPS 在开头，攒够这些就停（避免把整个分片读进内存） */
    private const val MAX_VIDEO_PAYLOAD = 256 * 1024

    /**
     * 是不是 **media playlist**（子列表）。
     *
     * <p>判据：含 `#EXTINF`（分片条目）**且不含** `#EXT-X-STREAM-INF`（master 的变体条目）。
     * 只有这种才需要走分片内探 —— master playlist 的探测老路径已经覆盖。
     */
    @JvmStatic
    fun isMediaPlaylist(text: String?): Boolean {
        if (text.isNullOrEmpty()) return false
        if (!text.contains("#EXTINF", ignoreCase = true)) return false
        return !text.contains("#EXT-X-STREAM-INF", ignoreCase = true)
    }

    /**
     * 是否加密。
     *
     * <p>AES-128 会把**整个分片**加密，SPS 也解不出来 ⇒ 必须**在读分片之前**放弃，
     * 否则白白多下一次几百 KB。`METHOD=NONE` 不算加密。
     */
    @JvmStatic
    fun isEncrypted(text: String?): Boolean {
        if (text.isNullOrEmpty()) return false
        val at = text.indexOf("#EXT-X-KEY", ignoreCase = true)
        if (at < 0) return false
        val line = text.substring(at).substringBefore('\n')
        return !line.contains("METHOD=NONE", ignoreCase = true)
    }

    /**
     * fMP4 形态的初始化段地址（`#EXT-X-MAP:URI="..."`）；没有 → null。
     *
     * <p>该段含 `moov/stsd`，可直接复用 [Mp4BoxReader] —— 比 TS 路径还简单，所以优先于首分片。
     */
    @JvmStatic
    fun mapInitUri(text: String?, playlistUrl: String): String? {
        if (text.isNullOrEmpty()) return null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (!line.startsWith("#EXT-X-MAP", ignoreCase = true)) continue
            val at = line.indexOf("URI=\"", ignoreCase = true)
            if (at < 0) continue
            val from = at + 5
            val to = line.indexOf('"', from)
            if (to <= from) continue
            return resolveUrl(playlistUrl, line.substring(from, to))
        }
        return null
    }

    /**
     * 首个媒体分片的绝对地址（跳过 `#` 注释与空行）；找不到 → null。
     */
    @JvmStatic
    fun firstSegmentUri(text: String?, playlistUrl: String): String? {
        if (text.isNullOrEmpty()) return null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            return resolveUrl(playlistUrl, line)
        }
        return null
    }

    /**
     * MPEG-TS → H.264 SPS → `[width, height]`；解不出 → null。
     *
     * <p>三步：①按 188 定长解包，从 PAT 拿 PMT 的 PID；②从 PMT 拿 H.264 视频流的 PID；
     * ③把该 PID 的 payload 拼起来，找 Annex-B 起始码后的 SPS 并解宽高。
     *
     * @param length 实际有效字节数（[data] 可能比它长，见 VideoQualityProbe.readCapped）
     */
    @JvmStatic
    fun parseTsH264Size(data: ByteArray, length: Int): IntArray? {
        val payload = demuxVideoPayload(data, length) ?: return null
        return findSpsSize(payload, payload.size)
    }

    /**
     * MPEG-TS → **H.265/HEVC** SPS → `[width, height]`（2026-10-11 新增）。
     *
     * <p>与 [parseTsH264Size] 共用同一条 TS 解复用（[demuxVideoPayload]，PMT 同时认
     * `0x1B`/`0x24`），差别只在 SPS 的找法与语法：HEVC 的 NAL 头是 2 字节、
     * SPS 携带 `pic_width_in_luma_samples`（直接就是像素数，没有 H.264 的宏块换算）。
     *
     * <p>**只解到宽高就停**：HEVC SPS 里 `pic_width/height` 位于 conformance window 之前，
     * 后面的 `scaling_list` / `st_ref_pic_set` / VUI 全部不用碰 —— 解析面越小越不容易错位。
     */
    @JvmStatic
    fun parseTsHevcSize(data: ByteArray, length: Int): IntArray? {
        val payload = demuxVideoPayload(data, length) ?: return null
        return findHevcSpsSize(payload, payload.size)
    }

    /**
     * **内容嗅探**入口：同一份分片字节，H.264 与 HEVC 依次尝试。
     *
     * <p>为什么入口做成"依次尝试"而不是"按 PMT 的 stream_type 分派"：
     * 真机见过站点把 stream_type 写错/写死的情况 —— **分片里的字节不会骗人，PMT 的声明会**。
     * 两个解析器都自带 NAL type 校验，猜错的那条会安全地返回 null，不会产出错误尺寸。
     */
    @JvmStatic
    fun parseTsVideoSize(data: ByteArray, length: Int): IntArray? {
        return parseTsH264Size(data, length) ?: parseTsHevcSize(data, length)
    }

    /** TS 解复用（PAT → PMT → 视频 PID → 拼 payload），H.264 / HEVC 共用。 */
    private fun demuxVideoPayload(data: ByteArray, length: Int): ByteArray? {
        val end = minOf(length, data.size)
        if (end < TS_PACKET * 3) return null

        var pmtPid = -1
        var videoPid = -1
        var patDone = false
        var pmtDone = false
        val video = ByteArrayOutputStream(64 * 1024)

        var i = alignToSync(data, end)
        if (i < 0) return null
        while (i + TS_PACKET <= end) {
            if ((data[i].toInt() and 0xFF) != TS_SYNC) {
                // 丢同步：滑动重找，比整段放弃鲁棒
                val re = alignToSync(data, end, i + 1)
                if (re < 0) break
                i = re
                continue
            }
            val pid = ((data[i + 1].toInt() and 0x1F) shl 8) or (data[i + 2].toInt() and 0xFF)
            val afc = ((data[i + 3].toInt() and 0xFF) shr 4) and 0x3
            var p = i + 4
            if (afc == 2 || afc == 3) {
                if (p >= i + TS_PACKET) {
                    i += TS_PACKET
                    continue
                }
                p += 1 + (data[p].toInt() and 0xFF)
            }
            val hasPayload = (afc == 1 || afc == 3) && p < i + TS_PACKET

            if (hasPayload) {
                if (pid == 0 && !patDone) {
                    pmtPid = readPatPmtPid(data, p, i + TS_PACKET)
                    patDone = pmtPid > 0
                } else if (pmtPid > 0 && pid == pmtPid && !pmtDone) {
                    videoPid = readPmtVideoPid(data, p, i + TS_PACKET)
                    pmtDone = videoPid > 0
                } else if (videoPid > 0 && pid == videoPid) {
                    video.write(data, p, i + TS_PACKET - p)
                    if (video.size() >= MAX_VIDEO_PAYLOAD) break
                }
            }
            i += TS_PACKET
        }

        if (video.size() <= 0) return null
        return video.toByteArray()
    }

    // ==================== 内部：TS 解复用 ====================

    /**
     * 找到同步起点：要求 `off` 处是 0x47 **且下一个包边界也是 0x47**（单字节匹配太容易误判）。
     */
    private fun alignToSync(d: ByteArray, end: Int, from: Int = 0): Int {
        val last = minOf(end, d.size) - TS_PACKET - 1
        var off = maxOf(0, from)
        while (off <= last) {
            if ((d[off].toInt() and 0xFF) == TS_SYNC &&
                (d[off + TS_PACKET].toInt() and 0xFF) == TS_SYNC
            ) {
                return off
            }
            off++
        }
        // 退化：只有单个包也接受（分片极短时仍可能解出 SPS）
        off = maxOf(0, from)
        while (off < minOf(end, d.size)) {
            if ((d[off].toInt() and 0xFF) == TS_SYNC) return off
            off++
        }
        return -1
    }

    /** PAT：pointer_field + section，取第一个节目号非 0 的 PMT PID */
    private fun readPatPmtPid(d: ByteArray, from: Int, to: Int): Int {
        if (from >= to) return -1
        var p = from + 1 + (d[from].toInt() and 0xFF)
        if (p + 8 > to || p >= d.size) return -1
        if ((d[p].toInt() and 0xFF) != 0x00) return -1
        val secLen = ((d[p + 1].toInt() and 0x0F) shl 8) or (d[p + 2].toInt() and 0xFF)
        val secEnd = minOf(to, p + 3 + secLen - 4)
        var i = p + 8
        while (i + 4 <= secEnd && i + 4 <= d.size) {
            val prog = (d[i].toInt() and 0xFF shl 8) or (d[i + 1].toInt() and 0xFF)
            val pid = ((d[i + 2].toInt() and 0x1F) shl 8) or (d[i + 3].toInt() and 0xFF)
            if (prog != 0 && pid != 0) return pid
            i += 4
        }
        return -1
    }

    /** PMT：跳过节目信息，取视频 elementary PID（H.264 `0x1B` 或 H.265 `0x24`） */
    private fun readPmtVideoPid(d: ByteArray, from: Int, to: Int): Int {
        if (from >= to) return -1
        var p = from + 1 + (d[from].toInt() and 0xFF)
        if (p + 12 > to || p + 12 > d.size) return -1
        if ((d[p].toInt() and 0xFF) != 0x02) return -1
        val secLen = ((d[p + 1].toInt() and 0x0F) shl 8) or (d[p + 2].toInt() and 0xFF)
        val progInfoLen = ((d[p + 10].toInt() and 0x0F) shl 8) or (d[p + 11].toInt() and 0xFF)
        val secEnd = minOf(to, p + 3 + secLen - 4)
        var i = p + 12 + progInfoLen
        while (i + 5 <= secEnd && i + 5 <= d.size) {
            val st = d[i].toInt() and 0xFF
            val pid = ((d[i + 1].toInt() and 0x1F) shl 8) or (d[i + 2].toInt() and 0xFF)
            val esLen = ((d[i + 3].toInt() and 0x0F) shl 8) or (d[i + 4].toInt() and 0xFF)
            if (st == STREAM_TYPE_H264 || st == STREAM_TYPE_HEVC) return pid
            i += 5 + esLen
        }
        return -1
    }

    // ==================== 内部：H.264 SPS ====================

    /**
     * 找 Annex-B 起始码后的 SPS 并解宽高。
     *
     * <p>只按 **3 字节起始码**（`00 00 01`）扫描：4 字节码 `00 00 00 01` 从第 2 个 0 起看
     * 正好也是 3 字节码形态，故两种写法都被覆盖，不必分别处理。
     */
    private fun findSpsSize(d: ByteArray, len: Int): IntArray? {
        var i = 0
        while (i + 4 < len) {
            if (d[i].toInt() == 0 && d[i + 1].toInt() == 0 && d[i + 2].toInt() == 1) {
                val nalType = d[i + 3].toInt() and 0x1F
                if (nalType == NAL_SPS) {
                    val rbsp = toRbsp(d, i + 4, len)
                    val size = parseSps(rbsp)
                    if (size != null) return size
                }
                i += 3
            } else {
                i++
            }
        }
        return null
    }

    /** 去掉 emulation prevention 字节（`00 00 03` → `00 00`），否则 Exp-Golomb 会读错位 */
    private fun toRbsp(d: ByteArray, from: Int, to: Int): ByteArray {
        val out = ByteArray(to - from)
        var n = 0
        var zeros = 0
        var i = from
        while (i < to) {
            val b = d[i].toInt() and 0xFF
            if (zeros >= 2 && b == 0x03) {
                zeros = 0
                i++
                continue
            }
            zeros = if (b == 0) zeros + 1 else 0
            out[n++] = d[i]
            i++
        }
        return out.copyOf(n)
    }

    /** Exp-Golomb 位读取器；越界一律返回 -1，由调用方判定非法 */
    private class BitReader(private val d: ByteArray) {
        private var pos = 0

        fun u(n: Int): Int {
            var v = 0
            for (k in 0 until n) {
                val idx = pos shr 3
                if (idx >= d.size) return -1
                v = (v shl 1) or ((d[idx].toInt() shr (7 - (pos and 7))) and 1)
                pos++
            }
            return v
        }

        fun ue(): Int {
            var z = 0
            while (true) {
                val b = u(1)
                if (b < 0) return -1
                if (b == 1) break
                z++
                if (z > 31) return -1
            }
            if (z == 0) return 0
            val rest = u(z)
            if (rest < 0) return -1
            return (1 shl z) - 1 + rest
        }

        fun se(): Int {
            val v = ue()
            if (v < 0) return 0
            return if (v and 1 == 1) (v + 1) shr 1 else -(v shr 1)
        }
    }

    /** scaling list 是可变长 delta 编码，必须按位跳过去，否则后面全错位 */
    private fun skipScalingList(r: BitReader, size: Int) {
        var last = 8
        var next = 8
        for (k in 0 until size) {
            if (next != 0) {
                next = (last + r.se() + 256) % 256
            }
            if (next != 0) last = next
        }
    }

    /**
     * SPS → 宽高（含 frame_cropping 修正，所以 1914x798 这类非 16 倍数也能得出）。
     */
    private fun parseSps(rbsp: ByteArray): IntArray? {
        val r = BitReader(rbsp)
        val profile = r.u(8)
        r.u(8)
        r.u(8)
        if (profile < 0) return null
        if (r.ue() < 0) return null

        var chroma = 1
        if (profile in HIGH_PROFILES) {
            chroma = r.ue()
            if (chroma < 0) return null
            if (chroma == 3) r.u(1)
            if (r.ue() < 0) return null
            if (r.ue() < 0) return null
            r.u(1)
            if (r.u(1) == 1) {
                val n = if (chroma != 3) 8 else 12
                for (k in 0 until n) {
                    if (r.u(1) == 1) skipScalingList(r, if (k < 6) 16 else 64)
                }
            }
        }

        if (r.ue() < 0) return null
        val poc = r.ue()
        if (poc < 0) return null
        if (poc == 0) {
            if (r.ue() < 0) return null
        } else if (poc == 1) {
            r.u(1)
            r.se()
            r.se()
            val cnt = r.ue()
            if (cnt < 0) return null
            for (k in 0 until cnt) r.se()
        }
        if (r.ue() < 0) return null

        r.u(1)
        val wMbs = r.ue()
        val hUnits = r.ue()
        val frameMbsOnly = r.u(1)
        if (wMbs < 0 || hUnits < 0 || frameMbsOnly < 0) return null
        if (frameMbsOnly == 0) r.u(1)
        r.u(1)
        val cropL = r.ue()
        val cropR = r.ue()
        val cropT = r.ue()
        val cropB = r.ue()
        if (cropL < 0 || cropR < 0 || cropT < 0 || cropB < 0) return null

        var w = (wMbs + 1) * 16
        var h = (2 - frameMbsOnly) * (hUnits + 1) * 16
        val cropUnitX: Int
        val cropUnitY: Int
        when (chroma) {
            0 -> {
                cropUnitX = 1
                cropUnitY = 2 - frameMbsOnly
            }
            1 -> {
                cropUnitX = 2
                cropUnitY = 2 * (2 - frameMbsOnly)
            }
            else -> {
                cropUnitX = 1
                cropUnitY = 2 - frameMbsOnly
            }
        }
        w -= (cropL + cropR) * cropUnitX
        h -= (cropT + cropB) * cropUnitY

        // 合理性闸：越界一律判非法，宁可探测不到也不能往记忆里写垃圾
        if (w < 64 || h < 64 || w > 8192 || h > 8192) return null
        return intArrayOf(w, h)
    }

    /** 含 chroma_format_idc 等扩展字段的 profile（SPS 语法分岔点） */
    private val HIGH_PROFILES = intArrayOf(
        100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135,
    )

    // ==================== 内部：H.265/HEVC SPS ====================

    /**
     * 找 HEVC 的 SPS 并解宽高（2026-10-11）。
     *
     * <p>HEVC 的 NAL 头是 2 字节：第一字节 `type(6) = 33` ⇒ `(b0 and 0x7E) == 0x42`；
     * 第二字节低 3 位是 `temporal_id_plus1`，规范要求 ≥ 1（据此过滤偶发的字节巧合）。
     * SPS body 从起始码后 **5** 字节开始（3 字节起始码 + 2 字节 NAL 头）。
     *
     * <p>与 H.264 的查找互不干扰：H.264 侧看 `b0 and 0x1F == 7`，`0x42` 在 H.264 里是
     * NAL type 2（分片数据），两边都不会把对方的数据当成自己的 SPS —— 双方解析失败
     * 也只是返回 null 继续扫，**不会产出错误尺寸**。
     */
    private fun findHevcSpsSize(d: ByteArray, len: Int): IntArray? {
        var i = 0
        while (i + 6 < len) {
            if (d[i].toInt() == 0 && d[i + 1].toInt() == 0 && d[i + 2].toInt() == 1) {
                val b0 = d[i + 3].toInt() and 0xFF
                val b1 = d[i + 4].toInt() and 0xFF
                if ((b0 and HEVC_NAL_SPS_MASK) == HEVC_NAL_SPS_PATTERN && (b1 and 0x07) != 0) {
                    val rbsp = toRbsp(d, i + 5, len)
                    val size = parseHevcSps(rbsp)
                    if (size != null) return size
                }
                i += 3
            } else {
                i++
            }
        }
        return null
    }

    /**
     * HEVC SPS → 宽高（含 conformance window 修正）。
     *
     * <p>**只解到 `pic_width/height_in_luma_samples` + conformance window 就返回** ——
     * 宽高在 SPS 里位置靠前，后面的 scaling_list / st_ref_pic_set / VUI 全部不用碰，
     * 解析面越小越不容易因个别流的可变结构而错位。
     *
     * <p>与 H.264 的两处语法差异（都是踩过才记下的）：
     * ① NAL 头 2 字节，SPS 里没有 8 位的 profile/level 前缀，取而代之的是 96 位的 profile_tier_level；
     * ② 宽高是**直接的字素样本数**（H.264 是宏块数 ×16），所以 1920x800 这类值不需要裁剪修正也能直出。
     */
    private fun parseHevcSps(rbsp: ByteArray): IntArray? {
        val r = BitReader(rbsp)
        if (r.u(4) < 0) return null                       // sps_video_parameter_set_id
        val maxSub = r.u(3)                               // sps_max_sub_layers_minus1
        if (maxSub < 0 || maxSub > 7) return null
        if (r.u(1) < 0) return null                       // temporal_id_nesting_flag
        if (!skipProfileTierLevel(r, maxSub)) return null
        if (r.ue() < 0) return null                       // sps_seq_parameter_set_id
        val chroma = r.ue()
        if (chroma < 0 || chroma > 3) return null
        val sepColour = if (chroma == 3) r.u(1) else 0
        if (sepColour < 0) return null
        val w = r.ue()
        val h = r.ue()
        if (w <= 0 || h <= 0) return null
        val cw = r.u(1)                                   // conformance_window_flag
        if (cw < 0) return null
        var width = w
        var height = h
        if (cw == 1) {
            val l = r.ue()
            val rr = r.ue()
            val t = r.ue()
            val b = r.ue()
            if (l < 0 || rr < 0 || t < 0 || b < 0) return null
            // SubWidthC / SubHeightC（separate_colour_plane=1 视同单色,步长 1）
            val sx: Int
            val sy: Int
            when {
                chroma == 0 || sepColour == 1 -> { sx = 1; sy = 1 }
                chroma == 1 -> { sx = 2; sy = 2 }
                chroma == 2 -> { sx = 2; sy = 1 }
                else -> { sx = 1; sy = 1 }
            }
            width -= (l + rr) * sx
            height -= (t + b) * sy
        }
        // 越界即弃:宁可"探测不到"也不能把畸形流解出的垃圾尺寸写进记忆
        if (width <= 0 || height <= 0 || width > 16384 || height > 16384) return null
        return intArrayOf(width, height)
    }

    /**
     * 跳过 profile_tier_level。
     *
     * <p>位序:space(2)+tier(1)+idc(5) → compat[32] → 4 个源/打包 flag → 43 位 reserved +
     * 1 位 inbld → level_idc(8) —— 恰好 **96 位 = 12 字节**（maxNumSubLayersMinus1 == 0 时）。
     * 多 sub-layer 的流（可伸缩编码,流媒体里少见）按规范补齐各 present 标志与 88/8 位块,
     * 解错位会让后面的宽高全错 —— 所以这段必须完整实现,不能只处理常见情形。
     */
    private fun skipProfileTierLevel(r: BitReader, maxSub: Int): Boolean {
        if (r.u(2) < 0 || r.u(1) < 0 || r.u(5) < 0) return false
        for (k in 0 until 32) if (r.u(1) < 0) return false
        for (k in 0 until 4) if (r.u(1) < 0) return false
        for (k in 0 until 43) if (r.u(1) < 0) return false
        if (r.u(1) < 0) return false
        if (r.u(8) < 0) return false                      // general_level_idc
        val profilePresent = BooleanArray(maxSub)
        val levelPresent = BooleanArray(maxSub)
        for (i in 0 until maxSub) {
            val p = r.u(1)
            val l = r.u(1)
            if (p < 0 || l < 0) return false
            profilePresent[i] = p == 1
            levelPresent[i] = l == 1
        }
        if (maxSub > 0) {
            for (i in maxSub until 8) if (r.u(2) < 0) return false
            for (i in 0 until maxSub) {
                if (profilePresent[i] && !skipBits(r, 88)) return false
                if (levelPresent[i] && r.u(8) < 0) return false
            }
        }
        return true
    }

    private fun skipBits(r: BitReader, n: Int): Boolean {
        for (k in 0 until n) if (r.u(1) < 0) return false
        return true
    }

    // ==================== 内部：URL ====================

    /**
     * 相对地址 → 绝对地址。手写而不用 [java.net.URI]：分片名里常含 `?` / `&` / 中文，
     * URI 的多参构造会直接抛异常，而这里只需要"拼目录"这一件事。
     */
    private fun resolveUrl(base: String, ref: String): String {
        if (ref.startsWith("http://", ignoreCase = true) ||
            ref.startsWith("https://", ignoreCase = true)
        ) {
            return ref
        }
        val schemeEnd = base.indexOf("://")
        if (schemeEnd < 0) return ref
        if (ref.startsWith("/")) {
            val hostEnd = base.indexOf('/', schemeEnd + 3)
            val origin = if (hostEnd < 0) base else base.substring(0, hostEnd)
            return origin + ref
        }
        val clean = base.substringBefore('?')
        val cut = clean.lastIndexOf('/')
        val dir = if (cut > schemeEnd + 2) clean.substring(0, cut + 1) else "$clean/"
        return dir + ref
    }
}
