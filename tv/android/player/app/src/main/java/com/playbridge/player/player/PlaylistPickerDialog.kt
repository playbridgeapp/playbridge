package com.playbridge.player.player

import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.playbridge.player.ui.player.panels.PanelFocus
import com.playbridge.player.ui.player.panels.PanelRow
import com.playbridge.player.ui.player.panels.PlayerSidePanel
import com.playbridge.player.ui.player.panels.initialPanelFocus
import com.playbridge.player.ui.player.panels.queuePosition
import com.playbridge.player.ui.player.panels.queueRowText
import playbridge.PlayPayload

/**
 * Right-hand Queue panel over the video: numbered items, the playing one tinted and marked
 * "Playing now", the next one "Up next". Selecting an item calls [onItemSelected] with its
 * 0-based index. Items are added, removed and reordered from the phone, so the panel says so.
 */
@Composable
fun PlaylistPickerDialog(
    items: List<PlayPayload>,
    currentIndex: Int,
    onItemSelected: (index: Int) -> Unit,
    onDismiss: () -> Unit
) {
    val focus = remember(currentIndex) { PanelFocus(currentIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))) }

    PlayerSidePanel(
        title = "Queue",
        headerValue = queuePosition(currentIndex, items.size),
        footer = "Add or reorder videos from your phone.",
        onDismiss = onDismiss,
        focus = focus,
    ) {
        itemsIndexed(items) { index, item ->
            val text = queueRowText(index, item.title, currentIndex)
            PanelRow(
                title = text.title,
                subtitle = text.subtitle,
                current = index == currentIndex,
                height = 72.dp,
                onClick = { onItemSelected(index) },
                modifier = Modifier.initialPanelFocus(focus, index),
            )
        }
    }
}
