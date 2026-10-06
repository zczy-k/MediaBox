package com.github.tvbox.osc.player;

import java.util.ArrayList;
import java.util.List;

public class TrackInfo {
    /**
     * 内核是否已给出轨道信息。
     *
     * <p>必须区分"没有轨道"与"还没有轨道信息":空 {@link #audio}/{@link #video} 两种情况都会出现,
     * 但前者可能是纯音频片(该跳音乐页),后者只是内核还没解析完(判成纯音频就会把影视误判成音乐)。
     */
    private boolean tracksKnown;

    private List<TrackInfoBean> audio;
    private List<TrackInfoBean> video;
    private List<TrackInfoBean> subtitle;

    public TrackInfo() {
        audio = new ArrayList<>();
        video = new ArrayList<>();
        subtitle = new ArrayList<>();
    }

    public boolean hasKnownTracks() {
        return tracksKnown;
    }

    public void setTracksKnown(boolean known) {
        tracksKnown = known;
    }

    public List<TrackInfoBean> getAudio() {
        return audio;
    }

    public int getAudioSelected(boolean track) {
        return getSelected(audio, track);
    }

    public int getSubtitleSelected(boolean track) {
        return getSelected(subtitle, track);
    }

    public int getSelected(List<TrackInfoBean> list, boolean track) {
        int i = 0;
        for (TrackInfoBean trackInfoBean : list) {
            if (trackInfoBean.selected) return track ? trackInfoBean.trackId : i;
            i++;
        }
        return 99999;
    }

    public void addAudio(TrackInfoBean audio) {
        this.audio.add(audio);
    }

    public List<TrackInfoBean> getVideo() {
        return video;
    }

    public int getVideoSelected(boolean track) {
        return getSelected(video, track);
    }

    public void addVideo(TrackInfoBean video) {
        this.video.add(video);
    }

    public List<TrackInfoBean> getSubtitle() {
        return subtitle;
    }

    public void addSubtitle(TrackInfoBean subtitle) {
        this.subtitle.add(subtitle);
    }
}
