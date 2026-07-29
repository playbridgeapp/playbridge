package com.playbridge.sender.cast.browser

import com.playbridge.sender.model.CastProtocol
import com.playbridge.sender.model.TvDevice

/** Snapshot of the phone-hosted browser receiver while the host is running. */
data class BrowserHostState(
    val running: Boolean = false,
    val urls: List<String> = emptyList(),
    val port: Int = 0,
    val pending: List<BrowserPairingRequest> = emptyList(),
    val ready: List<BrowserReadySession> = emptyList(),
    val lastError: String? = null,
    val busy: Boolean = false,
) {
    val primaryUrl: String?
        get() = urls.firstOrNull { url ->
            val host = runCatching { java.net.URI(url).host }.getOrNull().orEmpty()
            host.isNotEmpty() && host != "127.0.0.1" && host != "localhost" && host != "::1"
        } ?: urls.firstOrNull()

    val otherUrls: List<String>
        get() {
            val primary = primaryUrl
            return if (primary == null) urls else urls.filter { it != primary }
        }
}

data class BrowserPairingRequest(
    val sessionId: String,
    val receiverId: String,
    val name: String,
    /** Wall-clock deadline (System.currentTimeMillis()) when the PIN expires. */
    val expiresAtMs: Long,
) {
    val remainingMs: Long
        get() = (expiresAtMs - System.currentTimeMillis()).coerceAtLeast(0L)
}

data class BrowserReadySession(
    val sessionId: String,
    val receiverId: String,
    val name: String,
) {
    fun toTvDevice(hostIp: String, port: Int): TvDevice = TvDevice(
        ip = hostIp.ifBlank { "127.0.0.1" },
        port = port,
        token = "",
        name = name,
        uuid = sessionId,
        protocol = CastProtocol.WEB_BROWSER,
        addresses = listOfNotNull(hostIp.takeIf { it.isNotBlank() }),
    )
}
