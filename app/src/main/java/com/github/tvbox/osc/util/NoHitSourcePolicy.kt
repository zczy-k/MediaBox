package com.github.tvbox.osc.util

/**
 * 「这个源答过话、但这部片它没有」这条结论的**判据**(纯函数,好测;存取在 [NoHitSourceCache])。
 *
 * <p>## 要解决什么
 * 详情页每次进一部片都会把整个源池(真机 322 个)问一遍。命中结果早有缓存
 * ([SearchResultCache]),但**零命中什么都没留下** —— 于是重进同一部片时,
 * 那几百个"确实答过话、也确实没有"的源要重新各烧一次 8 秒超时额度。
 * 真机实测一部《狮子王》两分钟内 55 个源答话、51 个空响应、28 个超时,而给出这部剧的源是个位数。
 *
 * <p>## 两条边界(比 TTL 更要紧,写错就变成"源被缓存成没有")
 * <ul>
 *   <li>**只有确实答过话且确实没给出同名条目**才入账。超时/异常/解析崩/空响应都不算 ——
 *       那是这个源的耐心问题,不是它有没有。仓库里"超时≠没有"是既有不变量。</li>
 *   <li>**用户主动点「换源」绝不短路**。那一刻他的意图就是"还要继续找",
 *       用缓存少给几个源只会让他认为功能坏了。</li>
 * </ul>
 *
 * <p>## 为什么 TTL 抄命中缓存(24 小时),不抄「无资源标记」(90 天)
 * 命中与零命中是**同一件事的两种结果**,必须同一个时间尺度作废,否则会出现
 * "命中缓存说这个源有、短路表说这个源没有"这种自相矛盾。而且零命中这条证据天生比命中薄:
 * 命中拿到的是具体 id,零命中只是"这次这个名字没搜到",片名写法一换结论就可能翻。
 */
object NoHitSourcePolicy {

    /** 与 [SearchResultCache] 的命中缓存同尺度:一次重搜会把两边证据一起刷新 */
    const val TTL_MS = 24L * 60 * 60 * 1000

    /** 记多少个片名:与命中缓存同量级(源 key 很短,20 部片满打满算也就几十 KB) */
    const val MAX_TITLES = 20

    /** 每部片最多记多少个源:整个池 322 已经够用,设个上限只为挡住异常膨胀 */
    const val MAX_SOURCES_PER_TITLE = 400

    /** 条目是否还在有效期内。时间倒挂(用户改过系统时钟)一律当过期,不信任它 */
    @JvmStatic
    fun isFresh(atMs: Long, nowMs: Long): Boolean =
        atMs > 0L && nowMs >= atMs && nowMs - atMs <= TTL_MS

    /**
     * 这一轮能不能吃"零命中短路"。
     *
     * <p>用户主动点「换源」(userInitiated)与"手动那一轮要跑全量"(fullPoolPass)都**绝不吃**:
     * 那一刻他的意图就是"还要继续找",用缓存少给几个源只会让他认为功能坏了。
     */
    @JvmStatic
    fun allowShortCircuit(userInitiated: Boolean, fullPoolPass: Boolean): Boolean =
        !userInitiated && !fullPoolPass

    /**
     * 把新增的零命中源并进已有名单:去重、**最近的排最后**、超上限时先丢最旧的。
     *
     * <p>丢最旧而不是丢最新:这份名单的用途是"别重问已经问过很久的",刚记下的那条恰恰最该挡住重问。
     */
    @JvmStatic
    fun merge(existing: List<String>, added: List<String>): List<String> {
        val out = ArrayList<String>(existing.size + added.size)
        existing.forEach { if (it.isNotBlank() && !out.contains(it)) out.add(it) }
        added.forEach { if (it.isNotBlank() && !out.contains(it)) out.add(it) }
        val overflow = (out.size - MAX_SOURCES_PER_TITLE).coerceAtLeast(0)
        return if (overflow == 0) out else out.subList(overflow, out.size)
    }
}
