package com.playbridge.sender.downloads.engine

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

/**
 * Remuxes a single progressive media file (concatenated MPEG-TS, or fMP4 init+segments)
 * into MP4 — stream copy, no re-encode, no FFmpeg.
 *
 * We deliberately do NOT use Media3 `Transformer` with an HLS source here: that path hit
 * `ERROR_CODE_MUXING_TIMEOUT` (the export watchdog aborting because the HLS reader stalled
 * before delivering samples). The platform `MediaExtractor` → `MediaMuxer` sample copy is
 * the canonical, deterministic "merge HLS segments to MP4" approach on Android and has no
 * watchdog. `HlsStrategy` does the segment download + decrypt + concatenation; this only
 * remuxes the resulting container.
 */
interface Muxer {
    suspend fun remux(input: File, output: File): File
}

class PlatformMuxer : Muxer {

    override suspend fun remux(input: File, output: File): File = withContext(Dispatchers.IO) {
        if (output.exists()) output.delete()
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(input.absolutePath)
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val trackMap = HashMap<Int, Int>()
            val aacTracks = HashMap<Int, AacTrack>()
            var maxInputSize = DEFAULT_BUFFER
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                        aacTracks[i] = prepareAacTrack(input, i, format)
                    }
                    extractor.selectTrack(i)
                    trackMap[i] = muxer.addTrack(format)
                    if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        maxInputSize = maxOf(maxInputSize, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                    }
                }
            }
            if (trackMap.isEmpty()) error("No audio/video tracks found")

            muxer.start()
            val buffer = ByteBuffer.allocate(maxInputSize)
            val info = MediaCodec.BufferInfo()
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val muxTrack = trackMap[extractor.sampleTrackIndex]
                if (muxTrack == null) { extractor.advance(); continue }
                buffer.position(0)
                buffer.limit(size)
                val aacTrack = aacTracks[extractor.sampleTrackIndex]
                val aacSample = aacTrack?.let {
                    AdtsAac.split(buffer, extractor.sampleTime, it.lastTimeUs)
                }
                if (aacTrack != null && aacSample != null &&
                    (aacTrack.config == null || aacTrack.config == aacSample.config)
                ) {
                    for (frame in aacSample.frames) {
                        info.set(frame.offset, frame.size, frame.presentationTimeUs, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                        muxer.writeSampleData(muxTrack, buffer, info)
                    }
                    aacTrack.lastTimeUs = aacSample.frames.last().presentationTimeUs
                    extractor.advance()
                    continue
                }
                if (aacTrack != null && (aacTrack.adts || AdtsAac.hasSync(buffer))) {
                    Log.w(TAG, "AAC sample could not be split as ADTS; copying unchanged")
                }
                info.offset = 0
                info.size = size
                info.presentationTimeUs = extractor.sampleTime
                info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else 0
                muxer.writeSampleData(muxTrack, buffer, info)
                aacTrack?.lastTimeUs = extractor.sampleTime
                extractor.advance()
            }
            muxer.stop()
            Log.d(TAG, "Remux complete: ${output.length()} bytes")
        } finally {
            runCatching { muxer?.release() }
            extractor.release()
        }
        output
    }

    private data class AacTrack(val adts: Boolean, val config: AdtsAac.Config?, var lastTimeUs: Long? = null)

    private fun prepareAacTrack(input: File, track: Int, format: MediaFormat): AacTrack {
        val declaredAdts = format.containsKey(MediaFormat.KEY_IS_ADTS) &&
            format.getInteger(MediaFormat.KEY_IS_ADTS) == 1
        // Probe independently so registration never advances or seeks the video extractor.
        val probe = MediaExtractor()
        try {
            probe.setDataSource(input.absolutePath)
            probe.selectTrack(track)
            val capacity = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                maxOf(DEFAULT_BUFFER, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
            } else DEFAULT_BUFFER
            val buffer = ByteBuffer.allocate(capacity)
            val size = probe.readSampleData(buffer, 0)
            buffer.position(0)
            buffer.limit(maxOf(0, size))
            val sample = AdtsAac.split(buffer, 0)
            val adts = declaredAdts || sample != null
            val config = sample?.config ?: if (declaredAdts) AdtsAac.configuration(buffer) else null
            if (adts) {
                format.setInteger(MediaFormat.KEY_IS_ADTS, 0)
                if (format.getByteBuffer("csd-0")?.hasRemaining() != true) {
                    if (config != null) {
                        format.setByteBuffer("csd-0", ByteBuffer.wrap(config.audioSpecificConfig()))
                    } else {
                        Log.w(TAG, "AAC codec data unavailable from first ADTS sample")
                    }
                }
            }
            return AacTrack(adts, config)
        } finally {
            probe.release()
        }
    }

    private companion object {
        const val TAG = "PlatformMuxer"
        const val DEFAULT_BUFFER = 1 shl 21 // 2 MB
    }
}
