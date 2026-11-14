package com.playbridge.player.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.playbridge.player.player.SubtitleCueLoader
import com.playbridge.player.ui.components.ListRow
import com.playbridge.player.ui.player.panels.PanelButton
import com.playbridge.player.ui.player.panels.PanelCaption
import com.playbridge.player.ui.player.panels.PanelFocus
import com.playbridge.player.ui.player.panels.PlayerSidePanel
import com.playbridge.player.ui.player.panels.formatSubtitleDelay
import com.playbridge.player.ui.player.panels.initialPanelFocus
import com.playbridge.player.ui.theme.TvText
import com.playbridge.player.ui.theme.TvTheme

private const val OFF_KEY = "__off__"
private const val EMBEDDED_KEY = "__embedded__"
private const val REMOTE_KEY = "__remote__"
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
    if (t.type == "sub" && t.id != "off" && t.id != "none") {
        return SubInfo(EMBEDDED_KEY, "Embedded", t.name)
    }

    if (t.type == "external_sub" && !t.name.contains("OpenSubtitles #")) {
        return SubInfo(REMOTE_KEY, "Phone Remote", t.name.ifBlank { "Remote Subtitle" })
    }

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

private fun isOpenSubtitlesTrack(track: UnifiedTrack): Boolean =
    track.type == "external_sub" && track.name.contains("OpenSubtitles #", ignoreCase = true)

private val NATURAL_NAME_PART = Regex("\\d+|\\D+")

internal fun compareSubtitleOptionNames(left: String, right: String): Int {
    val leftParts = NATURAL_NAME_PART.findAll(left.lowercase()).map { it.value }.toList()
    val rightParts = NATURAL_NAME_PART.findAll(right.lowercase()).map { it.value }.toList()
    for (index in 0 until minOf(leftParts.size, rightParts.size)) {
        val leftPart = leftParts[index]
        val rightPart = rightParts[index]
        val leftNumber = leftPart.toLongOrNull()
        val rightNumber = rightPart.toLongOrNull()
        val compared = if (leftNumber != null && rightNumber != null) {
            leftNumber.compareTo(rightNumber)
        } else {
            leftPart.compareTo(rightPart)
        }
        if (compared != 0) return compared
    }
    return leftParts.size.compareTo(rightParts.size)
}

private fun sortOpenSubtitlesOptions(tracks: List<UnifiedTrack>): List<UnifiedTrack> {
    if (tracks.none(::isOpenSubtitlesTrack)) return tracks
    return tracks.sortedWith { left, right ->
        when {
            isOpenSubtitlesTrack(left) && !isOpenSubtitlesTrack(right) -> 1
            !isOpenSubtitlesTrack(left) && isOpenSubtitlesTrack(right) -> -1
            else -> compareSubtitleOptionNames(optionLabel(left), optionLabel(right))
        }
    }
}

private fun sourceLabel(t: UnifiedTrack): String =
    if (t.type == "external_sub") "EXTERNAL" else "EMBEDDED"

private fun groupSubtitleTracks(tracks: List<UnifiedTrack>): List<SubLangGroup> {
    val off = tracks.firstOrNull { it.id == "off" || it.id == "none" }
    val byLang = LinkedHashMap<String, Pair<String, MutableList<UnifiedTrack>>>()
    tracks.filter { it.id != "off" && it.id != "none" }.forEach { t ->
        val info = classify(t)
        byLang.getOrPut(info.langKey) { info.langDisplay to mutableListOf() }.second.add(t)
    }
    val groups = mutableListOf<SubLangGroup>()
    if (off != null) groups.add(SubLangGroup(OFF_KEY, "Off", listOf(off), off.isSelected))

    // Put "Embedded" group first after "Off" if it exists
    byLang[EMBEDDED_KEY]?.let { (display, list) ->
        groups.add(SubLangGroup(EMBEDDED_KEY, display, list, list.any { it.isSelected }))
    }

    // Put "Phone Remote" group next if it exists
    byLang[REMOTE_KEY]?.let { (display, list) ->
        groups.add(SubLangGroup(REMOTE_KEY, display, list, list.any { it.isSelected }))
    }

    // OpenSubtitles languages are stable and easy to scan regardless of endpoint response order.
    byLang
        .filterKeys { it != EMBEDDED_KEY && it != REMOTE_KEY && it != EXTERNAL_KEY }
        .toList()
        .sortedBy { (_, value) -> value.first.lowercase() }
        .forEach { (key, value) ->
            val sortedTracks = sortOpenSubtitlesOptions(value.second)
            groups.add(SubLangGroup(key, value.first, sortedTracks, sortedTracks.any { it.isSelected }))
        }
    byLang[EXTERNAL_KEY]?.let { (display, list) ->
        groups.add(SubLangGroup(EXTERNAL_KEY, display, list, list.any { it.isSelected }))
    }
    return groups
}

/** One row of the Subtitles panel: [track] with its display [title], optional [note] and [trailing] tag. */
internal data class SubtitleRowModel(
    val track: UnifiedTrack,
    val title: String,
    val note: String?,
    val trailing: String?,
    val isOff: Boolean,
)

private val GENERIC_OPTION_LABELS = setOf("Embedded", "Add-on")

/**
 * Flattens the subtitle tracks into the panel's single list, in the order the language rail used:
 * Off, embedded, phone remote, languages A-Z (add-ons, sorted naturally), then unlabelled external ones.
 */
internal fun subtitleRows(tracks: List<UnifiedTrack>): List<SubtitleRowModel> =
    groupSubtitleTracks(tracks).flatMap { g ->
        g.tracks.map { t ->
            when (g.key) {
                OFF_KEY -> SubtitleRowModel(t, "Off", null, null, isOff = true)
                EMBEDDED_KEY -> SubtitleRowModel(t, t.name, t.secondaryText, null, false)
                REMOTE_KEY -> SubtitleRowModel(t, optionLabel(t), t.secondaryText, "From phone", false)
                EXTERNAL_KEY -> SubtitleRowModel(t, optionLabel(t), t.secondaryText, "From page", false)
                else -> SubtitleRowModel(
                    t, g.label, optionLabel(t).takeUnless { it in GENERIC_OPTION_LABELS },
                    if (t.type == "external_sub") "Online" else null, false,
                )
            }
        }
    }

/** Index of the row focus should start on: the selected track, else the first real subtitle, else Off. */
internal fun subtitleInitialFocusIndex(rows: List<SubtitleRowModel>): Int {
    val selected = rows.indexOfFirst { it.track.isSelected && !it.isOff }
    if (selected >= 0) return selected
    val off = rows.indexOfFirst { it.isOff && it.track.isSelected }
    if (off >= 0) return off
    return 0
}

/**
 * Right-hand Subtitles panel: Off and every subtitle track in one list (embedded, from the phone,
 * online add-ons by language), then "Out of sync? Shift the timing" with Earlier / Later (0.1 s) and
 * Earlier / Later (1 s) and Reset timing. Selection goes through [onTrackSelected]; choosing Off
 * also closes the panel. Focusing an online track preloads it ([onPreloadLanguage]) and shows a
 * line of its dialogue at the frozen scene position, so you can judge sync before choosing.
 *
 * Subtitle style has no screen in the player today (it lives in Settings), so there is no style row.
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
    onResetDelay: () -> Unit = {},
    onDismiss: () -> Unit,
    activeMetadata: playbridge.VisualMetadata? = null,
) {
    // Freeze the scene position when the panel opens so every option is previewed at the same
    // moment; the timing buttons then re-window that frozen position live.
    val frozenPos = remember { previewPositionMs }
    val rows = remember(subtitleTracks) { subtitleRows(subtitleTracks) }
    val focus = remember { PanelFocus(subtitleInitialFocusIndex(rows)) }

    val sourceInfo = activeMetadata?.let { meta ->
        val imdbId = meta.imdb_id?.takeIf { it.isNotBlank() } ?: return@let null
        val s = meta.season
        val e = meta.episode
        if (s != null && s > 0 && e != null && e > 0) {
            "Source: https://opensubtitles-v3.strem.io/subtitles/series/$imdbId:$s:$e.json"
        } else {
            "Source: https://opensubtitles-v3.strem.io/subtitles/movie/$imdbId.json"
        }
    }

    PlayerSidePanel(title = "Subtitles", onDismiss = onDismiss, focus = focus) {
        items(rows.size, key = { rows[it].track.id }) { i ->
            val r = rows[i]
            SubtitleTrackRow(
                row = r,
                frozenPos = frozenPos,
                delayMs = subtitleDelayMs,
                cuesVersion = cuesVersion,
                onFocused = { if (r.track.type == "external_sub") onPreloadLanguage(listOf(r.track.id)) },
                onClick = {
                    onTrackSelected(r.track)
                    if (r.isOff) onDismiss()
                },
                modifier = Modifier.initialPanelFocus(focus, i),
            )
        }
        if (rows.isEmpty()) item(key = "empty") { PanelCaption("No subtitles available") }

        item(key = "timing_caption") {
            PanelCaption("Out of sync? Shift the timing", Modifier.padding(top = 10.dp))
        }
        item(key = "timing_fine") {
            TimingRow(
                earlier = "Earlier", later = "Later",
                center = formatSubtitleDelay(subtitleDelayMs),
                onEarlier = { onAdjustDelay(-100L) }, onLater = { onAdjustDelay(100L) },
            )
        }
        item(key = "timing_coarse") {
            TimingRow(
                earlier = "Earlier 1 s", later = "Later 1 s", center = null,
                onEarlier = { onAdjustDelay(-1000L) }, onLater = { onAdjustDelay(1000L) },
            )
        }
        item(key = "timing_reset") {
            // Absolute zero via its own callback; never derived from the current delay.
            PanelButton(
                "Reset timing", onClick = { if (subtitleDelayMs != 0L) onResetDelay() },
                modifier = Modifier.fillMaxWidth(), height = 52.dp, quiet = true,
            )
        }
        if (sourceInfo != null) item(key = "source") { PanelCaption(sourceInfo) }
    }
}

@Composable
private fun SubtitleTrackRow(
    row: SubtitleRowModel,
    frozenPos: Long,
    delayMs: Long,
    cuesVersion: Int,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val isExternal = row.track.type == "external_sub"
    // Only the focused online row looks up a preview line; it re-windows when the timing changes.
    val previewLine = if (focused && isExternal) {
        val id = row.track.id
        remember(id, cuesVersion, delayMs) {
            SubtitleCueLoader.cached(id)
                ?.let { cues -> SubtitleCueLoader.preview(cues, frozenPos - delayMs).firstOrNull() ?: "No dialogue at this point" }
                ?: "Loading preview…"
        }
    } else null
    ListRow(
        title = row.title,
        subtitle = previewLine ?: row.note,
        trailing = row.trailing,
        selected = row.track.isSelected,
        onClick = onClick,
        modifier = modifier.onFocusChanged {
            if (it.hasFocus != focused) {
                focused = it.hasFocus
                if (it.hasFocus) onFocused()
            }
        },
    )
}

/** [Earlier] [value] [Later]; Left on Later moves to Earlier, Left on Earlier closes the panel. */
@Composable
private fun TimingRow(
    earlier: String,
    later: String,
    center: String?,
    onEarlier: () -> Unit,
    onLater: () -> Unit,
) {
    val earlierFocus = remember { FocusRequester() }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PanelButton(earlier, onEarlier, Modifier.weight(1f).focusRequester(earlierFocus))
        if (center != null) {
            Text(
                center, style = TvText.settingTitle, color = TvTheme.player.onSurface,
                modifier = Modifier.width(110.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                maxLines = 1,
            )
        }
        PanelButton(
            later, onLater,
            Modifier.weight(1f).onKeyEvent { e ->
                if (e.key == Key.DirectionLeft) {
                    if (e.type == KeyEventType.KeyDown) runCatching { earlierFocus.requestFocus() }
                    true
                } else false
            },
        )
    }
}
