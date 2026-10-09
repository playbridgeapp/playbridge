package com.playbridge.sender.cast.dlna

/** Response metadata shared by GET and HEAD for ContentResolver-backed files. */
internal data class LocalFileResponse(
    val code: Int,
    val reason: String,
    val start: Long,
    val length: Long,
    val contentRange: String? = null,
) {
    companion object {
        fun forRequest(range: String?, total: Long): LocalFileResponse {
            // A provider may not know its length. Serve it as an unbounded 200 stream.
            if (range == null || total < 0) return LocalFileResponse(200, "OK", 0, total)
            val parsed = parseByteRange(range, total)
                ?: return LocalFileResponse(416, "Range Not Satisfiable", 0, 0, "bytes */$total")
            val (start, end) = parsed
            return LocalFileResponse(206, "Partial Content", start, end - start + 1, "bytes $start-$end/$total")
        }

        /** Mirrors Rust parse_byte_range, including its unsigned 64-bit numeric bounds. */
        private fun parseByteRange(value: String, total: Long): Pair<Long, Long>? {
            if (!value.startsWith("bytes=") || total == 0L) return null
            val raw = value.removePrefix("bytes=")
            if (',' in raw) return null
            val dash = raw.indexOf('-')
            if (dash < 0) return null
            val startText = raw.substring(0, dash)
            val endText = raw.substring(dash + 1)
            val size = total.toULong()
            if (startText.isEmpty()) {
                val suffix = parseUnsigned(endText) ?: return null
                if (suffix == 0uL) return null
                return (size - minOf(suffix, size)).toLong() to total - 1
            }
            val start = parseUnsigned(startText) ?: return null
            if (start >= size) return null
            val end = if (endText.isEmpty()) size - 1uL
                else minOf(parseUnsigned(endText) ?: return null, size - 1uL)
            return if (start <= end) start.toLong() to end.toLong() else null
        }

        private fun parseUnsigned(value: String): ULong? {
            val digits = value.removePrefix("+")
            if (digits.isEmpty() || digits.any { it !in '0'..'9' }) return null
            return digits.toULongOrNull()
        }
    }
}
