package com.playbridge.shared.player

import java.io.File
import java.nio.file.Files
import java.util.Properties

/** The largest custom subtitle font a phone may install; CJK fonts above this are covered by fallback. */
const val MAX_SUBTITLE_FONT_BYTES = 10 * 1024 * 1024

/** How many custom subtitle fonts one device keeps. */
const val MAX_SUBTITLE_FONTS = 5

/** Their combined size limit. */
const val MAX_SUBTITLE_FONT_LIBRARY_BYTES = 30 * 1024 * 1024

/** Raw bytes per `subtitle_font_chunk`; base64 keeps each frame near 256 KiB. */
const val SUBTITLE_FONT_CHUNK_BYTES = 192 * 1024

/** The directory mpv loads extra fonts from (`sub-fonts-dir`). */
fun subtitleFontsDir(filesDir: File) = File(filesDir, "fonts")

private const val ACTIVE_FONT_PREFIX = "custom-subtitle."

/**
 * The selected custom font in mpv's font directory, if any. Only the selected font sits there,
 * because libass loads every font in that directory into memory.
 */
fun customSubtitleFontFile(filesDir: File): File? =
    subtitleFontsDir(filesDir).listFiles { file -> file.name.startsWith(ACTIVE_FONT_PREFIX) }?.firstOrNull()

/**
 * The custom subtitle fonts installed on this device, by family name: at most
 * [MAX_SUBTITLE_FONTS] and [MAX_SUBTITLE_FONT_LIBRARY_BYTES] in all. A font with an installed
 * family's name replaces it. [select] links the chosen one into mpv's font directory.
 */
class SubtitleFontLibrary(private val filesDir: File) {
    sealed interface AddResult {
        data class Added(val family: String) : AddResult
        /** [code] is a `subtitle_font_result` error: `too_large`, `invalid_font` or `font_limit`. */
        data class Failed(val code: String) : AddResult
    }

    private val dir = File(filesDir, "subtitle-fonts")
    private val indexFile = File(dir, "index.properties")

    init {
        migrateSingleSlot()
    }

    /** Installed family names, alphabetically. */
    @Synchronized
    fun families(): List<String> = index().stringPropertyNames().sorted()

    /** The file of [family], or null when it isn't installed. */
    @Synchronized
    fun file(family: String): File? = index().getProperty(family)?.let { File(dir, it) }?.takeIf { it.isFile }

    @Synchronized
    fun add(bytes: ByteArray): AddResult {
        if (bytes.size > MAX_SUBTITLE_FONT_BYTES) return AddResult.Failed("too_large")
        val family = readFontFamily(bytes) ?: return AddResult.Failed("invalid_font")
        val index = index()
        val others = index.stringPropertyNames() - family
        val othersBytes = others.sumOf { name -> File(dir, index.getProperty(name)).length() }
        if (others.size >= MAX_SUBTITLE_FONTS || othersBytes + bytes.size > MAX_SUBTITLE_FONT_LIBRARY_BYTES) {
            return AddResult.Failed("font_limit")
        }
        dir.mkdirs()
        val name = "${SubtitleFontReceiver.sha256(family.toByteArray()).take(16)}.${extension(bytes)}"
        val staged = File(dir, ".incoming").apply { writeBytes(bytes) }
        index.getProperty(family)?.let { File(dir, it).delete() }
        if (!staged.renameTo(File(dir, name))) {
            staged.delete()
            return AddResult.Failed("invalid_font")
        }
        index.setProperty(family, name)
        save(index)
        return AddResult.Added(family)
    }

    /** Uninstalls [family]; it stops being the selected font. */
    @Synchronized
    fun remove(family: String): Boolean {
        val index = index()
        val name = index.getProperty(family) ?: return false
        if (selectedName() == name) select(null)
        File(dir, name).delete()
        index.remove(family)
        save(index)
        return true
    }

    /** Puts [family] (or nothing, for null) in mpv's font directory; false when it isn't installed. */
    @Synchronized
    fun select(family: String?): Boolean {
        val source = family?.let(::file)
        if (family != null && source == null) return false
        val fonts = subtitleFontsDir(filesDir)
        val current = customSubtitleFontFile(filesDir)
        if (source != null && current != null && selectedName() == source.name) return true
        current?.delete()
        selectedMarker.delete()
        if (source == null) return true
        fonts.mkdirs()
        val target = File(fonts, ACTIVE_FONT_PREFIX + source.extension)
        // A hard link costs nothing; copy where the file system refuses one.
        runCatching { Files.createLink(target.toPath(), source.toPath()) }
            .onFailure { source.copyTo(target, overwrite = true) }
        selectedMarker.writeText(source.name)
        return true
    }

    // Kept beside the library, not in mpv's font directory, which libass reads in full.
    private val selectedMarker get() = File(dir, ".selected")

    private fun selectedName(): String? = selectedMarker.takeIf { it.isFile }?.readText()

    private fun index() = Properties().apply {
        if (indexFile.isFile) indexFile.reader(Charsets.UTF_8).use(::load)
    }

    private fun save(index: Properties) {
        dir.mkdirs()
        indexFile.writer(Charsets.UTF_8).use { index.store(it, null) }
    }

    /** Devices that had the earlier single font slot keep that font, now in the library. */
    private fun migrateSingleSlot() {
        if (indexFile.isFile) return
        val single = customSubtitleFontFile(filesDir) ?: return
        val result = runCatching { add(single.readBytes()) }.getOrNull()
        if (result is AddResult.Added) select(result.family) else single.delete()
    }

    private fun extension(bytes: ByteArray) = when (String(bytes, 0, 4, Charsets.ISO_8859_1)) {
        "OTTO" -> "otf"
        "ttcf" -> "ttc"
        else -> "ttf"
    }
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
