package com.github.tvbox.osc.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「清除缓存」白名单登记表。
 *
 * 锁的是最坏的那种错：**用户攒出来的资产被当成缓存清掉**。
 * 长期权重、封禁台账、画质实测、完整度、续播进度、音轨/字幕选择 —— 这些丢了不会自己长回来,
 * 只能重新踩一遍坑。所以新加台账时忘了登记，后果必须是"不会被清"（默认 USER），不是"被清掉"。
 */
class KvDataKindTest {

    @Test
    fun unregisteredKeys_defaultToUser_neverDeleted() {
        // 白名单删除:没登记的键一律不属于可再生,清除缓存碰不到
        assertEquals(DataKind.USER, KvDataKind.kindOf("brand_new_ledger_2026"))
        assertEquals(DataKind.USER, KvDataKind.kindOf(""))
    }

    @Test
    fun assetsAreNeverInTheRebuildableList() {
        val assets = listOf(
            "source_weight_abcd1234",
            "source_health_abcd1234",
            "source_health_nolist_abcd1234",
            "video_quality_memory",
            "video_availability_memory",
            "completeness_sets",
            "progress_index_abcd",
            "track_mem_源A|片9_text",
        )
        for (key in assets) {
            assertEquals("资产不许被清: $key", DataKind.ASSET, KvDataKind.kindOf(key))
            assertTrue(
                "$key 命中了可再生前缀",
                KvDataKind.rebuildablePrefixes().none { key.startsWith(it) },
            )
        }
    }

    @Test
    fun rebuildableFamilies_areTheOnlyOnesCleared() {
        val rebuildable = listOf(
            "search_cache_奈义日记",
            "jsRuntime_源A_key",
            "title_index_abcd",
            "line_health_abcd1234",
        )
        rebuildable.forEach { assertEquals("应可再生: $it", DataKind.REBUILDABLE, KvDataKind.kindOf(it)) }
        assertEquals(rebuildable.size, KvDataKind.rebuildablePrefixes().size)
    }

    @Test
    fun spiderBusinessData_isUserData_notCache() {
        // cache_* 名字像缓存,实际是 spider 存登录态/token 的地方 —— 清了要重新登录
        assertEquals(DataKind.USER, KvDataKind.kindOf("cache_some_spider"))
    }
}
