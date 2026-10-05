package com.github.tvbox.osc.ui.page

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.data.RoomDataManger
import com.github.tvbox.osc.event.RefreshEvent
import com.github.tvbox.osc.util.EpisodeTotals
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.HistoryHelper
import com.github.tvbox.osc.util.HistoryMerge
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.PlaybackProgress
import com.github.tvbox.osc.util.TrackMemory
import com.github.tvbox.osc.util.WatchProgressStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

class HistoryViewModel : ViewModel() {
    val loading = MutableStateFlow(true)
    val items = MutableStateFlow<List<VodInfo>>(emptyList())
    val episodeTotals = MutableStateFlow<Map<String, Int>>(emptyMap())
    val playedPercents = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** 无痕:列表与卡片整体不显示(页面仍保留"清空历史"这个主动操作) */
    val incognito = MutableStateFlow(HistoryHelper.isIncognito())

    init {
        EventBus.getDefault().register(this)
        refresh()
        // 配置就绪后再刷一次:换订阅瞬间按 cid 读库是对的,但站名与可用性要等新配置解析完才算得准
        viewModelScope.launch {
            AppBootstrap.state.collect { boot ->
                if (boot is AppBootstrap.Boot.Ready) refresh()
            }
        }
    }

    override fun onCleared() {
        EventBus.getDefault().unregister(this)
    }

    val scrollSignal = MutableStateFlow(0)

    val placementAnim = MutableStateFlow(false)

    fun refresh(scrollToTop: Boolean = false) {
        // 无痕:不读库也不显示卡片(历史合并的去重删库同样跳过 —— 都不展示了,没必要动库)
        if (HistoryHelper.isIncognito()) {
            incognito.value = true
            loading.value = false
            items.value = emptyList()
            episodeTotals.value = emptyMap()
            playedPercents.value = emptyMap()
            return
        }
        incognito.value = false
        if (items.value.isEmpty()) loading.value = true
        if (scrollToTop) placementAnim.value = false
        viewModelScope.launch(Dispatchers.IO) {
            val limit = HistoryHelper.getHisNum(KV.get(HawkConfig.HISTORY_NUM, 0))
            val all = RoomDataManger.getAllVodRecord(limit)
            if (HistoryMerge.isEnabled()) {
                // 历史合并:同一部剧只保留最新一条,被合并掉的旧记录直接清库(上游"历史合并"语义,见 HistoryMerge)
                val (kept, dropped) = HistoryMerge.dedupe(all) { it.name }
                dropped.forEach { RoomDataManger.deleteVodRecord(it.sourceKey, it) }
                items.value = kept
            } else {
                items.value = all
            }
            episodeTotals.value = EpisodeTotals.snapshot()
            playedPercents.value = PlaybackProgress.snapshot()
            resolveSourceNames()
            loading.value = false
            if (scrollToTop) scrollSignal.value++
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onRefreshEvent(event: RefreshEvent) {
        // 带 obj(Boolean.FALSE)= 起播点刷新:只重读快照,不把用户手里的列表滚回顶部
        if (event.type == RefreshEvent.TYPE_HISTORY_REFRESH) refresh(scrollToTop = event.obj !is Boolean)
        // 历史按当前订阅隔离:换了订阅必须重读库,只重解析站名会继续列着上一个订阅的记录
        else if (event.type == RefreshEvent.TYPE_API_URL_CHANGE) refresh()
    }

    private var resolveJob: Job? = null

    fun resolveSourceNames() {
        resolveJob?.cancel()
        resolveJob = viewModelScope.launch(Dispatchers.IO) {
            val list = items.value
            if (list.isEmpty()) return@launch
            // 配置未就绪时 getSource 全为空,此刻把站点标成"当前源不可用"是误判,等 Ready 那次刷新再算
            if (AppBootstrap.state.value !is AppBootstrap.Boot.Ready) return@launch
            val cache = KV.get(HawkConfig.SOURCE_NAME_CACHE, HashMap<String, String>())
            var cacheChanged = false
            var listChanged = false
            list.forEach { info ->
                val key = info.sourceKey
                val bean = if (key.isNullOrEmpty()) null else ApiConfig.get().getSource(key)
                val resolved = if (key.isNullOrEmpty()) {
                    ""
                } else {
                    val current = bean?.name
                    if (!current.isNullOrEmpty()) {
                        if (cache[key] != current) {
                            cache[key] = current
                            cacheChanged = true
                        }
                        current
                    } else {
                        cache[key] ?: key
                    }
                }
                if (info.sourceName != resolved) {
                    info.sourceName = resolved
                    listChanged = true
                }
                // 站名快照会跨订阅残留,是否可用必须按当前订阅现判
                val unavailable = !key.isNullOrEmpty() && bean == null
                if (info.sourceUnavailable != unavailable) {
                    info.sourceUnavailable = unavailable
                    listChanged = true
                }
            }
            if (cacheChanged) KV.put(HawkConfig.SOURCE_NAME_CACHE, cache)
            if (listChanged) items.value = list.toList()
        }
    }

    fun deleteSelected(list: List<VodInfo>) {
        if (list.isEmpty()) return
        placementAnim.value = true
        viewModelScope.launch(Dispatchers.IO) {
            list.forEach { item ->
                RoomDataManger.deleteVodRecord(item.sourceKey, item)
                // 记录删了,该片的进度痕迹与轨道/字幕记忆一并清掉,免得留下访问不到的孤儿键
                WatchProgressStore.clearOwner(WatchProgressStore.ownerOf(item))
                TrackMemory.delete(TrackMemory.contentKey(item.sourceKey, item.id))
            }
            refresh()
        }
    }

    fun deleteAll() {
        placementAnim.value = false
        viewModelScope.launch(Dispatchers.IO) {
            RoomDataManger.deleteVodRecordAll()
            WatchProgressStore.clearAll()
            TrackMemory.deleteAll()
            refresh()
        }
    }

    companion object {
        fun key(item: VodInfo): String = item.sourceKey + "|" + item.id
    }
}
