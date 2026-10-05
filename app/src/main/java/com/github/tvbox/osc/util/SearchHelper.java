package com.github.tvbox.osc.util;

import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.SourceBean;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/**
 * 搜索源选择(哪些源参与全站搜索)。
 *
 * <p>存储:`SOURCES_FOR_SEARCH` 是一张「点播源地址 → {源 key → "1"}」的表 —— 每个源集合各自记住自己的勾选。
 * 表里没有当前地址的记录(或记录为空)= 没限制,搜索当前源集合的**全部可搜源**。
 */
public class SearchHelper {

    /**
     * 读取当前地址对应的搜索源选择。
     *
     * @return null 或空表 ⇒ 调用方按"全部可搜源"处理
     */
    public static HashMap<String, String> getSourcesForSearch() {
        HashMap<String, String> mCheckSources;
        try {
            String api = KV.get(HawkConfig.API_URL, "");
            if (api.isEmpty()) return null;
            HashMap<String, HashMap<String, String>> mCheckSourcesForApi =
                    KV.get(HawkConfig.SOURCES_FOR_SEARCH, new HashMap<>());
            mCheckSources = mCheckSourcesForApi.get(api);
        } catch (Exception e) {
            return null;
        }
        if (mCheckSources == null || mCheckSources.isEmpty()) {
            mCheckSources = getSources();
        }
        return mCheckSources;
    }

    /**
     * 判断一份选择是否还"对得上当前源列表"。
     *
     * <p>存在的理由(2026-09-13 修):选择是按**源 key** 记的,而源 key 属于**具体的源集合**。
     * 换了点播源之后,旧选择里的 key 在新源列表里基本都不存在 —— 若还拿它去过滤,结果就是
     * "只搜到新旧共有的那一个源"(用户实测:切源后只剩「玩偶4K」能搜到),重启才恢复。
     *
     * <p>判据:选择里的每个 key 都必须在当前源列表里存在。只要有一个对不上,这份选择就已过期。
     */
    public static boolean isSelectionStale(HashMap<String, String> checked) {
        Set<String> liveKeys = new HashSet<>();
        for (SourceBean bean : ApiConfig.get().getSourceBeanList()) {
            liveKeys.add(bean.getKey());
        }
        return isSelectionStale(checked, liveKeys);
    }

    /** 纯判定(与 Android 解耦,便于单测):选择是否已不匹配给定的源 key 集合 */
    public static boolean isSelectionStale(HashMap<String, String> checked, Set<String> liveSourceKeys) {
        if (checked == null || checked.isEmpty()) return false; // 没限制,谈不上过期
        for (String checkedKey : checked.keySet()) {
            if (!liveSourceKeys.contains(checkedKey)) return true;
        }
        return false;
    }

    /** 当前源集合里所有可搜源(key → "1"),用于"未限制"时的选择 */
    public static HashMap<String, String> getSources() {
        HashMap<String, String> mCheckSources = new HashMap<>();
        for (SourceBean bean : ApiConfig.get().getSourceBeanList()) {
            if (!bean.isSearchable()) {
                continue;
            }
            mCheckSources.put(bean.getKey(), "1");
        }
        return mCheckSources;
    }

}
