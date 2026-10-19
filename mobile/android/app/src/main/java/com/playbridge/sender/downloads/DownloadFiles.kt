package com.playbridge.sender.downloads

import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.util.LruCache
import android.util.Size
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** What the Downloads list shows about a saved file, loaded off the main thread. */
data class SavedFileInfo(
    val exists: Boolean,
    val thumbnail: Bitmap? = null,
    val durationMs: Long = 0L,
    val mimeType: String? = null,
    val displayName: String? = null,
)

/** Result of deleting a saved file. */
sealed interface FileDeleteResult {
    data object Deleted : FileDeleteResult
    /** Android 11+: the file isn't ours any more (e.g. after reinstall) — ask the user. */
    data class NeedsConsent(val request: IntentSender) : FileDeleteResult
    data object Failed : FileDeleteResult
}

/**
 * Android-side helpers for finished downloads. A download's `filePath` is a MediaStore
 * `content://` URI on Android 10+, or a `file://` URI in public Downloads before that.
 */
object DownloadFiles {
    private const val TAG = "DownloadFiles"
    private val THUMB_SIZE = Size(480, 270)
    private val cache = LruCache<String, SavedFileInfo>(64)

    fun uriOf(context: Context, stored: String): Uri {
        val parsed = stored.toUri()
        if (parsed.scheme != "file") return parsed
        val file = File(parsed.path.orEmpty())
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    fun cached(stored: String): SavedFileInfo? = cache.get(stored)

    fun invalidate(stored: String) {
        cache.remove(stored)
    }

    suspend fun load(context: Context, stored: String): SavedFileInfo = withContext(Dispatchers.IO) {
        cache.get(stored)?.let { return@withContext it }
        val info = runCatching { readInfo(context, stored) }
            .getOrElse { SavedFileInfo(exists = false) }
        cache.put(stored, info)
        info
    }

    private fun readInfo(context: Context, stored: String): SavedFileInfo {
        val parsed = stored.toUri()
        val resolver = context.contentResolver
        if (parsed.scheme == "file") {
            val file = File(parsed.path.orEmpty())
            if (!file.exists()) return SavedFileInfo(exists = false)
            val (thumb, duration) = frameAndDuration(context, Uri.fromFile(file))
            return SavedFileInfo(true, thumb, duration, mimeFromName(file.name), file.name)
        }
        var displayName: String? = null
        var durationMs = 0L
        val projection = buildList {
            add(OpenableColumns.DISPLAY_NAME)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(MediaStore.MediaColumns.DURATION)
        }.toTypedArray()
        val found = resolver.query(parsed, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use false
            displayName = cursor.getString(0)
            if (projection.size > 1 && !cursor.isNull(1)) durationMs = cursor.getLong(1)
            true
        } ?: false
        if (!found) return SavedFileInfo(exists = false)
        val mime = resolver.getType(parsed)
        var thumb: Bitmap? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { resolver.loadThumbnail(parsed, THUMB_SIZE, null) }.getOrNull()
        } else null
        if (thumb == null || durationMs <= 0) {
            val (frame, duration) = frameAndDuration(context, parsed, wantFrame = thumb == null)
            thumb = thumb ?: frame
            if (durationMs <= 0) durationMs = duration
        }
        return SavedFileInfo(true, thumb, durationMs, mime, displayName)
    }

    private fun frameAndDuration(context: Context, uri: Uri, wantFrame: Boolean = true): Pair<Bitmap?, Long> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val frame = if (!wantFrame) null else {
                // 10% in avoids black intro frames; scaled to keep memory small.
                val at = if (duration > 0) duration * 100L else 1_000_000L
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    retriever.getScaledFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, THUMB_SIZE.width, THUMB_SIZE.height)
                } else {
                    retriever.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }
            }
            frame to duration
        } catch (e: Exception) {
            null to 0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun mimeFromName(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "ts" -> "video/mp2t"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        else -> null
    }

    /** Deletes the saved file. Missing files count as deleted. */
    suspend fun delete(context: Context, stored: String): FileDeleteResult = withContext(Dispatchers.IO) {
        invalidate(stored)
        val parsed = stored.toUri()
        if (parsed.scheme == "file") {
            val file = File(parsed.path.orEmpty())
            return@withContext if (!file.exists() || file.delete()) FileDeleteResult.Deleted else FileDeleteResult.Failed
        }
        try {
            context.contentResolver.delete(parsed, null, null)
            FileDeleteResult.Deleted
        } catch (e: SecurityException) {
            consentRequest(context, listOf(parsed), e)?.let { FileDeleteResult.NeedsConsent(it) } ?: FileDeleteResult.Failed
        } catch (e: Exception) {
            Log.w(TAG, "Delete failed (${e.javaClass.simpleName})")
            FileDeleteResult.Failed
        }
    }

    /** One system prompt to delete several files we no longer own (Android 11+). */
    fun bulkDeleteRequest(context: Context, stored: List<String>): IntentSender? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val uris = stored.map { it.toUri() }.filter { it.scheme == "content" }
        if (uris.isEmpty()) return null
        return runCatching { MediaStore.createDeleteRequest(context.contentResolver, uris).intentSender }.getOrNull()
    }

    private fun consentRequest(context: Context, uris: List<Uri>, error: SecurityException): IntentSender? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
            runCatching { MediaStore.createDeleteRequest(context.contentResolver, uris).intentSender }.getOrNull()
        Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && error is RecoverableSecurityException ->
            error.userAction.actionIntent.intentSender
        else -> null
    }

    /** Best-effort rename of the saved file itself; the list title changes regardless. */
    suspend fun rename(context: Context, stored: String, newTitle: String): Boolean = withContext(Dispatchers.IO) {
        val parsed = stored.toUri()
        if (parsed.scheme != "content" || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext false
        val current = cache.get(stored)?.displayName ?: runCatching {
            context.contentResolver.query(parsed, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
        val name = renamedDisplayName(newTitle, current) ?: return@withContext false
        val ok = runCatching {
            context.contentResolver.update(
                parsed,
                ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name) },
                null,
                null,
            ) > 0
        }.getOrDefault(false)
        if (ok) invalidate(stored)
        ok
    }

    fun shareIntent(context: Context, stored: String, mime: String?, title: String): Intent {
        val uri = uriOf(context, stored)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime ?: "video/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Share")
    }

    /** Opens the system Downloads folder (where saved files live). */
    fun showInFilesIntent(): Intent =
        Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun freeBytes(): Long? = runCatching {
        StatFs(Environment.getExternalStorageDirectory().path).availableBytes
    }.getOrNull()
}
