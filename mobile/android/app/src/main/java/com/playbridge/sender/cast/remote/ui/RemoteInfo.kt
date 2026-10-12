package com.playbridge.sender.cast.remote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.playbridge.sender.cast.remote.RemoteAction
import com.playbridge.sender.cast.remote.RemoteLayoutSpec
import com.playbridge.sender.cast.remote.RemoteUiState
import com.playbridge.sender.cast.remote.ready

/** 3a owns this host. 3c supplies media; 3d supplies linkage/Up next and its sheet. */
@Composable
internal fun RemoteInfo(
    state: RemoteUiState,
    layout: RemoteLayoutSpec,
    dispatch: (RemoteAction) -> Unit,
    collapsedUpNext: Boolean,
    onInfoHeight: (Float) -> Unit,
    onQueueHeight: (Float) -> Unit,
    media: @Composable () -> Unit = { RemoteMediaInfo(state, layout, dispatch) },
    linkage: @Composable () -> Unit = { RemoteLinkedWebsite(state, dispatch) },
    upNext: @Composable (Boolean) -> Unit = { RemoteQueueRow(state, it, dispatch) },
) {
    val density = LocalDensity.current
    Column(Modifier.fillMaxWidth().testTag("remote.info"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (layout.info || state.linkedWebsite?.active == true) {
            Column(Modifier.fillMaxWidth().onSizeChanged { onInfoHeight(with(density) { it.height.toDp().value }) },
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (layout.info) RemoteSectionCard("remote.mediaInfo", state.connection.ready, content = media)
                if (state.linkedWebsite?.active == true) RemoteSectionCard("remote.linkage", content = linkage)
            }
        }
        if (layout.showQueue) {
            Box(Modifier.fillMaxWidth().onSizeChanged { onQueueHeight(with(density) { it.height.toDp().value }) }) {
                RemoteSectionCard("remote.upNext", state.connection.ready, padding = if (collapsedUpNext) 0.dp else 12.dp) { upNext(collapsedUpNext) }
            }
        }
    }
}
