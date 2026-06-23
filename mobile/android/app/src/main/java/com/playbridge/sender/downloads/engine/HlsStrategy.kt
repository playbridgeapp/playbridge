package com.playbridge.sender.downloads.engine

import android.content.Context
import android.util.Log
import com.playbridge.sender.cast.HlsParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * HLS download: fetch every segment ourselves (retry / resume / pause control + header-gated
 * auth), decrypt AES-128 if present, concatenate into one progressive file, then stream-copy
 * remux to MP4 via the [Muxer] seam (platform `MediaMuxer`, no FFmpeg). See
 * DOWNLOAD_REWRITE_PLAN §4.
 *
 * Scope (v1): VOD playlists, TS or fMP4, optional AES-128, single muxed variant. NOT yet:
 * live, #EXT-X-BYTERANGE, SAMPLE-AES, separate-audio-group muxing — these throw a clear error.
 */
class HlsStrategy(
    private val context: Context,
    private val client: OkHttpClient,
    private val muxer: Muxer,
) : DownloadStrategy {

    override val kind = DownloadKind.HLS

    override suspend fun download(
        request: DownloadRequest,
        downloadDir: File,
        controller: DownloadController,
        onProgress: (Progress) -> Unit,
    ): File = coroutineScope {
        val headers = request.headers

        val variantUrl = resolveVariantUrl(request, headers)
        val media = HlsMediaPlaylistParser.fetch(client, variantUrl, headers)

        if (media.isLive) throw IOException("Live HLS not supported yet")
        if (media.hasByteRanges) throw IOException("Byte-range HLS segments not supported yet")
        if (media.segments.isEmpty()) throw IOException("No segments in media playlist")
        media.key?.let {
            if (!it.method.equals("AES-128", ignoreCase = true)) {
                throw IOException("Unsupported HLS encryption: ${it.method}")
            }
        }

        val segDownloader = SegmentDownloader(client, headers, controller)
        val segDir = File(downloadDir, "segments").apply { mkdirs() }
        val fmp4 = media.initSegmentUrl != null
        val ext = if (fmp4) "m4s" else "ts"

        // 1. Init segment + AES key (once).
        var initLocal: File? = null
        media.initSegmentUrl?.let { url ->
            val f = File(segDir, "init.mp4")
            segDownloader.download(url, f, "HLS-init")
            initLocal = f
        }
        var keyBytes: ByteArray? = null
        media.key?.let { k ->
            val f = File(segDir, "enc.key")
            segDownloader.download(k.uri, f, "HLS-key")
            keyBytes = f.readBytes()
        }

        // 2. Download segments in parallel (resume skips ones already present).
        val total = media.segments.size
        val done = AtomicInteger(0)
        val bytes = AtomicLong(0L)
        val dispatcher = Dispatchers.IO.limitedParallelism(PARALLELISM)
        val jobs = mutableListOf<Job>()
        media.segments.forEachIndexed { index, segUrl ->
            val target = File(segDir, "seg_%05d.%s".format(index, ext))
            jobs += launch(dispatcher) {
                val written = segDownloader.download(segUrl, target, "HLS-seg")
                val n = done.incrementAndGet()
                val totalBytes = bytes.addAndGet(written)
                val estTotal = if (n > 0) totalBytes / n * total else -1L
                onProgress(Progress(totalBytes, estTotal, "Segments $n/$total"))
            }
        }
        jobs.joinAll()

        if (controller.isCancelRequested()) throw CancellationException("cancelled")
        if (controller.isPauseRequested()) throw DownloadPausedException()

        // 3. Concatenate (decrypting AES-128 per segment) into one progressive container.
        onProgress(Progress(bytes.get(), bytes.get(), "Merging…"))
        val mergedName = if (fmp4) "merged.mp4" else "merged.ts"
        val merged = File(downloadDir, mergedName)
        concatenate(merged, segDir, total, ext, initLocal, media.key, keyBytes)

        // 4. Remux to MP4 (platform muxer, stream copy).
        val output = File(downloadDir, "output.mp4")
        muxer.remux(merged, output)
        merged.delete()
        Log.d(TAG, "HLS complete: ${output.length()} bytes")
        output
    }

    private suspend fun resolveVariantUrl(request: DownloadRequest, headers: Map<String, String>): String {
        request.selectedVariantUrl?.let { return it }
        val playlist = HlsParser.parsePlaylist(request.url, headers)
        return playlist.videoQualities.firstOrNull()?.url ?: request.url
    }

    /**
     * Streams segments (and the fMP4 init segment first) into one file. When AES-128 is in
     * play each media segment is decrypted with the key and its IV (explicit, or the segment
     * media-sequence number as a big-endian IV per the HLS spec).
     */
    private suspend fun concatenate(
        out: File,
        segDir: File,
        total: Int,
        ext: String,
        initLocal: File?,
        key: HlsMediaPlaylist.EncryptionKey?,
        keyBytes: ByteArray?,
    ) = withContext(Dispatchers.IO) {
        val explicitIv = key?.iv?.let { hexToBytes(it) }
        FileOutputStream(out).use { os ->
            initLocal?.let { os.write(it.readBytes()) } // init segments aren't AES-128 encrypted
            for (i in 0 until total) {
                val seg = File(segDir, "seg_%05d.%s".format(i, ext))
                val raw = seg.readBytes()
                val data = if (keyBytes != null) {
                    val iv = explicitIv ?: sequenceIv(i)
                    decryptAes128(raw, keyBytes, iv)
                } else raw
                os.write(data)
            }
        }
    }

    private fun decryptAes128(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    /** HLS implicit IV = 128-bit big-endian segment media-sequence number. */
    private fun sequenceIv(sequence: Int): ByteArray {
        val iv = ByteArray(16)
        iv[12] = (sequence ushr 24).toByte()
        iv[13] = (sequence ushr 16).toByte()
        iv[14] = (sequence ushr 8).toByte()
        iv[15] = sequence.toByte()
        return iv
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.removePrefix("0x").removePrefix("0X")
        return ByteArray(clean.length / 2) {
            ((Character.digit(clean[it * 2], 16) shl 4) + Character.digit(clean[it * 2 + 1], 16)).toByte()
        }
    }

    private companion object {
        const val PARALLELISM = 4
        const val TAG = "HlsStrategy"
    }
}
