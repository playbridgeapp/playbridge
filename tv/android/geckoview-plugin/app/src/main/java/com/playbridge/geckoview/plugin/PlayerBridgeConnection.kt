package com.playbridge.geckoview.plugin

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import com.playbridge.shared.browser.PluginBridgeProtocol

/** Started-Activity binding: Android propagates this visible client's importance to the player. */
internal class PlayerBridgeConnection(
    private val context: Context,
    private val isResumed: () -> Boolean,
    private val onCommand: (Intent) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private var connection: ServiceConnection? = null
    private var service: Messenger? = null
    private val reply = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (!started || message.what != PluginBridgeProtocol.COMMAND ||
                message.replyTo?.binder != service?.binder || service == null) return
            try {
                val action = message.data.getString(PluginBridgeProtocol.ACTION) ?: return
                onCommand(Intent(action).putExtras(message.data))
            } catch (_: RuntimeException) {
                // Ignore malformed primitive fields without logging text, URLs, or page data.
            }
        }
    })

    fun start() {
        started = true
        if (connection == null) bind()
    }

    private fun bind() {
        val next = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (connection !== this || !started || binder == null) return
                service = Messenger(binder)
                hello()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                if (connection === this) service = null
                // BIND_AUTO_CREATE keeps this binding and reconnects after process death.
            }

            override fun onBindingDied(name: ComponentName?) {
                if (connection !== this) return
                releaseBinding()
                handler.post { if (started && connection == null) bind() }
            }

            override fun onNullBinding(name: ComponentName?) {
                if (connection === this) releaseBinding()
                // Unsupported service: retain the broadcast fallback until the next onStart.
            }
        }
        connection = next
        val intent = Intent().setComponent(ComponentName(
            PluginBridgeProtocol.PLAYER_PACKAGE, PluginBridgeProtocol.SERVICE_CLASS,
        ))
        try {
            // Do not waive priority: the purpose is to keep the WebSocket owner important
            // while the plugin is started/visible. No renderer services are bound here.
            if (!context.bindService(intent, next, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)) {
                releaseBinding()
            }
        } catch (_: RuntimeException) {
            // Old/missing player service or permission mismatch: broadcasts keep working.
            releaseBinding()
        }
    }

    fun hello(): Boolean = send(PluginBridgeProtocol.HELLO, Bundle().apply {
        putBoolean(PluginBridgeProtocol.RESUMED, isResumed())
    })

    fun videoStatus(json: String): Boolean = send(PluginBridgeProtocol.VIDEO_STATUS, Bundle().apply {
        putString(PluginBridgeProtocol.STATUS_JSON, json)
    })

    fun browserClosed(): Boolean = send(PluginBridgeProtocol.CONTEXT_IDLE)

    private fun send(type: Int, data: Bundle = Bundle()): Boolean {
        val target = service ?: return false
        return try {
            target.send(Message.obtain().apply {
                what = type
                replyTo = reply
                this.data = data
            })
            true
        } catch (_: RemoteException) {
            if (service === target) service = null
            false
        }
    }

    fun stop() {
        send(PluginBridgeProtocol.GOODBYE)
        started = false
        releaseBinding()
        handler.removeCallbacksAndMessages(null)
    }

    private fun releaseBinding() {
        val old = connection
        connection = null
        service = null
        if (old != null) runCatching { context.unbindService(old) }
    }
}
