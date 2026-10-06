package com.github.tvbox.osc.ui.activity

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 线路标签口径单测。
 *
 * <p>锁两条:①没测到必须返回空串(宁缺勿假,不能编一个清晰度出来);
 * ②实测高度**向下取档**,绝不把 1080 说成 1440。
 */
class LineLabelPolicyTest {

    @Test
    fun unmeasuredReturnsEmptySoCallerShowsIndexOnly() {
        assertEquals("", LineLabelPolicy.qualitySuffix(0))
        assertEquals("", LineLabelPolicy.qualitySuffix(-1))
    }

    @Test
    fun standardHeightsMapToTheirTier() {
        assertEquals("2160P", LineLabelPolicy.qualitySuffix(2160))
        assertEquals("1440P", LineLabelPolicy.qualitySuffix(1440))
        assertEquals("1080P", LineLabelPolicy.qualitySuffix(1080))
        assertEquals("720P", LineLabelPolicy.qualitySuffix(720))
        assertEquals("480P", LineLabelPolicy.qualitySuffix(480))
    }

    @Test
    fun nonStandardHeightsRoundDownAndNeverOverstate() {
        // 1088 是 1080 档(不能报 1440P),1441 是 1440 档,719 只能算 576 档
        assertEquals("1080P", LineLabelPolicy.qualitySuffix(1088))
        assertEquals("1440P", LineLabelPolicy.qualitySuffix(1441))
        assertEquals("576P", LineLabelPolicy.qualitySuffix(719))
        assertEquals("1080P", LineLabelPolicy.qualitySuffix(1079))
    }

    @Test
    fun belowLowestTierReportsActualHeight() {
        assertEquals("240P", LineLabelPolicy.qualitySuffix(240))
        assertEquals("180P", LineLabelPolicy.qualitySuffix(180))
    }
}
