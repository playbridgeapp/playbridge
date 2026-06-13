package com.playbridge.sender.ui

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * Detects the "wrong APK on the wrong device" case: this is the phone sender app,
 * but it's running on an Android TV / Fire TV device.
 *
 * The sender has no leanback launcher entry, so on a TV it can only be reached via
 * sideload launchers — users who get there are almost certainly looking for the TV
 * Player receiver instead (issue #16). This guard shows a one-time dismissible
 * dialog pointing them at it.
 */
object TvDeviceGuard {
    private const val PREFS = "device_guard"
    private const val KEY_DISMISSED = "wrong_device_dismissed"

    fun isRunningOnTv(context: Context): Boolean {
        val pm = context.packageManager
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        return uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    fun shouldWarn(context: Context): Boolean =
        isRunningOnTv(context) &&
            !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_DISMISSED, false)

    fun dismiss(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DISMISSED, true).apply()
    }
}

@Composable
fun WrongDeviceDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("This is the phone app") },
        text = {
            Text(
                "It looks like you're running the PlayBridge Phone app on a TV. " +
                    "This app is the sender — it belongs on your phone.\n\n" +
                    "On this TV, install PlayBridge TV Player instead: open the " +
                    "Downloader app and enter code 9557748, or sideload " +
                    "playbridge-tv-player-*.apk from the GitHub Releases page."
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Continue anyway")
            }
        }
    )
}
