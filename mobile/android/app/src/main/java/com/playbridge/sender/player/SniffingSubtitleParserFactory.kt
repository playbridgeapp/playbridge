package com.playbridge.sender.player

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import com.playbridge.shared.player.ExternalSubtitleFormat
import com.playbridge.shared.player.detectSubtitleFormat

/**
 * Sidecar subtitles are labelled from addon metadata or the URL, and the label can be wrong:
 * OpenSubtitles serves SRT as `vtt`, which ExoPlayer's WebVTT parser rejects, dropping the track.
 * This parses each text sidecar with the parser for what its bytes actually are, as the TV and
 * the phone's mpv already do.
 */
@OptIn(UnstableApi::class)
internal class SniffingSubtitleParserFactory(
    private val delegate: SubtitleParser.Factory = DefaultSubtitleParserFactory(),
) : SubtitleParser.Factory {
    override fun supportsFormat(format: Format): Boolean = delegate.supportsFormat(format)

    override fun getCueReplacementBehavior(format: Format): Int = delegate.getCueReplacementBehavior(format)

    override fun create(format: Format): SubtitleParser {
        val declared = delegate.create(format)
        if (format.sampleMimeType !in SNIFFED_MIME_TYPES) return declared
        return object : SubtitleParser {
            override fun parse(
                data: ByteArray,
                offset: Int,
                length: Int,
                outputOptions: SubtitleParser.OutputOptions,
                output: Consumer<CuesWithTiming>,
            ) {
                val actual = sniffedMimeType(data, offset, length)
                val parser = if (actual == null || actual == format.sampleMimeType) declared
                    else delegate.create(format.buildUpon().setSampleMimeType(actual).build())
                parser.parse(data, offset, length, outputOptions, output)
            }

            override fun getCueReplacementBehavior(): Int = declared.cueReplacementBehavior

            override fun reset() = declared.reset()
        }
    }

    companion object {
        private val SNIFFED_MIME_TYPES = setOf(
            MimeTypes.TEXT_VTT, MimeTypes.APPLICATION_SUBRIP, MimeTypes.TEXT_SSA, MimeTypes.APPLICATION_TTML,
        )

        /** The MIME type the subtitle's own bytes show, by the shared detection. */
        internal fun sniffedMimeType(data: ByteArray, offset: Int, length: Int): String? {
            val head = data.copyOfRange(offset, offset + minOf(length, 8_192))
            return when (detectSubtitleFormat(url = "", contentType = null, bytes = head)) {
                ExternalSubtitleFormat.WEBVTT -> MimeTypes.TEXT_VTT
                ExternalSubtitleFormat.SUBRIP -> MimeTypes.APPLICATION_SUBRIP
                ExternalSubtitleFormat.ASS, ExternalSubtitleFormat.SSA -> MimeTypes.TEXT_SSA
                ExternalSubtitleFormat.TTML -> MimeTypes.APPLICATION_TTML
            }
        }
    }
}
