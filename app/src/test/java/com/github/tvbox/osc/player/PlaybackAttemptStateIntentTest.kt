package com.github.tvbox.osc.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PlaybackAttemptState 切换意图单测:锁住三态语义(REBUILD 覆盖 REUSE、取用即复位) */
class PlaybackAttemptStateIntentTest {

    @Test
    fun reuseIntent_consumedOnce() {
        val st = PlaybackAttemptState()
        st.setReuseIntent(true)
        assertTrue(st.consumeReuseIntent())
        assertFalse(st.consumeReuseIntent())
    }

    @Test
    fun releaseIntent_outranksReuseIntent() {
        val st = PlaybackAttemptState()
        st.setReuseIntent(true)
        st.setReleaseIntent(true)
        assertFalse(st.consumeReuseIntent())
    }

    @Test
    fun reuseIntent_doesNotOverwriteReleaseIntent() {
        val st = PlaybackAttemptState()
        st.setReleaseIntent(true)
        st.setReuseIntent(true)
        assertFalse(st.consumeReuseIntent())
    }

    @Test
    fun lineSwitched_keepsRebuildIntent() {
        val st = PlaybackAttemptState()
        st.setReleaseIntent(true)
        st.onLineSwitched()
        assertFalse(st.consumeReuseIntent())
    }

    @Test
    fun lineSwitched_setsReuseWhenNoRebuildIntent() {
        val st = PlaybackAttemptState()
        st.onLineSwitched()
        assertTrue(st.consumeReuseIntent())
    }

    @Test
    fun sourceSwitchClearsIntent() {
        val st = PlaybackAttemptState()
        st.setReuseIntent(true)
        st.stoppedForSourceSwitch()
        assertFalse(st.consumeReuseIntent())
    }
}
