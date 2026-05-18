package com.playbridge.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Settings
import android.util.Log
import androidx.compose.ui.graphics.vector.ImageVector
import com.playbridge.player.Screen

/**
 * A persistent sidebar navigation component inspired by Apple TV.
 * Uses icons and full text labels.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AppSidebar(
    currentScreen: Screen,
    onScreenSelected: (Screen) -> Unit,
    modifier: Modifier = Modifier
) {
    // Fixed-width persistent sidebar (Apple TV style: 400px width translates to ~300-400dp on TV)
    Surface(
        modifier = modifier
            .fillMaxHeight()
            .width(240.dp),
        colors = SurfaceDefaults.colors(
            containerColor = Color.Black.copy(alpha = 0.1f)
        ),
        shape = androidx.compose.ui.graphics.RectangleShape
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Branding/Header
            Text(
                text = "PlayBridge",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 32.dp, start = 12.dp)
            )

            SidebarItem(
                screen = Screen.Pairing,
                currentScreen = currentScreen,
                title = "Pairing",
                icon = Icons.Default.Add,
                onSelected = onScreenSelected
            )

            SidebarItem(
                screen = Screen.History,
                currentScreen = currentScreen,
                title = "History",
                icon = Icons.Default.List,
                onSelected = onScreenSelected
            )

            SidebarItem(
                screen = Screen.Favorites,
                currentScreen = currentScreen,
                title = "Favorites",
                icon = Icons.Default.Favorite,
                onSelected = onScreenSelected
            )

            SidebarItem(
                screen = Screen.Settings,
                currentScreen = currentScreen,
                title = "Settings",
                icon = Icons.Default.Settings,
                onSelected = onScreenSelected
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
    onSelected: (Screen) -> Unit
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
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = if (screen == currentScreen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = if (screen == currentScreen) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
