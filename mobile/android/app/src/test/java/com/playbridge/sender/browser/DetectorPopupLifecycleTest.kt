package com.playbridge.sender.browser

import com.playbridge.sender.cast.DetectorMessageOrder
import com.playbridge.sender.cast.DetectorPageVersion
import com.playbridge.sender.cast.VideoDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectorPopupLifecycleTest {
    private val tracker = DetectorTabBindingTracker()
    private val epoch = 100L
    private val opener = "opener-${System.nanoTime()}"
    private val popup = "popup-${System.nanoTime()}"
    private val openerUrl = "https://site.example/watch"
    private val targetUrl = "https://site.example/popup"

    private fun version(detectorTabId: Int, tabId: String, generation: Long) = DetectorPageVersion(
        epoch, generation, detectorTabId,
        requireNotNull(tracker.bindingGeneration(epoch, detectorTabId, tabId)),
    )

    @Test
    fun `blank selected popup owns opener-origin detections despite duplicate old URLs`() {
        val tabs = listOf(
            DetectorTabCandidate(opener, openerUrl),
            DetectorTabCandidate("duplicate", openerUrl),
            DetectorTabCandidate(popup, "about:blank"),
        )
        tracker.resolve(epoch, 9000, listOf(openerUrl), tabs, opener)
        val openerVersion = version(9000, opener, 20L)
        assertTrue(VideoDetector.acceptDetectorVideo(opener, openerVersion))
        tracker.registerCreatedTab(popup, "", opener, popup = true)

        assertEquals(popup, tracker.resolve(epoch, 10097, listOf(openerUrl), tabs, popup))
        for (generation in 1L..4L) {
            val incoming = version(10097, popup, generation)
            assertEquals(DetectorMessageOrder.ADVANCE, VideoDetector.onDetectorNavigation(popup, incoming))
            repeat(24) { assertTrue(VideoDetector.acceptDetectorVideo(popup, incoming)) }
            assertEquals(DetectorMessageOrder.CURRENT, VideoDetector.onDetectorNavigation(popup, incoming))
        }
        assertFalse(VideoDetector.acceptDetectorVideo(popup, version(10097, popup, 3L)))
        assertEquals(DetectorMessageOrder.CURRENT, VideoDetector.detectorOrder(opener, openerVersion))
    }

    @Test
    fun `popup registration precedes store add so initial message waits instead of binding opener`() {
        tracker.registerCreatedTab(popup, "", opener, popup = true)
        val openerOnly = listOf(DetectorTabCandidate(opener, openerUrl))
        assertNull(tracker.resolve(epoch, 10097, listOf(openerUrl), openerOnly, opener))
        val added = openerOnly + DetectorTabCandidate(popup, "")
        assertEquals(popup, tracker.resolve(epoch, 10097, listOf(openerUrl), added, popup))
    }

    @Test
    fun `new native session detector starts at one and rejects late previous detector`() {
        val tabs = listOf(DetectorTabCandidate(popup, openerUrl))
        tracker.resolve(epoch, 10097, listOf(openerUrl), tabs, popup)
        val old = version(10097, popup, 20L)
        assertTrue(VideoDetector.acceptDetectorVideo(popup, old))
        tracker.sessionChanged(popup)
        assertEquals(popup, tracker.resolve(epoch, 10103, listOf(openerUrl), tabs, popup))
        val fresh = version(10103, popup, 1L)
        assertEquals(DetectorMessageOrder.ADVANCE, VideoDetector.onDetectorNavigation(popup, fresh))
        assertTrue(VideoDetector.acceptDetectorVideo(popup, fresh))
        assertFalse(VideoDetector.acceptDetectorVideo(popup, old))
        assertNull(tracker.resolve(epoch, 10097, listOf(openerUrl), tabs, popup))
    }

    @Test
    fun `stale binding can move to native popup with a different document`() {
        val openerOnly = listOf(DetectorTabCandidate(opener, openerUrl))
        tracker.resolve(epoch, 10097, listOf(openerUrl), openerOnly, opener)
        assertTrue(VideoDetector.acceptDetectorVideo(opener, version(10097, opener, 20L)))
        tracker.registerCreatedTab(popup, targetUrl, opener, popup = true)
        val tabs = openerOnly + DetectorTabCandidate(popup, "about:blank")
        val stale = version(10097, opener, 1L)
        assertEquals(DetectorMessageOrder.STALE, VideoDetector.detectorOrder(opener, stale))
        assertEquals(popup, tracker.resolve(
            epoch, 10097, listOf(openerUrl, targetUrl), tabs, popup,
            documentUrl = targetUrl, recoverStaleBinding = true,
        ))
        assertTrue(VideoDetector.acceptDetectorVideo(popup, version(10097, popup, 1L)))
        assertNull(tracker.bindingGeneration(epoch, 10097, opener))
    }

    @Test
    fun `stale navigation of current opener document cannot steal pending blank popup`() {
        val tabs = listOf(DetectorTabCandidate(opener, openerUrl), DetectorTabCandidate(popup, ""))
        tracker.resolve(epoch, 10097, listOf(openerUrl), tabs, opener)
        tracker.registerCreatedTab(popup, "", opener, popup = true)
        assertEquals(opener, tracker.resolve(
            epoch, 10097, listOf(openerUrl), tabs, popup,
            documentUrl = openerUrl, recoverStaleBinding = true,
        ))
    }

    @Test
    fun `two popup detector IDs stay separate even with same initial URL`() {
        val otherPopup = "other-popup-${System.nanoTime()}"
        val tabs = listOf(
            DetectorTabCandidate(opener, openerUrl),
            DetectorTabCandidate(popup, openerUrl),
            DetectorTabCandidate(otherPopup, openerUrl),
        )
        tracker.registerCreatedTab(popup, openerUrl, opener, popup = true)
        tracker.registerCreatedTab(otherPopup, openerUrl, opener, popup = true)
        assertEquals(popup, tracker.resolve(epoch, 10097, listOf(openerUrl), tabs, popup))
        assertEquals(otherPopup, tracker.resolve(epoch, 10103, listOf(openerUrl), tabs, otherPopup))
        assertTrue(VideoDetector.acceptDetectorVideo(popup, version(10097, popup, 4L)))
        assertTrue(VideoDetector.acceptDetectorVideo(otherPopup, version(10103, otherPopup, 1L)))
        assertEquals(popup, tracker.resolve(epoch, 10097, listOf(openerUrl), tabs, otherPopup))
    }
    @Test
    fun `popup with native target URL still owns a resource initiated by opener`() {
        val tabs = listOf(DetectorTabCandidate(opener, openerUrl), DetectorTabCandidate(popup, targetUrl))
        tracker.registerCreatedTab(popup, targetUrl, opener, popup = true)
        assertEquals(popup, tracker.resolve(epoch, 10097, listOf(openerUrl), tabs, popup))
    }

    @Test
    fun `confirmed real navigation cannot recover into new tab on its previous document`() {
        val tabs = listOf(DetectorTabCandidate(opener, targetUrl), DetectorTabCandidate(popup, openerUrl))
        tracker.resolve(epoch, 10097, listOf(targetUrl), tabs, opener, documentUrl = targetUrl)
        tracker.registerCreatedTab(popup, openerUrl, opener, popup = true)
        assertEquals(opener, tracker.resolve(
            epoch, 10097, listOf(openerUrl), tabs, popup,
            documentUrl = openerUrl, recoverStaleBinding = true,
        ))
    }

}
