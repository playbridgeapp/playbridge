package com.playbridge.sender.browser

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import mozilla.components.browser.state.selector.normalTabs
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
    onNewTab: () -> Unit,
    onTabDuplicate: (String) -> Unit,
    onTabBookmark: (String) -> Unit
) {
    // Observe only the tab list structure and selections.
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (tabsScreenState.tabs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                        modifier = Modifier.size(80.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Star,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        "No Open Tabs",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Open a new tab to start browsing.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    FilledTonalButton(onClick = onNewTab) {
                        Text("Create Tab")
                    }
                }
            }
        } else {
            val listState = rememberLazyListState()
            val initialSelectedTabIndex = remember { 
                tabsScreenState.tabs.indexOfFirst { it.id == tabsScreenState.selectedTabId } 
            }

            LaunchedEffect(Unit) {
                if (initialSelectedTabIndex >= 0) {
                    listState.scrollToItem(initialSelectedTabIndex)
                }
            }

            val playingTabIds = Components.tabManager?.playingTabIds ?: emptyMap<String, Boolean>()
            
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(items = tabsScreenState.tabs, key = { it.id }) { tab ->
                    TabRowCard(
                        tab = tab,
                        onSelect = { onTabSelected(tab.id) },
                        onClose = { onTabClosed(tab.id) },
                        onDuplicate = { onTabDuplicate(tab.id) },
                        onBookmark = { onTabBookmark(tab.id) },
                        isSelected = tab.id == tabsScreenState.selectedTabId,
                        isPlaying = playingTabIds[tab.id] == true
                    )
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TabRowCard(
    tab: TabDisplayState,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    onDuplicate: () -> Unit,
    onBookmark: () -> Unit,
    isSelected: Boolean,
    isPlaying: Boolean
) {
    var showMenu by remember { mutableStateOf(false) }
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onSelect,
                onLongClick = { showMenu = true }
            ),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
            else
                MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isSelected) 2.dp else 0.5.dp
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Selected state indicator line on the left margin
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(32.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.primary)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }

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
                        .size(28.dp)
                        .clip(RoundedCornerShape(6.dp)),
                    error = rememberVectorPainter(Icons.Default.Public),
                    placeholder = rememberVectorPainter(Icons.Default.Public),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Public,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            // Tab title and URL info
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = tab.title.ifEmpty { "Untitled" },
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                    
                    if (isPlaying) {
                        Spacer(modifier = Modifier.width(8.dp))
                        
                        val infiniteTransition = rememberInfiniteTransition(label = "audioWaveRow")
                        val pulseScale by infiniteTransition.animateFloat(
                            initialValue = 0.85f,
                            targetValue = 1.15f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(600, easing = LinearEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "pulse"
                        )
                        
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = "Playing audio/video",
                            modifier = Modifier
                                .size(18.dp)
                                .scale(pulseScale),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(2.dp))
                
                Text(
                    text = tab.url,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            Spacer(modifier = Modifier.width(8.dp))

            // Close button
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close tab",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Duplicate Tab") },
                    onClick = {
                        showMenu = false
                        onDuplicate()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Bookmark Tab") },
                    onClick = {
                        showMenu = false
                        onBookmark()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Copy Link") },
                    onClick = {
                        showMenu = false
                        clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(tab.url))
                        android.widget.Toast.makeText(context, "Link copied", android.widget.Toast.LENGTH_SHORT).show()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Share Link") },
                    onClick = {
                        showMenu = false
                        try {
                            val sendIntent = android.content.Intent().apply {
                                action = android.content.Intent.ACTION_SEND
                                putExtra(android.content.Intent.EXTRA_TEXT, tab.url)
                                type = "text/plain"
                            }
                            context.startActivity(android.content.Intent.createChooser(sendIntent, null))
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(context, "Cannot share link", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
        }
    }
}
