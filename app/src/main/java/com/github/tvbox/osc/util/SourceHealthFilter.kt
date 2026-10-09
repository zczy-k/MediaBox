package com.github.tvbox.osc.util

import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.bean.SourceBean

/**
 * 「被屏蔽的源」在**消费侧**的唯一判据出口(防滥用封禁机制,P2 过滤层)。
 *
 * <p>为什么要单独一层:屏蔽态由 [SourceHealthMemory] 持有,而"要不要用它"在不同位置口径不同 ——
 * 自动选源池要滤掉、搜索结果的**卡片**要留、首页卡片要按"还有没有别的源"决定藏不藏。
 * 三处各写一遍 if,迟早漂移成"某处还能选到已屏蔽的源"。所以判断只在这里。
 *
 * <p>两条硬约束(踩了就是真机可见的坏体验):
 * <ul>
 *   <li>**fail-open**:整池被滤空时回退到原列表。否则一旦台账误判,用户会变成"一个源都搜不到"
 *       —— 比"偶尔选到坏源"严重得多;</li>
 *   <li>**开关关闭时彻底放行**:所有判据先看 [SourceHealthMemory.isEnabled],关掉就该和没这套机制一样。</li>
 * </ul>
 */
object SourceHealthFilter {

    /** 单个源是否允许进入自动选源池(搜索池 / 详情自动换源池 / 源清单) */
    @JvmStatic
    fun isAllowed(sourceKey: String?): Boolean {
        if (!SourceHealthMemory.isEnabled()) return true
        val key = sourceKey?.trim().orEmpty()
        if (key.isEmpty()) return true
        return !SourceHealthMemory.isBlocked(key)
    }

    /** 当前屏蔽集合(调用方批量过滤时先取一次,避免逐源查台账) */
    @JvmStatic
    fun blockedKeys(): Set<String> =
        if (SourceHealthMemory.isEnabled()) SourceHealthMemory.blockedKeys() else emptySet()

    /**
     * 本轮需要**降权**的源集合(近期超时)。
     *
     * <p>与 [blockedKeys] 的区别是"降权 ≠ 屏蔽":这些源照搜,只是排到队尾、
     * 不占首轮快速源的并发额度。所以它**不需要** fail-open —— 排序最坏只是没起作用,
     * 不会让用户"搜不到"。
     */
    @JvmStatic
    fun penalizedKeys(): Set<String> =
        if (SourceHealthMemory.isEnabled()) SourceHealthMemory.penalizedKeys() else emptySet()

    /**
     * 过滤源列表(首页换源 chip、搜索池、详情换源池共用)。
     *
     * <p>需求规则 4:被屏蔽的源必须从首页换源 chip 列表中过滤掉,不予展示。
     */
    @JvmStatic
    fun filter(beans: List<SourceBean>): List<SourceBean> {
        if (beans.isEmpty()) return beans
        val blocked = blockedKeys()
        if (blocked.isEmpty()) return beans
        val kept = beans.filterNot { blocked.contains(it.key) }
        if (kept.isEmpty()) {
            LOG.i("echo-srcban pool fail-open all-blocked n=" + beans.size)
            return beans
        }
        if (kept.size != beans.size) {
            LOG.i("echo-srcban pool kept=" + kept.size + "/" + beans.size)
        }
        return kept
    }

    /**
     * 首页卡片是否显示(需求规则 5)。
     *
     * <p>判据:提供该卡片的源没被屏蔽 ⇒ 显示;被屏蔽 ⇒ 只有**已知**还有别的没被屏蔽的源收录这部片
     * 才显示(那样卡片点进去仍能换源播出来),否则隐藏 —— 此时这张海报已经"无可用播放价值"。
     */
    @JvmStatic
    fun isCardVisible(providerSourceKey: String?, title: String?, blocked: Set<String>): Boolean {
        if (blocked.isEmpty()) return true
        val key = providerSourceKey?.trim().orEmpty()
        if (key.isEmpty() || !blocked.contains(key)) return true
        return SourceHealthMemory.hasUnblockedCarrier(title, blocked, key)
    }

    /**
     * 过滤首页列表(推荐位与各分区共用)。
     *
     * <p>⚠️ **全被隐空时回退原列表**:首页卡片只来自一个源(当前首页源),若它被屏蔽且这些片名
     * 没有跨源覆盖记录,按规则 5 会被整片隐掉 —— 首页变白板的代价远大于"显示了一张播不了的海报"。
     * 回退时留痕,便于真机对照。
     *
     * @param label 日志用标签(rec / sortId),便于区分是哪个渲染点
     */
    @JvmStatic
    fun filterHomeCards(
        videos: List<Movie.Video>,
        providerSourceKey: String?,
        label: String,
    ): List<Movie.Video> {
        if (videos.isEmpty()) return videos
        val blocked = blockedKeys()
        if (blocked.isEmpty()) return videos
        val provider = providerSourceKey?.trim().orEmpty()
        if (provider.isEmpty() || !blocked.contains(provider)) return videos
        val kept = videos.filter { isCardVisible(provider, it.name, blocked) }
        if (kept.isEmpty()) {
            LOG.i("echo-srcban home-cards fail-open label=" + label + " n=" + videos.size + " provider=" + provider)
            return videos
        }
        LOG.i("echo-srcban home-cards hidden=" + (videos.size - kept.size) + "/" + videos.size + " label=" + label)
        return kept
    }
}
