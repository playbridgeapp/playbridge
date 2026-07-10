package com.playbridge.sender.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MimeTypes

/**
 * Detects the real container of **local** media (content:// / file://) from a
 * small header read. MediaStore / SAF often label MPEG-TS as `video/mp4` when
 * the filename ends in `.mp4`; ExoPlayer then uses only the MP4 extractor and
 * hangs.
 */
object LocalContainerSniffer {
    private const val TAG = "LocalContainerSniffer"
    private const val SNIFF_BYTES = 2048
    private const val TS_PACKET = 188
    private const val TS_SYNC: Byte = 0x47

    fun isLocalUri(url: String): Boolean {
        val lower = url.lowercase()
        return lower.startsWith("content:") || lower.startsWith("file:")
    }

    /**
     * Prefer a byte-level sniff for local URIs; fall back to [claimedMime]
     * (MediaStore / SAF) when the header is inconclusive.
     */
    fun resolveMime(context: Context, url: String, claimedMime: String?): String? {
        if (!isLocalUri(url)) return claimedMime
        val sniffed = sniff(context, url) ?: return claimedMime
        if (claimedMime != null &&
            !claimedMime.equals(sniffed, ignoreCase = true) &&
            !mimeCompatible(claimedMime, sniffed)
        ) {
            Log.i(TAG, "override $claimedMime → $sniffed")
        }
        return sniffed
    }

    fun sniff(context: Context, url: String): String? {
        return try {
            val uri = Uri.parse(url)
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buf = ByteArray(SNIFF_BYTES)
                var total = 0
                while (total < buf.size) {
                    val n = input.read(buf, total, buf.size - total)
                    if (n <= 0) break
                    total += n
                }
                if (total <= 0) return null
                detect(buf, total)
            }
        } catch (e: Exception) {
            Log.w(TAG, "sniff failed: ${e.message}")
            null
        }
    }

    /** Pure header detection — unit-tested. */
    fun detect(bytes: ByteArray, length: Int = bytes.size): String? {
        if (length <= 0) return null
        val n = length.coerceAtMost(bytes.size)

        val asText = runCatching {
            String(bytes, 0, minOf(n, 16), Charsets.UTF_8)
        }.getOrNull()
        if (asText?.startsWith("#EXTM3U") == true) return MimeTypes.APPLICATION_M3U8

        // MPEG-TS before MP4 heuristics (misnamed .mp4 with 0x47 packets).
        if (looksLikeMpegTs(bytes, n)) return MimeTypes.VIDEO_MP2T

        if (n >= 8 &&
            bytes[4] == 'f'.code.toByte() &&
            bytes[5] == 't'.code.toByte() &&
            bytes[6] == 'y'.code.toByte() &&
            bytes[7] == 'p'.code.toByte()
        ) {
            return MimeTypes.VIDEO_MP4
        }

        if (n >= 4 &&
            bytes[0] == 0x1A.toByte() &&
            bytes[1] == 0x45.toByte() &&
            bytes[2] == 0xDF.toByte() &&
            bytes[3] == 0xA3.toByte()
        ) {
            return MimeTypes.VIDEO_MATROSKA
        }

        if (n >= 4 &&
            bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte()
        ) {
            return MimeTypes.VIDEO_AVI
        }

        if (n >= 3 &&
            bytes[0] == 'F'.code.toByte() &&
            bytes[1] == 'L'.code.toByte() &&
            bytes[2] == 'V'.code.toByte()
        ) {
            return MimeTypes.VIDEO_FLV
        }

        return null
    }

    fun looksLikeMpegTs(bytes: ByteArray, length: Int = bytes.size): Boolean {
        val n = length.coerceAtMost(bytes.size)
        if (n < TS_PACKET * 2) return false
        val maxOffset = minOf(TS_PACKET, n - TS_PACKET * 2)
        for (offset in 0 until maxOffset) {
            if (bytes[offset] != TS_SYNC) continue
            var packets = 0
            var pos = offset
            while (pos < n && bytes[pos] == TS_SYNC) {
                packets++
                pos += TS_PACKET
                if (pos >= n) break
                if (pos < n && bytes[pos] != TS_SYNC) break
            }
            if (packets >= 3) return true
        }
        return false
    }

    private fun mimeCompatible(claimed: String, sniffed: String): Boolean {
        if (claimed.contains("mp2t", ignoreCase = true) &&
            sniffed.contains("mp2t", ignoreCase = true)
        ) {
            return true
        }
        if (claimed.contains("mp4", ignoreCase = true) && sniffed == MimeTypes.VIDEO_MP4) {
            return true
        }
        return claimed.equals(sniffed, ignoreCase = true)
    }
}
