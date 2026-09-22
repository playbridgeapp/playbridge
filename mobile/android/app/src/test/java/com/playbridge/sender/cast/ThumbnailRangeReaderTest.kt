package com.playbridge.sender.cast

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailRangeReaderTest {
    @Test fun seeksToTailAndCachesWithoutDownloadingMovie() {
        val requests = mutableListOf<String>()
        val length = 310_653_314L
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/video") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            requests.add(range)
            val (start, requestedEnd) = range.removePrefix("bytes=").split('-').map(String::toLong)
            val end = minOf(requestedEnd, length - 1)
            val bytes = ByteArray((end - start + 1).toInt()) { ((start + it) % 251).toByte() }
            exchange.responseHeaders.add("Content-Range", "bytes $start-$end/$length")
            exchange.sendResponseHeaders(206, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            ThumbnailRangeReader("http://127.0.0.1:${server.address.port}/video", mapOf("Range" to "bytes=99-")).use { reader ->
                assertEquals(length, reader.size())
                val result = ByteArray(64)
                assertEquals(64, reader.readAt(309_754_350, result, 0, 64))
                assertArrayEquals(ByteArray(64) { ((309_754_350L + it) % 251).toByte() }, result)
                reader.readAt(309_754_350, result, 0, 64)
                assertEquals(2, requests.size)
                assertEquals(-1, reader.readAt(length, result, 0, 64))
                assertEquals(1, reader.readAt(length - 1, result, 0, 1))
                assertTrue(requests.last().endsWith("-${length - 1}"))
            }
        } finally { server.stop(0) }
    }

    @Test fun rejectsIgnoredRangeInsteadOfReadingWrongOffset() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/video") { exchange ->
            exchange.sendResponseHeaders(200, 1)
            exchange.responseBody.use { it.write(0) }
        }
        server.start()
        try {
            ThumbnailRangeReader("http://127.0.0.1:${server.address.port}/video", emptyMap()).use { reader ->
                try {
                    reader.readAt(1_000_000, ByteArray(1), 0, 1)
                    throw AssertionError("Expected ignored range failure")
                } catch (_: IOException) { }
            }
        } finally { server.stop(0) }
    }

    @Test fun darkFrameRetriesAreLimitedAndRespectShortClips() {
        assertEquals(listOf(1_000_000L, 5_000_000L, 10_000_000L), thumbnailSeekTimesUs(60_000))
        assertEquals(listOf(400_000L), thumbnailSeekTimesUs(500))
        assertTrue(isNearlyBlackThumbnail(32, 32) { _, _ -> 0xff000000.toInt() })
        assertFalse(isNearlyBlackThumbnail(32, 32) { x, _ -> if (x < 16) 0xff808080.toInt() else 0 })
    }

    @Test fun exhaustedBudgetsStopBeforeNetworkAccess() {
        for (reader in listOf(
            ThumbnailRangeReader("http://127.0.0.1:1", emptyMap(), maxBytes = 0),
            ThumbnailRangeReader("http://127.0.0.1:1", emptyMap(), timeoutMs = 0),
        )) {
            reader.use {
                try {
                    it.readAt(0, ByteArray(1), 0, 1)
                    throw AssertionError("Expected budget failure")
                } catch (e: IOException) {
                    assertTrue(e.message.orEmpty().contains("budget exhausted"))
                }
            }
        }
    }
}
