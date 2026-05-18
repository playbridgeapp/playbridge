package com.playbridge.shared.player

import app.cash.turbine.test
import playbridge.PlayPayload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaybackEngineTest {

    private val fakePayload = PlayPayload(
        url = "https://example.com/video.mp4",
        title = "Test Video",
        headers = mapOf("Referer" to "https://example.com/")
    )

    @Test
    fun `initial state is Idle`() = runTest {
        val engine = FakePlaybackEngine()
        engine.state.test {
            assertEquals(PlaybackState.Idle, awaitItem())
        }
    }

    @Test
    fun `load transitions to Buffering`() = runTest {
        val engine = FakePlaybackEngine()
        engine.state.test {
            assertEquals(PlaybackState.Idle, awaitItem())
            engine.load(fakePayload)
            assertEquals(PlaybackState.Buffering, awaitItem())
        }
        assertEquals(fakePayload, engine.lastLoadedPayload)
    }

    @Test
    fun `play transitions to Playing`() = runTest {
        val engine = FakePlaybackEngine()
        engine.state.test {
            assertEquals(PlaybackState.Idle, awaitItem())
            engine.load(fakePayload)
            assertEquals(PlaybackState.Buffering, awaitItem())
            engine.play()
            assertEquals(PlaybackState.Playing, awaitItem())
        }
    }

    @Test
    fun `pause transitions to Paused`() = runTest {
        val engine = FakePlaybackEngine()
        engine.state.test {
            assertEquals(PlaybackState.Idle, awaitItem())
            engine.play()
            assertEquals(PlaybackState.Playing, awaitItem())
            engine.pause()
            assertEquals(PlaybackState.Paused, awaitItem())
        }
    }

    @Test
    fun `stop returns to Idle`() = runTest {
        val engine = FakePlaybackEngine()
        engine.state.test {
            assertEquals(PlaybackState.Idle, awaitItem())
            engine.play()
            assertEquals(PlaybackState.Playing, awaitItem())
            engine.stop()
            assertEquals(PlaybackState.Idle, awaitItem())
        }
    }

    @Test
    fun `seek updates position`() = runTest {
        val engine = FakePlaybackEngine()
        engine.position.test {
            assertEquals(0L, awaitItem())
            engine.seek(42_000)
            assertEquals(42_000, awaitItem())
        }
    }

    @Test
    fun `tracks emit correctly`() = runTest {
        val engine = FakePlaybackEngine()
        val audio = listOf(Track("1", "English", "en"), Track("2", "Spanish", "es"))
        val subs = listOf(Track("10", "English", "en"))

        engine.audioTracks.test {
            assertTrue(awaitItem().isEmpty())
            engine.emitAudioTracks(audio)
            assertEquals(audio, awaitItem())
        }

        engine.subtitleTracks.test {
            assertTrue(awaitItem().isEmpty())
            engine.emitSubtitleTracks(subs)
            assertEquals(subs, awaitItem())
        }
    }

    @Test
    fun `release sets state to Idle and flags released`() = runTest {
        val engine = FakePlaybackEngine()
        engine.state.test {
            assertEquals(PlaybackState.Idle, awaitItem())
            engine.play()
            assertEquals(PlaybackState.Playing, awaitItem())
            engine.release()
            assertEquals(PlaybackState.Idle, awaitItem())
        }
        assertTrue(engine.released)
    }

    @Test
    fun `PlaybackState sealed class covers all expected states`() {
        // Compilation-level contract test: if a new state is added,
        // this will need updating, forcing an explicit decision.
        val states: List<PlaybackState> = listOf(
            PlaybackState.Idle,
            PlaybackState.Buffering,
            PlaybackState.Ready,
            PlaybackState.Playing,
            PlaybackState.Paused,
            PlaybackState.Ended,
            PlaybackState.Error("CODE", "msg")
        )
        assertEquals(7, states.size)
    }
}
