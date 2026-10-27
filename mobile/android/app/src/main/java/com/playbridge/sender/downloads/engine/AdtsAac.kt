package com.playbridge.sender.downloads.engine

import java.nio.ByteBuffer

/** ADTS access units represented as ranges in the original sample, without their headers. */
internal object AdtsAac {
    data class Config(
        val audioObjectType: Int,
        val frequencyIndex: Int,
        val channelConfiguration: Int,
    ) {
        val sampleRate: Int get() = SAMPLE_RATES[frequencyIndex]

        fun audioSpecificConfig(): ByteArray = byteArrayOf(
            ((audioObjectType shl 3) or (frequencyIndex ushr 1)).toByte(),
            (((frequencyIndex and 1) shl 7) or (channelConfiguration shl 3)).toByte(),
        )
    }

    data class Frame(val offset: Int, val size: Int, val presentationTimeUs: Long)
    data class Sample(val config: Config, val frames: List<Frame>)
    private data class Header(val config: Config, val size: Int, val headerSize: Int)

    fun hasSync(buffer: ByteBuffer): Boolean = buffer.remaining() >= 2 &&
        unsigned(buffer, buffer.position()) == 0xff &&
        unsigned(buffer, buffer.position() + 1) and 0xf0 == 0xf0

    fun configuration(buffer: ByteBuffer): Config? = header(buffer, buffer.position())?.config

    /** Returns null unless every byte belongs to a complete, compatible, single-block ADTS frame. */
    fun split(buffer: ByteBuffer, sampleTimeUs: Long, previousTimeUs: Long? = null): Sample? {
        var offset = buffer.position()
        val config = header(buffer, offset)?.config ?: return null
        val frames = ArrayList<Frame>()
        var previous = previousTimeUs
        while (offset < buffer.limit()) {
            val header = header(buffer, offset) ?: return null
            if (header.config != config) return null
            // Calculate from the common base, rather than accumulating rounded frame durations.
            val deltaUs = frames.size.toLong() * 1024 * 1_000_000 / config.sampleRate
            if (sampleTimeUs > Long.MAX_VALUE - deltaUs) return null
            var timeUs = sampleTimeUs + deltaUs
            if (previous != null && timeUs <= previous) {
                if (previous == Long.MAX_VALUE) return null
                timeUs = previous + 1
            }
            frames.add(Frame(offset + header.headerSize, header.size - header.headerSize, timeUs))
            previous = timeUs
            offset += header.size
        }
        return Sample(config, frames)
    }

    /** Keeps a contiguous run ending at EOF or a partial ADTS frame; never returns garbage bytes. */
    fun recover(
        buffer: ByteBuffer,
        sampleTimeUs: Long,
        previousTimeUs: Long? = null,
        expectedConfig: Config? = null,
    ): Sample? {
        var offset = buffer.position()
        var start = offset
        var config: Config? = null
        while (offset < buffer.limit()) {
            val header = header(buffer, offset)
            val compatible = header != null && (expectedConfig == null || header.config == expectedConfig)
            if (compatible && (config == null || header.config == config)) {
                if (config == null) start = offset
                config = header.config
                offset += header.size
            } else {
                if (config != null && trailingPartial(buffer, offset, config)) break
                config = null
                // A configuration change can itself begin a new valid run.
                if (!compatible) offset++
            }
        }
        if (config == null) return null
        val kept = buffer.duplicate()
        kept.position(start)
        kept.limit(offset)
        // Leading junk has no reliable frame count, so the first kept frame uses the sample PTS.
        return split(kept, sampleTimeUs, previousTimeUs)
    }

    private fun trailingPartial(buffer: ByteBuffer, offset: Int, config: Config): Boolean {
        val remaining = buffer.limit() - offset
        if (remaining >= 7) {
            val header = header(buffer, offset, requireComplete = false) ?: return false
            return header.config == config && header.size > remaining
        }
        if (remaining == 0 || unsigned(buffer, offset) != 0xff) return false
        if (remaining == 1) return true
        val second = unsigned(buffer, offset + 1)
        if (second and 0xf6 != 0xf0) return false
        if (remaining == 2) return true
        val third = unsigned(buffer, offset + 2)
        if ((third ushr 6) + 1 != config.audioObjectType ||
            (third ushr 2) and 15 != config.frequencyIndex ||
            third and 1 != config.channelConfiguration ushr 2
        ) return false
        if (remaining == 3) return true
        val fourth = unsigned(buffer, offset + 3)
        if (fourth ushr 6 != config.channelConfiguration and 3) return false
        if (remaining < 6) return true
        val size = ((fourth and 3) shl 11) or
            (unsigned(buffer, offset + 4) shl 3) or (unsigned(buffer, offset + 5) ushr 5)
        return size > if (second and 1 == 0) 9 else 7
    }

    private fun header(buffer: ByteBuffer, offset: Int, requireComplete: Boolean = true): Header? {
        if (buffer.limit() - offset < 7) return null
        val second = unsigned(buffer, offset + 1)
        if (unsigned(buffer, offset) != 0xff || second and 0xf6 != 0xf0) return null
        val third = unsigned(buffer, offset + 2)
        val fourth = unsigned(buffer, offset + 3)
        val frequencyIndex = (third ushr 2) and 0x0f
        if (frequencyIndex >= SAMPLE_RATES.size) return null
        val headerSize = if (second and 1 == 0) 9 else 7
        val size = ((fourth and 3) shl 11) or
            (unsigned(buffer, offset + 4) shl 3) or (unsigned(buffer, offset + 5) ushr 5)
        if (size <= headerSize || (requireComplete && size > buffer.limit() - offset)) return null
        // Multiple raw_data_blocks need their own framing/CRC parser; never strip them blindly.
        if (unsigned(buffer, offset + 6) and 3 != 0) return null
        return Header(
            Config((third ushr 6) + 1, frequencyIndex, ((third and 1) shl 2) or (fourth ushr 6)),
            size,
            headerSize,
        )
    }

    private fun unsigned(buffer: ByteBuffer, offset: Int): Int = buffer.get(offset).toInt() and 0xff

    private val SAMPLE_RATES = intArrayOf(
        96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350,
    )
}
