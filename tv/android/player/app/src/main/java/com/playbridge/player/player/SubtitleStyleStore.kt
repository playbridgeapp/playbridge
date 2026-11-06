package com.playbridge.player.player

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import com.playbridge.shared.player.SubtitleStyle
import com.playbridge.shared.player.customSubtitleFontFile
import com.playbridge.shared.protocol.createSubtitleStyleJson
import com.playbridge.shared.protocol.parseSubtitleStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * The TV's subtitle style, changed from TV Settings or by a paired phone (`subtitle_style`), and
 * its custom font, installed by a phone (`subtitle_font_*`). Main process only: the mpv renderer
 * process receives the style over its binder and loads the font from the shared fonts directory.
 */
internal class SubtitleStyleStore private constructor(
    private val prefs: SharedPreferences,
    private val filesDir: java.io.File,
) {
    private val mutableStyle = MutableStateFlow(normalize(read()))
    val style: StateFlow<SubtitleStyle> = mutableStyle.asStateFlow()

    private val mutableFontRevision = MutableStateFlow(0)
    /** Changes when a new custom font file replaces the old one, even under the same name. */
    val fontRevision: StateFlow<Int> = mutableFontRevision.asStateFlow()

    /** The installed custom font's family name, or null when there is none. */
    val customFontFamily: String?
        get() = prefs.getString(FONT_KEY, null)?.takeIf { customSubtitleFontFile(filesDir) != null }

    /** Saves [style]; returns false when it was already the current style. */
    fun set(style: SubtitleStyle): Boolean {
        val normalized = normalize(style)
        if (normalized == mutableStyle.value) return false
        prefs.edit().putString(KEY, createSubtitleStyleJson(normalized)).apply()
        mutableStyle.value = normalized
        return true
    }

    /** A phone installed font [family]: select it. */
    fun customFontInstalled(family: String) {
        prefs.edit().putString(FONT_KEY, family).apply()
        mutableFontRevision.value++
        set(mutableStyle.value.copy(font = SubtitleStyle.Font.CUSTOM, customFont = family))
    }

    /** The custom font for ExoPlayer and the overlay, or null for their default font. */
    fun typeface(style: SubtitleStyle): Typeface? =
        if (style.font != SubtitleStyle.Font.CUSTOM) null
        else customSubtitleFontFile(filesDir)?.let { runCatching { Typeface.createFromFile(it) }.getOrNull() }

    /** The custom font can only be the one installed here; without one it is the default. */
    private fun normalize(style: SubtitleStyle): SubtitleStyle {
        if (style.font != SubtitleStyle.Font.CUSTOM) return style
        val family = customFontFamily ?: return style.copy(font = SubtitleStyle.Font.DEFAULT, customFont = null)
        return style.copy(customFont = family)
    }

    private fun read(): SubtitleStyle = prefs.getString(KEY, null)?.let(::parseSubtitleStyleJson) ?: SubtitleStyle.DEFAULT

    companion object {
        private const val KEY = "subtitle_style"
        private const val FONT_KEY = "custom_font_family"

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
