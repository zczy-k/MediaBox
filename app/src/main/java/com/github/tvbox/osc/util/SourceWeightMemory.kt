package com.github.tvbox.osc.util

import android.os.Handler
import android.os.Looper
import org.json.JSONObject

/**
 * 源**连续权重**台账的**唯一持久化出口**(《源与线路的权重模型》P0 存储层,2026-10-13)。
 *
 * <p>## 存什么
 * 每个源一条 `{score, at, lastSearched}`:
 * <ul>
 *   <li>`score` / `at`:落账分与落账时刻(有效权重由 [SourceWeightPolicy.effective] 实时衰减算出,不落盘);</li>
 *   <li>`lastSearched`:上次被搜时刻 —— 供 [SourceWeightPolicy.floorQuota] 在权重并列时**轮换**,
 *       保证"最久没被搜的"优先,而不是每轮都选同样几个源。</li>
 * </ul>
 *
 * <p>## 为什么按点播源地址分桶
 * 与 [SourceHealthMemory] 同款:源 key 只属于具体的源集合,换仓(换地址)后旧 key 基本都不存在。
 * 不分桶会出现"换仓后莫名其妙一大批源权重是旧的"。口径与 `SearchHelper.SOURCES_FOR_SEARCH` 一致。
 *
 * <p>## 为什么写盘要延迟
 * 一轮搜索会连续给几十个源落账,若每次都"读全量 JSON → 改 → 写全量 JSON",开销随台账增长放大。
 * 改动先进内存,合并到 [FLUSH_DELAY_MS] 后一次落盘。代价是进程在这 2 秒内被杀会丢最后几条 ——
 * 可接受:权重本身就有时间衰减,丢几条不影响长期趋势。
 *
 * <p>⚠️ **不做单测**:本类依赖 Android `KV`(单测下不可用)。判据口径由 [SourceWeightPolicyTest] 覆盖,
 * 这里只负责存取与裁剪 —— 与 [SourceHealthMemory] / [VideoQualityMemory] 的分工一致。
 */
object SourceWeightMemory {

    /** 延迟落盘窗口(合并一轮搜索里的连续写入) */
    private const val FLUSH_DELAY_MS = 2000L

    /** 台账最多保留多少个源(超出按"最近落账时间最旧"淘汰) */
    private const val MAX_SOURCES = 400

    /**
     * 落盘体积哨兵(字符数)。正常用量在几十 KB,这道闸只拦"异常膨胀":
     * 权重键里塞进超长地址、源集合异常变大时,单键无限增长会实打实吃掉存储。
     */
    private const val MAX_JSON_CHARS = 1_000_000

    /** 哨兵淘汰的下限:再超也不把台账清空,免得一次异常就抹掉全部长期意见 */
    private const val MIN_KEEP_SOURCES = 50

    /** 与源健康台账同款:按点播源地址分桶,换仓后旧记忆自动作废 */
    private const val KEY_PREFIX = "source_weight_"

    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())

    /** 内存态(与盘上同构)。null = 尚未装载 */
    private var cache: MutableMap<String, SourceWeightPolicy.Weight>? = null

    /** 上次被搜时刻(源 key → ms);与权重分表存储,不进 [MAX_SOURCES] 的淘汰口径 */
    private var lastSearched: MutableMap<String, Long>? = null

    private var dirty = false
    private var cacheBucket: String? = null

    private fun now() = System.currentTimeMillis()

    // ==================== 常开(2026-10-15 起不再受「自动屏蔽反复不可用的源」管辖) ====================

    /**
     * 权重台账**没有开关**,一直记。
     *
     * <p>此前它读 [HawkConfig.AUTO_BLOCK_BAD_SOURCES]:用户关掉"自动屏蔽坏源"本来是说不想被剔除,
     * 却连带把"越用越准的排序"整体冻结 —— 权重只改位次,从不剔除任何源,与那把开关没有因果关系。
     * 同一份证据的另一个视图([SourceHealthMemory] 的二值封禁)才归那把开关管。
     *
     * <p>副作用是好事:关着屏蔽用一段时间,再打开时排序与封禁都已有账可依。
     */

    // ==================== 记录 ====================

    /** 记一次证据(更新权重)。 */
    @JvmStatic
    fun record(sourceKey: String?, kind: SourceWeightPolicy.EvidenceKind) {
        val key = sourceKey?.trim().orEmpty()
        if (key.isEmpty()) return
        val t = now()
        synchronized(lock) {
            val map = load()
            val before = map[key]
            val after = SourceWeightPolicy.record(before, kind, t)
            if (after == before) return
            map[key] = after
            trim(map)
            dirty = true
            LOG.i(
                "echo-weight record key=" + key + " kind=" + kind +
                    " delta=" + SourceWeightPolicy.deltaFor(kind) +
                    " eff=" + SourceWeightPolicy.scoreOf(after, t) + " -> " + after.score +
                    " n=" + (after.ok + after.bad).toInt() +
                    " long=" + (SourceWeightPolicy.longTermScore(after.ok, after.bad) ?: -1.0)
            )
        }
        scheduleFlush()
    }

    /**
     * 记"这个源本轮被搜了"(不管成败)—— 供保底配额的**轮换**用。
     *
     * <p>与 [record] 分开:被搜是**调度事实**,不是质量证据,不该改动 score。
     */
    @JvmStatic
    fun markSearched(sourceKey: String?) {
        val key = sourceKey?.trim().orEmpty()
        if (key.isEmpty()) return
        val t = now()
        synchronized(lock) {
            val map = loadLastSearched()
            if (map[key] == t) return
            map[key] = t
            trimLastSearched(map)
            dirty = true
        }
        scheduleFlush()
    }

    // ==================== 查询 ====================

    /** 单源有效权重(0..100);无记录 = [SourceWeightPolicy.NEUTRAL]。 */
    @JvmStatic
    fun scoreOf(sourceKey: String?): Int {
        val key = sourceKey?.trim().orEmpty()
        if (key.isEmpty()) return SourceWeightPolicy.NEUTRAL
        val t = now()
        synchronized(lock) {
            return SourceWeightPolicy.scoreOf(load()[key], t)
        }
    }

    /**
     * 批量读有效权重:一次装载 + 一次解析,供 [SearchBatchPolicy] 排序。
     *
     * @param keys 源 key 集合
     * @return key → 有效权重;缺失的源不在结果里(调用方按中性处理)
     */
    @JvmStatic
    fun scoresOf(keys: Collection<String>): Map<String, Int> {
        if (keys.isEmpty()) return emptyMap()
        val t = now()
        synchronized(lock) {
            val map = load()
            val out = HashMap<String, Int>()
            for (k in keys) {
                if (k.isEmpty()) continue
                val w = map[k] ?: continue
                out[k] = SourceWeightPolicy.scoreOf(w, t)
            }
            return out
        }
    }

    /** 批量读"上次被搜时刻";无记录返回 0(供保底配额轮换排序,越小越优先)。 */
    @JvmStatic
    fun lastSearchedAt(keys: Collection<String>): Map<String, Long> {
        if (keys.isEmpty()) return emptyMap()
        synchronized(lock) {
            val map = loadLastSearched()
            val out = HashMap<String, Long>()
            for (k in keys) {
                if (k.isEmpty()) continue
                val at = map[k] ?: continue
                out[k] = at
            }
            return out
        }
    }

    /** 已屏蔽以外的"低权重源"数量(设置页诊断用)。 */
    @JvmStatic
    fun lowWeightCount(): Int {
        val t = now()
        synchronized(lock) {
            return load().values.count {
                SourceWeightPolicy.tier(it, t) == SourceWeightPolicy.Tier.RED
            }
        }
    }

    // ==================== 解除 ====================

    /**
     * 全部复位到中性。
     *
     * <p>⚠️ **故意不接进设置页的「清除缓存」**(原注释写着"清缓存用",但全仓没有调用点 ——
     * 那不是漏接,是不该接):长期权重是 App 用出来的资产,「清除缓存」的语义是"腾空间、
     * 丢了能自己长回来的东西"。可"哪个源好用"恰恰是**长不回来**的 —— 清一次等于把用户
     * 攒了几十轮的判断力扔掉,反而更慢。将来要放,也该是一个独立的「重置源权重」入口。
     */
    @JvmStatic
    fun clear() {
        synchronized(lock) {
            cache = HashMap()
            lastSearched = HashMap()
            dirty = true
        }
        flushNow()
        LOG.i("echo-weight clear")
    }

    // ==================== 装载/落盘 ====================

    private fun bucketKey(): String? {
        val api = try {
            KV.get(HawkConfig.API_URL, "")
        } catch (th: Throwable) {
            ""
        }
        return SubscriptionBucket.resolveKey(KEY_PREFIX, api)
    }

    /** 取权重表(必要时装载)。调用方**必须**持 [lock] */
    private fun load(): MutableMap<String, SourceWeightPolicy.Weight> {
        ensureLoaded()
        return cache ?: HashMap()
    }

    /** 取"上次被搜"表(必要时装载)。调用方**必须**持 [lock] */
    private fun loadLastSearched(): MutableMap<String, Long> {
        ensureLoaded()
        return lastSearched ?: HashMap()
    }

    private fun ensureLoaded() {
        val bucket = bucketKey() ?: run {
            if (cache == null) cache = HashMap()
            if (lastSearched == null) lastSearched = HashMap()
            return
        }
        if (cache != null && cacheBucket == bucket) return
        if (dirty && cacheBucket != null) flushLocked()
        val parsed = parse(read(bucket))
        cache = parsed.first
        lastSearched = parsed.second
        cacheBucket = bucket
        dirty = false
    }

    private fun read(bucket: String): String = try {
        KV.get(bucket, "")
    } catch (th: Throwable) {
        ""
    }

    private fun parse(raw: String?): Pair<MutableMap<String, SourceWeightPolicy.Weight>, MutableMap<String, Long>> {
        val weights = HashMap<String, SourceWeightPolicy.Weight>()
        val searched = HashMap<String, Long>()
        if (raw.isNullOrEmpty()) return weights to searched
        try {
            val root = JSONObject(raw)
            val wJson = root.optJSONObject("w")
            if (wJson != null) {
                for (key in wJson.keys()) {
                    val obj = wJson.optJSONObject(key) ?: continue
                    weights[key] = SourceWeightPolicy.Weight(
                        score = obj.optInt("s", SourceWeightPolicy.NEUTRAL),
                        at = obj.optLong("at"),
                        // 老版本盘上没有这两个字段:optDouble 拿不到就是 0 ⇒ 无长期意见,
                        // 行为与加长期分之前完全一致(向后兼容,不迁移)。
                        ok = obj.optDouble("ok", 0.0),
                        bad = obj.optDouble("bad", 0.0),
                    )
                }
            }
            val sJson = root.optJSONObject("ls")
            if (sJson != null) {
                for (key in sJson.keys()) {
                    val at = sJson.optLong(key)
                    if (at > 0) searched[key] = at
                }
            }
        } catch (th: Throwable) {
            // 载荷坏掉不能连带把功能打死:当作空台账重新积累(与 SourceHealthMemory 同款兜底)
            LOG.i("echo-weight load-failed: " + th.message)
        }
        return weights to searched
    }

    private fun encode(): String {
        val weights = cache ?: return ""
        val root = JSONObject()
        val wJson = JSONObject()
        for ((key, w) in weights) {
            val obj = JSONObject().put("s", w.score).put("at", w.at)
            // 计数为零就不写:冷源保持老格式的尺寸,别为"还没攒出的长期分"白占字节
            if (w.ok >= 0.01) obj.put("ok", round2(w.ok))
            if (w.bad >= 0.01) obj.put("bad", round2(w.bad))
            wJson.put(key, obj)
        }
        val sJson = JSONObject()
        for ((key, at) in lastSearched ?: emptyMap<String, Long>()) {
            sJson.put(key, at)
        }
        root.put("w", wJson)
        root.put("ls", sJson)
        return root.toString()
    }

    /** 长期计数落盘只留 2 位小数:衰减后的尾巴对排序没有意义,却会拉长载荷。 */
    private fun round2(v: Double): Double = ((v * 100.0) + 0.5).toLong() / 100.0

    /**
     * 体积哨兵:载荷超预算就按"最近落账最旧"每轮砍掉 1/4,直到达标或只剩 [MIN_KEEP_SOURCES]。
     *
     * <p>"上次被搜"表跟着一起删,否则会留下没有权重却占着轮换名额的孤儿键。
     *
     * @return 淘汰条数(0 = 没动刀)
     */
    private fun enforceSizeLocked(): Int {
        val weights = cache ?: return 0
        var dropped = 0
        var guard = 0
        while (guard++ < 8 && weights.size > MIN_KEEP_SOURCES && encode().length > MAX_JSON_CHARS) {
            val keepCount = (weights.size * 3 / 4).coerceAtLeast(MIN_KEEP_SOURCES)
            val survivors = HashMap<String, SourceWeightPolicy.Weight>()
            val ordered = weights.entries.sortedByDescending { it.value.at }.take(keepCount)
            for (e in ordered) survivors[e.key] = e.value
            val doomed = weights.keys.filterNot { survivors.containsKey(it) }
            for (k in doomed) {
                weights.remove(k)
                lastSearched?.remove(k)
            }
            dropped += doomed.size
        }
        return dropped
    }

    /** 必须有 [lock] */
    private fun flushLocked() {
        val bucket = cacheBucket ?: return
        if (!dirty) return
        val dropped = enforceSizeLocked()
        if (dropped > 0) {
            LOG.i(
                "echo-weight size-trim dropped=" + dropped + " keep=" + (cache?.size ?: 0) +
                    " limit=" + MAX_JSON_CHARS
            )
        }
        try {
            KV.put(bucket, encode())
        } catch (th: Throwable) {
            LOG.i("echo-weight flush-failed: " + th.message)
            return
        }
        dirty = false
    }

    fun flushNow() {
        main.removeCallbacks(flushTask)
        synchronized(lock) { flushLocked() }
    }

    private fun scheduleFlush() {
        main.removeCallbacks(flushTask)
        main.postDelayed(flushTask, FLUSH_DELAY_MS)
    }

    private val flushTask = Runnable {
        synchronized(lock) { flushLocked() }
    }

    /** 权重表容量兜底:超出按最近落账时间淘汰 */
    private fun trim(map: MutableMap<String, SourceWeightPolicy.Weight>) {
        if (map.size <= MAX_SOURCES) return
        val keep = map.entries.sortedByDescending { it.value.at }.take(MAX_SOURCES)
        val kept = HashMap<String, SourceWeightPolicy.Weight>()
        for ((k, v) in keep) kept[k] = v
        map.clear()
        map.putAll(kept)
    }

    /** "上次被搜"表容量兜底:超出按最久未搜淘汰(与权重表同上限) */
    private fun trimLastSearched(map: MutableMap<String, Long>) {
        if (map.size <= MAX_SOURCES) return
        val keep = map.entries.sortedByDescending { it.value }.take(MAX_SOURCES)
        val kept = HashMap<String, Long>()
        for ((k, v) in keep) kept[k] = v
        map.clear()
        map.putAll(kept)
    }
}