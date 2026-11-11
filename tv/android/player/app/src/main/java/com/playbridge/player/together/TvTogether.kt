package com.playbridge.player.together

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.playbridge.player.logging.FileLogger
import com.playbridge.player.server.ServerService
import com.playbridge.shared.together.RustTogetherBoundary
import com.playbridge.shared.together.TogetherClient
import com.playbridge.shared.together.TogetherDevice
import com.playbridge.shared.together.TogetherDeviceKind
import com.playbridge.shared.together.TogetherEvent
import com.playbridge.shared.together.TogetherPlayerCommand
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
import playbridge.PlayPayload
import playbridge.PlaylistPayload

/**
 * Watch Together on the TV (`docs/design-watch-together.md`): joins a party by
 * pairing code and lets the room drive the player. Main process only.
 *
 * Party playback skips history and resume points, and its URLs, headers and
 * invite never reach logs.
 */
object TvTogether {
    private const val TAG = "TvTogether"
    const val PREFS = "watch_together"
    const val PREF_PAIRING_URL = "pairing_url"

    /** PlayBridge's pairing service; Settings can point at another one. */
    const val DEFAULT_PAIRING_URL = "https://pair.playbridge.app"

    sealed interface State {
        data object Idle : State
        data object Registering : State
        data class Code(val code: String, val qrSize: Int, val qrModules: String, val expiresAtMs: Long) : State
        data class Invited(val from: String, val relay: String) : State
        data class InParty(
            val relay: String,
            val connected: Boolean = false,
            val members: Map<String, String> = emptyMap(),
            val hostName: String? = null,
            val title: String? = null,
            val waitingFor: List<String> = emptyList(),
            val notice: String? = null,
        ) : State
        data class Ended(val message: String) : State
    }

    /** The player while it shows a party video. Calls arrive on the main thread. */
    interface PartyPlayer {
        fun applyPartyCommand(command: TogetherPlayerCommand)
    }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = mutableState.asStateFlow()

    private var appContext: Context? = null
    private var client: TogetherClient? = null
    /** Answers a paired phone's join once the room reports this TV's keys. */
    private var pendingPhoneJoin: ((TogetherEvent.Room?, String?) -> Unit)? = null
    private var me: String? = null
    private var room: String? = null
    private var player: PartyPlayer? = null
    private var playerItem: String? = null
    /** The item the room last asked this TV to load. */
    private var loadingItem: String? = null

    fun pairingUrl(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(PREF_PAIRING_URL, null)?.trim()?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_PAIRING_URL

    fun setPairingUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(PREF_PAIRING_URL, url.trim()).apply()
    }

    /** Shows a code to join a party with. */
    fun startPairing(context: Context) {
        val pairing = pairingUrl(context)
        if (pairing.isEmpty()) {
            mutableState.value = State.Ended("Set the pairing service address first.")
            return
        }
        val session = ensureClient(context) ?: return
        if (state.value is State.InParty) return
        mutableState.value = State.Registering
        session.pairRegister(pairing)
    }

    fun cancelPairing() {
        client?.pairCancel()
        if (state.value !is State.InParty) mutableState.value = State.Idle
    }

    /** Joins the party from the pending invite. */
    fun accept() {
        val session = client ?: return
        val invited = state.value as? State.Invited ?: return
        mutableState.value = State.InParty(relay = invited.relay)
        session.pairAccept(TogetherDevice(deviceName(), TogetherDeviceKind.Tv, screen = true))
    }

    /**
     * A paired phone added this TV to its party. It is already trusted, so the TV
     * joins without asking; [reply] gets this TV's member ID and keys, or an error.
     */
    fun joinFromPhone(
        context: Context,
        invite: String,
        from: String,
        reply: (TogetherEvent.Room?, String?) -> Unit,
    ) {
        val session = ensureClient(context) ?: return reply(null, "unavailable")
        pendingPhoneJoin?.invoke(null, "unavailable")
        pendingPhoneJoin = reply
        client?.pairCancel()
        mutableState.value = State.InParty(relay = "", hostName = from.takeIf { it.isNotBlank() })
        if (!session.join(invite, TogetherDevice(deviceName(), TogetherDeviceKind.Tv, screen = true))) {
            pendingPhoneJoin = null
            mutableState.value = State.Idle
            reply(null, "unavailable")
        }
    }

    fun decline() {
        client?.pairCancel()
        mutableState.value = State.Idle
    }

    fun leave() {
        client?.leave()
        mutableState.value = State.Idle
        loadingItem = null
    }

    fun isInParty(): Boolean = state.value is State.InParty

    // -- Player bridge

    /** The player started [item] for the party. */
    fun attach(partyPlayer: PartyPlayer, item: String) {
        player = partyPlayer
        playerItem = item
    }

    fun detach(partyPlayer: PartyPlayer) {
        if (player === partyPlayer) {
            player = null
            playerItem = null
        }
    }

    /** The player started something that isn't the party's video: leave the party. */
    fun onOtherPlayback() {
        if (!isInParty()) return
        FileLogger.i(TAG, "Leaving the party: other playback started")
        leave()
        mutableState.value = State.Ended("You left the watch party because something else started playing.")
    }

    fun onLoaded(item: String, durationMs: Long) {
        if (item == loadingItem) client?.loaded(item, durationMs.takeIf { it > 0 })
    }

    fun onPlayerState(positionMs: Long, playing: Boolean, buffering: Boolean) {
        if (playerItem != null) client?.snapshot(positionMs, playing, buffering)
    }

    /**
     * A play, pause or seek from this TV's remote or a paired phone: it goes to the
     * whole party, and comes back as a player command. Returns false outside a party.
     */
    fun localPlay(): Boolean = playerItem != null && client?.play() == true
    fun localPause(): Boolean = playerItem != null && client?.pause() == true
    fun localSeek(positionMs: Long): Boolean = playerItem != null && client?.seek(positionMs.coerceAtLeast(0)) == true

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
            FileLogger.e(TAG, "Watch Together unavailable: ${error.javaClass.simpleName}")
            mutableState.value = State.Ended("Watch Together isn't available on this TV.")
            null
        }
    }

    private fun onEvent(event: TogetherEvent) {
        when (event) {
            is TogetherEvent.PairingCode -> mutableState.value =
                State.Code(event.code, event.qrSize, event.qrModules, event.expiresAtMs)
            is TogetherEvent.PairingInvite -> mutableState.value = State.Invited(event.from, event.relay)
            is TogetherEvent.Room -> {
                me = event.member
                room = event.roomId
                pendingPhoneJoin?.invoke(event, null)
                pendingPhoneJoin = null
                mutableState.value = State.InParty(relay = event.relay)
                FileLogger.i(TAG, "Joined a watch party on ${event.relay}")
            }
            is TogetherEvent.Connected -> updateParty { it.copy(connected = true, notice = null) }
            TogetherEvent.Disconnected -> updateParty { it.copy(connected = false, notice = "Reconnecting…") }
            is TogetherEvent.Member -> updateParty {
                it.copy(
                    members = it.members + (event.member to event.name),
                    hostName = if (event.host && it.hostName == null) event.name else it.hostName,
                )
            }
            is TogetherEvent.MemberLeft -> updateParty { it.copy(members = it.members - event.member) }
            is TogetherEvent.Video -> updateParty { it.copy(title = event.item.title) }
            is TogetherEvent.Waiting -> updateParty { party ->
                party.copy(waitingFor = event.members.filter { it != me }.map { party.members[it] ?: "a device" })
            }
            is TogetherEvent.Warning -> updateParty { it.copy(notice = event.message) }
            is TogetherEvent.Player -> onPlayerCommand(event.command)
            is TogetherEvent.Error -> {
                FileLogger.w(TAG, "Watch Together ${event.context} failed")
                if (event.context == "join") {
                    pendingPhoneJoin?.invoke(null, "invalid_invite")
                    pendingPhoneJoin = null
                    mutableState.value = State.Ended("The invite from your phone isn't valid.")
                }
                if (event.context == "pair" || state.value !is State.InParty) {
                    mutableState.value = State.Ended(event.message.ifBlank { "Something went wrong." })
                }
            }
            is TogetherEvent.Stopped -> {
                // A room this TV already switched away from. No room ID: the engine itself stopped.
                if (event.roomId.isNotEmpty() && event.roomId != room) return
                room = null
                loadingItem = null
                if (state.value is State.InParty) {
                    mutableState.value = when (event.reason) {
                        TogetherStop.Left -> State.Idle
                        TogetherStop.RoomFull -> State.Ended("This watch party is full.")
                        TogetherStop.Expired -> State.Ended("This watch party has ended.")
                        TogetherStop.Unauthorized -> State.Ended("This invite isn't valid any more.")
                        TogetherStop.Replaced -> State.Ended("This TV joined the party somewhere else.")
                        TogetherStop.Error -> State.Ended("The watch party connection failed.")
                    }
                }
            }
            is TogetherEvent.Rejected -> FileLogger.d(TAG, "Dropped a room message: ${event.reason}")
            else -> Unit
        }
    }

    private fun updateParty(change: (State.InParty) -> State.InParty) {
        mutableState.update { current -> if (current is State.InParty) change(current) else current }
    }

    private fun onPlayerCommand(command: TogetherPlayerCommand) {
        if (command is TogetherPlayerCommand.Load) {
            load(command)
            return
        }
        val target = player ?: return
        main.post { target.applyPartyCommand(command) }
    }

    private fun load(command: TogetherPlayerCommand.Load) {
        val item = command.item
        val url = when (val source = item.source) {
            is TogetherSource.Url -> source.url
            is TogetherSource.Page, is TogetherSource.App -> {
                updateParty { it.copy(notice = "This TV can't play \"${item.title}\" yet.") }
                return
            }
        }
        loadingItem = item.id
        val payload = PlaylistPayload(
            items = listOf(
                PlayPayload(
                    url = url,
                    title = item.title,
                    headers = command.headers,
                    subtitles = item.subtitles,
                    start_position_ms = command.startMs,
                    skip_history = true,
                    item_id = item.id,
                ),
            ),
            start_index = 0,
            skip_preplay = true,
        )
        if (!ServerService.playForWatchTogether(payload, item.id)) {
            updateParty { it.copy(notice = "Couldn't open the player.") }
        }
    }

    private fun deviceName(): String {
        val context = appContext
        return context?.let { Settings.Global.getString(it.contentResolver, Settings.Global.DEVICE_NAME) }
            ?: android.os.Build.MODEL
            ?: "TV"
    }
}
