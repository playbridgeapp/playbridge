package com.playbridge.player.ui.player.panels

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.withFrameNanos
import androidx.tv.material3.Text
import com.playbridge.player.ui.components.TvFocusSurface
import com.playbridge.player.ui.components.TvTone
import com.playbridge.player.ui.components.tvControlColors
import com.playbridge.player.ui.theme.TvRadius
import com.playbridge.player.ui.theme.TvSize
import com.playbridge.player.ui.theme.TvSpacing
import com.playbridge.player.ui.theme.TvText
import com.playbridge.player.ui.theme.TvTheme

/** Room around the lazy list so the 7dp focus ring of the outermost rows is not clipped. */
private val RingRoom = 8.dp

/** Which lazy item should take focus when a panel opens. */
class PanelFocus(val index: Int) {
    val requester = FocusRequester()
}

/** Marks the lazy item at [itemIndex] as the one that receives initial focus. */
fun Modifier.initialPanelFocus(focus: PanelFocus, itemIndex: Int): Modifier =
    if (itemIndex == focus.index) focusRequester(focus.requester) else this

/**
 * The shared right-hand panel for the player (Subtitles, More, Queue, Chapters, Player).
 * Same metrics as `SidePanel` (480dp wide, full height, panel colour, 40/36dp padding, 30sp title)
 * but with a [LazyColumn] so long lists stay cheap. It fills the screen and aligns the panel to the
 * right edge, so a caller can slide the whole thing in as one layer.
 *
 * Keys: Up and Down move through the rows and stop at the ends; focus cannot leave the panel;
 * Left or Back call [onDismiss]. Initial focus goes to the item that carries
 * [initialPanelFocus] for [focus].
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PlayerSidePanel(
    title: String,
    onDismiss: () -> Unit,
    focus: PanelFocus,
    modifier: Modifier = Modifier,
    headerValue: String? = null,
    footer: String? = null,
    listState: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    val dismiss by rememberUpdatedState(onDismiss)
    BackHandler { dismiss() }

    LaunchedEffect(focus) {
        if (focus.index > 2) listState.scrollToItem(focus.index - 2)
        var attempts = 0
        while (attempts < 6) {
            val ok = runCatching { focus.requester.requestFocus() }.isSuccess
            if (ok) break
            withFrameNanos { }
            attempts++
        }
    }

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier
                .width(TvSize.sidePanelWidth)
                .fillMaxHeight()
                .background(TvTheme.player.panel)
                .padding(horizontal = TvSpacing.panelPaddingH - RingRoom, vertical = TvSpacing.panelPaddingV)
                .semantics { paneTitle = title }
                .onKeyEvent { e ->
                    if (e.key == Key.DirectionLeft) {
                        if (e.type == KeyEventType.KeyDown) dismiss()
                        true
                    } else false
                }
                .focusProperties { exit = { FocusRequester.Cancel } }
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(TvSpacing.l),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = RingRoom),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    title, style = TvText.panelTitle, color = TvTheme.player.onSurface,
                    modifier = Modifier.semantics { heading() },
                )
                if (headerValue != null) {
                    Text(
                        headerValue, style = TvText.value, color = TvTheme.player.onSurfaceMuted,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(RingRoom),
                verticalArrangement = Arrangement.spacedBy(TvSpacing.rowGap),
                content = content,
            )
            if (footer != null) {
                Text(
                    footer, style = TvText.caption, color = TvTheme.player.onSurfaceMuted,
                    modifier = Modifier.padding(horizontal = RingRoom),
                )
            }
        }
    }
}

/** Muted caption line inside a panel list. */
@Composable
internal fun PanelCaption(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = TvText.caption, color = TvTheme.player.onSurfaceMuted,
        modifier = modifier.fillMaxWidth().padding(horizontal = TvSpacing.s, vertical = TvSpacing.xs),
    )
}

/**
 * Fixed-height panel row with an optional fixed-width [leading] label (a time), [title], an
 * optional [subtitle] and a [trailing] note. [current] tints the row with the accent container
 * (the item that is playing now); focus swaps to the focus fill and ring. Never changes size.
 */
@Composable
internal fun PanelRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: String? = null,
    leadingWidth: Dp = 62.dp,
    subtitle: String? = null,
    trailing: String? = null,
    current: Boolean = false,
    height: Dp = 64.dp,
) {
    val c = tvControlColors(TvTone.Player, row = true)
    val currentFill = TvTheme.colors.accentContainer
    val onCurrent = TvTheme.colors.onAccentContainer
    TvFocusSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(height),
        shape = TvRadius.rowShape,
        container = if (current) currentFill else c.container,
        focusedContainer = c.focusedContainer,
        ringColor = c.ring,
        role = Role.RadioButton,
        contentAlignment = Alignment.CenterStart,
    ) { focused ->
        val fg = when { focused -> c.focusedContent; current -> onCurrent; else -> c.content }
        val muted = when { focused -> c.focusedContent; current -> onCurrent; else -> c.muted }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = TvSpacing.xl),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TvSpacing.xl - 2.dp),
        ) {
            if (leading != null) {
                Text(leading, style = TvText.value, color = muted, maxLines = 1, modifier = Modifier.width(leadingWidth))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title, style = if (current) TvText.rowSelected else TvText.row, color = fg,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(subtitle, style = TvText.small, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (trailing != null) {
                Text(trailing, style = TvText.small, color = muted, maxLines = 1)
            }
        }
    }
}

/** Centered-label button used for timing controls (Earlier / Later / Reset). Fixed size, no scaling. */
@Composable
internal fun PanelButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 56.dp,
    quiet: Boolean = false,
) {
    val c = tvControlColors(TvTone.Player, row = false)
    TvFocusSurface(
        onClick = onClick,
        modifier = modifier.height(height),
        shape = TvRadius.rowShape,
        container = if (quiet) TvTheme.player.panel else c.container,
        focusedContainer = c.focusedContainer,
        ringColor = c.ring,
        role = Role.Button,
    ) { focused ->
        Text(
            text, style = TvText.button,
            color = if (focused) c.focusedContent else if (quiet) c.muted else c.content,
            maxLines = 1,
        )
    }
}
