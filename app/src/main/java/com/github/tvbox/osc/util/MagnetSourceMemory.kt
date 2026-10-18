package com.github.tvbox.osc.util

import org.json.JSONArray
import org.json.JSONObject

/**
 * "该源只给磁力/种子"按源记忆的**唯一持久化出口**。
 *
 * <p>## 存什么
 * 源 key → "该源详情基本只给磁力"的时间戳列表。判定规则在 [MagnetSourcePolicy]
 * (30 天窗口内 ≥2 次 ⇒ 按磁力源对待);本类只管记/查/清与落盘。
 *
 * <p>## 消费方(读写各一,与 [DetailNoListMemory] 同构)
 * <ul>
 *   <li>**记**:[com.github.tvbox.osc.sourcedata.PushDetailResolver] 的详情通道
 *       摘除磁力时,若命中 [MagnetSourcePolicy.isMagnetOnlyEvidence] 记一票;</li>
 *   <li>**用**:[com.github.tvbox.osc.ui.activity.DetailViewModel.loadDetail] 的
 *       indexSource 判据扩一项 `|| MagnetSourceMemory.isMagnetOnly(sourceKey)` ——
 *       命中即跳过详情请求,省掉注定白等的 20~45 秒(该源只会再给磁力),直接走聚合搜索;</li>
 *   <li>**清**:详情一旦取到**正常线路**立即 [clear] —— 源在正常服务,画像作废。
 *       这是唯一的"恢复"通道:画像只影响"要不要先发详情",不影响搜索通道,
 *       所以源改版后仍能被聚合搜索找到并经详情回包洗白。</li>
 * </ul>
 *
 * <p>## 为什么独立于 [SourceHealthMemory](与 [DetailNoListMemory] 同一套分工)
 * 那是"源坏了要屏蔽/降权"的**负面台账**(判据是失败证据,产出封禁态);
 * 本存储是"源的交付形态就是磁力"的**行为画像**(判据是回包内容形状,只改路径)。
 * 语义不同、证据不同、处置不同 —— 混在一起会让"该源被屏蔽了吗"和
 * "该源要不要发详情"互相污染口径。
 *
 * <p>⚠️ 因此:**磁力源被摘空不能记 `recordBoardFailure`**。它是行为特征,不是故障;
 * 记了会让该源在健康台账里累积失败证据,凑够门槛就被封 6 小时 —— 而那是个误伤
 * (该源可能只是"这部片只有磁力")。
 *
 * <p>## 为什么按点播源地址分桶(与 [SourceHealthMemory]/[DetailNoListMemory] 同款)
 * 源 key 只属于具体的源集合:换了地址(换仓)后旧 key 基本不存在,
 * 不分桶会出现"换仓后莫名一大批源被跳过详情"。
 *
 * <p>## 写盘策略
 * 详情请求是低频事件(每次点卡片/换源至多一次),且本存储量很小(≤400 源 × ≤5 条时间戳),
 * 直接**同步落盘**,不需要 [SourceHealthMemory] 那套 2 秒合并窗口 —— 简单可靠优先。
 */
object MagnetSourceMemory {

    /** 台账最多保留多少个源(超出按"最近证据时间最旧"淘汰) */
    private const val MAX_SOURCES = 400

    private val lock = Any()

    /** 内存态(与盘上同构)。null = 尚未装载 */
    private var cache: MutableMap<String, MutableList<Long>>? = null

    /** 归属的桶(点播源地址的 MD5);地址变化时换桶 */
    private var cacheBucket: String? = null

    /** 有未落盘的改动 */
    private var dirty = false

    private fun now() = System.currentTimeMillis()

    // ==================== 记/清 ====================

    /**
     * 记一票"该源详情基本只给磁力"(详情通道摘除磁力时,命中证据门槛才调用)。
     *
     * @param magnetLines 本次摘掉的磁力条目数
     * @param totalLines  摘除前的全部可播条目数
     */
    @JvmStatic
    fun record(sourceKey: String?, magnetLines: Int, totalLines: Int) {
        val key = sourceKey?.trim().orEmpty()
        if (key.isEmpty()) return
        if (!MagnetSourcePolicy.isMagnetOnlyEvidence(magnetLines, totalLines)) return
        val t = now()
        synchronized(lock) {
            val bucket = load()
            val strikes = bucket[key] ?: ArrayList()
            val updated = MagnetSourcePolicy.record(strikes, t)
            bucket[key] = ArrayList(updated)
            trim(bucket)
            dirty = true
            flushLocked()
            LOG.i(
                "echo-magnet record source=" + key + " strikes=" + updated.size +
                    " magnet=" + magnetLines + "/" + totalLines +
                    " magnetOnly=" + MagnetSourcePolicy.isMagnetOnly(updated, t)
            )
        }
    }

    /**
     * 详情取到了**正常线路** ⇒ 该源的画像作废(源在正常交付内容,别再跳过它)。
     * 只在真的有正常内容时调用;摘空不清 —— 摘空正是证据本身。
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
            LOG.i("echo-magnet clear source=" + key)
        }
    }

    // ==================== 查询 ====================

    /** 该源是否应按"磁力源"对待(跳过详情请求,直接聚合搜索) */
    @JvmStatic
    fun isMagnetOnly(sourceKey: String?): Boolean {
        val key = sourceKey?.trim().orEmpty()
        if (key.isEmpty()) return false
        val t = now()
        synchronized(lock) {
            val bucket = load()
            val strikes = bucket[key] ?: return false
            if (MagnetSourcePolicy.isMagnetOnly(strikes, t)) return true
            // 证据全过期:顺手清掉,台账瘦身(低频路径,不值得为它单独排落盘任务)
            if (MagnetSourcePolicy.hasNoFreshStrike(strikes, t)) {
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
        return SubscriptionBucket.resolveKey(HawkConfig.SOURCE_HEALTH + "_magnet_", api)
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
            LOG.i("echo-magnet load-failed: " + th.message)
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
            LOG.i("echo-magnet flush-failed: " + th.message)
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
