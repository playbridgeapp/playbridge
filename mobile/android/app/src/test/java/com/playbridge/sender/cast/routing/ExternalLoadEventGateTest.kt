package com.playbridge.sender.cast.routing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalLoadEventGateTest {

    @Test
    fun staleOrUnversionedEventCannotAffectActiveLoad() {
        assertFalse(ExternalLoadEventGate.isCurrent(4L, 5L, mediaLoaded = true))
        assertFalse(ExternalLoadEventGate.isCurrent(null, 5L, mediaLoaded = true))
        assertTrue(ExternalLoadEventGate.isCurrent(5L, 5L, mediaLoaded = true))
    }

    @Test
    fun targetSessionStatusIsAllowedWhenNoMediaIsLoaded() {
        assertTrue(ExternalLoadEventGate.isCurrent(null, 5L, mediaLoaded = false))
    }
}
