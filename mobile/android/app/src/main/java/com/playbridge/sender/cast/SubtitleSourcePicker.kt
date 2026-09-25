package com.playbridge.sender.cast

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.playbridge.sender.cast.dlna.DlnaProxyHolder
import java.net.URLEncoder

internal enum class SubtitleSource(
    val label: String,
    val description: String,
    val icon: ImageVector,
) {
    DETECTED("Detected", "Detected subtitles", Icons.Default.Subtitles),
    LOCAL("Local", "Choose a local subtitle file", Icons.Default.UploadFile),
    URL("URL", "Enter a subtitle URL", Icons.Default.Link),
}

@Composable
internal fun SubtitleSourceTabs(
    sources: List<SubtitleSource>,
    selected: SubtitleSource,
    onSelect: (SubtitleSource) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        sources.forEach { source ->
            val isSelected = source == selected
            val containerColor = animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                label = "Subtitle source background",
            ).value
            val contentColor = animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
                label = "Subtitle source content",
            ).value
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(containerColor)
                    .selectable(selected = isSelected, role = Role.Tab) { onSelect(source) }
                    .semantics { contentDescription = source.description },
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(source.icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(18.dp))
                Text(
                    source.label,
                    color = contentColor,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Serve a picked file from the phone so any PlayBridge receiver can fetch it. */
internal fun publishLocalSubtitle(context: Context, uri: Uri): Pair<String, String>? {
    val fileName = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index != -1 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "subtitle.srt"
    val extension = fileName.substringAfterLast('.', "").lowercase()
    if (extension !in setOf("srt", "vtt", "ass", "ssa", "sub")) {
        Toast.makeText(context, "Choose an SRT, VTT, ASS, SSA or SUB file", Toast.LENGTH_SHORT).show()
        return null
    }
    val mime = when (extension) {
        "vtt" -> "text/vtt"
        "ass", "ssa" -> "text/x-ssa"
        else -> "application/x-subrip"
    }
    val proxyUrl = try {
        DlnaProxyHolder.proxy(context).publishLocal(uri, mime)
    } catch (_: Exception) {
        Toast.makeText(context, "Could not share this subtitle file", Toast.LENGTH_SHORT).show()
        return null
    }
    return "$proxyUrl#${URLEncoder.encode(fileName, "UTF-8")}" to fileName
}
