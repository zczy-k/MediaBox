package com.github.tvbox.osc.util;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds a small set of complementary subtitle-search terms from a media filename/title. */
public final class SubtitleSearchQuery {

    private static final Pattern VIDEO_EXTENSION = Pattern.compile(
            "(?i)\\.(mkv|mp4|avi|mov|wmv|flv|webm|m4v|ts|m2ts)$");
    private static final Pattern BRACKETED_NOISE = Pattern.compile(
            "(?:\\[[^\\]]*]|【[^】]*】|（[^）]*）|\\([^)]*\\))");
    private static final Pattern TECHNICAL_TAGS = Pattern.compile(
            "(?i)\\b(480p|720p|1080p|2160p|4k|8k|hdr|hevc|h265|h264|x264|x265|aac|dts|web[- ]?dl|bluray|brrip|webrip|uncensored|中字|字幕组)\\b");
    private static final Pattern CODE = Pattern.compile(
            "(?i)(?<![a-z0-9])(?:fc2[\\s._-]*ppv[\\s._-]*[0-9]{5,8}|[a-z]{2,8}[\\s._-]*[0-9]{2,6})(?![a-z0-9])");
    private static final Pattern WORDS = Pattern.compile("[\\p{L}\\p{N}]{3,}");
    private static final int MAX_QUERIES = 4;

    private SubtitleSearchQuery() {
    }

    /**
     * Prioritizes the cleaned original title, then the catalog number (formatted and compact),
     * and finally useful title tokens. Keeps requests bounded to avoid multiplying load.
     */
    public static List<String> buildQueries(String input) {
        String raw = baseName(input);
        if (raw.isEmpty()) return new ArrayList<>();

        LinkedHashMap<String, String> unique = new LinkedHashMap<>();
        add(unique, raw);

        Matcher codeMatcher = CODE.matcher(raw);
        while (codeMatcher.find()) {
            String code = codeMatcher.group().trim();
            add(unique, code);
            add(unique, code.replaceAll("[^a-zA-Z0-9]", ""));
        }

        String clean = BRACKETED_NOISE.matcher(raw).replaceAll(" ");
        clean = VIDEO_EXTENSION.matcher(clean).replaceAll("");
        clean = TECHNICAL_TAGS.matcher(clean).replaceAll(" ");
        add(unique, clean);

        Matcher wordMatcher = WORDS.matcher(clean);
        while (wordMatcher.find() && unique.size() < MAX_QUERIES) {
            String token = wordMatcher.group();
            if (!isNoiseToken(token)) add(unique, token);
        }

        return new ArrayList<>(unique.values());
    }

    /** Best compact title to send to subtitle sites that index catalog numbers more reliably. */
    public static String providerTitle(String input) {
        String raw = baseName(input);
        Matcher matcher = CODE.matcher(raw);
        if (matcher.find()) {
            return matcher.group().trim()
                    .replaceAll("[\\s._]+", "-")
                    .replaceAll("-+", "-")
                    .toUpperCase(Locale.ROOT);
        }
        String clean = BRACKETED_NOISE.matcher(raw).replaceAll(" ");
        clean = TECHNICAL_TAGS.matcher(clean).replaceAll(" ").trim();
        return clean.isEmpty() ? raw : clean;
    }

    /** True when a provider result meaningfully matches this query variant after punctuation folding. */
    public static boolean matches(String resultTitle, String query) {
        String result = normalize(resultTitle);
        String term = normalize(query);
        return !result.isEmpty() && !term.isEmpty() && result.contains(term);
    }

    /** Relevance for sorting merged provider results; exact code/title matches rank first. */
    public static int relevance(String resultTitle, String originalTitle) {
        String result = normalize(resultTitle);
        String original = normalize(baseName(originalTitle));
        if (result.isEmpty() || original.isEmpty()) return 0;
        if (result.equals(original)) return 100;
        if (result.startsWith(original)) return 90;
        if (result.contains(original)) return 80;

        int best = 0;
        for (String query : buildQueries(originalTitle)) {
            String term = normalize(query);
            if (term.isEmpty() || !result.contains(term)) continue;
            int score = term.length() >= 5 ? 70 : 40;
            if (score > best) best = score;
        }
        return best;
    }

    /** Unicode-aware lowercase alphanumeric normalization (hyphens/spaces/brackets are ignored). */
    public static String normalize(String value) {
        if (value == null) return "";
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFKD).toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(decomposed.length());
        for (int i = 0; i < decomposed.length();) {
            int cp = decomposed.codePointAt(i);
            int type = Character.getType(cp);
            if (Character.isLetterOrDigit(cp) && type != Character.NON_SPACING_MARK) out.appendCodePoint(cp);
            i += Character.charCount(cp);
        }
        return out.toString();
    }

    private static String baseName(String input) {
        if (input == null) return "";
        String value = input.trim().replace('\\', '/');
        int slash = value.lastIndexOf('/');
        if (slash >= 0) value = value.substring(slash + 1);
        value = VIDEO_EXTENSION.matcher(value).replaceAll("").trim();
        return value;
    }

    private static void add(Map<String, String> unique, String candidate) {
        if (candidate == null) return;
        String value = candidate.trim().replaceAll("\\s+", " ");
        String key = value.toLowerCase(Locale.ROOT);
        if (normalize(value).length() < 2 || unique.containsKey(key) || unique.size() >= MAX_QUERIES) return;
        // Keep punctuation variants distinct (MIAA-195 and MIAA195 can index differently on providers).
        unique.put(key, value);
    }

    private static boolean isNoiseToken(String value) {
        String token = value.toLowerCase(Locale.ROOT);
        return token.matches("\\d{3,4}p?") || token.equals("www") || token.equals("com")
                || token.equals("mkv") || token.equals("mp4") || token.equals("ass")
                || token.equals("srt") || token.equals("subtitle") || token.equals("subtitles");
    }
}
