package com.playbridge.sender.downloads.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class AdtsAacTest {
    @Test
    fun splitsPackedFramesWithAndWithoutCrc() {
        for (crc in listOf(false, true)) {
            val payloads = List(17) { i -> ByteArray(16 + i) { (i + it).toByte() } }
            val bytes = payloads.fold(byteArrayOf()) { acc, payload -> acc + adts(payload, crc = crc) }
            val sample = requireNotNull(AdtsAac.split(ByteBuffer.wrap(bytes), 2_000_000))
            assertEquals(17, sample.frames.size)
            assertEquals(48000, sample.config.sampleRate)
            sample.frames.forEachIndexed { index, frame ->
                assertArrayEquals(payloads[index], bytes.copyOfRange(frame.offset, frame.offset + frame.size))
                assertEquals(2_000_000 + index * 1024L * 1_000_000 / 48000, frame.presentationTimeUs)
            }
        }
    }

    @Test
    fun splitsSingleFrameAndPreservesItsTimestamp() {
        for (crc in listOf(false, true)) {
            val bytes = adts(byteArrayOf(1, 2, 3), crc = crc)
            val sample = requireNotNull(AdtsAac.split(ByteBuffer.wrap(bytes), 123_456))
            assertEquals(listOf(AdtsAac.Frame(if (crc) 9 else 7, 3, 123_456)), sample.frames)
        }
    }

    @Test
    fun roundsFromTheOriginalTimestampWithoutAccumulatingDrift() {
        val bytes = List(100) { adts(frequencyIndex = 4) }.fold(byteArrayOf()) { acc, frame -> acc + frame }
        val sample = requireNotNull(AdtsAac.split(ByteBuffer.wrap(bytes), 123))
        sample.frames.forEachIndexed { index, frame ->
            assertEquals(123 + index * 1024L * 1_000_000 / 44100, frame.presentationTimeUs)
            if (index > 0) assertTrue(frame.presentationTimeUs > sample.frames[index - 1].presentationTimeUs)
        }
    }

    @Test
    fun keepsTimestampsIncreasingAcrossRoundedOrOverlappingExtractorSamples() {
        val bytes = adts() + adts()
        val first = requireNotNull(AdtsAac.split(ByteBuffer.wrap(bytes), 0))
        val second = requireNotNull(AdtsAac.split(ByteBuffer.wrap(bytes), 21_333, first.frames.last().presentationTimeUs))
        assertEquals(21_334L, second.frames.first().presentationTimeUs)
        assertEquals(42_666L, second.frames.last().presentationTimeUs)
    }

    @Test
    fun buildsAudioSpecificConfigFromProfileRateAndChannels() {
        val stereo48 = requireNotNull(AdtsAac.configuration(ByteBuffer.wrap(adts())))
        assertArrayEquals(byteArrayOf(0x11, 0x90.toByte()), stereo48.audioSpecificConfig())
        val stereo44 = requireNotNull(AdtsAac.configuration(ByteBuffer.wrap(adts(frequencyIndex = 4))))
        assertArrayEquals(byteArrayOf(0x12, 0x10), stereo44.audioSpecificConfig())
        val mainMono = requireNotNull(AdtsAac.configuration(ByteBuffer.wrap(adts(profile = 0, channels = 1))))
        assertEquals(1, mainMono.audioObjectType)
        assertEquals(1, mainMono.channelConfiguration)
        assertArrayEquals(byteArrayOf(0x09, 0x88.toByte()), mainMono.audioSpecificConfig())
    }

    @Test
    fun respectsBufferBoundsWithoutChangingPositionOrLimit() {
        val frame = adts(crc = true)
        val writable = ByteBuffer.allocateDirect(frame.size + 5)
        writable.put(byteArrayOf(8, 9)).put(frame).put(byteArrayOf(10, 11, 12))
        writable.position(2)
        writable.limit(2 + frame.size)
        val buffer = writable.asReadOnlyBuffer()
        val sample = requireNotNull(AdtsAac.split(buffer, 0))
        assertEquals(11, sample.frames.single().offset)
        assertEquals(2, buffer.position())
        assertEquals(2 + frame.size, buffer.limit())
        val slice = buffer.slice()
        assertEquals(9, requireNotNull(AdtsAac.split(slice, 0)).frames.single().offset)
    }

    @Test
    fun leavesNonAdtsSamplesUnchanged() {
        for (bytes in listOf(byteArrayOf(), byteArrayOf(0x21, 0x10, 0x45, 0x67), ByteArray(32))) {
            assertNull(AdtsAac.split(ByteBuffer.wrap(bytes), 0))
            assertNull(AdtsAac.configuration(ByteBuffer.wrap(bytes)))
        }
    }

    @Test
    fun rejectsEveryTruncatedPrefixIncludingCrcHeadersAndPayloads() {
        for (crc in listOf(false, true)) {
            val bytes = adts(crc = crc)
            for (length in 0 until bytes.size) {
                assertNull("prefix length $length", AdtsAac.split(ByteBuffer.wrap(bytes.copyOf(length)), 0))
            }
        }
    }

    @Test
    fun rejectsGarbageAndPartialFramesWithoutReturningPartialAudio() {
        val valid = adts()
        for (bytes in listOf(
            byteArrayOf(0) + valid,
            valid + byteArrayOf(0),
            valid + adts(crc = true).dropLast(1).toByteArray(),
            valid + byteArrayOf(0xff.toByte(), 0xf1.toByte()),
        )) {
            assertNull(AdtsAac.split(ByteBuffer.wrap(bytes), 0))
        }
        // Codec data can still be recovered before track registration when a later frame is bad.
        assertNotNull(AdtsAac.configuration(ByteBuffer.wrap(valid + byteArrayOf(0))))
    }

    @Test
    fun rejectsInvalidHeadersAndUnsupportedMultipleRawDataBlocks() {
        val valid = adts()
        val invalid = listOf(
            valid.copyOf().apply { this[0] = 0xfe.toByte() },
            valid.copyOf().apply { this[1] = 0xf3.toByte() }, // nonzero layer
            adts(frequencyIndex = 13),
            adts(frequencyIndex = 15),
            valid.copyOf().apply { this[4] = 0; this[5] = 0xff.toByte() }, // length 7, no payload
            valid.copyOf().apply { this[4] = 0; this[5] = 0x1f }, // zero length
            valid.copyOf().apply { this[4] = 0xff.toByte() }, // length past end
            valid.copyOf().apply { this[6] = 0xfd.toByte() }, // two raw_data_blocks
        )
        for (bytes in invalid) assertNull(AdtsAac.split(ByteBuffer.wrap(bytes), 0))
    }

    @Test
    fun rejectsConfigurationChangesWithinASample() {
        for (different in listOf(adts(frequencyIndex = 4), adts(channels = 1), adts(profile = 0))) {
            assertNull(AdtsAac.split(ByteBuffer.wrap(adts() + different), 0))
        }
    }

    @Test
    fun acceptsMpeg2SyncAndMixedCrcProtection() {
        val mpeg2 = adts().apply { this[1] = 0xf9.toByte() }
        assertEquals(2, requireNotNull(AdtsAac.split(ByteBuffer.wrap(mpeg2 + adts(crc = true)), 0)).frames.size)
    }

    @Test
    fun usesAllThirteenFrameLengthBitsWithoutSearchingInsideThePayload() {
        val payload = ByteArray(4097) { 0xff.toByte() }
        val bytes = adts(payload, crc = true) + adts(payload)
        val sample = requireNotNull(AdtsAac.split(ByteBuffer.wrap(bytes), 0))
        assertEquals(listOf(AdtsAac.Frame(9, 4097, 0), AdtsAac.Frame(4113, 4097, 21_333)), sample.frames)
        assertArrayEquals(payload, bytes.copyOfRange(sample.frames[1].offset, bytes.size))
    }

    @Test
    fun fallsBackIfTimestampsWouldOverflow() {
        assertNull(AdtsAac.split(ByteBuffer.wrap(adts() + adts()), Long.MAX_VALUE))
        assertNull(AdtsAac.split(ByteBuffer.wrap(adts()), 0, Long.MAX_VALUE))
    }

    private fun adts(
        payload: ByteArray = byteArrayOf(1, 2, 3, 4),
        crc: Boolean = false,
        frequencyIndex: Int = 3,
        profile: Int = 1,
        channels: Int = 2,
    ): ByteArray {
        val length = payload.size + if (crc) 9 else 7
        val header = byteArrayOf(
            0xff.toByte(),
            (if (crc) 0xf0 else 0xf1).toByte(),
            ((profile shl 6) or (frequencyIndex shl 2) or (channels ushr 2)).toByte(),
            (((channels and 3) shl 6) or (length ushr 11)).toByte(),
            (length ushr 3).toByte(),
            (((length and 7) shl 5) or 0x1f).toByte(),
            0xfc.toByte(),
        )
        return header + (if (crc) byteArrayOf(0x12, 0x34) else byteArrayOf()) + payload
    }
}
