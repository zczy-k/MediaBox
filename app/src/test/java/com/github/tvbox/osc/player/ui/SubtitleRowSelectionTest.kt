package com.github.tvbox.osc.player.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 在线字幕列表的选中判定:记忆里的"文件名"和"发布页地址"**任一对得上**就算选中。
 *
 * 搜索结果一行往往是一个发布页(点进去才是 .srt/.ass),只比文件名的话,
 * 从压缩包里挑的那条在列表上永远标不出来(真机反馈"看不出选了哪条")。
 */
class SubtitleRowSelectionTest {

    @Test
    fun nothingRemembered_marksNobody() {
        assertFalse(subtitleRowMatches("a.srt", "http://r/1.xml", null, null))
        assertFalse(subtitleRowMatches("a.srt", "http://r/1.xml", "", ""))
    }

    @Test
    fun fileRow_matchesByName() {
        assertTrue(subtitleRowMatches("movie.chs.srt", "http://cdn/x.srt", "movie.chs.srt", ""))
        assertFalse(subtitleRowMatches("other.srt", "http://cdn/y.srt", "movie.chs.srt", ""))
    }

    @Test
    fun releaseRow_matchesByReleaseUrl_evenThoughNameDiffers() {
        // 列表行 = 发布页(名字是片名),记忆里 = 该发布页里被挑中的那个文件
        assertTrue(
            subtitleRowMatches(
                "Movie.2024.1080p", "https://assrt.net/xml/sub/802/802010.xml",
                "movie.chs.ass", "https://assrt.net/xml/sub/802/802010.xml",
            ),
        )
    }

    @Test
    fun emptyActiveValues_neverMatchAnEmptyRowUrl() {
        // 记忆缺失(空串)不许把"没有地址"的行误标成选中
        assertFalse(subtitleRowMatches("a.srt", "", "", ""))
        assertFalse(subtitleRowMatches(null, null, "", ""))
    }
}
