package com.github.tvbox.osc.player

import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.LOG
import org.json.JSONObject

/**
 * 线路健康记忆(2026-10-12):把"这部片的这条线路试过几次、成不成、快不快"**跨会话**记住。
 *
 * <p>## 补的空白
 * 此前线路级健康**只在单次播放会话内有效**(`PlaybackAttemptState.triedLineFlags`),
 * 于是换集、重开、下次播放时都要在同一条坏线路上重来一遍。
 * 100 部片积累的坏线经验一条都没留下。
 *
 * <p>## 键形与容量
 * 键 = `源key|片id|flag`(**按片绑定**,由用户确认;线路地址是源-specific 的,
 * 跨片/跨源复用是错的)。上限 [MAX_ENTRIES] 条,按时间淘汰 ——
 * 三维键会膨胀(330 源 × 每片 4 线 × 100 部片 ≈ 132k),不设上限会撑爆 KV。
 *
 * <p>## 只记"有问题"的
 * 成功是常态,记录它会让条目数乘以 3。所以 [recordPlayed] 只用于**归零已有条目**
 * (已有失败记录时才需要重置),不会为"一切正常"的线路新建条目。
 *
 * <p>## 四条硬约束
 * 见 [LineHealthPolicy] 的 KDoc:只降权不屏蔽 / 按片绑定 /
 * 弱网期暂停累计(调用方传 suppressCount) / 播放证据永不参与源级封禁。
 */
object LineHealthMemory {

    /** 条目上限:覆盖约 300 部片的近期观看;超出按时间淘汰 */
    private const val MAX_ENTRIES = 1500

    /** 与源健康台账同款:按点播源地址分桶,换仓后旧记忆自动作废 */
    private const val KEY_PREFIX = "line_health_"

    private fun bucketKey(): String {
        val api = try {
            KV.get(HawkConfig.API_URL, "")
        } catch (th: Throwable) {
            ""
        }
        // 取不到地址时退回固定桶:功能降级为"不分桶",但不因此完全不记
        return KEY_PREFIX + if (api.isNullOrEmpty()) "default" else com.github.tvbox.osc.util.MD5.string2MD5(api)
    }

    /** 复合键。三要素缺一不可判 —— 空则无法定位一条线路。 */
    fun keyOf(sourceKey: String?, vodId: String?, flag: String?): String {
        val s = sourceKey?.trim().orEmpty()
        val v = vodId?.trim().orEmpty()
        val f = flag?.trim().orEmpty()
        return if (s.isEmpty() || v.isEmpty() || f.isEmpty()) "" else "$s|$v|$f"
    }

    /** 读一条;不存在 / 装载失败一律中性态(调用方按"没记过"处理) */
    fun lookup(sourceKey: String?, vodId: String?, flag: String?): LineHealthPolicy.State {
        val k = keyOf(sourceKey, vodId, flag)
        if (k.isEmpty()) return LineHealthPolicy.State()
        return try {
            val all = loadAll()
            decode(k, all.optJSONObject(k))
        } catch (th: Throwable) {
            LineHealthPolicy.State()
        }
    }

    /** 记一次失败 */
    @JvmStatic
    @JvmOverloads
    fun recordFail(
        sourceKey: String?,
        vodId: String?,
        flag: String?,
        now: Long = System.currentTimeMillis(),
        suppressCount: Boolean = false,
    ) {
        val k = keyOf(sourceKey, vodId, flag)
        if (k.isEmpty()) return
        mutate(k) { current, t -> LineHealthPolicy.recordFail(current, t, suppressCount) }
    }

    /**
     * 记一次成功起播。
     *
     * <p>⚠️ 已有条目才写:见类 KDoc 的"只记有问题的" ——
     * 为"一切正常"的线路新建条目会让容量翻三倍,而它们的记忆价值为零
     * (正常线路本来就不会被降权)。
     */
    fun recordPlayed(sourceKey: String?, vodId: String?, flag: String?, now: Long = System.currentTimeMillis()) {
        val k = keyOf(sourceKey, vodId, flag)
        if (k.isEmpty()) return
        try {
            val all = loadAll()
            if (!all.has(k)) return
            val current = decode(k, all.optJSONObject(k))
            val next = LineHealthPolicy.recordPlayed(current, now)
            if (next.failStreak == current.failStreak && next.playedAt == current.playedAt) return
            all.put(k, encode(next))
            all.put(encodeMeta(pruneLocked(all)))
            persist(all)
        } catch (th: Throwable) {
            LOG.i("echo-line-health played-failed key=" + k + " err=" + th.message)
        }
    }

    /** 记一次取流耗时;<=0 视为未知,不覆盖 */
    fun recordResolveMs(sourceKey: String?, vodId: String?, flag: String?, resolveMs: Long) {
        if (resolveMs <= 0) return
        val k = keyOf(sourceKey, vodId, flag)
        if (k.isEmpty()) return
        mutate(k) { current, _ -> LineHealthPolicy.recordResolveMs(current, resolveMs) }
    }

    /**
     * 批量读排序分:一次装载 + 一次解析,供 [LineHealthSelector] 重排候选。
     *
     * <p>⚠️ 刻意不缓存到内存:KV 读取本身是同步且廉价的(单键 JSON),
     * 而缓存会引入"何时失效"的问题 —— 一次换线读一次就够,不值得为它引入失效逻辑。
     */
    fun orderScores(keys: Collection<String>, now: Long): Map<String, Int> {
        if (keys.isEmpty()) return emptyMap()
        return try {
            val all = loadAll()
            keys.mapNotNull { k ->
                if (k.isEmpty()) null
                else k to LineHealthPolicy.orderScore(decode(k, all.optJSONObject(k)), now)
            }.toMap()
        } catch (th: Throwable) {
            emptyMap()
        }
    }

    /** 只用于测试/诊断:当前条目数 */
    fun size(): Int = try {
        loadAll().optJSONObject(ENTRIES)?.length() ?: 0
    } catch (th: Throwable) {
        0
    }

    /** 清空当前订阅的线路记忆(设置页"清缓存"用) */
    fun clear() {
        try {
            KV.put(bucketKey(), "")
        } catch (th: Throwable) {
            LOG.i("echo-line-health clear-failed: " + th.message)
        }
    }

    // ---------------- 内部 ----------------

    private const val ENTRIES = "entries"
    private const val PRUNED_AT = "prunedAt"

    private fun mutate(
        key: String,
        now: Long = System.currentTimeMillis(),
        transform: (LineHealthPolicy.State, Long) -> LineHealthPolicy.State,
    ) {
        try {
            val all = loadAll()
            val entries = entriesOf(all)
            val current = decode(key, entries.optJSONObject(key))
            val next = transform(current, now)
            entries.put(key, encode(next))
            all.put(encodeMeta(pruneLocked(entries)))
            persist(all)
        } catch (th: Throwable) {
            LOG.i("echo-line-health mutate-failed key=" + key + " err=" + th.message)
        }
    }

    /** 装载;载荷坏掉时返回空表(宁可丢记忆,也不能让功能不可用) */
    private fun loadAll(): JSONObject {
        val raw = try {
            KV.get(bucketKey(), "")
        } catch (th: Throwable) {
            ""
        }
        if (raw.isNullOrEmpty()) return JSONObject()
        return try {
            JSONObject(raw)
        } catch (th: Throwable) {
            LOG.i("echo-line-health load reset: " + th.javaClass.simpleName)
            JSONObject()
        }
    }

    private fun entriesOf(root: JSONObject): JSONObject {
        val e = root.optJSONObject(ENTRIES)
        if (e != null) return e
        val fresh = JSONObject()
        root.put(ENTRIES, fresh)
        return fresh
    }

    /**
     * 超出上限时按时间淘汰最旧。
     *
     * <p>时间戳取"最近一次被写过"(`failAt`/`playedAt` 三者最大),
     * 因为那才是"这条记忆还有没有用"的依据。纯中性态(全 0)不会出现在表里
     * (见 [recordPlayed] 的守卫)所以不会有"淘汰不掉的中性条目"。
     *
     * @return 淘汰了多少条(>0 时留一条日志,便于真机判断容量是否定得合理)
     */
    private fun pruneLocked(entries: JSONObject): Int {
        val n = entries.length()
        if (n <= MAX_ENTRIES) return 0
        data class Item(val key: String, val at: Long)
        val all = ArrayList<Item>(n)
        for (k in entries.keys()) {
            val o = entries.optJSONObject(k) ?: continue
            val at = maxOf(o.optLong("fa"), o.optLong("pa"), 0L)
            all.add(Item(k, at))
        }
        all.sortBy { it.at }
        val overflow = n - MAX_ENTRIES
        for (i in 0 until overflow) entries.remove(all[i].key)
        LOG.i("echo-line-health pruned=" + overflow + " kept=" + MAX_ENTRIES)
        return overflow
    }

    private fun encodeMeta(pruned: Int): JSONObject =
        if (pruned <= 0) JSONObject() else JSONObject().put(PRUNED_AT, System.currentTimeMillis())

    private fun persist(root: JSONObject) {
        KV.put(bucketKey(), root.toString())
    }

    private fun encode(s: LineHealthPolicy.State): JSONObject = JSONObject()
        .put("fs", s.failStreak)
        .put("fa", s.failAt)
        .put("rm", s.resolveMs)
        .put("pa", s.playedAt)

    private fun decode(key: String, o: JSONObject?): LineHealthPolicy.State {
        if (o == null) return LineHealthPolicy.State()
        return try {
            LineHealthPolicy.State(
                failStreak = o.optInt("fs"),
                failAt = o.optLong("fa"),
                resolveMs = o.optLong("rm"),
                playedAt = o.optLong("pa"),
            )
        } catch (th: Throwable) {
            LineHealthPolicy.State()
        }
    }
}
