package com.playbridge.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import android.util.Log
import androidx.compose.ui.graphics.vector.ImageVector
import com.playbridge.player.Screen

/**
 * Compact TV navigation rail. It expands only while focus is inside it, preserving screen
 * space and making the left/right D-pad boundary unambiguous.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AppSidebar(
    currentScreen: Screen,
    onScreenSelected: (Screen) -> Unit,
    modifier: Modifier = Modifier
) {
    var hasRailFocus by remember { mutableStateOf(false) }
    val railWidth by animateDpAsState(if (hasRailFocus) 232.dp else 84.dp, label = "navigationRailWidth")
    // Do not compose labels until the rail is effectively expanded. Showing them as soon as
    // focus enters makes Text reflow through several lines while the width animation runs.
    val showLabels = hasRailFocus && railWidth >= 220.dp
    Surface(
        modifier = modifier
            .fillMaxHeight()
            .width(railWidth)
            .focusGroup()
            .onFocusChanged { hasRailFocus = it.hasFocus },
        colors = SurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)
        ),
        shape = androidx.compose.ui.graphics.RectangleShape
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Branding/Header
            Text(
                text = if (showLabels) "PlayBridge" else "PB",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 28.dp, start = 10.dp)
            )

            SidebarItem(
                screen = Screen.Library,
                currentScreen = currentScreen,
                title = "Library",
                icon = Icons.AutoMirrored.Filled.List,
                onSelected = onScreenSelected,
                expanded = showLabels,
            )

            SidebarItem(
                screen = Screen.Pairing,
                currentScreen = currentScreen,
                title = "Connect",
                icon = Icons.Default.Add,
                onSelected = onScreenSelected,
                expanded = showLabels,
            )

            SidebarItem(
                screen = Screen.Settings,
                currentScreen = currentScreen,
                title = "Settings",
                icon = Icons.Default.Settings,
                onSelected = onScreenSelected,
                expanded = showLabels,
            )

        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SidebarItem(
    screen: Screen,
    currentScreen: Screen,
    title: String,
    icon: ImageVector,
    onSelected: (Screen) -> Unit,
    expanded: Boolean,
) {
    Surface(
        selected = screen == currentScreen,
        onClick = { 
            Log.d("Sidebar", "Screen selected: $screen")
            onSelected(screen) 
        },
        scale = SelectableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = SelectableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
            focusedSelectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
        ),
        shape = SelectableSurfaceDefaults.shape(MaterialTheme.shapes.medium),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = if (screen == currentScreen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            if (expanded) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (screen == currentScreen) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}
