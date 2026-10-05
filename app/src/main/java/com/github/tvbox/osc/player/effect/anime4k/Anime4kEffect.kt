package com.github.tvbox.osc.player.effect.anime4k

import android.content.Context
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

internal class Anime4kEffect : GlEffect {

    @Volatile
    var tier: Anime4kTier? = null

    /** media3 不消费 isNoOp:tier=null 同样会被实例化,关闭态必须由链自身兜住(纯拷贝),不得兜底成任何档位 */
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        Anime4kChainProgram(context, tier, useHdr)

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = tier == null
}
