package com.playbridge.sender.notifications

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal enum class NotificationPermissionAction { REQUEST_PERMISSION, OPEN_SETTINGS }

/** No Android dependencies: a dismissed dialog is not a permanent denial. */
internal fun notificationPermissionAction(
    apiLevel: Int,
    permissionGranted: Boolean,
    hasPermissionDecision: Boolean,
    shouldShowRationale: Boolean,
): NotificationPermissionAction =
    if (apiLevel >= 33 && !permissionGranted && (!hasPermissionDecision || shouldShowRationale)) {
        NotificationPermissionAction.REQUEST_PERMISSION
    } else {
        NotificationPermissionAction.OPEN_SETTINGS
    }

internal fun shouldPromptForNotifications(
    notificationsEnabled: Boolean,
    notificationNeeded: Boolean,
    activityResumed: Boolean,
    promptedThisProcess: Boolean,
    permissionRequestInFlight: Boolean,
): Boolean = !notificationsEnabled && notificationNeeded && activityResumed &&
    !promptedThisProcess && !permissionRequestInFlight

/** Service/engine events carry no Activity reference and never open UI themselves. */
internal object NotificationPermissionRecovery {
    private val opportunities = MutableStateFlow(0L)
    val notificationOpportunities = opportunities.asStateFlow()

    var promptedThisProcess = false
        private set

    fun onNotificationNeeded() { opportunities.update { it + 1 } }

    @Synchronized fun claimPrompt(): Boolean {
        if (promptedThisProcess) return false
        promptedThisProcess = true
        return true
    }
}
