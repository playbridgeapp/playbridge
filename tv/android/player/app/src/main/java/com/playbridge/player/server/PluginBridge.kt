package com.playbridge.player.server

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import com.playbridge.shared.browser.PluginBridgeProtocol

/** Main-process transport only. ServerService retains ownership of routing/context/status policy. */
internal object PluginBridge {
    private class Client(val reply: Messenger, val service: Messenger, val uid: Int, val resumed: Boolean) {
        lateinit var death: IBinder.DeathRecipient
        fun unlink() {
            runCatching { reply.binder.unlinkToDeath(death, 0) }
        }
    }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val clients = PluginClientRegistry<Client, Bundle> { client, data ->
        try {
            client.reply.send(Message.obtain().apply {
                what = PluginBridgeProtocol.COMMAND
                replyTo = client.service
                this.data = data
            })
            true
        } catch (_: RemoteException) {
            client.unlink()
            false
        }
    }

    fun hello(message: Message, service: Messenger) {
        val reply = message.replyTo ?: return
        if (message.sendingUid < 0) return
        val resumed = message.data.getBoolean(PluginBridgeProtocol.RESUMED, false)
        val next = Client(reply, service, message.sendingUid, resumed)
        next.death = IBinder.DeathRecipient {
            mainHandler.post { if (clients.remove(next)) next.unlink() }
        }
        clients.replace(next)?.unlink()
        try {
            reply.binder.linkToDeath(next.death, 0)
        } catch (_: RemoteException) {
            clients.remove(next)
            return
        }
        if (resumed) ServerService.recoverExternalBrowserContextFromPlugin()
    }

    /** Authenticate a message against the current hello, including its reply endpoint. */
    fun handleClientMessage(message: Message, service: Messenger) {
        val client = clients.current() ?: return
        if (client.service.binder != service.binder || message.sendingUid != client.uid ||
            message.replyTo?.binder != client.reply.binder) return
        when (message.what) {
            PluginBridgeProtocol.GOODBYE -> if (clients.remove(client)) client.unlink()
            PluginBridgeProtocol.VIDEO_STATUS -> message.data.getString(PluginBridgeProtocol.STATUS_JSON)
                ?.let(ServerService::receiveExternalBrowserVideoStatus)
            PluginBridgeProtocol.CONTEXT_IDLE -> ServerService.receiveExternalBrowserClosed()
        }
    }

    fun recoverContextIfResumed() {
        if (clients.current()?.resumed == true) ServerService.recoverExternalBrowserContextFromPlugin()
    }

    fun disconnect(service: Messenger) {
        val client = clients.current() ?: return
        if (client.service.binder == service.binder && clients.remove(client)) client.unlink()
    }

    /** One main-loop FIFO for all mouse/remote/control/UA messages and fallback dispatches. */
    fun sendCommand(intent: Intent, fallback: () -> Unit) {
        val data = Bundle(intent.extras ?: Bundle()).apply {
            putString(PluginBridgeProtocol.ACTION, intent.action)
        }
        val deliver = Runnable { clients.deliver(data, fallback) }
        // Always enqueue, even for main-thread callers; they cannot overtake queued mouse input.
        mainHandler.post(deliver)
    }
}
