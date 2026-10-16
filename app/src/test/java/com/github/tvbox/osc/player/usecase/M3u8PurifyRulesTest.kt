package com.github.tvbox.osc.player.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * m3u8 去广告判据。每一条都对应真机上放过的一次火:
 * master 被当媒体列表洗、"洗掉几条"读到一个已经被别的调用覆盖的数。
 */
class M3u8PurifyRulesTest {

    private val master = """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=4000000,RESOLUTION=1920x1080
        /1080p/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720
        /720p/index.m3u8
    """.trimIndent()

    private val mediaList = """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXTINF:10.0,
        seg0.ts
        #EXTINF:10.0,
        seg1.ts
        #EXT-X-ENDLIST
    """.trimIndent()

    @Test
    fun `master 与媒体清单分得开`() {
        assertTrue(M3u8PurifyRules.isMasterPlaylist(master))
        assertFalse(M3u8PurifyRules.isMasterPlaylist(mediaList))
        assertFalse(M3u8PurifyRules.isMasterPlaylist(null))
    }

    @Test
    fun `master 在第 50 行之后也认得出来`() {
        val sb = StringBuilder("#EXTM3U\n")
        for (i in 1..60) sb.append("#EXT-X-COMMENT-$i\n")
        sb.append("#EXT-X-STREAM-INF:BANDWIDTH=1\nvar.m3u8\n")
        assertTrue(M3u8PurifyRules.isMasterPlaylist(sb.toString()))
    }

    @Test
    fun `子清单只拒绝明确写了媒体文件后缀的`() {
        assertTrue(M3u8PurifyRules.isFollowableVariant("index.m3u8"))
        // 这三条是原来"只接受结尾 .m3u8"漏掉的写法
        assertTrue(M3u8PurifyRules.isFollowableVariant("/video/abcdef"))
        assertTrue(M3u8PurifyRules.isFollowableVariant("index.php?type=m3u8"))
        assertTrue(M3u8PurifyRules.isFollowableVariant("index.m3u8?tok=/a/b"))
        // 直接写媒体文件名:拉回来是二进制,不能当文本洗
        assertFalse(M3u8PurifyRules.isFollowableVariant("https://cdn.x/1080p.mp4"))
        assertFalse(M3u8PurifyRules.isFollowableVariant("/raw/stream.ts"))
        assertFalse(M3u8PurifyRules.isFollowableVariant("#EXT-X-ENDLIST"))
        assertFalse(M3u8PurifyRules.isFollowableVariant(""))
        assertFalse(M3u8PurifyRules.isFollowableVariant(null))
    }

    @Test
    fun `分片条数只数真正的片段行`() {
        assertEquals(2, M3u8PurifyRules.mediaLineCount(mediaList))
        assertEquals(0, M3u8PurifyRules.mediaLineCount(null))
        assertEquals(2, M3u8PurifyRules.mediaLineCount("#EXTM3U\r\n#EXTINF:1\r\na.ts\r\n\r\nb.ts\r\n"))
    }

    @Test
    fun `洗掉几条用本次前后的差值不读静态计数`() {
        val purified = "#EXTM3U\n#EXTINF:10.0,\nseg1.ts\n#EXT-X-ENDLIST\n"
        assertEquals(1, M3u8PurifyRules.removedCount(mediaList, purified))
        // purify 判定"洗太多"会退回原文 ⇒ 差值为 0 ⇒ 调用方走直链,不会拿着原样内容再跑一遍代理
        assertEquals(0, M3u8PurifyRules.removedCount(mediaList, mediaList))
        // purify 返回 null(内容不是 m3u8)时不能算成"洗掉了全部"
        assertEquals(0, M3u8PurifyRules.removedCount(mediaList, null))
        // 净化结果反而多了行(改写过 URL 的边界情况)按"没洗"处理
        assertEquals(0, M3u8PurifyRules.removedCount("#EXTM3U\na.ts\n", mediaList))
    }
}
