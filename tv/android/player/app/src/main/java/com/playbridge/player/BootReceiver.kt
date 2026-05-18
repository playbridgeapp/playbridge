package com.playbridge.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.playbridge.player.server.ServerService

/**
 * Starts the PlayBridge server service automatically after device boot.
 *
 * The service runs in the background (foreground service with notification) so the TV is
 * immediately ready to receive commands from the phone without the user having to manually
 * open the app after a reboot.
 *
 * Requires: RECEIVE_BOOT_COMPLETED permission (already declared in manifest).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            Log.i("BootReceiver", "Boot completed — starting PlayBridge server service")
            ServerService.start(context)
        }
    }
}
