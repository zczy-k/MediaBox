package com.github.tvbox.osc.util;

import android.app.Activity;

import com.github.tvbox.osc.util.KV;

public class SubtitleHelper {

    public static int getSubtitleTextAutoSize(Activity activity) {
        double screenSqrt = ScreenUtils.getSqrt(activity);
        int subtitleTextSize = 16;
        if (screenSqrt > 7.0 && screenSqrt <= 13.0) {
            subtitleTextSize = 24;
        } else if (screenSqrt > 13.0 && screenSqrt <= 50.0) {
            subtitleTextSize = 36;
        } else if (screenSqrt > 50.0) {
            subtitleTextSize = 46;
        }
        return subtitleTextSize;
    }

    public static int getTextSize(Activity activity) {
        int autoSize = getSubtitleTextAutoSize(activity);
        int subtitleConfigSize = KV.get(HawkConfig.SUBTITLE_TEXT_SIZE, autoSize);
        return subtitleConfigSize;
    }

    public static void setTextSize(int size) {
        KV.put(HawkConfig.SUBTITLE_TEXT_SIZE, size);
    }

    /** 字幕总开关:默认关闭,需要时由用户打开;关闭后播放层不加载也不显示字幕 */
    public static boolean isEnabled() {
        return KV.get(HawkConfig.SUBTITLE_ENABLED, false);
    }

    public static void setEnabled(boolean enabled) {
        KV.put(HawkConfig.SUBTITLE_ENABLED, enabled);
    }

    public static int getTimeDelay() {
        int subtitleConfigTimeDelay = KV.get(HawkConfig.SUBTITLE_TIME_DELAY, 0);
        return subtitleConfigTimeDelay;
    }

    public static void setTimeDelay(int delay) {
        KV.put(HawkConfig.SUBTITLE_TIME_DELAY, delay);
    }

    public static int getExoSubtitleScale() {
        return KV.get(HawkConfig.SUBTITLE_EXO_SCALE, 100);
    }

    public static void setExoSubtitleScale(int scale) {
        KV.put(HawkConfig.SUBTITLE_EXO_SCALE, scale);
    }

    public static float getExoSubtitlePosition() {
        return KV.get(HawkConfig.SUBTITLE_EXO_POSITION, 0.0f);
    }

    public static void setExoSubtitlePosition(float position) {
        KV.put(HawkConfig.SUBTITLE_EXO_POSITION, position);
    }

    public static void reset() {
        KV.delete(HawkConfig.SUBTITLE_TEXT_SIZE);
        KV.delete(HawkConfig.SUBTITLE_TIME_DELAY);
        KV.delete(HawkConfig.SUBTITLE_TEXT_STYLE);
        KV.delete(HawkConfig.SUBTITLE_EXO_SCALE);
        KV.delete(HawkConfig.SUBTITLE_EXO_POSITION);
    }

}
