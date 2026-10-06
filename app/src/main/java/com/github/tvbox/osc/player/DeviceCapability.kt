package com.github.tvbox.osc.player

import android.content.Context
import android.content.res.Configuration
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.LOG

/**
 * 设备画质能力画像:这台设备/这块屏幕最多能顺畅播到多少高度。
 *
 * <p>为什么必须有上限:4K / 蓝光不只是"更清晰",它同时是**带宽门槛 + 解码门槛**。
 * 硬上 4K 而设备或网络撑不住,代价是起播失败 → 降档 → 再失败,**比一开始选 1080P 更慢**,
 * 而且用户看到的是卡顿。所以选线排序里必须有一道 `min(探测到的最高, 设备上限)`。
 *
 * <p>上限来源:①电视/盒子默认 1080(HDMI 模式芯片性能与散热普遍弱于手机);②手机默认不限制;
 * ③每次因起播失败而降档就把上限压到该档之下,**只降不升** —— 升档要重新验证,收益不值那个风险。
 */
object DeviceCapability {

    private const val DEFAULT_CAP_TV = 1080
    private const val DEFAULT_CAP_MOBILE = 2160

    /** 标准档位阶梯:某一档播不了就落到它的下一档,再下一档就是"不限制" */
    private val LADDER = intArrayOf(2160, 1440, 1080, 720, 480)

    /**
     * 是否电视 / 盒子。
     *
     * <p>用 `Configuration.UI_MODE_TYPE_TELEVISION` 而**不是** `UiModeManager.UI_MODE_TYPE_TELEVISION`
     * —— 后者在 AOSP 里是 @hide,SDK 编译期取不到(CI 实证:`Unresolved reference`)。
     */
    fun isTelevision(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
            Configuration.UI_MODE_TYPE_TELEVISION

    /**
     * 设备最高可播高度(像素)。0 = 不限制。
     * 尚未学到失败记录时按设备类型取默认值。
     */
    @JvmStatic
    fun capHeight(context: Context): Int {
        val learned = KV.get(HawkConfig.VIDEO_QUALITY_CAP, 0)
        if (learned > 0) return learned
        return if (isTelevision(context)) DEFAULT_CAP_TV else DEFAULT_CAP_MOBILE
    }

    /**
     * 记一次"这一档起播失败",把上限压到它的下一档。
     *
     * <p>只在**已经学到过上限**时才继续下调:首播就因为一次失败把上限压到 1080P,
     * 会让一台本来能播 4K 的设备永久失去上高档的机会(而那次失败可能只是网络抖动)。
     */
    @JvmStatic
    fun notePlaybackFailure(context: Context, failedHeight: Int) {
        if (failedHeight <= 0) return
        val learned = KV.get(HawkConfig.VIDEO_QUALITY_CAP, 0)
        if (learned <= 0) return
        if (failedHeight <= learned) return
        val next = LADDER.firstOrNull { it < failedHeight } ?: 0
        if (next == learned) return
        KV.put(HawkConfig.VIDEO_QUALITY_CAP, next)
        LOG.d("DeviceCapability", "cap down after failure at ${failedHeight}p -> $next")
    }

    /** 用户在设置里显式改过上限后调用(0 = 恢复按设备类型自动定) */
    @JvmStatic
    fun setCapHeight(height: Int) {
        if (height <= 0) {
            KV.delete(HawkConfig.VIDEO_QUALITY_CAP)
            return
        }
        KV.put(HawkConfig.VIDEO_QUALITY_CAP, height.coerceIn(480, 4320))
    }
}
