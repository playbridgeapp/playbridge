package com.playbridge.sender.browser

import android.content.Context
import android.content.ClipboardManager
import android.content.ClipData
import android.content.SharedPreferences
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import com.playbridge.sender.data.debrid.DebridRepository
import com.playbridge.sender.data.history.BookmarkEntity
import com.playbridge.sender.data.history.DatabaseProvider
import com.playbridge.sender.data.library.AddonRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.playbridge.sender.data.backup.BackupManager
import com.playbridge.sender.data.backup.BackupUtils
import java.util.Date
import java.text.SimpleDateFormat
import java.util.Locale
import mozilla.components.browser.state.state.ContentState
import mozilla.components.browser.state.state.TabSessionState

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
        scope.launch {
            val result = BackupUtils.importFromJson(context, jsonString)
            when (result) {
                is BackupUtils.ImportResult.Success -> {
                    if (result.hasDuplicates) {
                        importedTabsToRestore = result.tabsToRestore
                        showImportTabsDialog = true
                    } else {
                        Toast.makeText(context, "Settings imported successfully", Toast.LENGTH_SHORT).show()
                    }
                }
                is BackupUtils.ImportResult.Error -> {
                    Toast.makeText(context, "Failed to import: ${result.message}", Toast.LENGTH_LONG).show()
                }
            }
            isImporting = false
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
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Failed to export: ${e.message}", Toast.LENGTH_SHORT).show()
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

    val backupManager = remember { BackupManager(context) }
    val backupPrefs = remember { context.getSharedPreferences("backup_prefs", Context.MODE_PRIVATE) }
    
    var s3Endpoint by remember { mutableStateOf(backupPrefs.getString(BackupManager.KEY_ENDPOINT, "") ?: "") }
    var s3Bucket by remember { mutableStateOf(backupPrefs.getString(BackupManager.KEY_BUCKET, "") ?: "") }
    var s3AccessKey by remember { mutableStateOf(backupPrefs.getString(BackupManager.KEY_ACCESS_KEY, "") ?: "") }
    var s3SecretKey by remember { mutableStateOf(backupPrefs.getString(BackupManager.KEY_SECRET_KEY, "") ?: "") }
    var s3Region by remember { mutableStateOf(backupPrefs.getString(BackupManager.KEY_REGION, "us-east-1") ?: "") }
    var isBackupEnabled by remember { mutableStateOf(backupPrefs.getBoolean(BackupManager.KEY_ENABLED, false)) }
    
    var isCloudBackingUp by remember { mutableStateOf(false) }
    var isCloudRestoring by remember { mutableStateOf(false) }
    var lastBackupTimestamp by remember { mutableStateOf(backupManager.getLastBackupTimestamp()) }

    DisposableEffect(Unit) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == BackupManager.KEY_LAST_BACKUP) {
                lastBackupTimestamp = backupManager.getLastBackupTimestamp()
            }
        }
        backupPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { backupPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    val triggerExport = {
        scope.launch {
            try {
                val jsonString = BackupUtils.createExportJson(context)
                
                if (exportAction == "clipboard") {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("PlayBridge Settings", jsonString))
                    Toast.makeText(context, "Settings copied to clipboard", Toast.LENGTH_SHORT).show()
                } else if (exportAction == "file") {
                    pendingExportJson = jsonString
                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    exportLauncher.launch("playbridge_settings_$timestamp.json")
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to prepare export: ${e.message}", Toast.LENGTH_SHORT).show()
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

            Spacer(modifier = Modifier.height(12.dp))

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

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Cloud Backup (S3 / Backblaze B2)",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                
                IconButton(onClick = {
                    try {
                        val config = com.playbridge.sender.data.backup.CloudBackupConfig(
                            endpoint = s3Endpoint,
                            bucket = s3Bucket,
                            accessKey = s3AccessKey,
                            secretKey = s3SecretKey,
                            region = s3Region
                        )
                        val json = Json.encodeToString(config)
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Cloud Backup Config", json))
                        Toast.makeText(context, "Cloud config copied", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(context, "Failed to copy config", Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy cloud config")
                }

                IconButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                    if (text != null) {
                        try {
                            val config = Json.decodeFromString<com.playbridge.sender.data.backup.CloudBackupConfig>(text)
                            s3Endpoint = config.endpoint
                            s3Bucket = config.bucket
                            s3AccessKey = config.accessKey
                            s3SecretKey = config.secretKey
                            s3Region = config.region
                            
                            backupPrefs.edit().apply {
                                putString(BackupManager.KEY_ENDPOINT, config.endpoint)
                                putString(BackupManager.KEY_BUCKET, config.bucket)
                                putString(BackupManager.KEY_ACCESS_KEY, config.accessKey)
                                putString(BackupManager.KEY_SECRET_KEY, config.secretKey)
                                putString(BackupManager.KEY_REGION, config.region)
                                apply()
                            }
                            Toast.makeText(context, "Cloud config pasted", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(context, "Invalid cloud config in clipboard", Toast.LENGTH_SHORT).show()
                        }
                    }
                }) {
                    Icon(Icons.Default.ContentPaste, contentDescription = "Paste cloud config")
                }
            }


            OutlinedTextField(
                value = s3Endpoint,
                onValueChange = { 
                    s3Endpoint = it
                    backupPrefs.edit().putString(BackupManager.KEY_ENDPOINT, it).apply()
                },
                label = { Text("Endpoint (e.g. s3.us-west-004.backblazeb2.com)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = s3Bucket,
                onValueChange = { 
                    s3Bucket = it
                    backupPrefs.edit().putString(BackupManager.KEY_BUCKET, it).apply()
                },
                label = { Text("Bucket Name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = s3AccessKey,
                onValueChange = { 
                    s3AccessKey = it
                    backupPrefs.edit().putString(BackupManager.KEY_ACCESS_KEY, it).apply()
                },
                label = { Text("Access Key ID") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = s3SecretKey,
                onValueChange = { 
                    s3SecretKey = it
                    backupPrefs.edit().putString(BackupManager.KEY_SECRET_KEY, it).apply()
                },
                label = { Text("Secret Access Key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )

            OutlinedTextField(
                value = s3Region,
                onValueChange = { 
                    s3Region = it
                    backupPrefs.edit().putString(BackupManager.KEY_REGION, it).apply()
                },
                label = { Text("Region (default: us-east-1)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Enable Automatic Backup", modifier = Modifier.weight(1f))
                Switch(
                    checked = isBackupEnabled,
                    onCheckedChange = { 
                        isBackupEnabled = it
                        backupPrefs.edit().putBoolean(BackupManager.KEY_ENABLED, it).apply()
                    }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        isCloudBackingUp = true
                        scope.launch {
                            val json = BackupUtils.createExportJson(context)
                            val success = backupManager.uploadBackup(json)
                            if (success) {
                                backupManager.saveLastBackupTimestamp()
                                lastBackupTimestamp = backupManager.getLastBackupTimestamp()
                                Toast.makeText(context, "Cloud backup successful", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Cloud backup failed", Toast.LENGTH_LONG).show()
                            }
                            isCloudBackingUp = false
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !isCloudBackingUp && backupManager.isConfigured()
                ) {
                    if (isCloudBackingUp) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Backing up…")
                    } else {
                        Text("Backup Now")
                    }
                }

                OutlinedButton(
                    onClick = {
                        isCloudRestoring = true
                        scope.launch {
                            val json = backupManager.downloadBackup()
                            if (json != null) {
                                importSettings(json)
                            } else {
                                Toast.makeText(context, "Failed to download backup", Toast.LENGTH_LONG).show()
                            }
                            isCloudRestoring = false
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !isCloudRestoring && backupManager.isConfigured()
                ) {
                    if (isCloudRestoring) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Restoring…")
                    } else {
                        Text("Restore from Cloud")
                    }
                }
            }

            if (lastBackupTimestamp > 0L) {
                val formatter = SimpleDateFormat("MMM dd, yyyy 'at' hh:mm a", Locale.US)
                val lastBackupText = "Last backup: ${formatter.format(Date(lastBackupTimestamp))}"
                Text(
                    text = lastBackupText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
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
