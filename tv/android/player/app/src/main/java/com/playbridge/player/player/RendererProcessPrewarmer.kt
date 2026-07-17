package com.playbridge.player.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.playbridge.player.logging.FileLogger
import java.util.EnumMap

/**
 * Keeps the isolated renderer processes lightly warmed without creating a player or decoder.
 *
 * Binding loads the process, Application, service class, and Binder endpoint. The renderer
 * engines remain lazy until [IRendererService.prepare], so the idle bindings do not retain a
 * MediaCodec instance. `BIND_WAIVE_PRIORITY` lets Android reclaim either process under memory
 * pressure; a killed/crashed renderer is rebound after a short cooldown.
 */
internal object RendererProcessPrewarmer {
    private const val TAG = "RendererPrewarmer"
    private const val REBIND_DELAY_MS = 1_500L

    private data class BindingState(
        var connection: ServiceConnection? = null,
        var pendingRebind: Runnable? = null,
    )

    private val mainHandler by lazy(LazyThreadSafetyMode.NONE) {
        Handler(Looper.getMainLooper())
    }
    private val bindings = EnumMap<RendererProcessSupervisor.Kind, BindingState>(
        RendererProcessSupervisor.Kind::class.java,
    ).apply {
        RendererProcessSupervisor.Kind.entries.forEach { put(it, BindingState()) }
    }
    private var applicationContext: Context? = null

    fun start(context: Context) {
        if (applicationContext != null) return
        applicationContext = context.applicationContext
        RendererProcessSupervisor.Kind.entries.forEach(::bind)
    }

    private fun bind(kind: RendererProcessSupervisor.Kind) {
        val context = applicationContext ?: return
        val state = bindings.getValue(kind)
        if (state.connection != null) return
        state.pendingRebind?.let(mainHandler::removeCallbacks)
        state.pendingRebind = null

        val serviceClass = when (kind) {
            RendererProcessSupervisor.Kind.MPV -> MpvRendererService::class.java
            RendererProcessSupervisor.Kind.EXO -> ExoRendererService::class.java
        }
        lateinit var newConnection: ServiceConnection
        newConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (state.connection !== newConnection) return
                FileLogger.d(TAG, "$kind renderer process is warm")
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                recycleBinding(kind, newConnection)
            }

            override fun onBindingDied(name: ComponentName?) {
                recycleBinding(kind, newConnection)
            }

            override fun onNullBinding(name: ComponentName?) {
                recycleBinding(kind, newConnection)
            }
        }
        state.connection = newConnection
        val flags = Context.BIND_AUTO_CREATE or Context.BIND_WAIVE_PRIORITY
        if (!context.bindService(Intent(context, serviceClass), newConnection, flags)) {
            state.connection = null
            scheduleRebind(kind)
        }
    }

    private fun recycleBinding(
        kind: RendererProcessSupervisor.Kind,
        disconnected: ServiceConnection,
    ) {
        val context = applicationContext ?: return
        val state = bindings.getValue(kind)
        if (state.connection !== disconnected) return
        state.connection = null
        runCatching { context.unbindService(disconnected) }
        FileLogger.w(TAG, "$kind renderer process disconnected; scheduling prewarm")
        scheduleRebind(kind)
    }

    private fun scheduleRebind(kind: RendererProcessSupervisor.Kind) {
        val state = bindings.getValue(kind)
        if (state.pendingRebind != null) return
        val rebind = Runnable {
            state.pendingRebind = null
            bind(kind)
        }
        state.pendingRebind = rebind
        mainHandler.postDelayed(rebind, REBIND_DELAY_MS)
    }
}
