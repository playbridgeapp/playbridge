package com.playbridge.sender.cast.mirror

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.view.Surface
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/** Surface-input H.264 encoder with a small MPEG-TS muxer for low-latency mirroring. */
internal class MediaCodecMpegTsEncoder(
    width: Int,
    height: Int,
    bitrateBps: Int,
    framesPerSecond: Int,
    projection: android.media.projection.MediaProjection,
    deviceAudio: Boolean,
    private val output: OutputStream,
    private val onError: (Throwable) -> Unit,
    onAudioActive: () -> Unit,
    onAudioError: (Throwable) -> Unit,
) : Closeable {
    private val codec = MediaCodec.createEncoderByType(MIME_AVC)
    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val muxer: H264MpegTsMuxer
    private val audioEncoder: PlaybackAudioMpegTsEncoder?
    private val drainThread = Thread(::drain, "PlayBridgeMirrorEncoder").apply { isDaemon = true }
    private var codecConfig = ByteArray(0)
    private var lastSyncRequestNs = 0L

    val inputSurface: Surface
    val hasAudio: Boolean get() = audioEncoder != null

    init {
        require(width > 0 && height > 0)
        require(bitrateBps > 0 && framesPerSecond > 0)
        val format = MediaFormat.createVideoFormat(MIME_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, framesPerSecond)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEYFRAME_INTERVAL_SECONDS)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
            }
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        val audioCandidate = if (deviceAudio) {
            PlaybackAudioMpegTsEncoder.create(
                projection = projection,
                onFrame = { accessUnit, presentationTimeUs ->
                    muxer.writeAudioAccessUnit(accessUnit, presentationTimeUs)
                },
                onActive = onAudioActive,
                onError = onAudioError,
            )
        } else {
            null
        }
        val audio = audioCandidate?.takeIf { it.prepare() }
        if (audio == null) audioCandidate?.close()
        muxer = H264MpegTsMuxer(output, includeAudio = audio != null)
        audioEncoder = audio
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        codec.start()
        lastSyncRequestNs = System.nanoTime()
        drainThread.start()
        audioEncoder?.start()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        audioEncoder?.close()
        running.set(false)
        if (drainThread.isAlive && Thread.currentThread() !== drainThread) {
            drainThread.interrupt()
            drainThread.join(STOP_TIMEOUT_MS)
        }
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { inputSurface.release() }
        runCatching { output.close() }
    }

    private fun drain() {
        val info = MediaCodec.BufferInfo()
        try {
            while (running.get()) {
                requestSyncFrameWhenDue()
                when (val index = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> updateCodecConfig(codec.outputFormat)
                    else -> if (index >= 0) drainBuffer(index, info)
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Throwable) {
            if (!closed.get()) onError(error)
        }
    }

    private fun updateCodecConfig(format: MediaFormat) {
        val config = buildList {
            format.getByteBuffer("csd-0")?.copyRemaining()?.let(::add)
            format.getByteBuffer("csd-1")?.copyRemaining()?.let(::add)
        }
        codecConfig = config.fold(ByteArray(0), ByteArray::plus).toAnnexB()
    }

    private fun drainBuffer(index: Int, info: MediaCodec.BufferInfo) {
        try {
            if (info.size <= 0) return
            val buffer = codec.getOutputBuffer(index) ?: return
            buffer.position(info.offset)
            buffer.limit(info.offset + info.size)
            val encoded = ByteArray(info.size)
            buffer.get(encoded)
            if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                codecConfig = encoded.toAnnexB()
                return
            }
            val keyFrame = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
            val accessUnit = encoded.toAnnexB()
            val payload = if (keyFrame && codecConfig.isNotEmpty() && !accessUnit.hasParameterSets()) {
                codecConfig + accessUnit
            } else {
                accessUnit
            }
            muxer.writeAccessUnit(payload, info.presentationTimeUs, keyFrame)
        } finally {
            codec.releaseOutputBuffer(index, false)
        }
    }

    private fun requestSyncFrameWhenDue() {
        val now = System.nanoTime()
        if (now - lastSyncRequestNs < KEYFRAME_INTERVAL_NS) return
        codec.setParameters(Bundle().apply {
            putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
        })
        lastSyncRequestNs = now
    }

    private companion object {
        private const val MIME_AVC = "video/avc"
        private const val KEYFRAME_INTERVAL_SECONDS = 1
        private const val KEYFRAME_INTERVAL_NS = 1_000_000_000L
        private const val DEQUEUE_TIMEOUT_US = 10_000L
        private const val STOP_TIMEOUT_MS = 2_000L
    }
}

private fun ByteBuffer.copyRemaining(): ByteArray {
    val copy = duplicate()
    return ByteArray(copy.remaining()).also(copy::get)
}

internal fun ByteArray.toAnnexB(): ByteArray {
    if (hasAnnexBStartCode()) return this
    val output = java.io.ByteArrayOutputStream(size + 32)
    var offset = 0
    while (offset + 4 <= size) {
        val length = ((this[offset].toInt() and 0xff) shl 24) or
            ((this[offset + 1].toInt() and 0xff) shl 16) or
            ((this[offset + 2].toInt() and 0xff) shl 8) or
            (this[offset + 3].toInt() and 0xff)
        offset += 4
        if (length <= 0 || offset + length > size) return this
        output.write(ANNEX_B_START_CODE)
        output.write(this, offset, length)
        offset += length
    }
    return if (offset == size && output.size() > 0) output.toByteArray() else this
}

private fun ByteArray.hasAnnexBStartCode(): Boolean =
    size >= 4 && this[0] == 0.toByte() && this[1] == 0.toByte() &&
        (this[2] == 1.toByte() || (this[2] == 0.toByte() && this[3] == 1.toByte()))

private fun ByteArray.hasParameterSets(): Boolean {
    var offset = 0
    var hasSps = false
    var hasPps = false
    while (offset + 4 < size) {
        val nal = when {
            this[offset] == 0.toByte() && this[offset + 1] == 0.toByte() && this[offset + 2] == 1.toByte() -> offset + 3
            this[offset] == 0.toByte() && this[offset + 1] == 0.toByte() &&
                this[offset + 2] == 0.toByte() && this[offset + 3] == 1.toByte() -> offset + 4
            else -> {
                offset++
                continue
            }
        }
        when (this[nal].toInt() and 0x1f) {
            7 -> hasSps = true
            8 -> hasPps = true
        }
        if (hasSps && hasPps) return true
        offset = nal + 1
    }
    return false
}

/** Minimal single-program MPEG-TS muxer for H.264 video and optional AAC audio. */
internal class H264MpegTsMuxer(
    private val output: OutputStream,
    private val includeAudio: Boolean = false,
) {
    private val continuity = IntArray(MAX_PID + 1)
    private var wroteTables = false

    @Synchronized
    fun writeAccessUnit(accessUnit: ByteArray, presentationTimeUs: Long, keyFrame: Boolean) {
        if (accessUnit.isEmpty()) return
        if (!wroteTables || keyFrame) {
            writePsi(PAT_PID, patSection())
            writePsi(PMT_PID, pmtSection())
            wroteTables = true
        }
        val pts90Khz = presentationTimeUs.coerceAtLeast(0L) * 90L / 1_000L
        val pes = pesHeader(pts90Khz) + accessUnit
        writePes(VIDEO_PID, pes, pts90Khz, keyFrame, writePcr = true)
        output.flush()
    }

    @Synchronized
    fun writeAudioAccessUnit(accessUnit: ByteArray, presentationTimeUs: Long) {
        if (!includeAudio || accessUnit.isEmpty()) return
        if (!wroteTables) {
            writePsi(PAT_PID, patSection())
            writePsi(PMT_PID, pmtSection())
            wroteTables = true
        }
        val pts90Khz = presentationTimeUs.coerceAtLeast(0L) * 90L / 1_000L
        val pes = pesHeader(pts90Khz, AUDIO_STREAM_ID, accessUnit.size) + accessUnit
        writePes(AUDIO_PID, pes, pts90Khz, keyFrame = false, writePcr = false)
        output.flush()
    }

    private fun writePsi(pid: Int, section: ByteArray) {
        val packet = ByteArray(TS_PACKET_BYTES) { 0xff.toByte() }
        writeHeader(packet, pid, payloadStart = true, adaptationControl = 1)
        packet[4] = 0
        section.copyInto(packet, destinationOffset = 5)
        output.write(packet)
    }

    private fun writePes(
        pid: Int,
        pes: ByteArray,
        pts90Khz: Long,
        keyFrame: Boolean,
        writePcr: Boolean,
    ) {
        var offset = 0
        var first = true
        while (offset < pes.size) {
            val minimumAdaptationBytes = if (first && writePcr) 8 else 0
            val maximumPayload = TS_PAYLOAD_BYTES - minimumAdaptationBytes
            val payloadBytes = minOf(maximumPayload, pes.size - offset)
            val packet = ByteArray(TS_PACKET_BYTES) { 0xff.toByte() }
            val needsAdaptation = (first && writePcr) || payloadBytes < TS_PAYLOAD_BYTES
            writeHeader(packet, pid, payloadStart = first, adaptationControl = if (needsAdaptation) 3 else 1)
            var payloadOffset = 4
            if (needsAdaptation) {
                val adaptationLength = TS_PAYLOAD_BYTES - payloadBytes - 1
                packet[4] = adaptationLength.toByte()
                if (adaptationLength > 0) {
                    packet[5] = if (first && keyFrame) 0x50 else if (first && writePcr) 0x10 else 0
                    if (first && writePcr) writePcr(packet, 6, pts90Khz)
                }
                payloadOffset = 5 + adaptationLength
            }
            pes.copyInto(packet, destinationOffset = payloadOffset, startIndex = offset, endIndex = offset + payloadBytes)
            output.write(packet)
            offset += payloadBytes
            first = false
        }
    }

    private fun writeHeader(packet: ByteArray, pid: Int, payloadStart: Boolean, adaptationControl: Int) {
        packet[0] = 0x47
        packet[1] = (((pid ushr 8) and 0x1f) or if (payloadStart) 0x40 else 0).toByte()
        packet[2] = pid.toByte()
        packet[3] = ((adaptationControl shl 4) or continuity[pid]).toByte()
        continuity[pid] = (continuity[pid] + 1) and 0x0f
    }

    private fun writePcr(packet: ByteArray, offset: Int, pts90Khz: Long) {
        val value = pts90Khz and PTS_MASK
        packet[offset] = (value ushr 25).toByte()
        packet[offset + 1] = (value ushr 17).toByte()
        packet[offset + 2] = (value ushr 9).toByte()
        packet[offset + 3] = (value ushr 1).toByte()
        packet[offset + 4] = (((value and 1) shl 7) or 0x7e).toByte()
        packet[offset + 5] = 0
    }

    private fun pesHeader(
        pts90Khz: Long,
        streamId: Int = VIDEO_STREAM_ID,
        payloadSize: Int? = null,
    ): ByteArray {
        val packetLength = payloadSize?.plus(PES_OPTIONAL_HEADER_BYTES)?.coerceAtMost(0xffff) ?: 0
        return byteArrayOf(
            0x00, 0x00, 0x01, streamId.toByte(),
            (packetLength ushr 8).toByte(), packetLength.toByte(),
            0x80.toByte(), 0x80.toByte(), 0x05,
            (0x21L or (((pts90Khz ushr 30) and 0x07) shl 1)).toByte(),
            (pts90Khz ushr 22).toByte(),
            (((pts90Khz ushr 14) and 0xfe) or 0x01).toByte(),
            (pts90Khz ushr 7).toByte(),
            (((pts90Khz shl 1) and 0xfe) or 0x01).toByte(),
        )
    }

    private fun patSection(): ByteArray = psiWithCrc(
        byteArrayOf(
            0x00, 0xb0.toByte(), 0x0d,
            0x00, 0x01, 0xc1.toByte(), 0x00, 0x00,
            0x00, 0x01, (0xe0 or (PMT_PID ushr 8)).toByte(), PMT_PID.toByte(),
        ),
    )

    private fun pmtSection(): ByteArray {
        val streams = byteArrayOf(
            0x1b, (0xe0 or (VIDEO_PID ushr 8)).toByte(), VIDEO_PID.toByte(), 0xf0.toByte(), 0x00,
        ) + if (includeAudio) {
            byteArrayOf(
                0x0f, (0xe0 or (AUDIO_PID ushr 8)).toByte(), AUDIO_PID.toByte(), 0xf0.toByte(), 0x00,
            )
        } else {
            ByteArray(0)
        }
        val sectionLength = PMT_BASE_SECTION_LENGTH + streams.size
        return psiWithCrc(
            byteArrayOf(
                0x02, (0xb0 or (sectionLength ushr 8)).toByte(), sectionLength.toByte(),
                0x00, 0x01, 0xc1.toByte(), 0x00, 0x00,
                (0xe0 or (VIDEO_PID ushr 8)).toByte(), VIDEO_PID.toByte(),
                0xf0.toByte(), 0x00,
            ) + streams,
        )
    }

    private fun psiWithCrc(section: ByteArray): ByteArray {
        val crc = mpegCrc32(section)
        return section + byteArrayOf(
            (crc ushr 24).toByte(),
            (crc ushr 16).toByte(),
            (crc ushr 8).toByte(),
            crc.toByte(),
        )
    }

    private companion object {
        private const val TS_PACKET_BYTES = 188
        private const val TS_PAYLOAD_BYTES = 184
        private const val PAT_PID = 0x0000
        private const val PMT_PID = 0x0100
        private const val VIDEO_PID = 0x0101
        private const val AUDIO_PID = 0x0102
        private const val VIDEO_STREAM_ID = 0xe0
        private const val AUDIO_STREAM_ID = 0xc0
        private const val PES_OPTIONAL_HEADER_BYTES = 8
        private const val PMT_BASE_SECTION_LENGTH = 13
        private const val MAX_PID = 0x1fff
        private const val PTS_MASK = (1L shl 33) - 1
    }
}

internal fun mpegCrc32(bytes: ByteArray): Int {
    var crc = -1
    bytes.forEach { byte ->
        crc = crc xor ((byte.toInt() and 0xff) shl 24)
        repeat(8) {
            crc = if ((crc and Int.MIN_VALUE) != 0) (crc shl 1) xor 0x04c11db7 else crc shl 1
        }
    }
    return crc
}

private val ANNEX_B_START_CODE = byteArrayOf(0x00, 0x00, 0x00, 0x01)
