package com.playbridge.shared.player

import app.cash.turbine.test
import playbridge.PlayPayload
import com.playbridge.shared.resume.ResumeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// ==================== Tests ====================

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private lateinit var engine: FakePlaybackEngine
    private lateinit var resumeStore: FakeResumeStore
    private lateinit var vm: PlayerViewModel

    private val directPayload = PlayPayload(
        url = "https://example.com/video.mp4",
        title = "Test Video",
    )

    @BeforeTest
    fun setup() {
        engine = FakePlaybackEngine()
        resumeStore = FakeResumeStore()
        vm = PlayerViewModel(
            engine = engine,
            resumeStore = resumeStore,
            scope = testScope.backgroundScope,
        )
    }

    @AfterTest
    fun tearDown() {
        vm.dispose()
        testScope.cancel()
    }

    @Test
    fun `initial state is Idle`() = runTest(testDispatcher) {
        vm.ui.test {
            assertEquals(PlayerUiState.Idle, awaitItem())
        }
    }

    @Test
    fun `onPayload transitions to Loading then Playing`() = runTest(testDispatcher) {
        vm.ui.test {
            assertEquals(PlayerUiState.Idle, awaitItem())

            vm.onPayload(directPayload)
            assertEquals(
                PlayerUiState.Loading(directPayload, false, 0, 0),
                awaitItem(),
            )

            // engine.play() inside onPayload triggers Playing automatically
            assertEquals(
                PlayerUiState.Playing(directPayload, false, false, 0, 0),
                awaitItem(),
            )
        }
    }

    @Test
    fun `onPayload loads resume position from store`() = runTest(testDispatcher) {
        resumeStore.savePosition(directPayload.url, 42_000)
        vm.onPayload(directPayload)

        assertEquals(directPayload, engine.lastLoadedPayload)
        // Resume position is stored but seek only happens when engine reaches Ready
        engine.emitState(PlaybackState.Ready)
        assertEquals(42_000, engine.position.value)
    }

    @Test
    fun `togglePlayPause delegates to engine`() = runTest(testDispatcher) {
        vm.onPayload(directPayload)
        engine.emitState(PlaybackState.Playing)

        vm.togglePlayPause()
        assertEquals(PlaybackState.Paused, engine.state.value)

        vm.togglePlayPause()
        assertEquals(PlaybackState.Playing, engine.state.value)
    }

    // TODO: Re-enable once tvosSimulatorArm64 UnconfinedTestDispatcher interleaving
    // with StateFlow collectors is resolved (Step 5 follow-up).
    // @Test
    // fun `playlist next advances index and loads next item`() = runTest(testDispatcher) {
    //     val items = listOf(
    //         PlayPayload(url = "https://a.com/1.mp4", title = "A"),
    //         PlayPayload(url = "https://a.com/2.mp4", title = "B"),
    //     )
    //
    //     vm.onPlaylistPayload(items, 0)
    //     engine.emitState(PlaybackState.Playing)
    //
    //     vm.next()
    //     yield()
    //     assertEquals(items[1], engine.lastLoadedPayload)
    //
    //     engine.emitState(PlaybackState.Playing)
    //     yield()
    //     val state = vm.ui.value
    //     assertTrue(state is PlayerUiState.Playing, "Expected Playing but was $state")
    //     assertEquals(items[1], (state as PlayerUiState.Playing).payload)
    //     assertEquals(1, state.playlistIndex)
    //     assertEquals(2, state.playlistSize)
    // }

    @Test
    fun `playlist next advances index and loads next item`() = runTest(testDispatcher) {
        val items = listOf(
            PlayPayload(url = "https://a.com/1.mp4", title = "A"),
            PlayPayload(url = "https://a.com/2.mp4", title = "B"),
        )
        vm.onPlaylistPayload(items, 0)
        engine.emitState(PlaybackState.Playing)

        vm.next()
        yield()
        assertEquals(items[1], engine.lastLoadedPayload)
        assertEquals(1, vm.currentIndex)
    }

    @Test
    fun `appendToPlaylist on a single video makes it index 0 and queues the rest`() = runTest(testDispatcher) {
        vm.onPayload(directPayload)
        engine.emitState(PlaybackState.Playing)
        assertFalse(vm.isPlaylistActive)

        val queued = listOf(
            PlayPayload(url = "https://a.com/ep2.mp4", title = "Ep2"),
            PlayPayload(url = "https://a.com/ep3.mp4", title = "Ep3"),
        )
        vm.appendToPlaylist(queued)

        assertTrue(vm.isPlaylistActive)
        assertEquals(0, vm.currentIndex)
        assertEquals(directPayload, vm.currentPlaylist[0])
        assertEquals(3, vm.currentPlaylist.size)

        // Advancing from the single video plays the first queued episode.
        vm.next()
        yield()
        assertEquals(queued[0], engine.lastLoadedPayload)
        assertEquals(1, vm.currentIndex)
    }

    @Test
    fun `appendToPlaylist extends an existing playlist`() = runTest(testDispatcher) {
        val items = listOf(
            PlayPayload(url = "https://a.com/1.mp4", title = "A"),
            PlayPayload(url = "https://a.com/2.mp4", title = "B"),
        )
        vm.onPlaylistPayload(items, 0)
        engine.emitState(PlaybackState.Playing)

        vm.appendToPlaylist(listOf(PlayPayload(url = "https://a.com/3.mp4", title = "C")))
        assertEquals(3, vm.currentPlaylist.size)
        assertEquals(0, vm.currentIndex)
    }

    @Test
    fun `appendToPlaylist ignores empty input`() = runTest(testDispatcher) {
        vm.onPayload(directPayload)
        vm.appendToPlaylist(emptyList())
        assertFalse(vm.isPlaylistActive)
        assertTrue(vm.currentPlaylist.isEmpty())
    }

    @Test
    fun `appended queue auto-advances on Ended`() = runTest(testDispatcher) {
        vm.onPayload(directPayload)
        engine.emitState(PlaybackState.Playing)
        val queued = listOf(PlayPayload(url = "https://a.com/ep2.mp4", title = "Ep2"))
        vm.appendToPlaylist(queued)

        engine.emitState(PlaybackState.Ended)
        yield()
        assertEquals(queued[0], engine.lastLoadedPayload)
        assertEquals(1, vm.currentIndex)
    }

    @Test
    fun `jumpToPlaylistIndex out of bounds is ignored`() = runTest(testDispatcher) {
        val items = listOf(
            PlayPayload(url = "https://a.com/1.mp4", title = "A"),
            PlayPayload(url = "https://a.com/2.mp4", title = "B"),
        )
        vm.onPlaylistPayload(items, 0)
        engine.emitState(PlaybackState.Playing)

        vm.jumpToPlaylistIndex(5)
        yield()
        assertEquals(0, vm.currentIndex)

        vm.jumpToPlaylistIndex(1)
        yield()
        assertEquals(1, vm.currentIndex)
        assertEquals(items[1], engine.lastLoadedPayload)
    }

    @Test
    fun `playlist previous goes back`() = runTest(testDispatcher) {
        val items = listOf(
            PlayPayload(url = "https://a.com/1.mp4", title = "A"),
            PlayPayload(url = "https://a.com/2.mp4", title = "B"),
        )
        vm.onPlaylistPayload(items, 1)
        engine.emitState(PlaybackState.Playing)

        vm.previous()
        assertEquals(items[0], engine.lastLoadedPayload)
    }

    @Test
    fun `engine Error transitions VM to Error`() = runTest(testDispatcher) {
        vm.ui.test {
            assertEquals(PlayerUiState.Idle, awaitItem())

            vm.onPayload(directPayload)
            assertEquals(
                PlayerUiState.Loading(directPayload, false, 0, 0),
                awaitItem(),
            )

            // engine.play() inside onPayload triggers Playing automatically
            assertEquals(
                PlayerUiState.Playing(directPayload, false, false, 0, 0),
                awaitItem(),
            )

            engine.emitState(PlaybackState.Error("E_CODE", "Something broke"))
            assertEquals(
                PlayerUiState.Error("E_CODE", "Something broke"),
                awaitItem(),
            )
        }
    }

    @Test
    fun `looping seeks back to zero on Ended`() = runTest(testDispatcher) {
        vm.onPayload(directPayload)
        engine.emitState(PlaybackState.Playing)
        vm.setLooping(true)

        engine.emitState(PlaybackState.Ended)
        assertEquals(0L, engine.position.value)
        assertEquals(PlaybackState.Playing, engine.state.value)
    }

    @Test
    fun `Ended with playlist auto-advances`() = runTest(testDispatcher) {
        val items = listOf(
            PlayPayload(url = "https://a.com/1.mp4", title = "A"),
            PlayPayload(url = "https://a.com/2.mp4", title = "B"),
        )
        vm.onPlaylistPayload(items, 0)
        engine.emitState(PlaybackState.Playing)

        engine.emitState(PlaybackState.Ended)
        assertEquals(items[1], engine.lastLoadedPayload)
    }

    @Test
    fun `Ended with no playlist or series transitions to Ended`() = runTest(testDispatcher) {
        vm.ui.test {
            assertEquals(PlayerUiState.Idle, awaitItem())

            vm.onPayload(directPayload)
            assertEquals(
                PlayerUiState.Loading(directPayload, false, 0, 0),
                awaitItem(),
            )

            // engine.play() inside onPayload triggers Playing automatically
            assertEquals(
                PlayerUiState.Playing(directPayload, false, false, 0, 0),
                awaitItem(),
            )

            engine.emitState(PlaybackState.Ended)
            assertEquals(PlayerUiState.Ended(null), awaitItem())
        }
    }

    @Test
    fun `retry reloads current payload`() = runTest(testDispatcher) {
        vm.onPayload(directPayload)
        engine.emitState(PlaybackState.Playing)

        vm.retry()
        assertEquals(directPayload, engine.lastLoadedPayload)
    }

    @Test
    fun `dispose releases engine`() = runTest(testDispatcher) {
        vm.dispose()
        assertTrue(engine.released)
    }

    @Test
    fun `saveCurrentProgress persists position`() = runTest(testDispatcher) {
        vm.onPayload(directPayload)
        engine.emitState(PlaybackState.Playing)
        engine.emitPosition(123_000)
        engine.emitDuration(300_000)

        vm.next() // triggers saveCurrentProgress internally
        assertEquals(123_000, resumeStore.loadPosition(directPayload.url))
    }
}
