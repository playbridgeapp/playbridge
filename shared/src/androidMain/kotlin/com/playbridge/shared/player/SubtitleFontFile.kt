package com.playbridge.shared.player

import java.io.File

/** The largest custom subtitle font a phone may install; CJK fonts above this are covered by fallback. */
const val MAX_SUBTITLE_FONT_BYTES = 10 * 1024 * 1024

/** Raw bytes per `subtitle_font_chunk`; base64 keeps each frame near 256 KiB. */
const val SUBTITLE_FONT_CHUNK_BYTES = 192 * 1024

/** The directory mpv loads extra fonts from (`sub-fonts-dir`); the custom font lives there too. */
fun subtitleFontsDir(filesDir: File) = File(filesDir, "fonts")

private const val CUSTOM_FONT_PREFIX = "custom-subtitle."

/** The installed custom subtitle font, if any. There is one slot. */
fun customSubtitleFontFile(filesDir: File): File? =
    subtitleFontsDir(filesDir).listFiles { file -> file.name.startsWith(CUSTOM_FONT_PREFIX) }?.firstOrNull()

/**
 * Installs [bytes] as the custom subtitle font, replacing any previous one, and returns its
 * family name. Returns null, leaving the old font in place, when the bytes are not a font.
 */
fun installCustomSubtitleFont(filesDir: File, bytes: ByteArray): String? {
    if (bytes.size > MAX_SUBTITLE_FONT_BYTES) return null
    val family = readFontFamily(bytes) ?: return null
    val extension = when (String(bytes, 0, 4, Charsets.ISO_8859_1)) {
        "OTTO" -> "otf"
        "ttcf" -> "ttc"
        else -> "ttf"
    }
    val dir = subtitleFontsDir(filesDir).apply { mkdirs() }
    val staged = File(dir, ".incoming").apply { writeBytes(bytes) }
    customSubtitleFontFile(filesDir)?.delete()
    if (!staged.renameTo(File(dir, CUSTOM_FONT_PREFIX + extension))) {
        staged.delete()
        return null
    }
    return family
}

/**
 * The family name of a TrueType/OpenType font (the first face of a collection), or null when
 * [bytes] is not a well-formed font. mpv selects fonts by this name.
 */
fun readFontFamily(bytes: ByteArray): String? = runCatching {
    var face = 0
    if (bytes.tag(0) == "ttcf") face = bytes.u32(12).toInt()
    if (bytes.u32(face) != 0x00010000L && bytes.tag(face) !in setOf("OTTO", "true")) return null
    val tables = bytes.u16(face + 4)
    val nameTable = (0 until tables).map { face + 12 + it * 16 }
        .firstOrNull { bytes.tag(it) == "name" }
        ?.let { bytes.u32(it + 8).toInt() }
        ?: return null
    val count = bytes.u16(nameTable + 2)
    val strings = nameTable + bytes.u16(nameTable + 4)
    data class Name(val platform: Int, val language: Int, val id: Int, val value: String)
    val names = (0 until count).mapNotNull { index ->
        val record = nameTable + 6 + index * 12
        val platform = bytes.u16(record)
        val id = bytes.u16(record + 6)
        if (id != 1 && id != 16) return@mapNotNull null
        val start = strings + bytes.u16(record + 10)
        val raw = bytes.copyOfRange(start, start + bytes.u16(record + 8))
        val value = when (platform) {
            0, 3 -> String(raw, Charsets.UTF_16BE)
            1 -> String(raw, Charsets.ISO_8859_1)
            else -> return@mapNotNull null
        }.trim()
        Name(platform, bytes.u16(record + 4), id, value).takeIf { value.isNotEmpty() }
    }
    // The legacy family (ID 1) is what libass matches; English Windows names first.
    names.sortedWith(
        compareBy<Name>({ it.id != 1 }, { it.platform != 3 }, { it.language != 0x409 }),
    ).firstOrNull()?.value?.take(128)
}.getOrNull()

private fun ByteArray.u16(at: Int) = ((this[at].toInt() and 0xFF) shl 8) or (this[at + 1].toInt() and 0xFF)

private fun ByteArray.u32(at: Int) = (u16(at).toLong() shl 16) or u16(at + 2).toLong()

private fun ByteArray.tag(at: Int) = String(this, at, 4, Charsets.ISO_8859_1)
