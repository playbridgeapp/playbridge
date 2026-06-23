package com.playbridge.sender.downloads.spike

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * PHASE-0 SPIKE — does NOT ship.
 *
 * Proves the load-bearing assumption behind the download rewrite: that Media3
 * [Transformer] can **transmux** (stream-copy, no re-encode) an HLS source into a
 * single playable MP4 — across MPEG-TS, fMP4/CMAF, and AES-128-encrypted variants —
 * without pulling in FFmpeg.
 *
 * This is the `Muxer` seam the real `HlsStrategy` will sit behind. In production the
 * input [MediaItem] points at a **local** rewritten media playlist (file:// segment
 * paths, with #EXT-X-KEY preserved) plus a header-injecting DataSource.Factory; here
 * we feed remote test playlists directly, which exercises the identical Media3
 * demux → decrypt → mux pipeline. The only production delta is the segment source and
 * header injection (see README → "Mapping to the real strategy").
 */
@UnstableApi
sealed interface TransmuxResult {
    /** Transmux succeeded and produced a non-empty, probe-valid MP4. */
    data class Success(val outputFile: File, val durationMs: Long) : TransmuxResult

    /** Network/IO problem reaching the test stream — inconclusive, not a muxer failure. */
    data class Skipped(val reason: String) : TransmuxResult

    /** Transformer reported a real export/mux failure — the capability does NOT hold. */
    data class Failed(val reason: String, val errorCode: Int) : TransmuxResult
}

/** The seam: anything that can turn an HLS [MediaItem] into a finished MP4 file. */
@UnstableApi
interface Muxer {
    suspend fun transmux(input: MediaItem, outputFile: File): TransmuxResult
}

/**
 * Media3-backed [Muxer]. No FFmpeg. Default [Transformer] config performs a
 * stream-copy transmux when there are no effects and the output container (MP4)
 * supports the input codecs (H.264/H.265 + AAC) — the common HLS case.
 */
@UnstableApi
class Media3Muxer(private val context: Context) : Muxer {

    override suspend fun transmux(input: MediaItem, outputFile: File): TransmuxResult =
        suspendCancellableCoroutine { cont ->
            // Transformer must be built and started on a thread with a prepared Looper.
            Handler(Looper.getMainLooper()).post {
                val transformer = Transformer.Builder(context).build()

                transformer.addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, result: ExportResult) {
                        if (!cont.isActive) return
                        val duration = probeDurationMs(outputFile)
                        cont.resume(
                            if (outputFile.length() > 0 && duration > 0) {
                                TransmuxResult.Success(outputFile, duration)
                            } else {
                                TransmuxResult.Failed(
                                    "Export completed but output is empty/unreadable " +
                                        "(len=${outputFile.length()}, durationMs=$duration)",
                                    errorCode = -1,
                                )
                            }
                        )
                    }

                    override fun onError(
                        composition: Composition,
                        result: ExportResult,
                        exception: ExportException,
                    ) {
                        if (!cont.isActive) return
                        // Media3 IO/network error codes live in 2000..2999. Treat those as
                        // "couldn't reach the stream" rather than "transmux can't do this".
                        val isIo = exception.errorCode in 2000..2999
                        val msg = "${exception.errorCodeName}: ${exception.message}"
                        cont.resume(
                            if (isIo) TransmuxResult.Skipped(msg)
                            else TransmuxResult.Failed(msg, exception.errorCode)
                        )
                    }
                })

                // No effects, no MIME overrides → Transformer transmuxes (stream copy).
                val edited = EditedMediaItem.Builder(input).build()
                transformer.start(edited, outputFile.absolutePath)

                cont.invokeOnCancellation {
                    Handler(Looper.getMainLooper()).post { transformer.cancel() }
                }
            }
        }
}

/** True if the file contains at least one video and one audio track. */
fun hasVideoAndAudioTracks(file: File): Pair<Boolean, Boolean> {
    val extractor = MediaExtractor()
    var hasVideo = false
    var hasAudio = false
    try {
        extractor.setDataSource(file.absolutePath)
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString("mime") ?: continue
            if (mime.startsWith("video/")) hasVideo = true
            if (mime.startsWith("audio/")) hasAudio = true
        }
    } catch (_: Throwable) {
        // leave both false
    } finally {
        extractor.release()
    }
    return hasVideo to hasAudio
}

/** Container duration in ms, or 0 if unreadable. */
private fun probeDurationMs(file: File): Long {
    if (file.length() == 0L) return 0L
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    } catch (_: Throwable) {
        0L
    } finally {
        retriever.release()
    }
}
