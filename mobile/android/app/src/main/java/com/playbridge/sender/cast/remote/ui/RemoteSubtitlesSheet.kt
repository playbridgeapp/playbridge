package com.playbridge.sender.cast.remote.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.playbridge.sender.cast.DetectedVideo
import com.playbridge.sender.cast.MediaTrack
import com.playbridge.sender.cast.VideoDetector
import com.playbridge.sender.cast.newestSubtitlesFirst
import com.playbridge.sender.cast.publishLocalSubtitle
import com.playbridge.sender.cast.remote.RemoteDialogVolumeKeys
import com.playbridge.sender.cast.subtitleOffIds
import com.playbridge.sender.cast.subtitleOffTrack
import com.playbridge.sender.cast.subtitleResourceForDetected
import java.util.Locale
import kotlin.math.abs

internal enum class SubtitlePage(val label: String) { Tracks("Tracks"), Adjust("Adjust"), Add("Add") }

/** Where a subtitle track came from; the Tracks page filters by it. */
internal enum class SubtitleOrigin(val filter: String, val tag: String) {
    BuiltIn("Built-in", "built-in"), AddOn("Add-ons", "add-on"), Added("Added", "added"),
}

internal enum class SubtitleAddSource(val label: String) { Detected("Found on page"), File("Phone file"), Link("Link") }

internal data class SubtitleRow(val track: MediaTrack, val label: String, val origin: SubtitleOrigin, val language: String?)

/** Lists longer than this get a search field. */
internal const val SUBTITLE_SEARCH_THRESHOLD = 8

internal fun subtitleOrigin(track: MediaTrack): SubtitleOrigin {
    val external = track.type == "external_sub" || track.id.startsWith("external_") || track.id.contains("://")
    return when {
        !external -> SubtitleOrigin.BuiltIn
        track.name.contains("OpenSubtitles #") -> SubtitleOrigin.AddOn
        // Stremio's subtitle add-ons serve from *.strem.io.
        runCatching { java.net.URI(track.id.substringBefore('#')).host }.getOrNull()?.let { it == "strem.io" || it.endsWith(".strem.io") } == true -> SubtitleOrigin.AddOn
        else -> SubtitleOrigin.Added
    }
}

internal fun subtitleLanguage(track: MediaTrack): String? =
    track.name.split(' ', '·', '•', '|', '/', ',', '(', ')', '[', ']').firstNotNullOfOrNull { segment -> segment.takeIf { it.isNotBlank() }?.let(::languageOf) }
        ?: if (subtitleOrigin(track) != SubtitleOrigin.BuiltIn) subtitleLinkLanguage(track.id) else null

/** Every real track (Off excluded), in receiver order. */
internal fun subtitleRows(tracks: List<MediaTrack>): List<SubtitleRow> {
    val labels = subtitleOptionLabels(tracks)
    return tracks.filterNot { it.id.lowercase() in subtitleOffIds }.map { track ->
        SubtitleRow(track, labels.getValue(track.id), subtitleOrigin(track), subtitleLanguage(track))
    }
}

/** Origin filters that have tracks, with counts; All is implied and only worth showing with two or more. */
internal fun subtitleOriginFilters(rows: List<SubtitleRow>): List<Pair<SubtitleOrigin, Int>> =
    SubtitleOrigin.entries.mapNotNull { origin -> rows.count { it.origin == origin }.takeIf { it > 0 }?.let { origin to it } }

/**
 * Language filters for a list: the phone's language, then the current track's, then the rest by
 * track count and name. Tracks without a language don't get one.
 */
internal fun subtitleLanguageFilters(rows: List<SubtitleRow>, preferred: List<String?> = emptyList()): List<Pair<String, Int>> {
    val counts = rows.mapNotNull { it.language }.groupingBy { it }.eachCount()
    val first = preferred.filterNotNull().distinct().filter { it in counts }
    return first.map { it to counts.getValue(it) } +
        counts.entries.filter { it.key !in first }.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { it.key to it.value }
}

/** The phone's language in the names [languageOf] uses ("English", "Spanish", …). */
internal fun phoneSubtitleLanguage(locale: Locale = Locale.getDefault()): String? =
    languageOf(locale.language) ?: languageOf(locale.getDisplayLanguage(Locale.ENGLISH))

internal fun filterSubtitleRows(rows: List<SubtitleRow>, origin: SubtitleOrigin?, language: String?, query: String): List<SubtitleRow> {
    val needle = query.trim()
    return rows.filter { row ->
        (origin == null || row.origin == origin) && (language == null || row.language == language) &&
            (needle.isEmpty() || row.label.contains(needle, ignoreCase = true) || row.track.name.contains(needle, ignoreCase = true))
    }
}

/** "+0.3 s" / "−1.25 s" / "Synced". */
internal fun subtitleOffsetLabel(offsetMs: Long): String {
    if (offsetMs == 0L) return "Synced"
    val seconds = String.format(Locale.US, "%.2f", abs(offsetMs) / 1000.0).trimEnd('0').trimEnd('.')
    return (if (offsetMs > 0) "+" else "−") + seconds + " s"
}

/** "−0.5" / "+0.1": the value above shows the unit, so the buttons stay short. */
internal fun subtitleOffsetStepLabel(stepMs: Long): String =
    (if (stepMs > 0) "+" else "−") + subtitleOffsetLabel(abs(stepMs)).removePrefix("+").removeSuffix(" s")

private val SUBTITLE_OFFSET_STEPS = listOf(-500L, -100L, 100L, 500L)

/**
 * One sheet with three pages picked by chips: Tracks (filtered by origin and, for long add-on
 * lists, language), Adjust (timing), and Add (found on page / phone file / link). The Add page
 * is the [com.playbridge.sender.cast.remote.RemoteSheet.AddSubtitle] state, so the view model's
 * add handling returns here to Tracks once a subtitle is accepted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubtitlesSheet(
    tracks: List<MediaTrack>,
    addPage: Boolean,
    onShowAdd: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    showSelection: Boolean,
    canAdd: Boolean,
    detectedSubtitles: List<DetectedVideo>,
    onAddResource: (playbridge.SubtitleResource) -> Unit,
    onAddLocalUrl: (String) -> Unit,
    offsetMs: Long?,
    onAdjustOffset: (Long) -> Unit,
) {
    var adjust by rememberSaveable { mutableStateOf(false) }
    val pages = buildList {
        if (showSelection) add(SubtitlePage.Tracks)
        if (offsetMs != null) add(SubtitlePage.Adjust)
        if (canAdd) add(SubtitlePage.Add)
    }
    val page = when {
        addPage && canAdd -> SubtitlePage.Add
        adjust && offsetMs != null -> SubtitlePage.Adjust
        else -> pages.firstOrNull { it != SubtitlePage.Add } ?: pages.firstOrNull() ?: SubtitlePage.Tracks
    }
    val current = tracks.firstOrNull { it.selected && it.id.lowercase() !in subtitleOffIds }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        RemoteDialogVolumeKeys()
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 24.dp).testTag("remote.subtitlesSheet")) {
            Text("Subtitles", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            // Its own full-width line: the end of a label (often a number) is what tells tracks apart.
            if (showSelection) Text(
                if (current != null) "On: ${subtitleOptionLabel(current, tracks)}" else "Off",
                style = MaterialTheme.typography.bodyMedium,
                color = if (current != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("remote.subtitles.current"),
            )
            Spacer(Modifier.height(12.dp))
            if (pages.size > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pages.forEach { option ->
                        FilterChip(
                            enabled = LocalRemoteControlsEnabled.current,
                            selected = option == page,
                            onClick = {
                                adjust = option == SubtitlePage.Adjust
                                onShowAdd(option == SubtitlePage.Add)
                            },
                            label = { Text(option.label, fontWeight = FontWeight.SemiBold) },
                            leadingIcon = {
                                Icon(
                                    when (option) {
                                        SubtitlePage.Tracks -> Icons.Default.Subtitles
                                        SubtitlePage.Adjust -> Icons.Default.Tune
                                        SubtitlePage.Add -> Icons.Default.Add
                                    },
                                    contentDescription = null, modifier = Modifier.size(18.dp),
                                )
                            },
                            shape = RoundedCornerShape(20.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                            modifier = Modifier.testTag("remote.subtitles.page.${option.name}"),
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            // Every page gets the same height so switching chips doesn't resize the sheet.
            val pageHeight = (LocalConfiguration.current.screenHeightDp * 0.6f).dp.coerceIn(320.dp, 560.dp)
            Column(Modifier.fillMaxWidth().height(pageHeight)) {
                when (page) {
                    SubtitlePage.Tracks -> TracksPage(tracks, onSelect)
                    SubtitlePage.Adjust -> AdjustPage(offsetMs ?: 0L, onAdjustOffset)
                    SubtitlePage.Add -> AddPage(detectedSubtitles, onAddResource, onAddLocalUrl)
                }
            }
        }
    }
}

/** Small outlined chips: the second level, inside a page. */
@Composable
private fun SubFilterRow(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun SubFilter(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    FilterChip(
        enabled = LocalRemoteControlsEnabled.current,
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.height(32.dp).testTag(tag),
    )
}

@Composable
private fun ColumnScope.TracksPage(tracks: List<MediaTrack>, onSelect: (String) -> Unit) {
    val rows = remember(tracks) { subtitleRows(tracks) }
    val origins = remember(rows) { subtitleOriginFilters(rows) }
    var origin by rememberSaveable { mutableStateOf<SubtitleOrigin?>(null) }
    if (origin != null && origins.none { it.first == origin }) origin = null
    val byOrigin = remember(rows, origin) { filterSubtitleRows(rows, origin, null, "") }
    // Language chips only help when a long add-on list mixes languages.
    val current = remember(rows) { rows.firstOrNull { it.track.selected } }
    val languages = remember(byOrigin, current?.language) {
        subtitleLanguageFilters(byOrigin, listOf(phoneSubtitleLanguage(), current?.language)).takeIf { it.size > 1 && byOrigin.size > SUBTITLE_SEARCH_THRESHOLD }.orEmpty()
    }
    var language by rememberSaveable { mutableStateOf<String?>(null) }
    if (language != null && languages.none { it.first == language }) language = null
    var query by rememberSaveable { mutableStateOf("") }
    val searchable = byOrigin.size > SUBTITLE_SEARCH_THRESHOLD
    val shown = remember(byOrigin, language, query, searchable) { filterSubtitleRows(byOrigin, null, language, if (searchable) query else "") }
    val off = remember(tracks) { subtitleOffTrack(tracks) }

    if (rows.isEmpty()) {
        Text(
            "No subtitle tracks yet. Use Add to attach one without restarting playback.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )
    }
    if (origins.size > 1) {
        SubFilterRow {
            SubFilter("All ${rows.size}", origin == null, "remote.subtitles.origin.All") { origin = null }
            origins.forEach { (value, count) ->
                SubFilter("${value.filter} $count", origin == value, "remote.subtitles.origin.${value.name}") { origin = value }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
    if (languages.isNotEmpty()) {
        SubFilterRow {
            languages.forEach { (value, count) ->
                SubFilter("$value $count", language == value, "remote.subtitles.language.$value") { language = if (language == value) null else value }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
    if (searchable) {
        CompactSearchField(query, { query = it }, "Search subtitles", Modifier.testTag("remote.subtitles.search"))
        Spacer(Modifier.height(8.dp))
    }
    // Open on the current track (one row of context above it) rather than at the top of a long list.
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        val index = shown.indexOfFirst { it.track.selected }
        if (index >= 0) listState.scrollToItem(index) // Off is item 0, so this leaves one row above.
    }
    LazyColumn(Modifier.fillMaxWidth().weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "off") { TrackRow("Off", null, off.selected) { onSelect(off.id) } }
        items(shown, key = { it.track.id }) { row ->
            TrackRow(row.label, row.origin.tag.takeIf { origin == null && origins.size > 1 }, row.track.selected) { onSelect(row.track.id) }
        }
        if (shown.isEmpty() && rows.isNotEmpty()) item(key = "empty") {
            Text("No matches", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
        }
    }
}

/** A 40 dp search field; the stock text field's 56 dp crowds the list on a phone. */
@Composable
private fun CompactSearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), modifier = modifier.fillMaxWidth().height(40.dp)) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(8.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                BasicTextField(
                    value = value, onValueChange = onValueChange, singleLine = true,
                    enabled = LocalRemoteControlsEnabled.current,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = placeholder },
                )
            }
            if (value.isNotEmpty()) Icon(
                Icons.Default.Close, contentDescription = "Clear search",
                modifier = Modifier.size(18.dp).clickable { onValueChange("") },
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TrackRow(label: String, tag: String?, selected: Boolean, onClick: () -> Unit) {
    Surface(
        enabled = LocalRemoteControlsEnabled.current,
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label, style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // The selected row drops its tag so the check mark doesn't push the label's distinguishing end out.
            if (tag != null && !selected) Text(tag, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
            if (selected) Icon(Icons.Default.Check, "Selected", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp).size(20.dp))
        }
    }
}

@Composable
private fun AdjustPage(offsetMs: Long, onAdjust: (Long) -> Unit) = Column(Modifier.fillMaxWidth()) {
    Text("Timing", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(12.dp))
    Text(
        subtitleOffsetLabel(offsetMs),
        style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold,
        color = if (offsetMs != 0L) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.align(Alignment.CenterHorizontally).testTag("remote.subtitles.offset"),
    )
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SUBTITLE_OFFSET_STEPS.forEach { step ->
            FilledTonalButton(
                enabled = LocalRemoteControlsEnabled.current,
                onClick = { onAdjust(step) },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 4.dp),
            ) { Text(subtitleOffsetStepLabel(step), maxLines = 1) }
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "If subtitles are out of sync with speech, nudge them until they line up.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(enabled = LocalRemoteControlsEnabled.current && offsetMs != 0L, onClick = { onAdjust(-offsetMs) }) { Text("Reset") }
    }
}

@Composable
private fun ColumnScope.AddPage(
    detectedSubtitles: List<DetectedVideo>,
    onAddResource: (playbridge.SubtitleResource) -> Unit,
    onAddLocalUrl: (String) -> Unit,
) {
    val context = LocalContext.current
    val detected = remember(detectedSubtitles) { newestSubtitlesFirst(detectedSubtitles) }
    val sources = remember(detected.isNotEmpty()) {
        if (detected.isNotEmpty()) SubtitleAddSource.entries.toList() else SubtitleAddSource.entries.filterNot { it == SubtitleAddSource.Detected }
    }
    var source by rememberSaveable(sources.size) { mutableStateOf(sources.first()) }
    var url by rememberSaveable { mutableStateOf("") }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) publishLocalSubtitle(context, uri)?.let { (localUrl, _) -> onAddLocalUrl(localUrl) }
    }

    SubFilterRow {
        sources.forEach { value ->
            val label = if (value == SubtitleAddSource.Detected) "${value.label} ${detected.size}" else value.label
            SubFilter(label, source == value, "remote.subtitles.add.${value.name}") { source = value }
        }
    }
    Spacer(Modifier.height(12.dp))
    when (source) {
        SubtitleAddSource.Detected -> LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(detected, key = { it.url }) { subtitle -> DetectedSubtitleRow(subtitle) { onAddResource(subtitleResourceForDetected(subtitle)) } }
        }
        SubtitleAddSource.File -> Column {
            Text("Choose a subtitle file from this phone. The TV loads it through the phone.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(
                enabled = LocalRemoteControlsEnabled.current,
                onClick = { filePicker.launch("*/*") },
                modifier = Modifier.fillMaxWidth().testTag("remote.subtitles.chooseFile"),
            ) { Text("Choose file") }
        }
        SubtitleAddSource.Link -> {
            val entered = url.trim()
            val valid = entered.startsWith("https://", true) || entered.startsWith("http://", true)
            OutlinedTextField(
                enabled = LocalRemoteControlsEnabled.current,
                value = url, onValueChange = { url = it },
                label = { Text("Subtitle link (.srt / .vtt)") },
                isError = entered.isNotEmpty() && !valid,
                supportingText = if (entered.isNotEmpty() && !valid) { { Text("Enter an http:// or https:// link") } } else null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("remote.subtitles.link"),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                enabled = LocalRemoteControlsEnabled.current && valid,
                onClick = { onAddResource(playbridge.SubtitleResource(url = entered)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Add link") }
        }
    }
}

@Composable
private fun DetectedSubtitleRow(subtitle: DetectedVideo, onClick: () -> Unit) {
    var preview by remember(subtitle.url) { mutableStateOf(subtitle.subtitlePreview) }
    var language by remember(subtitle.url) { mutableStateOf(subtitle.subtitleLanguage) }
    LaunchedEffect(subtitle.url) {
        preview = VideoDetector.fetchSubtitlePreview(subtitle)
        language = subtitle.subtitleLanguage
    }
    Surface(
        enabled = LocalRemoteControlsEnabled.current,
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    subtitle.title ?: subtitle.url.substringBefore('?').substringAfterLast('/'),
                    style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                language?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp)) }
            }
            preview?.takeIf(String::isNotBlank)?.let {
                Text(
                    "“$it”", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                "Found " + java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(subtitle.timestamp)),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
