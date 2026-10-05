package com.github.tvbox.osc.player.controller

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.Window
import com.github.tvbox.osc.util.GestureHelper
import xyz.doikki.videoplayer.controller.BaseVideoController
import xyz.doikki.videoplayer.player.VideoView
import xyz.doikki.videoplayer.util.PlayerUtils
import kotlin.math.abs

/**
 * 直播控制层 Compose 化(avbox-mobile-ui-spec Step 5,复用方案 C1 思路):
 * - 继承 [BaseVideoController],`getLayoutId() = 0`,**无任何自绘 UI**(headless):
 *   loading / 时移条 / 全屏浮层 / 手势指示器全部由 LivePlayActivity 的 Compose 层渲染;
 * - 职责只剩两件事:
 *   1) 手势桥——单击 / 长按 / 左右快滑切台 / 上下滑调亮度音量(灵敏度照抄旧
 *      LiveController + BaseController,保证手感等价);
 *   2) 播放状态转发(onPlayStateChanged → 直播页自动换源状态机)。
 * - 旧 LiveController(含 BaseController 基类)随本类落地后退役。
 */
class ComposeLiveController(
    context: Context,
) : BaseVideoController(context),
    GestureDetector.OnGestureListener,
    GestureDetector.OnDoubleTapListener,
    View.OnTouchListener {

    companion object {
        /** 快滑最小识别距离(px,照抄旧 LiveController) */
        private const val MIN_FLING_DISTANCE = 100
        /** 快滑最小识别速度(px/s,照抄旧 LiveController) */
        private const val MIN_FLING_VELOCITY = 10
    }

    interface LiveControlListener {
        /** 单击画面;返回 true 表示已消费(旧 singleTap 契约) */
        fun onSingleTap(): Boolean

        /** 长按画面(回看态呼时移条,直播态呼设置,旧 longPress 契约) */
        fun onLongPress()

        /** 播放状态变化(驱动自动换源状态机) */
        fun onPlayStateChanged(playState: Int)

        /** 左右快滑:direction -1=左滑(上一频道) / 1=右滑(下一频道) */
        fun onHorizontalFling(direction: Int)

        /** 亮度/音量手势指示器(旧 BaseController msg 100 无展示位,此处补齐,空实现亦可) */
        fun onGesturePercent(isBrightness: Boolean, percent: Int) {}
    }

    private var listener: LiveControlListener? = null

    fun setListener(listener: LiveControlListener) {
        this.listener = listener
    }

    // —— 手势引擎字段(照抄 BaseController;必须 lateinit:initView 由父类构造函数虚调用,
    //    那时属性初始化器还没跑 —— 用带初始化器的属性持有会在 initView 里读到 null) ——
    private lateinit var gestureDetector: GestureDetector
    private lateinit var audioManager: AudioManager
    private var streamVolume = 0
    private var brightness = 0f
    private var firstTouch = false
    private var changeBrightness = false
    private var changeVolume = false
    private var curPlayState = VideoView.STATE_IDLE

    override fun getLayoutId(): Int = 0

    @SuppressLint("ClickableViewAccessibility")
    override fun initView() {
        super.initView()
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        gestureDetector = GestureDetector(context, this)
        setOnTouchListener(this)
    }
    override fun onPlayStateChanged(playState: Int) {
        super.onPlayStateChanged(playState)
        curPlayState = playState
        listener?.onPlayStateChanged(playState)
    }

    // ============================================================
    // 手势(照抄 BaseController,行为等价)
    // ============================================================

    private fun gesturePlaybackState(): Boolean {
        return mControlWrapper != null &&
                curPlayState != VideoView.STATE_ERROR &&
                curPlayState != VideoView.STATE_IDLE &&
                curPlayState != VideoView.STATE_PREPARING &&
                curPlayState != VideoView.STATE_PREPARED &&
                curPlayState != VideoView.STATE_START_ABORT &&
                curPlayState != VideoView.STATE_PLAYBACK_COMPLETED
    }

    private fun canHandleGesture(event: MotionEvent): Boolean {
        return gesturePlaybackState() && !PlayerUtils.isEdge(context, event)
    }

    /**
     * 是否允许"上下滑调亮度/音量"(「禁用手势控制」设置项)。
     * 直播侧的手势只有亮度/音量与左右快滑切台(走 onFling),故只在这一处收口。
     */
    private fun canChangeBrightnessVolume(event: MotionEvent): Boolean {
        return canHandleGesture(event) && !GestureHelper.isControlDisabled()
    }

    override fun onDown(e: MotionEvent): Boolean {
        if (!gesturePlaybackState() || PlayerUtils.isEdge(context, e)) {
            return true
        }
        streamVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val activity = PlayerUtils.scanForActivity(context)
        brightness = activity?.window?.attributes?.screenBrightness ?: 0f
        firstTouch = true
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
        // 直播无 seek(旧 setCanChangePosition(false)):横向滑动不响应,仅保留亮度/音量
        if (firstTouch) {
            if (abs(distanceX) < abs(distanceY)) {
                // 禁用手势控制:竖向滑动静默忽略,不调亮度也不调音量
                if (!canChangeBrightnessVolume(e1)) return true
                val halfScreen = PlayerUtils.getScreenWidth(context, true) / 2
                if (e2.x > halfScreen) changeVolume = true else changeBrightness = true
            }
            firstTouch = false
        }
        if (changeBrightness) {
            slideToChangeBrightness(e1.y - e2.y)
        } else if (changeVolume) {
            slideToChangeVolume(e1.y - e2.y)
        }
        return true
    }

    private fun slideToChangeBrightness(deltaY: Float) {
        val activity = PlayerUtils.scanForActivity(context) ?: return
        val window: Window = activity.window
        val attributes = window.attributes
        val height = measuredHeight
        if (height <= 0) return
        if (brightness == -1.0f) brightness = 0.5f
        // BugReview #10:onScroll 传入的是整段手势累计位移(e1.y-e2.y),基准 brightness 必须保持
        // DOWN 时快照,不得回写;否则累计位移被反复叠加,亮度近似平方增长(轻扫即饱和)。
        // 与点播侧 ComposeVideoController.slideToChangeBrightness 对齐。
        var target = deltaY * 2 / height + brightness
        if (target < 0) target = 0f
        if (target > 1.0f) target = 1.0f
        val percent = (target * 100).toInt()
        attributes.screenBrightness = target
        window.setAttributes(attributes)
        listener?.onGesturePercent(true, percent)
    }

    private fun slideToChangeVolume(deltaY: Float) {
        val height = measuredHeight
        if (height <= 0) return
        val streamMaxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val deltaV = deltaY * 2 / height * streamMaxVolume
        var index = streamVolume + deltaV
        if (index > streamMaxVolume) index = streamMaxVolume.toFloat()
        if (index < 0) index = 0f
        val percent = (index / streamMaxVolume * 100).toInt()
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, index.toInt(), 0)
        listener?.onGesturePercent(false, percent)
    }

    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
        val consumed = listener?.onSingleTap() ?: false
        if (consumed) return true
        // 旧实现回落 super.toggleShowState,headless 下无 UI 可切换,直接视为已消费
        return true
    }

    /** 旧直播页禁用双击暂停(setDoubleTapTogglePlayEnabled(false)),保持禁用 */
    override fun onDoubleTap(e: MotionEvent): Boolean = true

    override fun onDoubleTapEvent(e: MotionEvent): Boolean = false
    override fun onSingleTapUp(e: MotionEvent): Boolean = false
    override fun onShowPress(e: MotionEvent) {}

    override fun onLongPress(e: MotionEvent) {
        listener?.onLongPress()
    }

    override fun onFling(
        e1: MotionEvent?,
        e2: MotionEvent,
        velocityX: Float,
        velocityY: Float,
    ): Boolean {
        if (e1 == null) return false
        if (e1.x - e2.x > MIN_FLING_DISTANCE && abs(velocityX) > MIN_FLING_VELOCITY) {
            listener?.onHorizontalFling(-1) // 左滑
        } else if (e2.x - e1.x > MIN_FLING_DISTANCE && abs(velocityX) > MIN_FLING_VELOCITY) {
            listener?.onHorizontalFling(1) // 右滑
        }
        return false
    }

    /** 锁屏守卫不存在(直播无锁屏),触摸全量交给手势引擎(等价旧 setOnTouchListener) */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        return gestureDetector.onTouchEvent(event)
    }
}
