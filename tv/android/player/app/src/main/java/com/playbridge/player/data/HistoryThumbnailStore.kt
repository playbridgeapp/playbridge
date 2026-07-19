package com.playbridge.player.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Small, private artwork cache for history entries that arrive without poster metadata.
 * Images live under noBackupFilesDir, are never uploaded, and are capped to the same number
 * of entries as playback history.
 */
class HistoryThumbnailStore(context: Context) {
    private val directory = File(context.noBackupFilesDir, DIRECTORY_NAME)

    fun existingUrl(historyId: String): String? = fileFor(historyId)
        .takeIf(File::isFile)
        ?.also { it.setLastModified(System.currentTimeMillis()) }
        ?.let { Uri.fromFile(it).toString() }

    fun save(historyId: String, bitmap: Bitmap): String? = runCatching {
        directory.mkdirs()
        val destination = fileFor(historyId)
        val temporary = File(directory, "${destination.name}.tmp")
        FileOutputStream(temporary).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output))
        }
        if (destination.exists()) destination.delete()
        check(temporary.renameTo(destination))
        destination.setLastModified(System.currentTimeMillis())
        trim()
        Uri.fromFile(destination).toString()
    }.getOrNull()

    fun remove(historyId: String) {
        fileFor(historyId).delete()
    }

    fun removeAll(historyIds: Collection<String>) {
        historyIds.forEach(::remove)
    }

    private fun trim() {
        directory.listFiles()
            ?.filter { it.isFile && it.extension == "jpg" }
            ?.sortedByDescending(File::lastModified)
            ?.drop(MAX_IMAGES)
            ?.forEach(File::delete)
    }

    private fun fileFor(historyId: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(historyId.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return File(directory, "$digest.jpg")
    }

    companion object {
        const val WIDTH = 320
        const val HEIGHT = 180
        private const val DIRECTORY_NAME = "history_thumbnails"
        private const val JPEG_QUALITY = 72
        private const val MAX_IMAGES = 50
    }
}
