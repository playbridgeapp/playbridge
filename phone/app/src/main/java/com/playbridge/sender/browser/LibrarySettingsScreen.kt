package com.playbridge.sender.browser

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrarySettingsScreen(
    onBack: () -> Unit,
    onAddonSettings: () -> Unit
) {
    val context = LocalContext.current
    val tmdbPrefs = remember { context.getSharedPreferences("browser_settings", Context.MODE_PRIVATE) }

    var tmdbApiKey by remember { mutableStateOf(tmdbPrefs.getString("tmdb_api_key", "") ?: "") }
    var showTmdbKey by remember { mutableStateOf(false) }

    var omdbApiKey by remember { mutableStateOf(tmdbPrefs.getString("omdb_api_key", "") ?: "") }
    var showOmdbKey by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Library") },
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
                visualTransformation = if (showTmdbKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showTmdbKey = !showTmdbKey }) {
                        Icon(
                            if (showTmdbKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showTmdbKey) "Hide key" else "Show key"
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

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
        }
    }
}
