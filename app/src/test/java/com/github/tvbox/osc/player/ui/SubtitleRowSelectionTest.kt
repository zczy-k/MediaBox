package com.github.tvbox.osc.player.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 在线字幕列表的选中判定：**有"所点行地址"就只认它**，老记录没有才退回文件名/发布页。
 *
 * 一份搜索结果里"同名不同内容"的字幕很常见（同一部片好几个上传者的版本），
 * 只比文件名会把好几条一起点亮 —— 用户看到的就不是"我选的那一条"（真机反馈）。
 */
class SubtitleRowSelectionTest {

    @Test
    fun nothingRemembered_marksNobody() {
        assertFalse(subtitleRowMatches("a.srt", "http://r/1", null, null, null))
        assertFalse(subtitleRowMatches("a.srt", "http://r/1", "", "", ""))
    }

    @Test
    fun pickUrl_winsAndIsExact_evenWhenNamesCollide() {
        // 两条同名不同内容的行:只有被点的那条亮
        assertTrue(subtitleRowMatches("x.srt", "xunlei:AAA", "x.srt", "", "xunlei:AAA"))
        assertFalse(subtitleRowMatches("x.srt", "xunlei:BBB", "x.srt", "", "xunlei:AAA"))
    }

    @Test
    fun legacyRecordWithoutPickUrl_fallsBackToNameThenRelease() {
        // 老记录只有"发布页|文件名":按文件名认
        assertTrue(subtitleRowMatches("movie.ass", "http://cdn/a", "movie.ass", "", ""))
        // 列表行是发布页、记的是压缩包里的文件名:按发布页地址认
        assertTrue(subtitleRowMatches(
            "Movie.2024", "https://assrt.net/xml/sub/627/627916.xml",
            "movie.ass", "https://assrt.net/xml/sub/627/627916.xml", "",
        ))
        assertFalse(subtitleRowMatches("other.srt", "http://cdn/b", "movie.ass", "http://rel/1", ""))
    }

    @Test
    fun emptyActiveValues_neverMark() {
        assertFalse(subtitleRowMatches("a.srt", "", "", "", ""))
        assertFalse(subtitleRowMatches(null, null, null, null, null))
    }
}
