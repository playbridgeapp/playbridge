package com.playbridge.sender.cast

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class CastTargetSlotTest {
    @Test
    fun `replacing a target releases the previous target exactly once`() {
        val slot = CastTargetSlot()
        val first = FakeTarget("first")
        val second = FakeTarget("second")

        slot.replace(first)
        slot.replace(second)

        assertEquals(1, first.releases)
        assertEquals(0, second.releases)
        assertSame(second, slot.target)
    }

    @Test
    fun `clearing releases current target and leaves slot empty`() {
        val slot = CastTargetSlot()
        val target = FakeTarget("target")

        slot.replace(target)
        slot.clear()
        slot.clear()

        assertEquals(1, target.releases)
        assertNull(slot.target)
    }

    @Test
    fun `taking detaches target without releasing it so stop can be ordered first`() {
        val slot = CastTargetSlot()
        val target = FakeTarget("target")

        slot.replace(target)
        val detached = slot.take()

        assertSame(target, detached)
        assertEquals(0, target.releases)
        assertNull(slot.target)
    }

    private class FakeTarget(override val id: String) : CastTarget {
        var releases = 0
        override val name = id
        override val kind = TargetKind.DLNA
        override val capabilities = emptySet<Capability>()
        override suspend fun load(media: MediaItem) = Unit
        override suspend fun play() = Unit
        override suspend fun pause() = Unit
        override suspend fun stop() = Unit
        override suspend fun seekTo(positionMs: Long) = Unit
        override suspend fun setVolume(percent: Int) = Unit
        override fun status(): Flow<PlaybackStatus> = flowOf(PlaybackStatus(PlaybackState.IDLE))
        override fun release() { releases++ }
    }
}
