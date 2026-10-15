package com.github.tvbox.osc.ui.activity

/**
 * 候选源列表的**分页判据**(纯函数,可 JVM 单测,2026-10-14)。
 *
 * <p>## 背景(用户终稿设计)
 * 详情页「换源」按钮点击后启动搜索,结果**边搜边出**;候选源按**每页 5 个**显示,
 * 用户用「上一页/下一页」翻页浏览 —— **不排序**(自然顺序),每个源都显示评分
 * (有数据显"XX分"、无数据显"暂无评分")。
 *
 * <p>## 为什么不用「继续换源」按钮
 * 早期设想是"点『继续换源』再显示下一批 5 个"。但该按钮语义模糊(继续搜?还是翻一页?)。
 * 改为**纯翻页**后语义清晰,且搜索**后台跑全量**(不受翻页影响),用户随时可双向浏览已搜到的源。
 *
 * <p>## 与"搜索"的关系(本判据不碰搜索)
 * 分页只决定"**已搜到的列表怎么切页显示**",与"搜索进度"解耦:
 * 搜索跑全量(3~5 分钟)期间,列表**随搜随长**,总页数随之增加;翻页越界由 [clampPage] 钳制。
 *
 * <p>纯逻辑,无 IO / Android 依赖 —— 由 `CandidatePagerTest` 锁行为。
 */
object CandidatePager {

    /** 每页显示的候选源数 */
    const val PAGE_SIZE = 5

    /**
     * 总页数(向上取整)。
     *
     * @param total 候选源总数;<=0 返回 0(无页可翻)
     */
    fun pageCount(total: Int): Int {
        if (total <= 0) return 0
        return (total + PAGE_SIZE - 1) / PAGE_SIZE
    }

    /**
     * 取第 [page] 页(0 起)的切片。越界页返回空列表(调用方负责用 [clampPage] 钳制后再取)。
     *
     * @param list 全量候选(自然顺序)
     * @param page 页号(0 起);负数视为 0
     */
    fun <T> pageSlice(list: List<T>, page: Int): List<T> {
        if (list.isEmpty()) return emptyList()
        val p = page.coerceAtLeast(0)
        val from = p * PAGE_SIZE
        if (from >= list.size) return emptyList()
        val to = (from + PAGE_SIZE).coerceAtMost(list.size)
        return list.subList(from, to)
    }

    /** 是否存在下一页(用于决定是否显示「下一页」按钮)。 */
    fun hasNext(total: Int, page: Int): Boolean = page.coerceAtLeast(0) < pageCount(total) - 1

    /**
     * 候选条目 → **真正参与分页的源序列**(排除当前源 + 按源去重)。
     *
     * <p>⚠️ 分页的总数必须**只**由本结果的 size 决定。真机 v1.1.40 抓到分叉:
     * 界面用**未去重的条目数**算「有没有下一页」,而那一行 chip 是按**去重后的源**渲染的 ——
     * 一部片在同一个源里返回多张卡片(国语/粤语/不同 id)时,条目 25 / 源 3,
     * 于是"只有 3 个源却显示下一页",点下去又被 [clampPage] 钳回第一页 ⇒ 看着像按钮坏了。
     *
     * <p>与旧内联写法严格一致:`sourceKey` 为 null 的条目**保留**(它不等于当前源 key),
     * 而 [distinctBy] 会把所有 null 归为同一组只留第一条 —— 改这里的语义会连带改变候选数量。
     */
    fun <T> pageableSources(candidates: List<T>, currentSourceKey: String?, keyOf: (T) -> String?): List<T> =
        candidates.asSequence()
            .filter { keyOf(it) != currentSourceKey }
            .distinctBy { keyOf(it) }
            .toList()

    /** 是否存在上一页(用于决定是否显示「上一页」按钮)。 */
    fun hasPrev(page: Int): Boolean = page > 0

    /**
     * 把页号钳制到合法范围(列表变长/变短后,当前页可能越界)。
     *
     * <p>例:用户翻到第 3 页后切走又回来,列表可能只剩 2 页 ⇒ 必须钳回最后一页,
     * 否则显示空白(用户以为"没有源")。
     */
    fun clampPage(page: Int, total: Int): Int {
        val pages = pageCount(total)
        if (pages <= 0) return 0
        return page.coerceIn(0, pages - 1)
    }
}