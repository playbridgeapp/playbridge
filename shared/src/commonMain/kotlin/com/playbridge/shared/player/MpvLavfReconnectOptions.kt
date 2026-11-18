package com.playbridge.shared.player

/**
 * FFmpeg `lavf` HTTP reconnect options shared by TV and mobile `MpvPlayerEngine` instances.
 *
 * Verified against the FFmpeg (`38b8833` / `Lavf62.12.102`) binaries bundled in
 * `tv/android/player/app/libs/mpv-android.aar` (`armeabi-v7a` and `arm64-v8a` `libavformat.so`).
 * Caps FFmpeg itself to at most 3 reconnect attempts per disconnect/error with backoff capped at
 * 5 seconds (`reconnect_delay_max=5,reconnect_max_retries=3`), and enables HTTP reconnect on
 * network errors and `429`/`5xx` HTTP responses.
 *
 * Omits `reconnect_streamed`: with it FFmpeg treats the normal end of a response of unknown length
 * (such as a gzip-compressed HLS playlist) as a dropped connection, re-requests past the end and
 * fails to parse the playlist, so only the audio plays.
 */
object MpvLavfReconnectOptions {
    const val MAX_RETRIES = 3
    const val DELAY_MAX_SECONDS = 5
    const val VALUE =
        "reconnect=1,reconnect_on_network_error=1," +
            "reconnect_on_http_error=\"429,5xx\",reconnect_delay_max=$DELAY_MAX_SECONDS," +
            "reconnect_max_retries=$MAX_RETRIES"
}
