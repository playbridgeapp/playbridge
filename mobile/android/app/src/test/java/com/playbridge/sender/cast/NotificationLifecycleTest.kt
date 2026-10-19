package com.playbridge.sender.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationLifecycleTest {
    @Test fun staleCastOrOwnerlessMediaStartMustFinishItsForegroundContractThenStop() {
        assertEquals(NotificationStart.FINISH_START_AND_STOP, notificationStart(hasWork = false))
        assertEquals(NotificationStart.RUN, notificationStart(hasWork = true))
        val owners = MediaNotificationOwners<String>()
        owners.add("tab") // activate enqueues startForegroundService
        owners.remove("tab") // deactivate happens before onCreate
        assertEquals(NotificationStart.FINISH_START_AND_STOP, notificationStart(!owners.isEmpty()))
        owners.add("other") // a real replacement owner keeps the service alive
        assertEquals(NotificationStart.RUN, notificationStart(!owners.isEmpty()))
    }

    @Test fun deniedForegroundPromotionStopsExactlyOnceWithoutEscaping() {
        for (failure in listOf(SecurityException("denied"), IllegalStateException("background start denied"))) {
            var stops = 0
            var loggedType: String? = null
            assertFalse(promoteNotificationForeground(
                promote = { throw failure },
                onFailure = { loggedType = it.javaClass.simpleName },
                stop = { stops++ },
            ))
            assertEquals(failure.javaClass.simpleName, loggedType)
            assertEquals(1, stops)
        }
    }

    @Test fun successfulPromotionDoesNotStopLiveWork() {
        var starts = 0
        var stops = 0
        assertTrue(promoteNotificationForeground(
            promote = { starts++ },
            onFailure = { throw AssertionError(it) },
            stop = { stops++ },
        ))
        assertEquals(1, starts)
        assertEquals(0, stops)
    }

    @Test fun anOwnerWithNoStateOrPausedStateExpiresButPlayingCancelsIdleStop() {
        val owners = MediaNotificationOwners<String>()
        owners.add("tab")
        assertTrue(mediaNotificationNeedsIdleStop(owners.current()?.playing))
        owners.updatePlayback("tab", false)
        assertTrue(mediaNotificationNeedsIdleStop(owners.current()?.playing))
        owners.updatePlayback("tab", true)
        assertFalse(mediaNotificationNeedsIdleStop(owners.current()?.playing))
    }

    @Test fun dismissStopAndIdleTeardownKeepMetadataForTheNextPlayingEvent() {
        for (reason in listOf("dismiss", "Stop", "MediaSession Stop", "idle")) {
            val owners = MediaNotificationOwners<String>()
            val metadata = MediaNotificationMetadata("Episode title", "Artist", "artwork")
            owners.add("tab")
            owners.updateMetadata("tab", metadata)
            owners.updatePlayback("tab", true)
            owners.pauseCurrent() // service teardown; owner lifetime continues
            assertFalse("$reason must keep the owner", owners.isEmpty())
            assertEquals(metadata, owners.current()?.metadata)
            assertTrue(mediaNotificationNeedsIdleStop(owners.current()?.playing))
            owners.updatePlayback("tab", true)
            assertEquals(metadata, owners.current()?.metadata)
            assertEquals(true, owners.current()?.playing)
        }
    }

    @Test fun aMetadataUpdateWhileDismissedIsCachedWithoutAddingAnOwner() {
        val owners = MediaNotificationOwners<String>()
        owners.add("tab")
        owners.pauseCurrent()
        val metadata = MediaNotificationMetadata("New title", "New artist", "new art")
        assertTrue(owners.updateMetadata("tab", metadata))
        assertEquals(false, owners.current()?.playing)
        assertEquals(metadata, owners.current()?.metadata)
        assertFalse(owners.updateMetadata("unregistered", metadata))
        assertFalse(owners.updatePlayback("unregistered", true))
        assertEquals("tab", owners.current()?.ownerId)
    }

    @Test fun multipleOwnersRestoreTheirOwnMetadataAndArtwork() {
        val owners = MediaNotificationOwners<String>()
        val first = MediaNotificationMetadata("First", "One", "art one")
        val second = MediaNotificationMetadata("Second", "Two", "art two")
        owners.add("first")
        owners.updateMetadata("first", first)
        owners.updatePlayback("first", true)
        owners.add("second")
        owners.updateMetadata("second", second)
        owners.updatePlayback("second", true)
        assertEquals(second, owners.current()?.metadata)
        owners.pauseCurrent()
        owners.updatePlayback("first", true)
        assertEquals(first, owners.current()?.metadata)
        owners.updatePlayback("second", false)
        assertEquals("first", owners.current()?.ownerId)
        assertEquals(true, owners.current()?.playing)
    }

    @Test fun deactivationClearsOnlyThatOwnersCacheAndRefcountStaysIdempotent() {
        val owners = MediaNotificationOwners<String>()
        val metadata = MediaNotificationMetadata("First", "Artist", "art")
        owners.add("first")
        owners.updateMetadata("first", metadata)
        owners.add("first") // duplicate activation must not reset metadata or add a ref
        assertEquals(metadata, owners.current()?.metadata)
        owners.add("second")
        owners.updatePlayback("second", true)
        assertTrue(owners.remove("second"))
        assertFalse(owners.isEmpty())
        assertEquals(metadata, owners.current()?.metadata)
        assertTrue(owners.remove("first"))
        assertTrue(owners.isEmpty())
        assertNull(owners.current())
        assertFalse(owners.updateMetadata("first", metadata))
        assertFalse(owners.updatePlayback("first", true))
        owners.add("first")
        assertNull(owners.current()?.metadata)
        assertNull(owners.current()?.playing)
    }
}
