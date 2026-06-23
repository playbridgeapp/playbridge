package com.playbridge.sender.downloads.engine

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.net.toUri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Copies a finished download out of the app's private temp dir into the public Downloads
 * collection. Returns the destination URI string (stored as `filePath`).
 *
 * Android 10+ writes through a [android.os.ParcelFileDescriptor], NOT
 * `ContentResolver.openOutputStream()`. This matches the lesson baked into the legacy
 * `HlsExporter`: on some devices (notably Samsung) `openOutputStream` to a MediaStore
 * Downloads item blocks indefinitely on the ContentProvider IPC pipe. Writing the backing
 * fd directly avoids that hang.
 *
 * Blocking IO — call from Dispatchers.IO.
 */
object MediaStorePublisher {

    fun publish(context: Context, source: File, displayName: String, mimeType: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val pending = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, pending)
                ?: throw IOException("MediaStore insert returned null for $displayName")

            resolver.openFileDescriptor(uri, "w")?.use { pfd ->
                FileOutputStream(pfd.fileDescriptor).use { out ->
                    source.inputStream().use { it.copyTo(out) }
                    out.flush()
                }
            } ?: throw IOException("Could not open file descriptor for $uri")

            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null,
            )
            return uri.toString()
        }

        @Suppress("DEPRECATION")
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloads.exists()) downloads.mkdirs()
        val dest = File(downloads, displayName)
        source.copyTo(dest, overwrite = true)
        return Uri.fromFile(dest).toString()
    }

    fun toUri(stored: String): Uri = stored.toUri()
}
