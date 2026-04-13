package com.turbomesh.computingmachine.data

import android.bluetooth.BluetoothDevice
import android.content.Context
import com.turbomesh.computingmachine.bluetooth.BleGattManager
import com.turbomesh.computingmachine.bluetooth.BleScanner
import com.turbomesh.computingmachine.bluetooth.MeshNetworkManager
import com.turbomesh.computingmachine.data.models.NetworkStats
import com.turbomesh.computingmachine.mesh.MeshMessage
import com.turbomesh.computingmachine.mesh.MeshNode
import com.turbomesh.computingmachine.mesh.MeshRouter
import kotlinx.coroutines.flow.StateFlow

/**
 * Single source of truth for all mesh data. Coordinates between BleScanner,
 * MeshNetworkManager, and MeshRouter and exposes flows to the UI layer.
 */
class MeshRepository(context: Context) {

    private val bleScanner = BleScanner(context)
    private val gattManager = BleGattManager(context)
    private val meshRouter = MeshRouter()
    private val networkManager = MeshNetworkManager(context, bleScanner, gattManager, meshRouter)

    // Exposed flows
    val discoveredNodes: StateFlow<List<MeshNode>> = bleScanner.scanResults
    val isScanning: StateFlow<Boolean> = bleScanner.isScanning
    val provisionedNodes: StateFlow<List<MeshNode>> = networkManager.provisionedNodes
    val receivedMessages: StateFlow<List<MeshMessage>> = networkManager.receivedMessages
    val networkStats: StateFlow<NetworkStats> = networkManager.networkStats
    val connectedDevices: StateFlow<Set<String>> = gattManager.connectedDevices

    fun startScan() = bleScanner.startScan()
    fun stopScan() = bleScanner.stopScan()

    fun connectNode(device: BluetoothDevice) = networkManager.connectNode(device)
    fun disconnectNode(address: String) = networkManager.disconnectNode(address)

    fun provisionNode(node: MeshNode) = networkManager.provisionNode(node)
    fun unprovisionNode(nodeId: String) = networkManager.unprovisionNode(nodeId)

    fun sendMessage(message: MeshMessage) = networkManager.sendMessage(message)

    fun destroy() {
        bleScanner.stopScan()
        networkManager.destroy()
    }
}
