package com.github.tvbox.osc.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [EpisodeSetMath] 单测 —— 覆盖评审文档 17.2 场景 A-F 与区间编码。 */
class EpisodeSetMathTest {

    private fun set(vararg eps: Int) = eps.toSet()

    // ==================== 区间编码 ====================

    @Test
    fun `编码_连续段压缩`() {
        assertEquals("1-12,14", EpisodeSetMath.encode(set(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 14)))
        assertEquals("37", EpisodeSetMath.encode(set(37)))
        assertEquals("", EpisodeSetMath.encode(emptySet()))
    }

    @Test
    fun `解码_往返一致`() {
        val original = set(1, 2, 3, 10, 11, 12, 14)
        assertEquals(original, EpisodeSetMath.decode(EpisodeSetMath.encode(original)))
        assertEquals(emptySet<Int>(), EpisodeSetMath.decode(null))
        assertEquals(emptySet<Int>(), EpisodeSetMath.decode(""))
    }

    // ==================== 场景 A:完整源 ====================

    @Test
    fun `场景A_源A比源B覆盖率高`() {
        val a = set(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)
        val b = set(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        // 覆盖率以**并集**为参照(衡量"已发现内容谁更全"):A 12/12=100%,B 10/12≈83%
        val unionRef = EpisodeSetMath.union(listOf(a, b))
        assertEquals(set(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12), unionRef)
        val covA = SourceCompletenessPolicy.coverage(a, unionRef)
        val covB = SourceCompletenessPolicy.coverage(b, unionRef)
        assertTrue(covA > covB)
        // 缺集断言以**可信集**(≥2 源印证)为参照:11-12 仅单源观测 → 不断言 B 缺 11-12
        // (花开锦绣案例:单源自报的"第37项"不产生"缺第37集"误标)
        val confirmed = EpisodeSetMath.confirmed(listOf(a, b))
        assertEquals(set(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), confirmed)
        assertEquals(emptyList<Int>(), EpisodeSetMath.missing(a, confirmed))
        assertEquals(emptyList<Int>(), EpisodeSetMath.missing(b, confirmed))
    }

    // ==================== 场景 B:中间缺集 ====================

    @Test
    fun `场景B_并集口径可识别缺第9集_标注口径不误断言`() {
        val a = set(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)
        val b = set(1, 2, 3, 4, 5, 6, 7, 8, 10, 11, 12)
        // 数据层面(并集参照):B 缺第 9 集 —— max 模型的盲区在此消除,作为参考信息保留
        val unionRef = EpisodeSetMath.union(listOf(a, b))
        assertEquals(listOf(9), EpisodeSetMath.missing(b, unionRef))
        // 标注层面:可信集(≥2 源)不含 9(仅 A 单源观测)⇒ 不做"缺第9集"断言
        // —— 文档 6.3"参考集合尚未稳定时不产生缺集断言"与花开锦绣防污染一致
        val confirmed = EpisodeSetMath.confirmed(listOf(a, b))
        assertEquals(set(1, 2, 3, 4, 5, 6, 7, 8, 10, 11, 12), confirmed)
        assertEquals(emptyList<Int>(), EpisodeSetMath.missing(b, confirmed))
        // 连续性:B 的前缀只有 8,A 全连续 —— 作为标注与排序的参考
        assertEquals(8, EpisodeSetMath.continuity(b))
        assertEquals(12, EpisodeSetMath.continuity(a))
    }

    // ==================== 场景 C:特殊篇很多 ====================

    @Test
    fun `预告与花絮不入正片集(由Normalizer上游过滤)`() {
        // 上游(Normalizer)把预告/花絮分类掉后,传入的集合只含正片;
        // 源A 12正片+8预告 → 正片集仍是 12,不因条目总数大而占优
        val a = set(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)
        val b = set(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)
        val confirmed = EpisodeSetMath.confirmed(listOf(a, b))
        assertEquals(a, confirmed)
    }

    // ==================== 场景 D:不同季 ====================

    @Test
    fun `带季数的集号键含季_不与第一季混淆`() {
        // Normalizer 产出的集合键:有季数时=season*100000+episode
        val s1e12 = 100012
        val s2e1 = 200001
        assertTrue(EpisodeSetMath.missing(set(s1e12, s2e1), set(s1e12, s2e1)).isEmpty())
        // 只有第一季 12 集的源 vs 含第二季的源:第二季集号视为"可信集外的单源观测"
        val only1 = set(100001, 100002, 100003, 100004, 100005, 100006, 100007, 100008, 100009, 100010, 100011, 100012)
        val both = only1 + set(s2e1)
        val confirmed = EpisodeSetMath.confirmed(listOf(only1, both))
        // s2e1 单源观测 → 不入可信集 → 只1源不被误标缺集
        assertEquals(emptyList<Int>(), EpisodeSetMath.missing(only1, confirmed))
    }

    // ==================== 场景 E:只有一个源 ====================

    @Test
    fun `场景E_单源不产生可信参考集_不宣称全集`() {
        val confirmed = EpisodeSetMath.confirmed(listOf(set(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)))
        assertEquals(emptySet<Int>(), confirmed)
        // 无可信集 ⇒ coverage -1(不判) ⇒ 档位 UNKNOWN
        assertEquals(
            -1.0,
            SourceCompletenessPolicy.coverage(set(1, 2, 3), confirmed),
            0.0001,
        )
    }

    // ==================== 场景 F:无法识别 ====================

    @Test
    fun `场景F_不可识别条目不入集合_由上游保留原始条目`() {
        // Normalizer 对"TC中字"等返回 UNKNOWN ⇒ 集合不含它们;不作为缺集/存在证据
        val confirmed = EpisodeSetMath.confirmed(listOf(set(1, 2, 3)))
        assertTrue(confirmed.isEmpty())
    }

    // ==================== 花开锦绣回归:单源污染自愈 ====================

    @Test
    fun `单源37集污染不进入可信集_36集源不再被误标`() {
        val polluted = set(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
            21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37)
        val normal = (1..36).toSet()
        val confirmed = EpisodeSetMath.confirmed(listOf(polluted, normal))
        // 37 只有单源观测 → 不入可信集 → 36 集的源无缺失标注
        assertEquals((1..36).toSet(), confirmed)
        assertEquals(emptyList<Int>(), EpisodeSetMath.missing(normal, confirmed))
    }

    // ==================== missingText ====================

    @Test
    fun `缺失展示串_单集与区间`() {
        assertEquals("9", EpisodeSetMath.missingText(listOf(9)))
        assertEquals("11-12", EpisodeSetMath.missingText(listOf(11, 12)))
        assertEquals("9、11-12", EpisodeSetMath.missingText(listOf(9, 11, 12)))
    }
}
