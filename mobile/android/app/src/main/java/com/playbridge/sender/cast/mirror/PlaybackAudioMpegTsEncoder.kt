package com.playbridge.sender.cast.mirror

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Captures permitted app playback and encodes AAC-LC frames for the external mirror transport. */
internal class PlaybackAudioMpegTsEncoder private constructor(
    private val audioRecord: AudioRecord,
    private val codec: MediaCodec,
    private val sampleRate: Int,
    private val channelCount: Int,
    private val onFrame: (ByteArray, Long) -> Unit,
    private val onActive: () -> Unit,
    private val onError: (Throwable) -> Unit,
) : Closeable {
    private val running = AtomicBoolean(false)
    private val prepared = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val thread = Thread(::capture, "PlayBridgeMirrorAudio").apply { isDaemon = true }
    private var submittedFrames = 0L
    private var firstPresentationTimeUs = 0L

    /** Starts the platform capture synchronously so callers can advertise audio accurately. */
    fun prepare(): Boolean {
        if (closed.get()) return false
        if (prepared.get()) return true
        try {
            codec.start()
            audioRecord.startRecording()
            if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IOException("Playback audio capture did not enter the recording state")
            }
            firstPresentationTimeUs = System.nanoTime() / 1_000L
            prepared.set(true)
            onActive()
            return true
        } catch (error: Throwable) {
            onError(error)
            return false
        }
    }

    fun start() {
        if (!prepared.get() || !running.compareAndSet(false, true)) return
        thread.start()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        running.set(false)
        if (prepared.get()) runCatching { audioRecord.stop() }
        if (thread.isAlive && Thread.currentThread() !== thread) {
            thread.interrupt()
            thread.join(STOP_TIMEOUT_MS)
        }
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { audioRecord.release() }
    }

    private fun capture() {
        val info = MediaCodec.BufferInfo()
        try {
            while (running.get()) {
                feedInput()
                drainOutput(info)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Throwable) {
            if (!closed.get()) onError(error)
        }
    }

    private fun feedInput() {
        val index = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
        if (index < 0) return
        val input = codec.getInputBuffer(index) ?: return
        input.clear()
        val bytesRead = audioRecord.read(input, input.remaining(), AudioRecord.READ_BLOCKING)
        if (bytesRead < 0) throw IOException("Playback audio read failed: $bytesRead")
        if (bytesRead == 0) {
            codec.queueInputBuffer(index, 0, 0, presentationTimeUs(), 0)
            return
        }
        codec.queueInputBuffer(index, 0, bytesRead, presentationTimeUs(), 0)
        submittedFrames += bytesRead / (channelCount * PCM_BYTES_PER_SAMPLE)
    }

    private fun presentationTimeUs(): Long =
        firstPresentationTimeUs + submittedFrames * 1_000_000L / sampleRate

    private fun drainOutput(info: MediaCodec.BufferInfo) {
        while (true) {
            when (val index = codec.dequeueOutputBuffer(info, 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                else -> if (index >= 0) drainBuffer(index, info)
            }
        }
    }

    private fun drainBuffer(index: Int, info: MediaCodec.BufferInfo) {
        try {
            if (info.size <= 0 || (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) return
            val buffer = codec.getOutputBuffer(index) ?: return
            buffer.position(info.offset)
            buffer.limit(info.offset + info.size)
            val accessUnit = ByteArray(info.size)
            buffer.get(accessUnit)
            onFrame(adtsFrame(accessUnit, sampleRate, channelCount), info.presentationTimeUs)
        } finally {
            codec.releaseOutputBuffer(index, false)
        }
    }

    companion object {
        private const val MIME_AAC = "audio/mp4a-latm"
        private const val SAMPLE_RATE = 48_000
        private const val CHANNEL_COUNT = 2
        private const val BITRATE_BPS = 128_000
        private const val PCM_BYTES_PER_SAMPLE = 2
        private const val DEQUEUE_TIMEOUT_US = 10_000L
        private const val STOP_TIMEOUT_MS = 2_000L

        fun create(
            projection: MediaProjection,
            onFrame: (ByteArray, Long) -> Unit,
            onActive: () -> Unit,
            onError: (Throwable) -> Unit,
        ): PlaybackAudioMpegTsEncoder? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
            val minimumBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minimumBuffer <= 0) return null
            val bufferSize = maxOf(minimumBuffer * 2, SAMPLE_RATE * CHANNEL_COUNT * PCM_BYTES_PER_SAMPLE / 10)
            val factory = ScreenMirrorPlaybackAudioRecordFactory().apply { attachProjection(projection) }
            val record = factory.createAudioRecord(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            ) ?: return null
            val codec = runCatching {
                MediaCodec.createEncoderByType(MIME_AAC).apply {
                    configure(
                        MediaFormat.createAudioFormat(MIME_AAC, SAMPLE_RATE, CHANNEL_COUNT).apply {
                            setInteger(
                                MediaFormat.KEY_AAC_PROFILE,
                                MediaCodecInfo.CodecProfileLevel.AACObjectLC,
                            )
                            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE_BPS)
                            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferSize)
                        },
                        null,
                        null,
                        MediaCodec.CONFIGURE_FLAG_ENCODE,
                    )
                }
            }.getOrElse {
                record.release()
                onError(it)
                return null
            }
            return PlaybackAudioMpegTsEncoder(
                audioRecord = record,
                codec = codec,
                sampleRate = SAMPLE_RATE,
                channelCount = CHANNEL_COUNT,
                onFrame = onFrame,
                onActive = onActive,
                onError = onError,
            )
        }
    }
}

internal fun adtsFrame(accessUnit: ByteArray, sampleRate: Int, channelCount: Int): ByteArray {
    require(channelCount in 1..7)
    val frequencyIndex = when (sampleRate) {
        96_000 -> 0
        88_200 -> 1
        64_000 -> 2
        48_000 -> 3
        44_100 -> 4
        32_000 -> 5
        24_000 -> 6
        22_050 -> 7
        16_000 -> 8
        12_000 -> 9
        11_025 -> 10
        8_000 -> 11
        7_350 -> 12
        else -> throw IllegalArgumentException("Unsupported AAC sample rate: $sampleRate")
    }
    val frameLength = accessUnit.size + ADTS_HEADER_BYTES
    require(frameLength <= 0x1fff)
    return ByteArray(frameLength).apply {
        this[0] = 0xff.toByte()
        this[1] = 0xf1.toByte()
        this[2] = ((AAC_LC_ADTS_PROFILE shl 6) or (frequencyIndex shl 2) or (channelCount ushr 2)).toByte()
        this[3] = (((channelCount and 0x03) shl 6) or (frameLength ushr 11)).toByte()
        this[4] = (frameLength ushr 3).toByte()
        this[5] = (((frameLength and 0x07) shl 5) or 0x1f).toByte()
        this[6] = 0xfc.toByte()
        accessUnit.copyInto(this, destinationOffset = ADTS_HEADER_BYTES)
    }
}

private const val ADTS_HEADER_BYTES = 7
private const val AAC_LC_ADTS_PROFILE = 1
