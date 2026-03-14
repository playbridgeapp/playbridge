package com.playbridge.sender.browser

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.view.Gravity
import android.widget.Toast
import java.io.File
import android.webkit.MimeTypeMap
import androidx.media3.common.util.UnstableApi

object DownloadUtils {

    @androidx.annotation.OptIn(UnstableApi::class)
    fun enqueueDownload(
        context: Context,
        url: String,
        fileName: String?,
        mimeType: String?,
        userAgent: String?,
        cookies: String?,
        referer: String?
    ) {
        try {
            // Check for HLS/M3U8
            val isHls = url.contains(".m3u8") || mimeType?.contains("mpegurl") == true || mimeType?.contains("x-mpegURL") == true
            
            if (isHls && !url.startsWith("data:")) {
                // Use ExoPlayer DownloadService
                val downloadRequest = androidx.media3.exoplayer.offline.DownloadRequest.Builder(
                    url,
                    Uri.parse(url)
                ).setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8)
                 .setData(fileName?.toByteArray() ?: url.toByteArray()) // Store filename in data
                 .build()
                
                androidx.media3.exoplayer.offline.DownloadService.sendAddDownload(
                    context,
                    MediaDownloadService::class.java,
                    downloadRequest,
                    true // foreground
                )
                Toast.makeText(context, "Started HLS Download: ${fileName ?: "Video"}", Toast.LENGTH_SHORT).show()
                return
            }

            // Standard DownloadManager logic
            val uri = Uri.parse(url)
            val request = DownloadManager.Request(uri)
            
            // Set headers
            if (cookies != null) request.addRequestHeader("Cookie", cookies)
            if (userAgent != null) request.addRequestHeader("User-Agent", userAgent)
            if (referer != null) request.addRequestHeader("Referer", referer)

            // Determine filename if not provided
            var finalFileName = fileName
            if (finalFileName.isNullOrEmpty()) {
                 finalFileName = url.substringAfterLast("/", "download")
                 if (finalFileName.contains("?")) {
                     finalFileName = finalFileName.substringBefore("?")
                 }
            }

            // Set destination
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, finalFileName)
            
            // Set notification visibility
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            
            // Set mime type if available
            if (mimeType != null) {
                request.setMimeType(mimeType)
            } else {
                val extension = MimeTypeMap.getFileExtensionFromUrl(url)
                if (extension != null) {
                    val typedMime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                    if (typedMime != null) request.setMimeType(typedMime)
                }
            }
            
            request.setTitle(finalFileName)
            request.setDescription("Downloading file...")
            request.setAllowedOverMetered(true)
            request.setAllowedOverRoaming(true)

            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadManager.enqueue(request)
            
            Toast.makeText(context, "Download started: $finalFileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    fun formatFileSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format("%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
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
