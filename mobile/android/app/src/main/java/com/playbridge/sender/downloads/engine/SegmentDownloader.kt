package com.playbridge.sender.downloads.engine

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Downloads one media artifact (segment / init / key) with retries and
 * pause/cancel awareness. Ported in spirit from the reference's `SegmentDownloader`.
 *
 * Resume is implicit: a target file that already exists with length > 0 is skipped, so a
 * re-run only fetches what's missing.
 */
class SegmentDownloader(
    private val client: OkHttpClient,
    private val headers: Map<String, String>,
    private val controller: DownloadController,
) {

    /** @return bytes written (0 if skipped because already present). */
    suspend fun download(url: String, target: File, tag: String): Long {
        if (target.exists() && target.length() > 0) {
            return target.length()
        }

        var lastError: Throwable? = null
        for (attempt in 1..RETRY_COUNT) {
            if (controller.isCancelRequested()) throw CancellationException("cancelled")
            if (controller.isPauseRequested()) throw DownloadPausedException()

            try {
                val request = Request.Builder().url(url).headers(headers.toHeaders()).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        // 403/auth failures won't fix themselves on retry — fail fast.
                        if (response.code == 403 || response.code == 401) {
                            throw IOException("$tag denied: HTTP ${response.code}")
                        }
                        throw IOException("$tag failed: HTTP ${response.code}")
                    }
                    val body = response.body ?: throw IOException("$tag empty body")
                    val tmp = File(target.parentFile, target.name + ".part")
                    body.byteStream().use { input ->
                        tmp.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (!tmp.renameTo(target)) {
                        tmp.copyTo(target, overwrite = true); tmp.delete()
                    }
                    return target.length()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: DownloadPausedException) {
                throw e
            } catch (e: IOException) {
                if (e.message?.contains("denied") == true) throw e // don't retry auth failures
                lastError = e
                Log.w(TAG, "$tag attempt $attempt/$RETRY_COUNT failed: ${e.message}")
                target.delete()
                if (attempt < RETRY_COUNT) delay(BACKOFF_MS * attempt)
            }
        }
        throw IOException("$tag failed after $RETRY_COUNT attempts", lastError)
    }

    private companion object {
        const val RETRY_COUNT = 3
        const val BACKOFF_MS = 1000L
        const val TAG = "SegmentDownloader"
    }
}
