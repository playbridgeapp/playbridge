package com.playbridge.sender.browser

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Compact remote control bottom sheet.
 * Hero area for Touchpad/D-Pad with toggle pill, compact action + context rows.
 *
 * @param isMediaPlaying Whether video/media is currently playing on the TV
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteControlSheet(
    isMediaPlaying: Boolean,
    onDismiss: () -> Unit,
    onRemoteKey: (String) -> Unit,
    onMouseMove: (dx: Float, dy: Float) -> Unit,
    onMouseClick: () -> Unit,
    onMouseScroll: (dx: Float, dy: Float) -> Unit,
    onBrowserControl: (String) -> Unit = {},
    onPlayerControl: (String) -> Unit = {}
) {
    // Default to touchpad when no media playing (browser mode), D-Pad when playing (player mode)
    var isTouchpad by remember { mutableStateOf(!isMediaPlaying) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Toggle Pill ──
            TogglePill(
                isTouchpad = isTouchpad,
                onToggle = { isTouchpad = it }
            )

            // ── Hero Area ──
            if (isTouchpad) {
                TouchpadArea(
                    onMouseMove = onMouseMove,
                    onMouseClick = onMouseClick,
                    onMouseScroll = onMouseScroll
                )
            } else {
                DpadArea(onRemoteKey = onRemoteKey)
            }

            // ── Navigation Row (Back) ──
            NavigationRow(onRemoteKey = onRemoteKey)

            // ── Media Controls (only when media is playing) ──
            if (isMediaPlaying) {
                MediaControlRow(onPlayerControl = onPlayerControl)
            }

            // ── Browser Controls (only when no media is playing) ──
            if (!isMediaPlaying) {
                BrowserContextRow(onBrowserControl = onBrowserControl)
            }
        }
    }
}

// ─────────────────────────────────────────────
// Toggle Pill
// ─────────────────────────────────────────────

@Composable
private fun TogglePill(isTouchpad: Boolean, onToggle: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        PillOption(
            label = "Touchpad",
            icon = Icons.Default.TouchApp,
            selected = isTouchpad,
            onClick = { onToggle(true) }
        )
        PillOption(
            label = "D-Pad",
            icon = Icons.Default.Gamepad,
            selected = !isTouchpad,
            onClick = { onToggle(false) }
        )
    }
}

@Composable
private fun PillOption(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    val bg by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary
        else Color.Transparent,
        label = "pillBg"
    )
    val fg by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "pillFg"
    )

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = bg,
        contentColor = fg,
        modifier = Modifier.height(36.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

// ─────────────────────────────────────────────
// Touchpad Area (Hero)
// ─────────────────────────────────────────────

@Composable
private fun TouchpadArea(
    onMouseMove: (dx: Float, dy: Float) -> Unit,
    onMouseClick: () -> Unit,
    onMouseScroll: (dx: Float, dy: Float) -> Unit
) {
    var isScrolling by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(250.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(16.dp)
            )
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val pointerCount = event.changes.count { it.pressed }

                        if (pointerCount >= 2) {
                            isScrolling = true
                            val change = event.changes.firstOrNull { it.pressed }
                            if (change != null && change.previousPressed) {
                                val delta = change.position - change.previousPosition
                                onMouseScroll(delta.x, delta.y * 2f)
                                change.consume()
                            }
                        } else if (pointerCount == 1 && !isScrolling) {
                            val change = event.changes.first()
                            if (change.pressed && change.previousPressed) {
                                val delta = change.position - change.previousPosition
                                onMouseMove(delta.x * 1.5f, delta.y * 1.5f)
                                change.consume()
                            }
                        } else if (pointerCount == 0) {
                            isScrolling = false
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onMouseClick() })
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Default.TouchApp,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "1 finger: move  •  2 fingers: scroll  •  Tap: click",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                textAlign = TextAlign.Center
            )
        }
    }
}

// ─────────────────────────────────────────────
// D-Pad Area (Hero)
// ─────────────────────────────────────────────

@Composable
private fun DpadArea(onRemoteKey: (String) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(250.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Up
            DpadBtn(Icons.Default.KeyboardArrowUp, "Up") { onRemoteKey("dpad_up") }

            // Left, OK, Right
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DpadBtn(Icons.Default.KeyboardArrowLeft, "Left") { onRemoteKey("dpad_left") }

                // OK button (larger, primary color)
                FilledTonalButton(
                    onClick = { onRemoteKey("dpad_center") },
                    modifier = Modifier.size(72.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("OK", style = MaterialTheme.typography.titleMedium)
                }

                DpadBtn(Icons.Default.KeyboardArrowRight, "Right") { onRemoteKey("dpad_right") }
            }

            // Down
            DpadBtn(Icons.Default.KeyboardArrowDown, "Down") { onRemoteKey("dpad_down") }
        }
    }
}

@Composable
private fun DpadBtn(icon: ImageVector, desc: String, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.size(60.dp),
        shape = CircleShape,
        contentPadding = PaddingValues(0.dp)
    ) {
        Icon(icon, contentDescription = desc, modifier = Modifier.size(30.dp))
    }
}

// ─────────────────────────────────────────────
// Navigation Row (Back)
// ─────────────────────────────────────────────

@Composable
private fun NavigationRow(onRemoteKey: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = { onRemoteKey("back") }) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

// ─────────────────────────────────────────────
// Media Control Row (Player mode)
// ─────────────────────────────────────────────

@Composable
private fun MediaControlRow(onPlayerControl: (String) -> Unit) {
    var isPlaying by remember { mutableStateOf(true) } // Assume playing if media started

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Seek -10s
        IconButton(onClick = { onPlayerControl("seek_back") }) {
            Icon(Icons.Default.Replay10, contentDescription = "Rewind 10s",
                tint = MaterialTheme.colorScheme.onSurface)
        }

        // Play/Pause toggle
        IconButton(onClick = {
            onPlayerControl(if (isPlaying) "pause" else "play")
            isPlaying = !isPlaying
        }) {
            Icon(
                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = MaterialTheme.colorScheme.primary
            )
        }

        // Seek +10s
        IconButton(onClick = { onPlayerControl("seek_forward") }) {
            Icon(Icons.Default.Forward10, contentDescription = "Forward 10s",
                tint = MaterialTheme.colorScheme.onSurface)
        }

        // Stop
        IconButton(onClick = { onPlayerControl("stop") }) {
            Icon(Icons.Default.Stop, contentDescription = "Stop",
                tint = MaterialTheme.colorScheme.error)
        }
    }
}

// ─────────────────────────────────────────────
// Browser Context Row
// ─────────────────────────────────────────────

@Composable
private fun BrowserContextRow(onBrowserControl: (String) -> Unit) {
    var isVideoMaximized by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Refresh
        IconButton(onClick = { onBrowserControl("refresh") }) {
            Icon(Icons.Default.Refresh, contentDescription = "Refresh",
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Ad Blocker
        IconButton(onClick = { onBrowserControl("toggle_ublock") }) {
            Icon(Icons.Default.Shield, contentDescription = "Ad Blocker",
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Maximize / Restore Video
        IconButton(onClick = {
            onBrowserControl(if (isVideoMaximized) "restore_video" else "maximize_video")
            isVideoMaximized = !isVideoMaximized
        }) {
            Icon(
                if (isVideoMaximized) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                contentDescription = if (isVideoMaximized) "Restore Video" else "Maximize Video",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
