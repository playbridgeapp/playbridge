package com.playbridge.browser

import android.app.Application
import com.playbridge.browser.logging.FileLogger

class PlayBridgeBrowserApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FileLogger.init(this)
    }
}
