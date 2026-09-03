package com.playbridge.sender.downloads

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.playbridge.sender.data.downloads.DownloadEntity
import com.playbridge.sender.downloads.engine.DownloadRepository
import com.playbridge.sender.downloads.engine.DownloadStatus
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.io.File

/* ------------------------------------------------------------------ *
 *  Status accents — one source of truth for colour + label + icon.   *
 * ------------------------------------------------------------------ */

private val AmberPaused = Color(0xFFF59E0B)
private val GreenDone = Color(0xFF22C55E)

private data class Accent(val color: Color, val label: String, val icon: ImageVector)

@Composable
private fun accentFor(status: String): Accent = when (status) {
    DownloadStatus.RUNNING.name -> Accent(MaterialTheme.colorScheme.primary, "Downloading", Icons.Default.Downloading)
    DownloadStatus.QUEUED.name -> Accent(MaterialTheme.colorScheme.primary, "Queued", Icons.Default.Downloading)
    DownloadStatus.MERGING.name -> Accent(MaterialTheme.colorScheme.tertiary, "Finalizing", Icons.Default.Movie)
    DownloadStatus.PAUSED.name -> Accent(AmberPaused, "Paused", Icons.Default.Pause)
    DownloadStatus.DONE.name -> Accent(GreenDone, "Saved", Icons.Default.CheckCircle)
    DownloadStatus.FAILED.name -> Accent(MaterialTheme.colorScheme.error, "Failed", Icons.Default.ErrorOutline)
    else -> Accent(MaterialTheme.colorScheme.outline, status, Icons.Default.Movie)
}

private fun isActive(status: String) = status == DownloadStatus.RUNNING.name ||
    status == DownloadStatus.QUEUED.name ||
    status == DownloadStatus.PAUSED.name ||
    status == DownloadStatus.MERGING.name

/* ------------------------------------------------------------------ *
 *  Screen                                                            *
 * ------------------------------------------------------------------ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(onBack: () -> Unit) {
    val repository: DownloadRepository = koinInject()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val downloads by repository.observe().collectAsStateWithLifecycle(initialValue = emptyList())

    // Derive per-item download speed from successive Room emissions (worker ticks ~1s).
    // Perf: plain map keyed off the downloads snapshot — recomputed only when Room
    // emits, no state writes inside derivedStateOf.
    val speedTracker = remember { mutableMapOf<String, Pair<Long, Long>>() }
    val speeds = remember(downloads) {
        downloads.associate { e ->
            val prev = speedTracker[e.id]
            val speed = if (e.status == DownloadStatus.RUNNING.name && prev != null && e.updatedAt > prev.second) {
                val dt = (e.updatedAt - prev.second).coerceAtLeast(1L)
                ((e.bytesDownloaded - prev.first) * 1000L / dt).coerceAtLeast(0L)
            } else 0L
            if (e.status == DownloadStatus.RUNNING.name) speedTracker[e.id] = e.bytesDownloaded to e.updatedAt
            else speedTracker.remove(e.id)
            e.id to speed
        }
    }

    var toDelete by remember { mutableStateOf<DownloadEntity?>(null) }
    var errorToShow by remember { mutableStateOf<String?>(null) }

    val active = downloads.filter { isActive(it.status) }
    val finished = downloads.filter { !isActive(it.status) }

    toDelete?.let { item ->
        val running = isActive(item.status)
        AlertDialog(
            onDismissRequest = { toDelete = null },
            icon = { Icon(if (running) Icons.Default.Close else Icons.Default.Delete, null) },
            title = { Text(if (running) "Cancel download?" else "Delete download?") },
            text = { Text("'${item.title}' will be ${if (running) "stopped and removed" else "deleted"}.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { repository.cancel(item.id, removeFiles = true) }
                    toDelete = null
                }) { Text(if (running) "Cancel download" else "Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Keep") } },
        )
    }

    errorToShow?.let { msg ->
        AlertDialog(
            onDismissRequest = { errorToShow = null },
            icon = { Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Download failed") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { errorToShow = null }) { Text("OK") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Downloads", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { padding ->
        if (downloads.isEmpty()) {
            EmptyState(Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { StatHero(active.size, finished, downloads) }

            if (active.isNotEmpty()) {
                item { SectionLabel("Active", active.size) }
                items(active, key = { it.id }) { item ->
                    ActiveCard(
                        item = item,
                        speedBytesPerSec = speeds[item.id] ?: 0L,
                        onPause = { scope.launch { repository.pause(item.id) } },
                        onResume = { scope.launch { repository.resume(item.id) } },
                        onCancel = { toDelete = item },
                    )
                }
            }

            if (finished.isNotEmpty()) {
                item { SectionLabel("Library", finished.count { it.status == DownloadStatus.DONE.name }) }
                items(finished, key = { it.id }) { item ->
                    FinishedRow(
                        item = item,
                        onOpen = { item.filePath?.let { openInExternalPlayer(context, it, item.mimeType) } },
                        onRetry = { scope.launch { repository.resume(item.id) } },
                        onError = { errorToShow = item.errorReason ?: "Unknown error" },
                        onDelete = { toDelete = item },
                    )
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ *
 *  Hero + section header                                             *
 * ------------------------------------------------------------------ */

@Composable
private fun StatHero(activeCount: Int, finished: List<DownloadEntity>, all: List<DownloadEntity>) {
    val savedCount = finished.count { it.status == DownloadStatus.DONE.name }
    val totalBytes = finished.filter { it.status == DownloadStatus.DONE.name }.sumOf { it.bytesDownloaded }
    Surface(
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent,
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.tertiary,
                        ),
                    ),
                )
                .padding(20.dp),
        ) {
            Column {
                Text(
                    if (activeCount > 0) "$activeCount download${if (activeCount == 1) "" else "s"} in progress"
                    else "All caught up",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HeroChip("$savedCount saved")
                    HeroChip(formatSize(totalBytes))
                }
            }
        }
    }
}

@Composable
private fun HeroChip(text: String) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.20f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SectionLabel(text: String, count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp, start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(8.dp))
        Text("$count", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }
}

/* ------------------------------------------------------------------ *
 *  Active card                                                       *
 * ------------------------------------------------------------------ */

@Composable
private fun ActiveCard(
    item: DownloadEntity,
    speedBytesPerSec: Long,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
) {
    val accent = accentFor(item.status)
    val indeterminate = item.status == DownloadStatus.MERGING.name ||
        item.status == DownloadStatus.QUEUED.name ||
        item.totalBytes <= 0
    // Perf: raw fraction — the 1s Room tick restarted a 450ms tween continuously.
    val fraction = if (item.totalBytes > 0) (item.bytesDownloaded.toFloat() / item.totalBytes).coerceIn(0f, 1f) else 0f
    val barColor = accent.color

    Surface(
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(accent.icon, accent.color)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                    Text(accent.label, color = accent.color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
                if (item.status == DownloadStatus.PAUSED.name) {
                    RoundAction(Icons.Default.PlayArrow, "Resume", accent.color, onResume)
                } else if (item.status == DownloadStatus.RUNNING.name) {
                    RoundAction(Icons.Default.Pause, "Pause", MaterialTheme.colorScheme.onSurfaceVariant, onPause)
                }
                RoundAction(Icons.Default.Close, "Cancel", MaterialTheme.colorScheme.onSurfaceVariant, onCancel)
            }

            Spacer(Modifier.height(14.dp))
            GradientBar(fraction = fraction, color = barColor, indeterminate = indeterminate)
            Spacer(Modifier.height(8.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                val left = if (item.totalBytes > 0)
                    "${formatSize(item.bytesDownloaded)} / ${formatSize(item.totalBytes)}"
                else formatSize(item.bytesDownloaded)
                Text(left, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val right = buildString {
                    if (speedBytesPerSec > 0) append("${formatSize(speedBytesPerSec)}/s")
                    if (!indeterminate) {
                        if (isNotEmpty()) append("  ·  ")
                        append("${(fraction * 100).toInt()}%")
                    }
                }
                if (right.isNotEmpty()) {
                    Text(right, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = accent.color)
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ *
 *  Finished / failed row                                             *
 * ------------------------------------------------------------------ */

@Composable
private fun FinishedRow(
    item: DownloadEntity,
    onOpen: () -> Unit,
    onRetry: () -> Unit,
    onError: () -> Unit,
    onDelete: () -> Unit,
) {
    val accent = accentFor(item.status)
    val failed = item.status == DownloadStatus.FAILED.name
    Surface(
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(accent.icon, accent.color)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                Text(
                    if (failed) "Tap to see why" else formatSize(item.bytesDownloaded),
                    fontSize = 12.sp,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (failed) {
                RoundAction(Icons.Default.ErrorOutline, "Error", MaterialTheme.colorScheme.error, onError)
                RoundAction(Icons.Default.Refresh, "Retry", MaterialTheme.colorScheme.primary, onRetry)
            } else {
                RoundAction(Icons.Default.OpenInNew, "Open", accent.color, onOpen)
            }
            RoundAction(Icons.Default.Delete, "Delete", MaterialTheme.colorScheme.onSurfaceVariant, onDelete)
        }
    }
}

/* ------------------------------------------------------------------ *
 *  Reusable bits                                                     *
 * ------------------------------------------------------------------ */

@Composable
private fun IconTile(icon: ImageVector, color: Color) {
    Box(
        Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = color) }
}

@Composable
private fun RoundAction(icon: ImageVector, desc: String, tint: Color, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, desc, tint = tint) }
}

/** Custom rounded-cap progress with a soft gradient fill (or a pulsing track when indeterminate). */
@Composable
private fun GradientBar(fraction: Float, color: Color, indeterminate: Boolean) {
    // Perf: entrance animates once; progress ticks drive the raw fraction directly.
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val entrance by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        label = "barEntrance",
    )
    Box(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.16f)),
    ) {
        if (indeterminate) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxSize().clip(CircleShape),
                color = color,
                trackColor = Color.Transparent,
            )
        } else {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction * entrance)
                    .clip(CircleShape)
                    .background(Brush.horizontalGradient(listOf(color, color.copy(alpha = 0.65f)))),
            )
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(96.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Downloading, null,
                    modifier = Modifier.size(44.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(20.dp))
            Text("No downloads yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Videos you save from the browser show up here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/* ------------------------------------------------------------------ *
 *  Helpers                                                            *
 * ------------------------------------------------------------------ */

/** Opens a finished download (content:// or file://) in an external player via chooser. */
fun openInExternalPlayer(context: Context, stored: String, mediaType: String?) {
    try {
        val parsed = stored.toUri()
        val mime = mediaType?.takeIf { it.isNotBlank() } ?: "video/*"
        val contentUri = if (parsed.scheme == "content") {
            parsed
        } else {
            val file = File(parsed.path ?: return)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Open with"))
    } catch (e: Exception) {
        Toast.makeText(context, "No player found for this file", Toast.LENGTH_SHORT).show()
    }
}

private fun formatSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val group = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
    val fmt = if (group >= 3) "%.2f %s" else "%.1f %s"
    return String.format(java.util.Locale.US, fmt, size / Math.pow(1024.0, group.toDouble()), units[group])
}
