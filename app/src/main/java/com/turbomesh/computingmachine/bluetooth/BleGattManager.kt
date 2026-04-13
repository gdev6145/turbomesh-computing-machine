package com.turbomesh.computingmachine.bluetooth

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Manages BLE GATT connections to mesh nodes, handles characteristic reads/writes,
 * and exposes received data via StateFlow.
 */
class BleGattManager(private val context: Context) {

    companion object {
        val MESH_SERVICE_UUID: UUID = UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB")
        val MESH_DATA_CHAR_UUID: UUID = UUID.fromString("0000FFF1-0000-1000-8000-00805F9B34FB")
        val MESH_CONTROL_CHAR_UUID: UUID = UUID.fromString("0000FFF2-0000-1000-8000-00805F9B34FB")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
        private const val TAG = "BleGattManager"
    }

    private val activeConnections = mutableMapOf<String, BluetoothGatt>()

    data class GattEvent(
        val deviceAddress: String,
        val type: EventType,
        val data: ByteArray? = null
    ) {
        enum class EventType { CONNECTED, DISCONNECTED, DATA_RECEIVED, WRITE_COMPLETE, SERVICES_DISCOVERED }
    }

    private val _events = MutableStateFlow<GattEvent?>(null)
    val events: StateFlow<GattEvent?> = _events.asStateFlow()

    private val _connectedDevices = MutableStateFlow<Set<String>>(emptySet())
    val connectedDevices: StateFlow<Set<String>> = _connectedDevices.asStateFlow()

    /** Maps device address → epoch-ms when GATT connection was established. */
    private val _connectionTimes = MutableStateFlow<Map<String, Long>>(emptyMap())
    val connectionTimes: StateFlow<Map<String, Long>> = _connectionTimes.asStateFlow()

    fun connect(device: BluetoothDevice) {
        if (!hasConnectPermission()) {
            Log.w(TAG, "Missing BLUETOOTH_CONNECT permission")
            return
        }
        if (activeConnections.containsKey(device.address)) {
            Log.d(TAG, "Already connected to ${device.address}")
            return
        }
        Log.d(TAG, "Connecting to ${device.address}")
        try {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException connecting: ${e.message}")
        }
    }

    fun disconnect(deviceAddress: String) {
        val gatt = activeConnections[deviceAddress] ?: return
        if (!hasConnectPermission()) return
        try {
            gatt.disconnect()
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException disconnecting: ${e.message}")
        }
    }

    fun sendData(deviceAddress: String, data: ByteArray) {
        val gatt = activeConnections[deviceAddress] ?: run {
            Log.w(TAG, "No active connection to $deviceAddress")
            return
        }
        if (!hasConnectPermission()) return
        try {
            val service = gatt.getService(MESH_SERVICE_UUID) ?: run {
                Log.w(TAG, "Mesh service not found on $deviceAddress")
                return
            }
            val characteristic = service.getCharacteristic(MESH_DATA_CHAR_UUID) ?: run {
                Log.w(TAG, "Data characteristic not found on $deviceAddress")
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(characteristic, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = data
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException writing characteristic: ${e.message}")
        }
    }

    fun sendControl(deviceAddress: String, data: ByteArray) {
        val gatt = activeConnections[deviceAddress] ?: return
        if (!hasConnectPermission()) return
        try {
            val service = gatt.getService(MESH_SERVICE_UUID) ?: return
            val characteristic = service.getCharacteristic(MESH_CONTROL_CHAR_UUID) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(characteristic, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = data
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException writing control: ${e.message}")
        }
    }

    fun closeAll() {
        if (!hasConnectPermission()) return
        activeConnections.values.forEach { gatt ->
            try {
                gatt.disconnect()
                gatt.close()
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException closing: ${e.message}")
            }
        }
        activeConnections.clear()
        _connectedDevices.value = emptySet()
        _connectionTimes.value = emptyMap()
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val address = gatt.device.address
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Connected to $address")
                    activeConnections[address] = gatt
                    _connectedDevices.value = _connectedDevices.value + address
                    _connectionTimes.value = _connectionTimes.value + (address to System.currentTimeMillis())
                    _events.value = GattEvent(address, GattEvent.EventType.CONNECTED)
                    if (!hasConnectPermission()) return
                    try {
                        gatt.discoverServices()
                    } catch (e: SecurityException) {
                        Log.e(TAG, "SecurityException discovering services: ${e.message}")
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected from $address")
                    activeConnections.remove(address)
                    _connectedDevices.value = _connectedDevices.value - address
                    _connectionTimes.value = _connectionTimes.value - address
                    _events.value = GattEvent(address, GattEvent.EventType.DISCONNECTED)
                    if (hasConnectPermission()) {
                        try { gatt.close() } catch (e: SecurityException) { /* ignore */ }
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Services discovered for ${gatt.device.address}")
                _events.value = GattEvent(gatt.device.address, GattEvent.EventType.SERVICES_DISCOVERED)
                enableNotifications(gatt)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == MESH_DATA_CHAR_UUID) {
                _events.value = GattEvent(gatt.device.address, GattEvent.EventType.DATA_RECEIVED, value)
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                if (characteristic.uuid == MESH_DATA_CHAR_UUID) {
                    _events.value = GattEvent(gatt.device.address, GattEvent.EventType.DATA_RECEIVED, characteristic.value)
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _events.value = GattEvent(gatt.device.address, GattEvent.EventType.WRITE_COMPLETE)
            } else {
                Log.w(TAG, "Write failed on ${gatt.device.address} status=$status")
            }
        }
    }

    private fun enableNotifications(gatt: BluetoothGatt) {
        if (!hasConnectPermission()) return
        val service = gatt.getService(MESH_SERVICE_UUID) ?: return
        val characteristic = service.getCharacteristic(MESH_DATA_CHAR_UUID) ?: return
        try {
            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(CCCD_UUID)
            if (descriptor != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(descriptor)
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException enabling notifications: ${e.message}")
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
