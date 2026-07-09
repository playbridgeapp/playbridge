package com.playbridge.sender.util

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File

/**
 * GeckoView spawns child processes (`:tab_*`, `:gpu_*`, …). Each still runs
 * [Application.onCreate]. Cast FGS / Koin session ownership must stay main-process only —
 * a child seeing `hasActiveSession=false` would otherwise [android.content.Context.stopService]
 * the main process's cast notification a few seconds after connect (STOP_GRACE race).
 */
object ProcessUtil {
    fun processName(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Application.getProcessName()
        }
        return readProcCmdline() ?: "unknown"
    }

    /**
     * True when this is the app's primary process (`packageName`), not a Gecko child.
     * Unknown process name (detection failure) is treated as main so we never skip
     * essential init on devices where /proc is unreadable.
     */
    fun isMainProcess(context: Context): Boolean {
        val name = processName()
        return name == "unknown" || name == context.packageName
    }

    private fun readProcCmdline(): String? = runCatching {
        File("/proc/${Process.myPid()}/cmdline").readBytes()
            .takeWhile { it != 0.toByte() }
            .toByteArray()
            .toString(Charsets.UTF_8)
            .trim()
            .ifEmpty { null }
    }.getOrNull()
}
