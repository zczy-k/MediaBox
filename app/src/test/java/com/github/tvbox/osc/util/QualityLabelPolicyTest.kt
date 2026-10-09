package com.github.tvbox.osc.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** [QualityLabelPolicy] 纯函数单测:TC 优先、高清识别、中性兜底、权重序。 */
class QualityLabelPolicyTest {

    @Test
    fun `TC优先于HD_HDTC归为抢先版而非高清`() {
        assertEquals(
            QualityLabelPolicy.Tier.TC,
            QualityLabelPolicy.tierFromLabel("HDTC抢先版"),
        )
        assertEquals(
            QualityLabelPolicy.Tier.TC,
            QualityLabelPolicy.tierFromLabel("TC中字"),
        )
    }

    @Test
    fun `高清与4K自报归HIGH(大小写不敏感)`() {
        assertEquals(QualityLabelPolicy.Tier.HIGH, QualityLabelPolicy.tierFromLabel("超级无敌4K"))
        assertEquals(QualityLabelPolicy.Tier.HIGH, QualityLabelPolicy.tierFromLabel("4k专线2️⃣"))
        assertEquals(QualityLabelPolicy.Tier.HIGH, QualityLabelPolicy.tierFromLabel("高清蓝光4K"))
        assertEquals(QualityLabelPolicy.Tier.HIGH, QualityLabelPolicy.tierFromLabel("B超高清"))
        assertEquals(QualityLabelPolicy.Tier.HIGH, QualityLabelPolicy.tierFromLabel("bd1080p"))
    }

    @Test
    fun `无标注或无法识别归PLAIN`() {
        assertEquals(QualityLabelPolicy.Tier.PLAIN, QualityLabelPolicy.tierFromLabel(null))
        assertEquals(QualityLabelPolicy.Tier.PLAIN, QualityLabelPolicy.tierFromLabel(""))
        assertEquals(QualityLabelPolicy.Tier.PLAIN, QualityLabelPolicy.tierFromLabel("线路四 (勿信广告)"))
        assertEquals(QualityLabelPolicy.Tier.PLAIN, QualityLabelPolicy.tierFromLabel("正片"))
    }

    @Test
    fun `权重序 HIGH大于PLAIN大于TC`() {
        val high = QualityLabelPolicy.priorityFromLabel("超级无敌4K")
        val plain = QualityLabelPolicy.priorityFromLabel("正片")
        val tc = QualityLabelPolicy.priorityFromLabel("TC中字")
        assertEquals(2, high)
        assertEquals(1, plain)
        assertEquals(0, tc)
        org.junit.Assert.assertTrue(high > plain && plain > tc)
    }
}
