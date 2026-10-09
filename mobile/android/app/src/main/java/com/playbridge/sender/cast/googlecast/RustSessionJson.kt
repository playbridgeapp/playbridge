package com.playbridge.sender.cast.googlecast

import org.json.JSONObject

/** Build the DLNA connect target around the SSDP LOCATION, without discovery enrichment. */
internal fun buildDlnaTargetJson(location: String, networkHandle: Long?): JSONObject =
    JSONObject()
        .put("protocol", "dlna")
        .put("location", location)
        .apply { if (networkHandle != null) put("network_handle", networkHandle) }

/** Build the additive DLNA LOAD payload without requiring the packaged native library. */
internal fun buildDlnaLoadFields(
    url: String,
    contentType: String?,
    title: String?,
    startSeconds: Double,
    durationMs: Long,
    streamType: String?,
    isScreenMirror: Boolean,
    fallbackUrl: String?,
    fallbackContentType: String?,
): JSONObject = JSONObject()
    .put("url", url)
    .put("start_seconds", startSeconds.coerceAtLeast(0.0))
    .put("duration_seconds", durationMs.coerceAtLeast(0L) / 1000.0)
    .put("is_screen_mirror", isScreenMirror)
    .apply {
        putOptional("content_type", contentType)
        putOptional("title", title)
        putOptional("stream_type", streamType)
        putOptional("fallback_url", fallbackUrl)
        putOptional("fallback_content_type", fallbackContentType)
    }

internal fun buildMediaFactsFields(isLive: Boolean?, durationMs: Long?): JSONObject =
    JSONObject().apply {
        if (isLive != null) put("is_live", isLive)
        durationMs?.takeIf { it > 0L }?.let { put("duration_seconds", it / 1000.0) }
    }

private fun JSONObject.putOptional(name: String, value: String?) {
    if (value != null) put(name, value)
}

/** Parse shared Rust status JSON without requiring the packaged native session library. */
internal fun parseRustVolumeSupport(event: JSONObject): Boolean? =
    event.optJSONObject("capabilities")?.optNullableBoolean("volume")

internal fun parseRustPlaybackStatus(
    status: JSONObject,
    previousVolumeSupported: Boolean? = null,
): RustPlaybackStatus = RustPlaybackStatus(
    state = status.optString("state", "unknown"),
    positionSeconds = status.optDouble("position_seconds", 0.0),
    durationSeconds = status.optDouble("duration_seconds", 0.0),
    isLive = status.optBoolean("is_live", false),
    hasLiveField = status.has("is_live") && !status.isNull("is_live"),
    volumeSupported = status.optNullableBoolean("volume_supported") ?: previousVolumeSupported,
)

internal fun JSONObject.optNullableBoolean(name: String): Boolean? =
    if (!has(name) || isNull(name)) null else optBoolean(name)
