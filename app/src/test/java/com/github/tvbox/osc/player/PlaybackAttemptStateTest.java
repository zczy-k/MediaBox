package com.github.tvbox.osc.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * PlaybackAttemptState 单测:锁住"起播前原样重播"额度的复位语义 —— 该额度排在"硬解→软解"之前,
 * 漏复位会让网络恢复后的起播失败直接落到软解,误改用户选的解码方式。
 *
 * <p>另锁换线/换源"接着看"的位置继承:换线的四个入口都经 {@code rememberProgressForSwitch} 记位置,
 * 而记下的位置只允许被"同一集的另一次起播"消费(见各用例)。
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

    // ---------- 换线/换源的位置继承 ----------
    // 记下的位置只在"下一次起播还是同一集"时成立 —— 缺这道守卫,"记了位置却没播成 + 用户点下一集"
    // 会把上一集的位置写进新一集的进度键(点下一集却从上一集中段开始播)。

    @Test
    public void rememberProgress_prefersLivePosition() {
        PlaybackAttemptState st = new PlaybackAttemptState();
        st.rememberProgressForSwitch("src|1|lineA|0第1集", 90_000, 10_000, "第1集");
        assertEquals(90_000, st.pendingInheritProgress);
        assertTrue(st.pendingInheritAppliesTo("第1集"));
    }

    @Test
    public void rememberProgress_fallsBackToSavedWhenKernelGone() {
        // 自动重试把内核收走后实时位置读作 0,只能靠上一次落盘的值
        PlaybackAttemptState st = new PlaybackAttemptState();
        st.rememberProgressForSwitch("k1", 0, 42_000, "第1集");
        assertEquals(42_000, st.pendingInheritProgress);
    }

    @Test
    public void rememberProgress_nothingWatched_isNotRecorded() {
        PlaybackAttemptState st = new PlaybackAttemptState();
        st.rememberProgressForSwitch("k1", 0, 0, "第1集");
        assertFalse(st.pendingInheritAppliesTo("第1集"));
    }

    @Test
    public void pendingInherit_matchesOnlySameEpisode() {
        PlaybackAttemptState st = new PlaybackAttemptState();
        st.rememberProgressForSwitch("k1", 90_000, 0, "第1集");
        // 各站集名写法不同,但同一集要认得出来
        assertTrue(st.pendingInheritAppliesTo("01"));
        assertFalse(st.pendingInheritAppliesTo("第2集"));
    }

    @Test
    public void pendingInherit_unknownEpisode_passesThrough() {
        // 集名任一侧为空 = 无从判定,不因为拿不到集名就丢掉用户的位置
        PlaybackAttemptState st = new PlaybackAttemptState();
        st.rememberProgressForSwitch("k1", 90_000, 0, null);
        assertTrue(st.pendingInheritAppliesTo("第9集"));
        assertTrue(st.pendingInheritAppliesTo(null));

        st.rememberProgressForSwitch("k1", 90_000, 0, "第1集");
        assertTrue(st.pendingInheritAppliesTo(null));
    }

    @Test
    public void clearPendingInherit_isOneShot() {
        PlaybackAttemptState st = new PlaybackAttemptState();
        st.rememberProgressForSwitch("k1", 90_000, 0, "第1集");
        st.clearPendingInherit();
        assertFalse(st.pendingInheritAppliesTo("第1集"));
        assertEquals(0, st.pendingInheritProgress);
    }
}
