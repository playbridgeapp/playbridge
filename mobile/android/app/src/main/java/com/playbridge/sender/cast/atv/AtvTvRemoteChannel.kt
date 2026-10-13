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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class AtvPairingPhase { Idle, Starting, WaitingForCode, Verifying, Failed, Paired }

enum class AtvPairingFailure { BadCode, Unreachable, Timeout, Protocol, Closed }

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
    private val discoverTimeoutMs: Long = 4_000L,
    private val maxTrustRetries: Int = 3,
    private val backoffMs: (Int) -> Long = { attempt ->
        (1_000L shl (attempt - 1).coerceAtMost(5)).coerceAtMost(30_000L)
    },
) : TvRemoteChannel, AtvPairingController {
    private val visible = MutableStateFlow(false)
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
    private data class TrustFailure(val needsRepair: Boolean = false, val pinMismatch: Boolean = false, val stopped: Boolean = false)
    private val trustFailures = mutableMapOf<String, TrustFailure>()

    override val state: StateFlow<TvRemoteChannelState> = channelState.asStateFlow()
    override val pairing: StateFlow<AtvPairingUi> = pairingState.asStateFlow()

    init {
        scope.launch {
            combine(visible, receivers, refresh) { isVisible, receiver, _ -> isVisible to receiver }
                .onEach { (isVisible, receiver) ->
                    if (!isVisible || pairingOwner != null && pairingOwner != receiver?.endpoint) cancel()
                    if (!isVisible || channelState.value.matchedEndpoint != receiver?.endpoint) clearConnected()
                }
                .collectLatest { (isVisible, receiver) -> supervise(isVisible, receiver) }
        }
        scope.launch {
            credentials.records
                .map { records -> records.map { it.fingerprint }.toSet() }
                .distinctUntilChanged()
                .collect { fingerprints ->
                    val active = activeFingerprint
                    if (active != null && active !in fingerprints) {
                        activeFingerprint = null
                        clearConnected()
                        refresh.value += 1
                    }
                }
        }
    }

    override fun setRemoteVisible(visible: Boolean) {
        this.visible.value = visible
        if (!visible) {
            clearConnected()
            cancel()
        }
    }

    override fun begin() {
        val phase = pairingState.value.phase
        if (phase == AtvPairingPhase.Starting || phase == AtvPairingPhase.WaitingForCode || phase == AtvPairingPhase.Verifying) return
        val target = pairingTarget
        if (!visible.value || target == null || target.endpoint != receivers.value?.endpoint) {
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
        if (!channelState.value.connected) return RemoteCommandResult.Failed("Full control is not connected")
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
        if (!channelState.value.connected) return RemoteCommandResult.Failed("Full control is not connected")
        if (steps == 0 || steps !in -100..100) return RemoteCommandResult.Failed("Volume step is out of range")
        val action = if (steps > 0) "up" else "down"
        return runCatchingCommand {
            repeat(kotlin.math.abs(steps)) { session.volume(action) }
        }
    }

    override suspend fun replaceText(text: String): RemoteCommandResult {
        if (!channelState.value.connected) return RemoteCommandResult.Failed("Full control is not connected")
        if (text.isEmpty()) return RemoteCommandResult.Failed("Text is empty")
        return runCatchingCommand { session.text(text) }
    }

    override suspend fun deleteText(): RemoteCommandResult {
        if (!channelState.value.connected) return RemoteCommandResult.Failed("Full control is not connected")
        return runCatchingCommand { session.key(name = "KEYCODE_DEL", code = null, direction = ATV_KEY_SHORT) }
    }

    override suspend fun editText(deletes: Int, text: String, enter: Boolean): RemoteCommandResult {
        if (deletes !in 0..ATV_TEXT_DELETE_CAP || text.codePointCount(0, text.length) > ATV_TEXT_DELETE_CAP) {
            return RemoteCommandResult.Failed("Text edit is too long")
        }
        val generation = controlGeneration
        val fingerprint = activeFingerprint
        return keyboardLock.withLock {
            runCatchingCommand {
                fun checkOwner() {
                    if (!channelState.value.connected || fingerprint == null || activeFingerprint != fingerprint || controlGeneration != generation ||
                        !visible.value || channelState.value.matchedEndpoint != receivers.value?.endpoint
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
        if (!channelState.value.connected) return RemoteCommandResult.Failed("Full control is not connected")
        val id = packageId.trim()
        if (id.isEmpty()) return RemoteCommandResult.Failed("App package is empty")
        return runCatchingCommand { session.launchApp(id) }
    }

    override suspend fun power(): RemoteCommandResult {
        if (!channelState.value.connected) return RemoteCommandResult.Failed("Full control is not connected")
        return runCatchingCommand { session.power() }
    }

    private suspend fun supervise(isVisible: Boolean, receiver: AtvReceiverTarget?) {
        clearConnected()
        pairingTarget = null
        if (!isVisible || receiver == null) return
        try {
            val devices = try {
                session.discover(discoverTimeoutMs)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: AtvCommandException) {
                emptyList()
            }
            val match = AtvDeviceMatcher.match(receiver, devices, credentials.records.value)
            pairingTarget = match.host?.let { PairingTarget(receiver.endpoint, it) }
            if (match.record != null && trustFailures[match.record.fingerprint]?.stopped != true && match.host != null) {
                connectLoop(receiver, match.record, match.host)
            } else if (match.available) {
                publishAvailable(receiver, match.record, match.host)
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
                val identity = credentials.identityOrNull()
                if (identity == null) {
                    publishAvailable(receiver, record, host)
                    awaitCancellation()
                }
                started = true
                session.connect(host, identity.certPem, identity.keyPem, record.fingerprint)
                currentCoroutineContext().ensureActive()
                if (!visible.value || receivers.value?.endpoint != receiver.endpoint) throw CancellationException("Control owner changed")
                credentials.updateLastIp(record.fingerprint, host)
                trustFailures.remove(record.fingerprint)
                publishConnected(receiver, record, session.television.value)
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
            } catch (error: AtvCommandException) {
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
            val stored = credentials.identityOrNull() ?: session.generateIdentity(ATV_CLIENT_NAME).let { generated ->
                ensurePairingCurrent(generation, target)
                AtvClientIdentity(generated.certPem, generated.keyPem).also(credentials::saveIdentity)
            }
            ensurePairingCurrent(generation, target)
            val started = session.startPairing(host, stored.certPem, stored.keyPem, ATV_CLIENT_NAME)
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
            credentials.upsert(
                AtvTvRecord(
                    fingerprint = paired.fingerprint,
                    mac = paired.mac,
                    serverName = paired.serverName ?: started.serverName,
                    lastIp = host,
                    receiverIds = setOf(endpoint.toString()),
                ),
            )
            previousPins.forEach(trustFailures::remove)
            trustFailures.remove(paired.fingerprint)
            pairingState.value = AtvPairingUi(AtvPairingPhase.Paired, serverName = paired.serverName ?: started.serverName)
            refresh.value += 1
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: AtvCommandException) {
            ensurePairingCurrent(generation, target)
            pairingState.value = AtvPairingUi(AtvPairingPhase.Failed, failure = pairingFailure(error.kind))
        }
    }

    private suspend fun ensurePairingCurrent(generation: Long, target: PairingTarget) {
        currentCoroutineContext().ensureActive()
        if (generation != pairingGeneration || !visible.value || receivers.value?.endpoint != target.endpoint) {
            throw CancellationException("Pairing owner changed")
        }
    }

    private fun clearConnected() {
        channelState.value = channelState.value.copy(
            connected = false, keys = emptySet(), volume = VolumeCapabilities(), text = TextCapabilities(), powerOn = null,
        )
    }

    private fun publishConnected(receiver: AtvReceiverTarget, record: AtvTvRecord, television: AtvTelevisionState?) {
        if (!visible.value || receivers.value?.endpoint != receiver.endpoint || activeFingerprint != record.fingerprint) return
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
            needsRepair = failure?.needsRepair == true,
            pinMismatch = failure?.pinMismatch == true,
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
