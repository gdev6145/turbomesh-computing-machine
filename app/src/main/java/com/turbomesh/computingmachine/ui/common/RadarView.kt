package com.turbomesh.computingmachine.ui.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View
import com.turbomesh.computingmachine.mesh.MeshNode
import kotlin.math.cos
import kotlin.math.sin

/**
 * Polar radar canvas view showing nearby BLE mesh nodes (feature 7).
 *
 * Nodes are plotted at angles evenly spread around the centre, at radii
 * inversely proportional to RSSI (stronger signal = closer to centre).
 * Concentric rings indicate signal-strength thresholds.
 */
class RadarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var nodes: List<MeshNode> = emptyList()

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = Color.parseColor("#33000000")
    }
    private val selfPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FF1565C0")
    }
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 26f
        color = Color.parseColor("#FF212121")
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 34f
        color = Color.parseColor("#FF9E9E9E")
        textAlign = Paint.Align.CENTER
    }

    fun setNodes(meshNodes: List<MeshNode>) {
        nodes = meshNodes
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val maxRadius = minOf(cx, cy) - LABEL_MARGIN

        drawRings(canvas, cx, cy, maxRadius)
        drawSelfDot(canvas, cx, cy)

        if (nodes.isEmpty()) {
            canvas.drawText("No nodes discovered", cx, cy + maxRadius + LABEL_MARGIN / 2f, emptyPaint)
            return
        }

        nodes.forEachIndexed { index, node ->
            val angle = (2 * Math.PI * index / nodes.size - Math.PI / 2).toFloat()
            val normRadius = rssiToRadius(node.rssi, maxRadius)
            val px = cx + normRadius * cos(angle)
            val py = cy + normRadius * sin(angle)

            val color = when {
                node.rssi >= -60 -> Color.parseColor("#FF2E7D32")
                node.rssi >= -75 -> Color.parseColor("#FFF9A825")
                else -> Color.parseColor("#FFC62828")
            }
            nodePaint.color = color
            canvas.drawCircle(px, py, NODE_RADIUS, nodePaint)
            canvas.drawText(node.displayName.take(10), px + NODE_RADIUS + 4f, py + 9f, labelPaint)
        }
    }

    private fun drawRings(canvas: Canvas, cx: Float, cy: Float, maxRadius: Float) {
        // Three rings: Excellent (-60), Good (-75), Fair (-90)
        listOf(0.33f, 0.60f, 0.85f).forEach { fraction ->
            canvas.drawCircle(cx, cy, maxRadius * fraction, ringPaint)
        }
        // Axis lines
        val axisAngles = listOf(0f, 90f, 180f, 270f)
        axisAngles.forEach { deg ->
            val rad = Math.toRadians(deg.toDouble()).toFloat()
            canvas.drawLine(cx, cy, cx + maxRadius * cos(rad), cy + maxRadius * sin(rad), ringPaint)
        }
    }

    private fun drawSelfDot(canvas: Canvas, cx: Float, cy: Float) {
        canvas.drawCircle(cx, cy, 14f, selfPaint)
    }

    /**
     * Maps RSSI (typically -30 to -100 dBm) to a radar radius where
     * strong signal → small radius (node plotted near centre).
     */
    private fun rssiToRadius(rssi: Int, maxRadius: Float): Float {
        val clamped = rssi.coerceIn(RSSI_MIN, RSSI_MAX)
        val fraction = (clamped - RSSI_MAX).toFloat() / (RSSI_MIN - RSSI_MAX).toFloat()
        return (fraction * (maxRadius - MIN_RADIUS) + MIN_RADIUS)
    }

    companion object {
        private const val NODE_RADIUS = 16f
        private const val LABEL_MARGIN = 48f
        private const val MIN_RADIUS = 30f
        private const val RSSI_MIN = -100
        private const val RSSI_MAX = -30
    }
}
