package com.playbridge.player.server

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.playbridge.shared.browser.PluginBridgeProtocol

/** Visible plugin binding protects the main process. No player, UI, or decoder is created here. */
class PluginBridgeService : Service() {
    private var destroyed = false
    private val messenger: Messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (destroyed) return
            try {
                when (message.what) {
                    PluginBridgeProtocol.HELLO -> PluginBridge.hello(message, messenger)
                    PluginBridgeProtocol.GOODBYE, PluginBridgeProtocol.VIDEO_STATUS,
                    PluginBridgeProtocol.CONTEXT_IDLE -> PluginBridge.handleClientMessage(message, messenger)
                }
            } catch (_: RuntimeException) {
                // Malformed Bundle fields must not crash the player. Never log payload contents.
            }
        }
    })

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        destroyed = true
        PluginBridge.disconnect(messenger)
        super.onDestroy()
    }
}
