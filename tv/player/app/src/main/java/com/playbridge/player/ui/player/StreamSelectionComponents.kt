package com.playbridge.player.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.*
import com.playbridge.shared.stremio.ScoredStremioStream

/**
 * Shared UI components for stream selection used in both PrePlayScreen and resolve overlays.
 */

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun StreamItem(
    stream: ScoredStremioStream,
    isCurrent: Boolean = false,
    isAutoPick: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val backgroundColor = when {
        isCurrent -> Color(0xFF00D9FF).copy(alpha = 0.25f)
        isFocused -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)
        else -> Color.Transparent
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused }
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .clickable { onClick() }
            .focusable()
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        // Title / Release Name
        Text(
            text = stream.title ?: stream.name ?: "Unknown Stream",
            fontSize = 14.sp,
            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
            color = if (isCurrent) Color(0xFF00D9FF) else MaterialTheme.colorScheme.onSurface,
            lineHeight = 18.sp
        )

        if (!stream.description.isNullOrBlank()) {
            Text(
                text = stream.description!!,
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.75f), // High contrast
                modifier = Modifier.padding(top = 4.dp),
                lineHeight = 16.sp
            )
        }

        // Metadata Row
        Row(
            modifier = Modifier.padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Quality Badge
            val qualityColor = when(stream.rank) {
                4 -> Color(0xFFE91E63) // 4K
                3 -> Color(0xFF4CAF50) // 1080p
                2 -> Color(0xFF2196F3) // 720p
                else -> Color.Gray
            }
            val qualityShort = when(stream.rank) {
                4 -> "4K"
                3 -> "1080p"
                2 -> "720p"
                else -> "SD"
            }

            Surface(
                shape = RoundedCornerShape(4.dp),
                colors = SurfaceDefaults.colors(
                    containerColor = qualityColor.copy(alpha = 0.2f)
                ),
                modifier = Modifier.padding(end = 8.dp)
            ) {
                Text(
                    text = qualityShort,
                    fontSize = 10.sp,
                    color = qualityColor,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }

            if (stream.isSeasonPack) {
                Text(
                    text = "Season Pack",
                    fontSize = 11.sp,
                    color = Color(0xFFFFC107),
                    modifier = Modifier.padding(end = 8.dp)
                )
            }

            if (stream.isExtras) {
                Text(
                    text = "Extras",
                    fontSize = 11.sp,
                    color = Color.Red.copy(alpha = 0.7f),
                    modifier = Modifier.padding(end = 8.dp)
                )
            }

            if (isAutoPick) {
                Text(
                    text = "Would Auto-pick",
                    fontSize = 11.sp,
                    color = Color(0xFFFFC107), // Amber/Gold
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Addon Name
            Text(
                text = stream.addonName ?: stream.name ?: "",
                fontSize = 11.sp,
                color = Color.Gray
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun FilterDropdownChip(
    label: String,
    valueText: String,
    isCustom: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable (dismiss: () -> Unit) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var isFocused by remember { mutableStateOf(false) }

    val themeColor = Color(0xFF00D9FF)
    val bgColor = when {
        !enabled -> Color(0xFF1E1E38).copy(alpha = 0.4f)
        isCustom -> themeColor.copy(alpha = 0.15f)
        isFocused -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
        else -> Color(0xFF1E1E38)
    }
    val borderColor = when {
        isCustom -> themeColor.copy(alpha = 0.5f)
        isFocused -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
        else -> Color.Transparent
    }
    val textColor = when {
        !enabled -> Color.Gray
        isCustom -> themeColor
        else -> MaterialTheme.colorScheme.onSurface
    }

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .widthIn(min = 120.dp, max = 220.dp)
                .height(38.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(bgColor)
                .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                .onFocusChanged { isFocused = it.isFocused }
                .clickable(enabled = enabled) { expanded = !expanded }
                .focusable(enabled = enabled)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "$label: $valueText",
                color = textColor,
                fontSize = 12.sp,
                fontWeight = if (isCustom) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(Modifier.width(6.dp))
            androidx.compose.material3.Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(16.dp)
            )
        }

        if (expanded) {
            Popup(
                alignment = Alignment.TopStart,
                offset = androidx.compose.ui.unit.IntOffset(0, with(androidx.compose.ui.platform.LocalDensity.current) { 42.dp.roundToPx() }),
                onDismissRequest = { expanded = false },
                properties = PopupProperties(
                    focusable = true,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true
                )
            ) {
                Column(
                    modifier = Modifier
                        .width(240.dp)
                        .background(Color(0xFF1E1E38), RoundedCornerShape(8.dp))
                        .border(1.dp, Color(0xFF00D9FF).copy(alpha = 0.25f), RoundedCornerShape(8.dp))
                        .padding(vertical = 4.dp)
                ) {
                    content { expanded = false }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun FilterDropdownItem(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val themeColor = Color(0xFF00D9FF)
    val bgColor = when {
        !enabled -> Color.Transparent
        isFocused -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
        else -> Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(bgColor)
            .onFocusChanged { if (enabled) isFocused = it.isFocused }
            .clickable(enabled = enabled) { onClick() }
            .focusable(enabled = enabled)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val textColor = when {
            !enabled -> Color.DarkGray
            selected -> themeColor
            else -> MaterialTheme.colorScheme.onSurface
        }
        Text(
            text = if (selected) "✓ $label" else label,
            color = textColor,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

fun resolutionRankLabel(rank: Int?): String = when (rank) {
    4 -> "4K"
    3 -> "1080p"
    2 -> "720p"
    1 -> "SD"
    else -> "All"
}
