package com.github.tvbox.osc.sourcedata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * 取流结果的序号归属:同一通道内"后发请求顶掉先发请求",而播放与预载两条通道各自持有序号、互不作废
 * (预载请求比真实播放先发、后到,共用一个序号会把真实播放的结果顶掉 —— 预载方案的核心前提)。
 */
class PlayLoaderSeqTest {

    @Test
    fun olderRequestBecomesStaleAfterCancelOrSwitch() {
        val seq = AtomicInteger(0)
        val mine = seq.incrementAndGet()
        assertFalse("本次请求的序号应被认领", PlayLoader.isStaleResult(mine, seq))

        seq.incrementAndGet() // cancelPlayRequest()/切集:序号前进 ⇒ 旧响应作废
        assertTrue("序号被顶掉后不得再投递", PlayLoader.isStaleResult(mine, seq))
    }

    /** 结构断言:取流与预载必须各自持有一个序号字段(合并成一个计数器就再也分不开两条通道) */
    @Test
    fun playAndPreloadKeepSeparateSeqFields() {
        val names = PlayLoader::class.java.declaredFields
            .filter { AtomicInteger::class.java.isAssignableFrom(it.type) }
            .map { it.name }
            .sorted()
        assertEquals(listOf("playRequestSeq", "preloadRequestSeq"), names)
    }

    @Test
    fun preloadSeqIsNotInvalidatedByRealPlayback() {
        val playSeq = AtomicInteger(0)
        val preloadSeqHolder = AtomicInteger(0)

        val preload = preloadSeqHolder.incrementAndGet()
        playSeq.incrementAndGet() // 真实播放开始(playRequestSeq 前进)

        assertFalse("预载结果不该被真实播放作废", PlayLoader.isStaleResult(preload, preloadSeqHolder))
        assertFalse("两条通道的序号各自独立", PlayLoader.isStaleResult(playSeq.get(), playSeq))
    }
}
