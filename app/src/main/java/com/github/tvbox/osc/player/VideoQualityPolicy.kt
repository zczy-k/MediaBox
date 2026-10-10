package com.github.tvbox.osc.player

import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 线路画质优选策略:**纯逻辑**,不碰网络 / 存储 / 视图,可直接单测。
 *
 * <p>设计约束(全部来自真机与代码事实,不是推测):
 * <ul>
 *   <li>画质高低**不能**按 flag 名猜 —— 站点把"超级无敌4K"写在 540P 地址上是常事,故只认实测值;</li>
 *   <li>分辨率高 ≠ 画质好:2160P 低码率可能不如 1080P 高码率,故同档内按每像素码率二次排序;</li>
 *   <li>设备撑不住的档**直接排除**而不是排后面 —— 排后面意味着会被选中后才失败,白等一次起播超时;</li>
 *   <li>探测值可信度不同:文件里读出来的(MP4 moov)比站点写在清单里的(HLS/DASH)可信,只用于同分破平。</li>
 * </ul>
 */
object VideoQualityPolicy {

    /** 探测结果的可信度。 */
    enum class Confidence {
        /** 从文件里读出来的真值(MP4 moov / 已起播后内核上报) */
        MEASURED,
        /** 站点写在清单里的声明值(HLS `RESOLUTION` / DASH `Representation`) */
        DECLARED,
        /** 拿不到 */
        UNKNOWN,
    }

    /**
     * 一个候选线路的画质。宽高为 0 表示"没测到",不是"没有画面"。
     *
     * @param flag 该线路在 `VodInfo.seriesMap` 里的 key;仅用于日志与记忆键,排序不用
     */
    data class Variant(
        val width: Int = 0,
        val height: Int = 0,
        val bitrate: Int = 0,
        val confidence: Confidence = Confidence.UNKNOWN,
        val flag: String = "",
    ) {
        val known: Boolean get() = width > 0 && height > 0
    }

    /**
     * 每像素码率(bitrate / 像素数)。省略了帧率因子,同片源帧率一致时与"每像素每帧码率"同序。
     *
     * @return 0 = 码率或尺寸缺失,无法计算
     */
    fun bitsPerPixel(v: Variant): Int {
        if (v.bitrate <= 0 || !v.known) return 0
        val pixels = v.width.toLong() * v.height.toLong()
        if (pixels <= 0L) return 0
        return (v.bitrate.toLong() / pixels).toInt()
    }

    // ── 度量层:等效清晰度 / 天花板 / 达标锚(《选线机制设计》附录 A)────────────────────
    //
    // 这一层是"不写死分辨率"的落点:所有比较都从**实测量**推导,
    // 不预设任何档位表(480/720/1080/1440/2160 那种表覆盖不到站点真实存在的分辨率)。

    /** 画质天花板(4K 级)的宽度阈值:达此宽度即视为到顶,停止向上追 */
    const val CEILING_WIDTH = 3840

    /** 画质天花板的高度兜底阈值(宽度未上报/非标准时用) */
    const val CEILING_HEIGHT = 2160

    /** 达标锚(电视):有效宽度 ≥ 此值算"够清晰" */
    const val ANCHOR_WIDTH_TV = 1920

    /** 达标锚(手机) */
    const val ANCHOR_WIDTH_MOBILE = 1280

    /**
     * 等效线性清晰度 **S = √(W × H)**。0 = 不可计算(尺寸未知)。
     *
     * <p>为什么不用高度:1920×800(宽银幕 1080p)按高度只有 800,会被判成"未达 1080" —— 误杀。
     * 为什么不用档位:S 是连续量,直接回答"谁更清晰",不需要先归类到某个写死的档。
     */
    @JvmStatic
    fun sharpness(v: Variant): Int {
        if (!v.known) return 0
        return sqrt(v.width.toDouble() * v.height.toDouble()).roundToInt()
    }

    /**
     * 是否已达天花板(4K 级):有效宽度 ≥[CEILING_WIDTH],或高度 ≥[CEILING_HEIGHT]。
     *
     * <p>用"级"不用等值:3840×1600 与 3840×2160 同为 4K 级,都判达顶 ——
     * 否则非标宽高会漏判,系统永远认为"上面还有",无限追高。
     */
    @JvmStatic
    fun isAtCeiling(v: Variant?): Boolean =
        v != null && v.known && (v.width >= CEILING_WIDTH || v.height >= CEILING_HEIGHT)

    /** 是否达到达标锚(有效宽度 ≥[anchorWidth])。anchorWidth ≤ 0 时视为"无锚",一律通过 */
    @JvmStatic
    fun meetsAnchor(v: Variant?, anchorWidth: Int): Boolean {
        if (anchorWidth <= 0) return true
        return v != null && v.known && v.width >= anchorWidth
    }

    /**
     * 内核上报尺寸的构造入口。
     *
     * <p>供 Java 侧(`PlaybackRetryDelegate.currentMeasuredVariant`)调用 —— 免去在 Java 里
     * 拼 5 参构造并显式传默认值,减少 Kotlin↔Java 互操作面。
     */
    @JvmStatic
    fun measured(width: Int, height: Int, bitrate: Int): Variant =
        Variant(width, height, bitrate, Confidence.MEASURED)

    /** 清晰度权重步长:S 差 1 的量级必须压过所有次键之和(见 [score]) */
    private const val SHARPNESS_STEP = 10_000_000L
    private const val BPP_STEP = 10_000L
    private const val BPP_CAP = 99

    private fun confidenceBonus(c: Confidence): Long = when (c) {
        Confidence.MEASURED -> 2L
        Confidence.DECLARED -> 1L
        Confidence.UNKNOWN -> 0L
    }

    /**
     * 排序键,越大越优先。三个位段从高到低:**清晰度 S** → 每像素码率(封顶 [BPP_CAP]) → 可信度。
     *
     * <p>主键用 S 而不是高度:1920×800 这类宽银幕按高度只有 800,会被 1280×720 反超(误判);
     * 按 S 则 1240 > 960,顺序正确。同理 1728×720(S=1115)能正确压过 1280×534(S=826)。
     *
     * <p>未知尺寸得 0 分主键,因此天然排在所有已知档之后 —— 没有信息就没有优先权。
     */
    fun score(v: Variant): Long {
        val s = sharpness(v).toLong()
        val bpp = bitsPerPixel(v).coerceIn(0, BPP_CAP).toLong()
        return s * SHARPNESS_STEP + bpp * BPP_STEP + confidenceBonus(v.confidence)
    }

    /**
     * 挑最优候选;[deviceCapHeight] > 0 时把超出设备能力的档**直接排除**。
     *
     * <p>同分时保持传入顺序(用严格 `>` 而非 `>=`),不打乱站点给的默认序。
     *
     * @return null = 全部候选都被设备上限排除
     */
    fun pickBest(candidates: List<Variant>, deviceCapHeight: Int): Variant? {
        var best: Variant? = null
        var bestScore = Long.MIN_VALUE
        for (v in candidates) {
            if (deviceCapHeight > 0 && v.known && v.height > deviceCapHeight) continue
            val s = score(v)
            if (s > bestScore) {
                bestScore = s
                best = v
            }
        }
        return best
    }

    /**
     * 早停判据:找到一个不低于 [thresholdHeight] 的档就不必再探下一条。
     *
     * <p>阈值给的是"够好"而不是"最好"—— 为找 2160P 多花一次爬虫解析不划算(实测每条 1~3 秒)。
     */
    fun goodEnough(v: Variant?, thresholdHeight: Int): Boolean =
        v != null && v.known && v.height >= thresholdHeight

    private val EXT_X_STREAM_INF = Regex("""#EXT-X-STREAM-INF:([^\r\n]*)""", RegexOption.IGNORE_CASE)
    private val ATTR_RESOLUTION = Regex("""RESOLUTION=(\d+)x(\d+)""", RegexOption.IGNORE_CASE)
    private val ATTR_BANDWIDTH = Regex("""BANDWIDTH=(\d+)""", RegexOption.IGNORE_CASE)
    private val ATTR_CODECS = Regex("""CODECS="([^"]*)"""", RegexOption.IGNORE_CASE)

    /**
     * 从 m3u8 master 清单里取最优变体。
     *
     * <p>单码率 playlist / 非 master / 文本异常一律返回 null —— 调用方按"探测不到"静默降级,
     * 绝不能让探测失败影响播放。
     */
    fun parseHlsMaster(text: String?): Variant? {
        if (text.isNullOrBlank()) return null
        var best: Variant? = null
        var bestScore = Long.MIN_VALUE
        for (m in EXT_X_STREAM_INF.findAll(text)) {
            val attrs = m.groupValues.getOrNull(1) ?: continue
            // 没有 RESOLUTION 的变体无法横向比较,跳过而不是当成"未知"(否则会挤掉已知档)
            val res = ATTR_RESOLUTION.find(attrs) ?: continue
            val w = res.groupValues.getOrNull(1)?.toIntOrNull() ?: continue
            val h = res.groupValues.getOrNull(2)?.toIntOrNull() ?: continue
            if (w <= 0 || h <= 0) continue
            val bw = ATTR_BANDWIDTH.find(attrs)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            val codec = ATTR_CODECS.find(attrs)?.groupValues?.getOrNull(1).orEmpty()
            val v = Variant(w, h, bw, Confidence.DECLARED)
            val s = score(v)
            if (s > bestScore) {
                bestScore = s
                best = v
            }
        }
        return best
    }
}
