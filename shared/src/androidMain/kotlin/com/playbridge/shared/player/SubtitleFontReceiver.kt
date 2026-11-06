package com.playbridge.shared.player

import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * Reassembles one custom subtitle font sent as `subtitle_font_begin`, ordered
 * `subtitle_font_chunk`s and `subtitle_font_end`, checks it, and hands it to [install]
 * (normally [SubtitleFontLibrary.add]). One transfer at a time; a new begin replaces an
 * unfinished one. Not thread-safe.
 */
class SubtitleFontReceiver(private val install: (ByteArray) -> SubtitleFontLibrary.AddResult) {
    sealed interface Result {
        /** [received] bytes are stored; send the next chunk. */
        data class Ack(val received: Int) : Result
        data class Installed(val family: String) : Result
        /** The transfer is over. [code] is one of the `subtitle_font_result` error codes. */
        data class Failed(val code: String) : Result
    }

    private class Transfer(val id: String, val size: Int, val sha256: String) {
        val bytes = ByteArrayOutputStream(size)
    }

    private var transfer: Transfer? = null

    fun begin(id: String, size: Int, sha256: String): Result {
        transfer = null
        if (size <= 0 || size > MAX_SUBTITLE_FONT_BYTES) return Result.Failed("too_large")
        transfer = Transfer(id, size, sha256.lowercase())
        return Result.Ack(0)
    }

    fun chunk(id: String, offset: Int, base64: String): Result {
        val current = transfer?.takeIf { it.id == id } ?: return Result.Failed("unknown_transfer")
        val data = runCatching { java.util.Base64.getDecoder().decode(base64) }.getOrNull()
        val received = current.bytes.size()
        if (data == null || offset != received || data.size > SUBTITLE_FONT_CHUNK_BYTES ||
            received + data.size > current.size
        ) {
            transfer = null
            return Result.Failed("invalid_chunk")
        }
        current.bytes.write(data)
        return Result.Ack(current.bytes.size())
    }

    fun end(id: String): Result {
        val current = transfer?.takeIf { it.id == id } ?: return Result.Failed("unknown_transfer")
        transfer = null
        val bytes = current.bytes.toByteArray()
        if (bytes.size != current.size || sha256(bytes) != current.sha256) return Result.Failed("checksum_mismatch")
        return when (val added = install(bytes)) {
            is SubtitleFontLibrary.AddResult.Added -> Result.Installed(added.family)
            is SubtitleFontLibrary.AddResult.Failed -> Result.Failed(added.code)
        }
    }

    companion object {
        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
