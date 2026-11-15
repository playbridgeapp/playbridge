package com.playbridge.sender.history

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkExpiryTest {
    private val saved = 1_700_000_000_000L // 2023-11-14T22:13:20Z
    private fun exp(url: String) = linkExpiresAtMs(url, saved)
    private fun jwt(payload: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        return "eyJhbGciOiJIUzI1NiJ9." + enc.encodeToString(payload.toByteArray()) + ".c2ln"
    }

    @Test fun plainSeconds() {
        assertEquals(1_700_003_600_000L, exp("https://cdn.example/v.mp4?expires=1700003600&sig=a"))
        assertEquals(1_700_003_600_000L, exp("https://cdn.example/v.mp4?Expires=1700003600&Signature=a&Key-Pair-Id=K"))
        assertEquals(1_700_003_600_000L, exp("https://cdn.example/v.mp4?validto=1700003600"))
        assertEquals(1_700_003_600_000L, exp("https://cdn.example/v.mp4?e=1700003600"))
        assertEquals(1_700_003_600_000L, exp("https://cdn.example/v.mp4?x=1&exp=1700003600#frag"))
    }

    @Test fun plainMilliseconds() {
        assertEquals(1_700_003_600_000L, exp("https://cdn.example/v.mp4?expires=1700003600000"))
    }

    @Test fun implausibleValuesReturnNull() {
        assertNull(exp("https://cdn.example/v.mp4?e=5"))
        assertNull(exp("https://cdn.example/v.mp4?exp=8080"))
        assertNull(exp("https://cdn.example/v.mp4?exp=1000000000")) // 2001
        assertNull(exp("https://cdn.example/v.mp4?expires=4102444800")) // year 2100
        assertNull(exp("https://cdn.example/v.mp4?expires=abc"))
        assertNull(exp("https://cdn.example/v.mp4?expires=1700003600000000")) // microseconds
        assertNull(exp("https://cdn.example/v.mp4?ttl=3600"))
        assertNull(exp("https://cdn.example/v.mp4"))
    }

    @Test fun awsSigV4() {
        assertEquals(
            1_699_999_200_000L + 3_600_000L,
            exp("https://b.s3.amazonaws.com/k.mp4?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Date=20231114T220000Z&X-Amz-Expires=3600&X-Amz-Signature=ab"),
        )
        assertNull(exp("https://b.s3.amazonaws.com/k.mp4?X-Amz-Date=20231114T220000Z&X-Amz-Expires=99999999"))
        assertNull(exp("https://b.s3.amazonaws.com/k.mp4?X-Amz-Date=garbage&X-Amz-Expires=3600"))
    }

    @Test fun googleSigned() {
        assertEquals(
            1_699_999_200_000L + 7_200_000L,
            exp("https://storage.googleapis.com/b/k.mp4?X-Goog-Date=20231114T220000Z&X-Goog-Expires=7200&X-Goog-Signature=ab"),
        )
    }

    @Test fun azureSas() {
        assertEquals(1_700_003_600_000L, exp("https://a.blob.core.windows.net/c/v.mp4?sv=2021&se=2023-11-14T23%3A13%3A20Z&sr=b&sig=x"))
        assertEquals(1_700_006_400_000L, exp("https://a.blob.core.windows.net/c/v.mp4?se=2023-11-15&sig=x")) // date-only: midnight UTC
        assertNull(exp("https://example.com/v.mp4?se=2023-11-14T23:13:20Z")) // no sig: ambiguous
    }

    @Test fun akamaiTokens() {
        val token = "st=1700000000~exp=1700003600~acl=/*~hmac=abc"
        assertEquals(1_700_003_600_000L, exp("https://a.akamaized.net/v/master.m3u8?hdnts=$token"))
        assertEquals(1_700_003_600_000L, exp("https://a.akamaized.net/v/master.m3u8?hdnea=" + java.net.URLEncoder.encode(token, "UTF-8")))
        assertEquals(1_700_003_600_000L, exp("https://a.example/v/master.m3u8?token=exp=1700003600~hmac=abc"))
        assertEquals(1_700_003_600_000L, exp("https://a.akamaized.net/hdnts=exp=1700003600~acl=/*~hmac=abc/v/master.m3u8"))
        assertNull(exp("https://a.example/v.m3u8?token=exp=12~hmac=abc"))
    }

    @Test fun jwtInQueryAndPath() {
        val t = jwt("""{"sub":"u","exp":1700003600}""")
        assertEquals(1_700_003_600_000L, exp("https://x.example/v.m3u8?auth=$t"))
        assertEquals(1_700_003_600_000L, exp("https://x.example/$t/v/master.mpd"))
        assertNull(exp("https://x.example/v.m3u8?auth=" + jwt("""{"exp":12}""")))
        assertNull(exp("https://x.example/v.m3u8?auth=" + jwt("""{"sub":"u"}""")))
        assertNull(exp("https://x.example/v.m3u8?auth=eyJnotjwt.x.y"))
    }

    @Test fun pathSegmentParam() {
        assertEquals(1_700_003_600_000L, exp("https://x.example/expires=1700003600/v/index.m3u8"))
    }

    @Test fun earliestWins() {
        assertEquals(
            1_700_003_600_000L,
            exp("https://x.example/v.mp4?expires=1700007200&hdnts=exp=1700003600~hmac=a"),
        )
    }

    @Test fun malformedInputIsSafe() {
        assertNull(exp(""))
        assertNull(exp("not a url ?? && == ##"))
        assertNull(exp("https://x.example/%zz?expires=%"))
    }
}
