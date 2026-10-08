package com.playbridge.sender.cast.mirror

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** How the external mirror fills the AAC elementary stream. WebRTC is unchanged. */
internal enum class MirrorAudioPlan {
    /** Device playback capture is running and supplies AAC frames. */
    PLAYBACK,

    /** Playback capture is off or unavailable, so the transport carries silence. */
    SILENT,
}

/**
 * DLNA renderers that reject a video-only MPEG-TS still need an audio PID.
 * Silence is used when the user disabled device audio, the API is below 29,
 * or playback capture did not start.
 */
internal fun mirrorAudioPlan(
    deviceAudioRequested: Boolean,
    playbackCaptureApiAvailable: Boolean,
    playbackCapturePrepared: Boolean,
): MirrorAudioPlan = if (
    deviceAudioRequested && playbackCaptureApiAvailable && playbackCapturePrepared
) {
    MirrorAudioPlan.PLAYBACK
} else {
    MirrorAudioPlan.SILENT
}

/** Both plans advertise AAC in the PMT. The external transport is never video-only. */
internal fun mirrorTransportIncludesAudio(plan: MirrorAudioPlan): Boolean = when (plan) {
    MirrorAudioPlan.PLAYBACK, MirrorAudioPlan.SILENT -> true
}

internal const val SILENT_AAC_SAMPLE_RATE = 48_000
internal const val SILENT_AAC_CHANNEL_COUNT = 2
internal const val SILENT_AAC_FRAME_SAMPLES = 1024

/**
 * One AAC-LC raw data block of stereo digital silence at 48 kHz (1024 samples).
 * Produced by an AAC-LC encoder from PCM silence so the live feeder and unit tests
 * share a decodable access unit without calling MediaCodec.
 */
internal val SILENT_AAC_LC_STEREO_48K: ByteArray = byteArrayOf(
    0x21, 0x10, 0x04, 0x60, 0x8c.toByte(), 0x1c,
)

internal fun silentAacAdtsFrame(): ByteArray = adtsFrame(
    SILENT_AAC_LC_STEREO_48K,
    SILENT_AAC_SAMPLE_RATE,
    SILENT_AAC_CHANNEL_COUNT,
)

internal fun silentAacFrameDurationUs(sampleRate: Int = SILENT_AAC_SAMPLE_RATE): Long =
    SILENT_AAC_FRAME_SAMPLES * 1_000_000L / sampleRate

/** PTS for silent frame [frameIndex], in microseconds, on the AAC sample clock. */
internal fun silentAudioPresentationTimeUs(
    frameIndex: Long,
    sampleRate: Int = SILENT_AAC_SAMPLE_RATE,
): Long {
    require(frameIndex >= 0)
    return frameIndex * SILENT_AAC_FRAME_SAMPLES * 1_000_000L / sampleRate
}

/**
 * Writes a silent AAC-LC frame at the AAC frame period (1024 samples at 48 kHz).
 * PAT/PMT stay the muxer's job: video keyframes repeat them with the audio PID.
 */
internal class SilentAacMpegTsFeeder(
    private val muxer: H264MpegTsMuxer,
    private val sampleRate: Int = SILENT_AAC_SAMPLE_RATE,
) : Closeable {
    private val running = AtomicBoolean(false)
    private val frame = silentAacAdtsFrame()
    private val thread = Thread(::emit, "PlayBridgeMirrorSilentAudio").apply { isDaemon = true }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread.start()
    }

    override fun close() {
        running.set(false)
        if (thread.isAlive && Thread.currentThread() !== thread) {
            thread.interrupt()
            thread.join(STOP_TIMEOUT_MS)
        }
    }

    private fun emit() {
        val startNs = System.nanoTime()
        val startPtsUs = startNs / 1_000L
        var index = 0L
        try {
            while (running.get()) {
                muxer.writeAudioAccessUnit(
                    frame,
                    startPtsUs + silentAudioPresentationTimeUs(index, sampleRate),
                )
                index++
                val nextNs = startNs + silentAudioPresentationTimeUs(index, sampleRate) * 1_000L
                val waitNs = nextNs - System.nanoTime()
                if (waitNs > 0 && !sleep(waitNs)) break
            }
        } catch (_: IOException) {
            // The transport closed during teardown.
        }
    }

    private fun sleep(waitNs: Long): Boolean = try {
        Thread.sleep(waitNs / 1_000_000L, (waitNs % 1_000_000L).toInt())
        true
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    private companion object {
        private const val STOP_TIMEOUT_MS = 2_000L
    }
}
