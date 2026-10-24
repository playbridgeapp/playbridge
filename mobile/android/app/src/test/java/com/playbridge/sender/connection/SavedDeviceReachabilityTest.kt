package com.playbridge.sender.connection

import com.playbridge.sender.model.TvDevice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SavedDeviceReachabilityTest {
    private val saved = TvDevice("192.0.2.10", 8765, "fixture-token", "Receiver", uuid = "receiver", lastConnected = 1L)
    private val live = saved.copy(ip = "192.0.2.20", port = 8766, wssPort = 8766, token = "")
    private val endpoint = saved.endpointKey.toString()

    @Test fun probesTheLiveEndpointAndDebouncesDiscoveryChanges() = runTest {
        val discovered = MutableStateFlow(emptyList<TvDevice>())
        val probed = mutableListOf<TvDevice>()
        val published = mutableListOf<Map<String, Boolean>>()
        val reachability = SavedDeviceReachability(backgroundScope, MutableStateFlow(listOf(saved)), discovered,
            MutableStateFlow<Long?>(1L), { device -> probed.add(device); true }, published::add)
        runCurrent()
        advanceTimeBy(350); runCurrent()
        assertEquals(listOf(saved), probed)

        discovered.value = listOf(live)
        runCurrent()
        advanceTimeBy(100)
        val latest = live.copy(ip = "192.0.2.21", port = 9000, wssPort = 9000)
        discovered.value = listOf(latest)
        runCurrent()
        advanceTimeBy(349); runCurrent()
        assertEquals(1, probed.size)
        advanceTimeBy(1); runCurrent()
        assertEquals(2, probed.size)
        assertEquals(latest.ip, probed.last().ip)
        assertEquals(latest.wssPort, probed.last().wssPort)
        assertEquals(listOf(latest.ip, saved.ip), probed.last().addresses)
        assertEquals(saved.token, probed.last().token)
        assertEquals(mapOf(endpoint to true), published.last())

        reachability.retry()
        runCurrent()
        advanceTimeBy(350); runCurrent()
        assertEquals(3, probed.size)
    }

    @Test fun changingNetworksClearsOldPingsBeforeReprobing() = runTest {
        val network = MutableStateFlow<Long?>(1L)
        val published = mutableListOf<Map<String, Boolean>>()
        SavedDeviceReachability(backgroundScope, MutableStateFlow(listOf(saved)), MutableStateFlow(listOf(live)),
            network, { network.value == 2L }, published::add)
        runCurrent()
        advanceTimeBy(350); runCurrent()
        assertEquals(mapOf(endpoint to false), published.last())

        network.value = 2L
        runCurrent()
        assertTrue(published.last().isEmpty())
        advanceTimeBy(350); runCurrent()
        assertEquals(mapOf(endpoint to true), published.last())
    }

    @Test fun anOldNetworkProbeCannotRepublishAfterTheNetworkChanges() = runTest {
        val network = MutableStateFlow<Long?>(1L)
        val published = mutableListOf<Map<String, Boolean>>()
        SavedDeviceReachability(backgroundScope, MutableStateFlow(listOf(saved)), MutableStateFlow(listOf(live)),
            network, {
                if (network.value == 1L) withContext(NonCancellable) { delay(1000); false } else true
            }, published::add)
        runCurrent()
        advanceTimeBy(350); runCurrent()
        network.value = 2L
        runCurrent()
        advanceTimeBy(1000); runCurrent()
        assertTrue(published.all { it.isEmpty() })
        advanceTimeBy(350); runCurrent()
        assertEquals(mapOf(endpoint to true), published.last())
    }
}
