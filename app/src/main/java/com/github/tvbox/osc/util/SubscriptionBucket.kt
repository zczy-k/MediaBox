package com.github.tvbox.osc.util

import org.json.JSONObject

/**
 * 订阅地址 → KV 桶身份(所有"按仓分桶"台账的唯一算键处)。
 *
 * <p>## 补的空白
 * 六本台账(源权重/源封禁/缺列表标记/拼音索引/搜中缓存/线路健康)的桶名此前是
 * `前缀 + MD5(订阅地址原文)`。"原文"这三个字是坑:末尾多个斜杠、http 抄成 https、
 * 复制时带个空格,都算一本新账 —— 用户看着是同一个仓,源权重与线路健康却从冷启动重攒。
 * 现在身份取自**规范化后的地址**(去协议、host 小写、去尾斜杠、去 #fragment),
 * 同一个仓的常见写法差异落到同一个桶。
 *
 * <p>去协议 = http 与 https 的同一个仓并成一本账。反向风险是"同 host+path 用协议区分两份
 * 不同配置",现实里没见过;而"抄地址差一个斜杠就清零"是天天会踩的。
 *
 * <p>## 老桶搬迁
 * 换算法就换桶名,不改版的用户会被当成"新仓、空账"。[resolveKey] 在新桶空、老桶
 * (未规范化 MD5)有数据时一次性搬过去并删老键 —— 不搬等于给所有升级用户清一次账。
 *
 * <p>## 孤儿桶回收
 * 彻底弃用的仓留下的台账没人删:各台账的体积哨兵只砍**桶内部**条目,不知道桶该不该存在。
 * [migrateAndReclaim] 每轮启动跑一次:还能切回去的仓(订阅列表 + 配置历史 + 当前)一律保留;
 * 切不回去的**先登记、不删**,等它连续 [ORPHAN_STALE_MS] 没被碰到才删。
 * ⚠️ 不能只按"不在已知集合里"就删 —— 配置历史只留 30 条,这么判会把还在用的仓的账本清掉,
 * 而"用户攒的资产被删"是这套设计里唯一不可接受的错。
 */
object SubscriptionBucket {

    /**
     * 按订阅地址分桶的台账前缀。
     * ⚠️ 新增分桶台账必须登记这里,否则它既不参与搬迁、也不会被回收(只会悄悄占空间)。
     */
    @JvmStatic
    val prefixes: List<String> = listOf(
        "source_weight_",
        HawkConfig.SOURCE_HEALTH + "_",
        HawkConfig.SOURCE_HEALTH + "_nolist_",
        "title_index_",
        "search_cache_",
        "nohit_",
        "line_health_",
    )

    /** 桶身份登记表(KV 里一个 JSON:桶 id → 最后被碰到的时刻)。丢了最坏是少删一轮 */
    private const val SEEN_KEY = "bucket_seen"

    /** 切不回去的仓,连续多久没被碰到才回收 */
    private const val ORPHAN_STALE_MS = 60L * 24 * 60 * 60 * 1000

    /** 登记表条数上界(超出只丢表项,不动台账) */
    private const val MAX_SEEN = 120

    /** 一个桶 id 的形状:32 位小写十六进制。用于把 "default" 这类固定桶排除在回收之外 */
    private val BUCKET_ID = Regex("^[0-9a-f]{32}$")

    /** 地址 → 规范化身份;空串表示"没有可用地址"(调用方据此退回不分桶/跳过) */
    @JvmStatic
    fun normalize(apiUrl: String?): String {
        val raw = apiUrl?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        // 只有 "xxx://" 形态才当 URL 处理。整串 base64 配置没有协议,必须原样返回:
        // 下面会对 host 小写,而 base64 **大小写敏感**,一小写就把两份不同配置并成一本账了。
        val schemeEnd = raw.indexOf("://")
        if (schemeEnd < 0) return raw
        val noScheme = raw.substring(schemeEnd + 3)
        val noFragment = noScheme.substringBefore('#')
        val queryIndex = noFragment.indexOf('?')
        val hostPath = if (queryIndex < 0) noFragment else noFragment.substring(0, queryIndex)
        val query = if (queryIndex < 0) "" else noFragment.substring(queryIndex)
        val slash = hostPath.indexOf('/')
        val host = (if (slash < 0) hostPath else hostPath.substring(0, slash)).lowercase()
        // path 大小写敏感(有些 CDN 的 json 路径就是带大写),只小写 host
        val path = (if (slash < 0) "" else hostPath.substring(slash)).trimEnd('/')
        return host + path + query
    }

    /** 规范化桶 id;无地址返回 null */
    @JvmStatic
    fun idOf(apiUrl: String?): String? = normalize(apiUrl).takeIf { it.isNotEmpty() }?.let { MD5.string2MD5(it) }

    /** 老算法的桶 id(未规范化),搬迁与"保留已知仓"都要认它 */
    private fun legacyId(apiUrl: String): String = MD5.string2MD5(apiUrl)

    /**
     * 该用哪个桶键,顺带把老键的数据一次性搬过来。
     *
     * <p>只在"新桶空 + 老桶有"时才动手,且搬完删老键 ⇒ 幂等:第二次调用什么都不做。
     */
    @JvmStatic
    fun resolveKey(prefix: String, apiUrl: String?): String? {
        val raw = apiUrl?.trim().orEmpty()
        val id = idOf(raw) ?: return null
        val key = prefix + id
        val old = prefix + legacyId(raw)
        if (old == key) return key
        try {
            if (KV.get(key, "").isNullOrEmpty()) {
                val payload = KV.get(old, "")
                if (!payload.isNullOrEmpty()) {
                    KV.put(key, payload)
                    KV.delete(old)
                    LOG.i("echo-bucket migrate $old -> $key")
                }
            }
        } catch (th: Throwable) {
            // 搬迁失败就用新桶:功能降级为"这本账重新攒",不能因为搬不动而读不到/写不进
            LOG.i("echo-bucket migrate-failed prefix=$prefix: ${th.javaClass.simpleName}")
        }
        return key
    }

    /**
     * 启动/换仓时跑一次:搬迁当前仓 + 回收没人碰的弃用仓。
     *
     * @return 本次删掉的桶键数
     */
    @JvmStatic
    @JvmOverloads
    fun migrateAndReclaim(now: Long = System.currentTimeMillis()): Int {
        val current = try {
            KV.get(HawkConfig.API_URL, "")
        } catch (th: Throwable) {
            ""
        }
        // 先搬当前仓:否则"新桶空、老桶有"会被下一轮当成孤儿
        prefixes.forEach { resolveKey(it, current) }
        val keep = HashSet<String>()
        val urls = SubscribeList.vodUrls() + listOf(current)
        urls.forEach { url ->
            if (url.isNotEmpty()) {
                keep.add(legacyId(url))
                idOf(url)?.let { keep.add(it) }
            }
        }
        val seen = loadSeen()
        var scanned = 0
        var removed = 0
        try {
            prefixes.forEach { prefix ->
                KV.keys(prefix).forEach { key ->
                    val id = key.removePrefix(prefix)
                    // "line_health_default" 这类不是仓桶,永不回收
                    if (!BUCKET_ID.matches(id)) return@forEach
                    scanned++
                    if (keep.contains(id)) {
                        seen.put(id, now)
                        return@forEach
                    }
                    val last = seen.optLong(id, 0L)
                    // 首次见到只登记,不删:登记表本身可能是空的(老版本升级上来)
                    if (last <= 0L) {
                        seen.put(id, now)
                        return@forEach
                    }
                    if (now - last > ORPHAN_STALE_MS) {
                        KV.delete(key)
                        seen.remove(id)
                        removed++
                    }
                }
            }
        } catch (th: Throwable) {
            LOG.i("echo-bucket reclaim-failed: ${th.javaClass.simpleName}: ${th.message}")
        }
        persistSeen(trim(seen, now))
        LOG.i(
            "echo-bucket reclaim scanned=$scanned deleted=$removed keep=${keep.size} " +
                "current=${idOf(current) ?: "none"}",
        )
        return removed
    }

    private fun loadSeen(): JSONObject = try {
        val raw = KV.get(SEEN_KEY, "")
        if (raw.isNullOrEmpty()) JSONObject() else JSONObject(raw)
    } catch (th: Throwable) {
        JSONObject()
    }

    /** 表只用来判"多久没被碰到",超界就丢最旧那些项(代价是那些桶这轮不会被回收) */
    private fun trim(seen: JSONObject, now: Long): JSONObject {
        if (seen.length() <= MAX_SEEN) return seen
        val ordered = seen.keys().asSequence().sortedByDescending { seen.optLong(it, 0L) }.take(MAX_SEEN)
        val kept = JSONObject()
        ordered.forEach { kept.put(it, seen.optLong(it, now)) }
        return kept
    }

    private fun persistSeen(seen: JSONObject) {
        try {
            KV.put(SEEN_KEY, seen.toString())
        } catch (th: Throwable) {
            LOG.i("echo-bucket seen-write-failed: ${th.message}")
        }
    }
}
