package com.github.tvbox.osc.ui.page

import org.junit.Assert.assertEquals
import org.junit.Test

class HomePosterRatioTest {

    @Test
    fun dominant_usesMedianForLandscapeSource() {
        assertEquals(16f / 9f, HomePosterRatio.dominant(listOf(1.78f, 1.77f, 1.8f, 1.76f)), 0.03f)
    }

    @Test
    fun dominant_usesMedianForPortraitSource() {
        assertEquals(2f / 3f, HomePosterRatio.dominant(listOf(0.66f, 0.67f, 0.68f, 0.65f)), 0.03f)
    }

    @Test
    fun dominant_resistsOneOutlier() {
        assertEquals(1.78f, HomePosterRatio.dominant(listOf(1.78f, 1.77f, 1.8f, 0.2f)), 0.03f)
    }

    @Test
    fun dominant_usesFallbackWhenNoValidRatios() {
        assertEquals(HomePosterRatio.DEFAULT, HomePosterRatio.dominant(emptyList()), 0f)
        assertEquals(HomePosterRatio.DEFAULT, HomePosterRatio.dominant(listOf(Float.NaN, 0f, -1f)), 0f)
    }

    @Test
    fun dominant_clampsExtremeRatios() {
        assertEquals(1.9f, HomePosterRatio.dominant(listOf(5f)), 0f)
        assertEquals(0.55f, HomePosterRatio.dominant(listOf(0.1f)), 0f)
    }
}
