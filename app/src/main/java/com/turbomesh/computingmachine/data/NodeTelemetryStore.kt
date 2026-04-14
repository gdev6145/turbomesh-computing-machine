package com.turbomesh.computingmachine.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds the last-known telemetry (battery, GPS) received from each node
 * via TELEMETRY and HEARTBEAT messages (features 9 and 10).
 *
 * Data is ephemeral (in-memory only) and refreshed as packets arrive.
 */
class NodeTelemetryStore {

    data class NodeTelemetry(
        val nodeId: String,
        /** Battery 0-100, or -1 if unknown. */
        val batteryLevel: Int = -1,
        /** Device model string from TELEMETRY TLV, empty if not received. */
        val deviceModel: String = "",
        /** Epoch-ms of the last telemetry packet. */
        val lastUpdatedMs: Long = 0L,
        /** Last-known GPS latitude (from HEARTBEAT), null if not received. */
        val latitude: Double? = null,
        /** Last-known GPS longitude (from HEARTBEAT), null if not received. */
        val longitude: Double? = null
    )

    private val _telemetry = MutableStateFlow<Map<String, NodeTelemetry>>(emptyMap())
    val telemetry: StateFlow<Map<String, NodeTelemetry>> = _telemetry.asStateFlow()

    fun updateBattery(nodeId: String, batteryLevel: Int, deviceModel: String = "") {
        val current = _telemetry.value[nodeId] ?: NodeTelemetry(nodeId)
        _telemetry.value = _telemetry.value + (nodeId to current.copy(
            batteryLevel = batteryLevel,
            deviceModel = deviceModel.ifBlank { current.deviceModel },
            lastUpdatedMs = System.currentTimeMillis()
        ))
    }

    fun updateLocation(nodeId: String, latitude: Double, longitude: Double) {
        val current = _telemetry.value[nodeId] ?: NodeTelemetry(nodeId)
        _telemetry.value = _telemetry.value + (nodeId to current.copy(
            latitude = latitude,
            longitude = longitude,
            lastUpdatedMs = System.currentTimeMillis()
        ))
    }

    fun get(nodeId: String): NodeTelemetry? = _telemetry.value[nodeId]
}
