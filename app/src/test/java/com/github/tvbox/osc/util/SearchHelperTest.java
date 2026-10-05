package com.github.tvbox.osc.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/**
 * 搜索源选择"是否过期"的判定单测(2026-09-13 回归)。
 *
 * <p>缺陷背景:该选择按**源 key** 记,而源 key 属于具体的源集合。换点播源后旧 key 在新源列表里不存在,
 * 若仍拿它过滤,搜索会被悄悄窄化到"新旧源共有的那几个源"(用户实测:切源后只剩「玩偶4K」可搜,重启恢复)。
 */
public class SearchHelperTest {

    private static Set<String> keys(String... keys) {
        return new HashSet<>(java.util.Arrays.asList(keys));
    }

    private static HashMap<String, String> checked(String... keys) {
        HashMap<String, String> map = new HashMap<>();
        for (String key : keys) map.put(key, "1");
        return map;
    }

    @Test
    public void nullOrEmptySelection_isNeverStale() {
        // 没限制(搜索全部可搜源)⇒ 与源集合无关,永远谈不上过期
        assertFalse(SearchHelper.isSelectionStale(null, keys("a", "b")));
        assertFalse(SearchHelper.isSelectionStale(new HashMap<>(), keys("a", "b")));
        // 源列表为空时也不该把"没限制"误判成过期
        assertFalse(SearchHelper.isSelectionStale(null, keys()));
    }

    @Test
    public void selectionMatchesCurrentSources_isNotStale() {
        assertFalse(SearchHelper.isSelectionStale(checked("a", "b"), keys("a", "b", "c")));
    }

    @Test
    public void selectionFromAnotherSourceSet_isStale() {
        // 换源后:选择里是旧源的 key,当前源列表换成了另一批
        assertTrue(SearchHelper.isSelectionStale(checked("old1", "old2"), keys("new1", "new2")));
    }

    @Test
    public void partiallyMatchingSelection_isStale() {
        // 用户实测形态:新旧源共有一个 key(如"玩偶4K"),其余全对不上。
        // 只要有一个 key 对不上就必须判过期 —— 否则搜索被窄化到只剩那个共有源
        assertTrue(SearchHelper.isSelectionStale(checked("shared", "oldOnly"), keys("shared", "newOnly")));
    }

    @Test
    public void selectionWithAllKeysUnknown_isStale() {
        assertTrue(SearchHelper.isSelectionStale(checked("x"), keys()));
    }
}
