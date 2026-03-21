package com.playbridge.sender.browser

import android.content.Context
import android.content.ClipboardManager
import android.content.ClipData
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.playbridge.sender.data.debrid.DebridRepository
import com.playbridge.sender.data.history.BookmarkEntity
import com.playbridge.sender.data.history.DatabaseProvider
import com.playbridge.sender.data.library.AddonRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mozilla.components.browser.state.state.ContentState
import mozilla.components.browser.state.state.TabSessionState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportExportSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE) }
    val tmdbPrefs = remember { context.getSharedPreferences("browser_settings", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    val addonDao = remember { DatabaseProvider.getDatabase(context).addonDao() }
    val addonRepository = remember { AddonRepository(addonDao) }

    var isImporting by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var exportAction by remember { mutableStateOf<String?>(null) }
    var exportDebrid by remember { mutableStateOf(true) }
    var exportTmdb by remember { mutableStateOf(true) }
    var exportTvDefaults by remember { mutableStateOf(true) }
    var exportUiPrefs by remember { mutableStateOf(true) }
    var exportAddons by remember { mutableStateOf(true) }
    var exportTabs by remember { mutableStateOf(true) }
    var exportBookmarks by remember { mutableStateOf(true) }
    var pendingExportJson by remember { mutableStateOf("") }
    var showImportTabsDialog by remember { mutableStateOf(false) }
    var importedTabsToRestore by remember { mutableStateOf<List<ExportedTab>?>(null) }

    val importSettings = { jsonString: String ->
        isImporting = true
        scope.launch(Dispatchers.IO) {
            try {
                val imported = Json { ignoreUnknownKeys = true }.decodeFromString<ExportedSettings>(jsonString)

                val prefsEdit = prefs.edit()
                if (imported.showInbuiltExtensions != null) prefsEdit.putBoolean("show_inbuilt_extensions", imported.showInbuiltExtensions)
                if (imported.tvPlayerMode != null) prefsEdit.putString("tv_player_mode", imported.tvPlayerMode)
                if (imported.tvBrowserMode != null) prefsEdit.putString("tv_browser_mode", imported.tvBrowserMode)
                prefsEdit.apply()

                val tmdbEdit = tmdbPrefs.edit()
                if (imported.tmdbApiKey != null) tmdbEdit.putString("tmdb_api_key", imported.tmdbApiKey)
                if (imported.omdbApiKey != null) tmdbEdit.putString("omdb_api_key", imported.omdbApiKey)
                if (imported.debridProvider != null) tmdbEdit.putString(DebridRepository.KEY_DEBRID_PROVIDER, imported.debridProvider)
                if (imported.debridApiKey != null) tmdbEdit.putString(DebridRepository.KEY_DEBRID_API_KEY, imported.debridApiKey)
                tmdbEdit.apply()

                if (imported.addonUrls.isNotEmpty()) {
                    imported.addonUrls.forEach { url -> addonRepository.installAddon(url) }
                }

                if (imported.bookmarks != null && imported.bookmarks.isNotEmpty()) {
                    val bookmarkDao = DatabaseProvider.getDatabase(context).bookmarkDao()
                    imported.bookmarks.forEach { bookmark ->
                        bookmarkDao.insert(BookmarkEntity(url = bookmark.url, title = bookmark.title))
                    }
                }

                withContext(Dispatchers.Main) {
                    if (imported.tabs != null && imported.tabs.isNotEmpty() && Components.isEngineInitialized()) {
                        val currentUrls = Components.store.state.tabs.map { it.content.url }
                        val hasDuplicates = imported.tabs.any { it.url in currentUrls }

                        if (hasDuplicates) {
                            importedTabsToRestore = imported.tabs
                            showImportTabsDialog = true
                        } else {
                            val sessionTabs = imported.tabs.map { tab ->
                                TabSessionState(
                                    id = tab.id,
                                    content = ContentState(url = tab.url, title = tab.title ?: ""),
                                    parentId = tab.parentId
                                )
                            }
                            Components.tabManager?.restoreTabs(sessionTabs, null, Components.store)
                            Toast.makeText(context, "Settings imported, restoring tabs...", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(context, "Settings imported successfully", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Failed to import settings: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                isImporting = false
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    if (pendingExportJson.isNotEmpty()) {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(pendingExportJson.toByteArray()) }
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Settings exported successfully", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "No settings selected to export", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Failed to export settings: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                } finally {
                    pendingExportJson = ""
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val jsonString = context.contentResolver.openInputStream(uri)?.use {
                        it.bufferedReader().use { r -> r.readText() }
                    }
                    if (jsonString != null) importSettings(jsonString)
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Failed to read file: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    val triggerExport = {
        scope.launch(Dispatchers.IO) {
            try {
                val addons = if (exportAddons) addonDao.getAllSync() else emptyList()
                val database = DatabaseProvider.getDatabase(context)

                val currentBookmarks = if (exportBookmarks) {
                    database.bookmarkDao().getAllSync().map { ExportedBookmark(url = it.url, title = it.title) }
                } else null

                val currentTabs = if (exportTabs) {
                    database.tabDao().getAll().map { ExportedTab(id = it.id, url = it.url, title = it.title, parentId = it.parentId) }
                } else null

                val exported = ExportedSettings(
                    showInbuiltExtensions = if (exportUiPrefs) prefs.getBoolean("show_inbuilt_extensions", false) else null,
                    debridProvider = if (exportDebrid) tmdbPrefs.getString(DebridRepository.KEY_DEBRID_PROVIDER, DebridRepository.PROVIDER_NONE) else null,
                    debridApiKey = if (exportDebrid) tmdbPrefs.getString(DebridRepository.KEY_DEBRID_API_KEY, "") else null,
                    tmdbApiKey = if (exportTmdb) tmdbPrefs.getString("tmdb_api_key", "") else null,
                    omdbApiKey = if (exportTmdb) tmdbPrefs.getString("omdb_api_key", "") else null,
                    tvPlayerMode = if (exportTvDefaults) prefs.getString("tv_player_mode", "tv") else null,
                    tvBrowserMode = if (exportTvDefaults) prefs.getString("tv_browser_mode", "tv") else null,
                    addonUrls = if (exportAddons) addons.map { it.manifestUrl } else emptyList(),
                    tabs = currentTabs,
                    bookmarks = currentBookmarks
                )

                val jsonString = Json { prettyPrint = true; encodeDefaults = false }.encodeToString(exported)

                withContext(Dispatchers.Main) {
                    if (exportAction == "clipboard") {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("PlayBridge Settings", jsonString))
                        Toast.makeText(context, "Settings copied to clipboard", Toast.LENGTH_SHORT).show()
                    } else if (exportAction == "file") {
                        pendingExportJson = jsonString
                        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                        exportLauncher.launch("playbridge_settings_$timestamp.json")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Failed to prepare export: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import / Export") },
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Import settings from a file or clipboard.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { importLauncher.launch("*/*") },
                    modifier = Modifier.weight(1f),
                    enabled = !isImporting
                ) {
                    if (isImporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Importing…")
                    } else {
                        Text("Import from File")
                    }
                }

                IconButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clipData = clipboard.primaryClip
                        if (clipData != null && clipData.itemCount > 0) {
                            val text = clipData.getItemAt(0).text?.toString()
                            if (text != null) importSettings(text)
                            else Toast.makeText(context, "Clipboard does not contain text", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = !isImporting
                ) {
                    Icon(Icons.Default.ContentPaste, contentDescription = "Paste settings from clipboard")
                }
            }

            HorizontalDivider()

            Text(
                "Export settings to a file or clipboard.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        exportAction = "file"
                        showExportDialog = true
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Export to File")
                }

                IconButton(
                    onClick = {
                        exportAction = "clipboard"
                        showExportDialog = true
                    }
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy settings to clipboard")
                }
            }
        }
    }

    if (showImportTabsDialog && importedTabsToRestore != null) {
        AlertDialog(
            onDismissRequest = {
                showImportTabsDialog = false
                importedTabsToRestore = null
            },
            title = { Text("Duplicate Tabs Found") },
            text = { Text("Some of the imported tabs are already open. What would you like to do?") },
            confirmButton = {
                Button(onClick = {
                    showImportTabsDialog = false
                    val sessionTabs = importedTabsToRestore!!.map { tab ->
                        TabSessionState(
                            id = tab.id,
                            content = ContentState(url = tab.url, title = tab.title ?: ""),
                            parentId = tab.parentId
                        )
                    }
                    Components.tabManager?.restoreTabs(sessionTabs, null, Components.store)
                    importedTabsToRestore = null
                }) { Text("Import All") }
            },
            dismissButton = {
                OutlinedButton(onClick = {
                    showImportTabsDialog = false
                    val currentUrls = Components.store.state.tabs.map { it.content.url }
                    val newTabs = importedTabsToRestore!!.filter { it.url !in currentUrls }
                    if (newTabs.isNotEmpty()) {
                        val sessionTabs = newTabs.map { tab ->
                            TabSessionState(
                                id = tab.id,
                                content = ContentState(url = tab.url, title = tab.title ?: ""),
                                parentId = tab.parentId
                            )
                        }
                        Components.tabManager?.restoreTabs(sessionTabs, null, Components.store)
                        Toast.makeText(context, "Imported ${newTabs.size} new tabs", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "No new tabs to import", Toast.LENGTH_SHORT).show()
                    }
                    importedTabsToRestore = null
                }) { Text("Skip Duplicates") }
            }
        )
    }

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("Export Settings") },
            text = {
                Column {
                    Text("Select which settings to export:")
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = exportDebrid, onCheckedChange = { exportDebrid = it })
                        Text("Debrid Credentials")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = exportTmdb, onCheckedChange = { exportTmdb = it })
                        Text("TMDB Credentials")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = exportAddons, onCheckedChange = { exportAddons = it })
                        Text("Stremio Addons")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = exportTvDefaults, onCheckedChange = { exportTvDefaults = it })
                        Text("TV Player / Browser Defaults")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = exportUiPrefs, onCheckedChange = { exportUiPrefs = it })
                        Text("UI Preferences (e.g., extensions toggle)")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = exportTabs, onCheckedChange = { exportTabs = it })
                        Text("Current Open Tabs")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = exportBookmarks, onCheckedChange = { exportBookmarks = it })
                        Text("Bookmarks")
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    showExportDialog = false
                    triggerExport()
                }) { Text("Export") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showExportDialog = false }) { Text("Cancel") }
            }
        )
    }
}
