package com.playbridge.sender.browser

import androidx.compose.foundation.clickable
import coil.compose.AsyncImage
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import mozilla.components.browser.state.selector.normalTabs
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.lib.state.ext.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Data class representing only the visual/display properties of a tab.
 * Helps prevent recompositions when non-visual properties of the tab change.
 */
data class TabDisplayState(
    val id: String,
    val title: String,
    val url: String
)

/**
 * Minimal state required by TabsScreen.
 */
data class TabsScreenState(
    val tabs: List<TabDisplayState>,
    val selectedTabId: String?
)

/**
 * Extension to observe BrowserStore state as Compose State.
 */
@Composable
fun BrowserStore.observeAsState(): State<BrowserState> {
    return this.flow().collectAsState(initial = this.state)
}

/**
 * Tab management screen showing open tabs from BrowserStore.
 */
@Composable
fun TabsScreen(
    onTabSelected: (String) -> Unit,
    onTabClosed: (String) -> Unit,
    onNewTab: () -> Unit
) {
    // Observe only the tab list structure and selections.
    // This ignores high-frequency updates like loading progress (40% -> 41% etc.),
    // scroll position updates, and background page changes, eliminating lag.
    val tabsScreenState by remember {
        Components.store.flow()
            .map { state ->
                TabsScreenState(
                    tabs = state.normalTabs.map { TabDisplayState(it.id, it.content.title, it.content.url) },
                    selectedTabId = state.selectedTabId
                )
            }
            .distinctUntilChanged()
    }.collectAsState(
        initial = TabsScreenState(
            tabs = Components.store.state.normalTabs.map { TabDisplayState(it.id, it.content.title, it.content.url) },
            selectedTabId = Components.store.state.selectedTabId
        )
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${tabsScreenState.tabs.size} Open Tabs",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            
            FilledTonalButton(
                onClick = onNewTab
            ) {
                Icon(Icons.Default.Add, "New Tab", modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("New Tab")
            }

            Spacer(Modifier.width(4.dp))

            var menuExpanded by remember { mutableStateOf(false) }
            val playingTabIds = Components.tabManager?.playingTabIds ?: emptyMap<String, Boolean>()
            
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, "More options")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Navigate to playing tab") },
                        onClick = {
                            menuExpanded = false
                            playingTabIds.keys.firstOrNull()?.let { onTabSelected(it) }
                        },
                        leadingIcon = { Icon(Icons.Default.VolumeUp, null) },
                        enabled = playingTabIds.isNotEmpty()
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Scroll to the active tab only on initial load
        val listState = rememberLazyListState()
        val initialSelectedTabIndex = remember { 
            tabsScreenState.tabs.indexOfFirst { it.id == tabsScreenState.selectedTabId } 
        }

        LaunchedEffect(Unit) {
            if (initialSelectedTabIndex >= 0) {
                listState.scrollToItem(initialSelectedTabIndex)
            }
        }

        if (tabsScreenState.tabs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "No open tabs",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            val playingTabIds = Components.tabManager?.playingTabIds ?: emptyMap<String, Boolean>()
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items = tabsScreenState.tabs, key = { it.id }) { tab ->
                    TabCard(
                        tab = tab,
                        onSelect = { onTabSelected(tab.id) },
                        onClose = { onTabClosed(tab.id) },
                        isSelected = tab.id == tabsScreenState.selectedTabId,
                        isPlaying = playingTabIds[tab.id] == true
                    )
                }
            }
        }
    }
}

@Composable
private fun TabCard(
    tab: TabDisplayState,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    isSelected: Boolean,
    isPlaying: Boolean = false
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Tab icon (Favicon)
            val url = tab.url
            val isValidWebUrl = remember(url) {
                (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true))
                        && !url.contains("about:", ignoreCase = true)
            }

            if (isValidWebUrl) {
                val faviconUrl = "https://www.google.com/s2/favicons?domain_url=$url&sz=64"
                AsyncImage(
                    model = faviconUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(24.dp)
                        .clip(MaterialTheme.shapes.extraSmall),
                    error = rememberVectorPainter(Icons.Default.Public),
                    placeholder = rememberVectorPainter(Icons.Default.Public),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Public,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            // Tab info
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = tab.title.ifEmpty { "Untitled" },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isPlaying) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.VolumeUp,
                            contentDescription = "Playing audio/video",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Text(
                    text = tab.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            // Close button
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close tab",
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

