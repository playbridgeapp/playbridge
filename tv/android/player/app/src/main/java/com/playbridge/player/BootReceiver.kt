package com.playbridge.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.playbridge.player.server.ServerService

/**
 * Starts the PlayBridge server service automatically after device boot.
 *
 * The service runs as a connected-device foreground service so the TV is immediately ready to
 * receive commands from the phone without the user having to manually open the app after a
 * reboot. Keeping the boot-started service limited to the connected-device type is required on
 * Android 15+, where BOOT_COMPLETED receivers cannot launch media-playback foreground services.
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
