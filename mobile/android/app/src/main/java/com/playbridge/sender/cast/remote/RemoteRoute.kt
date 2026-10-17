package com.playbridge.sender.cast.remote

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.playbridge.sender.cast.DetectedVideo
import com.playbridge.sender.cast.remote.ui.RemoteScreen
import org.koin.androidx.compose.koinViewModel

/** One lifecycle-owned state/action boundary for all receiver profiles. */
@Composable
fun RemoteRoute(
    onEffect: (RemoteEffect) -> Unit,
    subtitleSourceKey: Any?,
    detectedSubtitles: (String?) -> List<DetectedVideo>,
    viewModel: RemoteViewModel = koinViewModel(),
) {
    val connections: com.playbridge.sender.connection.ConnectionViewModel = koinViewModel()
    LaunchedEffect(Unit) { connections.prepareIndependentRemote { viewModel.dispatch(RemoteAction.OpenConnections) } }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val handleEffect by rememberUpdatedState(onEffect)
    RemoteRouteLifecycleEffect(viewModel, viewModel::activate) {
        connections.cancelOpenPlayBridge()
        viewModel.deactivate()
    }
    LaunchedEffect(viewModel) { viewModel.effects.collect { handleEffect(it) } }
    LaunchedEffect(viewModel, state.sessionId) { if (state.sessionId != null) viewModel.dispatch(RemoteAction.Resync) }
    val tvRows by connections.remoteTVDirectory.rows.collectAsStateWithLifecycle()
    val selectedTV = tvRows.firstOrNull { it.device.endpointKey == state.sessionId?.endpoint }
    val availability = RemoteAvailability.resolve(state.connection.ready, state.fullControlReady,
        selectedTV?.paired == true, selectedTV?.ambiguous == true)
    val playback = state.playback
    val external = state.receiver?.profile == RemoteProfile.External
    val subtitles = remember(subtitleSourceKey, playback?.title, external) { if (external) emptyList() else detectedSubtitles(playback?.title) }
    RemoteScreen(state = state, availability = availability, dispatch = { action ->
        if (action is RemoteAction.LaunchTvApp && action.packageId == RemoteAvailability.PLAYBRIDGE_PACKAGE && !state.connection.ready) connections.openPlayBridge()
        else viewModel.dispatch(action)
    }, detectedSubtitles = subtitles, execute = viewModel::execute)
}
