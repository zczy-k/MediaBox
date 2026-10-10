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

    /** 软解码时的上限:真机实测(麒麟990 手机)1080P H264 软解丢帧 0 已属勉强,再上一档必卡 */
    private const val DEFAULT_CAP_SOFTWARE = 1080

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
     * 尚未学到失败记录时按「解码方式 → 设备类型」取默认值。
     */
    @JvmStatic
    fun capHeight(context: Context): Int {
        val learned = KV.get(HawkConfig.VIDEO_QUALITY_CAP, 0)
        if (learned > 0) return learned
        // 用户显式选了软解码:4K/HEVC 软解基本等于幻灯片,再按"手机默认 2160"选就是明知会卡
        if (SOFTWARE_DECODE_LABEL == KV.get(HawkConfig.EXO_DECODE, "")) return DEFAULT_CAP_SOFTWARE
        return if (isTelevision(context)) DEFAULT_CAP_TV else DEFAULT_CAP_MOBILE
    }

    private const val SOFTWARE_DECODE_LABEL = "软解码" // i18n: keep —— 与设置页/PlayerConfigDelegate 同口径的字面量

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

    /**
     * 「画质选项」三档。**只分化"起播前做什么"**,起播后的故障链(降档 → 换线 → 换源 → 收口)
     * 三档完全一致 —— 卡顿降档的决策是读内存,零耗时,任何档都不该关掉它。
     *
     * <p>为什么画质功夫全部前置到起播前:播放中"升档"会造成画面跳变 + 音画不同步,只降不升;
     * 所以"画质 vs 速度"这笔账只会在起播前结算一次。
     */
    enum class QualityMode(
        val shouldProbeOnFirstWatch: Boolean,
        val probeBudgetMs: Long,
        val probeLines: Int,
    ) {
        /** 起播前把功夫做足:长预算探测候选线路,选实测最高(预解析管线接入后探测对象升级为真地址) */
        QUALITY_FIRST(true, 3000L, 4),

        /** 默认:记忆命中 0ms 选最高;无记忆做短预算直链探测,探不到按站点原序起播 */
        AUTO(true, 1000L, 3),

        /** 跳过全部起播前探测,站点原序即播;实测记忆回写不受档位影响,照常积累 */
        SPEED_FIRST(false, 0L, 0);

        companion object {
            /** KV 脏值兜底:越界/缺省一律落自动档 —— 跨版本升级可能读到任意 int,不可让播放路径抛异常 */
            @JvmStatic
            fun current(): QualityMode =
                values().getOrNull(KV.get(HawkConfig.VIDEO_QUALITY_MODE, AUTO.ordinal)) ?: AUTO
        }
    }

    // ── 流量节省(口径见《选线机制设计》附录 F)────────────────────────────────────

    /**
     * 「流量节省」是否开启。默认 **true**。
     *
     * <p>读失败也返回 true:口径取"安全侧"——漏判成开启只是少花流量,漏判成关闭会多花用户的流量。
     */
    @JvmStatic
    fun trafficSaverOn(): Boolean = KV.get(HawkConfig.TRAFFIC_SAVER, true)

    @JvmStatic
    fun setTrafficSaver(on: Boolean) {
        KV.put(HawkConfig.TRAFFIC_SAVER, on)
    }

    /**
     * 档位归一化:**纯逻辑**,可 JVM 单测(KV 在单测环境不可用,故把判断抽成无副作用函数)。
     *
     * <p>流量节省开启 ⇒ 生效档位恒为 [QualityMode.AUTO](画质优先被暂停);
     * 关闭 ⇒ 原样返回用户存储的档位。**不改写 KV** —— 用户的选择保留,关掉开关即自动恢复。
     */
    @JvmStatic
    fun resolveMode(stored: QualityMode, saverOn: Boolean): QualityMode =
        if (saverOn) QualityMode.AUTO else stored

    /**
     * 实际生效的档位。**决策点必须读这个**,而不是 [QualityMode.current]。
     *
     * <p>UI 侧仍展示用户原选择(见 PlaySettingsPage),以便流量节省关闭后一键恢复。
     */
    @JvmStatic
    fun effectiveMode(): QualityMode = resolveMode(QualityMode.current(), trafficSaverOn())
}
