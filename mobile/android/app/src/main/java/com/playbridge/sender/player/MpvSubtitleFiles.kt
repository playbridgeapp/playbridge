package com.playbridge.sender.player

import android.content.Context
import android.net.Uri
import android.util.Log
import com.playbridge.shared.logging.redactUrlForLog
import com.playbridge.shared.player.detectSubtitleFormat
import java.io.ByteArrayOutputStream
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
    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val TIMEOUT_MS = 15_000

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
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (output.size() + read > MAX_BYTES) return null
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
            // The same format detection as the TV, so an extensionless URL still gets a file
            // name mpv recognises.
            val format = detectSubtitleFormat(uri.toString(), connection.contentType, bytes)
            val target = File(directory(context).apply { mkdirs() }, "${sha1(uri.toString())}.${format.fileExtension}")
            target.writeBytes(bytes)
            return target.absolutePath
        } finally {
            connection.disconnect()
        }
    }

    private fun directory(context: Context) = File(context.cacheDir, "mpv-subtitles")

    private fun sha1(value: String): String =
        MessageDigest.getInstance("SHA-1").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
