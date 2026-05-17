package com.playbridge.shared.protocol

/**
 * Compact binary representations for high-frequency events (Mouse).
 *
 * Packet Structure (9 bytes):
 * [0] - Type (0=Move, 1=Click, 2=Scroll, 3=Down, 4=Up)
 * [1-4] - Float DX (Big Endian)
 * [5-8] - Float DY (Big Endian)
 */
object MousePacket {
    const val MOVE: Byte = 0
    const val CLICK: Byte = 1
    const val SCROLL: Byte = 2
    const val DOWN: Byte = 3
    const val UP: Byte = 4

    fun pack(event: String, dx: Float, dy: Float): ByteArray {
        val type = when (event) {
            "move" -> MOVE
            "click" -> CLICK
            "scroll" -> SCROLL
            "down" -> DOWN
            "up" -> UP
            else -> MOVE
        }
        val result = ByteArray(9)
        result[0] = type

        // Pack DX
        val xBits = dx.toRawBits()
        result[1] = (xBits shr 24).toByte()
        result[2] = (xBits shr 16).toByte()
        result[3] = (xBits shr 8).toByte()
        result[4] = xBits.toByte()

        // Pack DY
        val yBits = dy.toRawBits()
        result[5] = (yBits shr 24).toByte()
        result[6] = (yBits shr 16).toByte()
        result[7] = (yBits shr 8).toByte()
        result[8] = yBits.toByte()

        return result
    }

    data class Unpacked(val event: String, val dx: Float, val dy: Float)

    fun unpack(bytes: ByteArray): Unpacked? {
        if (bytes.size < 9) return null

        val type = bytes[0]
        val event = when (type) {
            MOVE -> "move"
            CLICK -> "click"
            SCROLL -> "scroll"
            DOWN -> "down"
            UP -> "up"
            else -> "move"
        }

        val xBits = ((bytes[1].toInt() and 0xFF) shl 24) or
                    ((bytes[2].toInt() and 0xFF) shl 16) or
                    ((bytes[3].toInt() and 0xFF) shl 8) or
                    (bytes[4].toInt() and 0xFF)
        val dx = Float.fromBits(xBits)

        val yBits = ((bytes[5].toInt() and 0xFF) shl 24) or
                    ((bytes[6].toInt() and 0xFF) shl 16) or
                    ((bytes[7].toInt() and 0xFF) shl 8) or
                    (bytes[8].toInt() and 0xFF)
        val dy = Float.fromBits(yBits)

        return Unpacked(event, dx, dy)
    }
}
