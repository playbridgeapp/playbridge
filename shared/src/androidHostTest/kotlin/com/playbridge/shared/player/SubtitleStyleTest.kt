package com.playbridge.shared.player

import com.playbridge.shared.protocol.IncomingMessage
import com.playbridge.shared.protocol.createSubtitleStyleJson
import com.playbridge.shared.protocol.parseIncomingMessage
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubtitleStyleTest {
    @Test
    fun wireFrameRoundTrips() {
        val style = SubtitleStyle(
            size = SubtitleStyle.Size.EXTRA_LARGE,
            background = SubtitleStyle.Background.TRANSLUCENT,
            edge = SubtitleStyle.Edge.SHADOW,
            overrideStyled = true,
        )
        val parsed = parseIncomingMessage(createSubtitleStyleJson(style))
        assertEquals(IncomingMessage.SubtitleStyle(style), parsed)
        assertEquals(IncomingMessage.SubtitleStyleQuery, parseIncomingMessage("""{"type":"subtitle_style_query"}"""))
    }

    @Test
    fun unknownAndMissingValuesUseTheDefaults() {
        val parsed = parseIncomingMessage(
            """{"type":"subtitle_style","size":"huge","background":"solid","font":"custom"}""",
        )
        assertEquals(
            IncomingMessage.SubtitleStyle(SubtitleStyle(background = SubtitleStyle.Background.SOLID)),
            parsed,
        )
    }

    @Test
    fun mpvOptionsFollowTheStyle() {
        val plain = mpvSubtitleOptions(SubtitleStyle.DEFAULT).toMap()
        assertEquals("45", plain["sub-font-size"])
        assertEquals("outline-and-shadow", plain["sub-border-style"])
        assertEquals("2.0", plain["sub-outline-size"])
        assertEquals("scale", plain["sub-ass-override"])

        val boxed = mpvSubtitleOptions(
            SubtitleStyle(
                size = SubtitleStyle.Size.LARGE,
                background = SubtitleStyle.Background.SOLID,
                edge = SubtitleStyle.Edge.NONE,
                overrideStyled = true,
            ),
        ).toMap()
        assertEquals("56", boxed["sub-font-size"])
        assertEquals("background-box", boxed["sub-border-style"])
        assertEquals("#FF000000", boxed["sub-back-color"])
        assertEquals("0", boxed["sub-outline-size"])
        assertEquals("0", boxed["sub-shadow-offset"])
        assertEquals("force", boxed["sub-ass-override"])
    }

    @Test
    fun fontConfigPointsLibassAtCachedSystemFonts() {
        val filesDir = Files.createTempDirectory("fonts").toFile()
        try {
            val config = writeSystemFontConfig(filesDir)!!
            val text = config.readText()
            assertTrue("<cachedir>${filesDir.absolutePath}/fontconfig/cache</cachedir>" in text)
            // The same content is not rewritten, so fontconfig keeps its cache valid.
            val modified = config.lastModified()
            writeSystemFontConfig(filesDir)
            assertEquals(modified, config.lastModified())
        } finally {
            filesDir.deleteRecursively()
        }
    }
}
