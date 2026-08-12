package com.playbridge.sender.cast.mirror

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.Surface
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Conflates display updates and renders the latest frame to MediaRecorder at a
 * stable cadence. VirtualDisplay otherwise follows high-refresh displays in
 * bursts and can stop producing samples while the screen is static.
 */
internal class MirrorFramePacer(
    private val outputWidth: Int,
    private val outputHeight: Int,
    private val inputWidth: Int,
    private val inputHeight: Int,
    private val framesPerSecond: Int,
    private val outputSurface: Surface,
    private val onError: (Throwable) -> Unit,
) : Closeable {
    private val thread = HandlerThread("PlayBridgeMirrorFrames", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)
    private val running = AtomicBoolean(false)
    private val framePending = AtomicBoolean(false)
    private val failed = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val frameIntervalNs = 1_000_000_000L / framesPerSecond

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var textureId = 0
    private var programId = 0
    private var positionLocation = -1
    private var textureLocation = -1
    private var mvpMatrixLocation = -1
    private var textureMatrixLocation = -1
    private var surfaceTexture: SurfaceTexture? = null
    private var sourceSurface: Surface? = null
    private var hasFrame = false
    private var nextFrameNs = 0L
    private val mvpMatrix = FloatArray(16)
    private val textureMatrix = FloatArray(16)

    val inputSurface: Surface
        get() = checkNotNull(sourceSurface) { "Mirror input surface is unavailable" }

    init {
        require(outputWidth > 0 && outputHeight > 0)
        require(inputWidth > 0 && inputHeight > 0)
        require(framesPerSecond > 0)
        val ready = CountDownLatch(1)
        var setupError: Throwable? = null
        handler.post {
            runCatching { setup() }
                .onFailure { setupError = it }
            ready.countDown()
        }
        check(ready.await(SETUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "Timed out initializing mirror renderer" }
        setupError?.let {
            close()
            throw IllegalStateException("Unable to initialize mirror renderer", it)
        }
        sourceSurface = Surface(checkNotNull(surfaceTexture))
    }

    fun start() {
        if (running.compareAndSet(false, true)) {
            nextFrameNs = 0L
            handler.post(renderTask)
        }
    }

    /**
     * Updates the VirtualDisplay producer size while the recorder's encoded
     * landscape canvas remains fixed. Keeping the coded resolution stable
     * prevents Google Cast from stalling on a mid-stream orientation change.
     */
    fun resizeInput(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || closed.get()) return
        handler.post {
            surfaceTexture?.setDefaultBufferSize(width, height)
            updateMvpMatrix(width, height)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        running.set(false)
        if (Thread.currentThread() === thread) {
            releaseOnRenderThread()
            return
        }
        val stopped = CountDownLatch(1)
        if (handler.post {
                runCatching { releaseOnRenderThread() }
                stopped.countDown()
            }
        ) {
            stopped.await(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        if (thread.isAlive) thread.quitSafely()
    }

    private val renderTask = object : Runnable {
        override fun run() {
            if (!running.get()) return
            runCatching { renderNextFrame() }
                .onFailure(::reportFailure)
            if (!running.get()) return

            val nowNs = System.nanoTime()
            if (nextFrameNs < nowNs - frameIntervalNs * MAX_CATCH_UP_FRAMES) {
                nextFrameNs = nowNs
            }
            val delayMs = ((nextFrameNs - nowNs).coerceAtLeast(0L) / NANOS_PER_MILLISECOND)
            handler.postDelayed(this, delayMs)
        }
    }

    private fun renderNextFrame() {
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            "eglMakeCurrent failed: 0x${Integer.toHexString(EGL14.eglGetError())}"
        }

        val texture = checkNotNull(surfaceTexture)
        if (framePending.getAndSet(false)) {
            texture.updateTexImage()
            texture.getTransformMatrix(textureMatrix)
            hasFrame = true
        }
        if (!hasFrame) {
            nextFrameNs = System.nanoTime() + frameIntervalNs
            return
        }

        val presentationNs = if (nextFrameNs == 0L) System.nanoTime() else nextFrameNs
        drawFrame()
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, presentationNs)
        check(EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
            "eglSwapBuffers failed: 0x${Integer.toHexString(EGL14.eglGetError())}"
        }
        nextFrameNs = presentationNs + frameIntervalNs
    }

    private fun setup() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "Unable to get EGL display" }
        val versions = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, versions, 0, versions, 1)) { "Unable to initialize EGL" }

        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val configCount = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, attributes, 0, configs, 0, 1, configCount, 0)) {
            "Unable to choose EGL config"
        }
        val config = checkNotNull(configs.firstOrNull()).also {
            check(configCount[0] > 0) { "No recordable EGL config" }
        }

        eglContext = EGL14.eglCreateContext(
            eglDisplay,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0,
        )
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "Unable to create EGL context" }
        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay,
            config,
            outputSurface,
            intArrayOf(EGL14.EGL_NONE),
            0,
        )
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "Unable to create recorder EGL surface" }
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            "Unable to activate recorder EGL surface"
        }

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)

        surfaceTexture = SurfaceTexture(textureId).apply {
            setDefaultBufferSize(inputWidth, inputHeight)
            setOnFrameAvailableListener({ framePending.set(true) }, handler)
        }
        updateMvpMatrix(inputWidth, inputHeight)
        programId = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionLocation = GLES20.glGetAttribLocation(programId, "aPosition")
        textureLocation = GLES20.glGetAttribLocation(programId, "aTextureCoord")
        mvpMatrixLocation = GLES20.glGetUniformLocation(programId, "uMvpMatrix")
        textureMatrixLocation = GLES20.glGetUniformLocation(programId, "uTextureMatrix")
        GLES20.glViewport(0, 0, outputWidth, outputHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
    }

    private fun updateMvpMatrix(inputWidth: Int, inputHeight: Int) {
        val (scaleX, scaleY) = mirrorFrameFitScale(
            inputWidth = inputWidth,
            inputHeight = inputHeight,
            outputWidth = outputWidth,
            outputHeight = outputHeight,
        )
        Matrix.setIdentityM(mvpMatrix, 0)
        Matrix.scaleM(mvpMatrix, 0, scaleX, -scaleY, 1f)
    }

    private fun drawFrame() {
        // Clear the fixed landscape encoder canvas before drawing a letterboxed
        // portrait frame; otherwise pixels from the preceding landscape frame remain.
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(programId)
        GLES20.glUniformMatrix4fv(mvpMatrixLocation, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(textureMatrixLocation, 1, false, textureMatrix, 0)
        GLES20.glEnableVertexAttribArray(positionLocation)
        GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(textureLocation)
        GLES20.glVertexAttribPointer(textureLocation, 2, GLES20.GL_FLOAT, false, 0, textureBuffer)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        GLES20.glDisableVertexAttribArray(positionLocation)
        GLES20.glDisableVertexAttribArray(textureLocation)
        GLES20.glUseProgram(0)
        checkGlError("draw mirror frame")
    }

    private fun releaseOnRenderThread() {
        handler.removeCallbacksAndMessages(null)
        surfaceTexture?.setOnFrameAvailableListener(null)
        sourceSurface?.release()
        sourceSurface = null
        surfaceTexture?.release()
        surfaceTexture = null

        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
            if (programId != 0) GLES20.glDeleteProgram(programId)
            if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
            EGL14.eglReleaseThread()
        }
        outputSurface.release()
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
        thread.quitSafely()
    }

    private fun reportFailure(error: Throwable) {
        if (!failed.compareAndSet(false, true)) return
        running.set(false)
        onError(error)
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        val error = GLES20.glGetProgramInfoLog(program)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        check(linked[0] == GLES20.GL_TRUE) { "Unable to link mirror shader: $error" }
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        val error = GLES20.glGetShaderInfoLog(shader)
        check(compiled[0] == GLES20.GL_TRUE) {
            GLES20.glDeleteShader(shader)
            "Unable to compile mirror shader: $error"
        }
        return shader
    }

    private fun checkGlError(operation: String) {
        val error = GLES20.glGetError()
        check(error == GLES20.GL_NO_ERROR) { "$operation failed with GL error $error" }
    }

    private companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private const val SETUP_TIMEOUT_SECONDS = 3L
        private const val STOP_TIMEOUT_SECONDS = 2L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val MAX_CATCH_UP_FRAMES = 2L
        private val vertexBuffer = floatBufferOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        private val textureBuffer = floatBufferOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)

        private fun floatBufferOf(vararg values: Float): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(values)
                    position(0)
                }

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            uniform mat4 uMvpMatrix;
            uniform mat4 uTextureMatrix;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = uMvpMatrix * aPosition;
                vTextureCoord = (uTextureMatrix * aTextureCoord).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTextureCoord);
            }
        """
    }
}

internal fun externalMirrorEncoderSize(width: Int, height: Int): Pair<Int, Int> =
    maxOf(width, height) to minOf(width, height)

internal fun mirrorFrameFitScale(
    inputWidth: Int,
    inputHeight: Int,
    outputWidth: Int,
    outputHeight: Int,
): Pair<Float, Float> {
    val inputAspect = inputWidth.toFloat() / inputHeight

    val outputAspect = outputWidth.toFloat() / outputHeight
    return if (inputAspect > outputAspect) {
        1f to (outputAspect / inputAspect)
    } else {
        (inputAspect / outputAspect) to 1f
    }
}
