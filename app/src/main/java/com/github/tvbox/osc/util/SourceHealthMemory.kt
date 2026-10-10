package com.github.tvbox.osc.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject

/**
 * 源健康台账 + 跨源覆盖索引的**唯一持久化出口**(防滥用封禁机制,P0 存储层)。
 *
 * <p>## 存什么
 * <ul>
 *   <li>**健康台账**:每个源在 24 小时窗口内的失败证据与封禁状态(判据在 [SourceHealthPolicy]);</li>
 *   <li>**覆盖索引**:片名 → 收录它的源 key 集合。只用于回答"这部片还有没有**没被封**的源" ——
 *       即需求里的"仅有一个源且该源已被屏蔽 ⇒ 首页不再显示其图片"。没有这份索引,
 *       "仅有一个源"无从判定,只能按"提供卡片的源被封就隐藏"处理,会误伤"别的源其实收录了同一部片"的情况。</li>
 * </ul>
 *
 * <p>## 为什么按**点播源地址**分桶
 * 源 key 只属于具体的源集合:换了地址(换仓)之后旧 key 基本都不存在。不分桶会出现"换仓后
 * 莫名其妙一大批源被屏蔽"。这与 [SearchHelper] 的 `SOURCES_FOR_SEARCH` 是同一口径。
 *
 * <p>## 为什么写盘要延迟(而不是每次失败都落盘)
 * 一轮搜索会连续收口几十个源,若每次都做"读全量 JSON → 改 → 写全量 JSON",开销随台账增长而放大。
 * 所以:改动先进内存,**普通失败**合并到 [FLUSH_DELAY_MS] 后一次落盘;**封禁/解封**这种低频且
 * 必须可靠的转折点当场落盘。代价是进程在这 2 秒内被杀会丢掉最后几条失败证据(可接受:
 * 证据丢了下一次失败会重新累计,而封禁态不会丢)。
 *
 * <p>## 全局护栏
 * **断网时不记**([isNetworkAvailable] 为假直接返回):一次断网会让所有源同时"超时/失败",
 * 不拦就会把所有源一起拉黑 —— 这是本机制最危险的误伤路径。
 */
object SourceHealthMemory {

    /** 延迟落盘窗口(合并一轮搜索里的连续写入) */
    private const val FLUSH_DELAY_MS = 2000L

    /** 台账最多保留多少个源(超出按"最近失败时间最旧"淘汰) */
    private const val MAX_SOURCES = 400

    /** 覆盖索引最多保留多少条片名(超出按最近更新时间淘汰) */
    private const val MAX_TITLES = 800

    /** 每条片名最多记几个来源(只用于"还有没有别的源",记满即止) */
    private const val MAX_CARRIERS_PER_TITLE = 8

    private const val KEY_HEALTH = "health"
    private const val KEY_COVERAGE = "coverage"

    // ==================== 雪崩保险丝(2026-10-10) ====================
    //
    // 弱网(或换网瞬间)一轮搜索能让上百个源同时"连续超时 2 次",规则 2 会把它们成批封 1 小时
    // (真机实测:12:31-12:34 源池 671 → 485,一秒内封掉 186 个)。网络恢复后这些源依然被挡在外面
    // —— 最需要源的时候反而无源可搜。所以:
    //  • 30 秒窗口内规则 2 封禁达到 [AVALANCHE_THRESHOLD] 个 ⇒ 判定为网络抖动:
    //    把本窗口内已封的源**回滚**(1h 封禁解除,降权由 fails 里的超时事件自然保留),
    //    并在窗口剩余时间内抑制后续规则 2 封禁(超时只记 fails 供降权,不加 streak 不封)。
    //  • 只作用于规则 2:规则 1(升级档位)的内容相关证据强、频次低,不参与。
    //  • 窗口过期后一切复位;若网络仍差,每 30 秒最多再封 [AVALANCHE_THRESHOLD] 个并立即回滚
    //    —— 自限节奏,坏不到哪去。

    /** 保险丝的观测窗口 */
    private const val AVALANCHE_WINDOW_MS = 30_000L

    /** 窗口内规则 2 封禁达到该数量即判定为网络抖动 */
    private const val AVALANCHE_THRESHOLD = 20

    /** 当前滚动窗口起点(0 = 未开窗) */
    private var avWinStart = 0L

    /** 窗口内规则 2 封禁计数 */
    private var avCount = 0

    /** 窗口内被规则 2 封禁的源 key(回滚清单) */
    private val avKeys = ArrayList<String>()

    /** 保险丝激活截止时刻(此前的规则 2 封禁一律跳过);0 = 未激活 */
    private var avSuppressedUntil = 0L

    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())

    /** 内存态(与盘上同构)。null = 尚未装载 */
    private var cache: Cache? = null

    /** 有未落盘的改动 */
    private var dirty = false

    /** 归属的桶(点播源地址的 MD5);地址变化时换桶 */
    private var cacheBucket: String? = null

    private val revisionState = mutableStateOf(0)

    /** 台账版本号:任何写入后自增,供 Compose(设置页"已屏蔽 N 个")订阅 */
    val revision: Int get() = revisionState.value

    private class Cache(
        val health: MutableMap<String, SourceHealthState>,
        /** 片名 → (源 key 集合, 最后更新时间) */
        val coverage: MutableMap<String, Pair<MutableSet<String>, Long>>,
    )

    private fun now() = System.currentTimeMillis()

    // ==================== 开关 ====================

    /** 功能开关(需求规则 6:默认开启) */
    fun isEnabled(): Boolean = try {
        KV.get(HawkConfig.AUTO_BLOCK_BAD_SOURCES, true)
    } catch (th: Throwable) {
        true
    }

    // ==================== 记录 ====================

    /**
     * 记一次源级失败。
     *
     * @param title 归一化前的影片名(内部走 [SourceHealthPolicy.contentKey])
     * @return true = 本次触发了封禁(调用方据此广播 UI 刷新,见 RefreshEvent.TYPE_SOURCE_BLOCK_CHANGE)
     */
    @JvmStatic
    fun recordFail(kind: SourceFailKind, sourceKey: String?, title: String?): Boolean {
        if (!isEnabled()) return false
        val key = sourceKey?.trim().orEmpty()
        if (key.isEmpty()) return false
        if (!isNetworkAvailable()) {
            LOG.i("echo-srcban skip no-network kind=$kind source=$key")
            return false
        }
        val contentKey = SourceHealthPolicy.contentKey(title)
        val t = now()
        var newlyBanned = false
        var escalated = false
        var avalancheRolledBack = 0
        synchronized(lock) {
            val bucket = load()
            // 雪崩抑制期:超时只记 fails(供降权),不加 streak、不触发规则 2 封禁(见顶部说明)
            val suppressTimeoutBan = kind == SourceFailKind.SEARCH_TIMEOUT && t < avSuppressedUntil
            val before = bucket.health[key] ?: SourceHealthState()
            val wasBlocked = SourceHealthPolicy.isBlocked(before, t)
            val after = SourceHealthPolicy.recordFail(before, kind, contentKey, t, suppressTimeoutBan)
            bucket.health[key] = after
            trimHealth(bucket)
            val banned = after.manualLocked || after.bannedUntil > t
            newlyBanned = banned && !wasBlocked
            // 按"升级次数有没有涨"区分两条规则的产物:涨了 = 走了升级档位(规则 1)
            escalated = after.banCount > before.banCount
            dirty = true
            LOG.i(
                "echo-srcban fail kind=" + kind + " source=" + key + " content=" + contentKey +
                    " fails=" + after.fails.size + " banCount=" + after.banCount +
                    " streak=" + after.timeoutStreak +
                    " banned=" + banned + " locked=" + after.manualLocked
            )
            // 规则 2 的成批封禁计数:30 秒窗口内达到阈值 ⇒ 判定网络抖动,回滚本窗口内已封源
            // 并抑制窗口剩余时间内的后续封禁。只认规则 2 的产物(未升级档位、由超时触发)。
            if (newlyBanned && !escalated && kind == SourceFailKind.SEARCH_TIMEOUT) {
                if (avWinStart == 0L || t - avWinStart > AVALANCHE_WINDOW_MS) {
                    avWinStart = t
                    avCount = 0
                    avKeys.clear()
                }
                avCount++
                avKeys.add(key)
                if (avCount >= AVALANCHE_THRESHOLD) {
                    avSuppressedUntil = avWinStart + AVALANCHE_WINDOW_MS
                    for (k in avKeys) {
                        val st = bucket.health[k] ?: continue
                        // 只回滚"当前仍生效的临时封禁":规则 1 的升级档位(6h/24h/永久)不在本清单里
                        // (avKeys 只收 !escalated 的规则 2 产物),这里再挡一层 manualLocked 兜底。
                        if (!st.manualLocked && st.bannedUntil > t) {
                            bucket.health[k] = st.copy(bannedUntil = 0L, timeoutStreak = 0)
                            avalancheRolledBack++
                        }
                    }
                    LOG.i(
                        "echo-srcban avalanche rollback n=" + avalancheRolledBack +
                            " windowMs=" + AVALANCHE_WINDOW_MS + " suppressedUntil=" + avSuppressedUntil
                    )
                    // 回滚也是成批转折点,但同属弱网风暴期:走合并落盘,revision 要 bump
                    // (首页"已屏蔽 N 个"计数立刻回落)。
                    bumpRevision()
                    avCount = 0
                    avKeys.clear()
                }
            }
        }
        if (escalated) {
            // 升级档位是低频、且"升到永久"不可逆的转折点:当场落盘,别赌 2 秒窗口
            flushNow()
            bumpRevision()
        } else {
            // ⚠️ 规则 2 的临时封禁会**成批**发生:一轮搜索里几百个源同时到达"连续超时 2 次",
            // 若也逐个 flushNow,就是把整张台账 JSON 重复序列化几百次(台账上限 400 源)。
            // 所以走合并落盘;代价是进程在这 2 秒内被杀会丢掉这几次临时封禁 ——
            // 可接受:证据(fails)还在,下次超时会重新触发,而 1 小时封禁本身是低风险的。
            if (newlyBanned) bumpRevision()
            scheduleFlush()
        }
        return newlyBanned
    }

    /**
     * 源**给出了答案**(搜索有命中或无命中)⇒ 连续超时计数归零(规则 2 的重置入口)。
     *
     * <p>不做网络/开关之外的任何判断:能走到这里就说明该源这一轮答话了,这就是"它还活着"的证据。
     * 与 [recordFail] 一样**合并落盘**,不为一次成功单独写盘。
     */
    @JvmStatic
    fun recordAnswered(sourceKey: String?) {
        if (!isEnabled()) return
        val key = sourceKey?.trim().orEmpty()
        if (key.isEmpty()) return
        synchronized(lock) {
            val bucket = load()
            val before = bucket.health[key] ?: return
            val after = SourceHealthPolicy.recordAnswered(before)
            if (after === before) return
            bucket.health[key] = after
            dirty = true
            LOG.i("echo-srcban answered source=" + key + " streak=0")
        }
        scheduleFlush()
    }

    /**
     * 记下"这些影片由这个源收录"(覆盖索引)。
     *
     * <p>只在搜索**成功命中**时调用:命中的结果才代表"这个源确实有这部片"。
     */
    @JvmStatic
    fun recordCarriers(sourceKey: String?, titles: Collection<String>) {
        if (!isEnabled()) return
        val key = sourceKey?.trim().orEmpty()
        if (key.isEmpty() || titles.isEmpty()) return
        val t = now()
        var changed = false
        synchronized(lock) {
            val bucket = load()
            for (title in titles) {
                val titleKey = SourceHealthPolicy.contentKey(title)
                if (titleKey.isEmpty()) continue
                val existing = bucket.coverage[titleKey]
                if (existing == null) {
                    bucket.coverage[titleKey] = mutableSetOf(key) to t
                    changed = true
                } else if (existing.first.add(key)) {
                    bucket.coverage[titleKey] = existing.first to t
                    changed = true
                }
            }
            if (changed) {
                trimCoverage(bucket)
                dirty = true
            }
        }
        if (changed) scheduleFlush()
    }

    // ==================== 查询 ====================

    /** 该源当前是否被屏蔽(含到点自动解封) */
    @JvmStatic
    fun isBlocked(sourceKey: String?): Boolean {
        if (!isEnabled()) return false
        val key = sourceKey?.trim().orEmpty()
        if (key.isEmpty()) return false
        val t = now()
        synchronized(lock) {
            val bucket = load()
            val state = bucket.health[key] ?: return false
            val normalized = SourceHealthPolicy.normalize(state, t)
            if (normalized !== state) {
                bucket.health[key] = normalized
                dirty = true
                scheduleFlush()
                LOG.i("echo-srcban auto-unblock source=" + key + " banCount=" + normalized.banCount)
            }
            return SourceHealthPolicy.isBlocked(normalized, t)
        }
    }

    /** 当前被屏蔽的源 key 集合(批量过滤用,一次装载避免逐源查盘) */
    @JvmStatic
    fun blockedKeys(): Set<String> {
        if (!isEnabled()) return emptySet()
        val t = now()
        synchronized(lock) {
            val bucket = load()
            val blocked = HashSet<String>()
            var changed = false
            for ((key, state) in bucket.health) {
                val normalized = SourceHealthPolicy.normalize(state, t)
                if (normalized !== state) {
                    bucket.health[key] = normalized
                    changed = true
                }
                if (SourceHealthPolicy.isBlocked(normalized, t)) blocked.add(key)
            }
            if (changed) {
                dirty = true
                scheduleFlush()
            }
            return blocked
        }
    }

    /** 已屏蔽数量(设置页显示用) */
    @JvmStatic
    fun blockedCount(): Int = blockedKeys().size

    /**
     * 本轮需要**降权**的源集合:最近 [SourceHealthPolicy.DEFER_MS] 内有过搜索超时。
     *
     * <p>降权 ≠ 屏蔽:这些源**照搜**,只是不占用首轮"快速源"的并发额度(见 [SearchBatchPolicy])。
     * 所以它是纯读、不写盘、也不需要 fail-open —— 最坏情况只是排序没起作用。
     */
    @JvmStatic
    fun penalizedKeys(): Set<String> {
        if (!isEnabled()) return emptySet()
        val t = now()
        synchronized(lock) {
            val bucket = load()
            val out = HashSet<String>()
            for ((key, state) in bucket.health) {
                if (SourceHealthPolicy.shouldDefer(state, t)) out.add(key)
            }
            return out
        }
    }

    /**
     * 覆盖索引里,这部影片还有没有**没被封**的源收录。
     *
     * @param title      影片名
     * @param blocked    已屏蔽集合(调用方一次取,避免重复查盘)
     * @param excludeKey 发起判定的那个源(它自己不算"别的源")
     * @return true = 已知还有可用来源;false = 未知或只剩被封的源
     */
    @JvmStatic
    fun hasUnblockedCarrier(title: String?, blocked: Set<String>, excludeKey: String?): Boolean {
        val titleKey = SourceHealthPolicy.contentKey(title)
        if (titleKey.isEmpty()) return false
        synchronized(lock) {
            val carriers = load().coverage[titleKey]?.first ?: return false
            return carriers.any { it != excludeKey && !blocked.contains(it) }
        }
    }

    // ==================== 解除 ====================

    /** 全部解除(设置页入口)。保留升级次数 —— 再犯仍按原档位升级 */
    fun unblockAll() {
        synchronized(lock) {
            val bucket = load()
            val targets = bucket.health.filterValues {
                it.manualLocked || it.bannedUntil > 0L || it.fails.isNotEmpty() || it.timeoutStreak > 0
            }.keys
            if (targets.isEmpty()) return
            for (key in targets) {
                bucket.health[key] = SourceHealthPolicy.unblock(bucket.health[key] ?: SourceHealthState())
            }
            dirty = true
            LOG.i("echo-srcban unblock-all n=" + targets.size)
        }
        flushNow()
        bumpRevision()
    }

    // ==================== 装载/落盘 ====================

    private fun bucketKey(): String? {
        val api = try {
            KV.get(HawkConfig.API_URL, "")
        } catch (th: Throwable) {
            ""
        }
        if (api.isNullOrEmpty()) return null
        return HawkConfig.SOURCE_HEALTH + "_" + MD5.string2MD5(api)
    }

    /** 取当前桶的内存态(必要时装载)。调用方**必须**持 [lock] */
    private fun load(): Cache {
        val bucket = bucketKey() ?: return Cache(HashMap(), HashMap())
        if (cache != null && cacheBucket == bucket) return cache!!
        // 换桶:先把旧桶的未落盘改动写掉,别丢
        if (dirty && cacheBucket != null) flushLocked()
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

    private fun parse(raw: String?): Cache {
        val health = HashMap<String, SourceHealthState>()
        val coverage = HashMap<String, Pair<MutableSet<String>, Long>>()
        if (raw.isNullOrEmpty()) return Cache(health, coverage)
        try {
            val root = JSONObject(raw)
            val healthJson = root.optJSONObject(KEY_HEALTH)
            if (healthJson != null) {
                for (key in healthJson.keys()) {
                    val obj = healthJson.optJSONObject(key) ?: continue
                    val fails = ArrayList<SourceFailEvent>()
                    val arr = obj.optJSONArray("fails") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        val e = arr.optJSONObject(i) ?: continue
                        val kind = runCatching { SourceFailKind.valueOf(e.optString("k")) }.getOrNull() ?: continue
                        fails.add(SourceFailEvent(kind, e.optLong("at"), e.optString("c")))
                    }
                    health[key] = SourceHealthState(
                        fails = fails,
                        banCount = obj.optInt("banCount"),
                        bannedUntil = obj.optLong("until"),
                        manualLocked = obj.optBoolean("locked"),
                        // optInt 对缺失键返回 0 ⇒ 加了字段的旧台账读出来是"无连续超时",向后兼容,
                        // 不需要任何迁移(与 banCount 当初加进来时同款)
                        timeoutStreak = obj.optInt("timeoutStreak"),
                    )
                }
            }
            val coverageJson = root.optJSONObject(KEY_COVERAGE)
            if (coverageJson != null) {
                for (title in coverageJson.keys()) {
                    val obj = coverageJson.optJSONObject(title) ?: continue
                    val arr = obj.optJSONArray("s") ?: JSONArray()
                    val set = HashSet<String>()
                    for (i in 0 until arr.length()) {
                        val k = arr.optString(i)
                        if (k.isNotEmpty()) set.add(k)
                    }
                    if (set.isNotEmpty()) coverage[title] = set to obj.optLong("at")
                }
            }
        } catch (th: Throwable) {
            // 载荷坏掉不能连带把功能打死:当作空台账重新积累(与 VideoQualityMemory 同款兜底)
            LOG.i("echo-srcban load-failed: " + th.message)
        }
        return Cache(health, coverage)
    }

    private fun encode(): String {
        val bucket = cache ?: return ""
        val root = JSONObject()
        val healthJson = JSONObject()
        for ((key, state) in bucket.health) {
            // 全零条目不落盘(台账瘦身)。⚠️ timeoutStreak 也要参与判空:连续超时计数非零时
            // 该源必须被持久化,否则重启后计数丢失、要重新超时两次才封 —— 白白多等一轮。
            if (state.fails.isEmpty() && state.banCount == 0 && state.bannedUntil == 0L &&
                !state.manualLocked && state.timeoutStreak == 0
            ) {
                continue
            }
            val obj = JSONObject()
            val arr = JSONArray()
            for (e in state.fails) {
                arr.put(JSONObject().put("k", e.kind.name).put("at", e.at).put("c", e.contentKey))
            }
            obj.put("fails", arr)
            obj.put("banCount", state.banCount)
            obj.put("until", state.bannedUntil)
            obj.put("locked", state.manualLocked)
            obj.put("timeoutStreak", state.timeoutStreak)
            healthJson.put(key, obj)
        }
        val coverageJson = JSONObject()
        for ((title, entry) in bucket.coverage) {
            coverageJson.put(
                title,
                JSONObject().put("s", JSONArray(entry.first.toList())).put("at", entry.second),
            )
        }
        root.put(KEY_HEALTH, healthJson)
        root.put(KEY_COVERAGE, coverageJson)
        return root.toString()
    }

    /** 必须有 [lock] */
    private fun flushLocked() {
        val bucket = cacheBucket ?: return
        if (!dirty) return
        try {
            KV.put(bucket, encode())
        } catch (th: Throwable) {
            LOG.i("echo-srcban flush-failed: " + th.message)
            return
        }
        dirty = false
    }

    /** 立即落盘(封禁/解封/开关切换等转折点) */
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
        bumpRevision()
    }

    private fun bumpRevision() {
        revisionState.value = revisionState.value + 1
    }

    /** 台账容量兜底:超出按最近失败时间淘汰(刚写的那条一定在窗口内,不会被挑中) */
    private fun trimCoverage(bucket: Cache) {
        if (bucket.coverage.size <= MAX_TITLES) {
            // 单条片名的来源数也要封顶
            for ((title, entry) in bucket.coverage) {
                if (entry.first.size > MAX_CARRIERS_PER_TITLE) {
                    bucket.coverage[title] = entry.first.take(MAX_CARRIERS_PER_TITLE).toMutableSet() to entry.second
                }
            }
            return
        }
        val keep = bucket.coverage.entries.sortedByDescending { it.value.second }.take(MAX_TITLES)
        bucket.coverage.clear()
        for ((title, entry) in keep) bucket.coverage[title] = entry
    }

    /** 台账内源数量兜底(实际远小于此,只为防脏数据把键值写爆) */
    private fun trimHealth(bucket: Cache) {
        if (bucket.health.size <= MAX_SOURCES) return
        val keep = bucket.health.entries.sortedByDescending { it.value.fails.maxOfOrNull { f -> f.at } ?: 0L }
            .take(MAX_SOURCES)
        val kept = HashMap<String, SourceHealthState>()
        for ((key, state) in keep) kept[key] = state
        bucket.health.clear()
        bucket.health.putAll(kept)
    }

    // ==================== 环境判据 ====================

    /**
     * 当前是否有可用网络。断网时一律不记失败 —— 见类注释的全局护栏。
     *
     * <p>取不到上下文/查询异常时**按有网处理**:宁可记一条可能不准的失败,也不要因为一次异常
     * 让整个机制静默失效(那会表现成"怎么一直不生效",极难排查)。
     */
    fun isNetworkAvailable(): Boolean {
        return try {
            val context = AppContextHolder.context() ?: return true
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (th: Throwable) {
            true
        }
    }
}
