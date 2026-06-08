package com.playbridge.sender.cast

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A video or audio file on the device, castable via the local proxy. */
data class PhoneMediaItem(
    val uri: Uri,
    val title: String,
    val durationMs: Long,
    val mimeType: String?,
    val isAudio: Boolean,
)

/** Enumerates on-device media (MediaStore) for the Phone Files screen. */
object PhoneMediaStore {

    suspend fun query(context: Context): List<PhoneMediaItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<PhoneMediaItem>()
        collect(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, isAudio = false, items)
        collect(context, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, isAudio = true, items)
        items.sortedBy { it.title.lowercase() }
    }

    private fun collect(context: Context, collection: Uri, isAudio: Boolean, out: MutableList<PhoneMediaItem>) {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.MIME_TYPE,
        )
        runCatching {
            context.contentResolver.query(
                collection,
                projection,
                null,
                null,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC",
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val durCol = c.getColumnIndex(MediaStore.MediaColumns.DURATION) // not present on all OS versions
                val mimeCol = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    out.add(
                        PhoneMediaItem(
                            uri = ContentUris.withAppendedId(collection, id),
                            title = c.getString(nameCol) ?: "Unknown",
                            durationMs = if (durCol >= 0) c.getLong(durCol) else 0L,
                            mimeType = if (mimeCol >= 0) c.getString(mimeCol) else null,
                            isAudio = isAudio,
                        ),
                    )
                }
            }
        }
    }
}
