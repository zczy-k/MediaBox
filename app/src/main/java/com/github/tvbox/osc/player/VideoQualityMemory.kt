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
        } catch (t: Throwable) {
            // 记忆写不进去只影响下次优选,绝不能影响本次播放
            LOG.d("VideoQualityMemory", "record failed: " + t.message)
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
        KV.put(HawkConfig.VIDEO_QUALITY_MEMORY, kept.toString())
    }
}
