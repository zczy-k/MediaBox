package com.github.tvbox.osc.player

/**
 * 按 CDN 主机记两条结论:上次**靠哪档身份**测通、以及**多久之内别再探**(2026-10-15 智能探测)。
 *
 * <p>## 为什么只放内存、不落盘
 * 这两条结论都过期得很快:站点换 CDN、加或撤鉴权、今天网关抖动。把"这次 403"变成持久标签,
 * 后果是整个站的画质优选长期失效 —— 而那正是这次要修的毛病(真机《玩具总动员5》:8 条线路
 * 探测 18 次全 403,实测档一条都攒不出来)。所以内存记忆的代价只有"重启后多花几次探测",
 * 而落盘的代价是"把一个瞬时判断刻成永久事实"。
 *
 * <p>键是 host 而不是整条 URL:签名参数每次都变,按 URL 记等于什么都没记住。
 *
 * <p>⚠️ 探测是多条线路**并发**跑的(一次三到五条),所以四个入口都加了 `@Synchronized`。
 * 这里的数据全是"提示",丢了不会错,因此不追求原子性,只追求不崩、不读到半个 map。
 */
object ProbeHostMemory {

    /** 记多少个站:再多也只是几十 KB,但内存记忆本来就该只覆盖"这次会话真碰到的站" */
    private const val MAX_HOSTS = 64

    private class Entry {
        var tactic: ProbeTactic? = null
        var cooldownUntil: Long = 0L
    }

    private val hosts = HashMap<String, Entry>()

    /** 这个站上次是靠哪档测通的(没记过返回 null ⇒ 从默认档开始) */
    @Synchronized
    fun lastSuccessful(host: String?): ProbeTactic? = host?.let { hosts[it]?.tactic }

    /** 测通了就记下这一档,下次同站直接从这里开始 —— 省掉前面几次注定失败的往返 */
    @Synchronized
    fun rememberSuccess(host: String?, tactic: ProbeTactic) {
        val h = host ?: return
        val entry = hosts.getOrPut(h) { Entry() }
        entry.tactic = tactic
    }

    @Synchronized
    fun isCooling(host: String?, nowMs: Long): Boolean {
        val h = host ?: return false
        val until = hosts[h]?.cooldownUntil ?: 0L
        return until > nowMs
    }

    /** 给这个站上冷却(时长由 [ProbeStrategy.coolDownMs] 按失败性质定,0 = 不冷却) */
    @Synchronized
    fun coolDown(host: String?, ms: Long, nowMs: Long) {
        if (host == null || ms <= 0L) return
        if (hosts.size >= MAX_HOSTS && !hosts.containsKey(host)) hosts.clear() // 满了就整张重来:提示缓存,丢了最坏是多探几次
        hosts.getOrPut(host) { Entry() }.cooldownUntil = maxOf(
            hosts[host]?.cooldownUntil ?: 0L,
            nowMs + ms,
        )
    }

    /** 诊断/测试用:当前记了几个站 */
    @Synchronized
    fun trackedHosts(): Int = hosts.size

    /** 同时最多记多少个"正在探测中"的地址(超了就放弃去重,不能挡正常探测) */
    private const val MAX_IN_FLIGHT = 32
    private val inFlight = HashSet<String>()

    /**
     * 占住这个地址的探测权;返回 false = 已经有人在探同一条地址。
     *
     * <p>真机日志里同一 `index.m3u8` 会在两三百毫秒内被打两次 —— "起播后小探"与"详情页深探"
     * 各自独立跑,谁也不知道对方在探什么。探测本身是**和播放抢带宽**的,同一条地址探两次
     * 只是把同一件事做两遍,所以这里做去重;满了就直接放行(宁可多探一次也不能挡路)。
     */
    @Synchronized
    fun beginProbe(url: String): Boolean {
        if (!inFlight.add(url)) return false
        if (inFlight.size > MAX_IN_FLIGHT) {
            inFlight.remove(url)
            return true
        }
        return true
    }

    @Synchronized
    fun endProbe(url: String) {
        inFlight.remove(url)
    }

    /** 测试用:清空 */
    @Synchronized
    fun clearForTest() {
        hosts.clear()
        inFlight.clear()
    }
}
