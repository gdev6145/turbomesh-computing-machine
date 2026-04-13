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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Manages BLE scanning for mesh nodes advertising the custom mesh service UUID.
 */
class BleScanner(private val context: Context) {

    companion object {
        val MESH_SERVICE_UUID: UUID = UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB")
        private const val TAG = "BleScanner"
        private const val RSSI_HISTORY_SIZE = 6
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
        _rssiTrends.value = emptyMap()
        _scanResults.value = emptyList()
    }

    private fun computeTrend(history: ArrayDeque<Int>): String {
        if (history.size < 4) return "—"
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Manages BLE scanning for mesh nodes advertising the custom mesh service UUID.
 */
class BleScanner(private val context: Context) {

    companion object {
        val MESH_SERVICE_UUID: UUID = UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB")
        private const val TAG = "BleScanner"
    }

    private val bluetoothLeScanner by lazy {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter?.bluetoothLeScanner
    }

    private val _scanResults = MutableStateFlow<List<MeshNode>>(emptyList())
    val scanResults: StateFlow<List<MeshNode>> = _scanResults.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val discoveredNodes = mutableMapOf<String, MeshNode>()

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
        _scanResults.value = emptyList()
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
