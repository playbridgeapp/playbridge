package com.playbridge.shared.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.video.VideoRendererEventListener
import java.util.ArrayList

/**
 * The ExoPlayer building blocks the TV renderer ([ExoPlayerEngine]) and the phone player share,
 * so buffering and decoder choice behave the same on both.
 */
@OptIn(UnstableApi::class)
object ExoPlayerSetup {
    /** Memory-aware buffering from [AndroidBufferConfig]; no back buffer. */
    fun loadControl(context: Context): DefaultLoadControl {
        val config = AndroidBufferConfig.compute(context)
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ config.maxBufferMs,
                /* bufferForPlaybackMs = */ 2_500,
                /* bufferForPlaybackAfterRebufferMs = */ 5_000,
            )
            .setTargetBufferBytes(config.targetBytes)
            .setPrioritizeTimeOverSizeThresholds(config.prioritizeTime)
            .setBackBuffer(/* backBufferDurationMs = */ 0, /* retainBackBufferFromKeyframe = */ false)
            .build()
    }

    /**
     * FFmpeg audio first, hardware video first, with decoder fallback.
     *
     * @param synchronousCodecs operate MediaCodec synchronously; some vendor decoders (MTK TV
     *   panels) crash in the API 31+ asynchronous mode yet decode the same stream fine synchronously.
     * @param blockDolbyVision skip Dolby Vision decoders so the HEVC/AVC base layer is decoded
     *   instead (MTK DV decoders accept dvhe.08 then die with 0xfffffff4).
     * @param textRenderers builds the subtitle renderers; null keeps Media3's own.
     */
    fun renderersFactory(
        context: Context,
        synchronousCodecs: Boolean,
        blockDolbyVision: Boolean = false,
        textRenderers: ((TextOutput, Looper, ArrayList<Renderer>) -> Unit)? = null,
    ): DefaultRenderersFactory = object : DefaultRenderersFactory(context) {
        init {
            // PREFER exists for AUDIO: platform decoders frequently misdeclare or
            // botch DTS/TrueHD/E-AC3, so the FFmpeg audio renderer goes first.
            // Video is forced back to hardware-first in buildVideoRenderers below.
            setExtensionRendererMode(EXTENSION_RENDERER_MODE_PREFER)
            // If the primary decoder fails to initialize, let MediaCodecRenderer
            // try the next capable decoder (alternate vendor codec, then software)
            // instead of surfacing a fatal playback error.
            setEnableDecoderFallback(true)
            if (synchronousCodecs) forceDisableMediaCodecAsynchronousQueueing()
            if (blockDolbyVision) {
                // Media3 already appends the HEVC/AVC base-layer decoders as fallbacks for
                // DV profiles 8/9, so this renders the same content as HDR10.
                setMediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                    if (mimeType == MimeTypes.VIDEO_DOLBY_VISION) {
                        emptyList()
                    } else {
                        MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
                    }
                }
            }
        }

        override fun buildVideoRenderers(
            context: Context,
            extensionRendererMode: Int,
            mediaCodecSelector: MediaCodecSelector,
            enableDecoderFallback: Boolean,
            eventHandler: Handler,
            eventListener: VideoRendererEventListener,
            allowedVideoJoiningTimeMs: Long,
            out: ArrayList<Renderer>,
        ) {
            // Hardware MediaCodec must stay first for video: with PREFER, the
            // FFmpeg SOFTWARE video renderer would front-run the hardware decoder
            // for any codec the FFmpeg build supports — software video decode is
            // a stutter/thermal crash on TV silicon. ON keeps the extension
            // renderer available strictly as a last-resort fallback.
            super.buildVideoRenderers(
                context,
                EXTENSION_RENDERER_MODE_ON,
                mediaCodecSelector,
                enableDecoderFallback,
                eventHandler,
                eventListener,
                allowedVideoJoiningTimeMs,
                out,
            )
        }

        override fun buildTextRenderers(
            context: Context,
            output: TextOutput,
            outputLooper: Looper,
            extensionRendererMode: Int,
            out: ArrayList<Renderer>,
        ) {
            if (textRenderers != null) {
                textRenderers(output, outputLooper, out)
            } else {
                super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out)
            }
        }
    }
}
