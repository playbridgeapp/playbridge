package com.playbridge.player.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.playbridge.player.ui.theme.TvRadius
import com.playbridge.player.ui.theme.TvSpacing
import com.playbridge.player.ui.theme.TvText
import com.playbridge.player.ui.theme.TvTheme

/**
 * Explains why the TV receiver needs the "Display over other apps"
 * (SYSTEM_ALERT_WINDOW) permission before dropping the user into the system
 * settings screen.
 *
 * The permission lets [OverlayWindowHelper] keep a tiny invisible overlay window
 * alive while a phone is connected, which is what exempts the background
 * [ServerService] from Android 14+ Background-Activity-Launch restrictions so it
 * can pop the player/browser onto the screen when a cast arrives. Sending the
 * user straight to a bare "allow display over other apps" toggle with no context
 * is confusing — this rationale tells them what it's for first.
 */
object OverlayPermissionGuard {

    private const val TAG = "OverlayPermissionGuard"

    /**
     * True when we should prompt. Android 10+ (Q) enforces Background-Activity-Launch
     * restrictions, and the overlay-window's "non-app visible window" exemption is what
     * lets the backgrounded [ServerService] pop the player/browser onto the screen when a
     * cast arrives. Below Q background launches work without it, so we stay quiet there.
     * Only prompt when the permission isn't already granted.
     */
    fun isNeeded(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            !Settings.canDrawOverlays(context)

    /** Opens the system "Display over other apps" settings screen for this app. */
    fun openSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { Log.e(TAG, "Failed to launch overlay settings activity", it) }
    }
}

/** Full-screen one-time setup notice. Back behaves like "Not now". */
@Composable
fun OverlayPermissionScreen(onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    val primary = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { primary.requestFocus() } }
    BackHandler(onBack = onDismiss)
    val colors = TvTheme.colors
    Row(
        Modifier.fillMaxSize().background(colors.surface).padding(horizontal = TvSpacing.screenGutter),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(56.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(26.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("One-time setup", style = TvText.value.copy(fontSize = 18.sp, fontWeight = FontWeight.Medium), color = colors.accent)
                Text("Let casts start from any app", style = TvText.pageTitle.copy(fontSize = 44.sp, lineHeight = 50.sp), color = colors.onSurface)
                Text(
                    "Without this, a cast from your phone only starts while PlayBridge is already open on the TV. " +
                        "It never draws anything over other apps.",
                    style = TvText.row.copy(fontSize = 20.sp, lineHeight = 30.sp, fontWeight = FontWeight.Normal),
                    color = colors.onSurfaceMuted, modifier = Modifier.widthIn(max = 620.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton("Open TV settings", onClick = onOpenSettings, height = 60.dp, modifier = Modifier.focusRequester(primary))
                PillButton("Not now", onClick = onDismiss, style = PillStyle.Secondary, height = 60.dp)
            }
        }
        Column(
            Modifier.width(440.dp).background(colors.container, TvRadius.optionShape).padding(30.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("On the next screen", style = TvText.caption, color = colors.onSurfaceMuted)
            SetupStep(1, "Find ", "PlayBridge TV", " in the list")
            SetupStep(2, "Turn on ", "Display over other apps", "")
            SetupStep(3, "Press Back to return here", "", "")
        }
    }
}

@Composable
private fun SetupStep(number: Int, before: String, bold: String, after: String) {
    val colors = TvTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(40.dp).background(colors.accentContainer, TvRadius.circle), contentAlignment = Alignment.Center) {
            Text("$number", style = TvText.row, color = colors.onAccentContainer)
        }
        Text(
            buildAnnotatedString {
                append(before)
                if (bold.isNotEmpty()) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(bold) }
                append(after)
            },
            style = TvText.row.copy(fontWeight = FontWeight.Normal), color = colors.onSurface,
        )
    }
}
