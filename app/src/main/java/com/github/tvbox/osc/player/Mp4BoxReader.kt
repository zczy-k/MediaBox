package com.github.tvbox.osc.player

/**
 * 从 MP4 字节流里读**真实**视频宽高。
 *
 * <p>为什么必须读文件而不是猜:站点把 540P 的地址标成"超级无敌4K"是常事,而文件里写死的尺寸
 * 骗不了人 —— 这是"真实探测"里唯一完全可信的来源(HLS/DASH 的 `RESOLUTION` 只是站点声明)。
 *
 * <p>**本类是纯逻辑、零 Android 依赖、可单测**:真实 mp4 由调用方喂进来,测试里可构造最小字节流。
 * 任何不符合预期的结构一律返回 null(走"探测不到"的静默降级),绝不抛异常。
 *
 * <p>**两条读取路径(2026-10-10 修)**,按优先级:
 * <ol>
 *   <li>`moov/trak/tkhd` 尾部的 16.16 定点宽高 —— 显示尺寸,最直观;</li>
 *   <li>`moov/trak/mdia/minf/stbl/stsd` 里**视觉采样条目**(`avc1`/`hev1`/`av01`…)的 16 位宽高 ——
 *       编码尺寸,也是实现里"最后一定有的那份"。</li>
 * </ol>
 *
 * <p>⚠️ **为什么必须有第 2 条(真机踩过,别删)**:老实现**只**读 `tkhd`。而实测拿内核做对照:
 * 同一条线路(ixigua)内核从容器里报出 **1728x720**(播放完全正常),探测却报
 * `probe-no-size src=hls streamInf=false head=ftypisom…moov…` ——
 * 即"`moov` 就在文件开头、容器里确实有尺寸,而我们的读取器拿不到"。
 * `tkhd` 的偏移已逐字节核对过 H.264 规范(version 0 = body+76,version 1 = body+88),没有写错,
 * 所以只剩一种解释:**该文件的 `tkhd` 宽高为 0 或缺失**,尺寸只在采样条目里。
 * 这正是"两份都要读"的理由 —— 任一可用即可出尺寸,而不是单点依赖。
 *
 * <p>同时明确 `visit` 的语义:**返回 null 只表示"这个 box 不是我要的",继续找** ——
 * 音频轨的 `tkhd` 宽高恒为 0,它会自然地返回 null 并让搜索继续到视频轨,不会被误当成"搜索失败"。
 *
 * <p>已知边界:非 faststart 的 mp4 把 `moov` 放在文件尾,前若干字节读不到 ⇒ 返回 null。
 * 刻意不去 Range 文件尾 —— 那就变成下大文件了(见 [VideoQualityProbe])。
 */
object Mp4BoxReader {

    /** 递归深度上限:畸形文件里构造出环或超深结构时兜底 */
    private const val MAX_DEPTH = 6

    /** 容器 box:只有这些需要往下钻 */
    private val CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl")

    /**
     * 视觉采样条目类型。
     *
     * <p>**必须按白名单过滤**:`stsd` 在音频轨里也有(条目是 `mp4a`),而"body+24/26"这个偏移
     * 只在视觉条目里才是宽高,在音频条目里是别的字段 —— 不过滤就会把音频的垃圾字节当成分辨率。
     */
    private val VISUAL_ENTRIES = setOf(
        "avc1", "avc3", "avc4",          // H.264
        "hev1", "hvc1",                  // H.265
        "av01",                          // AV1
        "vp08", "vp09",                  // VP8 / VP9
        "dvhe", "dva1", "dvav",          // Dolby Vision
        "mp4v", "s263", "h263",          // 其它常见
        "jpeg", "mjpa", "png",
    )

    /**
     * @return `宽 to 高`，或 null（不是 MP4 / 尺寸不在范围内 / 结构异常）
     */
    fun readVideoSize(buf: ByteArray, length: Int): Pair<Int, Int>? {
        if (buf.size < 16 || length <= 0 || length > buf.size) return null
        val end = length.coerceAtMost(buf.size)
        // ① 显示尺寸:moov/trak/tkhd(文件写死,最直观)
        walk(buf, 0, end, 0) { type, bs, be ->
            if (type == "tkhd") readTkhdSize(buf, bs, be) else null
        }?.let { return it }
        // ② 编码尺寸:stsd 的视觉采样条目 —— ExoPlayer 用的就是这条,所以它能播我们就该能读
        return walk(buf, 0, end, 0) { type, bs, be ->
            if (type == "stsd") readStsdSize(buf, bs, be) else null
        }
    }

    /** 前 4 字节是不是 `ftyp` / `styp` / `moov` —— 供上游把"MP4 解析失败"与"根本不是 MP4"分开记 */
    fun looksLikeMp4(buf: ByteArray, length: Int): Boolean {
        val end = length.coerceAtMost(buf.size)
        if (end < 8) return false
        val t = readType(buf, 4)
        return t == "ftyp" || t == "styp" || t == "moov"
    }

    /**
     * 顶层 box 类型串(最多 8 个),仅供**排障日志** —— 解析失败时能一眼看出结构长什么样,
     * 不必再"猜为什么解不出"(2026-10-10:为了终结反复猜测)。
     */
    fun topLevelTypes(buf: ByteArray, length: Int): String {
        val end = length.coerceAtMost(buf.size)
        val sb = StringBuilder()
        var offset = 0
        var n = 0
        while (offset + 8 <= end && n < 8) {
            var boxSize = readUInt32(buf, offset)
            val type = readType(buf, offset + 4)
            var headerSize = 8
            if (boxSize == 1L) {
                if (offset + 16 > end) break
                boxSize = readUInt64(buf, offset + 8)
                headerSize = 16
            } else if (boxSize == 0L) {
                boxSize = (end - offset).toLong()
            }
            if (boxSize < headerSize) break
            if (sb.isNotEmpty()) sb.append(',')
            sb.append(type).append(':').append(boxSize)
            val boxEnd = offset + boxSize
            if (boxEnd > end) { sb.append("(truncated)"); break }
            offset = boxEnd.toInt()
            n++
        }
        return if (sb.isEmpty()) "none" else sb.toString()
    }

    /**
     * 深度优先遍历所有 box。
     *
     * <p>关键语义:**`visit` 返回 null 表示"这个 box 不是我要的",继续往下找**,而不是"整个搜索失败"。
     * 老实现写成"命中目标就 return",于是音频轨的 `tkhd`(宽高 0 ⇒ 解析返回 null)会把整个搜索中断。
     */
    private inline fun walk(
        buf: ByteArray,
        start: Int,
        end: Int,
        depth: Int,
        visit: (type: String, bodyStart: Int, bodyEnd: Int) -> Pair<Int, Int>?,
    ): Pair<Int, Int>? {
        if (depth > MAX_DEPTH) return null
        var offset = start
        while (offset + 8 <= end) {
            var boxSize = readUInt32(buf, offset)
            val type = readType(buf, offset + 4)
            var headerSize = 8
            if (boxSize == 1L) {
                if (offset + 16 > end) return null
                boxSize = readUInt64(buf, offset + 8)
                headerSize = 16
            } else if (boxSize == 0L) {
                // size==0 表示"延伸到文件末尾"
                boxSize = (end - offset).toLong()
            }
            if (boxSize < headerSize) return null
            val bodyStart = offset + headerSize
            val boxEnd = offset + boxSize
            // 读到了缓冲区之外:说明这个 box 被截断(典型是 mdat),后面不再有完整结构
            if (boxEnd > end) return null
            val bodyEnd = boxEnd.toInt()
            if (type in CONTAINERS) {
                walk(buf, bodyStart, bodyEnd, depth + 1, visit)?.let { return it }
            } else {
                visit(type, bodyStart, bodyEnd)?.let { return it }
            }
            offset = bodyEnd
        }
        return null
    }

    /** `tkhd` 尾部的 width/height 是 16.16 定点,高 16 位才是整数部分 */
    private fun readTkhdSize(buf: ByteArray, bodyStart: Int, bodyEnd: Int): Pair<Int, Int>? {
        if (bodyStart + 4 > bodyEnd) return null
        val version = buf[bodyStart].toInt() and 0xFF
        // version 0: 头部 76 字节后是 width;version 1: 头部 88 字节后是 width
        val widthOffset = if (version == 1) bodyStart + 88 else bodyStart + 76
        if (widthOffset + 8 > bodyEnd) return null
        val width = (readUInt32(buf, widthOffset) ushr 16).toInt()
        val height = (readUInt32(buf, widthOffset + 4) ushr 16).toInt()
        // ⚠️ 音频轨的 tkhd 宽高恒为 0 —— 这里返回 null 后**调用方会继续找下一个 trak**,不能中断
        if (width <= 0 || height <= 0) return null
        return width to height
    }

    /**
     * `stsd`:version+flags(4) + entry_count(4) + 采样条目列表。
     *
     * <p>视觉采样条目的布局(相对条目 body 起点):`6(reserved)+2(dataRefIdx)` + `2+2+12(preDefined/reserved)`
     * = **24**,紧跟 **width(uint16)** 与 **height(uint16)**。
     */
    private fun readStsdSize(buf: ByteArray, bodyStart: Int, bodyEnd: Int): Pair<Int, Int>? {
        if (bodyStart + 8 > bodyEnd) return null
        var offset = bodyStart + 8
        while (offset + 8 <= bodyEnd) {
            val size = readUInt32(buf, offset)
            val type = readType(buf, offset + 4)
            if (size < 8L || offset + size > bodyEnd) return null
            if (type in VISUAL_ENTRIES) {
                val entryBody = offset + 8
                if (entryBody + 28 > bodyEnd) return null
                val w = ((buf[entryBody + 24].toInt() and 0xFF) shl 8) or (buf[entryBody + 25].toInt() and 0xFF)
                val h = ((buf[entryBody + 26].toInt() and 0xFF) shl 8) or (buf[entryBody + 27].toInt() and 0xFF)
                if (w > 0 && h > 0) return w to h
            }
            offset += size.toInt()
        }
        return null
    }

    private fun readUInt32(buf: ByteArray, at: Int): Long {
        if (at + 4 > buf.size) return 0L
        return ((buf[at].toLong() and 0xFF) shl 24) or
                ((buf[at + 1].toLong() and 0xFF) shl 16) or
                ((buf[at + 2].toLong() and 0xFF) shl 8) or
                (buf[at + 3].toLong() and 0xFF)
    }

    private fun readUInt64(buf: ByteArray, at: Int): Long {
        val hi = readUInt32(buf, at)
        val lo = readUInt32(buf, at + 4)
        if (hi < 0 || lo < 0) return 0L
        return (hi shl 32) or lo
    }

    private fun readType(buf: ByteArray, at: Int): String {
        if (at + 4 > buf.size) return ""
        return String(buf, at, 4, Charsets.ISO_8859_1)
    }
}
