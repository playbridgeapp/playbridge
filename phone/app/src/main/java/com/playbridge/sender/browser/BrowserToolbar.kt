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
import androidx.compose.ui.text.input.TextFieldValue
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
    tabCount: Int = 1,
    onUrlChange: (String) -> Unit,
    onNavigate: (String) -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onRefresh: () -> Unit,
    onStop: () -> Unit,
    onMenuClick: () -> Unit,
    onVideoClick: () -> Unit = {},
    onTabsClick: () -> Unit = {},
    isEditing: Boolean = false,
    onEditingChange: (Boolean) -> Unit = {},
    menuContent: @Composable () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Use TextFieldValue for selection control
    var textFieldValue by remember { mutableStateOf(TextFieldValue(currentUrl)) }
    
    // Update text when currentUrl changes (only if not editing)
    LaunchedEffect(currentUrl) {
        if (!isEditing) {
            textFieldValue = TextFieldValue(currentUrl)
        }
    }

    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    var clipboardDetail by remember { mutableStateOf<String?>(null) }
    var everFocused by remember { mutableStateOf(false) }

    // Check clipboard when editing starts
    LaunchedEffect(isEditing) {
        if (isEditing) {
            val clipText = clipboardManager.getText()?.text
            if (!clipText.isNullOrBlank() && clipText != textFieldValue.text) {
                clipboardDetail = clipText
            } else {
                clipboardDetail = null
            }
        } else {
             // Reset to current URL when editing stops
             textFieldValue = TextFieldValue(currentUrl)
        }
    }
    
    Surface(
        shadowElevation = 4.dp,
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
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isEditing) {
                    // Back button to cancel editing
                    IconButton(
                        onClick = { 
                            onEditingChange(false) 
                            keyboardController?.hide()
                            textFieldValue = TextFieldValue(currentUrl)
                        },
                        modifier = Modifier.size(40.dp)
                    ) {
                         Icon(
                             Icons.AutoMirrored.Filled.ArrowBack,
                             contentDescription = "Cancel editing",
                             modifier = Modifier.size(24.dp)
                         )
                    }
                } else {
                    // Video count badge - clickable to show detected videos
                    IconButton(
                        onClick = onVideoClick,
                        modifier = Modifier.size(40.dp)
                    ) {
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
                                tint = if (videoCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.width(4.dp))
                
                // URL Bar
                TextField(
                    value = textFieldValue,
                    onValueChange = { newValue ->
                        textFieldValue = newValue
                        onUrlChange(newValue.text)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .onFocusChanged { focusState ->
                            if (focusState.isFocused) {
                                onEditingChange(true)
                                // Select all text on first focus
                                if (!everFocused) {
                                     textFieldValue = textFieldValue.copy(
                                         selection = androidx.compose.ui.text.TextRange(0, textFieldValue.text.length)
                                     )
                                     everFocused = true
                                }
                            } else {
                                everFocused = false
                                scope.launch {
                                    delay(200)
                                    if (isEditing) {
                                        onEditingChange(false)
                                        textFieldValue = TextFieldValue(currentUrl)
                                    }
                                }
                            }
                        },
                    singleLine = true,
                    placeholder = { 
                        Text(
                            "Search or enter URL",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall
                        ) 
                    },
                    trailingIcon = {
                        if (textFieldValue.text.isNotEmpty() && isEditing) {
                            IconButton(
                                onClick = { 
                                    textFieldValue = TextFieldValue("")
                                    onUrlChange("") // Establish empty state
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Clear URL",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(
                        onGo = {
                            val url = normalizeUrl(textFieldValue.text)
                            onNavigate(url)
                            onEditingChange(false)
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
                    textStyle = MaterialTheme.typography.bodySmall,
                    shape = MaterialTheme.shapes.extraLarge // Pill shape
                )
                
                Spacer(modifier = Modifier.width(4.dp))
                
                if (!isEditing) {
                    Spacer(modifier = Modifier.width(4.dp))
                    
                    // Tabs button with badge
                    IconButton(
                        onClick = onTabsClick,
                        modifier = Modifier.size(40.dp)
                    ) {
                        BadgedBox(
                            badge = {
                                Badge(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                ) {
                                    Text(tabCount.toString())
                                }
                            }
                        ) {
                            Icon(
                                Icons.Default.WebAsset,
                                contentDescription = "$tabCount tabs",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    
                    // Menu button
                    Box {
                        IconButton(
                            onClick = onMenuClick,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = "Menu",
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        menuContent()
                    }
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
                        textFieldValue = TextFieldValue(url, androidx.compose.ui.text.TextRange(url.length))
                        onNavigate(url)
                        onEditingChange(false)
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
