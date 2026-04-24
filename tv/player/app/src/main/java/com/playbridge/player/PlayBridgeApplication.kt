package com.playbridge.player

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.util.Log
import com.playbridge.player.logging.FileLogger
import com.playbridge.player.server.ServerService

private const val TAG = "PlayBridgeApp"

/**
 * Custom Application that installs a global crash handler.
 *
 * Goal: if ExoPlayer, WebView, or GeckoView crashes in a background thread, finish the
 * offending activity (Player or Browser) cleanly and keep the ServerService alive so
 * the TV remains ready to accept the next command from the phone.
 *
 * If the crash is on the main thread we can't safely recover, so the default handler
 * is invoked (process exits). For non-main-thread crashes the process survives because
 * we deliberately do NOT call the default handler — the crashing thread simply dies.
 */
class PlayBridgeApplication : Application() {

    /** The activity currently in the foreground (null when app is backgrounded). */
    private var currentActivity: Activity? = null

    override fun onCreate() {
        super.onCreate()
        com.playbridge.shared.SharedContext.init(this)
        com.playbridge.shared.stremio.StremioClient.init(this)
        
        // Restore stream cache duration from settings
        val prefs = getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE)
        val cacheHours = prefs.getInt("stream_cache_hours", 0)
        com.playbridge.shared.stremio.StremioClient.updateCacheDuration(cacheHours)
        
        FileLogger.init(this)
        registerActivityLifecycleCallbacks(activityLifecycleCallbacks)
        installCrashHandler()
    }


    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Persist crash to file FIRST — this may be our only chance
            FileLogger.logCrash(thread, throwable)

            val isMainThread = thread == Looper.getMainLooper().thread

            if (isMainThread) {
                FileLogger.e(TAG, "Crash on main thread — delegating to system handler")
                defaultHandler?.uncaughtException(thread, throwable)
                return@setDefaultUncaughtExceptionHandler
            }

            FileLogger.w(TAG, "Crash on background thread '${thread.name}' — finishing current activity")

            val activity = currentActivity
            if (activity != null && !activity.isFinishing) {
                activity.runOnUiThread {
                    try {
                        activity.finish()
                    } catch (e: Exception) {
                        FileLogger.e(TAG, "Failed to finish activity after crash", e)
                    }
                }
            }

            // Make sure the service is still running (no-op if already running)
            try {
                ServerService.start(applicationContext)
            } catch (e: Exception) {
                FileLogger.e(TAG, "Failed to restart ServerService after crash", e)
            }
        }
    }


    private val activityLifecycleCallbacks = object : ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            currentActivity = activity
        }

        override fun onActivityPaused(activity: Activity) {
            if (currentActivity === activity) currentActivity = null
        }

        // Unused callbacks
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
