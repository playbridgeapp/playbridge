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
import androidx.compose.ui.input.key.*

/**
 * Browser toolbar with navigation controls, URL bar, and menu.
 */
@Composable
fun BrowserToolbar(
    currentUrl: String,
    isLoading: Boolean,
    onUrlChange: (String) -> Unit,
    onNavigate: (String) -> Unit,
    onRefresh: () -> Unit,
    onStop: () -> Unit,
    onRemoteClick: (() -> Unit)? = null,
    isEditing: Boolean = false,
    isSecure: Boolean = false,
    onSecurityIconClick: () -> Unit = {},
    onEditingChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    var isFocused by remember { mutableStateOf(false) }

    // Use TextFieldValue for selection control; display stripped URL when not editing
    var textFieldValue by remember { mutableStateOf(TextFieldValue(if (currentUrl == "about:blank") "" else stripProtocol(currentUrl))) }

    // Keep textFieldValue in sync with currentUrl and isEditing changes.
    // When editing starts, we show the full URL (including http/https) and select all text.
    // When editing stops, we strip the protocol for a cleaner, compact display.
    LaunchedEffect(currentUrl, isEditing) {
        if (isEditing) {
            val fullUrl = if (currentUrl == "about:blank") "" else currentUrl
            // Only overwrite if the user is not actively typing a different URL
            if (textFieldValue.text != fullUrl && !textFieldValue.text.startsWith(fullUrl.removeSuffix("/"))) {
                textFieldValue = TextFieldValue(
                    text = fullUrl,
                    selection = androidx.compose.ui.text.TextRange(0, fullUrl.length)
                )
            }
        } else {
            textFieldValue = TextFieldValue(if (currentUrl == "about:blank") "" else stripProtocol(currentUrl))
            focusManager.clearFocus(force = true)
        }
    }

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
                    Spacer(modifier = Modifier.width(2.dp))
                } else {
                    // Security / Search icon all the way to the left
                    if (currentUrl == "about:blank") {
                        IconButton(
                            onClick = { onEditingChange(true) },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = "Search",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    } else {
                        IconButton(
                            onClick = onSecurityIconClick,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = if (isSecure) Icons.Default.Lock else Icons.Default.LockOpen,
                                contentDescription = if (isSecure) "Secure connection" else "Insecure connection",
                                tint = if (isSecure) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(2.dp))
                }

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
                        .height(40.dp)
                        .onFocusChanged { focusState ->
                            isFocused = focusState.isFocused
                            if (focusState.isFocused) {
                                onEditingChange(true)
                            } else {
                                scope.launch {
                                    delay(200)
                                    // Use the reactive isFocused state to guarantee focus was not regained
                                    if (!isFocused && isEditing) {
                                        onEditingChange(false)
                                    }
                                }
                            }
                        }
                        .onPreviewKeyEvent { keyEvent ->
                            // Dismiss edit mode and hide keyboard/clear focus on system back press
                            if (keyEvent.key == Key.Back && keyEvent.type == KeyEventType.KeyUp) {
                                keyboardController?.hide()
                                focusManager.clearFocus()
                                onEditingChange(false)
                                true
                            } else {
                                false
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
                            focusManager.clearFocus()
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
                            leadingIcon = null,
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
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                cursorColor = MaterialTheme.colorScheme.primary
                            ),
                            contentPadding = TextFieldDefaults.contentPaddingWithoutLabel(
                                top = 6.dp,
                                bottom = 6.dp,
                                start = 4.dp,
                                end = 4.dp
                            )
                        )
                    }
                )

                Spacer(modifier = Modifier.width(2.dp))

                if (!isEditing) {

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
                        Spacer(modifier = Modifier.width(2.dp))
                    }

                    // Refresh / Stop button (in place of menu button)
                    IconButton(
                        onClick = { if (isLoading) onStop() else onRefresh() },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = if (isLoading) Icons.Default.Close else Icons.Default.Refresh,
                            contentDescription = if (isLoading) "Refresh" else "Refresh",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(22.dp)
                        )
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
        else -> {
            val encodedQuery = try {
                java.net.URLEncoder.encode(trimmed, "UTF-8")
            } catch (e: Exception) {
                trimmed.replace(" ", "+")
            }
            "https://www.google.com/search?q=$encodedQuery"
        }
    }
}

/**
 * Strip http:// or https:// from a URL for compact display.
 */
private fun stripProtocol(url: String): String {
    return url.removePrefix("https://").removePrefix("http://")
}
