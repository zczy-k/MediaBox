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

    // ==================== emptyReason(2026-10-18) ====================

    /**
     * 空态说"没有"还是"没问到"。真机《怦然心动》问过 58 个源、其中 49 个超时、
     * 只有 1 个答话,却统一报「此影片暂无法播放」—— 用户读到的是"这片不存在",
     * 而该做的事其实是换个网络/时段重试。
     */
    @Test
    fun `多数源超时要说没问到`() {
        // 真机现场:58 问 / 49 超时
        assertEquals(
            SourceSearchStatus.EmptyReason.TIMEOUT_DOMINANT,
            SourceSearchStatus.emptyReason(asked = 58, timedOut = 49),
        )
        // 恰好一半也算(判据是 timedOut*2 >= asked)
        assertEquals(
            SourceSearchStatus.EmptyReason.TIMEOUT_DOMINANT,
            SourceSearchStatus.emptyReason(asked = 10, timedOut = 5),
        )
    }

    @Test
    fun `源都答了话确实没有就说没有`() {
        // 过半超时才算"没问到";低于这个比例说明源确实答了话、只是没有这部片
        assertEquals(
            SourceSearchStatus.EmptyReason.NO_SOURCE,
            SourceSearchStatus.emptyReason(asked = 10, timedOut = 4),
        )
        assertEquals(
            SourceSearchStatus.EmptyReason.NO_SOURCE,
            SourceSearchStatus.emptyReason(asked = 100, timedOut = 0),
        )
    }

    @Test
    fun `一个源都没问过时按没有处理`() {
        // asked=0 说明还没开始问,不能说"多数超时";且这条路径本来也不该走到空态
        assertEquals(
            SourceSearchStatus.EmptyReason.NO_SOURCE,
            SourceSearchStatus.emptyReason(asked = 0, timedOut = 0),
        )
    }
}
