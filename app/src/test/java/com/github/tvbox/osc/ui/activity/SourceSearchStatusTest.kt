package com.github.tvbox.osc.ui.activity

import com.github.tvbox.osc.ui.activity.SourceSearchStatus.Line
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 换源那一行的措辞判据(2026-10-16)。锁的是"四个事实 → 六种话"这张表,
 * 尤其锁住两条真机踩过的:①不许再出现兑现不了的 `x/323` 之类总分;
 * ②"搜过了确实没有"不能退回"点击搜索其他片源"催用户反复点。
 */
class SourceSearchStatusTest {

    @Test
    fun `还没搜过又没结果时只给入口`() {
        assertEquals(Line.NOT_SEARCHED, SourceSearchStatus.line(false, false, asked = 0, found = 0))
    }

    @Test
    fun `刚启动一个源都没问到`() {
        assertEquals(Line.SEARCHING_START, SourceSearchStatus.line(true, true, asked = 0, found = 0))
    }

    @Test
    fun `在搜但还没出结果时报已问到第几个而不是报总分`() {
        assertEquals(Line.SEARCHING_NONE, SourceSearchStatus.line(true, true, asked = 108, found = 0))
    }

    @Test
    fun `在搜且已有候选时报发现数`() {
        assertEquals(Line.SEARCHING_FOUND, SourceSearchStatus.line(true, true, asked = 124, found = 3))
        // 进度与状态撕裂(progress=null 而 searching=true)时也要说"在搜",不能停在校入口提示
        assertEquals(Line.SEARCHING_FOUND, SourceSearchStatus.line(true, true, asked = 0, found = 3))
    }

    @Test
    fun `搜完有结果时报来源数`() {
        assertEquals(Line.FOUND, SourceSearchStatus.line(true, false, asked = 108, found = 3))
        // 候选来自上一场的缓存、这场还没搜过:照样是"有源",不是"没搜过"
        assertEquals(Line.FOUND, SourceSearchStatus.line(false, false, asked = 0, found = 2))
    }

    @Test
    fun `搜过了确实没有就不能再催用户去搜`() {
        assertEquals(Line.NONE_FOUND, SourceSearchStatus.line(true, false, asked = 156, found = 0))
    }

    @Test
    fun `按钮字样有源或已搜过就叫搜源`() {
        // 既没源也还没搜过 ⇒ 这一步是"换源"的入口
        assertEquals(false, SourceSearchStatus.useSearchVerb(searchedOnce = false, found = 0))
        // 用户 2026-10-16 的原始判据:下面已经有源可点
        assertEquals(true, SourceSearchStatus.useSearchVerb(searchedOnce = false, found = 3))
        // 补的角落:搜完 0 个,点了只是再问一批,不该再写"换源"
        assertEquals(true, SourceSearchStatus.useSearchVerb(searchedOnce = true, found = 0))
    }
}
