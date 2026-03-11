package com.voiceover

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

class SegmentTimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var videoDurationMs: Long = 1
    private var segments: List<RecordedSegment> = emptyList()
    private var currentPositionMs: Long = 0

    private val segmentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.secondary)
    }

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.on_surface_secondary)
        alpha = 50
    }

    private val positionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.white)
        strokeWidth = 3f
    }

    private val rect = RectF()

    fun setVideoDuration(durationMs: Long) {
        videoDurationMs = maxOf(durationMs, 1)
        invalidate()
    }

    fun setSegments(segments: List<RecordedSegment>) {
        this.segments = segments
        invalidate()
    }

    fun setCurrentPosition(positionMs: Long) {
        currentPositionMs = positionMs
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val cornerRadius = h / 2

        // Background track
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, backgroundPaint)

        // Draw segments
        for (segment in segments) {
            val startX = ((segment.startPositionMs.toFloat() / videoDurationMs) * w).coerceIn(0f, w)
            val endX = ((segment.endPositionMs.toFloat() / videoDurationMs) * w).coerceIn(0f, w)
            if (endX > startX) {
                rect.set(startX, 0f, endX, h)
                canvas.drawRoundRect(rect, cornerRadius, cornerRadius, segmentPaint)
            }
        }

        // Position indicator
        val posX = ((currentPositionMs.toFloat() / videoDurationMs) * w).coerceIn(0f, w)
        canvas.drawLine(posX, 0f, posX, h, positionPaint)
    }
}
