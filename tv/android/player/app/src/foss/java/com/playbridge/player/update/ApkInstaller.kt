package com.playbridge.player.update

import android.content.Context
import com.playbridge.shared.update.SideloadApkInstaller
import com.playbridge.shared.update.UpdateConfigs
import com.playbridge.shared.update.UpdateInstaller

/**
 * FOSS TV installer. Download and system-installer handoff live in [SideloadApkInstaller];
 * this wrapper only pins the TV updater user agent.
 */
class ApkInstaller(appContext: Context) : UpdateInstaller by SideloadApkInstaller(
    appContext,
    UpdateConfigs.tvPlayer.userAgent,
) {
    companion object {
        fun cleanupStaleApks(context: Context, maxAgeMillis: Long = 60 * 60 * 1000L) {
            SideloadApkInstaller.cleanupStaleApks(context, maxAgeMillis)
        }
    }
}
