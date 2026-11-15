package com.playbridge.sender.history

import android.content.Context
import android.graphics.Bitmap
import com.playbridge.sender.browser.BridgedAppStore
import com.playbridge.sender.cast.VideoDetector
import com.playbridge.sender.data.history.CommandHistoryDao
import com.playbridge.sender.data.history.CommandHistoryStamp
import com.playbridge.sender.data.settings.SettingsRepository
import okhttp3.OkHttpClient
import java.io.File

internal class RoomLegacyHistory(private val dao: CommandHistoryDao) : LegacyHistory {
    override suspend fun stamps(): List<CommandHistoryStamp> = dao.idsAndTimestamps()
    override suspend fun delete(id: Long) = dao.deleteById(id)
    override suspend fun deleteAll() = dao.clear()
}

internal class SettingsHistorySource(settings: SettingsRepository) : CastHistorySettingsSource {
    override val settings = settings.castHistory
}

/** Reads the installed bridged apps fresh each time, so an install or removal applies at once. */
internal class InstalledBridgedApps(context: Context) : BridgedOriginPolicy {
    private val prefs = context.applicationContext.getSharedPreferences("bridged_apps", Context.MODE_PRIVATE)

    override fun isBridged(pageUrl: String): Boolean {
        val origin = BridgedAppStore.originFor(pageUrl) ?: return false
        return runCatching {
            BridgedAppStore.decodeInstalled(prefs.getString("installed", "[]") ?: "[]")
        }.getOrDefault(emptyList()).any { it.origin == origin }
    }
}

internal class DetectorPageOrigins : PageOriginResolver {
    override fun originOf(mediaUrl: String): String? = VideoDetector.originUrlFor(mediaUrl)
}

/** Copies the picture the cast sheet already cached; never downloads anything. */
internal class FilePreviewStore(
    context: Context,
    private val cached: (String) -> Bitmap? = VideoDetector::getCachedThumbnail,
) : PreviewStore {
    private val dir = File(context.applicationContext.filesDir, "cast_history_previews")

    override fun save(id: String, key: String?): String? {
        if (key == null) return null
        val bitmap = cached(key) ?: return null
        return runCatching {
            dir.mkdirs()
            val scaled = if (bitmap.width > MAX_WIDTH) {
                Bitmap.createScaledBitmap(bitmap, MAX_WIDTH, (bitmap.height * MAX_WIDTH / bitmap.width).coerceAtLeast(1), true)
            } else bitmap
            val file = File(dir, "$id.jpg")
            file.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            file.absolutePath
        }.getOrNull()
    }

    override fun delete(id: String) { File(dir, "$id.jpg").delete() }

    override fun retainOnly(ids: Set<String>) {
        dir.listFiles()?.filter { it.name.removeSuffix(".jpg") !in ids }?.forEach { it.delete() }
    }

    override fun clear() { dir.listFiles()?.forEach { it.delete() } }

    override fun sizeBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    private companion object { const val MAX_WIDTH = 480 }
}

/**
 * Checks a remembered link with the shared [checkLink]: the app's network policy, saved headers sent
 * to the original origin only, and the app's shared client. Private-origin grants are not saved with
 * a cast, so a LAN-only link answers "couldn't check".
 */
internal class PolicyLinkChecker(private val client: OkHttpClient) : LinkChecker {
    override suspend fun check(url: String, headers: Map<String, String>): LinkCheckResult =
        checkLink(client, url, headers)
}
