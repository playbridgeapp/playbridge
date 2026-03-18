package com.playbridge.sender.browser

import android.content.Context
import android.os.Environment
import android.widget.Toast
import android.content.ClipboardManager
import android.content.ClipData
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import com.playbridge.sender.data.debrid.DebridRepository
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

import com.playbridge.sender.data.history.BookmarkEntity
import com.playbridge.sender.data.history.DatabaseProvider
import com.playbridge.sender.data.library.AddonRepository
import org.mozilla.geckoview.GeckoSession
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.browser.state.state.ContentState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onAddonSettings: () -> Unit = {},
    tvIp: String? = null,
    tvPort: Int? = null
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE) }
    val tmdbPrefs = remember { context.getSharedPreferences("browser_settings", Context.MODE_PRIVATE) }

    val addonDao = remember { DatabaseProvider.getDatabase(context).addonDao() }
    val addonRepository = remember { AddonRepository(addonDao) }

    var showInbuiltExtensions by remember {
        mutableStateOf(prefs.getBoolean("show_inbuilt_extensions", false))
    }

    // Variables for forcing recomposition / state update on import
    var debridProviderTrigger by remember { mutableStateOf(0) }
    var tvPlayerTrigger by remember { mutableStateOf(0) }
    var tvBrowserTrigger by remember { mutableStateOf(0) }
    var tmdbTrigger by remember { mutableStateOf(0) }

    // Export Selection State
    var showExportDialog by remember { mutableStateOf(false) }
    var exportAction by remember { mutableStateOf<String?>(null) } // "file" or "clipboard"
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

    val scope = rememberCoroutineScope()

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    if (pendingExportJson.isNotEmpty()) {
                        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                            outputStream.write(pendingExportJson.toByteArray())
                        }
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
                    pendingExportJson = "" // Reset after export
                }
            }
        }
    }
    var isImporting by remember { mutableStateOf(false) }

    val importSettings = { jsonString: String ->
        isImporting = true
        scope.launch(Dispatchers.IO) {
            try {
                val imported = Json { ignoreUnknownKeys = true }.decodeFromString<ExportedSettings>(jsonString)

                // Update standard preferences
                if (imported.showInbuiltExtensions != null) {
                    prefs.edit().putBoolean("show_inbuilt_extensions", imported.showInbuiltExtensions).apply()
                    showInbuiltExtensions = imported.showInbuiltExtensions
                }
                if (imported.tvPlayerMode != null) {
                    prefs.edit().putString("tv_player_mode", imported.tvPlayerMode).apply()
                    tvPlayerTrigger++
                }
                if (imported.tvBrowserMode != null) {
                    prefs.edit().putString("tv_browser_mode", imported.tvBrowserMode).apply()
                    tvBrowserTrigger++
                }

                // Update TMDB key
                if (imported.tmdbApiKey != null) {
                    tmdbPrefs.edit().putString("tmdb_api_key", imported.tmdbApiKey).apply()
                    tmdbTrigger++
                }

                // Update OMDB key
                if (imported.omdbApiKey != null) {
                    tmdbPrefs.edit().putString("omdb_api_key", imported.omdbApiKey).apply()
                    tmdbTrigger++
                }

                // Update Debrid settings
                        val tmdbEdit = tmdbPrefs.edit()
                if (imported.debridProvider != null) {
                            tmdbEdit.putString(DebridRepository.KEY_DEBRID_PROVIDER, imported.debridProvider)
                }
                if (imported.debridApiKey != null) {
                            tmdbEdit.putString(DebridRepository.KEY_DEBRID_API_KEY, imported.debridApiKey)
                }
                        tmdbEdit.apply()
                debridProviderTrigger++

                // Install Addons
                if (imported.addonUrls.isNotEmpty()) {
                    imported.addonUrls.forEach { url ->
                        addonRepository.installAddon(url)
                    }
                }

                // Import Bookmarks
                if (imported.bookmarks != null && imported.bookmarks.isNotEmpty()) {
                    val bookmarkDao = DatabaseProvider.getDatabase(context).bookmarkDao()
                    imported.bookmarks.forEach { bookmark ->
                        bookmarkDao.insert(
                            BookmarkEntity(
                                url = bookmark.url,
                                title = bookmark.title
                            )
                        )
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
                            // Restore directly without dialog
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

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val jsonString = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        inputStream.bufferedReader().use { it.readText() }
                    }
                    if (jsonString != null) {
                        importSettings(jsonString)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Failed to read file: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
    var isDownloading by remember { mutableStateOf(false) }
    var isClearing by remember { mutableStateOf(false) }
    val isTvAvailable = tvIp != null && tvPort != null

    val triggerExport = {
        scope.launch(Dispatchers.IO) {
            try {
                val addons = if (exportAddons) addonDao.getAllSync() else emptyList()
                val addonUrls = addons.map { it.manifestUrl }
                val database = DatabaseProvider.getDatabase(context)

                val currentBookmarks = if (exportBookmarks) {
                    database.bookmarkDao().getAllSync().map { entity ->
                        ExportedBookmark(
                            url = entity.url,
                            title = entity.title
                        )
                    }
                } else null

                val currentTabs = if (exportTabs) {
                    database.tabDao().getAll().map { entity ->
                        ExportedTab(
                            id = entity.id,
                            url = entity.url,
                            title = entity.title,
                            parentId = entity.parentId
                        )
                    }
                } else null

                val exported = ExportedSettings(
                    showInbuiltExtensions = if (exportUiPrefs) prefs.getBoolean("show_inbuilt_extensions", false) else null,
                    debridProvider = if (exportDebrid) tmdbPrefs.getString(DebridRepository.KEY_DEBRID_PROVIDER, DebridRepository.PROVIDER_NONE) else null,
                    debridApiKey = if (exportDebrid) tmdbPrefs.getString(DebridRepository.KEY_DEBRID_API_KEY, "") else null,
                    tmdbApiKey = if (exportTmdb) tmdbPrefs.getString("tmdb_api_key", "") else null,
                    omdbApiKey = if (exportTmdb) tmdbPrefs.getString("omdb_api_key", "") else null,
                    tvPlayerMode = if (exportTvDefaults) prefs.getString("tv_player_mode", "tv") else null,
                    tvBrowserMode = if (exportTvDefaults) prefs.getString("tv_browser_mode", "tv") else null,
                    addonUrls = if (exportAddons) addonUrls else emptyList(),
                    tabs = currentTabs,
                    bookmarks = currentBookmarks
                )

                val jsonString = Json {
                    prettyPrint = true
                    encodeDefaults = false // Omits null values
                }.encodeToString(exported)

                withContext(Dispatchers.Main) {
                    if (exportAction == "clipboard") {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("PlayBridge Settings", jsonString)
                        clipboard.setPrimaryClip(clip)
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
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                windowInsets = WindowInsets(0.dp)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Existing setting: Show Inbuilt Extensions
            // Import / Export Section
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = {
                        importLauncher.launch("*/*")
                    },
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
                        Text("Import")
                    }
                }

                IconButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clipData = clipboard.primaryClip
                        if (clipData != null && clipData.itemCount > 0) {
                            val text = clipData.getItemAt(0).text?.toString()
                            if (text != null) {
                                importSettings(text)
                            } else {
                                Toast.makeText(context, "Clipboard does not contain text", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = !isImporting
                ) {
                    Icon(Icons.Default.ContentPaste, contentDescription = "Paste settings from clipboard")
                }

                Button(
                    onClick = {
                        exportAction = "file"
                        showExportDialog = true
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Export")
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

            HorizontalDivider()

            // Existing setting: Show Inbuilt Extensions
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Show Inbuilt Extensions",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "Display system extensions in the extensions list",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = showInbuiltExtensions,
                    onCheckedChange = { checked ->
                        showInbuiltExtensions = checked
                        prefs.edit().putBoolean("show_inbuilt_extensions", checked).apply()
                    }
                )
            }

            HorizontalDivider()

            // TMDB API Key
            Text(
                text = "Library",
                style = MaterialTheme.typography.titleMedium
            )

            var tmdbApiKey by remember(tmdbTrigger) {
                mutableStateOf(tmdbPrefs.getString("tmdb_api_key", "") ?: "")
            }
            var showApiKey by remember { mutableStateOf(false) }

            OutlinedTextField(
                value = tmdbApiKey,
                onValueChange = { newKey ->
                    tmdbApiKey = newKey
                    tmdbPrefs.edit().putString("tmdb_api_key", newKey.trim()).apply()
                },
                label = { Text("TMDB API Key") },
                placeholder = { Text("Enter your TMDB API key") },
                supportingText = { Text("Free at themoviedb.org → Settings → API") },
                singleLine = true,
                visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showApiKey = !showApiKey }) {
                        Icon(
                            if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showApiKey) "Hide key" else "Show key"
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            )

            var omdbApiKey by remember(tmdbTrigger) {
                mutableStateOf(tmdbPrefs.getString("omdb_api_key", "") ?: "")
            }
            var showOmdbKey by remember { mutableStateOf(false) }

            OutlinedTextField(
                value = omdbApiKey,
                onValueChange = { newKey ->
                    omdbApiKey = newKey
                    tmdbPrefs.edit().putString("omdb_api_key", newKey.trim()).apply()
                },
                label = { Text("OMDB API Key (Optional)") },
                placeholder = { Text("Enter your OMDB API key") },
                supportingText = { Text("Free at omdbapi.com (For IMDb/Rotten Tomatoes Ratings)") },
                singleLine = true,
                visualTransformation = if (showOmdbKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showOmdbKey = !showOmdbKey }) {
                        Icon(
                            if (showOmdbKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showOmdbKey) "Hide key" else "Show key"
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedButton(
                onClick = onAddonSettings,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Manage Stremio Addons")
            }

            HorizontalDivider()

            // Debrid Settings
            Text(
                text = "Debrid Services",
                style = MaterialTheme.typography.titleMedium
            )

            var debridProvider by remember(debridProviderTrigger) {
                mutableStateOf(tmdbPrefs.getString(DebridRepository.KEY_DEBRID_PROVIDER, DebridRepository.PROVIDER_NONE) ?: DebridRepository.PROVIDER_NONE)
            }
            var debridApiKey by remember(debridProviderTrigger) {
                mutableStateOf(tmdbPrefs.getString(DebridRepository.KEY_DEBRID_API_KEY, "") ?: "")
            }
            var debridExpanded by remember { mutableStateOf(false) }
            val debridOptions = listOf(
                DebridRepository.PROVIDER_NONE,
                DebridRepository.PROVIDER_REAL_DEBRID,
                DebridRepository.PROVIDER_ALL_DEBRID,
                DebridRepository.PROVIDER_PREMIUMIZE
            )

            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    readOnly = true,
                    value = debridProvider,
                    onValueChange = { },
                    label = { Text("Active Provider") },
                    trailingIcon = {
                        IconButton(onClick = { debridExpanded = !debridExpanded }) {
                            Icon(
                                if (debridExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                contentDescription = if (debridExpanded) "Collapse" else "Expand"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                // Transparent spacer overlay to capture clicks
                Spacer(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable { debridExpanded = !debridExpanded }
                )
                DropdownMenu(
                    expanded = debridExpanded,
                    onDismissRequest = { debridExpanded = false },
                    modifier = Modifier.fillMaxWidth(0.9f)
                ) {
                    debridOptions.forEach { selectionOption ->
                        DropdownMenuItem(
                            text = { Text(selectionOption) },
                            onClick = {
                                debridProvider = selectionOption
                                debridExpanded = false
                                tmdbPrefs.edit().putString(DebridRepository.KEY_DEBRID_PROVIDER, selectionOption).apply()
                            }
                        )
                    }
                }
            }

            if (debridProvider != DebridRepository.PROVIDER_NONE) {
                var showDebridKey by remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = debridApiKey,
                    onValueChange = { newKey ->
                        debridApiKey = newKey
                        tmdbPrefs.edit().putString(DebridRepository.KEY_DEBRID_API_KEY, newKey.trim()).apply()
                    },
                    label = { Text("$debridProvider API Key") },
                    placeholder = { Text("Enter API key") },
                    singleLine = true,
                    visualTransformation = if (showDebridKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showDebridKey = !showDebridKey }) {
                            Icon(
                                if (showDebridKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showDebridKey) "Hide key" else "Show key"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            HorizontalDivider()

            // Playback Preferences Section
            Text(
                text = "Playback Preferences",
                style = MaterialTheme.typography.titleMedium
            )

            // Preferred Audio Language
            var audioLangTrigger by remember { mutableStateOf(0) }
            var preferredAudioLang by remember(audioLangTrigger) {
                mutableStateOf(prefs.getString("preferred_audio_lang", "") ?: "")
            }
            var audioExpanded by remember { mutableStateOf(false) }

            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    readOnly = true,
                    value = SubtitlePreferences.audioLanguages.find { it.second == preferredAudioLang }?.first ?: "Auto",
                    onValueChange = { },
                    label = { Text("Preferred Audio Language") },
                    trailingIcon = {
                        IconButton(onClick = { audioExpanded = !audioExpanded }) {
                            Icon(
                                if (audioExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                contentDescription = if (audioExpanded) "Collapse" else "Expand"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable { audioExpanded = !audioExpanded }
                )
                DropdownMenu(
                    expanded = audioExpanded,
                    onDismissRequest = { audioExpanded = false },
                    modifier = Modifier.fillMaxWidth(0.9f)
                ) {
                    SubtitlePreferences.audioLanguages.forEach { (label, code) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                preferredAudioLang = code
                                audioExpanded = false
                                prefs.edit().putString("preferred_audio_lang", code).apply()
                            }
                        )
                    }
                }
            }

            // Preferred Subtitle Language
            var subLangTrigger by remember { mutableStateOf(0) }
            var preferredSubLang by remember(subLangTrigger) {
                mutableStateOf(prefs.getString("preferred_subtitle_lang", "") ?: "")
            }
            var subExpanded by remember { mutableStateOf(false) }

            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    readOnly = true,
                    value = SubtitlePreferences.subtitleLanguages.find { it.second == preferredSubLang }?.first ?: "None",
                    onValueChange = { },
                    label = { Text("Preferred Subtitle Language") },
                    trailingIcon = {
                        IconButton(onClick = { subExpanded = !subExpanded }) {
                            Icon(
                                if (subExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                contentDescription = if (subExpanded) "Collapse" else "Expand"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable { subExpanded = !subExpanded }
                )
                DropdownMenu(
                    expanded = subExpanded,
                    onDismissRequest = { subExpanded = false },
                    modifier = Modifier.fillMaxWidth(0.9f)
                ) {
                    SubtitlePreferences.subtitleLanguages.forEach { (label, code) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                preferredSubLang = code
                                subExpanded = false
                                prefs.edit().putString("preferred_subtitle_lang", code).apply()
                            }
                        )
                    }
                }
            }

            // Default Video Quality
            var videoQualityTrigger by remember { mutableStateOf(0) }
            var defaultVideoQuality by remember(videoQualityTrigger) {
                mutableStateOf(prefs.getString("default_video_quality", "Auto") ?: "Auto")
            }
            var qualityExpanded by remember { mutableStateOf(false) }

            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    readOnly = true,
                    value = SubtitlePreferences.videoQualities.find { it.second == defaultVideoQuality }?.first ?: "Auto",
                    onValueChange = { },
                    label = { Text("Default Video Quality") },
                    trailingIcon = {
                        IconButton(onClick = { qualityExpanded = !qualityExpanded }) {
                            Icon(
                                if (qualityExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                contentDescription = if (qualityExpanded) "Collapse" else "Expand"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable { qualityExpanded = !qualityExpanded }
                )
                DropdownMenu(
                    expanded = qualityExpanded,
                    onDismissRequest = { qualityExpanded = false },
                    modifier = Modifier.fillMaxWidth(0.9f)
                ) {
                    SubtitlePreferences.videoQualities.forEach { (label, code) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                defaultVideoQuality = code
                                qualityExpanded = false
                                prefs.edit().putString("default_video_quality", code).apply()
                            }
                        )
                    }
                }
            }

            HorizontalDivider()

            // TV Settings Section
            Text(
                text = "TV Defaults",
                style = MaterialTheme.typography.titleMedium
            )

            // Video Player setting
            var playerMode by remember(tvPlayerTrigger) {
                mutableStateOf(prefs.getString("tv_player_mode", "tv") ?: "tv")
            }
            var playerExpanded by remember { mutableStateOf(false) }
            val playerOptions = listOf(
                "tv" to "TV Default",
                "internal" to "Internal (ExoPlayer)",
                "external" to "External Player"
            )

            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    readOnly = true,
                    value = playerOptions.find { it.first == playerMode }?.second ?: "TV Default",
                    onValueChange = { },
                    label = { Text("TV Video Player") },
                    trailingIcon = {
                        IconButton(onClick = { playerExpanded = !playerExpanded }) {
                            Icon(
                                if (playerExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                contentDescription = if (playerExpanded) "Collapse" else "Expand"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable { playerExpanded = !playerExpanded }
                )
                DropdownMenu(
                    expanded = playerExpanded,
                    onDismissRequest = { playerExpanded = false },
                    modifier = Modifier.fillMaxWidth(0.9f)
                ) {
                    playerOptions.forEach { (optionId, optionName) ->
                        DropdownMenuItem(
                            text = { Text(optionName) },
                            onClick = {
                                playerMode = optionId
                                playerExpanded = false
                                prefs.edit().putString("tv_player_mode", optionId).apply()
                            }
                        )
                    }
                }
            }

            // Browser Engine setting
            var browserMode by remember(tvBrowserTrigger) {
                mutableStateOf(prefs.getString("tv_browser_mode", "tv") ?: "tv")
            }
            var browserExpanded by remember { mutableStateOf(false) }
            val browserOptions = listOf(
                "tv" to "TV Default",
                "webview" to "System WebView",
                "gecko" to "GeckoView (Mozilla)"
            )

            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    readOnly = true,
                    value = browserOptions.find { it.first == browserMode }?.second ?: "TV Default",
                    onValueChange = { },
                    label = { Text("TV Browser Engine") },
                    trailingIcon = {
                        IconButton(onClick = { browserExpanded = !browserExpanded }) {
                            Icon(
                                if (browserExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                contentDescription = if (browserExpanded) "Collapse" else "Expand"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable { browserExpanded = !browserExpanded }
                )
                DropdownMenu(
                    expanded = browserExpanded,
                    onDismissRequest = { browserExpanded = false },
                    modifier = Modifier.fillMaxWidth(0.9f)
                ) {
                    browserOptions.forEach { (optionId, optionName) ->
                        DropdownMenuItem(
                            text = { Text(optionName) },
                            onClick = {
                                browserMode = optionId
                                browserExpanded = false
                                prefs.edit().putString("tv_browser_mode", optionId).apply()
                            }
                        )
                    }
                }
            }

            HorizontalDivider()

            // TV Logs Section
            Text(
                text = "TV Diagnostics",
                style = MaterialTheme.typography.titleMedium
            )

            if (!isTvAvailable) {
                Text(
                    text = "Connect to a TV to download or clear logs.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Download TV Logs
            Button(
                onClick = {
                    if (tvIp == null || tvPort == null) return@Button
                    isDownloading = true
                    scope.launch {
                        downloadTvLogs(context, tvIp, tvPort)
                        isDownloading = false
                    }
                },
                enabled = isTvAvailable && !isDownloading,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isDownloading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(if (isDownloading) "Downloading…" else "Download TV Logs")
            }

            // Clear TV Logs
            OutlinedButton(
                onClick = {
                    if (tvIp == null || tvPort == null) return@OutlinedButton
                    isClearing = true
                    scope.launch {
                        clearTvLogs(context, tvIp, tvPort)
                        isClearing = false
                    }
                },
                enabled = isTvAvailable && !isClearing,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(if (isClearing) "Clearing…" else "Clear TV Logs")
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
                }) {
                    Text("Import All")
                }
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
                }) {
                    Text("Skip Duplicates")
                }
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
                }) {
                    Text("Export")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showExportDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

private suspend fun downloadTvLogs(context: Context, tvIp: String, tvPort: Int) {
    withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url("http://$tvIp:$tvPort/logs")
                .get()
                .build()

            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                val body = response.body?.string() ?: "Empty log"

                // Save to Downloads folder
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val fileName = "playbridge_tv_logs_$timestamp.txt"
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val file = File(downloadsDir, fileName)
                file.writeText(body)

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Logs saved to Downloads/$fileName", Toast.LENGTH_LONG).show()
                }
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Failed to download logs: ${response.code}", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

private suspend fun clearTvLogs(context: Context, tvIp: String, tvPort: Int) {
    withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url("http://$tvIp:$tvPort/logs")
                .delete()
                .build()

            val response = client.newCall(request).execute()

            withContext(Dispatchers.Main) {
                if (response.isSuccessful) {
                    Toast.makeText(context, "TV logs cleared", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Failed to clear logs: ${response.code}", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
