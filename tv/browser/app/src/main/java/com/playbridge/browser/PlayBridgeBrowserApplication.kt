package com.playbridge.browser

import android.app.Application
import android.util.Log
import com.playbridge.browser.logging.FileLogger
import org.mozilla.geckoview.GeckoRuntime

class PlayBridgeBrowserApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        com.playbridge.shared.SharedContext.init(this)
        FileLogger.init(this)

        // Pre-warm GeckoRuntime and ensure extensions once at startup to improve browser launch speed.
        // IMPORTANT: Only do this in the main process! Child processes (Gecko tab processes)
        // should NOT re-initialize the runtime.
        if (isMainProcess()) {
            try {
                val runtime = GeckoRuntime.getDefault(this)
                runtime.warmUp()

                runtime.webExtensionController.ensureBuiltIn(
                    "resource://android/assets/extensions/ublock_origin/",
                    "uBlock0@raymondhill.net"
                ).accept(
                    { _ -> Log.i("PBApplication", "uBlock Origin pre-initialized") },
                    { e -> Log.e("PBApplication", "uBlock Origin pre-init failed", e) }
                )

                runtime.webExtensionController.ensureBuiltIn(
                    "resource://android/assets/extensions/pb_bridge/",
                    "pb-bridge@playbridge.com"
                ).accept(
                    { _ -> Log.i("PBApplication", "PB Bridge pre-initialized") },
                    { e -> Log.e("PBApplication", "PB Bridge pre-init failed", e) }
                )
            } catch (e: Exception) {
                Log.e("PBApplication", "Failed to pre-warm Gecko", e)
            }
        }
    }

    private fun isMainProcess(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            Application.getProcessName() == packageName
        } else {
            // Fallback for older versions (Min SDK is 26)
            val pid = android.os.Process.myPid()
            val am = getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.runningAppProcesses?.find { it.pid == pid }?.processName == packageName
        }
    }
}
