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
     * 番号尾部的**版本标记**(如 `ADN-811-RM` 的 `-RM`、`FC2-PPV-4925855` 的 `-PPV`)。
     *
     * <p>匹配结尾的 1~4 个字母,分隔符**可选** —— 用户可能写 `ADN-811-RM`,
     * 也可能写 `ADN811RM`(不加分隔符),两者都该剥成 `adn811`。
     *
     * <p>1~4 字母的上限:真正的番号尾段通常是数字,不会被 `[a-z]{1,4}` 匹配到;
     * 而常见版本标记(`RM`/`C`/`UC`/`PPV`/`UNC`)都在 4 字母以内。
     */
    private val TRAILING_VERSION_TAG = Regex("[-_.\\s]*[a-z]{1,4}$", RegexOption.IGNORE_CASE)

    /**
     * [extractCode] 的缓存(2026-10-12)。
     *
     * <p>本方法被**逐条结果**调用:一次搜索上千条 × (matches 一次 + relevanceScore 一次),
     * 而关键词在一次搜索内固定不变。不缓存的话每条结果都要跑一遍正则 + 归一化。
     * 容量取 64:正常使用中"当前搜索词"只有 1~2 个,64 足够吸收连续换词的抖动。
     */
    private const val CODE_CACHE_CAPACITY = 64

    private val codeCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * 归一化缓存(2026-10-08 搜索准确度/性能改造)。
     *
     * <p>存在理由:搜索页里 {@link #relevanceScore} 每条结果至少被调用两次(过滤一次、排序一次),
     * 一次搜索几百源、每源十几条就是**上万次**归一化;而 {@link #normalize} 每次都新建 StringBuilder、
     * 跑两条正则 replace 再 lowercase。同一批结果里又有大量重复标题(不同源的同一部片子)。
     *
     * <p>## 容量与淘汰(2026-10-11 调整)
     * 容量 512 保持不变。但**淘汰策略从"满了整体清空"改为"淘汰约四分之一"**:
     * 整体清空在真实搜索规模下会反复触发 —— 一次搜索涉及数千个不同标题(每源十几条 × 数百源),
     * 于是"涨到 512 → 清空 → 再涨到 512 → 再清空"会来回十几次,每次清空后的下一批
     * 全都要重新跑正则。淘汰四分之一既保住了缓存的主体,又不会让 map 无限增长。
     *
     * <p>⚠️ 仍用 [java.util.concurrent.ConcurrentHashMap] 而不是 LRU LinkedHashMap:
     * 本方法是**每次匹配都会走的热路径**(单次搜索上万次调用),ConcurrentHashMap 的读是无锁的,
     * 而 LRU 需要对每次 `get` 加锁(accessOrder 会改结构)—— 那是拿最热的路径去换最冷的收益。
     */
    private const val NORMALIZE_CACHE_CAPACITY = 512

    /** 超容量时淘汰的比例(约 1/4);见 [normalizeCached] 的说明 */
    private const val NORMALIZE_EVICT_FRACTION = 4

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
        if (normalizeCache.size >= NORMALIZE_CACHE_CAPACITY) {
            // 淘汰约 1/4 而不是清空:见上方 KDoc。迭代器是弱一致的,
            // 并发下可能少删或多删几个,都无害 —— 这里的正确性只要求"最终会缩小"。
            val target = normalizeCache.size / NORMALIZE_EVICT_FRACTION
            if (target > 0) {
                val it = normalizeCache.keys.iterator()
                var removed = 0
                while (removed < target && it.hasNext()) {
                    it.next()
                    it.remove()
                    removed++
                }
            }
        }
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
     *
     * <p>⚠️ 2026-10-12:**番号查询走宽松匹配**(见 [matchesCode])。
     * 原实现要求整串查询完整出现在标题里,于是 `ADN-811-RM` 只能命中标题里恰好也带
     * `-RM` 的源 —— 表现为"同一个番号时好时坏"(真机日志 `hit=0`,源答话了但全被滤掉)。
     * 番号的主体是"字母块+数字块",其后的 `-RM/-C/-UC` 是**版本后缀**,不该参与匹配。
     */
    fun matches(name: String?, alias: String?, keyword: String?, mode: MatchMode): Boolean {
        // "全部"模式不过滤:番号判据也不能拦(那是用户显式要求的"都给我")
        if (mode == MatchMode.All) return true
        // 番号查询:**只**走番号语义(2026-10-12)。
        //
        // ⚠️ 必须**短路**,不能"番号不中再落回通用包含" —— 否则 `ADN-81` 会经由
        // Smart 的 `normalize(name).contains(k)` 命中 `ADN-811`(裸子串包含),
        // 短号误命中长号的老问题原样回来。番号有明确边界语义,就该用它自己的判据。
        if (extractCode(keyword).isNotEmpty()) {
            return matchesCode(name, alias, keyword)
        }
        return when (mode) {
            MatchMode.All -> true
            MatchMode.Smart -> {
                val k = normalizeCached(keyword)
                k.isNotEmpty() && (normalizeCached(name).contains(k) || aliasesContain(alias, k) || auxContains(name, k))
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
     * 从查询里抽出**番号主体**:去掉尾部的版本后缀后归一化,如:
     * ```
     * ADN-811-RM      -> adn811   (尾部 RM 是版本标记,去掉)
     * ADN811          -> adn811
     * MIAA-195        -> miaa195
     * FC2-PPV-4925855 -> fc24925855 (尾部 PPV 是版本标记,去掉)
     * ```
     * 抽不出(纯中文 / 无数字)⇒ 空串。
     *
     * <p>## 为什么需要它(2026-10-12 真机反馈)
     * 用户搜 `ADN-811-RM` 时**时好时坏**。根因:原匹配要求整串
     * `adn811rm` **完整出现**在归一化标题里,而源站标题常常只写 `ADN-811`(不带 `-RM`)——
     * 于是"标题恰好带 -RM 的源命中、不带的全被滤掉",表现为随机。
     *
     * <p>真机日志证据:`echo-searchplan ... hit=0 empty=23` ——
     * 源站**答话了**,但客户端把结果全判为不匹配。
     *
     * <p>## 算法:剥尾部版本标记,而不是"取前两段"
     * 先试过"取第一段字母+数字"的写法,但它对 `FC2-PPV-4925855` 这类
     * "字母块里带数字、且中间夹版本标记"的番号会切错。改为**从尾部剥**:
     * 反复去掉结尾的短字母标记(1~4 个字母,如 `RM`/`C`/`UC`/`PPV`),剩下的就是番号主体。
     * 这样"番号在前、版本标记在后"的通用形态都能正确处理。
     *
     * <p>⚠️ 带缓存:本方法在 [matches] 与 [relevanceScore] 里被**逐条结果**调用
     * (一次搜索上千条 × 2),而关键词在一次搜索内是固定的。缓存把重复的正则与
     * 归一化开销压到一次。
     */
    fun extractCode(keyword: String?): String {
        val raw = keyword?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        codeCache[raw]?.let { return it }
        val result = extractCodeUncached(raw)
        if (codeCache.size >= CODE_CACHE_CAPACITY) codeCache.clear()
        codeCache[raw] = result
        return result
    }

    private fun extractCodeUncached(raw: String): String {
        // 必须在 ASCII 字母数字与分隔符的范围内,否则不是番号查询
        if (!NUMBERED_CODE_PATTERN.matches(raw)) return ""
        // 反复剥掉结尾的短字母版本标记,但**只在剥完仍同时含字母与数字时才接受** ——
        // 否则说明剥过头了(如纯字母串 "thematrix" 会被一路剥空)。
        var body = raw
        var guard = 0
        while (guard++ < 4) {
            val m = TRAILING_VERSION_TAG.find(body) ?: break
            if (m.range.first == 0) break
            val candidate = body.substring(0, m.range.first).trimEnd('-', '_', '.', ' ')
            if (!hasLetterAndDigit(candidate)) break
            body = candidate
        }
        val code = normalizeCached(body)
        if (!hasLetterAndDigit(code)) return ""
        return code
    }

    /** 同时含 ASCII 字母与数字(番号的必要条件) */
    private fun hasLetterAndDigit(s: String): Boolean =
        s.any { it.isDigit() } && s.any { it in 'a'..'z' || it in 'A'..'Z' }

    /**
     * **番号的宽松匹配**(2026-10-12):查询抽出的番号主体出现在标题里即算命中。
     *
     * <p>与 [hasExactNumberedCodePrefix] 的区别(后者只服务"精准模式",要求番号在**标题开头**):
     * 本方法只要求**包含**,因为番号常出现在标题中段
     * (如 `[Reducing Mosaic] ADN-811 I Want You To Love Me`)。
     *
     * <p>⚠️ 仍严禁短号误命中长号:`ADN-81` **不得**命中 `ADN-811`。
     * 做法是在归一化标题里找番号主体,并检查**其后不能紧跟数字**
     * (紧跟数字说明命中的是更长番号)。
     *
     * <p>⚠️ 刻意**不**对标题调 [extractCode]:标题是"番号 + 片名"的长串,
     * 抽出来的会是"番号+片名"而不是番号本身,比对必然失败(白跑一次正则)。
     * 标题侧只做"边界检查的包含"。
     */
    fun matchesCode(name: String?, alias: String?, keyword: String?): Boolean {
        val code = extractCode(keyword)
        if (code.isEmpty()) return false
        return containsCode(normalizeCached(name), code) || containsCode(normalizeCached(alias), code)
    }

    /**
     * 归一化文本里是否**以番号为主体**地包含 [code](避免 `adn81` 命中 `adn811`)。
     *
     * <p>边界规则:
     * <ul>
     *   <li>命中位置**之前**若仍是字母数字,说明是更长番号的一部分(如 `xadn811`)⇒ 不算;</li>
     *   <li>命中位置**之后**若仍是数字,说明是更长番号(如 `adn8112`)⇒ 不算;</li>
     *   <li>之后是字母则**可以**(如 `adn811rm`、`adn811title`)—— 那是版本后缀或片名。</li>
     * </ul>
     */
    private fun containsCode(normalizedText: String, code: String): Boolean {
        if (normalizedText.isEmpty()) return false
        var from = 0
        while (true) {
            val idx = normalizedText.indexOf(code, from)
            if (idx < 0) return false
            val before = idx - 1
            val after = idx + code.length
            val beforeOk = before < 0 || !normalizedText[before].isLetterOrDigit()
            val afterOk = after >= normalizedText.length || !normalizedText[after].isDigit()
            if (beforeOk && afterOk) return true
            from = idx + 1
        }
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
    fun relevanceScore(name: String?, alias: String?, keyword: String?): Int =
        relevanceScore(name, alias, keyword, null)

    /**
     * 同 [relevanceScore],额外考虑别名与 note(见 [matches] 的 KDoc)。
     *
     * <p>2026-10-10 正片优先:标题/note 命中"预告/花絮/幕后/特辑/片花"的非正片内容,
     * 分值压到 ≤2 —— 低于任何"标题包含关键词"的正片(3),高于纯别名命中(1)。
     * "花开锦绣 预告"再贴词也不能排在正片前面;关键词本身含这些词时不降权
     * (用户就是在搜预告,全员降权等于全不排)。
     */
    fun relevanceScore(name: String?, alias: String?, keyword: String?, note: String?): Int {
        val n = normalizeCached(name)
        val k = normalizeCached(keyword)
        if (k.isEmpty()) return 0
        var base = if (n.isEmpty()) {
            aliasScore(alias, k)
        } else when {
            n == k -> 5
            n.startsWith(k) -> 4
            n.contains(k) -> 3
            else -> {
                val tokens = keyword.orEmpty().trim().split(WHITESPACE_PATTERN)
                    .map { normalizeCached(it) }
                    .filter { it.isNotEmpty() }
                if (tokens.size > 1 && tokens.all { n.contains(it) }) 2 else aliasScore(alias, k)
            }
        }
        // 长音符辅助匹配(2026-10-10,《日文支持》§二.5):主形式完全未命中时剥长音符再比,
        // 给 2 分 —— 低于任何主形式标题命中,高于纯别名(1);不直接认定剥长音后相等=同一作品
        if (base == 0 && n.isNotEmpty()) {
            val auxK = k.replace("ー", "")
            if (auxK.isNotEmpty() && n.replace("ー", "").contains(auxK)) base = 2
        }
        // 番号查询:补一档"番号主体命中"的分(2026-10-12)。
        //
        // ⚠️ 只能**兜底补分**,不能**抢先返回** —— 否则会推翻更精确的通用档位。
        // 真机 CI 抓到的回归:查询 `ABC-123` 时标题也是 `ABC-123`(全等,应为 5),
        // 但 `extractCode` 把它判成番号并返回 4,把"全等"降级成了"前缀"。
        // 所以这里用 max:通用档位更贴切就保留通用档位。
        //
        // 番号主体命中说明"这是同一个番号"(可能是不同版本),给 3~4 分;
        // 且**不套**"非正片降权"(用户搜番号就是要全部版本,压到 2 分会让他们沉底,
        // 与"搜得全面"的诉求相反),故在此直接返回。
        val code = extractCode(keyword)
        if (code.isNotEmpty() && containsCode(n, code)) {
            val codeScore = if (n.startsWith(code)) 4 else 3
            return maxOf(base, codeScore)
        }
        if (base <= 0) return base
        return if (isNonMainContent(name, note, keyword)) minOf(base, 2) else base
    }

    /**
     * 非正片内容标记:命中即视为花絮/预告/资讯类,只用于排序降权,绝不隐藏/过滤
     * (条目仍可命中、可点开播放)。词表口径"语义明确才收",与可用性粗筛同一哲学。
     */
    private val NON_MAIN_CONTENT_PATTERN = Regex("预告|花絮|幕后|特辑|片花|路透|探班|开机|杀青|发布会|专访|采访|混剪|解说")

    /**
     * 标题长度启发式的富余阈值:正片标题 = 关键词 + 少量富余(年份/季数/短副题,
     * 如"花开锦绣2026"=+3、"西游记之大圣归来"=+6),资讯/路透/二创类 = 关键词 + 长描述
     * ("开机大吉!丁禹兮邓恩熙《花开锦绣》养眼同框"=+17)。+8 是两者之间保守的分界,
     * 宁漏杀不误杀 —— 长名副标题的正片(≤+8)不受影响。
     */
    private const val MAIN_TITLE_MAX_EXTRA = 8

    /**
     * 结果是否为非正片内容。三重特征(2026-10-10):
     * ① 标题/note 命中识别词(预告/花絮/路透/开机…);
     * ② 标题长度启发式:包含关键词且超长富余(关键词+8 以上)——非正片标题普遍是
     *    "关键词+长描述",字数显著多于正片标题;
     * 关键词本身含识别词时一律返回 false —— 搜索意图就是非正片时不降权。
     */
    fun isNonMainContent(name: String?, note: String?, keyword: String?): Boolean {
        val k = normalizeCached(keyword)
        if (k.isEmpty() || NON_MAIN_CONTENT_PATTERN.containsMatchIn(k)) return false
        // ⚠️ 先按**原始名**判,再按规范化名判(2026-10-15 真机:`怒之杀[电影解说]`被当成精确同名)。
        // normalize() 会把整段括号删掉,于是"解说"这个词在读到之前就消失了 ——
        // 降权规则形同虚设,衍生条目直接以 5 分(全等)排到正片前面。
        if (NON_MAIN_CONTENT_PATTERN.containsMatchIn(name.orEmpty())) return true
        val n = normalizeCached(name)
        if (NON_MAIN_CONTENT_PATTERN.containsMatchIn(n)) return true
        if (NON_MAIN_CONTENT_PATTERN.containsMatchIn(note.orEmpty())) return true
        // 长度启发式只对"包含关键词"的条目生效(全等/别名命中不涉及长度差);
        // 番号/拉丁字母关键词豁免 —— 番号正片标题普遍是"编号+长日文副题",长度差天然大,会误伤
        if (ASCII_LETTER_PATTERN.containsMatchIn(k)) return false
        return n.contains(k) && n.length - k.length > MAIN_TITLE_MAX_EXTRA
    }

    /** 仅别名命中的分数:完全相等给 1(低于任何标题命中);只"包含"关系不给正分(当作不匹配) */
    private fun aliasScore(alias: String?, normalizedKeyword: String): Int =
        if (aliasEquals(alias, normalizedKeyword)) 1 else 0

    /** 长音符辅助包含(2026-10-10):主形式未命中时剥片假名长音符再比 —— 「ゲセン」能命中「ゲーセン」 */
    private fun auxContains(name: String?, k: String): Boolean {
        val auxK = k.replace("ー", "")
        if (auxK.isEmpty()) return false
        return normalizeCached(name).replace("ー", "").contains(auxK)
    }

    // ==================== 查询意图与冲突保护(2026-10-10,《排序引擎》§6/§7.5/§10.3) ====================

    /**
     * 查询意图:从用户输入中**可靠识别**出的结构化约束。识别不了的字段一律保持空值 ——
     * 宁可退化成普通关键词参与文本匹配,也不强制生成错误的结构化条件。
     */
    data class QueryIntent(
        val year: Int = 0,      // 独立 token 的 1900-2100
        val season: Int = 0,    // 第二季/第2季/S02
        val code: String = "",  // 番号归一化串(如 miaa195);空=无可靠番号
    )

    private val INTENT_YEAR_PATTERN = Regex("(?<!\\d)(19\\d{2}|20\\d{2})(?!\\d)")
    private val INTENT_SEASON_CN = Regex("第\\s*([0-9]{1,2}|[一二两三四五六七八九十]{1,3})\\s*季")
    private val INTENT_SEASON_S = Regex("(?<![A-Za-z0-9])S([0-9]{1,2})(?!\\d)", RegexOption.IGNORE_CASE)
    private val INTENT_CODE_TOKEN = Regex("(?i)[a-z]{2,6}-?\\d{2,5}")

    /**
     * 查询意图缓存。
     *
     * <p>⚠️ 必须是并发容器。虽然 {@code SearchViewModel.onSearchResultEvent} 走
     * {@code ThreadMode.MAIN}(主线程),但本函数是**公开工具方法**,且
     * {@code SearchScreens} 在 Compose 组合期也会调它 —— 一旦将来有人把结果解析挪到
     * IO 线程(或从后台预计算),普通 HashMap 的并发 put 会损坏内部结构(取到 null、
     * 极端情况下死循环)。旁边的 [normalizeCache] 早已是 ConcurrentHashMap,
     * 同一文件里两个同性质缓存不该一个有锁一个没有。
     */
    private val intentCache = java.util.concurrent.ConcurrentHashMap<String, QueryIntent>()

    /** 解析查询意图(带缓存;纯字符串分析,无副作用) */
    fun parseIntent(keyword: String?): QueryIntent {
        val raw = keyword?.trim().orEmpty()
        if (raw.isEmpty()) return QueryIntent()
        intentCache[raw]?.let { return it }
        var year = 0
        var season = 0
        var code = ""
        // 番号:字母(2-6)+数字(2-5)组合;字母+年份数字("HD2024")不算番号 —— 年份由年份规则表达
        INTENT_CODE_TOKEN.find(raw)?.let { m ->
            val digits = m.value.takeLastWhile { ch -> ch.isDigit() }
            val asYear = digits.toIntOrNull() ?: 0
            if (!(digits.length == 4 && asYear in 1900..2100)) {
                code = Regex("[^0-9a-z]").replace(m.value.lowercase(java.util.Locale.ROOT), "")
            }
        }
        INTENT_YEAR_PATTERN.find(raw)?.let { year = it.value.toInt() }
        INTENT_SEASON_CN.find(raw)?.let { season = cnSeasonNumber(it.groupValues[1]) }
        if (season == 0) INTENT_SEASON_S.find(raw)?.let { season = it.groupValues[1].toInt() }
        val intent = QueryIntent(year, season, code)
        if (intentCache.size >= 64) intentCache.clear()
        intentCache[raw] = intent
        return intent
    }

    /**
     * 意图修饰分(加在 [relevanceScore] 之后的档内修饰,文档 §7.5/§10.3/§6.3):
     * - 年份/季数:一致 +1;**明确冲突 -2**(下沉);候选缺失 0 —— 降置信不判死
     * - 番号:候选标题包含查询番号 +2(强证据,§6.2);包含**另一个**可识别番号且不含查询番号 -2(§6.3 编号冲突);
     *   候选无可识别番号 0 —— 不视为冲突
     */
    fun intentModifier(intent: QueryIntent?, name: String?, candidateYear: Int): Int {
        if (intent == null) return 0
        val n = normalizeCached(name)
        if (n.isEmpty()) return 0
        var mod = 0
        if (intent.year > 0) {
            // 标题里的年份优先于源字段(标题自证更可靠);两者都没有视为缺失
            val titleYear = INTENT_YEAR_PATTERN.find(n)?.value?.toInt() ?: 0
            val y = if (titleYear > 0) titleYear else candidateYear
            when {
                y == 0 -> {}
                y == intent.year -> mod += 1
                else -> mod -= 2
            }
        }
        if (intent.season > 0) {
            val s = seasonOfName(n)
            when {
                s == 0 -> {}
                s == intent.season -> mod += 1
                else -> mod -= 2
            }
        }
        if (intent.code.isNotEmpty()) {
            if (n.contains(intent.code)) {
                mod += 2
            } else {
                val other = INTENT_CODE_TOKEN.findAll(n)
                    .map { m -> Regex("[^0-9a-z]").replace(m.value.lowercase(java.util.Locale.ROOT), "") }
                    .filter { it != intent.code }
                    .toList()
                if (other.isNotEmpty()) mod -= 2
            }
        }
        return mod
    }

    private fun seasonOfName(n: String): Int {
        INTENT_SEASON_CN.find(n)?.let { return cnSeasonNumber(it.groupValues[1]) }
        INTENT_SEASON_S.find(n)?.let { return it.groupValues[1].toInt() }
        return 0
    }

    /** 中文数字季数:一二两三…十/十一/二十三,全角与阿拉伯数字直转;解析不了返回 0 */
    private fun cnSeasonNumber(token: String): Int {
        val ascii = buildString {
            for (ch in token) append(if (ch in '０'..'９') ('0' + (ch - '０')) else ch)
        }
        ascii.toIntOrNull()?.let { return it }
        val digit = mapOf('一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        if (token == "十") return 10
        val shi = token.indexOf('十')
        if (shi >= 0) {
            val tens = if (shi == 0) 1 else digit[token[shi - 1]] ?: return 0
            val ones = if (shi == token.length - 1) 0 else digit[token.last()] ?: return 0
            return tens * 10 + ones
        }
        return if (token.length == 1) digit[token[0]] ?: 0 else 0
    }

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
    /**
     * 一次搜索允许使用的查询变体数上限。
     *
     * <p>每个变体都意味着"某个源原词没命中时要再问一次" —— 数百源规模下,变体数直接乘在请求量上。
     * 4 个是"覆盖率收益 / 请求量代价"的折中:再多就只是在给慢源加压。
     */
    private const val MAX_QUERY_VARIANTS = 4

    /** 年份:半角/全角括号内的 19xx/20xx,括号外的也剥(标题常写"片名 2024") */
    private val YEAR_PATTERN = Regex("[（(\\[【]?\\s*(?:19|20)\\d{2}\\s*[)\\]）】]?")

    /** 季/部/集数/版本后缀:这些在站方标题里位置不定,剥掉后按"核心片名"再问一次 */
    private val EDITION_PATTERN = Regex(
        "第\\s*[0-9一二三四五六七八九十]+\\s*[季部集]|Season\\s*\\d+|\\d+\\s*集全?|更新至\\s*\\d+\\s*集|" +
            "完结|全集|高清|标清|蓝光|HD|BD|TC|CD|版",
        RegexOption.IGNORE_CASE,
    )

    /** 折叠连续空白(剥完年份/后缀会留下空洞) */
    private val MULTI_SPACE_PATTERN = Regex("\\s+")

    /**
     * 搜索关键词的**查询变体**(原词在前),用于"某个源用原词没命中时再问一次"。
     *
     * <p>## 为什么要变体
     * 站方写片名没有统一规范:`功夫女足` / `功夫女足(2026)` / `功夫女足 HD` / `知否, 知否` /
     * 繁体 `夢華錄` —— 只用用户输入的原词去问,这些源会答"没有",表现为**明明有源却搜不到**。
     *
     * <p>## 变体集(按代价从低到高,总数封顶 [MAX_QUERY_VARIANTS])
     * ① 原词;② 去掉空白与连接符的紧凑形(番号最常差这个,如 `MIAA-195` → `MIAA195`);
     * ③ 去年份;④ 去年份 + 去季/集/版本后缀;⑤ 简繁互转(繁中用户搜简体片名、以及源站只存繁体的情况)。
     * 简繁转换靠 ICU,**任何异常都降级成"没有这个变体"** —— 它只是加分项,不能把搜索带崩。
     */
    /**
     * 标题的**核心片名**：归一化后剥去年份与季/集/版本后缀 —— 与 [queryVariants] 第④个变体
     * 用的是同一把刀，所以"搜索时认为同一部的两个写法"与"换源时认为同一部"不会各说各话
     * （2026-10-16 补：同名判据 [PlayTitleMatch] 要用它，另写一张尾缀表必然分叉）。
     */
    internal fun coreTitle(text: String?): String {
        val noYear = YEAR_PATTERN.replace(normalizeCached(text), "")
        return EDITION_PATTERN.replace(noYear, "").trim()
    }

    fun queryVariants(keyword: String?): List<String> {
        val raw = keyword?.trim().orEmpty()
        if (raw.isEmpty()) return emptyList()
        val variants = LinkedHashSet<String>()

        /** 到上限就静默丢弃:变体是"值钱的先要"(紧凑形与去年份能覆盖绝大多数漏搜) */
        fun offer(candidate: String?) {
            if (variants.size >= MAX_QUERY_VARIANTS) return
            val value = candidate?.trim().orEmpty()
            if (value.isNotEmpty()) variants.add(value)
        }

        offer(raw)
        offer(raw.filterNot { ch ->
            ch.isWhitespace() || ch == '-' || ch == '_' || ch == '.' ||
                ch == '－' || ch == '—' || ch == '–' || ch == '−'
        })
        val noYear = collapse(YEAR_PATTERN.replace(raw, " "))
        offer(noYear)
        offer(collapse(EDITION_PATTERN.replace(noYear, " ")))
        offer(s2t(raw))
        offer(t2s(raw))
        return variants.toList()
    }

    private fun collapse(text: String): String =
        text.replace(MULTI_SPACE_PATTERN, " ").trim()

    /** 简繁互转的候选 ID:各 ICU 版本命名不一,按序试,能真正转出结果的那个才算数 */
    private val TRANS_TO_SIMPLIFIED = arrayOf("Hant-Hans", "Traditional-Simplified", "HanSimp")
    private val TRANS_TO_TRADITIONAL = arrayOf("Hans-Hant", "Simplified-Traditional", "HanTrad")

    /** 自检用的探针词:**造得出实例但转不动的 ID 一律不用**(那样只会白占一个变体名额) */
    private const val PROBE_TRADITIONAL = "夢華錄"
    private const val PROBE_SIMPLIFIED = "梦华录"

    private fun workingTransliterator(
        ids: Array<String>,
        probeInput: String,
        probeExpected: String,
    ): android.icu.text.Transliterator? {
        for (id in ids) {
            val transliterator =
                runCatching { android.icu.text.Transliterator.getInstance(id) }.getOrNull() ?: continue
            val probed = runCatching { transliterator.transliterate(probeInput) }.getOrNull()
            if (probed != null && probed.contains(probeExpected)) return transliterator
        }
        return null
    }

    // ICU 的 Transliterator **不是线程安全的**(与 PinyinTransliterator 同一取舍),按线程各持一份。
    // JVM 单测环境没有 android.icu ⇒ 实例恒为 null,简繁变体静默缺席、其余变体照常(不许把搜索带崩)。
    private val toSimplified = ThreadLocal.withInitial {
        workingTransliterator(TRANS_TO_SIMPLIFIED, PROBE_TRADITIONAL, "梦")
    }
    private val toTraditional = ThreadLocal.withInitial {
        workingTransliterator(TRANS_TO_TRADITIONAL, PROBE_SIMPLIFIED, "夢")
    }

    /** 繁体→简体;无可用转换器 ⇒ null */
    private fun t2s(text: String): String? =
        toSimplified.get()?.let { runCatching { it.transliterate(text) }.getOrNull() }?.takeIf { it != text }

    /** 简体→繁体 */
    private fun s2t(text: String): String? =
        toTraditional.get()?.let { runCatching { it.transliterate(text) }.getOrNull() }?.takeIf { it != text }

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

    /** 归一化标题的对外只读入口(同名聚合的归并键用;内部走带缓存的 [normalizeCached]) */
    fun normalizedTitle(name: String?): String = normalizeCached(name)

    /** 归一化 = 全角转半角 → 删括注及其内容 → 删空白与标点 → 忽略大小写;主体文字之外的差异(如「第二季」)仍然区分 */
    internal fun normalize(text: String?): String {
        if (text == null) return ""
        // NFKC 兼容性规范化(2026-10-10 替代手写全角映射表):全角 ASCII/全角标点/圆圈数字/
        // 半角片假名+浊点(ｶﾞ→ガ)等兼容字符一步到位 —— 日文半角浊音不再匹配不上
        val nfkc = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC)
        val sb = StringBuilder(nfkc.length)
        for (ch in nfkc) {
            // 片假名→平假名折叠(U+30A1..30F6 固定偏移 0x60,含ヴ):查询与标题同侧折叠,
            // 「ゲーム」与「げーむ」类平片假名差异不再阻断匹配;长音符ー(U+30FC)保留,辅助形式另行剥离
            sb.append(
                when (ch.code) {
                    in 0x30A1..0x30F6 -> (ch.code - 0x60).toChar()
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
