package com.playbridge.shared.player

/** Sidecar subtitle formats the TV renderer and the phone player load from a URL. */
enum class ExternalSubtitleFormat(val fileExtension: String) {
    SUBRIP("srt"),
    WEBVTT("vtt"),
    ASS("ass"),
    SSA("ssa"),
    TTML("ttml"),
}

/** Subtitle text from its bytes: honours UTF-8/UTF-16 byte order marks, falling back to Windows-1252 for invalid UTF-8. */
fun decodeSubtitleText(bytes: ByteArray): String {
    if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
        return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
    }
    if (bytes.size >= 2) {
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
    }
    return try {
        val utf8 = String(bytes, Charsets.UTF_8)
        if (utf8.contains("�")) throw Exception("Invalid UTF-8")
        utf8
    } catch (e: Exception) {
        String(bytes, java.nio.charset.Charset.forName("Windows-1252"))
    }
}

/** A downloaded subtitle's format, from its URL's extension, then its Content-Type, then its first lines. */
fun detectSubtitleFormat(
    url: String,
    contentType: String?,
    bytes: ByteArray,
): ExternalSubtitleFormat {
    val extension = url.substringBefore('#')
        .substringBefore('?')
        .substringAfterLast('/', missingDelimiterValue = "")
        .substringAfterLast('.', missingDelimiterValue = "")
        .lowercase()
    when (extension) {
        "vtt" -> return ExternalSubtitleFormat.WEBVTT
        "ass" -> return ExternalSubtitleFormat.ASS
        "ssa" -> return ExternalSubtitleFormat.SSA
        "ttml", "dfxp", "xml" -> return ExternalSubtitleFormat.TTML
        "srt" -> return ExternalSubtitleFormat.SUBRIP
    }

    val normalizedContentType = contentType.orEmpty().substringBefore(';').trim().lowercase()
    when (normalizedContentType) {
        "text/vtt" -> return ExternalSubtitleFormat.WEBVTT
        "text/x-ass", "application/x-ass" -> return ExternalSubtitleFormat.ASS
        "text/x-ssa", "application/x-ssa" -> return ExternalSubtitleFormat.SSA
        "application/ttml+xml" -> return ExternalSubtitleFormat.TTML
        "application/x-subrip", "application/srt", "text/srt" ->
            return ExternalSubtitleFormat.SUBRIP
    }

    val prefix = decodeSubtitleText(bytes.copyOfRange(0, minOf(bytes.size, 8_192))).trimStart()
    return when {
        prefix.startsWith("WEBVTT", ignoreCase = true) -> ExternalSubtitleFormat.WEBVTT
        prefix.startsWith("[Script Info]", ignoreCase = true) ||
            prefix.contains("[V4+ Styles]", ignoreCase = true) -> ExternalSubtitleFormat.ASS
        prefix.startsWith("<tt", ignoreCase = true) ||
            prefix.contains("<tt ", ignoreCase = true) -> ExternalSubtitleFormat.TTML
        else -> ExternalSubtitleFormat.SUBRIP
    }
}
