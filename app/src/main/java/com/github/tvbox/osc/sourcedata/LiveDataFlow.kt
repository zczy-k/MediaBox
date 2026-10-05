package com.github.tvbox.osc.sourcedata

import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * `LiveData` → `Flow` 的唯一桥接点(V4)。
 *
 * 取数侧是 Java、继续用 LiveData(D2 分域决策:重写 9 个文件违反"不重写 Java→Kotlin");页面 VM 是
 * Kotlin,只见 Flow。桥接必须**恰好一处**,否则又会散出一堆手动配对的 `observeForever`/`removeObserver`
 * (V4 之前页面层 8 处,漏配对即泄漏)。因此:**页面层**(Kotlin 页面 VM 与组合层)的 `observeForever`
 * 只允许出现在本文件;播放层(`PlaybackController`/`PreloadCoordinator`,Java,均已有配对清理)
 * 保留原写法,归 V5 收口。
 *
 * 生命周期:观察者挂在收集协程上 —— 作用域取消即摘除(`viewModelScope` 由框架在 `onCleared()`
 * 返回后取消)。**收集方必须用带主线程 Dispatcher 的作用域**:`observeForever` 内部有
 * `assertMainThread`,用裸 `CoroutineScope(Job)` 会兜底成 `Dispatchers.Default` 并在此抛异常。
 *
 * `trySend` 的返回值不检查:`callbackFlow` 自带 64 容量缓冲,而这些通道每条请求只有个位数回包;
 * 真到容量上限说明收集方长期停摆,此时丢弃与阻塞生产者相比更安全(生产者是 LiveData 的主线程派发)。
 */
internal fun <T> LiveData<T>.observeAsFlow(): Flow<T> = callbackFlow {
    val observer = Observer<T> { value -> trySend(value) }
    observeForever(observer)
    awaitClose { removeObserver(observer) }
}
