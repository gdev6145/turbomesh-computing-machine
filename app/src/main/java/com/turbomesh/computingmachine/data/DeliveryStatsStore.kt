package com.turbomesh.computingmachine.data

import android.content.Context
import org.json.JSONObject

class DeliveryStatsStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    enum class Event { SENT, ACKED, FAILED }

    data class NodeDeliveryStats(
        val nodeId: String,
        val sent: Int,
        val acked: Int,
        val failed: Int
    ) {
        val ackRate: Float get() = if (sent > 0) acked.toFloat() / sent.toFloat() else 0f
    }

    fun record(nodeId: String, event: Event) {
        val current = getStats(nodeId)
        val updated = when (event) {
            Event.SENT -> current.copy(sent = current.sent + 1)
            Event.ACKED -> current.copy(acked = current.acked + 1)
            Event.FAILED -> current.copy(failed = current.failed + 1)
        }
        save(updated)
    }

    fun getStats(nodeId: String): NodeDeliveryStats {
        val json = prefs.getString(nodeId, null) ?: return NodeDeliveryStats(nodeId, 0, 0, 0)
        return try {
            val obj = JSONObject(json)
            NodeDeliveryStats(
                nodeId = nodeId,
                sent = obj.optInt("sent", 0),
                acked = obj.optInt("acked", 0),
                failed = obj.optInt("failed", 0)
            )
        } catch (e: Exception) {
            NodeDeliveryStats(nodeId, 0, 0, 0)
        }
    }

    fun allStats(): Map<String, NodeDeliveryStats> =
        prefs.all.mapNotNull { (key, _) ->
            key to getStats(key)
        }.toMap()

    fun resetAll() {
        prefs.edit().clear().apply()
    }

    private fun save(stats: NodeDeliveryStats) {
        val obj = JSONObject()
        obj.put("sent", stats.sent)
        obj.put("acked", stats.acked)
        obj.put("failed", stats.failed)
        prefs.edit().putString(stats.nodeId, obj.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "delivery_stats"
    }
}
