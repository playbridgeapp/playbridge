package com.playbridge.player.browser

import android.app.Application
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process

/** Process-role helper for the private process that owns System WebView and its renderer. */
internal object WebProcess {
    private const val PROCESS_SUFFIX = ":web"

    fun isCurrent(context: Context): Boolean =
        isWebProcessName(context.packageName, currentProcessName(context))

    internal fun isWebProcessName(packageName: String, processName: String?): Boolean =
        processName == packageName + PROCESS_SUFFIX

    private fun currentProcessName(context: Context): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return Application.getProcessName()
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val pid = Process.myPid()
        return activityManager?.runningAppProcesses
            ?.firstOrNull { it.pid == pid }
            ?.processName
    }
}
