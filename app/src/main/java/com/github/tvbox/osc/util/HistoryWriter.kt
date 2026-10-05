package com.github.tvbox.osc.util

import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.data.RoomDataManger
import com.github.tvbox.osc.event.RefreshEvent
import org.greenrobot.eventbus.EventBus
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 观看历史落库通道:整剧 JSON 序列化 + SQLite 写放到独立线程,不占用起播/切集那一帧。
 *
 * 单线程串行保证"后写先到"不会发生;不随页面生命周期取消 —— 退出页面时的最后一次更新不能丢。
 */
object HistoryWriter {

    private val writer: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "vod-history-writer") }
    }

    fun write(sourceKey: String, info: VodInfo) {
        writer.execute { writeNow(sourceKey, info) }
    }

    private fun writeNow(sourceKey: String, info: VodInfo) {
        try {
            RoomDataManger.insertVodRecord(sourceKey, info)
        } catch (th: Throwable) {
            // 序列化与主线程持有同一份 VodInfo:并发改写可能抛异常,失败要留日志而不是静默丢历史
            LOG.e("HistoryWriter", "echo-history insert failed: " + th, th)
            return
        }
        EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_HISTORY_REFRESH))
    }
}
