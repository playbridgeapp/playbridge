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

    @Test fun foregroundConnectsWithoutRemoteAndWithoutDiscoveryWait() = runTest {
        val session = FakeSession()
        withChannel(session) { channel, _, _ ->
            channel.setRemoteVisible(false)
            advanceUntilIdle()
            assertEquals(0, session.discoverCount)
            assertEquals(1, session.connectCount)
            assertTrue(channel.state.value.connected)
            assertEquals(AtvPairingPhase.Idle, channel.pairing.value.phase)
        }
    }

    @Test fun visiblePairedTvConnectsAndHidingKeepsTheSocket() = runTest {
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
            assertTrue(channel.state.value.connected)
            assertEquals(0, session.disconnectCount)
            channel.setRemoteVisible(true)
            runCurrent()
            assertEquals(1, session.connectCount)
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

    @Test fun identityIsSavedOnlyAfterVerificationAndFailedSaveCanRetry() = runTest {
        val file = FaultSecretFile().apply { fail = true }
        val store = AtvCredentialStore(file, XorCipher())
        val session = FakeSession()
        withChannel(session, store) { channel, _, _ ->
            channel.setRemoteVisible(true)
            runCurrent()
            channel.begin()
            runCurrent()
            assertEquals(AtvPairingPhase.WaitingForCode, channel.pairing.value.phase)
            assertNull(store.identityOrNull())
            channel.submitCode("ABC123"); runCurrent()
            assertEquals(AtvPairingPhase.Failed, channel.pairing.value.phase)
            assertEquals(AtvPairingFailure.Storage, channel.pairing.value.failure)
            assertEquals(1, session.pairStarts)
            assertNull(store.identityOrNull())
            file.fail = false
            channel.begin()
            runCurrent()
            assertEquals(AtvPairingPhase.WaitingForCode, channel.pairing.value.phase)
            assertEquals(2, session.pairStarts)
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

    @Test fun backgroundGraceExpiresAtSixtySecondsAndForegroundReconnectsWithoutRemote() = runTest {
        val session = FakeSession()
        withChannel(session) { channel, _, _ ->
            runCurrent()
            assertTrue(channel.state.value.connected)
            channel.setAppForeground(false)
            advanceTimeBy(59_999); runCurrent()
            assertTrue(channel.state.value.connected)
            assertEquals(0, session.disconnectCount)
            advanceTimeBy(1); runCurrent()
            assertFalse(channel.state.value.connected)
            assertEquals(1, session.disconnectCount)
            channel.setAppForeground(true); runCurrent()
            assertTrue(channel.state.value.connected)
            assertEquals(2, session.connectCount)
        }
    }

    @Test fun hidingAndImmediatelyReopeningRemoteCannotReviveAnOldKeyboardBatch() = runTest {
        val session = FakeSession()
        withChannel(session) { channel, _, _ ->
            channel.setRemoteVisible(true); runCurrent()
            session.onKey = {
                channel.setRemoteVisible(false)
                channel.setRemoteVisible(true)
            }
            assertTrue(channel.editText(2, "stale", true) is RemoteCommandResult.Failed)
            assertEquals(listOf("KEYCODE_DEL"), session.operations)
            assertEquals(1, session.connectCount)
            assertTrue(channel.state.value.connected)
        }
    }

    @Test fun explicitAppLaunchFromCastUiUsesForegroundSocketWithoutRemoteButBackgroundCannotSend() = runTest {
        val session = FakeSession()
        withChannel(session) { channel, _, _ ->
            runCurrent()
            assertEquals(RemoteCommandResult.Submitted, channel.launchApp("com.playbridge.player"))
            assertEquals(listOf("com.playbridge.player"), session.launches)
            channel.setAppForeground(false); runCurrent()
            assertTrue(channel.state.value.connected)
            assertTrue(channel.launchApp("com.playbridge.player") is RemoteCommandResult.Failed)
            assertEquals(1, session.launches.size)
        }
    }

    @Test fun enteringRemoteOrReturningWithinGraceDoesNotRestartAnInFlightHandshake() = runTest {
        val session = FakeSession().apply { connectGate = CompletableDeferred() }
        withChannel(session) { channel, _, _ ->
            runCurrent()
            assertEquals(1, session.connectCount)
            channel.setRemoteVisible(true); runCurrent()
            channel.setRemoteVisible(false)
            channel.setAppForeground(false); runCurrent()
            advanceTimeBy(1_000); runCurrent()
            channel.setAppForeground(true); runCurrent()
            assertEquals(1, session.connectCount)
            assertEquals(0, session.disconnectCount)
            session.connectGate!!.complete(Unit); runCurrent()
            assertTrue(channel.state.value.connected)
        }
    }

    @Test fun foregroundAfterSuspendedDeadlineReconnectsEvenBeforeTheTimerRuns() = runTest {
        var elapsed = 0L
        val session = FakeSession()
        withChannel(session, nowMs = { elapsed }) { channel, _, _ ->
            runCurrent()
            channel.setAppForeground(false); runCurrent()
            elapsed = 60_001 // Simulate suspended execution: the timer has not run.
            channel.setAppForeground(true); runCurrent()
            assertEquals(2, session.connectCount)
            assertEquals(1, session.disconnectCount)
            assertTrue(channel.state.value.connected)
        }
    }

    @Test fun returningWithinGracePreservesSocketAndCancelsTheOldDeadline() = runTest {
        val session = FakeSession()
        withChannel(session) { channel, _, _ ->
            runCurrent()
            channel.setAppForeground(false)
            advanceTimeBy(30_000); runCurrent()
            channel.setAppForeground(true); runCurrent()
            advanceTimeBy(40_000); runCurrent()
            assertTrue(channel.state.value.connected)
            assertEquals(1, session.connectCount)
            assertEquals(0, session.disconnectCount)
            channel.setAppForeground(false)
            advanceTimeBy(60_000); runCurrent()
            assertFalse(channel.state.value.connected)
            assertEquals(1, session.disconnectCount)
        }
    }

    @Test fun foregroundAndRemoteEntryInterruptLongBackoffWithoutChangingTrustPolicy() = runTest {
        val session = FakeSession().apply { connectError = AtvCommandException("unreachable", "fixed") }
        withChannel(session) { channel, _, _ ->
            runCurrent()
            for (wait in listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L)) {
                advanceTimeBy(wait); runCurrent()
            }
            assertEquals(6, session.connectCount) // Next retry would be in 30 seconds.
            channel.setRemoteVisible(true); runCurrent()
            assertEquals(7, session.connectCount)
            advanceTimeBy(1_000); runCurrent()
            assertEquals(8, session.connectCount)
            channel.setRemoteVisible(false)
            channel.setAppForeground(false); runCurrent()
            channel.setAppForeground(true); runCurrent()
            assertEquals(9, session.connectCount)
            advanceTimeBy(1_000); runCurrent()
            assertEquals(10, session.connectCount)
            assertEquals(AtvPairingPhase.Idle, channel.pairing.value.phase)
        }
    }

    @Test fun silentRepairOutsideRemoteNeverStartsPairingOrShowsItsCodeState() = runTest {
        val session = FakeSession().apply { connectError = AtvCommandException("pin_mismatch", "fixed") }
        withChannel(session) { channel, _, _ ->
            runCurrent()
            assertTrue(channel.state.value.pinMismatch)
            assertEquals(AtvPairingPhase.Idle, channel.pairing.value.phase)
            channel.begin(); runCurrent()
            assertEquals(0, session.pairStarts)
            assertEquals(AtvPairingPhase.Idle, channel.pairing.value.phase)
            channel.setRemoteVisible(true); runCurrent()
            channel.begin(); runCurrent()
            assertEquals(1, session.pairStarts)
            channel.setAppForeground(false); runCurrent()
            assertEquals(AtvPairingPhase.Idle, channel.pairing.value.phase)
        }
    }

    @Test fun selectionSwitchAndDeselectCloseThePreviousSocketDuringGrace() = runTest {
        val session = FakeSession()
        withChannel(session) { channel, store, receivers ->
            val other = EndpointKey(CastProtocol.PLAYBRIDGE, "other")
            store.upsert(AtvTvRecord("cd".repeat(32), null, null, null, setOf(other.toString())))
            runCurrent()
            channel.setAppForeground(false); runCurrent()
            receivers.value = target.copy(endpoint = other)
            runCurrent()
            assertEquals(listOf("ab".repeat(32), "cd".repeat(32)), session.pins)
            assertEquals(1, session.disconnectCount)
            receivers.value = null; runCurrent()
            assertFalse(channel.state.value.connected)
            assertNull(channel.state.value.matchedEndpoint)
            assertEquals(2, session.disconnectCount)
            advanceTimeBy(60_000); runCurrent()
            assertEquals(2, session.connectCount)
        }
    }

    @Test fun passiveDiscoveryRelocatesOnlyTheVerifiedSelectedTvAndDoesNotRestartForUnrelatedRecords() = runTest {
        val session = FakeSession()
        withChannel(session) { channel, _, _ ->
            runCurrent()
            assertEquals(0, session.discoverCount)
            channel.updateDiscovery(listOf(AtvDiscoveredDevice("Living Room", "192.0.2.7", 6466,
                listOf("192.0.2.7"), listOf("bt" to "C0:E5:DA:D8:C3:5F"))))
            runCurrent()
            assertEquals(2, session.connectCount)
            assertEquals("192.0.2.7", session.hosts.last())
            channel.updateDiscovery(listOf(
                AtvDiscoveredDevice("Living Room", "192.0.2.7", 6466, listOf("192.0.2.7"), listOf("bt" to "C0:E5:DA:D8:C3:5F")),
                AtvDiscoveredDevice("Other", "192.0.2.8", 6466, listOf("192.0.2.8"), emptyList()),
            ))
            runCurrent()
            assertEquals(2, session.connectCount)
            assertTrue(channel.state.value.connected)
            channel.setAppForeground(false)
            channel.updateDiscovery(emptyList()) // Process discovery stops on background.
            runCurrent()
            assertEquals(2, session.connectCount)
            assertTrue(channel.state.value.connected)
        }
    }

    @Test fun legacyPairingMigrationIsVerifiedBeforeCommitAndKeepsOtherTvCredentials() = runTest {
        val session = FakeSession().apply {
            preparedIdentity = AtvIdentity("NEW-CERT", "NEW-KEY", "01".repeat(32), "PlayBridge (Android) fixture", true)
        }
        withChannel(session) { channel, store, receivers ->
            val old = requireNotNull(store.identityOrNull())
            val other = AtvTvRecord("cd".repeat(32), null, "Other", null, setOf("playbridge:other"))
            store.upsert(other)
            channel.setRemoteVisible(true); runCurrent()
            channel.begin(); runCurrent()
            assertTrue(session.suppliedIdentity == old)
            assertTrue(store.identityOrNull() == old)
            assertTrue(session.suppliedName == session.preparedIdentity?.clientName)
            channel.cancel(); runCurrent()
            assertTrue(store.identityOrNull() == old)
            channel.begin(); runCurrent()
            channel.submitCode("ABC123"); runCurrent()
            assertTrue(store.identityOrNull()?.certPem == "NEW-CERT")
            assertTrue(store.identityOrNull(other.fingerprint) == old)
            assertEquals(2, store.records.value.size)
            receivers.value = target.copy(endpoint = EndpointKey(CastProtocol.PLAYBRIDGE, "other"))
            runCurrent()
            assertTrue(session.certificates.last() == old.certPem)
        }
    }

    @Test fun repeatedPlainDisconnectsRetryWithoutOfferingRepair() = runTest {
        val session = FakeSession()
        withChannel(session) { channel, _, _ ->
            runCurrent()
            repeat(4) {
                session.closures.send(Unit); runCurrent()
                assertFalse(channel.state.value.needsRepair || channel.state.value.pinMismatch)
                advanceTimeBy(1_000); runCurrent()
                assertTrue(channel.state.value.connected)
            }
        }
    }

    /** Foreground child of the test. backgroundScope work stays queued through advanceUntilIdle. */
    private suspend fun TestScope.withChannel(session: FakeSession, credentialStore: AtvCredentialStore? = null, nowMs: () -> Long = { testScheduler.currentTime }, block: suspend (AtvTvRemoteChannel, AtvCredentialStore, MutableStateFlow<AtvReceiverTarget?>) -> Unit) {
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
            nowMs = nowMs,
        )
        channel.setAppForeground(true)
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
    var connectGate: CompletableDeferred<Unit>? = null
    val keys = mutableListOf<Pair<String?, String>>()
    val closures = Channel<Unit>(Channel.UNLIMITED)
    val pins = mutableListOf<String>()
    val hosts = mutableListOf<String>()
    val certificates = mutableListOf<String>()
    val launches = mutableListOf<String>()
    val operations = mutableListOf<String>()
    var preparedIdentity: AtvIdentity? = null
    var suppliedIdentity: AtvClientIdentity? = null
    var suppliedName: String? = null
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
    override suspend fun preparePairingIdentity(existing: AtvClientIdentity?): AtvIdentity {
        suppliedIdentity = existing
        return preparedIdentity ?: existing?.let { AtvIdentity(it.certPem, it.keyPem, "01".repeat(32)) } ?: generateIdentity(ATV_CLIENT_NAME)
    }
    override suspend fun startPairing(host: String, certPem: String, keyPem: String, clientName: String): AtvPairingStarted {
        pairStarts++
        suppliedName = clientName
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
        hosts += host
        certificates += certPem
        pins += pinSha256Hex
        connectGate?.await()
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
    override suspend fun launchApp(uriOrPackage: String) { launches += uriOrPackage }
    override suspend fun power() = Unit
    override suspend fun disconnect() { disconnectCount++ }
    override suspend fun close() = Unit
}
