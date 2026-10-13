package com.github.tvbox.osc.player

import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.MD5
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
 * <p>## 存储形态:扁平的 `键 → {fs,fa,rm,pa}`
 * ⚠️ **刻意不嵌套**(不要包一层 `"entries"`):本类第一版把条目嵌在 `"entries"` 下,
 * 结果 `lookup`/`orderScores`/`recordPlayed` 都忘了先取那一层、直接对根对象取条目 ——
 * **读永远读不到、成功上报永远不生效**,而 TypeScript/JSON 都不会报错,是纯静默故障
 * (CI 只抓到了编译期那部分)。扁平结构让"键就是键"没有中间层可忘。
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
        return KEY_PREFIX + if (api.isNullOrEmpty()) "default" else MD5.string2MD5(api)
    }

    /** 复合键。三要素缺一不可判 —— 空则无法定位一条线路。 */
    @JvmStatic
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
            decode(loadAll().optJSONObject(k))
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
            // 没有条目就什么都不做 —— 这正是"只记有问题的"
            val current = decode(all.optJSONObject(k))
            if (current.failStreak == 0 && current.failAt == 0L) return
            val next = LineHealthPolicy.recordPlayed(current, now)
            if (next.failStreak == current.failStreak && next.playedAt == current.playedAt) return
            all.put(k, encode(next))
            pruneLocked(all)
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
     * 而缓存会引入"何时失效"的问题 —— 一次换线读一次就够。
     */
    fun orderScores(keys: Collection<String>, now: Long): Map<String, Int> {
        if (keys.isEmpty()) return emptyMap()
        return try {
            val all = loadAll()
            keys.mapNotNull { k ->
                if (k.isEmpty()) null
                else k to LineHealthPolicy.orderScore(decode(all.optJSONObject(k)), now)
            }.toMap()
        } catch (th: Throwable) {
            emptyMap()
        }
    }

    /** 只用于测试/诊断:当前条目数 */
    fun size(): Int = try {
        loadAll().length()
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

    private fun mutate(
        key: String,
        now: Long = System.currentTimeMillis(),
        transform: (LineHealthPolicy.State, Long) -> LineHealthPolicy.State,
    ) {
        try {
            val all = loadAll()
            val current = decode(all.optJSONObject(key))
            all.put(key, encode(transform(current, now)))
            pruneLocked(all)
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

    /**
     * 超出上限时按时间淘汰最旧(**就地**修改 [entries])。
     *
     * <p>时间戳取 `failAt`/`playedAt` 的较大者 —— 那才是"这条记忆还有没有用"的依据。
     * 表里不会出现"全中性"条目(见 [recordPlayed] 的守卫),所以不会有淘汰不掉的僵尸项。
     */
    private fun pruneLocked(entries: JSONObject) {
        val n = entries.length()
        if (n <= MAX_ENTRIES) return
        // 收集 key + 时间戳,按时间升序,删最旧的那一批
        val items = ArrayList<Pair<String, Long>>(n)
        val keys = entries.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val o = entries.optJSONObject(k) ?: continue
            items.add(k to maxOf(o.optLong("fa"), o.optLong("pa")))
        }
        items.sortBy { it.second }
        val overflow = n - MAX_ENTRIES
        for (i in 0 until overflow) entries.remove(items[i].first)
        LOG.i("echo-line-health pruned=" + overflow + " kept=" + MAX_ENTRIES)
    }

    private fun persist(root: JSONObject) {
        KV.put(bucketKey(), root.toString())
    }

    private fun encode(s: LineHealthPolicy.State): JSONObject = JSONObject()
        .put("fs", s.failStreak)
        .put("fa", s.failAt)
        .put("rm", s.resolveMs)
        .put("pa", s.playedAt)

    private fun decode(o: JSONObject?): LineHealthPolicy.State {
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
