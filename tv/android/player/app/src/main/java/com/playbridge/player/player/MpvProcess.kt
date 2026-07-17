package com.playbridge.player.player

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.playbridge.player.logging.FileLogger
import java.util.concurrent.atomic.AtomicBoolean

/** Process-role helpers for the private process that owns libmpv and its MediaCodec state. */
internal object MpvProcess {
    private const val TAG = "MpvProcess"
    private const val PROCESS_SUFFIX = ":mpv"
    private const val TERMINATION_DELAY_MS = 500L
    private val terminationScheduled = AtomicBoolean(false)
    private val mainHandler by lazy(LazyThreadSafetyMode.NONE) {
        Handler(Looper.getMainLooper())
    }
    private val terminationRunnable = Runnable {
        if (!terminationScheduled.compareAndSet(true, false)) return@Runnable
        FileLogger.i(TAG, "Terminating private MPV process to reclaim native decoder state")
        Process.killProcess(Process.myPid())
    }

    fun isCurrent(context: Context): Boolean =
        isMpvProcessName(context.packageName, currentProcessName(context))

    internal fun isMpvProcessName(packageName: String, processName: String?): Boolean =
        processName == packageName + PROCESS_SUFFIX

    /**
     * End the private MPV process after Activity teardown has been reported to the system.
     * Process death is the only reliable cancellation boundary for a JNI call wedged in a
     * vendor MediaCodec driver.
     */
    fun terminateAfterActivityDestroy(context: Context) {
        if (!isCurrent(context) || !terminationScheduled.compareAndSet(false, true)) return
        mainHandler.postDelayed(terminationRunnable, TERMINATION_DELAY_MS)
    }

    /** Keep a newly-created MPV activity alive if it arrives during the teardown grace period. */
    fun cancelScheduledTermination() {
        if (!terminationScheduled.compareAndSet(true, false)) return
        mainHandler.removeCallbacks(terminationRunnable)
        FileLogger.i(TAG, "Cancelled private MPV process termination for a new activity")
    }

    /** Kill a running private MPV process from the main process before another decoder starts. */
    fun terminateRunningProcess(context: Context): Boolean {
        val terminated = RendererProcessSupervisor.terminate(context, RendererProcessSupervisor.Kind.MPV)
        if (terminated) FileLogger.w(TAG, "Terminating stale private MPV process")
        return terminated
    }

    /**
     * Returns whether the private MPV process is still visible to ActivityManager.
     *
     * ActivityManager can briefly retain a process entry after killProcess(), so callers
     * must not use a fixed sleep as proof that libmpv has stopped. This method is deliberately
     * kept in the main process and never performs a blocking wait.
     */
    fun isRunning(context: Context): Boolean =
        RendererProcessSupervisor.isRunning(context, RendererProcessSupervisor.Kind.MPV)

    /**
     * Poll for actual process exit without blocking the caller's thread.
     * [onComplete] receives true when the process disappeared before [timeoutMs].
     */
    fun awaitExit(
        context: Context,
        timeoutMs: Long,
        onComplete: (exited: Boolean) -> Unit,
    ) {
        RendererProcessSupervisor.awaitExit(
            context,
            RendererProcessSupervisor.Kind.MPV,
            timeoutMs,
            onComplete,
        )
    }

    private fun currentProcessName(context: Context): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Application.getProcessName()
        }

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val pid = Process.myPid()
        return activityManager?.runningAppProcesses
            ?.firstOrNull { it.pid == pid }
            ?.processName
    }
}
