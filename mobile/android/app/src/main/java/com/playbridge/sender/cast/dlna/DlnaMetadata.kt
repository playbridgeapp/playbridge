package com.playbridge.sender.cast.dlna

/**
 * DLNA.ORG_OP describes byte-range support; conversion is never performed by this proxy.
 * DLNA.ORG_FLAGS is the streaming/background/connection-stall/v1.5 value that UMS (incl. its LG
 * webOS profiles), minidlna and Kodi send; some renderers refuse resources without it.
 */
internal fun dlnaContentFeatures(byteSeek: Boolean): String =
    "DLNA.ORG_OP=${if (byteSeek) "01" else "00"};DLNA.ORG_CI=0;DLNA.ORG_FLAGS=$DLNA_ORG_FLAGS"

private const val DLNA_ORG_FLAGS = "01700000000000000000000000000000"

internal fun isDlnaStreamingMime(mimeType: String): Boolean {
    val category = mimeType.substringBefore(';').trim().substringBefore('/').lowercase()
    return category == "audio" || category == "video"
}

/** Build DLNA response headers; Accept-Ranges is emitted separately by the response writer. */
internal fun dlnaResponseHeaders(
    mimeType: String,
    requestContentFeatures: Boolean,
    byteSeek: Boolean,
): List<Pair<String, String>> = buildList {
    add("transferMode.dlna.org" to if (isDlnaStreamingMime(mimeType)) "Streaming" else "Interactive")
    if (isDlnaStreamingMime(mimeType)) add("realTimeInfo.dlna.org" to "DLNA.ORG_TLAG=*")
    if (requestContentFeatures) add("contentFeatures.dlna.org" to dlnaContentFeatures(byteSeek))
}

internal fun requestsDlnaContentFeatures(headers: Iterable<Pair<String, String>>): Boolean =
    headers.any { (name, value) ->
        name.equals("getcontentFeatures.dlna.org", ignoreCase = true) && value.trim() == "1"
    }

/** Only audio, video, and image have useful ConnectionManager Sink compatibility. */
internal fun shouldPreflightDlnaMime(mimeType: String): Boolean =
    mimeType.substringBefore(';').substringBefore('/').trim().lowercase() in setOf("audio", "video", "image")

/** Null means permissive: the sink value was empty or had no parsable protocol entries. */
internal fun protocolInfoAllows(sink: String?, mimeType: String): Boolean? {
    val entries = splitProtocolInfo(sink.orEmpty()).mapNotNull { value ->
        val fields = value.trim().split(':', limit = 4)
        if (fields.size == 4 && fields.all(String::isNotBlank)) fields else null
    }
    if (entries.isEmpty()) return null
    return entries.any { fields ->
        (fields[0].equals("http-get", ignoreCase = true) || fields[0] == "*") &&
            mimeCategoryMatches(fields[2], mimeType)
    }
}

private fun mimeCategoryMatches(advertised: String, requested: String): Boolean {
    fun category(value: String): String = value.substringBefore(';').trim().substringBefore('/').lowercase()
    val a = category(advertised)
    val r = category(requested)
    return a == "*" || r == "*" || (a.isNotEmpty() && a == r)
}

private fun splitProtocolInfo(value: String): List<String> {
    val parts = mutableListOf<String>()
    val part = StringBuilder()
    var escaped = false
    for (char in value) {
        when {
            escaped -> { part.append(char); escaped = false }
            char == '\\' -> escaped = true
            char == ',' -> { parts += part.toString(); part.setLength(0) }
            else -> part.append(char)
        }
    }
    if (escaped) part.append('\\')
    parts += part.toString()
    return parts
}

internal fun dlnaMpostHeaders(soapAction: String): Map<String, String> = mapOf(
    "MAN" to "\"http://schemas.xmlsoap.org/soap/envelope/\"; ns=01",
    "01-SOAPACTION" to soapAction,
)

internal fun shouldRetrySetAvTransportAfterStop(
    upnpCode: String?,
    description: String?,
): Boolean = upnpCode == "701" || description?.contains("transition not available", ignoreCase = true) == true

internal fun shouldRetryWithoutMetadata(
    actionName: String,
    upnpCode: String?,
    isHls: Boolean,
    isMirror: Boolean,
): Boolean = actionName == "SetAVTransportURI" && !isHls && !isMirror && upnpCode in setOf("714", "716", "501")

internal fun buildDlnaDidl(
    url: String,
    title: String,
    mimeType: String,
    durationMs: Long = 0L,
    byteSeek: Boolean = true,
): String {
    fun xml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
    val category = mimeType.substringBefore(';').substringBefore('/').trim().lowercase()
    val upnpClass = when (category) {
        "audio" -> "object.item.audioItem.musicTrack"
        "image" -> "object.item.imageItem.photo"
        else -> "object.item.videoItem"
    }
    val duration = durationMs.takeIf { it > 0L }?.let { " duration=\"${DlnaCastTarget.formatTime(it)}\"" }.orEmpty()
    val protocolInfo = "http-get:*:${xml(mimeType)}:${dlnaContentFeatures(byteSeek)}"
    return """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="playbridge-media" parentID="0" restricted="1"><dc:title>${xml(title)}</dc:title><upnp:class>$upnpClass</upnp:class><res protocolInfo="$protocolInfo"$duration>${xml(url)}</res></item></DIDL-Lite>"""
}
