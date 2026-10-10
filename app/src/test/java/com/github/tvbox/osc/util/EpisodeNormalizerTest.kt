package com.github.tvbox.osc.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [EpisodeNormalizer] 单测 —— 覆盖评审文档 17.1 测试清单:
 * 常见格式解析、季数识别、特殊集识别、年份红线、无法识别保留 UNKNOWN。
 */
class EpisodeNormalizerTest {

    private fun n(name: String?) = EpisodeNormalizer.normalize(name)

    // ==================== REGULAR 常见格式 ====================

    @Test
    fun `第N集_第0N集_带空格均可解析`() {
        assertEquals(1, n("第1集").episode)
        assertEquals(1, n("第01集").episode)
        assertEquals(1, n("第 1 集").episode)
        assertEquals(EpisodeType.REGULAR, n("第1集").type)
        assertEquals(0.9, n("第1集").confidence, 0.001)
    }

    @Test
    fun `EP01与Episode1可解析`() {
        assertEquals(1, n("EP01").episode)
        assertEquals(1, n("Episode 1").episode)
    }

    @Test
    fun `纯序号与清晰度后缀可解析`() {
        assertEquals(1, n("01").episode)
        assertEquals(1, n("1").episode)
        assertEquals(1, n("01.1080p").episode)
    }

    @Test
    fun `第12话按集解析`() {
        assertEquals(12, n("第12话").episode)
    }

    // ==================== 季数 ====================

    @Test
    fun `S01E01解析出季与集`() {
        val info = n("S01E01")
        assertEquals(1, info.season)
        assertEquals(1, info.episode)
    }

    @Test
    fun `S02E01与S01E01是不同的集(季不能丢)`() {
        val s1 = n("S01E01")
        val s2 = n("S02E01")
        assertTrue(s1.season != s2.season)
        assertTrue(!(s1.season == s2.season && s1.episode == s2.episode))
    }

    @Test
    fun `第2季第3集解析出季与集`() {
        val info = n("第2季第3集")
        assertEquals(2, info.season)
        assertEquals(3, info.episode)
    }

    // ==================== 特殊条目 ====================

    @Test
    fun `预告与trailer归TRAILER_第37集预告也是预告(花开锦绣误标根治点)`() {
        assertEquals(EpisodeType.TRAILER, n("预告").type)
        assertEquals(EpisodeType.TRAILER, n("trailer").type)
        val info = n("第37集预告")
        assertEquals(EpisodeType.TRAILER, info.type)
    }

    @Test
    fun `花絮_幕后_特辑归BEHIND_SCENES`() {
        assertEquals(EpisodeType.BEHIND_SCENES, n("花絮").type)
        assertEquals(EpisodeType.BEHIND_SCENES, n("幕后花絮").type)
        assertEquals(EpisodeType.BEHIND_SCENES, n("NG镜头").type)
    }

    @Test
    fun `OVA_SP01_番外_特别篇归SPECIAL且不算正片第1集`() {
        assertEquals(EpisodeType.SPECIAL, n("SP01").type)
        assertEquals(EpisodeType.SPECIAL, n("OVA01").type)
        assertEquals(EpisodeType.SPECIAL, n("番外篇").type)
        assertEquals(EpisodeType.SPECIAL, n("特别篇").type)
        assertTrue(n("SP01").episode != 1)
    }

    // ==================== 红线:年份与无法识别 ====================

    @Test
    fun `带年份的标题不得把年份当集数`() {
        assertEquals(EpisodeType.UNKNOWN, n("潜伏2010").type)
        assertEquals(EpisodeType.UNKNOWN, n("2010").type)
    }

    @Test
    fun `集名不可数的形态归UNKNOWN(TC中字_超级无敌4K_正片)`() {
        assertEquals(EpisodeType.UNKNOWN, n("TC中字").type)
        assertEquals(EpisodeType.UNKNOWN, n("超级无敌4K").type)
        assertEquals(EpisodeType.UNKNOWN, n("正片").type)
        assertEquals(EpisodeType.UNKNOWN, n("HD").type)
    }

    @Test
    fun `合并条目解析为起始集但置信度降低`() {
        val info = n("第1-2集")
        assertEquals(1, info.episode)
        assertTrue(info.confidence < 0.9)
    }

    @Test
    fun `LRU缓存幂等_同输入同输出`() {
        val a = n("第41集")
        val b = n("第41集")
        assertEquals(a, b)
    }
}
