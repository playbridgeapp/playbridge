package com.playbridge.sender.cast.proxy

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JniUpstreamRedirectTest {
    @Test fun checkedOriginsUseAuthenticatedGatewayWithoutResolvingOriginHost() {
        val reads = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val password = "A".repeat(43)
        val credential = "Basic " + java.util.Base64.getEncoder().encodeToString("playbridge:$password".toByteArray())
        server.createContext("/checked") { exchange ->
            assertEquals("checked-origin.invalid", exchange.requestURI.host)
            if (exchange.requestHeaders.getFirst("Proxy-Authorization") != credential) {
                exchange.responseHeaders.add("Proxy-Authenticate", "Basic realm=\"PlayBridge origin\"")
                exchange.sendResponseHeaders(407, -1)
            } else {
                assertEquals("original-secret", exchange.requestHeaders.getFirst("Authorization"))
                reads.incrementAndGet()
                val body = "checked".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.write(body)
            }
            exchange.close()
        }
        server.start()
        try {
            val gateway = JSONObject().put("host", "127.0.0.1").put("port", server.address.port)
                .put("username", "playbridge").put("password", password).toString()
            val response = JSONObject(JniUpstreamHttpClient.openChecked(
                "http://checked-origin.invalid:${server.address.port}/checked",
                "{\"Authorization\":\"original-secret\"}", gateway,
            ))
            assertTrue(response.toString(), response.getBoolean("ok"))
            assertEquals(1, reads.get())
            val handle = response.getLong("handle")
            assertEquals("checked", String(JniUpstreamHttpClient.read(handle, 100)!!))
            JniUpstreamHttpClient.close(handle)
            JniUpstreamHttpClient.close(handle)
            val invalid = JSONObject(JniUpstreamHttpClient.openChecked(
                "http://checked-origin.invalid:${server.address.port}/checked", "{}",
                JSONObject(gateway).put("host", "192.168.1.1").toString(),
            ))
            assertEquals(false, invalid.getBoolean("ok"))
            assertEquals(1, reads.get())
        } finally { server.stop(0) }
    }
    @Test fun hostReturnsRedirectWithoutContactingItsDestination() {
        val destinationReads = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/redirect.mp4") { exchange ->
            exchange.responseHeaders.add("Location", "/private")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/private") { exchange ->
            destinationReads.incrementAndGet()
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.start()
        try {
            val result = JSONObject(JniUpstreamHttpClient.open(
                "http://127.0.0.1:${server.address.port}/redirect.mp4", "{}",
            ))
            assertTrue(result.getBoolean("ok"))
            assertEquals(302, result.getInt("status"))
            assertEquals("/private", result.getJSONObject("headers").getString("location"))
            assertEquals(0, destinationReads.get())
            val handle = result.getLong("handle")
            JniUpstreamHttpClient.close(handle)
            JniUpstreamHttpClient.close(handle)
        } finally {
            server.stop(0)
        }
    }
}
