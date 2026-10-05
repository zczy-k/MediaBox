package com.github.tvbox.osc.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 有界调用的口径回归:超时只作废结果,任务靠 interrupt 收尾。
 *
 * 存在理由:各调用点都靠 null 走"无结果"分支,若哪天改成抛异常、或改成不打断任务,
 * 站点请求的失败路径会整体变样。
 */
class BoundedCallTest {

    @Test
    fun call_returnsTaskResult() {
        assertEquals("ok", BoundedCall.call(Callable { "ok" }, 1_000L, "test"))
    }

    @Test
    fun call_timesOutToNullAndInterruptsTask() {
        val interrupted = CountDownLatch(1)
        val result = BoundedCall.call(Callable<String?> {
            try {
                Thread.sleep(5_000L)
            } catch (e: InterruptedException) {
                interrupted.countDown()
            }
            null
        // 等待窗口必须远大于线程启动耗时:取消发生在任务启动前时任务不会跑,断言会假失败
        }, 1_000L, "test-timeout")
        assertNull(result)
        assertTrue("超时必须打断任务线程", interrupted.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun call_swallowsTaskFailure() {
        val failed = Callable<String> { throw IllegalStateException("boom") }
        assertNull(BoundedCall.call(failed, 1_000L, "test-error"))
    }
}
