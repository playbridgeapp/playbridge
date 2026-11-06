package com.playbridge.shared.player

import java.io.File
import kotlin.math.roundToInt

/** mpv's font size at [SubtitleStyle.Size.MEDIUM], in its scaled 720p units. */
private const val MPV_BASE_FONT_SIZE = 45

/**
 * The mpv options that draw [style]. They work as init options and as live properties.
 * mpv only applies them to plain subtitles unless `sub-ass-override` is `force`.
 */
internal fun mpvSubtitleOptions(style: SubtitleStyle): List<Pair<String, String>> {
    val box = style.background != SubtitleStyle.Background.NONE
    return listOf(
        "sub-font" to "Roboto",
        "sub-font-size" to (MPV_BASE_FONT_SIZE * style.size.scale).roundToInt().toString(),
        "sub-color" to "#FFFFFFFF",
        "sub-border-style" to if (box) "background-box" else "outline-and-shadow",
        "sub-back-color" to when (style.background) {
            SubtitleStyle.Background.NONE -> "#00000000"
            SubtitleStyle.Background.TRANSLUCENT -> "#B3000000"
            SubtitleStyle.Background.SOLID -> "#FF000000"
        },
        "sub-outline-color" to "#FF000000",
        "sub-outline-size" to if (style.edge == SubtitleStyle.Edge.OUTLINE) "2.0" else "0",
        "sub-shadow-color" to "#B3000000",
        "sub-shadow-offset" to when (style.edge) {
            SubtitleStyle.Edge.OUTLINE -> "1.0"
            SubtitleStyle.Edge.SHADOW -> "2.5"
            SubtitleStyle.Edge.NONE -> "0"
        },
        "sub-ass-override" to if (style.overrideStyled) "force" else "scale",
    )
}

/**
 * libass only knows the fonts mpv gives it, so scripts missing from Roboto (Arabic, Hebrew,
 * CJK, Indic…) had no font. Point its fontconfig provider at the device's own fonts: fontconfig
 * opens a font only when a character needs it, and caches the scan under [filesDir].
 * Returns the config file to export as `FONTCONFIG_FILE`, or null when it can't be written.
 */
internal fun writeSystemFontConfig(filesDir: File): File? = runCatching {
    val dir = File(filesDir, "fontconfig").apply { mkdirs() }
    val config = File(dir, "fonts.conf")
    val content = buildString {
        appendLine("""<?xml version="1.0"?>""")
        appendLine("""<!DOCTYPE fontconfig SYSTEM "fonts.dtd">""")
        appendLine("<fontconfig>")
        listOf("/system/fonts", "/product/fonts").filter { File(it).isDirectory }
            .forEach { appendLine("  <dir>$it</dir>") }
        appendLine("  <cachedir>${File(dir, "cache").absolutePath}</cachedir>")
        appendLine("</fontconfig>")
    }
    if (!config.isFile || config.readText() != content) config.writeText(content)
    config
}.getOrNull()
