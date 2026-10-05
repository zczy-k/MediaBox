package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class SubtitleSearchQueryTest {

    @Test
    public void buildQueries_extractsAndCompactsAvNumber() {
        List<String> queries = SubtitleSearchQuery.buildQueries(
                "D:/Video/MIAA-195 [1080p] uncensored.mkv");
        assertTrue(queries.contains("MIAA-195"));
        assertTrue(queries.contains("MIAA195"));
        assertTrue(queries.size() <= 4);
    }

    @Test
    public void buildQueries_handlesFc2CatalogNumbers() {
        List<String> queries = SubtitleSearchQuery.buildQueries("FC2-PPV-4925855.mp4");
        assertTrue(queries.contains("FC2-PPV-4925855"));
        assertTrue(queries.contains("FC2PPV4925855"));
    }

    @Test
    public void buildQueries_removesPathExtensionAndNoise() {
        List<String> queries = SubtitleSearchQuery.buildQueries("/storage/series/Some.Show.S01E02.1080p.mkv");
        assertFalse(queries.isEmpty());
        assertFalse(queries.get(0).contains("/storage/"));
        assertFalse(queries.get(0).toLowerCase().endsWith(".mkv"));
    }

    @Test
    public void matches_ignoresNumberPunctuationAndCase() {
        assertTrue(SubtitleSearchQuery.matches("MIAA 195 中文字幕", "MIAA-195"));
        assertTrue(SubtitleSearchQuery.matches("fc2 ppv 4925855 Chinese subtitle", "FC2-PPV-4925855"));
        assertFalse(SubtitleSearchQuery.matches("ABP-123 English", "MIAA-195"));
    }

    @Test
    public void relevance_ranksNumberHitAbovePartialNoise() {
        int exact = SubtitleSearchQuery.relevance("MIAA-195 Chinese subtitle", "MIAA-195 movie.mkv");
        int unrelated = SubtitleSearchQuery.relevance("ABP-123 Chinese subtitle", "MIAA-195 movie.mkv");
        assertTrue(exact > unrelated);
        assertEquals(0, unrelated);
    }

    @Test
    public void providerTitle_prefersCatalogCodeAndRemovesReleaseNoise() {
        assertEquals("MIAA-195", SubtitleSearchQuery.providerTitle("[Group] MIAA-195 Movie Name 1080p.mkv"));
        assertEquals("FC2-PPV-4925855", SubtitleSearchQuery.providerTitle("FC2 PPV 4925855 [1080p].mp4"));
        assertEquals("Some Show S01E02", SubtitleSearchQuery.providerTitle("Some Show S01E02 [WEB-DL].mkv"));
    }
}
