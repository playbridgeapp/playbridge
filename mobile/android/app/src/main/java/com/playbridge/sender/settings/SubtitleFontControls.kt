package com.playbridge.sender.settings

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import com.playbridge.shared.player.MAX_SUBTITLE_FONT_BYTES
import com.playbridge.shared.player.readFontFamily

/** The custom font part of the subtitle editor, for the phone player or a TV. */
internal class SubtitleFontControls(
    /** The installed custom font's family name, selected or not. */
    val installed: String?,
    /** Progress or an error, shown under the font choice. */
    val status: String?,
    /** A font is being installed; picking another waits. */
    val busy: Boolean,
    /** The installed font, for the preview, when it is available here. */
    val typeface: Typeface?,
    val onPick: (Uri) -> Unit,
)

/** A picked font file, checked before anything is installed or sent. */
internal sealed interface PickedFont {
    data class Font(val bytes: ByteArray, val family: String) : PickedFont
    data class Rejected(val message: String) : PickedFont
}

/** Reads and checks a font the user picked. Blocking; call off the main thread. */
internal fun readPickedFont(context: Context, uri: Uri): PickedFont {
    val bytes = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            // One byte over the limit is enough to know the font is too large.
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (output.size() <= MAX_SUBTITLE_FONT_BYTES) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
    }.getOrNull() ?: return PickedFont.Rejected("Couldn't open that file.")
    if (bytes.size > MAX_SUBTITLE_FONT_BYTES) return PickedFont.Rejected(fontErrorMessage("too_large"))
    val family = readFontFamily(bytes) ?: return PickedFont.Rejected(fontErrorMessage("invalid_font"))
    return PickedFont.Font(bytes, family)
}

/** A user-facing sentence for a `subtitle_font_result` error code. */
internal fun fontErrorMessage(code: String?): String = when (code) {
    "too_large" -> "Fonts over 10 MB can't be used. Pick a smaller font file."
    "invalid_font" -> "That file isn't a TrueType or OpenType font."
    else -> "Couldn't send the font to the TV. Try again."
}

/** File types the font picker offers; some providers only label fonts as binary data. */
internal val FONT_MIME_TYPES = arrayOf(
    "font/*", "application/font-sfnt", "application/x-font-ttf", "application/x-font-otf",
    "application/vnd.ms-opentype", "application/x-font-truetype", "application/octet-stream",
)
