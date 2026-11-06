package com.playbridge.sender.connection

import com.playbridge.shared.player.SubtitleStyle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TvSubtitleStyleTest {
    @Test fun reportedStyleReplacesTheLastOne() = runTest {
        val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
        val coordinator = ConnectionCoordinator(messages, null, backgroundScope) { 0L }
        runCurrent()
        assertNull(coordinator.tvSubtitleStyle.value)

        messages.emit(
            """{"type":"subtitle_style","size":"large","background":"solid","edge":"shadow","font":"default","overrideStyled":true}""",
        )
        runCurrent()
        assertEquals(
            SubtitleStyle(
                size = SubtitleStyle.Size.LARGE,
                background = SubtitleStyle.Background.SOLID,
                edge = SubtitleStyle.Edge.SHADOW,
                overrideStyled = true,
            ),
            coordinator.tvSubtitleStyle.value,
        )

        // Values a newer TV knows but this phone doesn't read as the defaults.
        messages.emit("""{"type":"subtitle_style","size":"huge","background":"none","edge":"outline","font":"custom"}""")
        runCurrent()
        assertEquals(SubtitleStyle.DEFAULT, coordinator.tvSubtitleStyle.value)
    }

    @Test fun withoutAConnectedTvNothingIsSent() = runTest {
        val coordinator = ConnectionCoordinator(MutableSharedFlow(), null, backgroundScope) { 0L }
        assertFalse(coordinator.tvSupportsSubtitleStyle)
        coordinator.setTvSubtitleStyle(SubtitleStyle(size = SubtitleStyle.Size.SMALL))
        assertNull(coordinator.tvSubtitleStyle.value)
    }
}
