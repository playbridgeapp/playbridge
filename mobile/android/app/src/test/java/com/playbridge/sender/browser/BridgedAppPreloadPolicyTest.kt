package com.playbridge.sender.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgedAppPreloadPolicyTest {
    private val now = 1_800_000_000_000L
    private val ok = BridgedAppPreloadConditions(
        enabled = true, lastOpenedAt = now - 60_000, now = now, alreadyLive = false, online = true,
        metered = false, dataSaver = false, powerSave = false, lowMemory = false, maxAliveSessions = 5,
    )

    @Test
    fun preloadsARecentlyUsedAppOnAnUnmeteredNetwork() = assertTrue(BridgedAppPreloadPolicy.shouldPreload(ok))

    @Test
    fun eachSkipConditionPreventsThePreload() {
        listOf(
            ok.copy(enabled = false),
            ok.copy(lastOpenedAt = null),
            ok.copy(lastOpenedAt = now - BridgedAppPreloadPolicy.RECENT_USE_MS),
            ok.copy(lastOpenedAt = now + 1),
            ok.copy(alreadyLive = true),
            ok.copy(online = false),
            ok.copy(metered = true),
            ok.copy(dataSaver = true),
            ok.copy(powerSave = true),
            ok.copy(lowMemory = true),
            ok.copy(maxAliveSessions = 1),
        ).forEach { assertFalse(it.toString(), BridgedAppPreloadPolicy.shouldPreload(it)) }
    }

    @Test
    fun justInsideTheRecentWindowStillPreloads() =
        assertTrue(BridgedAppPreloadPolicy.shouldPreload(ok.copy(lastOpenedAt = now - BridgedAppPreloadPolicy.RECENT_USE_MS + 1)))
}
