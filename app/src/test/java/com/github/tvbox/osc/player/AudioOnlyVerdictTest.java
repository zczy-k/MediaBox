package com.github.tvbox.osc.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 「是否纯音频」三态判定的口径单测。
 *
 * <p>这条判据错了不是文案问题:判成纯音频会置上粘滞标记 audioOnlyConfirmed,详情页随后把普通影视
 * 交接给音乐播放页,外部表现是**只有声音、没有画面**(真机实测:关掉「音乐播放页」开关才正常)。
 *
 * <p>核心回归点是 {@code everHadVideoTrack} 这一维:内核在 prepare 早期只报出音轨是常态,
 * 那时的"无视频轨"是解析未完成,不是纯音频。
 */
public class AudioOnlyVerdictTest {

    private static final boolean KNOWN = true;
    private static final boolean UNKNOWN = false;

    @Test
    public void tracksNotKnown_staysUnknown() {
        // 内核还没给出轨道信息:此时"无视频轨"不代表纯音频
        assertNull(MusicSessionDelegate.audioOnlyOrNull(UNKNOWN, true, 0, false));
        assertNull(MusicSessionDelegate.audioOnlyOrNull(UNKNOWN, false, 0, false));
    }

    @Test
    public void knownTracksWithAudioOnly_meansAudioOnly() {
        // 从未见过视频轨 + 轨道已知 + 只有音轨 = 真纯音频
        assertEquals(Boolean.TRUE, MusicSessionDelegate.audioOnlyOrNull(KNOWN, true, 0, false));
    }

    @Test
    public void knownTracksWithVideoTrack_meansVideo() {
        // 有视频轨就确定是影视,哪怕此刻还没报出宽高(它仍然会有画面,只是还没解出来)
        assertEquals(Boolean.FALSE, MusicSessionDelegate.audioOnlyOrNull(KNOWN, true, 1, false));
        assertEquals(Boolean.FALSE, MusicSessionDelegate.audioOnlyOrNull(KNOWN, false, 1, false));
    }

    @Test
    public void videoSeenThenGone_staysUnknown() {
        // ★ 本次修复的主回归点:曾经出现过视频轨,此刻 video 列表空只是解析未完成,
        // 绝不能判成纯音频,否则影视会被交接去音乐页(只有声音没有画面)
        assertNull(MusicSessionDelegate.audioOnlyOrNull(KNOWN, true, 0, true));
    }

    @Test
    public void knownTracksButNoAudioNoVideo_staysUnknown() {
        // 轨道已知却是空流/仅字幕:不能当纯音频,否则会给影视挂上海报
        assertNull(MusicSessionDelegate.audioOnlyOrNull(KNOWN, false, 0, false));
    }

    @Test
    public void everHadVideoTrack_clearedByBeginNewPlay() {
        // 内容边界必须复位,否则上一部是影视就会让下一部真音乐永远判不出来
        PlaybackAttemptState st = new PlaybackAttemptState();
        st.everHadVideoTrack = true;
        // 曾见视频轨 ⇒ 此刻"视频轨为空"只是解析未完成,应为"未知"而非"确定影视"
        // (与 videoSeenThenGone_staysUnknown 同口径;2026-10-10 修正原断言笔误)
        assertNull(MusicSessionDelegate.audioOnlyOrNull(KNOWN, true, 0, st.everHadVideoTrack));
        st.beginNewPlay();
        assertFalse(st.everHadVideoTrack);
        assertEquals(Boolean.TRUE,
                MusicSessionDelegate.audioOnlyOrNull(KNOWN, true, 0, st.everHadVideoTrack));
    }

    @Test
    public void trackInfo_carriesTracksKnownFlag() {
        TrackInfo info = new TrackInfo();
        assertFalse(info.hasKnownTracks());
        info.setTracksKnown(true);
        assertTrue(info.hasKnownTracks());
    }

    @Test
    public void trackInfoBean_videoQualityDefaultsToUnknown() {
        // 0/空串 = 内核未上报,不能被当成有效画质参与排序
        TrackInfoBean bean = new TrackInfoBean();
        assertEquals(0, bean.width);
        assertEquals(0, bean.height);
        assertEquals(0, bean.bitrate);
        assertEquals("", bean.codecs);
    }
}
