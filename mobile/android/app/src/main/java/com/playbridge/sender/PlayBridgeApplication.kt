package com.playbridge.sender

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.util.Log
import com.playbridge.sender.data.backup.BackupTrigger
import com.playbridge.sender.diagnostics.CrashLogger
import com.playbridge.sender.di.appModule
import com.playbridge.sender.util.ProcessUtil
import com.playbridge.sender.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

class PlayBridgeApplication : Application() {
    private val applicationScope = MainScope()

    override fun onCreate() {
        super.onCreate()
        val process = ProcessUtil.processName()
        // GeckoView multi-process children still run Application.onCreate. Starting full Koin
        // there creates a second CastSessionManager with hasActiveSession=false, which after
        // STOP_GRACE_MS stops the *main* process cast FGS and cancels the notification (B7).
        if (!ProcessUtil.isMainProcess(this)) {
            Log.d(TAG, "Skipping full app init in Gecko child process: $process")
            return
        }
        Log.d(TAG, "Initializing application (process=$process)")

        // Persist uncaught exceptions so phone crashes survive a restart (logcat is wiped on reboot).
        CrashLogger.install(this)

        com.playbridge.shared.SharedContext.init(this)

        // Initialize Koin DI Container
        startKoin {
            androidLogger(if (BuildConfig.DEBUG) Level.INFO else Level.NONE)
            androidContext(this@PlayBridgeApplication)
            modules(appModule)
        }

        // Start backup trigger
        BackupTrigger(this, applicationScope).start()

        // The recently used bridged app is kept live across tab switches; give it up when memory is short.
        registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (com.playbridge.sender.browser.BridgedAppMemoryPolicy.shouldRelease(level)) releaseKeptBridgedApp()
            }
            override fun onLowMemory() = releaseKeptBridgedApp()
            override fun onConfigurationChanged(newConfig: Configuration) = Unit
        })

        // Perf: Room init + stale-APK cleanup do file IO — off the main thread so
        // cold start isn't blocked on DB verification / cacheDir scans.
        applicationScope.launch(Dispatchers.IO) {
            runCatching {
                com.playbridge.sender.data.history.DatabaseProvider.getDatabase(this@PlayBridgeApplication)
            }
            runCatching {
                com.playbridge.sender.update.ApkInstaller.cleanupStaleApks(this@PlayBridgeApplication)
            }
        }
    }

    private fun releaseKeptBridgedApp() {
        val components = com.playbridge.sender.browser.Components
        if (components.isEngineInitialized()) components.tabManager.releaseKeptBridgedApp()
    }

    companion object {
        private const val TAG = "PlayBridgeApplication"
    }
}
