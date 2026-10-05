package com.github.tvbox.osc.player.effect

import android.content.Context
import androidx.media3.common.C
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.PlaybackVideoGraphWrapper
import androidx.media3.exoplayer.video.VideoFrameReleaseControl
import com.github.tvbox.osc.player.ExoPlayer
import com.github.tvbox.osc.player.PlayerCodecStats
import com.github.tvbox.osc.util.LOG

/**
 * 打开「可重放帧缓存」的视频渲染器：media3 默认关闭它，于是暂停态既没有新帧、`VideoFrameProcessor.REDRAW`
 * 又会直接抛异常。开着它，暂停中的画面才能按新参数/新几何重绘一帧。
 *
 * 构建设置逐项照抄 media3 1.11.1 的 `createPlaybackVideoGraphWrapper`，升级 media3 时须回来对账。
 */
class ReplayableCacheVideoRenderer(
    builder: MediaCodecVideoRenderer.Builder,
    /** 必须与 builder 上的丢帧阈值同值：上游拿它的负值当 wrapper 的阈值 */
    private val lateThresholdToDropDecoderInputUs: Long,
) : MediaCodecVideoRenderer(builder) {

    override fun createPlaybackVideoGraphWrapper(
        context: Context,
        videoFrameReleaseControl: VideoFrameReleaseControl,
    ): PlaybackVideoGraphWrapper =
        PlaybackVideoGraphWrapper.Builder(context, videoFrameReleaseControl)
            .setEnablePlaylistMode(true)
            .experimentalSetLateThresholdToDropInputUs(
                if (lateThresholdToDropDecoderInputUs != C.TIME_UNSET) {
                    -lateThresholdToDropDecoderInputUs
                } else {
                    C.TIME_UNSET
                },
            )
            .setClock(clock)
            // 与上游唯一差别：开缓存（代价 = media3 自述的更耗电、更耗算力）
            .setEnableReplayableCache(true)
            .build()

    override fun onCodecInitialized(
        name: String,
        configuration: MediaCodecAdapter.Configuration,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        super.onCodecInitialized(name, configuration, initializedTimestampMs, initializationDurationMs)
        PlayerCodecStats.videoDecoderName = name
        LOG.i("echo-exo-codec-init: name=$name preferSoft=${ExoPlayer.isPreferSoftwareDecode()}")
    }
}
