package com.playbridge.sender.downloads

import com.playbridge.sender.downloads.engine.DownloadStatus
import com.playbridge.sender.downloads.engine.Progress
import com.playbridge.sender.downloads.notifications.DOWNLOAD_GROUP_SUMMARY_ID
import com.playbridge.sender.downloads.notifications.DownloadNotificationMode
import com.playbridge.sender.downloads.notifications.DownloadNotificationProgress
import com.playbridge.sender.downloads.notifications.downloadActionRequestCode
import com.playbridge.sender.downloads.notifications.downloadNotificationId
import com.playbridge.sender.downloads.notifications.downloadNotificationGroupCount
import com.playbridge.sender.downloads.notifications.downloadNotificationMode
import com.playbridge.sender.downloads.notifications.downloadNotificationProgress
import com.playbridge.sender.downloads.notifications.downloadNotificationTitle
import com.playbridge.sender.downloads.notifications.downloadSize
import com.playbridge.sender.downloads.notifications.shouldUpdateDownloadNotification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadNotificationStateTest {
    @Test fun idsAreStableAndUseDisjointForegroundAndResultRanges() {
        val id = "550e8400-e29b-41d4-a716-446655440000"
        assertEquals(downloadNotificationId(id), downloadNotificationId(id))
        assertTrue(downloadNotificationId(id) in 0x20000000..0x2fffffff)
        assertTrue(downloadNotificationId(id, result = true) in 0x40000000..0x4fffffff)
        assertNotEquals(downloadNotificationId(id), downloadNotificationId(id, result = true))
    }

    @Test fun idsAvoidEveryExistingFixedNotificationAndTheSummary() {
        val fixed = setOf(101, 4411, 4712, 4713, 4720, DOWNLOAD_GROUP_SUMMARY_ID)
        val generated = (0..999).flatMap { listOf(downloadNotificationId("download-$it"), downloadNotificationId("download-$it", true)) }
        assertTrue(generated.none { it in fixed })
        assertEquals(2_000, generated.toSet().size)
    }

    @Test fun idsDoNotReuseJavaStringHashCollisions() {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        assertNotEquals(downloadNotificationId("Aa"), downloadNotificationId("BB"))
    }

    @Test fun actionRequestCodesAreStableAndDistinctPerDownloadAndAction() {
        val actions = listOf("PAUSE", "CANCEL", "RESUME", "RETRY", "DISMISS", "open-file", "open-downloads")
        val codes = listOf("first", "second").flatMap { id -> actions.map { downloadActionRequestCode(id, it) } }
        assertEquals(14, codes.toSet().size)
        assertEquals(downloadActionRequestCode("first", "PAUSE"), downloadActionRequestCode("first", "PAUSE"))
    }

    @Test fun knownTotalShowsPercentageAndBothSizes() {
        assertEquals(DownloadNotificationProgress("25% · 20.0 MB / 80.0 MB", 25),
            downloadNotificationProgress(Progress(20L * 1024 * 1024, 80L * 1024 * 1024)))
    }

    @Test fun knownTotalFormatsGigabytes() {
        assertEquals("50% · 1.0 GB / 2.0 GB",
            downloadNotificationProgress(Progress(1L shl 30, 2L shl 30)).text)
    }

    @Test fun zeroOrUnknownTotalIsIndeterminateWithBytesSoFar() {
        for (total in listOf(-1L, 0L)) {
            val progress = downloadNotificationProgress(Progress(12_595_200, total))
            assertNull(progress.percent)
            assertEquals("12.0 MB downloaded", progress.text)
        }
    }

    @Test fun unknownTotalCanShowOnlyTrustedSegmentCounts() {
        assertEquals("Segments 12/80 · 1.0 KB downloaded", downloadNotificationProgress(Progress(1024, -1, "Segments 12/80")).text)
        assertEquals("1.0 KB downloaded", downloadNotificationProgress(Progress(1024, -1, "https://secret.invalid/?token=hidden")).text)
    }

    @Test fun progressClampsNegativeAndOverCompleteBytesAndAvoidsOverflow() {
        assertEquals(0, downloadNotificationProgress(Progress(-1, 10)).percent)
        assertEquals(100, downloadNotificationProgress(Progress(12, 10)).percent)
        assertEquals(100, downloadNotificationProgress(Progress(Long.MAX_VALUE, Long.MAX_VALUE)).percent)
        assertEquals(50, downloadNotificationProgress(Progress(Long.MAX_VALUE / 2, Long.MAX_VALUE)).percent)
    }

    @Test fun sizesCoverBytesKilobytesAndNegativeValues() {
        assertEquals("0 B", downloadSize(-1))
        assertEquals("1023 B", downloadSize(1023))
        assertEquals("1.0 KB", downloadSize(1024))
        assertEquals("1.5 MB", downloadSize(1_572_864))
        assertEquals("1.0 GB", downloadSize(1L shl 30))
    }

    @Test fun mergingAndPublishingAreIndeterminateFinalizing() {
        for (info in listOf("Merging…", "Finalizing…")) {
            assertEquals(DownloadNotificationProgress("Finalizing…", null, true), downloadNotificationProgress(Progress(100, 100, info)))
        }
    }

    @Test fun everyDurableStateMapsToTheExpectedNotificationMode() {
        val expected = mapOf(
            DownloadStatus.QUEUED to DownloadNotificationMode.RUNNING,
            DownloadStatus.RUNNING to DownloadNotificationMode.RUNNING,
            DownloadStatus.MERGING to DownloadNotificationMode.FINALIZING,
            DownloadStatus.PAUSED to DownloadNotificationMode.PAUSED,
            DownloadStatus.DONE to DownloadNotificationMode.DONE,
            DownloadStatus.FAILED to DownloadNotificationMode.FAILED,
        )
        assertEquals(DownloadStatus.entries.toSet(), expected.keys)
        expected.forEach { (status, mode) -> assertEquals(mode, downloadNotificationMode(status)) }
    }

    @Test fun cancelledOrDeletedDownloadHasNoNotification() {
        assertEquals(DownloadNotificationMode.NONE, downloadNotificationMode(null))
        DownloadStatus.entries.forEach { assertEquals(DownloadNotificationMode.NONE, downloadNotificationMode(it, cancelled = true)) }
    }

    private val before = DownloadNotificationProgress("10%", 10)
    private val after = DownloadNotificationProgress("11%", 11)

    @Test fun firstUpdateIsAllowedImmediately() {
        assertTrue(shouldUpdateDownloadNotification(null, before, 0, 0))
    }

    @Test fun changesWaitForOneSecondBoundary() {
        assertFalse(shouldUpdateDownloadNotification(before, after, 100, 1_099))
        assertTrue(shouldUpdateDownloadNotification(before, after, 100, 1_100))
    }

    @Test fun identicalVisibleProgressNeverReposts() {
        assertFalse(shouldUpdateDownloadNotification(before, before.copy(), 0, 10_000))
    }

    @Test fun byteTextAndFinalizingChangesAreAlsoThrottled() {
        val bytes = DownloadNotificationProgress("1.0 MB downloaded", null)
        val next = DownloadNotificationProgress("1.1 MB downloaded", null)
        val finalizing = DownloadNotificationProgress("Finalizing…", null, true)
        assertTrue(shouldUpdateDownloadNotification(bytes, next, 0, 1_000))
        assertFalse(shouldUpdateDownloadNotification(next, finalizing, 1_000, 1_500))
        assertTrue(shouldUpdateDownloadNotification(next, finalizing, 1_000, 2_000))
    }

    @Test fun backwardsClockDoesNotBurstUpdates() {
        assertFalse(shouldUpdateDownloadNotification(before, after, 1_000, 900))
    }

    @Test fun groupingCountsDownloadsRatherThanForegroundAndResultCards() {
        assertEquals(2, downloadNotificationGroupCount(listOf("first", "first", "second")))
        assertEquals(1, downloadNotificationGroupCount(listOf("first", "first")))
    }

    @Test fun groupingDropsCancelledOrDismissedDownloadsAndHandlesEmptyShade() {
        assertEquals(1, downloadNotificationGroupCount(listOf("first", "first", "second"), excludingId = "first"))
        assertEquals(0, downloadNotificationGroupCount(emptyList()))
    }

    @Test fun titlesAllowNamesAndRejectUrlsOrCredentialFallbacks() {
        assertEquals("Big Buck Bunny.mp4", downloadNotificationTitle(" Big Buck Bunny.mp4 "))
        for (title in listOf("", "https://secret.invalid/?token=hidden", "Authorization: secret", "Cookie: secret", "token=secret")) {
            assertEquals("Download", downloadNotificationTitle(title))
        }
    }
}
