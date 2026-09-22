package com.playbridge.player.ui

internal data class PairingNavigationState(
    val pendingDeviceUUID: String? = null,
) {
    fun onRequest(deviceUUID: String): PairingNavigationState = copy(
        pendingDeviceUUID = deviceUUID,
    )

    fun onCompletion(deviceUUID: String, approved: Boolean): PairingNavigationTransition {
        if (deviceUUID != pendingDeviceUUID) {
            return PairingNavigationTransition(this, openLibrary = false)
        }
        return PairingNavigationTransition(
            state = copy(pendingDeviceUUID = null),
            openLibrary = approved,
        )
    }
}

internal data class PairingNavigationTransition(
    val state: PairingNavigationState,
    val openLibrary: Boolean,
)
