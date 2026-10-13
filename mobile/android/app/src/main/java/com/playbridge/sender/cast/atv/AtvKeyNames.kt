package com.playbridge.sender.cast.atv

import com.playbridge.sender.cast.remote.RemoteKey

internal const val ATV_KEY_SHORT = "short"

/** Names accepted by `key_code_from_name`. Mute and Power are volume and power commands. */
internal fun atvKeyName(key: RemoteKey): String? = when (key) {
    RemoteKey.Up -> "DPAD_UP"
    RemoteKey.Down -> "DPAD_DOWN"
    RemoteKey.Left -> "DPAD_LEFT"
    RemoteKey.Right -> "DPAD_RIGHT"
    RemoteKey.Ok -> "DPAD_CENTER"
    RemoteKey.Back -> "BACK"
    RemoteKey.Home -> "HOME"
    RemoteKey.Enter -> "KEYCODE_ENTER"
    RemoteKey.Delete -> "KEYCODE_DEL"
    RemoteKey.Menu -> "KEYCODE_MENU"
    RemoteKey.Mute, RemoteKey.Power -> null
}
