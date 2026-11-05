package com.playbridge.sender.player

import android.content.Context
import android.net.Uri
import android.util.Log
import com.playbridge.shared.logging.redactUrlForLog
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Fetches sidecar subtitles for mpv. mpv's own HTTP client would send the video's headers to every
 * subtitle host, so subtitles are downloaded here with only their own headers and handed to mpv as
 * local files.
 */
internal object MpvSubtitleFiles {
    private const val TAG = "PB_PLAYER"
    private const val MAX_BYTES = 8L * 1024 * 1024
    private const val TIMEOUT_MS = 15_000
    private val knownExtensions = setOf("srt", "vtt", "ass", "ssa", "sub", "ttml", "smi")

    /** A local path mpv can open, or null when the subtitle can't be fetched. Blocking; call off the main thread. */
    fun prepare(context: Context, uri: Uri, headers: Map<String, String>): String? =
        when (uri.scheme?.lowercase()) {
            "file" -> uri.path
            "http", "https" -> runCatching { download(context, uri, headers) }
                .onFailure { Log.w(TAG, "mpv subtitle fetch failed: ${redactUrlForLog(uri.toString())}: ${it.javaClass.simpleName}") }
                .getOrNull()
            else -> null
        }

    fun clear(context: Context) {
        directory(context).listFiles()?.forEach { it.delete() }
    }

    private fun download(context: Context, uri: Uri, headers: Map<String, String>): String? {
        val connection = URL(uri.toString()).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (connection.responseCode !in 200..299) return null
            val extension = uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase()
                ?.takeIf { it in knownExtensions } ?: "srt"
            val target = File(directory(context).apply { mkdirs() }, "${sha1(uri.toString())}.$extension")
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_BYTES) {
                            output.close()
                            target.delete()
                            return null
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
            return target.absolutePath
        } finally {
            connection.disconnect()
        }
    }

    private fun directory(context: Context) = File(context.cacheDir, "mpv-subtitles")

    private fun sha1(value: String): String =
        MessageDigest.getInstance("SHA-1").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
