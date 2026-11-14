package com.playbridge.player.server

import android.content.Context
import android.provider.Settings

/**
 * The name this TV advertises to phones (the mDNS service name): Android's device name, else the
 * model. Home and Settings show this same name; the stored PairingStore name is not what is sent.
 */
fun advertisedDeviceName(context: Context): String =
    Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) ?: android.os.Build.MODEL
