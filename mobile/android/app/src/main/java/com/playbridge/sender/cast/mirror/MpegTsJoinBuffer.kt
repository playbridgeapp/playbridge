package com.playbridge.sender.cast.mirror

import java.io.ByteArrayOutputStream

internal data class MpegTsSegment(
    val bytes: ByteArray,
    val durationSeconds: Double,
)

/**
 * Aligns MediaRecorder output to MPEG-TS packets and keeps only the latest
 * independently decodable H.264 group for newly connected renderers.
 *
 * A byte-only ring can cover an unbounded amount of playback time when the
 * captured screen is static. Starting each client at the latest IDR keeps the
 * bootstrap both byte-bounded and time-bounded.
 */
internal class MpegTsJoinBuffer(private val capacityBytes: Int) {
    private var remainder = ByteArray(0)
    private var pmtPid: Int? = null
    private var videoPid: Int? = null
    private var patPacket: ByteArray? = null
    private var pmtPacket: ByteArray? = null
    private var codecConfigPackets: ByteArray? = null

    private var hasDecodableGroup = false
    private val group = ByteArrayOutputStream()
    private val currentPesPackets = ByteArrayOutputStream()
    private val currentPesPayload = ByteArrayOutputStream()
    private var collectingVideoPes = false
    private var currentPesHasIdr = false
    private var currentPesHasCodecConfig = false
    private var currentPesPts90Khz: Long? = null
    private var groupStartPts90Khz: Long? = null
    private val completedSegments = ArrayDeque<MpegTsSegment>()

    init {
        require(capacityBytes >= TS_PACKET_BYTES * 3)
    }

    /** Returns complete, sync-aligned TS packets that are safe to broadcast. */
    @Synchronized
    fun consume(source: ByteArray, count: Int): ByteArray {
        if (count <= 0) return ByteArray(0)
        require(count <= source.size)

        val combined = ByteArray(remainder.size + count)
        remainder.copyInto(combined)
        source.copyInto(combined, remainder.size, 0, count)

        var cursor = findSync(combined, 0)
        if (cursor < 0) {
            remainder = combined.copyOfRange(
                (combined.size - (TS_PACKET_BYTES - 1)).coerceAtLeast(0),
                combined.size,
            )
            return ByteArray(0)
        }

        val output = ByteArrayOutputStream(combined.size - cursor)
        while (cursor + TS_PACKET_BYTES <= combined.size) {
            if (combined[cursor] != TS_SYNC_BYTE) {
                val next = findSync(combined, cursor + 1)
                if (next < 0) break
                cursor = next
                continue
            }
            val packet = combined.copyOfRange(cursor, cursor + TS_PACKET_BYTES)
            onPacket(packet)
            output.write(packet)
            cursor += TS_PACKET_BYTES
        }

        remainder = combined.copyOfRange(cursor, combined.size)
        return output.toByteArray()
    }

    @Synchronized
    fun snapshot(): ByteArray = if (hasDecodableGroup) group.toByteArray() else ByteArray(0)

    @Synchronized
    fun isReadyForJoin(): Boolean = hasDecodableGroup

    @Synchronized
    fun drainCompletedSegments(): List<MpegTsSegment> = buildList {
        while (completedSegments.isNotEmpty()) add(completedSegments.removeFirst())
    }

    private fun onPacket(packet: ByteArray) {
        val pid = packet.pid()
        when (pid) {
            PAT_PID -> {
                patPacket = packet.copyOf()
                parsePat(packet)?.let { pmtPid = it }
            }

            pmtPid -> {
                pmtPacket = packet.copyOf()
                parsePmt(packet)?.let { videoPid = it }
            }
        }

        val isVideo = pid == videoPid
        if (isVideo && packet.payloadUnitStart()) {
            finishCurrentPes()
            collectingVideoPes = true
            currentPesHasIdr = false
            currentPesHasCodecConfig = false
            currentPesPts90Khz = packet.pesPts90Khz()
            currentPesPackets.reset()
            currentPesPayload.reset()
        }

        appendToGroup(packet)

        if (!collectingVideoPes || currentPesHasIdr) return
        currentPesPackets.write(packet)
        if (isVideo) {
            packet.videoElementaryPayload()?.let(currentPesPayload::write)
            val elementaryBytes = currentPesPayload.toByteArray()
            if (!currentPesHasCodecConfig && containsH264ParameterSets(elementaryBytes)) {
                currentPesHasCodecConfig = true
            }
            if (containsH264Idr(elementaryBytes)) {
                currentPesHasIdr = true
                resetGroupAtCurrentPes()
            }
        }
    }

    private fun finishCurrentPes() {
        if (currentPesHasCodecConfig && !currentPesHasIdr) {
            codecConfigPackets = currentPesPackets.toByteArray()
        }
    }

    private fun appendToGroup(packet: ByteArray) {
        if (!hasDecodableGroup) return
        if (group.size() + packet.size > capacityBytes) {
            hasDecodableGroup = false
            group.reset()
            return
        }
        group.write(packet)
    }

    private fun resetGroupAtCurrentPes() {
        val nextPts = currentPesPts90Khz
        if (hasDecodableGroup && group.size() > currentPesPackets.size()) {
            val completedBytes = group.toByteArray()
                .copyOfRange(0, group.size() - currentPesPackets.size())
            val duration = ptsDurationSeconds(groupStartPts90Khz, nextPts)
            completedSegments += MpegTsSegment(
                bytes = completedBytes,
                durationSeconds = duration.coerceAtLeast(MIN_SEGMENT_SECONDS),
            )
        }
        hasDecodableGroup = false
        group.reset()
        patPacket?.let(group::write)
        pmtPacket?.let(group::write)
        codecConfigPackets?.let { config ->
            rewritePesTimestamps(config, nextPts)?.let(group::write)
        }
        val pesPackets = currentPesPackets.toByteArray()
        if (group.size() + pesPackets.size > capacityBytes) return
        group.write(pesPackets)
        groupStartPts90Khz = nextPts
        hasDecodableGroup = true
    }

    private fun ptsDurationSeconds(start: Long?, end: Long?): Double {
        if (start == null || end == null) return DEFAULT_SEGMENT_SECONDS
        val ticks = (end - start) and PTS_MASK
        return ticks.toDouble() / PTS_TIMESCALE
    }

    private fun ByteArray.videoElementaryPayload(): ByteArray? {
        var offset = payloadOffset() ?: return null
        if (payloadUnitStart()) {
            if (offset + 9 > size || this[offset] != 0.toByte() || this[offset + 1] != 0.toByte() ||
                this[offset + 2] != 1.toByte()
            ) {
                return null
            }
            offset += 9 + (this[offset + 8].toInt() and 0xff)
        }
        if (offset >= size) return null
        return copyOfRange(offset, size)
    }

    private fun ByteArray.pesPts90Khz(): Long? {
        val offset = payloadOffset() ?: return null
        if (!payloadUnitStart() || offset + 14 > size) return null
        if (this[offset] != 0.toByte() || this[offset + 1] != 0.toByte() || this[offset + 2] != 1.toByte()) return null
        if ((this[offset + 7].toInt() and 0x80) == 0) return null
        val pts = offset + 9
        return (
            (((this[pts].toLong() ushr 1) and 0x07) shl 30) or
                ((this[pts + 1].toLong() and 0xff) shl 22) or
                (((this[pts + 2].toLong() ushr 1) and 0x7f) shl 15) or
                ((this[pts + 3].toLong() and 0xff) shl 7) or
                ((this[pts + 4].toLong() ushr 1) and 0x7f)
            )
    }

    private fun rewritePesTimestamps(source: ByteArray, pts90Khz: Long?): ByteArray? {
        if (pts90Khz == null) return source
        val rewritten = source.copyOf()
        var offset = 0
        while (offset + TS_PACKET_BYTES <= rewritten.size) {
            val packet = rewritten.copyOfRange(offset, offset + TS_PACKET_BYTES)
            if (packet.pid() == videoPid && packet.payloadUnitStart()) {
                val payload = packet.payloadOffset() ?: return rewritten
                if (payload + 14 <= packet.size &&
                    packet[payload] == 0.toByte() && packet[payload + 1] == 0.toByte() && packet[payload + 2] == 1.toByte()
                ) {
                    encodePts(rewritten, offset + payload + 9, pts90Khz)
                    rewritePcrIfPresent(rewritten, offset, pts90Khz)
                    return rewritten
                }
            }
            offset += TS_PACKET_BYTES
        }
        return rewritten
    }

    private fun rewritePcrIfPresent(buffer: ByteArray, packetOffset: Int, pts90Khz: Long) {
        val packet = buffer.copyOfRange(packetOffset, packetOffset + TS_PACKET_BYTES)
        val adaptationControl = (packet[3].toInt() ushr 4) and 0x03
        if (adaptationControl != 3 || packet[4].toInt() == 0 || (packet[5].toInt() and 0x10) == 0) return
        encodePcr(buffer, packetOffset + 6, pts90Khz)
    }

    private fun encodePts(buffer: ByteArray, offset: Int, value: Long) {
        val pts = value and PTS_MASK
        buffer[offset] = (0x21L or (((pts ushr 30) and 0x07) shl 1)).toByte()
        buffer[offset + 1] = (pts ushr 22).toByte()
        buffer[offset + 2] = (((pts ushr 14) and 0xfe) or 0x01).toByte()
        buffer[offset + 3] = (pts ushr 7).toByte()
        buffer[offset + 4] = (((pts shl 1) and 0xfe) or 0x01).toByte()
    }

    private fun encodePcr(buffer: ByteArray, offset: Int, value: Long) {
        val pcr = value and PTS_MASK
        buffer[offset] = (pcr ushr 25).toByte()
        buffer[offset + 1] = (pcr ushr 17).toByte()
        buffer[offset + 2] = (pcr ushr 9).toByte()
        buffer[offset + 3] = (pcr ushr 1).toByte()
        buffer[offset + 4] = (((pcr and 1) shl 7) or 0x7e).toByte()
        buffer[offset + 5] = 0
    }

    private fun parsePat(packet: ByteArray): Int? {
        val section = packet.psiSection(TABLE_ID_PAT) ?: return null
        val sectionLength = ((section[1].toInt() and 0x0f) shl 8) or (section[2].toInt() and 0xff)
        val end = (3 + sectionLength - CRC_BYTES).coerceAtMost(section.size)
        var offset = 8
        while (offset + 4 <= end) {
            val program = ((section[offset].toInt() and 0xff) shl 8) or (section[offset + 1].toInt() and 0xff)
            val pid = ((section[offset + 2].toInt() and 0x1f) shl 8) or (section[offset + 3].toInt() and 0xff)
            if (program != 0) return pid
            offset += 4
        }
        return null
    }

    private fun parsePmt(packet: ByteArray): Int? {
        val section = packet.psiSection(TABLE_ID_PMT) ?: return null
        if (section.size < 12) return null
        val sectionLength = ((section[1].toInt() and 0x0f) shl 8) or (section[2].toInt() and 0xff)
        val end = (3 + sectionLength - CRC_BYTES).coerceAtMost(section.size)
        val programInfoLength = ((section[10].toInt() and 0x0f) shl 8) or (section[11].toInt() and 0xff)
        var offset = 12 + programInfoLength
        while (offset + 5 <= end) {
            val streamType = section[offset].toInt() and 0xff
            val elementaryPid = ((section[offset + 1].toInt() and 0x1f) shl 8) or
                (section[offset + 2].toInt() and 0xff)
            val infoLength = ((section[offset + 3].toInt() and 0x0f) shl 8) or
                (section[offset + 4].toInt() and 0xff)
            if (streamType == STREAM_TYPE_H264) return elementaryPid
            offset += 5 + infoLength
        }
        return null
    }

    private fun ByteArray.psiSection(expectedTableId: Int): ByteArray? {
        var offset = payloadOffset() ?: return null
        if (!payloadUnitStart() || offset >= size) return null
        val pointer = this[offset].toInt() and 0xff
        offset += 1 + pointer
        if (offset + 3 > size || (this[offset].toInt() and 0xff) != expectedTableId) return null
        return copyOfRange(offset, size)
    }

    private fun ByteArray.payloadOffset(): Int? {
        val adaptationControl = (this[3].toInt() ushr 4) and 0x03
        if (adaptationControl == 0 || adaptationControl == 2) return null
        var offset = 4
        if (adaptationControl == 3) {
            offset += 1 + (this[4].toInt() and 0xff)
        }
        return offset.takeIf { it < size }
    }

    private fun ByteArray.pid(): Int = ((this[1].toInt() and 0x1f) shl 8) or (this[2].toInt() and 0xff)

    private fun ByteArray.payloadUnitStart(): Boolean = (this[1].toInt() and 0x40) != 0

    private fun findSync(bytes: ByteArray, from: Int): Int {
        var offset = from
        while (offset < bytes.size) {
            if (bytes[offset] == TS_SYNC_BYTE &&
                (offset + TS_PACKET_BYTES >= bytes.size || bytes[offset + TS_PACKET_BYTES] == TS_SYNC_BYTE)
            ) {
                return offset
            }
            offset++
        }
        return -1
    }

    private fun containsH264Idr(bytes: ByteArray): Boolean {
        return h264NalTypes(bytes).any { it == H264_NAL_IDR }
    }

    private fun containsH264ParameterSets(bytes: ByteArray): Boolean {
        val types = h264NalTypes(bytes)
        return H264_NAL_SPS in types && H264_NAL_PPS in types
    }

    private fun h264NalTypes(bytes: ByteArray): Set<Int> {
        val types = mutableSetOf<Int>()
        var offset = 0
        while (offset + 4 < bytes.size) {
            val nalOffset = when {
                bytes[offset] == 0.toByte() && bytes[offset + 1] == 0.toByte() && bytes[offset + 2] == 1.toByte() -> offset + 3
                offset + 4 < bytes.size && bytes[offset] == 0.toByte() && bytes[offset + 1] == 0.toByte() &&
                    bytes[offset + 2] == 0.toByte() && bytes[offset + 3] == 1.toByte() -> offset + 4
                else -> {
                    offset++
                    continue
                }
            }
            if (nalOffset < bytes.size) types += bytes[nalOffset].toInt() and 0x1f
            offset = nalOffset + 1
        }
        return types
    }

    private companion object {
        private const val TS_PACKET_BYTES = 188
        private const val TS_SYNC_BYTE: Byte = 0x47
        private const val PAT_PID = 0
        private const val TABLE_ID_PAT = 0x00
        private const val TABLE_ID_PMT = 0x02
        private const val STREAM_TYPE_H264 = 0x1b
        private const val H264_NAL_IDR = 5
        private const val H264_NAL_SPS = 7
        private const val H264_NAL_PPS = 8
        private const val CRC_BYTES = 4
        private const val PTS_MASK = (1L shl 33) - 1
        private const val PTS_TIMESCALE = 90_000.0
        private const val DEFAULT_SEGMENT_SECONDS = 2.0
        private const val MIN_SEGMENT_SECONDS = 0.1
    }
}
