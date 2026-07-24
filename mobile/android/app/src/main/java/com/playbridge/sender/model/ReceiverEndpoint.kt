package com.playbridge.sender.model

import kotlinx.serialization.Serializable

/** A receiver transport exposed on the local network. */
@Serializable
enum class CastProtocol(
    val displayName: String,
    val discoveryMask: Int,
    val defaultPort: Int?,
) {
    PLAYBRIDGE("PlayBridge", 1, null),
    DLNA("DLNA", 2, null),
    ROKU("Roku", 4, 8060),
    GOOGLE_CAST("Google Cast", 16, 8009),
}

/**
 * Protocol-qualified receiver identity.
 *
 * IDs advertised by unrelated protocols are not comparable. Including [protocol] prevents a
 * multi-protocol television from overwriting one of its other endpoints in discovery/history.
 */
@Serializable
data class EndpointKey(
    val protocol: CastProtocol,
    val stableId: String,
) {
    override fun toString(): String = "${protocol.name.lowercase()}:$stableId"
}

/** Network and discovery data for one protocol endpoint. Credentials live separately. */
@Serializable
data class ReceiverEndpoint(
    val key: EndpointKey,
    val name: String,
    val addresses: List<String>,
    val port: Int? = null,
    val wssPort: Int? = null,
    val logsPort: Int? = null,
    /** SSDP/UPnP device-description document used for re-enrichment and Rust sessions. */
    val descriptionUrl: String? = null,
    /** Protocol playback-control endpoint (DLNA AVTransport today). */
    val controlUrl: String? = null,
    /** Optional DLNA RenderingControl endpoint. */
    val renderingControlUrl: String? = null,
) {
    val protocol: CastProtocol get() = key.protocol
    val preferredAddress: String? get() = addresses.firstOrNull()
    val effectivePort: Int? get() = port ?: protocol.defaultPort
}

/** PlayBridge-only secrets and receiver capabilities. Never attach these to another protocol. */
@Serializable
data class PlayBridgeCredentials(
    val token: String = "",
    val certFingerprint: String? = null,
    val players: List<String> = emptyList(),
    val browsers: List<String> = emptyList(),
)

/** Persisted endpoint plus optional protocol-specific credentials. */
@Serializable
data class SavedReceiverEndpoint(
    val endpoint: ReceiverEndpoint,
    val playBridgeCredentials: PlayBridgeCredentials? = null,
    val lastConnected: Long = System.currentTimeMillis(),
) {
    init {
        require(
            playBridgeCredentials == null || endpoint.protocol == CastProtocol.PLAYBRIDGE,
        ) { "PlayBridge credentials cannot be stored for ${endpoint.protocol}" }
    }
}
