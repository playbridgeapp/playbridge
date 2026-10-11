package com.playbridge.sender.cast.remote

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.playbridge.sender.model.CastProtocol
import com.playbridge.sender.model.EndpointKey
import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.playbridge.sender.cast.RemoteControlScreen
import com.playbridge.sender.cast.MediaTrack
import com.playbridge.sender.cast.SeekVolumeBar
import com.playbridge.sender.cast.TrackChipsRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RemoteScreenTest {
    @get:Rule val compose = createComposeRule()

    @Composable
    private fun screenFixture(
        context: String, name: String = "TV", image: Boolean = false, desktop: Boolean = false,
        position: Long = 0L, duration: Long = 0L, media: String = "fixture", onSeek: (Long) -> Unit = {},
    ): Pair<RemoteUiState, (RemoteAction) -> Unit> {
        val reducer = remember { RemoteStateReducer(SystemClock::uptimeMillis) }
        val session = remember { RemoteSessionId(EndpointKey(CastProtocol.PLAYBRIDGE, "fixture"), 1L) }
        val profile = if (desktop) RemoteProfile.Desktop else RemoteProfile.AndroidTv
        val kind = if (image) RemoteMediaKind.Image else RemoteMediaKind.Video
        val typed = remoteContextFromWire(context)
        val snapshot = RemoteReceiverSnapshot(RemoteUiState(
            sessionId = session, receiver = RemoteReceiver(session.endpoint, name, profile),
            context = typed, connection = RemoteConnection.Connected(name),
            capabilities = nativeRemoteCapabilities(profile, typed, kind),
            playback = RemotePlayback(mediaId = RemoteMediaId(session, null, media, 0L), title = "Fixture", mediaKind = kind, reportedState = RemotePlaybackState.Paused, reportedPositionMs = position, durationMs = duration, seekable = Support.Supported),
        ))
        var state by remember { mutableStateOf(reducer.observe(snapshot)) }
        LaunchedEffect(context, name, image, desktop, position, duration, media) { state = reducer.observe(snapshot) }
        return state to { action ->
            val resolved = when (action) {
                is RemoteAction.SeekBy -> RemoteAction.SeekTo(relativeRemoteSeek(state.playback?.displayedPositionMs ?: 0L, action.deltaMs, duration))
                else -> action
            }
            state = if (resolved is RemoteAction.SeekTo) reducer.begin(resolved) else reducer.local(resolved)
            if (resolved is RemoteAction.SeekTo) onSeek(resolved.positionMs)
        }
    }

    @Test fun trackChipsShowNamesAndAccessibleLabelsWithoutDisplacingMore() {
        val audio = mutableStateOf(listOf(MediaTrack("en", "English", true), MediaTrack("fr", "French", false)))
        val subtitles = mutableStateOf(listOf(MediaTrack("off", "Off", true)))
        val selections = mutableListOf<String>()
        var subtitleClicks = 0
        var moreClicks = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp).testTag("chips bounds")) {
                    TrackChipsRow(
                        audioTracks = audio.value, subtitleTracks = subtitles.value, showSubtitles = true,
                        onSelectAudio = { id -> selections += id; audio.value = audio.value.map { it.copy(selected = it.id == id) } },
                        onSubtitlesClick = { subtitleClicks++ }, onMore = { moreClicks++ },
                    )
                }
            }
        }
        val english = compose.onNodeWithContentDescription("Audio track: English")
        english.assertTextEquals("English").assertIsDisplayed()
        val englishLayout = mutableListOf<TextLayoutResult>()
        english.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(englishLayout) }
        assertTrue(englishLayout.isNotEmpty())
        assertTrue("Short selected name must fit", !englishLayout.single().isLineEllipsized(0))
        compose.onNodeWithContentDescription("Subtitles: Off").assertTextEquals("Off").performClick()
        english.performClick()
        compose.onNodeWithText("French").performClick()
        compose.onNodeWithContentDescription("Audio track: French").assertTextEquals("French")
        compose.runOnIdle {
            assertEquals(listOf("fr"), selections)
            audio.value = audio.value.map { it.copy(selected = false) }
            subtitles.value = emptyList()
        }
        compose.onNodeWithContentDescription("Audio track: Audio").assertTextEquals("Audio")
        compose.onNodeWithContentDescription("Subtitles: Off").assertTextEquals("Off")
        compose.runOnIdle { subtitles.value = listOf(MediaTrack("en", "English", true)) }
        compose.onNodeWithContentDescription("Subtitles: English").assertTextEquals("English")

        val longAudio = "English audio with an exceptionally long descriptive track name"
        val longSubtitle = "English subtitles with an exceptionally long descriptive track name"
        compose.runOnIdle {
            audio.value = listOf(MediaTrack("en", longAudio, true))
            subtitles.value = listOf(MediaTrack("en", longSubtitle, true))
        }
        for ((description, text) in listOf("Audio track: $longAudio" to longAudio, "Subtitles: $longSubtitle" to longSubtitle)) {
            val chip = compose.onNodeWithContentDescription(description)
            chip.assertTextEquals(text)
            val layout = mutableListOf<TextLayoutResult>()
            chip.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layout) }
            assertTrue(layout.isNotEmpty())
            assertEquals(1, layout.single().lineCount)
            assertTrue("Long names must ellipsize", layout.single().isLineEllipsized(0))
        }
        val more = compose.onNodeWithText("More").assertIsDisplayed()
        val rowBounds = compose.onNodeWithTag("chips bounds").fetchSemanticsNode().boundsInRoot
        val moreBounds = more.fetchSemanticsNode().boundsInRoot
        assertTrue(moreBounds.left >= rowBounds.left && moreBounds.right <= rowBounds.right)
        more.performClick()
        compose.runOnIdle { assertEquals(1, subtitleClicks); assertEquals(1, moreClicks) }
    }

    @Test fun seekBarWrapsContentInsideTallColumnAndGrowsWithFontScale() {
        val fontScale = mutableStateOf(1f)
        val volume = mutableStateOf(true)
        var pixelsPerDp = 1f
        compose.setContent {
            val density = LocalDensity.current.density
            pixelsPerDp = density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale.value)) {
                MaterialTheme {
                    Column(Modifier.fillMaxWidth().height(600.dp)) {
                        Spacer(Modifier.weight(1f).fillMaxWidth().testTag("remaining space"))
                        SeekVolumeBar(
                            positionMs = 10_000, durationMs = 120_000, isLive = false,
                            isSeekable = true, isPlaying = false, enableVolume = volume.value,
                            onSeekTo = {}, onVolumeUp = {}, onVolumeDown = {}, onPlayPauseToggle = {},
                        )
                    }
                }
            }
        }
        val bar = compose.onNodeWithContentDescription("Playback seek and volume")
        fun assertCompactBar(maxHeightDp: Int) {
            bar.assertHeightIsAtLeast(86.dp)
            assertTrue("Seek bar must wrap content", bar.fetchSemanticsNode().boundsInRoot.height <= maxHeightDp * pixelsPerDp + 1f)
        }
        assertCompactBar(130)
        val regularHeight = bar.fetchSemanticsNode().boundsInRoot.height
        compose.onNodeWithTag("remaining space").assertHeightIsAtLeast(200.dp)
        compose.runOnIdle { fontScale.value = 2f }
        assertCompactBar(230)
        assertTrue(bar.fetchSemanticsNode().boundsInRoot.height > regularHeight)
        compose.onNodeWithTag("remaining space").assertHeightIsAtLeast(200.dp)
        compose.runOnIdle { fontScale.value = 1f; volume.value = false }
        assertCompactBar(130)
    }

    @Test fun desktopVideoHasNoDeadTabsAndImageKeepsPointerAndMore() {
        val image = mutableStateOf(false)
        compose.setContent {
            val fixture = screenFixture("player", image = image.value, desktop = true)
            MaterialTheme {
                RemoteControlScreen(
                    activeContext = "player", onBack = {}, onRemoteKey = {},
                    onMouseMove = { _, _ -> }, onMouseClick = {}, onMouseScroll = { _, _ -> },
                    playbackState = "paused", receiverConnected = true,
                    mediaKind = if (image.value) "image" else "video",
                    managedState = fixture.first, onRemoteUiAction = fixture.second,
                )
            }
        }
        compose.onNodeWithText("Context").assertDoesNotExist()
        compose.onNodeWithText("D-Pad").assertDoesNotExist()
        compose.onNodeWithText("Touchpad").assertDoesNotExist()
        compose.onNodeWithContentDescription("TV Back", substring = true).assertIsNotEnabled()
        compose.onNodeWithContentDescription("TV Home", substring = true).assertIsNotEnabled()
        compose.onNodeWithContentDescription("More player controls").assertDoesNotExist()
        compose.onNodeWithText("More").performClick()
        compose.onNodeWithText("Stop playback").assertIsDisplayed()
        androidx.test.espresso.Espresso.pressBack()
        compose.runOnIdle { image.value = true }
        compose.onNodeWithText("Context").assertIsDisplayed()
        compose.onNodeWithText("D-Pad").assertDoesNotExist()
        compose.onNodeWithText("Touchpad").performClick()
        compose.onNodeWithText("More").performClick()
        compose.onNodeWithText("Stop playback").assertIsDisplayed()
    }

    @Test fun navPersistsAcrossModesAndContextsAndIdleNavigates() {
        val context = mutableStateOf("player")
        var libraries = 0
        var browsers = 0
        compose.setContent {
            val fixture = screenFixture(context.value, name = "Hisense")
            MaterialTheme {
                RemoteControlScreen(
                    managedState = fixture.first, onRemoteUiAction = fixture.second,
                    activeContext = context.value, onBack = {}, onRemoteKey = {},
                    onMouseMove = { _, _ -> }, onMouseClick = {}, onMouseScroll = { _, _ -> },
                    playbackState = "paused", receiverConnected = true, tvName = "Hisense",
                    onBrowseLibrary = { libraries++ }, onOpenBrowser = { browsers++ },
                )
            }
        }
        fun assertNav() {
            compose.onNodeWithContentDescription("TV Back", substring = true).assertIsDisplayed()
            compose.onNodeWithContentDescription("TV Home", substring = true).assertIsDisplayed()
            compose.onNodeWithContentDescription("Keys", substring = true).assertIsDisplayed()
            compose.onNodeWithContentDescription("TV volume down").assertIsDisplayed()
            compose.onNodeWithContentDescription("TV volume up").assertIsDisplayed()
        }
        assertNav()
        for (mode in listOf("D-Pad", "Touchpad", "Context")) {
            compose.onNodeWithText(mode).performClick()
            assertNav()
            compose.onNodeWithText("More").assertIsDisplayed()
        }
        compose.onNodeWithContentDescription("TV Home", substring = true).assertIsNotEnabled()
        compose.runOnIdle { context.value = "browser" }
        assertNav()
        compose.onNodeWithText("Keyboard").performClick()
        compose.onNodeWithText("Enter").assertIsDisplayed()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithContentDescription("Keys").performClick()
        compose.onNodeWithText("Enter").assertIsDisplayed()
        androidx.test.espresso.Espresso.pressBack()
        compose.runOnIdle { context.value = "idle" }
        assertNav()
        compose.onNodeWithText("Context").assertDoesNotExist()
        compose.onNodeWithText("D-Pad").assertDoesNotExist()
        compose.onNodeWithText("Touchpad").assertDoesNotExist()
        compose.onNodeWithText("Nothing playing on Hisense").assertIsDisplayed()
        compose.onNodeWithText("Browse Library").performClick()
        compose.onNodeWithText("Open browser").performClick()
        compose.runOnIdle { assertEquals(1, libraries); assertEquals(1, browsers) }
    }

    @Test fun releasedWaveAndTransportSeekUsePendingPositionThenIdentityResets() {
        val position = mutableStateOf(10_000L)
        val media = mutableStateOf("first")
        val seeks = mutableListOf<Long>()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val fixture = screenFixture("player", position = position.value, duration = 120_000L, media = media.value, onSeek = { seeks += it })
            MaterialTheme {
                RemoteControlScreen(
                    managedState = fixture.first, onRemoteUiAction = fixture.second,
                    activeContext = "player", onBack = {}, onRemoteKey = {},
                    onMouseMove = { _, _ -> }, onMouseClick = {}, onMouseScroll = { _, _ -> },
                    playbackState = "paused", receiverConnected = true,
                    positionMs = position.value, durationMs = 120_000,
                    onSeekTo = { seeks += it },
                )
            }
        }
        compose.mainClock.advanceTimeByFrame()
        val wave = compose.onNodeWithContentDescription("Playback seek and volume")
        wave.performTouchInput { swipe(center, center.copy(x = center.x + width / 4f), durationMillis = 200) }
        compose.mainClock.advanceTimeBy(200)
        val released = seeks.last()
        wave.assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(released.toFloat(), 0f..120_000f)))
        compose.onNodeWithContentDescription("Seek forward 10 seconds").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertEquals((released + 10_000).coerceAtMost(120_000), seeks.last()) }
        compose.runOnIdle { media.value = "second"; position.value = 3_000 }
        compose.mainClock.advanceTimeByFrame()
        wave.assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(3_000f, 0f..120_000f)))
        wave.performSemanticsAction(SemanticsActions.SetProgress) { it(60_000f) }
        compose.mainClock.advanceTimeByFrame()
        wave.assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(60_000f, 0f..120_000f)))
    }

    @Test fun arrowTapIsExactlyOneKey() {
        val keys = mutableListOf<String>()
        compose.setContent { MaterialTheme { DpadArea(onRemoteKey = { keys += it }, enabled = true) } }
        compose.onNodeWithContentDescription("TV Left").performTouchInput { swipe(center, center, durationMillis = 80) }
        compose.runOnIdle { assertEquals(listOf("dpad_left"), keys) }
    }
}
