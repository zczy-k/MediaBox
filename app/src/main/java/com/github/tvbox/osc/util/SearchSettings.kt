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

    /** 别名分隔符:与 {@code AbsJson.AbsJsonVod.joinAliases} 的输出口径一致 */
    private val ALIAS_SPLIT_PATTERN = Regex("[|,，、;；]")

    // 多词关键词切分(按空白),用于"每个词都出现"的低档相关度判定
    private val WHITESPACE_PATTERN = Regex("\\s+")

    /** 英数番号查询判定:如 MIAA-195 / FC2-PPV-4925855。仅限 ASCII 字母数字与常见分隔符。 */
    private val NUMBERED_CODE_PATTERN = Regex("(?i)^[a-z0-9]+(?:[-_.\\s]+[a-z0-9]+)*$")

    private val ASCII_LETTER_PATTERN = Regex("[a-z]", RegexOption.IGNORE_CASE)

    private val ASCII_DIGIT_PATTERN = Regex("[0-9]")

    private val ASCII_CODE_TOKEN_PATTERN = Regex("[a-z0-9]+", RegexOption.IGNORE_CASE)

    /**
     * 归一化缓存(2026-10-08 搜索准确度/性能改造)。
     *
     * <p>存在理由:搜索页里 {@link #relevanceScore} 每条结果至少被调用两次(过滤一次、排序一次),
     * 一次搜索几百源、每源十几条就是**上万次**归一化;而 {@link #normalize} 每次都新建 StringBuilder、
     * 跑两条正则 replace 再 lowercase。同一批结果里又有大量重复标题(不同源的同一部片子)。
     *
     * <p>容量刻意保守(512):搜索页生命周期很短,一次搜索涉及的标题种类通常远小于这个数,
     * 不需要更激进的淘汰策略;满了整体清空,不做 LRU 维护。
     */
    private const val NORMALIZE_CACHE_CAPACITY = 512

    private val normalizeCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * [normalize] 的带缓存版本。
     *
     * <p>null 与空串不进缓存:null 的语义是"没有标题",与"标题归一化后为空串"在匹配上的处理不同,
     * 不能混同(见 {@link #matches})。
     */
    fun normalizeCached(text: String?): String {
        if (text.isNullOrEmpty()) return normalize(text)
        normalizeCache[text]?.let { return it }
        val normalized = normalize(text)
        if (normalizeCache.size >= NORMALIZE_CACHE_CAPACITY) normalizeCache.clear()
        normalizeCache[text] = normalized
        return normalized
    }


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

    /** 结果是否按给定模式保留(只看标题)。 */
    fun matches(name: String?, keyword: String?, mode: MatchMode): Boolean = matches(name, null, keyword, mode)

    /**
     * 结果是否按给定模式保留,**标题与别名一起参与**(2026-10-08)。
     *
     * <p>为什么必须看别名:JSON 型源的列表接口把副标题/英文名放在 vod_sub / vod_en,
     * 客户端此前只按 name 过滤,于是"源站能搜到、客户端判不匹配"的结果被整批丢掉。
     * 别名命中给到的分数略低于标题命中(见 [relevanceScore]),排序上仍让标题匹配优先。
     */
    fun matches(name: String?, alias: String?, keyword: String?, mode: MatchMode): Boolean = when (mode) {
        MatchMode.All -> true
        MatchMode.Smart -> {
            val k = normalizeCached(keyword)
            k.isNotEmpty() && (normalizeCached(name).contains(k) || aliasesContain(alias, k))
        }
        // 精准文本仍要求全标题相等；但番号查询的“精准”应命中标题开头完整的番号 token，
        // 例如搜 MIAA-195 要能命中“ MIAA-195 + 标题”，不能要求整条影片标题只剩番号。
        //
        // ⚠️ 别名在精准模式下用**相等**而非包含（aliasEquals,不是 aliasesContain）：
        // 「精准」的用户契约是"就是这部片子",别名写成「三体的异世界」也命中「三体」的话,
        // 精准模式实际退化成了智能模式,开了等于没开。
        MatchMode.Exact -> isExactMatch(name, keyword) || hasExactNumberedCodePrefix(name, keyword) ||
            aliasEquals(alias, normalizeCached(keyword))
    }

    /** 别名里是否有任一条归一化后**完全等于**关键词(别名按 {@code |} 分隔,口径同 AbsJson.joinAliases) */
    private fun aliasEquals(alias: String?, normalizedKeyword: String): Boolean {
        if (alias.isNullOrEmpty() || normalizedKeyword.isEmpty()) return false
        return alias.split(ALIAS_SPLIT_PATTERN).any { normalizeCached(it) == normalizedKeyword }
    }

    /** 别名里是否有任一条归一化后包含关键词(别名按 {@code |} 分隔,口径同 AbsJson.joinAliases) */
    private fun aliasesContain(alias: String?, normalizedKeyword: String): Boolean {
        if (alias.isNullOrEmpty() || normalizedKeyword.isEmpty()) return false
        return alias.split(ALIAS_SPLIT_PATTERN).any { normalizeCached(it).contains(normalizedKeyword) }
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
     * 5=归一化相等;4=以关键词开头;3=包含关键词;2=多词关键词的每个词都出现;
     * 1=别名完全等于关键词;0=不匹配。
     *
     * <p>2026-10-08:分值整体上移一位,给"仅别名命中"留出位置 ——
     * 别名命中不该和"标题包含"同级,否则一部片的英文名会把真正的标题匹配压下去。
     */
    fun relevanceScore(name: String?, keyword: String?): Int = relevanceScore(name, null, keyword)

    /** 同 [relevanceScore],额外考虑别名(见 [matches] 的 KDoc) */
    fun relevanceScore(name: String?, alias: String?, keyword: String?): Int {
        val n = normalizeCached(name)
        val k = normalizeCached(keyword)
        if (k.isEmpty()) return 0
        if (n.isEmpty()) return aliasScore(alias, k)
        if (n == k) return 5
        if (n.startsWith(k)) return 4
        if (n.contains(k)) return 3
        val tokens = keyword.orEmpty().trim().split(WHITESPACE_PATTERN)
            .map { normalizeCached(it) }
            .filter { it.isNotEmpty() }
        if (tokens.size > 1 && tokens.all { n.contains(it) }) return 2
        return aliasScore(alias, k)
    }

    /** 仅别名命中的分数:完全相等给 1(低于任何标题命中);只"包含"关系不给正分(当作不匹配) */
    private fun aliasScore(alias: String?, normalizedKeyword: String): Int =
        if (aliasEquals(alias, normalizedKeyword)) 1 else 0

    /**
     * 番号类关键词的查询变体(2026-10-05 引入,2026-10-08 改口径)。
     *
     * <p>背景:客户端搜索是**原样把关键词透传给源站搜索接口**,不做本地检索。很多源站的库里把番号
     * 存成**无分隔符**形式(如 `MIAA195`),而用户按标题里的写法输入带横杠的 `MIAA-195`,
     * 源站 `LIKE '%MIAA-195%'` 就匹配不上 → 客户端一条都收不到。
     *
     * <p>故除原词外再补一个"去掉常见分隔符"的紧凑形式。原词始终排第一,结果按 id/标题去重。
     * 无分隔符的关键词(如「庆余年」)只返回原词,不增加任何请求。
     *
     * <p>⚠️ 2026-10-08:调用方**不要**对每个源无条件把全部变体都发出去 ——
     * 变体是"主词没打中时的补充",不是"每次都补一刀"。见 [SearchViewModel] 的
     * {@code runSourceSearch}:只有原词这一轮**没有任何有效命中**时才发紧凑形式,
     * 因此普通片名(无分隔符)全程仍是每源一次请求。
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
        val normalized = normalizeCached(title)
        return normalized.isNotEmpty() && normalized == normalizeCached(keyword)
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
