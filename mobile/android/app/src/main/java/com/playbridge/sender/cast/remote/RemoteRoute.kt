package com.playbridge.sender.cast.remote

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.playbridge.sender.cast.Capability
import com.playbridge.sender.cast.DetectedVideo
import com.playbridge.sender.cast.MediaTrack
import com.playbridge.sender.cast.RemoteControlScreen
import com.playbridge.sender.cast.TvPlayerSettings
import com.playbridge.sender.connection.WebSocketClient
import com.playbridge.sender.library.PlaylistEpisode
import org.koin.androidx.compose.koinViewModel
import playbridge.SubtitleResource

/** Parameter compatibility only: receiver ownership and command dispatch live in the view model. */
@Composable
fun RemoteRoute(
    onEffect: (RemoteEffect) -> Unit,
    subtitleSourceKey: Any?,
    detectedSubtitles: (String?) -> List<DetectedVideo>,
    viewModel: RemoteViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val handleEffect by rememberUpdatedState(onEffect)
    DisposableEffect(viewModel) {
        viewModel.activate()
        onDispose { viewModel.deactivate() }
    }
    LaunchedEffect(viewModel) { viewModel.effects.collect { handleEffect(it) } }
    LaunchedEffect(viewModel, state.sessionId) { if (state.sessionId != null) viewModel.dispatch(RemoteAction.Resync) }
    val dispatch: (RemoteAction) -> Unit = viewModel::dispatch
    val playback = state.playback
    val settings = state.settings
    val external = state.receiver?.profile == RemoteProfile.External
    val subtitles = remember(subtitleSourceKey, playback?.title, external) { if (external) emptyList() else detectedSubtitles(playback?.title) }
    val externalCapabilities = if (external) buildSet<Capability> {
        if (state.capabilities.keys.isNotEmpty()) add(Capability.REMOTE)
        if (state.capabilities.volume.adjust == Support.Supported) add(Capability.VOLUME)
        if (state.capabilities.transport.seekAbsolute == Support.Supported) add(Capability.SEEK)
    } else null
    RemoteControlScreen(
        activeContext = when (state.context) { RemoteContext.Player -> "player"; RemoteContext.Browser -> "browser"; else -> "idle" },
        managedState = state,
        onRemoteUiAction = dispatch,
        receiverConnected = state.connection.ready,
        onBack = { dispatch(RemoteAction.BackToOrigin) },
        onBrowseLibrary = { dispatch(RemoteAction.BrowseLibrary) },
        onOpenBrowser = { dispatch(RemoteAction.OpenPhoneBrowser) },
        onUnlinkWebsite = { dispatch(RemoteAction.UnlinkWebsite) },
        linkedPageCastControllerName = state.linkedWebsite?.controllerName?.takeIf { state.linkedWebsite?.active == true },
        tvName = state.receiver?.name,
        externalProtocolLabel = state.receiver?.endpoint?.protocol?.displayName?.takeIf { external },
        externalCapabilities = externalCapabilities,
        connectionState = if (external) WebSocketClient.ConnectionState.Disconnected else state.connection.screenConnection(),
        playbackState = playback?.reportedState?.name?.lowercase(),
        positionMs = playback?.displayedPositionMs ?: 0L,
        durationMs = playback?.durationMs ?: 0L,
        mediaTitle = playback?.title,
        mediaKind = playback?.mediaKind?.name?.lowercase() ?: "video",
        isLive = playback?.isLive == true,
        isSeekable = playback?.seekable == Support.Supported,
        episodes = state.queue?.items.orEmpty().map { PlaylistEpisode(it.index, it.title, it.season, it.episode, it.imdbId, it.bingeGroup, it.mediaKind.name.lowercase(), it.itemId, it.tmdbId) },
        currentEpisodeIndex = state.queue?.currentIndex ?: 0,
        videoTracks = state.tracks.video.map { it.screenTrack() },
        audioTracks = state.tracks.audio.map { it.screenTrack() },
        subtitleTracks = state.tracks.subtitles.map { it.screenTrack() },
        // Preserve stage-1 presentation defaults. Nullable contracts do not claim these are reported.
        playerSettings = TvPlayerSettings(
            speed = settings.speed ?: 1f, scaling = settings.scaling?.wireValue ?: "Fit",
            audioBoost = settings.audioBoost ?: false, subtitleOffsetMs = settings.subtitleOffsetMs ?: 0L,
            engine = settings.engine.orEmpty(), qualityMaxHeight = settings.qualityMaxHeight ?: 0,
            currentVideoHeight = settings.currentVideoHeight ?: 0, isLive = playback?.isLive == true,
            isSeekable = playback?.seekable != Support.Unsupported,
            speedAvailable = settings.availability[PlayerSetting.Speed] != Support.Unsupported,
            scalingAvailable = settings.availability[PlayerSetting.Scaling] != Support.Unsupported,
            audioBoostAvailable = settings.availability[PlayerSetting.AudioBoost] != Support.Unsupported,
            qualityAvailable = settings.availability[PlayerSetting.Quality] == Support.Supported,
        ),
        detectedSubtitles = subtitles,
        onSeekTo = { dispatch(RemoteAction.SeekTo(it)) },
        onJumpToEpisode = { dispatch(RemoteAction.JumpToEpisode(it)) },
        onSelectAudio = { dispatch(RemoteAction.SelectAudio(it)) },
        onSelectSubtitle = { dispatch(RemoteAction.SelectSubtitle(it)) },
        onSetVideoQuality = { dispatch(RemoteAction.SetVideoQuality(it.takeIf { height -> height > 0 })) },
        onSetSpeed = { dispatch(RemoteAction.SetSpeed(it)) },
        onSetScaling = { dispatch(RemoteAction.SetScaling(ScalingMode.entries.firstOrNull { mode -> mode.wireValue == it } ?: ScalingMode.Fit)) },
        onToggleAudioBoost = { dispatch(RemoteAction.ToggleAudioBoost) },
        onAdjustSubtitleOffset = { dispatch(RemoteAction.AdjustSubtitleOffset(it)) },
        onSwitchEngine = { dispatch(RemoteAction.SwitchEngine(it)) },
        onSubmitSubtitle = { dispatch(RemoteAction.AddSubtitle(it)) },
        onAddSubtitleUrl = { dispatch(RemoteAction.AddSubtitle(SubtitleResource(url = it), legacyUrlOnly = true)) },
        onRemoteKey = { dispatch(RemoteCallbackActions.key(it)) },
        onPlayerControl = { dispatch(RemoteCallbackActions.player(it)) },
        onBrowserControl = { dispatch(RemoteCallbackActions.browser(it)) },
        onMouseMove = { x, y -> dispatch(RemoteAction.PointerMove(x, y)) },
        onMouseClick = { dispatch(RemoteAction.PointerClick) },
        onMouseScroll = { x, y -> dispatch(RemoteAction.PointerScroll(x, y)) },
        onMouseDown = { dispatch(RemoteAction.PointerDown) },
        onMouseUp = { dispatch(RemoteAction.PointerUp) },
        onPointerGestureEnd = { dispatch(RemoteAction.PointerGestureEnd) },
        onPinchZoom = { dispatch(RemoteAction.ImageZoom(it)) },
        onRotateImage = { dispatch(RemoteAction.ImageRotate(it)) },
        onTransformAnchor = { x, y -> dispatch(RemoteAction.ImageAnchor(x, y)) },
        onResetZoom = { dispatch(RemoteAction.ResetImageTransform) },
    )
}

private fun RemoteTrack.screenTrack() = MediaTrack(id, name, selected, type)
private fun RemoteConnection.screenConnection(): WebSocketClient.ConnectionState = when (this) {
    RemoteConnection.Disconnected -> WebSocketClient.ConnectionState.Disconnected
    is RemoteConnection.Connected -> WebSocketClient.ConnectionState.Connected(name, secure)
    is RemoteConnection.Connecting -> WebSocketClient.ConnectionState.Connecting(name)
    is RemoteConnection.Reconnecting -> WebSocketClient.ConnectionState.Retrying(attempt, maximum, seconds)
    is RemoteConnection.Pairing -> when (phase) {
        RemoteConnection.Pairing.Phase.CodeInput -> WebSocketClient.ConnectionState.WaitingForCodeInput(name, attemptsLeft, lastCodeWrong)
        RemoteConnection.Pairing.Phase.Verifying -> WebSocketClient.ConnectionState.VerifyingCode(name)
        RemoteConnection.Pairing.Phase.Approval -> WebSocketClient.ConnectionState.WaitingForApproval(name)
        RemoteConnection.Pairing.Phase.Denied -> WebSocketClient.ConnectionState.PairingDenied(name)
    }
    is RemoteConnection.Failed -> when (kind) {
        RemoteConnection.Failed.Kind.Error -> WebSocketClient.ConnectionState.Error(message)
        RemoteConnection.Failed.Kind.Auth -> WebSocketClient.ConnectionState.AuthFailed
        RemoteConnection.Failed.Kind.Pin -> WebSocketClient.ConnectionState.PinMismatch(name)
    }
}
