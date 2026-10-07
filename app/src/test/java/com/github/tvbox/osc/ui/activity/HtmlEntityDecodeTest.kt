package com.github.tvbox.osc.ui.activity

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `decodeHtmlEntities` 的实体解码测试。
 *
 * <p>为什么单独测它:`cleanVodDescription` 整体依赖 `android.text.Html`(unit test 下
 * 不可用),但**实体解码是纯字符串逻辑**,而且是真机上实际出过问题的地方 ——
 * `Html.fromHtml` 在 `FROM_HTML_MODE_LEGACY` 下没解掉 `&#128293;` 与 `&lt;br&gt;`,
 * 导致推广块正则匹配不上、推广语留在界面上。所以把这段拆出来单独锁死。
 *
 * <p>⚠️ 若将来把 `decodeHtmlEntities` 改成非 internal,这个测试文件要跟着改包名。
 */
class HtmlEntityDecodeTest {

    @Test
    fun `十进制数字实体要解码`() {
        // 真机样本:火 emoji 的数字实体,fromHtml 没解掉,卡在【】里导致推广块漏删
        assertEquals("🔥", HtmlEntityDecode.decode("&#128293;"))
        assertEquals("A", HtmlEntityDecode.decode("&#65;"))
    }

    @Test
    fun `十六进制数字实体要解码`() {
        assertEquals("🔥", HtmlEntityDecode.decode("&#x1F525;"))
        assertEquals("A", HtmlEntityDecode.decode("&#x41;"))
        assertEquals("🔥", HtmlEntityDecode.decode("&#X1F525;"))
    }

    @Test
    fun `保留未知的具名实体`() {
        // 具名实体交给 fromHtml 解;这里解不了就必须**原样保留**而不是吃掉,
        // 否则描述里的正常文本会被静默删字
        assertEquals("&nbsp;", HtmlEntityDecode.decode("&nbsp;"))
        assertEquals("&amp;", HtmlEntityDecode.decode("&amp;"))
    }

    @Test
    fun `无分号或超长的一律原样保留`() {
        assertEquals("a & b", HtmlEntityDecode.decode("a & b"))
        assertEquals("100%&nbsp", HtmlEntityDecode.decode("100%&nbsp"))
        // 超过 10 字符的片段不能被当成实体吃掉
        assertEquals("&#1234567890123;", HtmlEntityDecode.decode("&#1234567890123;"))
    }

    @Test
    fun `纯文本不受影响`() {
        val s = "挽救计划：太阳正在被吃掉，绝望吞噬地球。"
        assertEquals(s, HtmlEntityDecode.decode(s))
    }

    @Test
    fun `混合文本只解实体不动其它字符`() {
        assertEquals(
            "【🔥官方交流群】正文",
            HtmlEntityDecode.decode("【&#128293;官方交流群】正文"),
        )
    }

    @Test
    fun `不含实体时快速返回`() {
        val s = "no entity here"
        assertEquals(s, HtmlEntityDecode.decode(s))
        assertEquals("", HtmlEntityDecode.decode(""))
    }
}