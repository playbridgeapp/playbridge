package com.playbridge.sender.cast

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LatestVolumeCommandsTest {
    @Test fun slowReceiverHasOneCallInFlightAndOnlyLatestPendingAbsolute() = runTest {
        val gate = CompletableDeferred<Unit>()
        val applied = mutableListOf<Int>()
        val commands = LatestVolumeCommands<Int>(backgroundScope) {
            applied += it
            if (applied.size == 1) gate.await()
        }
        commands.submit(0)
        runCurrent()
        for (level in 1..20) commands.submit(level)
        runCurrent()
        assertEquals(listOf(0), applied)
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(0, 20), applied)
    }
}
