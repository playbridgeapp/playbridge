package com.playbridge.player.player

import com.playbridge.shared.player.VideoFilter
import com.playbridge.shared.player.VideoFilterAndroid
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import androidx.compose.ui.graphics.asImageBitmap

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun VideoFilterDialog(
    currentFilter: VideoFilter,
    customBrightness: Float,
    customContrast: Float,
    customSaturation: Float,
    previewFrame: android.graphics.Bitmap?,
    onFilterSelected: (VideoFilter) -> Unit,
    onCustomChanged: (brightness: Float, contrast: Float, saturation: Float) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedFilter by remember { mutableStateOf(currentFilter) }
    var brightness by remember { mutableFloatStateOf(customBrightness) }
    var contrast by remember { mutableFloatStateOf(customContrast) }
    var saturation by remember { mutableFloatStateOf(customSaturation) }

    val currentFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        currentFocusRequester.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                   (event.key == Key.DirectionCenter || event.key == Key.Enter)) {
                    // Global intercept of Center/Enter key to dismiss the dialog
                    onDismiss()
                    true
                } else false
            },
        contentAlignment = Alignment.BottomCenter
    ) {
        // Paused preview frame with instantaneous filter application
        if (previewFrame != null) {
            val composeColorMatrix = androidx.compose.ui.graphics.ColorMatrix(
                VideoFilterAndroid.customMatrix(brightness, contrast, saturation).array
            )
            if (selectedFilter != VideoFilter.CUSTOM) {
                composeColorMatrix.values.indices.forEach { i ->
                    composeColorMatrix.values[i] = VideoFilterAndroid.matrixFor(selectedFilter).array[i]
                }
            }

            androidx.compose.foundation.Image(
                bitmap = previewFrame.asImageBitmap(),
                contentDescription = "Filter Preview Overlay",
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                colorFilter = androidx.compose.ui.graphics.ColorFilter.colorMatrix(composeColorMatrix)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth(0.75f)
                .padding(bottom = 20.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xE8101020))
                .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Filter preset chips — single scrollable row
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                for (filter in VideoFilter.entries) {
                    val isActive = selectedFilter == filter
                    val isCurrentFilter = filter == currentFilter

                    FilterChip(
                        filter = filter,
                        isActive = isActive,
                        focusRequester = if (isCurrentFilter) currentFocusRequester else null,
                        onFocused = {
                            selectedFilter = filter
                            onFilterSelected(filter)
                        },
                        onClick = {
                            selectedFilter = filter
                            onFilterSelected(filter)
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Compact inline custom sliders — only when Custom is selected
            if (selectedFilter == VideoFilter.CUSTOM) {
                // Compact vertical slider stack — up/down navigates, left/right adjusts
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CompactSlider(
                        label = "Brightness",
                        value = brightness,
                        valueRange = -0.5f..0.5f,
                        defaultValue = 0f,
                        onValueChange = {
                            brightness = it
                            onCustomChanged(brightness, contrast, saturation)
                        },
                        onDismiss = onDismiss
                    )
                    CompactSlider(
                        label = "Contrast",
                        value = contrast,
                        valueRange = 0.5f..2f,
                        defaultValue = 1f,
                        onValueChange = {
                            contrast = it
                            onCustomChanged(brightness, contrast, saturation)
                        },
                        onDismiss = onDismiss
                    )
                    CompactSlider(
                        label = "Saturation",
                        value = saturation,
                        valueRange = 0f..2f,
                        defaultValue = 1f,
                        onValueChange = {
                            saturation = it
                            onCustomChanged(brightness, contrast, saturation)
                        },
                        onDismiss = onDismiss
                    )

                    // Reset button
                    var resetFocused by remember { mutableStateOf(false) }
                    Box(
                        modifier = Modifier
                            .height(24.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (resetFocused) Color(0xFF3A3A5E) else Color(0xFF2A2A3E))
                            .border(
                                if (resetFocused) 1.dp else 0.dp,
                                if (resetFocused) Color(0xFF00D9FF).copy(alpha = 0.5f) else Color.Transparent,
                                RoundedCornerShape(6.dp)
                            )
                            .onKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionCenter) {
                                    brightness = 0f; contrast = 1f; saturation = 1f
                                    onCustomChanged(0f, 1f, 1f)
                                    onDismiss()
                                    true
                                } else false
                            }
                            .onFocusChanged { resetFocused = it.isFocused }
                            .focusable()
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Reset",
                            fontSize = 11.sp,
                            color = if (resetFocused) Color(0xFF00D9FF) else Color.Gray
                        )
                    }
                }
            }
        }
    }

    androidx.activity.compose.BackHandler { onDismiss() }
}

@Composable
private fun FilterChip(
    filter: VideoFilter,
    isActive: Boolean,
    focusRequester: FocusRequester?,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }

    val bgColor = when {
        isActive -> Color(0xFF00D9FF).copy(alpha = 0.15f)
        isFocused -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
        else -> Color(0xFF1E1E38)
    }
    val borderColor = when {
        isActive -> Color(0xFF00D9FF).copy(alpha = 0.5f)
        isFocused -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
        else -> Color.Transparent
    }

    Box(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester)
                else Modifier
            )
            .onFocusChanged { state ->
                isFocused = state.isFocused
                if (state.isFocused) onFocused()
            }
            .focusable()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionCenter) {
                    onClick(); true
                } else false
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (isActive) "✓ ${filter.label}" else filter.label,
            color = if (isActive) Color(0xFF00D9FF) else MaterialTheme.colorScheme.onSurface,
            fontSize = 11.sp,
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

/** Compact slider row — entire row is focusable for reliable D-pad navigation. */
@Composable
private fun CompactSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    defaultValue: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val step = (valueRange.endInclusive - valueRange.start) / 40f
    val pct = ((value - defaultValue) / (valueRange.endInclusive - valueRange.start)) * 200f

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(22.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (isFocused) Color(0xFF2A2A4A) else Color.Transparent)
            .border(
                if (isFocused) 1.dp else 0.dp,
                if (isFocused) Color(0xFF00D9FF).copy(alpha = 0.4f) else Color.Transparent,
                RoundedCornerShape(4.dp)
            )
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.DirectionLeft -> { onValueChange((value - step).coerceIn(valueRange)); true }
                        Key.DirectionRight -> { onValueChange((value + step).coerceIn(valueRange)); true }
                        Key.DirectionCenter -> { onDismiss(); true }
                        else -> false
                    }
                } else false
            }
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = if (isFocused) Color(0xFF00D9FF) else Color.Gray,
            maxLines = 1,
            modifier = Modifier.width(70.dp)
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .height(12.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color(0xFF1E1E38)),
            contentAlignment = Alignment.CenterStart
        ) {
            val fraction = ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start))
                .coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color(0xFF00D9FF).copy(alpha = 0.25f),
                                Color(0xFF00D9FF).copy(alpha = 0.45f)
                            )
                        ),
                        RoundedCornerShape(3.dp)
                    )
            )
        }

        Text(
            text = "%.0f%%".format(pct),
            fontSize = 10.sp,
            color = if (isFocused) Color(0xFF00D9FF).copy(alpha = 0.7f) else Color.Gray.copy(alpha = 0.6f),
            maxLines = 1,
            modifier = Modifier.width(36.dp)
        )
    }
}
