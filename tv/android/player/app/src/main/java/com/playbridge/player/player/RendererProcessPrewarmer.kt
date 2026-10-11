package com.playbridge.player.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.playbridge.player.logging.FileLogger

/**
 * Warms isolated Binder endpoints without allocating a player or decoder. Browser contexts
 * release both bindings/processes and suppress rebinds so Gecko/WebView can use the memory.
 */
internal object RendererProcessPrewarmer {
    private const val TAG = "RendererPrewarmer"
    private val mainHandler by lazy(LazyThreadSafetyMode.NONE) { Handler(Looper.getMainLooper()) }
    private var controller: RendererPrewarmController? = null
    @Volatile private var browserActive = false

    fun start(context: Context) {
        if (controller != null) return
        val app = context.applicationContext
        controller = RendererPrewarmController(
            platform = object : RendererPrewarmController.Platform {
                override fun bind(
                    kind: RendererProcessSupervisor.Kind,
                    connected: () -> Unit,
                    disconnected: () -> Unit,
                ): (() -> Unit)? {
                    val serviceClass = when (kind) {
                        RendererProcessSupervisor.Kind.MPV -> MpvRendererService::class.java
                        RendererProcessSupervisor.Kind.EXO -> ExoRendererService::class.java
                    }
                    val connection = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, service: IBinder?) = connected()
                        override fun onServiceDisconnected(name: ComponentName?) = disconnected()
                        override fun onBindingDied(name: ComponentName?) = disconnected()
                        override fun onNullBinding(name: ComponentName?) = disconnected()
                    }
                    val flags = Context.BIND_AUTO_CREATE or Context.BIND_WAIVE_PRIORITY
                    if (!app.bindService(Intent(app, serviceClass), connection, flags)) return null
                    return { runCatching { app.unbindService(connection) } }
                }

                override fun schedule(delayMs: Long, action: () -> Unit): () -> Unit {
                    val callback = Runnable(action)
                    mainHandler.postDelayed(callback, delayMs)
                    return { mainHandler.removeCallbacks(callback) }
                }

                override fun releaseProcess(kind: RendererProcessSupervisor.Kind) {
                    releaseProcessWhenUnused(app, kind)
                }
            },
            onWarm = { FileLogger.d(TAG, "$it renderer process is warm") },
            onDisconnected = { FileLogger.w(TAG, "$it renderer process disconnected; scheduling prewarm") },
        ).also { it.setBrowserActive(browserActive) }
    }

    private fun releaseProcessWhenUnused(context: Context, kind: RendererProcessSupervisor.Kind) {
        if (!browserActive) return
        // The stop broadcast and context write can arrive in different main-loop turns. Do not
        // turn an intentional browser handoff into renderer-failure/failover in a retiring host.
        if (PlayerHostActivity.isActive()) {
            mainHandler.postDelayed({ releaseProcessWhenUnused(context, kind) }, 50L)
        } else {
            RendererProcessSupervisor.terminate(context, kind)
        }
    }

    /** Context writes can originate in the IO command collector; Binder operations stay on main. */
    fun setBrowserActive(active: Boolean) {
        browserActive = active
        if (Looper.myLooper() == Looper.getMainLooper()) {
            controller?.setBrowserActive(browserActive)
        } else {
            // Read the latest state when applied: an obsolete browser transition must not kill
            // a renderer after a newer player command has already taken ownership.
            mainHandler.post { controller?.setBrowserActive(browserActive) }
        }
    }
}
