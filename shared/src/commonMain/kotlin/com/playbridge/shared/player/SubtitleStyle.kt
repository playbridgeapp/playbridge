package com.playbridge.shared.player

/**
 * How plain subtitles look. A receiver stores one style and reports it as the `subtitle_style`
 * frame; senders replace it with a complete style. Each value has a stable wire name, and an
 * unknown or missing name reads as the default, so older and newer senders stay compatible.
 */
data class SubtitleStyle(
    val size: Size = Size.MEDIUM,
    val background: Background = Background.NONE,
    val edge: Edge = Edge.OUTLINE,
    val font: Font = Font.DEFAULT,
    /** Also restyle subtitles that carry their own styling (ASS/SSA, styled WebVTT/TTML). */
    val overrideStyled: Boolean = false,
    /** Family name of the installed custom font; set exactly when [font] is [Font.CUSTOM]. */
    val customFont: String? = null,
) {
    enum class Size(val wire: String, val scale: Float) {
        SMALL("small", 0.8f),
        MEDIUM("medium", 1f),
        LARGE("large", 1.25f),
        EXTRA_LARGE("extra_large", 1.5f),
    }

    /** [opacity] is the black box's alpha; see-through must stay clearly lighter than solid. */
    enum class Background(val wire: String, val opacity: Float) {
        NONE("none", 0f),
        TRANSLUCENT("translucent", 0.5f),
        SOLID("solid", 1f),
    }

    enum class Edge(val wire: String) {
        OUTLINE("outline"),
        SHADOW("shadow"),
        NONE("none"),
    }

    enum class Font(val wire: String) {
        /** The player's font, falling back to the device's fonts for other scripts. */
        DEFAULT("default"),
        /** The font the user installed; characters it lacks still fall back to the device's fonts. */
        CUSTOM("custom"),
    }

    companion object {
        val DEFAULT = SubtitleStyle()

        /** Builds a style from wire names; anything unknown or missing uses the default. */
        fun fromWire(
            size: String?,
            background: String?,
            edge: String?,
            font: String?,
            overrideStyled: Boolean?,
            customFont: String? = null,
        ): SubtitleStyle {
            // A custom font without its name can't be drawn, so it reads as the default font.
            val name = customFont?.trim()?.takeIf { it.isNotEmpty() }?.take(128)
            val chosen = Font.entries.firstOrNull { it.wire == font }
                ?.takeUnless { it == Font.CUSTOM && name == null } ?: DEFAULT.font
            return SubtitleStyle(
                size = Size.entries.firstOrNull { it.wire == size } ?: DEFAULT.size,
                background = Background.entries.firstOrNull { it.wire == background } ?: DEFAULT.background,
                edge = Edge.entries.firstOrNull { it.wire == edge } ?: DEFAULT.edge,
                font = chosen,
                overrideStyled = overrideStyled ?: DEFAULT.overrideStyled,
                customFont = name.takeIf { chosen == Font.CUSTOM },
            )
        }
    }
}
