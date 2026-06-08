package com.playbridge.sender.dlna

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.playbridge.sender.ui.theme.PlayBridgeTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * DEBUG-ONLY throwaway harness to validate DLNA/UPnP discovery + AVTransport
 * control against real renderers (VLC, Kodi) before building the real feature.
 *
 * Not wired into the production cast flow; registered only in the debug source
 * set (src/debug/AndroidManifest.xml) as a separate "DLNA Spike" launcher icon.
 */
class DlnaSpikeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PlayBridgeTheme { DlnaSpikeScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DlnaSpikeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val http = remember {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()
    }
    val ssdp = remember { SsdpDiscovery(context) }
    val descFetcher = remember { DeviceDescription(http) }
    val proxy = remember { LocalProxyServer(http, context.contentResolver) }

    var testUrl by remember { mutableStateOf(DEFAULT_TEST_URL) }
    var useProxy by remember { mutableStateOf(false) }
    var pasteJson by remember { mutableStateOf("") }
    var headerName by remember { mutableStateOf("") }
    var headerValue by remember { mutableStateOf("") }
    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    var pickedName by remember { mutableStateOf<String?>(null) }
    val renderers = remember { mutableStateListOf<DeviceDescription.Renderer>() }
    var selected by remember { mutableStateOf<DeviceDescription.Renderer?>(null) }
    var client by remember { mutableStateOf<AvTransportClient?>(null) }
    var discovering by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("idle") }
    val log = remember { mutableStateListOf<String>() }

    fun logLine(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        log.add(0, "$ts  $msg")
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            pickedUri = uri
            pickedName = displayName(context, uri) ?: uri.lastPathSegment
            logLine("Picked local file: $pickedName")
        }
    }

    // Local proxy runs for the whole screen lifetime.
    DisposableEffect(Unit) {
        val p = proxy.start()
        logLine("Proxy started on port $p")
        onDispose { proxy.stop() }
    }

    // While a renderer is selected, poll position so we can see RelTime advance —
    // this is the key "we can drive now-playing by polling" success criterion.
    LaunchedEffect(client) {
        val c = client ?: return@LaunchedEffect
        while (true) {
            delay(1000)
            val pos = c.getPositionInfo() ?: continue
            val st = c.getTransportState()
            status = "${st ?: "?"}   ${pos.relTime ?: "?"} / ${pos.trackDuration ?: "?"}"
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("DLNA Spike") }) }) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = testUrl,
                onValueChange = { testUrl = it },
                label = { Text("Test media URL (direct .mp4)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Switch(checked = useProxy, onCheckedChange = { useProxy = it })
                Text("Cast via local proxy")
            }
            if (useProxy) {
                OutlinedTextField(
                    value = pasteJson,
                    onValueChange = { pasteJson = it },
                    label = { Text("Paste cast message JSON (url + headers) — optional") },
                    minLines = 2,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "If pasted, its url + headers override the fields below.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = headerName,
                        onValueChange = { headerName = it },
                        label = { Text("Header") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = headerValue,
                        onValueChange = { headerValue = it },
                        label = { Text("Value") },
                        singleLine = true,
                        modifier = Modifier.weight(1.4f),
                    )
                }
            }

            OutlinedButton(
                onClick = { filePicker.launch(arrayOf("video/*")) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(pickedName?.let { "Local file: $it" } ?: "Pick local file") }

            Button(
                onClick = {
                    discovering = true
                    renderers.clear(); selected = null; client = null
                    logLine("Discovery: M-SEARCH…")
                    scope.launch {
                        val hits = ssdp.search()
                        logLine("SSDP hits: ${hits.size}")
                        for (hit in hits) {
                            val r = descFetcher.fetch(hit.location)
                            if (r != null && r.isUsable) {
                                renderers.add(r)
                                logLine("Renderer: ${r.friendlyName}")
                            } else {
                                logLine("Skipped (no AVTransport): ${hit.location}")
                            }
                        }
                        if (renderers.isEmpty()) logLine("No usable renderers found.")
                        discovering = false
                    }
                },
                enabled = !discovering,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (discovering) "Discovering…" else "Discover renderers") }

            renderers.forEach { r ->
                val isSelected = r === selected
                ElevatedCard(
                    onClick = {
                        selected = r
                        client = r.avTransportControlUrl?.let { AvTransportClient(it, http) }
                        logLine("Selected ${r.friendlyName}")
                    },
                    colors = if (isSelected) {
                        CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                        )
                    } else {
                        CardDefaults.elevatedCardColors()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(r.friendlyName, style = MaterialTheme.typography.titleMedium)
                        Text(
                            r.avTransportControlUrl.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            client?.let { c ->
                Text("Status: $status", style = MaterialTheme.typography.bodyMedium)
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        scope.launch {
                            val target = if (useProxy) {
                                val parsed = pasteJson.takeIf { it.isNotBlank() }
                                    ?.let { CastMessageParser.parse(it) }
                                if (pasteJson.isNotBlank() && parsed == null) {
                                    logLine("⚠ Could not parse pasted message (need a url field)")
                                }
                                val srcUrl = parsed?.url ?: testUrl
                                val headers = parsed?.headers
                                    ?: if (headerName.isNotBlank()) {
                                        mapOf(headerName.trim() to headerValue.trim())
                                    } else {
                                        emptyMap()
                                    }
                                if (parsed != null) {
                                    logLine("Parsed: url=${srcUrl.take(60)}… headers=${headers.keys}")
                                }
                                proxy.publish(srcUrl, headers, null).also { logLine("Proxy URL: $it") }
                            } else {
                                testUrl
                            }
                            // Loads media (resets playhead to 0) then plays — this is the cast hand-off.
                            logLine("Load & Play (${if (useProxy) "proxy" else "direct"})")
                            c.setAvTransportUri(target)
                            c.play()
                            status = c.getTransportState() ?: "?"
                        }
                    },
                ) { Text("Load & Play (cast)") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        // Resume from current position — no SetAVTransportURI, so it doesn't restart.
                        scope.launch { logLine("Resume"); c.play(); status = c.getTransportState() ?: "?" }
                    }) { Text("Play") }
                    OutlinedButton(onClick = {
                        scope.launch { logLine("Pause"); c.pause() }
                    }) { Text("Pause") }
                    OutlinedButton(onClick = {
                        scope.launch { logLine("Seek 00:00:30"); c.seek("00:00:30") }
                    }) { Text("Seek 30s") }
                    OutlinedButton(onClick = {
                        scope.launch { logLine("Stop"); c.stop() }
                    }) { Text("Stop") }
                }
                Button(
                    onClick = {
                        val uri = pickedUri
                        if (uri == null) {
                            logLine("Pick a local file first")
                        } else {
                            scope.launch {
                                val mime = context.contentResolver.getType(uri)
                                val proxyUrl = proxy.publishLocal(uri, mime)
                                logLine("Cast local file → $proxyUrl")
                                c.setAvTransportUri(proxyUrl)
                                c.play()
                                status = c.getTransportState() ?: "?"
                            }
                        }
                    },
                    enabled = pickedUri != null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Cast local file") }
            }

            HorizontalDivider()
            Text("Log", style = MaterialTheme.typography.titleSmall)
            SelectionContainer {
                Column(Modifier.fillMaxWidth()) {
                    log.take(100).forEach { line ->
                        Text(
                            line,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

private const val DEFAULT_TEST_URL =
    "http://192.168.1.24:8000/api/video/-1003566794400/2158"

private fun displayName(context: Context, uri: Uri): String? =
    context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
