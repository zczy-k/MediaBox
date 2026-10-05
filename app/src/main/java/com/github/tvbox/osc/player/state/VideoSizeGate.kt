package com.github.tvbox.osc.player.state

/**
 * 分辨率角标的会话闸门（顶栏预览态右块 / 底栏胶囊共用）：内核 `mVideoSize` 要到 `setUrl()` 才清零，
 * 换片换集的取流窗口里读到的仍是**上一会话**的尺寸，不能直接采信。
 */
class VideoSizeGate {

    companion object {
        /** 占位文案：未知时照画（换空串会让文字"消失→出现"） */
        const val UNKNOWN = "0 X 0"

        fun format(width: Int, height: Int): String = "$width X $height"
    }

    private var awaitingNewSession = false
    private var kernelContentReplaced = false
    private var priorWidth = 0
    private var priorHeight = 0

    /** 起播（取流）入口：返回该显示的占位文案 */
    fun onNewSession(kernelWidth: Int, kernelHeight: Int): String {
        awaitingNewSession = true
        kernelContentReplaced = false
        priorWidth = kernelWidth
        priorHeight = kernelHeight
        return UNKNOWN
    }

    /** 内核换了内容（setUrl）：此后上报的值都属于本会话 */
    fun onKernelContentReplaced() {
        kernelContentReplaced = true
    }

    fun textFor(kernelWidth: Int, kernelHeight: Int): String {
        if (isPreviousSessionValue(kernelWidth, kernelHeight)) return UNKNOWN
        awaitingNewSession = false
        return if (kernelWidth > 0 && kernelHeight > 0) format(kernelWidth, kernelHeight) else UNKNOWN
    }

    /** 新会话已开始、内核还没换内容，读数又和起播时的基准相同 ⇒ 这是上一会话的值 */
    private fun isPreviousSessionValue(width: Int, height: Int): Boolean =
        awaitingNewSession && !kernelContentReplaced && width == priorWidth && height == priorHeight
}
