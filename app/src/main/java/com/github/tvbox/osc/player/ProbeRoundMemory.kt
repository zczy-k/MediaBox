package com.github.tvbox.osc.player

import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.LOG
import org.json.JSONObject

/**
 * 「这个源的这条片探测无望」的轮次台账(判据在 [ProbeRoundPolicy],这里只管存取)。
 *
 * <p>存储形态与画质记忆同款:单键 JSON,值为 `"最后时刻,轮次"` 紧凑串。
 * 刻意**不复用** [VideoQualityMemory] 那张表 —— 键形态不同(这里两段、那边三段)、
 * 生命周期不同(7 天 vs 30 天)、语义更相反(一边是"测到了的资产",一边是"没测到的计数");
 * 混表会让两边的裁剪互相踩(画质条目过期顺带把计数清了,或反之)。
 *
 * <p>它是 [com.github.tvbox.osc.util.DataKind.REBUILDABLE]:丢了最坏是多探两遍,
 * 所以「修复工具」可以清 —— 与实测画质(资产,永不碰)分得很开。
 *
 * <p>⚠️ 本类不做单测:`org.json` 在 unit test 下只返默认值,测了等于没测;口径由
 * [ProbeRoundPolicy] 覆盖。
 */
object ProbeRoundMemory {

    private const val MAX_ENTRIES = 1500

    private fun keyOf(siteKey: String, vodId: String) = "$siteKey|$vodId"

    /** 已跑过的轮数(不存在 / 已过期 / 脏数据一律 0 ⇒ 照常给一次机会) */
    @JvmStatic
    fun rounds(siteKey: String, vodId: String): Int {
        if (siteKey.isEmpty() || vodId.isEmpty()) return 0
        return try {
            decode(loadAll().optString(keyOf(siteKey, vodId)))
        } catch (t: Throwable) {
            0
        }
    }

    /** 一遍深探跑完时记账:`measured` 是这一遍真的测出来的条数 */
    @JvmStatic
    fun finishRound(siteKey: String, vodId: String, measured: Int) {
        if (siteKey.isEmpty() || vodId.isEmpty()) return
        try {
            val all = loadAll()
            val key = keyOf(siteKey, vodId)
            val next = ProbeRoundPolicy.nextRounds(decode(all.optString(key)), measured)
            val now = System.currentTimeMillis()
            // 清零等于把这一条从表里删掉:留着"0 轮"只会让表越积越大而没有信息量
            if (next <= 0) {
                if (!all.has(key)) return
                all.remove(key)
            } else {
                val value = now.toString() + "," + next
                if (all.optString(key) == value) return
                all.put(key, value)
            }
            persist(all, now)
            LOG.i("echo-quality probe-rounds key=" + key + " rounds=" + next)
        } catch (t: Throwable) {
            LOG.i("echo-quality probe-rounds FAILED key=" + keyOf(siteKey, vodId) +
                " err=" + t.javaClass.simpleName + ":" + t.message)
        }
    }

    private fun decode(raw: String?): Int {
        if (raw.isNullOrEmpty()) return 0
        val parts = raw.split(',')
        if (parts.size < 2) return 0
        val at = parts[0].toLongOrNull() ?: return 0
        val rounds = parts[1].toIntOrNull() ?: return 0
        val now = System.currentTimeMillis()
        // 时间倒挂(改系统时钟)或结论过期 ⇒ 当作没记过
        if (at > now || now - at > ProbeRoundPolicy.TTL_MS) return 0
        return rounds.coerceAtLeast(0)
    }

    private fun loadAll(): JSONObject {
        val raw = KV.get(HawkConfig.VIDEO_PROBE_ROUNDS, "")
        if (raw.isNullOrBlank()) return JSONObject()
        return try {
            JSONObject(raw)
        } catch (t: Throwable) {
            LOG.i("echo-quality probe-rounds load reset: " + t.javaClass.simpleName + ":" + t.message)
            JSONObject()
        }
    }

    /** 裁剪过期 + 超量后落盘(与画质记忆同款:写时裁剪,读时只判单条) */
    private fun persist(all: JSONObject, nowTs: Long) {
        val kept = JSONObject()
        val keys = all.keys()
        val alive = ArrayList<Pair<String, Long>>()
        while (keys.hasNext()) {
            val k = keys.next()
            val at = all.optString(k).split(',').firstOrNull()?.toLongOrNull() ?: continue
            if (at in 1..nowTs && nowTs - at <= ProbeRoundPolicy.TTL_MS) alive.add(k to at)
        }
        alive.sortBy { it.second }
        val overflow = (alive.size - MAX_ENTRIES).coerceAtLeast(0)
        for (i in alive.indices) {
            if (i < overflow) continue
            kept.put(alive[i].first, all.optString(alive[i].first))
        }
        KV.put(HawkConfig.VIDEO_PROBE_ROUNDS, kept.toString())
    }
}
