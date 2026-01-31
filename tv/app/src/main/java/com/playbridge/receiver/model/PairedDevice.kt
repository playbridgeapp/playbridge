package com.playbridge.receiver.model

import kotlinx.serialization.Serializable

/**
 * Represents a paired phone device
 */
@Serializable
data class PairedDevice(
    val id: String,
    val name: String,
    val lastConnected: Long = System.currentTimeMillis()
)
