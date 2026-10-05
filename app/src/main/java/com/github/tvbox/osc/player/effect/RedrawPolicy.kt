package com.github.tvbox.osc.player.effect

/** 补重绘的判定：效果链的合成只发生在有新帧时，暂停态的几何/参数变化只能靠显式重绘一帧 */
object RedrawPolicy {

    /** 输出几何变了（退出全屏回小窗 / 转屏）：已合成那一帧还是旧尺寸 */
    @JvmStatic
    fun shouldRedrawOnGeometry(sizeChanged: Boolean, playing: Boolean, redrawReady: Boolean): Boolean =
        sizeChanged && !playing && redrawReady

    @JvmStatic
    fun shouldRedrawOnParams(playing: Boolean, redrawReady: Boolean): Boolean = !playing && redrawReady
}
