package com.github.tvbox.osc.player.usecase

/**
 * m3u8 去广告的**判据**（纯函数，可测；网络编排在 [M3u8PurifyUseCase]）。
 *
 * **为什么单独拆出来**：这三条判据每一条都在真机上放过火 ——
 * master 清单认不出来被当媒体列表洗、"洗掉几条"读的是一个会被别的调用覆盖的静态字段。
 * 判据留在纯函数里才能钉住反例；网络那半段没有任何可测的东西。
 */
object M3u8PurifyRules {

    /**
     * 明确写了**媒体文件名**的后缀。这些行不能当"下一级播放列表"去拉 ——
     * 拉回来是二进制流，按文本解析必然失败，白白吃掉净化预算。
     */
    private val MEDIA_FILE_EXT = setOf(
        "mp4", "flv", "mkv", "avi", "mov", "m4v", "webm", "wmv", "ts", "mp3", "m4a", "aac"
    )

    /** 有没有 `#EXT-X-STREAM-INF`：有就是 master（多档清单），要先跟到某一档的清单才能洗 */
    @JvmStatic
    fun isMasterPlaylist(content: String?): Boolean =
        content != null && content.lineSequence().any { it.trimStart().startsWith("#EXT-X-STREAM-INF") }

    /**
     * `#EXT-X-STREAM-INF` 后面那一行能不能跟着拉。
     *
     * 判据是**反向**的：只拒绝明确写了媒体文件后缀的（`/1080p.mp4`），其余一律跟着拉。
     * 原来写的是"只接受结尾 `.m3u8`"，于是无扩展名的清单、`/index.php?...` 这类网关地址
     * 全被判成"没有 master"，整份 master 被丢进 purify 当媒体列表洗。
     */
    @JvmStatic
    fun isFollowableVariant(rawLine: String?): Boolean {
        val line = rawLine?.trim() ?: return false
        if (line.isEmpty() || line.startsWith("#")) return false
        val path = line.substringBefore('#').substringBefore('?')
        val name = path.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        if (dot < 0) return true
        val ext = name.substring(dot + 1).lowercase()
        return ext == "m3u8" || !MEDIA_FILE_EXT.contains(ext)
    }

    /** 媒体分片行数（非空且不以 `#` 开头）—— 一份清单里"真正的片段"有几个 */
    @JvmStatic
    fun mediaLineCount(content: String?): Int {
        if (content == null) return 0
        var n = 0
        val lines = content.split('\n')
        for (line in lines) {
            val t = line.trim()
            if (t.isNotEmpty() && !t.startsWith("#")) n++
        }
        return n
    }

    /**
     * 本次**真的**洗掉了几条。用净化前后的行数差，不读 `M3u8.currentAdCount`。
     *
     * 那个字段是 static：purify 开头清零、中间累加、几条"洗太多就退回原文"的保护靠把它
     * 重置为 0 来表达失败。两集同时进净化时后一次会覆盖前一次，读它的线程拿到的
     * 可能是别人的账，也可能是已经作废的账。行数差只属于本次调用，且天然不会被覆盖。
     * 负数（净化结果反而多了行）按 0 算：宁可当作"没洗"走直链。
     */
    @JvmStatic
    fun removedCount(original: String?, purified: String?): Int {
        if (purified == null) return 0
        val diff = mediaLineCount(original) - mediaLineCount(purified)
        return if (diff > 0) diff else 0
    }
}
