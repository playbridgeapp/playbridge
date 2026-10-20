package com.playbridge.sender.cast.remote.ui

import com.playbridge.sender.cast.remote.RemoteCapabilities
import com.playbridge.sender.cast.remote.PlayerSetting
import com.playbridge.sender.cast.remote.Support
import com.playbridge.sender.cast.remote.BrowserAction
import com.playbridge.sender.cast.remote.receiverActionSupported
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
import com.playbridge.sender.cast.remote.RemoteCommandResult
import com.playbridge.sender.cast.remote.RemoteDialogVolumeKeys
import com.playbridge.sender.cast.remote.RemoteMediaKind
import com.playbridge.sender.cast.remote.RemoteKeyboardRoute
import com.playbridge.sender.cast.remote.RemoteProfile
import com.playbridge.sender.cast.remote.RemoteSheet
import com.playbridge.sender.cast.remote.RemoteUiState
import com.playbridge.sender.cast.remote.RemoteViewport
import com.playbridge.sender.cast.remote.ScalingMode
import com.playbridge.sender.cast.remote.fullControlReady
import com.playbridge.sender.cast.remote.keyboardRoute
import com.playbridge.sender.cast.remote.ready
import com.playbridge.sender.cast.remote.resolveRemoteLayout
import com.playbridge.sender.cast.subtitleOffIds
import com.playbridge.sender.cast.subtitleOffTrack
import com.playbridge.sender.cast.subtitleResourceForDetected
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BrowserMoreSheet(onBrowserControl: (String) -> Unit, onDismiss: () -> Unit, supported: (RemoteAction) -> Boolean = { true }) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        RemoteDialogVolumeKeys()
        Text("More controls", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 24.dp, bottom = 4.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (supported(RemoteAction.Browser(BrowserAction.CycleSource))) LabeledIconButton(icon = Icons.Default.Layers, label = "Source", tint = MaterialTheme.colorScheme.onSurfaceVariant, onClick = { onBrowserControl("video_target_cycle"); onDismiss() })
            if (supported(RemoteAction.Browser(BrowserAction.Unmute))) LabeledIconButton(icon = Icons.AutoMirrored.Filled.VolumeUp, label = "Unmute", tint = MaterialTheme.colorScheme.onSurfaceVariant, onClick = { onBrowserControl("video_unmute"); onDismiss() })
            if (supported(RemoteAction.ManageUserScripts)) LabeledIconButton(icon = Icons.Default.Code, label = "Scripts", tint = MaterialTheme.colorScheme.onSurfaceVariant, onClick = { onBrowserControl("manage_user_scripts"); onDismiss() })
            if (supported(RemoteAction.ManageUserAgent)) LabeledIconButton(icon = Icons.Default.Language, label = "User Agent", tint = MaterialTheme.colorScheme.onSurfaceVariant, onClick = { onBrowserControl("manage_user_agent"); onDismiss() })
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
    onSwitchEngine: (String) -> Unit,
    showSettings: Boolean,
    showLoop: Boolean,
    loopEnabled: Boolean?,
    onLoop: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    capabilities: RemoteCapabilities? = null,
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

            // 1. Playback group: Speed, Loop, Stop
            val showSpeed = showSettings && settings.speedAvailable
            if (showSpeed || showLoop) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                    if (loopEditorUsesSwitch(loopEnabled)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Loop",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                enabled = LocalRemoteControlsEnabled.current,
                                checked = loopEnabled == true,
                                onCheckedChange = onLoop,
                            )
                        }
                    } else {
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
            val showEngine = showSettings && (capabilities?.let { PlayerSetting.Engine in it.settings } ?: (settings.engine != null))
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
                                options = capabilities?.engines?.map { id -> (when (id) { "exo" -> "ExoPlayer"; "avplayer" -> "AVPlayer"; else -> "MPV" }) to id } ?: listOf("ExoPlayer" to "exo", "MPV" to "mpv"),
                                selectedKey = settings.engine,
                                onSelect = onSwitchEngine,
                            )
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

internal fun languageOf(segment: String): String? = segment.trim().lowercase().let { LANG_TOKENS[it] ?: LOCALE_LANGUAGE_NAMES[it] }

// Full English language names Java knows ("catalan", "tamil", …) for tracks the table above misses.
// Names only, not codes: short codes collide with ordinary words.
private val LOCALE_LANGUAGE_NAMES: Map<String, String> by lazy {
    java.util.Locale.getISOLanguages().mapNotNull { code ->
        java.util.Locale(code).getDisplayLanguage(java.util.Locale.ENGLISH).takeIf { it.length >= 4 && it != code }
    }.associateBy { it.lowercase() }
}

/** Chip and sheet label. A numeric or hex id from the TV is not shown; the track id itself is unchanged. */
internal fun subtitleOptionLabel(track: MediaTrack, tracks: List<MediaTrack>): String =
    if (track.id.lowercase() in subtitleOffIds) "Off" else subtitleOptionLabels(tracks)[track.id] ?: baseSubtitleLabel(track, tracks)

/** Labels for every real track by id, in one pass. Same words on several tracks get numbered in list order. */
internal fun subtitleOptionLabels(tracks: List<MediaTrack>): Map<String, String> {
    val real = tracks.filter { it.id.lowercase() !in subtitleOffIds }
    val bases = real.map { it.id to baseSubtitleLabel(it, tracks) }
    val totals = bases.groupingBy { it.second }.eachCount()
    val seen = HashMap<String, Int>()
    return bases.associate { (id, base) ->
        id to if (totals.getValue(base) > 1) "$base ${seen.merge(base, 1, Int::plus)}" else base
    }
}

private fun baseSubtitleLabel(track: MediaTrack, tracks: List<MediaTrack>): String {
    if (track.id.lowercase() in subtitleOffIds) return "Off"
    readableSubtitleName(track.name)?.let { return it }
    if (subtitleOrigin(track) == SubtitleOrigin.BuiltIn) return "Subtitle ${subtitleOrdinal(tracks, track)}"
    // An added track named only by an id: describe its link instead, else number it among added tracks.
    // Duplicate "Added" labels are numbered by subtitleOptionLabels.
    return addedSubtitleLinkLabel(track.id) ?: "Added"
}

// Two-letter tokens that are usually ordinary path words rather than language codes.
private val ambiguousPathTokens = setOf("id", "it", "no", "he", "hi", "do", "is", "to")

/** Language named in a subtitle link's path, e.g. `/subs/eng/…` or `movie.en.srt`. */
internal fun subtitleLinkLanguage(url: String): String? {
    val path = runCatching { java.net.URI(url.substringBefore('#')).path }.getOrNull() ?: return null
    return path.split('/', '.', '_', '-').firstNotNullOfOrNull { token ->
        token.takeIf { it.isNotBlank() && it.lowercase() !in ambiguousPathTokens }?.let(::languageOf)
    }
}

/** "subs5.strem.io" → "strem.io"; keeps three labels for hosts like "subs.example.co.uk". */
internal fun shortHost(host: String): String {
    val labels = host.lowercase().split('.').filter { it.isNotEmpty() }
    val keep = if (labels.size >= 3 && labels.last().length == 2 && labels[labels.size - 2].length <= 3) 3 else 2
    return labels.takeLast(keep).joinToString(".")
}

/** "movie.en" / "English · subs.example.com" / "subs.example.com", from a link; null when it says nothing readable. */
internal fun addedSubtitleLinkLabel(url: String): String? {
    url.substringAfter('#', "").takeIf { it.isNotBlank() }
        ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
        ?.let(::readableSubtitleName)?.let { return it }
    val uri = runCatching { java.net.URI(url.substringBefore('#')) }.getOrNull() ?: return null
    val file = uri.path.orEmpty().substringAfterLast('/')
        .let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
        .substringBeforeLast('.')
        .takeIf { name -> name.length >= 3 && name.any(Char::isLetter) && !name.isOpaqueTrackId() }
    if (file != null) return file
    val host = uri.host?.takeUnless { it == "localhost" || it.all { c -> c.isDigit() || c == '.' } || it.contains(':') }?.let(::shortHost)
    return listOfNotNull(subtitleLinkLanguage(url), host).joinToString(" · ").ifBlank { null }
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
    // A segment that is only a language code reads as its name: "eng · OpenSubtitles v3" → "English · OpenSubtitles v3".
    val segments = trimmed.split(labelSeparators)
    if (segments.size > 1 && segments.any { it.length <= 3 && languageOf(it) != null }) {
        return segments.joinToString(" · ") { segment -> if (segment.length <= 3) languageOf(segment) ?: segment else segment }
    }
    return trimmed
}

private val labelSeparators = Regex("\\s*[·•|]\\s*")

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

/**
 * Whether More would show anything. Stop and Add subtitle moved out of More, so a receiver with no
 * player settings (e.g. desktop) must not get an empty sheet. Mirrors [PlayerSettingsSheet]'s rows.
 */
internal fun playerSettingsHaveContent(state: RemoteUiState): Boolean {
    val external = state.receiver?.profile == RemoteProfile.External
    val image = state.playback?.mediaKind == RemoteMediaKind.Image
    val show = !external && !image
    val settings = state.screenSettings()
    val loop = show && state.playback?.isLive != true && (state.nativePolicy == null || PlayerSetting.Loop in state.capabilities.settings)
    val quality = settings.qualityAvailable && state.tracks.video.count { it.id != "auto" } > 1
    val engine = state.nativePolicy?.let { PlayerSetting.Engine in state.capabilities.settings } ?: (settings.engine != null)
    return loop || show && (settings.speedAvailable || quality || settings.scalingAvailable || settings.audioBoostAvailable || engine)
}

/** A reported loop boolean draws the switch. A missing field keeps Enable and Disable. */
internal fun loopEditorUsesSwitch(loopEnabled: Boolean?): Boolean = loopEnabled != null

/** Stage 3d's sole sheet entry. Script/UA sheets keep their existing RemoteEffect host. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RemoteSheets(
    state: RemoteUiState,
    dispatch: (RemoteAction) -> Unit,
    detectedSubtitles: List<DetectedVideo>,
    execute: suspend (RemoteAction) -> RemoteCommandResult = { action -> dispatch(action); RemoteCommandResult.Submitted },
) {
    val external = state.receiver?.profile == RemoteProfile.External
    val image = state.playback?.mediaKind == RemoteMediaKind.Image
    val audio = state.playback?.mediaKind == RemoteMediaKind.Audio
    val close = { dispatch(RemoteAction.CloseSheet) }
    val canAddSubtitle = (state.nativePolicy == null || state.capabilities.tracks.addUrl == Support.Supported || state.capabilities.tracks.addResource == Support.Supported)
    val layout = resolveRemoteLayout(state, RemoteViewport())
    val enabled = state.connection.ready || state.sheet == RemoteSheet.Keyboard && layout.nav.keys.enabled ||
        state.sheet == RemoteSheet.OpenTvApp && state.fullControlReady
    CompositionLocalProvider(LocalRemoteControlsEnabled provides enabled) {
        when (state.sheet) {
            RemoteSheet.More -> if (layout.tracks.more && playerSettingsHaveContent(state)) PlayerSettingsSheet(
                settings = state.screenSettings(), videoTracks = state.tracks.video.map { it.screenTrack() },
                onSetVideoQuality = { dispatch(RemoteAction.SetVideoQuality(it.takeIf { value -> value > 0 })) },
                onSetSpeed = { dispatch(RemoteAction.SetSpeed(it)) },
                onSetScaling = { dispatch(RemoteAction.SetScaling(ScalingMode.entries.firstOrNull { mode -> mode.wireValue == it } ?: ScalingMode.Fit)) },
                onToggleAudioBoost = { dispatch(RemoteAction.ToggleAudioBoost) },
                onSwitchEngine = { dispatch(RemoteAction.SwitchEngine(it)) },
                showSettings = !external && !image, showLoop = !external && !image && state.playback?.isLive != true && (state.nativePolicy == null || PlayerSetting.Loop in state.capabilities.settings),
                loopEnabled = state.settings.loopEnabled,
                onLoop = { dispatch(RemoteAction.SetLoop(it)) },
                onDismiss = close,
                capabilities = state.capabilities.takeIf { state.nativePolicy != null },
            )
            RemoteSheet.Keyboard -> if (layout.nav.keys.enabled || state.nativePolicy != null && state.connection.ready) {
                val route = remember { state.keyboardRoute() }
                val context = remember { state.context }
                val receiverText = state.fullControl?.receiverText ?: state.capabilities.text
                RemoteKeyboardSheet(
                    dispatch = { dispatch(RemoteAction.KeyboardInput(it, route, context)) },
                    onDismiss = close,
                    imeKeys = route == RemoteKeyboardRoute.FullControl,
                    canReplace = route == RemoteKeyboardRoute.FullControl || receiverText.replace == Support.Supported,
                    canEnter = route == RemoteKeyboardRoute.FullControl || state.nativePolicy == null || receiverText.enter == Support.Supported ||
                        state.nativePolicy.operations.keys.contains("key_enter"),
                    execute = { execute(RemoteAction.KeyboardInput(it, route, context)) },
                )
            }
            RemoteSheet.WholeTvControl -> AtvPairingRoute(close)
            RemoteSheet.OpenTvApp -> AtvOpenAppSheet(dispatch, close)
            RemoteSheet.BrowserMore -> BrowserMoreSheet({ dispatch(RemoteCallbackActions.browser(it)) }, close, state::receiverActionSupported)
            // One call site for both states keeps the same sheet open when switching to the Add page.
            RemoteSheet.Subtitles, RemoteSheet.AddSubtitle -> if (!image && !audio && (layout.tracks.subtitles || state.sheet == RemoteSheet.AddSubtitle && canAddSubtitle)) SubtitlesSheet(
                tracks = if (state.nativePolicy == null || state.capabilities.tracks.subtitle == Support.Supported) state.tracks.subtitles.map { it.screenTrack() } else emptyList(),
                addPage = state.sheet == RemoteSheet.AddSubtitle,
                onShowAdd = { dispatch(RemoteAction.OpenSheet(if (it) RemoteSheet.AddSubtitle else RemoteSheet.Subtitles)) },
                onSelect = { dispatch(RemoteAction.SelectSubtitle(it)) },
                onDismiss = close,
                showSelection = state.nativePolicy == null || state.capabilities.tracks.subtitle == Support.Supported,
                canAdd = canAddSubtitle,
                detectedSubtitles = if (state.nativePolicy == null || state.capabilities.tracks.addResource == Support.Supported) detectedSubtitles else detectedSubtitles.filter { subtitleResourceForDetected(it).headers.isEmpty() },
                onAddResource = { dispatch(RemoteAction.AddSubtitle(it)) },
                onAddLocalUrl = { dispatch(RemoteAction.AddSubtitle(playbridge.SubtitleResource(url = it), legacyUrlOnly = state.nativePolicy == null || state.capabilities.tracks.addResource != Support.Supported)) },
                offsetMs = state.screenSettings().subtitleOffsetMs.takeIf {
                    !external && (state.nativePolicy == null || PlayerSetting.SubtitleOffset in state.capabilities.settings)
                },
                onAdjustOffset = { dispatch(RemoteAction.AdjustSubtitleOffset(it)) },
            )
            RemoteSheet.Queue -> if (layout.showQueue) RemoteQueueSheet(state, dispatch)
            else -> Unit
        }
    }
}
