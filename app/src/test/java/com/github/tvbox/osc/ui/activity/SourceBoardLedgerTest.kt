package com.github.tvbox.osc.ui.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 换源列表的匿名号台账（2026-10-15：号绑身份，不绑渲染位置）。
 *
 * 判错的后果很具体：号若会漂，用户说"切回刚才那个源"就没有抓手；
 * 号若不回收，隐藏过的源会留下跳号 —— 这是刻意接受的代价，测试把它钉住，防止"顺手改成连续"。
 */
class SourceBoardLedgerTest {

    @Test
    fun `第一次进表才占号，重复登记拿回同一个号`() {
        val board = SourceBoardLedger()
        assertEquals(1, board.assign("A"))
        assertEquals(2, board.assign("B"))
        assertEquals("重复追加不换号", 1, board.assign("A"))
        assertEquals("查号与定号同源", 2, board.ordinalOf("B"))
        assertEquals(2, board.size())
    }

    /** 真机 bug 的根形态：同一源每次回包重排都换个数字，用户就认不出它了。 */
    @Test
    fun `号与进入顺序有关、与渲染顺序无关`() {
        val board = SourceBoardLedger()
        listOf("X", "Y", "Z").forEach { board.assign(it) }
        // 模拟"下一轮回包把顺序打乱"：号必须一个都不变
        listOf("Z", "X", "Y").forEach { board.assign(it) }
        assertEquals(1, board.ordinalOf("X"))
        assertEquals(2, board.ordinalOf("Y"))
        assertEquals(3, board.ordinalOf("Z"))
    }

    /** 没进过表的源不该拿到号（返回 0，调用方据此**不渲染**，而不是显示"源0"）。 */
    @Test
    fun `未登记的源没有号`() {
        val board = SourceBoardLedger()
        assertEquals(0, board.ordinalOf("nope"))
        assertEquals(0, board.ordinalOf(null))
        assertEquals(0, board.ordinalOf(""))
        assertEquals("脏 key 不占号", 0, board.assign(null))
        assertEquals(0, board.assign(""))
        assertEquals(0, board.size())
    }

    @Test
    fun `换片整张作废，号从1重新开始`() {
        val board = SourceBoardLedger()
        board.assign("A"); board.assign("B")
        board.clear()
        assertEquals(0, board.size())
        assertEquals("下一场观看是新的身份台账", 1, board.assign("A"))
    }

    /** 号从 1 起、不跳不重（隐藏/移除都不会回收号）。 */
    @Test
    fun `号连续分配且永不回收`() {
        val board = SourceBoardLedger()
        val assigned = (1..5).map { board.assign("s$it") }
        assertEquals(listOf(1, 2, 3, 4, 5), assigned)
        assertTrue(board.ordinalOf("s3") > 0)
    }
}
