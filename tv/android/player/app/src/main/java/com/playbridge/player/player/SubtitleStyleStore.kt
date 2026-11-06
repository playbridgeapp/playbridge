package com.playbridge.player.player

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import com.playbridge.shared.player.SubtitleStyle
import com.playbridge.shared.protocol.createSubtitleStyleJson
import com.playbridge.shared.protocol.parseSubtitleStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * The TV's subtitle style, changed from TV Settings or by a paired phone (`subtitle_style`).
 * Main process only: the mpv renderer process receives it over its binder.
 */
internal class SubtitleStyleStore private constructor(private val prefs: SharedPreferences) {
    private val mutableStyle = MutableStateFlow(read())
    val style: StateFlow<SubtitleStyle> = mutableStyle.asStateFlow()

    /** Saves [style]; returns false when it was already the current style. */
    fun set(style: SubtitleStyle): Boolean {
        if (style == mutableStyle.value) return false
        prefs.edit().putString(KEY, createSubtitleStyleJson(style)).apply()
        mutableStyle.value = style
        return true
    }

    private fun read(): SubtitleStyle = prefs.getString(KEY, null)?.let(::parseSubtitleStyleJson) ?: SubtitleStyle.DEFAULT

    companion object {
        private const val KEY = "subtitle_style"

        @Volatile private var instance: SubtitleStyleStore? = null

        fun get(context: Context): SubtitleStyleStore = instance ?: synchronized(this) {
            instance ?: SubtitleStyleStore(
                context.applicationContext.getSharedPreferences("subtitle_style", Context.MODE_PRIVATE),
            ).also { instance = it }
        }
    }
}

/** Reads a style sent over the renderer binder; a malformed one is the default. */
internal fun parseSubtitleStyleJson(json: String): SubtitleStyle =
    runCatching { parseSubtitleStyle(Json.parseToJsonElement(json).jsonObject) }.getOrDefault(SubtitleStyle.DEFAULT)

/** ExoPlayer's caption renderer: [style] for plain cues, embedded styles unless overridden. */
internal fun SubtitleView.applySubtitleStyle(style: SubtitleStyle) {
    setApplyEmbeddedStyles(!style.overrideStyled)
    setApplyEmbeddedFontSizes(!style.overrideStyled)
    setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * style.size.scale)
    setStyle(
        CaptionStyleCompat(
            Color.WHITE,
            when (style.background) {
                SubtitleStyle.Background.NONE -> Color.TRANSPARENT
                SubtitleStyle.Background.TRANSLUCENT -> Color.argb(0xB3, 0, 0, 0)
                SubtitleStyle.Background.SOLID -> Color.BLACK
            },
            Color.TRANSPARENT,
            when (style.edge) {
                SubtitleStyle.Edge.OUTLINE -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
                SubtitleStyle.Edge.SHADOW -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
                SubtitleStyle.Edge.NONE -> CaptionStyleCompat.EDGE_TYPE_NONE
            },
            Color.BLACK,
            null as Typeface?,
        ),
    )
}
