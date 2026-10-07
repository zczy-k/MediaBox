package com.github.tvbox.osc.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [ratingBadgeText] 的源身份边界测试。
 *
 * <p>为什么这组用例重要:真机实测(诊断包 v1.0.24,js_douban 源)在详情页
 * "相关推荐"卡片角标上渲染出了「蝴蝶影视」—— 站点把**站名塞进 note 字段**,
 * 而老实现的兜底分支 `return n` 会把 note 原文当角标直接渲染。
 * 这类泄露最隐蔽:它不在源名列表里,伪装成"备注"这种看起来无害的字段。
 *
 * <p>因此本测试锁死的不只是"返回值",而是**产品硬边界**:
 * 解析不出评分时必须返回 null(不渲染),绝不能回显原文。
 */
class RatingBadgeLeakTest {

    @Test
    fun `note含站名时必须返回null而不是回显`() {
        // 真机抓到的真实数据:note 就是站名
        assertNull(ratingBadgeText("蝴蝶影视"))
        assertNull(ratingBadgeText("大米星球"))
        assertNull(ratingBadgeText("777午夜影院"))
        // 站名常带这类前后缀,一样不能漏
        assertNull(ratingBadgeText("🔞 蝴蝶全量独享专线"))
        assertNull(ratingBadgeText("某站·严控渠道"))
    }

    @Test
    fun `note含评分时只提取评分数字`() {
        assertEquals("8.5", ratingBadgeText("评分: 8.5"))
        assertEquals("8.5", ratingBadgeText("评分8.5"))
        assertEquals("7", ratingBadgeText("7分"))
        assertEquals("9.0", ratingBadgeText("9.0 分"))
    }

    @Test
    fun `评分与站名混排时只取评分不取站名`() {
        // 站点常见写法:评分与站名/备注挤在同一字段里
        assertEquals("8.5", ratingBadgeText("蝴蝶影视 评分:8.5"))
        assertEquals("7.2", ratingBadgeText("某站 7.2分"))
    }

    @Test
    fun `评分为0视为无评分不渲染`() {
        // 0 分不是有效评分,渲染出来会造成"这片子0分"的误解
        assertNull(ratingBadgeText("评分: 0"))
        assertNull(ratingBadgeText("0分"))
    }

    @Test
    fun `空值与空白返回null`() {
        assertNull(ratingBadgeText(null))
        assertNull(ratingBadgeText(""))
        assertNull(ratingBadgeText("   "))
    }

    @Test
    fun `普通集数备注不再展示但也不泄露`() {
        // 集数类备注在旧实现下会被渲染。它不含源身份,但行为已统一为不渲染,
        // 锁住这个决定,避免有人"顺手恢复"回显逻辑
        assertNull(ratingBadgeText("共30集,更新至12集"))
        assertNull(ratingBadgeText("更新至 20 集"))
    }

    @Test
    fun `任意非评分文本都不会被原样返回`() {
        // 兜底断言:任何非评分 note 都不得原样返回
        listOf("某源站", "AAA影视", "www.example.com", "内含0分", "？？？")
            .forEach { note ->
                val out = ratingBadgeText(note)
                assertNull("note=$note 泄露了原文: $out", out)
            }
    }
}
