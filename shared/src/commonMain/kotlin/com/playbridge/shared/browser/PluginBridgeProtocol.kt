package com.playbridge.shared.browser

/** Internal signature-protected Android Messenger contract; not the phone/WSS protocol. */
object PluginBridgeProtocol {
    const val PLAYER_PACKAGE = "com.playbridge.player"
    const val SERVICE_CLASS = "com.playbridge.player.server.PluginBridgeService"
    const val PERMISSION = "com.playbridge.permission.BROWSER_CONTROL"

    const val HELLO = 1
    const val GOODBYE = 2
    const val VIDEO_STATUS = 3
    const val CONTEXT_IDLE = 4
    const val COMMAND = 5

    const val RESUMED = "resumed"
    const val STATUS_JSON = "status_json"
    const val ACTION = "action"
    // COMMAND uses the same primitive extras as the broadcast transport.
}
