package com.github.tvbox.osc.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class RegexUtils {

    // BugReview #26:被 NanoHTTPD 线程、主线程、QuickJS loadModule 线程并发读写,改并发容器
    private static final Map<String, Pattern> patternCache = new ConcurrentHashMap<>();
    public static Pattern getPattern(String regex) {
        Pattern pattern = patternCache.get(regex);
        if (pattern == null) {
            pattern = Pattern.compile(regex);
            patternCache.put(regex, pattern);
        }
        return pattern;
    }

    public static Pattern getPattern(String regex,int flag) {
        Pattern pattern = patternCache.get(regex);
        if (pattern == null) {
            pattern = Pattern.compile(regex,flag);
            patternCache.put(regex, pattern);
        }
        return pattern;
    }
}
