package com.playbridge.player.ui.player.panels

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import com.playbridge.player.ui.components.ListRow

/** Index of the engine row in [PlayerEngines] for [currentPlayer], or 0. */
internal fun playerChoiceFocusIndex(currentPlayer: String): Int =
    PlayerEngines.indexOfFirst { it.first == currentPlayer.lowercase() }.coerceAtLeast(0)

/** The engine rows (ExoPlayer, MPV), shared by the Player panel and the Player page of More. */
internal fun LazyListScope.playerChoiceItems(
    currentPlayer: String,
    focus: PanelFocus,
    onPlayerSelected: (String) -> Unit,
) {
    items(PlayerEngines, key = { it.first }) { (id, name) ->
        val index = PlayerEngines.indexOfFirst { it.first == id }
        val selected = id == currentPlayer.lowercase()
        ListRow(
            title = name,
            subtitle = if (selected) "Playing with this player" else null,
            selected = selected,
            onClick = { onPlayerSelected(id) },
            modifier = Modifier.initialPanelFocus(focus, index),
        )
    }
}
