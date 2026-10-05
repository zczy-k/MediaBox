package com.github.tvbox.osc.player;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import androidx.annotation.NonNull;

/** 取流/换线/播完待撤三条定时消息的投递与撤销(超时后的判定与重试仍在宿主)。 */
final class PlaybackTimeouts {

    static final long RESOLVE_PLAY_URL_TIMEOUT_MS = 15 * 1000L;

    /** 取流超时/换线播放超时(与既有 mHandler 的三条定时消息拆开:解析超时留在页面/解析层) */
    private static final int MSG_RESOLVE_PLAY_URL_TIMEOUT = 101;
    private static final int MSG_SWITCH_LINE_PLAY_TIMEOUT = 102;
    /** 本集播完后的**延后一拍**撤会话判定(见 {@link PlaybackController#handlePlayStateForMusicSession(int)}) */
    private static final int MSG_DROP_SESSION_AFTER_COMPLETED = 103;
    private static final long SWITCH_LINE_PLAY_TIMEOUT_MS = 20 * 1000L;

    interface Callback {
        void onResolvePlayUrlTimeout();

        void onSwitchLinePlayTimeout();

        void onPendingCompletionDrop();
    }

    private final Callback callback;

    private final Handler handler = new Handler(Looper.getMainLooper(), new Handler.Callback() {
        @Override
        public boolean handleMessage(@NonNull Message msg) {
            switch (msg.what) {
                case MSG_RESOLVE_PLAY_URL_TIMEOUT:
                    callback.onResolvePlayUrlTimeout();
                    return true;
                case MSG_SWITCH_LINE_PLAY_TIMEOUT:
                    callback.onSwitchLinePlayTimeout();
                    return true;
                case MSG_DROP_SESSION_AFTER_COMPLETED:
                    callback.onPendingCompletionDrop();
                    return true;
                default:
                    return false;
            }
        }
    });

    PlaybackTimeouts(Callback callback) {
        this.callback = callback;
    }

    void startResolvePlayUrlTimeout(long timeoutMs) {
        cancelPlayTimeout();
        handler.sendEmptyMessageDelayed(MSG_RESOLVE_PLAY_URL_TIMEOUT, timeoutMs);
    }

    void startSwitchLinePlayTimeout() {
        cancelPlayTimeout();
        handler.sendEmptyMessageDelayed(MSG_SWITCH_LINE_PLAY_TIMEOUT, SWITCH_LINE_PLAY_TIMEOUT_MS);
    }

    void cancelPlayTimeout() {
        handler.removeMessages(MSG_RESOLVE_PLAY_URL_TIMEOUT);
        handler.removeMessages(MSG_SWITCH_LINE_PLAY_TIMEOUT);
    }

    void cancelResolvePlayUrlTimeout() {
        handler.removeMessages(MSG_RESOLVE_PLAY_URL_TIMEOUT);
    }

    void cancelPendingCompletionDrop() {
        handler.removeMessages(MSG_DROP_SESSION_AFTER_COMPLETED);
    }

    void armPendingCompletionDrop() {
        handler.sendEmptyMessage(MSG_DROP_SESSION_AFTER_COMPLETED);
    }
}
