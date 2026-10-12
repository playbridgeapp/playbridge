package com.playbridge.sender.cast.atv

import com.playbridge.sender.cast.remote.RemoteCommandResult
import com.playbridge.sender.cast.remote.RemoteKey
import com.playbridge.sender.cast.remote.Support
import com.playbridge.sender.cast.remote.TextCapabilities
import com.playbridge.sender.cast.remote.TvRemoteChannel
import com.playbridge.sender.cast.remote.TvRemoteChannelState
import com.playbridge.sender.cast.remote.VolumeCapabilities
import com.playbridge.sender.model.EndpointKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
    private var pairingHost: String? = null
    private var pairingEndpoint: EndpointKey? = null
    private var activeFingerprint: String? = null
    private var needsRepair = false
    private var pinMismatch = false

    override val state: StateFlow<TvRemoteChannelState> = channelState.asStateFlow()
    override val pairing: StateFlow<AtvPairingUi> = pairingState.asStateFlow()

    init {
        scope.launch {
            combine(visible, receivers, refresh) { isVisible, receiver, _ -> isVisible to receiver }
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
                        refresh.value += 1
                    }
                }
        }
    }

    override fun setRemoteVisible(visible: Boolean) {
        this.visible.value = visible
        if (!visible) {
            channelState.value = channelState.value.copy(
                connected = false,
                keys = emptySet(),
                volume = VolumeCapabilities(),
                text = TextCapabilities(),
                powerOn = null,
            )
        }
    }

    override fun begin() {
        val phase = pairingState.value.phase
        if (phase == AtvPairingPhase.Starting || phase == AtvPairingPhase.WaitingForCode || phase == AtvPairingPhase.Verifying) return
        pairingJob?.cancel()
        pairingJob = scope.launch {
            runCatching { session.cancelPairing() }
            runPairing()
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
        pairingJob?.cancel()
        pairingJob = null
        codeSlot = null
        pairingState.value = AtvPairingUi()
        scope.launch { runCatching { session.cancelPairing() } }
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
        if (!isVisible || receiver == null) return
        val devices = try {
            session.discover(discoverTimeoutMs)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: AtvCommandException) {
            emptyList()
        }
        val match = AtvDeviceMatcher.match(receiver, devices, credentials.records.value)
        pairingHost = match.host
        pairingEndpoint = receiver.endpoint
        if (match.record != null && !pinMismatch && match.host != null) {
            connectLoop(receiver, match.record, match.host)
        } else if (match.available) {
            publishAvailable(receiver, match.record, match.host)
            awaitCancellation()
        }
    }

    private suspend fun connectLoop(receiver: AtvReceiverTarget, record: AtvTvRecord, host: String) {
        activeFingerprint = record.fingerprint
        var attempt = 1
        while (true) {
            var started = false
            try {
                val identity = credentials.identityOrNull()
                if (identity == null) {
                    publishAvailable(receiver, record, host)
                    awaitCancellation()
                }
                started = true
                session.connect(host, identity.certPem, identity.keyPem, record.fingerprint)
                credentials.updateLastIp(record.fingerprint, host)
                needsRepair = false
                publishConnected(receiver, record, session.television.value)
                coroutineScope {
                    launch {
                        session.television.collect { television ->
                            if (television != null && channelState.value.connected) publishConnected(receiver, record, television)
                        }
                    }
                    session.awaitClosed()
                }
                publishAvailable(receiver, record, host)
                delay(backoffMs(attempt))
                attempt++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: AtvCommandException) {
                when (error.kind) {
                    "not_trusted" -> {
                        needsRepair = true
                        publishAvailable(receiver, record, host)
                        if (attempt >= maxTrustRetries) awaitCancellation()
                        delay(backoffMs(attempt))
                        attempt++
                    }
                    "pin_mismatch" -> {
                        pinMismatch = true
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
                    withContext(NonCancellable) {
                        runCatching { session.disconnect() }
                    }
                }
            }
        }
    }

    private suspend fun runPairing() {
        val host = pairingHost
        val endpoint = pairingEndpoint
        pairingState.value = AtvPairingUi(AtvPairingPhase.Starting)
        try {
            if (host.isNullOrBlank() || endpoint == null) {
                pairingState.value = AtvPairingUi(AtvPairingPhase.Failed, failure = AtvPairingFailure.Unreachable)
                return
            }
            val stored = credentials.identityOrNull() ?: session.generateIdentity(ATV_CLIENT_NAME).let { generated ->
                AtvClientIdentity(generated.certPem, generated.keyPem).also(credentials::saveIdentity)
            }
            val started = session.startPairing(host, stored.certPem, stored.keyPem, ATV_CLIENT_NAME)
            val deferred = CompletableDeferred<String>()
            codeSlot = deferred
            pairingState.value = AtvPairingUi(AtvPairingPhase.WaitingForCode, serverName = started.serverName)
            val code = try {
                deferred.await()
            } finally {
                if (codeSlot === deferred) codeSlot = null
            }
            pairingState.value = AtvPairingUi(AtvPairingPhase.Verifying, serverName = started.serverName ?: pairingState.value.serverName)
            val paired = session.finishPairing(code)
            credentials.upsert(
                AtvTvRecord(
                    fingerprint = paired.fingerprint,
                    mac = paired.mac,
                    serverName = paired.serverName ?: started.serverName,
                    lastIp = host,
                    receiverIds = setOf(endpoint.toString()),
                ),
            )
            needsRepair = false
            pinMismatch = false
            pairingState.value = AtvPairingUi(AtvPairingPhase.Paired, serverName = paired.serverName ?: started.serverName)
            refresh.value += 1
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: AtvCommandException) {
            pairingState.value = AtvPairingUi(AtvPairingPhase.Failed, failure = pairingFailure(error.kind))
        }
    }

    private fun publishConnected(receiver: AtvReceiverTarget, record: AtvTvRecord, television: AtvTelevisionState?) {
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
        channelState.value = TvRemoteChannelState(
            matchedEndpoint = receiver.endpoint,
            physicalDeviceId = record?.let(::physicalId),
            paired = record != null,
            connected = false,
            needsRepair = needsRepair,
            pinMismatch = pinMismatch,
            serverName = record?.serverName,
            fullControlAvailable = true,
        )
        if (host != null) pairingHost = host
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
