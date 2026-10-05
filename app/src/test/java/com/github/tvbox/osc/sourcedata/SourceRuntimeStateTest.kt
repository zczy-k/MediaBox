package com.github.tvbox.osc.sourcedata

import com.github.tvbox.osc.bean.AbsSortXml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 门面搬出去的运行期状态:`sortCache` 的上限与 access-order 语义、`clearRuntimeCache` 的两条清空。
 *
 * 上限与 access-order 是 2026-09-13(`aa5b132`)定的语义 —— 换源清理和"连 get 都算访问"都靠它,
 * 搬位置时容易只搬字段、把 `removeEldestEntry` 或 access-order 参数丢了。
 *
 * 未覆盖(已知缺口):"5 个 Loader 拿到的是同一个 map 实例"这条接线**没有**用例 —— 读它需要
 * new `SourceViewModel`(构造器初始化 `MutableLiveData`,纯 JVM 单测拿不到 Looper,见
 * `history/features.md` 记的同一个坑),而 Loader 没有实例就拿不到字段。改动
 * `SourceViewModel` 构造器里那 5 行传参时要人工确认传的是 `SourceRuntimeState` 的字段本身。
 */
class SourceRuntimeStateTest {

    private fun put(key: String) {
        synchronized(SourceRuntimeState.sortCache) {
            SourceRuntimeState.sortCache[key] = AbsSortXml()
        }
    }

    private fun get(key: String): AbsSortXml? = synchronized(SourceRuntimeState.sortCache) {
        SourceRuntimeState.sortCache[key]
    }

    @Test
    fun sortCacheKeepsOnlyFiveEntries() {
        SourceRuntimeState.clearRuntimeCache()
        (1..5).forEach { put("src$it") }
        assertSize(5)
        put("src6") // 第 6 条挤掉最早的一条(插入序 src1..src5 + src6 ⇒ src1 被淘汰)
        assertSize(5)
        assertNull(get("src1"))
        assertTrue(get("src6") != null)
    }

    @Test
    fun sortCacheGetRefreshesRecency() {
        SourceRuntimeState.clearRuntimeCache()
        (1..5).forEach { put("src$it") }
        // get 是访问:读过 src1 后它变成最近使用,被淘汰的应是 src2。
        // 用 assertSame 而不是取值比对:LinkedHashMap 的 get 必须返回同一个对象引用,
        // 顺带证明这里读到的就是那份共享 map(不是副本)
        assertSame(SourceRuntimeState.sortCache["src1"], get("src1"))
        put("src6")
        assertTrue(get("src1") != null)
        assertNull(get("src2"))
    }

    @Test
    fun clearRuntimeCacheEmptiesBothCaches() {
        put("src1")
        SourceRuntimeState.extendCache["extend-key"] = "{}"
        SourceRuntimeState.clearRuntimeCache()
        assertSize(0)
        assertEquals(0, SourceRuntimeState.extendCache.size)
        // 清空不是换新 map:实例同一,Loader 手里那份引用才跟着空
        assertTrue(mapInstanceStable())
    }

    private fun assertSize(expected: Int) = synchronized(SourceRuntimeState.sortCache) {
        assertEquals(expected, SourceRuntimeState.sortCache.size)
    }

    private fun mapInstanceStable(): Boolean {
        val before = System.identityHashCode(SourceRuntimeState.sortCache)
        SourceRuntimeState.clearRuntimeCache()
        return before == System.identityHashCode(SourceRuntimeState.sortCache)
    }
}
