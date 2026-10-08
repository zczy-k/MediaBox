package com.github.tvbox.osc.util

/** 一条进度值的处置结论:落盘 / 不写 */
enum class WatchDecision { SAVE, SKIP }

/**
 * "这条进度值不值得记住"的唯一判据:续播点、历史页百分比、"看过"门槛三处共用 ——
 * 各自定阈值必然漂移出"卡片显示看过、点进去却从头"。纯 JVM,边界见 [WatchProgressRulesTest]。
 */
object WatchProgressRules {

    /** 续播点最小位置(毫秒);时长未知时用绝对值 */
    const val MIN_RESUME_MS = 30_000L

    /** 续播点最小比例(%);与 [MIN_RESUME_MS] 取更小者,让短视频也能留下续播点 */
    const val MIN_RESUME_PERCENT = 30L

    /** 到这个比例即视为看完:百分比照常留到 100%,续播点由播出结束的清除路径负责 */
    const val FINISHED_PERCENT = 95L

    fun decide(positionMs: Long, durationMs: Long): WatchDecision {
        if (positionMs <= 0) return WatchDecision.SKIP
        if (durationMs > 0 && positionMs * 100 >= durationMs * FINISHED_PERCENT) return WatchDecision.SAVE
        val minResume = if (durationMs > 0) {
            minOf(MIN_RESUME_MS, durationMs * MIN_RESUME_PERCENT / 100)
        } else {
            MIN_RESUME_MS
        }
        return if (positionMs >= minResume) WatchDecision.SAVE else WatchDecision.SKIP
    }

    /** "真看过":进观看历史与记集数的门槛。与 SAVE 同源 —— 看完的那一集当然也算看过 */
    fun shouldRemember(positionMs: Long, durationMs: Long): Boolean =
        decide(positionMs, durationMs) != WatchDecision.SKIP

    /**
     * 换线/换源继承位置的**下限**(毫秒)。
     *
     * <p>为什么不能沿用 [decide] 的 30 秒门槛:那条门槛回答的是"这个位置值不值得记成续播点",
     * 而换线/换源问的是"用户刚才明明在看,只是换了个来源"—— 这是显式意图,只要位置为正就该接着看。
     * 套 30 秒门槛会让"刚看开头就切线"变成从头播,与"换线不打断观看"的预期直接冲突。
     */
    const val MIN_INHERIT_MS = 3_000L

    /** 换线/换源是否把该位置继承给新的进度键([WatchProgressStore.inherit] 的唯一判据) */
    fun shouldInherit(positionMs: Long): Boolean = positionMs >= MIN_INHERIT_MS
}
