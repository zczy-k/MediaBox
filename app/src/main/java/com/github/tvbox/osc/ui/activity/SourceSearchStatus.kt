package com.github.tvbox.osc.ui.activity

/**
 * 详情页换源那一行的**措辞判据**(纯函数,可 JVM 单测,2026-10-16)。
 *
 * <p>## 要解决的毛病
 * 旧实现只在"搜索在跑"时打 `搜索进度(108/323)`,不跑就退回「点击搜索其他片源」。两条都不对:
 *
 * - **分母是假的**:自动兜底只问权重最高的 1/3([ExpandPoolPolicy] 首轮),命中就收口
 *   (真机 v1.1.47:`wave-done searched=108 hit=3` 之后 `all-done`,分母却一直写着 323)。
 *   用户读成"还剩 215 个没搜却停了",这正是他反复报的那族"进度不动"的观感。
 * - **把两种相反的状态说成同一句话**:搜完 0 个(《幸运有八只触手》问过 156 个源、列表仍空)
 *   和从没搜过,屏幕上都是「点击搜索其他片源」—— 前者该明说"没有",否则用户会一遍遍点。
 *
 * <p>## 口径
 * 开放式发现**只报已问数与发现数,不报总分** —— 总分是"这一轮真会问的池子大小",
 * 而它会随命中与否提前收口,写成 `x/323` 就是兑现不了的承诺。
 * 结果优先(有源就说有几个),状态次之(在搜就说问到第几个)。
 *
 * <p>四个事实已经够用,不需要第五个旋钮:`searchedOnce`(这场为这部片搜过吗)、
 * `searching`(正在搜吗)、`asked`(问到第几个源)、`found`(下面那一行有几个源)。
 *
 * <p>纯逻辑,无 IO / Android 依赖 —— 由 `SourceSearchStatusTest` 锁行为。
 */
object SourceSearchStatus {

    /** 这一行该说的六种话(资源文案在 UI 侧按此枚举挑选) */
    enum class Line {
        /** 还没为这部片搜过 ⇒ 给个入口 */
        NOT_SEARCHED,

        /** 刚启动、一个源都还没问到 */
        SEARCHING_START,

        /** 在搜、暂时还没有结果:报"问到第几个"证明它没死 */
        SEARCHING_NONE,

        /**
         * 在搜且已出候选:**两个数都给**。
         *
         * 真机 v1.1.53 的教训:这一格原本只报发现数(理由是一行里塞两个数像说明书、
         * 结果优先),于是用户点了「搜源」之后看到的是几分钟不动的"已找到 4 个来源"——
         * 日志里 `已问过` 其实从 48 一路走到 205。"有结果就不报进度"把唯一的活性信号砍掉了,
         * 和当初"停在 108/323 像卡死"是同一类观感问题,只是换了个表现。
         */
        SEARCHING_FOUND,

        /** 不在搜、列表里有源 */
        FOUND,

        /** 不在搜、且这场已经搜过 ⇒ 明说"没有",而不是又催他去搜 */
        NONE_FOUND,
    }

    /**
     * @param searchedOnce 这一场是否已为这部片发起过聚合搜索(换片即复位)
     * @param searching    聚合搜索是否正在跑
     * @param asked        已问过多少个源(0 表示还没开始有回包)
     * @param found        下面那一行**可见**的源数(与列表同口径:去重、排除正在播的那个)
     */
    fun line(searchedOnce: Boolean, searching: Boolean, asked: Int, found: Int): Line = when {
        searching && found > 0 -> Line.SEARCHING_FOUND
        searching && asked > 0 -> Line.SEARCHING_NONE
        searching -> Line.SEARCHING_START
        found > 0 -> Line.FOUND
        searchedOnce -> Line.NONE_FOUND
        else -> Line.NOT_SEARCHED
    }

    /**
     * 换源链彻底收口、页面进空态时,那句话该说"没有"还是"没问到"。
     *
     * <p>## 要解决的毛病(2026-10-18 真机现场)
     * 《潜伏6》收口的文案是「此影片暂无法播放」—— 而真实情况是:
     * 问过 58 个源、其中 **49 个超时**、33 个返回空响应、**只有 1 个答过话**。
     * 用户读到的意思接近"这部片不存在",而事实是"绝大多数源根本没被问到/没答上"。
     * 两者该做的事完全不同:前者只能换片,后者值得换个网络/时段重试。
     *
     * <p>判据只看**已问过的源里超时占了多大比例**,不看源池大小:
     * 源池大小与本轮失败无关("池子只有 20 个"不是失败的原因)。
     *
     * @param asked 本轮真正问过的源数
     * @param timedOut 其中判定为超时的源数(与权重台账同一口径:原词没答话)
     */
    @JvmStatic
    fun emptyReason(asked: Int, timedOut: Int): EmptyReason = when {
        asked <= 0 -> EmptyReason.NO_SOURCE
        // 过半超时 ⇒ 明显是"没问到",而不是"问到了都说没有"
        timedOut * 2 >= asked -> EmptyReason.TIMEOUT_DOMINANT
        else -> EmptyReason.NO_SOURCE
    }

    /** 空态该说哪一种话 */
    enum class EmptyReason {
        /** 源都答了话、确实没有这部片 */
        NO_SOURCE,

        /** 多数源超时/没答上 —— 网络或源站问题,不是"没有" */
        TIMEOUT_DOMINANT,
    }

    /**
     * 那颗按钮该写「换源」还是「搜源」。
     *
     * <p>按钮**从不"换"**什么 —— 换是点下面芯片做的([DetailViewModel.switchSource]),
     * 它只做一件事:发起/续跑全站搜源。所以只要满足任一条就写「搜源」:
     * ① 下面已经有源可点(用户 2026-10-16 的原始判据);
     * ② 这一场已经搜过一遍(此时它的本职只剩"再问一批进来")。
     * 只在"既没源、也还没搜过"时保留「换源」—— 那时点它确实是"换源"这件事的第一步。
     *
     * <p>为什么不能只用①:候选也能从上一场的缓存里直接种子进来(真机见过 `cache-seed n=2`
     * 而自动搜索被起播冻结),那种时刻列表有源却"从没搜过",只认②会写成「换源」;
     * 反过来只认①,搜完 0 个的那一行又永远停在「换源」,而它点了只是再搜一遍。
     */
    fun useSearchVerb(searchedOnce: Boolean, found: Int): Boolean = searchedOnce || found > 0
}
