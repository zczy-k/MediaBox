package com.github.tvbox.osc.util

import org.json.JSONArray
import org.json.JSONObject

/**
 * 「这个源答过话、但这部片它没有」的短路台账(判据见 [NoHitSourcePolicy])。
 *
 * <p>存储形态照 [SearchResultCache]:按订阅分桶的单键 JSON,键为归一化片名,值
 * `{"ts": 时刻, "sources": [源 key...]}`。**刻意不与命中缓存合表** ——
 * 那份表的不变量是"只收命中"(videos 非空),把负条目混进去会让它的读侧
 * (`seedCandidatesFromCache`)和写侧一起被污染。两者生命周期一致(都 24 小时)但语义相反,
 * 分表才能让"这个源其实有"随时覆盖"这个源没有"。
 *
 * <p>全程 try-catch:载荷坏掉/写失败一律降级为"没有短路信息",照常真搜。
 * 这份数据是 [DataKind.REBUILDABLE]:丢了最坏是把源池重问一遍。
 */
object NoHitSourceCache {

    private const val PREFIX = "nohit_"

    private fun bucketKey(): String? {
        val api = try {
            KV.get(HawkConfig.API_URL, "")
        } catch (th: Throwable) {
            ""
        }
        return SubscriptionBucket.resolveKey(PREFIX, api)
    }

    /** 这一轮可以短路的源(答过话且确实没有)。未过期、载荷完好才返回 */
    @JvmStatic
    fun knownAbsent(normalizedTitle: String): Set<String> {
        val bucket = bucketKey() ?: return emptySet()
        if (normalizedTitle.isEmpty()) return emptySet()
        return try {
            val raw = KV.get(bucket, "")
            if (raw.isNullOrEmpty()) return emptySet()
            val root = JSONObject(raw)
            val entry = root.optJSONObject(normalizedTitle) ?: return emptySet()
            val now = System.currentTimeMillis()
            if (!NoHitSourcePolicy.isFresh(entry.optLong("ts"), now)) {
                root.remove(normalizedTitle)
                KV.put(bucket, root.toString())
                return emptySet()
            }
            val arr = entry.optJSONArray("sources") ?: return emptySet()
            val out = HashSet<String>(arr.length())
            for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.isNotBlank() }?.let { out.add(it) }
            }
            out
        } catch (th: Throwable) {
            LOG.i("echo-searchplan nohit-load-failed: " + th.message)
            emptySet()
        }
    }

    /**
     * 记若干"答过话但零同名命中"的源。与既有名单**并集**,超量丢最旧那端(见 [NoHitSourcePolicy.merge])。
     *
     * <p>只在搜索收口/冻结/换片这些边界调用(调用方负责合并窗口),别逐源落盘 ——
     * 每次写都要序列化整张表。
     */
    @JvmStatic
    fun record(normalizedTitle: String, sourceKeys: Collection<String>) {
        val bucket = bucketKey() ?: return
        if (normalizedTitle.isEmpty() || sourceKeys.isEmpty()) return
        try {
            val now = System.currentTimeMillis()
            val root = try {
                JSONObject(KV.get(bucket, ""))
            } catch (th: Throwable) {
                JSONObject()
            }
            // 顺手清过期 + 片名数封顶(与命中缓存同款淘汰:留最近的那些)
            for (k in root.keys().asSequence().toList()) {
                val e = root.optJSONObject(k)
                if (e == null || !NoHitSourcePolicy.isFresh(e.optLong("ts"), now)) root.remove(k)
            }
            val old = root.optJSONObject(normalizedTitle)?.optJSONArray("sources")
            val oldList = ArrayList<String>()
            if (old != null) for (i in 0 until old.length()) oldList.add(old.optString(i))
            val merged = NoHitSourcePolicy.merge(oldList, sourceKeys.toList())
            val arr = JSONArray()
            merged.forEach { arr.put(it) }
            root.put(normalizedTitle, JSONObject().put("ts", now).put("sources", arr))
            val trimmed = JSONObject()
            root.keys().asSequence()
                .map { k -> k to (root.optJSONObject(k)?.optLong("ts") ?: 0L) }
                .sortedByDescending { it.second }
                .take(NoHitSourcePolicy.MAX_TITLES)
                .forEach { (k, _) -> root.optJSONObject(k)?.let { trimmed.put(k, it) } }
            KV.put(bucket, trimmed.toString())
            LOG.i("echo-searchplan nohit-saved kw=" + normalizedTitle +
                " n=" + merged.size + " titles=" + trimmed.length())
        } catch (th: Throwable) {
            LOG.i("echo-searchplan nohit-save-failed: " + th.message)
        }
    }

    /**
     * 某个源后来**真给出了**同名条目 ⇒ 把它从"没有"名单里摘掉。
     *
     * <p>这一步不能省:片名写法/站方库是会变的,不摘就会把一个曾经零命中的源永久挡住,
     * 而那正是这次要修的"重进同一部片不重问"的反面 —— 该重问的时候必须重问。
     */
    @JvmStatic
    fun forget(normalizedTitle: String, sourceKey: String) {
        val bucket = bucketKey() ?: return
        if (normalizedTitle.isEmpty() || sourceKey.isEmpty()) return
        try {
            val raw = KV.get(bucket, "")
            if (raw.isNullOrEmpty()) return
            val root = JSONObject(raw)
            val entry = root.optJSONObject(normalizedTitle) ?: return
            val arr = entry.optJSONArray("sources") ?: return
            val kept = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val k = arr.optString(i)
                if (k.isNotBlank() && k != sourceKey) kept.add(k)
            }
            if (kept.size == arr.length()) return
            if (kept.isEmpty()) {
                root.remove(normalizedTitle)
            } else {
                val newArr = JSONArray()
                kept.forEach { newArr.put(it) }
                entry.put("sources", newArr)
            }
            KV.put(bucket, root.toString())
            LOG.i("echo-searchplan nohit-forget kw=" + normalizedTitle + " key=" + sourceKey)
        } catch (th: Throwable) {
            LOG.i("echo-searchplan nohit-forget-failed: " + th.message)
        }
    }
}
