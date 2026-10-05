package com.github.tvbox.osc.sourcedata

import androidx.lifecycle.MutableLiveData
import com.github.tvbox.osc.bean.AbsXml
import com.github.tvbox.osc.event.RefreshEvent
import com.google.gson.Gson
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解析结果的分投口径(改动这里会直接决定"搜索能出结果""详情能起播"):
 * 搜索通道 → EventBus(面板自己收),详情通道 → 过 push/迅雷后处理再投,其余通道 → 直接 postValue。
 */
class SourceResultParserRoutingTest {

    private val gson = Gson()

    /** 记录投递而不落到 Android 主线程(单测里没有 Looper) */
    private class RecordingChannel : MutableLiveData<AbsXml>() {
        val posted = ArrayList<AbsXml?>()
        override fun postValue(value: AbsXml?) {
            posted.add(value)
        }
    }

    /** 必须是非 private 类 + public 方法:EventBus 走反射分发,私有类会拿不到访问权 */
    class EventRecorder {
        val events = ArrayList<RefreshEvent>()

        @Subscribe
        fun onRefresh(event: RefreshEvent) {
            events.add(event)
        }
    }

    private val payload =
        """{"list":[{"vod_id":"1","vod_name":"测试片","vod_play_from":"线路甲","vod_play_url":"第1集${'$'}http://a.example/1.m3u8"}]}"""

    private fun newParser(search: MutableLiveData<AbsXml>, detail: MutableLiveData<AbsXml>) =
        SourceResultParser(gson, search, detail, PushDetailResolver(gson, detail))

    @Test
    fun searchChannelGoesToEventBusNotChannel() {
        val search = RecordingChannel()
        val detail = RecordingChannel()
        val recorder = EventRecorder()
        EventBus.getDefault().register(recorder)
        try {
            newParser(search, detail).json(search, payload, "src", "token")
        } finally {
            EventBus.getDefault().unregister(recorder)
        }
        assertTrue("搜索通道不应直接投递(面板从 EventBus 收)", search.posted.isEmpty())
        assertTrue(
            "应发出搜索结果的 RefreshEvent",
            recorder.events.any { it.type == RefreshEvent.TYPE_SEARCH_RESULT && it.obj is AbsXml },
        )
    }

    @Test
    fun detailChannelReceivesParsedDetail() {
        val search = RecordingChannel()
        val detail = RecordingChannel()
        newParser(search, detail).json(detail, payload, "src")
        assertEquals(1, detail.posted.size)
        val data = detail.posted[0]!!
        assertEquals(1, data.movie.videoList.size)
        assertEquals("测试片", data.movie.videoList[0].name)
        assertEquals("src", data.sourceKey)
    }

    @Test
    fun plainChannelReceivesValue() {
        val search = RecordingChannel()
        val detail = RecordingChannel()
        val list = RecordingChannel()
        newParser(search, detail).json(list, payload, "src")
        assertEquals(1, list.posted.size)
        assertEquals("src", list.posted[0]!!.sourceKey)
    }
}
