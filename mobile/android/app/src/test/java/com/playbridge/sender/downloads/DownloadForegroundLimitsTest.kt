package com.playbridge.sender.downloads

import com.playbridge.sender.downloads.engine.DownloadForegroundLimits
import com.playbridge.sender.downloads.engine.DownloadStatus
import com.playbridge.sender.downloads.engine.shouldKeepDownloadPaused
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadForegroundLimitsTest {

    @Test
    fun budgetIsBelowAndroid15DataSyncLimit() {
        assertTrue(
            DownloadForegroundLimits.DATA_SYNC_BUDGET_MS <
                DownloadForegroundLimits.PLATFORM_DATA_SYNC_LIMIT_MS,
        )
        assertTrue(DownloadForegroundLimits.DATA_SYNC_BUDGET_MS >= 5L * 60 * 60 * 1000)
    }

    @Test
    fun budgetOnlyAppliesOnAndroid15AndNewer() {
        assertFalse(DownloadForegroundLimits.appliesTo(34))
        assertTrue(DownloadForegroundLimits.appliesTo(35))
        assertTrue(DownloadForegroundLimits.appliesTo(36))
    }

    @Test
    fun timeoutReplacementKeepsPersistedOrSignalledPause() {
        assertTrue(shouldKeepDownloadPaused(DownloadStatus.PAUSED.name, pauseRequested = false))
        assertTrue(shouldKeepDownloadPaused(DownloadStatus.RUNNING.name, pauseRequested = true))
        assertFalse(shouldKeepDownloadPaused(DownloadStatus.QUEUED.name, pauseRequested = false))
    }
}
