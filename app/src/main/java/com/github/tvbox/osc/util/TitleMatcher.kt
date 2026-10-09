package com.github.tvbox.osc.util

/**
 * 影片标题归一化匹配(纯函数,无 Android 依赖,可 JVM 单测)。
 *
 * <p>## 为什么不能用全等
 * 聚合搜索的候选过滤原来是 `name.trim() == searchTitle`:同一部片在不同站点常写作
 * 「功夫女足 / 功夫女足(2026)前后缀之外的差异不在此列 / 全角空格 / 《功夫女足》/ 老婆們的功夫」之外的
 * 标点变体 —— 全等会把这些**明明是同一部片**的候选全部漏掉,表现为"这部片明明有源却搜不到"。
 * 本匹配把两边都归一化后再比较:全角→半角、去空白与常见标点(书名号/引号/中英文冒号等)、小写。
 *
 * <p>## 刻意**保守**的字符集
 * 只删"几乎不会参与片名区分"的字符:空白、引号/书名号/括号、中英文冒号/逗号/句号/感叹/问号、
 * 连接符(-—_~…·)。像 `%`、`&`、`+` 这类可能真正参与片名的字符(《3%》《M&Ms》)**保留**,
 * 宁可漏匹配也不误合并两部不同的片 —— 误合并会把 A 片的源拿去播 B 片,那是播放事故。
 *
 * <p>归一化后为空(如标题只剩标点)一律不算同片。
 */
object TitleMatcher {

    /** 归一化:全角→半角、去空白与常见标点、小写 */
    fun normalize(title: String?): String {
        if (title.isNullOrEmpty()) return ""
        val sb = StringBuilder(title.length)
        for (raw in title) {
            // 全角 ASCII(!\uFF5E)折回半角;全角空格(\u3000)按空白处理
            val c = if (raw.code in 0xFF01..0xFF5E) (raw.code - 0xFEE0).toChar() else raw
            if (c.isWhitespace()) continue
            if (isIgnorablePunct(c)) continue
            sb.append(c.lowercaseChar())
        }
        return sb.toString()
    }

    /** 两部标题是否指向同一部片(归一化相等;任一为空/只剩标点 ⇒ 不算) */
    fun isSameTitle(a: String?, b: String?): Boolean {
        val na = normalize(a)
        if (na.isEmpty()) return false
        return na == normalize(b)
    }

    private fun isIgnorablePunct(c: Char): Boolean = when (c) {
        // 引号/书名号/括号类
        '"', '\'', '“', '”', '‘', '’', '《', '》', '〈', '〉', '「', '」', '『', '』',
        '(', ')', '[', ']', '【', '】', '（', '）',
        // 停顿/收尾类(中英文)
        ':', '：', ',', '，', '、', '.', '。', '!', '！', '?', '？', ';', '；',
        // 连接/装饰类
        '-', '—', '–', '_', '~', '…', '·', '•', '※', '|', '/',
        -> true
        else -> false
    }
}
