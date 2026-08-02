package com.playbridge.sender.browser

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.input.key.*
import androidx.compose.ui.res.painterResource
import com.playbridge.sender.R
import com.playbridge.sender.cast.DetectedMediaKind
import com.playbridge.sender.cast.mediaCategoryAccent
import com.playbridge.sender.cast.mediaCategoryContentColor
import androidx.compose.foundation.Image

/**
 * Browser toolbar with navigation controls, URL bar, and menu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BrowserToolbar(
    currentUrl: String,
    isLoading: Boolean,
    onUrlChange: (String) -> Unit,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
    onMagnetDetected: (String) -> Unit = {},
    isTvConnected: Boolean,
    onTvClick: () -> Unit,
    onRemoteClick: () -> Unit,
    isPlayEnabled: Boolean,
    mediaCount: Int,
    mediaKind: DetectedMediaKind?,
    onPlayClick: () -> Unit,
    onPlayLongClick: () -> Unit,
    onLogoClick: () -> Unit = {},
    isEditing: Boolean = false,
    isSecure: Boolean = false,
    onSecurityIconClick: () -> Unit = {},
    onEditingChange: (Boolean) -> Unit = {},
) {
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    var isFocused by remember { mutableStateOf(false) }
    val mediaBadgeColor = if (mediaKind != null) {
        mediaCategoryAccent(mediaKind)
    } else {
        MaterialTheme.colorScheme.primary
    }
    val mediaBadgeContentColor = mediaCategoryContentColor(mediaBadgeColor)

    val mainColor = MaterialTheme.colorScheme.onSurface
    val dullColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    val urlVisualTransformation = remember(mainColor, dullColor) {
        UrlVisualTransformation(mainColor, dullColor)
    }

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
                    // Dashboard (blocks) icon → back to the Dashboard
                    IconButton(
                        onClick = onLogoClick,
                        modifier = Modifier.size(36.dp)
                    ) {
                        com.playbridge.sender.ui.DashboardBlocksIcon(
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(2.dp))
                    // Security / Search icon
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
                                painter = painterResource(id = R.drawable.ic_chrome_tune),
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
                    visualTransformation = if (isEditing) VisualTransformation.None else urlVisualTransformation,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(
                        onGo = {
                            val raw = textFieldValue.text.trim()
                            // A magnet link typed/pasted into the URL bar is handled like a
                            // clicked magnet link (GeckoView can't load magnet: anyway).
                            if (raw.startsWith("magnet:", ignoreCase = true)) {
                                onMagnetDetected(raw)
                            } else {
                                onNavigate(normalizeUrl(textFieldValue.text))
                            }
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
                            visualTransformation = if (isEditing) VisualTransformation.None else urlVisualTransformation,
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
                    // 1. Remote button (Shown ONLY if TV is connected)
                    if (isTvConnected) {
                        IconButton(
                            onClick = onRemoteClick,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_remote),
                                contentDescription = "TV Remote Control",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(2.dp))
                    }

                    // 2. TV Connection button (Permanent)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onTvClick)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (isTvConnected) {
                                Icon(
                                    imageVector = Icons.Default.Tv,
                                    contentDescription = "TV Connection (Connected)",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                                // Glowing active dot in top-right corner of TV icon
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(x = 2.dp, y = (-2).dp)
                                        .size(8.dp)
                                        .background(Color(0xFF4CAF50), shape = CircleShape)
                                        .border(1.5.dp, MaterialTheme.colorScheme.surfaceContainer, CircleShape)
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Tv,
                                    contentDescription = "TV Connection (Disconnected)",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(2.dp))

                    // 3. Play / Cast button (Permanent)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .combinedClickable(
                                enabled = isPlayEnabled,
                                onClick = onPlayClick,
                                onLongClick = onPlayLongClick
                            )
                    ) {
                        BadgedBox(
                            badge = {
                                if (isPlayEnabled && mediaCount > 0) {
                                    Badge(
                                        containerColor = mediaBadgeColor,
                                        contentColor = mediaBadgeContentColor,
                                    ) {
                                        Text(mediaCount.toString())
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Cast detected media",
                                tint = if (isPlayEnabled) {
                                    if (mediaKind != null) mediaBadgeColor else MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                },
                                modifier = Modifier.size(22.dp)
                            )
                        }
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

/**
 * Visual transformation that styles the domain of a URL brightly,
 * while keeping the protocol and path/query parameters dull.
 */
class UrlVisualTransformation(
    private val mainColor: Color,
    private val dullColor: Color
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val originalText = text.text

        // 1. Identify where the domain (host) starts (after "://")
        var hostStart = 0
        val protocolIndex = originalText.indexOf("://")
        if (protocolIndex != -1) {
            hostStart = protocolIndex + 3
        }

        // 2. Identify where the domain ends and path/query/fragment starts
        val firstSlash = originalText.indexOf('/', startIndex = hostStart)
        val firstQuestion = originalText.indexOf('?', startIndex = hostStart)
        val firstHash = originalText.indexOf('#', startIndex = hostStart)

        var pathStart = originalText.length
        if (firstSlash != -1 && firstSlash < pathStart) pathStart = firstSlash
        if (firstQuestion != -1 && firstQuestion < pathStart) pathStart = firstQuestion
        if (firstHash != -1 && firstHash < pathStart) pathStart = firstHash

        val hostEnd = pathStart

        val annotated = buildAnnotatedString {
            // Protocol prefix (dull color)
            if (hostStart > 0) {
                append(originalText.substring(0, hostStart))
                addStyle(SpanStyle(color = dullColor), 0, hostStart)
            }

            // Domain / Host name (main color)
            if (hostEnd > hostStart) {
                append(originalText.substring(hostStart, hostEnd))
                addStyle(SpanStyle(color = mainColor), hostStart, hostEnd)
            }

            // Path, queries, etc. (dull color)
            if (originalText.length > hostEnd) {
                append(originalText.substring(hostEnd))
                addStyle(SpanStyle(color = dullColor), hostEnd, originalText.length)
            }
        }

        return TransformedText(annotated, OffsetMapping.Identity)
    }
}
