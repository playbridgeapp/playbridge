package com.playbridge.sender.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnedCallbackGateTest {
    @Test
    fun `stale activity cannot release callbacks claimed by replacement`() {
        val gate = OwnedCallbackGate()
        val oldActivity = Any()
        val replacementActivity = Any()

        gate.claim(oldActivity)
        gate.claim(replacementActivity)

        assertFalse(gate.release(oldActivity))
        assertTrue(gate.release(replacementActivity))
        assertFalse(gate.release(replacementActivity))
    }
}
