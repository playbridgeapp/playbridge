package com.playbridge.sender.diagnostics

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

private enum class LogTab { PHONE, TV }

/**
 * In-app log viewer. The Phone tab tails this app's own logcat (which already captures every
 * `android.util.Log` call and the shared logger); the TV tab pulls the player's persisted log
 * file over the existing `GET /logs` HTTP endpoint.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    onBack: () -> Unit,
    tvIp: String? = null,
    tvPort: Int? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var tab by remember { mutableStateOf(LogTab.PHONE) }
    var query by remember { mutableStateOf("") }
    var minLevel by remember { mutableStateOf(LogcatReader.Level.VERBOSE) }
    var autoScroll by remember { mutableStateOf(true) }
    // Include framework/OEM noise (frame-rate hints, surface churn, etc.). Off by default.
    var includeSystem by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    // When non-null, the Log Detail view is shown for this entry instead of the list.
    var detail by remember { mutableStateOf<LogcatReader.LogLine?>(null) }

    // Phone log state
    var phoneLines by remember { mutableStateOf<List<LogcatReader.LogLine>>(emptyList()) }

    // TV log state. Stored as a capped list of lines (split off the main thread) so the viewer
    // never lays out one multi-megabyte Text node, which would freeze the UI.
    var tvLines by remember { mutableStateOf<List<String>?>(null) }
    var tvLoading by remember { mutableStateOf(false) }
    val tvAvailable = tvIp != null && tvPort != null

    // Poll the phone logcat while the Phone tab is visible.
    LaunchedEffect(tab, includeSystem) {
        if (tab == LogTab.PHONE) {
            while (true) {
                phoneLines = LogcatReader.snapshot(includeNoise = includeSystem)
                delay(1500)
            }
        }
    }

    val levelRank = remember { LogcatReader.Level.entries.associateWith { it.ordinal } }
    val filteredPhone = remember(phoneLines, query, minLevel) {
        phoneLines.filter { line ->
            (line.level == LogcatReader.Level.UNKNOWN ||
                (levelRank[line.level] ?: 0) >= (levelRank[minLevel] ?: 0)) &&
                (query.isBlank() ||
                    line.message.contains(query, true) ||
                    line.tag.contains(query, true))
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(filteredPhone.size, autoScroll, tab) {
        if (tab == LogTab.PHONE && autoScroll && filteredPhone.isNotEmpty()) {
            listState.scrollToItem(filteredPhone.lastIndex)
        }
    }

    // Back from the detail view returns to the list rather than leaving the Logs screen.
    BackHandler(enabled = detail != null) { detail = null }
    detail?.let { entry ->
        LogDetailScreen(entry = entry, onBack = { detail = null })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Logs") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Refresh") },
                            leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                scope.launch {
                                    if (tab == LogTab.PHONE) {
                                        phoneLines = LogcatReader.snapshot(includeNoise = includeSystem)
                                    } else if (tvAvailable) {
                                        tvLoading = true
                                        tvLines = fetchTvLogs(tvIp!!, tvPort!!)
                                        tvLoading = false
                                    }
                                }
                            }
                        )
                        if (tab == LogTab.PHONE) {
                            DropdownMenuItem(
                                text = { Text("Auto-scroll") },
                                leadingIcon = { Icon(Icons.Default.VerticalAlignBottom, contentDescription = null) },
                                trailingIcon = {
                                    if (autoScroll) Icon(Icons.Default.Check, contentDescription = "On")
                                },
                                onClick = { autoScroll = !autoScroll; menuOpen = false }
                            )
                            DropdownMenuItem(
                                text = { Text("Show system logs") },
                                leadingIcon = { Icon(Icons.Default.Tune, contentDescription = null) },
                                trailingIcon = {
                                    if (includeSystem) Icon(Icons.Default.Check, contentDescription = "On")
                                },
                                onClick = { includeSystem = !includeSystem; menuOpen = false }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Share") },
                            leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                scope.launch {
                                    val text = if (tab == LogTab.PHONE) {
                                        filteredPhone.joinToString("\n") { it.raw }
                                    } else {
                                        tvLines.orEmpty().joinToString("\n")
                                    }
                                    shareLogs(context, text, if (tab == LogTab.PHONE) "phone" else "tv")
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Clear logs") },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                scope.launch {
                                    if (tab == LogTab.PHONE) {
                                        LogcatReader.clear()
                                        CrashLogger.clear()
                                        phoneLines = LogcatReader.snapshot(includeNoise = includeSystem)
                                        Toast.makeText(context, "Phone logs cleared", Toast.LENGTH_SHORT).show()
                                    } else if (tvAvailable) {
                                        clearTvLogs(tvIp!!, tvPort!!)
                                        tvLines = emptyList()
                                        Toast.makeText(context, "TV logs cleared", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            TabRow(selectedTabIndex = tab.ordinal) {
                Tab(
                    selected = tab == LogTab.PHONE,
                    onClick = { tab = LogTab.PHONE },
                    text = { Text("Phone") }
                )
                Tab(
                    selected = tab == LogTab.TV,
                    onClick = {
                        tab = LogTab.TV
                        if (tvLines == null && tvAvailable) {
                            scope.launch {
                                tvLoading = true
                                tvLines = fetchTvLogs(tvIp!!, tvPort!!)
                                tvLoading = false
                            }
                        }
                    },
                    text = { Text("TV") }
                )
            }

            // Search + level filter (Phone tab only — TV log is fetched as plain text)
            if (tab == LogTab.PHONE) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear search")
                            }
                        }
                    },
                    placeholder = { Text("Filter by tag or message") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions.Default,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val levels = listOf(
                        LogcatReader.Level.VERBOSE to "All",
                        LogcatReader.Level.DEBUG to "Debug",
                        LogcatReader.Level.INFO to "Info",
                        LogcatReader.Level.WARN to "Warn",
                        LogcatReader.Level.ERROR to "Error",
                    )
                    levels.forEach { (level, label) ->
                        FilterChip(
                            selected = minLevel == level,
                            onClick = { minLevel = level },
                            label = { Text(label) }
                        )
                    }
                }
            }

            when (tab) {
                LogTab.PHONE -> {
                    if (filteredPhone.isEmpty()) {
                        EmptyState("No matching log lines.")
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 8.dp)
                        ) {
                            items(filteredPhone) { line ->
                                LogRow(line, onClick = { detail = line })
                            }
                        }
                    }
                }

                LogTab.TV -> {
                    when {
                        !tvAvailable -> EmptyState("Connect to a TV to view its logs.")
                        tvLoading -> Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) { CircularProgressIndicator() }
                        tvLines.isNullOrEmpty() -> EmptyState("No TV logs available.")
                        else -> {
                            val lines = tvLines.orEmpty()
                            // Parse once per fetch into structured entries so rows match the phone
                            // tab and support tap-to-detail; filter separately so typing in the
                            // search box doesn't re-run the regex over every line. The combined TV
                            // log is chronological oldest→newest, so reverse it to show newest first.
                            val tvParsed = remember(lines) {
                                lines.filter { it.isNotBlank() }.map { parseTvLine(it) }.asReversed()
                            }
                            val tvEntries = remember(tvParsed, query) {
                                if (query.isBlank()) tvParsed
                                else tvParsed.filter {
                                    it.message.contains(query, true) || it.tag.contains(query, true)
                                }
                            }
                            // Render each line as its own item so only on-screen lines lay out,
                            // instead of one giant Text node measuring the whole log on the main thread.
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 8.dp)
                            ) {
                                items(tvEntries) { line ->
                                    LogRow(line, onClick = { detail = line })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun levelDotColor(level: LogcatReader.Level): Color = when (level) {
    LogcatReader.Level.ERROR, LogcatReader.Level.ASSERT -> Color(0xFFFF5A5A)
    LogcatReader.Level.WARN -> Color(0xFFFFA726)
    LogcatReader.Level.INFO, LogcatReader.Level.DEBUG, LogcatReader.Level.VERBOSE -> Color(0xFF7AA2F7)
    else -> Color(0xFF8A8A8A)
}

/** Subtle row tint behind warnings/errors, matching the Immich-style list. */
private fun levelRowTint(level: LogcatReader.Level): Color = when (level) {
    LogcatReader.Level.ERROR, LogcatReader.Level.ASSERT -> Color(0xFF2A1416)
    LogcatReader.Level.WARN -> Color(0xFF241C10)
    else -> Color.Transparent
}

/** Extracts just the HH:MM:SS.mmm portion from a logcat/file timestamp. */
private fun timeOf(timestamp: String): String =
    timestamp.substringAfterLast(' ').ifBlank { timestamp }

@Composable
private fun LogRow(line: LogcatReader.LogLine, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(levelRowTint(line.level))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .padding(end = 16.dp, top = 6.dp)
                .size(9.dp)
                .clip(CircleShape)
                .background(levelDotColor(line.level))
                .align(Alignment.Top)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = line.message,
                fontFamily = FontFamily.Monospace,
                fontSize = 15.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
            val subtitle = buildString {
                append("at ")
                append(timeOf(line.timestamp))
                if (line.tag.isNotEmpty()) {
                    append(" in ")
                    append(line.tag)
                }
            }
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogDetailScreen(entry: LogcatReader.LogLine, onBack: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Log Detail") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DetailLabel("MESSAGE", Modifier.weight(1f))
                IconButton(onClick = {
                    clipboard.setText(AnnotatedString(entry.message))
                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy message")
                }
            }
            DetailValueBox(entry.message)

            Spacer(Modifier.height(20.dp))
            DetailLabel("FROM")
            DetailPill(entry.tag.ifBlank { "—" })

            Spacer(Modifier.height(20.dp))
            DetailLabel("TIME")
            DetailPill(timeOf(entry.timestamp).ifBlank { "—" })

            Spacer(Modifier.height(20.dp))
            DetailLabel("LEVEL")
            DetailPill(entry.level.name)
        }
    }
}

@Composable
private fun DetailLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun DetailValueBox(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(16.dp)
    ) {
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun DetailPill(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

// TV file lines look like: "2026-06-19 12:30:37.267 E/Tag: message". Stack-trace continuation
// lines (and crash dumps) won't match — they render as plain UNKNOWN-level entries.
private val TV_LINE_REGEX =
    Regex("""^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+([VDIWEAF])/(.+?):\s?(.*)$""")

private fun parseTvLine(line: String): LogcatReader.LogLine {
    val m = TV_LINE_REGEX.matchEntire(line)
    return if (m != null) {
        val (ts, lvl, tag, msg) = m.destructured
        LogcatReader.LogLine(
            timestamp = ts,
            level = LogcatReader.Level.fromCode(lvl.first()),
            tag = tag.trim(),
            message = msg,
            raw = line,
        )
    } else {
        LogcatReader.LogLine("", LogcatReader.Level.UNKNOWN, "", line, line)
    }
}

@Composable
private fun EmptyState(text: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private const val TV_LOG_MAX_LINES = 3000

/** Fetches the TV log over HTTP and returns it split into a capped list of lines (newest kept). */
private suspend fun fetchTvLogs(tvIp: String, tvPort: Int): List<String> = withContext(Dispatchers.IO) {
    try {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        val response = client.newCall(
            Request.Builder().url("http://$tvIp:$tvPort/logs").get().build()
        ).execute()
        if (response.isSuccessful) {
            val lines = (response.body?.string() ?: "").split("\n")
            if (lines.size > TV_LOG_MAX_LINES) lines.subList(lines.size - TV_LOG_MAX_LINES, lines.size).toList()
            else lines
        } else if (response.code == 403) {
            listOf("Logging is disabled on the TV. Enable it in TV → Settings → Logs.")
        } else {
            listOf("Failed to fetch logs: ${response.code}")
        }
    } catch (e: Exception) {
        listOf("Error: ${e.message}")
    }
}

private suspend fun clearTvLogs(tvIp: String, tvPort: Int) = withContext(Dispatchers.IO) {
    try {
        val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).build()
        client.newCall(
            Request.Builder().url("http://$tvIp:$tvPort/logs").delete().build()
        ).execute().close()
    } catch (_: Exception) {
    }
}

private suspend fun shareLogs(context: Context, text: String, source: String) {
    if (text.isBlank()) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "Nothing to share", Toast.LENGTH_SHORT).show()
        }
        return
    }
    withContext(Dispatchers.IO) {
        try {
            val dir = File(context.cacheDir, "logs").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "playbridge_${source}_logs_$stamp.txt")
            file.writeText(text)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            withContext(Dispatchers.Main) {
                context.startActivity(Intent.createChooser(intent, "Share logs").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Share failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
