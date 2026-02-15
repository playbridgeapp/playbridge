package com.playbridge.sender.model

import com.playbridge.protocol.protocolJson
import kotlinx.serialization.Serializable

// ==================== QR Code Data ====================

/**
 * Data encoded in the TV's QR code
 */
@Serializable
data class QRCodeData(
    val ip: String,
    val port: Int,
    val token: String,
    val name: String,
    val version: Int = 1
)

fun parseQRCode(jsonString: String): QRCodeData? {
    return try {
        protocolJson.decodeFromString<QRCodeData>(jsonString)
    } catch (e: Exception) {
        null
    }
}
