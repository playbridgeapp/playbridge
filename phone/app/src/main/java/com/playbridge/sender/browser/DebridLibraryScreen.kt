package com.playbridge.sender.browser

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.playbridge.sender.data.debrid.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebridLibraryScreen(
    onBack: () -> Unit,
    onCopyUrl: (String) -> Unit,
    onPlayOnTv: (String, String) -> Unit, // url, title
    onPlayPlaylistOnTv: (List<com.playbridge.protocol.PlayPayload>) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { DebridRepository(context) }
    
    var torrents by remember { mutableStateOf<List<DebridTorrentInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    var selectedTorrent by remember { mutableStateOf<DebridTorrentInfo?>(null) }
    var torrentDetails by remember { mutableStateOf<DebridTorrentInfo?>(null) }
    var isLoadingDetails by remember { mutableStateOf(false) }
    var unrestrictError by remember { mutableStateOf<String?>(null) }
    
    var showBottomSheet by remember { mutableStateOf(false) }

    fun loadTorrents() {
        scope.launch {
            isLoading = true
            errorMessage = null
            try {
                val provider = repository.getActiveProvider()
                if (provider == null) {
                    errorMessage = "No Debrid provider configured."
                } else {
                    torrents = provider.getTorrents()
                }
            } catch (e: Exception) {
                errorMessage = "Failed to load torrents: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        loadTorrents()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Debrid Library") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { loadTorrents() }) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (errorMessage != null) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(errorMessage!!, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { loadTorrents() }) {
                        Text("Retry")
                    }
                }
            } else if (torrents.isEmpty()) {
                Text("No recent torrents found.", modifier = Modifier.align(Alignment.Center))
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(torrents) { torrent ->
                        ListItem(
                            headlineContent = { Text(torrent.filename, maxLines = 1) },
                            supportingContent = {
                                Text(
                                    "Status: ${torrent.status.name} - Progress: %.1f%%".format(torrent.progress),
                                    maxLines = 1
                                )
                            },
                            leadingContent = {
                                val icon = when (torrent.status) {
                                    TorrentStatus.READY -> Icons.Default.CheckCircle
                                    TorrentStatus.DOWNLOADING -> Icons.Default.Download
                                    TorrentStatus.ERROR -> Icons.Default.Error
                                    else -> Icons.Default.HourglassEmpty
                                }
                                Icon(icon, contentDescription = "Status: ${torrent.status.name}", tint = if (torrent.status == TorrentStatus.READY) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            },
                            modifier = Modifier.clickable {
                                if (torrent.status == TorrentStatus.READY) {
                                    selectedTorrent = torrent
                                    showBottomSheet = true
                                    scope.launch {
                                        isLoadingDetails = true
                                        unrestrictError = null
                                        try {
                                            val provider = repository.getActiveProvider()
                                            if (provider != null) {
                                                torrentDetails = provider.getTorrentInfo(torrent.id)
                                            }
                                        } catch (e: Exception) {
                                            unrestrictError = e.message
                                        } finally {
                                            isLoadingDetails = false
                                        }
                                    }
                                } else {
                                    Toast.makeText(context, "Torrent is not ready yet.", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (showBottomSheet && selectedTorrent != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { 
                showBottomSheet = false 
                selectedTorrent = null
                torrentDetails = null
            },
            sheetState = sheetState
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(selectedTorrent!!.filename, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                Spacer(Modifier.height(8.dp))
                
                if (isLoadingDetails) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally).padding(16.dp))
                } else if (unrestrictError != null) {
                    Text("Error loading details: $unrestrictError", color = MaterialTheme.colorScheme.error)
                } else if (torrentDetails != null) {
                    val files = torrentDetails!!.files.filter { it.isVideo || it.selected == 1 }
                    if (files.isEmpty()) {
                        Text("No playable files found.", modifier = Modifier.padding(vertical = 16.dp))
                    } else {
                        if (files.size > 1) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        try {
                                            Toast.makeText(context, "Resolving all links, please wait...", Toast.LENGTH_SHORT).show()
                                            val provider = repository.getActiveProvider() ?: return@launch
                                            val configName = repository.getConfiguredProviderName()
                                            
                                            val playlistItems = mutableListOf<com.playbridge.protocol.PlayPayload>()
                                            
                                            for (file in files) {
                                                val linkToUnrestrict = when (configName) {
                                                    DebridRepository.PROVIDER_PREMIUMIZE -> file.id
                                                    else -> {
                                                        if (file.link.isNotEmpty()) file.link else file.id
                                                    }
                                                }
                                                val unrestrictResponse = provider.unrestrictLink(linkToUnrestrict)
                                                playlistItems.add(
                                                    com.playbridge.protocol.PlayPayload(
                                                        url = unrestrictResponse.downloadUrl,
                                                        title = file.path
                                                    )
                                                )
                                            }
                                            
                                            onPlayPlaylistOnTv(playlistItems)
                                            showBottomSheet = false
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                            ) {
                                Icon(Icons.Default.List, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Play All on TV (${files.size} items)")
                            }
                        }
                        
                        LazyColumn(modifier = Modifier.fillMaxWidth()) {
                            items(files) { file ->
                                ListItem(
                                    headlineContent = { Text(file.path, maxLines = 2, style = MaterialTheme.typography.bodyMedium) },
                                    supportingContent = { Text("${file.bytes / (1024 * 1024)} MB", style = MaterialTheme.typography.bodySmall) },
                                    leadingContent = { Icon(Icons.Default.Movie, contentDescription = null) },
                                    trailingContent = {
                                        Row {
                                            IconButton(onClick = {
                                                scope.launch {
                                                    try {
                                                        Toast.makeText(context, "Resolving link...", Toast.LENGTH_SHORT).show()
                                                        val provider = repository.getActiveProvider() ?: return@launch
                                                        val configName = repository.getConfiguredProviderName()
                                                        
                                                        val linkToUnrestrict = when (configName) {
                                                            DebridRepository.PROVIDER_PREMIUMIZE -> file.id
                                                            else -> {
                                                                if (file.link.isNotEmpty()) file.link else file.id
                                                            }
                                                        }

                                                        val unrestrictResponse = provider.unrestrictLink(linkToUnrestrict)
                                                        onCopyUrl(unrestrictResponse.downloadUrl)
                                                        showBottomSheet = false
                                                    } catch (e: Exception) {
                                                        Toast.makeText(context, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
                                                    }
                                                }
                                            }) {
                                                Icon(Icons.Default.ContentCopy, "Copy Link", tint = MaterialTheme.colorScheme.primary)
                                            }
                                            IconButton(onClick = {
                                                scope.launch {
                                                    try {
                                                        Toast.makeText(context, "Resolving link...", Toast.LENGTH_SHORT).show()
                                                        val provider = repository.getActiveProvider() ?: return@launch
                                                        val configName = repository.getConfiguredProviderName()
                                                        
                                                        val linkToUnrestrict = when (configName) {
                                                            DebridRepository.PROVIDER_PREMIUMIZE -> file.id
                                                            else -> {
                                                                if (file.link.isNotEmpty()) file.link else file.id
                                                            }
                                                        }

                                                        val unrestrictResponse = provider.unrestrictLink(linkToUnrestrict)
                                                        onPlayOnTv(unrestrictResponse.downloadUrl, file.path)
                                                        showBottomSheet = false
                                                    } catch (e: Exception) {
                                                        Toast.makeText(context, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
                                                    }
                                                }
                                            }) {
                                                Icon(Icons.Default.Tv, "Play on TV", tint = MaterialTheme.colorScheme.primary)
                                            }
                                        }
                                    }
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
