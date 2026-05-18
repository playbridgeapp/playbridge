package com.playbridge.shared.protocol

import kotlinx.serialization.json.Json

/**
 * Lenient JSON instance shared by code that serializes local app state (HistoryStore, PairingStore).
 *
 * Wire-protocol encoding/decoding lives in `IncomingMessage.kt` (Wire + Moshi). This [Json]
 * instance is intentionally kept around because several stores serialize their own local
 * data classes — it is NOT used for the over-the-wire protocol anymore.
 */
val protocolJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
}
