@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.playbridge.player.ui.player

import androidx.activity.compose.BackHandler
import com.playbridge.player.player.SubtitleCueLoader
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

private const val OFF_KEY = "__off__"
private const val EXTERNAL_KEY = "__external__"

private data class SubLangGroup(
    val key: String,
    val label: String,
    val tracks: List<UnifiedTrack>,
    val hasSelected: Boolean,
)

// token (lowercased language name or ISO code) -> display name. Used to decide whether a
// subtitle entry carries a real language. Library/embedded subs do (e.g. "English · BluRay",
// "English • EN"); browser-detected subs are sniffed .vtt/.srt URLs with NO language, so their
// label is just a filename — those collapse into a single "External" group rather than one
// group per filename.
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

private fun languageOf(segment: String): String? = LANG_TOKENS[segment.trim().lowercase()]

private data class SubInfo(val langKey: String, val langDisplay: String, val optionLabel: String)

/** Decide a subtitle's language group + option label. No recognizable language → External. */
private fun classify(t: UnifiedTrack): SubInfo {
    val segs = t.name.split(" · ", " • ").map { it.trim() }.filter { it.isNotEmpty() }
    val lang = segs.firstNotNullOfOrNull { languageOf(it) }
    return if (lang != null) {
        val rest = segs.filter { languageOf(it) == null }.joinToString(" • ")
        val opt = rest.ifBlank { if (t.type == "external_sub") "Add-on" else "Embedded" }
        SubInfo(lang.lowercase(), lang, opt)
    } else {
        // Browser-detected / unlabeled: bucket together; the filename distinguishes options.
        SubInfo(EXTERNAL_KEY, "External", t.name.ifBlank { "Subtitle" })
    }
}

private fun optionLabel(t: UnifiedTrack): String = classify(t).optionLabel

private fun sourceLabel(t: UnifiedTrack): String =
    if (t.type == "external_sub") "EXTERNAL" else "EMBEDDED"

private fun groupSubtitleTracks(tracks: List<UnifiedTrack>): List<SubLangGroup> {
    val off = tracks.firstOrNull { it.id == "off" }
    val byLang = LinkedHashMap<String, Pair<String, MutableList<UnifiedTrack>>>()
    tracks.filter { it.id != "off" }.forEach { t ->
        val info = classify(t)
        byLang.getOrPut(info.langKey) { info.langDisplay to mutableListOf() }.second.add(t)
    }
    val groups = mutableListOf<SubLangGroup>()
    if (off != null) groups.add(SubLangGroup(OFF_KEY, "Off", listOf(off), off.isSelected))
    // Real languages first (encounter order), the External bucket last.
    byLang.filterKeys { it != EXTERNAL_KEY }.forEach { (k, v) ->
        groups.add(SubLangGroup(k, v.first, v.second, v.second.any { it.isSelected }))
    }
    byLang[EXTERNAL_KEY]?.let { (display, list) ->
        groups.add(SubLangGroup(EXTERNAL_KEY, display, list, list.any { it.isSelected }))
    }
    return groups
}

/**
 * Fullscreen, NuvioTV-style subtitle overlay: Language rail → Options for that language → Sync.
 * Reads the unified subtitle track list (Off + embedded + external) already produced by the
 * player activities, so selection routes through the existing [onTrackSelected] path.
 */
@Composable
fun SubtitleSelectionOverlay(
    subtitleTracks: List<UnifiedTrack>,
    subtitleDelayMs: Long,
    previewPositionMs: Long,
    cuesVersion: Int,
    onPreloadLanguage: (List<String>) -> Unit,
    onTrackSelected: (UnifiedTrack) -> Unit,
    onAdjustDelay: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler { onDismiss() }

    // Freeze the scene position when the overlay opens so every option is previewed at the same
    // moment; the offset slider then re-windows that frozen position live.
    val frozenPos = remember { previewPositionMs }

    val groups = remember(subtitleTracks) { groupSubtitleTracks(subtitleTracks) }
    val initialLangKey = remember(groups) {
        groups.firstOrNull { g -> g.tracks.any { it.isSelected } && g.key != OFF_KEY }?.key
            ?: groups.firstOrNull { it.key != OFF_KEY }?.key
            ?: groups.firstOrNull()?.key
    }
    var selectedLangKey by remember(groups) { mutableStateOf(initialLangKey) }
    val currentGroup = groups.firstOrNull { it.key == selectedLangKey }

    val initialFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { initialFocus.requestFocus() } }

    // Fetch previews only for the selected language's external subs (lazy, cached).
    LaunchedEffect(selectedLangKey, groups) {
        currentGroup?.takeIf { it.key != OFF_KEY }?.let { g ->
            onPreloadLanguage(g.tracks.filter { it.type == "external_sub" }.map { it.id })
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.62f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 48.dp, end = 48.dp, top = 40.dp, bottom = 56.dp),
            verticalArrangement = Arrangement.Top
        ) {
            Text(
                text = "Subtitles",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                modifier = Modifier.padding(bottom = 14.dp)
            )

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                // ---- Language rail ----
                RailColumn(title = "Language", width = 220.dp) {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.heightIn(max = 460.dp)
                    ) {
                        items(groups, key = { it.key }) { g ->
                            SubCard(
                                title = g.label,
                                meta = null,
                                source = null,
                                trailingCount = (g.tracks.size).takeIf { g.key != OFF_KEY && it > 0 },
                                checked = g.hasSelected,
                                highlighted = g.key == selectedLangKey,
                                focusRequester = if (g.key == initialLangKey) initialFocus else null,
                                onFocused = { selectedLangKey = g.key },
                                onClick = {
                                    if (g.key == OFF_KEY) {
                                        onTrackSelected(g.tracks.first())
                                        onDismiss()
                                    } else {
                                        selectedLangKey = g.key
                                    }
                                }
                            )
                        }
                    }
                }

                // ---- Options rail ----
                RailColumn(title = "Subtitles", width = 380.dp) {
                    if (currentGroup == null || currentGroup.key == OFF_KEY) {
                        EmptyRailCard("Subtitles off")
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.heightIn(max = 460.dp)
                        ) {
                            items(currentGroup.tracks, key = { it.id }) { t ->
                                val isExternal = t.type == "external_sub"
                                // Re-windows when the offset (subtitleDelayMs) or cache version changes.
                                val preview = remember(t.id, cuesVersion, subtitleDelayMs) {
                                    if (!isExternal) null
                                    else SubtitleCueLoader.cached(t.id)
                                        ?.let { SubtitleCueLoader.preview(it, frozenPos + subtitleDelayMs) }
                                }
                                val loading = isExternal && SubtitleCueLoader.cached(t.id) == null
                                SubCard(
                                    title = optionLabel(t),
                                    meta = null,
                                    source = sourceLabel(t),
                                    trailingCount = null,
                                    checked = t.isSelected,
                                    highlighted = t.isSelected,
                                    previewLines = preview,
                                    loading = loading,
                                    focusRequester = null,
                                    onFocused = {},
                                    onClick = { onTrackSelected(t) }
                                )
                            }
                        }
                    }
                }

                // ---- Sync rail ----
                RailColumn(title = "Sync", width = 250.dp) {
                    SyncControl(delayMs = subtitleDelayMs, onAdjust = onAdjustDelay)
                }
            }
        }
    }
}

@Composable
private fun RailColumn(title: String, width: androidx.compose.ui.unit.Dp, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.width(width),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = Color.White.copy(alpha = 0.6f)
        )
        content()
    }
}

@Composable
private fun SubCard(
    title: String,
    meta: String?,
    source: String?,
    trailingCount: Int?,
    checked: Boolean,
    highlighted: Boolean,
    focusRequester: FocusRequester?,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    previewLines: List<String>? = null,
    loading: Boolean = false,
) {
    Surface(
        onClick = onClick,
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                             else Color.White.copy(alpha = 0.06f),
            focusedContainerColor = MaterialTheme.colorScheme.primary,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { if (it.isFocused) onFocused() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (source != null) {
                    Text(
                        text = source,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                }
                // Preview mode (subtitle options): show the cue lines at the current scene
                // instead of a filename, so you can eyeball which one is in sync.
                val showPreview = loading || previewLines != null
                if (showPreview) {
                    when {
                        loading -> Text(
                            text = "Loading preview…",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.55f)
                        )
                        previewLines!!.isEmpty() -> Text(
                            text = "— no dialogue at this point —",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.45f)
                        )
                        else -> previewLines.forEachIndexed { i, line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = if (i == 0) 0.95f else 0.55f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                } else {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!meta.isNullOrBlank()) {
                        Text(
                            text = meta,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.6f)
                        )
                    }
                }
            }
            if (checked) {
                Text(text = "●", color = Color(0xFF00D9FF))
            } else if (trailingCount != null) {
                Text(
                    text = trailingCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun EmptyRailCard(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 14.dp)
    ) {
        Text(text = text, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.6f))
    }
}

@Composable
private fun SyncControl(delayMs: Long, onAdjust: (Long) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = if (delayMs == 0L) "Synced" else "${if (delayMs > 0) "+" else ""}${delayMs} ms",
            style = MaterialTheme.typography.headlineSmall,
            color = if (delayMs != 0L) Color(0xFF00D9FF) else Color.White,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
        SyncRow(label = "Fine", minus = "−100ms", plus = "+100ms", step = 100L, onAdjust = onAdjust)
        SyncRow(label = "Coarse", minus = "−1s", plus = "+1s", step = 1000L, onAdjust = onAdjust)
        if (delayMs != 0L) {
            Button(
                onClick = { onAdjust(-delayMs) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.colors(
                    containerColor = Color.White.copy(alpha = 0.1f),
                    contentColor = Color.White
                )
            ) { Text("Reset", style = MaterialTheme.typography.labelMedium) }
        }
    }
}

@Composable
private fun SyncRow(label: String, minus: String, plus: String, step: Long, onAdjust: (Long) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = { onAdjust(-step) },
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.colors(
                containerColor = Color.White.copy(alpha = 0.1f),
                contentColor = Color.White
            )
        ) { Text(minus, style = MaterialTheme.typography.labelSmall) }
        Button(
            onClick = { onAdjust(step) },
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.colors(
                containerColor = Color.White.copy(alpha = 0.1f),
                contentColor = Color.White
            )
        ) { Text(plus, style = MaterialTheme.typography.labelSmall) }
    }
}
