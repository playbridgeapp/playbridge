package com.playbridge.sender.settings

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.playbridge.sender.ui.theme.AppTheme
import org.koin.compose.koinInject
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE) }
    val settingsRepository: com.playbridge.sender.data.settings.SettingsRepository = koinInject()
    val maxAliveTabsRepository by settingsRepository.maxAliveTabs.collectAsState(initial = 5)
    val coroutineScope = rememberCoroutineScope()
    var selectedTheme by remember { mutableStateOf(AppTheme.fromPrefs(context)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Appearance") },
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
                .padding(horizontal = 16.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Theme",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            AppTheme.entries.forEach { theme ->
                ThemeOptionRow(
                    theme = theme,
                    isSelected = theme == selectedTheme,
                    onSelect = {
                        if (theme != selectedTheme) {
                            selectedTheme = theme
                            prefs.edit().putString("app_theme", theme.name).apply()
                            (context as? Activity)?.recreate()
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Changes take effect immediately.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Tab Management",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            var maxTabs by remember { mutableFloatStateOf(5f) }
            LaunchedEffect(maxAliveTabsRepository) {
                maxTabs = maxAliveTabsRepository.toFloat()
            }

            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Active Tab Limit", style = MaterialTheme.typography.bodyLarge)
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "${maxTabs.toInt()} tabs",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Slider(
                    value = maxTabs,
                    onValueChange = { maxTabs = it },
                    onValueChangeFinished = {
                        coroutineScope.launch {
                            settingsRepository.setMaxAliveTabs(maxTabs.toInt())
                        }
                    },
                    valueRange = 1f..15f,
                    steps = 13
                )
                Text(
                    text = "Controls how many tabs are kept loaded in memory. Higher values allow faster switching between tabs but increase memory usage and risk of crashes on low-end devices.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ThemeOptionRow(
    theme: AppTheme,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    val description = when (theme) {
        AppTheme.DARK   -> "Deep indigo — easy on the eyes in dark environments"
        AppTheme.AMOLED -> "True black — saves battery on OLED screens"
        AppTheme.LIGHT  -> "Soft violet-white — for bright environments"
    }

    val borderColor = if (isSelected)
        MaterialTheme.colorScheme.primary
    else
        MaterialTheme.colorScheme.outlineVariant

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(20.dp)
            )
            .clickable(onClick = onSelect),
        shape = RoundedCornerShape(20.dp),
        color = if (isSelected)
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
        else
            MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = theme.label,
                    style = MaterialTheme.typography.titleLarge,
                    color = if (isSelected)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (isSelected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
