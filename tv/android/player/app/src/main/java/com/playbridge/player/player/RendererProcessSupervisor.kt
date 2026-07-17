package com.playbridge.player.player

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import java.util.EnumMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Main-process lifecycle boundary for renderer processes.
 *
 * Renderer teardown is intentionally process-based: a vendor MediaCodec/JNI call can remain
 * stuck after the activity has stopped, and no amount of Activity lifecycle cleanup can make
 * a new libmpv/MediaCodec instance safe in that case.
 */
internal object RendererProcessSupervisor {
    enum class Kind(val suffix: String) {
        MPV(":mpv"),
        EXO(":exo"),
    }

    private const val POLL_INTERVAL_MS = 50L
    private val handler by lazy(LazyThreadSafetyMode.NONE) { Handler(Looper.getMainLooper()) }
    private val generations = EnumMap<Kind, AtomicLong>(Kind::class.java).apply {
        Kind.entries.forEach { put(it, AtomicLong(0L)) }
    }

    internal fun processName(packageName: String, kind: Kind): String = packageName + kind.suffix

    /** Starts a new logical renderer session and invalidates callbacks from older sessions. */
    fun nextGeneration(kind: Kind): Long = generations.getValue(kind).incrementAndGet()

    fun isCurrentGeneration(kind: Kind, generation: Long): Boolean =
        generations.getValue(kind).get() == generation

    fun isRunning(context: Context, kind: Kind): Boolean = find(context, kind) != null

    fun terminate(context: Context, kind: Kind): Boolean {
        val process = find(context, kind) ?: return false
        if (process.pid == Process.myPid()) return false
        Process.killProcess(process.pid)
        return true
    }

    /** Polls without blocking the caller. The callback runs on the main looper. */
    fun awaitExit(
        context: Context,
        kind: Kind,
        timeoutMs: Long,
        onComplete: (exited: Boolean) -> Unit,
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs.coerceAtLeast(0L)
        fun poll() {
            val exited = !isRunning(context, kind)
            if (exited || SystemClock.elapsedRealtime() >= deadline) {
                onComplete(exited)
            } else {
                handler.postDelayed(::poll, POLL_INTERVAL_MS)
            }
        }
        poll()
    }

    private fun find(
        context: Context,
        kind: Kind,
    ): ActivityManager.RunningAppProcessInfo? {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return null
        val expectedName = processName(context.packageName, kind)
        return activityManager.runningAppProcesses
            ?.firstOrNull { it.processName == expectedName && it.pid != Process.myPid() }
    }
}
