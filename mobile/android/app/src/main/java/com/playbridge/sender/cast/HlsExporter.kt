package com.playbridge.sender.cast

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.SimpleCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.util.NavigableSet

/**
 * Exports a completed Media3 HLS download from the proprietary SimpleCache format
 * into a single concatenated .ts file in the public Downloads folder.
 *
 * Two-phase strategy:
 *  Phase 1 — Read all cached segments from SimpleCache span files and concatenate into a temp
 *             file on internal storage (context.cacheDir). Pure disk I/O, ~250ms for 170MB.
 *  Phase 2 — Copy temp file → MediaStore Downloads using ParcelFileDescriptor.
 *             PFD writes directly to the backing file descriptor, bypassing the ContentProvider
 *             IPC pipe that makes ContentResolver.openOutputStream() block indefinitely.
 */
@OptIn(UnstableApi::class)
object HlsExporter {

    private const val TAG = "HlsExport"

    /** Wraps the output target so we don't have to thread Uri vs File everywhere. */
    private data class OutputTarget(
        val uri: Uri?,       // non-null on Android 10+
        val file: File?,     // non-null on Android < 10
        val displayPath: String
    )

    suspend fun export(
        context: Context,
        hlsUrl: String,
        title: String,
        cache: SimpleCache,
        onProgress: (Long) -> Unit = {}
    ): String? = withContext(Dispatchers.IO) {

        // Manifest reads: HTTP fallback allowed (manifest may not be in cache)
        val manifestDsFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory())

        // Network fallback for the rare case a segment is missing from cache
        val segmentDsFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory())

        val cacheKeys = cache.keys.take(5)
        Log.d(TAG, "Cache has ${cache.keys.size} entries. Sample keys:")
        cacheKeys.forEach { Log.d(TAG, "  cache key: $it") }

        fun readManifest(uri: Uri): ByteArray? {
            val inCache = cache.isCached(uri.toString(), 0, 1)
            Log.d(TAG, "readManifest (inCache=$inCache): $uri")
            return runCatching {
                val ds = manifestDsFactory.createDataSource()
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
                Log.d(TAG, "readManifest OK: ${bytes.size} bytes")
                bytes
            }.onFailure { e ->
                Log.e(TAG, "readManifest FAILED: $uri — ${e.message}", e)
            }.getOrNull()
        }

        // --- Step 1: read the manifest ---
        Log.d(TAG, "Step 1: reading manifest from $hlsUrl")
        val manifestBytes = readManifest(Uri.parse(hlsUrl))
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
            Log.d(TAG, "Step 2: master playlist detected — finding cached variant")

            val allVariants = manifestText.lines()
                .filter { !it.startsWith("#") && it.isNotBlank() }
                .map { resolveUrl(hlsUrl, it) }

            Log.d(TAG, "Found ${allVariants.size} variants, checking cache coverage:")
            allVariants.forEach { v ->
                val firstByte = cache.isCached(v, 0, 1)
                val first100k = cache.isCached(v, 0, 100 * 1024)
                Log.d(TAG, "  variant firstByte=$firstByte first100k=$first100k: $v")
            }

            val chosenVariant = allVariants.firstOrNull { cache.isCached(it, 0, 1) }
                ?: allVariants.firstOrNull()

            if (chosenVariant == null) {
                Log.e(TAG, "ABORT: no variants found in master playlist")
                return@withContext null
            }

            mediaPlaylistUrl = chosenVariant
            Log.d(TAG, "Using media playlist: $mediaPlaylistUrl")
            val playlistBytes = readManifest(Uri.parse(mediaPlaylistUrl))
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

        Log.d(TAG, "Segment cache diagnostics:")
        segmentUrls.take(3).forEachIndexed { i, seg ->
            val b1 = cache.isCached(seg, 0, 1)
            val b1m = cache.isCached(seg, 0, 1 * 1024 * 1024)
            val spans = runCatching { cache.getCachedSpans(seg) }.getOrNull()
            val spanInfo = spans?.joinToString { "pos=${it.position}+${it.length}" } ?: "n/a"
            Log.d(TAG, "  seg[$i] 1B=$b1 1MB=$b1m spans=[$spanInfo]: $seg")
        }

        // --- Step 4: build output filename ---
        val displayTitle = if (title.startsWith("http://") || title.startsWith("https://")) {
            title.substringAfterLast("/").substringBefore("?").takeIf { it.isNotBlank() } ?: "video"
        } else {
            title
        }
        val stripped = displayTitle
            .removeSuffix(".m3u8").removeSuffix(".m3u").removeSuffix(".mp4")
            .removeSuffix(".mkv").removeSuffix(".ts")
        val safeTitle = stripped.replace(Regex("[^a-zA-Z0-9._\\- ]"), "_").trim().take(80)
        val fileName = "$safeTitle.ts"
        Log.d(TAG, "Step 4: writing to Downloads as '$fileName'")

        val outputTarget = openOutputTarget(context, fileName)
        if (outputTarget == null) {
            Log.e(TAG, "ABORT: openOutputTarget returned null for '$fileName'")
            return@withContext null
        }
        Log.d(TAG, "Output path: ${outputTarget.displayPath}")

        // --- Phase 1: Concatenate all segments into a temp file on internal storage ---
        // Writes to context.cacheDir (fast, direct FileOutputStream, no ContentProvider).
        val tempFile = File(context.cacheDir, fileName)
        var totalWritten = 0L
        var segmentsFailed = 0
        val phase1Start = System.currentTimeMillis()
        val buf = ByteArray(256 * 1024)

        tempFile.outputStream().buffered(4 * 1024 * 1024).use { tempOut ->
            for ((index, segUrl) in segmentUrls.withIndex()) {
                if (!isActive) {
                    Log.w(TAG, "Export cancelled at segment $index")
                    tempFile.delete()
                    cleanupOutputTarget(context, outputTarget)
                    return@withContext null
                }
                val segStart = System.currentTimeMillis()

                val spans: NavigableSet<CacheSpan>? = runCatching {
                    cache.getCachedSpans(segUrl)
                }.onFailure { e ->
                    Log.e(TAG, "  seg[$index] getCachedSpans failed: ${e.message}")
                }.getOrNull()

                if (spans.isNullOrEmpty()) {
                    Log.w(TAG, "  seg[$index] not in cache, falling back to network")
                    val ds = segmentDsFactory.createDataSource()
                    runCatching {
                        ds.open(DataSpec(Uri.parse(segUrl)))
                        while (isActive) {
                            val n = ds.read(buf, 0, buf.size)
                            if (n == C.RESULT_END_OF_INPUT) break
                            if (n > 0) { tempOut.write(buf, 0, n); totalWritten += n }
                        }
                    }.onFailure { e ->
                        segmentsFailed++
                        Log.e(TAG, "  seg[$index] network fallback FAILED: ${e.message}")
                    }
                    runCatching { ds.close() }
                } else {
                    var spansFailed = 0
                    for (span in spans.sortedBy { it.position }) {
                        val spanFile = span.file ?: continue
                        runCatching {
                            spanFile.inputStream().use { fis ->
                                var n: Int
                                while (fis.read(buf).also { n = it } != -1) {
                                    if (!isActive) {
                                        tempFile.delete()
                                        cleanupOutputTarget(context, outputTarget)
                                        return@withContext null
                                    }
                                    tempOut.write(buf, 0, n)
                                    totalWritten += n
                                    onProgress(totalWritten)
                                }
                            }
                        }.onFailure { e ->
                            spansFailed++
                            Log.e(TAG, "  seg[$index] span read failed: ${e.message}")
                        }
                    }
                    if (spansFailed > 0) segmentsFailed++
                    val segMs = System.currentTimeMillis() - segStart
                    if (index < 3 || (index + 1) % 10 == 0) {
                        Log.d(TAG, "  seg ${index + 1}/${segmentUrls.size}: ${segMs}ms ${spans.size} spans, total=${totalWritten / 1024}KB")
                    }
                }
            }
        } // buffered flush + close

        val phase1Ms = System.currentTimeMillis() - phase1Start
        Log.d(TAG, "Phase 1 done in ${phase1Ms}ms: ${totalWritten / 1024}KB, $segmentsFailed failed, tempFile=${tempFile.length() / 1024}KB")

        if (totalWritten == 0L) {
            Log.e(TAG, "ABORT: nothing written — all segments failed")
            tempFile.delete()
            cleanupOutputTarget(context, outputTarget)
            return@withContext null
        }

        // --- Phase 2: Write temp file → output target via ParcelFileDescriptor ---
        // PFD gives us a raw file descriptor — writes go directly to the backing file,
        // bypassing the ContentProvider IPC pipe that made ContentResolver.openOutputStream block.
        val tempSizeKb = tempFile.length() / 1024
        Log.d(TAG, "Phase 2: copying ${tempSizeKb}KB to ${outputTarget.displayPath} via PFD")
        val phase2Start = System.currentTimeMillis()

        val result = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && outputTarget.uri != null) {
                val pfd: ParcelFileDescriptor = context.contentResolver
                    .openFileDescriptor(outputTarget.uri, "w")
                    ?: throw IOException("openFileDescriptor returned null for ${outputTarget.uri}")

                var copied = 0L
                pfd.use {
                    FileOutputStream(it.fileDescriptor).buffered(8 * 1024 * 1024).use { out ->
                        tempFile.inputStream().use { inp ->
                            val chunk = ByteArray(1024 * 1024)
                            var n: Int
                            while (inp.read(chunk).also { n = it } != -1) {
                                out.write(chunk, 0, n)
                                copied += n
                                if (copied % (50L * 1024 * 1024) < chunk.size) {
                                    Log.d(TAG, "Phase 2: ${copied / 1024}KB / ${tempSizeKb}KB written")
                                }
                            }
                        }
                    }
                }
                Log.d(TAG, "Phase 2: PFD write complete, ${copied / 1024}KB written — clearing IS_PENDING")
                val pendingValues = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                context.contentResolver.update(outputTarget.uri, pendingValues, null, null)
                Log.d(TAG, "Phase 2: IS_PENDING cleared → file is now visible in Downloads")
            } else if (outputTarget.file != null) {
                // Android < 10: copy directly to the public Downloads file
                tempFile.copyTo(outputTarget.file, overwrite = true)
                Log.d(TAG, "Phase 2: file copied to ${outputTarget.file.absolutePath}")
            }
        }.onFailure { e ->
            Log.e(TAG, "Phase 2 FAILED: ${e.message}", e)
        }

        runCatching { tempFile.delete() }

        val phase2Ms = System.currentTimeMillis() - phase2Start

        if (result.isFailure) {
            Log.e(TAG, "Phase 2 failed after ${phase2Ms}ms")
            cleanupOutputTarget(context, outputTarget)
            return@withContext null
        }

        Log.d(TAG, "Phase 2 done in ${phase2Ms}ms. Export complete → ${outputTarget.displayPath}")
        outputTarget.displayPath
    }

    /** Creates the MediaStore entry (Android 10+) or output File (older), returns the target. */
    private fun openOutputTarget(context: Context, fileName: String): OutputTarget? {
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
            OutputTarget(uri, null, uri.toString())
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            val file = File(dir, fileName)
            OutputTarget(null, file, file.absolutePath)
        }
    }

    /** Removes a partially-written output target on failure. */
    private fun cleanupOutputTarget(context: Context, target: OutputTarget) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && target.uri != null) {
                context.contentResolver.delete(target.uri, null, null)
                Log.d(TAG, "Cleaned up pending MediaStore entry: ${target.uri}")
            } else {
                target.file?.delete()
            }
        }.onFailure { Log.e(TAG, "cleanup failed: ${it.message}") }
    }

    /** Resolves a potentially relative URL against a base URL. */
    private fun resolveUrl(base: String, relative: String): String {
        if (relative.startsWith("http://") || relative.startsWith("https://")) return relative
        return runCatching { URI(base).resolve(relative).toString() }.getOrDefault(relative)
    }
}
