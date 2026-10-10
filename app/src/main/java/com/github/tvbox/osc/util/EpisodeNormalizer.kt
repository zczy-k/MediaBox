package com.github.tvbox.osc.util

/**
 * 剧集条目类型(P-完整性第二阶段 A1,2026-10-10)。
 * REGULAR=正片;SPECIAL=特别篇/OVA/番外;TRAILER=预告;BEHIND_SCENES=花絮/幕后;UNKNOWN=无法识别。
 */
enum class EpisodeType { REGULAR, SPECIAL, TRAILER, BEHIND_SCENES, UNKNOWN }

/**
 * 单条剧集条目的标准化结果。
 *
 * @param season  季数;null=无法识别(不虚构"只有一季")
 * @param episode 集数;null=无法识别。S01E01 记 season=1,episode=1
 * @param type    条目类型;只有 REGULAR 参与正片计数
 * @param confidence 解析置信度 0.0-1.0;低置信度不得作为缺集/存在的确定证据
 */
data class EpisodeInfo(
    val season: Int?,
    val episode: Int?,
    val type: EpisodeType,
    val confidence: Double,
)

/**
 * 集名标准化解析器(P-完整性第二阶段 A1,2026-10-10;纯函数+LRU 缓存,无 Android 依赖)。
 *
 * <p>## 为什么独立于 EpisodeTotals
 * `EpisodeTotals` 是**历史页进度条**的严格过滤器(多版本铺列返回 null、单序号返回 null),
 * 语义是"敢不敢显示进度";本解析器是**跨源完整度计数**的结构化引擎,需要季/集/类型/置信度
 * 四元组。两者消费场景不同、容错口径不同 —— 各自维护,避免互相拖累(文档 5.3 的例外说明)。
 *
 * <p>## 判定顺序(先特殊后正片,顺序即正确性)
 * 1. 预告/花絮/幕后/NG/特辑 关键词 → TRAILER/BEHIND_SCENES("第37集预告"归 TRAILER,
 *    **这正是《花开锦绣》37 集误标的根治点**);
 * 2. OVA/SP01/番外/特别篇 → SPECIAL;
 * 3. S01E02 / 第2季第3集 → 带季数 REGULAR;
 * 4. 第N集/期/话、EP/Episode N → REGULAR;
 * 5. 纯序号(可带清晰度后缀) → REGULAR;**4 位 1900-2100 判为年份 → UNKNOWN**;
 * 6. 其余(“TC中字”“超级无敌4K”“正片”) → UNKNOWN —— 不参与正片计数,但条目保留、可播。
 *
 * <p>## 性能
 * 预编译 Regex + LRU 缓存(512 条):分批搜索/面板重组中同一集名反复出现时近零成本。
 */
object EpisodeNormalizer {

    private val UNKNOWN_INFO = EpisodeInfo(null, null, EpisodeType.UNKNOWN, 0.0)
    private const val CACHE_MAX = 512

    private val cache = object : LinkedHashMap<String, EpisodeInfo>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, EpisodeInfo>) =
            size > CACHE_MAX
    }

    private val RE_TRAILER = Regex("预告|trailer", RegexOption.IGNORE_CASE)
    private val RE_BEHIND = Regex("花絮|幕后|NG|特辑|behind", RegexOption.IGNORE_CASE)
    private val RE_SPECIAL = Regex("OVA|SP\\d|番外|特别篇|special", RegexOption.IGNORE_CASE)
    private val RE_SEASON_EP =
        Regex("(?:第\\s*(\\d{1,2})\\s*季\\s*第\\s*(\\d{1,4})\\s*[集期话話]?)|(?i)(?:S(\\d{1,2})\\s*E(\\d{1,4}))")
    private val RE_RANGE =
        Regex("第?\\s*(\\d{1,4})\\s*[-—~～]\\s*(\\d{1,4})\\s*[集期话話]?")
    private val RE_EP =
        Regex("第\\s*(\\d{1,4})\\s*[集期话話]|(?i)(?:ep|episode)\\.?\\s*(\\d{1,4})")
    private val RE_BARE = Regex("^(\\d{1,4})(?:\\.[a-z0-9]{1,5})?$")

    fun normalize(name: String?): EpisodeInfo {
        if (name.isNullOrEmpty()) return UNKNOWN_INFO
        synchronized(cache) { cache[name]?.let { return it } }
        val result = parse(name.trim())
        synchronized(cache) { cache[name] = result }
        return result
    }

    private fun parse(text: String): EpisodeInfo {
        if (text.isEmpty()) return UNKNOWN_INFO
        val lower = text.lowercase()

        if (RE_TRAILER.containsMatchIn(lower)) {
            return EpisodeInfo(seasonOf(text), episodeOf(text), EpisodeType.TRAILER, 0.9)
        }
        if (RE_BEHIND.containsMatchIn(lower)) {
            return EpisodeInfo(seasonOf(text), episodeOf(text), EpisodeType.BEHIND_SCENES, 0.9)
        }
        if (RE_SPECIAL.containsMatchIn(lower)) {
            return EpisodeInfo(seasonOf(text), episodeOf(text), EpisodeType.SPECIAL, 0.9)
        }
        RE_SEASON_EP.find(text)?.let { m ->
            val g = m.groupValues
            val season = (g[1].ifEmpty { g[3] }).toIntOrNull()
            val ep = (g[2].ifEmpty { g[4] }).toIntOrNull()
            if (season != null && ep != null) {
                return EpisodeInfo(season, ep, EpisodeType.REGULAR, 0.95)
            }
        }
        // 合并条目(第1-2集):解析为起始集但降置信度 —— 不虚增两个条目(文档 5.3.7)
        RE_RANGE.find(text)?.let { m ->
            val start = m.groupValues[1].toIntOrNull()
            if (start != null && start !in 1900..2100) {
                return EpisodeInfo(null, start, EpisodeType.REGULAR, 0.5)
            }
        }
        RE_EP.find(text)?.let { m ->
            val g = m.groupValues
            val ep = (g[1].ifEmpty { g[2] }).toIntOrNull()
            if (ep != null && ep in 1..9999) {
                return EpisodeInfo(null, ep, EpisodeType.REGULAR, 0.9)
            }
        }
        RE_BARE.find(text)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: return UNKNOWN_INFO
            // 4 位年份段(1900-2100)不能当集数(文档 17.1"年份误识别"红线)
            if (n in 1900..2100) return UNKNOWN_INFO
            return EpisodeInfo(null, n, EpisodeType.REGULAR, 0.85)
        }
        return UNKNOWN_INFO
    }

    /** 从任意位置提取首个独立序号(供特殊类条目附带集号用;纯年份不算) */
    private fun episodeOf(text: String): Int? = RE_EP.find(text)?.let { m ->
        val g = m.groupValues
        (g[1].ifEmpty { g[2] }).toIntOrNull()
    } ?: RE_BARE.find(text)?.let { m ->
        m.groupValues[1].toIntOrNull()?.takeIf { it !in 1900..2100 }
    }

    private fun seasonOf(text: String): Int? =
        Regex("第\\s*(\\d{1,2})\\s*季").find(text)?.groupValues?.get(1)?.toIntOrNull()
}
