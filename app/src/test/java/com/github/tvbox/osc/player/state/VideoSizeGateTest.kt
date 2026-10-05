package com.github.tvbox.osc.player.state

import org.junit.Assert.assertEquals
import org.junit.Test

/** VideoSizeGate 单测:锁住"换片取流窗口不采信内核残留尺寸"的口径(轮询与上报事件共用同一判定) */
class VideoSizeGateTest {

    @Test
    fun noSession_kernelValueIsShown() {
        val gate = VideoSizeGate()
        assertEquals("1920 X 1080", gate.textFor(1920, 1080))
    }

    @Test
    fun newSession_showsPlaceholderWhileKernelStillHoldsPreviousSize() {
        val gate = VideoSizeGate()
        assertEquals(VideoSizeGate.UNKNOWN, gate.onNewSession(1920, 1080))
        // 取流窗口内(setUrl 之前)内核读到的仍是上一部/上一集的尺寸
        assertEquals(VideoSizeGate.UNKNOWN, gate.textFor(1920, 1080))
        assertEquals(VideoSizeGate.UNKNOWN, gate.textFor(1920, 1080))
    }

    @Test
    fun newSession_kernelResetShowsPlaceholder() {
        val gate = VideoSizeGate()
        gate.onNewSession(1920, 1080)
        gate.onKernelContentReplaced()
        // 换内容后内核先回落 0(media3 渲染器 onDisabled 发 UNKNOWN)
        assertEquals(VideoSizeGate.UNKNOWN, gate.textFor(0, 0))
        assertEquals("1280 X 544", gate.textFor(1280, 544))
        assertEquals("1280 X 544", gate.textFor(1280, 544))
    }

    @Test
    fun newSession_reportBeforeNextPollIsShownAtOnce() {
        val gate = VideoSizeGate()
        gate.onNewSession(1920, 1080)
        gate.onKernelContentReplaced()
        // 事件路径:内核上报新尺寸时不留到下一次轮询
        assertEquals("1280 X 544", gate.textFor(1280, 544))
    }

    @Test
    fun newSession_sameSizeAsPreviousNeedsContentReplaced() {
        // 未收到"内核已换内容"且读数与残留基准相同:分不清是新会话上报还是上一会话残留,按未知处理
        val gate = VideoSizeGate()
        gate.onNewSession(1920, 1080)
        assertEquals(VideoSizeGate.UNKNOWN, gate.textFor(1920, 1080))
    }

    @Test
    fun contentReplaced_sameSizeAsPreviousIsShown() {
        // 画质效果链开通时尺寸只由 onTracksChanged 补报:同分辨率的下一集必须能画出来
        val gate = VideoSizeGate()
        gate.onNewSession(1920, 1080)
        gate.onKernelContentReplaced()
        assertEquals("1920 X 1080", gate.textFor(1920, 1080))
    }

    @Test
    fun newSession_unknownKernelSnapshotTrustsNextReport() {
        // 起播时内核已是 0(上一会话已 release):此后任何非零读数都属于本会话
        val gate = VideoSizeGate()
        assertEquals(VideoSizeGate.UNKNOWN, gate.onNewSession(0, 0))
        assertEquals(VideoSizeGate.UNKNOWN, gate.textFor(0, 0))
        assertEquals("1920 X 1080", gate.textFor(1920, 1080))
    }

    @Test
    fun sessionEnded_laterSizesAreShownDirectly() {
        val gate = VideoSizeGate()
        gate.onNewSession(1920, 1080)
        gate.onKernelContentReplaced()
        gate.textFor(1280, 544)
        // 同一会话内的后续读数(ABR 换档等)不再过滤
        assertEquals("1920 X 1080", gate.textFor(1920, 1080))
        assertEquals(VideoSizeGate.UNKNOWN, gate.textFor(0, 0))
    }
}
