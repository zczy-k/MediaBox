package com.github.tvbox.osc.util

import org.json.JSONArray
import org.json.JSONObject

/**
 * "详情回包缺 list"按源记忆的**唯一持久化出口**(S2 升级判据,2026-10-09)。
 *
 * <p>## 存什么
 * 源 key → "详情回包缺 list"的时间戳列表。判定规则在 [DetailNoListPolicy]
 * (30 天窗口内 ≥2 次 ⇒ 按索引型对待);本类只管记/查/清与落盘。
 *
 * <p>## 消费方(两处,读写各一)
 * <ul>
 *   <li>**记**:[com.github.tvbox.osc.sourcedata.SourceResultParser] 的详情通道
 *       解析出"合法 JSON 缺 list"时记一票(搜索通道不记 —— 搜索缺 list 另有语义);</li>
 *   <li>**用**:[com.github.tvbox.osc.ui.activity.DetailViewModel.loadDetail] 的
 *       indexSource 判据升级为 `isIndexSource() || isIndexLike(sourceKey)` ——
 *       命中即跳过详情请求,省掉注定白等的 20~45 秒,直接走聚合搜索;</li>
 *   <li>**清**:详情一旦取到内容立即 [clear] —— 源在正常服务详情,记忆作废。
 *       这是唯一的"恢复"通道:画像只影响"要不要先发详情",不影响搜索通道,
 *       所以源改版后仍能被聚合搜索找到并经详情回包洗白。</li>
 * </ul>
 *
 * <p>## 为什么独立于 [SourceHealthMemory]
 * 那是"源坏了要屏蔽/降权"的**负面台账**(判据是失败证据,产出封禁态);
 * 本存储是"源的取数方式是索引型"的**行为画像**(判据是应用层回包形状,只改路径)。
 * 语义不同、证据不同、处置不同 —— 混在一起会让"该源被屏蔽了吗"和
 * "该源要不要发详情"互相污染口径。
 *
 * <p>## 为什么按点播源地址分桶(与 [SourceHealthMemory] 同款)
 * 源 key 只属于具体的源集合:换了地址(换仓)后旧 key 基本不存在,
 * 不分桶会出现"换仓后莫名一大批源被跳过详情"。
 *
 * <p>## 写盘策略
 * 详情请求是低频事件(每次点卡片/换源至多一次),且本存储量很小(≤400 源 × ≤5 条时间戳),
 * 直接**同步落盘**,不需要 [SourceHealthMemory] 那套 2 秒合并窗口 —— 简单可靠优先。
 */
object DetailNoListMemory {

    /** 台账最多保留多少个源(超出按"最近证据时间最旧"淘汰) */
    private const val MAX_SOURCES = 400

    private val lock = Any()

    /** 内存态(与盘上同构)。null = 尚未装载 */
    private var cache: MutableMap<String, MutableList<Long>>? = null

    /** 归属的桶(点播源地址的 MD5);地址变化时换桶 */
    private var cacheBucket: String? = null

    private fun now() = System.currentTimeMillis()

    // ==================== 记/清 ====================

    /** 记一票"该源详情回包缺 list"(解析层在详情通道发现时调用) */
    @JvmStatic
    fun record(sourceKey: String?) {
        val key = sourceKey?.trim().orEmpty()
        if (key.isEmpty()) return
        val t = now()
        synchronized(lock) {
            val bucket = load()
            val strikes = bucket[key] ?: ArrayList()
            val updated = DetailNoListPolicy.record(strikes, t)
            bucket[key] = ArrayList(updated)
            trim(bucket)
            dirty = true
            flushLocked()
            LOG.i(
                "echo-dnolist record source=" + key + " strikes=" + updated.size +
                    " indexLike=" + DetailNoListPolicy.isIndexLike(updated, t)
            )
        }
    }

    /**
     * 详情取到了内容 ⇒ 该源的记忆作废(源在正常服务详情,别再跳过它)。
     * 只在真的有内容时调用;空详情不清 —— 空详情正是证据本身。
     */
    @JvmStatic
    fun clear(sourceKey: String?) {
        val key = sourceKey?.trim().orEmpty()
        if (key.isEmpty()) return
        synchronized(lock) {
            val bucket = load()
            if (!bucket.containsKey(key)) return
            bucket.remove(key)
            dirty = true
            flushLocked()
            LOG.i("echo-dnolist clear source=" + key)
        }
    }

    // ==================== 查询 ====================

    /** 该源是否应按索引型对待(跳过详情请求,直接聚合搜索) */
    @JvmStatic
    fun isIndexLike(sourceKey: String?): Boolean {
        val key = sourceKey?.trim().orEmpty()
        if (key.isEmpty()) return false
        val t = now()
        synchronized(lock) {
            val bucket = load()
            val strikes = bucket[key] ?: return false
            if (DetailNoListPolicy.isIndexLike(strikes, t)) return true
            // 证据全过期:顺手清掉,台账瘦身(低频路径,不值得为它单独排落盘任务)
            if (DetailNoListPolicy.hasNoFreshStrike(strikes, t)) {
                bucket.remove(key)
                dirty = true
                flushLocked()
            }
            return false
        }
    }

    // ==================== 装载/落盘 ====================

    private fun bucketKey(): String? {
        val api = try {
            KV.get(HawkConfig.API_URL, "")
        } catch (th: Throwable) {
            ""
        }
        if (api.isNullOrEmpty()) return null
        return HawkConfig.SOURCE_HEALTH + "_nolist_" + MD5.string2MD5(api)
    }

    /** 取当前桶的内存态(必要时装载)。调用方**必须**持 [lock] */
    private fun load(): MutableMap<String, MutableList<Long>> {
        val bucket = bucketKey() ?: return HashMap()
        if (cache != null && cacheBucket == bucket) return cache!!
        cache = parse(read(bucket))
        cacheBucket = bucket
        dirty = false
        return cache!!
    }

    private fun read(bucket: String): String = try {
        KV.get(bucket, "")
    } catch (th: Throwable) {
        ""
    }

    private fun parse(raw: String?): MutableMap<String, MutableList<Long>> {
        val out = HashMap<String, MutableList<Long>>()
        if (raw.isNullOrEmpty()) return out
        try {
            val root = JSONObject(raw)
            val sources = root.optJSONObject("s") ?: return out
            for (key in sources.keys()) {
                val arr = sources.optJSONArray(key) ?: continue
                val strikes = ArrayList<Long>(arr.length())
                for (i in 0 until arr.length()) {
                    val v = arr.optLong(i)
                    if (v > 0) strikes.add(v)
                }
                if (strikes.isNotEmpty()) out[key] = strikes
            }
        } catch (th: Throwable) {
            // 载荷坏掉不能连带把功能打死:当作空台账重新积累(与 SourceHealthMemory 同款兜底)
            LOG.i("echo-dnolist load-failed: " + th.message)
        }
        return out
    }

    private fun encode(bucket: Map<String, List<Long>>): String {
        val sources = JSONObject()
        for ((key, strikes) in bucket) {
            if (strikes.isEmpty()) continue
            val arr = JSONArray()
            for (v in strikes) arr.put(v)
            sources.put(key, arr)
        }
        return JSONObject().put("s", sources).toString()
    }

    /** 必须持 [lock] */
    private fun flushLocked() {
        if (!dirty) return
        val bucket = cacheBucket ?: return
        try {
            KV.put(bucket, encode(cache ?: return))
        } catch (th: Throwable) {
            LOG.i("echo-dnolist flush-failed: " + th.message)
            return
        }
        dirty = false
    }

    /** 台账容量兜底:超出按最近证据时间最旧淘汰 */
    private fun trim(bucket: MutableMap<String, MutableList<Long>>) {
        if (bucket.size <= MAX_SOURCES) return
        val keep = bucket.entries
            .sortedByDescending { it.value.maxOrNull() ?: 0L }
            .take(MAX_SOURCES)
        val kept = HashMap<String, MutableList<Long>>(keep.size)
        for ((key, strikes) in keep) kept[key] = strikes
        bucket.clear()
        bucket.putAll(kept)
    }
}
