package com.playbridge.sender.library

import com.playbridge.sender.downloads.DownloadUtils
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.playbridge.sender.data.debrid.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val REGEX_SO_TYPO = Regex("so(\\d+)")
private val REGEX_EO_TYPO = Regex("eo(\\d+)")
private val REGEX_NUMBER_PAD = Regex("\\d+")

enum class MagnetSheetState {
    ADDING,
    SELECTING_FILES,
    RESOLVING,
    ERROR
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MagnetParsingSheet(
    magnetUri: String? = null,
    torrentBytes: ByteArray? = null,
    provider: DebridProvider,
    onDismiss: () -> Unit,
    onPlayLinks: (List<DebridUnrestrictedLink>) -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var currentState by remember { mutableStateOf(MagnetSheetState.ADDING) }
    var errorMessage by remember { mutableStateOf("") }

    var torrentId by remember { mutableStateOf<String?>(null) }
    var torrentInfo by remember { mutableStateOf<DebridTorrentInfo?>(null) }
    
    // Set of selected file IDs
    var selectedFiles by remember { mutableStateOf<Set<String>>(emptySet()) }
    
    // Resolved Links
    val resolvedLinks = remember { mutableStateListOf<DebridUnrestrictedLink>() }

    // State Machine
    LaunchedEffect(magnetUri, torrentBytes) {
        try {
            currentState = MagnetSheetState.ADDING
            val id = if (torrentBytes != null) {
                provider.addTorrent(torrentBytes)
            } else if (magnetUri != null) {
                provider.addMagnet(magnetUri)
            } else {
                throw IllegalArgumentException("Either magnetUri or torrentBytes must be provided")
            }
            torrentId = id

            // Poll until it's ready for file selection or downloading
            var info: DebridTorrentInfo
            while (true) {
                info = provider.getTorrentInfo(id)
                if (info.status == TorrentStatus.WAITING_FILES_SELECTION || info.status == TorrentStatus.READY || info.status == TorrentStatus.DOWNLOADING) {
                    break
                }
                if (info.status == TorrentStatus.ERROR) {
                    throw DebridException("Torrent encountered an error on Debrid.")
                }
                delay(1500)
            }
            torrentInfo = info
            
            // Pre-select video files
            val videos = info.files.filter { it.isVideo }.map { it.id }.toSet()
            selectedFiles = videos.ifEmpty { info.files.map { it.id }.toSet() }

            if (info.status == TorrentStatus.WAITING_FILES_SELECTION ||
                (info.files.isNotEmpty() && provider.name == "Real-Debrid")) {
                currentState = MagnetSheetState.SELECTING_FILES
            } else {
                // If already downloading or ready and doesn't explicitly need selection UI, just jump to resolving
                currentState = MagnetSheetState.RESOLVING
                startResolvingFlow(
                    provider, 
                    id, 
                    selectedFiles.toList(), 
                    { resolvedLinks.addAll(it); onPlayLinks(resolvedLinks) }, 
                    { errorMessage = it; currentState = MagnetSheetState.ERROR }
                )
            }
        } catch (e: Exception) {
            errorMessage = e.message ?: "Unknown error adding magnet"
            currentState = MagnetSheetState.ERROR
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        ) {
            // Header
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Debrid Magnet Parser",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Provider: ${provider.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))

            when (currentState) {
                MagnetSheetState.ADDING -> {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(if (torrentBytes != null) "Uploading torrent to ${provider.name}..." else "Adding magnet to ${provider.name}...")
                    }
                }
                MagnetSheetState.SELECTING_FILES -> {
                    val info = torrentInfo
                    if (info != null) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                            Text(
                                text = info.filename,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Select files to unlock:",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f, fill = false)
                                    .heightIn(max = 300.dp)
                            ) {
                                items(info.files) { file ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                val current = selectedFiles.toMutableSet()
                                                if (current.contains(file.id)) {
                                                    current.remove(file.id)
                                                } else {
                                                    current.add(file.id)
                                                }
                                                selectedFiles = current
                                            }
                                            .padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = selectedFiles.contains(file.id),
                                            onCheckedChange = null
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column {
                                            Text(
                                                text = file.path.substringAfterLast('/'),
                                                style = MaterialTheme.typography.bodyMedium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = DownloadUtils.formatFileSize(file.bytes),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = onDismiss) {
                                    Text("Cancel")
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        currentState = MagnetSheetState.RESOLVING
                                        val id = torrentId ?: return@Button
                                        scope.launch {
                                            startResolvingFlow(
                                                provider, 
                                                id, 
                                                selectedFiles.toList(), 
                                                { resolvedLinks.addAll(it); onPlayLinks(it); onDismiss() },
                                                { errorMessage = it; currentState = MagnetSheetState.ERROR }
                                            )
                                        }
                                    },
                                    enabled = selectedFiles.isNotEmpty()
                                ) {
                                    Text("Unrestrict Selected")
                                }
                            }
                        }
                    }
                }
                MagnetSheetState.RESOLVING -> {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Unrestricting links on ${provider.name}...",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "This may take a moment. Ensure the magnet is cached.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                MagnetSheetState.ERROR -> {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = "Error",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Failed to Parse Magnet",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = onDismiss) {
                            Text("Close")
                        }
                    }
                }
            }
        }
    }
}

private suspend fun startResolvingFlow(
    provider: DebridProvider,
    torrentId: String,
    selectedFileIds: List<String>,
    onSuccess: (List<DebridUnrestrictedLink>) -> Unit,
    onError: (String) -> Unit
) {
    try {
        provider.selectFiles(torrentId, selectedFileIds)
        
        // Wait for torrent to be Ready
        var attempts = 0
        var info: DebridTorrentInfo
        while (attempts < 20) {
            info = provider.getTorrentInfo(torrentId)
            if (info.status == TorrentStatus.READY) {
                break
            }
            if (info.status == TorrentStatus.ERROR) {
                onError("Torrent error on Debrid service")
                return
            }
            delay(2000)
            attempts++
        }
        
        // Unrestrict the links
        val restrictedLinks = provider.getRestrictedLinks(torrentId)
        if (restrictedLinks.isEmpty()) {
            onError("Debrid provider returned no unrestricted links.")
            return
        }

        val resolved = mutableListOf<DebridUnrestrictedLink>()
        for (link in restrictedLinks) {
            val unrestrict = provider.unrestrictLink(link)
            resolved.add(unrestrict)
        }
        
        if (resolved.isEmpty()) {
            onError("Failed to unrestrict any links.")
        } else {
            // Sort files with a natural alphanumeric key: pad numbers to 6 digits and handle common "so"/"eo" typos.
            onSuccess(resolved.sortedBy { link -> 
                link.filename.lowercase()
                    .replace(REGEX_SO_TYPO) { "s0${it.groupValues[1]}" }
                    .replace(REGEX_EO_TYPO) { "e0${it.groupValues[1]}" }
                    .replace(REGEX_NUMBER_PAD) { it.value.padStart(6, '0') }
            })
        }
    } catch (e: Exception) {
        onError(e.message ?: "Failed during unrestrict flow")
    }
}
