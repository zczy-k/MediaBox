package com.github.tvbox.osc.util

/**
 * 搜索页的全局设置(精准匹配开关)与搜索站点选择(写入侧)。
 *
 * 站点选择的读取与"过期"判定仍在 [SearchHelper];选择永远只保留当前源集合里存在的 key。
 */
object SearchSettings {

    enum class SearchLayout { Horizontal, Vertical }

    /**
     * 结果匹配强度(2026-10-04 新增,替代原来的布尔"精准搜索"):
     * - [All]   全部:源返回什么就显示什么,不过滤(旧"精准搜索=关"的行为);
     * - [Smart] 智能:归一化后**包含**关键词才保留(能收「庆余年 第二季」,又能滤掉源里跑题的条目),默认;
     * - [Exact] 精准:归一化后**完全相等**才保留(旧"精准搜索=开"的行为)。
     *
     * 无论哪种模式,结果都会按 [relevanceScore] 从高到低排序,让最贴合的条目浮到最前。
     */
    enum class MatchMode { All, Smart, Exact }

    // 该键未登记 KVKeySpec:读取必须带默认值(靠默认值携带 Boolean 类型),KV.get(key) 不带默认值会解不出
    private const val KEY_EXACT_MATCH = "search_exact_match"

    // 新键:三态匹配模式。未登记 KVKeySpec,读取带默认值(默认值携带 String 类型)
    private const val KEY_MATCH_MODE = "search_match_mode"

    private const val VALUE_MODE_ALL = "all"

    private const val VALUE_MODE_SMART = "smart"

    private const val VALUE_MODE_EXACT = "exact"

    // 同 KEY_EXACT_MATCH:未登记 KVKeySpec 的键读取必须带默认值
    private const val KEY_RESULT_LAYOUT = "search_result_layout"

    private const val VALUE_LAYOUT_HORIZONTAL = "horizontal"

    private const val VALUE_LAYOUT_VERTICAL = "vertical"

    // 底层"无记录=不限制"表达不了"一个都不搜",只能按源地址另记一份空选择
    private const val KEY_EMPTY_SOURCES = "search_sources_empty"

    // 精准匹配按结果逐条调用:正则必须预编译,写在 normalize 里等于每条结果都重新编译两次
    private val BRACKET_PATTERN = Regex("[（(\\[【][^）)\\]】]*[）)\\]】]")

    private val NOISE_PATTERN = Regex("[\\s\\p{Z}\\p{P}\\p{S}]")

    // 多词关键词切分(按空白),用于"每个词都出现"的低档相关度判定
    private val WHITESPACE_PATTERN = Regex("\\s+")

    /** 英数番号查询判定:如 MIAA-195 / FC2-PPV-4925855。仅限 ASCII 字母数字与常见分隔符。 */
    private val NUMBERED_CODE_PATTERN = Regex("(?i)^[a-z0-9]+(?:[-_.\\s]+[a-z0-9]+)*$")

    private val ASCII_LETTER_PATTERN = Regex("[a-z]", RegexOption.IGNORE_CASE)

    private val ASCII_DIGIT_PATTERN = Regex("[0-9]")

    private val ASCII_CODE_TOKEN_PATTERN = Regex("[a-z0-9]+", RegexOption.IGNORE_CASE)

    fun isExactMatchEnabled(): Boolean = KV.get(KEY_EXACT_MATCH, false)

    fun setExactMatchEnabled(enabled: Boolean) {
        KV.put(KEY_EXACT_MATCH, enabled)
    }

    /**
     * 当前匹配模式。未写入新键时从旧的布尔"精准搜索"迁移:
     * 旧开 → [MatchMode.Exact];旧关(含从未设置)→ [MatchMode.Smart](比旧的"全部"更准,作为新默认)。
     */
    fun matchMode(): MatchMode = when (KV.get(KEY_MATCH_MODE, "")) {
        VALUE_MODE_ALL -> MatchMode.All
        VALUE_MODE_SMART -> MatchMode.Smart
        VALUE_MODE_EXACT -> MatchMode.Exact
        else -> if (isExactMatchEnabled()) MatchMode.Exact else MatchMode.Smart
    }

    fun setMatchMode(mode: MatchMode) {
        KV.put(
            KEY_MATCH_MODE,
            when (mode) {
                MatchMode.All -> VALUE_MODE_ALL
                MatchMode.Smart -> VALUE_MODE_SMART
                MatchMode.Exact -> VALUE_MODE_EXACT
            },
        )
    }

    /** 结果是否按给定模式保留 */
    fun matches(name: String?, keyword: String?, mode: MatchMode): Boolean = when (mode) {
        MatchMode.All -> true
        MatchMode.Smart -> {
            val k = normalize(keyword)
            k.isNotEmpty() && normalize(name).contains(k)
        }
        // 精准文本仍要求全标题相等；但番号查询的“精准”应命中标题开头完整的番号 token，
        // 例如搜 MIAA-195 要能命中“ MIAA-195 + 标题”，不能要求整条影片标题只剩番号。
        MatchMode.Exact -> isExactMatch(name, keyword) || hasExactNumberedCodePrefix(name, keyword)
    }

    private fun hasExactNumberedCodePrefix(name: String?, keyword: String?): Boolean {
        val title = name?.trim().orEmpty()
        val query = keyword?.trim().orEmpty()
        if (title.isEmpty() || query.isEmpty() || !NUMBERED_CODE_PATTERN.matches(query)) return false
        if (!ASCII_LETTER_PATTERN.containsMatchIn(query) || !ASCII_DIGIT_PATTERN.containsMatchIn(query)) return false
        val queryTokens = ASCII_CODE_TOKEN_PATTERN.findAll(query).map { it.value.lowercase(java.util.Locale.ROOT) }.toList()
        if (queryTokens.isEmpty()) return false
        val titleTokens = ASCII_CODE_TOKEN_PATTERN.findAll(title).map { it.value.lowercase(java.util.Locale.ROOT) }.toList()
        if (titleTokens.isEmpty()) return false

        // 允许同一番号的分隔符输入不同: MIAA195 与标题中的 MIAA-195 都对应 miaa195。
        // 逐个累加标题开头的字母数字 token,必须刚好等于查询编号;严禁短号误命中长号(MIAA-19 ≠ MIAA-195)。
        val queryCode = queryTokens.joinToString(separator = "")
        val titleCode = StringBuilder()
        for (token in titleTokens) {
            titleCode.append(token)
            if (titleCode.length == queryCode.length) return titleCode.toString() == queryCode
            if (titleCode.length > queryCode.length) return false
        }
        return false
    }

    /**
     * 结果相关度(越高越贴合),用于排序让最佳匹配浮到最前:
     * 4=归一化相等;3=以关键词开头;2=包含关键词;1=多词关键词的每个词都出现;0=不匹配。
     */
    fun relevanceScore(name: String?, keyword: String?): Int {
        val n = normalize(name)
        val k = normalize(keyword)
        if (n.isEmpty() || k.isEmpty()) return 0
        if (n == k) return 4
        if (n.startsWith(k)) return 3
        if (n.contains(k)) return 2
        val tokens = keyword.orEmpty().trim().split(WHITESPACE_PATTERN)
            .map { normalize(it) }
            .filter { it.isNotEmpty() }
        if (tokens.size > 1 && tokens.all { n.contains(it) }) return 1
        return 0
    }

    /**
     * 番号类关键词的查询变体(2026-10-05)。
     *
     * <p>背景:客户端搜索是**原样把关键词透传给源站搜索接口**,不做本地检索。很多源站的库里把番号
     * 存成**无分隔符**形式(如 `MIAA195`),而用户按标题里的写法输入带横杠的 `MIAA-195`,
     * 源站 `LIKE '%MIAA-195%'` 就匹配不上 → 客户端一条都收不到。
     *
     * <p>故除原词外再补一个"去掉常见分隔符"的紧凑形式,让源站多匹配一次。原词始终排第一,
     * 结果去重。无分隔符的关键词(如「庆余年」)只返回原词,不增加任何请求。
     */
    fun queryVariants(keyword: String?): List<String> {
        val raw = keyword?.trim().orEmpty()
        if (raw.isEmpty()) return emptyList()
        val variants = LinkedHashSet<String>()
        variants.add(raw)
        val compact = raw.filterNot { ch ->
            ch.isWhitespace() || ch == '-' || ch == '_' || ch == '.' ||
                ch == '－' || ch == '—' || ch == '–' || ch == '−'
        }
        if (compact.isNotEmpty() && compact != raw) variants.add(compact)
        return variants.toList()
    }

    /** 搜索结果展示方式:竖排(左侧站点栏 + 右侧结果,**默认**)/ 横排(各源分区 + 横向卡片行) */
    fun resultLayout(): SearchLayout =
        if (KV.get(KEY_RESULT_LAYOUT, VALUE_LAYOUT_VERTICAL) == VALUE_LAYOUT_HORIZONTAL) {
            SearchLayout.Horizontal
        } else {
            SearchLayout.Vertical
        }

    fun setResultLayout(layout: SearchLayout) {
        KV.put(
            KEY_RESULT_LAYOUT,
            if (layout == SearchLayout.Vertical) VALUE_LAYOUT_VERTICAL else VALUE_LAYOUT_HORIZONTAL,
        )
    }

    /** 当前源地址是否被显式设为"不搜任何站点" */
    fun isSourcesEmpty(): Boolean {
        val api = KV.get(HawkConfig.API_URL, "")
        return api.isNotEmpty() && emptyApis().contains(api)
    }

    /**
     * 当前源地址的站点选择:null=不限制(全部可搜源),空集=不搜任何站点。
     *
     * 必须走不带默认值的 KV 读取(靠 KVKeySpec 登记的泛型还原内层 HashMap);带 `HashMap<>()` 默认值读会让
     * 内层退化成 Gson 的 LinkedTreeMap,Java 侧 `SearchHelper` 强转 HashMap 时抛异常并静默回落"全部"。
     */
    fun currentSelection(): Set<String>? {
        val api = KV.get(HawkConfig.API_URL, "")
        if (api.isEmpty()) return null
        if (isSourcesEmpty()) return emptySet()
        val stored = KV.get<HashMap<String, HashMap<String, String>>>(HawkConfig.SOURCES_FOR_SEARCH) ?: return null
        val picked = stored[api] ?: return null
        return if (picked.isEmpty()) null else picked.keys.toSet()
    }

    /** 写入当前地址的搜索站点选择;空选择=不搜任何站点,覆盖全部可搜源=不限制(删记录) */
    fun putSourcesForSearch(checked: Set<String>) {
        val api = KV.get(HawkConfig.API_URL, "")
        if (api.isEmpty()) return
        val searchable = SearchHelper.getSources().keys
        val picked = checked.filter { it in searchable }
        val all = KV.get<HashMap<String, HashMap<String, String>>>(HawkConfig.SOURCES_FOR_SEARCH) ?: HashMap()
        if (picked.isEmpty() || picked.containsAll(searchable)) {
            all.remove(api)
        } else {
            all[api] = HashMap<String, String>().apply { picked.forEach { this[it] = "1" } }
        }
        if (all.isEmpty()) KV.delete(HawkConfig.SOURCES_FOR_SEARCH) else KV.put(HawkConfig.SOURCES_FOR_SEARCH, all)
        setUpEmpty(api, picked.isEmpty() && searchable.isNotEmpty())
    }

    /** 精准匹配:归一化后相等。站点标题普遍带年份/集数等括注(如「庆余年(2019)」),直接相等会全滤掉 */
    fun isExactMatch(title: String?, keyword: String?): Boolean {
        val normalized = normalize(title)
        return normalized.isNotEmpty() && normalized == normalize(keyword)
    }

    /** 归一化 = 全角转半角 → 删括注及其内容 → 删空白与标点 → 忽略大小写;主体文字之外的差异(如「第二季」)仍然区分 */
    internal fun normalize(text: String?): String {
        if (text == null) return ""
        // 全角 ASCII(U+FF01..U+FF5E)与全角空格(U+3000)先转半角:用户常打出全角数字/字母导致匹配不上
        val sb = StringBuilder(text.length)
        for (ch in text) {
            sb.append(
                when (ch.code) {
                    in 0xFF01..0xFF5E -> (ch.code - 0xFEE0).toChar()
                    0x3000 -> ' '
                    else -> ch
                },
            )
        }
        return sb.toString()
            .replace(BRACKET_PATTERN, "")
            .replace(NOISE_PATTERN, "")
            .lowercase(java.util.Locale.ROOT)
    }

    private fun emptyApis(): Set<String> =
        KV.get(KEY_EMPTY_SOURCES, "").split('\n').filter { it.isNotEmpty() }.toSet()

    private fun setUpEmpty(api: String, empty: Boolean) {
        val next = emptyApis().toMutableSet()
        if (empty) next.add(api) else next.remove(api)
        if (next.isEmpty()) KV.delete(KEY_EMPTY_SOURCES) else KV.put(KEY_EMPTY_SOURCES, next.joinToString("\n"))
    }
}
