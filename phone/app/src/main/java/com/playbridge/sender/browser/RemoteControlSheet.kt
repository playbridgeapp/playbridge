package com.playbridge.sender.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * TV active mode - determines which controls to show
 */
enum class TvMode {
    Player,
    Browser,
    Unknown
}

/**
 * Bottom sheet with remote controls for TV
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteControlSheet(
    tvMode: TvMode,
    onDismiss: () -> Unit,
    onRemoteKey: (String) -> Unit,
    onMouseMove: (dx: Float, dy: Float) -> Unit,
    onMouseClick: () -> Unit,
    onMouseScroll: (dx: Float, dy: Float) -> Unit,
    onBrowserControl: (String) -> Unit = {}
) {
    // Allow user to override auto-detected mode
    var selectedTab by remember { mutableIntStateOf(if (tvMode == TvMode.Browser) 1 else 0) }
    
    // Update tab when tvMode changes (auto-detection)
    LaunchedEffect(tvMode) {
        if (tvMode != TvMode.Unknown) {
            selectedTab = if (tvMode == TvMode.Browser) 1 else 0
        }
    }
    
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Title
            Text(
                text = "Remote Control",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            
            // Tab selector
            TabRow(
                selectedTabIndex = selectedTab,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp)),
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("D-Pad") },
                    icon = { Icon(Icons.Default.Gamepad, contentDescription = null) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Touchpad") },
                    icon = { Icon(Icons.Default.TouchApp, contentDescription = null) }
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Content based on selected tab
            when (selectedTab) {
                0 -> DpadControls(onRemoteKey = onRemoteKey)
                1 -> TouchpadControls(
                    onMouseMove = onMouseMove,
                    onMouseClick = onMouseClick,
                    onMouseScroll = onMouseScroll
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Common controls (Back button)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                FilledTonalButton(
                    onClick = { onRemoteKey("back") },
                    modifier = Modifier.width(120.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Back")
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Browser control buttons (Refresh, Ad Blocker toggle)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
            ) {
                OutlinedButton(
                    onClick = { onBrowserControl("refresh") },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Refresh")
                }
                
                OutlinedButton(
                    onClick = { onBrowserControl("toggle_ublock") },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Shield, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Ad Blocker")
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun DpadControls(onRemoteKey: (String) -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Up
        DpadButton(
            icon = Icons.Default.KeyboardArrowUp,
            contentDescription = "Up",
            onClick = { onRemoteKey("dpad_up") }
        )
        
        // Left, Center, Right
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DpadButton(
                icon = Icons.Default.KeyboardArrowLeft,
                contentDescription = "Left",
                onClick = { onRemoteKey("dpad_left") }
            )
            
            // Center/OK button - larger
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
            
            DpadButton(
                icon = Icons.Default.KeyboardArrowRight,
                contentDescription = "Right",
                onClick = { onRemoteKey("dpad_right") }
            )
        }
        
        // Down
        DpadButton(
            icon = Icons.Default.KeyboardArrowDown,
            contentDescription = "Down",
            onClick = { onRemoteKey("dpad_down") }
        )
    }
}

@Composable
private fun DpadButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.size(64.dp),
        shape = CircleShape,
        contentPadding = PaddingValues(0.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(32.dp)
        )
    }
}

@Composable
private fun TouchpadControls(
    onMouseMove: (dx: Float, dy: Float) -> Unit,
    onMouseClick: () -> Unit,
    onMouseScroll: (dx: Float, dy: Float) -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Touchpad area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        // Send mouse movement with sensitivity scaling
                        onMouseMove(dragAmount.x * 1.5f, dragAmount.y * 1.5f)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onMouseClick() }
                    )
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
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Drag to move cursor\nTap to click",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
            }
        }
        
        // Click button (alternative to tap)
        FilledTonalButton(
            onClick = onMouseClick,
            modifier = Modifier.fillMaxWidth(0.6f)
        ) {
            Icon(Icons.Default.TouchApp, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Click")
        }
    }
}
