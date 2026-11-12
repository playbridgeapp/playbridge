package com.playbridge.player.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.playbridge.player.server.WebSocketServer
import com.playbridge.player.ui.components.CornerCard
import com.playbridge.player.ui.components.PillButton
import com.playbridge.player.ui.theme.TvRadius
import com.playbridge.player.ui.theme.TvSize
import com.playbridge.player.ui.theme.TvText
import com.playbridge.player.ui.theme.TvTheme
import kotlinx.coroutines.delay

/**
 * "<phone> wants to connect" with the 6-digit code, a countdown and "Don't connect". Focus goes to
 * the deny button. The server itself times the request out after [PAIRING_WINDOW_MS] and removes
 * it; the countdown here only mirrors that, counted from when this card first showed the request.
 */
@Composable
fun PairingRequestCard(
    request: WebSocketServer.PairingRequest,
    onDeny: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val deny = remember { FocusRequester() }
    var elapsedMs by remember(request) { mutableLongStateOf(0L) }
    LaunchedEffect(request) {
        runCatching { deny.requestFocus() }
        val start = System.nanoTime()
        while (true) {
            elapsedMs = (System.nanoTime() - start) / 1_000_000L
            delay(1000)
        }
    }
    val colors = TvTheme.colors
    CornerCard(
        contentDescription = "${request.deviceName} wants to connect",
        modifier = modifier,
        width = TvSize.pairingCardWidth,
        gap = 22.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(
                Modifier.size(52.dp).background(colors.accentContainer, TvRadius.circle),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    phoneIconPainter(), contentDescription = null,
                    modifier = Modifier.size(26.dp), colorFilter = ColorFilter.tint(colors.onAccentContainer),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "${request.deviceName} wants to connect", style = TvText.cardTitle, color = colors.onSurface,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text("Enter this code on the phone", style = TvText.caption, color = colors.onSurfaceMuted)
            }
        }
        Box(
            Modifier.fillMaxWidth().height(124.dp).background(colors.surface, TvRadius.code.let { androidx.compose.foundation.shape.RoundedCornerShape(it) }),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                formatPairingCode(request.sasCode), style = TvText.code, color = colors.accent,
                textAlign = TextAlign.Center, maxLines = 1,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CountdownBar(fraction = pairingFractionLeft(elapsedMs))
            Text(expiresInText(pairingSecondsLeft(elapsedMs)), style = TvText.caption, color = colors.onSurfaceMuted)
        }
        PillButton(
            text = "Don't connect", onClick = onDeny,
            modifier = Modifier.fillMaxWidth().focusRequester(deny),
        )
        Text(
            "Only connect phones you recognise. A connected phone can play video and open pages on this TV.",
            style = TvText.small, color = colors.onSurfaceMuted,
        )
    }
}

@Composable
private fun CountdownBar(fraction: Float) {
    val track = TvTheme.colors.containerHighest
    val fill = TvTheme.colors.accent
    Box(
        Modifier.fillMaxWidth().height(6.dp).drawBehind {
            val r = CornerRadius(3.dp.toPx())
            drawRoundRect(track, size = size, cornerRadius = r)
            drawRoundRect(fill, size = Size(size.width * fraction, size.height), cornerRadius = r)
        },
    )
}
