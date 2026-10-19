package com.playbridge.sender.downloads.notifications

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.playbridge.sender.data.downloads.DownloadEntity
import com.playbridge.sender.downloads.engine.DownloadStatus
import java.io.File

internal data class DownloadNotificationOpen(val id: String?, val openFile: Boolean)

/** Called from an Activity, never through a notification broadcast trampoline. */
internal fun openCompletedDownload(context: Context, entity: DownloadEntity) {
    if (entity.status != DownloadStatus.DONE.name) return
    val stored = entity.filePath ?: return
    try {
        val parsed = stored.toUri()
        val uri = when (parsed.scheme) {
            "content" -> parsed
            "file" -> FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", File(parsed.path ?: return),
            )
            else -> return
        }
        // Prefer the published container MIME (HLS input MIME is not the final MP4 MIME).
        val mime = context.contentResolver.getType(uri) ?: entity.mimeType ?: "application/octet-stream"
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    } catch (_: Exception) {
        // The calling Activity already selected Downloads: missing files/viewers stay there.
    }
}
