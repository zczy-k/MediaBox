package com.github.tvbox.osc.player

/**
 * 迷你只读详情通道(P1)的**准入过滤**:决定哪些候选源值得被探测。
 *
 * <p>## 为什么必须过滤
 * P1 的收益是"提前知道对端画质",代价是**额外网络请求**。而实测源超时率 75~83% ——
 * 无脑对每个候选发详情请求,大概率是"探了、失败了、还拖慢了本机",纯负收益。
 * 所以入口先按**类型**筛一遍:**明知探不到的类型,一次请求都不发**。
 *
 * <p>## 各类的实测结论
 * <ul>
 *   <li><b>type 1(JSON 接口)</b>:覆盖最好 —— 普通 OkHttp 请求即可拿详情,支持。</li>
 *   <li><b>type 0(XML 接口)</b>:同样走接口,支持(解析路径与 type 1 只差 ac 参数)。</li>
 *   <li><b>type 4(extend 扩展)</b>:extend 需先解析;冷缓存时解析本身就要一次网络往返 +
 *       可能阻塞在 EXTEND_POOL 上 ⇒ **冷缓存直接跳过**;只有 extend 已热(进程内缓存命中)才探。</li>
 *   <li><b>type 3(Spider/crawler)</b>:必须占用 SPIDER_POOL(仅 3 线程),与用户当前的
 *       详情/播放取数抢线程 —— 为了"提前知道画质"而拖慢用户正在看的东西,不划算 ⇒ 不探。</li>
 *   <li><b>indexs == 1(索引型源)</b>:卡片只是搜索入口,压根没有详情接口 ⇒ 不探。</li>
 * </ul>
 *
 * <p>纯逻辑,可直接 JVM 单测。
 */
object MiniProbeGate {

    /** 一次请求都不该发的判定结果(带原因,便于日志核对"为什么没探") */
    sealed class Decision {
        /** 值得探:类型可控 + 未封禁 + 未探过 */
        object Allow : Decision() { override fun toString() = "allow" }

        /** 不发请求(原因只进日志,不进 UI) */
        data class Skip(val reason: String) : Decision() {
            override fun toString() = reason
        }
    }

    /**
     * 是否允许为 [sourceType] 的源发起迷你详情探测。
     *
     * @param sourceType `SourceBean.getType()`:0 XML / 1 JSON / 3 Spider / 4 extend
     * @param indexSource `SourceBean.isIndexSource()`:索引型源没有详情接口
     * @param alreadyUsed 该源就在当前播放链上(自己跟自己比画质没意义)
     * @param healthBlocked 该源已被健康台账封禁
     * @param extendWarm type 4 专用:其 extend 是否已在进程内缓存(冷则解析要额外往返)
     */
    @JvmStatic
    fun decide(
        sourceType: Int,
        indexSource: Boolean,
        alreadyUsed: Boolean,
        healthBlocked: Boolean,
        extendWarm: Boolean,
    ): Decision = when {
        alreadyUsed -> Decision.Skip("xhint-skip used")
        healthBlocked -> Decision.Skip("xhint-skip blocked")
        indexSource -> Decision.Skip("xhint-skip index-only")
        sourceType == 3 -> Decision.Skip("xhint-skip spider")
        sourceType == 4 && !extendWarm -> Decision.Skip("xhint-skip extend-cold")
        sourceType == 0 || sourceType == 1 || sourceType == 4 -> Decision.Allow
        else -> Decision.Skip("xhint-skip type$sourceType")
    }
}
