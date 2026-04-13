package com.turbomesh.computingmachine.data

import android.bluetooth.BluetoothDevice
import android.content.Context
import com.turbomesh.computingmachine.bluetooth.BleGattManager
import com.turbomesh.computingmachine.bluetooth.BleScanner
import com.turbomesh.computingmachine.bluetooth.MeshNetworkManager
import com.turbomesh.computingmachine.data.db.AppDatabase
import com.turbomesh.computingmachine.data.db.toEntity
import com.turbomesh.computingmachine.data.models.NetworkStats
import com.turbomesh.computingmachine.mesh.MeshMessage
import com.turbomesh.computingmachine.mesh.MeshNode
import com.turbomesh.computingmachine.mesh.MeshRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Single source of truth for all mesh data. Coordinates between BleScanner,
 * MeshNetworkManager, and MeshRouter and exposes flows to the UI layer.
 */
class MeshRepository(context: Context) {

    private val bleScanner = BleScanner(context)
    private val gattManager = BleGattManager(context)
    private val meshRouter = MeshRouter()
    private val networkManager = MeshNetworkManager(context, bleScanner, gattManager, meshRouter)
    private val nicknameStore = NodeNicknameStore(context)
    private val messageDao = AppDatabase.getInstance(context).messageDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Nodes with nicknames and RSSI trends applied (scanResults, nicknames, rssiTrends)
    val discoveredNodes: Flow<List<MeshNode>> = combine(
        bleScanner.scanResults,
        nicknameStore.nicknames,
        bleScanner.rssiTrends
    ) { nodes, nicknames, trends ->
        nodes.map { node ->
            node.copy(
                nickname = nicknames[node.id] ?: "",
                rssiTrend = trends[node.id] ?: ""
            )
        }
    }

    val isScanning: StateFlow<Boolean> = bleScanner.isScanning

    val provisionedNodes: Flow<List<MeshNode>> = combine(
        networkManager.provisionedNodes,
        nicknameStore.nicknames
    ) { nodes, nicknames ->
        nodes.map { node -> node.copy(nickname = nicknames[node.id] ?: "") }
    }

    val networkStats: StateFlow<NetworkStats> = networkManager.networkStats
    val connectedDevices: StateFlow<Set<String>> = gattManager.connectedDevices

    /** Current set of node IDs known to the mesh routing table. */
    val knownNodes: StateFlow<Set<String>> = networkManager.knownNodes

    /** All messages (sent + received) persisted to the DB, oldest first. */
    val allMessages: Flow<List<MeshMessage>> = messageDao.getAllMessages()
        .map { entities -> entities.map { it.toMeshMessage() } }

    init {
        observeIncomingMessages()
        observeAcknowledgedMessages()
    }

    // -------------------------------------------------------------------------
    // Scanning
    // -------------------------------------------------------------------------

    fun startScan() = bleScanner.startScan()
    fun stopScan() = bleScanner.stopScan()

    // -------------------------------------------------------------------------
    // Connections
    // -------------------------------------------------------------------------

    fun connectNode(device: BluetoothDevice) = networkManager.connectNode(device)
    fun disconnectNode(address: String) = networkManager.disconnectNode(address)

    // -------------------------------------------------------------------------
    // Provisioning
    // -------------------------------------------------------------------------

    fun provisionNode(node: MeshNode) = networkManager.provisionNode(node)
    fun unprovisionNode(nodeId: String) = networkManager.unprovisionNode(nodeId)

    // -------------------------------------------------------------------------
    // Messaging
    // -------------------------------------------------------------------------

    fun sendMessage(message: MeshMessage) {
        networkManager.sendMessage(message)
        scope.launch { messageDao.insertMessage(message.toEntity()) }
    }

    fun deleteMessage(id: String) {
        scope.launch { messageDao.deleteById(id) }
    }

    fun clearMessages() {
        scope.launch { messageDao.deleteAll() }
    }

    // -------------------------------------------------------------------------
    // Nicknames
    // -------------------------------------------------------------------------

    fun renameNode(nodeId: String, nickname: String) {
        nicknameStore.setNickname(nodeId, nickname)
    }

    fun getNickname(nodeId: String): String = nicknameStore.getNickname(nodeId)

    // -------------------------------------------------------------------------
    // Cleanup
    // -------------------------------------------------------------------------

    fun destroy() {
        bleScanner.stopScan()
        networkManager.destroy()
        scope.cancel()
    }

    // -------------------------------------------------------------------------
    // Internal observers
    // -------------------------------------------------------------------------

    private fun observeIncomingMessages() {
        scope.launch {
            networkManager.receivedMessages.collect { messages ->
                val latest = messages.lastOrNull() ?: return@collect
                messageDao.insertMessage(latest.toEntity())
            }
        }
    }

    private fun observeAcknowledgedMessages() {
        scope.launch {
            networkManager.acknowledgedMessageIds.collect { messageId ->
                messageDao.markAcknowledged(messageId)
            }
        }
    }
}

