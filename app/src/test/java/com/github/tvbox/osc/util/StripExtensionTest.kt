package com.github.tvbox.osc.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [stripExtension] 纯函数单测:批量导入的**源名去扩展名**规则。
 *
 * <p>为什么单独测它:批量导入时源名**完全由文件名决定**(用户不输入名字),
 * 取错名字会直接显示到订阅列表里 —— 这是用户唯一能看到的产出,必须有回归保护。
 *
 * <p>[nameFromUri] 依赖 `Context`/`Uri`(ContentResolver 查询 DISPLAY_NAME),
 * 无 Robolectric 环境下不可 JVM 测,故把可测部分抽成本函数。
 */
class StripExtensionTest {

    @Test
    fun `去掉常见订阅扩展名`() {
        assertEquals("config", stripExtension("config.json"))
        assertEquals("订阅源", stripExtension("订阅源.txt"))
        assertEquals("spider", stripExtension("spider.py"))
        assertEquals("m3u8", stripExtension("m3u8"))  // 无点 ⇒ 原样
    }

    @Test
    fun `多个点时只去掉最后一段`() {
        // 文件名本身就是"带点"的形态(站点常用),不能把中间那段也切掉
        assertEquals("my.tvbox.config", stripExtension("my.tvbox.config.json"))
        assertEquals("v1.2.3", stripExtension("v1.2.3.txt"))
    }

    @Test
    fun `无扩展名原样返回`() {
        assertEquals("config", stripExtension("config"))
        assertEquals("订阅源", stripExtension("订阅源"))
    }

    @Test
    fun `隐藏文件不当作有扩展名`() {
        // 点是首字符 ⇒ dot == 0 ⇒ 原样返回(不能被切空)
        assertEquals(".json", stripExtension(".json"))
        assertEquals(".hidden", stripExtension(".hidden"))
    }

    @Test
    fun `末尾是点时去掉点`() {
        // "abc." ⇒ dot 在末位、base="abc" 非空 ⇒ 去掉点得到 "abc"
        // (只有 base 为空才退回原名 —— 见下一个用例)
        assertEquals("abc", stripExtension("abc."))
    }

    @Test
    fun `去掉扩展名后为空则保留原名`() {
        // 形如 ".json" ⇒ dot==0 ⇒ 走"隐藏文件"分支原样返回
        assertEquals(".json", stripExtension(".json"))
    }

    @Test
    fun `空串与空白安全`() {
        assertEquals("", stripExtension(""))
        assertEquals("", stripExtension("   "))
        // 两侧空白被裁掉
        assertEquals("config", stripExtension("  config.json  "))
        assertEquals("config", stripExtension("  config  "))
    }
}
