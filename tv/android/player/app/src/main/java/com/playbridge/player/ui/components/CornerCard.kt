package com.playbridge.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.playbridge.player.ui.theme.TvRadius
import com.playbridge.player.ui.theme.TvSize
import com.playbridge.player.ui.theme.TvSpacing
import com.playbridge.player.ui.theme.TvTheme

/**
 * A card that sits in a screen corner over the current content (pairing, invites, updates, phone
 * confirmations). Fixed [width] (480dp default; 440dp for pairing), 24dp radius, 30dp padding,
 * content stacked with [gap]. When [takesFocus] is false nothing inside is focusable, so the
 * screen underneath keeps D-pad focus; set it true only when the card needs an answer.
 * Announced to accessibility as a polite live region titled [contentDescription].
 */
@Composable
fun CornerCard(
    contentDescription: String,
    modifier: Modifier = Modifier,
    width: Dp = TvSize.cornerCardWidth,
    takesFocus: Boolean = true,
    gap: Dp = 20.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .width(width)
            .then(if (takesFocus) Modifier else Modifier.focusProperties { canFocus = false })
            .background(TvTheme.colors.container, TvRadius.cardShape)
            .padding(30.dp)
            .semantics { paneTitle = contentDescription; liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(gap),
        content = content,
    )
}

/** Positions a [CornerCard] over a screen: [alignment] is a corner, inset 48dp from the edges. */
@Composable
fun BoxScope.CornerCardSlot(
    alignment: Alignment = Alignment.BottomEnd,
    content: @Composable () -> Unit,
) {
    Box(Modifier.align(alignment).padding(TvSpacing.cornerInset)) { content() }
}
