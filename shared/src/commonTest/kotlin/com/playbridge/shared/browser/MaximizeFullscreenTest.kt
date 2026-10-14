package com.playbridge.shared.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MaximizeFullscreenTest {
    @Test
    fun enterTargetsTheSelectedFrameAndHoldsWhenAlreadyOn() {
        assertEquals(MaximizeRoute.TargetFrame, maximizeRoute(hasTargetFrame = true, alreadyOn = false))
        assertEquals(MaximizeRoute.MainFrameFallback, maximizeRoute(hasTargetFrame = false, alreadyOn = false))
        assertEquals(MaximizeRoute.Hold, maximizeRoute(hasTargetFrame = true, alreadyOn = true))
        assertEquals(MaximizeRoute.Hold, maximizeRoute(hasTargetFrame = false, alreadyOn = true))
    }

    @Test
    fun phoneFullscreenFollowsChromeFillOrConfirmedDom() {
        assertFalse(browserFullscreenReported(false, false, false))
        assertTrue(browserFullscreenReported(true, false, false))
        assertTrue(browserFullscreenReported(false, true, false))
        assertTrue(browserFullscreenReported(false, false, true))
    }

    @Test
    fun tokensAndSignalsKeepFillUntilRestore() {
        assertEquals("unknown", maximizeResultToken("https://videos.example/a"))
        assertEquals("ok_fill", maximizeResultToken("ok_fill"))
        val filled = applyMaximizeSignal(FullscreenFlags(), maximizeSignal("maximize", "ok_fill"))
        assertEquals(FullscreenFlags(frameFill = true), filled)
        val held = applyMaximizeSignal(filled, maximizeSignal("maximize", "ok_held"))
        assertEquals(FullscreenFlags(frameFill = true), held)
        assertEquals(FullscreenFlags(), applyMaximizeSignal(held, maximizeSignal("restore", "ok")))
        val claimed = applyMaximizeSignal(FullscreenFlags(), maximizeSignal("maximize", "ok_video"))
        assertEquals(FullscreenFlags(nativeClaim = true), claimed)
        assertEquals("ok_site", maximizeResultToken("ok_site"))
        assertEquals(FullscreenFlags(nativeClaim = true), applyMaximizeSignal(FullscreenFlags(), maximizeSignal("maximize", "ok_site")))
        assertEquals(FullscreenFlags(), applyMaximizeSignal(claimed, maximizeSignal("restore", "ok_native")))
        assertEquals(FullscreenFlags(), applyMaximizeSignal(filled, maximizeSignal("maximize", "no_video")))
        assertEquals(FullscreenFlags(frameFill = true), applyMaximizeSignal(filled, maximizeSignal("maximize", "https://secret")))
    }
    @Test
    fun heldAndPendingCannotCreateAClaimAndFillClearsAnOldClaim() {
        assertEquals(FullscreenFlags(), applyMaximizeSignal(FullscreenFlags(), maximizeSignal("maximize", "ok_held")))
        assertEquals(FullscreenFlags(), applyMaximizeSignal(FullscreenFlags(), maximizeSignal("maximize", "pending")))
        assertEquals(FullscreenFlags(frameFill = true), applyMaximizeSignal(
            FullscreenFlags(nativeClaim = true), maximizeSignal("maximize", "ok_fill")))
    }

    @Test
    fun repeatedDeniedRequestFillRestoreCyclesReportOnlyVisibleModes() {
        var flags = FullscreenFlags()
        repeat(3) {
            flags = applyMaximizeSignal(flags, maximizeSignal("maximize", "pending"))
            assertFalse(browserFullscreenReported(false, flags.frameFill, flags.nativeClaim))
            flags = applyMaximizeSignal(flags, maximizeSignal("maximize", "ok_fill"))
            assertTrue(browserFullscreenReported(false, flags.frameFill, flags.nativeClaim))
            flags = applyMaximizeSignal(flags, maximizeSignal("maximize", "ok_held"))
            assertFalse(flags.nativeClaim)
            flags = applyMaximizeSignal(flags, maximizeSignal("restore", "ok"))
            assertFalse(browserFullscreenReported(false, flags.frameFill, flags.nativeClaim))
        }
    }
}
