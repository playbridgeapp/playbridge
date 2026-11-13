package com.playbridge.sender.together

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.playbridge.sender.connection.ConnectionCoordinator
import com.playbridge.sender.player.PlayerLauncher
import com.playbridge.sender.player.SubtitleTrack
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

        fun positionNow(): Long =
            if (playing) positionMs + (SystemClock.elapsedRealtime() - positionAt) else positionMs

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
        if (command is TogetherPlayerCommand.Load) {
            openPlayer(command)
            return
        }
        player?.applyPartyCommand(command)
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
            headers = command.headers,
            subtitles = item.subtitles.map { SubtitleTrack(url = it) },
            startPositionMs = command.startMs,
            watchTogetherItem = item.id,
        )
    }

    fun dismissEnded() {
        if (state.value is State.Ended) mutableState.value = State.Idle
    }

    /** Adds the TV this phone is connected to. It joins without asking and counts as this phone's own. */
    fun addConnectedTv(coordinator: ConnectionCoordinator) {
        val party = party() ?: return
        scope.launch {
            coordinator.addTvToWatchTogether(party.invite, phoneName())
                .onSuccess { tv ->
                    client?.addOwn(tv.member, tv.boxKey)
                    // The host's own TV can carry on as host if this phone leaves.
                    if (party.host) client?.delegate(tv.member, tv.signKey)
                    updateParty { it.copy(own = it.own + tv.member) }
                    toast("${tv.name} joined the party")
                }
                .onFailure { toast("The TV couldn't join (${it.message})") }
        }
    }

    /** Invites a device showing [target], a code or the link in its QR code. */
    fun addByCode(context: Context, target: String) {
        if (party() == null) return
        client?.pairSend(pairingUrl(context), target.trim())
    }

    fun play() = client?.play()
    fun pause() = client?.pause()
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

    /**
     * Plays a video in the party: changes it when allowed, otherwise asks the host.
     * [headers] may include sign-in headers; only this phone's own devices get them.
     */
    fun playInParty(url: String, title: String?, headers: Map<String, String>, subtitles: List<String>, startMs: Long?) {
        val party = party() ?: return
        val site = runCatching { java.net.URI(url).host }.getOrNull()?.removePrefix("www.")
        val item = TogetherItem(
            title = title?.takeIf { it.isNotBlank() } ?: site ?: "Video",
            site = site,
            source = TogetherSource.Url(url),
            headers = headers,
            subtitles = subtitles,
            startMs = startMs ?: 0,
        )
        if (party.canChangeVideo) {
            client?.load(item)
            toast("Playing in the watch party")
        } else if (party.locked) {
            toast("The host locked the controls")
        } else if (party.policy == TogetherPolicy.OnlyHost) {
            toast("Only the host can change the video")
        } else {
            client?.requestPlay(item)
            toast("Asked the host to play it")
        }
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

    private fun onEvent(event: TogetherEvent) {
        when (event) {
            is TogetherEvent.Room -> {
                room = event.roomId
                mutableState.value = State.InParty(
                    Party(event.roomId, event.invite, event.relay, event.member, event.host, watching = watchOnJoin),
                )
            }
            is TogetherEvent.Player -> onPlayerCommand(event.command)
            is TogetherEvent.Connected -> updateParty { it.copy(connected = true, notice = null) }
            TogetherEvent.Disconnected -> updateParty { it.copy(connected = false, notice = "Reconnecting…") }
            is TogetherEvent.Member -> updateParty {
                it.copy(members = it.members + (event.member to Member(event.name, event.kind, event.host)))
            }
            is TogetherEvent.MemberLeft -> updateParty { it.copy(members = it.members - event.member) }
            is TogetherEvent.Video -> updateParty { it.copy(item = event.item, notice = null) }
            is TogetherEvent.Timeline -> updateParty {
                it.copy(playing = event.playing, positionMs = event.positionMs, positionAt = SystemClock.elapsedRealtime())
            }
            is TogetherEvent.Waiting -> updateParty { it.copy(waitingFor = event.members) }
            is TogetherEvent.ContinueWithoutAvailable -> updateParty { it.copy(continueWithout = event.members) }
            is TogetherEvent.Locked -> updateParty { it.copy(locked = event.locked) }
            is TogetherEvent.Policy -> updateParty { it.copy(policy = event.policy) }
            is TogetherEvent.PlayRequested -> updateParty {
                it.copy(requests = it.requests + Request(event.request, event.from, event.title, event.site))
            }
            is TogetherEvent.RequestAnswered -> toast(if (event.allowed) "The host is playing your video" else "The host said no")
            is TogetherEvent.RequestExpired -> {
                updateParty { party -> party.copy(requests = party.requests.filterNot { it.id == event.request }) }
                toast("The host didn't respond")
            }
            is TogetherEvent.Warning -> updateParty { it.copy(notice = event.message) }
            is TogetherEvent.Live -> updateParty { it.copy(live = event.live) }
            is TogetherEvent.PairSent -> toast("Invite sent. Confirm on that device.")
            is TogetherEvent.PairingCode -> if (state.value is State.GettingCode || state.value is State.Code) {
                mutableState.value = State.Code(event.code, event.qrSize, event.qrModules, event.expiresAtMs)
            }
            is TogetherEvent.PairingInvite -> if (state.value is State.Code) {
                mutableState.value = State.Invited(event.from, event.relay)
            }
            is TogetherEvent.Error -> when (event.context) {
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
            is TogetherEvent.Stopped -> {
                if (event.roomId.isNotEmpty() && event.roomId != room) return
                room = null
                closePlayer()
                if (state.value is State.InParty || state.value is State.Starting) {
                    mutableState.value = when (event.reason) {
                        TogetherStop.Left -> State.Idle
                        TogetherStop.RoomFull -> State.Ended("This watch party is full.")
                        TogetherStop.Expired -> State.Ended("This watch party has ended.")
                        TogetherStop.Unauthorized -> State.Ended("This invite isn't valid any more.")
                        TogetherStop.Replaced -> State.Ended("You joined this party somewhere else.")
                        TogetherStop.Error -> State.Ended("The watch party connection failed.")
                    }
                }
            }
            is TogetherEvent.Rejected -> Log.d(TAG, "Dropped a room message: ${event.reason}")
            else -> Unit
        }
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
