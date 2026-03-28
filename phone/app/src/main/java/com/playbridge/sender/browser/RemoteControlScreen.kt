package com.playbridge.sender.browser

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import com.playbridge.sender.connection.BluetoothClient

/**
 * Full-screen remote control.
 * Hero area for Touchpad/D-Pad with toggle pill, compact action + context rows.
 *
 * @param isMediaPlaying Whether video/media is currently playing on the TV
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteControlScreen(
    isMediaPlaying: Boolean,
    btConnectionState: BluetoothClient.ConnectionState = BluetoothClient.ConnectionState.Disconnected,
    pairedDevices: List<android.bluetooth.BluetoothDevice> = emptyList(),
    savedBluetoothMac: String? = null,
    onBluetoothDeviceSelected: (String) -> Unit,
    onBack: () -> Unit,
    onRemoteKey: (String) -> Unit,
    onMouseMove: (dx: Float, dy: Float) -> Unit,
    onMouseClick: () -> Unit,
    onMouseScroll: (dx: Float, dy: Float) -> Unit,
    onBrowserControl: (String) -> Unit = {},
    onPlayerControl: (String) -> Unit = {}
) {
    // Default to touchpad when no media playing (browser mode), D-Pad when playing (player mode)
    var isTouchpad by remember { mutableStateOf(!isMediaPlaying) }
    var showDeviceListDialog by remember { mutableStateOf(false) }

    if (showDeviceListDialog) {
        // Sort: saved/connected device first, rest alphabetically
        val sortedDevices = remember(pairedDevices, savedBluetoothMac) {
            pairedDevices.sortedWith(compareByDescending { it.address == savedBluetoothMac })
        }

        AlertDialog(
            onDismissRequest = { showDeviceListDialog = false },
            title = { Text("Select TV Bluetooth Device") },
            text = {
                if (sortedDevices.isEmpty()) {
                    Text("No paired devices found. Please pair your TV in Android settings first.")
                } else {
                    androidx.compose.foundation.lazy.LazyColumn {
                        items(sortedDevices.size) { index ->
                            val device = sortedDevices[index]
                            @android.annotation.SuppressLint("MissingPermission")
                            val deviceName = device.name ?: device.address
                            val isSaved = device.address == savedBluetoothMac

                            androidx.compose.material3.ListItem(
                                headlineContent = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(deviceName, fontWeight = if (isSaved) FontWeight.SemiBold else FontWeight.Normal)
                                        if (isSaved) {
                                            Spacer(Modifier.width(6.dp))
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = "Current TV",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                },
                                supportingContent = { Text(device.address) },
                                modifier = Modifier.clickable {
                                    onBluetoothDeviceSelected(device.address)
                                    showDeviceListDialog = false
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDeviceListDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clickable { showDeviceListDialog = true }
                            .padding(4.dp)
                    ) {
                        Text("Remote Control")
                        Spacer(modifier = Modifier.width(8.dp))
                        when (btConnectionState) {
                            is BluetoothClient.ConnectionState.Connected -> {
                                Icon(Icons.Default.BluetoothConnected, contentDescription = "Bluetooth Connected", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            }
                            is BluetoothClient.ConnectionState.Connecting -> {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                            }
                            is BluetoothClient.ConnectionState.Error -> {
                                Icon(Icons.Default.BluetoothDisabled, contentDescription = "Bluetooth Error", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                            }
                            is BluetoothClient.ConnectionState.Disconnected -> {
                                Icon(Icons.Default.Bluetooth, contentDescription = "Bluetooth Disconnected", tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Toggle Pill ──
            TogglePill(
                isTouchpad = isTouchpad,
                onToggle = { isTouchpad = it }
            )

            // ── Hero Area (fills available space) ──
            Box(modifier = Modifier.weight(1f)) {
                if (isTouchpad) {
                    TouchpadArea(
                        onMouseMove = onMouseMove,
                        onMouseClick = onMouseClick,
                        onMouseScroll = onMouseScroll
                    )
                } else {
                    DpadArea(onRemoteKey = onRemoteKey)
                }
            }

            // ── Navigation Row ──
            NavigationRow(onRemoteKey = onRemoteKey)

            // ── Context Controls ──
            if (isMediaPlaying) {
                MediaControlRow(onPlayerControl = onPlayerControl)
            } else {
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
        modifier = Modifier.height(40.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
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
            .fillMaxSize()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(20.dp)
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
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "1 finger: move  •  2 fingers: scroll  •  Tap: click",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
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
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Up
            DpadBtn(Icons.Default.KeyboardArrowUp, "Up") { onRemoteKey("dpad_up") }

            // Left, OK, Right
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DpadBtn(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Left") { onRemoteKey("dpad_left") }

                // OK button (larger, primary color)
                FilledTonalButton(
                    onClick = { onRemoteKey("dpad_center") },
                    modifier = Modifier.size(80.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("OK", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }

                DpadBtn(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Right") { onRemoteKey("dpad_right") }
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
        modifier = Modifier.size(64.dp),
        shape = CircleShape,
        contentPadding = PaddingValues(0.dp)
    ) {
        Icon(icon, contentDescription = desc, modifier = Modifier.size(32.dp))
    }
}

// ─────────────────────────────────────────────
// Navigation Row (Back + Home)
// ─────────────────────────────────────────────

@Composable
private fun NavigationRow(onRemoteKey: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Back
        LabeledIconButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            label = "Back",
            tint = MaterialTheme.colorScheme.onSurface,
            onClick = { onRemoteKey("back") }
        )
        // Home
        LabeledIconButton(
            icon = Icons.Default.Home,
            label = "Home",
            tint = MaterialTheme.colorScheme.onSurface,
            onClick = { onRemoteKey("home") }
        )
    }
}

// ─────────────────────────────────────────────
// Media Control Row (Player mode)
// ─────────────────────────────────────────────

@Composable
private fun MediaControlRow(onPlayerControl: (String) -> Unit) {
    var isPlaying by remember { mutableStateOf(true) } // Assume playing if media started
    var isLooping by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Seek -10s
        LabeledIconButton(
            icon = Icons.Default.Replay10,
            label = "-10s",
            tint = MaterialTheme.colorScheme.onSurface,
            onClick = { onPlayerControl("seek_back") }
        )

        // Play/Pause toggle
        LabeledIconButton(
            icon = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
            label = if (isPlaying) "Pause" else "Play",
            tint = MaterialTheme.colorScheme.primary,
            onClick = {
                onPlayerControl(if (isPlaying) "pause" else "play")
                isPlaying = !isPlaying
            }
        )

        // Seek +10s
        LabeledIconButton(
            icon = Icons.Default.Forward10,
            label = "+10s",
            tint = MaterialTheme.colorScheme.onSurface,
            onClick = { onPlayerControl("seek_forward") }
        )

        // Loop toggle
        LabeledIconButton(
            icon = Icons.Default.Repeat,
            label = "Loop",
            tint = if (isLooping) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            onClick = {
                val next = !isLooping
                onPlayerControl(if (next) "loop_on" else "loop_off")
                isLooping = next
            }
        )

        // Stop
        LabeledIconButton(
            icon = Icons.Default.Stop,
            label = "Stop",
            tint = MaterialTheme.colorScheme.error,
            onClick = { onPlayerControl("stop") }
        )
    }
}

// ─────────────────────────────────────────────
// Browser Context Row
// ─────────────────────────────────────────────

@Composable
private fun BrowserContextRow(onBrowserControl: (String) -> Unit) {
    var isVideoMaximized by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Refresh
        LabeledIconButton(
            icon = Icons.Default.Refresh,
            label = "Refresh",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            onClick = { onBrowserControl("refresh") }
        )
        // Ad Blocker
        LabeledIconButton(
            icon = Icons.Default.Shield,
            label = "Ad Block",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            onClick = { onBrowserControl("toggle_ublock") }
        )
        // Maximize / Restore Video
        LabeledIconButton(
            icon = if (isVideoMaximized) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
            label = if (isVideoMaximized) "Restore" else "Fullscreen",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            onClick = {
                onBrowserControl(if (isVideoMaximized) "restore_video" else "maximize_video")
                isVideoMaximized = !isVideoMaximized
            }
        )
    }
}

// ─────────────────────────────────────────────
// Labeled Icon Button
// ─────────────────────────────────────────────

@Composable
private fun LabeledIconButton(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 4.dp)
    ) {
        IconButton(onClick = onClick) {
            Icon(icon, contentDescription = label, tint = tint)
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = tint.copy(alpha = 0.7f),
            fontSize = 10.sp
        )
    }
}
