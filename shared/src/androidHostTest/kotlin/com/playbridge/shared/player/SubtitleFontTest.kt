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
    fun receivedFontIsCheckedAndInstalledInOneSlot() {
        val filesDir = Files.createTempDirectory("font").toFile()
        try {
            val bytes = font("Tajawal") + ByteArray(SUBTITLE_FONT_CHUNK_BYTES) // spans two chunks
            val receiver = SubtitleFontReceiver(filesDir)
            assertEquals(SubtitleFontReceiver.Result.Ack(0), receiver.begin("t1", bytes.size, SubtitleFontReceiver.sha256(bytes)))
            var offset = 0
            while (offset < bytes.size) {
                val end = minOf(offset + SUBTITLE_FONT_CHUNK_BYTES, bytes.size)
                val chunk = Base64.getEncoder().encodeToString(bytes.copyOfRange(offset, end))
                assertEquals(SubtitleFontReceiver.Result.Ack(end), receiver.chunk("t1", offset, chunk))
                offset = end
            }
            assertEquals(SubtitleFontReceiver.Result.Installed("Tajawal"), receiver.end("t1"))
            assertEquals("custom-subtitle.ttf", customSubtitleFontFile(filesDir)?.name)

            // A replacement takes the slot; a non-font leaves the installed one alone.
            assertEquals("Amiri", installCustomSubtitleFont(filesDir, font("Amiri", version = 0x4F54544F)))
            assertEquals(listOf("custom-subtitle.otf"), subtitleFontsDir(filesDir).list()?.toList())
            assertNull(installCustomSubtitleFont(filesDir, "not a font".toByteArray()))
            assertEquals(listOf("custom-subtitle.otf"), subtitleFontsDir(filesDir).list()?.toList())
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun badTransfersFail() {
        val filesDir = Files.createTempDirectory("font").toFile()
        try {
            val receiver = SubtitleFontReceiver(filesDir)
            assertEquals(SubtitleFontReceiver.Result.Failed("too_large"), receiver.begin("big", MAX_SUBTITLE_FONT_BYTES + 1, "00"))

            val bytes = font("Cairo")
            receiver.begin("t", bytes.size, SubtitleFontReceiver.sha256(bytes))
            // Out of order: the transfer is dropped.
            val late = Base64.getEncoder().encodeToString(bytes.copyOfRange(4, bytes.size))
            assertEquals(SubtitleFontReceiver.Result.Failed("invalid_chunk"), receiver.chunk("t", 4, late))
            assertEquals(SubtitleFontReceiver.Result.Failed("unknown_transfer"), receiver.end("t"))

            // Wrong checksum, then a well-formed transfer of something that isn't a font.
            receiver.begin("t", bytes.size, "0".repeat(64))
            receiver.chunk("t", 0, Base64.getEncoder().encodeToString(bytes))
            assertEquals(SubtitleFontReceiver.Result.Failed("checksum_mismatch"), receiver.end("t"))
            val text = "not a font".toByteArray()
            receiver.begin("u", text.size, SubtitleFontReceiver.sha256(text))
            receiver.chunk("u", 0, Base64.getEncoder().encodeToString(text))
            assertEquals(SubtitleFontReceiver.Result.Failed("invalid_font"), receiver.end("u"))
            assertNull(customSubtitleFontFile(filesDir))
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
