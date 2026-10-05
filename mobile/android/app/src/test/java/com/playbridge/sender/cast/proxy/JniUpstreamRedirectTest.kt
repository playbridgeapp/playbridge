package com.playbridge.sender.cast.proxy

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JniUpstreamRedirectTest {
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
