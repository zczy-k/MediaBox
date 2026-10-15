package com.github.tvbox.osc.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 订阅地址 → 桶身份。测的是"同一个仓的常见写法差异必须落到同一个桶"——
 * 判错方向的后果是用户换种写法粘贴地址就冷启动重攒账本(此前的真实行为)。
 */
class SubscriptionBucketTest {

    @Test
    fun `写法差异不影响身份`() {
        val base = SubscriptionBucket.normalize("http://cat.com/config.json")
        assertEquals(base, SubscriptionBucket.normalize("http://cat.com/config.json/"))
        assertEquals(base, SubscriptionBucket.normalize(" https://cat.com/config.json "))
        assertEquals(base, SubscriptionBucket.normalize("HTTP://CAT.com/config.json"))
        assertEquals(base, SubscriptionBucket.normalize("http://cat.com/config.json#备注"))
        assertEquals("cat.com/config.json", base)
    }

    @Test
    fun `不同仓绝不合并`() {
        assertNotEquals(
            SubscriptionBucket.normalize("http://a.com/config.json"),
            SubscriptionBucket.normalize("http://b.com/config.json"),
        )
        // 路径不同 = 不同配置;query 也保留(cache-buster 与真正的参数都原样算身份,宁可少合并)
        assertNotEquals(
            SubscriptionBucket.normalize("http://a.com/config.json"),
            SubscriptionBucket.normalize("http://a.com/other.json"),
        )
        assertNotEquals(
            SubscriptionBucket.normalize("http://a.com/config.json?t=1"),
            SubscriptionBucket.normalize("http://a.com/config.json?t=2"),
        )
    }

    @Test
    fun `path 大小写敏感,只有 host 小写`() {
        assertEquals("cat.com/Config.JSON", SubscriptionBucket.normalize("http://CAT.com/Config.JSON"))
        assertNotEquals(
            SubscriptionBucket.normalize("http://cat.com/a.json"),
            SubscriptionBucket.normalize("http://cat.com/A.json"),
        )
    }

    @Test
    fun `解析不出 host 的串保持原身份`() {
        // base64 配置整串当地址:URI 里没有 host,若强行改写入参会让老桶凭空换名字
        val blob = "eyJ1c2VyQWdlbnQiOiJDVFZCLTEuMCIsInNwaWRlciI6WyJjspA9="
        assertEquals(blob, SubscriptionBucket.normalize(blob))
    }

    @Test
    fun `空地址没有身份`() {
        assertEquals("", SubscriptionBucket.normalize(""))
        assertEquals("", SubscriptionBucket.normalize("   "))
        assertEquals("", SubscriptionBucket.normalize(null))
        assertNull(SubscriptionBucket.idOf(""))
        assertNull(SubscriptionBucket.idOf(null))
    }

    @Test
    fun `同一仓的不同写法算出同一个桶 id`() {
        assertEquals(
            SubscriptionBucket.idOf("http://cat.com/config.json"),
            SubscriptionBucket.idOf("https://cat.com/config.json/"),
        )
    }

    /**
     * 身份里去掉了协议 ⇒ 规范化后的 id 与"地址原文的 MD5"**必然不同**,老桶全都需要搬一次家。
     * 这是有意为之(不搬就等于给每个升级用户清一次账),所以这里断言的是"一定会触发搬迁",
     * 而不是"干净地址不用搬"。搬迁幂等:搬完删老键,第二次调用不再动手。
     */
    @Test
    fun `老桶名与新桶名不同,所以升级一定会搬迁一次`() {
        val url = "http://cat.com/config.json"
        assertNotEquals(MD5.string2MD5(url), SubscriptionBucket.idOf(url))
        // 脏写法与干净写法共用一个 id ⇒ 老键只可能来自其中一种,搬过去不会撞车
        assertEquals(SubscriptionBucket.idOf(url), SubscriptionBucket.idOf("https://cat.com/config.json/"))
    }
}
