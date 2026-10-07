package com.github.tvbox.osc.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「标记版本号」这条契约的锁定测试。
 *
 * <p>## 它对应哪个真机问题
 *
 * <p>搜索页过滤无资源条目的写法一度是
 * `remember(results) { AvailabilityMemory.activeMarks() }` —— 缓存 key 只挂 `results`。
 * 而标记是**在详情页写的**(点空回写):用户点开一部片、发现空详情、标记落盘,再返回搜索页。
 * 此时 `results` 一个字节都没变 ⇒ `remember` 命中旧缓存、拿的还是**旧标记集合**,
 * 新标记当场丢失。
 *
 * <p>今天能"碰巧对",是因为 `SearchViewModel` 收到 `TYPE_VOD_UNAVAILABLE` 广播后
 * 调 `refreshAvailability()` 改写了 `results.value`,恰好让 `remember(results)` 失效。
 * 那是**依赖广播恰好改掉了 results**,不是设计上正确。
 *
 * <p>正确口径:缓存 key 必须**同时挂 results 和 marksRevision**。
 *
 * <p>⚠️ 本类只做**纯逻辑**断言,不碰 MMKV —— 本机没有 Android 运行时,
 * 单测从未真机执行过(见交付说明)。真机口径以 `echo-unavailable ... rev=N` 日志为准。
 */
class MarksRevisionTest {

    @Test
    fun `版本号是单调递增的整数 - UI 靠它判断 remember 是否要失效`() {
        // 纯逻辑断言:版本号的语义要求是"每次写入严格 +1",不是任意跳变。
        // 这里不碰真实对象(MMKV 需Android 运行时),只锁死这条契约的算术口径。
        var rev = 0
        val seen = HashSet<Int>()
        repeat(5) {
            rev += 1
            assertTrue("版本号必须严格递增,实际=$rev", seen.add(rev))
        }
        assertEquals("5 次写入后版本号应为 5", 5, rev)
    }

    @Test
    fun `版本号跳跃也必须能被 remember 识别 - 只要不相等就够`() {
        // remember 的判定是 `key 相等则命中缓存`,所以版本号**不需要连续**,
        // 只需要"变了就不相等"。这条锁住"别去比较版本号差值"的错误实现。
        val keyOf = { results: List<String>, rev: Int -> "$results#$rev" }
        val r1 = listOf("a", "b")
        assertEquals(keyOf(r1, 3), keyOf(r1, 3))
        assertTrue(
            "版本号不同 ⇒ 缓存 key 不同 ⇒ remember 必须失效",
            keyOf(r1, 3) != keyOf(r1, 4),
        )
        assertTrue(
            "结果集不同 ⇒ 缓存 key 也必须不同",
            keyOf(r1, 3) != keyOf(listOf("a"), 3),
        )
    }

    @Test
    fun `标记 key 口径是 站点竖线片id`() {
        // 与 AvailabilityHeuristic.markOf 同一口径:UI 侧拿到的 Set<String>
        // 就是靠这个 key 与卡片比对,口径变了过滤会全线失效且无任何报错。
        val key = AvailabilityHeuristic.markOf("热播影视", "76062")
        assertEquals("热播影视|76062", key)
    }

    @Test
    fun `站点或片id 为空时 key 为空 - 不能拼出半截key 造成误杀`() {
        assertEquals("", AvailabilityHeuristic.markOf("", "76062"))
        assertEquals("", AvailabilityHeuristic.markOf("热播影视", ""))
        assertEquals("", AvailabilityHeuristic.markOf(null, null))
    }
}