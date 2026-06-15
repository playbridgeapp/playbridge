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
    /** File size in bytes (0 when unknown). */
    val sizeBytes: Long = 0L,
    /** Last-modified time, epoch seconds (0 when unknown). */
    val dateModified: Long = 0L,
    /** Date added to MediaStore, epoch seconds — drives the natural "Unsorted" order. */
    val dateAdded: Long = 0L,
    /** Containing folder (bucket) display name, null when unavailable. */
    val bucketName: String? = null,
    /** Containing folder (bucket) id, null when unavailable. */
    val bucketId: Long? = null,
)

/** Enumerates on-device media (MediaStore) for the Phone Files screen. */
object PhoneMediaStore {

    suspend fun query(context: Context): List<PhoneMediaItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<PhoneMediaItem>()
        collect(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, isAudio = false, items)
        collect(context, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, isAudio = true, items)
        // Return in natural (date-added desc) order; the UI applies the chosen sort so
        // the "Unsorted" option stays meaningful.
        items
    }

    private fun collect(context: Context, collection: Uri, isAudio: Boolean, out: MutableList<PhoneMediaItem>) {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.BUCKET_ID,
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
                // Columns below are not present on all OS versions — resolve defensively.
                val durCol = c.getColumnIndex(MediaStore.MediaColumns.DURATION)
                val mimeCol = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                val sizeCol = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val modifiedCol = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                val addedCol = c.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                val bucketNameCol = c.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                val bucketIdCol = c.getColumnIndex(MediaStore.MediaColumns.BUCKET_ID)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    out.add(
                        PhoneMediaItem(
                            uri = ContentUris.withAppendedId(collection, id),
                            title = c.getString(nameCol) ?: "Unknown",
                            durationMs = if (durCol >= 0) c.getLong(durCol) else 0L,
                            mimeType = if (mimeCol >= 0) c.getString(mimeCol) else null,
                            isAudio = isAudio,
                            sizeBytes = if (sizeCol >= 0) c.getLong(sizeCol) else 0L,
                            dateModified = if (modifiedCol >= 0) c.getLong(modifiedCol) else 0L,
                            dateAdded = if (addedCol >= 0) c.getLong(addedCol) else 0L,
                            bucketName = if (bucketNameCol >= 0) c.getString(bucketNameCol) else null,
                            bucketId = if (bucketIdCol >= 0 && !c.isNull(bucketIdCol)) c.getLong(bucketIdCol) else null,
                        ),
                    )
                }
            }
        }
    }
}
