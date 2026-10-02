package com.playbridge.sender.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * What the user picked in the Clear Data sheet. Mirrors Firefox's
 * "Clear Browsing Data" categories.
 */
data class ClearDataSelection(
    val openTabs: Boolean,
    val browsingHistory: Boolean,
    val cookiesAndSiteData: Boolean,
    val cachedImagesAndFiles: Boolean,
    val sitePermissions: Boolean,
    val downloads: Boolean,
) {
    val isEmpty: Boolean
        get() = !openTabs && !browsingHistory && !cookiesAndSiteData &&
            !cachedImagesAndFiles && !sitePermissions && !downloads
}

/**
 * Firefox-style "Clear Browsing Data" bottom sheet: one checkbox per data
 * category plus a destructive confirm button. Owns its own sheet state; the
 * caller controls visibility via [onDismissRequest].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClearDataSheet(
    openTabsCount: Int,
    onDismissRequest: () -> Unit,
    onConfirm: (ClearDataSelection) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var confirming by remember { mutableStateOf(false) }
    var openTabs by remember { mutableStateOf(false) }
    var browsingHistory by remember { mutableStateOf(true) }
    var cookies by remember { mutableStateOf(true) }
    var caches by remember { mutableStateOf(true) }
    var permissions by remember { mutableStateOf(false) }
    var downloads by remember { mutableStateOf(false) }

    val selection = ClearDataSelection(
        openTabs = openTabs,
        browsingHistory = browsingHistory,
        cookiesAndSiteData = cookies,
        cachedImagesAndFiles = caches,
        sitePermissions = permissions,
        downloads = downloads,
    )

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
        ) {
            Text(
                text = "Clear Browsing Data",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            ClearDataRow(
                label = "Open browser tabs",
                subtitle = "$openTabsCount tabs; installed bridged apps stay available",
                checked = openTabs,
                onCheckedChange = { openTabs = it },
            )
            ClearDataRow(
                label = "Browsing history",
                subtitle = "Includes search history",
                checked = browsingHistory,
                onCheckedChange = { browsingHistory = it },
            )
            ClearDataRow(
                label = "Cookies and site data",
                subtitle = "Includes bridged apps; you may be logged out",
                checked = cookies,
                onCheckedChange = { cookies = it },
            )
            ClearDataRow(
                label = "Cached images and files",
                subtitle = "Frees up storage space",
                checked = caches,
                onCheckedChange = { caches = it },
            )
            ClearDataRow(
                label = "Site permissions",
                subtitle = "Website casting, popup rules, and browser site permissions",
                checked = permissions,
                onCheckedChange = { permissions = it },
            )
            ClearDataRow(
                label = "Downloads",
                subtitle = "Clears the download list and temporary files; saved videos stay on your device",
                checked = downloads,
                onCheckedChange = { downloads = it },
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { confirming = true },
                enabled = !selection.isEmpty,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Clear Browsing Data")
            }
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Clear selected browsing data?") },
            text = {
                Text(buildList {
                    if (openTabs) add("Open browser tabs")
                    if (browsingHistory) add("Browsing history")
                    if (cookies) add("Cookies and site data (including bridged apps)")
                    if (caches) add("Cached images and files")
                    if (permissions) add("Site permissions")
                    if (downloads) add("Downloads")
                }.joinToString("\n"))
            },
            confirmButton = { TextButton(onClick = { confirming = false; onConfirm(selection) }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ClearDataRow(
    label: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp)
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
