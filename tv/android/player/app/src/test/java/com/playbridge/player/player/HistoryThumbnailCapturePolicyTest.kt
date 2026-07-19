package com.playbridge.player.player

import com.playbridge.player.data.HistoryThumbnailMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryThumbnailCapturePolicyTest {
    @Test
    fun `smart capture waits fifteen seconds and preserves existing artwork`() {
        assertFalse(shouldCaptureHistoryThumbnail(HistoryThumbnailMode.SMART, false, 14_999, null))
        assertTrue(shouldCaptureHistoryThumbnail(HistoryThumbnailMode.SMART, false, 15_000, null))
        assertFalse(shouldCaptureHistoryThumbnail(HistoryThumbnailMode.SMART, true, 15_000, null))
    }

    @Test
    fun `live capture refreshes every two minutes after its first frame`() {
        assertTrue(shouldCaptureHistoryThumbnail(HistoryThumbnailMode.LIVE, true, 15_000, null))
        assertFalse(shouldCaptureHistoryThumbnail(HistoryThumbnailMode.LIVE, true, 134_999, 15_000))
        assertTrue(shouldCaptureHistoryThumbnail(HistoryThumbnailMode.LIVE, true, 135_000, 15_000))
    }

    @Test
    fun `exit fallback is bounded and artwork only never captures`() {
        assertFalse(
            shouldCaptureHistoryThumbnail(
                HistoryThumbnailMode.SMART,
                false,
                4_999,
                null,
                exitFallback = true,
            ),
        )
        assertTrue(
            shouldCaptureHistoryThumbnail(
                HistoryThumbnailMode.SMART,
                false,
                5_000,
                null,
                exitFallback = true,
            ),
        )
        assertFalse(
            shouldCaptureHistoryThumbnail(
                HistoryThumbnailMode.ARTWORK_ONLY,
                false,
                60_000,
                null,
                exitFallback = true,
            ),
        )
        assertFalse(
            shouldCaptureHistoryThumbnail(
                HistoryThumbnailMode.LIVE,
                true,
                44_999,
                15_000,
                exitFallback = true,
            ),
        )
        assertTrue(
            shouldCaptureHistoryThumbnail(
                HistoryThumbnailMode.LIVE,
                true,
                45_000,
                15_000,
                exitFallback = true,
            ),
        )
    }
}
