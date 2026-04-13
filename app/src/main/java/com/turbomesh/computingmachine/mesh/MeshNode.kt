package com.turbomesh.computingmachine.mesh

data class MeshNode(
    val id: String,
    val name: String,
    val rssi: Int,
    val isProvisioned: Boolean = false,
    val isConnected: Boolean = false,
    val address: String,
    val nickname: String = "",
    val rssiTrend: String = "",
    /** Epoch-ms at which this node became connected, or 0 if not connected. */
    val connectedSinceMs: Long = 0L,
    /** Recent RSSI readings, oldest first, up to 6 entries. */
    val rssiReadings: List<Int> = emptyList(),
    /** Presence status line set by the remote user (feature 18). */
    val presenceStatus: String = "",
    /** Last-known GPS latitude from HEARTBEAT payload (feature 10). */
    val lastLatitude: Double? = null,
    /** Last-known GPS longitude from HEARTBEAT payload (feature 10). */
    val lastLongitude: Double? = null,
    /** Battery level 0-100 from TELEMETRY payload (feature 9). */
    val batteryLevel: Int = -1,
    /** True when this node's public key has been exchanged and PIN confirmed (feature 12). */
    val isVerified: Boolean = false
) {
    /** Preferred display name: user nickname > BLE name > truncated address. */
    val displayName: String
        get() = when {
            nickname.isNotBlank() -> nickname
            name.isNotBlank() -> name
            else -> address.takeLast(8)
        }

    /**
     * Connection quality label derived from current RSSI and trend.
     * Returns one of: "Excellent", "Good", "Fair", "Poor".
     */
    val connectionQuality: String
        get() {
            val trendBonus = when (rssiTrend) {
                "▲" -> 5
                "▼" -> -5
                else -> 0
            }
            return when (rssi + trendBonus) {
                in Int.MIN_VALUE..-90 -> "Poor"
                in -89..-75 -> "Fair"
                in -74..-60 -> "Good"
                else -> "Excellent"
            }
        }
}
