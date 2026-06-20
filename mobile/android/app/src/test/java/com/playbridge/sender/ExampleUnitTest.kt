package com.playbridge.sender

import org.junit.Test
import org.junit.Assert.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import com.playbridge.sender.connection.LinkLocalDns

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun testLinkLocalDnsFormatting() {
        val rawIp = "fe80::d704:283a:2eca:e313%lo0"
        
        // Encode IP into a safe hostname
        val hostname = LinkLocalDns.encodeIpv6ToHost(rawIp)
        assertEquals("fe80000000000000d704283a2ecae313-lo0.local-ipv6", hostname)
        
        val url = "https://$hostname:8766/"
        
        // OkHttp HttpUrl should parse this perfectly
        val httpUrl = url.toHttpUrl()
        assertEquals(hostname, httpUrl.host)
        
        // Decode hostname back to IPv6 string
        val decoded = LinkLocalDns.decodeHostToIpv6String(hostname)
        val expectedNormalizedIp = "fe80:0000:0000:0000:d704:283a:2eca:e313%lo0"
        assertEquals(expectedNormalizedIp, decoded)
    }
}