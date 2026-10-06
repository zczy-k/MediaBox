package com.github.tvbox.osc.ui.activity

/**
 * 线路标签的**显示**口径:序号 + 实测画质。
 *
 * <p>为什么不直接用站点的 flag 名:那是站点自报的字符串(常见"1080P""蓝光""高清"),
 * 与真实画质没有必然关系;更糟的是有些 flag 直接带站名/域名,连源身份一起泄露。
 * 而我们其实**有实测值**(见 `VideoQualityMemory`),拿真数据去顶掉假标签才是正解。
 *
 * <p>为什么"没测到就不显示画质":宁缺勿假。没有实测值的线路只给序号,用户看到"线路3"
 * 就知道这条还没数据,而不会误以为它真是某个清晰度。
 *
 * <p>纯逻辑,可直接 JVM 单测。
 */
internal object LineLabelPolicy {

    /** 标准清晰度档位(从高到低)。实测高度**向下取档**,保证只低报不高报。 */
    private val TIERS = intArrayOf(2160, 1440, 1080, 720, 576, 480, 360, 240)

    /**
     * 实测高度 → 画质后缀。
     *
     * @param measuredHeight 该线路的实测高度(像素);<=0 表示没测到
     * @return 如 "1080P";没测到时返回空串(调用方只显示线路序号)
     */
    fun qualitySuffix(measuredHeight: Int): String {
        if (measuredHeight <= 0) return ""
        for (tier in TIERS) {
            if (measuredHeight >= tier) return "${tier}P"
        }
        // 低于最低档(竖屏短视频类):照实报,不硬套档位
        return "${measuredHeight}P"
    }
}
