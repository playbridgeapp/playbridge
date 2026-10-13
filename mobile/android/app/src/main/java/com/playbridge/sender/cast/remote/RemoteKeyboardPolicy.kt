package com.playbridge.sender.cast.remote

enum class RemoteKeyboardRoute { PlayBridge, FullControl }

/** Use the receiver's text support, never the merged Full control capabilities. */
internal fun RemoteUiState.keyboardRoute(fullControlReady: Boolean = this.fullControlReady): RemoteKeyboardRoute {
    val receiverText = fullControl?.receiverText ?: capabilities.text
    val browserText = connection.ready && receiver?.profile == RemoteProfile.AndroidTv &&
        context == RemoteContext.Browser && receiverText.replace == Support.Supported
    return if (fullControlReady && !browserText) RemoteKeyboardRoute.FullControl else RemoteKeyboardRoute.PlayBridge
}
