package xyz.doikki.videoplayer.player;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import java.lang.ref.WeakReference;

/**
 * 音频焦点改变监听
 */
final class AudioFocusHelper implements AudioManager.OnAudioFocusChangeListener {

    private Handler mHandler = new Handler(Looper.getMainLooper());

    private WeakReference<VideoView> mWeakVideoView;

    private AudioManager mAudioManager;

    private boolean mStartRequested = false;
    private boolean mPausedForLoss = false;
    /** 是否持有焦点(不能用"上次焦点事件==GAIN"代替:焦点被收回后会失真,暂停后恢复将不再请求焦点) */
    private boolean mFocusGranted = false;
    /** 上次派发过的焦点事件(仅用于同值去重,与持有状态无关) */
    private int mLastFocusChange = 0;

    AudioFocusHelper(@NonNull VideoView videoView) {
        mWeakVideoView = new WeakReference<>(videoView);
        mAudioManager = (AudioManager) videoView.getContext().getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
    }

    @Override
    public void onAudioFocusChange(final int focusChange) {
        if (mLastFocusChange == focusChange) {
            return;
        }

        //由于onAudioFocusChange有可能在子线程调用，
        //故通过此方式切换到主线程去执行
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                handleAudioFocusChange(focusChange);
            }
        });

        mLastFocusChange = focusChange;
    }

    private void handleAudioFocusChange(int focusChange) {
        final VideoView videoView = mWeakVideoView.get();
        if (videoView == null) {
            return;
        }
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_GAIN://获得焦点
            case AudioManager.AUDIOFOCUS_GAIN_TRANSIENT://暂时获得焦点
                mFocusGranted = true;
                //播放中再调 start() 会重复派发 PLAYING 状态(媒体通知被无谓重发)
                if ((mStartRequested || mPausedForLoss) && !videoView.isPlaying()) {
                    videoView.start();
                }
                mStartRequested = false;
                mPausedForLoss = false;
                if (!videoView.isMute())//恢复音量
                    videoView.setVolume(1.0f, 1.0f);
                break;
            case AudioManager.AUDIOFOCUS_LOSS://焦点丢失
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT://焦点暂时丢失
                mFocusGranted = false;
                if (videoView.isPlaying()) {
                    mPausedForLoss = true;
                    videoView.pause();
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK://此时需降低音量
                if (videoView.isPlaying() && !videoView.isMute()) {
                    videoView.setVolume(0.1f, 0.1f);
                }
                break;
        }
    }

    /** 新一次播放开始(实例被复用):清掉上次会话遗留的"待焦点恢复"状态,防被陈旧 GAIN 自动起播 */
    void onNewPlayback() {
        mStartRequested = false;
        mPausedForLoss = false;
    }

    /**
     * Requests to obtain the audio focus
     */
    void requestFocus() {
        if (mFocusGranted) {
            return;
        }

        if (mAudioManager == null) {
            return;
        }

        int status = mAudioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        if (AudioManager.AUDIOFOCUS_REQUEST_GRANTED == status) {
            mFocusGranted = true;
            return;
        }

        mStartRequested = true;
    }

    /**
     * Requests the system to drop the audio focus
     */
    void abandonFocus() {

        if (mAudioManager == null) {
            return;
        }

        //必须复位持有状态:否则下次 requestFocus() 误判"已持有"直接返回(暂停→播放后再也不会申请焦点)
        mFocusGranted = false;
        mStartRequested = false;
        mAudioManager.abandonAudioFocus(this);
    }
}