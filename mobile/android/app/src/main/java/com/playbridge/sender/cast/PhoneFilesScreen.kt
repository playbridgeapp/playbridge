package com.playbridge.sender.cast

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.playbridge.sender.connection.ConnectionViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phone Files: in-app Videos/Audio tabs listing on-device media (MediaStore) with
 * thumbnails, each castable to the active target (DLNA renderer or native receiver).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneFilesScreen(
    viewModel: ConnectionViewModel,
    onBack: () -> Unit,
    onOpenAllDevices: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var showDevicePicker by remember { mutableStateOf(false) }

    val requiredPerms = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
    fun hasAnyPerm() = requiredPerms.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    var granted by remember { mutableStateOf(hasAnyPerm()) }
    var allItems by remember { mutableStateOf<List<PhoneMediaItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) } // 0 = Videos, 1 = Audio

    val videos = remember(allItems) { allItems.filter { !it.isAudio } }
    val audio = remember(allItems) { allItems.filter { it.isAudio } }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted = it.values.any { v -> v } || hasAnyPerm() }

    LaunchedEffect(Unit) { if (!granted) permLauncher.launch(requiredPerms) }
    LaunchedEffect(granted) {
        if (granted) {
            loading = true
            allItems = PhoneMediaStore.query(context)
            loading = false
        }
    }

    fun cast(media: PhoneMediaItem) {
        val ok = viewModel.castLocalFile(media.uri.toString(), media.mimeType, media.title, media.durationMs)
        if (ok) {
            scope.launch { snackbar.showSnackbar("Casting ${media.title}") }
        } else {
            // No active target — open the device picker instead of just complaining.
            showDevicePicker = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Phone Files") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Same cast-target chip as the Library / Cast sheet — shows the active
                    // device (native or DLNA) and opens the device picker on tap.
                    DeviceChip(
                        onOpenAllDevices = onOpenAllDevices,
                        showThisDevice = false,
                        castStatusLabel = true,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        // (The remote FAB lived here; replaced by the app-wide NowPlayingBar.)
    ) { pad ->
        Box(modifier = Modifier.padding(pad).fillMaxSize()) {
            when {
                !granted -> Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        "Permission needed to list your videos and audio.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { permLauncher.launch(requiredPerms) }) { Text("Grant permission") }
                }

                loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

                else -> Column(modifier = Modifier.fillMaxSize()) {
                    TabRow(selectedTabIndex = tab) {
                        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Videos (${videos.size})") })
                        Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Audio (${audio.size})") })
                    }
                    val shown = if (tab == 0) videos else audio
                    if (shown.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                if (tab == 0) "No videos found." else "No audio found.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            // Keep the last row clear of the system navigation bar and the
                            // overlaid NowPlayingBar.
                            contentPadding = PaddingValues(
                                bottom = WindowInsets.navigationBars.asPaddingValues()
                                    .calculateBottomPadding() + 84.dp
                            ),
                        ) {
                            items(shown) { media -> PhoneMediaRow(media) { cast(media) } }
                        }
                    }
                }
            }
        }
    }

    // Opened when the user taps a file with no active cast target.
    if (showDevicePicker) {
        DeviceConnectionSheet(
            onDismiss = { showDevicePicker = false },
            onOpenAllDevices = {
                showDevicePicker = false
                onOpenAllDevices()
            },
            showThisDevice = false,
        )
    }
}

@Composable
private fun PhoneMediaRow(media: PhoneMediaItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MediaThumbnail(media)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                media.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (media.durationMs > 0) {
                Text(
                    clockShort(media.durationMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Thumbnail (video frame / album art) loaded lazily via ContentResolver; icon fallback. */
@Composable
private fun MediaThumbnail(media: PhoneMediaItem) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, media.uri) {
        value = withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching { context.contentResolver.loadThumbnail(media.uri, Size(128, 128), null) }.getOrNull()
            } else {
                null
            }
        }
    }
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = if (media.isAudio) Icons.Default.Audiotrack else Icons.Default.Movie,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

private fun clockShort(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
