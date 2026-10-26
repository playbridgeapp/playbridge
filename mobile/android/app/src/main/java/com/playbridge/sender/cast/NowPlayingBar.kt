package com.playbridge.sender.cast

import com.playbridge.sender.cast.remote.ui.QueueTint
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The shared mini player (Phone Files canvas, "Mini player" board), shown across the main
 * screens. The host computes the texts and flags from the live connection state:
 *
 *  - **Playing / paused**: thumbnail, title, "on <TV>" (or "Paused · on <TV>"), an Up next
 *    pill with the count when anything is queued, and play/pause. Tapping opens the Remote.
 *  - **Linked to a website**: as playing, plus Unlink; the subtitle names the website.
 *  - **TV chosen, nothing playing** and **This phone**: the destination with a chevron;
 *    tapping opens Play on.
 *
 * A thin progress line runs along the bottom while media is loaded. [accentColor] (the
 * library detail poster) replaces the container colour when given. A phone list's [listTint]
 * colours the bar like Up next's current card: the artwork's light tint fills as it plays.
 */
@Composable
fun NowPlayingBar(
    primaryText: String,
    secondaryText: String?,
    leadingIcon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = false,
    isPaused: Boolean = false,
    /** 0..1 playback progress for the bottom line; null hides it (e.g. live streams). */
    progress: Float? = null,
    /** Idle with a connected TV: the destination icon shows as ready. */
    ready: Boolean = false,
    /** Playing: the item's thumbnail; null shows [leadingIcon] in a tile. */
    thumbnail: (@Composable () -> Unit)? = null,
    upNextCount: Int = 0,
    onUpNext: (() -> Unit)? = null,
    onPlayPause: (() -> Unit)? = null,
    onUnlinkClick: (() -> Unit)? = null,
    accentColor: Color? = null,
    listTint: QueueTint? = null,
) {
    val container = listTint?.cardBase ?: accentColor ?: MaterialTheme.colorScheme.surfaceContainerHighest
    val content = when {
        listTint != null -> listTint.onCard
        accentColor != null -> if (accentColor.luminance() > 0.5f) Color.Black else Color.White
        else -> MaterialTheme.colorScheme.onSurface
    }
    val muted = content.copy(alpha = 0.68f)

    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        shape = RoundedCornerShape(18.dp),
        color = container,
        shadowElevation = 8.dp,
    ) {
        Box(Modifier.height(64.dp)) {
            if (listTint != null && isPlaying && progress != null) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(listTint.cardFill))
            }
            Row(
                Modifier.fillMaxHeight().padding(start = 8.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)
                        .semantics { contentDescription = if (isPlaying) "Open remote" else "Change where to play" },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (isPlaying) {
                        Box(
                            Modifier.size(64.dp, 36.dp).clip(RoundedCornerShape(8.dp)).background(content.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            thumbnail?.invoke() ?: Icon(leadingIcon, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
                        }
                    } else {
                        Box(
                            Modifier.size(40.dp).clip(CircleShape)
                                .background(if (ready) Color(0xFF16301F) else content.copy(alpha = 0.10f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(leadingIcon, contentDescription = null, tint = if (ready) Color(0xFF46D483) else content, modifier = Modifier.size(20.dp))
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            primaryText,
                            style = if (isPlaying) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = content,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (secondaryText != null) {
                            Text(
                                if (isPlaying && isPaused) "Paused · $secondaryText" else secondaryText,
                                style = MaterialTheme.typography.labelMedium,
                                color = muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (!isPlaying) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = muted, modifier = Modifier.padding(end = 8.dp))
                    }
                }
                if (isPlaying && upNextCount > 0 && onUpNext != null) {
                    Row(
                        Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(content.copy(alpha = 0.08f))
                            .clickable(onClick = onUpNext).padding(horizontal = 12.dp)
                            .semantics { contentDescription = "Up next, $upNextCount items" },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
                        Text(upNextCount.toString(), style = MaterialTheme.typography.labelLarge, color = content)
                    }
                }
                if (isPlaying && onUnlinkClick != null) {
                    IconButton(onClick = onUnlinkClick, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Default.LinkOff, contentDescription = "Unlink website", tint = muted, modifier = Modifier.size(20.dp))
                    }
                }
                if (isPlaying && onPlayPause != null) {
                    IconButton(
                        onClick = onPlayPause,
                        modifier = Modifier.size(44.dp).clip(CircleShape).background(listTint?.onCard ?: MaterialTheme.colorScheme.primary),
                    ) {
                        Icon(
                            if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                            contentDescription = if (isPaused) "Play" else "Pause",
                            tint = listTint?.cardFill ?: MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
            // Perf: position ticks at ~1 Hz, so no tween (it would never settle).
            if (isPlaying && progress != null && listTint == null) {
                Box(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth(progress.coerceIn(0f, 1f)).height(2.dp)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}
