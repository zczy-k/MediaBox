package com.github.tvbox.osc.bean

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁住 2026-10-09 修的 NPE:详情回包**没有 list 字段**时,`toAbsXml()` 必须返回空结果,
 * 而不是抛 `NullPointerException: ... ArrayList.iterator()` 再被上层吞成一个"无解释的空详情"。
 *
 * <p>真机事故:js_douban 的详情回包只有提示/元数据、没有 list ⇒ 旧代码在 for-each 处抛 NPE ⇒
 * `SourceResultParser` catch 后详情为空 ⇒ 页面停 Loading 等 120 源聚合搜索(实测白等 43 秒)。
 */
class AbsJsonTest {

    private val gson = Gson()

    @Test
    fun missingListField_yieldsEmptyInsteadOfThrowing() {
        val absJson = gson.fromJson("{\"code\":1,\"msg\":\"提示信息\"}", AbsJson::class.java)
        val xml = absJson.toAbsXml()
        assertNotNull(xml)
        assertNotNull(xml.movie)
        assertNotNull(xml.movie.videoList)
        assertTrue(xml.movie.videoList.isEmpty())
    }

    @Test
    fun emptyListField_yieldsEmptyInsteadOfThrowing() {
        // 边界:list 键存在但为空数组 —— 同样必须不抛
        val absJson = gson.fromJson("{\"code\":1,\"list\":[]}", AbsJson::class.java)
        val xml = absJson.toAbsXml()
        assertTrue(xml.movie.videoList.isEmpty())
    }

    @Test
    fun listPresent_mapsVideosAndKeepsPageInfo() {
        val body = """
            {"code":1,"page":2,"pagecount":209,"total":4166,"limit":"20",
             "list":[{"vod_id":71989,"vod_name":"咸鱼先生","vod_pic":"http://p/1.jpg",
                      "vod_play_from":"dbyun",
                      "vod_play_url":"第01集${'$'}http://v/1#第02集${'$'}http://v/2"}]}
        """.trimIndent()
        val absJson = gson.fromJson(body, AbsJson::class.java)
        val xml = absJson.toAbsXml()
        assertEquals(1, xml.movie.videoList.size)
        assertEquals(209, xml.movie.pagecount)
        assertEquals(4166, xml.movie.recordcount)
        val v = xml.movie.videoList[0]
        assertEquals("71989", v.id)
        assertEquals("咸鱼先生", v.name)
        // ⚠️ infoList 是"每条**线路**(play_from)一项",分集在 urls 字符串里,
        // 拆成 beanList 是后续 SourceHelper.absXml 的事(本方法不做)。
        // 此前断言写成 infoList.size == 2,是把"线路数"和"集数"混为一谈(CI 实跑抓到)。
        assertEquals(1, v.urlBean.infoList.size)
        assertEquals("dbyun", v.urlBean.infoList[0].flag)
        assertEquals("第01集\$http://v/1#第02集\$http://v/2", v.urlBean.infoList[0].urls)
    }
}
