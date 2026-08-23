package com.playbridge.shared.protocol

import playbridge.PlayPayload

enum class MediaKind(val wireValue: String) {
    VIDEO("video"),
    AUDIO("audio"),
    IMAGE("image");

    companion object {
        fun fromWire(value: String?): MediaKind? = when (value?.trim()?.lowercase()) {
            VIDEO.wireValue -> VIDEO
            AUDIO.wireValue -> AUDIO
            IMAGE.wireValue -> IMAGE
            else -> null
        }
    }
}

private val audioExtensions = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac", "weba")
private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "avif", "gif", "bmp", "heic", "heif")
private val videoExtensions = setOf("mp4", "m4v", "mkv", "webm", "avi", "mov", "wmv", "flv", "ts", "m2ts", "mpg", "mpeg")

/** Resolve one playlist item independently. Explicit sender intent wins over weak MIME/URL hints. */
fun resolveMediaKind(payload: PlayPayload): MediaKind =
    resolveMediaKind(payload.media_kind, payload.url, payload.content_type)

fun resolveMediaKind(explicitKind: String?, url: String, contentType: String?): MediaKind {
    MediaKind.fromWire(explicitKind)?.let { return it }

    val mime = contentType.orEmpty().substringBefore(';').trim().lowercase()
    when {
        mime.startsWith("audio/") -> return MediaKind.AUDIO
        mime.startsWith("image/") -> return MediaKind.IMAGE
        mime.startsWith("video/") -> return MediaKind.VIDEO
    }

    val path = url.substringBefore('?').substringBefore('#').lowercase()
    val extension = path.substringAfterLast('.', "")
    return when (extension) {
        in audioExtensions -> MediaKind.AUDIO
        in imageExtensions -> MediaKind.IMAGE
        in videoExtensions -> MediaKind.VIDEO
        // Adaptive application MIME types and unknown legacy payloads remain video-compatible.
        else -> MediaKind.VIDEO
    }
}

fun PlayPayload.withResolvedMediaKind(): PlayPayload {
    val resolved = MediaKind.fromWire(media_kind) ?: resolveMediaKind(this)
    return if (media_kind == resolved.wireValue) this else copy(media_kind = resolved.wireValue)
}
