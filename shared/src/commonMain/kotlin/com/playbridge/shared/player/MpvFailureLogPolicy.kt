package com.playbridge.shared.player

/** Classify in memory. Never retain, forward or log raw libmpv text (it may contain credentials). */
object MpvFailureLogPolicy {
    private val http = Regex("\\bHTTP error ([1-5][0-9]{2})\\b")
    fun classify(prefix: String, level: Int, text: String): String? {
        if (level !in 10..20 || prefix != "cplayer" && prefix != "ffmpeg" && !prefix.startsWith("ffmpeg/")) return null
        http.find(text)?.groupValues?.get(1)?.let { return "mpv_http_$it" }
        return when {
            text.startsWith("Failed to recognize file format") || text.startsWith("Unrecognized file format") -> "mpv_unsupported_format"
            listOf("Failed to resolve hostname", "Connection timed out", "TLS handshake failed", "Connection refused", "Network is unreachable").any(text::startsWith) -> "mpv_network_failed"
            prefix == "cplayer" && text.startsWith("Failed to open ") -> "mpv_load_failed"
            else -> null
        }
    }

    fun isTerminal(prefix: String, text: String): Boolean = prefix == "cplayer" &&
        (text.startsWith("Failed to recognize file format") || text.startsWith("Failed to open "))
}
