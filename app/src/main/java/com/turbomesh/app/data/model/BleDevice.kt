package com.turbomesh.app.data.model

import java.util.Locale

/**
 * Represents a BLE device discovered during a Bluetooth LE scan.
 *
 * @param name         Advertised device name, or empty string if unavailable.
 * @param address      MAC address (e.g. "AA:BB:CC:DD:EE:FF").
 * @param rssi         Signal strength in dBm (typically -100 to 0).
 * @param isMeshNode   True when the device advertises a Bluetooth Mesh service UUID
 *                     (Mesh Provisioning 0x1827 or Mesh Proxy 0x1828).
 * @param serviceUuids List of 128-bit UUIDs extracted from the advertisement record.
 * @param lastSeenAt   Unix epoch milliseconds of the most recent advertisement seen.
 */
data class BleDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    val isMeshNode: Boolean,
    val serviceUuids: List<String> = emptyList(),
    val lastSeenAt: Long = System.currentTimeMillis()
) {
    /** A concise display name that falls back to the MAC address when unnamed. */
    val displayName: String get() = name.ifBlank { address }

    /** Human-readable signal-strength label. */
    val signalStrengthLabel: String
        get() = when {
            rssi >= -60 -> "Strong ($rssi dBm)"
            rssi >= -75 -> "Good ($rssi dBm)"
            rssi >= -90 -> "Weak ($rssi dBm)"
            else -> "Very Weak ($rssi dBm)"
        }

    companion object {
        /** Bluetooth Mesh Provisioning Service UUID (16-bit: 0x1827). */
        const val MESH_PROVISIONING_UUID = "00001827-0000-1000-8000-00805f9b34fb"
        /** Bluetooth Mesh Proxy Service UUID (16-bit: 0x1828). */
        const val MESH_PROXY_UUID = "00001828-0000-1000-8000-00805f9b34fb"

        /** Returns true when [uuidString] matches a known BLE mesh service UUID. */
        fun isMeshServiceUuid(uuidString: String): Boolean {
            val lower = uuidString.lowercase(Locale.ROOT)
            return lower == MESH_PROVISIONING_UUID || lower == MESH_PROXY_UUID
        }
    }
}
