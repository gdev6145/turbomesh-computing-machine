package com.turbomesh.computingmachine.mesh

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Handles mesh message routing, including unicast and broadcast delivery,
 * TTL enforcement, and routing table management.
 */
class MeshRouter {

    private val tag = "MeshRouter"

    // routing table: nodeId -> list of intermediate hop nodeIds
    companion object {
        private const val MAX_HISTORY_SIZE = 500
    }

    private val routingTable = mutableMapOf<String, MutableList<String>>()

    private val _routedMessages = MutableStateFlow<List<MeshMessage>>(emptyList())
    val routedMessages: StateFlow<List<MeshMessage>> = _routedMessages.asStateFlow()

    private val messageHistory = mutableListOf<MeshMessage>()

    /**
     * Register a direct route to a node (no hops).
     */
    fun registerDirectRoute(nodeId: String) {
        routingTable[nodeId] = mutableListOf()
        Log.d(tag, "Registered direct route to $nodeId")
    }

    /**
     * Register a route to a node via intermediate hops.
     */
    fun registerRoute(destinationId: String, hops: List<String>) {
        routingTable[destinationId] = hops.toMutableList()
        Log.d(tag, "Registered route to $destinationId via $hops")
    }

    /**
     * Remove route for a disconnected node.
     */
    fun removeRoute(nodeId: String) {
        routingTable.remove(nodeId)
        Log.d(tag, "Removed route to $nodeId")
    }

    /**
     * Route a message. Returns the (possibly modified) message if it should be forwarded,
     * or null if it should be dropped (TTL exhausted or already seen).
     */
    fun routeMessage(message: MeshMessage): MeshMessage? {
        if (message.ttl <= 0) {
            Log.d(tag, "Dropping message ${message.id} – TTL exhausted")
            return null
        }

        if (messageHistory.any { it.id == message.id }) {
            Log.d(tag, "Dropping duplicate message ${message.id}")
            return null
        }

        val routed = message.copy(hopCount = message.hopCount + 1, ttl = message.ttl - 1)
        messageHistory.add(routed)
        if (messageHistory.size > MAX_HISTORY_SIZE) messageHistory.removeAt(0)

        _routedMessages.value = _routedMessages.value + routed
        Log.d(tag, "Routing message ${routed.id} type=${routed.type} dst=${routed.destinationNodeId} hop=${routed.hopCount}")
        return routed
    }

    /**
     * Returns the next-hop node ID for a given destination, or null if unknown.
     */
    fun nextHop(destinationId: String): String? {
        val hops = routingTable[destinationId] ?: return null
        return if (hops.isEmpty()) destinationId else hops.first()
    }

    /**
     * True when we have any route to the destination.
     */
    fun hasRoute(destinationId: String): Boolean =
        destinationId == MeshMessage.BROADCAST_DESTINATION || routingTable.containsKey(destinationId)

    fun clearRoutes() {
        routingTable.clear()
    }

    fun knownNodes(): Set<String> = routingTable.keys.toSet()
}
