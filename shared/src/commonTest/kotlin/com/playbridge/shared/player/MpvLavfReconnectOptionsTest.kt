package com.playbridge.shared.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MpvLavfReconnectOptionsTest {
    @Test fun includesRequiredBoundedReconnectOptions() {
        val opts = MpvLavfReconnectOptions.VALUE
        assertTrue(opts.contains("reconnect=1"))
        assertFalse(opts.contains("reconnect_streamed"))
        assertTrue(opts.contains("reconnect_on_network_error=1"))
        assertTrue(opts.contains("reconnect_on_http_error=\"429,5xx\""))
        assertTrue(opts.contains("reconnect_delay_max=5"))
        assertTrue(opts.contains("reconnect_max_retries=3"))
        assertEquals(3, MpvLavfReconnectOptions.MAX_RETRIES)
        assertEquals(5, MpvLavfReconnectOptions.DELAY_MAX_SECONDS)
    }
}
