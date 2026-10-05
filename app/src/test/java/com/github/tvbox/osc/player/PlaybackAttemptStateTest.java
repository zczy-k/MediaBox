package com.github.tvbox.osc.player;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * PlaybackAttemptState 单测:锁住"起播前原样重播"额度的复位语义 —— 该额度排在"硬解→软解"之前,
 * 漏复位会让网络恢复后的起播失败直接落到软解,误改用户选的解码方式。
 */
public class PlaybackAttemptStateTest {

    private static PlaybackAttemptState bootRetriedState() {
        PlaybackAttemptState st = new PlaybackAttemptState();
        st.hasRetriedSameUrlOnBoot = true;
        return st;
    }

    @Test
    public void sameUrlRetry_defaultsToFalse() {
        assertFalse(new PlaybackAttemptState().hasRetriedSameUrlOnBoot);
    }

    @Test
    public void sameUrlRetry_clearedByBeginNewPlay() {
        PlaybackAttemptState st = bootRetriedState();
        st.beginNewPlay();
        assertFalse(st.hasRetriedSameUrlOnBoot);
    }

    @Test
    public void sameUrlRetry_clearedByUserSelfRescue() {
        PlaybackAttemptState st = bootRetriedState();
        st.userSelfRescue();
        assertFalse(st.hasRetriedSameUrlOnBoot);
    }

    @Test
    public void sameUrlRetry_clearedByLadderReset() {
        PlaybackAttemptState st = bootRetriedState();
        st.resetAutoRetryLadder();
        assertFalse(st.hasRetriedSameUrlOnBoot);
    }

    @Test
    public void sameUrlRetry_keptBySessionBoundary() {
        PlaybackAttemptState st = bootRetriedState();
        st.beginSession();
        assertTrue(st.hasRetriedSameUrlOnBoot);
    }

    @Test
    public void preloadedResultFlag_clearedByBeginNewPlay() {
        PlaybackAttemptState st = new PlaybackAttemptState();
        st.usedPreloadedResult = true;
        st.beginNewPlay();
        assertFalse(st.usedPreloadedResult);
    }
}
