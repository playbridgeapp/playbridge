package com.playbridge.player.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatSeekBar

/**
 * A SeekBar that draws a thin horizontal line from the current playback position
 * to the buffered-ahead position ([bufferedMs] / [durationMs]).
 *
 * The line runs along the track centre, overlaying the unplayed region between
 * the playhead and the buffered-ahead position — the same visual style as mpv's
 * on-screen cache indicator.
 *
 * **Important:** set [bufferedMs] and [durationMs] instead of [secondaryProgress]
 * to avoid AppCompatSeekBar internally painting an unwanted white fill for the
 * secondary-progress layer on top of the track.
 */
class BufferSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.seekBarStyle
) : AppCompatSeekBar(context, attrs, defStyleAttr) {

    private val density = context.resources.displayMetrics.density

    private val bufferPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt()   // 60 % white
        strokeWidth = 2f * density   // 2 dp
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    // Offset from track centre (0 = centred on the track)
    private val lineOffsetY = 0f

    /**
     * Buffered position in milliseconds.  Setting this triggers a redraw.
     * Use this instead of [secondaryProgress] to avoid the system drawing
     * an unwanted white secondary-progress bar over the track.
     */
    var bufferedMs: Long = 0L
        set(value) { field = value; invalidate() }

    /** Total duration in milliseconds — required to convert [bufferedMs] to a fraction. */
    var durationMs: Long = 0L
        set(value) { field = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (durationMs <= 0 || max <= 0) return

        // Convert playhead position from the seekbar's own progress scale
        val playFraction   = progress.toFloat() / max.toFloat()
        val bufferFraction = (bufferedMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)

        if (bufferFraction <= playFraction) return

        val trackLeft  = paddingLeft.toFloat()
        val trackRight = (width - paddingRight).toFloat()
        val trackWidth = trackRight - trackLeft

        val playX   = trackLeft + trackWidth * playFraction
        val bufferX = trackLeft + trackWidth * bufferFraction
        val y = height / 2f - lineOffsetY

        canvas.drawLine(playX, y, bufferX, y, bufferPaint)
    }
}
