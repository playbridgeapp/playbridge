package com.playbridge.sender.browser

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
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
    tabCount: Int = 1,
    onUrlChange: (String) -> Unit,
    onNavigate: (String) -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onRefresh: () -> Unit,
    onStop: () -> Unit,
    onMenuClick: () -> Unit,
    onDrawerClick: () -> Unit = {},
    onTabsClick: () -> Unit = {},
    onRemoteClick: (() -> Unit)? = null,
    isEditing: Boolean = false,
    isSecure: Boolean = false,
    onSecurityIconClick: () -> Unit = {},
    isDesktopMode: Boolean = false,
    onDesktopModeChange: (Boolean) -> Unit = {},
    onBookmarkClick: () -> Unit = {},
    onEditingChange: (Boolean) -> Unit = {},
    menuContent: @Composable () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Use TextFieldValue for selection control; display stripped URL when not editing
    var textFieldValue by remember { mutableStateOf(TextFieldValue(if (currentUrl == "about:blank") "" else stripProtocol(currentUrl))) }

    // Update text when currentUrl changes (only if not editing)
    LaunchedEffect(currentUrl) {
        if (!isEditing) {
            textFieldValue = TextFieldValue(if (currentUrl == "about:blank") "" else stripProtocol(currentUrl))
        }
    }

    // Reset stripped URL when editing stops
    LaunchedEffect(isEditing) {
        if (!isEditing) {
            textFieldValue = TextFieldValue(if (currentUrl == "about:blank") "" else stripProtocol(currentUrl))
        }
    }

    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

    Surface(
        shadowElevation = 4.dp,
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isEditing) {
                    // Back button to cancel editing
                    IconButton(
                        onClick = {
                            onEditingChange(false)
                            keyboardController?.hide()
                            focusManager.clearFocus()
                            textFieldValue = TextFieldValue(if (currentUrl == "about:blank") "" else stripProtocol(currentUrl))
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
                    // Hamburger menu
                    IconButton(
                        onClick = onDrawerClick,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            Icons.Default.Menu,
                            contentDescription = "Open navigation drawer",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(4.dp))

                // URL Bar — BasicTextField + DecorationBox for custom (compact) content padding
                val urlInteractionSource = remember { MutableInteractionSource() }
                @OptIn(ExperimentalMaterial3Api::class)
                BasicTextField(
                    value = textFieldValue,
                    onValueChange = { newValue ->
                        textFieldValue = newValue
                        onUrlChange(newValue.text)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 50.dp)
                        .onFocusChanged { focusState ->
                            if (focusState.isFocused) {
                                onEditingChange(true)
                                val fullUrl = if (currentUrl == "about:blank") "" else currentUrl
                                textFieldValue = TextFieldValue(
                                    text = fullUrl,
                                    selection = androidx.compose.ui.text.TextRange(0, fullUrl.length)
                                )
                            } else {
                                scope.launch {
                                    delay(200)
                                    if (isEditing) {
                                        onEditingChange(false)
                                        textFieldValue = TextFieldValue(if (currentUrl == "about:blank") "" else stripProtocol(currentUrl))
                                    }
                                }
                            }
                        },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(
                        onGo = {
                            val url = normalizeUrl(textFieldValue.text)
                            onNavigate(url)
                            onEditingChange(false)
                            keyboardController?.hide()
                        }
                    ),
                    interactionSource = urlInteractionSource,
                    decorationBox = { innerTextField ->
                        TextFieldDefaults.DecorationBox(
                            value = textFieldValue.text,
                            innerTextField = innerTextField,
                            enabled = true,
                            singleLine = true,
                            visualTransformation = VisualTransformation.None,
                            interactionSource = urlInteractionSource,
                            placeholder = {
                                Text(
                                    "Search or type URL",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            },
                            leadingIcon = if (!isEditing) {
                                {
                                    if (currentUrl == "about:blank") {
                                        Icon(
                                            Icons.Default.Search,
                                            contentDescription = "Search",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    } else {
                                        Icon(
                                            if (isSecure) Icons.Default.Lock else Icons.Default.LockOpen,
                                            contentDescription = if (isSecure) "Secure connection" else "Insecure connection",
                                            tint = if (isSecure) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier
                                                .size(16.dp)
                                                .pointerInput(Unit) {
                                                    detectTapGestures { onSecurityIconClick() }
                                                }
                                        )
                                    }
                                }
                            } else null,
                            trailingIcon = if (textFieldValue.text.isNotEmpty() && isEditing) {
                                {
                                    IconButton(
                                        onClick = {
                                            textFieldValue = TextFieldValue("")
                                            onUrlChange("")
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
                            } else null,
                            shape = CircleShape,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                cursorColor = MaterialTheme.colorScheme.primary
                            ),
                            contentPadding = TextFieldDefaults.contentPaddingWithoutLabel(
                                top = 6.dp,
                                bottom = 6.dp,
                                start = 12.dp,
                                end = 12.dp
                            )
                        )
                    }
                )

                Spacer(modifier = Modifier.width(4.dp))

                if (!isEditing) {
                    Spacer(modifier = Modifier.width(4.dp))

                    // Remote control shortcut (only when connected to TV)
                    if (onRemoteClick != null) {
                        IconButton(
                            onClick = onRemoteClick,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                Icons.Default.Gamepad,
                                contentDescription = "Remote Control",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

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

/**
 * Strip http:// or https:// from a URL for compact display.
 */
private fun stripProtocol(url: String): String {
    return url.removePrefix("https://").removePrefix("http://")
}
