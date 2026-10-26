package com.playbridge.sender.cast.remote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import org.koin.compose.koinInject
import com.playbridge.sender.cast.PhoneQueueCoordinator
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.playbridge.sender.cast.DetectedVideo
import com.playbridge.sender.cast.remote.LocalRemoteMotionPolicy
import com.playbridge.sender.cast.remote.RemoteAction
import com.playbridge.sender.cast.remote.RemoteAdaptiveLayout
import com.playbridge.sender.cast.remote.RemoteAdaptiveSections
import com.playbridge.sender.cast.remote.RemoteColumnSplit
import com.playbridge.sender.cast.remote.RemoteCommandResult
import com.playbridge.sender.cast.remote.RemoteInputMode
import com.playbridge.sender.cast.remote.RemoteSectionHeights
import com.playbridge.sender.cast.remote.RemoteSheet
import com.playbridge.sender.cast.remote.RemoteUiState
import com.playbridge.sender.cast.remote.RemoteViewport
import com.playbridge.sender.cast.remote.RemoteVolumeKeys
import com.playbridge.sender.cast.remote.actionReady
import com.playbridge.sender.cast.remote.fullControlReady
import com.playbridge.sender.cast.remote.ready
import com.playbridge.sender.cast.remote.rememberRemoteMotionPolicy
import com.playbridge.sender.cast.remote.resolveRemoteLayout
import com.playbridge.sender.cast.remote.LocalRemotePreferences
import kotlin.math.roundToInt

/** The only screen shell. Receiver state, optimism, modes, sheets and commands remain owned by the view model. */
@Composable
fun RemoteScreen(
    state: RemoteUiState,
    availability: com.playbridge.sender.cast.remote.RemoteAvailability = com.playbridge.sender.cast.remote.RemoteAvailability.resolve(
        state.connection.ready, state.fullControlReady, state.fullControl?.paired == true),
    dispatch: (RemoteAction) -> Unit,
    modifier: Modifier = Modifier,
    detectedSubtitles: List<DetectedVideo> = emptyList(),
    execute: suspend (RemoteAction) -> RemoteCommandResult = { action -> dispatch(action); RemoteCommandResult.Submitted },
) {
    val motion = rememberRemoteMotionPolicy()
    val layout = resolveRemoteLayout(state, RemoteViewport())
    if (availability == com.playbridge.sender.cast.remote.RemoteAvailability.Offline) {
        Column(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RemoteHeader(state, dispatch, showRetry = false)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    androidx.compose.material3.Text(com.playbridge.sender.cast.remote.RemoteAvailability.OFFLINE_TITLE, style = MaterialTheme.typography.titleLarge)
                    androidx.compose.material3.Text(com.playbridge.sender.cast.remote.RemoteAvailability.OFFLINE_MESSAGE)
                    Row {
                        androidx.compose.material3.TextButton(onClick = { dispatch(RemoteAction.RetryConnection) }) { androidx.compose.material3.Text("Retry") }
                        androidx.compose.material3.TextButton(onClick = { dispatch(RemoteAction.OpenConnections) }) { androidx.compose.material3.Text("Switch TV") }
                    }
                }
            }
        }
        RemoteSheets(state, dispatch, detectedSubtitles, execute)
        return
    }
    RemoteVolumeKeys(layout.nav.volume.enabled, state.sessionId.toString()) {
        dispatch(RemoteAction.AdjustVolume(if (it) 1 else -1))
    }
    // A phone list paints the Remote with its artwork's gradient, like Up next.
    val phoneQueue = koinInject<PhoneQueueCoordinator>()
    val phoneList by phoneQueue.state.collectAsStateWithLifecycle()
    // Follows the active list, not the TV's reported item, which lags or blanks while songs change.
    val listPlaying = phoneList.active && phoneList.current != null && state.connection.ready
    val tint = rememberQueueTint(phoneList.current?.uri?.takeIf { listPlaying }, MaterialTheme.colorScheme.primary)
    val baseScheme = MaterialTheme.colorScheme
    CompositionLocalProvider(LocalRemoteMotionPolicy provides motion, LocalRemotePreferences provides state.preferences) {
        MaterialTheme(colorScheme = if (listPlaying) baseScheme.tinted(tint) else baseScheme) {
            Surface(
                modifier.fillMaxSize().then(if (listPlaying) Modifier.background(tint.gradient()) else Modifier),
                color = if (listPlaying) Color.Transparent else MaterialTheme.colorScheme.surface,
            ) {
                BoxWithConstraints(Modifier.fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime))
                    .padding(horizontal = 16.dp, vertical = 8.dp)) {
                    val density = LocalDensity.current
                    val viewport = RemoteViewport(maxWidth.value.roundToInt(), maxHeight.value.roundToInt(), fontScale = density.fontScale)
                    val sections = RemoteAdaptiveSections(
                        info = layout.info || state.connection.ready && state.linkedWebsite?.active == true,
                        upNext = layout.showQueue, toolbar = layout.browserRow,
                        input = layout.input,
                        seek = layout.wave, inputPresentation = layout.inputPresentation,
                    )
                    // Width/font/context changes invalidate wrapping measurements, not receiver or UI intent.
                    var heights by remember(viewport.widthDp, density.fontScale, sections, layout.showModeRow, state.sessionId) {
                        mutableStateOf(RemoteSectionHeights())
                    }
                    val adaptive = RemoteAdaptiveLayout.resolve(viewport, sections, heights)
                    val navHeight = heights.nav ?: 48f
                    fun measured(update: (RemoteSectionHeights, Float) -> RemoteSectionHeights): Modifier =
                        Modifier.onSizeChanged { size -> heights = update(heights, with(density) { size.height.toDp().value }) }
                    val guardedDispatch: (RemoteAction) -> Unit = { action ->
                        // Pointer cleanup must still run when a gesture loses readiness.
                        if (state.actionReady(action) || action == RemoteAction.PointerGestureEnd || action == RemoteAction.PointerUp ||
                            action is RemoteAction.SetButtonPad || action is RemoteAction.SelectInputMode || action is RemoteAction.OpenSheet || action == RemoteAction.CloseSheet) dispatch(action)
                    }
                    val header: @Composable () -> Unit = {
                        Column(measured { old, value -> old.copy(header = value) }) {
                            RemoteHeader(state, dispatch)
                            if (availability == com.playbridge.sender.cast.remote.RemoteAvailability.TvRemoteOnly) {
                                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
                                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                        androidx.compose.material3.Text(com.playbridge.sender.cast.remote.RemoteAvailability.RECEIVER_MISSING)
                                        androidx.compose.material3.TextButton(onClick = { dispatch(RemoteAction.LaunchTvApp(com.playbridge.sender.cast.remote.RemoteAvailability.PLAYBRIDGE_PACKAGE)) }) {
                                            androidx.compose.material3.Text("Open PlayBridge")
                                        }
                                    }
                                }
                            }
                        }
                    }
                    val info: @Composable () -> Unit = {
                        if (sections.info || sections.upNext) RemoteInfo(state, layout, guardedDispatch, adaptive.collapseUpNext,
                            onInfoHeight = { heights = heights.copy(info = it) },
                            onQueueHeight = { heights = if (adaptive.collapseUpNext) heights.copy(compactUpNext = it) else heights.copy(upNext = it) },
                            linkage = { RemoteLinkedWebsite(state, dispatch) },
                        )
                    }
                    val toolbar: @Composable () -> Unit = {
                        if (sections.toolbar) Box(measured { old, value -> old.copy(toolbar = value) }) {
                            RemoteSectionCard("remote.toolbar", state.connection.ready) { RemoteBrowserToolbar(state, guardedDispatch) }
                        }
                    }
                    val input: @Composable (Modifier) -> Unit = { inputModifier ->
                        Box(inputModifier.testTag("remote.input")) {
                            RemoteSectionCard("remote.inputCard", state.connection.ready || layout.idle || state.fullControlReady && layout.selectedMode == RemoteInputMode.Pad) {
                                RemoteInput(state, layout, guardedDispatch)
                            }
                        }
                    }
                    val pinned: @Composable () -> Unit = {
                        if (sections.seek) Box(measured { old, value -> old.copy(seek = value) }.testTag("remote.seek").alpha(if (state.connection.ready) 1f else 0.45f)) {
                            CompositionLocalProvider(LocalRemoteControlsEnabled provides state.connection.ready) {
                                RemoteSeekBar(state, layout, guardedDispatch)
                            }
                        }

                    }
                    val contentModifier = Modifier.fillMaxSize().padding(bottom = (navHeight + 8f).dp)
                    if (adaptive.split == RemoteColumnSplit.InputLeft) {
                        Row(contentModifier.testTag("remote.columns"), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState())) {
                                input(Modifier.fillMaxWidth().height(adaptive.inputHeightDp.dp))
                            }
                            Column(Modifier.weight(1f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).testTag("remote.upper"),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)) { header(); info(); toolbar() }
                                pinned()
                            }
                        }
                    } else if (adaptive.split == RemoteColumnSplit.InfoAndQueue) {
                        val leftCards = buildList<@Composable (Dp) -> Unit> {
                            if (layout.info) add { max ->
                                RemoteSectionCard("remote.mediaInfo", state.connection.ready, maxHeight = max) {
                                    RemoteMediaInfo(state, layout, guardedDispatch)
                                }
                            }
                            if (state.connection.ready && state.linkedWebsite?.active == true) add { max ->
                                RemoteSectionCard("remote.linkage", maxHeight = max) { RemoteLinkedWebsite(state, guardedDispatch) }
                            }
                            if (sections.toolbar) add { max ->
                                RemoteSectionCard("remote.toolbar", state.connection.ready, maxHeight = max) {
                                    RemoteBrowserToolbar(state, guardedDispatch)
                                }
                            }
                        }
                        val rightCards = buildList<@Composable (Dp) -> Unit> {
                            if (sections.upNext) add { max ->
                                RemoteSectionCard("remote.upNext", state.connection.ready, padding = if (adaptive.collapseUpNext) 0.dp else 12.dp, maxHeight = max) {
                                    RemoteQueueRow(state, adaptive.collapseUpNext, guardedDispatch)
                                }
                            }
                        }
                        Column(contentModifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            header()
                            Row(Modifier.weight(1f).fillMaxWidth().testTag("remote.columns"), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (leftCards.isNotEmpty()) {
                                    BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().testTag("remote.infoColumn")) {
                                        val columnHeight = this.maxHeight
                                        BoundedCards(Modifier.fillMaxWidth().heightIn(max = columnHeight), leftCards)
                                    }
                                }
                                if (rightCards.isNotEmpty()) {
                                    BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().testTag("remote.queueColumn")) {
                                        val columnHeight = this.maxHeight
                                        BoundedCards(Modifier.fillMaxWidth().heightIn(max = columnHeight), rightCards)
                                    }
                                }
                            }
                            if (sections.seek) pinned()
                        }
                    } else {
                        Column(contentModifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val upperModifier = Modifier.weight(1f).fillMaxWidth().testTag("remote.upper")
                            Column(if (adaptive.scrollUpper) upperModifier.verticalScroll(rememberScrollState()) else upperModifier,
                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                header(); info(); toolbar()
                                if (sections.input) input(if (adaptive.scrollUpper) Modifier.fillMaxWidth().height(adaptive.minimumInputHeightDp.dp)
                                    else Modifier.weight(1f).fillMaxWidth().heightIn(min = adaptive.minimumInputHeightDp.dp))
                            }
                            pinned()
                        }
                    }
                    // Nav overlays the shell; its measured height reserves space above the bottom safe/IME inset.
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().then(measured { old, value -> old.copy(nav = value) })) {
                        RemoteNavBar(state, layout, dispatch)
                    }
                }
            }
        }
        // Per-media sheets start fresh on a new item; the queue stays open while it moves on.
        val sheetMedia = if (state.sheet == RemoteSheet.Queue) null else state.playback?.mediaId
        key(state.sessionId, sheetMedia) { RemoteSheets(state, dispatch, detectedSubtitles, execute) }
    }
}

/** Places cards at their intrinsic height. A card that would be sliced is capped and scrolls inside its own border. */
@Composable
private fun BoundedCards(modifier: Modifier, cards: List<@Composable (Dp) -> Unit>) {
    SubcomposeLayout(modifier) { constraints ->
        if (cards.isEmpty()) {
            layout(0, 0) {}
        } else {
            val gap = 8.dp.roundToPx()
            val width = if (constraints.hasBoundedWidth) constraints.maxWidth else 0
            val loose = Constraints(
                minWidth = width,
                maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else Constraints.Infinity,
            )
            val intrinsic = cards.mapIndexed { index, card ->
                subcompose("fit-$index") { card(Dp.Unspecified) }.first().measure(loose)
            }
            val gaps = gap * (cards.size - 1)
            val maxHeight = if (constraints.hasBoundedHeight) constraints.maxHeight else Constraints.Infinity
            val total = intrinsic.sumOf { it.height } + gaps
            val placeables = if (total <= maxHeight) intrinsic else {
                val floor = 48.dp.roundToPx()
                val heights = intrinsic.map { it.height }.toMutableList()
                var overflow = total - maxHeight
                while (overflow > 0) {
                    val index = heights.indices.maxBy { heights[it] }
                    if (heights[index] <= floor) break
                    val cut = minOf(overflow, heights[index] - floor)
                    heights[index] -= cut
                    overflow -= cut
                }
                cards.mapIndexed { index, card ->
                    val cap = heights[index].coerceAtLeast(0)
                    subcompose("cap-$index") { card(cap.toDp()) }.first().measure(
                        Constraints(minWidth = width, maxWidth = loose.maxWidth, maxHeight = cap),
                    )
                }
            }
            val used = placeables.sumOf { it.height } + gap * (placeables.size - 1).coerceAtLeast(0)
            layout(width, if (constraints.hasBoundedHeight) minOf(used, constraints.maxHeight) else used) {
                var y = 0
                placeables.forEach { placeable ->
                    placeable.place(0, y)
                    y += placeable.height + gap
                }
            }
        }
    }
}
