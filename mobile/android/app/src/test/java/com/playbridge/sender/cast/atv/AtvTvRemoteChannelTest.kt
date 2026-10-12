package com.playbridge.sender.cast.atv

import com.playbridge.sender.cast.remote.RemoteKey
import com.playbridge.sender.model.CastProtocol
import com.playbridge.sender.model.EndpointKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AtvTvRemoteChannelTest {
    private val endpoint = EndpointKey(CastProtocol.PLAYBRIDGE, "hisense")
    private val target = AtvReceiverTarget(endpoint, "Hisense", listOf("192.168.1.22"), androidTv = true, googleCast = false)

    @Test fun hiddenRemoteDoesNotConnect() = runTest {
        val session = FakeSession()
        withChannel(session) { channel ->
            channel.setRemoteVisible(false)
            advanceUntilIdle()
            assertEquals(0, session.discoverCount)
            assertEquals(0, session.connectCount)
        }
    }

    @Test fun visiblePairedTvConnectsAndHidingDisconnects() = runTest {
        val session = FakeSession()
        withChannel(session) { channel ->
            channel.setRemoteVisible(true)
            advanceUntilIdle()
            assertEquals(1, session.connectCount)
            assertTrue(channel.state.value.connected)
            assertEquals(endpoint, channel.state.value.matchedEndpoint)
            channel.sendKey(RemoteKey.Enter)
            channel.sendKey(RemoteKey.Delete)
            channel.sendKey(RemoteKey.Up)
            channel.sendKey(RemoteKey.Up)
            assertEquals(listOf("KEYCODE_ENTER", "KEYCODE_DEL", "DPAD_UP", "DPAD_UP"), session.keys.map { it.first })
            assertTrue(session.keys.all { it.second == "short" })
            channel.setRemoteVisible(false)
            advanceUntilIdle()
            assertFalse(channel.state.value.connected)
            assertTrue(session.disconnectCount >= 1)
        }
    }

    @Test fun notTrustedStopsAfterThreeAttempts() = runTest {
        val session = FakeSession()
        session.connectError = AtvCommandException("not_trusted", "Android TV does not trust this client; pair again")
        withChannel(session) { channel ->
            channel.setRemoteVisible(true)
            advanceUntilIdle()
            assertEquals(3, session.connectCount)
            assertTrue(channel.state.value.needsRepair)
            assertFalse(channel.state.value.connected)
            advanceTimeBy(60_000)
            advanceUntilIdle()
            assertEquals(3, session.connectCount)
        }
    }

    @Test fun pinMismatchStopsAndPromptsWithoutRetry() = runTest {
        val session = FakeSession()
        session.connectError = AtvCommandException("pin_mismatch", "Android TV certificate does not match the stored pin")
        withChannel(session) { channel ->
            channel.setRemoteVisible(true)
            advanceUntilIdle()
            assertEquals(1, session.connectCount)
            assertTrue(channel.state.value.pinMismatch)
            assertFalse(channel.state.value.connected)
            advanceTimeBy(60_000)
            advanceUntilIdle()
            assertEquals(1, session.connectCount)
        }
    }

    /** Foreground child of the test. backgroundScope work stays queued through advanceUntilIdle. */
    private suspend fun TestScope.withChannel(session: FakeSession, block: suspend (AtvTvRemoteChannel) -> Unit) {
        val job = SupervisorJob(coroutineContext[Job])
        val file = MemorySecretFile()
        val store = AtvCredentialStore(file, XorCipher())
        store.saveIdentity(AtvClientIdentity("CERT", "KEY"))
        store.upsert(
            AtvTvRecord(
                fingerprint = "ab".repeat(32),
                mac = "C0:E5:DA:D8:C3:5F",
                serverName = "Hisense",
                lastIp = "192.168.1.22",
                receiverIds = setOf(endpoint.toString()),
            ),
        )
        val channel = AtvTvRemoteChannel(
            session,
            store,
            CoroutineScope(coroutineContext + job),
            MutableStateFlow(target),
            discoverTimeoutMs = 0,
        )
        try {
            block(channel)
        } finally {
            job.cancelAndJoin()
        }
    }
}

private class FakeSession : AtvControlSession {
    private val televisionState = MutableStateFlow<AtvTelevisionState?>(null)
    override val television: StateFlow<AtvTelevisionState?> = televisionState.asStateFlow()
    var connectCount = 0
    var disconnectCount = 0
    var discoverCount = 0
    var connectError: AtvCommandException? = null
    val keys = mutableListOf<Pair<String?, String>>()
    private val closed = CompletableDeferred<Unit>()

    override suspend fun discover(timeoutMs: Long): List<AtvDiscoveredDevice> {
        discoverCount++
        return emptyList()
    }
    override suspend fun generateIdentity(clientName: String) = error("unused")
    override suspend fun startPairing(host: String, certPem: String, keyPem: String, clientName: String) = error("unused")
    override suspend fun finishPairing(code: String) = error("unused")
    override suspend fun cancelPairing() = Unit
    override suspend fun connect(host: String, certPem: String, keyPem: String, pinSha256Hex: String) {
        connectCount++
        connectError?.let { throw it }
        televisionState.value = AtvTelevisionState(true, 3, 100, false, null, "Hisense", null)
    }
    override suspend fun awaitClosed() {
        suspendCancellableCoroutine<Unit> { continuation ->
            continuation.invokeOnCancellation { if (!closed.isCompleted) closed.cancel() }
        }
    }
    override suspend fun key(name: String?, code: Int?, direction: String) { keys += name to direction }
    override suspend fun volume(action: String) = Unit
    override suspend fun text(text: String) = Unit
    override suspend fun launchApp(uriOrPackage: String) = Unit
    override suspend fun power() = Unit
    override suspend fun disconnect() { disconnectCount++ }
    override fun close() = Unit
}
