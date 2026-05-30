package com.playbridge.player.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SwitchPlayerDialog(
    currentPlayer: String,
    onPlayerSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val players = listOf(
        Pair("internal_exo", "ExoPlayer (Internal)"),
        Pair("internal_mpv", "MPV (Internal)"),
        Pair("external", "External Player")
    )

    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        try {
            focusRequester.requestFocus()
        } catch (e: Exception) {
            // Ignore focus errors if not attached yet
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(360.dp)
                .background(Color(0xF21A1A2E), RoundedCornerShape(14.dp))
                .padding(24.dp)
        ) {
            Text(
                text = "Switch Player",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            LazyColumn {
                items(players) { (playerId, playerName) ->
                    var isFocused by remember { mutableStateOf(false) }
                    val isSelected = playerId == currentPlayer

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                when {
                                    isFocused -> Color.White.copy(alpha = 0.2f)
                                    isSelected -> Color(0xFF00D9FF).copy(alpha = 0.15f)
                                    else -> Color.Transparent
                                }
                            )
                            .onFocusChanged { isFocused = it.isFocused }
                            .then(if (isSelected) Modifier.focusRequester(focusRequester) else Modifier)
                            .clickable { onPlayerSelected(playerId) }
                            .focusable(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = playerName,
                            color = if (isSelected) Color(0xFF00D9FF) else Color.White,
                            fontSize = 16.sp,
                            modifier = Modifier.padding(16.dp)
                        )
                        if (isSelected) {
                            Text(
                                text = "Current",
                                color = Color(0xFF00D9FF),
                                fontSize = 12.sp,
                                modifier = Modifier.padding(end = 16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
