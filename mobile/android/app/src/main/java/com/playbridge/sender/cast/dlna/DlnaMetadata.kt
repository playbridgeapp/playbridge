package com.playbridge.sender.cast.dlna

/** DLNA.ORG_OP describes byte-range support; conversion is never performed by this proxy. */
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
