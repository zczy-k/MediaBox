package com.github.tvbox.osc.ui.activity

/**
 * 数字 HTML 实体的兜底解码。
 *
 * <p>## 为什么需要它
 *
 * <p>`android.text.Html.fromHtml` 在 `FROM_HTML_MODE_LEGACY` 下**没有解掉全部数字实体**。
 * 真机实测(js_douban 源的 `挽救计划`)详情页简介里残留着:
 * ```
 * 【&#128293;官方交流群:https://t.me/tvshare23】━━━…
 * &lt;br&gt;
 * ```
 * `&#128293;`(火 emoji)就卡在推广块的方括号里,导致按方括号切推广块的正则**匹配不到**
 * —— 实体不解干净,广告过滤就漏。这不是理论风险,是已复现的问题。
 *
 * <p>## 边界(刻意保守)
 *
 * <p>* **只解数字实体**(`&#123;` / `&#x1F;`)。具名实体(`&nbsp;` `&amp;`)交给
 *   `fromHtml`,这里解不了就**原样保留** —— 静默吃掉会让描述缺字,比多留一个 `&` 更糟。
 * * 实体片段超过 10 字符就不认,避免把正常文本里的 `&` 拖进实体解析。
 * * 不处理无分号的实体(HTML5 允许无分号,但在描述文本里误判风险大于收益)。
 *
 * <p>单独成类而不是塞进 [DetailSectionsKt] 的私有函数:它不依赖 Android SDK,
 * 可以被单测直接覆盖 —— 而"实体没解干净导致广告漏删"正是需要回归防护的地方。
 */
internal object HtmlEntityDecode {

    /** 实体片段长度上限(含 `&` 与 `;`)。超过就不当实体处理。 */
    private const val MAX_ENTITY_LEN = 10

    @JvmStatic
    fun decode(text: String): String {
        if (!text.contains('&')) return text
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '&') {
                sb.append(c)
                i++
                continue
            }
            val semi = text.indexOf(';', i + 1)
            if (semi < 0 || semi - i > MAX_ENTITY_LEN) {
                sb.append(c)
                i++
                continue
            }
            val body = text.substring(i + 1, semi)
            val code = when {
                body.startsWith("#x", ignoreCase = true) -> body.drop(2).toIntOrNull(16)
                body.startsWith("#") -> body.drop(1).toIntOrNull()
                else -> null
            }
            if (code != null && code in 1..0x10FFFF) {
                sb.appendCodePoint(code)
                i = semi + 1
            } else {
                // 具名实体或非法值:原样保留 '&',不吞字符
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}