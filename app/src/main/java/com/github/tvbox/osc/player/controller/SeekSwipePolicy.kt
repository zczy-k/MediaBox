package com.github.tvbox.osc.player.controller

/**
 * 横滑快进的跨度口径 —— **满屏滑过的秒数按片长自适应**。
 *
 * <p>旧口径是写死的 240 秒/满屏:40 分钟的剧集刚好,2 小时的电影要滑 30 次满屏才能从头到尾,
 * 用户体感就是"快进很慢"。改成按时长比例后,长短片的手感一致。
 *
 * <p>两端要夹住:
 * <ul>
 *   <li>下限 60 秒 —— 短片/时长未知(直播、还没出 duration)时,一次滑动至少要有意义;</li>
 *   <li>上限 15 分钟 —— 超长片(4 小时直播回放)不许一次滑掉半小时,否则落点完全不可控。</li>
 * </ul>
 *
 * <p>精确落点本来就不该由手势负责(进度条可拖、长按倍速另有档),手势只管粗调。
 */
object SeekSwipePolicy {

    /** 满屏 = 时长的 1/8(12.5%) */
    const val FULL_SWIPE_DIVISOR = 8.0

    /** 一次满屏最少跨多少秒 */
    const val MIN_FULL_SWIPE_MS = 60_000L

    /** 一次满屏最多跨多少秒(15 分钟) */
    const val MAX_FULL_SWIPE_MS = 15L * 60L * 1000L

    /**
     * @param durationMs 片长(ms);<=0(时长未知)按最短档返回 [MIN_FULL_SWIPE_MS]
     */
    fun fullSwipeSpanMs(durationMs: Long): Long {
        if (durationMs <= 0L) return MIN_FULL_SWIPE_MS
        return (durationMs / FULL_SWIPE_DIVISOR)
            .coerceIn(MIN_FULL_SWIPE_MS.toDouble(), MAX_FULL_SWIPE_MS.toDouble())
            .toLong()
    }
}
