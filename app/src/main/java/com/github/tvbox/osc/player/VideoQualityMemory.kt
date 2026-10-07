package com.github.tvbox.osc.player

import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.LOG
import org.json.JSONObject

/**
 * 每条线路的**实测**画质记忆(站点 key + 影片 id + 线路 flag → 宽高/码率/可信度/时间)。
 *
 * <p>为什么存"实测值"而不是排序结果:同一站点同一集的线路档位是稳定的,探测一次即可复用;
 * 而排序会随设备能力、网络、站点改版变化,存排序等于把过期的决策一直用下去。
 *
 * <p>存储形态:单键 JSON,值为 `"宽,高,码率,可信度,时间戳"` 紧凑串(不用嵌套对象 ——
 * 免去类型登记,解析失败也只是丢一条记忆,不会连累其它条目)。
 *
 * <p>⚠️ 本类**不做单测**:`org.json` 在 unit test 下 `isReturnDefaultValues=true` 只返默认值,
 * 测了等于没测。口径由 [VideoQualityPolicyTest] 覆盖,这里只负责存取与过期裁剪。
 */
object VideoQualityMemory {

    private const val TTL_MS = 30L * 24 * 60 * 60 * 1000
    private const val MAX_ENTRIES = 2000

    private fun keyOf(siteKey: String, vodId: String, flag: String) = "$siteKey|$vodId|$flag"

    private fun now() = System.currentTimeMillis()

    /**
     * 记一条实测画质。**只在值真的变了时写盘** —— 起播状态回调高频触发,
     * 无条件写会把 MMKV 的异步写队列打满。
     */
    @JvmStatic
    fun record(siteKey: String, vodId: String, variant: VideoQualityPolicy.Variant) {
        if (siteKey.isEmpty() || vodId.isEmpty() || !variant.known || variant.flag.isEmpty()) return
        try {
            val all = loadAll()
            val key = keyOf(siteKey, vodId, variant.flag)
            val value = encode(variant, now())
            if (all.optString(key) == value) return
            all.put(key, value)
            persist(all)
            // ⚠️ 这条日志是「记忆为什么读不回来」的唯一可观测点,别删(前缀 echo-quality 已登记)。
            // v1.0.30 真机实测:写入侧日志齐全(write-key / resolved 都打出来了),
            // 读取侧 raw={} 恒为空,中间只有 persist 这一步 —— 它此前失败是静默的
            // (catch 里只有 LOG.d,不落文件日志),所以完全没痕迹。
            LOG.i("echo-quality record key=" + key + " value=" + value)
        } catch (t: Throwable) {
            // 记忆写不进去只影响下次优选,绝不能影响本次播放
            // 这里也用 LOG.i:写盘失败是"标签永不出现"的唯一成因,必须能在文件日志里看到
            LOG.i("echo-quality record FAILED key=" + siteKey + "|" + vodId + "|" + variant.flag +
                " err=" + t.javaClass.simpleName + ":" + t.message)
        }
    }

    /** 读一条;不存在 / 过期 / 格式损坏一律返回 null(调用方按"没记忆"走初筛) */
    @JvmStatic
    fun lookup(siteKey: String, vodId: String, flag: String): VideoQualityPolicy.Variant? {
        if (siteKey.isEmpty() || vodId.isEmpty() || flag.isEmpty()) return null
        return try {
            decode(loadAll().optString(keyOf(siteKey, vodId, flag)))
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 按 [flags] 顺序取出有记忆的候选,供 [VideoQualityPolicy.pickBest] 排序。
     * 记忆里没有的 flag 不会出现在结果里 —— 调用方负责把它们补成"未知"候选。
     */
    @JvmStatic
    fun lookupAll(siteKey: String, vodId: String, flags: List<String>): List<VideoQualityPolicy.Variant> {
        if (siteKey.isEmpty() || vodId.isEmpty() || flags.isEmpty()) return emptyList()
        return try {
            val all = loadAll()
            flags.mapNotNull { flag ->
                decode(all.optString(keyOf(siteKey, vodId, flag)))?.copy(flag = flag)
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    private fun encode(v: VideoQualityPolicy.Variant, at: Long): String =
        "${v.width},${v.height},${v.bitrate},${v.confidence.ordinal},$at"

    private fun decode(raw: String?): VideoQualityPolicy.Variant? {
        if (raw.isNullOrEmpty()) return null
        val parts = raw.split(',')
        if (parts.size < 5) return null
        val width = parts[0].toIntOrNull() ?: return null
        val height = parts[1].toIntOrNull() ?: return null
        if (width <= 0 || height <= 0) return null
        val bitrate = parts[2].toIntOrNull() ?: 0
        val ordinal = parts[3].toIntOrNull() ?: 0
        val at = parts[4].toLongOrNull() ?: return null
        if (now() - at > TTL_MS || at > now()) return null
        val confidence = VideoQualityPolicy.Confidence.values().getOrNull(ordinal)
            ?: VideoQualityPolicy.Confidence.UNKNOWN
        return VideoQualityPolicy.Variant(width, height, bitrate, confidence)
    }

    private fun loadAll(): JSONObject = JSONObject(KV.get(HawkConfig.VIDEO_QUALITY_MEMORY, ""))

    // ── 「已确认无资源」标记(方案 B 第二层,见 util/AvailabilityMemory)────────────
    //
    // 刻意**不复用**上面那张表:键形态不同(那边是"站点|片|线路"五段,这边"站点|片"
    // 两段)、TTL 不同(30 天 vs 90 天)、语义也不同(画质复用 vs 别再点空)。
    // 混在一张表里会让两边的裁剪逻辑互相踩 —— 画质记忆过期顺带把无资源标记清了,
    // 用户点的空就白点了。

    /** 一条无资源标记。 */
    data class UnavailableEntry(val key: String, val markedAt: Long, val siteName: String)

    private const val UNAVAILABLE_MAX = 3000

    @JvmStatic
    fun recordAvailability(key: String, markedAt: Long, siteName: String) {
        if (key.isEmpty()) return
        try {
            val all = loadAvailabilityAll()
            // 值格式 "时间戳,站名"。站名只是诊断用的冗余信息,允许为空。
            val value = markedAt.toString() + "," + siteName.replace(',', ' ')
            if (all.optString(key) == value) return
            all.put(key, value)
            persistAvailability(all)
        } catch (t: Throwable) {
            LOG.d("VideoQualityMemory", "recordAvailability failed: " + t.message)
        }
    }

    @JvmStatic
    fun lookupAvailability(key: String): UnavailableEntry? {
        if (key.isEmpty()) return null
        return try {
            val parts = loadAvailabilityAll().optString(key).split(',')
            if (parts.size < 2) return null
            val at = parts[0].toLongOrNull() ?: return null
            if (at > now()) return null
            UnavailableEntry(key, at, parts.drop(1).joinToString(","))
        } catch (t: Throwable) {
            null
        }
    }

    /** 取 TTL 内的全部标记。ttlMs 由调用方给 —— 不同调用口径不同(读写不对称)。 */
    @JvmStatic
    fun activeAvailability(ttlMs: Long): List<UnavailableEntry> {
        return try {
            val all = loadAvailabilityAll()
            val out = ArrayList<UnavailableEntry>()
            val keys = all.keys()
            val nowTs = now()
            while (keys.hasNext()) {
                val k = keys.next()
                val at = all.optString(k).split(',').firstOrNull()?.toLongOrNull() ?: continue
                if (nowTs - at in 0..ttlMs) out.add(UnavailableEntry(k, at, ""))
            }
            out
        } catch (t: Throwable) {
            emptyList()
        }
    }

    private fun loadAvailabilityAll(): JSONObject =
        JSONObject(KV.get(HawkConfig.VIDEO_AVAILABILITY_MEMORY, ""))

    /** 裁剪过期 + 超量后落盘(与画质记忆同套路:写时裁剪,读时只判单条)。 */
    private fun persistAvailability(all: JSONObject) {
        val kept = JSONObject()
        val nowTs = now()
        val keys = all.keys()
        val alive = ArrayList<Pair<String, Long>>()
        while (keys.hasNext()) {
            val k = keys.next()
            val at = all.optString(k).split(',').firstOrNull()?.toLongOrNull() ?: continue
            if (nowTs - at >= 0) alive.add(k to at)
        }
        // 超量丢最旧的(JSONObject 遍历顺序不保证时间序,必须显式排序)
        alive.sortBy { it.second }
        val overflow = (alive.size - UNAVAILABLE_MAX).coerceAtLeast(0)
        for (i in alive.indices) {
            if (i < overflow) continue
            kept.put(alive[i].first, all.optString(alive[i].first))
        }
        KV.put(HawkConfig.VIDEO_AVAILABILITY_MEMORY, kept.toString())
    }

    /** 裁剪过期 + 超量后落盘。裁剪放在写路径,读路径只判单条过期,避免每次读都全量遍历。 */
    private fun persist(all: JSONObject) {
        val kept = JSONObject()
        val nowTs = now()
        val keys = all.keys()
        val alive = ArrayList<String>()
        while (keys.hasNext()) {
            val k = keys.next()
            val at = all.optString(k).split(',').getOrNull(4)?.toLongOrNull() ?: 0L
            if (nowTs - at in 0..TTL_MS) alive.add(k)
        }
        // 超量时丢最旧的:alive 的顺序取决于 JSONObject 内部实现,故显式按时间戳排序后再截断
        alive.sortBy { all.optString(it).split(',').getOrNull(4)?.toLongOrNull() ?: 0L }
        val overflow = (alive.size - MAX_ENTRIES).coerceAtLeast(0)
        for (i in alive.indices) {
            if (i < overflow) continue
            kept.put(alive[i], all.optString(alive[i]))
        }
        val payload = kept.toString()
        val ok = KV.put(HawkConfig.VIDEO_QUALITY_MEMORY, payload)
        // ⚠️ 同上:写入失败必须留痕。MMKV.put 返回 false(编码失败/实例未就绪)时
        // 不会有任何异常抛出,而"标签不显示"正是它的唯一外部表现。
        // 顺带把长度也打出来 —— 长度为 0 是个很有用的信号(整表被裁没了)。
        LOG.i("echo-quality persist ok=" + ok + " n=" + kept.length() + " len=" + payload.length)
    }
}
