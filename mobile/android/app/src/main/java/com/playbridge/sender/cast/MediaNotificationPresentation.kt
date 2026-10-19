package com.playbridge.sender.cast

import java.net.URI

internal data class WebsiteMediaNotificationText(val title: String, val subtitle: String)

internal fun websiteMediaNotificationText(
    pageTitle: String?,
    mediaTitle: String?,
    pageUrl: String?,
): WebsiteMediaNotificationText {
    val page = pageTitle?.trim()?.takeIf { it.isNotEmpty() }
    val media = mediaTitle?.trim()?.takeIf { it.isNotEmpty() }
    // Only the host is a fallback, never a URL/query that might carry credentials.
    val host = runCatching { URI(pageUrl.orEmpty()).host }.getOrNull()?.takeIf { it.isNotBlank() }
    val title = page ?: media ?: host ?: "Web Video"
    val subtitle = media?.takeUnless { it.equals(title, ignoreCase = true) }
        ?: host?.takeUnless { it.equals(title, ignoreCase = true) } ?: "Browser"
    return WebsiteMediaNotificationText(title, subtitle)
}

/** Gecko uses seconds; the Android MediaSession uses milliseconds and elapsed realtime. */
internal data class MediaNotificationPosition(
    val durationMs: Long?,
    val positionMs: Long?,
    val playbackRate: Float,
    val updatedAtMs: Long,
) {
    fun positionAt(nowMs: Long, playing: Boolean): Long? {
        val position = positionMs ?: return null
        val elapsed = if (playing) (nowMs.toDouble() - updatedAtMs.toDouble()).coerceAtLeast(0.0) else 0.0
        return (position.toDouble() + elapsed * playbackRate)
            .coerceIn(0.0, (durationMs ?: Long.MAX_VALUE).toDouble()).toLong()
    }

    fun rebase(nowMs: Long, wasPlaying: Boolean): MediaNotificationPosition =
        copy(positionMs = positionAt(nowMs, wasPlaying), updatedAtMs = nowMs)

    companion object {
        fun fromSeconds(duration: Double, position: Double, rate: Double, nowMs: Long): MediaNotificationPosition {
            fun milliseconds(value: Double): Long? = value.takeIf {
                it.isFinite() && it >= 0 && it <= Long.MAX_VALUE / 1000.0
            }?.let { (it * 1000).toLong() }
            val durationMs = milliseconds(duration)?.takeIf { it > 0 }
            val positionMs = milliseconds(position)?.coerceAtMost(durationMs ?: Long.MAX_VALUE)
            val playbackRate = rate.takeIf { it.isFinite() && it >= 0 && it <= Float.MAX_VALUE }?.toFloat() ?: 1f
            return MediaNotificationPosition(durationMs, positionMs, playbackRate, nowMs)
        }
    }
}

internal fun mediaNotificationCanSeek(position: MediaNotificationPosition?, supported: Boolean): Boolean =
    supported && (position?.durationMs ?: 0) > 0

internal fun mediaNotificationSeekTarget(position: MediaNotificationPosition?, supported: Boolean, targetMs: Long): Long? =
    if (mediaNotificationCanSeek(position, supported)) targetMs.coerceIn(0, position!!.durationMs!!) else null

/** Resolves only an existing tab, so a stale notification never creates/reloads a replacement. */
internal fun mediaNotificationTabTarget(tabId: String?, existingTabIds: Collection<String>): String? =
    tabId?.takeIf { it in existingTabIds }
