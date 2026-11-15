package com.playbridge.sender.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkStatusTest {
    private val now = 1_700_000_000_000L
    private val h = 3_600_000L
    private fun st(exp: Long? = null, r: LinkCheckResult? = null, at: Long? = null, c: Boolean = false) =
        linkStatus(now, exp, r, at, c)

    @Test fun states() {
        assertEquals(LinkStatus.None, st())
        assertEquals(LinkStatus.None, st(exp = now + 30 * h))
        assertEquals(LinkStatus.ExpiresSoon(2 * h), st(exp = now + 2 * h))
        assertEquals(LinkStatus.Expired(now - h), st(exp = now - h))
        assertEquals(LinkStatus.Expired(null), st(r = LinkCheckResult.Expired, at = now))
        assertEquals(LinkStatus.Checking, st(exp = now - h, c = true))
        assertEquals(LinkStatus.CouldNotCheck, st(r = LinkCheckResult.CouldNotCheck, at = now))
        assertEquals(LinkStatus.None, st(r = LinkCheckResult.Works, at = now))
        assertEquals(LinkStatus.ExpiresSoon(h), st(exp = now + h, r = LinkCheckResult.CouldNotCheck, at = now))
        // A check that succeeded after the stated expiry overrides the link's own claim.
        assertEquals(LinkStatus.None, st(exp = now - h, r = LinkCheckResult.Works, at = now))
    }

    @Test fun durations() {
        assertEquals("1 min", formatLinkDuration(5_000))
        assertEquals("45 min", formatLinkDuration(45 * 60_000L))
        assertEquals("1 h", formatLinkDuration(h))
        assertEquals("2 h", formatLinkDuration(2 * h + 59 * 60_000L))
        assertEquals("1 day", formatLinkDuration(25 * h))
        assertEquals("3 days", formatLinkDuration(72 * h))
    }

    @Test fun labels() {
        assertNull(linkStatusLabel(LinkStatus.None, now))
        assertEquals("Link expires in 2 h", linkStatusLabel(LinkStatus.ExpiresSoon(2 * h), now))
        assertEquals("Link expired", linkStatusLabel(LinkStatus.Expired(null), now))
        assertEquals("Link expired 3 h ago", linkStatusLabel(LinkStatus.Expired(now - 3 * h), now))
        assertEquals("Link expired", linkStatusLabel(LinkStatus.Expired(now - 1000), now))
        assertEquals("Checking the link…", linkStatusLabel(LinkStatus.Checking, now))
        assertEquals("Couldn’t check · may have expired", linkStatusLabel(LinkStatus.CouldNotCheck, now))
    }
}
