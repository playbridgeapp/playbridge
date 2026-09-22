package com.playbridge.sender.cast

import java.io.Closeable
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Seekable, bounded HTTP input for MediaMetadataRetriever; never downloads an entire large movie. */
internal class ThumbnailRangeReader(
    private val url: String,
    private val headers: Map<String, String>,
    private val maxBytes: Int = 12 * 1024 * 1024,
    private val timeoutMs: Long = 12_000,
    private val report: (String) -> Unit = {},
) : Closeable {
    private val deadline = System.nanoTime() + timeoutMs * 1_000_000
    private val blocks = HashMap<Long, ByteArray>()
    private var length = -1L
    private var downloaded = 0
    @Volatile private var closed = false
    @Volatile private var connection: HttpURLConnection? = null

    private fun checkBudget() {
        if (closed || System.nanoTime() >= deadline) {
            report("stop=time_or_closed totalBytes=$downloaded")
            throw IOException("thumbnail time budget exhausted")
        }
    }

    @Synchronized
    fun size(): Long {
        if (length < 0) block(0)
        return length
    }

    @Synchronized
    fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        checkBudget()
        require(position >= 0 && offset >= 0 && size >= 0 && offset <= buffer.size - size)
        if (size == 0) return 0
        if (length >= 0 && position >= length) return -1
        val start = position / BLOCK_SIZE * BLOCK_SIZE
        val bytes = block(start)
        val within = (position - start).toInt()
        if (within >= bytes.size) return -1
        val count = minOf(size, bytes.size - within)
        bytes.copyInto(buffer, offset, within, within + count)
        return count
    }

    private fun block(start: Long): ByteArray {
        checkBudget()
        blocks[start]?.let { return it }
        if (downloaded >= maxBytes) {
            report("stop=byte_budget totalBytes=$downloaded")
            throw IOException("thumbnail byte budget exhausted")
        }
        val wanted = minOf(
            BLOCK_SIZE.toLong(),
            (maxBytes - downloaded).toLong(),
            if (length >= 0) length - start else Long.MAX_VALUE,
        ).toInt()
        val conn = URL(url).openConnection() as HttpURLConnection
        connection = conn
        try {
            checkBudget()
            val remaining = ((deadline - System.nanoTime()) / 1_000_000).coerceIn(1, 4_000).toInt()
            conn.connectTimeout = remaining
            conn.readTimeout = remaining
            headers.forEach { (key, value) ->
                if (key.lowercase() !in SKIP_HEADERS) conn.setRequestProperty(key, value)
            }
            conn.setRequestProperty("Accept-Encoding", "identity")
            conn.setRequestProperty("Range", "bytes=$start-${start + wanted - 1}")
            val status = conn.responseCode
            report("requestOffset=$start status=$status")
            var expected: Int? = null
            when (status) {
                206 -> {
                    val match = RANGE.matchEntire(conn.getHeaderField("Content-Range").orEmpty())
                        ?: throw IOException("invalid thumbnail Content-Range")
                    val first = match.groupValues[1].toLong()
                    val last = match.groupValues[2].toLong()
                    val total = match.groupValues[3].toLong()
                    if (first != start || last < first || last - first >= wanted || total <= last ||
                        (length >= 0 && length != total)) throw IOException("inconsistent thumbnail range")
                    length = total
                    expected = (last - first + 1).toInt()
                }
                200 -> {
                    if (start != 0L) throw IOException("server ignored thumbnail range")
                    length = conn.getHeaderFieldLong("Content-Length", -1)
                }
                else -> throw IOException("thumbnail HTTP $status")
            }
            val bytes = ByteArray(expected ?: wanted)
            var count = 0
            conn.inputStream.use { input ->
                while (count < bytes.size) {
                    checkBudget()
                    val read = input.read(bytes, count, bytes.size - count)
                    if (read < 0) break
                    count += read
                    downloaded += read
                }
            }
            if (expected != null && count != expected) throw IOException("truncated thumbnail range")
            if (status == 200 && count < wanted && length < 0) length = count.toLong()
            report("offset=$start bytes=$count totalBytes=$downloaded status=$status size=$length")
            return bytes.copyOf(count).also { blocks[start] = it }
        } finally {
            conn.disconnect()
            connection = null
        }
    }

    override fun close() {
        closed = true
        connection?.disconnect()
    }

    private companion object {
        const val BLOCK_SIZE = 256 * 1024
        val RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+)")
        val SKIP_HEADERS = setOf("range", "accept-encoding", "host", "connection", "content-length")
    }
}
