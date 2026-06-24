package com.playbridge.sender.downloads.engine

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withContext
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/**
 * Plain-file download. Multi-threaded ranged chunks when the origin cleanly supports
 * `Accept-Ranges: bytes` on a large file; single-stream Range-resume otherwise.
 *
 * Robustness rules learned from real hosts (e.g. gofile.io):
 *  - If a "Range" request comes back `200` (server ignored it), abort ranged and fall back
 *    to a clean single-stream GET — otherwise every chunk gets the full body at the wrong
 *    offset.
 *  - A `0`-byte result is treated as a FAILURE, never published. Hosts that need a cookie
 *    sometimes answer 200 with an empty body; surfacing that as "Failed (0 bytes…)" is far
 *    better than silently saving an empty file.
 */
class FileStrategy(
    private val client: OkHttpClient,
    private val threadCount: Int = DEFAULT_THREADS,
) : DownloadStrategy {

    override val kind = DownloadKind.FILE

    private class RangeNotHonored : IOException("Server ignored Range request")

    override suspend fun download(
        request: DownloadRequest,
        downloadDir: File,
        controller: DownloadController,
        onProgress: (Progress) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val finalFile = File(downloadDir, "content" + guessExtension(request))

        // Prefer the browser's already-fetched stream (carries the session). Falls through to
        // HTTP re-fetch if absent or if it fails (e.g. a retry after the stream was consumed).
        val prefetched = BrowserResponseStore.take(request.url)
        if (prefetched != null) {
            try {
                streamFromBrowser(prefetched, finalFile, controller, onProgress)
                if (finalFile.length() == 0L) {
                    throw IOException("Browser stream produced 0 bytes for ${request.url}")
                }
                Log.d(TAG, "FileStrategy complete via browser stream: ${finalFile.length()} bytes")
                return@withContext finalFile
            } catch (e: DownloadPausedException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "Browser stream failed (${e.message}) — re-fetching over HTTP")
                finalFile.delete()
            }
        }

        val head = probe(request.url, request.headers)
        Log.d(
            TAG,
            "probe url=${request.url} len=${head.length} ranges=${head.acceptsRanges} " +
                "hasCookie=${request.headers.keys.any { it.equals("Cookie", true) }}",
        )

        val tryRanged = head.acceptsRanges && head.length > RANGED_MIN_SIZE && threadCount > 1
        var doneViaRanged = false
        if (tryRanged) {
            try {
                ranged(request, downloadDir, finalFile, head.length, controller, onProgress)
                if (finalFile.length() >= head.length) {
                    doneViaRanged = true
                } else {
                    Log.w(TAG, "Ranged produced ${finalFile.length()}/${head.length} bytes — falling back")
                }
            } catch (e: RangeNotHonored) {
                Log.w(TAG, "Range not honored — falling back to single-stream")
            }
        }
        if (!doneViaRanged) {
            finalFile.delete()
            File(downloadDir, "parts").deleteRecursively()
            singleStream(request, finalFile, controller, onProgress)
        }

        if (finalFile.length() == 0L) {
            throw IOException(
                "Downloaded 0 bytes from ${request.url}. The source likely needs cookies/auth " +
                    "that weren't captured, or returned an empty body.",
            )
        }
        Log.d(TAG, "FileStrategy complete: ${finalFile.length()} bytes -> ${finalFile.name}")
        finalFile
    }

    // --- browser-prefetched stream (carries the GeckoView session) ---

    private fun streamFromBrowser(
        response: mozilla.components.concept.fetch.Response,
        finalFile: File,
        controller: DownloadController,
        onProgress: (Progress) -> Unit,
    ) {
        val total = response.headers
            .firstOrNull { it.name.equals("Content-Length", ignoreCase = true) }
            ?.value?.toLongOrNull() ?: -1L
        Log.d(TAG, "browser-stream status=${response.status} total=$total")
        var copied = 0L
        response.body.useStream { input ->
            FileOutputStream(finalFile).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    if (controller.isCancelRequested()) throw CancellationException("cancelled")
                    // A browser stream can't be resumed once consumed; pause just stops it.
                    if (controller.isPauseRequested()) throw DownloadPausedException()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    onProgress(Progress(copied, total))
                }
            }
        }
    }

    // --- multi-threaded ranged ---

    private suspend fun ranged(
        request: DownloadRequest,
        downloadDir: File,
        finalFile: File,
        contentSize: Long,
        controller: DownloadController,
        onProgress: (Progress) -> Unit,
    ) = coroutineScope {
        val partsDir = File(downloadDir, "parts").apply { mkdirs() }
        val n = threadCount
        val chunkSize = contentSize / n
        val copied = AtomicLong(0L)

        data class ChunkSpec(val index: Int, val start: Long, val end: Long, val part: File)
        val chunks = (0 until n).map { i ->
            val start = i * chunkSize
            val end = if (i == n - 1) contentSize - 1 else (i + 1) * chunkSize - 1
            ChunkSpec(i, start, end, File(partsDir, "part_$i"))
        }
        copied.set(chunks.sumOf { if (it.part.exists()) it.part.length() else 0L })

        val dispatcher = Dispatchers.IO.limitedParallelism(n)
        val jobs = mutableListOf<Job>()
        for (c in chunks) {
            jobs += launch(dispatcher) {
                val expected = c.end - c.start + 1
                val have = if (c.part.exists()) c.part.length() else 0L
                if (have >= expected) return@launch
                if (have > expected) c.part.delete()

                val resumeAt = c.start + minOf(have, expected)
                val headers = request.headers.toMutableMap()
                headers["Range"] = "bytes=$resumeAt-${c.end}"
                val req = Request.Builder().url(request.url).headers(headers.toHeaders()).build()

                client.newCall(req).execute().use { response ->
                    if (response.code == 200) throw RangeNotHonored() // server ignored Range
                    if (!response.isSuccessful) throw IOException("Chunk ${c.index}: HTTP ${response.code}")
                    val body = response.body ?: throw IOException("Chunk ${c.index}: empty body")
                    body.byteStream().use { input ->
                        FileOutputStream(c.part, have > 0).use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                coroutineContext.ensureActive()
                                if (controller.isCancelRequested()) throw CancellationException("cancelled")
                                if (controller.isPauseRequested()) throw DownloadPausedException()
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                onProgress(Progress(copied.addAndGet(read.toLong()), contentSize))
                            }
                        }
                    }
                }
            }
        }
        jobs.joinAll()

        FileOutputStream(finalFile).use { out ->
            for (c in chunks) c.part.inputStream().use { it.copyTo(out) }
        }
        partsDir.deleteRecursively()
        Log.d(TAG, "Ranged complete: $contentSize bytes in $n chunks")
    }

    // --- single-stream with Range resume ---

    private suspend fun singleStream(
        request: DownloadRequest,
        finalFile: File,
        controller: DownloadController,
        onProgress: (Progress) -> Unit,
    ) {
        val part = File(finalFile.parentFile, finalFile.name + ".part")
        val already = if (part.exists()) part.length() else 0L
        val headers = request.headers.toMutableMap()
        val resume = already > 0 && probe(request.url, request.headers).acceptsRanges
        if (resume) headers["Range"] = "bytes=$already-" else if (already > 0) part.delete()
        val resumeFrom = if (resume) already else 0L

        val req = Request.Builder().url(request.url).headers(headers.toHeaders()).build()
        client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for ${request.url}")
            // Session-gated hosts answer an un-authed request with their HTML page (200) instead
            // of the file. Don't save that as a video — fail with something actionable.
            val ctype = response.header("Content-Type").orEmpty()
            if (ctype.contains("text/html", ignoreCase = true) &&
                request.mimeType?.contains("html", ignoreCase = true) != true
            ) {
                throw IOException(
                    "Host returned a web page, not the file — it likely requires a logged-in " +
                        "session this download can't replicate.",
                )
            }
            val body = response.body ?: throw IOException("Empty body for ${request.url}")
            val reported = body.contentLength()
            val grandTotal = if (reported >= 0) resumeFrom + reported else -1L
            Log.d(TAG, "singleStream code=${response.code} contentLength=$reported resumeFrom=$resumeFrom")
            var copied = resumeFrom
            body.byteStream().use { input ->
                FileOutputStream(part, resumeFrom > 0).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        if (controller.isCancelRequested()) throw CancellationException("cancelled")
                        if (controller.isPauseRequested()) throw DownloadPausedException()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        onProgress(Progress(copied, grandTotal))
                    }
                }
            }
        }
        if (part != finalFile) { part.copyTo(finalFile, overwrite = true); part.delete() }
    }

    // --- helpers ---

    private data class ProbeResult(val length: Long, val acceptsRanges: Boolean)

    private fun probe(url: String, headers: Map<String, String>): ProbeResult = runCatching {
        val req = Request.Builder().url(url).head().headers(headers.toHeaders()).build()
        client.newCall(req).execute().use { resp ->
            val len = resp.header("Content-Length")?.toLongOrNull() ?: -1L
            val ranges = resp.header("Accept-Ranges")?.equals("bytes", true) == true
            ProbeResult(len, ranges)
        }
    }.getOrDefault(ProbeResult(-1L, false))

    private fun guessExtension(request: DownloadRequest): String {
        val name = request.url.substringAfterLast('/').substringBefore('?')
        val ext = name.substringAfterLast('.', "")
        return if (ext.isNotBlank() && ext.length <= 5) ".$ext" else ""
    }

    private companion object {
        const val DEFAULT_THREADS = 4
        const val RANGED_MIN_SIZE = 1_000_000L // don't bother chunking < ~1 MB
        const val TAG = "FileStrategy"
    }
}
