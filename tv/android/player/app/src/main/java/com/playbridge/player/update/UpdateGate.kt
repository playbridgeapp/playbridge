package com.playbridge.player.update

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.playbridge.player.ui.components.CornerCard
import com.playbridge.player.ui.components.CornerCardSlot
import com.playbridge.player.ui.components.PillButton
import com.playbridge.player.ui.components.PillStyle
import com.playbridge.player.ui.theme.TvText
import com.playbridge.player.ui.theme.TvTheme
import com.playbridge.shared.update.InstallSource
import com.playbridge.shared.update.UpdateInfo
import com.playbridge.shared.update.UpdateState

/**
 * Renders the update flow driven by [UpdateChecker.state] as a card in the bottom-right corner of
 * the screen it is placed in. Place it once in the activity's root Box; it shows nothing while
 * [UpdateState.Idle].
 *
 * - [UpdateState.Available]: card with the new and current version, "Update now" (Play Store or
 *   sideload) and "Later". Takes focus because it needs an answer.
 * - [UpdateState.Downloading] / [UpdateState.Installing]: progress card. It cannot be dismissed
 *   and has no buttons, so it never takes focus and the screen underneath stays usable; the old
 *   dialog blocked the whole screen while working.
 * - manual [UpdateState.UpToDate] / [UpdateState.Error]: toast, as before.
 */
@Composable
fun BoxScope.UpdateGate(checker: UpdateChecker) {
    val context = LocalContext.current
    val state by checker.state.collectAsState()

    LaunchedEffect(state) {
        when (val s = state) {
            is UpdateState.UpToDate ->
                Toast.makeText(context, "You're on the latest version.", Toast.LENGTH_SHORT).show()
            is UpdateState.Error ->
                if (s.manual) Toast.makeText(context, s.message, Toast.LENGTH_LONG).show()
            else -> Unit
        }
    }

    val installed = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
    }

    when (val s = state) {
        is UpdateState.Available -> CornerCardSlot(Alignment.BottomEnd) {
            AvailableCard(
                info = s.info,
                installedVersion = installed,
                onAccept = {
                    if (s.info.source == InstallSource.SIDELOADED && !checker.canInstall()) {
                        checker.requestInstallPermission()
                    } else {
                        checker.accept(s.info)
                    }
                },
                onDismiss = checker::dismiss,
            )
        }

        is UpdateState.Downloading -> CornerCardSlot(Alignment.BottomEnd) {
            ProgressCard(title = "Downloading ${s.info.version}", fraction = s.fraction)
        }

        is UpdateState.Installing -> CornerCardSlot(Alignment.BottomEnd) {
            ProgressCard(title = "Installing ${s.info.version}", fraction = null)
        }

        else -> Unit
    }
}

@Composable
private fun AvailableCard(
    info: UpdateInfo,
    installedVersion: String?,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isPlay = info.source == InstallSource.PLAY_STORE
    val accept = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { accept.requestFocus() } }
    CornerCard(contentDescription = "Update available") {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Update available", style = TvText.cardHeadline, color = TvTheme.colors.onSurface)
            Text(
                buildString {
                    append("PlayBridge TV ${info.version}")
                    if (installedVersion != null) append(" · you have $installedVersion")
                },
                style = TvText.button.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal),
                color = TvTheme.colors.onSurfaceMuted,
            )
        }
        if (isPlay) {
            Text("Open the Play Store to update.", style = TvText.value, color = TvTheme.colors.onSurface)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            PillButton(
                text = if (isPlay) "Open Play Store" else "Update now", onClick = onAccept,
                modifier = Modifier.weight(1f).focusRequester(accept),
            )
            PillButton("Later", onClick = onDismiss, style = PillStyle.Secondary, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun ProgressCard(title: String, fraction: Float?) {
    CornerCard(contentDescription = title, takesFocus = false, gap = 14.dp) {
        Text(title, style = TvText.cardHeadline, color = TvTheme.colors.onSurface)
        if (fraction != null) {
            val f = fraction.coerceIn(0f, 1f)
            val track = TvTheme.colors.containerHighest
            val fill = TvTheme.colors.accent
            Box(
                Modifier.fillMaxWidth().height(6.dp).drawBehind {
                    val r = CornerRadius(3.dp.toPx())
                    drawRoundRect(track, size = size, cornerRadius = r)
                    drawRoundRect(fill, size = Size(size.width * f, size.height), cornerRadius = r)
                },
            )
            Text("${(f * 100).toInt()}%", style = TvText.caption, color = TvTheme.colors.onSurfaceMuted)
        } else {
            Text("Follow the Android prompt to finish.", style = TvText.caption, color = TvTheme.colors.onSurfaceMuted)
        }
    }
}
