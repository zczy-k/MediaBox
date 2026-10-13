package com.github.tvbox.osc.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 线路有效性判据的纯逻辑单测(2026-10-12)。
 *
 * <p>四条硬约束各自都要有反例钉住:
 * ① 只降权不屏蔽 ⇒ 可疑线路仍出现在候选里(由 [LineHealthSelector] 的
 *   "只重排不剔除"保证,本测试锁 [LineHealthPolicy.orderScore] 为负而非 -∞);
 * ② 按片绑定 ⇒ 键形必须含片 id(锁 [LineHealthMemory.keyOf]);
 * ③ 弱网期暂停累计 ⇒ `suppressCount` 只记证据不加 streak;
 * ④ 播放证据不参与源级封禁 ⇒ 本方案的源侧改动只调 recordAnswered(降权路径),
 *   不新增任何调 recordFail 的调用点 —— 这条由设计约束保证,无代码可测,
 *   但写入 KDoc 并在集成测试侧人工核对。
 */
class LineHealthPolicyTest {

    private val t0 = 1_700_000_000_000L

    // ---------- 可疑判定:连续失败 ----------

    @Test
    fun `单次失败不算可疑`() {
        val s = LineHealthPolicy.recordFail(LineHealthPolicy.State(), t0)
        assertFalse("单次失败可能是偶发", LineHealthPolicy.isSuspect(s, t0))
    }

    @Test
    fun `连续两次失败即可疑`() {
        var s = LineHealthPolicy.recordFail(LineHealthPolicy.State(), t0)
        s = LineHealthPolicy.recordFail(s, t0 + 1000)
        assertTrue(LineHealthPolicy.isSuspect(s, t0 + 1000))
        assertEquals(2, s.failStreak)
    }

    @Test
    fun `弱网期只记证据不累计streak`() {
        var s = LineHealthPolicy.State()
        repeat(5) { i -> s = LineHealthPolicy.recordFail(s, t0 + i * 1000, suppressCount = true) }
        assertEquals("抑制期内不得累计 streak", 0, s.failStreak)
        // 证据仍在:失败时刻被更新,供"最近失败过"参与排序
        assertEquals(t0 + 4000, s.failAt)
        assertFalse(LineHealthPolicy.isSuspect(s, t0 + 4000))
    }

    @Test
    fun `抑制期后恢复正常累计`() {
        var s = LineHealthPolicy.State()
        // 抖动期内失败 1 次:只记证据,streak 保持 0
        s = LineHealthPolicy.recordFail(s, t0, suppressCount = true)
        assertEquals("抑制期不累计", 0, s.failStreak)
        // ⚠️ 抖动期过去后要**重新累计到阈值**才可疑:一次非抑制失败只把 streak 推到 1,
        //    仍然不是"可疑"(阈值是 2)。所以这里必须给两次 —— 第一版测试只给了一次,
        //    把"累计已恢复"和"已达阈值"混为一谈,断言错了。
        s = LineHealthPolicy.recordFail(s, t0)
        assertEquals(1, s.failStreak)
        assertFalse("只累计到 1 次还不够", LineHealthPolicy.isSuspect(s, t0))
        s = LineHealthPolicy.recordFail(s, t0 + 1)
        assertEquals(2, s.failStreak)
        assertTrue("抖动期过去后应重新累计到阈值", LineHealthPolicy.isSuspect(s, t0 + 1))
    }

    // ---------- 可疑判定:时间闸 ----------

    @Test
    fun `超过TTL后不再可疑`() {
        var s = LineHealthPolicy.State()
        s = LineHealthPolicy.recordFail(s, t0)
        s = LineHealthPolicy.recordFail(s, t0 + 1)
        assertTrue(LineHealthPolicy.isSuspect(s, t0 + 1))
        // ⚠️ 边界口径:判据是 `now - failAt > TTL`(严格大于),
        //    所以"刚好 TTL"仍可疑、必须再晚 1ms 才算过期。
        //    failAt = t0+1 ⇒ 过期点是 t0+1+TTL+1。
        val justExpired = t0 + 1 + LineHealthPolicy.SUSPECT_TTL_MS + 1
        assertFalse("过期必须恢复中性", LineHealthPolicy.isSuspect(s, justExpired))
    }

    @Test
    fun `失败后又成功过则不复疑`() {
        var s = LineHealthPolicy.State()
        s = LineHealthPolicy.recordFail(s, t0)
        s = LineHealthPolicy.recordFail(s, t0 + 1)
        assertTrue(LineHealthPolicy.isSuspect(s, t0 + 1))
        s = LineHealthPolicy.recordPlayed(s, t0 + 2000)
        assertFalse("失败后成功 = 偶发,不该继续压", LineHealthPolicy.isSuspect(s, t0 + 2000))
        assertEquals("成功必须归零 streak", 0, s.failStreak)
    }

    // ---------- 慢线路 ----------

    @Test
    fun `取流慢被识别并降权`() {
        val s = LineHealthPolicy.recordResolveMs(LineHealthPolicy.State(), LineHealthPolicy.SLOW_LINE_MS)
        assertTrue(LineHealthPolicy.isSlow(s))
        assertTrue("慢线路要排后", LineHealthPolicy.orderScore(s, t0) < 0)
    }

    @Test
    fun `未达阈值不算慢`() {
        val s = LineHealthPolicy.recordResolveMs(LineHealthPolicy.State(), LineHealthPolicy.SLOW_LINE_MS - 1)
        assertFalse(LineHealthPolicy.isSlow(s))
        assertEquals(0, LineHealthPolicy.orderScore(s, t0))
    }

    @Test
    fun `未知耗时不降权`() {
        assertEquals(0, LineHealthPolicy.orderScore(LineHealthPolicy.State(), t0))
    }

    @Test
    fun `可疑加慢压得最狠`() {
        val s = LineHealthPolicy.recordFail(
            LineHealthPolicy.recordResolveMs(LineHealthPolicy.State(), LineHealthPolicy.SLOW_LINE_MS), t0,
        )
        val s2 = LineHealthPolicy.recordFail(s, t0 + 1)
        assertTrue(LineHealthPolicy.isSuspect(s2, t0 + 1))
        assertTrue(LineHealthPolicy.isSlow(s2))
        assertEquals(-200, LineHealthPolicy.orderScore(s2, t0 + 1))
    }

    // ---------- 硬约束一:只降权不屏蔽 ----------

    /**
     * 锁死"只降权不屏蔽"这条硬约束。
     *
     * <p>排后用的是**有限负分**(-100/-50/-200),不是 MIN_VALUE ——
     * 语义上就是"排到队尾,但仍然会被试到"。若有人改成无限小 + 在调用侧 filter,
     * 本断言不会红,所以另用 [reorder_neverDropsCandidates] 锁调用侧行为。
     */
    @Test
    fun `降权分是有限值而非剔除标记`() {
        var s = LineHealthPolicy.State()
        s = LineHealthPolicy.recordFail(s, t0)
        s = LineHealthPolicy.recordFail(s, t0 + 1)
        val sc = LineHealthPolicy.orderScore(s, t0 + 1)
        assertTrue("必须是有限负分(排后),实际 $sc", sc > Int.MIN_VALUE && sc < 0)
    }

    // ---------- 硬约束一(调用侧):只重排,永不剔除 ----------

    /**
     * 锁死调用侧行为:坏线路**只是排后**,不能从候选里消失。
     *
     * <p>这是"只降权不屏蔽"在代码层的落点 —— 若有人把 reorder 改成 filter,
     * 或让返回列表短于入参,这里立刻红。
     */
    @Test
    fun `reorder_neverDropsCandidates`() {
        val flags = listOf("线路一", "线路二", "线路三")
        val keys = listOf("s|1|线路一", "s|1|线路二", "s|1|线路三")
        // 线路二可疑(连续失败 2 次)
        val scores = mapOf(
            "s|1|线路一" to 0,
            "s|1|线路二" to -100,
            "s|1|线路三" to 0,
        )
        val out = LineHealthPolicy.reorderByScore(flags, { i -> keys[i] }) { scores[it] ?: 0 }
        assertEquals("长度必须不变(不剔除)", flags.size, out.size)
        assertEquals("内容必须一致(不新增不丢失)", flags.toSet(), out.toSet())
        // 坏线路被排到最后一名
        assertEquals("线路二", out.last())
    }

    @Test
    fun `reorder_stableForEqualScores`() {
        val flags = listOf("a", "b", "c", "d")
        val keys = listOf("ka", "kb", "kc", "kd")
        // 全部同分 ⇒ 必须保持原序(站点展示顺序,不应被无故打乱)
        val scores = mapOf("ka" to 0, "kb" to 0, "kc" to 0, "kd" to 0)
        val out = LineHealthPolicy.reorderByScore(flags, { i -> keys[i] }) { scores[it] ?: 0 }
        assertEquals(flags, out)
    }

    @Test
    fun `reorder_partialScoresKeepRelativeOrder`() {
        val flags = listOf("a", "b", "c")
        val keys = listOf("ka", "kb", "kc")
        // 只把 a 压后,b/c 同分保持相对顺序
        val scores = mapOf("ka" to -50, "kb" to 0, "kc" to 0)
        val out = LineHealthPolicy.reorderByScore(flags, { i -> keys[i] }) { scores[it] ?: 0 }
        assertEquals(listOf("b", "c", "a"), out)
    }

    /**
     * ⚠️ 重复 flag 的回归测试(2026-10-12)。
     *
     * <p>为什么需要它:`reorderByScore` 的第一版 API 是 `(T) -> String` 取键,
     * 调用方只能用 `indexOf(item)` 从平行键列表里反查 —— 而**同一源站可能两次上报同名线路**,
     * 反查永远命中第一条,把第二条的分数错记到第一条上(两条一起被压或一起不被压)。
     * 改成按下标取键后此问题不存在,本测试钉住它。
     */
    @Test
    fun `reorder_duplicateFlagsUseTheirOwnScore`() {
        // 两条同名线路:第一条好(0),第二条坏(-100) —— 必须只压第二条
        val flags = listOf("线路一", "线路一", "线路二")
        val keys = listOf("s|1|线路一#0", "s|1|线路一#1", "s|1|线路二")
        val scores = mapOf("s|1|线路一#0" to 0, "s|1|线路一#1" to -100, "s|1|线路二" to 0)
        val out = LineHealthPolicy.reorderByScore(flags, { i -> keys[i] }) { scores[it] ?: 0 }
        assertEquals("长度不变", 3, out.size)
        // 第三个位置应是"被压后的那条"(keys[1] 对应坏掉的那一条,排到末尾)
        assertEquals(listOf("线路一", "线路二", "线路一"), out)
    }

    @Test
    fun `reorder_shortListUnchanged`() {
        val flags = listOf("only")
        // ⚠️ 空列表必须显式给类型参数:reorderByScore 是泛型 <T>,
        //    裸 emptyList() 无法推断 T(CI 报 "Cannot infer type for type parameter 'T'")。
        assertEquals(flags, LineHealthPolicy.reorderByScore(flags, { "k" }) { -100 })
        assertEquals(
            emptyList<String>(),
            LineHealthPolicy.reorderByScore(emptyList<String>(), { "" }) { -100 },
        )
    }

    // ---------- 硬约束二:键必须绑定片 id ----------

    /**
     * 锁死"按片绑定":键里三要素缺一不可判。
     *
     * <p>缺片 id 会让 A 片的坏线经验错用到 B 片(线路地址是源-specific 的,
     * 但同源不同片的可用性不同);缺 flag 则无法定位一条线路。
     */
    @Test
    fun `keyOf_requiresAllThreeParts`() {
        assertEquals("源A|片9|线路一", LineHealthMemory.keyOf("源A", "片9", "线路一"))
        assertEquals("", LineHealthMemory.keyOf("", "片9", "线路一"))
        assertEquals("", LineHealthMemory.keyOf("源A", "", "线路一"))
        assertEquals("", LineHealthMemory.keyOf("源A", "片9", ""))
        assertEquals("", LineHealthMemory.keyOf(null, null, null))
    }
}
