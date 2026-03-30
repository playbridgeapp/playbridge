package com.playbridge.browser

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AdBlockerTest {

    private lateinit var adBlocker: AdBlocker

    @Before
    fun setUp() {
        adBlocker = AdBlocker(null as Any as android.content.Context)
    }

    @Test
    fun testExtractHost() {
        assertEquals("example.com", adBlocker.extractHost("https://example.com/path/to/page?query=1"))
        assertEquals("example.com", adBlocker.extractHost("http://example.com:8080/path"))
        assertEquals("sub.example.com", adBlocker.extractHost("https://sub.example.com/"))
        assertEquals("example.com", adBlocker.extractHost("example.com/path"))
        assertEquals("", adBlocker.extractHost(""))
    }

    @Test
    fun testBuiltInBlocking() {
        // Test a few known built-in domains
        assertTrue(adBlocker.shouldBlock("https://doubleclick.net/ad"))
        assertTrue(adBlocker.shouldBlock("http://googleads.g.doubleclick.net/pagead"))
        assertTrue(adBlocker.shouldBlock("https://google-analytics.com/collect"))

        // Test legitimate domain
        assertFalse(adBlocker.shouldBlock("https://google.com/search"))
        assertFalse(adBlocker.shouldBlock("https://github.com/playbridge"))
    }

    @Test
    fun testParseDomainAnchor() {
        // ||example.com^ matches example.com and its subdomains
        adBlocker.parseLine("||example.com^")

        assertTrue(adBlocker.shouldBlock("https://example.com/ad.js"))
        assertTrue(adBlocker.shouldBlock("http://sub.example.com/ad.js"))
        assertFalse(adBlocker.shouldBlock("https://notexample.com/ad.js"))
        assertFalse(adBlocker.shouldBlock("https://example.com.org/ad.js"))
    }

    @Test
    fun testParseException() {
        adBlocker.parseLine("||example.com^")
        adBlocker.parseLine("@@||example.com/allowed.js")

        assertTrue(adBlocker.shouldBlock("https://example.com/ad.js"))
        assertFalse(adBlocker.shouldBlock("https://example.com/allowed.js"))
    }

    @Test
    fun testParseOptionsResourceTypes() {
        adBlocker.parseLine("||example.com^\$script")

        assertTrue(adBlocker.shouldBlock("https://example.com/script.js", resourceType = AdBlocker.TYPE_SCRIPT))
        assertFalse(adBlocker.shouldBlock("https://example.com/image.png", resourceType = AdBlocker.TYPE_IMAGE))
    }

    @Test
    fun testParseOptionsThirdParty() {
        adBlocker.parseLine("||adserver.com^\$third-party")

        // Blocked when loaded from different host
        assertTrue(adBlocker.shouldBlock("https://adserver.com/ad.js", pageUrl = "https://example.com/index.html"))

        // Not blocked when loaded from same host
        assertFalse(adBlocker.shouldBlock("https://adserver.com/ad.js", pageUrl = "https://adserver.com/index.html"))
    }

    @Test
    fun testParseOptionsDomainRestriction() {
        adBlocker.parseLine("||ads.com^\$domain=example.com")

        // Blocked on example.com
        assertTrue(adBlocker.shouldBlock("https://ads.com/ad.js", pageUrl = "https://example.com/"))
        assertTrue(adBlocker.shouldBlock("https://ads.com/ad.js", pageUrl = "https://sub.example.com/"))

        // Not blocked on other domains
        assertFalse(adBlocker.shouldBlock("https://ads.com/ad.js", pageUrl = "https://other.com/"))
    }

    @Test
    fun testParseOptionsDomainExclusion() {
        adBlocker.parseLine("||ads.com^\$domain=~example.com")

        // Not blocked on example.com
        assertFalse(adBlocker.shouldBlock("https://ads.com/ad.js", pageUrl = "https://example.com/"))

        // Blocked elsewhere
        assertTrue(adBlocker.shouldBlock("https://ads.com/ad.js", pageUrl = "https://other.com/"))
    }

    @Test
    fun testIsDomainBlockedRecursion() {
        adBlocker.parseLine("||doubleclick.net^")

        assertTrue(adBlocker.isDomainBlocked("doubleclick.net"))
        assertTrue(adBlocker.isDomainBlocked("ads.doubleclick.net"))
        assertTrue(adBlocker.isDomainBlocked("more.ads.doubleclick.net"))
        assertFalse(adBlocker.isDomainBlocked("example.net"))
    }

    @Test
    fun testPatternToRegex() {
        val regex = adBlocker.patternToRegex("||example.com^/ads/*")
        assertNotNull(regex)
        assertTrue(regex!!.containsMatchIn("https://example.com/ads/script.js"))
        assertTrue(regex.containsMatchIn("http://sub.example.com/ads/test"))
        assertFalse(regex.containsMatchIn("https://example.com/not-ads/test"))
    }
}
