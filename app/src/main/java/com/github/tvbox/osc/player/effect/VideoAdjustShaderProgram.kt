package com.github.tvbox.osc.player.effect

import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram

/** 画质调色着色器基类:全屏四边形 + 采样;参数按帧从 effect 实例现读,故调参不需要重建效果链 */
internal abstract class VideoAdjustShaderProgram(useHdr: Boolean, fragmentShader: String) :
    BaseGlShaderProgram(false, 1) {

    protected val glProgram: GlProgram

    // HDR(PQ/HLG)数据按 SDR 取值来算会得出错画面,只能退化为纯拷贝 —— 但绝不能抛异常,那会变成"播放出错"
    private val passthrough = useHdr

    init {
        try {
            glProgram = GlProgram(VERTEX_SHADER, if (passthrough) PASSTHROUGH_SHADER else fragmentShader)
            glProgram.setBufferAttribute(
                "aFramePosition",
                GlUtil.getNormalizedCoordinateBounds(),
                GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
            )
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size = Size(inputWidth, inputHeight)

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            if (!passthrough) bindUniforms()
            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    /** 绑定本帧参数(在 glProgram.use() 之后调用;HDR 退化路径不会调用) */
    protected abstract fun bindUniforms()

    override fun release() {
        super.release()
        try {
            glProgram.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    private companion object {
        const val VERTEX_SHADER = """
            attribute vec4 aFramePosition;
            varying vec2 vTexSamplingCoord;
            void main() {
              gl_Position = aFramePosition;
              vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;
            }
            """

        const val PASSTHROUGH_SHADER = """
            precision highp float;
            uniform sampler2D uTexSampler;
            varying vec2 vTexSamplingCoord;
            void main() {
              gl_FragColor = texture2D(uTexSampler, vTexSamplingCoord);
            }
            """
    }
}
