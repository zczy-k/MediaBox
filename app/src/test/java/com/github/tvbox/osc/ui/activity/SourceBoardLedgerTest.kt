package com.github.tvbox.osc.ui.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ---------- 🟡 与隐藏的状态机 ----------

    /**
     * 用户定的形态：自动链替他试过 → 标 🟡 但仍留着让他自己再试；
     * 他亲手切过去又失败 → 才从这一场里隐掉。
     */
    @Test
    fun `隐藏需要两条不同来源的失败证据`() {
        val board = SourceBoardLedger()
        board.assign("A")
        assertTrue(board.recordFailure("A", fromAutoChain = true))
        assertEquals("自动链试过 ⇒ 只标黄", SourceBoardLedger.Mark.SUSPECT, board.markOf("A"))
        assertTrue("标黄时仍然可见", board.isVisible("A"))

        assertTrue(board.recordFailure("A", fromAutoChain = false))
        assertEquals("用户又亲手试过一次 ⇒ 隐藏", SourceBoardLedger.Mark.HIDDEN, board.markOf("A"))
        assertFalse(board.isVisible("A"))
        assertEquals(setOf("A"), board.hiddenKeys())
    }

    /**
     * 为什么不用"失败两次"计数：同一次故障会被多条路径各报一次
     * （线路耗尽 + 详情超时 + 质量看门狗），计数就会把一次抖动当成两条证据。
     */
    @Test
    fun `同一条路的重复失败不会凑成隐藏`() {
        val board = SourceBoardLedger()
        board.assign("A")
        assertTrue(board.recordFailure("A", fromAutoChain = true))
        assertFalse("第二次同路：状态没变", board.recordFailure("A", fromAutoChain = true))
        assertFalse("第三次也是", board.recordFailure("A", fromAutoChain = true))
        assertEquals(SourceBoardLedger.Mark.SUSPECT, board.markOf("A"))
        assertTrue("仍然看得见", board.isVisible("A"))
    }

    /** 🟡 必须可逆：抖过一次不该被这一场判死。 */
    @Test
    fun `播通一次就把状态撤销`() {
        val board = SourceBoardLedger()
        board.assign("A")
        board.recordFailure("A", fromAutoChain = true)
        board.recordFailure("A", fromAutoChain = false)
        assertEquals(SourceBoardLedger.Mark.HIDDEN, board.markOf("A"))
        assertTrue(board.recordSuccess("A"))
        assertEquals(SourceBoardLedger.Mark.NONE, board.markOf("A"))
        assertTrue("隐藏的源播通后原样回来", board.isVisible("A"))
        assertEquals("号不回收", 1, board.ordinalOf("A"))
        assertFalse("本来就没状态时不算变化", board.recordSuccess("A"))
    }

    @Test
    fun `脏 key 不参与状态`() {
        val board = SourceBoardLedger()
        assertFalse(board.recordFailure(null, fromAutoChain = true))
        assertFalse(board.recordFailure("", fromAutoChain = false))
        assertFalse(board.recordSuccess(null))
        assertEquals(SourceBoardLedger.Mark.NONE, board.markOf(null))
        assertTrue(board.isVisible(null))
    }

    /** 号绑身份之后，隐藏会留下跳号 —— 这是刻意接受的代价，钉住别"顺手改成连续"。 */
    @Test
    fun `号连续分配且永不回收`() {
        val board = SourceBoardLedger()
        val assigned = (1..5).map { board.assign("s$it") }
        assertEquals(listOf(1, 2, 3, 4, 5), assigned)
        board.recordFailure("s3", fromAutoChain = true)
        board.recordFailure("s3", fromAutoChain = false)
        assertFalse(board.isVisible("s3"))
        assertEquals("隐藏不腾号", 3, board.ordinalOf("s3"))
    }
}
