package com.playbridge.sender.model

import kotlinx.serialization.Serializable

/**
 * Stored TV device connection info
 */
@Serializable
data class TvDevice(
    val ip: String,
    val port: Int,
    val token: String,
    val name: String,
    val uuid: String = "",
    // Port of the receiver's wss:// listener (from the wss_port mDNS TXT attr).
    // Null means the receiver only serves plaintext ws://.
    val wssPort: Int? = null,
    // Optional HTTP diagnostics port advertised via the logs_port mDNS TXT attr.
    val logsPort: Int? = null,
    // SPKI pin (sha256/<base64>) captured at pairing; validated on every wss
    // connection. Null until paired with a TLS-capable receiver.
    val certFingerprint: String? = null,
    // Players/browsers this TV reported it supports at the last auth (player_mode /
    // browser_mode ids, e.g. "mpv", "gecko"). Drives the phone's pickers via
    // TvCapabilityOptions. Empty until we've authed with a capability-reporting receiver.
    val players: List<String> = emptyList(),
    val browsers: List<String> = emptyList(),
    // Per-item media presentations supported by the native receiver. Empty means
    // a legacy receiver, which the phone treats as video-only.
    val mediaKinds: List<String> = emptyList(),
    // New protocol-qualified model. Null is retained only so records written by older builds can
    // be decoded and migrated using the legacy booleans below.
    val protocol: CastProtocol? = null,
    // Rust discovery can return multiple IPv4/IPv6 endpoints. [ip] remains the preferred address
    // for compatibility with the existing WebSocket and UI call sites during migration.
    val addresses: List<String> = emptyList(),
    // DLNA/UPnP renderer discovered via SSDP (not the native WS receiver). When true,
    // [controlUrl] is the AVTransport SOAP endpoint and there is no token/pairing.
    val isDlna: Boolean = false,
    val isRoku: Boolean = false,
    val isGoogleCast: Boolean = false,
    val descriptionUrl: String? = null,
    val controlUrl: String? = null,
    val renderingControlUrl: String? = null,
    val lastConnected: Long = System.currentTimeMillis()
) {
    val resolvedProtocol: CastProtocol
        get() = protocol ?: when {
            isDlna -> CastProtocol.DLNA
            isRoku -> CastProtocol.ROKU
            isGoogleCast -> CastProtocol.GOOGLE_CAST
            else -> CastProtocol.PLAYBRIDGE
        }

    fun supportsNativeMediaKind(kind: String): Boolean =
        resolvedProtocol != CastProtocol.PLAYBRIDGE ||
            kind in mediaKinds ||
            (mediaKinds.isEmpty() && kind == "video")

    val endpointKey: EndpointKey
        get() = EndpointKey(
            protocol = resolvedProtocol,
            stableId = uuid.ifEmpty { "${addresses.firstOrNull() ?: ip}:${port}" },
        )

    fun toReceiverEndpoint(): ReceiverEndpoint = ReceiverEndpoint(
        key = endpointKey,
        name = name,
        addresses = (addresses.ifEmpty { listOf(ip) }).filter(String::isNotBlank).distinct(),
        port = port.takeIf { it > 0 },
        wssPort = wssPort,
        logsPort = logsPort,
        descriptionUrl = descriptionUrl,
        controlUrl = controlUrl,
        renderingControlUrl = renderingControlUrl,
    )

    fun toSavedEndpoint(): SavedReceiverEndpoint = SavedReceiverEndpoint(
        endpoint = toReceiverEndpoint(),
        playBridgeCredentials = if (resolvedProtocol == CastProtocol.PLAYBRIDGE) {
            PlayBridgeCredentials(token, certFingerprint, players, browsers, mediaKinds)
        } else {
            null
        },
        lastConnected = lastConnected,
    )

    companion object {
        fun fromSavedEndpoint(saved: SavedReceiverEndpoint): TvDevice {
            val endpoint = saved.endpoint
            val address = endpoint.preferredAddress.orEmpty()
            val credentials = saved.playBridgeCredentials
            return TvDevice(
                ip = address,
                port = endpoint.effectivePort ?: 0,
                token = credentials?.token.orEmpty(),
                name = endpoint.name,
                uuid = endpoint.key.stableId,
                wssPort = endpoint.wssPort,
                logsPort = endpoint.logsPort,
                certFingerprint = credentials?.certFingerprint,
                players = credentials?.players.orEmpty(),
                browsers = credentials?.browsers.orEmpty(),
                mediaKinds = credentials?.mediaKinds.orEmpty(),
                protocol = endpoint.protocol,
                addresses = endpoint.addresses,
                isDlna = endpoint.protocol == CastProtocol.DLNA,
                isRoku = endpoint.protocol == CastProtocol.ROKU,
                isGoogleCast = endpoint.protocol == CastProtocol.GOOGLE_CAST,
                descriptionUrl = endpoint.descriptionUrl,
                controlUrl = endpoint.controlUrl,
                renderingControlUrl = endpoint.renderingControlUrl,
                lastConnected = saved.lastConnected,
            )
        }
    }
}
