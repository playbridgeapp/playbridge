package com.playbridge.player.player

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process

/** Process-role helpers for the private process that owns ExoPlayer/MediaCodec state. */
internal object ExoProcess {
    private const val PROCESS_SUFFIX = ":exo"

    fun isCurrent(context: Context): Boolean =
        isExoProcessName(context.packageName, currentProcessName(context))

    internal fun isExoProcessName(packageName: String, processName: String?): Boolean =
        processName == packageName + PROCESS_SUFFIX

    fun isRunning(context: Context): Boolean =
        RendererProcessSupervisor.isRunning(context, RendererProcessSupervisor.Kind.EXO)

    fun terminateRunningProcess(context: Context): Boolean =
        RendererProcessSupervisor.terminate(context, RendererProcessSupervisor.Kind.EXO)

    fun awaitExit(context: Context, timeoutMs: Long, onComplete: (Boolean) -> Unit) =
        RendererProcessSupervisor.awaitExit(
            context,
            RendererProcessSupervisor.Kind.EXO,
            timeoutMs,
            onComplete,
        )

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
