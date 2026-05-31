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
    // SPKI pin (sha256/<base64>) captured at pairing; validated on every wss
    // connection. Null until paired with a TLS-capable receiver.
    val certFingerprint: String? = null,
    // Players/browsers this TV reported it supports at the last auth (player_mode /
    // browser_mode ids, e.g. "internal_mpv", "gecko"). Drives the phone's pickers via
    // TvCapabilityOptions. Empty until we've authed with a capability-reporting receiver.
    val players: List<String> = emptyList(),
    val browsers: List<String> = emptyList(),
    val lastConnected: Long = System.currentTimeMillis()
)
