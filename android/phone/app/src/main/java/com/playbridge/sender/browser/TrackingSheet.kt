package com.playbridge.sender.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.playbridge.sender.data.library.WatchlistEntity
import com.playbridge.sender.data.library.WatchlistStatus

// ---------------------------------------------------------------------------
// Result type returned to callers
// ---------------------------------------------------------------------------

sealed interface TrackingAction {
    /** User confirmed a status (and optional progress/rating/notes). */
    data class Upsert(
        val status: WatchlistStatus,
        val season: Int?,
        val episode: Int?,
        val rating: Int?,       // 1–10, null = unrated
        val notes: String?,
    ) : TrackingAction

    /** User tapped "Remove from list". */
    object Remove : TrackingAction

    /** User dismissed without saving. */
    object Dismiss : TrackingAction
}

// ---------------------------------------------------------------------------
// Sheet
// ---------------------------------------------------------------------------

/**
 * Bottom sheet for setting watch status, episode progress, personal rating,
 * and an optional note on any tracked media item.
 *
 * @param entity  The current [WatchlistEntity], or null if the item is not yet tracked.
 * @param mediaType  "movie" or "tv" — controls whether episode steppers are shown.
 * @param title   Display name shown in the sheet header.
 * @param onAction  Called when the user saves, removes, or dismisses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackingSheet(
    entity: WatchlistEntity?,
    mediaType: String,
    title: String,
    onAction: (TrackingAction) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isTracked = entity != null

    // Local state initialised from existing entity (or defaults for new items)
    var selectedStatus by remember {
        mutableStateOf(
            if (entity != null) WatchlistStatus.from(entity.status)
            else WatchlistStatus.PLAN_TO_WATCH
        )
    }
    var season by remember { mutableIntStateOf(entity?.seasonProgress ?: 1) }
    var episode by remember { mutableIntStateOf(entity?.episodeProgress ?: 1) }
    // userRating stored as 1–10; display as 1–5 stars (each star = 2 points)
    var starRating by remember { mutableIntStateOf((entity?.userRating ?: 0) / 2) }
    var notes by remember { mutableStateOf(entity?.notes ?: "") }

    val showEpisodePicker = mediaType == "tv" && selectedStatus == WatchlistStatus.WATCHING

    ModalBottomSheet(
        onDismissRequest = { onAction(TrackingAction.Dismiss) },
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
            )

            // Status chips
            StatusChips(
                selected = selectedStatus,
                onSelect = { selectedStatus = it },
            )

            // Episode progress (TV + Watching only)
            if (showEpisodePicker) {
                EpisodeStepper(
                    season = season,
                    episode = episode,
                    onSeasonChange = { season = it },
                    onEpisodeChange = { episode = it },
                )
            }

            // Star rating
            StarRatingRow(
                stars = starRating,
                onRate = { starRating = it },
            )

            // Notes
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Notes (optional)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )

            // Save button
            Button(
                onClick = {
                    onAction(
                        TrackingAction.Upsert(
                            status = selectedStatus,
                            season = if (showEpisodePicker) season else null,
                            episode = if (showEpisodePicker) episode else null,
                            rating = if (starRating > 0) starRating * 2 else null,
                            notes = notes.trim().ifBlank { null },
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(if (isTracked) "Save" else "Add to List")
            }

            // Remove button (only when already tracked)
            if (isTracked) {
                TextButton(
                    onClick = { onAction(TrackingAction.Remove) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Remove from list")
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Status chips
// ---------------------------------------------------------------------------

@Composable
private fun StatusChips(
    selected: WatchlistStatus,
    onSelect: (WatchlistStatus) -> Unit,
) {
    val statusColors = mapOf(
        WatchlistStatus.WATCHING      to MaterialTheme.colorScheme.primary,
        WatchlistStatus.COMPLETED     to Color(0xFF4CAF50),
        WatchlistStatus.PLAN_TO_WATCH to MaterialTheme.colorScheme.secondary,
        WatchlistStatus.ON_HOLD       to Color(0xFFFF9800),
        WatchlistStatus.DROPPED       to MaterialTheme.colorScheme.error,
    )

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Status",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(WatchlistStatus.entries) { status ->
                val isSelected = status == selected
                val accentColor = statusColors[status] ?: MaterialTheme.colorScheme.primary
                FilterChip(
                    selected = isSelected,
                    onClick = { onSelect(status) },
                    label = { Text(status.displayName, style = MaterialTheme.typography.labelMedium) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = accentColor.copy(alpha = 0.15f),
                        selectedLabelColor = accentColor,
                        selectedLeadingIconColor = accentColor,
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = isSelected,
                        selectedBorderColor = accentColor,
                        selectedBorderWidth = 1.5.dp,
                    ),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Episode stepper
// ---------------------------------------------------------------------------

@Composable
private fun EpisodeStepper(
    season: Int,
    episode: Int,
    onSeasonChange: (Int) -> Unit,
    onEpisodeChange: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Progress",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepperField(label = "Season", value = season, min = 1, onChange = onSeasonChange)
            StepperField(label = "Episode", value = episode, min = 1, onChange = onEpisodeChange)
        }
    }
}

@Composable
private fun StepperField(
    label: String,
    value: Int,
    min: Int,
    onChange: (Int) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(
                onClick = { if (value > min) onChange(value - 1) },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(Icons.Default.Remove, contentDescription = "Decrease $label", modifier = Modifier.size(18.dp))
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {
                Text(
                    text = value.toString().padStart(2, '0'),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            IconButton(
                onClick = { onChange(value + 1) },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = "Increase $label", modifier = Modifier.size(18.dp))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Star rating (1–5 stars; tapping the active star clears the rating)
// ---------------------------------------------------------------------------

@Composable
private fun StarRatingRow(
    stars: Int,    // 0 = unrated, 1–5
    onRate: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Rating",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (stars > 0) {
                Text(
                    text = "${stars * 2}/10",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (i in 1..5) {
                val filled = i <= stars
                Icon(
                    imageVector = if (filled) Icons.Filled.Star else Icons.Outlined.StarOutline,
                    contentDescription = "$i star${if (i == 1) "" else "s"}",
                    tint = if (filled) Color(0xFFFFC107) else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(32.dp)
                        .clickable { onRate(if (stars == i) 0 else i) }, // tap same star = clear
                )
            }
        }
    }
}
