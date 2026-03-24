package com.playbridge.player.player

import android.content.Context
import android.graphics.ColorMatrix
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import androidx.annotation.OptIn

/**
 * A Media3 [GlEffect] that applies an Android [ColorMatrix] using a custom GLSL fragment shader.
 * The matrix can be updated dynamically without tearing down the video pipeline.
 */
@OptIn(UnstableApi::class)
class ColorMatrixEffect : GlEffect {
    private var shaderProgram: ColorMatrixShaderProgram? = null
    private var currentMatrix = ColorMatrix()

    fun setMatrix(matrix: ColorMatrix) {
        currentMatrix = matrix
        shaderProgram?.setMatrix(matrix)
    }

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        val program = ColorMatrixShaderProgram(context, useHdr, currentMatrix)
        shaderProgram = program
        return program
    }
}

@OptIn(UnstableApi::class)
private class ColorMatrixShaderProgram(
    context: Context,
    useHdr: Boolean,
    initialMatrix: ColorMatrix
) : BaseGlShaderProgram(useHdr, 1) {

    private val glProgram: GlProgram
    @Volatile
    private var colorMat4 = FloatArray(16)
    @Volatile
    private var colorOffset = FloatArray(4)

    init {
        setMatrix(initialMatrix)

        try {
            glProgram = GlProgram(context, "shaders/color_matrix_vertex.glsl", "shaders/color_matrix_fragment.glsl")
            // The texture uniform matches the base class implementation which automatically binds the input texture
            glProgram.setBufferAttribute(
                "aFramePosition",
                GlUtil.getNormalizedCoordinateBounds(),
                GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
            )
        } catch (e: Exception) {
            throw VideoFrameProcessingException(e)
        }
    }

    fun setMatrix(matrix: ColorMatrix) {
        val array = matrix.array
        
        // Create new arrays so GlProgram detects the reference change and marks the uniform as dirty
        val newMat4 = FloatArray(16)
        val newOffset = FloatArray(4)

        // R scale
        newMat4[0] = array[0]
        newMat4[4] = array[1]
        newMat4[8] = array[2]
        newMat4[12] = array[3]

        // G scale
        newMat4[1] = array[5]
        newMat4[5] = array[6]
        newMat4[9] = array[7]
        newMat4[13] = array[8]

        // B scale
        newMat4[2] = array[10]
        newMat4[6] = array[11]
        newMat4[10] = array[12]
        newMat4[14] = array[13]

        // A scale
        newMat4[3] = array[15]
        newMat4[7] = array[16]
        newMat4[11] = array[17]
        newMat4[15] = array[18]

        // Offsets
        newOffset[0] = array[4] / 255f
        newOffset[1] = array[9] / 255f
        newOffset[2] = array[14] / 255f
        newOffset[3] = array[19] / 255f
        
        colorMat4 = newMat4
        colorOffset = newOffset
    }

    override fun configure(inputWidth: Int, inputHeight: Int): androidx.media3.common.util.Size {
        return androidx.media3.common.util.Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            glProgram.setFloatsUniform("uColorMatrix", colorMat4)
            glProgram.setFloatsUniform("uColorOffset", colorOffset)
            glProgram.bindAttributesAndUniforms()
            
            // Draw
            android.opengl.GLES20.glDrawArrays(android.opengl.GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
        } catch (e: Exception) {
            throw VideoFrameProcessingException(e)
        }
    }

    override fun release() {
        super.release()
        try {
            glProgram.delete()
        } catch (e: Exception) {
            // Ignore on release
        }
    }
}
