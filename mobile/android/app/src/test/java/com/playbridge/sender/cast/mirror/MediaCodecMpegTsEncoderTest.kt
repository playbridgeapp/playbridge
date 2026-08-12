package com.playbridge.sender.cast.mirror

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCodecMpegTsEncoderTest {
    @Test
    fun `length prefixed codec output is converted to annex b`() {
        val encoded = byteArrayOf(
            0, 0, 0, 2, 0x67, 0x01,
            0, 0, 0, 2, 0x68, 0x02,
        )

        assertEquals(
            listOf<Byte>(0, 0, 0, 1, 0x67, 0x01, 0, 0, 0, 1, 0x68, 0x02),
            encoded.toAnnexB().toList(),
        )
    }

    @Test
    fun `first keyframe is immediately available to continuous clients`() {
        val output = ByteArrayOutputStream()
        val muxer = H264MpegTsMuxer(output)
        val join = MpegTsJoinBuffer(capacityBytes = 256 * 1024)

        muxer.writeAccessUnit(decodableKeyframe(marker = 0x11), presentationTimeUs = 0, keyFrame = true)
        val firstGroup = output.toByteArray()
        join.consume(firstGroup, firstGroup.size)

        assertTrue(join.isReadyForJoin())
        assertTrue(join.snapshot().contains(0x11.toByte()))
        assertTrue(join.drainCompletedSegments().isEmpty())
    }

    @Test
    fun `one second keyframes produce independent one second hls segments`() {
        val output = ByteArrayOutputStream()
        val muxer = H264MpegTsMuxer(output)
        val join = MpegTsJoinBuffer(capacityBytes = 256 * 1024)
        var consumed = 0

        fun drainMuxerOutput() {
            val bytes = output.toByteArray()
            val newBytes = bytes.copyOfRange(consumed, bytes.size)
            join.consume(newBytes, newBytes.size)
            consumed = bytes.size
        }

        muxer.writeAccessUnit(decodableKeyframe(marker = 0x11), presentationTimeUs = 0, keyFrame = true)
        drainMuxerOutput()
        muxer.writeAccessUnit(deltaFrame(marker = 0x22), presentationTimeUs = 500_000, keyFrame = false)
        drainMuxerOutput()
        muxer.writeAccessUnit(decodableKeyframe(marker = 0x33), presentationTimeUs = 1_000_000, keyFrame = true)
        drainMuxerOutput()

        val segment = join.drainCompletedSegments().single()
        assertEquals(1.0, segment.durationSeconds, 0.001)
        assertTrue(segment.bytes.contains(0x11.toByte()))
        assertTrue(segment.bytes.contains(0x22.toByte()))
        assertFalse(segment.bytes.contains(0x33.toByte()))
        assertEquals(listOf(0, 0x100), segment.bytes.packetPids().take(2))
        assertTrue(segment.bytes.asList().chunked(TS_PACKET_BYTES).all { it.size == TS_PACKET_BYTES && it[0] == 0x47.toByte() })
    }

    private fun decodableKeyframe(marker: Int): ByteArray = byteArrayOf(
        0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1f,
        0, 0, 0, 1, 0x68, 0xce.toByte(), 0x06, 0xe2.toByte(),
        0, 0, 0, 1, 0x65, marker.toByte(),
    )

    private fun deltaFrame(marker: Int): ByteArray = byteArrayOf(
        0, 0, 0, 1, 0x61, marker.toByte(),
    )

    private fun ByteArray.packetPids(): List<Int> = asList()
        .chunked(TS_PACKET_BYTES)
        .filter { it.size == TS_PACKET_BYTES }
        .map { packet -> ((packet[1].toInt() and 0x1f) shl 8) or (packet[2].toInt() and 0xff) }

    private companion object {
        private const val TS_PACKET_BYTES = 188
    }
}
