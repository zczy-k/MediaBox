package com.github.tvbox.osc.util

import java.util.Locale

/**
 * 拼音匹配的**纯逻辑**(无 Android 依赖,可 JVM 单测)。
 *
 * <p>## 为什么拼音必须配"本地索引"才有用
 * 关键约束:源站的搜索接口收的是**字面关键词**。用户敲 `qjyf` 时,把它原样发给源站
 * 一定搜不到 —— 源站库里没有哪个片名是 `qjyf`。所以拼音搜索**不能**只是"多一种匹配规则",
 * 它必须先把拼音**解析成中文片名**,再拿那个中文片名去搜(见 [TitleIndexMemory])。
 *
 * <p>本对象只负责"给定一个片名的拼音、和一个拼音输入,是否匹配"这一层判定;
 * 汉字→拼音的转换在 [PinyinTransliterator](依赖平台 ICU),两者刻意分开,
 * 让这一层能被单测完全覆盖。
 *
 * <p>## 匹配口径(与中文匹配同构,便于复用既有排序权重)
 * 全拼优先于首字母、前缀优先于包含:
 * <ol>
 *   <li>全拼全等(5)/ 前缀(4)/ 包含(3)—— 与中文的 `全等/前缀/包含` 同档;</li>
 *   <li>首字母全等(2)/ 前缀(2)/ 包含(1)—— 首字母信息量远低于全拼
 *       (`hy` 能命中几十个片名),所以整体压到中文"包含"之下。</li>
 * </ol>
 *
 * <p>## 为什么首字母全等只给 2 分
 * 首字母的碰撞率高得惊人:一部剧叫《汉月》,拼音 `hanyue`、首字母 `hy`;
 * 另一部《红颜》也是 `hy`。若给首字母全等 5 分,输入 `hy` 会让《红颜》排到
 * 《汉月》前面,而用户想要哪一个完全无法从输入判断 —— 此时**应该并列**,
 * 让后续的中文相关度与到达序去决定。压低到 2 分正是为了让它们同分。
 */
object PinyinQuery {

    /** 拼音音节里允许出现的字符(去声调后的 ASCII 字母) */
    private val NON_ALNUM = Regex("[^a-z0-9]")

    /**
     * 输入是否"看起来是拼音":全是 ASCII 字母/数字,且**不含任何汉字**。
     *
     * <p>用于决定"要不要走拼音解析这条路"。判据刻意保守:一旦含 CJK 就按中文处理,
     * 不做拼音猜测 —— 否则搜「三体」时会被当成某个拼音去索引里乱撞。
     */
    fun looksLikePinyin(query: String?): Boolean {
        val q = query?.trim().orEmpty()
        if (q.isEmpty()) return false
        // 至少要有一个字母,纯数字(如番号 "12345")不当拼音
        var hasLetter = false
        for (c in q) {
            when {
                c in 'a'..'z' || c in 'A'..'Z' -> hasLetter = true
                c in '0'..'9' -> {}
                c == ' ' || c == '-' || c == '_' || c == '\'' -> {}
                else -> return false   // 含 CJK / 标点 / 其它 ⇒ 不是拼音输入
            }
        }
        return hasLetter
    }

    /** 归一化查询:小写、去所有非字母数字(空格/连字符/撇号都不参与比较) */
    fun normalize(raw: String?): String =
        NON_ALNUM.replace(raw?.lowercase(Locale.ROOT).orEmpty(), "")

    /**
     * 把"音节串"压成**全拼**。
     *
     * <p>输入是 [PinyinTransliterator] 产出的空格分隔音节(如 `"han yue"`),
     * 输出 `"hanyue"`。同时容忍输入里本来就带连字符或已压紧的形式。
     */
    fun compact(pinyin: String?): String = normalize(pinyin)

    /**
     * 取**首字母串**:`"han yue"` → `"hy"`。
     *
     * <p>按音节边界取首字母,而不是取整串的首字母 —— 后者对多字片名没有意义。
     * 音节边界用空白、连字符、下划线**或撇号**;若输入已压紧(无分隔),则退化为
     * "整串首字母",此时调用方本就不该指望首字母匹配。
     *
     * <p>⚠️ 撇号必须算分隔符:ICU 给 `xi'an`(西安)这类音节加隔音撇号,
     * 而 [PinyinTransliterator.normalizeSyllables] 会把撇号折成空格 —— 两处口径必须一致,
     * 否则"转换侧产出 `xi an`、匹配侧直接收到 `xi'an`"两种输入会得出不同首字母
     * (前者 `xa`、后者 `x`),同一部片在两条路径上匹配结果不同。
     * 这是 CI 抓到的:单测期望 `xa` 实得 `x`。
     */
    fun initials(pinyin: String?): String {
        val raw = pinyin?.lowercase(Locale.ROOT)?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        val parts = raw.split(' ', '-', '_', '\'').filter { it.isNotEmpty() }
        if (parts.size <= 1) {
            // 无音节分隔:无法可靠切分,返回整串首字母(调用方应避免依赖)
            val only = NON_ALNUM.replace(raw, "")
            return if (only.isEmpty()) "" else only.first().toString()
        }
        val sb = StringBuilder(parts.size)
        for (p in parts) {
            val cleaned = NON_ALNUM.replace(p, "")
            if (cleaned.isNotEmpty()) sb.append(cleaned[0])
        }
        return sb.toString()
    }

    /**
     * 拼音匹配打分:越高越贴合。0 = 不匹配。
     *
     * <p>档位与中文匹配([SearchSettings.relevanceScore])刻意保持同构,
     * 这样两边的分数可以在同一张表里比较。
     */
    fun score(pinyin: String?, query: String?): Int {
        val full = compact(pinyin)
        val q = normalize(query)
        if (full.isEmpty() || q.isEmpty()) return 0
        // 全拼:全等 / 前缀 / 包含
        if (full == q) return 5
        if (full.startsWith(q)) return 4
        if (full.contains(q)) return 3
        // 首字母:全等与前缀同档(信息量相同),包含再低一档
        val ini = initials(pinyin)
        if (ini.isNotEmpty()) {
            if (ini == q) return 2
            if (ini.startsWith(q)) return 2
            if (ini.contains(q)) return 1
        }
        return 0
    }

    fun matches(pinyin: String?, query: String?): Boolean = score(pinyin, query) > 0
}
