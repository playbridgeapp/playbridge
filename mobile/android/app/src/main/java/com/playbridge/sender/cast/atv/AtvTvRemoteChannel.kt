package com.playbridge.sender.cast.atv

import com.playbridge.sender.cast.remote.RemoteCommandResult
import com.playbridge.sender.cast.remote.RemoteKey
import com.playbridge.sender.cast.remote.Support
import com.playbridge.sender.cast.remote.TextCapabilities
import com.playbridge.sender.cast.remote.TvRemoteChannel
import com.playbridge.sender.cast.remote.TvRemoteChannelState
import com.playbridge.sender.cast.remote.VolumeCapabilities
import com.playbridge.sender.cast.remote.ATV_TEXT_DELETE_CAP
import com.playbridge.sender.model.EndpointKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class AtvPairingPhase { Idle, Starting, WaitingForCode, Verifying, Failed, Paired }

enum class AtvPairingFailure { BadCode, Unreachable, Timeout, Protocol, Closed, Storage }

data class AtvPairingUi(
    val phase: AtvPairingPhase = AtvPairingPhase.Idle,
    val serverName: String? = null,
    val failure: AtvPairingFailure? = null,
)

/** Sheet-facing pairing. The same object is the [TvRemoteChannel]. */
interface AtvPairingController {
    val pairing: StateFlow<AtvPairingUi>
    fun begin()
    fun submitCode(code: String)
    fun cancel()
}

internal class AtvTvRemoteChannel(
    private val session: AtvControlSession,
    private val credentials: AtvCredentialStore,
    private val scope: CoroutineScope,
    private val receivers: StateFlow<AtvReceiverTarget?>,
    private val maxTrustRetries: Int = 3,
    private val nowMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val backoffMs: (Int) -> Long = { attempt ->
        (1_000L shl (attempt - 1).coerceAtMost(5)).coerceAtMost(30_000L)
    },
) : TvRemoteChannel, AtvPairingController {
    private var visible = false
    private var lifetime = AtvConnectionLifetime()
    private var graceJob: Job? = null
    private val connectionAllowed = MutableStateFlow(false)
    private val discovered = MutableStateFlow<List<AtvDiscoveredDevice>>(emptyList())
    private val refresh = MutableStateFlow(0)
    private val channelState = MutableStateFlow(TvRemoteChannelState())
    private val pairingState = MutableStateFlow(AtvPairingUi())
    private var pairingJob: Job? = null
    private var codeSlot: CompletableDeferred<String>? = null
    private data class PairingTarget(val endpoint: EndpointKey, val host: String)
    private var pairingTarget: PairingTarget? = null
    private var pairingOwner: EndpointKey? = null
    private var pairingGeneration = 0L
    private val pairingLock = Mutex()
    private val keyboardLock = Mutex()
    private var activeFingerprint: String? = null
    private var controlGeneration = 0L
    private var inputGeneration = 0L
    private var connecting = false
    private var connectedReceiver: AtvReceiverTarget? = null
    private var connectedHost: String? = null
    private data class TrustFailure(val needsRepair: Boolean = false, val pinMismatch: Boolean = false, val stopped: Boolean = false)
    private val trustFailures = mutableMapOf<String, TrustFailure>()
    private val storageFailures = mutableSetOf<String>()

    override val state: StateFlow<TvRemoteChannelState> = channelState.asStateFlow()
    override val pairing: StateFlow<AtvPairingUi> = pairingState.asStateFlow()

    private data class Selection(
        val receiver: AtvReceiverTarget?, val record: AtvTvRecord?, val host: String?,
        val available: Boolean, val recoveryRequired: Boolean,
    )

    init {
        scope.launch {
            val selections = combine(receivers, credentials.records, discovered, credentials.recoveryRequired) { receiver, records, devices, recovery ->
                val match = receiver?.let { AtvDeviceMatcher.match(it, devices, records) }
                // Scan removal/stop must not move a live socket back to a stale saved address.
                val host = if (match?.device == null && match?.host != null && channelState.value.connected &&
                    receiver == connectedReceiver && match.record?.fingerprint == activeFingerprint
                ) connectedHost else match?.host
                // Last-IP persistence after connect must not restart a healthy connection.
                Selection(receiver, match?.record?.copy(lastIp = null), host, match?.available == true, recovery)
            }.distinctUntilChanged()
            combine(connectionAllowed, selections, refresh) { allowed, selection, _ -> allowed to selection }
                .onEach { (allowed, selection) ->
                    if (!allowed || pairingOwner != null && pairingOwner != selection.receiver?.endpoint) cancel()
                    if (!allowed || channelState.value.matchedEndpoint != selection.receiver?.endpoint) clearConnected()
                }
                .collectLatest { (allowed, selection) -> supervise(allowed, selection) }
        }
    }

    fun setAppForeground(foreground: Boolean) {
        if (lifetime.foreground == foreground) return
        graceJob?.cancel(); graceJob = null
        val expired = lifetime.expiredAt(nowMs())
        lifetime = lifetime.foregroundChanged(foreground, nowMs())
        connectionAllowed.value = lifetime.keepConnection
        if (foreground) {
            // Preserve a live socket during grace; interrupt only a disconnected retry.
            if (expired) {
                clearConnected()
                refresh.value += 1
            } else retryRemote()
        } else {
            inputGeneration++
            cancel() // Pairing never survives leaving the foreground UI.
            val deadline = requireNotNull(lifetime.backgroundDeadlineMs)
            graceJob = scope.launch {
                delay((deadline - nowMs()).coerceAtLeast(0))
                lifetime = lifetime.graceExpired()
                connectionAllowed.value = lifetime.keepConnection
                clearConnected()
            }
        }
    }

    fun updateDiscovery(devices: List<AtvDiscoveredDevice>) { discovered.value = devices }

    fun retryRemote() { if (!channelState.value.connected && !connecting) refresh.value += 1 }

    override fun setRemoteVisible(visible: Boolean) {
        if (this.visible == visible) return
        this.visible = visible
        if (visible && lifetime.foreground) retryRemote()
        if (!visible) {
            inputGeneration++
            cancel()
        }
    }

    override fun begin() {
        if (!visible || !lifetime.foreground) return
        val phase = pairingState.value.phase
        if (phase == AtvPairingPhase.Starting || phase == AtvPairingPhase.WaitingForCode || phase == AtvPairingPhase.Verifying) return
        val target = pairingTarget
        if (target == null || target.endpoint != receivers.value?.endpoint) {
            pairingState.value = AtvPairingUi(AtvPairingPhase.Failed, failure = AtvPairingFailure.Unreachable)
            return
        }
        val generation = ++pairingGeneration
        pairingOwner = target.endpoint
        pairingJob?.cancel()
        pairingState.value = AtvPairingUi(AtvPairingPhase.Starting)
        pairingJob = scope.launch {
            pairingLock.withLock {
                ensurePairingCurrent(generation, target)
                try {
                    session.cancelPairing()
                    runPairing(generation, target)
                } catch (error: AtvCommandException) {
                    ensurePairingCurrent(generation, target)
                    pairingState.value = AtvPairingUi(AtvPairingPhase.Failed, failure = pairingFailure(error.kind))
                } finally {
                    withContext(NonCancellable) { runCatching { session.cancelPairing() } }
                }
            }
        }
    }

    override fun submitCode(code: String) {
        val normalized = normalizePairingCode(code)
        if (normalized == null) {
            pairingState.value = pairingState.value.copy(phase = AtvPairingPhase.Failed, failure = AtvPairingFailure.BadCode)
            return
        }
        codeSlot?.complete(normalized)
    }

    override fun cancel() {
        pairingGeneration++
        pairingJob?.cancel()
        pairingJob = null
        pairingOwner = null
        codeSlot = null
        pairingState.value = AtvPairingUi()
    }

    override suspend fun sendKey(key: RemoteKey): RemoteCommandResult {
        if (!channelState.value.connected || !visible || !lifetime.foreground) return RemoteCommandResult.Failed("Full control is not connected")
        return when (key) {
            RemoteKey.Mute -> runCatchingCommand { session.volume("mute") }
            RemoteKey.Power -> runCatchingCommand { session.power() }
            else -> {
                val name = atvKeyName(key) ?: return RemoteCommandResult.Failed("invalid Android TV remote command or protocol message")
                runCatchingCommand { session.key(name = name, code = null, direction = ATV_KEY_SHORT) }
            }
        }
    }

    override suspend fun adjustVolume(steps: Int): RemoteCommandResult {
        if (!channelState.value.connected || !visible || !lifetime.foreground) return RemoteCommandResult.Failed("Full control is not connected")
        if (steps == 0 || steps !in -100..100) return RemoteCommandResult.Failed("Volume step is out of range")
        val action = if (steps > 0) "up" else "down"
        return runCatchingCommand {
            repeat(kotlin.math.abs(steps)) { session.volume(action) }
        }
    }

    override suspend fun replaceText(text: String): RemoteCommandResult {
        if (!channelState.value.connected || !visible || !lifetime.foreground) return RemoteCommandResult.Failed("Full control is not connected")
        if (text.isEmpty()) return RemoteCommandResult.Failed("Text is empty")
        return runCatchingCommand { session.text(text) }
    }

    override suspend fun deleteText(): RemoteCommandResult {
        if (!channelState.value.connected || !visible || !lifetime.foreground) return RemoteCommandResult.Failed("Full control is not connected")
        return runCatchingCommand { session.key(name = "KEYCODE_DEL", code = null, direction = ATV_KEY_SHORT) }
    }

    override suspend fun editText(deletes: Int, text: String, enter: Boolean): RemoteCommandResult {
        if (deletes !in 0..ATV_TEXT_DELETE_CAP || text.codePointCount(0, text.length) > ATV_TEXT_DELETE_CAP) {
            return RemoteCommandResult.Failed("Text edit is too long")
        }
        val generation = controlGeneration
        val input = inputGeneration
        val fingerprint = activeFingerprint
        return keyboardLock.withLock {
            runCatchingCommand {
                fun checkOwner() {
                    if (!channelState.value.connected || fingerprint == null || activeFingerprint != fingerprint || controlGeneration != generation || inputGeneration != input ||
                        !visible || !lifetime.foreground || channelState.value.matchedEndpoint != receivers.value?.endpoint
                    ) throw AtvCommandException("closed", fixedMessage("closed"))
                }
                checkOwner()
                repeat(deletes) {
                    checkOwner()
                    session.key(name = "KEYCODE_DEL", code = null, direction = ATV_KEY_SHORT)
                }
                if (text.isNotEmpty()) {
                    checkOwner()
                    session.text(text)
                }
                if (enter) {
                    checkOwner()
                    session.key(name = "KEYCODE_ENTER", code = null, direction = ATV_KEY_SHORT)
                }
            }
        }
    }

    override suspend fun launchApp(packageId: String): RemoteCommandResult {
        // Cast/device UI can explicitly open PlayBridge before Remote is mounted.
        if (!channelState.value.connected || !lifetime.foreground) return RemoteCommandResult.Failed("Full control is not connected")
        val id = packageId.trim()
        if (id.isEmpty()) return RemoteCommandResult.Failed("App package is empty")
        return runCatchingCommand { session.launchApp(id) }
    }

    override suspend fun power(): RemoteCommandResult {
        if (!channelState.value.connected || !visible || !lifetime.foreground) return RemoteCommandResult.Failed("Full control is not connected")
        return runCatchingCommand { session.power() }
    }

    private suspend fun supervise(allowed: Boolean, selection: Selection) {
        clearConnected()
        pairingTarget = null
        val receiver = selection.receiver
        if (!allowed || receiver == null) {
            channelState.value = TvRemoteChannelState()
            return
        }
        try {
            // The passive picker scan can relocate a verified owner, but never delays connect.
            val record = selection.record
            val host = selection.host
            pairingTarget = host?.let { PairingTarget(receiver.endpoint, it) }
            if (record != null && !selection.recoveryRequired &&
                trustFailures[record.fingerprint]?.stopped != true && host != null
            ) {
                connectLoop(receiver, record, host)
            } else if (selection.available) {
                publishAvailable(receiver, record, host)
                awaitCancellation()
            }
        } finally {
            clearConnected()
            activeFingerprint = null
            pairingTarget = null
        }
    }

    private suspend fun connectLoop(receiver: AtvReceiverTarget, record: AtvTvRecord, host: String) {
        activeFingerprint = record.fingerprint
        var attempt = 1
        while (true) {
            val generation = ++controlGeneration
            var started = false
            try {
                val identity = credentials.identityOrNull(record.fingerprint)
                if (identity == null) {
                    publishAvailable(receiver, record, host)
                    awaitCancellation()
                }
                started = true
                connecting = true
                session.connect(host, identity.certPem, identity.keyPem, record.fingerprint)
                connecting = false
                currentCoroutineContext().ensureActive()
                if (!connectionAllowed.value || receivers.value?.endpoint != receiver.endpoint) throw CancellationException("Control owner changed")
                credentials.updateLastIp(record.fingerprint, host)
                storageFailures.remove(record.fingerprint)
                trustFailures.remove(record.fingerprint)
                connectedReceiver = receiver
                connectedHost = host
                publishConnected(receiver, record, session.television.value)
                attempt = 1
                coroutineScope {
                    val collector = launch {
                        session.television.collect { television ->
                            if (television != null && channelState.value.connected) publishConnected(receiver, record, television)
                        }
                    }
                    try {
                        session.awaitClosed()
                    } finally {
                        collector.cancelAndJoin()
                    }
                }
                throw AtvCommandException("closed", fixedMessage("closed"))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: AtvStorageException) {
                connecting = false
                storageFailures.add(record.fingerprint)
                publishAvailable(receiver, record, host)
                withContext(NonCancellable) { runCatching { session.disconnect() } }
                started = false
                // Retry only after a fresh foreground/selection or verified pairing.
                awaitCancellation()
            } catch (error: AtvCommandException) {
                connecting = false
                when (error.kind) {
                    "not_trusted" -> {
                        trustFailures[record.fingerprint] = TrustFailure(needsRepair = true, stopped = attempt >= maxTrustRetries)
                        publishAvailable(receiver, record, host)
                        if (attempt >= maxTrustRetries) awaitCancellation()
                        delay(backoffMs(attempt))
                        attempt++
                    }
                    "pin_mismatch" -> {
                        trustFailures[record.fingerprint] = TrustFailure(pinMismatch = true, stopped = true)
                        publishAvailable(receiver, record, host)
                        awaitCancellation()
                    }
                    else -> {
                        publishAvailable(receiver, record, host)
                        delay(backoffMs(attempt))
                        attempt++
                    }
                }
            } finally {
                connecting = false
                if (started) {
                    if (controlGeneration == generation) clearConnected()
                    withContext(NonCancellable) {
                        runCatching { session.disconnect() }
                    }
                }
            }
        }
    }

    private suspend fun runPairing(generation: Long, target: PairingTarget) {
        val host = target.host
        val endpoint = target.endpoint
        try {
            val previousIdentity = credentials.pairingIdentityOrNull()
            val prepared = session.preparePairingIdentity(previousIdentity)
            ensurePairingCurrent(generation, target)
            val stored = AtvClientIdentity(prepared.certPem, prepared.keyPem)
            ensurePairingCurrent(generation, target)
            val started = session.startPairing(host, stored.certPem, stored.keyPem, prepared.clientName ?: ATV_CLIENT_NAME)
            ensurePairingCurrent(generation, target)
            val deferred = CompletableDeferred<String>()
            codeSlot = deferred
            pairingState.value = AtvPairingUi(AtvPairingPhase.WaitingForCode, serverName = started.serverName)
            val code = try {
                deferred.await()
            } finally {
                if (codeSlot === deferred) codeSlot = null
            }
            ensurePairingCurrent(generation, target)
            pairingState.value = AtvPairingUi(AtvPairingPhase.Verifying, serverName = started.serverName ?: pairingState.value.serverName)
            val paired = session.finishPairing(code)
            ensurePairingCurrent(generation, target)
            val previousPins = credentials.records.value.filter { endpoint.toString() in it.receiverIds }.map { it.fingerprint }
            credentials.saveVerifiedPairing(
                stored,
                AtvTvRecord(
                    fingerprint = paired.fingerprint,
                    mac = paired.mac,
                    serverName = paired.serverName ?: started.serverName,
                    lastIp = host,
                    receiverIds = setOf(endpoint.toString()),
                ),
                preserveOtherPairings = prepared.replacesLegacy,
            )
            previousPins.forEach(trustFailures::remove)
            previousPins.forEach(storageFailures::remove)
            trustFailures.remove(paired.fingerprint)
            storageFailures.remove(paired.fingerprint)
            pairingState.value = AtvPairingUi(AtvPairingPhase.Paired, serverName = paired.serverName ?: started.serverName)
            refresh.value += 1
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: AtvStorageException) {
            ensurePairingCurrent(generation, target)
            pairingState.value = AtvPairingUi(AtvPairingPhase.Failed, failure = AtvPairingFailure.Storage)
        } catch (error: AtvCommandException) {
            ensurePairingCurrent(generation, target)
            pairingState.value = AtvPairingUi(AtvPairingPhase.Failed, failure = pairingFailure(error.kind))
        }
    }

    private suspend fun ensurePairingCurrent(generation: Long, target: PairingTarget) {
        currentCoroutineContext().ensureActive()
        if (generation != pairingGeneration || !visible || !lifetime.foreground || receivers.value?.endpoint != target.endpoint) {
            throw CancellationException("Pairing owner changed")
        }
    }

    private fun clearConnected() {
        channelState.value = channelState.value.copy(
            connected = false, keys = emptySet(), volume = VolumeCapabilities(), text = TextCapabilities(), powerOn = null,
        )
    }

    private fun publishConnected(receiver: AtvReceiverTarget, record: AtvTvRecord, television: AtvTelevisionState?) {
        if (!connectionAllowed.value || receivers.value?.endpoint != receiver.endpoint || activeFingerprint != record.fingerprint) return
        val level = television?.volumeLevel
        val maximum = television?.volumeMax
        channelState.value = TvRemoteChannelState(
            matchedEndpoint = receiver.endpoint,
            physicalDeviceId = physicalId(record),
            paired = true,
            connected = true,
            keys = CONNECTED_KEYS,
            volume = VolumeCapabilities(
                adjust = Support.Supported,
                level = level,
                maximum = maximum,
                muted = television?.volumeMuted,
                reportedPercent = if (level != null && maximum != null && maximum > 0) (level * 100 / maximum).coerceIn(0, 100) else null,
            ),
            text = TextCapabilities(Support.Supported, Support.Supported, Support.Supported),
            needsRepair = false,
            pinMismatch = false,
            powerOn = television?.isOn,
            serverName = record.serverName,
            fullControlAvailable = true,
        )
    }

    private fun publishAvailable(receiver: AtvReceiverTarget, record: AtvTvRecord?, host: String?) {
        val failure = record?.let { trustFailures[it.fingerprint] }
        channelState.value = TvRemoteChannelState(
            matchedEndpoint = receiver.endpoint,
            physicalDeviceId = record?.let(::physicalId),
            paired = record != null,
            connected = false,
            needsRepair = failure?.needsRepair == true || credentials.recoveryRequired.value ||
                credentials.records.value.count { receiver.endpoint.toString() in it.receiverIds } > 1,
            pinMismatch = failure?.pinMismatch == true,
            storageFailure = record?.let { it.fingerprint in storageFailures } == true,
            serverName = record?.serverName,
            fullControlAvailable = true,
        )
        pairingTarget = host?.let { PairingTarget(receiver.endpoint, it) }
    }

    private suspend fun runCatchingCommand(block: suspend () -> Unit): RemoteCommandResult = try {
        block()
        RemoteCommandResult.Submitted
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: AtvCommandException) {
        RemoteCommandResult.Failed(error.message ?: fixedMessage(error.kind))
    }

    private companion object {
        val CONNECTED_KEYS = setOf(
            RemoteKey.Up, RemoteKey.Down, RemoteKey.Left, RemoteKey.Right, RemoteKey.Ok,
            RemoteKey.Back, RemoteKey.Home, RemoteKey.Enter, RemoteKey.Delete, RemoteKey.Menu,
            RemoteKey.Mute, RemoteKey.Power,
        )
    }
}

internal fun physicalId(record: AtvTvRecord): String = record.mac?.takeIf { it.isNotBlank() } ?: record.fingerprint

internal fun normalizePairingCodeInput(raw: String): String =
    raw.filter { it.isDigit() || it.uppercaseChar() in 'A'..'F' }.uppercase().take(6)

internal fun normalizePairingCode(raw: String): String? = normalizePairingCodeInput(raw).takeIf { it.length == 6 }

internal fun pairingFailure(kind: String): AtvPairingFailure = when (kind) {
    "bad_code" -> AtvPairingFailure.BadCode
    "unreachable" -> AtvPairingFailure.Unreachable
    "timeout" -> AtvPairingFailure.Timeout
    "closed" -> AtvPairingFailure.Closed
    else -> AtvPairingFailure.Protocol
}
