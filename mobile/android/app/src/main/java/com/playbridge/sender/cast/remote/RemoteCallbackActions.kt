package com.playbridge.sender.cast.remote

import java.util.Base64

/** Parses only the compatibility screen's callbacks, never builds outgoing protocol strings. */
internal object RemoteCallbackActions {
    fun key(key: String): RemoteAction = when {
        key == "volume_up" -> RemoteAction.AdjustVolume(1)
        key == "volume_down" -> RemoteAction.AdjustVolume(-1)
        key.startsWith("text:") -> runCatching { RemoteAction.ReplaceText(String(Base64.getDecoder().decode(key.removePrefix("text:")), Charsets.UTF_8)) }
            .getOrElse { RemoteAction.Unavailable("Invalid replacement text") }
        else -> RemoteKey.entries.firstOrNull { it.wireValue == key }?.let { RemoteAction.Key(it) } ?: RemoteAction.Unavailable("Unsupported remote key")
    }
    fun player(command: String): RemoteAction = when (command) {
        "play" -> RemoteAction.SetPlaying(true); "pause" -> RemoteAction.SetPlaying(false)
        "stop" -> RemoteAction.StopPlayback; "seek_back" -> RemoteAction.SeekBy(-10_000L); "seek_forward" -> RemoteAction.SeekBy(10_000L)
        "loop_on" -> RemoteAction.SetLoop(true); "loop_off" -> RemoteAction.SetLoop(false)
        else -> RemoteAction.Unavailable("Unsupported player command")
    }
    fun browser(command: String): RemoteAction = when {
        command == "toggle_play" -> RemoteAction.TogglePlayPause
        command.startsWith("seek_to:") -> command.removePrefix("seek_to:").toLongOrNull()?.let { RemoteAction.SeekTo(it) } ?: RemoteAction.Unavailable("Invalid seek position")
        command == "maximize_video" -> RemoteAction.SetBrowserFullscreen(true)
        command == "restore_video" -> RemoteAction.SetBrowserFullscreen(false)
        command == "manage_user_scripts" -> RemoteAction.ManageUserScripts
        command == "manage_user_agent" -> RemoteAction.ManageUserAgent
        else -> BrowserAction.entries.firstOrNull { it.wireValue == command }?.let { RemoteAction.Browser(it) } ?: RemoteAction.Unavailable("Unsupported browser command")
    }
}
