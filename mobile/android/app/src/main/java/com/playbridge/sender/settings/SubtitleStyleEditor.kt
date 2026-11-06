package com.playbridge.sender.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playbridge.shared.player.SubtitleStyle

/**
 * Edits a [SubtitleStyle] with a live preview. Used for the phone player and, over the
 * connection, for a TV's style; [enabled] is false while the TV isn't reachable.
 */
@Composable
internal fun SubtitleStyleEditor(
    style: SubtitleStyle,
    onChange: (SubtitleStyle) -> Unit,
    enabled: Boolean = true,
    font: SubtitleFontControls? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SubtitleStylePreview(style, font?.typeface?.takeIf { style.font == SubtitleStyle.Font.CUSTOM })
        if (font != null) FontChoice(style, onChange, enabled, font)
        StyleChoice(
            "Size", style.size, SubtitleStyle.Size.entries, enabled,
            label = { when (it) {
                SubtitleStyle.Size.SMALL -> "S"
                SubtitleStyle.Size.MEDIUM -> "M"
                SubtitleStyle.Size.LARGE -> "L"
                SubtitleStyle.Size.EXTRA_LARGE -> "XL"
            } },
        ) { onChange(style.copy(size = it)) }
        StyleChoice(
            "Background", style.background, SubtitleStyle.Background.entries, enabled,
            label = { when (it) {
                SubtitleStyle.Background.NONE -> "None"
                SubtitleStyle.Background.TRANSLUCENT -> "See-through"
                SubtitleStyle.Background.SOLID -> "Solid"
            } },
        ) { onChange(style.copy(background = it)) }
        StyleChoice(
            "Edge", style.edge, SubtitleStyle.Edge.entries, enabled,
            label = { when (it) {
                SubtitleStyle.Edge.OUTLINE -> "Outline"
                SubtitleStyle.Edge.SHADOW -> "Shadow"
                SubtitleStyle.Edge.NONE -> "None"
            } },
        ) { onChange(style.copy(edge = it)) }
        if (enabled) {
            SettingsSwitch(
                "Restyle styled subtitles",
                "Also apply this style to subtitles with their own styling, such as ASS. Off keeps their original look.",
                style.overrideStyled,
            ) { onChange(style.copy(overrideStyled = it)) }
        }
    }
}

/** Default or the installed custom font, and a button to pick a font file. */
@Composable
private fun FontChoice(
    style: SubtitleStyle,
    onChange: (SubtitleStyle) -> Unit,
    enabled: Boolean,
    font: SubtitleFontControls,
) {
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(font.onPick) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val installed = font.installed
        if (installed != null) {
            StyleChoice(
                "Font", style.font, SubtitleStyle.Font.entries, enabled && !font.busy,
                label = { if (it == SubtitleStyle.Font.CUSTOM) installed else "Default" },
            ) {
                onChange(style.copy(font = it, customFont = installed.takeIf { _ -> it == SubtitleStyle.Font.CUSTOM }))
            }
        } else {
            Text("Font", style = MaterialTheme.typography.bodyLarge)
        }
        androidx.compose.material3.OutlinedButton(
            onClick = { picker.launch(FONT_MIME_TYPES) },
            enabled = enabled && !font.busy,
        ) { Text(if (installed == null) "Add a font file…" else "Replace font file…") }
        Text(
            font.status ?: "A .ttf or .otf file up to 10 MB. Characters it lacks use the device's fonts.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun <T> StyleChoice(
    title: String,
    selected: T,
    options: List<T>,
    enabled: Boolean,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    label = { Text(label(option), maxLines = 1) },
                )
            }
        }
    }
}

/** A sample line drawn the way the players draw plain subtitles, over a bright-to-dark scene. */
@Composable
private fun SubtitleStylePreview(style: SubtitleStyle, typeface: android.graphics.Typeface?) {
    val size = (18f * style.size.scale).sp
    val base = TextStyle(
        fontSize = size,
        fontWeight = FontWeight.Bold,
        fontFamily = typeface?.let { androidx.compose.ui.text.font.FontFamily(it) },
        color = Color.White,
        textAlign = TextAlign.Center,
        shadow = if (style.edge == SubtitleStyle.Edge.SHADOW) {
            Shadow(Color.Black, Offset(2f, 3f), blurRadius = 6f)
        } else null,
    )
    Box(
        Modifier
            .fillMaxWidth()
            .height(132.dp)
            .background(
                Brush.linearGradient(listOf(Color(0xFFF4E3B1), Color(0xFF7A9CC6), Color(0xFF22303F))),
                RoundedCornerShape(12.dp),
            )
            .padding(12.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            Modifier
                .background(
                    Color.Black.copy(alpha = style.background.opacity),
                    RoundedCornerShape(4.dp),
                )
                .padding(horizontal = 8.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            val sample = "Subtitles look like this\nالترجمة تبدو هكذا"
            if (style.edge == SubtitleStyle.Edge.OUTLINE) {
                Text(sample, style = base.copy(color = Color.Black, drawStyle = Stroke(size.value * 0.18f, join = StrokeJoin.Round)))
            }
            Text(sample, style = base)
        }
    }
}

/**
 * The connected TV's subtitle style, edited from the phone. The TV keeps it and applies it
 * at once; [connected] is whether this phone is linked to that TV right now.
 */
@Composable
internal fun TvSubtitleStyleSection(connected: Boolean) {
    val coordinator: com.playbridge.sender.connection.ConnectionCoordinator = org.koin.compose.koinInject()
    val supported = connected && coordinator.tvSupportsSubtitleStyle
    val reported by coordinator.tvSubtitleStyle.collectAsState()
    androidx.compose.runtime.LaunchedEffect(supported) { if (supported) coordinator.queryTvSubtitleStyle() }
    when {
        !connected -> Text(
            "Connect to the TV to change how its subtitles look.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        !supported -> Text(
            "Update PlayBridge on this TV to change its subtitle style from your phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> SubtitleStyleEditor(
            style = reported ?: SubtitleStyle.DEFAULT,
            onChange = coordinator::setTvSubtitleStyle,
            enabled = reported != null,
            font = if (coordinator.tvSupportsSubtitleFont) tvFontControls(coordinator) else null,
        )
    }
}

/** Sends a picked font to the TV, which installs and selects it. */
@Composable
private fun tvFontControls(coordinator: com.playbridge.sender.connection.ConnectionCoordinator): SubtitleFontControls {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val installed by coordinator.tvInstalledFont.collectAsState()
    val progress by coordinator.tvFontUploadProgress.collectAsState()
    var error by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val sending = progress?.let { (sent, total) -> "Sending the font to the TV… ${sent * 100 / total.coerceAtLeast(1)}%" }
    return SubtitleFontControls(
        installed = installed,
        status = sending ?: error,
        busy = progress != null,
        typeface = null,
        onPick = { uri ->
            error = null
            scope.launch {
                when (val picked = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { readPickedFont(context, uri) }) {
                    is PickedFont.Rejected -> error = picked.message
                    is PickedFont.Font -> coordinator.uploadTvSubtitleFont(picked.bytes)
                        .onFailure { error = fontErrorMessage(it.message) }
                }
            }
        },
    )
}
