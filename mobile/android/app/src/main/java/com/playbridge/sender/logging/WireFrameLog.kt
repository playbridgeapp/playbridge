package com.playbridge.sender.logging

/**
 * Both release and debug logs retain only a known envelope type and UTF-8 size.
 * Encoded keyboard text, pins, protected credentials and open descriptors must
 * never reach logcat; regex credential sanitization cannot identify all of them.
 */
internal object WireFrameLog {
    private val typeField = Regex(""""type"\s*:\s*"([^"\\]+)"""")
    private val knownTypes = setOf(
        "auth", "auth_response", "pairing_commit", "pairing_challenge", "pairing_reveal", "pairing_confirmation",
        "pairing_approved", "pairing_denied", "command", "command_result", "status", "context", "context_query",
        "tracks", "playlist_status", "player_settings", "volume_state", "ping", "pong", "screen_mirror_status",
        "screen_mirror_answer", "screen_mirror_candidate", "user_scripts", "user_scripts_query", "user_script",
        "user_agent", "user_agent_query", "tv_logs", "tv_logs_query",
    )

    fun summary(frame: String): String {
        val type = typeField.find(frame)?.groupValues?.getOrNull(1)?.takeIf { it in knownTypes } ?: "unknown"
        val bytes = frame.toByteArray(Charsets.UTF_8).size
        return "type=$type bytes=$bytes"
    }

    fun redact(frame: String): String = summary(frame)
}
