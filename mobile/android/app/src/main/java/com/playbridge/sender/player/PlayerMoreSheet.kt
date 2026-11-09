package com.playbridge.sender.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.clickable
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout

private val SPEED_CHOICES = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/**
 * Everything that isn't a primary control: speed, repeat, A-B loop and what leaving does;
 * aspect, zoom and frame capture; which decoder plays; stats; and handing off to another app.
 */
@Composable
internal fun MoreSheet(
    state: PlayerScreenState,
    engine: PhonePlayerEngine,
    onSwitchEngine: (PhonePlayerEngine) -> Unit,
    leaveAction: MutableState<PhoneLeaveAction>,
    onOpenExternal: () -> Unit,
) {
    fun close() { state.showMore = false }
    PlayerSheet(onDismiss = ::close) {
        TrackSectionHeader("Playback")
        ChoiceLabel("Speed")
        ChoiceChips(SPEED_CHOICES.map { speedLabel(it) to (state.speed == it) }) { state.applySpeed(SPEED_CHOICES[it]) }
        ChoiceLabel("Repeat")
        val repeatModes = listOf(Player.REPEAT_MODE_OFF to "Off", Player.REPEAT_MODE_ONE to "One", Player.REPEAT_MODE_ALL to "All")
        ChoiceChips(repeatModes.map { (mode, label) -> label to (state.repeatMode == mode) }) { state.setRepeat(repeatModes[it].first) }
        ActionRow(
            label = "A-B loop",
            value = when {
                state.abStartMs == null -> "Set A"
                state.abEndMs == null -> "Set B"
                else -> "Clear"
            },
            onClick = state::stepAbLoop,
        )
        // The Settings choice, for this video only.
        ChoiceLabel("When you leave the player")
        val leaveChoices = PhoneLeaveAction.entries
        ChoiceChips(leaveChoices.map { it.label to (leaveAction.value == it) }) {
            val choice = leaveChoices[it]
            leaveAction.value = choice
            state.statusMessage = when (choice) {
                PhoneLeaveAction.PAUSE -> "Leaving pauses this video"
                PhoneLeaveAction.KEEP_PLAYING -> "This video keeps playing when you leave"
                PhoneLeaveAction.PICTURE_IN_PICTURE -> "Leaving shrinks this video into a window"
            }
        }

        TrackSectionHeader("Picture")
        val aspects = listOf(
            AspectRatioFrameLayout.RESIZE_MODE_FIT to "Fit",
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Zoom",
            AspectRatioFrameLayout.RESIZE_MODE_FILL to "Stretch",
        )
        ChoiceChips(aspects.map { (mode, label) -> label to (state.resizeMode == mode) }) { state.setAspect(aspects[it].first) }
        SwitchRow(
            label = "Zoom and pan",
            detail = "Pinch to zoom, drag to pan",
            checked = state.isPanZoomEnabled,
        ) { state.togglePanZoom() }
        ActionRow("Capture frame") { close(); state.captureFrame() }

        TrackSectionHeader("Player")
        ChoiceChips(PhonePlayerEngine.entries.map { it.label to (it == engine) }) { index ->
            val choice = PhonePlayerEngine.entries[index]
            close()
            if (choice != engine) onSwitchEngine(choice)
        }
        ActionRow("Playback stats", if (state.showStats) "Shown" else "Hidden") {
            state.showStats = !state.showStats
            close()
        }
        ActionRow("Open in another app") { close(); onOpenExternal() }
    }
}

@Composable
private fun ChoiceLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 2.dp),
    )
}

/** A row of single-choice chips; [onPick] gets the tapped chip's index. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceChips(choices: List<Pair<String, Boolean>>, onPick: (Int) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        choices.forEachIndexed { index, (label, selected) ->
            FilterChip(selected = selected, onClick = { onPick(index) }, label = { Text(label) })
        }
    }
}

@Composable
private fun ActionRow(label: String, value: String? = null, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (value != null) {
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SwitchRow(label: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
