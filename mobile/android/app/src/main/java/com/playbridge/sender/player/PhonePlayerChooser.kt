package com.playbridge.sender.player

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** Brings back the phone player if it's playing in the background; false when there's none. */
fun returnToPhonePlayer(context: Context): Boolean {
    val intent = PlayerActivity.returnIntent(context) ?: return false
    context.startActivity(intent)
    return true
}

/** The dashboard's Phone player entry when nothing is playing: play a file from the phone or a link. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhonePlayerChooser(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var enteringLink by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf("") }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            PlayerLauncher.start(context, uri.toString(), title = displayName(context, uri))
            onDismiss()
        }
    }
    val playableLink = link.trim().takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
    val playLink = {
        playableLink?.let {
            PlayerLauncher.start(context, it)
            onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "Phone player",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            val itemColors = ListItemDefaults.colors(containerColor = Color.Transparent)
            ListItem(
                headlineContent = { Text("Open a file") },
                supportingContent = { Text("A video or audio file on this phone") },
                leadingContent = { Icon(Icons.Default.Folder, contentDescription = null) },
                colors = itemColors,
                modifier = Modifier.clickable { pickFile.launch(arrayOf("video/*", "audio/*")) },
            )
            ListItem(
                headlineContent = { Text("Play a link") },
                supportingContent = { Text("A direct video or stream address (mp4, m3u8, …)") },
                leadingContent = { Icon(Icons.Default.Link, contentDescription = null) },
                colors = itemColors,
                modifier = Modifier.clickable { enteringLink = true },
            )
            if (enteringLink) {
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it },
                    label = { Text("Video link") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { playLink() }),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                )
                Button(
                    onClick = { playLink() },
                    enabled = playableLink != null,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                ) { Text("Play") }
            }
        }
    }
}

private fun displayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0)?.substringBeforeLast('.') else null
    }
}.getOrNull()
