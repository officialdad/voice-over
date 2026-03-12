package com.voiceover

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors

class SegmentTimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var videoDurationMs: Long = 1
    private var segments: List<RecordedSegment> = emptyList()
    private var currentPositionMs: Long = 0

    private val segmentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(context, com.google.android.material.R.attr.colorPrimary, Color.MAGENTA)
    }

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurfaceVariant, Color.DKGRAY)
        alpha = 50
    }

    private val positionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, Color.WHITE)
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
        val left = 0f
        val right = w
        val trackWidth = right - left
        val cornerRadius = h / 2

        // Background track
        rect.set(left, 0f, right, h)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, backgroundPaint)

        // Draw segments - extend past view bounds for edge segments so view clipping
        // creates flat edges instead of rounded corners leaving gray gaps
        for (segment in segments) {
            val startX = (left + (segment.startPositionMs.toFloat() / videoDurationMs) * trackWidth).coerceIn(left, right)
            val endX = (left + (segment.endPositionMs.toFloat() / videoDurationMs) * trackWidth).coerceIn(left, right)
            if (endX > startX) {
                val drawLeft = if (segment.startPositionMs <= 0) startX - cornerRadius else startX
                val drawRight = if (segment.endPositionMs >= videoDurationMs) endX + cornerRadius else endX
                rect.set(drawLeft, 0f, drawRight, h)
                canvas.drawRoundRect(rect, cornerRadius, cornerRadius, segmentPaint)
            }
        }

        // Position indicator
        val posX = (left + (currentPositionMs.toFloat() / videoDurationMs) * trackWidth).coerceIn(left, right)
        canvas.drawLine(posX, 0f, posX, h, positionPaint)
    }
}
