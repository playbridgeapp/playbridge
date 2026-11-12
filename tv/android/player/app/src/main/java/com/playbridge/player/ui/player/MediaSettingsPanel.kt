package com.playbridge.player.ui.player

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.playbridge.player.ui.components.ListRow
import com.playbridge.player.ui.player.panels.PanelCaption
import com.playbridge.player.ui.player.panels.PanelFocus
import com.playbridge.player.ui.player.panels.PlaybackSpeeds
import com.playbridge.player.ui.player.panels.PlayerSidePanel
import com.playbridge.player.ui.player.panels.ScalingOptions
import com.playbridge.player.ui.player.panels.initialPanelFocus
import com.playbridge.player.ui.player.panels.playerChoiceFocusIndex
import com.playbridge.player.ui.player.panels.playerChoiceItems
import com.playbridge.player.ui.player.panels.playerEngineLabel
import com.playbridge.player.ui.player.panels.scalingLabel
import com.playbridge.player.ui.player.panels.speedLabel

private enum class MorePage(val title: String) {
    MAIN("More"), AUDIO("Audio"), QUALITY("Quality"), SPEED("Speed"), SCALING("Picture size"), PLAYER("Player"),
}

/** One row of the main "More" list. */
private class MoreEntry(
    val title: String,
    val value: String,
    val page: MorePage? = null,
    val onClick: (() -> Unit)? = null,
)

/**
 * The "More" side panel: Audio, Quality, Speed, Picture size, Boost quiet audio, Repeat, Chapters
 * and Player as rows with their current value; Audio, Quality, Speed, Picture size and Player open
 * a page in the same panel. Left or Back go from a page to the list, and from the list call
 * [onDismiss].
 *
 * Optional extras (rows are hidden while the callback is null):
 * - [onPlayerSwitched]: shows the Player row/page; pass the host's renderer switch.
 * - [onToggleLoop]: shows the Repeat row (value from `state.isLooping`).
 * - [onOpenChapters]: shows the Chapters row when the media has two or more chapters.
 *
 * `state.activeSettingsTab == AUDIO` opens straight on the Audio page (the control row's Audio
 * button); any other value opens the list (the More button). Back from a page opened that way
 * closes the panel, since there is no list behind it. Read once, when the panel opens.
 */
@Composable
fun MediaSettingsPanel(
    state: PlayerControlsState,
    onTrackSelected: (UnifiedTrack) -> Unit,
    onSpeedSelected: (Float) -> Unit,
    onScalingSelected: (String) -> Unit,
    onToggleAudioBoost: () -> Unit,
    onDismiss: () -> Unit,
    onPlayerSwitched: ((String) -> Unit)? = null,
    onToggleLoop: (() -> Unit)? = null,
    onOpenChapters: (() -> Unit)? = null,
) {
    val startPage = remember { if (state.activeSettingsTab == SettingsTab.AUDIO) MorePage.AUDIO else MorePage.MAIN }
    var page by remember { mutableStateOf(startPage) }
    var mainFocusIndex by remember { mutableIntStateOf(0) }
    val back: () -> Unit = {
        if (page != MorePage.MAIN && startPage == MorePage.MAIN) page = MorePage.MAIN else onDismiss()
    }

    key(page) {
        when (page) {
            MorePage.MAIN -> {
                val caps = state.capabilities
                val entries = buildList {
                    if (state.audioTracks.isNotEmpty()) {
                        add(MoreEntry("Audio", state.audioTracks.firstOrNull { it.isSelected }?.name ?: "", MorePage.AUDIO))
                    }
                    if (caps.qualityAvailable && state.videoTracks.hasSelectableVideoQualities()) {
                        add(MoreEntry("Quality", state.videoTracks.firstOrNull { it.isSelected }?.name ?: "Auto", MorePage.QUALITY))
                    }
                    if (caps.speedAvailable) add(MoreEntry("Speed", speedLabel(state.playbackSpeed), MorePage.SPEED))
                    if (caps.scalingAvailable) add(MoreEntry("Picture size", scalingLabel(state.videoScalingMode), MorePage.SCALING))
                    if (caps.audioBoostAvailable) {
                        add(MoreEntry("Boost quiet audio", if (state.isAudioBoostEnabled) "On" else "Off", onClick = onToggleAudioBoost))
                    }
                    if (onToggleLoop != null) {
                        add(MoreEntry("Repeat", if (state.isLooping) "On" else "Off", onClick = onToggleLoop))
                    }
                    if (onOpenChapters != null && state.hasSelectableChapters()) {
                        add(MoreEntry("Chapters", state.chapters.size.toString(), onClick = onOpenChapters))
                    }
                    if (onPlayerSwitched != null && state.canSwitchPlayer) {
                        add(MoreEntry("Player", playerEngineLabel(state.engineType), MorePage.PLAYER))
                    }
                }
                val focus = remember { PanelFocus(mainFocusIndex.coerceIn(0, (entries.size - 1).coerceAtLeast(0))) }
                val streamDetails = listOfNotNull(state.streamInfo, state.hdrFormat).filter { it.isNotBlank() }.joinToString(" · ")
                PlayerSidePanel(title = MorePage.MAIN.title, onDismiss = back, focus = focus) {
                    items(entries.size) { i ->
                        val e = entries[i]
                        ListRow(
                            title = e.title,
                            trailing = e.value.takeIf { it.isNotEmpty() },
                            onClick = {
                                mainFocusIndex = i
                                if (e.page != null) page = e.page else e.onClick?.invoke()
                            },
                            modifier = Modifier.initialPanelFocus(focus, i),
                        )
                    }
                    if (streamDetails.isNotEmpty()) {
                        item(key = "stream_details") { PanelCaption("Stream: $streamDetails") }
                    }
                }
            }

            MorePage.AUDIO -> TrackPage(MorePage.AUDIO.title, state.audioTracks, onTrackSelected, back)
            MorePage.QUALITY -> TrackPage(MorePage.QUALITY.title, state.videoTracks, onTrackSelected, back)

            MorePage.SPEED -> {
                val focus = remember { PanelFocus(PlaybackSpeeds.indexOf(state.playbackSpeed).coerceAtLeast(0)) }
                PlayerSidePanel(title = MorePage.SPEED.title, onDismiss = back, focus = focus) {
                    items(PlaybackSpeeds.size) { i ->
                        val speed = PlaybackSpeeds[i]
                        ListRow(
                            title = speedLabel(speed),
                            selected = speed == state.playbackSpeed,
                            onClick = { onSpeedSelected(speed) },
                            modifier = Modifier.initialPanelFocus(focus, i),
                        )
                    }
                }
            }

            MorePage.SCALING -> {
                val focus = remember { PanelFocus(ScalingOptions.indexOfFirst { it.mode == state.videoScalingMode }.coerceAtLeast(0)) }
                PlayerSidePanel(title = MorePage.SCALING.title, onDismiss = back, focus = focus) {
                    items(ScalingOptions.size) { i ->
                        val o = ScalingOptions[i]
                        ListRow(
                            title = o.label,
                            subtitle = o.description,
                            selected = o.mode == state.videoScalingMode,
                            onClick = { onScalingSelected(o.mode) },
                            modifier = Modifier.initialPanelFocus(focus, i),
                        )
                    }
                }
            }

            MorePage.PLAYER -> {
                val current = state.engineType.lowercase()
                val focus = remember { PanelFocus(playerChoiceFocusIndex(current)) }
                PlayerSidePanel(title = MorePage.PLAYER.title, onDismiss = back, focus = focus) {
                    playerChoiceItems(current, focus) { id -> onPlayerSwitched?.invoke(id) }
                }
            }
        }
    }
}

/** A page listing audio or video tracks with a check on the selected one. */
@Composable
private fun TrackPage(
    title: String,
    tracks: List<UnifiedTrack>,
    onTrackSelected: (UnifiedTrack) -> Unit,
    onBack: () -> Unit,
) {
    val focus = remember { PanelFocus(tracks.indexOfFirst { it.isSelected }.coerceAtLeast(0)) }
    PlayerSidePanel(title = title, onDismiss = onBack, focus = focus) {
        trackRows(tracks, focus, onTrackSelected)
        if (tracks.isEmpty()) item(key = "empty") { PanelCaption("No tracks available") }
    }
}

private fun LazyListScope.trackRows(
    tracks: List<UnifiedTrack>,
    focus: PanelFocus,
    onTrackSelected: (UnifiedTrack) -> Unit,
) {
    items(tracks.size, key = { tracks[it].id }) { i ->
        val t = tracks[i]
        ListRow(
            title = t.name,
            subtitle = t.secondaryText,
            selected = t.isSelected,
            onClick = { onTrackSelected(t) },
            modifier = Modifier.initialPanelFocus(focus, i),
        )
    }
}
