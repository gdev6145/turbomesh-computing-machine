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
    val connectedSinceMs: Long = 0L
) {
    /** Preferred display name: user nickname > BLE name > truncated address. */
    val displayName: String
        get() = when {
            nickname.isNotBlank() -> nickname
            name.isNotBlank() -> name
            else -> address.takeLast(8)
        }
}
