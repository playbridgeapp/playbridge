package com.playbridge.player.ui.components

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.playbridge.player.ui.theme.TvRadius
import com.playbridge.player.ui.theme.TvSpacing
import com.playbridge.player.ui.theme.TvText
import com.playbridge.player.ui.theme.TvTheme

/**
 * Detects the "wrong APK on the wrong device" case: this is the TV receiver app,
 * but it's running on a phone/tablet (no leanback, touch-first hardware).
 *
 * Users who sideload the TV Player APK on their phone see a focus-driven UI that
 * doesn't respond to touch and report the app as "frozen" (issue #16). This guard
 * shows a one-time dismissible full-screen notice pointing them at the Phone app instead.
 */
object DeviceGuard {
    private const val PREFS = "device_guard"
    private const val KEY_DISMISSED = "wrong_device_dismissed"
    private const val RELEASES_URL = "https://github.com/playbridgeapp/playbridge/releases"

    fun isLikelyPhoneOrTablet(context: Context): Boolean {
        val pm = context.packageManager
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        val isTv = uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        return !isTv && pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }

    fun shouldWarn(context: Context): Boolean =
        isLikelyPhoneOrTablet(context) &&
            !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_DISMISSED, false)

    fun dismiss(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DISMISSED, true).apply()
    }

    fun openPhoneAppDownload(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

/** Full-screen "This is the TV app". Back behaves like "Use this as a TV anyway". */
@Composable
fun WrongDeviceScreen(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val primary = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { primary.requestFocus() } }
    BackHandler(onBack = onDismiss)
    Row(
        Modifier.fillMaxSize().background(TvTheme.colors.surface).padding(horizontal = TvSpacing.screenGutter),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(64.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(26.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("This is the TV app", style = TvText.pageTitle.copy(fontSize = 46.sp, lineHeight = 52.sp), color = TvTheme.colors.onSurface)
                Text(
                    "It looks like you've installed it on a phone or tablet. This app receives video. " +
                        "To send video, get the phone app instead.",
                    style = TvText.row.copy(fontSize = 20.sp, lineHeight = 30.sp, fontWeight = FontWeight.Normal),
                    color = TvTheme.colors.onSurfaceMuted, modifier = Modifier.widthIn(max = 620.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton(
                    "Get the phone app", onClick = { DeviceGuard.openPhoneAppDownload(context) },
                    height = 60.dp, modifier = Modifier.focusRequester(primary),
                )
                PillButton("Use this as a TV anyway", onClick = onDismiss, style = PillStyle.Secondary, height = 60.dp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
            Illustration("Sends", "Phone app", 120.dp, TvTheme.colors.accentContainer, TvRadius.card)
            Illustration("Plays", "TV app, this one", 300.dp, TvTheme.colors.container, TvRadius.row)
        }
    }
}

@Composable
private fun Illustration(label: String, caption: String, width: Dp, fill: Color, radius: Dp) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.size(width, 190.dp).background(fill, RoundedCornerShape(radius)),
            contentAlignment = Alignment.Center,
        ) { Text(label, style = TvText.value.copy(fontWeight = FontWeight.SemiBold), color = TvTheme.colors.onSurface) }
        Text(caption, style = TvText.value.copy(fontWeight = FontWeight.Normal), color = TvTheme.colors.onSurfaceMuted)
    }
}
