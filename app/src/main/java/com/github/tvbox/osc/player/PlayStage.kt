package com.github.tvbox.osc.player

/**
 * 一次起播请求走到哪一段(2026-10-15 分段计时)。
 *
 * <p>**为什么要分段**:"获取播放信息"不是一个耗时,是好几个耗时,而每一个指向不同的责任人。
 * 原来一把秒表(取流 6~9 秒、起播 7 秒)盖住全程,到点就换线并按"这条线路失败一次"记账 ——
 * 于是站点不答话、我们自己在拉 m3u8、这条流真的坏了,三种病被当成同一种治,
 * 而且慢的那个还被冤枉。分段之后,每段才能各自决定:该给多少耐心、该不该算到线路头上。
 *
 * <p>**能分到的边界**(刻意不假装能分更细的):
 * <ul>
 *   <li>{@link #SPIDER_WAIT}:向爬虫要地址的请求还悬着。⚠️ 这一段**内部不可再分** ——
 *       Python 爬虫是一次阻塞的 `callAttr`,HTTP 发生在 Python 里用 requests 发,Java 只见进出的边界;
 *       JS 只有走内置 `req` 才看得见网络层。所以这里只报"等了多久",不报"发出去没有、
 *       回包没有" —— 填一个看不见的数字比不填更坏。</li>
 *   <li>{@link #LOCAL_HANDOFF}:爬虫答了,地址还在本地链路(解析、抽取、跨源判断)。</li>
 *   <li>{@link #PLAYER_START}:地址已经交给内核,只剩"这个流能不能出画面" ——
 *       唯一能真正证明线路本身有问题的一段。</li>
 * </ul>
 *
 * <p>曾经的第四段 {@code PURIFY_IN_FLIGHT}(m3u8 去广告自己在拉播放列表)随净化链整条撤除:
 * 它没有可靠证据分级、拉回来概率近零,却会把起播预算吃掉 —— 一段测不到东西的段不值得留着。
 */
enum class PlayStage {
    SPIDER_WAIT,
    LOCAL_HANDOFF,
    PLAYER_START;

    /**
     * 这一段的延迟能不能算到**线路**头上。
     *
     * <p>只有地址交给内核之后(PLAYER_START),不出现画面才是这条流的问题。
     * 前面两段慢的是站点、是本地链路 —— 把它们记成"线路失败",
     * 会让一条本来好好的线因为"这个站今天很慢"被越排越后,再也轮不到。
     */
    fun blamesTheLine(): Boolean = this == PLAYER_START

    /** 日志里的短名(诊断包要看的就是"卡在哪一段") */
    fun tag(): String = when (this) {
        SPIDER_WAIT -> "spider-wait"
        LOCAL_HANDOFF -> "local-handoff"
        PLAYER_START -> "player-start"
    }
}

/** 从已有的标记推出当前阶段(纯函数,好测;标记本身在 PlaybackAttemptState 上) */
object PlayStages {

    /**
     * @param resolvePending 取流请求还悬着(没被接受)⇒ 爬虫还没答话
     * @param kernelGotUrl 地址已定稿并排队交给内核
     */
    @JvmStatic
    fun of(resolvePending: Boolean, kernelGotUrl: Boolean): PlayStage = when {
        // 爬虫没答话时后面一切都没开始,优先判它
        resolvePending -> PlayStage.SPIDER_WAIT
        kernelGotUrl -> PlayStage.PLAYER_START
        else -> PlayStage.LOCAL_HANDOFF
    }
}
