package com.playbridge.sender.connection

import android.app.Application
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Size
import androidx.core.net.toUri
import com.playbridge.sender.cast.MediaKind
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.withPermit
import playbridge.PlayPayload

/** Publishes phone files (and song artwork) through the local proxy as TV payloads. */
class LocalCastPackager(private val app: Application) {
    /** Prepares several items a few at a time, keeping their order; null if any fails. */
    suspend fun packageItems(items: List<LocalCastItem>, itemIds: List<String?> = emptyList()): List<PlayPayload>? = try {
        kotlinx.coroutines.coroutineScope {
            val gate = kotlinx.coroutines.sync.Semaphore(PARALLELISM)
            items.mapIndexed { index, item ->
                async { gate.withPermit { packageItem(item, itemIds.getOrNull(index)) } }
            }.awaitAll()
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        android.util.Log.e(TAG, "castLocalQueue packaging failed: ${e.message}")
        null
    }

    /** Publishes one phone file (and audio artwork) through the local proxy. */
    suspend fun packageItem(item: LocalCastItem, itemId: String? = null): PlayPayload {
        val routeService = com.playbridge.sender.cast.proxy.StreamRouteService(app)
        val packaged = routeService.packageForCast(
            media = com.playbridge.sender.cast.proxy.CastableMedia(
                url = item.uri,
                contentType = item.mime,
                title = item.title,
                localUri = item.uri.toUri(),
            ),
            mode = com.playbridge.sender.cast.proxy.StreamRouteMode.VIA_PHONE,
        )
        val artwork = if (item.kind == MediaKind.AUDIO) {
            packageLocalAudioArtwork(mediaUri = item.uri, routeService = routeService)
        } else {
            null
        }
        val visualMetadata = if (
            item.kind == MediaKind.AUDIO && listOf(item.artist, item.album, artwork).any { !it.isNullOrBlank() }
        ) {
            playbridge.VisualMetadata(
                title = item.title.orEmpty(),
                artist = item.artist,
                album = item.album,
                track_number = item.trackNumber,
                artwork_url = artwork,
            )
        } else {
            null
        }
        return PlayPayload(
            url = packaged.url,
            title = item.title ?: "Phone file",
            content_type = packaged.contentType ?: item.mime,
            media_kind = item.kind.wireValue,
            visual_metadata = visualMetadata,
            subtitles = item.subtitleUrls,
            display_duration_ms = item.displayDurationMs,
            item_id = itemId,
        )
    }

    private suspend fun packageLocalAudioArtwork(
        mediaUri: String,
        routeService: com.playbridge.sender.cast.proxy.StreamRouteService,
    ): String? {
        suspend fun packageUri(uri: android.net.Uri, contentType: String?): String? =
            runCatching {
                routeService.packageForCast(
                    media = com.playbridge.sender.cast.proxy.CastableMedia(
                        url = uri.toString(),
                        contentType = contentType ?: "image/jpeg",
                        localUri = uri,
                    ),
                    mode = com.playbridge.sender.cast.proxy.StreamRouteMode.VIA_PHONE,
                ).url
            }.getOrNull()

        // Prefer artwork embedded in this exact file. MediaStore album IDs can group
        // unrelated downloads under one synthetic album, which would send the same cover.
        val extracted = kotlinx.coroutines.withContext(Dispatchers.IO) {
            val directory = File(app.cacheDir, "cast-artwork").apply { mkdirs() }
            val stem = Integer.toUnsignedString(mediaUri.hashCode())
            val retriever = MediaMetadataRetriever()
            val embedded = try {
                retriever.setDataSource(app, mediaUri.toUri())
                retriever.embeddedPicture
            } catch (_: Exception) {
                null
            } finally {
                retriever.release()
            }
            if (embedded != null) {
                val extension = when {
                    embedded.size >= 8 && embedded[0] == 0x89.toByte() && embedded[1] == 0x50.toByte() -> "png"
                    embedded.size >= 12 && String(embedded, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"
                    else -> "jpg"
                }
                File(directory, "$stem.$extension").apply { writeBytes(embedded) }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // This is the same per-item thumbnail path used by the Phone Files UI.
                runCatching {
                    val thumbnail = app.contentResolver.loadThumbnail(
                        mediaUri.toUri(),
                        Size(1_024, 1_024),
                        null,
                    )
                    File(directory, "$stem.jpg").apply {
                        outputStream().use { output ->
                            thumbnail.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, output)
                        }
                    }
                }.getOrNull()
            } else {
                null
            }
        }
        if (extracted != null) {
            val contentType = when (extracted.extension.lowercase()) {
                "png" -> "image/png"
                "webp" -> "image/webp"
                else -> "image/jpeg"
            }
            packageUri(extracted.toUri(), contentType)?.let { return it }
        }

        return null
    }

    private companion object {
        const val TAG = "LocalCastPackager"
        /** Phone files prepared at once when sending a list. */
        const val PARALLELISM = 6
    }
}
