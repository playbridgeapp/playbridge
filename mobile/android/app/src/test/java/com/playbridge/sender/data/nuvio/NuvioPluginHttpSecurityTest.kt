package com.playbridge.sender.data.nuvio

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class NuvioPluginHttpSecurityTest {
    @Test(timeout = 8_000)
    fun cancellingCoroutineCancelsActiveCall() = runBlocking {
        ServerSocket(0).use { server ->
            val acceptThread = Thread {
                try {
                    server.accept().use { socket ->
                        val headers = "HTTP/1.1 200 OK\r\nContent-Length: 1000000\r\nConnection: close\r\n\r\n"
                        socket.getOutputStream().write(headers.toByteArray(Charsets.ISO_8859_1))
                        socket.getOutputStream().flush()
                        Thread.sleep(30_000)
                    }
                } catch (_: Exception) {
                    // closed by cancellation or the test
                }
            }
            acceptThread.isDaemon = true
            acceptThread.start()
            val started = System.nanoTime()
            try {
                withTimeout(1_500) {
                    permissiveHttp().pluginRequest(
                        requestJson("http://127.0.0.1:${server.localPort}/hang"),
                        gate("127.0.0.1"),
                        budget(),
                    )
                }
            } catch (_: Exception) {
                // Cancellation must abort the body read, not wait for the read timeout.
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue("call was not cancelled, elapsed ${elapsedMs}ms", elapsedMs < 4_000)
            acceptThread.join(1_000)
        }
    }

    @Test(timeout = 8_000)
    fun isolatedClientDropsCallerAuthAndCookies() = runBlocking {
        val seen = CopyOnWriteArrayList<String>()
        replyServer(seen, "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok").use { server ->
            val dirty = OkHttpClient.Builder()
                .addInterceptor(Interceptor { chain ->
                    chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer app-secret").build())
                })
                .cookieJar(object : okhttp3.CookieJar {
                    override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) = Unit
                    override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> =
                        listOf(
                            okhttp3.Cookie.Builder()
                                .name("secret")
                                .value("from-jar")
                                .domain("example.test")
                                .build()
                        )
                })
                .build()
            permissiveHttp(dirty).pluginRequest(
                requestJson("http://example.test:${server.port}/direct"),
                gate("example.test"),
                budget(),
            )
            val raw = seen.joinToString("\n")
            assertFalse(raw.contains("app-secret"))
            assertFalse(raw.contains("from-jar"))
            assertTrue(raw.contains("GET "))
        }
    }

    @Test(timeout = 8_000)
    fun crossOriginRedirectStripsCredentialsAndBody() = runBlocking {
        val secondSeen = CopyOnWriteArrayList<String>()
        replyServer(secondSeen, "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok").use { second ->
            val firstSeen = CopyOnWriteArrayList<String>()
            val redirect = "HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:${second.port}/next?token=super-secret-token\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            replyServer(firstSeen, redirect).use { first ->
                val response = permissiveHttp().pluginRequest(
                    """
                    {"url":"http://127.0.0.1:${first.port}/start","method":"POST","headers":{"Authorization":"Bearer user-secret","Cookie":"sid=user-secret","X-Api-Key":"user-secret","Accept":"text/plain"},"body":"credential-body"}
                    """.trimIndent(),
                    gate("127.0.0.1"),
                    budget(),
                )
                val forwarded = secondSeen.joinToString("\n")
                assertFalse(forwarded.contains("user-secret"))
                assertFalse(forwarded.contains("credential-body"))
                assertFalse(forwarded.contains("X-Api-Key"))
                assertFalse(response.contains("super-secret-token") && response.contains("\"error\""))
                assertTrue(forwarded.startsWith("GET ") || forwarded.contains("GET /next"))
            }
        }
    }

    @Test(timeout = 8_000)
    fun mixedPublicAndPrivateDnsIsBlockedBeforeConnect() = runBlocking {
        val connects = java.util.concurrent.atomic.AtomicInteger()
        val client = OkHttpClient.Builder().socketFactory(object : javax.net.SocketFactory() {
            override fun createSocket(): Socket {
                connects.incrementAndGet()
                throw java.io.IOException("unexpected connect")
            }
            override fun createSocket(host: String, port: Int) = createSocket()
            override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int) = createSocket()
            override fun createSocket(address: InetAddress, port: Int) = createSocket()
            override fun createSocket(address: InetAddress, port: Int, local: InetAddress, localPort: Int) = createSocket()
        }).build()
        val http = NuvioPluginHttp(
            client = client,
            resolver = NuvioHostResolver {
                listOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("10.1.2.3"))
            },
        )
        val response = http.pluginRequest(
            requestJson("http://cdn.example/video"),
            gate("cdn.example"),
            budget(),
        )
        assertTrue(response.contains("destination blocked"))
        assertEquals(0, connects.get())
    }

    @Test(timeout = 8_000)
    fun redirectToUnapprovedHostIsBlocked() = runBlocking {
        val blocked = mutableListOf<String>()
        val secondSeen = CopyOnWriteArrayList<String>()
        replyServer(secondSeen, "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok").use { second ->
            val redirect = "HTTP/1.1 302 Found\r\nLocation: http://evil.example:${second.port}/next\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            replyServer(CopyOnWriteArrayList(), redirect).use { first ->
                val response = permissiveHttp().pluginRequest(
                    requestJson("http://127.0.0.1:${first.port}/start"),
                    NuvioHostGate(setOf("127.0.0.1")) { blocked += it },
                    budget(),
                )
                assertTrue(blocked.contains("evil.example"))
                assertTrue(secondSeen.isEmpty())
                assertTrue(response.contains("host not approved"))
                assertFalse(response.contains("evil.example/") && response.contains("next"))
            }
        }
    }

    @Test(timeout = 8_000)
    fun oversizedResponseIsRejected() = runBlocking {
        val body = "OVERSIZE-MARKER-" + "x".repeat(64)
        val raw = "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
        replyServer(CopyOnWriteArrayList(), raw).use { server ->
            val response = permissiveHttp(maxPluginResponseBytes = 16).pluginRequest(
                requestJson("http://127.0.0.1:${server.port}/big"),
                gate("127.0.0.1"),
                budget(),
            )
            assertTrue(response.contains("response too large"))
            assertFalse(response.contains("OVERSIZE-MARKER"))
        }
    }

    @Test(timeout = 8_000)
    fun hungDnsLookupIsBoundedAndCancellable() = runBlocking {
        val http = NuvioPluginHttp(
            resolver = NuvioHostResolver {
                try {
                    Thread.sleep(60_000)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                emptyList()
            },
        )
        val started = System.nanoTime()
        val response = http.pluginRequest(
            requestJson("http://cdn.example/slow-dns"),
            gate("cdn.example"),
            budget(),
        )
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("DNS lookup was not bounded, elapsed ${elapsedMs}ms", elapsedMs < 4_000)
        assertTrue(response.contains("destination blocked"))
    }

    @Test(timeout = 8_000)
    fun installFetchRejectsNonSuccessStatus() = runBlocking {
        val raw = "HTTP/1.1 404 Not Found\r\nContent-Length: 11\r\nConnection: close\r\n\r\nsecret-body"
        replyServer(CopyOnWriteArrayList(), raw).use { server ->
            val fetched = permissiveHttp().fetchText(
                "http://example.test:${server.port}/manifest.json",
                budget(),
                maxBytes = 1024,
            )
            assertTrue(fetched.body == null)
            assertTrue(fetched.error == "repository request failed")
            assertFalse(fetched.error.orEmpty().contains("secret-body"))
        }
    }

    @Test(timeout = 8_000)
    fun unsafeHeaderNamesAndControlValuesAreDropped() = runBlocking {
        val seen = CopyOnWriteArrayList<String>()
        replyServer(seen, "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok").use { server ->
            val huge = "h".repeat(NuvioLimits.MAX_HEADER_VALUE_BYTES + 8)
            permissiveHttp().pluginRequest(
                """
                {"url":"http://127.0.0.1:${server.port}/hdr","method":"GET","headers":{"X-Ok":"fine\ttab","X-Evil":"bad\u0001value","Not A Token":"x","X-Big":"$huge"},"body":null}
                """.trimIndent(),
                gate("127.0.0.1"),
                budget(),
            )
            val raw = seen.joinToString("\n")
            assertTrue(raw.contains("fine\ttab") || raw.contains("fine\ttab".replace("\t", "\t")))
            assertTrue(raw.contains("X-Ok"))
            assertFalse(raw.contains("bad\u0001value"))
            assertFalse(raw.contains("Not A Token"))
            assertFalse(raw.contains(huge))
        }
    }

    private fun permissiveHttp(
        client: OkHttpClient = OkHttpClient(),
        maxPluginResponseBytes: Int = NuvioLimits.MAX_RESPONSE_BYTES,
    ): NuvioPluginHttp =
        NuvioPluginHttp(
            client = client,
            resolver = NuvioHostResolver { listOf(InetAddress.getByName("127.0.0.1")) },
            addressBlocked = { false },
            hostnameBlocked = { false },
            allowCleartextInstall = true,
            maxPluginResponseBytes = maxPluginResponseBytes,
        )

    private fun gate(vararg hosts: String) = NuvioHostGate(hosts.toSet()) {}

    private fun budget() = NuvioRequestBudget(4, kotlinx.coroutines.sync.Semaphore(4))

    private fun requestJson(url: String) = """{"url":"$url","method":"GET","headers":{},"body":null}"""

    private fun replyServer(seen: MutableList<String>, response: String): ServerHandle {
        val server = ServerSocket(0)
        val running = AtomicBoolean(true)
        val thread = Thread {
            while (running.get()) {
                val socket = try {
                    server.accept()
                } catch (_: Exception) {
                    break
                }
                Thread {
                    readRequest(socket, seen, response)
                }.apply { isDaemon = true }.start()
            }
        }
        thread.isDaemon = true
        thread.start()
        return ServerHandle(server.localPort, server, running, thread)
    }

    private fun readRequest(socket: Socket, seen: MutableList<String>, response: String) {
        socket.use { client ->
            val input = client.getInputStream()
            val buffer = ByteArray(8192)
            val count = input.read(buffer)
            if (count > 0) seen += String(buffer, 0, count, Charsets.ISO_8859_1)
            client.getOutputStream().write(response.toByteArray(Charsets.ISO_8859_1))
            client.getOutputStream().flush()
        }
    }

    private class ServerHandle(
        val port: Int,
        private val server: ServerSocket,
        private val running: AtomicBoolean,
        private val thread: Thread,
    ) : AutoCloseable {
        override fun close() {
            running.set(false)
            server.close()
            thread.join(1_000)
        }
    }
}
