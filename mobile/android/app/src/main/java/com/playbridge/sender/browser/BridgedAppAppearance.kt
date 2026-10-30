package com.playbridge.sender.browser

import java.util.Locale
import kotlin.math.pow

/** Keep ids, order and RGB stops identical to BridgedAppAppearance.swift. */
internal object BridgedAppAppearance {
    data class PaletteColor(val id: String, val name: String, val dark: Int, val light: Int)
    val palette = listOf(
        PaletteColor("pine", "Pine", 0x153D2C, 0x23573F),
        PaletteColor("teal", "Teal", 0x16433F, 0x235E58),
        PaletteColor("olive", "Olive", 0x3E4218, 0x565C24),
        PaletteColor("ochre", "Ochre", 0x513B13, 0x6C501D),
        PaletteColor("brick", "Brick", 0x5E2428, 0x7C353B),
        PaletteColor("cranberry", "Cranberry", 0x581F36, 0x763047),
        PaletteColor("plum", "Plum", 0x46284F, 0x603B6B),
        PaletteColor("indigo", "Indigo", 0x2B335B, 0x404D7B),
        PaletteColor("ocean", "Ocean", 0x193E57, 0x295773),
        PaletteColor("graphite", "Graphite", 0x343840, 0x4B515B),
    )

    fun paletteId(value: String?): String? = palette.find { it.id == value }?.id

    fun paletteIndex(origin: String): Int {
        var hash = 2166136261L
        for (byte in origin.toByteArray(Charsets.UTF_8)) {
            hash = ((hash xor (byte.toInt() and 255).toLong()) * 16777619L) and 0xffffffffL
        }
        return (hash % palette.size).toInt()
    }

    fun parseTheme(value: String?): Int? {
        if (value == null || !Regex("#[0-9a-fA-F]{3}([0-9a-fA-F]{3})?").matches(value)) return null
        val digits = value.drop(1)
        return (if (digits.length == 3) digits.flatMap { listOf(it, it) }.joinToString("") else digits).toInt(16)
    }

    fun themeColor(value: String?): String? = value?.takeIf { parseTheme(it) != null }

    fun contrast(rgb: Int): Double {
        fun linear(channel: Int): Double {
            val value = channel / 255.0
            return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        val luminance = 0.2126 * linear((rgb shr 16) and 255) +
            0.7152 * linear((rgb shr 8) and 255) + 0.0722 * linear(rgb and 255)
        return 1.05 / (luminance + 0.05)
    }

    private fun scale(rgb: Int, factor: Double): Int =
        (((rgb shr 16 and 255) * factor).toInt() shl 16) or
            (((rgb shr 8 and 255) * factor).toInt() shl 8) or ((rgb and 255) * factor).toInt()

    fun themeGradient(rgb: Int): Pair<Int, Int> {
        var light = rgb
        // Integer channel steps keep the clamp deterministic on both platforms.
        while (contrast(light) < 4.5) light = scale(light, 0.99)
        // A small lift gives even a black declaration two distinct stops.
        var lifted = ((light shr 16 and 255).coerceAtLeast(12) shl 16) or
            ((light shr 8 and 255).coerceAtLeast(12) shl 8) or (light and 255).coerceAtLeast(12)
        while (contrast(lifted) < 4.5) lifted = scale(lifted, 0.99)
        return scale(lifted, 0.76) to lifted
    }

    fun gradient(app: BridgedApp): Pair<Int, Int> {
        palette.find { it.id == app.tileColorId }?.let { return it.dark to it.light }
        parseTheme(app.themeColor)?.let { return themeGradient(it) }
        return palette[paletteIndex(app.origin)].let { it.dark to it.light }
    }

    fun iconCandidates(app: BridgedApp): List<String> =
        listOfNotNull(app.iconUrl, "${app.origin}/favicon.ico")
            .filter { BridgedAppStore.originFor(it) == app.origin }.distinct()

    fun <T : Any> selectIcon(app: BridgedApp, load: (String) -> T?): T? = iconCandidates(app).firstNotNullOfOrNull(load)

    fun monogram(name: String): String = name.trim().takeIf { it.isNotEmpty() }
        ?.let { String(Character.toChars(it.codePointAt(0))).uppercase(Locale.ROOT) } ?: "?"
}
