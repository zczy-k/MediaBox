package com.github.tvbox.osc.player.controller

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import com.github.tvbox.osc.R
import com.github.tvbox.osc.util.GestureHelper
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.LOG
import org.json.JSONException
import xyz.doikki.videoplayer.player.VideoView
import xyz.doikki.videoplayer.util.PlayerUtils
import kotlin.math.abs

class GestureController(private val host: ComposeVideoController) :
    GestureDetector.OnGestureListener, GestureDetector.OnDoubleTapListener, View.OnTouchListener {

    companion object {
        private const val SLIDE_POSITION_FULL_WIDTH_MS = 240000f
    }

    private val context: Context get() = host.context

    // —— 手势引擎字段（照抄 BaseController） ——
    private var gestureDetector: GestureDetector? = null
    private var audioManager: AudioManager? = null
    private var isGestureEnabled = true
    private var streamVolume = 0
    private var brightness = 0f
    private var mSeekPosition = -1
    private var firstTouch = false
    private var changePosition = false
    private var changeBrightness = false
    private var changeVolume = false
    private var canChangePosition = true
    private var enableInNormal = false
    private var canSlide = false
    private var isDoubleTapTogglePlayEnabled = true
    private var fromLongPress = false

    @SuppressLint("ClickableViewAccessibility")
    fun attach() {
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        gestureDetector = GestureDetector(context, this)
        host.setOnTouchListener(this)
    }

    fun onPlayerState(playerState: Int) {
        if (playerState == VideoView.PLAYER_NORMAL) {
            canSlide = enableInNormal
        } else if (playerState == VideoView.PLAYER_FULL_SCREEN) {
            canSlide = true
        }
    }

    fun setGestureEnabled(enabled: Boolean) {
        isGestureEnabled = enabled
    }

    fun setCanChangePosition(enabled: Boolean) {
        canChangePosition = enabled
    }

    fun setEnableInNormal(enabled: Boolean) {
        enableInNormal = enabled
    }

    private fun canHandleGesture(event: MotionEvent): Boolean {
        return host.isInPlaybackState() &&
                isGestureEnabled &&
                canSlide &&
                !host.isLocked() &&
                !PlayerUtils.isEdge(context, event)
    }

    private fun canChangeBrightnessVolume(event: MotionEvent): Boolean {
        return canHandleGesture(event) && !GestureHelper.isControlDisabled()
    }

    override fun onDown(e: MotionEvent): Boolean {
        if (!host.isInPlaybackState() || !isGestureEnabled || PlayerUtils.isEdge(context, e)) {
            return true
        }
        streamVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
        val activity = PlayerUtils.scanForActivity(context)
        brightness = if (activity == null) 0f else activity.window.attributes.screenBrightness
        firstTouch = true
        changePosition = false
        changeBrightness = false
        changeVolume = false
        return true
    }

    override fun onScroll(
        e1: MotionEvent?,
        e2: MotionEvent,
        distanceX: Float,
        distanceY: Float,
    ): Boolean {
        if (e1 == null) return true
        if (!canHandleGesture(e1)) return true
        val deltaX = e1.x - e2.x
        val deltaY = e1.y - e2.y
        if (firstTouch) {
            changePosition = abs(distanceX) >= abs(distanceY)
            if (!changePosition) {
                if (host.previewMode) return true
                if (!canChangeBrightnessVolume(e1)) return true
                val halfScreen = PlayerUtils.getScreenWidth(context, true) / 2
                if (e2.x > halfScreen) changeVolume = true else changeBrightness = true
            }
            if (changePosition) changePosition = canChangePosition
            firstTouch = false
        }
        if (changePosition) {
            slideToChangePosition(deltaX)
        } else if (changeBrightness) {
            slideToChangeBrightness(deltaY)
        } else if (changeVolume) {
            slideToChangeVolume(deltaY)
        }
        return true
    }

    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
        host.toggleControls()
        return true
    }

    override fun onDoubleTap(e: MotionEvent): Boolean {
        // 预览态（竖屏详情页）同样支持双击暂停/播放（此态只放行单击显隐）。
        // ⚠️ GestureDetector 语义下单击显隐要等双击窗口超时（~300ms）才确认，是双击功能的固有代价。
        if (isDoubleTapTogglePlayEnabled && !host.isLocked() && host.isInPlaybackState()) {
            host.wrapper?.togglePlay()
        }
        return true
    }

    override fun onLongPress(e: MotionEvent) {
        if (host.previewMode) return
        if (host.curPlayState != VideoView.STATE_PAUSED) {
            speedPlayStart()
        }
    }

    override fun onShowPress(e: MotionEvent) {}
    override fun onSingleTapUp(e: MotionEvent): Boolean = false
    override fun onDoubleTapEvent(e: MotionEvent): Boolean = false
    override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean = false

    /** 锁屏触摸守卫 + 手势分发（旧 rootView OnTouch 与 BaseController.OnTouch 合并，行为等价） */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        if (host.previewMode) {
            return gestureDetector?.onTouchEvent(event) ?: false
        }
        if (host.isLocked()) {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                host.showLockView()
            }
            return true
        }
        return gestureDetector?.onTouchEvent(event) ?: false
    }

    fun onTouchEvent(event: MotionEvent) {
        // CANCEL(来电浮窗/下拉通知栏/父容器拦截)时也要结束倍速,
        // 否则长按 3.0x 永不恢复
        when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> speedPlayEnd()
        }
        if (gestureDetector?.onTouchEvent(event) != true) {
            when (event.actionMasked) {
                MotionEvent.ACTION_UP -> {
                    if (mSeekPosition >= 0) {
                        host.wrapper?.seekTo(mSeekPosition.toLong())
                        mSeekPosition = -1
                    }
                }
                MotionEvent.ACTION_CANCEL -> mSeekPosition = -1
            }
        }
    }

    private fun slideToChangePosition(deltaX: Float) {
        val width = host.measuredWidth
        if (width <= 0) return
        val wrapper = host.wrapper ?: return
        val duration = PlayerUtils.safeTimeMs(wrapper.duration)
        val currentPosition = PlayerUtils.safeTimeMs(wrapper.currentPosition)
        var position = (-deltaX / width * SLIDE_POSITION_FULL_WIDTH_MS + currentPosition).toInt()
        if (position > duration) position = duration
        if (position < 0) position = 0
        host.updateSeekUiHint(currentPosition, position)
        mSeekPosition = position
    }

    private fun slideToChangeBrightness(deltaY: Float) {
        val activity = PlayerUtils.scanForActivity(context) ?: return
        val window = activity.window
        val attributes = window.attributes
        val height = host.measuredHeight
        if (height <= 0) return
        if (brightness == -1.0f) brightness = 0.5f
        var newBrightness = deltaY * 2 / height + brightness
        if (newBrightness < 0) newBrightness = 0f
        if (newBrightness > 1.0f) newBrightness = 1.0f
        val percent = (newBrightness * 100).toInt()
        attributes.screenBrightness = newBrightness
        window.attributes = attributes
        host.state.slideHintText = context.getString(R.string.player_gesture_percent, percent)
        host.state.slideHintBrightness = true
        host.state.slideHintVisible = true
    }

    private fun slideToChangeVolume(deltaY: Float) {
        val am = audioManager ?: return
        val height = host.measuredHeight
        if (height <= 0) return
        val streamMaxVolume = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val deltaV = deltaY * 2 / height * streamMaxVolume
        var index = streamVolume + deltaV
        if (index > streamMaxVolume) index = streamMaxVolume.toFloat()
        if (index < 0) index = 0f
        val percent = (index / streamMaxVolume * 100).toInt()
        am.setStreamVolume(AudioManager.STREAM_MUSIC, index.toInt(), 0)
        host.state.slideHintText = context.getString(R.string.player_gesture_percent, percent)
        host.state.slideHintBrightness = false
        host.state.slideHintVisible = true
    }

    private fun speedPlayStart() {
        fromLongPress = true
        try {
            val cfg = host.playerConfig ?: return
            // 倍速提速不入 playerCfg(原实现把 "sp":3.0 经 updatePlayerCfg
            // 持久化,手势被 CANCEL 中断或后续集数会持续 3.0x);只改播放器速度,配置保持原值
            host.speedOld = cfg.getDouble("sp").toFloat()
            // 长按倍速:设置页滑块可调 2x~10x,每次长按实时读 KV,改设置立即生效
            val boost = KV.get(HawkConfig.LONG_PRESS_SPEED, HawkConfig.LONG_PRESS_SPEED_DEFAULT).toFloat()
            host.wrapper?.setSpeed(boost)
            host.state.speedBoostValue = boost
            host.state.speedBoostVisible = true
        } catch (e: JSONException) {
            LOG.e("GestureController", e)
        }
    }

    private fun speedPlayEnd() {
        if (!fromLongPress) return
        fromLongPress = false
        // 恢复 DOWN 时快照的原速度;cfg 未被修改,无需回写与持久化
        host.wrapper?.setSpeed(host.speedOld)
        host.state.speedBoostVisible = false
    }
}
