package com.playbridge.sender.cast.proxy

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.InputStream
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

class RemoteProxyClientTest {
    private val media = CastableMedia("https://media.example/video.mp4", headers = mapOf("Authorization" to "test-auth"))

    @Test fun rejectsBaseUserinfoQueryFragmentMissingHostAndNonHttpSchemes() {
        listOf("https://user:pass@proxy.example", "https://user@proxy.example", "https://@proxy.example",
            "https://proxy.example?token=test", "https://proxy.example?", "https://proxy.example#part",
            "https://proxy.example#", "http:///", "http:///proxy.example", "file:///proxy", "/proxy").forEach {
            rejects { RemoteProxyClient.validatedUrl(it, base = true) }
        }
        assertEquals("https://proxy.example/api", RemoteProxyClient.validatedUrl("https://proxy.example/api", true).toString())
    }

    @Test fun rejectsReturnedUserinfoMissingHostAndNonHttpSchemes() {
        listOf("https://user:pass@media.example/file", "https://user@media.example/file", "https://@media.example/file",
            "http:///", "http:///media.example", "file:///file", "ftp://media.example/file", "/file", "").forEach {
            rejects { RemoteProxyClient.validatedUrl(it, base = false) }
        }
        assertEquals("https://media.example/file?signature=test#part",
            RemoteProxyClient.validatedUrl("https://media.example/file?signature=test#part", false).toString())
    }

    @Test fun registrationRejectsRedirectsWithoutContactingDestinationEvenWithInjectedRedirectClient() {
        withServer { server, base ->
            val destinationReads = AtomicInteger()
            server.createContext("/destination") { exchange ->
                destinationReads.incrementAndGet()
                respond(exchange, "{}")
            }
            server.createContext("/register") { exchange ->
                val status = exchange.requestURI.rawQuery.substringAfter("token=").toInt()
                exchange.responseHeaders.add("Location", "$base/destination")
                exchange.sendResponseHeaders(status, -1)
                exchange.close()
            }
            val client = OkHttpClient.Builder().followRedirects(true).followSslRedirects(true).build()
            listOf(301, 302, 303, 307, 308).forEach { status ->
                rejects { RemoteProxyClient.register(client, media, StreamProxySettings(base, status.toString())) }
            }
            assertEquals(0, destinationReads.get())
        }
    }

    @Test fun validatesProxyAndEncryptedUrlsFromActualRegistrationResponse() {
        withServer { server, base ->
            server.createContext("/register") { exchange ->
                val key = exchange.requestURI.rawQuery.substringAfter("token=")
                respond(exchange, JSONObject().put(key, "https://user:pass@media.example/file").toString())
            }
            listOf("proxy_url", "encrypted_url").forEach { key ->
                rejects { RemoteProxyClient.register(OkHttpClient(), media, StreamProxySettings(base, key)) }
            }
        }
    }

    @Test fun acceptsEncryptedFallbackAndEncodesPasswordAndBasePath() {
        withServer { server, base ->
            server.createContext("/api/register") { exchange ->
                assertEquals("token=a%2Bb%26c", exchange.requestURI.rawQuery)
                assertEquals("POST", exchange.requestMethod)
                val body = JSONObject(exchange.requestBody.bufferedReader().readText())
                assertEquals(media.url, body.getString("url"))
                assertTrue(body.getJSONObject("headers").has("Authorization"))
                respond(exchange, JSONObject().put("proxy_url", "").put("encrypted_url", "$base/stream").toString())
            }
            assertEquals(PackagedMedia("$base/stream", null, null),
                RemoteProxyClient.register(OkHttpClient(), media, StreamProxySettings(" $base/api/ ", "a+b&c")))
        }
    }

    @Test fun responseLimitStopsWhileReadingAndAcceptsExactlyTheBudget() {
        var bytesRead = 0
        val input = object : InputStream() {
            override fun read(): Int { bytesRead++; return 'x'.code }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                bytesRead += length
                buffer.fill('x'.code.toByte(), offset, offset + length)
                return length
            }
        }
        rejects { RemoteProxyClient.readResponse(input) }
        assertEquals(RemoteProxyClient.MAX_RESPONSE_BYTES + 1, bytesRead)
        val exact = ByteArray(RemoteProxyClient.MAX_RESPONSE_BYTES)
        assertEquals(exact.size, RemoteProxyClient.readResponse(exact.inputStream()).size)
    }

    @Test fun registrationRejectsOversizedChunkedResponseAndInvalidJson() {
        withServer { server, base ->
            server.createContext("/register") { exchange ->
                val body = if (exchange.requestURI.rawQuery.endsWith("large"))
                    " ".repeat(RemoteProxyClient.MAX_RESPONSE_BYTES + 1) else "invalid json"
                exchange.sendResponseHeaders(200, 0) // Chunked: no Content-Length shortcut.
                exchange.responseBody.use { it.write(body.toByteArray()) }
                exchange.close()
            }
            listOf("large", "invalid").forEach { value ->
                rejects { RemoteProxyClient.register(OkHttpClient(), media, StreamProxySettings(base, value)) }
            }
        }
    }

    private fun rejects(block: () -> Any?) {
        try {
            block()
            fail("Expected StreamRouteException")
        } catch (_: StreamRouteException) {
            // Errors intentionally contain no supplied URL or credentials.
        }
    }

    private fun respond(exchange: HttpExchange, body: String) {
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    private fun withServer(block: (HttpServer, String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        try { block(server, "http://127.0.0.1:${server.address.port}") }
        finally { server.stop(0) }
    }
}
