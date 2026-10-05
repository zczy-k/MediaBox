package com.github.tvbox.osc.player.effect.anime4k

import androidx.annotation.StringRes
import com.github.tvbox.osc.R
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV

internal const val ANIME4K_ASSET_DIR = "Anime4K/"

/** 防过冲/振铃：官方口径 = 开头算统计、放大前钳制高光（拼装见 [Anime4kShader.compose]） */
internal const val ANIME4K_CLAMP_HIGHLIGHTS = "Anime4K_Clamp_Highlights.glsl"

/** 无过冲锐化（官方口径），1x 上跑，面板可开关 */
internal const val ANIME4K_DEBLUR_DOG = "Anime4K_Deblur_DoG.glsl"

private const val RESTORE_CNN_S = "Anime4K_Restore_CNN_S.glsl"
private const val RESTORE_CNN_M = "Anime4K_Restore_CNN_M.glsl"
private const val RESTORE_CNN_L = "Anime4K_Restore_CNN_L.glsl"
private const val UPSCALE_CNN_X2_S = "Anime4K_Upscale_CNN_x2_S.glsl"
private const val UPSCALE_CNN_X2_M = "Anime4K_Upscale_CNN_x2_M.glsl"
private const val UPSCALE_CNN_X2_L = "Anime4K_Upscale_CNN_x2_L.glsl"

enum class Anime4kTier(
    @StringRes val labelRes: Int,
    /** 1x 修复档（可空） */
    val restore: String?,
    /** x2 放大档（可空；是否真的用、用几段由"源/画布"比值决定） */
    val upscale: String?,
) {
    Light(R.string.player_anime4k_tier_light, RESTORE_CNN_S, null),
    Standard(R.string.player_anime4k_tier_standard, RESTORE_CNN_S, UPSCALE_CNN_X2_S),
    High(R.string.player_anime4k_tier_high, RESTORE_CNN_M, UPSCALE_CNN_X2_M),
    Ultra(R.string.player_anime4k_tier_ultra, RESTORE_CNN_L, UPSCALE_CNN_X2_L),
    UpscaleOnly(R.string.player_anime4k_tier_upscale, null, UPSCALE_CNN_X2_S),
    ;

    /** 整条链用到的素材（先修复后放大；Clamp/Deblur 由 compose 拼装） */
    val assets: List<String> get() = listOfNotNull(restore, upscale)

    companion object {

        val default: Anime4kTier = Standard

        fun current(): Anime4kTier {
            val name = KV.get(HawkConfig.ANIME4K_TIER, default.name)
            return entries.firstOrNull { it.name == name } ?: default
        }
    }
}

object Anime4kSettings {

    /** 链末锐化强度默认值(0~1):拉满也不出白边(邻域钳制),觉得过火往下拖即可 */
    const val DEFAULT_SHARPEN = 1.00f

    @Volatile
    private var sharpenCached = -1f

    fun enabled(): Boolean = KV.get(HawkConfig.ANIME4K_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        KV.put(HawkConfig.ANIME4K_ENABLED, enabled)
    }

    /** 链内去模糊(Deblur_DoG):改它要重播本集(链的 pass 组成变了) */
    fun deblur(): Boolean = KV.get(HawkConfig.ANIME4K_DEBLUR, false)

    fun setDeblur(enabled: Boolean) {
        KV.put(HawkConfig.ANIME4K_DEBLUR, enabled)
    }

    fun sharpen(): Float {
        if (sharpenCached < 0f) {
            sharpenCached = KV.get(HawkConfig.ANIME4K_SHARPEN, DEFAULT_SHARPEN).coerceIn(0f, 1f)
        }
        return sharpenCached
    }

    fun setSharpen(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        sharpenCached = clamped
        KV.put(HawkConfig.ANIME4K_SHARPEN, clamped)
    }
}

object Anime4kStatus {

    @Volatile
    private var failed = false

    /** 最近一次构建是否失败（换档/重播会各构建一次，以最后一次为准） */
    fun unavailable(): Boolean = failed

    fun onBuildSucceeded() {
        failed = false
    }

    fun onBuildFailed() {
        failed = true
    }

    /** 「按住对比」期间置位：链退化成纯拷贝 —— 对比必须绕整条链（含 Anime4K），否则对比失真 */
    @Volatile
    private var bypassed = false

    fun bypass(): Boolean = bypassed

    fun setBypass(value: Boolean) {
        bypassed = value
    }
}
