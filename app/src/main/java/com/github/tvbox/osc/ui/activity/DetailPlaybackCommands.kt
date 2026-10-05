package com.github.tvbox.osc.ui.activity

/** 详情页 → 播放层的下行指令(UI 收集后投影到容器,见 `DetailScreen`;V2 前是 VM 直调容器) */
sealed interface PlaybackCommand {

    /** 同页换片:停掉当前内容 */
    data object StopForContentSwitch : PlaybackCommand

    /** 换源点击即停(tip 在 VM 侧取文案:指令在 VM 创建,取不到资源) */
    data class StopForSourceSwitch(val tip: String) : PlaybackCommand

    data object ClearSourceSwitchTip : PlaybackCommand

    /** 选集面板显隐(供播放底栏冻结自动收起) */
    data class SetEpisodeSheetOpen(val open: Boolean) : PlaybackCommand

    /** 选中结果由容器经 `PlayContainer.OnQualitySelectedListener` 回写 */
    data class SelectQuality(val position: Int) : PlaybackCommand
}

/**
 * 进/退全屏决策所需的设备事实。必须当帧由 UI 传入:方向读 Activity resources,
 * 视频是否竖屏在播放层,走状态通道会晚一帧 —— 而 `rotating` 要当帧交给布局。
 */
data class DetailPlaybackFacts(
    val landscape: Boolean,
    val portraitVideo: Boolean,
)

internal object DetailPlaybackCommands {

    /**
     * 旧 `setFullScreen` 的判据:`rotating = (requested && !portraitVideo) != landscape`。
     *
     * 退全屏**不是短路分支**:`requested=false` 时目标恒竖屏,窗口还横着必须置位,`fullBox`
     * 才会保持全屏样直到旋转落地(2026-09-13 真机验证点,features.md「不要为它盲改判据」)。
     * 八格真值表锁在 `DetailPlaybackCommandsTest`。
     */
    fun fullScreenState(
        requested: Boolean,
        facts: DetailPlaybackFacts,
    ): Pair<Boolean, Boolean> {
        val landscapeTarget = requested && !facts.portraitVideo
        return requested to (landscapeTarget != facts.landscape)
    }}
