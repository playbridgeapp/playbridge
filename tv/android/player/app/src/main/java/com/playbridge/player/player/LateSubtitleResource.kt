package com.playbridge.player.player

import com.playbridge.shared.protocol.decodeSubtitleResourceJson
import java.net.URI
import playbridge.SubtitleResource

/** Reject malformed late sidecars before any URL or header reaches the TV network stack. */
internal fun decodeLateSubtitleResource(json: String?): SubtitleResource? {
    val resource = json?.let(::decodeSubtitleResourceJson) ?: return null
    val uri = runCatching { URI(resource.url) }.getOrNull() ?: return null
    if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null) {
        return null
    }
    if (resource.headers.size > 32 || resource.label.orEmpty().length > 256 ||
        resource.language.orEmpty().length > 64
    ) return null
    var headerBytes = 0
    for ((name, value) in resource.headers) {
        if (!name.matches(Regex("[A-Za-z0-9-]{1,64}")) ||
            value.any { it == '\r' || it == '\n' } || value.length > 4_096
        ) return null
        headerBytes += name.length + value.length
    }
    return resource.takeIf { headerBytes <= 16_384 }
}
