package com.playbridge.sender.cast.remote.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.playbridge.sender.cast.remote.RemoteInputMode
import com.playbridge.sender.cast.remote.remotePressFeedback
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.playbridge.sender.cast.DetectedVideo
import com.playbridge.sender.cast.MediaTrack
import com.playbridge.sender.cast.SubtitleSource
import com.playbridge.sender.cast.SubtitleSourceTabs
import com.playbridge.sender.cast.TvPlayerSettings
import com.playbridge.sender.cast.VideoDetector
import com.playbridge.sender.cast.newestSubtitlesFirst
import com.playbridge.sender.cast.publishLocalSubtitle
import com.playbridge.sender.cast.remote.RemoteAction
import com.playbridge.sender.cast.remote.RemoteCallbackActions
import com.playbridge.sender.cast.remote.RemoteDialogVolumeKeys
import com.playbridge.sender.cast.remote.RemoteMediaKind
import com.playbridge.sender.cast.remote.RemoteProfile
import com.playbridge.sender.cast.remote.RemoteSheet
import com.playbridge.sender.cast.remote.RemoteUiState
import com.playbridge.sender.cast.remote.RemoteViewport
import com.playbridge.sender.cast.remote.ScalingMode
import com.playbridge.sender.cast.remote.ready
import com.playbridge.sender.cast.remote.resolveRemoteLayout
import com.playbridge.sender.cast.subtitleOffIds
import com.playbridge.sender.cast.subtitleOffTrack
import com.playbridge.sender.cast.subtitleResourceForDetected
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BrowserMoreSheet(onBrowserControl: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        RemoteDialogVolumeKeys()
        Text("More controls", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 24.dp, bottom = 4.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            LabeledIconButton(icon = Icons.Default.Layers, label = "Source", tint = MaterialTheme.colorScheme.onSurfaceVariant, onClick = { onBrowserControl("video_target_cycle"); onDismiss() })
            LabeledIconButton(icon = Icons.AutoMirrored.Filled.VolumeUp, label = "Unmute", tint = MaterialTheme.colorScheme.onSurfaceVariant, onClick = { onBrowserControl("video_unmute"); onDismiss() })
            LabeledIconButton(icon = Icons.Default.Code, label = "Scripts", tint = MaterialTheme.colorScheme.onSurfaceVariant, onClick = { onBrowserControl("manage_user_scripts"); onDismiss() })
            LabeledIconButton(icon = Icons.Default.Language, label = "User Agent", tint = MaterialTheme.colorScheme.onSurfaceVariant, onClick = { onBrowserControl("manage_user_agent"); onDismiss() })
        }
        Spacer(Modifier.height(16.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerSettingsSheet(
    settings: TvPlayerSettings,
    videoTracks: List<MediaTrack>,
    onSetVideoQuality: (Int) -> Unit,
    onSetSpeed: (Float) -> Unit,
    onSetScaling: (String) -> Unit,
    onToggleAudioBoost: () -> Unit,
    onAdjustSubtitleOffset: (Long) -> Unit,
    onSwitchEngine: (String) -> Unit,
    showSettings: Boolean,
    showLoop: Boolean,
    onLoop: (Boolean) -> Unit,
    onStop: () -> Unit,
    showAddSubtitle: Boolean,
    onAddSubtitle: () -> Unit,
    showInputPreferences: Boolean,
    buttonPad: Boolean,
    onButtonPad: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        RemoteDialogVolumeKeys()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                "Player settings",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            if (showInputPreferences) {
                ButtonPadPreference(buttonPad, onButtonPad)
            }

            // 1. Playback group: Speed, Loop, Stop
            val showSpeed = showSettings && settings.speedAvailable
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Playback",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (showSpeed) {
                    SettingRow("Speed") {
                        ChipGroup(
                            options = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f).map {
                                (if (it == 1.0f) "1x" else "${it}x") to it.toString()
                            },
                            selectedKey = settings.speed.toString(),
                            onSelect = { it.toFloatOrNull()?.let(onSetSpeed) },
                        )
                    }
                }
                if (showLoop) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "Loop · TV state unavailable",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                enabled = LocalRemoteControlsEnabled.current,
                                onClick = { onLoop(true) },
                            ) {
                                Text("Enable loop")
                            }
                            OutlinedButton(
                                enabled = LocalRemoteControlsEnabled.current,
                                onClick = { onLoop(false) },
                            ) {
                                Text("Disable loop")
                            }
                        }
                    }
                }
                Button(
                    enabled = LocalRemoteControlsEnabled.current,
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Stop playback")
                }
            }

            // 2. Picture group: Quality, Scaling
            val showQuality = showSettings && settings.qualityAvailable && videoTracks.count { it.id != "auto" } > 1
            val showScaling = showSettings && settings.scalingAvailable
            if (showQuality || showScaling) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Picture",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (showQuality) {
                        SettingRow("Quality") {
                            ChipGroup(
                                options = videoTracks.map { track -> track.name to track.id },
                                selectedKey = if (settings.qualityMaxHeight == 0) "auto" else "max:${settings.qualityMaxHeight}",
                                onSelect = { id -> onSetVideoQuality(id.removePrefix("max:").toIntOrNull() ?: 0) },
                            )
                        }
                    }
                    if (showScaling) {
                        SettingRow("Scaling") {
                            ChipGroup(
                                options = listOf("Fit" to "Fit", "Crop to fill" to "Zoom", "Stretch" to "Fill"),
                                selectedKey = settings.scaling,
                                onSelect = onSetScaling,
                            )
                        }
                    }
                }
            }

            // 3. Audio group: Audio Boost, Engine
            val showAudioBoost = showSettings && settings.audioBoostAvailable
            val showEngine = showSettings && settings.engine != null
            if (showAudioBoost || showEngine) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Audio",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (showAudioBoost) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Audio boost",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                enabled = LocalRemoteControlsEnabled.current,
                                checked = settings.audioBoost,
                                onCheckedChange = { onToggleAudioBoost() },
                            )
                        }
                    }
                    if (showEngine) {
                        SettingRow("Player engine") {
                            ChipGroup(
                                options = listOf("ExoPlayer" to "exo", "MPV" to "mpv"),
                                selectedKey = settings.engine,
                                onSelect = onSwitchEngine,
                            )
                        }
                    }
                }
            }

            // 4. Subtitles group: Offset / Delay, Add Subtitle
            val showOffset = showSettings
            if (showOffset || showAddSubtitle) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Subtitles",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (showOffset) {
                        SettingRow("Subtitle offset") {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                FilledTonalButton(
                                    enabled = LocalRemoteControlsEnabled.current,
                                    onClick = { onAdjustSubtitleOffset(-250L) },
                                ) {
                                    Text("−250ms")
                                }
                                Text("${settings.subtitleOffsetMs} ms", style = MaterialTheme.typography.bodyMedium)
                                FilledTonalButton(
                                    enabled = LocalRemoteControlsEnabled.current,
                                    onClick = { onAdjustSubtitleOffset(250L) },
                                ) {
                                    Text("+250ms")
                                }
                            }
                        }
                    }
                    if (showAddSubtitle) {
                        OutlinedButton(
                            enabled = LocalRemoteControlsEnabled.current,
                            onClick = onAddSubtitle,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Add subtitle")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingRow(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

@Composable
private fun ChipGroup(options: List<Pair<String, String>>, selectedKey: String, onSelect: (String) -> Unit) {
    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (label, key) ->
            FilterChip(enabled = LocalRemoteControlsEnabled.current, selected = key == selectedKey, onClick = { onSelect(key) }, label = { Text(label) })
        }
    }
}

@Composable
private fun AddSubtitleDialog(
    detectedSubtitles: List<DetectedVideo>,
    onSubmitResource: ((playbridge.SubtitleResource) -> Unit)? = null,
    onAddResource: (playbridge.SubtitleResource) -> Boolean,
    onAddLocalUrl: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf("") }
    val context = LocalContext.current
    val sources = remember(detectedSubtitles.isNotEmpty()) {
        if (detectedSubtitles.isNotEmpty()) SubtitleSource.entries.toList()
        else SubtitleSource.entries.filterNot { it == SubtitleSource.DETECTED }
    }
    var source by remember(sources) { mutableStateOf(sources.first()) }

    val filePickerLauncher = rememberLauncherForActivityResult(contract = ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            publishLocalSubtitle(context, uri)?.let { (localUrl, _) -> onAddLocalUrl(localUrl) }
        }
    }

    fun addResource(resource: playbridge.SubtitleResource) {
        if (onSubmitResource != null) { onSubmitResource(resource); return }
        if (!onAddResource(resource)) {
            android.widget.Toast.makeText(
                context, "Could not add subtitle. Check the TV connection or update the receiver.",
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }

    val detected = remember(detectedSubtitles) { newestSubtitlesFirst(detectedSubtitles) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { RemoteDialogVolumeKeys(); Text("Add subtitle") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                SubtitleSourceTabs(sources = sources, selected = source, onSelect = { source = it })
                Spacer(Modifier.height(16.dp))
                when (source) {
                    SubtitleSource.DETECTED -> LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 390.dp)) {
                        items(detected, key = { it.url }) { subtitle ->
                            var preview by remember(subtitle.url) { mutableStateOf(subtitle.subtitlePreview) }
                            var language by remember(subtitle.url) { mutableStateOf(subtitle.subtitleLanguage) }
                            LaunchedEffect(subtitle.url) {
                                preview = VideoDetector.fetchSubtitlePreview(subtitle)
                                language = subtitle.subtitleLanguage
                            }
                            Column(
                                modifier = Modifier.fillMaxWidth()
                                    .clickable(enabled = LocalRemoteControlsEnabled.current) { addResource(subtitleResourceForDetected(subtitle)) }
                                    .padding(vertical = 9.dp),
                            ) {
                                Text(
                                    subtitle.title ?: subtitle.url.substringBefore('?').substringAfterLast('/'),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    buildString {
                                        append("Detected ")
                                        append(java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
                                            .format(java.util.Date(subtitle.timestamp)))
                                        language?.let { append(" · Likely ").append(it) }
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                preview?.takeIf(String::isNotBlank)?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                                        overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                    SubtitleSource.LOCAL -> Column {
                        Text("Choose a subtitle file from this phone. The TV will load it through the phone.")
                        Spacer(Modifier.height(12.dp))
                        FilledTonalButton(enabled = LocalRemoteControlsEnabled.current,
                            onClick = { filePickerLauncher.launch("*/*") },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Choose local file") }
                    }
                    SubtitleSource.URL -> OutlinedTextField(enabled = LocalRemoteControlsEnabled.current,
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("Subtitle URL (.srt / .vtt)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            if (source == SubtitleSource.URL) {
                TextButton(
                    onClick = {
                        val entered = url.trim()
                        if (entered.startsWith("https://", true) || entered.startsWith("http://", true)) {
                            addResource(playbridge.SubtitleResource(url = entered))
                        } else {
                            android.widget.Toast.makeText(context, "Enter an HTTP or HTTPS subtitle URL",
                                android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = url.isNotBlank(),
                ) { Text("Add URL") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

internal const val OFF_KEY = "__off__"
internal const val EMBEDDED_KEY = "__embedded__"
internal const val REMOTE_KEY = "__remote__"
internal const val EXTERNAL_KEY = "__external__"

internal data class SubGroup(val key: String, val label: String, val tracks: List<MediaTrack>, val hasSelected: Boolean)
private data class SubInfo(val langKey: String, val langDisplay: String, val optionLabel: String)

private val LANG_TOKENS: Map<String, String> = buildMap {
    fun add(display: String, vararg tokens: String) { tokens.forEach { put(it, display) } }
    add("English", "english", "en", "eng")
    add("Spanish", "spanish", "es", "spa", "esp")
    add("French", "french", "fr", "fre", "fra")
    add("German", "german", "de", "ger", "deu")
    add("Italian", "italian", "it", "ita")
    add("Japanese", "japanese", "ja", "jpn")
    add("Korean", "korean", "ko", "kor")
    add("Chinese", "chinese", "zh", "chi", "zho")
    add("Russian", "russian", "ru", "rus")
    add("Portuguese", "portuguese", "pt", "por")
    add("Portuguese (BR)", "portuguese (br)", "pob", "pt-br", "ptbr")
    add("Arabic", "arabic", "ar", "ara")
    add("Hindi", "hindi", "hi", "hin")
    add("Dutch", "dutch", "nl", "dut", "nld")
    add("Swedish", "swedish", "sv", "swe")
    add("Turkish", "turkish", "tr", "tur")
    add("Polish", "polish", "pl", "pol")
    add("Romanian", "romanian", "ro", "ron", "rum")
    add("Greek", "greek", "el", "ell", "gre")
    add("Czech", "czech", "cs", "cze", "ces")
    add("Danish", "danish", "da", "dan")
    add("Hungarian", "hungarian", "hu", "hun")
    add("Bulgarian", "bulgarian", "bg", "bul")
    add("Slovenian", "slovenian", "sl", "slv")
    add("Indonesian", "indonesian", "id", "ind")
    add("Hebrew", "hebrew", "he", "heb")
    add("Finnish", "finnish", "fi", "fin")
    add("Serbian", "serbian", "sr", "srp")
    add("Croatian", "croatian", "hr", "hrv")
    add("Norwegian", "norwegian", "no", "nor")
    add("Ukrainian", "ukrainian", "uk", "ukr")
    add("Thai", "thai", "th", "tha")
    add("Vietnamese", "vietnamese", "vi", "vie")
    add("Persian", "persian", "farsi", "fa", "per", "fas")
}

internal fun languageOf(segment: String): String? = LANG_TOKENS[segment.trim().lowercase()]

/** Chip and sheet label. A numeric or hex id from the TV is not shown; the track id itself is unchanged. */
internal fun subtitleOptionLabel(track: MediaTrack, tracks: List<MediaTrack>): String {
    if (track.id.lowercase() in subtitleOffIds) return "Off"
    return readableSubtitleName(track.name) ?: "Subtitle ${subtitleOrdinal(tracks, track)}"
}

internal fun readableSubtitleName(name: String): String? {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed.equals("Off", ignoreCase = true)) return "Off"
    val parts = trimmed.split(' ', '·', '•', '|', '/', ',').map { it.trim() }.filter { it.isNotEmpty() }
    val languages = parts.mapNotNull(::languageOf).distinct()
    val rest = parts.filter { languageOf(it) == null }
    if (rest.isNotEmpty() && rest.all { it.isOpaqueTrackId() }) return languages.firstOrNull()
    if (rest.isEmpty() && languages.isNotEmpty()) return languages.joinToString(" • ")
    return trimmed
}

private fun String.isOpaqueTrackId(): Boolean {
    if (all { it.isDigit() }) return true
    val hex = filter { it != '-' }
    return hex.length >= 8 && hex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
}

private fun subtitleOrdinal(tracks: List<MediaTrack>, track: MediaTrack): Int {
    val real = tracks.filter { it.id.lowercase() !in subtitleOffIds }
    val index = real.indexOfFirst { it.id == track.id }.takeIf { it >= 0 } ?: real.lastIndex.coerceAtLeast(0)
    return index + 1
}

private val undeterminedAudioLanguages = setOf("und", "mis", "zxx", "unknown", "undefined", "undetermined")

/** Chip and menu label. Ids stay on the track; only the words change. */
internal fun audioOptionLabel(track: MediaTrack, tracks: List<MediaTrack>): String {
    return readableAudioName(track.name) ?: "Track ${audioOrdinal(tracks, track)}"
}

internal fun readableAudioName(name: String): String? {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return null
    val parts = trimmed.split(' ', '·', '•', '|', '/', ',').map { it.trim() }.filter { it.isNotEmpty() }
    val head = parts.firstOrNull() ?: return null
    val key = head.lowercase()
    if (key in undeterminedAudioLanguages || head.isOpaqueTrackId()) return null
    if (key == "mul" || key == "multiple") {
        val rest = parts.drop(1)
        return if (rest.isEmpty()) "Multiple" else "Multiple • ${rest.joinToString(" • ")}"
    }
    return trimmed
}

private fun audioOrdinal(tracks: List<MediaTrack>, track: MediaTrack): Int {
    val index = tracks.indexOfFirst { it.id == track.id }.takeIf { it >= 0 } ?: tracks.lastIndex.coerceAtLeast(0)
    return index + 1
}

private fun classifySub(t: MediaTrack): SubInfo {
    if (t.id.lowercase() in subtitleOffIds) return SubInfo(OFF_KEY, "Off", "Off")
    val isExternal = t.type == "external_sub" || t.id.startsWith("external_") || t.id.contains("://")
    if (!isExternal) return SubInfo(EMBEDDED_KEY, "Embedded", t.name)

    if (!t.name.contains("OpenSubtitles #")) return SubInfo(REMOTE_KEY, "Phone Remote", t.name.ifBlank { "Remote Subtitle" })

    val segs = t.name.split(" · ", " • ").map { it.trim() }.filter { it.isNotEmpty() }
    val lang = segs.firstNotNullOfOrNull { languageOf(it) }
    return if (lang != null) {
        val rest = segs.filter { languageOf(it) == null }.joinToString(" • ")
        val opt = rest.ifBlank { "Add-on" }
        SubInfo(lang.lowercase(), lang, opt)
    } else {
        SubInfo(EXTERNAL_KEY, "External", t.name.ifBlank { "Subtitle" })
    }
}

internal fun groupSubtitleTracks(tracks: List<MediaTrack>): List<SubGroup> {
    val off = subtitleOffTrack(tracks)
    val byLang = LinkedHashMap<String, Pair<String, MutableList<MediaTrack>>>()

    tracks.filterNot { it.id.lowercase() in subtitleOffIds }.forEach { t ->
        val info = classifySub(t)
        byLang.getOrPut(info.langKey) { info.langDisplay to mutableListOf() }.second.add(t)
    }

    val groups = mutableListOf<SubGroup>()
    groups.add(SubGroup(OFF_KEY, "Off", listOf(off), off.selected))
    byLang[EMBEDDED_KEY]?.let { (display, list) -> groups.add(SubGroup(EMBEDDED_KEY, display, list, list.any { it.selected })) }
    byLang[REMOTE_KEY]?.let { (display, list) -> groups.add(SubGroup(REMOTE_KEY, display, list, list.any { it.selected })) }
    byLang.filterKeys { it != EMBEDDED_KEY && it != REMOTE_KEY && it != EXTERNAL_KEY }.forEach { (k, v) -> groups.add(SubGroup(k, v.first, v.second, v.second.any { it.selected })) }
    byLang[EXTERNAL_KEY]?.let { (display, list) -> groups.add(SubGroup(EXTERNAL_KEY, display, list, list.any { it.selected })) }

    return groups
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubtitlesBottomSheet(
    tracks: List<MediaTrack>,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    val groups = remember(tracks) { groupSubtitleTracks(tracks) }
    val initialGroupKey = remember(groups) {
        groups.firstOrNull { g -> g.tracks.any { it.selected } && g.key != OFF_KEY }?.key
            ?: groups.firstOrNull { it.key != OFF_KEY }?.key
            ?: groups.firstOrNull()?.key
    }
    var selectedGroupKey by remember(groups) { mutableStateOf(initialGroupKey) }
    val currentGroup = groups.firstOrNull { it.key == selectedGroupKey }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        RemoteDialogVolumeKeys()
        Column(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Subtitles", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                TextButton(enabled = LocalRemoteControlsEnabled.current, onClick = onAdd) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add")
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                groups.forEach { group ->
                    val isSelected = selectedGroupKey == group.key
                    FilterChip(enabled = LocalRemoteControlsEnabled.current,
                        selected = isSelected,
                        onClick = {
                            if (group.key == OFF_KEY) {
                                onSelect(group.tracks.first().id)
                                onDismiss()
                            } else {
                                selectedGroupKey = group.key
                            }
                        },
                        label = {
                            val countSuffix = if (group.key != OFF_KEY && group.tracks.isNotEmpty()) " (${group.tracks.size})" else ""
                            Text(group.label + countSuffix)
                        },
                        leadingIcon = if (group.hasSelected) { { Icon(Icons.Default.Check, "Active", Modifier.size(16.dp)) } } else null,
                        shape = RoundedCornerShape(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (currentGroup != null) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(currentGroup.tracks, key = { it.id }) { track ->
                        val isSelected = track.selected
                        val displayLabel = if (currentGroup.key == OFF_KEY) "Off" else subtitleOptionLabel(track, tracks)
                        Surface(enabled = LocalRemoteControlsEnabled.current,
                            onClick = { onSelect(track.id); onDismiss() },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            border = if (isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(displayLabel, style = MaterialTheme.typography.bodyLarge, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal, color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
                                if (isSelected) {
                                    Icon(Icons.Default.Check, "Selected", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
            } else {
                Text(
                    "No subtitle tracks yet. Use Add to attach one without restarting playback.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
        }
    }
}

/** Stage 3d's sole sheet entry. Script/UA sheets keep their existing RemoteEffect host. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RemoteSheets(state: RemoteUiState, dispatch: (RemoteAction) -> Unit, detectedSubtitles: List<DetectedVideo>) {
    val external = state.receiver?.profile == RemoteProfile.External
    val image = state.playback?.mediaKind == RemoteMediaKind.Image
    val audio = state.playback?.mediaKind == RemoteMediaKind.Audio
    val close = { dispatch(RemoteAction.CloseSheet) }
    val layout = resolveRemoteLayout(state, RemoteViewport())
    CompositionLocalProvider(LocalRemoteControlsEnabled provides state.connection.ready) {
        when (state.sheet) {
            RemoteSheet.InputPreferences -> ModalBottomSheet(onDismissRequest = close) {
                RemoteDialogVolumeKeys()
                Column(Modifier.fillMaxWidth().padding(24.dp)) {
                    Text("Pad settings", style = MaterialTheme.typography.titleMedium)
                    ButtonPadPreference(state.preferences.buttonPad) { dispatch(RemoteAction.SetButtonPad(it)) }
                }
            }
            RemoteSheet.More -> PlayerSettingsSheet(
                settings = state.screenSettings(), videoTracks = state.tracks.video.map { it.screenTrack() },
                onSetVideoQuality = { dispatch(RemoteAction.SetVideoQuality(it.takeIf { value -> value > 0 })) },
                onSetSpeed = { dispatch(RemoteAction.SetSpeed(it)) },
                onSetScaling = { dispatch(RemoteAction.SetScaling(ScalingMode.entries.firstOrNull { mode -> mode.wireValue == it } ?: ScalingMode.Fit)) },
                onToggleAudioBoost = { dispatch(RemoteAction.ToggleAudioBoost) },
                onAdjustSubtitleOffset = { dispatch(RemoteAction.AdjustSubtitleOffset(it)) },
                onSwitchEngine = { dispatch(RemoteAction.SwitchEngine(it)) },
                showSettings = !external && !image, showLoop = !external && !image && state.playback?.isLive != true,
                onLoop = { dispatch(RemoteAction.SetLoop(it)) },
                onStop = { dispatch(RemoteAction.StopPlayback); close() },
                showAddSubtitle = !external && !image && !audio,
                showInputPreferences = RemoteInputMode.Pad in layout.modes,
                buttonPad = state.preferences.buttonPad,
                onButtonPad = { dispatch(RemoteAction.SetButtonPad(it)) },
                onAddSubtitle = { dispatch(RemoteAction.OpenSheet(RemoteSheet.AddSubtitle)) }, onDismiss = close,
            )
            RemoteSheet.Keyboard -> if (layout.nav.keys.enabled) RemoteKeyboardSheet(dispatch, close, imeKeys = state.fullControl?.connected == true)
            RemoteSheet.WholeTvControl -> AtvPairingRoute(close)
            RemoteSheet.OpenTvApp -> AtvOpenAppSheet(dispatch, close)
            RemoteSheet.BrowserMore -> BrowserMoreSheet({ dispatch(RemoteCallbackActions.browser(it)) }, close)
            RemoteSheet.Subtitles -> if (!image && !audio) SubtitlesBottomSheet(
                tracks = state.tracks.subtitles.map { it.screenTrack() },
                onSelect = { dispatch(RemoteAction.SelectSubtitle(it)) },
                onAdd = { dispatch(RemoteAction.OpenSheet(RemoteSheet.AddSubtitle)) }, onDismiss = close,
            )
            RemoteSheet.AddSubtitle -> if (!image && !audio) AddSubtitleDialog(
                detectedSubtitles = detectedSubtitles,
                onSubmitResource = { dispatch(RemoteAction.AddSubtitle(it)) },
                onAddResource = { dispatch(RemoteAction.AddSubtitle(it)); true },
                onAddLocalUrl = { dispatch(RemoteAction.AddSubtitle(playbridge.SubtitleResource(url = it), legacyUrlOnly = true)) },
                onDismiss = close,
            )
            RemoteSheet.Queue -> if (layout.showQueue) RemoteQueueSheet(state, dispatch)
            else -> Unit
        }
    }
}

@Composable
private fun ButtonPadPreference(buttonPad: Boolean, onButtonPad: (Boolean) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Button D-pad", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = buttonPad, onCheckedChange = onButtonPad,
            interactionSource = interaction,
            modifier = Modifier.remotePressFeedback(interaction).semantics { contentDescription = "Button D-pad" })
    }
}
