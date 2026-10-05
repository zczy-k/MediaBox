package com.github.tvbox.osc.ui.activity

import android.graphics.Bitmap
import android.os.Handler
import com.github.tvbox.osc.R
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.PlayerHelper
import xyz.doikki.videoplayer.player.VideoView
import xyz.doikki.videoplayer.util.PlayerUtils
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal class LiveOverlayController(
    private val activity: LivePlayActivity,
    private val handler: Handler,
) {

    companion object {
        private const val RESOLUTION_INFO_MAX_RETRY = 10
        private const val RESOLUTION_INFO_RETRY_DELAY = 300L
        private const val RESOLUTION_INFO_HIDE_DELAY = 3000L
        private const val OVERLAY_HIDE_DELAY = 6000L
        private const val GESTURE_HINT_HIDE_DELAY = 1000L
    }

    private var resolutionInfoRetryCount = 0
    private var resolutionInfoPending = false

    private val hideOverlayRun = Runnable { activity.overlayVisible = false }

    private val hideGestureHintRun = Runnable { activity.gestureHintText = null }

    private val hideResolutionInfoRun = Runnable {
        activity.resolutionVisible = false
    }

    fun scheduleOverlayHide() {
        handler.removeCallbacks(hideOverlayRun)
        handler.postDelayed(hideOverlayRun, OVERLAY_HIDE_DELAY)
    }

    fun showGestureHint(isBrightness: Boolean, percent: Int) {
        val label = activity.getString(if (isBrightness) R.string.live_brightness else R.string.live_volume)
        activity.gestureHintText = activity.getString(R.string.live_gesture_hint, label, percent)
        handler.removeCallbacks(hideGestureHintRun)
        handler.postDelayed(hideGestureHintRun, GESTURE_HINT_HIDE_DELAY)
    }

    fun showSwitchChannelSnapshot() {
        var bitmap: Bitmap? = null
        try {
            bitmap = activity.mVideoView?.doScreenShot()
        } catch (ignored: Throwable) {
            LOG.d("LiveOverlayController", "doScreenShot failed, switch-channel snapshot skipped")
        }
        activity.snapshotBitmap = bitmap
        activity.snapshotVisible = true
    }

    fun hideSwitchChannelSnapshot() {
        activity.snapshotVisible = false
        activity.snapshotBitmap = null
    }

    fun onPlaybackStarted() {
        hideSwitchChannelSnapshot()
        if (resolutionInfoPending) {
            resolutionInfoRetryCount = 0
            handler.removeCallbacks(updateResolutionInfoRun)
            handler.post(updateResolutionInfoRun)
        }
    }

    fun showResolutionAfterChannelSwitch() {
        resolutionInfoPending = true
        resolutionInfoRetryCount = 0
        activity.resolutionText = ""
        activity.resolutionVisible = false
        handler.removeCallbacks(hideResolutionInfoRun)
        handler.removeCallbacks(updateResolutionInfoRun)
        handler.postDelayed(updateResolutionInfoRun, RESOLUTION_INFO_RETRY_DELAY)
    }

    private val updateResolutionInfoRun = Runnable {
        val videoView = activity.mVideoView ?: return@Runnable
        if (videoView.currentPlayState != VideoView.STATE_PREPARED &&
            videoView.currentPlayState != VideoView.STATE_BUFFERED &&
            videoView.currentPlayState != VideoView.STATE_PLAYING
        ) {
            retryOrHideResolutionInfo()
            return@Runnable
        }
        val videoSize = videoView.videoSize
        if (videoSize != null && videoSize.size >= 2 && videoSize[0] > 0 && videoSize[1] > 0) {
            resolutionInfoPending = false
            activity.resolutionText = videoSize[0].toString() + " x " + videoSize[1]
            activity.resolutionVisible = true
            handler.removeCallbacks(hideResolutionInfoRun)
            handler.postDelayed(hideResolutionInfoRun, RESOLUTION_INFO_HIDE_DELAY)
            return@Runnable
        }
        retryOrHideResolutionInfo()
    }

    private fun retryOrHideResolutionInfo() {
        if (resolutionInfoPending && resolutionInfoRetryCount++ < RESOLUTION_INFO_MAX_RETRY) {
            handler.postDelayed(updateResolutionInfoRun, RESOLUTION_INFO_RETRY_DELAY)
        } else {
            activity.resolutionVisible = false
        }
    }

    fun showTime() {
        activity.showTimeOn = KV.get(HawkConfig.LIVE_SHOW_TIME, false)
        handler.removeCallbacks(updateTimeRun)
        if (activity.showTimeOn) handler.post(updateTimeRun)
    }

    private val updateTimeRun = object : Runnable {
        override fun run() {
            activity.timeText = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            handler.postDelayed(this, 1000)
        }
    }

    fun showNetSpeed() {
        activity.showNetSpeedOn = KV.get(HawkConfig.LIVE_SHOW_NET_SPEED, false)
        handler.removeCallbacks(updateNetSpeedRun)
        if (activity.showNetSpeedOn) handler.post(updateNetSpeedRun)
    }

    private val updateNetSpeedRun = object : Runnable {
        override fun run() {
            val videoView = activity.mVideoView ?: return
            activity.netSpeedText = PlayerHelper.getDisplaySpeed(videoView.tcpSpeed, true)
            handler.postDelayed(this, 1000)
        }
    }

    private val updateTimeshiftRun = object : Runnable {
        override fun run() {
            val videoView = activity.mVideoView ?: return
            if (!activity.isSHIYI) return
            activity.tsPosition = PlayerUtils.safeTimeMs(videoView.currentPosition)
            handler.postDelayed(this, 1000)
        }
    }

    fun startTimeshiftTicker() {
        handler.removeCallbacks(updateTimeshiftRun)
        handler.postDelayed(updateTimeshiftRun, 1000)
    }

    fun stopTimeshiftTicker() {
        handler.removeCallbacks(updateTimeshiftRun)
    }

    fun onTimeshiftSeek(progress: Float) {
        val videoView = activity.mVideoView ?: return
        val target = progress.toInt().coerceIn(0, activity.tsDuration.coerceAtLeast(1))
        videoView.seekTo(target.toLong())
        activity.tsPosition = target
        scheduleOverlayHide()
    }

    fun onTimeshiftTogglePlay() {
        val videoView = activity.mVideoView ?: return
        if (videoView.isPlaying) videoView.pause() else videoView.start()
        scheduleOverlayHide()
    }

    fun updateChannelInfoUi() {
        if (activity.isSHIYI) return
        val channel = activity.channelName ?: return
        val name = channel.channelName ?: return
        var ui = ChannelInfoUi(name = name, num = channel.channelNum)
        ui = if (channel.sourceNum <= 0) {
            ui.copy(sourceText = "1/1")
        } else {
            ui.copy(sourceText = activity.getString(R.string.live_line_index, channel.sourceIndex + 1, channel.sourceNum))
        }
        var current = ""
        var currentTitle = ""
        var next = ""
        var nextTitle = ""
        val arrayList = activity.epgController.cachedEpg(name)
        if (arrayList != null && arrayList.isNotEmpty()) {
            activity.epgdata = arrayList
        } else {
            activity.epgdata = ArrayList()
        }
        val timeZone = TimeZone.getTimeZone("GMT+8:00")
        val currentStart = Calendar.getInstance(timeZone)
        currentStart.set(Calendar.MINUTE, 0)
        currentStart.set(Calendar.SECOND, 0)
        currentStart.set(Calendar.MILLISECOND, 0)
        val currentEnd = (currentStart.clone() as Calendar).apply { add(Calendar.MINUTE, 59) }
        val nextStart = (currentEnd.clone() as Calendar).apply { add(Calendar.MINUTE, 1) }
        val nextEnd = (nextStart.clone() as Calendar).apply { add(Calendar.MINUTE, 59) }
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        timeFormat.timeZone = timeZone
        var hasInfo = false
        val list = activity.epgdata
        if (list.isNotEmpty()) {
            val date = Date()
            var size = list.size - 1
            while (size >= 0) {
                val info = list[size]
                if (info.startdateTime != null && info.enddateTime != null &&
                    date.after(info.startdateTime) && date.before(info.enddateTime)
                ) {
                    current = info.start + "-" + info.end
                    currentTitle = info.title
                    if (size != list.size - 1) {
                        next = list[size + 1].start + "-" + list[size + 1].end
                        nextTitle = list[size + 1].title
                    } else {
                        next = info.end + "-23:59"
                        nextTitle = activity.getString(R.string.live_epg_hot_no_info)
                    }
                    hasInfo = true
                    break
                } else {
                    size--
                }
            }
        }
        if (!hasInfo) {
            current = timeFormat.format(currentStart.time) + "-" + timeFormat.format(currentEnd.time)
            currentTitle = activity.getString(R.string.live_epg_hot)
            next = timeFormat.format(nextStart.time) + "-" + timeFormat.format(nextEnd.time)
            nextTitle = activity.getString(R.string.live_epg_no_info)
        }
        activity.channelInfoUi = ui.copy(
            currentEpgTime = current,
            currentEpgTitle = currentTitle,
            nextEpgTime = next,
            nextEpgTitle = nextTitle,
        )
        activity.epgVersion++
    }
}
