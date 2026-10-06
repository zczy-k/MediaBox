package com.github.tvbox.osc.player

/**
 * 从 MP4 字节流里读**真实**视频宽高(读 `moov/trak/tkhd`)。
 *
 * <p>为什么必须读文件而不是猜:站点把 540P 的地址标成"超级无敌4K"是常事,而 `moov` 里的
 * `tkhd` 是**文件本身写死的尺寸**,骗不了人。这是"真实探测"里唯一完全可信的来源
 * （HLS/DASH 的 `RESOLUTION` 只是站点声明）。
 *
 * <p>**本类是纯逻辑、零 Android 依赖、可单测**:真实 mp4 由调用方喂进来，测试里可构造最小字节流。
 * 任何不符合预期的结构一律返回 null（走"探测不到"的静默降级），绝不抛异常。
 *
 * <p>已知边界:非 faststart 的 mp4 把 `moov` 放在文件尾,前若干字节读不到 ⇒ 返回 null。
 * 刻意不去 Range 文件尾 —— 那就变成下大文件了（见 [VideoQualityProbe]）。
 */
object Mp4BoxReader {

    /** 递归深度上限:畸形文件里构造出环或超深结构时兜底 */
    private const val MAX_DEPTH = 6

    /** 容器 box:只有这些需要往下钻 */
    private val CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl")

    /**
     * @return `宽 to 高`，或 null（不是 MP4 / moov 不在范围内 / 结构异常）
     */
    fun readVideoSize(buf: ByteArray, length: Int): Pair<Int, Int>? {
        if (buf.size < 16 || length <= 0 || length > buf.size) return null
        return findInBoxes(buf, 0, length.coerceAtMost(buf.size), 0, "tkhd")
    }

    private fun findInBoxes(buf: ByteArray, start: Int, end: Int, depth: Int, target: String): Pair<Int, Int>? {
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
            if (boxSize < headerSize || offset + boxSize > end) return null
            val bodyStart = offset + headerSize
            val bodyEnd = (offset + boxSize).toInt()
            if (type == target) return readTkhdSize(buf, bodyStart, bodyEnd)
            if (type in CONTAINERS) {
                findInBoxes(buf, bodyStart, bodyEnd, depth + 1, target)?.let { return it }
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
        if (width <= 0 || height <= 0) return null
        return width to height
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
