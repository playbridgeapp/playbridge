package com.playbridge.sender.browser

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.net.URI

/**
 * Exports a completed Media3 HLS download from the proprietary SimpleCache format
 * into a single concatenated .ts file in the public Downloads folder.
 *
 * Strategy:
 *  1. Read the (cached) HLS manifest via CacheDataSource — no network needed.
 *  2. If it's a master playlist, follow the first/best media playlist.
 *  3. Parse segment URLs out of the media playlist.
 *  4. Concatenate every segment from the cache into an output stream.
 */
@OptIn(UnstableApi::class)
object HlsExporter {

    private const val TAG = "HlsExport"

    /**
     * @param hlsUrl   The original HLS URL used when the download was enqueued.
     * @param title    Suggested output filename (without extension).
     * @param cache    The app's SimpleCache instance.
     * @param onProgress  Called with bytes written so far (can be used for a progress indicator).
     * @return Absolute path of the exported file, or null on failure.
     */
    suspend fun export(
        context: Context,
        hlsUrl: String,
        title: String,
        cache: SimpleCache,
        onProgress: (Long) -> Unit = {}
    ): String? = withContext(Dispatchers.IO) {

        // Use network as upstream fallback so the manifest can be re-fetched if not cached.
        // Segments are always read from cache (they're fully downloaded).
        val dataSourceFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory())
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        fun readUri(uri: Uri): ByteArray? {
            Log.d(TAG, "readUri: $uri")
            return runCatching {
                val ds = dataSourceFactory.createDataSource()
                val spec = DataSpec(uri)
                ds.open(spec)
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(32 * 1024)
                while (isActive) {
                    val n = ds.read(buf, 0, buf.size)
                    if (n == C.RESULT_END_OF_INPUT) break
                    if (n > 0) out.write(buf, 0, n)
                }
                ds.close()
                val bytes = out.toByteArray()
                Log.d(TAG, "readUri OK: ${bytes.size} bytes from $uri")
                bytes
            }.onFailure { e ->
                Log.e(TAG, "readUri FAILED: $uri — ${e.message}", e)
            }.getOrNull()
        }

        // --- Step 1: read the manifest ---
        Log.d(TAG, "Step 1: reading manifest from $hlsUrl")
        val manifestBytes = readUri(Uri.parse(hlsUrl))
        if (manifestBytes == null) {
            Log.e(TAG, "ABORT: manifest read returned null for $hlsUrl")
            return@withContext null
        }
        val manifestText = manifestBytes.toString(Charsets.UTF_8)
        Log.d(TAG, "Manifest first line: ${manifestText.lines().firstOrNull()}")

        // --- Step 2: resolve to a media playlist if this is a master playlist ---
        val mediaPlaylistUrl: String
        val mediaPlaylistText: String
        if (manifestText.contains("#EXT-X-STREAM-INF")) {
            Log.d(TAG, "Step 2: master playlist detected — picking first variant")
            val variantRelative = manifestText.lines()
                .firstOrNull { !it.startsWith("#") && it.isNotBlank() }
            if (variantRelative == null) {
                Log.e(TAG, "ABORT: no variant URL found in master playlist")
                return@withContext null
            }
            mediaPlaylistUrl = resolveUrl(hlsUrl, variantRelative)
            Log.d(TAG, "Media playlist URL: $mediaPlaylistUrl")
            val playlistBytes = readUri(Uri.parse(mediaPlaylistUrl))
            if (playlistBytes == null) {
                Log.e(TAG, "ABORT: media playlist read returned null")
                return@withContext null
            }
            mediaPlaylistText = playlistBytes.toString(Charsets.UTF_8)
        } else {
            Log.d(TAG, "Step 2: manifest is already a media playlist")
            mediaPlaylistUrl = hlsUrl
            mediaPlaylistText = manifestText
        }

        // --- Step 3: extract ordered segment URLs ---
        val segmentUrls = mediaPlaylistText.lines()
            .filter { !it.startsWith("#") && it.isNotBlank() }
            .map { resolveUrl(mediaPlaylistUrl, it) }
        Log.d(TAG, "Step 3: found ${segmentUrls.size} segments")
        if (segmentUrls.isEmpty()) {
            Log.e(TAG, "ABORT: no segments found in media playlist")
            return@withContext null
        }
        segmentUrls.take(3).forEach { Log.d(TAG, "  segment: $it") }

        // --- Step 4: write all segments to Downloads ---
        // If title is a raw URL (stored when fileName was null), extract just the last path segment
        val displayTitle = if (title.startsWith("http://") || title.startsWith("https://")) {
            title.substringAfterLast("/").substringBefore("?").takeIf { it.isNotBlank() } ?: "video"
        } else {
            title
        }
        val safeTitle = displayTitle.replace(Regex("[^a-zA-Z0-9._\\- ]"), "_").trim().take(80)
        val fileName = if (safeTitle.endsWith(".ts", ignoreCase = true)) safeTitle else "$safeTitle.ts"
        Log.d(TAG, "Step 4: writing to Downloads as '$fileName'")

        val outputPair = openOutputFile(context, fileName)
        if (outputPair == null) {
            Log.e(TAG, "ABORT: openOutputFile returned null for '$fileName'")
            return@withContext null
        }
        val (outputStream, exportedPath) = outputPair
        Log.d(TAG, "Output path: $exportedPath")

        var totalWritten = 0L
        outputStream.use { out ->
            val buf = ByteArray(64 * 1024)
            for ((index, segUrl) in segmentUrls.withIndex()) {
                if (!isActive) {
                    Log.w(TAG, "Export cancelled at segment $index")
                    return@withContext null
                }
                val ds = dataSourceFactory.createDataSource()
                runCatching {
                    ds.open(DataSpec(Uri.parse(segUrl)))
                    while (isActive) {
                        val n = ds.read(buf, 0, buf.size)
                        if (n == C.RESULT_END_OF_INPUT) break
                        if (n > 0) {
                            out.write(buf, 0, n)
                            totalWritten += n
                            onProgress(totalWritten)
                        }
                    }
                    if (index == 0 || (index + 1) % 10 == 0) {
                        Log.d(TAG, "  wrote segment ${index + 1}/${segmentUrls.size}, total=${totalWritten / 1024}KB")
                    }
                }.onFailure { e ->
                    Log.e(TAG, "  segment $index FAILED: ${e.message}")
                }
                runCatching { ds.close() }
            }
        }

        Log.d(TAG, "Export complete: totalWritten=${totalWritten / 1024}KB")
        if (totalWritten == 0L) {
            Log.e(TAG, "ABORT: nothing was written — all segments failed")
            return@withContext null
        }
        exportedPath
    }

    /** Opens a writable output stream in the public Downloads folder. */
    private fun openOutputFile(context: Context, fileName: String): Pair<OutputStream, String>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "video/mp2t")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            ) ?: return null
            val stream = context.contentResolver.openOutputStream(uri) ?: return null
            // Mark as complete when stream is closed — wrap so we can finalize
            val wrappedStream = object : java.io.FilterOutputStream(stream) {
                override fun close() {
                    super.close()
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    context.contentResolver.update(uri, values, null, null)
                }
            }
            Pair(wrappedStream, uri.toString())
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            val file = File(dir, fileName)
            Pair(file.outputStream(), file.absolutePath)
        }
    }

    /** Resolves a potentially relative URL against a base URL. */
    private fun resolveUrl(base: String, relative: String): String {
        if (relative.startsWith("http://") || relative.startsWith("https://")) return relative
        return runCatching { URI(base).resolve(relative).toString() }.getOrDefault(relative)
    }
}
