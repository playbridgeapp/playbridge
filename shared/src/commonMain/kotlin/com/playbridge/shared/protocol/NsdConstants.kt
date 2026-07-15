package com.playbridge.shared.protocol

object NsdConstants {
    const val SERVICE_TYPE = "_playbridge._tcp."
    const val KEY_DEVICE_NAME = "device_name"

    // TXT key advertising the receiver's wss:// port. Absent when the receiver
    // has no TLS listener.
    const val KEY_WSS_PORT = "wss_port"

    // Optional TXT key advertising Android TV's HTTP diagnostics endpoint.
    const val KEY_LOGS_PORT = "logs_port"
}
