package com.playbridge.shared.player

import android.os.Looper
import androidx.media3.common.Format
import androidx.media3.common.Timeline
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.MediaClock
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererConfiguration
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer
import java.io.IOException

/**
 * Wraps Media3's final [TextRenderer] so subtitle timing can be offset at render time.
 *
 * Sign convention matches TV SubtitleManager / phone remote UI preview:
 * `effectivePosition = playbackPosition + delayMs` — a positive delay advances subtitles
 * relative to video (looks ahead in the subtitle timeline).
 *
 * Only [render] shifts the query clock; stream attachment stays on the true media timeline.
 */
@UnstableApi
internal class OffsetTextRenderer(
    output: TextOutput,
    outputLooper: Looper?,
    private val delayMsProvider: () -> Long,
) : Renderer {
    private val delegate = TextRenderer(output, outputLooper).also {
        it.experimentalSetLegacyDecodingEnabled(true)
    }

    override fun getName(): String = "Offset${delegate.name}"

    override fun getTrackType(): Int = delegate.trackType

    override fun getCapabilities() = delegate.capabilities

    override fun init(index: Int, playerId: PlayerId, clock: Clock) {
        delegate.init(index, playerId, clock)
    }

    override fun getMediaClock(): MediaClock? = delegate.mediaClock

    override fun getState(): Int = delegate.state

    @Throws(ExoPlaybackException::class)
    override fun enable(
        configuration: RendererConfiguration,
        formats: Array<out Format>,
        stream: SampleStream,
        positionUs: Long,
        joining: Boolean,
        mayRenderStartOfStream: Boolean,
        startPositionUs: Long,
        offsetUs: Long,
        mediaPeriodId: MediaSource.MediaPeriodId,
    ) {
        delegate.enable(
            configuration,
            formats,
            stream,
            positionUs,
            joining,
            mayRenderStartOfStream,
            startPositionUs,
            offsetUs,
            mediaPeriodId,
        )
    }

    @Throws(ExoPlaybackException::class)
    override fun start() {
        delegate.start()
    }

    @Throws(ExoPlaybackException::class)
    override fun replaceStream(
        formats: Array<out Format>,
        stream: SampleStream,
        startPositionUs: Long,
        offsetUs: Long,
        mediaPeriodId: MediaSource.MediaPeriodId,
    ) {
        delegate.replaceStream(formats, stream, startPositionUs, offsetUs, mediaPeriodId)
    }

    override fun getStream(): SampleStream? = delegate.stream

    override fun hasReadStreamToEnd(): Boolean = delegate.hasReadStreamToEnd()

    override fun getReadingPositionUs(): Long = delegate.readingPositionUs

    override fun setCurrentStreamFinal() {
        delegate.setCurrentStreamFinal()
    }

    override fun isCurrentStreamFinal(): Boolean = delegate.isCurrentStreamFinal

    @Throws(IOException::class)
    override fun maybeThrowStreamError() {
        delegate.maybeThrowStreamError()
    }

    @Throws(ExoPlaybackException::class)
    override fun resetPosition(positionUs: Long, sampleStreamIsResetToKeyFrame: Boolean) {
        delegate.resetPosition(positionUs, sampleStreamIsResetToKeyFrame)
    }

    override fun setTimeline(timeline: Timeline) {
        delegate.setTimeline(timeline)
    }

    @Throws(ExoPlaybackException::class)
    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        val offsetUs = delayMsProvider().coerceIn(MIN_DELAY_MS, MAX_DELAY_MS) * 1_000L
        delegate.render(positionUs + offsetUs, elapsedRealtimeUs)
    }

    override fun isReady(): Boolean = delegate.isReady

    override fun isEnded(): Boolean = delegate.isEnded

    override fun stop() {
        delegate.stop()
    }

    override fun disable() {
        delegate.disable()
    }

    override fun reset() {
        delegate.reset()
    }

    override fun release() {
        delegate.release()
    }

    @Throws(ExoPlaybackException::class)
    override fun handleMessage(messageType: Int, message: Any?) {
        delegate.handleMessage(messageType, message)
    }

    @Throws(ExoPlaybackException::class)
    override fun setPlaybackSpeed(currentPlaybackSpeed: Float, targetPlaybackSpeed: Float) {
        delegate.setPlaybackSpeed(currentPlaybackSpeed, targetPlaybackSpeed)
    }

    override fun enableMayRenderStartOfStream() {
        delegate.enableMayRenderStartOfStream()
    }

    companion object {
        private const val MIN_DELAY_MS = -120_000L
        private const val MAX_DELAY_MS = 120_000L
    }
}
