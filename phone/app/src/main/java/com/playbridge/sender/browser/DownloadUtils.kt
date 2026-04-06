package com.playbridge.sender.browser

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import android.webkit.MimeTypeMap
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.offline.DownloadHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import java.io.IOException

@UnstableApi
object DownloadUtils {

    fun enqueueDownload(
        context: Context,
        url: String,
        fileName: String?,
        mimeType: String?,
        userAgent: String?,
        cookies: String?,
        referer: String?,
        pageTitle: String? = null
    ) {
        try {
            val isHls = url.contains(".m3u8") ||
                    mimeType?.contains("mpegurl") == true ||
                    mimeType?.contains("x-mpegURL") == true

            if (isHls && !url.startsWith("data:")) {
                val displayName = fileName?.takeIf { it.isNotEmpty() }
                    ?: pageTitle?.sanitizeAsFilename()?.takeIf { it.isNotEmpty() }
                    ?: url.substringAfterLast("/").substringBefore("?").takeIf { it.isNotBlank() }
                    ?: "video"

                // Register browser session headers for segment requests
                val httpHeaders = mutableMapOf<String, String>()
                if (cookies != null) httpHeaders["Cookie"] = cookies
                if (userAgent != null) httpHeaders["User-Agent"] = userAgent
                if (referer != null) httpHeaders["Referer"] = referer
                DownloadHeadersStore.register(url, httpHeaders)

                // Use a header-aware factory for probing the master playlist
                val probeFactory = DefaultHttpDataSource.Factory().apply {
                    if (httpHeaders.isNotEmpty()) setDefaultRequestProperties(httpHeaders)
                }

                Toast.makeText(context, "Loading stream info…", Toast.LENGTH_SHORT).show()

                val mediaItem = MediaItem.Builder()
                    .setUri(Uri.parse(url))
                    .setMimeType(MimeTypes.APPLICATION_M3U8)
                    .build()
                val helper = DownloadHelper.forMediaItem(
                    mediaItem,
                    TrackSelectionParameters.Builder(context).build(),
                    DefaultRenderersFactory(context),
                    probeFactory
                )
                helper.prepare(object : DownloadHelper.Callback {

                    override fun onPrepared(helper: DownloadHelper) {
                        // Collect available video formats from the adaptive track group
                        data class Quality(val label: String, val width: Int, val height: Int, val bitrate: Int, val trackGroup: TrackGroup, val trackIndex: Int)
                        val qualities = mutableListOf<Quality>()

                        runCatching {
                            val mapped = helper.getMappedTrackInfo(0)
                            for (rendererIdx in 0 until mapped.rendererCount) {
                                if (mapped.getRendererType(rendererIdx) != C.TRACK_TYPE_VIDEO) continue
                                val groups = mapped.getTrackGroups(rendererIdx)
                                for (gi in 0 until groups.length) {
                                    val group = groups[gi]
                                    for (ti in 0 until group.length) {
                                        val fmt = group.getFormat(ti)
                                        val res = when {
                                            fmt.height > 0 -> "${fmt.height}p"
                                            else -> "Unknown"
                                        }
                                        val kbps = if (fmt.bitrate > 0) " (${fmt.bitrate / 1000} kbps)" else ""
                                        qualities.add(Quality("$res$kbps", fmt.width, fmt.height, fmt.bitrate, group, ti))
                                    }
                                }
                            }
                        }

                        Handler(Looper.getMainLooper()).post {
                            if (qualities.size <= 1) {
                                // Single quality or couldn't parse — download as-is
                                val req = helper.getDownloadRequest(displayName.toByteArray())
                                sendDownload(context, req)
                                helper.release()
                                return@post
                            }

                            // Sort highest → lowest resolution
                            val sorted = qualities.sortedByDescending { it.height * it.width }
                            val labels = sorted.map { it.label }.toTypedArray()

                            AlertDialog.Builder(context)
                                .setTitle("Select Quality")
                                .setItems(labels) { _, which ->
                                    val chosen = sorted[which]
                                    // Use TrackSelectionOverride to pin exactly one variant
                                    helper.clearTrackSelections(0)
                                    val params = TrackSelectionParameters.Builder(context)
                                        .addOverride(TrackSelectionOverride(chosen.trackGroup, listOf(chosen.trackIndex)))
                                        .build()
                                    helper.addTrackSelection(0, params)
                                    val req = helper.getDownloadRequest(displayName.toByteArray())
                                    sendDownload(context, req)
                                    helper.release()
                                }
                                .setNegativeButton("Cancel") { _, _ ->
                                    helper.release()
                                }
                                .show()
                        }
                    }

                    override fun onPrepareError(helper: DownloadHelper, e: IOException) {
                        // Manifest probe failed — fall back to full download
                        android.util.Log.w("DownloadUtils", "DownloadHelper prepare failed: ${e.message}")
                        val req = DownloadRequest.Builder(url, Uri.parse(url))
                            .setMimeType(MimeTypes.APPLICATION_M3U8)
                            .setData(displayName.toByteArray())
                            .build()
                        sendDownload(context, req)
                        helper.release()
                    }
                })
                return
            }

            // --- Standard (non-HLS) download via system DownloadManager ---
            val uri = Uri.parse(url)
            val request = DownloadManager.Request(uri)

            if (cookies != null) request.addRequestHeader("Cookie", cookies)
            if (userAgent != null) request.addRequestHeader("User-Agent", userAgent)
            if (referer != null) request.addRequestHeader("Referer", referer)

            var finalFileName = fileName?.takeIf { it.isNotEmpty() }
                ?: pageTitle?.sanitizeAsFilename()
            if (finalFileName.isNullOrEmpty()) {
                finalFileName = url.substringAfterLast("/", "download")
                if (finalFileName.contains("?")) finalFileName = finalFileName.substringBefore("?")
            }
            if (!finalFileName.isNullOrEmpty() && !finalFileName.contains('.') && mimeType != null) {
                val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
                if (!ext.isNullOrEmpty()) finalFileName = "$finalFileName.$ext"
            }

            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, finalFileName)
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            if (mimeType != null) {
                request.setMimeType(mimeType)
            } else {
                val ext = MimeTypeMap.getFileExtensionFromUrl(url)
                if (ext != null) {
                    val typedMime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                    if (typedMime != null) request.setMimeType(typedMime)
                }
            }
            request.setTitle(finalFileName)
            request.setDescription("Downloading file...")
            request.setAllowedOverMetered(true)
            request.setAllowedOverRoaming(true)

            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(context, "Download started: $finalFileName", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendDownload(context: Context, request: DownloadRequest) {
        DownloadService.sendAddDownload(
            context,
            MediaDownloadService::class.java,
            request,
            true
        )
        Toast.makeText(context, "Download started", Toast.LENGTH_SHORT).show()
    }

    fun formatFileSize(size: Long): String {
        if (size <= 0) return "0 B"
        if (size < 1024) return "$size B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        val format = if (digitGroups >= 3) "%.2f %s" else "%.1f %s"
        return String.format(java.util.Locale.US, format, size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }

    fun getDownloadErrorString(reason: Int): String {

        return when (reason) {
            DownloadManager.ERROR_CANNOT_RESUME -> "Cannot resume"
            DownloadManager.ERROR_DEVICE_NOT_FOUND -> "External storage device not found"
            DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "File already exists"
            DownloadManager.ERROR_FILE_ERROR -> "File error"
            DownloadManager.ERROR_HTTP_DATA_ERROR -> "HTTP data error"
            DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Insufficient space"
            DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "Too many redirects"
            DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "Unhandled HTTP code"
            DownloadManager.ERROR_UNKNOWN -> "Unknown error"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            500 -> "Internal Server Error"
            502 -> "Bad Gateway"
            503 -> "Service Unavailable"
            else -> "Error code: $reason"
        }
    }
}

/** Strip characters that are illegal in FAT/ext4 filenames and collapse whitespace. */
private fun String.sanitizeAsFilename(): String =
    trim()
        .replace(Regex("""[\\/:*?"<>|]"""), "")   // illegal on FAT32 / Windows
        .replace(Regex("""\s+"""), " ")            // collapse whitespace
        .take(120)                                 // keep it reasonable
        .trimEnd('.')                              // trailing dots confuse some FSes
