package com.playbridge.sender.cast.remote

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.playbridge.sender.cast.remote.ui.SeekVolumeBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RemoteSeekScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun playGlyphSurvivesLearnedHintAndDurationTogglesWithoutPlaybackTap() {
        val learned = mutableStateOf(emptySet<String>())
        val playing = mutableStateOf(false)
        val time = mutableStateOf(RemoteTimeDisplay.Duration)
        var taps = 0
        compose.setContent { MaterialTheme { Box(Modifier.width(400.dp)) {
            SeekVolumeBar(30_000, 120_000, false, true, playing.value, true, {}, {}, {},
                { taps++; playing.value = !playing.value }, learned.value, time.value, { time.value = it })
        } } }
        compose.onNodeWithTag("remote.playGlyph", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("remote.seekHint", useUnmergedTree = true).assertTextEquals("◄► SEEK · TAP")
        compose.onNodeWithTag("remote.volumeHint", useUnmergedTree = true).assertTextEquals("▲▼ VOLUME")
        compose.onNodeWithText("·", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Playback seek and volume").performClick()
        compose.onNodeWithContentDescription("Playback seek and volume").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Playing"))
        compose.runOnIdle { learned.value = setOf("tap", "seek", "volume") }
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithTag("remote.playGlyph", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("remote.remainingHint", useUnmergedTree = true).assertTextEquals("1:30 left")
        compose.onNodeWithText("·", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Total duration").performClick()
        compose.onNodeWithContentDescription("Time remaining").assertTextEquals("−1:30")
        compose.runOnIdle { assertEquals(1, taps); assertEquals(RemoteTimeDisplay.Remaining, time.value) }
        val actions = compose.onNodeWithContentDescription("Playback seek and volume").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(listOf("Pause", "Seek back 10 seconds", "Seek forward 10 seconds"), actions.map { it.label })
    }

    @Test fun stage1HeightAndSharedCentreLineSurviveHintLearning() {
        val learned = mutableStateOf(emptySet<String>())
        val density = compose.density.density
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, 1f)) {
                MaterialTheme { Box(Modifier.width(400.dp)) {
                    SeekVolumeBar(30_000, 120_000, false, true, false, true, {}, {}, {}, {}, learned.value)
                } }
            }
        }
        fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        for (gestures in listOf(emptySet(), setOf("tap", "seek", "volume"))) {
            compose.runOnIdle { learned.value = gestures }
            compose.mainClock.advanceTimeBy(200)
            compose.onNodeWithContentDescription("Playback seek and volume").assertHeightIsEqualTo(86.dp)
            val centre = bounds("remote.waveCanvas").center.y
            for (tag in listOf("remote.waveGuide", "remote.seekLeft", "remote.seekRight", "remote.volumeGuide")) {
                assertEquals(centre, bounds(tag).center.y, 1f)
            }
            val glyph = bounds("remote.playGlyph")
            val hint = bounds("remote.hintRow")
            val guide = bounds("remote.volumeGuide")
            assertTrue(glyph.top >= hint.top && glyph.bottom <= hint.bottom)
            assertTrue(glyph.bottom < bounds("remote.waveRow").top)
            assertTrue(guide.top >= hint.bottom - 1f)
            assertEquals(bounds("remote.elapsedTime").center.y, bounds("remote.durationText").center.y, 1f)
            compose.onNodeWithContentDescription("Total duration").assertHeightIsAtLeast(48.dp)
            compose.onNodeWithTag("remote.playGlyph", useUnmergedTree = true).assertIsDisplayed()
        }
    }

    @Test fun quickPlayheadDragScrubsDirectlyWhileAwayDragIsRelative() {
        val position = mutableStateOf(60_000L)
        val seeks = mutableListOf<Long>()
        compose.setContent { MaterialTheme { Box(Modifier.width(400.dp)) {
            SeekVolumeBar(position.value, 120_000, false, true, false, true,
                { seeks += it; position.value = it }, {}, {}, {})
        } } }
        val wave = compose.onNodeWithContentDescription("Playback seek and volume")
        val canvas = compose.onNodeWithTag("remote.waveCanvas", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val bounds = wave.fetchSemanticsNode().boundsInRoot
        val waveY = canvas.center.y - bounds.top
        wave.performTouchInput { swipe(Offset(width / 2f, waveY), Offset(width / 2f + canvas.width / 4f, waveY), 100) }
        compose.runOnIdle { assertTrue(seeks.last() in 88_000L..92_000L) }
        compose.runOnIdle { position.value = 60_000L }
        val awayX = canvas.left - bounds.left + canvas.width * 0.1f
        wave.performTouchInput { swipe(Offset(awayX, waveY), Offset(awayX + width / 10f, waveY), 100) }
        compose.runOnIdle { assertTrue(seeks.last() in 71_000L..73_000L) }
    }

    @Test fun durationTargetPreservesWaveTapsNearTheRightPlayheadAndSemanticTimeClicks() {
        var taps = 0
        var timeChanges = 0
        compose.setContent { MaterialTheme { Box(Modifier.width(400.dp)) {
            SeekVolumeBar(108_000, 120_000, false, true, false, true, {}, {}, {}, { taps++ },
                onTimeDisplay = { timeChanges++ })
        } } }
        val wave = compose.onNodeWithContentDescription("Playback seek and volume")
        val root = wave.fetchSemanticsNode().boundsInRoot
        val canvas = compose.onNodeWithTag("remote.waveCanvas", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        wave.performTouchInput { click(Offset(canvas.left + canvas.width * 0.9f - root.left, canvas.center.y - root.top)) }
        compose.runOnIdle { assertEquals(1, taps); assertEquals(0, timeChanges) }
        val clock = compose.onNodeWithTag("remote.durationText", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        wave.performTouchInput { click(Offset(clock.center.x - root.left, clock.center.y - root.top)) }
        compose.runOnIdle { assertEquals(1, taps); assertEquals(1, timeChanges) }
        compose.onNodeWithContentDescription("Total duration").performClick()
        compose.runOnIdle { assertEquals(1, taps); assertEquals(2, timeChanges) }
    }

    @Test fun verticalDragChangesVolumeWithoutSeekingOrToggling() {
        var volume = 0
        var seeks = 0
        var taps = 0
        compose.setContent { MaterialTheme { Box(Modifier.width(400.dp)) {
            SeekVolumeBar(30_000, 120_000, false, true, false, true, { seeks++ }, { volume++ }, { volume-- }, { taps++ })
        } } }
        compose.onNodeWithContentDescription("Playback seek and volume").performTouchInput {
            swipe(Offset(width / 2f, height * 0.8f), Offset(width / 2f, height * 0.2f), 150)
        }
        compose.runOnIdle { assertTrue(volume > 0); assertEquals(0, seeks); assertEquals(0, taps) }
    }

    @Test fun liveWaveKeepsGlyphAndPlaybackActionWithoutSeekActions() {
        var taps = 0
        compose.setContent { MaterialTheme { SeekVolumeBar(0, 0, true, false, false, false, {}, {}, {}, { taps++ }) } }
        compose.onNodeWithContentDescription("Playback seek and volume").performTouchInput {
            swipe(center, center.copy(x = center.x + width / 4f), 100)
        }
        compose.runOnIdle { assertEquals(0, taps) }
        compose.onNodeWithTag("remote.playGlyph", useUnmergedTree = true).assertIsDisplayed()
        val actions = compose.onNodeWithContentDescription("Playback seek and volume").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(listOf("Play"), actions.map { it.label })
    }
}
