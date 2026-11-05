package com.playbridge.sender.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import java.util.Locale

/**
 * A sheet drawn inside the player's own (immersive) window, so the system bars stay hidden —
 * unlike [androidx.compose.material3.ModalBottomSheet], which spawns a separate window that
 * drops out of immersive mode. Portrait slides it up from the bottom; landscape docks it to
 * the right. Tap the scrim or press back to dismiss; [content] gets the scroll state so it
 * can scroll the selected row into view.
 */
@Composable
internal fun PlayerSheet(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.(ScrollState) -> Unit
) {
    BackHandler(onBack = onDismiss)
    val scrollState = rememberScrollState()
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } }
    ) {
        val landscape = maxWidth > maxHeight
        Surface(
            modifier = (if (landscape) {
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(360.dp)
            } else {
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .heightIn(max = maxHeight * 0.75f)
            })
                .pointerInput(Unit) { detectTapGestures { } }, // swallow taps so they don't dismiss
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = if (landscape) RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp)
            else RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(scrollState)
                    .windowInsetsPadding(
                        if (landscape) WindowInsets.navigationBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.End + WindowInsetsSides.Vertical)
                        else WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)
                    )
                    .padding(vertical = 8.dp)
            ) {
                if (!landscape) {
                    Box(
                        Modifier
                            .padding(top = 4.dp, bottom = 4.dp)
                            .size(width = 36.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.outline)
                            .align(Alignment.CenterHorizontally)
                    )
                }
                content(scrollState)
            }
        }
    }
}

internal class SheetRow(
    val label: String,
    val selected: Boolean,
    val enabled: Boolean,
    val onClick: () -> Unit
)

/**
 * Renders [rows] inside a [PlayerSheet], auto-scrolling so the selected row is in view.
 * Offset is estimated from row count (no layout-coordinate APIs needed).
 */
@Composable
internal fun SelectionSheet(
    header: String,
    rows: List<SheetRow>,
    emptyMessage: String?,
    onDismiss: () -> Unit
) {
    val density = LocalDensity.current
    PlayerSheet(onDismiss = onDismiss) { scrollState ->
        val selectedIndex = rows.indexOfFirst { it.selected }
        LaunchedEffect(selectedIndex) {
            if (selectedIndex > 0) {
                val rowPx = with(density) { 48.dp.toPx() }
                val headerPx = with(density) { 52.dp.toPx() }
                val target = (headerPx + (selectedIndex - 1) * rowPx).toInt().coerceAtLeast(0)
                scrollState.animateScrollTo(target)
            }
        }
        TrackSectionHeader(header)
        if (rows.isEmpty() && emptyMessage != null) EmptyTracksMessage(emptyMessage)
        rows.forEach { r -> TrackRow(r.label, r.selected, r.enabled, r.onClick) }
    }
}

/** One sheet for both the audio track and the subtitles the stream exposes. */
@Composable
internal fun TracksSheet(
    tracks: Tracks,
    onDismiss: () -> Unit,
    onSelectAudio: (TrackGroup, Int) -> Unit,
    onSelectText: (TrackGroup, Int) -> Unit,
    onDisableText: () -> Unit
) {
    val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
    val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
    PlayerSheet(onDismiss = onDismiss) {
        TrackSectionHeader("Audio")
        if (audioGroups.sumOf { it.length } <= 1) EmptyTracksMessage("This stream has only one audio track.")
        audioGroups.forEach { g ->
            for (i in 0 until g.length) {
                TrackRow(audioTrackLabel(g.getTrackFormat(i), i), g.isTrackSelected(i), g.isTrackSupported(i),
                    onClick = { onSelectAudio(g.mediaTrackGroup, i); onDismiss() })
            }
        }
        TrackSectionHeader("Subtitles")
        val anyTextSelected = textGroups.any { g -> (0 until g.length).any { g.isTrackSelected(it) } }
        TrackRow("Off", !anyTextSelected, true, onClick = { onDisableText(); onDismiss() })
        textGroups.forEach { g ->
            for (i in 0 until g.length) {
                TrackRow(textTrackLabel(g.getTrackFormat(i), i), g.isTrackSelected(i), g.isTrackSupported(i),
                    onClick = { onSelectText(g.mediaTrackGroup, i); onDismiss() })
            }
        }
    }
}

@Composable
internal fun EmptyTracksMessage(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp)
    )
}

/** Bottom sheet listing the episodes in the current playlist; tap to jump. */
@Composable
internal fun EpisodeSheet(
    header: String,
    titles: List<String>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val rows = titles.mapIndexed { i, t ->
        SheetRow(t, i == currentIndex, true) { onSelect(i); onDismiss() }
    }
    SelectionSheet(header = header, rows = rows, emptyMessage = null, onDismiss = onDismiss)
}

@Composable
internal fun TrackSectionHeader(text: String) {
    Text(
        text = text.uppercase(Locale.getDefault()),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 6.dp)
    )
}

@Composable
internal fun TrackRow(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = when {
                !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                selected -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
            }
        )
    }
}

internal fun audioTrackLabel(format: Format, index: Int): String {
    val parts = mutableListOf<String>()
    format.label?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
    trackLanguageName(format.language)?.let { name ->
        if (parts.none { it.equals(name, ignoreCase = true) }) parts.add(name)
    }
    if (format.channelCount > 0) {
        parts.add(
            when (format.channelCount) {
                1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"
                else -> "${format.channelCount}ch"
            }
        )
    }
    return parts.joinToString(" · ").ifBlank { "Audio ${index + 1}" }
}

internal fun textTrackLabel(format: Format, index: Int): String {
    val parts = mutableListOf<String>()
    format.label?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
    trackLanguageName(format.language)?.let { name ->
        if (parts.none { it.equals(name, ignoreCase = true) }) parts.add(name)
    }
    return parts.joinToString(" · ").ifBlank { "Subtitle ${index + 1}" }
}

internal fun trackLanguageName(lang: String?): String? {
    if (lang.isNullOrBlank() || lang == "und") return null
    return try {
        Locale.forLanguageTag(lang).displayLanguage.ifBlank { lang }
    } catch (e: Exception) {
        lang
    }
}

/** Whichever sheet is open: More, episodes or queue, or audio and subtitles. */
@Composable
internal fun PlayerSheetsHost(
    state: PlayerScreenState,
    engine: PhonePlayerEngine,
    onSwitchEngine: (PhonePlayerEngine) -> Unit,
    backgroundMode: MutableState<Boolean>,
    onOpenExternal: () -> Unit,
) {
    val player = state.player
    if (state.showMore) {
        MoreSheet(state, engine, onSwitchEngine, backgroundMode, onOpenExternal)
    }

    if (state.showEpisodes) {
        EpisodeSheet(
            header = if (state.websiteQueue) "Queue" else "Episodes",
            titles = state.episodeTitles,
            currentIndex = state.currentIndex,
            onSelect = { idx -> state.goToEpisode(idx) },
            onDismiss = { state.showEpisodes = false }
        )
    }

    if (state.showTracks) {
        TracksSheet(
            tracks = state.currentTracks,
            onDismiss = { state.showTracks = false },
            onSelectAudio = { group, idx ->
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setOverrideForType(TrackSelectionOverride(group, idx))
                    .build()
            },
            onSelectText = { group, idx ->
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setOverrideForType(TrackSelectionOverride(group, idx))
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .build()
            },
            onDisableText = {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
            }
        )
    }
}
