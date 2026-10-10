package com.github.tvbox.osc.util

/**
 * 集数**集合**的纯数学工具(P-完整性第二阶段 A2,2026-10-10;无 Android 依赖,可 JVM 单测)。
 *
 * <p>存储/传输用**区间编码**:集合 {1..12, 14} ⇔ 字符串 "1-12,14"。
 * 200 集的片只需几个区间段,存储与解析成本都是 O(段数) 而非 O(集数)。
 *
 * <p>核心语义(跨源参考集合,文档 6.2/6.3):
 * <ul>
 *   <li>[union]:各源正片集号的并集 —— "目前已发现的内容全集";</li>
 *   <li>[confirmed]:被 **≥minSources 个独立源**实测到的集号 —— 可用于缺集断言与覆盖率的
 *       可信子集(单源观测可能是花絮/预告污染,不得作为确定证据);</li>
 *   <li>[missing]:某源相对 confirmed 的缺失集号(升序);</li>
 *   <li>[continuity]:从第 1 集起的连续前缀长度(文档 6.4 第一版口径)。</li>
 * </ul>
 */
object EpisodeSetMath {

    /** 集合 → 区间编码("1-12,14");空集 → "" */
    fun encode(set: Set<Int>): String {
        if (set.isEmpty()) return ""
        val sorted = set.toIntArray().also { it.sort() }
        val parts = ArrayList<String>()
        var start = sorted[0]
        var prev = start
        for (i in 1 until sorted.size) {
            val v = sorted[i]
            if (v == prev + 1) {
                prev = v
            } else {
                parts.add(if (start == prev) "$start" else "$start-$prev")
                start = v
                prev = v
            }
        }
        parts.add(if (start == prev) "$start" else "$start-$prev")
        return parts.joinToString(",")
    }

    /** 区间编码 → 集合;损坏片段静默跳过 */
    fun decode(s: String?): Set<Int> {
        if (s.isNullOrEmpty()) return emptySet()
        val out = HashSet<Int>()
        for (part in s.split(",")) {
            val t = part.trim()
            if (t.isEmpty()) continue
            val dash = t.indexOf('-')
            if (dash <= 0) {
                t.toIntOrNull()?.let { out.add(it) }
            } else {
                val a = t.substring(0, dash).toIntOrNull()
                val b = t.substring(dash + 1).toIntOrNull()
                if (a != null && b != null && a <= b && b - a <= 2000) {
                    for (v in a..b) out.add(v)
                }
            }
        }
        return out
    }

    /** 各源正片集号的并集(跨源参考集合) */
    fun union(sets: Collection<Set<Int>>): Set<Int> {
        val out = HashSet<Int>()
        sets.forEach { out.addAll(it) }
        return out
    }

    /**
     * 可信参考集:被 **≥minSources 个独立源**实测到的集号。
     * minSources=2 ⇒ 单源观测(可能是花絮/污染)不进入可信集 —— 《花开锦绣》37 集误标的根治。
     */
    fun confirmed(sets: Collection<Set<Int>>, minSources: Int = 2): Set<Int> {
        if (sets.size < minSources) return emptySet()
        val count = HashMap<Int, Int>()
        sets.forEach { s -> s.forEach { v -> count[v] = (count[v] ?: 0) + 1 } }
        return count.filterValues { it >= minSources }.keys
    }

    /** 某源相对可信参考集的缺失集号(升序) */
    fun missing(source: Set<Int>, confirmed: Set<Int>): List<Int> =
        confirmed.filterNot { source.contains(it) }.sorted()

    /** 从第 1 集起的连续前缀长度(文档 6.4 第一版口径) */
    fun continuity(source: Set<Int>): Int {
        var n = 0
        while (source.contains(n + 1)) n++
        return n
    }

    /** 缺失列表 → 连续区间列表(升序):[9,11,12] → [(9,9),(11,12)] */
    fun missingRanges(missing: List<Int>): List<Pair<Int, Int>> {
        if (missing.isEmpty()) return emptyList()
        val sorted = missing.distinct().sorted()
        val out = ArrayList<Pair<Int, Int>>()
        var start = sorted[0]
        var prev = start
        for (i in 1 until sorted.size) {
            val v = sorted[i]
            if (v == prev + 1) {
                prev = v
            } else {
                out.add(start to prev)
                start = v
                prev = v
            }
        }
        out.add(start to prev)
        return out
    }

    /** 缺失区间的展示串(数字与连接符,locale 无关):[9,11,12] → "9、11-12" */
    fun missingText(missing: List<Int>): String =
        missingRanges(missing).joinToString("、") { (a, b) -> if (a == b) "$a" else "$a-$b" }
}
