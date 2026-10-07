package com.playbridge.sender.update

import android.content.Context
import com.playbridge.shared.update.SideloadApkInstaller
import com.playbridge.shared.update.UpdateConfigs
import com.playbridge.shared.update.UpdateInstaller

/**
 * FOSS phone installer. Download and system-installer handoff live in [SideloadApkInstaller];
 * this wrapper only pins the phone updater user agent.
 */
class ApkInstaller(appContext: Context) : UpdateInstaller by SideloadApkInstaller(
    appContext,
    UpdateConfigs.phone.userAgent,
) {
    companion object {
        fun cleanupStaleApks(context: Context, maxAgeMillis: Long = 60 * 60 * 1000L) {
            SideloadApkInstaller.cleanupStaleApks(context, maxAgeMillis)
        }
    }
}
