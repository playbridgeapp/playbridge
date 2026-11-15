package com.playbridge.sender.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkCheckTest {
    @Test fun statusMapping() {
        assertEquals(LinkCheckResult.Works, decideLinkCheck(200))
        assertEquals(LinkCheckResult.Works, decideLinkCheck(206))
        assertEquals(LinkCheckResult.Works, decideLinkCheck(416))
        for (s in listOf(401, 403, 404, 410)) assertEquals(LinkCheckResult.Expired, decideLinkCheck(s))
        for (s in listOf(500, 502, 503, 429, 451, 302)) assertEquals(LinkCheckResult.CouldNotCheck, decideLinkCheck(s))
        assertEquals(LinkCheckResult.Expired, decideLinkCheck(400, "<Code>ExpiredToken</Code>"))
    }

    @Test fun rangeRejectedAsksForRetry() {
        assertNull(decideLinkCheck(405))
        assertNull(decideLinkCheck(501))
        assertNull(decideLinkCheck(400, "bad"))
        assertEquals(LinkCheckResult.CouldNotCheck, decideLinkCheck(405, rangeRequested = false))
    }

    @Test fun markExpiredFromStatusAndFailure() {
        assertTrue(shouldMarkExpired(403))
        assertTrue(shouldMarkExpired(410))
        assertFalse(shouldMarkExpired(500))
        assertFalse(shouldMarkExpired(null))
        assertTrue(shouldMarkExpiredFromFailure("The link was refused (HTTP 403)"))
        assertTrue(shouldMarkExpiredFromFailure("The video wasn't found (HTTP 404)"))
        assertFalse(shouldMarkExpiredFromFailure("The media couldn't be loaded (HTTP 503)"))
        assertFalse(shouldMarkExpiredFromFailure("The TV couldn't reach the video server"))
        assertFalse(shouldMarkExpiredFromFailure(null))
    }
}
