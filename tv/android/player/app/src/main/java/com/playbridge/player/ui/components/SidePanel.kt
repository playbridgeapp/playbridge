package com.playbridge.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.tv.material3.Text
import com.playbridge.player.ui.theme.TvSize
import com.playbridge.player.ui.theme.TvSpacing
import com.playbridge.player.ui.theme.TvText
import com.playbridge.player.ui.theme.TvTheme

/**
 * Right-hand layer over the content: fixed 480dp wide, full height, 40/36dp padding, [title] and a
 * scrollable column of [content] (rows 8dp apart). Put it inside [SidePanelHost], or align it
 * `CenterEnd` in your own Box. Open and close it instantly or with an alpha fade, never an
 * animated width.
 */
@Composable
fun SidePanel(
    title: String,
    modifier: Modifier = Modifier,
    tone: TvTone = TvTone.Player,
    content: @Composable ColumnScope.() -> Unit,
) {
    val bg = if (tone == TvTone.Player) TvTheme.player.panel else TvTheme.colors.container
    val fg = if (tone == TvTone.Player) TvTheme.player.onSurface else TvTheme.colors.onSurface
    Column(
        modifier = modifier
            .width(TvSize.sidePanelWidth)
            .fillMaxHeight()
            .background(bg)
            .padding(horizontal = TvSpacing.panelPaddingH, vertical = TvSpacing.panelPaddingV)
            .semantics { paneTitle = title },
        verticalArrangement = Arrangement.spacedBy(TvSpacing.l),
    ) {
        Text(title, style = TvText.panelTitle, color = fg, modifier = Modifier.semantics { heading() })
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(vertical = TvSpacing.s),
            verticalArrangement = Arrangement.spacedBy(TvSpacing.rowGap),
            content = content,
        )
    }
}

/** Places [panel] on the right edge over [content]; the content keeps its full size underneath. */
@Composable
fun SidePanelHost(
    panel: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier) {
        content()
        if (panel != null) Box(Modifier.align(Alignment.CenterEnd)) { panel() }
    }
}
