package com.playbridge.player.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.Text
import com.playbridge.player.ui.theme.TvRadius
import com.playbridge.player.ui.theme.TvSize
import com.playbridge.player.ui.theme.TvSpacing
import com.playbridge.player.ui.theme.TvText
import com.playbridge.player.ui.theme.TvTheme

/**
 * Fixed-height (60dp) list row: [title], optional [subtitle] under it, optional [trailing] text
 * (right aligned, muted) and a check when [selected]. Used in side panels and nav lists.
 * Selected rows are semibold and tinted; focused rows swap to the focus fill with the ring.
 */
@Composable
fun ListRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: String? = null,
    selected: Boolean = false,
    tone: TvTone = TvTone.Player,
    height: Dp = TvSize.listRow,
    contentDescription: String? = null,
) {
    val c = tvControlColors(tone, row = true)
    val selectedFill = if (tone == TvTone.App) TvTheme.colors.accentContainer else c.container
    TvFocusSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(height).semantics { this.selected = selected },
        shape = TvRadius.rowShape,
        container = if (selected) selectedFill else c.container,
        focusedContainer = c.focusedContainer,
        ringColor = c.ring,
        role = Role.RadioButton,
        contentDescription = contentDescription,
        contentAlignment = Alignment.CenterStart,
    ) { focused ->
        val fg = if (focused) c.focusedContent else c.content
        val muted = if (focused) c.focusedContent else c.muted
        Row(
            Modifier.fillMaxWidth().padding(horizontal = TvSpacing.xl),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TvSpacing.m),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title, style = if (selected) TvText.rowSelected else TvText.row, color = fg,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(subtitle, style = TvText.small, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (trailing != null) {
                Text(trailing, style = TvText.value, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (selected) {
                Text("✓", style = TvText.row, color = fg)
            }
        }
    }
}
