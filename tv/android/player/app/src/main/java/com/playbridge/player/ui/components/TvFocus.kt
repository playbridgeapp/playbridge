package com.playbridge.player.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.playbridge.player.ui.theme.TvRadius
import com.playbridge.player.ui.theme.TvSize
import com.playbridge.player.ui.theme.TvTheme

/** Which palette a shared component draws with: indigo brand surfaces or neutral player tones. */
enum class TvTone { App, Player }

/**
 * The one focus treatment: a colour swap ([container] to [focusedContainer]) plus an outer ring
 * (4dp, 3dp gap), both painted in the draw phase. It never changes size, padding or layout.
 * An optional [focusedScale] above 1f is applied through `graphicsLayer` only (default off).
 *
 * The ring is drawn outside the node's bounds, so parents (LazyRow etc.) need ~8dp of padding
 * on every side or it is clipped. [onFocusChange] reports focus for callers that swap text colour.
 * Pass [onClick] = null for a focusable-but-inert node.
 *
 * ```
 * Box(Modifier.size(60.dp).tvFocusable(onClick = ::toggle, shape = TvRadius.circle,
 *     container = TvTheme.player.control, focusedContainer = TvTheme.player.focusFill))
 * ```
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.tvFocusable(
    onClick: (() -> Unit)?,
    shape: Shape = TvRadius.rowShape,
    container: Color = Color.Transparent,
    focusedContainer: Color = Color.Unspecified,
    ringColor: Color = TvTheme.colors.focusRing,
    showRing: Boolean = true,
    focusedScale: Float = 1f,
    enabled: Boolean = true,
    role: Role? = null,
    contentDescription: String? = null,
    onLongClick: (() -> Unit)? = null,
    onFocusChange: ((Boolean) -> Unit)? = null,
): Modifier {
    var focused by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val ringWidth = TvSize.focusRingWidth
    val ringGap = TvSize.focusRingGap
    val fill = if (focusedContainer.isSpecified()) focusedContainer else container
    return this
        .graphicsLayer {
            val s = if (focused) focusedScale else 1f
            scaleX = s
            scaleY = s
        }
        .drawBehind {
            val outline = shape.createOutline(size, layoutDirection, this)
            drawOutline(outline, if (focused) fill else container)
            if (focused && showRing) {
                val off = (ringGap + ringWidth / 2).toPx()
                val grown = Size(size.width + 2 * off, size.height + 2 * off)
                translate(-off, -off) {
                    drawOutline(
                        shape.createOutline(grown, layoutDirection, this),
                        ringColor,
                        style = Stroke(ringWidth.toPx()),
                    )
                }
            }
        }
        .onFocusChanged {
            if (focused != it.isFocused) {
                focused = it.isFocused
                onFocusChange?.invoke(it.isFocused)
            }
        }
        .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier)
        .combinedClickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled && onClick != null,
            role = role,
            onLongClick = onLongClick,
            onClick = { onClick?.invoke() },
        )
}

private fun Color.isSpecified() = this != Color.Unspecified

/**
 * A fixed-size focusable box using [tvFocusable]; [content] receives the focus state so it can
 * pick a text/icon colour. Size comes from [modifier] and never depends on focus.
 */
@Composable
fun TvFocusSurface(
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    shape: Shape = TvRadius.rowShape,
    container: Color = TvTheme.colors.container,
    focusedContainer: Color = TvTheme.colors.focusFill,
    ringColor: Color = TvTheme.colors.focusRing,
    showRing: Boolean = true,
    focusedScale: Float = 1f,
    enabled: Boolean = true,
    role: Role? = Role.Button,
    contentDescription: String? = null,
    onLongClick: (() -> Unit)? = null,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier.tvFocusable(
            onClick = onClick, shape = shape, container = container, focusedContainer = focusedContainer,
            ringColor = ringColor, showRing = showRing, focusedScale = focusedScale, enabled = enabled,
            role = role, contentDescription = contentDescription, onLongClick = onLongClick,
            onFocusChange = { focused = it },
        ),
        contentAlignment = contentAlignment,
    ) { content(focused) }
}

/** Resolved colours for one tone: rest, focused, text and muted text. */
data class TvControlColors(
    val container: Color,
    val content: Color,
    val muted: Color,
    val focusedContainer: Color,
    val focusedContent: Color,
    val ring: Color,
)

/** Colours for circular controls, list rows and secondary pills in [tone]. */
@Composable
fun tvControlColors(tone: TvTone, row: Boolean = false): TvControlColors = when (tone) {
    TvTone.Player -> TvControlColors(
        container = if (row) TvTheme.player.row else TvTheme.player.control,
        content = TvTheme.player.onSurface, muted = TvTheme.player.onSurfaceMuted,
        focusedContainer = TvTheme.player.focusFill, focusedContent = TvTheme.player.onFocusFill,
        ring = TvTheme.player.focusRing,
    )
    TvTone.App -> TvControlColors(
        container = if (row) TvTheme.colors.container else TvTheme.colors.containerHigh,
        content = TvTheme.colors.onSurface, muted = TvTheme.colors.onSurfaceMuted,
        focusedContainer = TvTheme.colors.focusFill, focusedContent = TvTheme.colors.onFocusFill,
        ring = TvTheme.colors.focusRing,
    )
}
