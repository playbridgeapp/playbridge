package com.playbridge.sender.together

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchTogetherLinksTest {
    private val room = "0123456789abcdef0123456789abcdef"

    @Test
    fun `recognises invite links on any relay`() {
        assertTrue(WatchTogetherLinks.isInvite("https://relay.example.workers.dev/j/$room#k=abc&h=def"))
        assertTrue(WatchTogetherLinks.isInvite("https://example.com/together/j/$room#h=def&k=abc"))
    }

    @Test
    fun `moves a handed-over key back into the fragment`() {
        assertEquals(
            "https://relay.example.workers.dev/j/$room#k=abc&h=def",
            WatchTogetherLinks.invite("https://relay.example.workers.dev/j/$room?k=abc&h=def"),
        )
    }

    @Test
    fun `leaves ordinary pages to the browser`() {
        assertFalse(WatchTogetherLinks.isInvite("https://example.com/j/$room"))
        assertFalse(WatchTogetherLinks.isInvite("https://example.com/j/$room#k=abc"))
        assertFalse(WatchTogetherLinks.isInvite("https://example.com/j/notaroom#k=abc&h=def"))
        assertFalse(WatchTogetherLinks.isInvite("https://example.com/watch?v=1"))
    }
}
