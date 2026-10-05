package com.github.tvbox.osc.ui.page

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.tvbox.osc.bean.AbsXml
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.bean.MovieSort
import com.github.tvbox.osc.sourcedata.SourceViewModel
import com.github.tvbox.osc.sourcedata.observeAsFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class PartitionListVM : ViewModel() {
    sealed interface State {
        data object Loading : State
        data object Empty : State
        data object Ready : State
    }

    data class UiState(
        val state: State = State.Loading,
        val videos: List<Movie.Video> = emptyList(),
        val nextPage: Int = FIRST_PAGE,
        val maxPage: Int = 0,
    ) {
        val hasMore: Boolean get() = !(maxPage > 0 && nextPage > maxPage)
    }

    companion object {
        const val FIRST_PAGE = 1
    }

    val ui = MutableStateFlow(UiState())

    var sort: MovieSort.SortData? = null
        private set

    private val scope = viewModelScope
    private var initialized = false

    val actionMessages = MutableSharedFlow<String>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val actionViewModel = SourceViewModel()

    init {
        scope.launch {
            actionViewModel.actionResult.observeAsFlow().collect { json ->
                val msg = json?.optString("msg").orEmpty()
                if (msg.isNotEmpty()) actionMessages.tryEmit(msg)
                refresh()
            }
        }
    }

    private class LoaderResult(val stale: Boolean, val absXml: AbsXml?)

    /**
     * 收集作用域随 loader 生命周期:release() 取消它即摘掉观察者(等价旧 removeObserver)。
     * ⚠️ 必须在下面 `loader` 之前初始化(匿名对象的 init 用它);`SupervisorJob(parent)` 是为了
     * `cancel()` 只杀这个子 Job 而不带上 viewModelScope,`Main.immediate` 是因为 `observeForever` 有主线程断言。
     */
    private val loaderScope = CoroutineScope(
        SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.Main.immediate
    )

    private val loader = object {
        private val svm = SourceViewModel()

        @Volatile
        private var pending: ((LoaderResult) -> Unit)? = null

        @Volatile
        var busy: Boolean = false
            private set

        init {
            loaderScope.launch {
                svm.listResult.observeAsFlow().collect { abs ->
                    val current = pending
                    pending = null
                    busy = false
                    current?.invoke(LoaderResult(false, abs))
                }
            }
        }

        fun release() {
            pending?.invoke(LoaderResult(true, null))
            pending = null
            busy = false
            loaderScope.cancel()
        }

        fun request(page: Int, data: MovieSort.SortData, onDone: (LoaderResult) -> Unit) {
            pending?.invoke(LoaderResult(true, null))
            pending = onDone
            busy = true
            svm.getList(data, page)
        }
    }

    override fun onCleared() {
        // 两个收集器都不用手工摘:loaderScope 在这里取消,actionViewModel 的随 viewModelScope 取消(onCleared 返回后)
        loader.release()
    }

    fun runAction(video: Movie.Video) {
        actionViewModel.action(video.sourceKey, video.action)
    }

    fun refresh() {
        if (!initialized) return
        ui.value = UiState(State.Loading)
        request(FIRST_PAGE)
    }

    fun initIfNeed(sort: MovieSort.SortData) {
        if (initialized) return
        initialized = true
        this.sort = sort
        request(FIRST_PAGE)
    }

    fun applyFilter(selection: Map<String, String>) {
        if (!initialized) return
        sort?.filterSelect = HashMap(selection)
        ui.value = UiState(State.Loading)
        request(FIRST_PAGE)
    }

    fun loadMore() {
        if (!initialized) return
        val s = ui.value
        if (s.state != State.Ready || !s.hasMore || loader.busy) return
        request(s.nextPage)
    }

    private fun request(page: Int) {
        val data = sort ?: return
        scope.launch {
            val result = suspendCancellableCoroutine<LoaderResult> { cont ->
                loader.request(page, data) { r -> if (cont.isActive) cont.resume(r) }
            }
            if (!result.stale) applyResult(page, result.absXml)
        }
    }

    private fun applyResult(page: Int, abs: AbsXml?) {
        val videos = abs?.movie?.videoList ?: emptyList()
        val maxPage = abs?.movie?.pagecount ?: 0
        val cur = ui.value
        ui.value = when {
            videos.isEmpty() && page == FIRST_PAGE -> UiState(State.Empty, emptyList(), FIRST_PAGE, maxPage)
            else -> UiState(State.Ready, if (page == 0) videos else cur.videos + videos, page + 1, maxPage)
        }
    }
}
