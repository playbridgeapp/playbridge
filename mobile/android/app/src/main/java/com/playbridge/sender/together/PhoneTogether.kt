package com.playbridge.sender.together

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.playbridge.sender.cast.isOwnReceiverMemberMatch
import com.playbridge.sender.cast.isOwnTvMemberMatch
import com.playbridge.sender.connection.ConnectionCoordinator
import com.playbridge.sender.model.TvDevice
import com.playbridge.sender.player.PlayerLauncher
import com.playbridge.sender.player.SubtitleTrack
import com.playbridge.shared.player.sanitizePlayerHeaders
import com.playbridge.shared.together.RustTogetherBoundary
import com.playbridge.shared.together.TogetherClient
import com.playbridge.shared.together.TogetherDevice
import com.playbridge.shared.together.TogetherDeviceKind
import com.playbridge.shared.together.TogetherEvent
import com.playbridge.shared.together.TogetherItem
import com.playbridge.shared.together.TogetherPlayerCommand
import com.playbridge.shared.together.TogetherPolicy
import com.playbridge.shared.together.TogetherSource
import com.playbridge.shared.together.TogetherStop
import com.playbridge.shared.together.togetherLimitMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Watch Together on the phone (`docs/design-watch-together.md`): hosts or joins a
 * party, plays its video in the phone's player when watching here, and acts as its
 * remote. Party videos never go into cast history.
 *
 * The invite link and item headers can hold secrets: never log them.
 */
object PhoneTogether {
    private const val TAG = "PhoneTogether"
    const val PREFS = "watch_together"
    private const val PREF_RELAY = "relay_url"
    private const val PREF_PAIRING = "pairing_url"

    /** PlayBridge's shared relay: up to 4 devices for 3 hours. */
    const val SHARED_RELAY = "https://party.playbridge.app"
    const val DEFAULT_PAIRING = "https://pair.playbridge.app"

    data class Member(val name: String, val kind: com.playbridge.shared.together.TogetherDeviceKind, val host: Boolean)
    data class Request(val id: String, val from: String, val title: String, val site: String?)

    data class Party(
        val roomId: String,
        val invite: String,
        val relay: String,
        val me: String,
        val host: Boolean,
        /** This phone plays the video too; otherwise it is only a remote. */
        val watching: Boolean = false,
        val connected: Boolean = false,
        val members: Map<String, Member> = emptyMap(),
        /** Devices this phone added: they get its sign-ins. */
        val own: Set<String> = emptySet(),
        val item: TogetherItem? = null,
        val playing: Boolean = false,
        val positionMs: Long = 0,
        /** When [positionMs] was reported, on the elapsed-realtime clock. */
        val positionAt: Long = 0,
        /** Party-known video duration in milliseconds, or null when live or unknown. */
        val durationMs: Long? = null,
        val waitingFor: List<String> = emptyList(),
        val continueWithout: List<String> = emptyList(),
        val locked: Boolean = false,
        val policy: TogetherPolicy = TogetherPolicy.OnlyHost,
        val requests: List<Request> = emptyList(),
        val notice: String? = null,
        /** A live stream: the party shares play and pause only. */
        val live: Boolean = false,
    ) {
        override fun toString(): String = "Party(room=$roomId, relay=$relay, host=$host)"

        fun positionNow(elapsedMs: Long = SystemClock.elapsedRealtime()): Long {
            val raw = if (playing) positionMs + (elapsedMs - positionAt).coerceAtLeast(0L) else positionMs
            val length = durationMs?.takeIf { it > 0 }
            return if (length != null) raw.coerceIn(0L, length) else raw.coerceAtLeast(0L)
        }

        /** Whether this phone may change the video without asking. */
        val canChangeVideo: Boolean get() = host || (policy == TogetherPolicy.Anyone && !locked)
    }

    sealed interface State {
        data object Idle : State
        data object Starting : State
        /** Asking the pairing service for a code. */
        data object GettingCode : State
        /** Showing a code; someone in a party enters it to invite this phone. */
        data class Code(val code: String, val qrSize: Int, val qrModules: String, val expiresAtMs: Long) : State
        /** An invite arrived for the code; nothing is joined until [acceptInvite]. */
        data class Invited(val from: String, val relay: String) : State
        data class InParty(val party: Party) : State
        data class Ended(val message: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = mutableState.asStateFlow()
    private val mutableToasts = MutableStateFlow<String?>(null)
    /** One-off messages for the screen; cleared with [consumeToast]. */
    val toasts: StateFlow<String?> = mutableToasts.asStateFlow()

    /**
     * Whether Watch Party is the currently selected cast target on this phone.
     * Modeled alongside [com.playbridge.sender.cast.CastSessionManager.Route] so leaving or
     * picking another device never disturbs transport state or drops party membership.
     */
    private val mutablePartyTargetSelected = MutableStateFlow(false)
    val partyTargetSelected: StateFlow<Boolean> = mutablePartyTargetSelected.asStateFlow()

    private const val END_CLEAR_GRACE_MS = 1_500L
    private const val PRIVATE_NETWORK_NOTICE = "Only people on this Wi-Fi can load this video."

    /** Stable LAN receiver identity captured when this phone adds a connected TV via LAN. */
    data class OwnTvIdentity(
        val name: String,
        val uuid: String = "",
        val endpointKey: String? = null,
    )

    private val partyQueue = PartyQueue()
    private val advanceTracker = PartyAdvanceTracker()
    private var graceAdvanceJob: Job? = null
    private val mutableQueue = MutableStateFlow<List<PartyQueueItem>>(emptyList())
    val queue: StateFlow<List<PartyQueueItem>> = mutableQueue.asStateFlow()
    private val mutableQueueUpdates = MutableSharedFlow<PartyQueueUpdate>(extraBufferCapacity = 64)
    val queueUpdates: SharedFlow<PartyQueueUpdate> = mutableQueueUpdates.asSharedFlow()
    private val mutableQueueNotice = MutableStateFlow<String?>(null)
    val queueNotice: StateFlow<String?> = mutableQueueNotice.asStateFlow()

    /** Own TV identities keyed by room member ID, captured at LAN handoff time. */
    private val mutableOwnTvIdentities = MutableStateFlow<Map<String, OwnTvIdentity>>(emptyMap())
    val ownTvIdentities: StateFlow<Map<String, OwnTvIdentity>> = mutableOwnTvIdentities.asStateFlow()

    private var client: TogetherClient? = null
    private var room: String? = null
    private var appContext: Context? = null
    /** Whether the party being started or joined plays on this phone. */
    private var watchOnJoin = false

    /** The phone's player while it shows a party video. Calls arrive on the main thread. */
    interface PartyPlayer {
        fun applyPartyCommand(command: TogetherPlayerCommand)
        /** Stop showing the party video. */
        fun close()
    }

    private var player: PartyPlayer? = null
    private var playerItem: String? = null
    /** The item the room last asked this phone to load. */
    private var loadingItem: String? = null

    fun relayUrl(context: Context): String =
        prefs(context).getString(PREF_RELAY, null)?.trim()?.takeIf { it.isNotEmpty() } ?: SHARED_RELAY

    fun setRelayUrl(context: Context, url: String) {
        prefs(context).edit().putString(PREF_RELAY, url.trim()).apply()
    }

    fun pairingUrl(context: Context): String =
        prefs(context).getString(PREF_PAIRING, null)?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_PAIRING

    fun isSharedRelay(context: Context): Boolean = relayUrl(context) == SHARED_RELAY

    fun isInParty(): Boolean = state.value is State.InParty

    fun isPartySelected(): Boolean = isInParty() && mutablePartyTargetSelected.value

    fun selectPartyTarget() {
        if (isInParty()) mutablePartyTargetSelected.value = true
    }

    fun deselectPartyTarget() {
        mutablePartyTargetSelected.value = false
    }

    fun isOwnTvInParty(device: TvDevice): Boolean {
        val party = party() ?: return false
        return isOwnTvMemberMatch(device, party, mutableOwnTvIdentities.value)
    }

    fun isConnectedTvInParty(identity: OwnTvIdentity?, party: Party? = party()): Boolean {
        if (identity == null || party == null) return false
        return isOwnReceiverMemberMatch(
            name = identity.name,
            uuid = identity.uuid,
            endpointKey = identity.endpointKey,
            party = party,
            ownTvIdentities = mutableOwnTvIdentities.value,
        )
    }

    fun consumeToast() {
        mutableToasts.value = null
    }

    /** Whether to play on this phone by default: not when it's connected to a TV. */
    fun watchHereByDefault(coordinator: ConnectionCoordinator?): Boolean = coordinator?.connectedTvName == null

    fun start(context: Context, watchHere: Boolean) {
        val session = ensureClient(context) ?: return
        if (isJoiningByCode()) session.pairCancel()
        mutableState.value = State.Starting
        watchOnJoin = watchHere
        if (!session.host(relayUrl(context), device(watchHere))) {
            mutableState.value = State.Ended("Couldn't start a watch party.")
        }
    }

    /** An invite to another party that arrived while this phone is in one. */
    data class SwitchRequest(val link: String, val watchHere: Boolean) {
        override fun toString(): String = "SwitchRequest(watchHere=$watchHere)"
    }

    private val mutableSwitchRequest = MutableStateFlow<SwitchRequest?>(null)
    /** Asks before leaving the current party for the one in a link; see [openInvite]. */
    val switchRequest: StateFlow<SwitchRequest?> = mutableSwitchRequest.asStateFlow()

    /**
     * Opens an invite link that came from outside the app. The link of the party this phone is
     * in leaves it there: joining again would cost it its keys, and a host its host role. Another
     * party's link waits for [confirmSwitch].
     */
    fun openInvite(context: Context, link: String, watchHere: Boolean) {
        val current = party()
        when {
            current == null -> join(context, link, watchHere)
            current.roomId == WatchTogetherLinks.roomId(link) -> Unit
            else -> mutableSwitchRequest.value = SwitchRequest(link, watchHere)
        }
    }

    /** Leaves the current party and joins the one in the pending [switchRequest]. */
    fun confirmSwitch(context: Context) {
        val request = mutableSwitchRequest.value ?: return
        mutableSwitchRequest.value = null
        leave()
        join(context, request.link, request.watchHere)
    }

    fun cancelSwitch() {
        mutableSwitchRequest.value = null
    }

    fun join(context: Context, link: String, watchHere: Boolean) {
        val session = ensureClient(context) ?: return
        if (isJoiningByCode()) session.pairCancel()
        mutableState.value = State.Starting
        watchOnJoin = watchHere
        if (!session.join(link.trim(), device(watchHere))) {
            mutableState.value = State.Ended("That invite link isn't valid.")
        }
    }

    /** Shows a code to join a party with, for when sending a link is awkward. */
    fun showCode(context: Context, watchHere: Boolean) {
        val session = ensureClient(context) ?: return
        if (state.value is State.InParty) return
        watchOnJoin = watchHere
        mutableState.value = State.GettingCode
        if (!session.pairRegister(pairingUrl(context))) {
            mutableState.value = State.Ended("Couldn't get a code.")
        }
    }

    /** Drops the code or the invite that arrived for it. */
    fun cancelCode() {
        if (!isJoiningByCode()) return
        client?.pairCancel()
        mutableState.value = State.Idle
    }

    /** Joins the party from the pending invite, as a guest of whoever sent it. */
    fun acceptInvite() {
        if (state.value !is State.Invited) return
        mutableState.value = State.Starting
        if (client?.pairAccept(device(watchOnJoin), guest = true) != true) {
            mutableState.value = State.Ended("Couldn't join.")
        }
    }

    private fun isJoiningByCode(): Boolean =
        state.value.let { it is State.GettingCode || it is State.Code || it is State.Invited }

    fun leave() {
        client?.leave()
        room = null
        clearQueueAndTarget()
        mutableState.value = State.Idle
        closePlayer()
    }

    /** Starts or stops playing the party's video on this phone; the phone stays in the party. */
    fun setWatchHere(watch: Boolean) {
        val party = party() ?: return
        if (party.watching == watch) return
        if (client?.setScreen(watch) != true) return
        updateParty { it.copy(watching = watch) }
        if (!watch) closePlayer()
    }

    // -- Player bridge

    /** The player opened [item] for the party. */
    fun attach(partyPlayer: PartyPlayer, item: String) {
        player = partyPlayer
        playerItem = item
    }

    /**
     * The player went away. [stopWatching] when the user closed it: this phone turns
     * into a remote. A new party video replacing it isn't that.
     */
    fun playerClosed(partyPlayer: PartyPlayer, stopWatching: Boolean) {
        if (player !== partyPlayer) return
        player = null
        playerItem = null
        if (stopWatching) setWatchHere(false)
    }

    fun onLoaded(item: String, durationMs: Long?) {
        if (item == loadingItem) client?.loaded(item, durationMs)
    }

    fun onPlayerState(positionMs: Long, playing: Boolean, buffering: Boolean) {
        if (playerItem != null) client?.snapshot(positionMs, playing, buffering)
    }

    /** Play, pause and seek from the phone's player go to everyone. False outside a party. */
    fun localPlay(): Boolean = playerItem != null && client?.play() == true
    fun localPause(): Boolean = playerItem != null && client?.pause() == true
    fun localSeek(positionMs: Long): Boolean = playerItem != null && client?.seek(positionMs.coerceAtLeast(0)) == true

    private fun closePlayer() {
        val open = player
        player = null
        playerItem = null
        loadingItem = null
        open?.close()
    }

    private fun onPlayerCommand(command: TogetherPlayerCommand) {
        if (party()?.watching != true) return
        when (command) {
            is TogetherPlayerCommand.Load -> openPlayer(command)
            TogetherPlayerCommand.Clear -> closePlayer()
            else -> player?.applyPartyCommand(command)
        }
    }

    private fun openPlayer(command: TogetherPlayerCommand.Load) {
        val context = appContext ?: return
        val item = command.item
        val url = when (val source = item.source) {
            is TogetherSource.Url -> source.url
            is TogetherSource.Page, is TogetherSource.App -> {
                toast("This phone can't play \"${item.title}\" yet")
                return
            }
        }
        loadingItem = item.id
        PlayerLauncher.start(
            context = context,
            url = url,
            title = item.title,
            headers = sanitizePlayerHeaders(command.headers),
            subtitles = item.subtitles.map { SubtitleTrack(url = it) },
            startPositionMs = command.startMs,
            watchTogetherItem = item.id,
        )
    }

    fun dismissEnded() {
        if (state.value is State.Ended) mutableState.value = State.Idle
    }

    /** Adds the receiver this phone is connected to. It joins without asking and counts as this phone's own. */
    fun addConnectedTv(coordinator: ConnectionCoordinator) {
        val party = party() ?: return
        val deviceLabel = coordinator.connectedTvName ?: "That device"
        scope.launch {
            coordinator.addTvToWatchTogether(party.invite, phoneName())
                .onSuccess { tv ->
                    client?.addOwn(tv.member, tv.boxKey)
                    // The host's own receiver can carry on as host if this phone leaves.
                    if (party.host) client?.delegate(tv.member, tv.signKey)
                    mutableOwnTvIdentities.update { current ->
                        current + (tv.member to OwnTvIdentity(tv.name, tv.uuid, tv.endpointKey))
                    }
                    updateParty { it.copy(own = it.own + tv.member) }
                    toast("${tv.name} joined the party")
                }
                .onFailure { toast("$deviceLabel couldn't join (${it.message})") }
        }
    }

    /** Invites a device showing [target], a code or the link in its QR code. */
    fun addByCode(context: Context, target: String) {
        if (party() == null) return
        client?.pairSend(pairingUrl(context), target.trim())
    }

    fun play() = client?.play()
    fun pause() = client?.pause()
    fun stopVideo(): Boolean {
        val current = party() ?: return false
        if (!current.canChangeVideo) return false
        advanceTracker.onManualStopRequested(current.item?.id)
        val sent = client?.clear() == true
        if (sent) {
            graceAdvanceJob?.cancel()
            graceAdvanceJob = null
        } else {
            advanceTracker.onManualStopSendFailed()
        }
        return sent
    }
    fun seekBy(deltaMs: Long) {
        val party = party() ?: return
        client?.seek((party.positionNow() + deltaMs).coerceAtLeast(0))
    }
    fun seekTo(positionMs: Long) = client?.seek(positionMs.coerceAtLeast(0))
    fun lock(locked: Boolean) = client?.lock(locked)
    fun policy(policy: TogetherPolicy) = client?.policy(policy)
    fun continueWithout(member: String) = client?.continueWithout(member)
    fun shareSignIn(member: String) = client?.shareCredentials(member)

    fun answer(request: Request, allowed: Boolean, always: Boolean = false) {
        client?.reply(request.id, allowed, always)
        updateParty { party -> party.copy(requests = party.requests.filterNot { it.id == request.id }) }
    }

    /** Appends [item] to the phone-held Up next list when this phone may change the video. */
    fun enqueue(item: PartyQueueItem): Boolean = enqueueAll(listOf(item))

    /** Appends [items] in order to the phone-held Up next list without auto-starting playback. */
    fun enqueueAll(items: List<PartyQueueItem>): Boolean {
        val party = party() ?: return false
        if (!party.canChangeVideo) {
            toast("Only people who can change the video can add to the party queue.")
            return false
        }
        if (items.isEmpty()) return false
        val evaluated = items.map { it to PartyMediaSupport.evaluatePartyUrl(it.url) }
        val firstFailure = evaluated.firstNotNullOfOrNull { it.second.rejection }
        val valid = evaluated.filter { it.second.rejection == null }.map { it.first }
        if (valid.isEmpty()) {
            toast(firstFailure ?: "This video can't be added to a watch party.")
            return false
        }
        val anyPrivate = evaluated.any { it.second.rejection == null && it.second.isPrivateNetwork }
        partyQueue.addAll(valid)
        mutableQueueNotice.value = null
        syncQueueFlow()
        mutableQueueUpdates.tryEmit(PartyQueueUpdate.Enqueued(valid.map { it.id }, partyQueue.items))
        val message = when {
            anyPrivate -> PRIVATE_NETWORK_NOTICE
            valid.size < items.size -> "Added ${valid.size} to party queue (skipped ${items.size - valid.size} local-only)"
            valid.size == 1 -> "Added to party queue"
            else -> "Added ${valid.size} videos to party queue"
        }
        toast(message)
        return true
    }

    fun removeFromQueue(id: String) {
        if (partyQueue.remove(id) != null) {
            syncQueueFlow()
            mutableQueueUpdates.tryEmit(PartyQueueUpdate.Removed(id, partyQueue.items))
        }
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        if (partyQueue.move(fromIndex, toIndex)) {
            syncQueueFlow()
            mutableQueueUpdates.tryEmit(PartyQueueUpdate.Reordered(partyQueue.items))
        }
    }

    fun clearQueue() {
        partyQueue.clear()
        mutableQueueNotice.value = null
        syncQueueFlow()
        mutableQueueUpdates.tryEmit(PartyQueueUpdate.Cleared(emptyList()))
    }

    /** Plays the next queued item immediately, removing it from Up next. */
    fun playNextInQueueNow(): Boolean {
        val next = partyQueue.peek() ?: return false
        return playQueuedItemNow(next.id)
    }

    /** Plays the specific queued item [id] immediately and removes it from Up next. */
    fun playQueuedItemNow(id: String): Boolean {
        val party = party() ?: return false
        if (!party.canChangeVideo) {
            toast(if (party.locked) "The host locked the controls" else "Only the host can change the video")
            return false
        }
        val target = partyQueue.items.firstOrNull { it.id == id } ?: return false
        advanceTracker.onExplicitLoadSent(fromQueue = true)
        val ok = client?.load(target.toTogetherItem()) == true
        if (ok) {
            partyQueue.remove(id)
            mutableQueueNotice.value = null
            syncQueueFlow()
            mutableQueueUpdates.tryEmit(PartyQueueUpdate.LoadedFromQueue(target, partyQueue.items))
            if (PartyMediaSupport.evaluatePartyUrl(target.url).isPrivateNetwork) {
                toast(PRIVATE_NETWORK_NOTICE)
            }
        } else {
            (advanceTracker.onQueuedLoadFailed() as? PartyAdvanceDecision.Paused)?.let { paused ->
                mutableQueueNotice.value = paused.reason
            }
            toast("Couldn't send \"${target.title}\" to the watch party")
        }
        return ok
    }

    /** Called by the phone player if a party video errors before starting playback. */
    fun onLoadFailed(itemId: String, reason: String? = null) {
        if (advanceTracker.onPhonePlayerLoadFailed(itemId)) {
            val message = reason ?: "Couldn't load the next queued video. Up next is paused."
            mutableQueueNotice.value = message
            toast(message)
        }
    }

    /**
     * Plays a video in the party: changes it when allowed, otherwise asks the host.
     * When [queueItems] are provided (playlists / episode lists), they append in order to Up next.
     * [headers] may include sign-in headers; only this phone's own devices get them.
     */
    fun playInParty(
        url: String,
        title: String?,
        headers: Map<String, String>,
        subtitles: List<String>,
        startMs: Long?,
        queueItems: List<PartyQueueItem> = emptyList(),
    ): Boolean {
        val party = party() ?: return false
        val playCheck = PartyMediaSupport.evaluatePartyUrl(url)
        playCheck.rejection?.let { reason ->
            toast(reason)
            return false
        }
        val item = PartyMediaSupport.itemOf(url, title, headers, subtitles, startMs)
        if (party.canChangeVideo) {
            advanceTracker.onExplicitLoadSent(fromQueue = false)
            val sent = client?.load(item.toTogetherItem()) == true
            if (!sent) {
                toast("Couldn't send video to the watch party")
                return false
            }
            mutableQueueUpdates.tryEmit(PartyQueueUpdate.ManualPlay)
            mutableQueueNotice.value = null
            val validQueueChecks = queueItems
                .map { it to PartyMediaSupport.evaluatePartyUrl(it.url) }
                .filter { it.second.rejection == null }
            val validQueue = validQueueChecks.map { it.first }
            val anyPrivate = playCheck.isPrivateNetwork || validQueueChecks.any { it.second.isPrivateNetwork }
            if (validQueue.isNotEmpty()) {
                partyQueue.addAll(validQueue)
                syncQueueFlow()
                mutableQueueUpdates.tryEmit(PartyQueueUpdate.Enqueued(validQueue.map { it.id }, partyQueue.items))
                toast(if (anyPrivate) PRIVATE_NETWORK_NOTICE else "Playing in watch party · ${validQueue.size} up next")
            } else {
                toast(if (anyPrivate) PRIVATE_NETWORK_NOTICE else "Playing in the watch party")
            }
            return true
        } else if (party.locked) {
            toast("The host locked the controls")
            return false
        } else if (party.policy == TogetherPolicy.OnlyHost) {
            toast("Only the host can change the video")
            return false
        } else {
            val requested = client?.requestPlay(item.toTogetherItem()) == true
            if (!requested) {
                toast("Couldn't ask the host to play this video")
                return false
            }
            mutableQueueUpdates.tryEmit(PartyQueueUpdate.ManualPlay)
            toast(if (playCheck.isPrivateNetwork) PRIVATE_NETWORK_NOTICE else "Asked the host to play it")
            return true
        }
    }

    internal sealed interface LinkedPlayResult {
        data class Ok(val askOnly: Boolean, val queuedIds: List<String>) : LinkedPlayResult
        data class Error(val code: String) : LinkedPlayResult
    }

    internal sealed interface LinkedEnqueueResult {
        data class Ok(val addedItems: List<PartyQueueItem>) : LinkedEnqueueResult
        data class Error(val code: String) : LinkedEnqueueResult
    }

    internal fun playLinkedInParty(
        item: PartyQueueItem,
        queueItems: List<PartyQueueItem> = emptyList(),
        replaceQueueIds: Set<String> = emptySet(),
    ): LinkedPlayResult {
        val party = party() ?: return LinkedPlayResult.Error("receiver_stopped")
        if (!isPartySelected()) return LinkedPlayResult.Error("receiver_changed")
        val playCheck = PartyMediaSupport.evaluatePartyUrl(item.url)
        playCheck.rejection?.let { reason ->
            toast(reason)
            return LinkedPlayResult.Error("unsupported_target")
        }
        if (party.locked) {
            toast("The host locked the controls")
            return LinkedPlayResult.Error("not_allowed")
        }
        if (party.policy == TogetherPolicy.OnlyHost && !party.host) {
            toast("Only the host can change the video")
            return LinkedPlayResult.Error("not_allowed")
        }
        if (!party.canChangeVideo) {
            val requested = client?.requestPlay(item.toTogetherItem()) == true
            if (!requested) {
                toast("Couldn't ask the host to play this video")
                return LinkedPlayResult.Error("connect_failed")
            }
            if (replaceQueueIds.isNotEmpty()) {
                val removed = partyQueue.removeAll(replaceQueueIds)
                if (removed.isNotEmpty()) {
                    syncQueueFlow()
                    mutableQueueUpdates.tryEmit(PartyQueueUpdate.ReplacedSessionQueue(removed, partyQueue.items))
                }
            }
            toast(if (playCheck.isPrivateNetwork) PRIVATE_NETWORK_NOTICE else "Asked the host to play it")
            return LinkedPlayResult.Ok(askOnly = true, queuedIds = emptyList())
        }
        advanceTracker.onExplicitLoadSent(fromQueue = false)
        val sent = client?.load(item.toTogetherItem()) == true
        if (!sent) {
            toast("Couldn't send video to the watch party")
            return LinkedPlayResult.Error("connect_failed")
        }
        val removed = partyQueue.removeAll(replaceQueueIds)
        mutableQueueNotice.value = null
        val validQueueChecks = queueItems
            .map { it to PartyMediaSupport.evaluatePartyUrl(it.url) }
            .filter { it.second.rejection == null }
        val validQueue = validQueueChecks.map { it.first }
        if (validQueue.isNotEmpty()) partyQueue.addAll(validQueue)
        syncQueueFlow()
        if (removed.isNotEmpty()) {
            mutableQueueUpdates.tryEmit(PartyQueueUpdate.ReplacedSessionQueue(removed, partyQueue.items))
        } else if (validQueue.isNotEmpty()) {
            mutableQueueUpdates.tryEmit(PartyQueueUpdate.Enqueued(validQueue.map { it.id }, partyQueue.items))
        }
        val anyPrivate = playCheck.isPrivateNetwork || validQueueChecks.any { it.second.isPrivateNetwork }
        if (validQueue.isNotEmpty()) {
            toast(if (anyPrivate) PRIVATE_NETWORK_NOTICE else "Playing in watch party · ${validQueue.size} up next")
        } else {
            toast(if (anyPrivate) PRIVATE_NETWORK_NOTICE else "Playing in the watch party")
        }
        return LinkedPlayResult.Ok(askOnly = false, queuedIds = validQueue.map { it.id })
    }

    internal fun enqueueLinkedItems(items: List<PartyQueueItem>): LinkedEnqueueResult {
        val party = party() ?: return LinkedEnqueueResult.Error("receiver_stopped")
        if (!isPartySelected()) return LinkedEnqueueResult.Error("receiver_changed")
        if (party.locked) {
            toast("The host locked the controls")
            return LinkedEnqueueResult.Error("not_allowed")
        }
        if (party.policy == TogetherPolicy.OnlyHost && !party.host) {
            toast("Only the host can change the video")
            return LinkedEnqueueResult.Error("not_allowed")
        }
        if (!party.canChangeVideo) {
            toast("Only people who can change the video can add to the party queue.")
            return LinkedEnqueueResult.Error("unsupported_queue")
        }
        if (items.isEmpty()) return LinkedEnqueueResult.Error("invalid_request")
        val evaluated = items.map { it to PartyMediaSupport.evaluatePartyUrl(it.url) }
        val firstFailure = evaluated.firstNotNullOfOrNull { it.second.rejection }
        val valid = evaluated.filter { it.second.rejection == null }.map { it.first }
        if (valid.isEmpty()) {
            toast(firstFailure ?: "This video can't be added to a watch party.")
            return LinkedEnqueueResult.Error("unsupported_target")
        }
        val anyPrivate = evaluated.any { it.second.rejection == null && it.second.isPrivateNetwork }
        partyQueue.addAll(valid)
        mutableQueueNotice.value = null
        syncQueueFlow()
        mutableQueueUpdates.tryEmit(PartyQueueUpdate.Enqueued(valid.map { it.id }, partyQueue.items))
        val message = when {
            anyPrivate -> PRIVATE_NETWORK_NOTICE
            valid.size < items.size -> "Added ${valid.size} to party queue (skipped ${items.size - valid.size} local-only)"
            valid.size == 1 -> "Added to party queue"
            else -> "Added ${valid.size} videos to party queue"
        }
        toast(message)
        return LinkedEnqueueResult.Ok(valid)
    }

    internal fun replayLinkedItem(item: PartyQueueItem): LinkedPlayResult {
        val party = party() ?: return LinkedPlayResult.Error("receiver_stopped")
        if (!isPartySelected()) return LinkedPlayResult.Error("receiver_changed")
        val playCheck = PartyMediaSupport.evaluatePartyUrl(item.url)
        playCheck.rejection?.let { reason ->
            toast(reason)
            return LinkedPlayResult.Error("unsupported_target")
        }
        if (party.locked) {
            toast("The host locked the controls")
            return LinkedPlayResult.Error("not_allowed")
        }
        if (party.policy == TogetherPolicy.OnlyHost && !party.host) {
            toast("Only the host can change the video")
            return LinkedPlayResult.Error("not_allowed")
        }
        if (!party.canChangeVideo) {
            val requested = client?.requestPlay(item.toTogetherItem()) == true
            if (!requested) return LinkedPlayResult.Error("connect_failed")
            toast(if (playCheck.isPrivateNetwork) PRIVATE_NETWORK_NOTICE else "Asked the host to play it")
            return LinkedPlayResult.Ok(askOnly = true, queuedIds = emptyList())
        }
        advanceTracker.onExplicitLoadSent(fromQueue = false)
        val sent = client?.load(item.toTogetherItem()) == true
        if (!sent) {
            toast("Couldn't send video to the watch party")
            return LinkedPlayResult.Error("connect_failed")
        }
        mutableQueueNotice.value = null
        if (playCheck.isPrivateNetwork) toast(PRIVATE_NETWORK_NOTICE)
        return LinkedPlayResult.Ok(askOnly = false, queuedIds = emptyList())
    }

    // -- Events

    private fun ensureClient(context: Context): TogetherClient? {
        client?.let { return it }
        appContext = context.applicationContext
        return try {
            TogetherClient(RustTogetherBoundary(), scope).also { created ->
                client = created
                scope.launch { created.events.collect(::onEvent) }
            }
        } catch (error: Throwable) {
            Log.e(TAG, "Watch Together unavailable: ${error.javaClass.simpleName}")
            mutableState.value = State.Ended("Watch Together isn't available on this phone.")
            null
        }
    }

    /**
     * One debug line per party event that changes what people see, so a report like "it
     * paused everywhere but here" can be read back from the log. Ids are shortened; no
     * names, links or headers.
     */
    private fun logEvent(event: TogetherEvent) {
        val line = when (event) {
            is TogetherEvent.Connected -> "connected, ${event.members.size} in room"
            TogetherEvent.Disconnected -> "disconnected"
            TogetherEvent.Unreachable -> "relay unreachable"
            is TogetherEvent.MemberJoined -> "joined ${event.member.take(6)}"
            is TogetherEvent.MemberLeft -> "left ${event.member.take(6)}"
            is TogetherEvent.Member -> "member ${event.member.take(6)} ${event.kind} host=${event.host}"
            is TogetherEvent.Video -> "video ${event.item.id.take(6)}"
            TogetherEvent.VideoCleared -> "video cleared"
            is TogetherEvent.Timeline ->
                "timeline ${event.item.take(6)} playing=${event.playing} at=${event.positionMs} rev=${event.rev}"
            is TogetherEvent.Player -> {
                val command = when (val c = event.command) {
                    is TogetherPlayerCommand.Load -> "Load(${c.item.id.take(6)}, startMs=${c.startMs})"
                    else -> c.toString()
                }
                "player $command watching=${party()?.watching} attached=${player != null}"
            }
            else -> return
        }
        Log.d(TAG, "party: $line")
    }

    private fun onEvent(event: TogetherEvent) {
        logEvent(event)
        val currentParty = party()
        when (event) {
            is TogetherEvent.Room -> {
                if (room != event.roomId) clearQueueAndTarget()
                room = event.roomId
                val selfMember = Member(phoneName(), TogetherDeviceKind.Phone, event.host)
                mutableState.value = State.InParty(
                    Party(
                        roomId = event.roomId,
                        invite = event.invite,
                        relay = event.relay,
                        me = event.member,
                        host = event.host,
                        watching = watchOnJoin,
                        members = mapOf(event.member to selfMember),
                    ),
                )
                mutablePartyTargetSelected.value = true
            }
            is TogetherEvent.Player -> onPlayerCommand(event.command)
            is TogetherEvent.Connected -> updateParty { reducePartyEvent(it, event, SystemClock.elapsedRealtime()) }
            TogetherEvent.Disconnected -> updateParty { reducePartyEvent(it, event, SystemClock.elapsedRealtime()) }
            TogetherEvent.Unreachable -> updateParty { reducePartyEvent(it, event, SystemClock.elapsedRealtime()) }
            is TogetherEvent.Member -> updateParty { reducePartyEvent(it, event, SystemClock.elapsedRealtime()) }
            is TogetherEvent.MemberLeft -> {
                mutableOwnTvIdentities.update { it - event.member }
                updateParty { reducePartyEvent(it, event, SystemClock.elapsedRealtime()) }
            }
            is TogetherEvent.Video -> {
                graceAdvanceJob?.cancel()
                graceAdvanceJob = null
                if (currentParty != null) {
                    handleAdvanceDecision(advanceTracker.onEvent(event, currentParty, partyQueue))
                }
                updateParty { reducePartyEvent(it, event, SystemClock.elapsedRealtime()) }
            }
            TogetherEvent.VideoCleared -> {
                graceAdvanceJob?.cancel()
                graceAdvanceJob = null
                val decision = if (currentParty != null) {
                    advanceTracker.onEvent(event, currentParty, partyQueue)
                } else PartyAdvanceDecision.None
                closePlayer()
                updateParty { reducePartyEvent(it, event, SystemClock.elapsedRealtime()) }
                handleAdvanceDecision(decision)
            }
            is TogetherEvent.Ended -> {
                val decision = if (currentParty != null) {
                    advanceTracker.onEvent(event, currentParty, partyQueue)
                } else PartyAdvanceDecision.None
                if (playerItem == event.item || loadingItem == event.item) {
                    closePlayer()
                }
                updateParty { reducePartyEvent(it, event, SystemClock.elapsedRealtime()) }
                handleAdvanceDecision(decision)
            }
            is TogetherEvent.Timeline -> updateParty { reducePartyEvent(it, event, SystemClock.elapsedRealtime()) }
            is TogetherEvent.Waiting -> updateParty { reducePartyEvent(it, event, SystemClock.elapsedRealtime()) }
            is TogetherEvent.ContinueWithoutAvailable -> updateParty { reducePartyEvent(it, event, SystemClock.elapsedRealtime()) }
            is TogetherEvent.Locked -> {
                updateParty { reducePartyEvent(it, event, SystemClock.elapsedRealtime()) }
                if (!advanceTracker.isHaltedOnFailure && party()?.canChangeVideo == true) {
                    mutableQueueNotice.value = null
                }
            }
            is TogetherEvent.Policy -> {
                updateParty { reducePartyEvent(it, event, SystemClock.elapsedRealtime()) }
                if (!advanceTracker.isHaltedOnFailure && party()?.canChangeVideo == true) {
                    mutableQueueNotice.value = null
                }
            }
            is TogetherEvent.PlayRequested -> updateParty { reducePartyEvent(it, event, SystemClock.elapsedRealtime()) }
            is TogetherEvent.RequestAnswered -> toast(if (event.allowed) "The host is playing your video" else "The host said no")
            is TogetherEvent.RequestExpired -> {
                updateParty { party -> party.copy(requests = party.requests.filterNot { it.id == event.request }) }
                toast("The host didn't respond")
            }
            is TogetherEvent.Warning -> updateParty { reducePartyEvent(it, event, SystemClock.elapsedRealtime()) }
            is TogetherEvent.Live -> updateParty { reducePartyEvent(it, event, SystemClock.elapsedRealtime()) }
            is TogetherEvent.Duration -> updateParty { reducePartyEvent(it, event, SystemClock.elapsedRealtime()) }
            is TogetherEvent.PairSent -> toast("Invite sent. Confirm on that device.")
            is TogetherEvent.PairingCode -> if (state.value is State.GettingCode || state.value is State.Code) {
                mutableState.value = State.Code(event.code, event.qrSize, event.qrModules, event.expiresAtMs)
            }
            is TogetherEvent.PairingInvite -> if (state.value is State.Code) {
                mutableState.value = State.Invited(event.from, event.relay)
            }
            is TogetherEvent.Error -> {
                if (currentParty != null && state.value is State.InParty) {
                    handleAdvanceDecision(advanceTracker.onEvent(event, currentParty, partyQueue))
                }
                when (event.context) {
                    "pair_send" -> toast("Couldn't send the invite: ${event.message}")
                    "pair" -> when (state.value) {
                        State.GettingCode -> mutableState.value =
                            State.Ended("Couldn't get a code. Check your connection and try again.")
                        is State.Code -> mutableState.value =
                            State.Ended("That code stopped working. Get a new one to join.")
                        is State.Invited, State.Starting -> mutableState.value = State.Ended("Couldn't join that watch party.")
                        else -> Unit
                    }
                    "host", "join" -> mutableState.value = State.Ended(event.message.ifBlank { "Couldn't join." })
                    else -> Log.w(TAG, "Watch Together ${event.context} failed")
                }
            }
            is TogetherEvent.Stopped -> {
                if (event.roomId.isNotEmpty() && event.roomId != room) return
                room = null
                clearQueueAndTarget()
                closePlayer()
                if (state.value is State.InParty || state.value is State.Starting) {
                    mutableState.value = when (event.reason) {
                        TogetherStop.Left -> State.Idle
                        TogetherStop.RoomFull -> State.Ended("This watch party is full.")
                        TogetherStop.Expired -> State.Ended("This watch party has ended.")
                        TogetherStop.Unauthorized -> State.Ended("This invite isn't valid any more.")
                        TogetherStop.Replaced -> State.Ended("You joined this party somewhere else.")
                        TogetherStop.RoomLimit, TogetherStop.RelayBusy ->
                            State.Ended(togetherLimitMessage(event.reason, event.retryAfterSecs).orEmpty())
                        TogetherStop.Error -> State.Ended("The watch party connection failed.")
                    }
                }
            }
            is TogetherEvent.Rejected -> Log.d(TAG, "Dropped a room message: ${event.reason}")
            else -> Unit
        }
    }

    private fun handleAdvanceDecision(decision: PartyAdvanceDecision) {
        when (decision) {
            PartyAdvanceDecision.None -> Unit
            is PartyAdvanceDecision.WaitForClear -> {
                graceAdvanceJob?.cancel()
                graceAdvanceJob = scope.launch {
                    delay(END_CLEAR_GRACE_MS)
                    val current = party() ?: return@launch
                    handleAdvanceDecision(advanceTracker.onGraceElapsed(current, partyQueue))
                }
            }
            is PartyAdvanceDecision.Paused -> {
                syncQueueFlow()
                mutableQueueNotice.value = decision.reason
                toast(decision.reason)
            }
            is PartyAdvanceDecision.Load -> {
                syncQueueFlow()
                if (client?.load(decision.item.toTogetherItem()) == true) {
                    mutableQueueNotice.value = null
                    mutableQueueUpdates.tryEmit(PartyQueueUpdate.LoadedFromQueue(decision.item, partyQueue.items))
                } else {
                    val paused = advanceTracker.onQueuedLoadFailed()
                    if (paused is PartyAdvanceDecision.Paused) {
                        mutableQueueNotice.value = paused.reason
                        toast(paused.reason)
                    }
                }
            }
        }
    }

    private fun syncQueueFlow() {
        mutableQueue.value = partyQueue.items
    }

    private fun clearQueueAndTarget() {
        graceAdvanceJob?.cancel()
        graceAdvanceJob = null
        partyQueue.clear()
        advanceTracker.reset()
        mutableQueue.value = emptyList()
        mutableQueueNotice.value = null
        mutableOwnTvIdentities.value = emptyMap()
        mutablePartyTargetSelected.value = false
    }

    private fun party(): Party? = (state.value as? State.InParty)?.party

    private fun updateParty(change: (Party) -> Party) {
        mutableState.update { current -> if (current is State.InParty) State.InParty(change(current.party)) else current }
    }

    private fun toast(message: String) {
        mutableToasts.value = message
    }

    private fun device(screen: Boolean) = TogetherDevice(phoneName(), TogetherDeviceKind.Phone, screen = screen)

    private fun phoneName(): String {
        val context = appContext
        return context?.let {
            android.provider.Settings.Global.getString(it.contentResolver, android.provider.Settings.Global.DEVICE_NAME)
        } ?: Build.MODEL ?: "Phone"
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

internal fun reducePartyEvent(
    party: PhoneTogether.Party,
    event: TogetherEvent,
    elapsedMs: Long,
): PhoneTogether.Party = when (event) {
    is TogetherEvent.Connected -> party.copy(connected = true, notice = null)
    TogetherEvent.Disconnected -> party.copy(connected = false, notice = "Reconnecting…")
    TogetherEvent.Unreachable -> party.copy(notice = "Can't reach ${party.relay}. Still trying…")
    is TogetherEvent.Member -> party.copy(
        members = party.members + (event.member to PhoneTogether.Member(event.name, event.kind, event.host)),
    )
    is TogetherEvent.MemberLeft ->
        if (event.member == party.me) party else party.copy(members = party.members - event.member)
    is TogetherEvent.Video -> party.copy(
        item = event.item,
        playing = false,
        positionMs = event.item.startMs,
        positionAt = elapsedMs,
        durationMs = null,
        live = false,
        notice = null,
    )
    TogetherEvent.VideoCleared -> clearPartyItem(party, elapsedMs)
    is TogetherEvent.Ended ->
        if (party.item?.id == event.item) clearPartyItem(party, elapsedMs) else party
    is TogetherEvent.Timeline -> party.copy(playing = event.playing, positionMs = event.positionMs, positionAt = elapsedMs)
    is TogetherEvent.Waiting -> party.copy(waitingFor = event.members)
    is TogetherEvent.ContinueWithoutAvailable -> party.copy(continueWithout = event.members)
    is TogetherEvent.Locked -> party.copy(locked = event.locked)
    is TogetherEvent.Policy -> party.copy(policy = event.policy)
    is TogetherEvent.PlayRequested -> party.copy(
        requests = party.requests + PhoneTogether.Request(event.request, event.from, event.title, event.site),
    )
    is TogetherEvent.Warning -> party.copy(notice = event.message)
    is TogetherEvent.Live -> party.copy(live = event.live)
    is TogetherEvent.Duration -> party.copy(durationMs = event.durationMs)
    else -> party
}

private fun clearPartyItem(
    party: PhoneTogether.Party,
    elapsedMs: Long,
): PhoneTogether.Party = party.copy(
    item = null,
    playing = false,
    positionMs = 0,
    positionAt = elapsedMs,
    durationMs = null,
    live = false,
    waitingFor = emptyList(),
    continueWithout = emptyList(),
    notice = null,
)
