package com.playbridge.sender.cast.remote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.playbridge.sender.cast.remote.remotePressFeedback

@Composable
internal fun RemoteButton(icon: ImageVector, label: String, enabled: Boolean, action: () -> Unit) =
    RemoteButton(icon, label, enabled, Color.Unspecified, action)

/** [tint] Unspecified keeps the default content colour. */
@Composable
internal fun RemoteButton(icon: ImageVector, label: String, enabled: Boolean, tint: Color, action: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    IconButton(
        onClick = action, enabled = enabled, interactionSource = source,
        modifier = Modifier.size(48.dp).remotePressFeedback(source),
    ) { if (tint == Color.Unspecified) Icon(icon, contentDescription = label) else Icon(icon, contentDescription = label, tint = tint) }
}

/** Theme-aware solid remote card. */
@Composable
internal fun Modifier.remoteSolidSurface(
    shape: RoundedCornerShape = RoundedCornerShape(24.dp),
): Modifier = clip(shape)
    .background(MaterialTheme.colorScheme.surfaceContainer)
    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)

internal val LocalRemoteControlsEnabled = staticCompositionLocalOf { true }

/** A solid card; only the separate floating nav layer is translucent.
 *  [maxHeight] keeps the rounded edge intact: content scrolls inside the card instead of the parent slicing it. */
@Composable
internal fun RemoteSectionCard(
    tag: String,
    enabled: Boolean = true,
    padding: Dp = 12.dp,
    maxHeight: Dp = Dp.Unspecified,
    content: @Composable () -> Unit,
) {
    val capped = maxHeight != Dp.Unspecified
    val scroll = rememberScrollState()
    Box(Modifier.then(if (capped) Modifier.heightIn(max = maxHeight) else Modifier).testTag(tag).remoteSolidSurface().padding(padding)) {
        Box(Modifier
            .then(if (capped) Modifier.verticalScroll(scroll) else Modifier)
            .alpha(if (enabled) 1f else 0.45f)
            .then(if (enabled) Modifier else Modifier.semantics { disabled() })) {
            CompositionLocalProvider(LocalRemoteControlsEnabled provides enabled, content = content)
        }
    }
}
