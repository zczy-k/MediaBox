package com.github.tvbox.osc.util;

/**
 * 播放器手势开关(2026-09-13,「设置 → 偏好设置 → 禁用手势控制」)。
 *
 * <p>判定收敛在这里,点播(`ComposeVideoController`)与直播(`ComposeLiveController`)两侧共用一份实现 ——
 * 两个控制器的 {@code canHandleGesture} 都读它,避免"只禁了点播、直播还能调"的漏网。
 *
 * <p>⚠️ 生效范围只有**上下滑调亮度/音量**这一类;单击显隐控制条、双击播放/暂停、横滑进度、
 * 竖向切集等手势**不受影响**(用户要求只禁亮度与音量)。
 */
public class GestureHelper {

    /** 是否禁用了手势控制亮度/音量(默认关) */
    public static boolean isControlDisabled() {
        return KV.get(HawkConfig.GESTURE_CONTROL_DISABLED, false);
    }

    public static void setControlDisabled(boolean disabled) {
        KV.put(HawkConfig.GESTURE_CONTROL_DISABLED, disabled);
    }
}
