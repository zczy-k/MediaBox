package com.github.tvbox.osc.ui.page

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.base.App
import com.github.tvbox.osc.util.ApiLineSignal
import com.github.tvbox.osc.util.BootGuard
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.HistoryHelper
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.LanguageManager
import com.github.tvbox.osc.util.removeLocalCopy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

data class SubscribeSource(val name: String, val url: String)

/**
 * 待二次确认的切源请求。带 `vod` 是必需的:列表在 AnimatedContent 里渲染,过渡期内外两份内容
 * 同时在组合中,读外层 `isVod` 会把正在退场的那份按错的模式切源。
 */
data class PendingSwitch(val item: SubscribeSource, val vod: Boolean)

enum class ConfigMode { Vod, Live }

internal fun parseSubscribe(value: String): SubscribeSource {
    val index = value.indexOf(SUBSCRIBE_SPLIT)
    return if (index < 0) {
        SubscribeSource(value.trim(), value.trim())
    } else {
        SubscribeSource(
            value.substring(0, index).trim(),
            value.substring(index + SUBSCRIBE_SPLIT.length),
        )
    }
}

internal const val SUBSCRIBE_SPLIT = "\t"

class ConfigManageViewModel : ViewModel() {

    val vodItems = MutableStateFlow(loadSubscribes(ConfigMode.Vod))
    val liveItems = MutableStateFlow(loadSubscribes(ConfigMode.Live))
    val activeUrl = MutableStateFlow(KV.get(HawkConfig.API_URL, ""))
    val liveActiveUrl = MutableStateFlow(KV.get(HawkConfig.LIVE_API_URL, ""))
    val liveFollow = MutableStateFlow(ApiConfig.isLiveFollowVod())
    val disabledUrls = MutableStateFlow(BootGuard.disabledSources().toSet())
    val selected = MutableStateFlow(emptySet<String>())
    val manageMode = MutableStateFlow(false)
    val editTarget = MutableStateFlow<SubscribeSource?>(null)
    val pendingSwitch = MutableStateFlow<PendingSwitch?>(null)
    val toastEvent = MutableStateFlow<String?>(null)
    /** 即发即走:不随页面退出(VM cleared)取消,对齐旧 executor 语义 */
    private val copyCleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        viewModelScope.launch {
            ApiLineSignal.version.collect { refreshActiveSnapshot() }
        }
        viewModelScope.launch {
            // 收藏跨订阅打开也会切订阅(不经过本页):配置就绪后再对一次 KV,
            // 否则"使用中"标记与 switchToVod 的去重判断会停在旧值(表现为点某条源没反应)
            AppBootstrap.state.collect { boot ->
                if (boot is AppBootstrap.Boot.Ready) refreshActiveSnapshot()
            }
        }
    }

    private fun str(resId: Int, vararg args: Any): String {
        val app = App.getInstance() ?: return ""
        return LanguageManager.localized(app).getString(resId, *args)
    }

    fun clearToast() {
        toastEvent.value = null
    }

    fun cancelPendingSwitch() {
        pendingSwitch.value = null
    }

    /**
     * 多仓地址改写不产生 Compose 状态变化 ⇒ 当前态不会自更新,要退出重进才正确;
     * 刷新只走一条:改写点发的 [ApiLineSignal],不反推加载完成态、不挂 ON_RESUME。
     * 只重读"当前态"而**不**重读订阅列表 —— 列表增删改都同步写 KV,重读只会与勾选集错位。
     */
    private fun refreshActiveSnapshot() {
        activeUrl.value = KV.get(HawkConfig.API_URL, "")
        liveActiveUrl.value = KV.get(HawkConfig.LIVE_API_URL, "")
        liveFollow.value = ApiConfig.isLiveFollowVod()
    }

    /** 换模式清编辑态(repoSheetOpen 由 UI 自关) */
    fun onModeChanged() {
        manageMode.value = false
        selected.value = emptySet()
        editTarget.value = null
    }

    fun exitManageMode() {
        onModeChanged()
    }

    fun toggleSelected(value: String) {
        val cur = selected.value
        setSelected(if (value in cur) cur - value else cur + value)
    }

    fun longPressSelect(value: String) {
        selected.value = setOf(value)
        manageMode.value = true
    }

    private fun setSelected(next: Set<String>) {
        selected.value = next
        if (next.isEmpty()) manageMode.value = false
    }

    /**
     * 这一条源是不是"正在使用"。
     *
     * <p>多仓生效后 `API_URL` 已被改写成仓里第一条子源的地址,订阅列表里那条仓地址匹配不上,
     * 所以还要认"它正是当前仓的来源地址"(否则切到仓之后重进页面,所有源都显示未使用)。
     */
    private fun isInUse(url: String, vod: Boolean): Boolean =
        if (vod) {
            url == activeUrl.value || HistoryHelper.isApiLineSourceOf(url, activeUrl.value)
        } else {
            (!liveFollow.value && url == liveActiveUrl.value) ||
                (!liveFollow.value && HistoryHelper.isLiveApiLineSourceOf(url, liveActiveUrl.value))
        }

    /** 该地址在点播/直播任一侧仍在生效(激活源或仓来源)—— 只用于挡副本清理,不放宽上面的删除保护 */
    private fun activeInEitherMode(url: String): Boolean {
        val vodApi = KV.get(HawkConfig.API_URL, "")
        val liveApi = KV.get(HawkConfig.LIVE_API_URL, "")
        return url == vodApi || url == liveApi ||
            HistoryHelper.isApiLineSourceOf(url, vodApi) ||
            HistoryHelper.isLiveApiLineSourceOf(url, liveApi)
    }

    /** 任一模式的订阅列表里还留着该地址(同地址允许跨模式重复添加)—— 副本同样不能删 */
    private fun referencedBySubscribes(url: String): Boolean =
        loadSubscribes(ConfigMode.Vod).any { parseSubscribe(it).url == url } ||
            loadSubscribes(ConfigMode.Live).any { parseSubscribe(it).url == url }

    /** 多仓的子源条目里还留着该地址 —— 仓的多个子源只有当前生效那个会被上面查到,其余必须在这里挡 */
    private fun referencedByRepo(url: String): Boolean =
        (HistoryHelper.getApiLines().orEmpty() + HistoryHelper.getLiveApiLines().orEmpty())
            .any { HistoryHelper.getApiLineUrl(it) == url }

    private fun switchToVod(item: SubscribeSource) {
        if (activeUrl.value == item.url) return
        val followLive = applyVodSource(item)
        activeUrl.value = item.url
        if (followLive) {
            liveActiveUrl.value = ""
            liveFollow.value = true
        }
        toastEvent.value = str(R.string.config_switched_to, item.name)
    }

    private fun switchToLive(item: SubscribeSource) {
        if (!liveFollow.value && liveActiveUrl.value == item.url) return
        applyLiveSource(item)
        liveActiveUrl.value = item.url
        liveFollow.value = false
        toastEvent.value = str(R.string.config_switched_to, item.name)
    }

    /**
     * 切源统一入口:黑名单里的源**不当场切** —— 它上次就是在这个源上把应用崩掉的,
     * 直接切等于再崩一次,所以先弹二次确认(用户可能知道远端已经修好了)。
     */
    fun requestSwitch(item: SubscribeSource, vod: Boolean) {
        if (item.url in disabledUrls.value) {
            pendingSwitch.value = PendingSwitch(item, vod)
        } else if (vod) {
            switchToVod(item)
        } else {
            switchToLive(item)
        }
    }

    /** 二次确认"仍要启用":移出黑名单再切;真坏的话下次启动会重新记入 */
    fun enableAndSwitch() {
        val pending = pendingSwitch.value ?: return
        pendingSwitch.value = null
        BootGuard.enableSource(pending.item.url)
        disabledUrls.value = disabledUrls.value - pending.item.url
        if (pending.vod) switchToVod(pending.item) else switchToLive(pending.item)
    }

    fun followLiveNow() {
        applyLiveFollowVod()
        liveActiveUrl.value = ""
        liveFollow.value = true
        toastEvent.value = str(R.string.toast_live_follow_vod)
    }

    fun deleteSelected(vod: Boolean) {
        val mode = if (vod) ConfigMode.Vod else ConfigMode.Live
        val items = if (vod) vodItems.value else liveItems.value
        val target = selected.value.filterNot { isInUse(parseSubscribe(it).url, vod) }
        if (target.size != selected.value.size) {
            toastEvent.value = str(R.string.toast_source_in_use)
        }
        val remaining = items.filterNot { it in target }
        KV.put(subscribeKeyOf(mode), ArrayList(remaining))
        // 源都删了,就别再留着它的"崩过"记录 —— 否则名单里堆的是用户已经不要的地址
        val removedUrls = target.map { parseSubscribe(it).url }
        BootGuard.forgetSources(removedUrls)
        disabledUrls.value = disabledUrls.value - removedUrls
        // 副本清理要跨模式判"仍在用":点播页删除时,同一地址可能正被直播侧当激活源/仓来源,或被另一模式的订阅/仓子源引用
        val copyUrls = removedUrls.filterNot {
            activeInEitherMode(it) || referencedBySubscribes(it) || referencedByRepo(it)
        }
        if (copyUrls.isNotEmpty()) {
            copyCleanupScope.launch { copyUrls.forEach { removeLocalCopy(it) } }
        }
        if (vod) {
            vodItems.value = remaining
            if (remaining.isEmpty()) {
                ApiConfig.get().clearVodConfig()
                activeUrl.value = ""
                AppBootstrap.retry()
            }
        } else {
            liveItems.value = remaining
            if (remaining.isEmpty()) {
                applyLiveFollowVod()
                liveActiveUrl.value = ""
                liveFollow.value = true
            }
        }
        setSelected(emptySet())
    }

    fun commitAdd(vod: Boolean, name: String, url: String) {
        val mode = if (vod) ConfigMode.Vod else ConfigMode.Live
        val newItems = saveSubscribe(mode, name, url)
        if (vod) vodItems.value = newItems else liveItems.value = newItems
        if (newItems.size == 1) {
            val item = parseSubscribe(newItems.first())
            if (vod) switchToVod(item) else switchToLive(item)
        }
    }

    fun commitEdit(vod: Boolean, target: SubscribeSource, name: String, url: String) {
        if (url.isEmpty()) return
        val mode = if (vod) ConfigMode.Vod else ConfigMode.Live
        val newValue = (name.ifEmpty { url }) + SUBSCRIBE_SPLIT + url
        val oldValue = selected.value.firstOrNull { parseSubscribe(it).url == target.url }
        val updated = updateSubscribe(mode, target, name, url)
        if (vod) vodItems.value = updated else liveItems.value = updated
        if (oldValue != null) selected.value = selected.value - oldValue + newValue
        editTarget.value = null
        val item = parseSubscribe(newValue)
        if (vod) {
            if (target.url == activeUrl.value && url != activeUrl.value) switchToVod(item)
        } else if (!liveFollow.value && target.url == liveActiveUrl.value && url != liveActiveUrl.value) {
            switchToLive(item)
        }
    }

    private fun applyVodSource(item: SubscribeSource): Boolean =
        AppBootstrap.switchVodSubscription(item.url)

    private fun applyLiveSource(item: SubscribeSource) {
        HistoryHelper.setLiveApiHistory(item.url)
        KV.put(HawkConfig.LIVE_API_URL, item.url)
        // 多仓(2026-09-21):换到仓列表之外的地址即退出仓模式,否则「配置切换」会继续列上一仓的子源
        if (!HistoryHelper.isLiveApiLineHistory(item.url)) HistoryHelper.clearLiveApiLineList()
        // 换了直播源就得让旧源的 hosts 映射立刻失效:不能等下次加载成功(加载失败则永久残留)
        ApiConfig.get().clearLiveHosts()
        ApiConfig.get().invalidateLiveConfig()
    }

    private fun applyLiveFollowVod() {
        KV.put(HawkConfig.LIVE_API_URL, "")
        HistoryHelper.clearLiveApiLineList()
        ApiConfig.get().clearLiveHosts()
        ApiConfig.get().invalidateLiveConfig()
    }

    private fun subscribeKeyOf(mode: ConfigMode): String = when (mode) {
        ConfigMode.Vod -> HawkConfig.SUBSCRIBE_LIST
        ConfigMode.Live -> HawkConfig.LIVE_SUBSCRIBE_LIST
    }

    private fun loadSubscribes(mode: ConfigMode): List<String> =
        KV.get(subscribeKeyOf(mode), ArrayList<String>()).toList()

    private fun saveSubscribe(mode: ConfigMode, name: String, url: String): List<String> {
        val value = (name.ifEmpty { url }) + SUBSCRIBE_SPLIT + url
        val list = ArrayList(loadSubscribes(mode))
        val existIndex = list.indexOfFirst { parseSubscribe(it).url == url }
        if (existIndex >= 0) list[existIndex] = value else list.add(value)
        KV.put(subscribeKeyOf(mode), list)
        return list
    }

    private fun updateSubscribe(mode: ConfigMode, original: SubscribeSource, name: String, url: String): List<String> {
        val value = (name.ifEmpty { url }) + SUBSCRIBE_SPLIT + url
        val list = ArrayList(loadSubscribes(mode))
        val index = list.indexOfFirst { parseSubscribe(it).url == original.url }
        if (index < 0) return list
        list[index] = value
        val dupIndex = list.indexOfFirst { it != value && parseSubscribe(it).url == url }
        if (dupIndex >= 0) list.removeAt(dupIndex)
        KV.put(subscribeKeyOf(mode), list)
        return list
    }
}
