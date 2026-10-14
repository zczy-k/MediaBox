package com.github.tvbox.osc.ui.activity

/**
 * 换源链的**同名判定分级**(纯函数,可 JVM 单测,2026-10-14)。
 *
 * <p>## 它要补的漏洞
 * 详情页换源此前用一个二值判定 `TitleMatcher.isSameTitle` 决定"这条结果是不是这部剧",
 * 而搜索页早就有一套分档实现([SearchSettings.relevanceScore]:5 全等 / 4 前缀 / 3 包含 /
 * 2 每词都出现或剥长音符 / 1 仅别名 / 0 不匹配),还带别名与查询意图(年份/季数)修正。
 * 两套并存的后果是**换源比搜索页更容易漏**:归一化更弱(不删括注、无 NFKC/假名折叠),
 * 且"名字略差一点"的直接被丢掉而不是降级展示 —— 用户表现为"明明有源却搜不到"。
 *
 * <p>## 分级的意义
 * 换源是**拿别人的源播这一部片**,判错就是播放事故,所以不能像搜索页那样"全都列出来";
 * 但也不该像旧实现那样二值一刀切。折中:高分进候选(可自动起播),低分进"相关推荐"
 * (看得见、要用户自己点),零分丢弃。
 *
 * <p>阈值取自分档语义本身:3 分是"标题里真的包含关键词",2 分及以下都只是
 * "每个词都出现 / 别名相等 / 剥长音符才像" —— 这些作为换源候选风险高于收益。
 */
object CandidateMatchPolicy {

    /** 进换源候选的最低相关度(3 = 标题包含关键词及以上) */
    const val CANDIDATE_MIN_SCORE = 3

    /** 进"相关推荐"的最低相关度(1 = 别名相等 / 2 = 每词都出现或剥长音符) */
    const val RELATED_MIN_SCORE = 1

    enum class Verdict {
        /** 认定是同一部片:可入候选、可被自动换源链接管起播 */
        CANDIDATE,

        /** 只是"可能相关":列进相关推荐,绝不自动起播 */
        RELATED,

        /** 无关:丢弃 */
        DROP,
    }

    /**
     * 由相关度分档判定归属。
     *
     * <p>允许传**负数**(查询意图修正里的冲突惩罚,如年份不符),负数一律 DROP ——
     * 用户明说"就要 2019 版"时,2024 版不该混进候选。
     */
    @JvmStatic
    fun verdictOf(score: Int): Verdict = when {
        score >= CANDIDATE_MIN_SCORE -> Verdict.CANDIDATE
        score >= RELATED_MIN_SCORE -> Verdict.RELATED
        else -> Verdict.DROP
    }

    /** 是否与关键词指向同一部片(与搜索页同一份分档,不再另立一套归一化) */
    @JvmStatic
    fun isSameTitleScore(score: Int): Boolean = score >= CANDIDATE_MIN_SCORE
}
