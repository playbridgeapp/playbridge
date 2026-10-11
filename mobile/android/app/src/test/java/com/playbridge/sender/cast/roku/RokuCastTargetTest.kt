package com.playbridge.sender.cast.roku

import android.content.ContextWrapper
import com.playbridge.sender.cast.PlaybackState
import com.playbridge.sender.cast.PlaybackStatus
import com.playbridge.sender.model.TvDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe

class RokuCastTargetTest {
    @Test
    fun staleStopPreservesNewerLoadStatusAndPolling() = runTest {
        val target = targetWithoutNetwork()
        val newerStatus = PlaybackStatus(PlaybackState.PLAYING, positionMs = 12_000, loadEpoch = 2L)
        val polling = seedLoad(target, newerStatus)
        try {
            target.stop { false }

            assertEquals(newerStatus, target.status().first())
            assertTrue(polling.isActive)
            assertSame(polling, field("pollJob").get(target))
        } finally {
            target.release()
        }
    }

    @Test
    fun ordinaryStopStillStopsPollingAndPublishesStopped() = runTest {
        val target = targetWithoutNetwork()
        val polling = seedLoad(target, PlaybackStatus(PlaybackState.PLAYING, loadEpoch = 2L))
        try {
            target.stop()

            assertTrue(polling.isCancelled)
            assertNull(field("pollJob").get(target))
            assertEquals(PlaybackStatus(PlaybackState.STOPPED, loadEpoch = 2L), target.status().first())
        } finally {
            target.release()
        }
    }

    private fun targetWithoutNetwork(): RokuCastTarget {
        // Stop never uses Context. Bypass the Android stub constructor in this JVM-only fixture.
        val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").run {
            isAccessible = true
            get(null) as Unsafe
        }
        val context = unsafe.allocateInstance(ContextWrapper::class.java) as ContextWrapper
        // No keypress can run on this cancelled scope; the seeded polling job is independent.
        val scope = CoroutineScope(Job().apply { cancel() })
        return RokuCastTarget(
            device = TvDevice(ip = "127.0.0.1", port = 8060, token = "", name = "Roku", isRoku = true),
            scope = scope,
            context = context,
        )
    }

    private fun seedLoad(target: RokuCastTarget, status: PlaybackStatus): Job {
        // Seed the existing private polling/status state without launching media or doing I/O.
        @Suppress("UNCHECKED_CAST")
        val statusFlow = field("_status").get(target) as MutableStateFlow<PlaybackStatus>
        statusFlow.value = status
        field("activeLoadEpoch").set(target, status.loadEpoch)
        return Job().also { field("pollJob").set(target, it) }
    }

    private fun field(name: String) = RokuCastTarget::class.java.getDeclaredField(name).apply {
        isAccessible = true
    }
}
