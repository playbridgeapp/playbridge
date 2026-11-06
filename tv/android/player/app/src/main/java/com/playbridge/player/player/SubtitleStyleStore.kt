package com.playbridge.player.player

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import com.playbridge.shared.player.SubtitleStyle
import com.playbridge.shared.player.SubtitleFontLibrary
import com.playbridge.shared.protocol.createSubtitleStyleJson
import com.playbridge.shared.protocol.parseSubtitleStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * The TV's subtitle style, changed from TV Settings or by a paired phone (`subtitle_style`), and
 * its custom fonts, installed and removed by a phone (`subtitle_font_*`). Main process only: the
 * mpv renderer process receives the style over its binder and loads the selected font from the
 * shared fonts directory.
 */
internal class SubtitleStyleStore private constructor(
    private val prefs: SharedPreferences,
    filesDir: java.io.File,
) {
    // Before the style: normalising it reads the installed fonts.
    private val fonts = SubtitleFontLibrary(filesDir)
    private val mutableInstalledFonts = MutableStateFlow(fonts.families())
    /** Installed custom font families, alphabetically. */
    val installedFonts: StateFlow<List<String>> = mutableInstalledFonts.asStateFlow()

    private val mutableStyle = MutableStateFlow(normalize(read()).also { fonts.select(it.customFont) })
    val style: StateFlow<SubtitleStyle> = mutableStyle.asStateFlow()

    private val mutableFontRevision = MutableStateFlow(0)
    /** Changes when a font file replaces one of the same name. */
    val fontRevision: StateFlow<Int> = mutableFontRevision.asStateFlow()

    /** Saves [style] and puts its font in place for mpv; returns false when nothing changed. */
    fun set(style: SubtitleStyle): Boolean {
        val normalized = normalize(style)
        if (normalized == mutableStyle.value) return false
        fonts.select(normalized.customFont)
        prefs.edit().putString(KEY, createSubtitleStyleJson(normalized)).apply()
        mutableStyle.value = normalized
        return true
    }

    /** Adds a phone's font and selects it; the code of `subtitle_font_result` on failure. */
    fun addFont(bytes: ByteArray): SubtitleFontLibrary.AddResult {
        val result = fonts.add(bytes)
        if (result is SubtitleFontLibrary.AddResult.Added) {
            mutableInstalledFonts.value = fonts.families()
            mutableFontRevision.value++
            // The file may have replaced the selected one, so it is linked again either way.
            fonts.select(null)
            if (!set(mutableStyle.value.copy(font = SubtitleStyle.Font.CUSTOM, customFont = result.family))) {
                fonts.select(result.family)
            }
        }
        return result
    }

    /** Uninstalls [family]; a style that used it goes back to the default font. */
    fun removeFont(family: String) {
        if (!fonts.remove(family)) return
        mutableInstalledFonts.value = fonts.families()
        set(mutableStyle.value)
    }

    /** The selected custom font for ExoPlayer and the overlay, or null for their default font. */
    fun typeface(style: SubtitleStyle): Typeface? =
        style.customFont?.let(fonts::file)?.let { runCatching { Typeface.createFromFile(it) }.getOrNull() }

    /** A custom font must be installed here; otherwise the style uses the default font. */
    private fun normalize(style: SubtitleStyle): SubtitleStyle =
        if (style.font != SubtitleStyle.Font.CUSTOM || style.customFont in fonts.families()) style
        else style.copy(font = SubtitleStyle.Font.DEFAULT, customFont = null)

    private fun read(): SubtitleStyle = prefs.getString(KEY, null)?.let(::parseSubtitleStyleJson) ?: SubtitleStyle.DEFAULT

    companion object {
        private const val KEY = "subtitle_style"

        @Volatile private var instance: SubtitleStyleStore? = null

        fun get(context: Context): SubtitleStyleStore = instance ?: synchronized(this) {
            instance ?: SubtitleStyleStore(
                context.applicationContext.getSharedPreferences("subtitle_style", Context.MODE_PRIVATE),
                context.applicationContext.filesDir,
            ).also { instance = it }
        }
    }
}

/** Reads a style sent over the renderer binder; a malformed one is the default. */
internal fun parseSubtitleStyleJson(json: String): SubtitleStyle =
    runCatching { parseSubtitleStyle(Json.parseToJsonElement(json).jsonObject) }.getOrDefault(SubtitleStyle.DEFAULT)

/** ExoPlayer's caption renderer: [style] for plain cues, embedded styles unless overridden. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun SubtitleView.applySubtitleStyle(style: SubtitleStyle, typeface: Typeface? = null) {
    setApplyEmbeddedStyles(!style.overrideStyled)
    setApplyEmbeddedFontSizes(!style.overrideStyled)
    setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * style.size.scale)
    setStyle(
        CaptionStyleCompat(
            Color.WHITE,
            Color.argb((style.background.opacity * 255).toInt(), 0, 0, 0),
            Color.TRANSPARENT,
            when (style.edge) {
                SubtitleStyle.Edge.OUTLINE -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
                SubtitleStyle.Edge.SHADOW -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
                SubtitleStyle.Edge.NONE -> CaptionStyleCompat.EDGE_TYPE_NONE
            },
            Color.BLACK,
            typeface,
        ),
    )
}
