package com.playbridge.sender

import android.app.Application
import android.util.Log
import com.playbridge.sender.data.backup.BackupTrigger
import kotlinx.coroutines.MainScope

class PlayBridgeApplication : Application() {
    private val applicationScope = MainScope()

    override fun onCreate() {
        super.onCreate()
        Log.d("PlayBridgeApplication", "Initializing application")

        com.playbridge.shared.SharedContext.init(this)
        com.playbridge.shared.stremio.StremioClient.init(this)

        // Start backup trigger
        BackupTrigger(this, applicationScope).start()
    }
}
