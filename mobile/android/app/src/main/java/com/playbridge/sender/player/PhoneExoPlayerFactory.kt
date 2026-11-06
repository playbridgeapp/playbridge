package com.playbridge.sender.player

import android.content.Context
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.text.TextRenderer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.playbridge.shared.player.AndroidBufferConfig
import com.playbridge.shared.player.ExoPlayerSetup

/**
 * Phone in-app [ExoPlayer] builder — aligned with TV
 * [com.playbridge.shared.player.ExoPlayerEngine]:
 *
 * - FFmpeg audio extension preferred (`prebuilt/media3/lib-decoder-ffmpeg-release.aar`)
 *   Built for Media3 **1.9.x** (same catalog as phone/TV); see `prebuilt/media3/README.md`
 * - hardware-first video
 * - decoder fallback + synchronous MediaCodec
 * - audio offload off; no audio-focus fight with Browser
 * - load control from [AndroidBufferConfig] (remote-friendly caps; not the old 300s/128MB heap risk)
 */
object PhoneExoPlayerFactory {
    private const val TAG = "PB_PLAYER"
    private const val DEFAULT_UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    /** True when the shared FFmpeg AAR loaded its native lib (primary fix for silent video). */
    fun isFfmpegAvailable(): Boolean = try {
        FfmpegLibrary.isAvailable()
    } catch (_: Throwable) {
        false
    }

    /** Native FFmpeg lib version when the AAR loaded; null if unavailable. */
    fun ffmpegVersionOrNull(): String? = try {
        if (!FfmpegLibrary.isAvailable()) null else FfmpegLibrary.getVersion()
    } catch (_: Throwable) {
        null
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun create(context: Context, headers: Map<String, String> = emptyMap()): ExoPlayer {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(30_000)
            .setReadTimeoutMs(30_000)
            .setUserAgent(headers["User-Agent"] ?: DEFAULT_UA)
        if (headers.isNotEmpty()) {
            httpFactory.setDefaultRequestProperties(headers)
        }
        val dataSourceFactory: DataSource.Factory =
            DefaultDataSource.Factory(context, httpFactory)

        // Same memory-aware caps and decoder order as the TV.
        val bufCfg = AndroidBufferConfig.compute(context)
        val loadControl = ExoPlayerSetup.loadControl(context)

        val ffmpegOk = isFfmpegAvailable()
        val ffmpegVer = ffmpegVersionOrNull()
        Log.i(
            TAG,
            "ExoPlayer factory ffmpeg=$ffmpegOk version=${ffmpegVer ?: "n/a"} " +
                "maxBufferMs=${bufCfg.maxBufferMs} targetBytes=${bufCfg.targetBytes}",
        )
        if (!ffmpegOk) {
            // Phone silent-video bugs were largely fixed by this AAR — surface a clear log.
            Log.e(
                TAG,
                "FFmpeg audio decoder NOT available — check prebuilt/media3 AAR packaging " +
                    "(Media3 1.9.x, arm64-v8a/armeabi-v7a). Platform audio may fail on some files.",
            )
        }

        // FFmpeg audio first ("video plays, no audio" fix), hardware video first.
        val renderersFactory = ExoPlayerSetup.renderersFactory(context, synchronousCodecs = true) { output, looper, out ->
            out.add(TextRenderer(output, looper).apply {
                @Suppress("DEPRECATION")
                experimentalSetLegacyDecodingEnabled(true)
            })
        }

        val trackSelector = DefaultTrackSelector(context).apply {
            parameters = buildUponParameters()
                .setExceedVideoConstraintsIfNecessary(true)
                .setExceedRendererCapabilitiesIfNecessary(true)
                .setAudioOffloadPreferences(
                    TrackSelectionParameters.AudioOffloadPreferences.Builder()
                        .setAudioOffloadMode(
                            TrackSelectionParameters.AudioOffloadPreferences
                                .AUDIO_OFFLOAD_MODE_DISABLED,
                        )
                        .build(),
                )
                .build()
        }

        val audioAttrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        return ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(dataSourceFactory)
                    .setSubtitleParserFactory(SniffingSubtitleParserFactory()),
            )
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttrs, /* handleAudioFocus = */ false)
            .setHandleAudioBecomingNoisy(true)
            .setSeekForwardIncrementMs(10_000)
            .build()
    }

    /**
     * TV-style audio recovery (discontinuity / decoder-init).
     * Stages: `reinit` | `clear-override` | `drop-audio` | default play nudge.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun recoverAudio(player: ExoPlayer, stage: String): Boolean {
        val pos = player.currentPosition.coerceAtLeast(0)
        Log.w(TAG, "recoverAudio[$stage] pos=$pos state=${player.playbackState}")
        return when {
            stage.contains("drop-audio") -> {
                if (!player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_AUDIO)) {
                    player.trackSelectionParameters = player.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                        .build()
                }
                player.seekTo(pos)
                player.prepare()
                player.playWhenReady = true
                true
            }
            stage.contains("reinit") -> {
                player.seekTo(pos)
                player.prepare()
                player.playWhenReady = true
                player.play()
                true
            }
            stage.contains("clear-override") -> {
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                    .build()
                player.seekTo(pos)
                player.prepare()
                player.playWhenReady = true
                true
            }
            else -> {
                player.playWhenReady = true
                player.play()
                true
            }
        }
    }

    /** Force mime only when it changes MediaSource type (HLS/DASH/TS/…); not progressive MP4. */
    fun mapContentTypeToMime(contentType: String?, url: String): String? {
        val path = url.substringBefore('?')
        return when {
            url.contains(".m3u8", ignoreCase = true) ||
                url.startsWith("data:application/x-mpegurl", ignoreCase = true) ||
                contentType?.contains("mpegurl", ignoreCase = true) == true ->
                MimeTypes.APPLICATION_M3U8

            url.contains(".mpd", ignoreCase = true) ||
                contentType?.contains("dash", ignoreCase = true) == true ->
                MimeTypes.APPLICATION_MPD

            contentType?.contains("mp2t", ignoreCase = true) == true ||
                contentType?.contains("mpegts", ignoreCase = true) == true ||
                contentType?.contains("mpeg-ts", ignoreCase = true) == true ||
                path.endsWith(".ts", ignoreCase = true) ->
                MimeTypes.VIDEO_MP2T

            contentType?.contains("matroska", ignoreCase = true) == true ||
                contentType?.contains("webm", ignoreCase = true) == true ->
                MimeTypes.VIDEO_MATROSKA

            contentType?.contains("avi", ignoreCase = true) == true ->
                MimeTypes.VIDEO_AVI

            else -> null
        }
    }
}
