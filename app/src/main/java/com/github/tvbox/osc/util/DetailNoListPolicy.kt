package com.github.tvbox.osc.util

/**
 * "详情回包缺 list"按源记忆的**纯判定规则**(无 Android 依赖,可 JVM 单测)。
 *
 * <p>## 为什么要有这份规则
 * 索引型源(indexs=1)声明"只走搜索不进详情",但有些源(实测 js_douban)配置里没声明,
 * 详情请求却注定拿不到正片数据 —— 回包是**合法 JSON 但没有 list 字段**。每次点卡片
 * 都要白等一轮详情超时(实测 20~45 秒)才落到聚合搜索兜底。本规则定义"攒够几次证据
 * 才把该源按索引型对待"的门槛与证据有效期,存储与调用方见 [DetailNoListMemory]。
 *
 * <p>## 证据口径(与 [SourceHealthPolicy] 的失败证据刻意不同)
 * 只认"回包是合法 JSON 却缺 list"这一**应用层行为**:
 * 解析失败 / 空 body / 超时都**不是**本规则的证据 —— 那些是网络/健康问题,
 * 归 [SourceHealthMemory] 管;这里记的是"这个源的工作方式就是索引型"的画像,
 * 不封禁、不降权,只改取数路径(跳过详情,直接聚合搜索)。
 */
object DetailNoListPolicy {

    /** 30 天窗口内攒够几次"缺 list"才按索引型对待。1 次太少:偶发坏回包不该永久改路径 */
    const val STRIKES_TO_INDEX_LIKE = 2

    /** 单条证据的有效窗口:超过就算旧账,不再参与判定(源可能已改版) */
    const val STRIKE_TTL_MS: Long = 30L * 24 * 60 * 60 * 1000

    /** 每源最多保留几条证据(防脏数据把存储写爆) */
    const val MAX_STRIKES = 5

    /**
     * 记一票:淘汰过期证据 + 追加本次时间戳,最多留 [MAX_STRIKES] 条。
     * 纯函数:返回新列表,不改传入值。
     */
    fun record(strikes: List<Long>, now: Long): List<Long> {
        val fresh = strikes.filter { it > 0 && now - it <= STRIKE_TTL_MS }
        return (fresh + now).takeLast(MAX_STRIKES)
    }

    /** 是否达到"按索引型源对待"的门槛(窗口内证据 ≥ [STRIKES_TO_INDEX_LIKE]) */
    fun isIndexLike(strikes: List<Long>, now: Long): Boolean =
        strikes.count { it > 0 && now - it <= STRIKE_TTL_MS } >= STRIKES_TO_INDEX_LIKE

    /** 是否已无有效证据(全部过期/为空) —— 存储层据此清键瘦身 */
    fun hasNoFreshStrike(strikes: List<Long>, now: Long): Boolean =
        strikes.none { it > 0 && now - it <= STRIKE_TTL_MS }
}
