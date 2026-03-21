package com.playbridge.protocol

object BluetoothConstants {
    const val SERVICE_NAME = "PlayBridgeRemote"
    // Custom UUID for PlayBridge Bluetooth RFCOMM socket connection.
    // Must NOT be the standard SPP UUID (00001101-...) — that conflicts with Android's
    // built-in SPP/HFP profile handlers and causes SDP to return channel -1 on the client.
    const val SERVICE_UUID_STRING = "a8f5f167-f92d-4a28-9f02-5f8d9b5c6b4e"
}
