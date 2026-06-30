package com.playbridge.player.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * Explains why the TV receiver needs the "Display over other apps"
 * (SYSTEM_ALERT_WINDOW) permission before dropping the user into the system
 * settings screen.
 *
 * The permission lets [OverlayWindowHelper] keep a tiny invisible overlay window
 * alive while a phone is connected, which is what exempts the background
 * [ServerService] from Android 14+ Background-Activity-Launch restrictions so it
 * can pop the player/browser onto the screen when a cast arrives. Sending the
 * user straight to a bare "allow display over other apps" toggle with no context
 * is confusing — this rationale tells them what it's for first.
 */
object OverlayPermissionGuard {

    private const val TAG = "OverlayPermissionGuard"

    /**
     * True when we should prompt. Android 10+ (Q) enforces Background-Activity-Launch
     * restrictions, and the overlay-window's "non-app visible window" exemption is what
     * lets the backgrounded [ServerService] pop the player/browser onto the screen when a
     * cast arrives. Below Q background launches work without it, so we stay quiet there.
     * Only prompt when the permission isn't already granted.
     */
    fun isNeeded(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            !Settings.canDrawOverlays(context)

    /** Opens the system "Display over other apps" settings screen for this app. */
    fun openSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { Log.e(TAG, "Failed to launch overlay settings activity", it) }
    }
}

@Composable
fun OverlayPermissionDialog(onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Let PlayBridge open over other apps") },
        text = {
            Text(
                "When your phone casts a video or opens the browser, PlayBridge needs to " +
                    "bring the player to the screen on its own — even while it's running in " +
                    "the background.\n\n" +
                    "Android requires the \"Display over other apps\" permission for this. " +
                    "Without it, casting still works but you may have to open PlayBridge on " +
                    "the TV by hand each time.\n\n" +
                    "We'll take you to the settings screen to turn it on."
            )
        },
        confirmButton = {
            TextButton(onClick = onOpenSettings) {
                Text("Open settings")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Not now")
            }
        }
    )
}
