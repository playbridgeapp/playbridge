package com.playbridge.sender.browser

import androidx.compose.animation.*
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
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import coil.request.ImageRequest
import mozilla.components.browser.state.selector.normalTabs
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.lib.state.ext.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Data class representing only the visual/display properties of a tab.
 * Helps prevent recompositions when non-visual properties of the tab change.
 */
data class TabDisplayState(
    val id: String,
    val title: String,
    val url: String,
    val lowercaseTitle: String = title.lowercase(),
    val lowercaseUrl: String = url.lowercase(),
    val faviconUrl: String = run {
        val isValid = (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true))
                && !url.contains("about:", ignoreCase = true)
        if (isValid) {
            val domain = try {
                android.net.Uri.parse(url).host ?: ""
            } catch (e: Exception) {
                ""
            }
            if (domain.isNotEmpty()) {
                "https://www.google.com/s2/favicons?domain_url=$domain&sz=64"
            } else {
                "https://www.google.com/s2/favicons?domain_url=$url&sz=64"
            }
        } else {
            ""
        }
    }
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
    onTabBookmark: (String) -> Unit,
    // Optional hoisted states from external TopAppBar
    isSearchVisibleExternal: Boolean? = null,
    onSearchVisibleChangeExternal: ((Boolean) -> Unit)? = null,
    isMultiSelectModeExternal: Boolean? = null,
    onMultiSelectModeChangeExternal: ((Boolean) -> Unit)? = null,
    showCloseAllConfirmExternal: Boolean? = null,
    onCloseAllConfirmChangeExternal: ((Boolean) -> Unit)? = null
) {
    // Observe only the tab list structure and selections.
    val tabsScreenState by remember {
        Components.store.flow()
            .map { state ->
                TabsScreenState(
                    tabs = state.normalTabs.map { TabDisplayState(id = it.id, title = it.content.title, url = it.content.url) },
                    selectedTabId = state.selectedTabId
                )
            }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
    }.collectAsState(
        initial = TabsScreenState(
            tabs = Components.store.state.normalTabs.map { TabDisplayState(id = it.id, title = it.content.title, url = it.content.url) },
            selectedTabId = Components.store.state.selectedTabId
        )
    )

    var searchQuery by rememberSaveable { mutableStateOf("") }
    
    var localSearchVisible by rememberSaveable { mutableStateOf(false) }
    val isSearchVisible = isSearchVisibleExternal ?: localSearchVisible
    val onSearchVisibleChange: (Boolean) -> Unit = { visible ->
        if (isSearchVisibleExternal != null) {
            onSearchVisibleChangeExternal?.invoke(visible)
        } else {
            localSearchVisible = visible
        }
        if (!visible) {
            searchQuery = ""
        }
    }
    
    var isMenuExpanded by remember { mutableStateOf(false) }
    
    var localMultiSelectMode by rememberSaveable { mutableStateOf(false) }
    val isMultiSelectMode = isMultiSelectModeExternal ?: localMultiSelectMode
    val onMultiSelectModeChange: (Boolean) -> Unit = { selectMode ->
        if (isMultiSelectModeExternal != null) {
            onMultiSelectModeChangeExternal?.invoke(selectMode)
        } else {
            localMultiSelectMode = selectMode
        }
    }
    
    // High-performance constant-time SnapshotStateMap selection
    val selectedTabIds = remember { mutableStateMapOf<String, Boolean>() }
    
    var localCloseAllConfirm by remember { mutableStateOf(false) }
    val showCloseAllConfirm = showCloseAllConfirmExternal ?: localCloseAllConfirm
    val onCloseAllConfirmChange: (Boolean) -> Unit = { confirm ->
        if (showCloseAllConfirmExternal != null) {
            onCloseAllConfirmChangeExternal?.invoke(confirm)
        } else {
            localCloseAllConfirm = confirm
        }
    }

    LaunchedEffect(isMultiSelectMode) {
        if (!isMultiSelectMode) {
            selectedTabIds.clear()
        }
    }

    val totalTabsCount = tabsScreenState.tabs.size
    val filteredTabs = remember(tabsScreenState.tabs, searchQuery) {
        if (searchQuery.isBlank()) {
            tabsScreenState.tabs
        } else {
            val query = searchQuery.trim().lowercase()
            tabsScreenState.tabs.filter {
                it.lowercaseTitle.contains(query) || it.lowercaseUrl.contains(query)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Actions & Control Bar (Dropdown options menu or Multi-Select Actions)
        if (totalTabsCount > 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!isMultiSelectMode) {
                    Text(
                        text = if (searchQuery.isBlank()) "$totalTabsCount tabs" else "${filteredTabs.size} of $totalTabsCount tabs",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (isSearchVisibleExternal == null) {
                        Box {
                            IconButton(onClick = { isMenuExpanded = true }) {
                                Icon(
                                    Icons.Default.MoreVert,
                                    contentDescription = "More options"
                                )
                            }
                            DropdownMenu(
                                expanded = isMenuExpanded,
                                onDismissRequest = { isMenuExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(if (isSearchVisible) "Hide Search" else "Search") },
                                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                    onClick = {
                                        isMenuExpanded = false
                                        onSearchVisibleChange(!isSearchVisible)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Select Tabs") },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                                    onClick = {
                                        isMenuExpanded = false
                                        onMultiSelectModeChange(true)
                                    }
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Close All Tabs", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { 
                                        Icon(
                                            Icons.Default.Delete, 
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error
                                        ) 
                                    },
                                    onClick = {
                                        isMenuExpanded = false
                                        onCloseAllConfirmChange(true)
                                    }
                                )
                            }
                        }
                    }
                } else {
                    val selectedCount = selectedTabIds.size
                    Text(
                        text = "$selectedCount selected",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val isAllSelected = selectedCount == filteredTabs.size && filteredTabs.isNotEmpty()
                        TextButton(onClick = {
                            if (isAllSelected) {
                                selectedTabIds.clear()
                            } else {
                                selectedTabIds.clear()
                                filteredTabs.forEach { selectedTabIds[it.id] = true }
                            }
                        }) {
                            Text(if (isAllSelected) "Deselect All" else "Select All")
                        }
                        
                        Spacer(modifier = Modifier.width(4.dp))
                        
                        val browserViewModel: BrowserViewModel = koinInject()
                        IconButton(
                            onClick = {
                                selectedTabIds.keys.forEach { id ->
                                    val target = tabsScreenState.tabs.find { it.id == id }
                                    target?.let { tab ->
                                        if (tab.url.isNotEmpty() && tab.url != "about:blank") {
                                            browserViewModel.addBookmark(tab.url, tab.title)
                                        }
                                    }
                                }
                                android.widget.Toast.makeText(Components.applicationContext, "Bookmarked $selectedCount tabs", android.widget.Toast.LENGTH_SHORT).show()
                                onMultiSelectModeChange(false)
                            },
                            enabled = selectedCount > 0
                        ) {
                            Icon(
                                Icons.Default.Bookmark,
                                contentDescription = "Bookmark selected",
                                tint = if (selectedCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            )
                        }
                        
                        IconButton(
                            onClick = {
                                selectedTabIds.keys.forEach { id ->
                                    onTabClosed(id)
                                }
                                onMultiSelectModeChange(false)
                            },
                            enabled = selectedCount > 0
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Close selected",
                                tint = if (selectedCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            )
                        }
                        
                        IconButton(onClick = { onMultiSelectModeChange(false) }) {
                            Icon(Icons.Default.Close, contentDescription = "Exit selection")
                        }
                    }
                }
            }

            // Search text field (shown conditionally below the action row)
            if (isSearchVisible && !isMultiSelectMode) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    placeholder = { Text("Search open tabs...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear search")
                            }
                        } else {
                            IconButton(onClick = { onSearchVisibleChange(false) }) {
                                Icon(Icons.Default.Close, contentDescription = "Close search")
                            }
                        }
                    },
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    ),
                    singleLine = true
                )
            }
        }

        // 3. Main Content List
        if (totalTabsCount == 0) {
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
        } else if (filteredTabs.isEmpty()) {
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
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "No matching tabs",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "No tabs matched your search query \"$searchQuery\".",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    FilledTonalButton(onClick = { searchQuery = "" }) {
                        Text("Clear Search")
                    }
                }
            }
        } else {
            val listState = rememberLazyListState()
            val initialSelectedTabIndex = remember { 
                filteredTabs.indexOfFirst { it.id == tabsScreenState.selectedTabId } 
            }

            LaunchedEffect(Unit) {
                if (initialSelectedTabIndex >= 0) {
                    listState.scrollToItem(initialSelectedTabIndex)
                }
            }

            val playingTabIds = Components.tabManager?.playingTabIds ?: emptyMap<String, Boolean>()
            val scope = rememberCoroutineScope()

            val showScrollToTop by remember {
                derivedStateOf {
                    listState.firstVisibleItemIndex > 2
                }
            }
            val showScrollToBottom by remember {
                derivedStateOf {
                    val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()
                    lastVisibleItem != null && lastVisibleItem.index < filteredTabs.size - 1
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        top = 16.dp,
                        end = 16.dp,
                        bottom = 88.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(
                        items = filteredTabs, 
                        key = { it.id },
                        contentType = { "tab_row" }
                    ) { tab ->
                        val selectHandler = remember(tab.id, onTabSelected) { { onTabSelected(tab.id) } }
                        val closeHandler = remember(tab.id, onTabClosed) { { onTabClosed(tab.id) } }
                        val duplicateHandler = remember(tab.id, onTabDuplicate) { { onTabDuplicate(tab.id) } }
                        val bookmarkHandler = remember(tab.id, onTabBookmark) { { onTabBookmark(tab.id) } }
                        
                        TabRowCard(
                            tab = tab,
                            onSelect = selectHandler,
                            onClose = closeHandler,
                            onDuplicate = duplicateHandler,
                            onBookmark = bookmarkHandler,
                            isSelected = tab.id == tabsScreenState.selectedTabId,
                            isPlaying = playingTabIds[tab.id] == true,
                            isMultiSelectMode = isMultiSelectMode,
                            isChecked = selectedTabIds.containsKey(tab.id),
                            onCheckedChange = { checked ->
                                if (checked) {
                                    selectedTabIds[tab.id] = true
                                } else {
                                    selectedTabIds.remove(tab.id)
                                }
                            }
                        )
                    }
                }

                // Floating Action Buttons for quick scroll all the way up or down
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = 24.dp, end = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    AnimatedVisibility(
                        visible = showScrollToTop,
                        enter = fadeIn() + scaleIn(),
                        exit = fadeOut() + scaleOut()
                    ) {
                        SmallFloatingActionButton(
                            onClick = {
                                scope.launch {
                                    listState.scrollToItem(0)
                                }
                            },
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp),
                            shape = RoundedCornerShape(percent = 50)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowUpward,
                                contentDescription = "Scroll to top",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    AnimatedVisibility(
                        visible = showScrollToBottom,
                        enter = fadeIn() + scaleIn(),
                        exit = fadeOut() + scaleOut()
                    ) {
                        SmallFloatingActionButton(
                            onClick = {
                                scope.launch {
                                    if (filteredTabs.isNotEmpty()) {
                                        listState.scrollToItem(filteredTabs.size - 1)
                                    }
                                }
                            },
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp),
                            shape = RoundedCornerShape(percent = 50)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowDownward,
                                contentDescription = "Scroll to bottom",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCloseAllConfirm) {
        AlertDialog(
            onDismissRequest = { onCloseAllConfirmChange(false) },
            title = { Text("Close all tabs?") },
            text = { Text("Are you sure you want to close all $totalTabsCount open tabs?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onCloseAllConfirmChange(false)
                        tabsScreenState.tabs.forEach { tab ->
                            onTabClosed(tab.id)
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Close All")
                }
            },
            dismissButton = {
                TextButton(onClick = { onCloseAllConfirmChange(false) }) {
                    Text("Cancel")
                }
            }
        )
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
    isPlaying: Boolean,
    isMultiSelectMode: Boolean,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    if (isMultiSelectMode) {
                        onCheckedChange(!isChecked)
                    } else {
                        onSelect()
                    }
                },
                onLongClick = {
                    if (!isMultiSelectMode) {
                        showMenu = true
                    }
                }
            ),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            width = if (isSelected && !isMultiSelectMode) 1.5.dp else 1.dp,
            color = if (isSelected && !isMultiSelectMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected && !isMultiSelectMode)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
            else if (isChecked && isMultiSelectMode)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
            else
                MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isSelected && !isMultiSelectMode) 2.dp else 0.5.dp
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Checkbox or selected indicator line
            if (isMultiSelectMode) {
                Checkbox(
                    checked = isChecked,
                    onCheckedChange = onCheckedChange,
                    modifier = Modifier.padding(end = 4.dp)
                )
            } else if (isSelected) {
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
            val isWebUrl = remember(tab.faviconUrl) { tab.faviconUrl.isNotEmpty() }

            if (isWebUrl) {
                AsyncImage(
                    model = ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                        .data(tab.faviconUrl)
                        .crossfade(true)
                        .size(84) // Downsample large favicons to 84x84px inside background decoder!
                        .build(),
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
                            fontWeight = if (isSelected && !isMultiSelectMode) FontWeight.Bold else FontWeight.Medium
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                        color = if (isSelected && !isMultiSelectMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
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

            // Close button (only shown when not in multi-select mode)
            if (!isMultiSelectMode) {
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
