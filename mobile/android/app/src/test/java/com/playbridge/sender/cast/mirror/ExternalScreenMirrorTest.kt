package com.playbridge.sender.cast.mirror

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalScreenMirrorTest {
    @Test
    fun `completed hls segment is keyframe aligned and timed from pts`() {
        val pat = tsPacket(0, patPayload(0x100))
        val pmt = tsPacket(0x100, pmtPayload(0x101))
        val config = tsPacket(0x101, videoCodecConfigPes())
        val firstIdr = tsPacket(0x101, videoPes(nalType = 5, marker = 0x11, pts = 0))
        val delta = tsPacket(0x101, videoPes(nalType = 1, marker = 0x22, pts = 90_000))
        val secondIdr = tsPacket(0x101, videoPes(nalType = 5, marker = 0x33, pts = 180_000))
        val join = MpegTsJoinBuffer(capacityBytes = TS_PACKET_BYTES * 16)
        val stream = pat + pmt + config + firstIdr + delta + secondIdr

        join.consume(stream, stream.size)

        val completed = join.drainCompletedSegments()
        assertEquals(1, completed.size)
        assertEquals(2.0, completed.single().durationSeconds, 0.001)
        assertEquals(listOf(0, 0x100), completed.single().bytes.packetPids().take(2))
        assertTrue(completed.single().bytes.contains(0x11.toByte()))
        assertFalse(completed.single().bytes.contains(0x33.toByte()))
        assertTrue(join.snapshot().contains(0x33.toByte()))
    }

    @Test
    fun `live hls manifest advances media sequence and names retained segments`() {
        val manifest = String(
            buildMirrorHlsManifest(
                listOf(
                    MirrorHlsSegment(42, 1.25),
                    MirrorHlsSegment(43, 1.75),
                ),
            )!!,
            Charsets.UTF_8,
        )

        assertTrue(manifest.startsWith("#EXTM3U\n"))
        assertTrue(manifest.contains("#EXT-X-TARGETDURATION:2\n"))
        assertTrue(manifest.contains("#EXT-X-MEDIA-SEQUENCE:42\n"))
        assertTrue(manifest.contains("#EXT-X-INDEPENDENT-SEGMENTS\n"))
        assertTrue(manifest.contains("#EXTINF:1.250,\nsegment-42.ts\n"))
        assertTrue(manifest.contains("#EXTINF:1.750,\nsegment-43.ts\n"))
        assertFalse(manifest.contains("#EXT-X-ENDLIST"))
        assertNull(buildMirrorHlsManifest(emptyList()))
    }

    @Test
    fun `http responses expose cast cors and finite hls objects`() {
        val playlist = "#EXTM3U\n".toByteArray()
        val response = String(
            MirrorHttpResponse.bytes("application/x-mpegURL", playlist, headOnly = false),
            Charsets.US_ASCII,
        )

        assertTrue(response.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(response.contains("Content-Type: application/x-mpegURL\r\n"))
        assertTrue(response.contains("Content-Length: ${playlist.size}\r\n"))
        assertTrue(response.contains("Access-Control-Allow-Origin: *\r\n"))
        assertTrue(response.endsWith("#EXTM3U\n"))
    }

    @Test
    fun `continuous client backlog is byte bounded`() {
        val buffer = MirrorClientBuffer(capacityBytes = 5)

        assertTrue(buffer.offer(byteArrayOf(1, 2, 3)))
        assertFalse(buffer.offer(byteArrayOf(4, 5, 6)))
        assertEquals(listOf<Byte>(1, 2, 3), buffer.poll(1, TimeUnit.MILLISECONDS)?.toList())
        assertTrue(buffer.offer(byteArrayOf(4, 5, 6)))
        buffer.close()
        assertFalse(buffer.offer(byteArrayOf(7)))
    }

    private fun ByteArray.packetPids(): List<Int> = asList()
        .chunked(TS_PACKET_BYTES)
        .filter { it.size == TS_PACKET_BYTES }
        .map { packet -> ((packet[1].toInt() and 0x1f) shl 8) or (packet[2].toInt() and 0xff) }

    private fun tsPacket(pid: Int, payload: ByteArray): ByteArray =
        ByteArray(TS_PACKET_BYTES) { 0xff.toByte() }.apply {
            this[0] = 0x47
            this[1] = (((pid ushr 8) and 0x1f) or 0x40).toByte()
            this[2] = pid.toByte()
            this[3] = 0x10
            payload.copyInto(this, destinationOffset = 4, endIndex = payload.size.coerceAtMost(size - 4))
        }

    private fun patPayload(pmtPid: Int): ByteArray = byteArrayOf(
        0x00,
        0x00, 0xb0.toByte(), 0x0d,
        0x00, 0x01, 0xc1.toByte(), 0x00, 0x00,
        0x00, 0x01, (0xe0 or (pmtPid ushr 8)).toByte(), pmtPid.toByte(),
        0x00, 0x00, 0x00, 0x00,
    )

    private fun pmtPayload(videoPid: Int): ByteArray = byteArrayOf(
        0x00,
        0x02, 0xb0.toByte(), 0x12,
        0x00, 0x01, 0xc1.toByte(), 0x00, 0x00,
        (0xe0 or (videoPid ushr 8)).toByte(), videoPid.toByte(),
        0xf0.toByte(), 0x00,
        0x1b, (0xe0 or (videoPid ushr 8)).toByte(), videoPid.toByte(), 0xf0.toByte(), 0x00,
        0x00, 0x00, 0x00, 0x00,
    )

    private fun videoCodecConfigPes(): ByteArray =
        videoPesPrefix(0) + byteArrayOf(
            0x00, 0x00, 0x00, 0x01, 0x67, 0x64,
            0x00, 0x00, 0x00, 0x01, 0x68, 0xee.toByte(),
        )

    private fun videoPes(nalType: Int, marker: Int, pts: Long): ByteArray =
        videoPesPrefix(pts) + byteArrayOf(
            0x00, 0x00, 0x00, 0x01, (0x60 or nalType).toByte(), marker.toByte(),
        )

    private fun videoPesPrefix(pts: Long): ByteArray {
        val value = pts and ((1L shl 33) - 1)
        return byteArrayOf(
            0x00, 0x00, 0x01, 0xe0.toByte(), 0x00, 0x00,
            0x80.toByte(), 0x80.toByte(), 0x05,
            (0x21L or (((value ushr 30) and 0x07) shl 1)).toByte(),
            (value ushr 22).toByte(),
            (((value ushr 14) and 0xfe) or 0x01).toByte(),
            (value ushr 7).toByte(),
            (((value shl 1) and 0xfe) or 0x01).toByte(),
        )
    }

    private companion object {
        private const val TS_PACKET_BYTES = 188
    }
}
