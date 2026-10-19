package com.playbridge.sender.cast

import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState as PlatformPlaybackState
import android.os.Handler
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import com.playbridge.sender.cast.remote.RemoteAction
import com.playbridge.sender.cast.remote.RemoteCommandResult
import com.playbridge.sender.cast.remote.RemoteController
import com.playbridge.sender.cast.remote.RemoteControllerRegistry
import com.playbridge.sender.cast.remote.RemoteMediaId
import com.playbridge.sender.cast.remote.RemoteReceiverSnapshot
import com.playbridge.sender.cast.remote.Support
import com.playbridge.sender.cast.remote.ready
import com.playbridge.sender.connection.ConnectionCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.util.UUID

internal data class CastMediaNotificationView(
    val ownerToken: String,
    val title: String,
    val subtitle: String,
    val state: PlaybackState,
    val actions: CastMediaActions,
)

/** Receiver-only session: never emits on the website/in-app MediaControlChannel. */
internal class CastMediaSession(
    private val context: Context,
    private val manager: CastSessionManager,
    private val coordinator: ConnectionCoordinator,
    private val registry: RemoteControllerRegistry,
    private val scope: CoroutineScope,
    private val onChanged: () -> Unit,
    private val onUnlink: () -> Unit,
) {
    private data class Owner(
        val route: CastSessionManager.Route,
        val selection: Long,
        val external: CastTarget?,
        val generation: Long,
        val native: RemoteController?,
        val media: RemoteMediaId?,
    )

    private val main = Handler(Looper.getMainLooper())
    private var frameworkSession: MediaSession? = null
    val session: MediaSession get() = checkNotNull(frameworkSession)

    private fun createSession() = MediaSession(context, "PlayBridgeCast").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onPlay() = setPlaying(true)
            override fun onPause() = setPlaying(false)
            override fun onSeekTo(pos: Long) = seek(pos)
            override fun onStop() = stop()
            override fun onCustomAction(action: String, extras: Bundle?) {
                when (action) {
                    CUSTOM_STOP -> stop()
                    CUSTOM_UNLINK -> if (currentOwner() != null && latest?.actions?.unlink == true) onUnlink()
                }
            }
        }, main)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val updates = combine(
        manager.route, manager.activeTarget, manager.remoteExternalPlayback, coordinator.tvPlayback,
        registry.controller.flatMapLatest { it?.snapshot ?: flowOf(RemoteReceiverSnapshot()) },
    ) { _, _, _, _, _ -> Unit }

    private var owner: Owner? = null
    private var ownerToken = ""
    private var view: CastMediaNotificationView? = null
    private var latest: CastPlaybackPublication? = null
    private var published: CastPlaybackPublication? = null
    private var metadataKey: Triple<String, String, Long>? = null
    private var observedSequence = -1L
    private var observedAt = 0L
    private var optimistic: PlaybackState? = null
    private var optimisticSequence = -1L
    private var optimisticExpiry: Job? = null
    private var volumeProvider: VolumeProvider? = null
    private var volumeOwner: Owner? = null
    private var volumeSupported: Boolean? = null
    private var closed = false

    fun refresh(info: CastSessionManager.SessionInfo, loaded: Boolean, mirroring: Boolean, linked: Boolean): CastMediaNotificationView? {
        if (closed) return null
        val external = manager.remoteExternalTarget.takeIf { manager.route.value is CastSessionManager.Route.External }
        val native = registry.controller.value.takeIf { external == null && manager.route.value == CastSessionManager.Route.NativeTv }
        val nativeState = native?.snapshot?.value?.state
        val externalReport = manager.remoteExternalPlayback.value
        val nextOwner = Owner(manager.route.value, manager.selectionRevision, external,
            if (external != null) externalReport.loadGeneration else coordinator.remoteObservation.value.mediaGeneration,
            native, nativeState?.playback?.mediaId)
        if (nextOwner != owner) {
            owner = nextOwner
            ownerToken = UUID.randomUUID().toString()
            published = null
            metadataKey = null
            observedSequence = -1L
            clearOptimism()
        }
        val sequence = if (external != null) externalReport.sequence else coordinator.remoteObservation.value.playbackSequence
        val now = SystemClock.elapsedRealtime()
        if (sequence != observedSequence) {
            observedSequence = sequence
            observedAt = now
        }
        var playback = if (external != null) externalCastMediaPlayback(manager.externalStatus.value, loaded)
            else nativeCastMediaPlayback(coordinator.tvPlayback.value, loaded, nativeState?.playback?.isLive == true)
        val sourceAvailable = external != null || native != null
        if (!sourceAvailable || !castNotificationUsesMedia(loaded, mirroring, playback)) {
            releaseSession()
            return null
        }
        if (frameworkSession == null) {
            frameworkSession = createSession()
            ownerToken = UUID.randomUUID().toString()
        }
        if (optimistic != null && sequence == optimisticSequence) {
            val base = latest
            playback = playback.copy(state = optimistic!!, positionMs = base?.positionAt(now) ?: playback.positionMs)
        } else if (optimistic != null) clearOptimism()

        val caps = nativeState?.capabilities
        val actions = CastMediaActions(
            playPause = external?.capabilities?.contains(Capability.PLAY_PAUSE)
                ?: (nativeState?.connection?.ready == true && caps?.transport?.playPause == Support.Supported),
            stop = external?.capabilities?.contains(Capability.STOP) ?: (caps?.transport?.stop == Support.Supported),
            seek = castNotificationCanSeek(playback, external?.capabilities?.contains(Capability.SEEK)
                ?: (caps?.transport?.seekAbsolute == Support.Supported && nativeState?.playback?.seekable == Support.Supported)),
            volume = external?.capabilities?.contains(Capability.VOLUME) ?: (caps?.volume?.adjust == Support.Supported),
            unlink = linked,
        )
        val title = (if (external != null) manager.externalMediaTitle.value else coordinator.tvPlayback.value?.title)
            ?.takeIf { it.isNotBlank() } ?: info.title?.takeIf { it.isNotBlank() } ?: "Playing"
        val subtitle = "Casting to ${info.deviceName}"
        val next = CastPlaybackPublication(nextOwner, playback, actions, if (optimistic != null) now else observedAt)
        latest = next
        val key = Triple(title, subtitle, playback.durationMs)
        if (metadataKey != key) {
            session.setMetadata(MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, subtitle)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, playback.durationMs.takeIf { it > 0 } ?: -1L).build())
            metadataKey = key
        }
        if (castPlaybackNeedsPublication(published, next, now)) {
            val flags = (if (actions.playPause) PlatformPlaybackState.ACTION_PLAY or PlatformPlaybackState.ACTION_PAUSE or PlatformPlaybackState.ACTION_PLAY_PAUSE else 0L) or
                (if (actions.stop) PlatformPlaybackState.ACTION_STOP else 0L) or
                (if (actions.seek) PlatformPlaybackState.ACTION_SEEK_TO else 0L)
            val state = when (playback.state) {
                PlaybackState.PLAYING -> PlatformPlaybackState.STATE_PLAYING
                PlaybackState.PAUSED -> PlatformPlaybackState.STATE_PAUSED
                else -> PlatformPlaybackState.STATE_BUFFERING
            }
            val builder = PlatformPlaybackState.Builder().setActions(flags)
                .setState(state, next.positionAt(now), if (playback.playing) 1f else 0f, now)
            // Android 13+ derives the carousel buttons from PlaybackState rather
            // than the Notification.Action list. Preserve Stop and linked Unlink there.
            if (actions.stop) builder.addCustomAction(PlatformPlaybackState.CustomAction.Builder(
                CUSTOM_STOP, "Stop", android.R.drawable.ic_menu_close_clear_cancel).build())
            if (actions.unlink) builder.addCustomAction(PlatformPlaybackState.CustomAction.Builder(
                CUSTOM_UNLINK, "Unlink", android.R.drawable.ic_menu_revert).build())
            session.setPlaybackState(builder.build())
            published = next.copy(playback = playback.copy(positionMs = next.positionAt(now)), atMs = now)
        }
        updateVolume(nextOwner, actions.volume, caps?.volume?.reportedPercent)
        session.isActive = true
        return CastMediaNotificationView(ownerToken, title, subtitle, playback.state, actions).also { view = it }
    }

    fun matches(token: String?) = token != null && token == view?.ownerToken && currentOwner() != null

    private fun currentOwner(): Owner? = owner?.takeIf { expected ->
        !closed && frameworkSession != null &&
        manager.route.value == expected.route && manager.selectionRevision == expected.selection &&
            (if (expected.external != null) manager.isCurrentRemoteExternal(expected.external, expected.generation)
            else expected.native != null && registry.controller.value === expected.native &&
                coordinator.remoteObservation.value.mediaGeneration == expected.generation &&
                expected.native.snapshot.value.state.playback?.mediaId == expected.media)
    }

    fun setPlaying(playing: Boolean) {
        val expected = currentOwner() ?: return
        if (latest?.actions?.playPause != true) return
        if (expected.external != null && Capability.PLAY_PAUSE !in expected.external.capabilities) return
        optimistic = if (playing) PlaybackState.PLAYING else PlaybackState.PAUSED
        optimisticSequence = observedSequence
        optimisticExpiry?.cancel()
        optimisticExpiry = scope.launch {
            delay(2_500)
            clearOptimism()
            onChanged()
        }
        if (expected.external != null) {
            if (playing) manager.play() else manager.pause()
        } else nativeCommand(expected, RemoteAction.SetPlaying(playing)) {
            clearOptimism()
            onChanged()
        }
        onChanged()
    }

    private fun seek(positionMs: Long) {
        val expected = currentOwner() ?: return
        val state = latest ?: return
        if (!state.actions.seek) return
        if (expected.external != null && Capability.SEEK !in expected.external.capabilities) return
        val target = positionMs.coerceIn(0, state.playback.durationMs)
        if (expected.external != null) manager.seekTo(target)
        else nativeCommand(expected, RemoteAction.SeekTo(target))
    }

    fun stop() {
        if (currentOwner() != null && latest?.actions?.stop == true) manager.endCastSession()
    }

    private fun updateVolume(expected: Owner, supported: Boolean, percent: Int?) {
        if (volumeOwner != expected || volumeSupported != supported) {
            volumeOwner = expected
            volumeSupported = supported
            volumeProvider = object : VolumeProvider(if (supported) VOLUME_CONTROL_RELATIVE else VOLUME_CONTROL_FIXED, 100, percent?.coerceIn(0, 100) ?: 50) {
                override fun onAdjustVolume(direction: Int) {
                    if (direction != AudioManager.ADJUST_RAISE && direction != AudioManager.ADJUST_LOWER) return
                    main.post {
                        if (closed || !supported || currentOwner() != expected || latest?.actions?.volume != true ||
                            expected.external?.capabilities?.contains(Capability.VOLUME) == false) return@post
                        if (expected.external != null) manager.adjustVolume(direction == AudioManager.ADJUST_RAISE)
                        else nativeCommand(expected, RemoteAction.AdjustVolume(if (direction > 0) 1 else -1))
                    }
                }
            }.also(session::setPlaybackToRemote)
        }
        if (percent != null && volumeProvider?.currentVolume != percent.coerceIn(0, 100)) volumeProvider?.setCurrentVolume(percent.coerceIn(0, 100))
    }

    private fun clearOptimism() {
        optimistic = null
        optimisticExpiry?.cancel()
        optimisticExpiry = null
    }

    private fun nativeCommand(expected: Owner, action: RemoteAction, onFailure: () -> Unit = {}) {
        scope.launch {
            if (currentOwner() != expected) return@launch
            val result = try {
                expected.native?.execute(action, expected.media)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                RemoteCommandResult.Failed("Could not send receiver command")
            }
            if (currentOwner() == expected && (result is RemoteCommandResult.Failed || result is RemoteCommandResult.Unsupported)) onFailure()
        }
    }

    fun releaseSession() {
        val previous = frameworkSession
        frameworkSession = null
        view = null
        latest = null
        published = null
        metadataKey = null
        volumeProvider = null
        volumeOwner = null
        volumeSupported = null
        clearOptimism()
        if (previous != null) {
            previous.isActive = false
            previous.setCallback(null)
            previous.release()
        }
    }

    fun close() {
        closed = true
        releaseSession()
    }

    private companion object {
        const val CUSTOM_STOP = "com.playbridge.sender.cast.media.STOP"
        const val CUSTOM_UNLINK = "com.playbridge.sender.cast.media.UNLINK"
    }
}
