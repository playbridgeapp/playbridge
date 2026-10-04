package com.playbridge.sender.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingUiRequestTest {
    @Test fun `consumed picker request cannot replay on a fresh host`() {
        val picker = PendingUiRequest()
        picker.request()
        val id = picker.requests.value
        assertTrue(picker.consume(id))
        assertEquals(0L, picker.requests.value)
        assertFalse(picker.consume(id))
        assertFalse(picker.consume(picker.requests.value))
    }
    @Test fun `request arriving before UI mount remains pending`() {
        val picker = PendingUiRequest()
        picker.request()
        assertTrue(picker.consume(picker.requests.value))
    }
    @Test fun `stale UI cannot consume a newer request`() {
        val picker = PendingUiRequest()
        picker.request()
        val old = picker.requests.value
        picker.request()
        val current = picker.requests.value
        assertTrue(current > old)
        assertFalse(picker.consume(old))
        assertEquals(current, picker.requests.value)
        assertTrue(picker.consume(current))
    }
    @Test fun `host shutdown discards unseen requests without reusing their token`() {
        val picker = PendingUiRequest()
        picker.request()
        val abandoned = picker.requests.value
        picker.clear()
        assertFalse(picker.consume(abandoned))
        picker.request()
        assertTrue(picker.requests.value > abandoned)
        assertTrue(picker.consume(picker.requests.value))
    }
    @Test fun `repeated requests after consumption have distinct tokens`() {
        val picker = PendingUiRequest()
        picker.request()
        val first = picker.requests.value
        assertTrue(picker.consume(first))
        picker.request()
        assertTrue(picker.requests.value > first)
        assertTrue(picker.consume(picker.requests.value))
    }
}
