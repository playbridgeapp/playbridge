package com.playbridge.player.player

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException

internal data class DownloadedSubtitle(
    val bytes: ByteArray,
    val format: ExternalSubtitleFormat,
)

internal enum class ExternalSubtitleFormat(val fileExtension: String) {
    SUBRIP("srt"),
    WEBVTT("vtt"),
    ASS("ass"),
    SSA("ssa"),
    TTML("ttml"),
}

/** Header-aware subtitle download shared by the overlay and native renderer staging path. */
internal object ExternalSubtitleLoader {
    fun download(
        url: String,
        headers: Map<String, String>? = null,
    ): DownloadedSubtitle {
        val requestUrl = url.substringBefore('#')
        val sniffer = ContentSniffer()
        val client = sniffer.getOkHttpClient(
            allowLocalSelfSigned = sniffer.isLocalUrl(requestUrl),
        )
        val requestBuilder = Request.Builder()
            .url(requestUrl)
            .header("User-Agent", "Mozilla/5.0")

        headers.orEmpty().forEach { (key, value) ->
            // A supplied Host value must not redirect an authenticated request to another host.
            if (!key.equals("Host", ignoreCase = true)) requestBuilder.header(key, value)
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Unexpected HTTP code: ${response.code}")
            val bytes = response.body?.bytes() ?: ByteArray(0)
            if (bytes.isEmpty()) throw IOException("Subtitle response was empty")
            return DownloadedSubtitle(
                bytes = bytes,
                format = detectSubtitleFormat(
                    url = requestUrl,
                    contentType = response.header("Content-Type"),
                    bytes = bytes,
                ),
            )
        }
    }
}

/** Stages authenticated browser subtitles where either renderer process can read them. */
internal class ExternalSubtitleStager(context: Context) {
    private val directory = File(context.cacheDir, CACHE_DIRECTORY)

    suspend fun stage(
        url: String,
        headers: Map<String, String>? = null,
    ): File = withContext(Dispatchers.IO) {
        var stagedFile: File? = null
        try {
            val downloaded = ExternalSubtitleLoader.download(url, headers)
            currentCoroutineContext().ensureActive()
            if (!directory.exists() && !directory.mkdirs()) {
                throw IOException("Unable to create subtitle cache")
            }
            removeExpiredFiles()
            val file = File.createTempFile(
                FILE_PREFIX,
                ".${downloaded.format.fileExtension}",
                directory,
            ).apply {
                outputStream().use { it.write(downloaded.bytes) }
            }
            stagedFile = file
            currentCoroutineContext().ensureActive()
            file
        } catch (error: Exception) {
            stagedFile?.let(::delete)
            throw error
        }
    }

    fun uriFor(file: File): String = Uri.fromFile(file).toString()

    fun delete(file: File?) {
        if (file == null || file.parentFile != directory) return
        runCatching { file.delete() }
    }

    private fun removeExpiredFiles() {
        val cutoff = System.currentTimeMillis() - MAX_CACHE_AGE_MS
        directory.listFiles().orEmpty()
            .filter { it.isFile && it.lastModified() < cutoff }
            .forEach { runCatching { it.delete() } }
    }

    private companion object {
        const val CACHE_DIRECTORY = "external-subtitles"
        const val FILE_PREFIX = "subtitle-"
        const val MAX_CACHE_AGE_MS = 24L * 60L * 60L * 1_000L
    }
}

internal fun detectSubtitleFormat(
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

    val prefix = SubtitleParser.decode(bytes.copyOfRange(0, minOf(bytes.size, 8_192))).trimStart()
    return when {
        prefix.startsWith("WEBVTT", ignoreCase = true) -> ExternalSubtitleFormat.WEBVTT
        prefix.startsWith("[Script Info]", ignoreCase = true) ||
            prefix.contains("[V4+ Styles]", ignoreCase = true) -> ExternalSubtitleFormat.ASS
        prefix.startsWith("<tt", ignoreCase = true) ||
            prefix.contains("<tt ", ignoreCase = true) -> ExternalSubtitleFormat.TTML
        else -> ExternalSubtitleFormat.SUBRIP
    }
}
