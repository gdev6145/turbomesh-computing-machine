package com.turbomesh.computingmachine.data

import android.bluetooth.BluetoothDevice
import android.content.Context
import com.turbomesh.computingmachine.bluetooth.BleGattManager
import com.turbomesh.computingmachine.bluetooth.BleScanner
import com.turbomesh.computingmachine.bluetooth.MeshNetworkManager
import com.turbomesh.computingmachine.data.db.AppDatabase
import com.turbomesh.computingmachine.data.db.toEntity
import com.turbomesh.computingmachine.data.models.NetworkStats
import com.turbomesh.computingmachine.data.models.NodeStats
import com.turbomesh.computingmachine.mesh.FileTransferManager
import com.turbomesh.computingmachine.mesh.MeshMessage
import com.turbomesh.computingmachine.mesh.MeshNode
import com.turbomesh.computingmachine.mesh.MeshRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
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
    private val settingsStore = MeshSettingsStore(context)
    private val networkManager = MeshNetworkManager(context, bleScanner, gattManager, meshRouter, settingsStore)
    private val nicknameStore = NodeNicknameStore(context)
    private val groupStore = GroupStore(context)
    private val messageDao = AppDatabase.getInstance(context).messageDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // -------------------------------------------------------------------------
    // Node flows
    // -------------------------------------------------------------------------

    val discoveredNodes: Flow<List<MeshNode>> = combine(
        bleScanner.scanResults,
        nicknameStore.nicknames,
        bleScanner.rssiTrends,
        gattManager.connectionTimes,
        bleScanner.rssiHistories
    ) { nodes, nicknames, trends, connTimes, histories ->
        nodes.map { node ->
            node.copy(
                nickname = nicknames[node.id] ?: "",
                rssiTrend = trends[node.id] ?: "",
                connectedSinceMs = connTimes[node.id] ?: 0L,
                rssiReadings = histories[node.id] ?: emptyList()
            )
        }
    }

    val isScanning: StateFlow<Boolean> = bleScanner.isScanning

    val provisionedNodes: Flow<List<MeshNode>> = combine(
        networkManager.provisionedNodes,
        nicknameStore.nicknames,
        gattManager.connectionTimes
    ) { nodes, nicknames, connTimes ->
        nodes.map { node ->
            node.copy(
                nickname = nicknames[node.id] ?: "",
                connectedSinceMs = connTimes[node.id] ?: 0L
            )
        }
    }

    val networkStats: StateFlow<NetworkStats> = networkManager.networkStats
    val connectedDevices: StateFlow<Set<String>> = gattManager.connectedDevices
    val knownNodes: StateFlow<Set<String>> = networkManager.knownNodes
    val perNodeStats: StateFlow<Map<String, NodeStats>> = networkManager.perNodeStats
    val deliveryFailures: SharedFlow<String> = networkManager.failedMessageIds
    val settings: StateFlow<MeshSettings> = settingsStore.settings

    // -------------------------------------------------------------------------
    // New feature flows (features 1-18)
    // -------------------------------------------------------------------------

    /** Feature 1: Emits node ID when that node sends a TYPING packet. */
    val typingNodeIds: SharedFlow<String> = networkManager.typingNodeIds

    /** Feature 2: Emits (messageId, epochMs) when a READ receipt arrives. */
    val readReceiptIds: SharedFlow<Pair<String, Long>> = networkManager.readReceiptIds

    /** Feature 3: Emits (originalMsgId, emoji, senderNodeId) for reactions. */
    val reactions: SharedFlow<Triple<String, String, String>> = networkManager.reactions

    /** Feature 11: Emits (peerId, publicKeyBytes) when a key exchange CONTROL arrives. */
    val peerKeyExchanges: SharedFlow<Pair<String, ByteArray>> = networkManager.peerKeyExchange

    /** Feature 14: Emits (senderNodeId, text) for emergency SOS messages. */
    val emergencyMessages: SharedFlow<Pair<String, String>> = networkManager.emergencyMessages

    /** Feature 15: Bridge connection state. */
    val bridgeConnected: StateFlow<Boolean> = networkManager.bridgeManager.isConnected

    /** Feature 16: Emits (groupUuid, groupName, senderNodeId) for group invitations. */
    val groupInvites: SharedFlow<Triple<String, String, String>> = networkManager.groupInvites

    /** Feature 17: Emits clipboard text from a remote peer. */
    val inboundClipboard: SharedFlow<String> = networkManager.inboundClipboard

    /** Feature 9 & 10: Per-node telemetry (battery, GPS). */
    val nodeTelemetry = networkManager.nodeTelemetryStore.telemetry

    /** Feature 16: Current node groups. */
    val groups = groupStore.groups

    /** Features 4 & 5: Completed file/voice transfers. */
    val completedTransfers: SharedFlow<FileTransferManager.CompletedTransfer> =
        networkManager.fileTransferManager.completedTransfers

    // -------------------------------------------------------------------------
    // Messages
    // -------------------------------------------------------------------------

    val allMessages: Flow<List<MeshMessage>> = messageDao.getAllMessages()
        .map { entities -> entities.map { it.toMeshMessage() } }

    init {
        observeIncomingMessages()
        observeAcknowledgedMessages()
        observeReadReceipts()
        applyBridgeSettings()
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

    /** Feature 1: Send typing indicator. */
    fun sendTypingIndicator() = networkManager.sendTypingIndicator()

    /** Feature 3: Send emoji reaction. */
    fun sendReaction(originalMessageId: String, emoji: String, destinationNodeId: String) =
        networkManager.sendReaction(originalMessageId, emoji, destinationNodeId)

    /** Feature 14: Send emergency SOS. */
    fun sendEmergency(text: String) = networkManager.sendEmergency(text)

    /** Feature 17: Send clipboard. */
    fun sendClipboard(text: String) = networkManager.sendClipboard(text)

    /** Feature 16: Send group invitation. */
    fun sendGroupInvite(groupId: String, groupName: String, destinationNodeId: String) =
        networkManager.sendGroupInvite(groupId, groupName, destinationNodeId)

    /** Feature 11: Send public key to peer. */
    fun sendPublicKey(destinationNodeId: String, publicKeyBytes: ByteArray) =
        networkManager.sendPublicKey(destinationNodeId, publicKeyBytes)

    /** Feature 13: Drain pending messages for a newly available destination. */
    fun drainPendingDeliveries(destinationNodeId: String) {
        scope.launch {
            val pending = messageDao.getPendingMessages(destinationNodeId)
            pending.forEach { entity ->
                networkManager.sendMessage(entity.toMeshMessage())
                messageDao.clearPendingDelivery(entity.id)
            }
        }
    }

    /** Features 4 & 5: Build chunks for a file/voice transfer. */
    fun buildFileChunks(data: ByteArray, mimeType: String, isVoice: Boolean): Pair<String, List<Pair<MeshMessageType, ByteArray>>> =
        networkManager.fileTransferManager.buildChunks(data, mimeType, isVoice)

    /** Feature 16: Get a specific group by ID. */
    fun getGroup(groupId: String) = groupStore.getGroup(groupId)

    // -------------------------------------------------------------------------
    // Settings
    // -------------------------------------------------------------------------

    fun updateSettings(settings: MeshSettings) {
        settingsStore.update(settings)
        networkManager.applyBridgeSettings(settings)
    }

    // -------------------------------------------------------------------------
    // Nicknames
    // -------------------------------------------------------------------------

    fun renameNode(nodeId: String, nickname: String) = nicknameStore.setNickname(nodeId, nickname)
    fun getNickname(nodeId: String): String = nicknameStore.getNickname(nodeId)

    // -------------------------------------------------------------------------
    // Groups (feature 16)
    // -------------------------------------------------------------------------

    fun createGroup(name: String, members: List<String> = emptyList()) = groupStore.createGroup(name, members)
    fun addGroupMember(groupId: String, nodeId: String) = groupStore.addMember(groupId, nodeId)
    fun deleteGroup(groupId: String) = groupStore.deleteGroup(groupId)

    // -------------------------------------------------------------------------
    // Presence / status (feature 18)
    // -------------------------------------------------------------------------

    fun setMyStatus(status: String) = networkManager.nodeStatusStore.setMyStatus(status)
    fun getMyStatus(): String = networkManager.nodeStatusStore.getMyStatus()

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

    private fun observeReadReceipts() {
        scope.launch {
            networkManager.readReceiptIds.collect { (messageId, timestamp) ->
                messageDao.markRead(messageId, timestamp)
            }
        }
    }

    private fun applyBridgeSettings() {
        networkManager.applyBridgeSettings(settingsStore.current())
    }
}
