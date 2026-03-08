package com.playbridge.receiver

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.util.Log
import com.playbridge.receiver.logging.FileLogger
import com.playbridge.receiver.server.ServerService

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
        FileLogger.init(this)
        registerActivityLifecycleCallbacks(activityLifecycleCallbacks)
        installCrashHandler()
    }

    // -------------------------------------------------------------------------
    // Crash handler
    // -------------------------------------------------------------------------

    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Persist crash to file FIRST — this may be our only chance
            FileLogger.logCrash(thread, throwable)

            val isMainThread = thread == Looper.getMainLooper().thread

            if (isMainThread) {
                // Main-thread crash: we cannot safely continue — delegate to the OS.
                // The ServerService is a separate foreground service; Android will restart
                // it automatically because we use START_STICKY.
                FileLogger.e(TAG, "Crash on main thread — delegating to system handler")
                defaultHandler?.uncaughtException(thread, throwable)
                return@setDefaultUncaughtExceptionHandler
            }

            // Non-main-thread crash (e.g. ExoPlayer:Playback, GeckoView threads):
            // Finish the foreground activity so we return cleanly to the home screen,
            // but do NOT kill the process — the ServerService keeps running and the TV
            // stays ready to receive the next play/browser command.
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

    // -------------------------------------------------------------------------
    // Activity lifecycle tracking
    // -------------------------------------------------------------------------

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
