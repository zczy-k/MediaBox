package com.github.tvbox.osc.ui.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 换源列表的匿名号台账 + 🟡/隐藏状态机（2026-10-15/16）。
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
        board.recordFailure("A")
        board.clear()
        assertEquals(0, board.size())
        assertEquals("状态也一起清零", 0, board.failCount("A"))
        assertEquals("下一场观看是新的身份台账", 1, board.assign("A"))
    }

    // ---------- 🟡 与隐藏的状态机 ----------

    /**
     * 用户口径（2026-10-16 二次定案）：**独立尝试失败两次就隐藏**，不再要求"两条不同来源的证据"。
     * 旧判据在真机上永远触发不了 —— 他对着同一个起点源手动重播三次（v1.1.49
     * 11:15:41 / 11:16:21 / 11:17:27），三次全落在"他自己点过去"那一侧，
     * 另一侧的布尔永远填不上，源就一直 🟡 挂着，他看到的正是"说过次数的判据没生效"。
     */
    @Test
    fun `两次独立失败就隐藏，一次只标黄`() {
        val board = SourceBoardLedger()
        board.assign("A")
        assertTrue(board.recordFailure("A"))
        assertEquals("第一次失败只标黄，还留着给他切", SourceBoardLedger.Mark.SUSPECT, board.markOf("A"))
        assertTrue(board.isVisible("A"))
        assertEquals(1, board.failCount("A"))

        assertTrue("第二次失败 ⇒ 状态变了", board.recordFailure("A"))
        assertEquals(SourceBoardLedger.Mark.HIDDEN, board.markOf("A"))
        assertFalse(board.isVisible("A"))
        assertEquals(setOf("A"), board.hiddenKeys())
        assertEquals(2, board.failCount("A"))
    }

    /**
     * 第三次失败只是"状态没再变"，但**次数必须继续累加**：调用侧日志要靠 failCount 回答
     * "这个源失败到第几次了、为什么没继续动作"（旧实现状态不变时连一行日志都不打）。
     */
    @Test
    fun `第三次失败不再改状态但次数继续加`() {
        val board = SourceBoardLedger()
        board.assign("A")
        board.recordFailure("A"); board.recordFailure("A")
        assertFalse("已隐藏,状态无法再变", board.recordFailure("A"))
        assertEquals(3, board.failCount("A"))
        assertEquals(SourceBoardLedger.Mark.HIDDEN, board.markOf("A"))
    }

    /**
     * "别把一次抖动算成两条证据"这条保护搬到了调用侧（一次起播尝试只贡献一条事实：
     * `DetailViewModel.failureCountedForAttempt`）。台账只认调用次数，所以这里锁的是
     * "同一个源记一次 ⇒ 只标黄"，而不是"来自两条路径"。
     */
    @Test
    fun `一次尝试只贡献一格`() {
        val board = SourceBoardLedger()
        board.assign("A")
        board.recordFailure("A")
        assertEquals("一次尝试 = 一格 = 只标黄", SourceBoardLedger.Mark.SUSPECT, board.markOf("A"))
        assertEquals(1, board.failCount("A"))
    }

    /** 🟡 与隐藏都必须可逆：抖过一次不代表这一场都不行。 */
    @Test
    fun `播通一次就把状态撤销`() {
        val board = SourceBoardLedger()
        board.assign("A")
        board.recordFailure("A"); board.recordFailure("A")
        assertEquals(SourceBoardLedger.Mark.HIDDEN, board.markOf("A"))
        assertTrue(board.recordSuccess("A"))
        assertEquals(SourceBoardLedger.Mark.NONE, board.markOf("A"))
        assertEquals("次数也清零", 0, board.failCount("A"))
        assertTrue("隐藏的源播通后原样回来", board.isVisible("A"))
        assertEquals("号不回收", 1, board.ordinalOf("A"))
        assertFalse("本来就没状态时不算变化", board.recordSuccess("A"))
    }

    @Test
    fun `脏 key 不参与状态`() {
        val board = SourceBoardLedger()
        assertFalse(board.recordFailure(null))
        assertFalse(board.recordFailure(""))
        assertFalse(board.recordSuccess(null))
        assertEquals(SourceBoardLedger.Mark.NONE, board.markOf(null))
        assertTrue(board.isVisible(null))
        assertEquals(0, board.failCount(null))
    }

    /** 号绑身份之后，隐藏会留下跳号 —— 这是刻意接受的代价，钉住别"顺手改成连续"。 */
    @Test
    fun `号连续分配且永不回收`() {
        val board = SourceBoardLedger()
        val assigned = (1..5).map { board.assign("s$it") }
        assertEquals(listOf(1, 2, 3, 4, 5), assigned)
        board.recordFailure("s3"); board.recordFailure("s3")
        assertFalse(board.isVisible("s3"))
        assertEquals("隐藏不腾号", 3, board.ordinalOf("s3"))
    }
}
