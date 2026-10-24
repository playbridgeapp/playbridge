package com.playbridge.sender.cast

import com.playbridge.sender.model.CastProtocol
import com.playbridge.sender.model.TvDevice
import java.net.URI

/** The existing transports accept a Roku host or a DLNA description URL directly. */
internal fun manualPlayOnDevice(protocol: CastProtocol, input: String, port: Int? = null): TvDevice? {
    val address = input.trim()
    if (address.isEmpty() || address.any(Char::isWhitespace)) return null
    if (protocol == CastProtocol.DLNA) {
        val url = runCatching { URI(address) }.getOrNull() ?: return null
        val host = url.host?.takeIf(String::isNotBlank) ?: return null
        if (url.scheme !in listOf("http", "https") || url.rawUserInfo != null || url.fragment != null) return null
        if (url.port != -1 && url.port !in 1..65535) return null
        return TvDevice(host, if (url.port != -1) url.port else if (url.scheme == "https") 443 else 80,
            token = "", name = "DLNA device", protocol = protocol, descriptionUrl = address)
    }
    if (protocol != CastProtocol.PLAYBRIDGE && protocol != CastProtocol.ROKU) return null
    val host = address.removePrefix("[").removeSuffix("]")
    // Validate a host literal without performing DNS / network work on the UI thread.
    val authority = if (':' in host) "[$host]" else host
    val parsed = runCatching { URI("http://$authority") }.getOrNull() ?: return null
    if (parsed.host.isNullOrBlank() || parsed.rawUserInfo != null || parsed.rawPath.isNotEmpty() ||
        parsed.rawQuery != null || parsed.rawFragment != null || parsed.port != -1) return null
    val targetPort = if (protocol == CastProtocol.ROKU) 8060 else port?.takeIf { it in 1..65535 } ?: return null
    return TvDevice(host, targetPort, token = "", name = if (protocol == CastProtocol.ROKU) "Roku" else "Manual TV", protocol = protocol)
}
