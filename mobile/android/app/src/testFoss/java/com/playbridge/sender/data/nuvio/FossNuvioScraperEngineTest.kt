package com.playbridge.sender.data.nuvio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FossNuvioScraperEngineTest {

    private fun testRequest(
        code: String,
        scraperName: String = "test-scraper",
        settingsJson: String = "{}",
        fetch: suspend (String) -> String = { "{}" },
    ) = NuvioEvalRequest(
        scraperName = scraperName,
        scraperCode = code,
        tmdbId = "12345",
        nuvioType = "movie",
        season = null,
        episode = null,
        settingsJson = settingsJson,
        fetch = fetch,
    )

    @Test(timeout = 5_000)
    fun infiniteLoopInterruptedByEvaluationTimeout() = runBlocking {
        val engine = FossNuvioScraperEngine(
            evaluationTimeoutMs = 400L,
            overallBudgetMs = 3_000L,
        )

        val infiniteLoopCode = """
            module.exports = {
                getStreams: function() {
                    while (true) {}
                }
            };
        """.trimIndent()

        val outcome = engine.getStreams(testRequest(infiniteLoopCode))
        assertTrue("Streams must be empty on timeout", outcome.streams.isEmpty())
        assertTrue("Should report execution failure or timeout", outcome.warnings.isNotEmpty())
        assertTrue(
            "Warning message should mention timeout/failure",
            outcome.warnings.any { it.contains("timed out", ignoreCase = true) || it.contains("failed", ignoreCase = true) }
        )
    }

    @Test(timeout = 5_000)
    fun cancellationInterruptsEvaluationAndRethrows() = runBlocking {
        val engine = FossNuvioScraperEngine(
            evaluationTimeoutMs = 10_000L,
            overallBudgetMs = 10_000L,
        )

        val longRunningCode = """
            module.exports = {
                getStreams: async function() {
                    while (true) {}
                }
            };
        """.trimIndent()

        var caughtCancellation = false
        val job = launch(Dispatchers.Default) {
            try {
                engine.getStreams(testRequest(longRunningCode))
                fail("Expected CancellationException to be rethrown")
            } catch (e: CancellationException) {
                caughtCancellation = true
                throw e
            }
        }

        delay(150)
        job.cancelAndJoin()
        assertTrue("CancellationException must be propagated, not swallowed", caughtCancellation)
    }

    @Test(timeout = 5_000)
    fun freshRuntimePerEvaluationEnsuresIsolation() = runBlocking {
        val engine = FossNuvioScraperEngine(
            evaluationTimeoutMs = 3_000L,
            overallBudgetMs = 5_000L,
        )

        val scraper1 = """
            globalThis.__leaked_secret = "sensitive_data_123";
            module.exports = {
                getStreams: function() {
                    return [{ name: "S1", title: "Stream 1", url: "https://example.com/1.mp4" }];
                }
            };
        """.trimIndent()

        val outcome1 = engine.getStreams(testRequest(scraper1, scraperName = "scraper-1"))
        assertEquals(1, outcome1.streams.size)
        assertEquals("https://example.com/1.mp4", outcome1.streams[0].url)

        val scraper2 = """
            module.exports = {
                getStreams: function() {
                    if (typeof globalThis.__leaked_secret !== "undefined") {
                        return [{ name: "LEAK", title: globalThis.__leaked_secret, url: "https://example.com/leak.mp4" }];
                    }
                    return [{ name: "S2", title: "Clean", url: "https://example.com/2.mp4" }];
                }
            };
        """.trimIndent()

        val outcome2 = engine.getStreams(testRequest(scraper2, scraperName = "scraper-2"))
        assertEquals(1, outcome2.streams.size)
        assertEquals("https://example.com/2.mp4", outcome2.streams[0].url)
        assertEquals("Clean", outcome2.streams[0].title)
    }

    @Test(timeout = 5_000)
    fun outputExceedingMaxBytesIsRejected() = runBlocking {
        val engine = FossNuvioScraperEngine(
            evaluationTimeoutMs = 3_000L,
            overallBudgetMs = 5_000L,
        )

        val hugeOutputCode = """
            module.exports = {
                getStreams: function() {
                    var bigTitle = "A".repeat(600 * 1024);
                    return [{ name: "Huge", title: bigTitle, url: "https://example.com/huge.mp4" }];
                }
            };
        """.trimIndent()

        val outcome = engine.getStreams(testRequest(hugeOutputCode))
        assertTrue("Streams must be empty when output exceeds size limit", outcome.streams.isEmpty())
        assertTrue("Must emit size limit warning", outcome.warnings.any { it.contains("size limit", ignoreCase = true) })
    }

    @Test(timeout = 5_000)
    fun requireCryptoJsAesEncryptionAndDecryption() = runBlocking {
        val engine = FossNuvioScraperEngine()
        val code = """
            var CryptoJS = require('crypto-js');
            var secret = "HelloPlayBridge";
            var key = CryptoJS.enc.Utf8.parse("01234567890123456789012345678901");
            var options = { iv: CryptoJS.enc.Utf8.parse("0123456789012345") };
            var encrypted = CryptoJS.AES.encrypt(secret, key, options);
            var decrypted = CryptoJS.AES.decrypt(encrypted, key, options).toString(CryptoJS.enc.Utf8);
            module.exports = {
                getStreams: function() {
                    return [{ name: "AES", title: decrypted, url: "https://example.com/video.mp4" }];
                }
            };
        """.trimIndent()

        val outcome = engine.getStreams(testRequest(code))
        assertEquals(1, outcome.streams.size)
        assertEquals("HelloPlayBridge", outcome.streams[0].title)
    }

    @Test(timeout = 5_000)
    fun requireCheerioHtmlExtraction() = runBlocking {
        val engine = FossNuvioScraperEngine()
        val code = """
            var cheerio = require('cheerio');
            var html = '<div class="content"><a href="https://example.com/play.m3u8">Stream Link</a></div>';
            var ${'$'} = cheerio.load(html);
            var href = ${'$'}('div.content a').attr('href');
            var text = ${'$'}('div.content a').text();
            module.exports = {
                getStreams: function() {
                    return [{ name: text, title: "Title", url: href }];
                }
            };
        """.trimIndent()

        val outcome = engine.getStreams(testRequest(code))
        assertEquals(1, outcome.streams.size)
        assertEquals("https://example.com/play.m3u8", outcome.streams[0].url)
        assertEquals("Stream Link", outcome.streams[0].name)
    }

    @Test(timeout = 5_000)
    fun cryptoBridgeHugeRandomRejected() = runBlocking {
        val engine = FossNuvioScraperEngine()
        val code = """
            module.exports = {
                getStreams: function() {
                    try {
                        __crypto_get_random_values_hex(70 * 1024);
                        return [{ name: "Failed", title: "Should not reach", url: "https://example.com/fail.mp4" }];
                    } catch (e) {
                        return [{ name: "Rejected", title: "LimitEnforced", url: "https://example.com/ok.mp4" }];
                    }
                }
            };
        """.trimIndent()

        val outcome = engine.getStreams(testRequest(code))
        assertEquals(1, outcome.streams.size)
        assertEquals("Rejected", outcome.streams[0].name)
    }

    @Test(timeout = 5_000)
    fun cryptoBridgeHighPbkdf2IterationsRejected() = runBlocking {
        val engine = FossNuvioScraperEngine()
        val code = """
            module.exports = {
                getStreams: function() {
                    try {
                        __crypto_pbkdf2_hex("70617373", "73616c74", 20000, 256, "SHA256");
                        return [{ name: "Failed", title: "Should not reach", url: "https://example.com/fail.mp4" }];
                    } catch (e) {
                        return [{ name: "Rejected", title: "IterationsEnforced", url: "https://example.com/ok.mp4" }];
                    }
                }
            };
        """.trimIndent()

        val outcome = engine.getStreams(testRequest(code))
        assertEquals(1, outcome.streams.size)
        assertEquals("Rejected", outcome.streams[0].name)
    }

    @Test(timeout = 5_000)
    fun domBridgeRejectsMatchesRegexPseudo() = runBlocking {
        val engine = FossNuvioScraperEngine()
        val code = """
            var cheerio = require('cheerio');
            var ${'$'} = cheerio.load('<div><span>1</span></div>');
            module.exports = {
                getStreams: function() {
                    try {
                        ${'$'}('div:matches(^[a-z]+$)');
                        return [{ name: "Failed", title: "Should not reach", url: "https://example.com/fail.mp4" }];
                    } catch (e) {
                        return [{ name: "Rejected", title: "ReDoSProtected", url: "https://example.com/ok.mp4" }];
                    }
                }
            };
        """.trimIndent()

        val outcome = engine.getStreams(testRequest(code))
        assertEquals(1, outcome.streams.size)
        assertEquals("Rejected", outcome.streams[0].name)
    }

    @Test(timeout = 5_000)
    fun domBridgeEnforcesDocumentLimit() = runBlocking {
        val engine = FossNuvioScraperEngine()
        val code = """
            var cheerio = require('cheerio');
            module.exports = {
                getStreams: function() {
                    try {
                        for (var i = 0; i < 10; i++) {
                            cheerio.load('<div>Doc ' + i + '</div>');
                        }
                        return [{ name: "Failed", title: "Should not reach", url: "https://example.com/fail.mp4" }];
                    } catch (e) {
                        return [{ name: "Rejected", title: "DocLimitEnforced", url: "https://example.com/ok.mp4" }];
                    }
                }
            };
        """.trimIndent()

        val outcome = engine.getStreams(testRequest(code))
        assertEquals(1, outcome.streams.size)
        assertEquals("Rejected", outcome.streams[0].name)
    }

    @Test(timeout = 5_000)
    fun successfulStreamResolutionWithHeaders() = runBlocking {
        val engine = FossNuvioScraperEngine(
            evaluationTimeoutMs = 3_000L,
            overallBudgetMs = 5_000L,
        )

        val validCode = """
            module.exports = {
                getStreams: function(tmdbId, mediaType) {
                    return [
                        {
                            name: "Provider 1",
                            title: "1080p Stream",
                            url: "https://cdn.example.com/video.m3u8",
                            headers: { "Referer": "https://example.com" }
                        }
                    ];
                }
            };
        """.trimIndent()

        val outcome = engine.getStreams(testRequest(validCode))
        assertEquals(1, outcome.streams.size)
        val stream = outcome.streams[0]
        assertEquals("https://cdn.example.com/video.m3u8", stream.url)
        assertEquals("Provider 1", stream.name)
        assertEquals("1080p Stream", stream.title)
        assertEquals("https://example.com", stream.headers["Referer"])
    }

    @Test(timeout = 5_000)
    fun settingsSchemaExtractionWorks() = runBlocking {
        val engine = FossNuvioScraperEngine(
            evaluationTimeoutMs = 3_000L,
            overallBudgetMs = 5_000L,
        )

        val schemaCode = """
            module.exports = {
                onSettings: function() {
                    return [
                        { id: "api_key", type: "text", title: "API Key", description: "Enter key" }
                    ];
                }
            };
        """.trimIndent()

        val schema = engine.getSettingsSchema(testRequest(schemaCode))
        assertNotNull(schema)
        assertTrue(schema!!.contains("api_key"))
    }
}
