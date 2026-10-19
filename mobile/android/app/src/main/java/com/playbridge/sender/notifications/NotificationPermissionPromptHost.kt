package com.playbridge.sender.notifications

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Owned by the Activity; background events are deferred until this host is resumed. */
internal class NotificationPermissionPromptHost(private val activity: ComponentActivity) {
    val snackbar = SnackbarHostState()
    private val prefs = activity.getSharedPreferences("notification_permission", Context.MODE_PRIVATE)
    private val requestInFlight = MutableStateFlow(false)

    fun onCreate(savedInstanceState: Bundle?) {
        requestInFlight.value = savedInstanceState?.getBoolean(REQUEST_IN_FLIGHT) ?: false
        // Also recognize a grant/denial made before this recovery UI was introduced.
        if (Build.VERSION.SDK_INT >= 33 && (permissionGranted() ||
                activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))
        ) {
            prefs.edit {
                putBoolean("requested", true)
                putBoolean("has_decision", true)
            }
        }
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                combine(NotificationPermissionRecovery.notificationOpportunities, requestInFlight) { event, requesting ->
                    event to requesting
                }.collect { (event, requesting) ->
                    if (!shouldPromptForNotifications(
                            notificationsEnabled = NotificationManagerCompat.from(activity).areNotificationsEnabled(),
                            notificationNeeded = event > 0,
                            activityResumed = isResumed(),
                            promptedThisProcess = NotificationPermissionRecovery.promptedThisProcess,
                            permissionRequestInFlight = requesting,
                        ) || !NotificationPermissionRecovery.claimPrompt()) return@collect

                    val action = recoveryAction()
                    val result = snackbar.showSnackbar(
                        message = "Notifications are off — playback controls and the cast notification won't show",
                        actionLabel = if (action == NotificationPermissionAction.REQUEST_PERMISSION) "Enable" else "Settings",
                        withDismissAction = true,
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed && isResumed() &&
                        !NotificationManagerCompat.from(activity).areNotificationsEnabled()
                    ) {
                        // The system setting may have changed while the snackbar was visible.
                        when (recoveryAction()) {
                            NotificationPermissionAction.REQUEST_PERMISSION -> requestPermission()
                            NotificationPermissionAction.OPEN_SETTINGS -> openSettings()
                        }
                    }
                }
            }
        }

        // Retain the first-run system dialog; subsequent requests require the snackbar action.
        if (Build.VERSION.SDK_INT >= 33 && !permissionGranted() &&
            !prefs.getBoolean("requested", false) && !requestInFlight.value
        ) requestPermission()
    }

    fun saveState(outState: Bundle) { outState.putBoolean(REQUEST_IN_FLIGHT, requestInFlight.value) }

    fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != REQUEST_CODE || Build.VERSION.SDK_INT < 33) return
        val index = permissions.indexOf(Manifest.permission.POST_NOTIFICATIONS)
        val result = grantResults.getOrNull(index)
        prefs.edit {
            putString("last_outcome", when (result) {
                PackageManager.PERMISSION_GRANTED -> "granted"
                PackageManager.PERMISSION_DENIED -> "denied"
                else -> "dismissed"
            })
            // Empty results leave the previous decision intact: swiping away is not denial.
            if (result != null) putBoolean("has_decision", true)
        }
        requestInFlight.value = false
    }

    private fun permissionGranted(): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun recoveryAction() = notificationPermissionAction(
        apiLevel = Build.VERSION.SDK_INT,
        permissionGranted = permissionGranted(),
        hasPermissionDecision = prefs.getBoolean("has_decision", false),
        shouldShowRationale = Build.VERSION.SDK_INT >= 33 &&
            activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS),
    )

    private fun isResumed() = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
        !activity.isFinishing && !activity.isDestroyed

    private fun requestPermission() {
        if (Build.VERSION.SDK_INT < 33 || requestInFlight.value || permissionGranted()) return
        requestInFlight.value = true
        prefs.edit { putBoolean("requested", true) }
        try {
            activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_CODE)
        } catch (_: RuntimeException) {
            requestInFlight.value = false
            if (isResumed()) openSettings()
        }
    }

    private fun openSettings() {
        if (!isResumed()) return
        val notifications = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${activity.packageName}".toUri())
        try {
            activity.startActivity(notifications)
        } catch (_: RuntimeException) {
            try {
                activity.startActivity(details)
            } catch (_: RuntimeException) {
                Toast.makeText(activity, "Notification settings are unavailable on this device", Toast.LENGTH_LONG).show()
            }
        }
    }

    private companion object {
        const val REQUEST_CODE = 1001
        const val REQUEST_IN_FLIGHT = "notification_permission_request_in_flight"
    }
}
