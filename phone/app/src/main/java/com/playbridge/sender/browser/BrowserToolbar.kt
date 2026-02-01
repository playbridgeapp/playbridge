package com.playbridge.sender.browser

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Browser toolbar with navigation controls, URL bar, and menu.
 */
@Composable
fun BrowserToolbar(
    currentUrl: String,
    isLoading: Boolean,
    canGoBack: Boolean,
    canGoForward: Boolean,
    videoCount: Int = 0,
    onUrlChange: (String) -> Unit,
    onNavigate: (String) -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onRefresh: () -> Unit,
    onStop: () -> Unit,
    onMenuClick: () -> Unit,
    onVideoClick: () -> Unit = {},
    menuContent: @Composable () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var isEditing by remember { mutableStateOf(false) }
    var editUrl by remember(currentUrl) { mutableStateOf(currentUrl) }
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    var clipboardDetail by remember { mutableStateOf<String?>(null) }

    // Check clipboard when editing starts
    LaunchedEffect(isEditing) {
        if (isEditing) {
            val clipText = clipboardManager.getText()?.text
            if (!clipText.isNullOrBlank() && clipText != editUrl) {
                clipboardDetail = clipText
            } else {
                clipboardDetail = null
            }
        }
    }
    
    Surface(
        shadowElevation = 8.dp,
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer, // distinct from background
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.width(4.dp))

                // Video count badge - clickable to show detected videos
                IconButton(onClick = onVideoClick) {
                    BadgedBox(
                        badge = {
                            Badge(
                                containerColor = if (videoCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                                contentColor = MaterialTheme.colorScheme.onError
                            ) {
                                Text(videoCount.toString())
                            }
                        }
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = "$videoCount videos detected",
                            tint = if (videoCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.width(4.dp))
                
                // URL Bar
                TextField(
                    value = editUrl,
                    onValueChange = { newValue ->
                        editUrl = newValue
                        onUrlChange(newValue)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { focusState ->
                            if (focusState.isFocused) {
                                isEditing = true
                            } else {
                                scope.launch {
                                    delay(200)
                                    if (isEditing) {
                                        isEditing = false
                                        editUrl = currentUrl
                                    }
                                }
                            }
                        },
                    singleLine = true,
                    placeholder = { 
                        Text(
                            "Search or enter URL",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        ) 
                    },
                    trailingIcon = {
                        if (editUrl.isNotEmpty() && isEditing) {
                            IconButton(onClick = { 
                                editUrl = ""
                                onUrlChange("") // Establish empty state
                            }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Clear URL",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(
                        onGo = {
                            val url = normalizeUrl(editUrl)
                            onNavigate(url)
                            isEditing = false
                            keyboardController?.hide()
                        }
                    ),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = MaterialTheme.colorScheme.primary
                    ),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    shape = MaterialTheme.shapes.extraLarge // Pill shape
                )
                
                Spacer(modifier = Modifier.width(8.dp))
                
                // Menu button
                Box {
                    IconButton(onClick = onMenuClick) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "Menu"
                        )
                    }
                    menuContent()
                }
            }

            // Clipboard suggestion
            if (isEditing && clipboardDetail != null) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp), // Less padding
                    color = MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.medium,
                    onClick = {
                        val url = normalizeUrl(clipboardDetail!!)
                        editUrl = url
                        onNavigate(url)
                        isEditing = false
                        keyboardController?.hide()
                    }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Paste link",
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                text = "Link you copied",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = clipboardDetail!!,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            
            // Loading progress indicator
            if (isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.Transparent
                )
            }
        }
    }
}

/**
 * Normalize user input to a valid URL.
 */
private fun normalizeUrl(input: String): String {
    val trimmed = input.trim()
    return when {
        trimmed.isEmpty() -> "about:blank"
        trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
        trimmed.startsWith("about:") -> trimmed
        trimmed.contains(".") && !trimmed.contains(" ") -> "https://$trimmed"
        else -> "https://www.google.com/search?q=${trimmed.replace(" ", "+")}"
    }
}
