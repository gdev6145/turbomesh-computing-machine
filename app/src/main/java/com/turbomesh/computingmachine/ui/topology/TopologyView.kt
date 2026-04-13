package com.turbomesh.computingmachine.ui.topology

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Custom canvas view that draws a force-directed mesh topology graph (feature 6).
 *
 * Nodes are circles connected by edges. Edge thickness encodes RSSI quality.
 * A simple spring-repulsion simulation runs for a fixed number of iterations
 * whenever the node/edge set changes, then the result is rendered statically.
 */
class TopologyView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class TopologyNode(val id: String, val label: String, val isSelf: Boolean = false)
    data class TopologyEdge(val fromId: String, val toId: String, val rssi: Int = -70)

    private var nodes: List<TopologyNode> = emptyList()
    private var edges: List<TopologyEdge> = emptyList()
    private val positions = mutableMapOf<String, PointF>()

    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val selfNodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FF1565C0")
    }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#FF757575")
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 28f
        color = Color.parseColor("#FF212121")
        textAlign = Paint.Align.CENTER
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 36f
        color = Color.parseColor("#FF9E9E9E")
        textAlign = Paint.Align.CENTER
    }

    fun setTopology(newNodes: List<TopologyNode>, newEdges: List<TopologyEdge>) {
        nodes = newNodes
        edges = newEdges
        initPositions()
        runSimulation()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        initPositions()
        runSimulation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (nodes.isEmpty()) {
            canvas.drawText("No topology data yet", width / 2f, height / 2f, emptyPaint)
            return
        }
        drawEdges(canvas)
        drawNodes(canvas)
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private fun initPositions() {
        if (width == 0 || height == 0) return
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(cx, cy) * 0.65f
        nodes.forEachIndexed { i, node ->
            if (!positions.containsKey(node.id)) {
                val angle = (2 * Math.PI * i / nodes.size).toFloat()
                positions[node.id] = PointF(cx + radius * cos(angle), cy + radius * sin(angle))
            }
        }
        // Add "self" if not present
        if (!positions.containsKey(SELF_NODE_ID)) {
            positions[SELF_NODE_ID] = PointF(cx, cy)
        }
    }

    private fun runSimulation() {
        if (width == 0 || height == 0 || nodes.isEmpty()) return
        val allIds = (nodes.map { it.id } + SELF_NODE_ID).distinct()
        repeat(SIMULATION_STEPS) {
            val forces = mutableMapOf<String, PointF>()
            allIds.forEach { id -> forces[id] = PointF(0f, 0f) }

            // Repulsion between every pair
            allIds.forEach { a ->
                allIds.forEach { b ->
                    if (a == b) return@forEach
                    val pa = positions[a] ?: return@forEach
                    val pb = positions[b] ?: return@forEach
                    val dx = pa.x - pb.x
                    val dy = pa.y - pb.y
                    val dist = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
                    val repulsion = REPULSION / (dist * dist)
                    forces[a]?.apply { x += dx / dist * repulsion; y += dy / dist * repulsion }
                }
            }

            // Spring attraction along edges
            edges.forEach { edge ->
                val pa = positions[edge.fromId] ?: return@forEach
                val pb = positions[edge.toId] ?: return@forEach
                val dx = pb.x - pa.x
                val dy = pb.y - pa.y
                val dist = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
                val spring = SPRING_K * (dist - IDEAL_LENGTH)
                val fx = dx / dist * spring
                val fy = dy / dist * spring
                forces[edge.fromId]?.apply { x += fx; y += fy }
                forces[edge.toId]?.apply { x -= fx; y -= fy }
            }

            // Apply forces and clamp to bounds
            allIds.forEach { id ->
                val f = forces[id] ?: return@forEach
                val p = positions[id] ?: return@forEach
                p.x = (p.x + f.x * DAMPING).coerceIn(NODE_RADIUS, width - NODE_RADIUS)
                p.y = (p.y + f.y * DAMPING).coerceIn(NODE_RADIUS, height - NODE_RADIUS)
            }
        }
    }

    private fun drawEdges(canvas: Canvas) {
        edges.forEach { edge ->
            val pa = positions[edge.fromId] ?: positions[SELF_NODE_ID] ?: return@forEach
            val pb = positions[edge.toId] ?: return@forEach
            val strokeW = when {
                edge.rssi >= -60 -> 6f
                edge.rssi >= -75 -> 4f
                else -> 2f
            }
            val color = when {
                edge.rssi >= -60 -> Color.parseColor("#FF2E7D32")
                edge.rssi >= -75 -> Color.parseColor("#FFF9A825")
                else -> Color.parseColor("#FFC62828")
            }
            edgePaint.strokeWidth = strokeW
            edgePaint.color = color
            canvas.drawLine(pa.x, pa.y, pb.x, pb.y, edgePaint)
        }
    }

    private fun drawNodes(canvas: Canvas) {
        // Draw "self" node
        val selfPos = positions[SELF_NODE_ID]
        if (selfPos != null) {
            canvas.drawCircle(selfPos.x, selfPos.y, NODE_RADIUS, selfNodePaint)
            canvas.drawText("Me", selfPos.x, selfPos.y + NODE_RADIUS + 28f, labelPaint)
        }
        // Draw peer nodes
        nodes.forEach { node ->
            val pos = positions[node.id] ?: return@forEach
            nodePaint.color = if (node.isSelf) Color.parseColor("#FF1565C0")
            else Color.parseColor("#FF0288D1")
            canvas.drawCircle(pos.x, pos.y, NODE_RADIUS, nodePaint)
            canvas.drawText(node.label.take(10), pos.x, pos.y + NODE_RADIUS + 28f, labelPaint)
        }
    }

    companion object {
        private const val SELF_NODE_ID = "self"
        private const val SIMULATION_STEPS = 120
        private const val REPULSION = 8000f
        private const val SPRING_K = 0.04f
        private const val IDEAL_LENGTH = 200f
        private const val DAMPING = 0.85f
        private const val NODE_RADIUS = 28f
    }
}
