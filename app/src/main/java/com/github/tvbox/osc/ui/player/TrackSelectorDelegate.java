package com.github.tvbox.osc.ui.player;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.player.ExoPlayer;
import com.github.tvbox.osc.player.MyVideoView;
import com.github.tvbox.osc.player.TrackInfo;
import com.github.tvbox.osc.player.TrackInfoBean;
import com.github.tvbox.osc.player.state.PlayerUiState;
import com.github.tvbox.osc.player.state.SelectDialogState;
import com.github.tvbox.osc.util.LOG;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import xyz.doikki.videoplayer.player.AbstractPlayer;

final class TrackSelectorDelegate {

    interface Host {
        MyVideoView player();

        Context context();

        PlayerUiState uiState();
    }

    private final Host host;
    private final AtomicInteger trackSwitchSeq = new AtomicInteger(0);

    TrackSelectorDelegate(Host host) {
        this.host = host;
    }

    static boolean isSameTrack(TrackInfoBean left, TrackInfoBean right) {
        return left.renderId == right.renderId
                && left.trackGroupId == right.trackGroupId
                && left.trackId == right.trackId;
    }

    void invalidatePendingSwitch() {
        trackSwitchSeq.incrementAndGet();
    }

    void selectAudioTrack() {
        if (host.player() == null) return;
        AbstractPlayer mediaPlayer = host.player().getMediaPlayer();
        TrackInfo trackInfo = null;
        if (mediaPlayer instanceof ExoPlayer) {
            trackInfo = ((ExoPlayer) mediaPlayer).getTrackInfo();
        }
        Context context = host.context();
        if (trackInfo == null) {
            Toast.makeText(context, context.getString(R.string.player_no_audio_track), Toast.LENGTH_SHORT).show();
            return;
        }
        List<TrackInfoBean> bean = trackInfo.getAudio();
        if (bean.size() < 1) return;
        List<String> names = new ArrayList<>();
        for (TrackInfoBean item : bean) names.add(item.name);
        // 诊断:把弹窗列出的轨道与当前选中项落盘(vivo ROM 吞 logcat,只能看文件日志)
        LOG.i("echo-setTrack list: kernel=" + mediaPlayer.getClass().getSimpleName()
                + " count=" + bean.size() + " selected=" + trackInfo.getAudioSelected(false)
                + " names=" + names);
        host.uiState().setSelectDialog(new SelectDialogState(
                context.getString(R.string.player_switch_audio_track),
                names,
                trackInfo.getAudioSelected(false),
                pos -> {
                    if (pos < 0 || pos >= bean.size()) return kotlin.Unit.INSTANCE;
                    TrackInfoBean value = bean.get(pos);
                    try {
                        for (TrackInfoBean audio : bean) {
                            audio.selected = isSameTrack(audio, value);
                        }
                        mediaPlayer.pause();
                        long progress = mediaPlayer.getCurrentPosition();
                        // 诊断:记录点击了哪条轨 + 切换前的位置/状态
                        LOG.i("echo-setTrack request: name=" + value.name + " render=" + value.renderId
                                + " group=" + value.trackGroupId + " track=" + value.trackId
                                + " pos=" + progress + " state=" + (host.player() == null ? -999 : host.player().getCurrentPlayState()));
                        if (mediaPlayer instanceof ExoPlayer) ((ExoPlayer) mediaPlayer).setTrack(value);
                        final int seq = trackSwitchSeq.incrementAndGet();
                        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (seq != trackSwitchSeq.get()) return;
                                mediaPlayer.start();
                                // 诊断:切轨 +200ms 后的内核状态。⚠️ 只读播放状态、不读位置:本 runnable 在 try 块之外,
                                // 而这 200ms 内内核可能已被释放
                                LOG.i("echo-setTrack after start: state="
                                        + (host.player() == null ? -999 : host.player().getCurrentPlayState()));
                            }
                        }, 200);
                    } catch (Exception e) {
                        LOG.e("切换音轨出错");
                    }
                    return kotlin.Unit.INSTANCE;
                }));
    }

    void selectVideoTrack() {
        if (host.player() == null) return;
        AbstractPlayer mediaPlayer = host.player().getMediaPlayer();
        TrackInfo trackInfo = null;
        if (mediaPlayer instanceof ExoPlayer) {
            trackInfo = ((ExoPlayer) mediaPlayer).getTrackInfo();
        }
        Context context = host.context();
        if (trackInfo == null || trackInfo.getVideo().isEmpty()) {
            Toast.makeText(context, context.getString(R.string.player_no_video_track), Toast.LENGTH_SHORT).show();
            return;
        }
        List<TrackInfoBean> tracks = trackInfo.getVideo();
        List<String> names = new ArrayList<>();
        for (TrackInfoBean item : tracks) names.add(item.name);
        host.uiState().setSelectDialog(new SelectDialogState(
                context.getString(R.string.player_switch_video_track),
                names,
                trackInfo.getVideoSelected(false),
                pos -> {
                    if (pos < 0 || pos >= tracks.size()) return kotlin.Unit.INSTANCE;
                    TrackInfoBean value = tracks.get(pos);
                    try {
                        for (TrackInfoBean track : tracks) {
                            track.selected = isSameTrack(track, value);
                        }
                        mediaPlayer.pause();
                        long progress = mediaPlayer.getCurrentPosition();
                        if (mediaPlayer instanceof ExoPlayer) {
                            ((ExoPlayer) mediaPlayer).setTrack(value);
                        }
                        final int seq = trackSwitchSeq.incrementAndGet();
                        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (seq != trackSwitchSeq.get()) return;
                                mediaPlayer.seekTo(progress);
                                mediaPlayer.start();
                            }
                        }, 200);
                    } catch (Exception e) {
                        LOG.e("echo-switch-video-track-error:" + e.getMessage());
                    }
                    return kotlin.Unit.INSTANCE;
                }));
    }
}
