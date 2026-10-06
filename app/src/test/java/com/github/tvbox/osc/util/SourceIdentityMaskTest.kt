package com.github.tvbox.osc.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 源身份脱敏单测。
 *
 * <p>这条口径错了两头都出事:漏抹 ⇒ 用户看到接口地址/站名(隐藏源身份的目标就破了);
 * 抹太狠 ⇒ 连"超时/解析失败"这类可判断的信息也没了,用户只剩一个哑弹窗。
 * 所以锁住"抹可定位标识、留错误语义"这条线。
 */
class SourceIdentityMaskTest {

    @Test
    fun mask_nullOrBlank_returnsEmpty() {
        assertEquals("", SourceIdentityMask.mask(null))
        assertEquals("", SourceIdentityMask.mask(""))
        assertEquals("", SourceIdentityMask.mask("    "))
    }

    @Test
    fun mask_removesUrlAndKeepsErrorSemantics() {
        val out = SourceIdentityMask.mask("解析失败 >>> https://api.example.com/v1/play?token=abc123")
        assertFalse(out.contains("example.com"))
        assertFalse(out.contains("token"))
        assertFalse(out.contains("abc123"))
        assertTrue(out.contains("解析失败"))
    }

    @Test
    fun mask_removesBareDomain() {
        val out = SourceIdentityMask.mask("connect timed out: lytvs.top")
        assertFalse(out.contains("lytvs.top"))
        assertTrue(out.contains("timed out"))
    }

    @Test
    fun mask_removesDeepSubdomain() {
        val out = SourceIdentityMask.mask("failed https://a.b.c.example.cn/x")
        assertFalse(out.contains("example.cn"))
    }

    @Test
    fun mask_keepsIpAndPlainText() {
        // IP 不是域名(没有 TLD),不该被抹 —— 它对用户排查网络仍有意义
        val out = SourceIdentityMask.mask("HTTP 404 from 192.168.1.10")
        assertTrue(out.contains("192.168.1.10"))
        assertTrue(out.contains("404"))
    }

    @Test
    fun mask_keepsChineseMessage() {
        assertEquals("当前源解析失败", SourceIdentityMask.mask("当前源解析失败"))
    }

    @Test
    fun mask_isIdempotent() {
        val once = SourceIdentityMask.mask("err https://x.com/a")
        assertEquals(once, SourceIdentityMask.mask(once))
    }
}
