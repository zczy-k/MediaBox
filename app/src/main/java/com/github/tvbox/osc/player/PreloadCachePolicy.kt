package com.github.tvbox.osc.player

/** 预解析直链缓存的口径:独立为纯函数供 JVM 单测锁真值表 */
object PreloadCachePolicy {
    /** 直链有效期:短了命中率低(上次 60s 看一会儿就过期),长了失效概率高 —— 失效由"用缓存起播失败即重取流"兜底 */
    const val TTL_MS = 180_000L
    /** 池容量:够"当前集±1"与连续点选 */
    const val MAX_ENTRIES = 5

    @JvmStatic
    fun isExpired(nowMs: Long, cachedAtMs: Long): Boolean = nowMs - cachedAtMs > TTL_MS

    @JvmStatic
    fun sizeExceeded(size: Int): Boolean = size > MAX_ENTRIES
}
