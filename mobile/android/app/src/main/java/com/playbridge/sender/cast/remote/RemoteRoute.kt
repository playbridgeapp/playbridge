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
    val state by viewModel.state.collectAsStateWithLifecycle()
    val handleEffect by rememberUpdatedState(onEffect)
    RemoteRouteLifecycleEffect(viewModel, viewModel::activate, viewModel::deactivate)
    LaunchedEffect(viewModel) { viewModel.effects.collect { handleEffect(it) } }
    LaunchedEffect(viewModel, state.sessionId) { if (state.sessionId != null) viewModel.dispatch(RemoteAction.Resync) }
    val playback = state.playback
    val external = state.receiver?.profile == RemoteProfile.External
    val subtitles = remember(subtitleSourceKey, playback?.title, external) { if (external) emptyList() else detectedSubtitles(playback?.title) }
    RemoteScreen(state = state, dispatch = viewModel::dispatch, detectedSubtitles = subtitles, execute = viewModel::execute)
}
