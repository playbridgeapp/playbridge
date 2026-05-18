package com.playbridge.sender.browser

import java.net.URLEncoder

/**
 * URL rewriting helper for mediaflow-proxy (https://github.com/mhdzumair/mediaflow-proxy).
 *
 * Self-hosted endpoint is configured in Settings → Proxy. The TV app receives the
 * rewritten URL and plays it without any awareness of the proxy layer.
 *
 * Endpoints used:
 *   - /proxy/stream                    — generic HTTP passthrough (MP4, etc.)
 *   - /proxy/hls/manifest.m3u8         — HLS passthrough with header injection
 *   - /proxy/transcode/playlist.m3u8   — real-time MP4 → fMP4 HLS transcoding via ffmpeg
 */
object MediaflowProxy {

    const val PREFS_KEY_URL         = "mediaflow_proxy_url"
    const val PREFS_KEY_PASSWORD    = "mediaflow_proxy_password"
    const val PREFS_KEY_AUTO_SELECT = "mediaflow_proxy_auto_select"

    enum class Mode(val label: String) {
        OFF("No Proxy"),
        DIRECT("Proxy Direct"),
        HLS_PROXY("HLS Proxy"),
        TRANSCODE("Transcode → HLS"),
    }

    /**
     * Rewrite [sourceUrl] through the proxy according to [mode].
     *
     * Returns a [Result] with the new URL and an optional content-type override.
     * [DetectedVideo.headers] are encoded into the proxy URL as `h_<Name>=<value>`
     * query params so the TV does not need to forward them — call sites should
     * null-out the headers on the returned video copy.
     *
     * Data URIs (e.g. base64 filtered HLS playlists) pass through unchanged.
     */
    data class Result(val url: String, val contentType: String?)

    fun rewrite(
        mode: Mode,
        proxyBase: String,
        password: String,
        sourceUrl: String,
        headers: Map<String, String>? = null,
    ): Result {
        if (mode == Mode.OFF || proxyBase.isBlank()) return Result(sourceUrl, null)
        // Local data URIs are generated on-device; never send them through a remote proxy.
        if (sourceUrl.startsWith("data:")) return Result(sourceUrl, null)

        val base = proxyBase.trimEnd('/')
        val encodedSource   = encode(sourceUrl)
        val encodedPassword = encode(password)
        val headerParams    = headers
            ?.entries
            ?.filterNot { (k, _) -> k.isBlank() }
            ?.joinToString("") { (k, v) -> "&h_${encode(k)}=${encode(v)}" }
            .orEmpty()

        return when (mode) {
            Mode.OFF -> Result(sourceUrl, null)

            Mode.DIRECT -> Result(
                url = "$base/proxy/stream?d=$encodedSource&api_password=$encodedPassword$headerParams",
                contentType = null   // let ContentSniffer on TV decide
            )

            Mode.HLS_PROXY -> Result(
                url = "$base/proxy/hls/manifest.m3u8?d=$encodedSource&api_password=$encodedPassword$headerParams",
                contentType = "application/x-mpegurl"   // skip ContentSniffer round-trip
            )

            Mode.TRANSCODE -> Result(
                url = "$base/proxy/transcode/playlist.m3u8?d=$encodedSource&api_password=$encodedPassword$headerParams",
                contentType = "application/x-mpegurl"
            )
        }
    }

    /**
     * Suggest the most appropriate [Mode] for [video] based on its URL and content-type.
     * Returns [Mode.OFF] when the stream type is ambiguous or unknown.
     *
     * Rules (evaluated in order):
     *   HLS  (.m3u8 / mpegurl)  → [Mode.HLS_PROXY]   — proxy rewrites segment URLs so the
     *                                                    TV never touches the origin directly.
     *   MP4/direct video file   → [Mode.DIRECT]       — single-file passthrough.
     *   Everything else         → [Mode.OFF]           — leave the user to choose manually.
     *
     * This is intentionally conservative: when unsure we return OFF rather than picking
     * the wrong mode and breaking playback.
     */
    fun autoSelect(video: DetectedVideo): Mode {
        val url = video.url.lowercase()
        val ct  = video.contentType?.lowercase().orEmpty()

        // Strip query string for extension matching
        val path = url.substringBefore('?').substringBefore('#')

        return when {
            // HLS — URL path or content-type signals manifest
            path.endsWith(".m3u8") || ct.contains("mpegurl") -> Mode.HLS_PROXY

            // Direct video file extensions
            path.matches(Regex(".*\\.(mp4|m4v|mkv|avi|mov|webm|ts|mts|m2ts|flv|wmv|ogv|3gp)")) -> Mode.DIRECT

            // Content-type is a concrete video/* MIME (but not application/* which covers HLS/DASH)
            ct.startsWith("video/") -> Mode.DIRECT

            // Unknown — don't guess
            else -> Mode.OFF
        }
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
}
