package com.github.tvbox.osc.util;

/**
 * 订阅配置里"这条正则是在匹配 m3u8 清单标签吗"的判别，供 ConfigApplier 把站点 regex
 * 分流成解析规则与清单标签排除规则（后者写进 {@code VideoParseRuler.addHostRegex}）。
 *
 * <p>2026-10-16：m3u8 去广告净化链整条撤除，本类此前 900 多行的 purify 及其广告规则表已删除 ——
 * 它们是无证据分级的纯启发式（时长、URL 关键词、帧率指纹），唯一调用方也已不存在。
 * 这里只保留仍然有消费者的这一件事。
 */
public class M3u8 {
    private static final String TAG_DISCONTINUITY = "#EXT-X-DISCONTINUITY";
    private static final String TAG_MEDIA_DURATION = "#EXTINF";
    private static final String TAG_ENDLIST = "#EXT-X-ENDLIST";
    private static final String TAG_KEY = "#EXT-X-KEY";
    private static final String TAG_CUE_OUT = "#EXT-X-CUE-OUT";
    private static final String TAG_CUE_IN = "#EXT-X-CUE-IN";
    private static final String TAG_DATERANGE = "#EXT-X-DATERANGE";

    /** 命中任一清单标签、或本身是个非零数字 ⇒ 它匹配的是清单内容，不是站点解析规则 */
    public static boolean isAd(String regex) {
        return regex.contains(TAG_DISCONTINUITY) || regex.contains(TAG_MEDIA_DURATION)
                || regex.contains(TAG_ENDLIST) || regex.contains(TAG_KEY)
                || regex.contains(TAG_CUE_OUT) || regex.contains(TAG_CUE_IN)
                || regex.contains(TAG_DATERANGE) || isDouble(regex);
    }

    private static boolean isDouble(String ad) {
        try {
            return Double.parseDouble(ad) != 0;
        } catch (Exception e) {
            return false;
        }
    }
}
