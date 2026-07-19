package com.playbridge.player

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.util.Log
import com.playbridge.player.logging.FileLogger
import com.playbridge.player.browser.WebProcess
import com.playbridge.player.player.ExoProcess
import com.playbridge.player.player.MpvProcess
import com.playbridge.player.player.RendererProcessPrewarmer
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
        val isMpvProcess = MpvProcess.isCurrent(this)
        val isExoProcess = ExoProcess.isCurrent(this)
        val isWebProcess = WebProcess.isCurrent(this)
        if (isWebProcess && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            // WebView storage must have a process-unique suffix before any WebView is created.
            android.webkit.WebView.setDataDirectorySuffix("web")
        }
        com.playbridge.shared.SharedContext.init(this)

        val processLog = when {
            isMpvProcess -> "playbridge-mpv.log"
            isExoProcess -> "playbridge-exo.log"
            isWebProcess -> "playbridge-web.log"
            else -> "playbridge.log"
        }
        FileLogger.init(this, processLog)
        registerActivityLifecycleCallbacks(activityLifecycleCallbacks)

        if (isMpvProcess) {
            installMpvProcessCrashHandler()
            return
        }

        if (isExoProcess) {
            installRendererProcessCrashHandler("Exo")
            return
        }

        if (isWebProcess) {
            installRendererProcessCrashHandler("WebView")
            return
        }

        installCrashHandler()

        // Warm both isolated Binder endpoints without allocating either player/decoder. This
        // makes first playback and MPV<->Exo failover avoid cold process startup.
        RendererProcessPrewarmer.start(this)

        // Preload AdBlocker in background so filters are ready
        com.playbridge.player.browser.AdBlocker.preload(this)

        // Remove any leftover self-update APK from a previous session (cacheDir/updates).
        com.playbridge.player.update.ApkInstaller.cleanupStaleApks(this)
    }

    private fun installMpvProcessCrashHandler() {
        installRendererProcessCrashHandler("MPV")
    }

    private fun installRendererProcessCrashHandler(rendererName: String) {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            FileLogger.logCrash(thread, throwable)
            Log.e(TAG, "Crash in private $rendererName process on thread ${thread.name}", throwable)
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, throwable)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }


    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Persist crash to file FIRST — this may be our only chance
            FileLogger.logCrash(thread, throwable)
            Log.e(TAG, "Background thread crash on thread ${thread.name}", throwable)

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

            // Renderer processes must not start or own ServerService. The main process
            // remains responsible for the server and will observe renderer death through
            // the next command/session request.
            if (!MpvProcess.isCurrent(this) &&
                !ExoProcess.isCurrent(this) &&
                !WebProcess.isCurrent(this)
            ) {
                try {
                    ServerService.start(applicationContext)
                } catch (e: Exception) {
                    FileLogger.e(TAG, "Failed to restart ServerService after crash", e)
                }
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
