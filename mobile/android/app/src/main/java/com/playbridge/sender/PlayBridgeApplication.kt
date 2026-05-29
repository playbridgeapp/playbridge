package com.playbridge.sender

import android.app.Application
import android.util.Log
import com.playbridge.sender.data.backup.BackupTrigger
import com.playbridge.sender.di.appModule
import kotlinx.coroutines.MainScope
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

class PlayBridgeApplication : Application() {
    private val applicationScope = MainScope()

    override fun onCreate() {
        super.onCreate()
        Log.d("PlayBridgeApplication", "Initializing application")

        com.playbridge.shared.SharedContext.init(this)

        // Initialize Koin DI Container
        startKoin {
            androidLogger(Level.INFO)
            androidContext(this@PlayBridgeApplication)
            modules(appModule)
        }

        // Start backup trigger
        BackupTrigger(this, applicationScope).start()
    }
}
