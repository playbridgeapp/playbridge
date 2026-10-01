package com.playbridge.sender.data.settings

import com.playbridge.sender.cast.DetectedMediaKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Automatic detection preferences. Explicit website casting stays available independently. */
@Serializable
data class MediaDetectionSettings(
    val enabled: Boolean = true,
    val videos: Boolean = true,
    val images: Boolean = true,
    val audio: Boolean = true,
    val subtitles: Boolean = true,
    val domScanning: Boolean = true,
    val networkDetection: Boolean = true,
    val responseScanning: Boolean = true,
    val navigationRescans: Boolean = true,
    val playerProbes: Boolean = true,
    val visibilityOverrides: Boolean = true,
    val detectInBridgedSites: Boolean = false,
) {
    fun allows(kind: DetectedMediaKind): Boolean = enabled && when (kind) {
        DetectedMediaKind.VIDEO -> videos
        DetectedMediaKind.IMAGE -> images
        DetectedMediaKind.AUDIO -> audio
        DetectedMediaKind.SUBTITLE -> subtitles
    }

    companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun decode(value: String?): MediaDetectionSettings =
            value?.let { runCatching { json.decodeFromString<MediaDetectionSettings>(it) }.getOrNull() }
                ?: MediaDetectionSettings()
    }
}
