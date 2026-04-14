package com.turbomesh.computingmachine.bluetooth

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.turbomesh.computingmachine.mesh.MeshNode
import com.turbomesh.computingmachine.data.db.RssiLogDao
import com.turbomesh.computingmachine.data.db.RssiLogEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Manages BLE scanning for mesh nodes advertising the custom mesh service UUID.
 */
class BleScanner(
    private val context: Context,
    private val rssiLogDao: RssiLogDao? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {

    companion object {
        val MESH_SERVICE_UUID: UUID = UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB")
        private const val TAG = "BleScanner"
        private const val RSSI_HISTORY_SIZE = 6
        /** Minimum number of readings required before a trend can be computed. */
        private const val RSSI_TREND_MIN_READINGS = 4
        private const val RSSI_TREND_THRESHOLD = 2
    }

    private val bluetoothLeScanner by lazy {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter?.bluetoothLeScanner
    }

    private val _scanResults = MutableStateFlow<List<MeshNode>>(emptyList())
    val scanResults: StateFlow<List<MeshNode>> = _scanResults.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _rssiTrends = MutableStateFlow<Map<String, String>>(emptyMap())
    val rssiTrends: StateFlow<Map<String, String>> = _rssiTrends.asStateFlow()

    private val _rssiHistories = MutableStateFlow<Map<String, List<Int>>>(emptyMap())
    /** Maps device address → recent RSSI readings (oldest first, up to [RSSI_HISTORY_SIZE] entries). */
    val rssiHistories: StateFlow<Map<String, List<Int>>> = _rssiHistories.asStateFlow()

    var proximityThreshold: Int = -75
    var proximityAlertsEnabled: Boolean = false

    private val previouslyNearby = mutableSetOf<String>()
    private val _proximityEvents = MutableSharedFlow<Pair<String, Boolean>>(extraBufferCapacity = 64)
    val proximityEvents: SharedFlow<Pair<String, Boolean>> = _proximityEvents.asSharedFlow()

    private val discoveredNodes = mutableMapOf<String, MeshNode>()
    private val rssiHistory = mutableMapOf<String, ArrayDeque<Int>>()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = if (hasConnectPermission()) {
                device.name ?: "Unknown Device"
            } else {
                "Unknown Device"
            }
            val node = MeshNode(
                id = device.address,
                name = name,
                rssi = result.rssi,
                address = device.address,
                isProvisioned = discoveredNodes[device.address]?.isProvisioned ?: false,
                isConnected = discoveredNodes[device.address]?.isConnected ?: false
            )
            discoveredNodes[device.address] = node
            _scanResults.value = discoveredNodes.values.toList()

            // Track RSSI history and update trend
            val history = rssiHistory.getOrPut(device.address) { ArrayDeque() }
            history.addLast(result.rssi)
            if (history.size > RSSI_HISTORY_SIZE) history.removeFirst()
            _rssiTrends.value = _rssiTrends.value + (device.address to computeTrend(history))
            _rssiHistories.value = _rssiHistories.value + (device.address to history.toList())

            // Log RSSI to DB
            if (rssiLogDao != null) {
                scope.launch {
                    rssiLogDao.insert(RssiLogEntity(
                        nodeId = device.address,
                        rssi = result.rssi,
                        timestampMs = System.currentTimeMillis()
                    ))
                }
            }
            // Proximity events
            if (proximityAlertsEnabled) {
                val isNearby = result.rssi >= proximityThreshold
                val wasNearby = previouslyNearby.contains(device.address)
                if (isNearby && !wasNearby) {
                    previouslyNearby.add(device.address)
                    scope.launch { _proximityEvents.emit(device.address to true) }
                } else if (!isNearby && wasNearby) {
                    previouslyNearby.remove(device.address)
                    scope.launch { _proximityEvents.emit(device.address to false) }
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error code: $errorCode")
            _isScanning.value = false
        }
    }

    fun startScan() {
        if (!hasScanPermission()) {
            Log.w(TAG, "Missing BLE scan permission")
            return
        }
        if (_isScanning.value) return

        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(MESH_SERVICE_UUID))
            .build()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        try {
            bluetoothLeScanner?.startScan(listOf(scanFilter), scanSettings, scanCallback)
            _isScanning.value = true
            Log.d(TAG, "BLE scan started")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting scan: ${e.message}")
        }
    }

    fun stopScan() {
        if (!_isScanning.value) return
        try {
            bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException stopping scan: ${e.message}")
        }
        _isScanning.value = false
        Log.d(TAG, "BLE scan stopped")
    }

    fun updateNode(node: MeshNode) {
        discoveredNodes[node.id] = node
        _scanResults.value = discoveredNodes.values.toList()
    }

    fun clearResults() {
        discoveredNodes.clear()
        rssiHistory.clear()
        previouslyNearby.clear()
        _rssiTrends.value = emptyMap()
        _rssiHistories.value = emptyMap()
        _scanResults.value = emptyList()
    }

    private fun computeTrend(history: ArrayDeque<Int>): String {
        if (history.size < RSSI_TREND_MIN_READINGS) return "—"
        val mid = history.size / 2
        val earlier = history.take(mid).average()
        val recent = history.drop(mid).average()
        return when {
            recent > earlier + RSSI_TREND_THRESHOLD -> "▲"
            recent < earlier - RSSI_TREND_THRESHOLD -> "▼"
            else -> "—"
        }
    }

    private fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}

