package com.playbridge.sender.update

import android.content.Context
import com.playbridge.shared.update.UpdateChecker as SharedUpdateChecker
import com.playbridge.shared.update.UpdateConfigs

/**
 * Phone updater. Registered as a Koin singleton so cold-start and Settings share one [state].
 *
 * Endpoint, asset pattern, user agent, and Play fallback live in [UpdateConfigs.phone].
 */
class UpdateChecker(
    appContext: Context,
    installer: ApkInstaller,
) : SharedUpdateChecker(appContext, installer, UpdateConfigs.phone)
