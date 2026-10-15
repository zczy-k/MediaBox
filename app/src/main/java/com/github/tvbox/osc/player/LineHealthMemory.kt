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
 * <p>## 记得什么
 * 失败痕迹 + **每次成功起播的时刻**。后者从 2026-10-15 起才真正落盘,因为
 * [LineHealthPolicy.orderScore] 除了扣分还要靠它加分("上次就是这条线播通的"排在从没试过的之前)。
 * 条目数由 [MAX_ENTRIES] 封顶,超出按 `failAt`/`playedAt` 较新者留下、最旧的淘汰。
 *
 * <p>## 四条硬约束
 * 见 [LineHealthPolicy] 的 KDoc:只降权不屏蔽 / 按片绑定 /
 * 弱网期暂停累计(调用方传 suppressCount) / 播放证据永不参与源级封禁。
 */
object LineHealthMemory {

    /** 条目上限:覆盖约 300 部片的近期观看;超出按时间淘汰 */
    private const val MAX_ENTRIES = 1500

    /** 同一线路多久内的重复"播通"不再落盘(见 [recordPlayed]) */
    private const val REPLAY_DEDUPE_MS = 60_000L

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

    /**
     * 线路健康是否启用(2026-10-13)。
     *
     * <p>与源级封禁共用「自动屏蔽反复不可用的源」开关([HawkConfig.AUTO_BLOCK_BAD_SOURCES]):
     * 两者同属"自动处理质量差的源/线路"这一套机制,关掉就该整体停用 ——
     * 否则关掉源级封禁后线路仍被偷偷重排,开关语义自相矛盾。
     *
     * <p>口径与 [com.github.tvbox.osc.util.SourceHealthMemory.isEnabled] 一致:读不到时**按开启**(默认开)。
     */
    fun isEnabled(): Boolean = try {
        KV.get(HawkConfig.AUTO_BLOCK_BAD_SOURCES, true)
    } catch (th: Throwable) {
        true
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
    @Synchronized
    fun lookup(sourceKey: String?, vodId: String?, flag: String?): LineHealthPolicy.State {
        val k = keyOf(sourceKey, vodId, flag)
        if (k.isEmpty()) return LineHealthPolicy.State()
        return try {
            decode(loadAll().optJSONObject(k))
        } catch (th: Throwable) {
            LineHealthPolicy.State()
        }
    }

    /**
     * 这条线路此刻是否**可疑**(近期连续失败,且之后没有成功过)。
     *
     * <p>## 为什么在 [orderScores] 之外还要一个"是/否"入口(2026-10-13)
     * [orderScores] 回答的是"排序该往前还是往后",而**切换决策**要回答的是另一个问题:
     * "这条线现在能不能试着切过去"。两者不能互相替代 —— 实测画质最优 ≠ 能播:
     * 一条刚连续失败两次的线路,即使它实测/标称清晰度最高,切过去大概率还是失败,
     * 用户要多等一次起播超时。
     *
     * <p>所以源内扫描([DetailViewModel.sweepSourceLinesForQuality])与起播前预检
     * ([DetailViewModel.applyFirstWatchProbe])在按**实测画质**选定目标后,再用本方法确认
     * 它不"可疑"。判据本身仍只有一份([LineHealthPolicy.isSuspect]),这里只负责取数,
     * 避免出现第二套"可疑"口径。
     */
    @Synchronized
    fun isSuspect(sourceKey: String?, vodId: String?, flag: String?, now: Long = System.currentTimeMillis()): Boolean {
        if (!isEnabled()) return false
        val k = keyOf(sourceKey, vodId, flag)
        if (k.isEmpty()) return false
        return try {
            LineHealthPolicy.isSuspect(decode(loadAll().optJSONObject(k)), now)
        } catch (th: Throwable) {
            // 判据读不到就按"不可疑"处理:宁可不干预切换顺序,不可因一次读取异常阻断换线
            false
        }
    }

    /** 记一次失败 */
    @JvmStatic
    @JvmOverloads
    @Synchronized
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
     * <p>2026-10-15 起**干净线路也要记**：[LineHealthPolicy.playedBonus] 用的正是这个 `playedAt`
     * （"上次就是这条线播通的"是重看同一部片时最硬的证据）。此前这里只为已有失败痕迹的条目归零，
     * 于是 `playedAt` 在正常线路上永远是 0，加分档位形同虚设。
     *
     * <p>容量仍由 [MAX_ENTRIES] 兜住（条目时间戳取 `failAt`/`playedAt` 较大者，淘汰最久没碰的）。
     * [REPLAY_DEDUPE_MS] 内不重复落盘：一次 READY 会连带 seek/换清晰度反复触发，
     * 而"多久以前播过"的分档粒度是 24 小时，1 分钟内的重写纯属白写整张表。
     */
    @Synchronized
    fun recordPlayed(sourceKey: String?, vodId: String?, flag: String?, now: Long = System.currentTimeMillis()) {
        val k = keyOf(sourceKey, vodId, flag)
        if (k.isEmpty()) return
        try {
            val all = loadAll()
            val current = decode(all.optJSONObject(k))
            // 去重只挡"没什么可改"的重复 READY；带失败痕迹的条目必须立刻归零，不能等 60s
            if (current.failStreak == 0 && current.playedAt > 0L && now - current.playedAt < REPLAY_DEDUPE_MS) return
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
    @Synchronized
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
    @Synchronized
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
    @Synchronized
    fun size(): Int = try {
        loadAll().length()
    } catch (th: Throwable) {
        0
    }

    /**
     * **L1 线路级恢复**(2026-10-13,P0-3):网络恢复时,把"可疑"线路的失败痕迹清掉。
     *
     * <p>## 为什么需要它
     * 线路级的"可疑"是 TTL 制([LineHealthPolicy.SUSPECT_TTL_MS] = 15 分钟),本身会自动过期;
     * 但一次**弱网抖动**可能把一批好线路同时推过 [LineHealthPolicy.SUSPECT_STREAK],
     * 网络恢复后它们仍要从队尾爬起 —— 用户明明网已好,却还在等坏线。本方法让"网络一好就复位"。
     *
     * <p>## ⚠️ 只清"可疑"的,不清健康的
     * 判据用 [LineHealthPolicy.isSuspect](即连续失败 ≥ 2 且未成功过):
     * <ul>
     *   <li>**可疑** ⇒ 失败痕迹清零(复位为中性),网络恢复后不再压队尾;</li>
     *   <li>**健康/中性**(failStreak &lt; 2)⇒ 不动 —— 本来就没降权,清了也是空操作,
     *       而且"顺手清所有条目"会把真实坏线的证据一起抹掉。</li>
     * </ul>
     *
     * <p>⚠️ 与源级 [com.github.tvbox.osc.util.SourceHealthMemory.liftNetworkBans] 同款:
     * **保留 playedAt/resolveMs**(那是"看过/多快"的事实,与"网络恢复"无关),只清失败计数。
     * 也不动源级封禁 —— 那是另一个层的职责。
     *
     * @return 本次复位的线路条数
     */
    @Synchronized
    fun liftSuspectOnNetworkRestore(now: Long = System.currentTimeMillis()): Int {
        if (!isEnabled()) return 0
        return try {
            val all = loadAll()
            var lifted = 0
            val keys = all.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val current = decode(all.optJSONObject(k))
                // 只复位"可疑"的:健康条目本就没降权,顺手清会把真坏线证据一起抹掉
                if (!LineHealthPolicy.isSuspect(current, now)) continue
                // 复位:失败计数与失败时刻清零,保留"看过/取流耗时"这类与网络无关的事实
                all.put(k, encode(current.copy(failStreak = 0, failAt = 0L)))
                lifted++
            }
            if (lifted > 0) persist(all)
            // 日志不设 lifted>0 守卫:有守卫则"没触发"与"触发了但 0 个"在日志上无法区分
            LOG.i("echo-recover layer=L1 line-suspect lifted=" + lifted)
            lifted
        } catch (th: Throwable) {
            LOG.i("echo-recover line-suspect failed: " + th.message)
            0
        }
    }

    /** 是否已注册网络回调(幂等闩) */
    private var networkWatcherInstalled = false

    /**
     * **L1 线路级的网络监听**(2026-10-13,P0-3):网络**重新可用**时调用 [liftSuspectOnNetworkRestore]。
     *
     * <p>与 [com.github.tvbox.osc.util.SourceHealthMemory.installNetworkWatcher] 对称、**各自独立注册**:
     * 源级(util 包)与线路级(player 包)分属两层,让各自管自己的监听,避免 util 反向依赖 player
     * 的内部实现([LineHealthMemory])。两个回调都监听"默认网络可用",互不干扰。
     *
     * <p>幂等,注册失败只记日志(恢复是锦上添花)。
     */
    @JvmStatic
    fun installNetworkWatcher() {
        if (networkWatcherInstalled) return
        networkWatcherInstalled = true
        try {
            val context = com.github.tvbox.osc.util.AppContextHolder.context() ?: return
            val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                as? android.net.ConnectivityManager ?: return
            cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    try {
                        liftSuspectOnNetworkRestore()
                    } catch (th: Throwable) {
                        LOG.i("echo-recover line-lift failed: " + th.message)
                    }
                }
            })
            LOG.i("echo-recover line network watcher installed")
        } catch (th: Throwable) {
            LOG.i("echo-recover line watcher install failed: " + th.message)
        }
    }


    /** 清空当前订阅的线路记忆(设置页"清缓存"用) */
    @Synchronized
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
    @Synchronized
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
