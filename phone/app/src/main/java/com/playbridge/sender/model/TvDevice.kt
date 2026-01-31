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
    val lastConnected: Long = System.currentTimeMillis()
)
