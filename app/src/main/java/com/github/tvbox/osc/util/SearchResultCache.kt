package com.github.tvbox.osc.util

import com.github.tvbox.osc.bean.Movie
import com.google.gson.Gson
import org.json.JSONArray
import org.json.JSONObject

/**
 * 搜索结果的**关键词级本地缓存**(P3 stale-while-revalidate,2026-10-09)。
 *
 * <p>## 解决什么
 * 搜索是纯透传——每次 `search()` 都全量发网络请求,同词重搜/断网重进都要从零再来。
 * 本缓存按 (订阅地址, 归一化关键词) 存最近命中的列表,TTL 24 小时:
 * 同词重搜时先把缓存灌进结果区(秒开),新一轮真实搜索照常分批执行,回包经
 * `mergeResult` 并入覆盖 —— 缓存只是"先到的变体结果",与既有并入语义天然兼容。
 *
 * <p>## 陈旧性边界
 * <ul>
 *   <li>TTL 24h,过期条目读写时顺手清除;</li>
 *   <li>最多保留 20 个关键词,超出按时间淘汰;</li>
 *   <li>每个源最多缓存 30 条(防单源大结果撑爆 KV);</li>
 *   <li>只缓存**命中**(videos 非空的源):Empty/Timeout/Failed 不缓存,重搜照常真实进行;</li>
 *   <li>点空回写标记(AvailabilityMemory)独立于本缓存,陈旧条目仍会被列表粗筛滤掉。</li>
 * </ul>
 *
 * <p>## 可靠性
 * 读写全程 try-catch:载荷坏掉/序列化失败一律降级为"无缓存",绝不影响真实搜索。
 * 按订阅地址分桶([SourceHealthMemory] 同款原则):换仓后旧缓存自动作废。
 */
object SearchResultCache {

    private const val MAX_KEYWORDS = 20
    private const val MAX_VIDEOS_PER_SOURCE = 30
    private const val TTL_MS = 24L * 60 * 60 * 1000
    private val gson = Gson()

    private fun bucketKey(): String? {
        val api = try {
            KV.get(HawkConfig.API_URL, "")
        } catch (th: Throwable) {
            ""
        }
        return if (api.isNullOrEmpty()) null else "search_cache_" + MD5.string2MD5(api)
    }

    /**
     * 取缓存命中:sourceKey → 命中列表。未命中/过期/载荷损坏返回空 map。
     * 过期条目在读时顺手清除。
     */
    fun load(normalizedKeyword: String): Map<String, List<Movie.Video>> {
        val bucket = bucketKey() ?: return emptyMap()
        if (normalizedKeyword.isEmpty()) return emptyMap()
        return try {
            val raw = KV.get(bucket, "")
            if (raw.isNullOrEmpty()) return emptyMap()
            val root = JSONObject(raw)
            val entry = root.optJSONObject(normalizedKeyword) ?: return emptyMap()
            val ts = entry.optLong("ts")
            val now = System.currentTimeMillis()
            if (ts <= 0 || now - ts > TTL_MS) {
                root.remove(normalizedKeyword)
                KV.put(bucket, root.toString())
                return emptyMap()
            }
            val hitsObj = entry.optJSONObject("hits") ?: return emptyMap()
            val out = HashMap<String, List<Movie.Video>>()
            for (sourceKey in hitsObj.keys()) {
                val arr = hitsObj.optJSONArray(sourceKey) ?: continue
                val videos = ArrayList<Movie.Video>(arr.length())
                for (i in 0 until arr.length()) {
                    val v = runCatching { gson.fromJson(arr.optString(i), Movie.Video::class.java) }.getOrNull()
                    if (v != null && !v.id.isNullOrEmpty()) videos.add(v)
                }
                if (videos.isNotEmpty()) out[sourceKey] = videos
            }
            out
        } catch (th: Throwable) {
            LOG.i("echo-searchplan cache-load-failed: " + th.message)
            emptyMap()
        }
    }

    /** 保存一轮搜索的命中(只收 videos 非空的源);写失败静默降级 */
    fun save(normalizedKeyword: String, hits: Map<String, List<Movie.Video>>) {
        val bucket = bucketKey() ?: return
        if (normalizedKeyword.isEmpty() || hits.isEmpty()) return
        try {
            val now = System.currentTimeMillis()
            val root = try {
                JSONObject(KV.get(bucket, ""))
            } catch (th: Throwable) {
                JSONObject()
            }
            // 顺手清过期
            for (k in root.keys().asSequence().toList()) {
                val e = root.optJSONObject(k)
                if (e == null || now - e.optLong("ts") > TTL_MS) root.remove(k)
            }
            val hitsObj = JSONObject()
            for ((sourceKey, videos) in hits) {
                val arr = JSONArray()
                for (v in videos.take(MAX_VIDEOS_PER_SOURCE)) arr.put(gson.toJson(v))
                hitsObj.put(sourceKey, arr)
            }
            root.put(normalizedKeyword, JSONObject().put("ts", now).put("hits", hitsObj))
            // 容量上限:按时间留最近 MAX_KEYWORDS 个词
            val trimmed = JSONObject()
            root.keys().asSequence()
                .map { k -> k to (root.optJSONObject(k)?.optLong("ts") ?: 0L) }
                .sortedByDescending { it.second }
                .take(MAX_KEYWORDS)
                .forEach { (k, _) -> root.optJSONObject(k)?.let { trimmed.put(k, it) } }
            KV.put(bucket, trimmed.toString())
        } catch (th: Throwable) {
            LOG.i("echo-searchplan cache-save-failed: " + th.message)
        }
    }
}
