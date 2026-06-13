package com.playbridge.player.ui.components

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Detects the "wrong APK on the wrong device" case: this is the TV receiver app,
 * but it's running on a phone/tablet (no leanback, touch-first hardware).
 *
 * Users who sideload the TV Player APK on their phone see a focus-driven UI that
 * doesn't respond to touch and report the app as "frozen" (issue #16). This guard
 * shows a one-time dismissible dialog pointing them at the Phone app instead.
 */
object DeviceGuard {
    private const val PREFS = "device_guard"
    private const val KEY_DISMISSED = "wrong_device_dismissed"
    private const val RELEASES_URL = "https://github.com/playbridgeapp/playbridge/releases"

    fun isLikelyPhoneOrTablet(context: Context): Boolean {
        val pm = context.packageManager
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        val isTv = uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        return !isTv && pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }

    fun shouldWarn(context: Context): Boolean =
        isLikelyPhoneOrTablet(context) &&
            !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_DISMISSED, false)

    fun dismiss(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DISMISSED, true).apply()
    }

    fun openPhoneAppDownload(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

@Composable
fun WrongDeviceDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("This is the TV app") },
        text = {
            Text(
                "It looks like you're running PlayBridge TV Player on a phone or tablet. " +
                    "This app is the receiver — it's designed for Android TV / Fire TV and " +
                    "is controlled with a remote, so it won't respond well to touch.\n\n" +
                    "On this device you want the PlayBridge Phone app " +
                    "(playbridge-phone-*.apk) from the GitHub Releases page."
            )
        },
        confirmButton = {
            TextButton(onClick = { DeviceGuard.openPhoneAppDownload(context) }) {
                Text("Get the Phone app")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Continue anyway")
            }
        }
    )
}
