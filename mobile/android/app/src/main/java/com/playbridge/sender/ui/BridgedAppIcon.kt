package com.playbridge.sender.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.playbridge.sender.browser.BridgedApp
import com.playbridge.sender.browser.BridgedAppAppearance
import com.playbridge.sender.browser.BridgedAppIconCache

/** One fallback path for dashboard, reorder list and Info. Parent supplies the accessible app name. */
@Composable
internal fun BridgedAppIcon(app: BridgedApp, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val cached = remember(app.origin, app.iconUrl) { BridgedAppIconCache.cached(context, app) }
    val bitmap by produceState<Bitmap?>(cached, app.origin, app.iconUrl) {
        value = cached
        value = runCatching { BridgedAppIconCache.load(context, app) }.getOrNull()
    }
    val image = bitmap
    if (image != null) {
        Image(image.asImageBitmap(), contentDescription = null, modifier = modifier, contentScale = ContentScale.Fit)
    } else {
        Text(BridgedAppAppearance.monogram(app.name), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}
