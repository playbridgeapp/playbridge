package com.playbridge.sender.cast

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MediaNotificationPresentationTest {
    @Test fun pageTitleWinsOverTheIframePlayerTitle() {
        assertEquals(
            WebsiteMediaNotificationText("Episode 3 — A show", "VidStreaming Player"),
            websiteMediaNotificationText(" Episode 3 — A show ", "VidStreaming Player", "https://example.com/watch"),
        )
    }

    @Test fun duplicateOrBlankMediaTitleUsesOnlyTheSiteHost() {
        for (media in listOf(null, "  ", "episode", " EPISODE ")) {
            assertEquals(WebsiteMediaNotificationText("Episode", "example.com"),
                websiteMediaNotificationText("Episode", media, "https://user:password@example.com/watch?token=secret"))
        }
    }

    @Test fun blankPageTitleHasSensibleFallbacksWithoutExposingTheUrl() {
        assertEquals(WebsiteMediaNotificationText("Media title", "example.com"),
            websiteMediaNotificationText(" ", "Media title", "https://example.com"))
        assertEquals(WebsiteMediaNotificationText("example.com", "Browser"),
            websiteMediaNotificationText(null, null, "https://user:password@example.com/path?token=secret"))
        assertEquals(WebsiteMediaNotificationText("Web Video", "Browser"),
            websiteMediaNotificationText(null, null, "about:blank"))
        assertEquals(WebsiteMediaNotificationText("Web Video", "Browser"),
            websiteMediaNotificationText(null, null, "not a URI"))
    }

    @Test fun geckoSecondsBecomeMillisecondsAndPlayingPositionExtrapolatesAtTheReportedRate() {
        val position = MediaNotificationPosition.fromSeconds(120.75, 3.125, 1.5, 1000)
        assertEquals(120750L, position.durationMs)
        assertEquals(3125L, position.positionMs)
        assertEquals(1.5f, position.playbackRate)
        assertEquals(6125L, position.positionAt(3000, playing = true))
        assertEquals(3125L, position.positionAt(3000, playing = false))
        assertEquals(3125L, position.positionAt(0, playing = true))
        assertEquals(120750L, position.positionAt(Long.MAX_VALUE, playing = true))
    }

    @Test fun seekNeedsBothFinitePositiveDurationAndEngineSupport() {
        for (duration in listOf(-1.0, 0.0, Double.NaN, Double.POSITIVE_INFINITY, Double.MAX_VALUE, 0.0001)) {
            val position = MediaNotificationPosition.fromSeconds(duration, 0.0, 1.0, 0)
            assertFalse(mediaNotificationCanSeek(position, supported = true))
            assertNull(mediaNotificationSeekTarget(position, true, 1000))
        }
        val finite = MediaNotificationPosition.fromSeconds(120.0, 10.0, 1.0, 0)
        assertTrue(mediaNotificationCanSeek(finite, supported = true))
        assertFalse(mediaNotificationCanSeek(finite, supported = false))
        assertFalse(mediaNotificationCanSeek(null, supported = true))
        assertFalse(mediaNotificationCanSeek(finite.copy(durationMs = 0), supported = true))
        assertFalse(mediaNotificationCanSeek(finite.copy(durationMs = -1), supported = true))
        assertEquals(0L, mediaNotificationSeekTarget(finite, true, -2000))
        assertEquals(120000L, mediaNotificationSeekTarget(finite, true, Long.MAX_VALUE))
        assertEquals(1525L, mediaNotificationSeekTarget(finite, true, 1525))
    }

    @Test fun invalidPositionsAndRatesCannotPoisonPlatformState() {
        for (value in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.MAX_VALUE)) {
            assertNull(MediaNotificationPosition.fromSeconds(120.0, value, 1.0, 0).positionMs)
            assertEquals(1f, MediaNotificationPosition.fromSeconds(120.0, 0.0, value, 0).playbackRate)
        }
        val zeroRate = MediaNotificationPosition.fromSeconds(120.0, 10.0, 0.0, 0)
        assertEquals(10000L, zeroRate.positionAt(1000, playing = true))
        assertEquals(120000L, MediaNotificationPosition.fromSeconds(120.0, 130.0, 1.0, 0).positionMs)
    }

    @Test fun pausingAndResumingDoesNotCountThePausedGap() {
        val owners = MediaNotificationOwners<String>()
        owners.add("tab")
        owners.updatePosition("tab", MediaNotificationPosition.fromSeconds(120.0, 5.0, 1.0, 1000))
        owners.updatePlayback("tab", true, nowMs = 1000)
        owners.pauseCurrent(nowMs = 3500)
        assertEquals(7500L, owners.current()?.position?.positionMs)
        owners.updatePlayback("tab", true, nowMs = 103500)
        assertEquals(7500L, owners.current()?.position?.positionMs)
        assertEquals(8500L, owners.current()?.position?.positionAt(104500, true))
    }

    @Test fun positionTicksFromAnotherOwnerDoNotStealSelectionOrMetadata() {
        val owners = MediaNotificationOwners<String>()
        owners.add("playing")
        val metadata = MediaNotificationMetadata("Page title", "example.com", "art")
        owners.updateMetadata("playing", metadata)
        owners.updatePlayback("playing", true)
        owners.add("background")
        owners.updatePosition("background", MediaNotificationPosition.fromSeconds(120.0, 5.0, 1.0, 1000))
        owners.updateSeekSupported("background", true)
        assertEquals("playing", owners.current()?.ownerId)
        assertEquals(metadata, owners.current()?.metadata)
        assertFalse(owners.updatePosition("closed", MediaNotificationPosition.fromSeconds(120.0, 0.0, 1.0, 0)))
    }

    @Test fun stoppingTheSelectedOwnerCanKeepAnotherPlayingOwnerAlive() {
        val owners = MediaNotificationOwners<String>()
        owners.add("first")
        owners.updatePlayback("first", true)
        owners.add("second")
        owners.updatePlayback("second", true)
        owners.updatePlayback("second", false)
        assertEquals("first", owners.current()?.ownerId)
        assertEquals(true, owners.current()?.playing)
    }

    @Test fun staleOrMissingOwnerControlsNeverRetargetAnotherSource() {
        for (action in MediaControlChannel.Action.entries) {
            val position = 1500L.takeIf { action == MediaControlChannel.Action.SEEK_TO }
            assertNull(notificationMediaControl(null, "old-tab", action, position))
            assertNull(notificationMediaControl("new-tab", "old-tab", action, position))
            assertEquals("new-tab", notificationMediaControl("new-tab", action = action, positionMs = position)?.ownerId)
        }
        assertNull(notificationMediaControl("tab", action = MediaControlChannel.Action.SEEK_TO))
        assertNull(notificationMediaControl("tab", action = MediaControlChannel.Action.SEEK_TO, positionMs = -1))
    }

    @Test fun playPauseStopAndSeekAreConsumedOnlyByTheAddressedOwner() = runTest {
        val first = mutableListOf<MediaControlChannel.Command>()
        val second = mutableListOf<MediaControlChannel.Command>()
        val player = mutableListOf<MediaControlChannel.Command>()
        for ((id, received) in listOf("first" to first, "second" to second, "inapp-player" to player)) {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                MediaControlChannel.actions.collect { if (it.targets(id)) received += it }
            }
        }
        for (action in MediaControlChannel.Action.entries) {
            val command = notificationMediaControl("first", action = action,
                positionMs = 1525L.takeIf { action == MediaControlChannel.Action.SEEK_TO })!!
            assertTrue(MediaControlChannel.tryEmit(command))
        }
        runCurrent()
        assertEquals(MediaControlChannel.Action.entries, first.map { it.action })
        assertTrue(second.isEmpty())
        assertTrue(player.isEmpty())
        assertEquals(1525L, first.last().positionMs)
        MediaControlChannel.tryEmit(notificationMediaControl("inapp-player", action = MediaControlChannel.Action.PAUSE)!!)
        runCurrent()
        assertEquals(listOf(MediaControlChannel.Action.PAUSE), player.map { it.action })
        assertEquals(4, first.size)
        assertTrue(second.isEmpty())
    }

    @Test fun notificationTapSelectsOnlyAnExistingTab() {
        assertEquals("playing", mediaNotificationTabTarget("playing", listOf("selected", "playing")))
        assertNull(mediaNotificationTabTarget("closed", listOf("selected")))
        assertNull(mediaNotificationTabTarget(null, listOf("selected")))
    }
}
