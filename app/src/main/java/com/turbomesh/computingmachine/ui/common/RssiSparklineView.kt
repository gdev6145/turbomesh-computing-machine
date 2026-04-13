package com.turbomesh.computingmachine.ui.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.turbomesh.computingmachine.R

/**
 * A small canvas-drawn sparkline chart that visualises a list of recent RSSI readings
 * as proportional vertical bars, colour-coded by signal quality.
 *
 * Usage: call [setReadings] to update.
 */
class RssiSparklineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // RSSI value range to use for normalising bar heights.
    private val rssiMin = -100
    private val rssiMax = -40

    private var readings: List<Int> = emptyList()

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val barSpacingFraction = 0.25f   // fraction of bar-width used as gap

    fun setReadings(newReadings: List<Int>) {
        if (readings == newReadings) return
        readings = newReadings
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (readings.isEmpty()) return

        val count = readings.size
        val totalWidth = width.toFloat()
        val totalHeight = height.toFloat()

        // Total gap fraction across the whole width: (count-1) gaps each = barSpacingFraction of barWidth
        // totalWidth = count * barWidth + (count - 1) * barWidth * barSpacingFraction
        // totalWidth = barWidth * (count + (count - 1) * barSpacingFraction)
        val barWidth = totalWidth / (count + (count - 1) * barSpacingFraction)
        val gap = barWidth * barSpacingFraction

        readings.forEachIndexed { i, rssi ->
            val clamped = rssi.coerceIn(rssiMin, rssiMax)
            val normalised = (clamped - rssiMin).toFloat() / (rssiMax - rssiMin)
            val barHeight = (normalised * totalHeight).coerceAtLeast(2f)

            val left = i * (barWidth + gap)
            val top = totalHeight - barHeight
            val right = left + barWidth
            val bottom = totalHeight

            barPaint.color = when {
                rssi >= -60 -> ContextCompat.getColor(context, R.color.rssi_good)
                rssi >= -80 -> ContextCompat.getColor(context, R.color.rssi_medium)
                else -> ContextCompat.getColor(context, R.color.rssi_poor)
            }

            canvas.drawRoundRect(RectF(left, top, right, bottom), 2f, 2f, barPaint)
        }
    }
}
