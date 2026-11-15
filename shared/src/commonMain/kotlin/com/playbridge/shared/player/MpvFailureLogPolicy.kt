package com.playbridge.shared.player

/** Classify in memory. Never retain, forward or log raw libmpv text (it may contain credentials). */
object MpvFailureLogPolicy {
    private val http = Regex("\\bHTTP error ([1-5][0-9]{2})\\b")
    private val recoverableHttp = Regex("\\bHTTP error (?:429|5[0-9]{2})\\b")
    private val truncationOrNetworkMarkers = listOf(
        "partial file",
        "stream ends prematurely",
        "file ended prematurely",
        "premature eof",
        "will reconnect at ",
        "failed to reconnect",
        "reconnect failed",
        "connection reset by peer",
        "broken pipe",
        "operation timed out",
        "connection timed out",
        "connection refused",
        "failed to resolve hostname",
        "tls handshake failed",
        "network is unreachable",
        "input/output error",
        "i/o error",
        "error=end of file",
    )

    fun classify(prefix: String, level: Int, text: String): String? {
        if (level !in 10..20 || !isCoreNetworkPrefix(prefix)) return null
        http.find(text)?.groupValues?.get(1)?.let { return "mpv_http_$it" }
        return when {
            text.startsWith("Failed to recognize file format") || text.startsWith("Unrecognized file format") -> "mpv_unsupported_format"
            listOf("Failed to resolve hostname", "Connection timed out", "TLS handshake failed", "Connection refused", "Network is unreachable").any(text::startsWith) -> "mpv_network_failed"
            prefix == "cplayer" && text.startsWith("Failed to open ") -> "mpv_load_failed"
            else -> null
        }
    }

    /**
     * Returns whether an FFmpeg/mpv log entry signals stream truncation, reconnect activity, or
     * a network/5xx error (as opposed to a clean short file ending without errors).
     */
    fun isTruncationOrNetworkLog(prefix: String, level: Int, text: String): Boolean {
        if (level !in 10..30 || !isCoreNetworkPrefix(prefix)) return false
        if (recoverableHttp.containsMatchIn(text)) return true
        val lower = text.lowercase()
        if (truncationOrNetworkMarkers.any(lower::contains)) return true
        return level <= 20 && lower.contains("end of file")
    }

    fun isTerminal(prefix: String, text: String): Boolean = prefix == "cplayer" &&
        (text.startsWith("Failed to recognize file format") || text.startsWith("Failed to open "))

    private fun isCoreNetworkPrefix(prefix: String): Boolean =
        prefix == "cplayer" || prefix == "ffmpeg" || prefix.startsWith("ffmpeg/")
}
