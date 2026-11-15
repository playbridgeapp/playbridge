package com.playbridge.sender.history

import org.junit.Assert.assertEquals
import org.junit.Test

class LinkCheckPolicyTest {
    private val now = 10_000_000_000L
    private fun c(id: String, saved: Long, finished: Boolean = false, url: String = "https://x.example/$id.mp4",
                  exp: Long? = null, r: LinkCheckResult? = null, at: Long? = null) =
        LinkCheckCandidate(id, url, finished, saved, exp, r, at)

    @Test fun ordersUnfinishedThenNewestAndCaps() {
        val list = listOf(
            c("old", 1), c("fin-new", 100, finished = true), c("new", 50), c("mid", 20),
            c("a", 2), c("b", 3), c("c", 4),
        )
        assertEquals(listOf("new", "mid", "c", "b", "a"), selectLinksToCheck(list, now).map { it.id })
        assertEquals(5, MAX_LINK_CHECKS_PER_OPEN)
        assertEquals(listOf("new"), selectLinksToCheck(list, now, limit = 1).map { it.id })
    }

    @Test fun skips() {
        val list = listOf(
            c("expired-link", 1, exp = now - 1),
            c("known-expired", 2, r = LinkCheckResult.Expired, at = now - LINK_CHECK_FRESHNESS_MS * 5),
            c("fresh", 3, r = LinkCheckResult.Works, at = now - 1000),
            c("stale", 4, r = LinkCheckResult.Works, at = now - LINK_CHECK_FRESHNESS_MS),
            c("file", 5, url = "file:///x.mp4"),
            c("blob", 6, url = "blob:https://x/1"),
            c("nohost", 7, url = "http:///x"),
            c("failed-recent", 8, r = LinkCheckResult.CouldNotCheck, at = now - 60_000),
            c("ok", 9, exp = now + 1000),
        )
        assertEquals(listOf("ok", "stale"), selectLinksToCheck(list, now).map { it.id })
    }
}
