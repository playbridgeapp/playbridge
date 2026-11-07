package com.playbridge.shared.player

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubtitleFontTest {
    /** A minimal sfnt with only a `name` table holding [family] as a Windows ID 1 name. */
    private fun font(family: String, version: Int = 0x00010000): ByteArray {
        val name = family.toByteArray(Charsets.UTF_16BE)
        val nameTable = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).apply {
                writeShort(0); writeShort(1); writeShort(6 + 12)
                writeShort(3); writeShort(1); writeShort(0x409); writeShort(1); writeShort(name.size); writeShort(0)
                write(name)
            }
        }.toByteArray()
        return ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).apply {
                writeInt(version); writeShort(1); writeShort(16); writeShort(0); writeShort(0)
                writeBytes("name"); writeInt(0); writeInt(12 + 16); writeInt(nameTable.size)
                write(nameTable)
            }
        }.toByteArray()
    }

    @Test
    fun readsTheFamilyOfTrueTypeAndOpenTypeFonts() {
        assertEquals("Noto Naskh Arabic", readFontFamily(font("Noto Naskh Arabic")))
        assertEquals("Cairo", readFontFamily(font("Cairo", version = 0x4F54544F))) // "OTTO"
        assertNull(readFontFamily("1\n00:00:01,000 --> 00:00:02,000\nnot a font".toByteArray()))
        assertNull(readFontFamily(ByteArray(3)))
    }

    @Test
    fun receivedFontIsCheckedAndAddedToTheLibrary() {
        val filesDir = Files.createTempDirectory("font").toFile()
        try {
            val library = SubtitleFontLibrary(filesDir)
            val bytes = font("Tajawal") + ByteArray(SUBTITLE_FONT_CHUNK_BYTES) // spans two chunks
            val receiver = SubtitleFontReceiver(library::add)
            assertEquals(SubtitleFontReceiver.Result.Ack(0), receiver.begin("t1", bytes.size, SubtitleFontReceiver.sha256(bytes)))
            var offset = 0
            while (offset < bytes.size) {
                val end = minOf(offset + SUBTITLE_FONT_CHUNK_BYTES, bytes.size)
                val chunk = Base64.getEncoder().encodeToString(bytes.copyOfRange(offset, end))
                assertEquals(SubtitleFontReceiver.Result.Ack(end), receiver.chunk("t1", offset, chunk))
                offset = end
            }
            assertEquals(SubtitleFontReceiver.Result.Installed("Tajawal"), receiver.end("t1"))
            assertEquals(listOf("Tajawal"), library.families())
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun libraryKeepsFiveFontsAndLinksOnlyTheSelectedOneForMpv() {
        val filesDir = Files.createTempDirectory("font").toFile()
        try {
            val library = SubtitleFontLibrary(filesDir)
            listOf("Amiri", "Cairo", "Lato", "Noto Sans", "Tajawal").forEach {
                assertEquals(SubtitleFontLibrary.AddResult.Added(it), library.add(font(it)))
            }
            assertEquals(SubtitleFontLibrary.AddResult.Failed("font_limit"), library.add(font("Ubuntu")))
            // The same family replaces its file, so it never counts twice.
            assertEquals(SubtitleFontLibrary.AddResult.Added("Cairo"), library.add(font("Cairo", version = 0x4F54544F)))
            assertEquals(SubtitleFontLibrary.AddResult.Failed("invalid_font"), library.add("not a font".toByteArray()))
            assertEquals(listOf("Amiri", "Cairo", "Lato", "Noto Sans", "Tajawal"), library.families())

            assertTrue(library.select("Cairo"))
            assertEquals(listOf("custom-subtitle.otf"), subtitleFontsDir(filesDir).list()?.toList())
            assertTrue(library.select("Lato"))
            assertEquals(listOf("custom-subtitle.ttf"), subtitleFontsDir(filesDir).list()?.toList())
            assertEquals(false, library.select("Ubuntu"))

            // Removing the selected font clears mpv's copy; a new font then fits.
            assertTrue(library.remove("Lato"))
            assertNull(customSubtitleFontFile(filesDir))
            assertEquals(SubtitleFontLibrary.AddResult.Added("Ubuntu"), library.add(font("Ubuntu")))
            assertTrue(library.select(null))
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun theTotalSizeIsCapped() {
        val filesDir = Files.createTempDirectory("font").toFile()
        try {
            val library = SubtitleFontLibrary(filesDir)
            val nineMb = ByteArray(9 * 1024 * 1024)
            listOf("A", "B", "C").forEach { assertEquals(SubtitleFontLibrary.AddResult.Added(it), library.add(font(it) + nineMb)) }
            assertEquals(SubtitleFontLibrary.AddResult.Failed("font_limit"), library.add(font("D") + nineMb))
            assertEquals(SubtitleFontLibrary.AddResult.Added("D"), library.add(font("D")))
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun theEarlierSingleFontMovesIntoTheLibrary() {
        val filesDir = Files.createTempDirectory("font").toFile()
        try {
            subtitleFontsDir(filesDir).mkdirs()
            java.io.File(subtitleFontsDir(filesDir), "custom-subtitle.ttf").writeBytes(font("Cairo"))
            val library = SubtitleFontLibrary(filesDir)
            assertEquals(listOf("Cairo"), library.families())
            assertEquals("custom-subtitle.ttf", customSubtitleFontFile(filesDir)?.name)
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun customFontNeedsItsName() {
        val custom = SubtitleStyle.fromWire("medium", "none", "outline", "custom", false, customFont = "Cairo")
        assertEquals(SubtitleStyle.Font.CUSTOM, custom.font)
        assertEquals("Cairo", custom.customFont)
        assertTrue(mpvSubtitleOptions(custom).contains("sub-font" to "Cairo"))
        val nameless = SubtitleStyle.fromWire("medium", "none", "outline", "custom", false)
        assertEquals(SubtitleStyle.DEFAULT, nameless)
    }
}
