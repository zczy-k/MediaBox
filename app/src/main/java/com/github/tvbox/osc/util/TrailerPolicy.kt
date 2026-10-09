package com.github.tvbox.osc.util

import com.github.tvbox.osc.bean.Movie

/**
 * 「预告片不是正片」的**唯一判据**(纯函数,无 IO/Android 依赖 —— 由 TrailerPolicyTest 锁行为)。
 *
 * <p>## 解决什么
 *
 * 部分源对一部片只提供「预告片」这一条目。以前它会被当成**正片**走完整条播放链:
 * 播 2 秒 → 失败 → 换源链误以为"这个源有这部片"而反复选中它 → 再失败 → 再全网搜。
 * 真机实测(2026-10-09,js_douban《魔法少女小圆》):同一部片在 43 秒内循环了三轮。
 *
 * <p>产品口径(用户拍板):**只找到预告片 = 视同"没有这部片"**,继续找正片;最终找不到就提示"暂无片源"。
 * 且预告片**绝不以正片形式呈现** —— 所以剔除动作发生在分集列表进 [com.github.tvbox.osc.bean.VodInfo] 之前。
 *
 * <p>## 两条必须守住的边界
 *
 * <ul>
 *   <li>**不能只看"只有一条"**:电影本来就是单集,只看条数会把全部电影误判成预告片。
 *       必须是"唯一一条 **且** 集名命中词表"。</li>
 *   <li>**认不出就当正片(fail-open)**:源站把预告片命名成 `01`、`HD` 时判不出来,
 *       此时退回旧行为(当作正片)。宁可漏判,不可把真电影判成"没资源"。</li>
 * </ul>
 */
internal object TrailerPolicy {

    /**
     * 子串命中的"非正片"标记。都是中文语境的完整词或足够长的英文词,
     * 不放单字母/短词(如 `pv` 单独放进来会误伤标题里含 pv 的正片)。
     */
    private val CONTAINS_MARKS =
        listOf("预告", "片花", "特报", "先导", "花絮", "trailer", "teaser", "preview")

    /** `pv` 只认**开头**:中文媒体常把预告影像叫 PV1/PV2,但正片标题里出现 pv 的位置不可预测 */
    private const val PV_PREFIX = "pv"

    /**
     * 这条集名是不是"非正片"。
     *
     * <p>归一化复用 [SearchSettings.normalize] 的口径(全角转半角 → 删括注 → 删空白标点 → 小写),
     * 与搜索匹配同一套,避免两处各写一套导致漂移。括注会被删掉,
     * 所以「预告片(2026)」能命中。
     *
     * <p>⚠️ 但归一化**删括注**有一个反向副作用:整个集名都写在括号里时(源站真会这么写,
     * 如「【先导预告】」),归一化结果是**空串** ⇒ 只看归一化文本就会漏判。
     * 所以归一化判定为空/未命中时,再对**原文**(小写)做一次同样的判定兜底。
     */
    fun isTrailerName(name: String?): Boolean {
        if (name.isNullOrEmpty()) return false
        val normalized = SearchSettings.normalize(name)
        if (normalized.isNotEmpty()) {
            if (CONTAINS_MARKS.any { normalized.contains(it) }) return true
            if (normalized.startsWith(PV_PREFIX)) return true
        }
        val raw = name.trim().lowercase(java.util.Locale.ROOT)
        if (CONTAINS_MARKS.any { raw.contains(it) }) return true
        return raw.startsWith(PV_PREFIX)
    }

    /**
     * 就地剔除 [video] 分集里的"非正片"条目。
     *
     * @return 剔除后是否**还剩至少一条正片**。false = 这个源对这部片没有正片可播,
     *         调用方应把它视同"没有这部片"继续换源。
     *
     * <p>⚠️ **就地修改**的前提:调用方只在**详情回包 freshly 解析出来的** video 上调用
     * (`onDetailResult` 里的 `videoList[0]`),该对象没有别的持有者;
     * 搜索结果列表里的 Video 绝不能拿进来改,否则会改坏搜索页正在展示的内容。
     *
     * <p>多条线路(UrlInfo)逐条剔;全部剔空时 `beanList` 置空列表而不是 null,
     * 让 `VodInfo.setVideo` 的 `beanList.size() > 0` 判空自然跳过它。
     */
    fun stripTrailers(video: Movie.Video?): Boolean {
        if (video == null) return false
        val infoList = video.urlBean?.infoList ?: return false
        var remaining = 0
        for (urlInfo in infoList) {
            val beans = urlInfo.beanList ?: continue
            val real = beans.filter { !isTrailerName(it.name) }
            if (real.isNotEmpty()) remaining += real.size
            urlInfo.beanList = ArrayList(real)
        }
        return remaining > 0
    }
}
