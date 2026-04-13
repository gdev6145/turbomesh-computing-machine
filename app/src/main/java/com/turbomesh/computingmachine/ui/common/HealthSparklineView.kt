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
 * A canvas-drawn mini sparkline that visualises a list of network-health readings
 * (each in the range 0.0–1.0) as proportional vertical bars colour-coded by value.
 */
class HealthSparklineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var readings: List<Float> = emptyList()
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val barSpacingFraction = 0.25f

    fun setReadings(newReadings: List<Float>) {
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
        val barWidth = totalWidth / (count + (count - 1) * barSpacingFraction)
        val gap = barWidth * barSpacingFraction

        readings.forEachIndexed { i, health ->
            val clamped = health.coerceIn(0f, 1f)
            val barHeight = (clamped * totalHeight).coerceAtLeast(2f)

            val left = i * (barWidth + gap)
            val top = totalHeight - barHeight
            val right = left + barWidth
            val bottom = totalHeight

            barPaint.color = when {
                health >= 0.75f -> ContextCompat.getColor(context, R.color.health_good)
                health >= 0.4f -> ContextCompat.getColor(context, R.color.health_medium)
                else -> ContextCompat.getColor(context, R.color.health_poor)
            }

            canvas.drawRoundRect(RectF(left, top, right, bottom), 2f, 2f, barPaint)
        }
    }
}
