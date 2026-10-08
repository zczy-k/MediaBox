package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.github.tvbox.osc.bean.VodInfo;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * EpisodeMatcher 单测:锁住匹配算法行为(换线选集/同名集号匹配)。
 */
public class EpisodeMatcherTest {

    private static VodInfo.VodSeries series(String name) {
        return new VodInfo.VodSeries(name, "http://example/" + name);
    }

    private static List<VodInfo.VodSeries> seriesList(String... names) {
        List<VodInfo.VodSeries> list = new ArrayList<>();
        for (String name : names) list.add(series(name));
        return list;
    }

    private static VodInfo vod(List<String> flagNames, String... mapFlags) {
        VodInfo vod = new VodInfo();
        vod.seriesFlags = new ArrayList<>();
        for (String flag : flagNames) vod.seriesFlags.add(new VodInfo.VodSeriesFlag(flag));
        vod.seriesMap = new LinkedHashMap<>();
        for (String flag : mapFlags) vod.seriesMap.put(flag, seriesList("第1集"));
        return vod;
    }

    // ---------- extractEpisodeNumber ----------

    @Test
    public void episodeNumber_fromCommonNameForms() {
        assertEquals(12, EpisodeMatcher.extractEpisodeNumber("第12集"));
        assertEquals(12, EpisodeMatcher.extractEpisodeNumber("第 12 集"));
        assertEquals(12, EpisodeMatcher.extractEpisodeNumber("EP12"));
        assertEquals(12, EpisodeMatcher.extractEpisodeNumber("E12"));
        assertEquals(12, EpisodeMatcher.extractEpisodeNumber("12"));
    }

    @Test
    public void episodeNumber_ignoresYearBracketsAndQuality() {
        assertEquals(5, EpisodeMatcher.extractEpisodeNumber("[1080P] 第5集"));
        assertEquals(8, EpisodeMatcher.extractEpisodeNumber("影片名 (2024) 第08集"));
        assertEquals(1000, EpisodeMatcher.extractEpisodeNumber("名侦探柯南 2024 第1000集"));
        assertEquals(2, EpisodeMatcher.extractEpisodeNumber("S01E02"));
    }

    @Test
    public void episodeNumber_withoutNumber_isMinusOne() {
        assertEquals(-1, EpisodeMatcher.extractEpisodeNumber(null));
        assertEquals(-1, EpisodeMatcher.extractEpisodeNumber(""));
        assertEquals(-1, EpisodeMatcher.extractEpisodeNumber("抢先版"));
        // 既有行为:全角括号不在剔除规则内,"1080p" 被剔掉后无数字可提取
        assertEquals(-1, EpisodeMatcher.extractEpisodeNumber("【测试】1080P"));
    }

    // ---------- episodeMatchScore ----------

    @Test
    public void matchScore_exactNameWins() {
        assertEquals(100, EpisodeMatcher.episodeMatchScore("第3集", 3, "第3集"));
        assertEquals(100, EpisodeMatcher.episodeMatchScore("Final", -1, "final"));
    }

    @Test
    public void matchScore_sameEpisodeNumber() {
        assertEquals(80, EpisodeMatcher.episodeMatchScore("第3集", 3, "第3话"));
        assertEquals(0, EpisodeMatcher.episodeMatchScore("第3集", 3, "第5集"));
    }

    @Test
    public void matchScore_containsNameOnlyWhenNoEpisodeNumber() {
        assertEquals(70, EpisodeMatcher.episodeMatchScore("正片", -1, "正片 上"));
        assertEquals(60, EpisodeMatcher.episodeMatchScore("正片 上", -1, "正片"));
        // 单字不参与包含匹配
        assertEquals(0, EpisodeMatcher.episodeMatchScore("正", -1, "正片"));
        assertEquals(0, EpisodeMatcher.episodeMatchScore("正片", -1, "正"));
    }

    @Test
    public void matchScore_emptyInputs() {
        assertEquals(0, EpisodeMatcher.episodeMatchScore(null, -1, "x"));
        assertEquals(0, EpisodeMatcher.episodeMatchScore("x", -1, null));
        assertEquals(0, EpisodeMatcher.episodeMatchScore("", 3, ""));
    }

    // ---------- sameEpisodeIndex ----------

    @Test
    public void sameEpisodeIndex_matchesByEpisodeNumber() {
        List<VodInfo.VodSeries> targets = seriesList("第1集", "第3集", "第5集");
        assertEquals(1, EpisodeMatcher.sameEpisodeIndex(series("第3集"), targets, 0));
    }

    @Test
    public void sameEpisodeIndex_tieKeepsFirstCandidate() {
        // 两个候选都是"同集号"(80 分):取先出现的那个
        List<VodInfo.VodSeries> targets = seriesList("第3话", "EP3");
        assertEquals(0, EpisodeMatcher.sameEpisodeIndex(series("第3集"), targets, 0));
    }

    @Test
    public void sameEpisodeIndex_exactNameBeatsEarlierEpisodeNumber() {
        // 精确同名(100 分)必须压过"先出现的同集号"(80 分):否则会选中 0
        assertEquals(1, EpisodeMatcher.sameEpisodeIndex(series("第3集"), seriesList("EP3", "第3集"), 0));
    }

    @Test
    public void sameEpisodeIndex_containsMatchWhenNoEpisodeNumber() {
        // 无集号时走"包含关系"(70 分):该分支失效就会落到 fallback=1
        assertEquals(0, EpisodeMatcher.sameEpisodeIndex(series("正片"), seriesList("正片 上", "其他"), 1));
    }

    @Test
    public void sameEpisodeIndex_noMatch_fallsBackAndClamps() {
        List<VodInfo.VodSeries> targets = seriesList("第1集", "第2集");
        assertEquals(1, EpisodeMatcher.sameEpisodeIndex(series("第99集"), targets, 5));
        assertEquals(0, EpisodeMatcher.sameEpisodeIndex(series("第99集"), targets, -3));
    }

    @Test
    public void sameEpisodeIndex_degenerateInputs() {
        List<VodInfo.VodSeries> targets = seriesList("第1集", "第2集", "第3集");
        assertEquals(0, EpisodeMatcher.sameEpisodeIndex(series("第3集"), null, 0));
        assertEquals(0, EpisodeMatcher.sameEpisodeIndex(series("第3集"), seriesList(), 0));
        // 只有一条的线路直接返回 0(不再做集名匹配)
        assertEquals(0, EpisodeMatcher.sameEpisodeIndex(series("第3集"), seriesList("第9集"), 0));
        assertEquals(1, EpisodeMatcher.sameEpisodeIndex(null, targets, 1));
        assertEquals(2, EpisodeMatcher.sameEpisodeIndex(new VodInfo.VodSeries(), targets, 9));
    }

    // ---------- lineFlagsInDisplayOrder / lineFlagIndex ----------

    @Test
    public void lineFlags_seriesFlagsFirstThenRemainingMapKeys() {
        VodInfo vod = vod(Arrays.asList("线路A", "", "线路B", "线路A"), "线路B", "线路A", "线路C", "");
        assertEquals(Arrays.asList("线路A", "线路B", "线路C"), EpisodeMatcher.lineFlagsInDisplayOrder(vod));
    }

    @Test
    public void lineFlags_nullVodOrMap_isEmpty() {
        assertEquals(0, EpisodeMatcher.lineFlagsInDisplayOrder(null).size());
        assertEquals(0, EpisodeMatcher.lineFlagsInDisplayOrder(new VodInfo()).size());
    }

    @Test
    public void lineFlagIndex_foundAndMissing() {
        List<String> flags = Arrays.asList("线路A", "线路B");
        assertEquals(1, EpisodeMatcher.lineFlagIndex(flags, "线路B"));
        assertEquals(-1, EpisodeMatcher.lineFlagIndex(flags, "线路C"));
        assertEquals(-1, EpisodeMatcher.lineFlagIndex(null, "线路A"));
        // 传 null 走的是本地 isEmpty 分支:若换回 android.text.TextUtils,此处会 NPE(单测里它静默返回 false)
        assertEquals(-1, EpisodeMatcher.lineFlagIndex(flags, null));
        assertEquals(-1, EpisodeMatcher.lineFlagIndex(flags, ""));
    }

    // ---------- isSameEpisode(换线/换源继承位置的守卫) ----------

    @Test
    public void isSameEpisode_sameNumberDifferentWording() {
        // 各站写法不同(第N集 / 纯数字 / 带清晰度后缀)但指的是同一集:换线时位置必须继承
        assertTrue(EpisodeMatcher.isSameEpisode("第01集", "01"));
        assertTrue(EpisodeMatcher.isSameEpisode("第1集", "第01集"));
        assertTrue(EpisodeMatcher.isSameEpisode("E05", "第5集"));
    }

    @Test
    public void isSameEpisode_differentEpisode_doesNotMatch() {
        // 不同集绝不能算同集:否则"点下一集"会继承上一集的位置,从上一集中段开始播
        assertFalse(EpisodeMatcher.isSameEpisode("第1集", "第2集"));
        assertFalse(EpisodeMatcher.isSameEpisode("第01集", "第11集"));
    }

    @Test
    public void isSameEpisode_emptyName_doesNotMatch() {
        assertFalse(EpisodeMatcher.isSameEpisode("", "第1集"));
        assertFalse(EpisodeMatcher.isSameEpisode("第1集", ""));
        assertFalse(EpisodeMatcher.isSameEpisode(null, null));
    }

    // ---------- isDifferentEpisode(换线/换源继承位置的"确信换集"判据) ----------

    @Test
    public void isDifferentEpisode_bothNumberedAndDistinct() {
        // 两侧都有集号且不同 ⇒ 能断定是换集,位置继承必须拦下
        assertTrue(EpisodeMatcher.isDifferentEpisode("第1集", "第2集"));
        assertTrue(EpisodeMatcher.isDifferentEpisode("01", "E05"));
    }

    @Test
    public void isDifferentEpisode_sameNumberOrUnnumbered_isNotConfident() {
        // 同集(写法不同)当然不是"不同集"
        assertFalse(EpisodeMatcher.isDifferentEpisode("第01集", "01"));
        // 实测场景:天堂源那一集叫 `1`,瓜子源叫 `HD` —— 抽不到集号,只是命名体系不同,不能判成换集
        assertFalse(EpisodeMatcher.isDifferentEpisode("1", "HD"));
        assertFalse(EpisodeMatcher.isDifferentEpisode("1", "正片"));
        assertFalse(EpisodeMatcher.isDifferentEpisode("HD", "正片"));
        // 清晰度后缀会被剔除,推不出集号
        assertFalse(EpisodeMatcher.isDifferentEpisode("1080P", "720P"));
    }

    @Test
    public void isDifferentEpisode_emptyName_isNotConfident() {
        assertFalse(EpisodeMatcher.isDifferentEpisode("", "第2集"));
        assertFalse(EpisodeMatcher.isDifferentEpisode("第1集", null));
    }
}
