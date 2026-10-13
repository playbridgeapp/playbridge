package com.playbridge.sender.cast.atv

import com.playbridge.sender.cast.remote.RemoteKey
import com.playbridge.sender.model.CastProtocol
import com.playbridge.sender.model.EndpointKey
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runCurrent
import com.playbridge.sender.cast.remote.RemoteCommandResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AtvTvRemoteChannelTest {
    private val endpoint = EndpointKey(CastProtocol.PLAYBRIDGE, "hisense")
    private val target = AtvReceiverTarget(endpoint, "Hisense", listOf("192.168.1.22"), androidTv = true, googleCast = false)

    @Test fun hiddenRemoteDoesNotConnect() = runTest {
        val session = FakeSession()
        withChannel(session) { channel, _, _ ->
            channel.setRemoteVisible(false)
            advanceUntilIdle()
            assertEquals(0, session.discoverCount)
            assertEquals(0, session.connectCount)
        }
    }

    @Test fun visiblePairedTvConnectsAndHidingDisconnects() = runTest {
        val session = FakeSession()
        withChannel(session) { channel, _, _ ->
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
        withChannel(session) { channel, _, _ ->
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
        withChannel(session) { channel, _, _ ->
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

    @Test fun normalClosureClearsConnectedAndReconnectsAfterBackoff() = runTest {
        val session = FakeSession()
        withChannel(session) { channel, _, _ ->
            channel.setRemoteVisible(true)
            runCurrent()
            assertTrue(channel.state.value.connected)
            session.closures.send(Unit)
            runCurrent()
            assertFalse(channel.state.value.connected)
            assertEquals(1, session.connectCount)
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(2, session.connectCount)
            assertTrue(channel.state.value.connected)
        }
    }

    @Test fun forgettingAndClearingReceiverImmediatelyClearReadiness() = runTest {
        val session = FakeSession()
        withChannel(session) { channel, store, receivers ->
            channel.setRemoteVisible(true)
            runCurrent()
            receivers.value = null
            runCurrent()
            assertFalse(channel.state.value.connected)
            receivers.value = target
            runCurrent()
            assertTrue(channel.state.value.connected)
            store.forget("ab".repeat(32))
            runCurrent()
            assertFalse(channel.state.value.connected)
            assertTrue(channel.state.value.keys.isEmpty())
        }
    }

    @Test fun failedTvDoesNotPoisonHealthyTvAndFailureSurvivesReturn() = runTest {
        val session = FakeSession()
        withChannel(session) { channel, store, receivers ->
            val other = EndpointKey(CastProtocol.PLAYBRIDGE, "other")
            store.upsert(AtvTvRecord("cd".repeat(32), null, null, null, receiverIds = setOf(other.toString())))
            session.connectError = AtvCommandException("pin_mismatch", "fixed")
            channel.setRemoteVisible(true)
            runCurrent()
            assertTrue(channel.state.value.pinMismatch)
            session.connectError = null
            receivers.value = target.copy(endpoint = other, addresses = listOf("192.168.1.23"))
            runCurrent()
            assertTrue(channel.state.value.connected)
            assertFalse(channel.state.value.pinMismatch)
            assertFalse(channel.state.value.needsRepair)
            val attempts = session.connectCount
            receivers.value = target
            runCurrent()
            assertTrue(channel.state.value.pinMismatch)
            assertFalse(channel.state.value.connected)
            assertEquals(attempts, session.connectCount)
        }
    }

    @Test fun waitingForCodeCancelsOnNavigationAndReceiverSwitch() = runTest {
        for (hide in listOf(true, false)) {
            val session = FakeSession()
            withChannel(session) { channel, _, receivers ->
                channel.setRemoteVisible(true)
                runCurrent()
                channel.begin()
                runCurrent()
                assertEquals(AtvPairingPhase.WaitingForCode, channel.pairing.value.phase)
                if (hide) channel.setRemoteVisible(false) else receivers.value = null
                runCurrent()
                assertEquals(AtvPairingPhase.Idle, channel.pairing.value.phase)
                assertTrue(session.pairCancels >= 2)
                channel.submitCode("ABC123")
                runCurrent()
                assertEquals(0, session.pairFinishes)
            }
        }
    }

    @Test fun notTrustedStopIsScopedToFingerprintWhenSwitchingTvs() = runTest {
        val session = FakeSession()
        withChannel(session) { channel, store, receivers ->
            val other = EndpointKey(CastProtocol.PLAYBRIDGE, "other")
            store.upsert(AtvTvRecord("cd".repeat(32), null, null, null, setOf(other.toString())))
            session.connectError = AtvCommandException("not_trusted", "fixed")
            channel.setRemoteVisible(true)
            advanceUntilIdle()
            assertEquals(3, session.connectCount)
            session.connectError = null
            receivers.value = target.copy(endpoint = other)
            runCurrent()
            assertTrue(channel.state.value.connected)
            assertFalse(channel.state.value.needsRepair)
            receivers.value = target
            runCurrent()
            assertFalse(channel.state.value.connected)
            assertTrue(channel.state.value.needsRepair)
            assertEquals(4, session.connectCount)
        }
    }

    @Test fun cancelledVerificationCannotPersistPinAfterHideOrReceiverSwitch() = runTest {
        for (hide in listOf(true, false)) {
            val session = FakeSession()
            session.finishGate = CompletableDeferred()
            withChannel(session) { channel, store, receivers ->
                channel.setRemoteVisible(true)
                runCurrent()
                channel.begin()
                runCurrent()
                channel.submitCode("ABC123")
                runCurrent()
                assertEquals(AtvPairingPhase.Verifying, channel.pairing.value.phase)
                if (hide) channel.setRemoteVisible(false) else receivers.value = target.copy(endpoint = EndpointKey(CastProtocol.PLAYBRIDGE, "other"))
                runCurrent()
                session.finishGate!!.complete(Unit)
                runCurrent()
                assertEquals(listOf("ab".repeat(32)), store.records.value.map { it.fingerprint })
                assertEquals(AtvPairingPhase.Idle, channel.pairing.value.phase)
            }
        }
    }

    @Test fun sheetCancelAndRetryWaitForPreviousCleanup() = runTest {
        val session = FakeSession()
        withChannel(session) { channel, _, _ ->
            channel.setRemoteVisible(true)
            runCurrent()
            channel.begin()
            runCurrent()
            assertEquals(AtvPairingPhase.WaitingForCode, channel.pairing.value.phase)
            session.cleanupGate = CompletableDeferred()
            channel.cancel()
            runCurrent()
            channel.begin()
            runCurrent()
            assertEquals(1, session.pairStarts)
            session.cleanupGate!!.complete(Unit)
            runCurrent()
            assertEquals(2, session.pairStarts)
            assertEquals(AtvPairingPhase.WaitingForCode, channel.pairing.value.phase)
            channel.cancel()
            runCurrent()
            assertEquals(AtvPairingPhase.Idle, channel.pairing.value.phase)
        }
    }

    @Test fun verifiedRepairReplacesOldPinAndResumesControl() = runTest {
        val session = FakeSession()
        session.connectError = AtvCommandException("pin_mismatch", "fixed")
        withChannel(session) { channel, store, _ ->
            channel.setRemoteVisible(true)
            runCurrent()
            assertTrue(channel.state.value.pinMismatch)
            channel.begin()
            runCurrent()
            session.connectError = null
            channel.submitCode("ABC123")
            runCurrent()
            assertEquals(listOf("ef".repeat(32)), store.records.value.map { it.fingerprint })
            assertEquals("ef".repeat(32), session.pins.last())
            assertTrue(channel.state.value.connected)
            assertFalse(channel.state.value.pinMismatch)
        }
    }

    @Test fun pairingCancelErrorPublishesFixedFailureAndCanRetry() = runTest {
        val session = FakeSession()
        withChannel(session) { channel, _, _ ->
            channel.setRemoteVisible(true)
            runCurrent()
            session.cancelError = AtvCommandException("timeout", "fixed")
            channel.begin()
            runCurrent()
            assertEquals(AtvPairingPhase.Failed, channel.pairing.value.phase)
            assertEquals(AtvPairingFailure.Timeout, channel.pairing.value.failure)
            assertEquals(0, session.pairStarts)
            session.cancelError = null
            channel.begin()
            runCurrent()
            assertEquals(AtvPairingPhase.WaitingForCode, channel.pairing.value.phase)
            assertEquals(1, session.pairStarts)
        }
    }

    @Test fun reconnectCannotContinueOrAcceptQueuedEditsFromPreviousConnection() = runTest {
        val session = FakeSession()
        withChannel(session) { channel, _, _ ->
            channel.setRemoteVisible(true)
            runCurrent()
            session.keyGate = CompletableDeferred()
            val first = async { channel.editText(2, "new", true) }
            runCurrent()
            val queued = async { channel.editText(1, "other", false) }
            runCurrent()
            session.closures.send(Unit)
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(2, session.connectCount)
            assertTrue(channel.state.value.connected)
            session.keyGate!!.complete(Unit)
            assertTrue(first.await() is RemoteCommandResult.Failed)
            assertTrue(queued.await() is RemoteCommandResult.Failed)
            assertEquals(listOf("KEYCODE_DEL"), session.operations)
        }
    }

    @Test fun keyboardBatchAcknowledgesEveryDeleteBeforeInsertAndEnter() = runTest {
        val session = FakeSession()
        withChannel(session) { channel, _, _ ->
            channel.setRemoteVisible(true)
            runCurrent()
            assertEquals(RemoteCommandResult.Submitted, channel.editText(64, "new", true))
            assertEquals(List(64) { "KEYCODE_DEL" } + listOf("text", "KEYCODE_ENTER"), session.operations)
        }
    }

    @Test fun keyboardBatchStopsOnOwnerLossOrFirstFailure() = runTest {
        for (failure in listOf(true, false)) {
            val session = FakeSession()
            withChannel(session) { channel, _, _ ->
                channel.setRemoteVisible(true)
                runCurrent()
                session.onKey = {
                    if (failure) throw AtvCommandException("closed", "fixed")
                    channel.setRemoteVisible(false)
                }
                assertTrue(channel.editText(2, "new", true) is RemoteCommandResult.Failed)
                assertEquals(listOf("KEYCODE_DEL"), session.operations)
            }
        }
    }

    @Test fun failedAddressSaveClosesControlAndCanRetryWithoutLosingTheSupervisor() = runTest {
        val file = FaultSecretFile()
        val store = AtvCredentialStore(file, XorCipher()).apply {
            saveIdentity(AtvClientIdentity("CERT", "KEY"))
            upsert(AtvTvRecord("ab".repeat(32), null, "TV", "10.0.0.1", setOf(endpoint.toString())))
        }
        file.fail = true
        val session = FakeSession()
        withChannel(session, store) { channel, _, _ ->
            channel.setRemoteVisible(true)
            runCurrent()
            assertFalse(channel.state.value.connected)
            assertTrue(channel.state.value.storageFailure)
            assertTrue(session.disconnectCount >= 1)
            assertEquals("10.0.0.1", store.records.value.single().lastIp)
            channel.setRemoteVisible(false)
            runCurrent()
            file.fail = false
            channel.setRemoteVisible(true)
            runCurrent()
            assertTrue(channel.state.value.connected)
            assertFalse(channel.state.value.storageFailure)
            assertEquals(target.addresses.first(), AtvCredentialStore(file, XorCipher()).records.value.single().lastIp)
        }
    }

    @Test fun failedIdentitySaveShowsFixedPairingFailureAndRetryStillWorks() = runTest {
        val file = FaultSecretFile().apply { fail = true }
        val store = AtvCredentialStore(file, XorCipher())
        val session = FakeSession()
        withChannel(session, store) { channel, _, _ ->
            channel.setRemoteVisible(true)
            runCurrent()
            channel.begin()
            runCurrent()
            assertEquals(AtvPairingPhase.Failed, channel.pairing.value.phase)
            assertEquals(AtvPairingFailure.Storage, channel.pairing.value.failure)
            assertEquals(0, session.pairStarts)
            assertNull(store.identityOrNull())
            file.fail = false
            channel.begin()
            runCurrent()
            assertEquals(AtvPairingPhase.WaitingForCode, channel.pairing.value.phase)
            assertEquals(1, session.pairStarts)
        }
    }

    @Test fun failedVerifiedPairingSaveDoesNotPublishPairedOrReplaceThePin() = runTest {
        val file = FaultSecretFile()
        val store = AtvCredentialStore(file, XorCipher()).apply {
            saveIdentity(AtvClientIdentity("CERT", "KEY"))
            upsert(AtvTvRecord("ab".repeat(32), null, "TV", target.addresses.first(), setOf(endpoint.toString())))
        }
        withChannel(FakeSession(), store) { channel, _, _ ->
            channel.setRemoteVisible(true)
            runCurrent()
            file.fail = true
            channel.begin()
            runCurrent()
            channel.submitCode("ABC123")
            runCurrent()
            assertEquals(AtvPairingPhase.Failed, channel.pairing.value.phase)
            assertEquals(AtvPairingFailure.Storage, channel.pairing.value.failure)
            assertEquals("ab".repeat(32), store.records.value.single().fingerprint)
            assertEquals("ab".repeat(32), AtvCredentialStore(file, XorCipher()).records.value.single().fingerprint)
        }
    }

    @Test fun unreadableKeyPromptsPairAgainAndOnlyVerifiedPairingRewritesTheFile() = runTest {
        for (failWrite in listOf(false, true)) {
            val keys = FixtureKeystoreKeys()
            val file = FaultSecretFile()
            AtvCredentialStore(file, AndroidAtvCipher(keys)).apply {
                saveIdentity(AtvClientIdentity("OLD-CERT", "OLD-KEY"))
                upsert(AtvTvRecord("ab".repeat(32), null, "TV", target.addresses.first(), setOf(endpoint.toString())))
            }
            val before = file.read()!!
            keys.invalid = true
            val store = AtvCredentialStore(file, AndroidAtvCipher(keys))
            val session = FakeSession()
            withChannel(session, store) { channel, _, _ ->
                channel.setRemoteVisible(true)
                runCurrent()
                assertTrue(channel.state.value.needsRepair)
                assertFalse(channel.state.value.connected)
                assertEquals(0, session.connectCount)
                channel.begin()
                runCurrent()
                assertEquals(AtvPairingPhase.WaitingForCode, channel.pairing.value.phase)
                assertArrayEquals(before, file.read())
                assertEquals(0, keys.rotations)
                file.fail = failWrite
                channel.submitCode("ABC123")
                runCurrent()
                if (failWrite) {
                    assertEquals(AtvPairingFailure.Storage, channel.pairing.value.failure)
                    assertTrue(store.recoveryRequired.value)
                    assertArrayEquals(before, file.read())
                    assertTrue(store.records.value.isEmpty())
                    assertEquals(0, session.connectCount)
                    file.fail = false
                    channel.begin()
                    runCurrent()
                    channel.submitCode("ABC123")
                    runCurrent()
                }
                assertEquals(AtvPairingPhase.Paired, channel.pairing.value.phase)
                assertFalse(store.recoveryRequired.value)
                assertTrue(channel.state.value.connected)
                assertEquals("ef".repeat(32), AtvCredentialStore(file, AndroidAtvCipher(keys)).records.value.single().fingerprint)
            }
        }
    }

    /** Foreground child of the test. backgroundScope work stays queued through advanceUntilIdle. */
    private suspend fun TestScope.withChannel(session: FakeSession, credentialStore: AtvCredentialStore? = null, block: suspend (AtvTvRemoteChannel, AtvCredentialStore, MutableStateFlow<AtvReceiverTarget?>) -> Unit) {
        val job = SupervisorJob(coroutineContext[Job])
        val file = MemorySecretFile()
        val store = credentialStore ?: AtvCredentialStore(file, XorCipher()).apply {
            saveIdentity(AtvClientIdentity("CERT", "KEY"))
            upsert(
            AtvTvRecord(
                fingerprint = "ab".repeat(32),
                mac = "C0:E5:DA:D8:C3:5F",
                serverName = "Hisense",
                lastIp = "192.168.1.22",
                receiverIds = setOf(endpoint.toString()),
            ),
        )
        }
        val receivers = MutableStateFlow<AtvReceiverTarget?>(target)
        val channel = AtvTvRemoteChannel(
            session,
            store,
            CoroutineScope(coroutineContext + job),
            receivers,
            discoverTimeoutMs = 0,
        )
        try {
            block(channel, store, receivers)
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
    val closures = Channel<Unit>(Channel.UNLIMITED)
    val pins = mutableListOf<String>()
    val operations = mutableListOf<String>()
    var pairStarts = 0
    var pairCancels = 0
    var pairFinishes = 0
    var finishGate: CompletableDeferred<Unit>? = null
    var cleanupGate: CompletableDeferred<Unit>? = null
    var onKey: (() -> Unit)? = null
    var keyGate: CompletableDeferred<Unit>? = null
    var cancelError: AtvCommandException? = null

    override suspend fun discover(timeoutMs: Long): List<AtvDiscoveredDevice> {
        discoverCount++
        return emptyList()
    }
    override suspend fun generateIdentity(clientName: String) = AtvIdentity("CERT", "KEY", "01".repeat(32))
    override suspend fun startPairing(host: String, certPem: String, keyPem: String, clientName: String): AtvPairingStarted {
        pairStarts++
        return AtvPairingStarted("TV", "ab".repeat(32), null)
    }
    override suspend fun finishPairing(code: String): AtvPaired {
        pairFinishes++
        withContext(NonCancellable) { finishGate?.await() }
        return AtvPaired("ef".repeat(32), null, "TV")
    }
    override suspend fun cancelPairing() { pairCancels++; cleanupGate?.await(); cancelError?.let { throw it } }
    override suspend fun connect(host: String, certPem: String, keyPem: String, pinSha256Hex: String) {
        connectCount++
        pins += pinSha256Hex
        connectError?.let { throw it }
        televisionState.value = AtvTelevisionState(true, 3, 100, false, null, "Hisense", null)
    }
    override suspend fun awaitClosed() { closures.receive() }
    override suspend fun key(name: String?, code: Int?, direction: String) {
        keys += name to direction
        operations += name.orEmpty()
        onKey?.invoke()
        keyGate?.await()
    }
    override suspend fun volume(action: String) = Unit
    override suspend fun text(text: String) { operations += "text" }
    override suspend fun launchApp(uriOrPackage: String) = Unit
    override suspend fun power() = Unit
    override suspend fun disconnect() { disconnectCount++ }
    override suspend fun close() = Unit
}
