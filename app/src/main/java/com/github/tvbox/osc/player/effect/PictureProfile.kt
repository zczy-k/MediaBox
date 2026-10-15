package com.github.tvbox.osc.player.effect

/**
 * 画质预设(顺序即面板顺序;Original = 不套效果)。
 *
 * <p>**一档 = 一种看得见的差别**。原来 15 档里 Natural/Bright 与"原始"肉眼难分,
 * Anime/Sport/Game 与 Vivid 同族(只差锐化幅度),Dehaze 与 Clear 同族,Comfort 与 Soft 同族
 * —— 那不是在给选择,是在给"该点哪个"增加成本。合并后 8 档。
 */
enum class PicturePreset {
    Original, Clear, Vivid, Cinema, Soft, Warm, Cool, Custom;

    /** 只有「自定义」展开 9 个滑条 */
    val adjustable: Boolean get() = this == Custom

    companion object {

        /**
         * 被合并掉的旧档名 → 现在的档。**没有这层映射,删一档就会把老用户的设置打回「原始」**
         * (预置名是按字符串存盘的,枚举少一个就查不到)。
         */
        private val legacyNames: Map<String, PicturePreset> = mapOf(
            "Natural" to PicturePreset.Original, "Bright" to PicturePreset.Original,
            "Anime" to PicturePreset.Vivid, "Sport" to PicturePreset.Vivid,
            "Game" to PicturePreset.Vivid,
            "Dehaze" to PicturePreset.Clear,
            "Comfort" to PicturePreset.Soft,
        )

        /** 读 KV 里的预置名:先按现名查,再走合并映射,都不是才落 fallback */
        fun resolve(name: String?, fallback: PicturePreset = PicturePreset.Original): PicturePreset {
            if (name.isNullOrEmpty()) return fallback
            PicturePreset.entries.firstOrNull { it.name == name }?.let { return it }
            return legacyNames[name] ?: fallback
        }
    }
}

/** 画质参数(不可变)。取值语义:倍率类为 1 恒等、偏移类为 0 恒等;量程见伴生对象常量 */
data class PictureProfile(
    val saturation: Float = 1f,
    val contrast: Float = 1f,
    val brightness: Float = 0f,
    val gamma: Float = 1f,
    val hue: Float = 0f,
    val temperature: Float = 0f,
    val sharpness: Float = 0f,
    val shadowLift: Float = 0f,
    /**
     * 去灰强度 0..1:把被抬高的黑位压回 0、白位顶到 1(levels 拉伸)。
     *
     * <p>"画面灰蒙蒙"绝大多数不是分辨率问题,而是**黑位没到底** —— 压制过度的片、
     * 保守的 HDR→SDR 映射都会把黑底留在 4%~8%,看上去就是一层灰纱。对比度是绕 0.5 的
     * 线性放大,压不掉这个底(只会把高光一起顶爆),所以要单独一档。
     */
    val dehaze: Float = 0f,
    val threshold: Float = DEFAULT_THRESHOLD,
) {

    /** 色温折算的红/蓝增益(暖色抬红压蓝,冷色反向) */
    val redGain: Float
        get() = if (temperature >= 0f) 1f + temperature * WARM_GAIN else 1f + temperature * COOL_GAIN

    val blueGain: Float
        get() = if (temperature >= 0f) 1f - temperature * COOL_GAIN else 1f - temperature * WARM_GAIN

    val isColorNoOp: Boolean
        get() = saturation == 1f && contrast == 1f && brightness == 0f && dehaze == 0f &&
            redGain == 1f && blueGain == 1f

    val isToneNoOp: Boolean get() = gamma == 1f && hue == 0f

    val isDetailNoOp: Boolean get() = sharpness == 0f && shadowLift == 0f

    /** 全恒等(「原始」/「按住对比」):不必把效果链挂上内核 */
    val isNoOp: Boolean get() = isColorNoOp && isToneNoOp && isDetailNoOp

    /** KV 读回时钳到量程(NaN 与越界落默认) */
    fun clamped(): PictureProfile = copy(
        saturation = saturation.sane(MIN_SATURATION, MAX_SATURATION, 1f),
        contrast = contrast.sane(MIN_CONTRAST, MAX_CONTRAST, 1f),
        brightness = brightness.sane(MIN_BRIGHTNESS, MAX_BRIGHTNESS, 0f),
        gamma = gamma.sane(MIN_GAMMA, MAX_GAMMA, 1f),
        hue = hue.sane(MIN_HUE, MAX_HUE, 0f),
        temperature = temperature.sane(MIN_TEMPERATURE, MAX_TEMPERATURE, 0f),
        sharpness = sharpness.sane(MIN_SHARPNESS, MAX_SHARPNESS, 0f),
        shadowLift = shadowLift.sane(MIN_SHADOW_LIFT, MAX_SHADOW_LIFT, 0f),
        dehaze = dehaze.sane(MIN_DEHAZE, MAX_DEHAZE, 0f),
    )

    companion object {

        /** 锐化/暗部提升的边缘掩码阈值 */
        const val DEFAULT_THRESHOLD = 0.03f

        /** 色温增益斜率:暖色侧红升蓝降,冷色侧反向 */
        private const val WARM_GAIN = 0.0015f
        private const val COOL_GAIN = 0.0012f

        // 滑条量程(面板与 KV 读回共用)
        const val MIN_SATURATION = 0.5f
        const val MAX_SATURATION = 2.0f
        const val MIN_CONTRAST = 0.5f
        const val MAX_CONTRAST = 1.8f
        const val MIN_BRIGHTNESS = -0.2f
        const val MAX_BRIGHTNESS = 0.2f
        const val MIN_GAMMA = 0.5f
        const val MAX_GAMMA = 2.0f
        const val MIN_HUE = -180f
        const val MAX_HUE = 180f
        const val MIN_TEMPERATURE = -100f
        const val MAX_TEMPERATURE = 100f
        const val MIN_SHARPNESS = 0f
        const val MAX_SHARPNESS = 0.8f
        const val MIN_SHADOW_LIFT = 0f
        const val MAX_SHADOW_LIFT = 0.6f
        const val MIN_DEHAZE = 0f
        const val MAX_DEHAZE = 1f

        /** 去灰满程时黑位最多压到 11%、白位最多下压 3% —— 再狠就开始吃暗部细节了 */
        const val DEHAZE_MAX_BLACK = 0.11f
        const val DEHAZE_MAX_WHITE_PUSH = 0.03f

        /**
         * 去灰强度 → levels 的 (黑位, 白位)。着色器做 `out = (in - lo) / (hi - lo)`。
         *
         * <p>0 必须精确落在 (0,1) 恒等:链上任何一处判等都拿它当"没调"的依据,不能出现 1e-7 这种假非恒等。
         */
        fun levelsOf(dehaze: Float): FloatArray {
            val d = if (dehaze.isFinite()) dehaze.coerceIn(MIN_DEHAZE, MAX_DEHAZE) else 0f
            if (d == 0f) return floatArrayOf(0f, 1f)
            return floatArrayOf(d * DEHAZE_MAX_BLACK, 1f - d * DEHAZE_MAX_WHITE_PUSH)
        }

        /** 归一(不套效果) */
        val OFF = PictureProfile()

        fun of(preset: PicturePreset): PictureProfile = when (preset) {
            // 「清晰」= 通透(去灰)+ 锐化:两者本来就是一回事
            PicturePreset.Clear -> basic(1.05f, 1.10f, 0.0f, 0.34f, 0.025f, 0.02f)
                .copy(dehaze = 0.45f, gamma = 0.99f)
            // 「鲜艳」收编了动画/体育/游戏:它们与它的差别只是"再浓一点、再锐一点"
            PicturePreset.Vivid -> basic(1.22f, 1.12f, 0.015f, 0.26f, 0.028f, 0.03f)
            PicturePreset.Cinema -> style(1.04f, 1.14f, -0.03f, 0.03f, 0.03f, 0.97f, 26.0f)
            // 「柔和」收编了护眼:都是降饱和降对比 + 抬 gamma + 偏暖
            PicturePreset.Soft -> style(0.94f, 0.94f, 0.0f, 0.0f, 0.05f, 1.035f, 36.0f)
            PicturePreset.Warm -> style(1.05f, 1.04f, 0.0f, 0.03f, 0.02f, 1.0f, 42.0f)
            PicturePreset.Cool -> style(1.04f, 1.05f, 0.0f, 0.03f, 0.02f, 1.0f, -42.0f)
            PicturePreset.Original, PicturePreset.Custom -> OFF
        }

        /** 只调饱和度/对比度/亮度/锐度/暗部的预设 */
        private fun basic(
            saturation: Float,
            contrast: Float,
            brightness: Float,
            sharpness: Float,
            threshold: Float,
            shadowLift: Float,
        ) = PictureProfile(
            saturation = saturation,
            contrast = contrast,
            brightness = brightness,
            sharpness = sharpness,
            threshold = threshold,
            shadowLift = shadowLift,
        )

        /** 带伽马/色温的预设 */
        private fun style(
            saturation: Float,
            contrast: Float,
            brightness: Float,
            sharpness: Float,
            shadowLift: Float,
            gamma: Float,
            temperature: Float,
        ) = basic(saturation, contrast, brightness, sharpness, DEFAULT_THRESHOLD, shadowLift)
            .copy(gamma = gamma, temperature = temperature)
    }
}

private fun Float.sane(min: Float, max: Float, fallback: Float): Float =
    if (isFinite()) coerceIn(min, max) else fallback
