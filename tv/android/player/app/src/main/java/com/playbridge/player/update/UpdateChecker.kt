package com.playbridge.player.update

import android.content.Context
import com.playbridge.shared.update.UpdateChecker as SharedUpdateChecker
import com.playbridge.shared.update.UpdateConfigs

/**
 * TV updater. The TV app has no DI container, so use [getInstance] for a process-wide
 * singleton shared by [com.playbridge.player.MainActivity] (cold-start) and Settings.
 *
 * Endpoint, asset pattern, user agent, and Play fallback live in [UpdateConfigs.tvPlayer].
 */
class UpdateChecker private constructor(
    appContext: Context,
    installer: ApkInstaller,
) : SharedUpdateChecker(appContext, installer, UpdateConfigs.tvPlayer) {

    companion object {
        @Volatile
        private var instance: UpdateChecker? = null

        /** Process-wide singleton (the TV app has no DI container). */
        fun getInstance(context: Context): UpdateChecker =
            instance ?: synchronized(this) {
                instance ?: UpdateChecker(
                    appContext = context.applicationContext,
                    installer = ApkInstaller(context.applicationContext),
                ).also { instance = it }
            }
    }
}
