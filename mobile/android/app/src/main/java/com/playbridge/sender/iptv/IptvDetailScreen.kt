package com.playbridge.sender.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.playbridge.sender.collection.AddToCollectionSheet
import com.playbridge.sender.collection.CollectionsViewModel
import com.playbridge.sender.connection.ConnectionViewModel
import com.playbridge.sender.data.collection.CollectionItemDraft
import com.playbridge.sender.data.collection.CollectionItemKind
import com.playbridge.sender.data.collection.CollectionSource
import com.playbridge.sender.data.iptv.IptvChannelEntity
import com.playbridge.sender.data.iptv.IptvProbeStatus
import com.playbridge.sender.data.iptv.decodeHeaders
import com.playbridge.sender.player.PlayerLauncher
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IptvDetailScreen(
    playlistId: Long,
    viewModel: IptvViewModel,
    connectionViewModel: ConnectionViewModel,
    collectionsViewModel: CollectionsViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var query by remember { mutableStateOf("") }
    var searchActive by remember { mutableStateOf(false) }
    var selectedGroup by remember { mutableStateOf<String?>(null) } // null = All
    // Perf: debounce the DB-side search query so each keystroke doesn't re-query.
    var debouncedQuery by remember { mutableStateOf("") }
    LaunchedEffect(query) {
        kotlinx.coroutines.delay(300)
        debouncedQuery = query
    }
    // Perf: memoize the channel flow — recreating snapshotFlows per recomposition
    // would restart the Room collector on every probe tick/menu toggle.
    val channelsFlow = remember(playlistId) {
        viewModel.channelsFiltered(
            playlistId,
            snapshotFlow { debouncedQuery },
            snapshotFlow { selectedGroup },
        )
    }
    val channels by channelsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val activeFirst by viewModel.activeFirst.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    var addToCollection by remember { mutableStateOf<IptvChannelEntity?>(null) }

    val probeProgress by viewModel.probeProgress.collectAsStateWithLifecycle()
    val updatingId by viewModel.updatingPlaylistId.collectAsStateWithLifecycle()

    val playlist = viewModel.playlistById(playlistId)
    val title = playlist?.name ?: "Channels"
    val updating = updatingId == playlistId
    val probing = probeProgress?.let { it.playlistId == playlistId && it.isRunning } == true

    val groups by viewModel.groupsFor(playlistId).collectAsStateWithLifecycle(initialValue = emptyList())

    // Sorting/filtering already applied by the repository (query + group + active-first).
    val visible = channels

    fun castChannel(channel: IptvChannelEntity) {
        val headers = decodeHeaders(channel.headersJson)
        // Cast to the active TV/DLNA target if one is connected; otherwise play in the
        // built-in player on this device (same fallback as Phone Files).
        val casted = connectionViewModel.castWebStream(
            url = channel.url,
            headers = headers,
            title = channel.name,
        )
        if (casted) {
            scope.launch { snackbar.showSnackbar("Casting ${channel.name}") }
        } else {
            PlayerLauncher.start(
                context = context,
                url = channel.url,
                title = channel.name,
                headers = headers,
            )
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    if (searchActive) {
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("Search channels") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                            ),
                        )
                    } else {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (searchActive) { searchActive = false; query = "" } else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { searchActive = !searchActive; if (!searchActive) query = "" }) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                    IconButton(onClick = { viewModel.refresh(playlistId) }, enabled = !updating) {
                        if (updating) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Update")
                        }
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(if (probing) "Checking channels…" else "Check channels") },
                                leadingIcon = { Icon(Icons.Default.NetworkCheck, contentDescription = null) },
                                enabled = !probing,
                                onClick = { menuOpen = false; viewModel.probe(playlistId) },
                            )
                            DropdownMenuItem(
                                text = { Text("Live channels first") },
                                leadingIcon = {
                                    if (activeFirst) Icon(Icons.Default.Check, contentDescription = null)
                                    else Spacer(Modifier.size(24.dp))
                                },
                                onClick = { menuOpen = false; viewModel.setActiveFirst(!activeFirst) },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            probeProgress?.takeIf { it.playlistId == playlistId && it.total > 0 }?.let { p ->
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(
                        "Checking channels ${p.done}/${p.total}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(2.dp))
                    LinearProgressIndicator(
                        progress = { if (p.total == 0) 0f else p.done.toFloat() / p.total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (groups.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        FilterChip(
                            selected = selectedGroup == null,
                            onClick = { selectedGroup = null },
                            label = { Text("All") },
                        )
                    }
                    items(groups) { group ->
                        FilterChip(
                            selected = selectedGroup == group,
                            onClick = { selectedGroup = group },
                            label = { Text(group) },
                        )
                    }
                }
            }

            if (channels.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (updating) "Loading channels…" else "No channels. Tap Update to fetch.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    // Extra bottom padding so the floating now-playing bar doesn't cover the last row.
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(visible, key = { it.id }) { channel ->
                        ChannelRow(
                            channel = channel,
                            onClick = { castChannel(channel) },
                            onAddToCollection = { addToCollection = channel },
                        )
                    }
                }
            }
        }
    }

    addToCollection?.let { channel ->
        AddToCollectionSheet(
            viewModel = collectionsViewModel,
            draft = CollectionItemDraft(
                title = channel.name,
                url = channel.url,
                kind = CollectionItemKind.WEB,
                mimeType = null,
                headers = decodeHeaders(channel.headersJson),
                logo = channel.logo,
                sourceTag = CollectionSource.IPTV,
            ),
            onDismiss = { addToCollection = null },
            onAdded = { name, added ->
                scope.launch {
                    snackbar.showSnackbar(if (added) "Added to $name" else "Already in $name")
                }
            },
        )
    }
}

@Composable
private fun ChannelRow(
    channel: IptvChannelEntity,
    onClick: () -> Unit,
    onAddToCollection: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.LiveTv,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                channel.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            channel.groupTitle?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        ProbeDot(channel.probeStatus)
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "Options")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Add to Collection") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null) },
                    onClick = { menuOpen = false; onAddToCollection() },
                )
            }
        }
    }
}

@Composable
private fun ProbeDot(status: String) {
    val color = when (status) {
        IptvProbeStatus.ACTIVE -> Color(0xFF4CAF50)
        IptvProbeStatus.DEAD -> Color(0xFFE53935)
        else -> Color(0xFFBDBDBD)
    }
    if (status == IptvProbeStatus.UNKNOWN) return
    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
}
