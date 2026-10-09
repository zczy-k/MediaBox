package com.github.tvbox.osc.util

/**
 * 线路/源自报标签的**质量档位**分类(P-质量维度,2026-10-10;纯函数,可 JVM 单测)。
 *
 * <p>## 定位与边界
 * 自报标签(flag 名/note)是"站点说了什么",不是实测真相 —— 常有夸大("超级无敌4K"实际 536P)。
 * 因此本档位只做**排序先验**:实测画质记忆存在时永远由实测主导(见 LineQualitySelector);
 * 本档位仅在"无实测数据"时提供相对偏好,且**只影响排序,不影响显示与可达性**
 * (显示走 LineLabelPolicy 实测口径;自报名依旧不展示 —— 源身份匿名铁律)。
 *
 * <p>## 判定顺序
 * **TC 先于 HIGH**:"HDTC"同时包含 hd 与 tc,先判 TC 才能正确归为抢先版。
 */
object QualityLabelPolicy {

    /** HIGH=标称高清/4K;PLAIN=无标注;TC=抢先版。权重 HIGH > PLAIN > TC */
    enum class Tier { HIGH, PLAIN, TC }

    private val TC_PATTERNS = listOf("tc", "抢先", "枪版", "hdtc", "cam", "ts版")
    private val HIGH_PATTERNS = listOf(
        "4k", "2160", "1080", "720", "蓝光", "bluray", "超清", "高清", "bd", "hd", "hq",
    )

    /** 从自报标签解析质量档位;空白/无法识别一律 PLAIN(中性) */
    fun tierFromLabel(label: String?): Tier {
        val s = label?.trim()?.lowercase() ?: return Tier.PLAIN
        if (s.isEmpty()) return Tier.PLAIN
        // TC 先判:"HDTC"含 hd,必须先被 TC 拦截
        if (TC_PATTERNS.any { s.contains(it) }) return Tier.TC
        if (HIGH_PATTERNS.any { s.contains(it) }) return Tier.HIGH
        return Tier.PLAIN
    }

    /** 排序用权重值:HIGH=2 > PLAIN=1 > TC=0 */
    fun priority(tier: Tier): Int = when (tier) {
        Tier.HIGH -> 2
        Tier.PLAIN -> 1
        Tier.TC -> 0
    }

    /** 便捷入口:直接从标签取权重值 */
    fun priorityFromLabel(label: String?): Int = priority(tierFromLabel(label))
}
