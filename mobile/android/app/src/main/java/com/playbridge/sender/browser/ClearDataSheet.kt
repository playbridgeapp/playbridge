package com.playbridge.sender.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * What the user picked in the Clear Data sheet. Mirrors Firefox's
 * "Delete browsing data" categories.
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
 * Firefox-style "Delete browsing data" bottom sheet: one checkbox per data
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
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
        ) {
            Text(
                text = "Delete browsing data",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            ClearDataRow(
                label = "Open tabs",
                subtitle = if (openTabsCount == 1) "1 tab" else "$openTabsCount tabs",
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
                subtitle = "You'll be logged out of most sites",
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
                subtitle = "Camera, location, notifications, etc.",
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
                onClick = { onConfirm(selection) },
                enabled = !selection.isEmpty,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Delete browsing data")
            }
        }
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
