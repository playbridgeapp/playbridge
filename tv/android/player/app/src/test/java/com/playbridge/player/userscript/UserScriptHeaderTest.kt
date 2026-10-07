package com.playbridge.player.userscript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserScriptHeaderTest {
    @Test
    fun `parses every @match inside the header`() {
        val header = UserScriptHeaderParser.parse(
            """
            // ==UserScript==
            // @name Example
            // @match https://example.com/*
            // @match *://*.foo.com/path/*
            // @include https://ignored.example/*
            // ==/UserScript==
            (function(){})();
            """.trimIndent(),
        )

        assertFalse(header.runsOnAllSites)
        assertEquals(listOf("https://example.com/*", "*://*.foo.com/path/*"), header.rawMatches)
        assertEquals(2, header.matchPatterns.size)
    }

    @Test
    fun `ignores @match lines outside the header block`() {
        val header = UserScriptHeaderParser.parse(
            """
            // @match https://outside.example/*
            // ==UserScript==
            // @match https://inside.example/*
            // ==/UserScript==
            // @match https://after.example/*
            """.trimIndent(),
        )

        assertEquals(listOf("https://inside.example/*"), header.rawMatches)
        assertFalse(header.runsOnAllSites)
    }

    @Test
    fun `no header and a header without @match both run on all sites`() {
        assertTrue(UserScriptHeaderParser.parse("console.log(1)").runsOnAllSites)
        assertTrue(
            UserScriptHeaderParser.parse(
                """
                // ==UserScript==
                // @name Only a name
                // ==/UserScript==
                """.trimIndent(),
            ).runsOnAllSites,
        )
    }

    @Test
    fun `invalid @match lines are not treated as all sites`() {
        val header = UserScriptHeaderParser.parse(
            """
            // ==UserScript==
            // @match not-a-pattern
            // @match http://*example.com/*
            // ==/UserScript==
            """.trimIndent(),
        )

        assertFalse(header.runsOnAllSites)
        assertTrue(header.matchPatterns.isEmpty())
        assertEquals(listOf("not-a-pattern", "http://*example.com/*"), header.rawMatches)
    }

    @Test
    fun `accepts whitespace around the header markers and match values`() {
        val header = UserScriptHeaderParser.parse(
            """
            //==UserScript==
            //    @match    https://example.com/foo/*
            //==/UserScript==
            """.trimIndent(),
        )

        assertEquals(listOf("https://example.com/foo/*"), header.rawMatches)
        assertEquals("https://example.com/foo/*", header.matchPatterns.single().raw)
    }

    @Test
    fun `only the first header block is read`() {
        val header = UserScriptHeaderParser.parse(
            """
            // ==UserScript==
            // @match https://first.example/*
            // ==/UserScript==
            // ==UserScript==
            // @match https://second.example/*
            // ==/UserScript==
            """.trimIndent(),
        )

        assertEquals(listOf("https://first.example/*"), header.rawMatches)
    }
}
