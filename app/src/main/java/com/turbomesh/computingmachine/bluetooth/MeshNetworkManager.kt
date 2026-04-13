package com.turbomesh.computingmachine.bluetooth

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import com.turbomesh.computingmachine.data.models.NetworkStats
import com.turbomesh.computingmachine.mesh.MeshMessage
import com.turbomesh.computingmachine.mesh.MeshMessageType
import com.turbomesh.computingmachine.mesh.MeshNode
import com.turbomesh.computingmachine.mesh.MeshRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Manages the overall mesh network: provisioning, node tracking,
 * message sending, and network statistics.
 */
class MeshNetworkManager(
    private val context: Context,
    private val bleScanner: BleScanner,
    private val gattManager: BleGattManager,
    private val meshRouter: MeshRouter
) {
    companion object {
        private const val TAG = "MeshNetworkManager"
        private const val HEARTBEAT_INTERVAL_MS = 10_000L
        private const val RECONNECT_BASE_DELAY_MS = 5_000L
        private const val MAX_RECONNECT_ATTEMPTS = 5
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _networkStats = MutableStateFlow(NetworkStats())
    val networkStats: StateFlow<NetworkStats> = _networkStats.asStateFlow()

    private val _provisionedNodes = MutableStateFlow<List<MeshNode>>(emptyList())
    val provisionedNodes: StateFlow<List<MeshNode>> = _provisionedNodes.asStateFlow()

    private val _receivedMessages = MutableStateFlow<List<MeshMessage>>(emptyList())
    val receivedMessages: StateFlow<List<MeshMessage>> = _receivedMessages.asStateFlow()

    /** Emits the ID of a sent message whenever the remote peer sends back an ACK. */
    private val _acknowledgedMessageIds = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val acknowledgedMessageIds: SharedFlow<String> = _acknowledgedMessageIds.asSharedFlow()

    private var messagesSent = 0
    private var messagesReceived = 0

    // Auto-reconnect state
    private val reconnectJobs = mutableMapOf<String, Job>()
    private val reconnectAttempts = mutableMapOf<String, Int>()
    private val deviceRegistry = mutableMapOf<String, BluetoothDevice>()

    init {
        observeGattEvents()
        observeScanResults()
        startHeartbeat()
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    fun provisionNode(node: MeshNode) {
        val provisioned = node.copy(isProvisioned = true)
        val current = _provisionedNodes.value.toMutableList()
        val existingIndex = current.indexOfFirst { it.id == node.id }
        if (existingIndex >= 0) {
            current[existingIndex] = provisioned
        } else {
            current.add(provisioned)
        }
        _provisionedNodes.value = current
        bleScanner.updateNode(provisioned)
        meshRouter.registerDirectRoute(node.id)
        Log.d(TAG, "Node provisioned: ${node.id}")
        updateStats()
    }

    fun unprovisionNode(nodeId: String) {
        cancelReconnect(nodeId)
        val current = _provisionedNodes.value.toMutableList()
        current.removeAll { it.id == nodeId }
        _provisionedNodes.value = current
        meshRouter.removeRoute(nodeId)
        Log.d(TAG, "Node unprovisioned: $nodeId")
        updateStats()
    }

    fun connectNode(device: BluetoothDevice) {
        deviceRegistry[device.address] = device
        gattManager.connect(device)
    }

    fun disconnectNode(deviceAddress: String) {
        cancelReconnect(deviceAddress)
        gattManager.disconnect(deviceAddress)
        updateNodeConnectionState(deviceAddress, connected = false, isManual = true)
    }

    fun sendMessage(message: MeshMessage) {
        val routed = meshRouter.routeMessage(message) ?: return

        val payload = buildPacket(routed)

        if (routed.destinationNodeId == MeshMessage.BROADCAST_DESTINATION) {
            gattManager.connectedDevices.value.forEach { address ->
                gattManager.sendData(address, payload)
            }
        } else {
            val nextHop = meshRouter.nextHop(routed.destinationNodeId)
            if (nextHop != null) {
                gattManager.sendData(nextHop, payload)
            } else {
                Log.w(TAG, "No route to ${routed.destinationNodeId}")
            }
        }

        messagesSent++
        updateStats()
        Log.d(TAG, "Message sent: ${message.id}")
    }

    fun destroy() {
        scope.cancel()
        gattManager.closeAll()
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private fun observeGattEvents() {
        scope.launch {
            gattManager.events.collectLatest { event ->
                event ?: return@collectLatest
                when (event.type) {
                    BleGattManager.GattEvent.EventType.CONNECTED -> {
                        updateNodeConnectionState(event.deviceAddress, connected = true)
                        cancelReconnect(event.deviceAddress)
                    }
                    BleGattManager.GattEvent.EventType.DISCONNECTED -> {
                        updateNodeConnectionState(event.deviceAddress, connected = false)
                    }
                    BleGattManager.GattEvent.EventType.DATA_RECEIVED -> {
                        event.data?.let { parseIncomingPacket(event.deviceAddress, it) }
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun observeScanResults() {
        scope.launch {
            bleScanner.scanResults.collectLatest { nodes ->
                updateStats(totalNodes = nodes.size)
            }
        }
    }

    private fun startHeartbeat() {
        scope.launch {
            while (true) {
                delay(HEARTBEAT_INTERVAL_MS)
                gattManager.connectedDevices.value.forEach { address ->
                    val hb = MeshMessage(
                        sourceNodeId = "self",
                        destinationNodeId = address,
                        type = MeshMessageType.HEARTBEAT,
                        payload = byteArrayOf()
                    )
                    sendMessage(hb)
                }
            }
        }
    }

    private fun updateNodeConnectionState(
        address: String,
        connected: Boolean,
        isManual: Boolean = false
    ) {
        val current = _provisionedNodes.value.toMutableList()
        val idx = current.indexOfFirst { it.id == address }
        if (idx >= 0) {
            current[idx] = current[idx].copy(isConnected = connected)
            _provisionedNodes.value = current

            if (!connected && !isManual && current[idx].isProvisioned) {
                scheduleReconnect(address)
            }
        }
        bleScanner.scanResults.value
            .firstOrNull { it.id == address }
            ?.let { bleScanner.updateNode(it.copy(isConnected = connected)) }
        updateStats()
    }

    private fun scheduleReconnect(address: String) {
        val attempts = reconnectAttempts.getOrDefault(address, 0)
        if (attempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.d(TAG, "Max reconnect attempts reached for $address")
            reconnectAttempts.remove(address)
            return
        }

        val delayMs = RECONNECT_BASE_DELAY_MS * (1L shl attempts) // 5s, 10s, 20s, 40s, 80s
        Log.d(TAG, "Scheduling reconnect #${attempts + 1} to $address in ${delayMs}ms")

        reconnectJobs[address]?.cancel()
        reconnectJobs[address] = scope.launch {
            delay(delayMs)
            val device = deviceRegistry[address]
            if (device != null) {
                reconnectAttempts[address] = attempts + 1
                Log.d(TAG, "Attempting reconnect to $address (attempt ${attempts + 1})")
                gattManager.connect(device)
            } else {
                Log.w(TAG, "No cached device for $address – cannot reconnect")
            }
        }
    }

    private fun cancelReconnect(address: String) {
        reconnectJobs.remove(address)?.cancel()
        reconnectAttempts.remove(address)
    }

    private fun parseIncomingPacket(sourceAddress: String, data: ByteArray) {
        if (data.isEmpty()) return
        val opcode = data[0]
        val type = MeshMessageType.fromOpcode(opcode) ?: return
        val payload = if (data.size > 1) data.copyOfRange(1, data.size) else byteArrayOf()

        when (type) {
            MeshMessageType.ACK -> {
                // Payload is the UTF-8 ID of the message being acknowledged
                val ackedId = payload.decodeToString().trim()
                if (ackedId.isNotBlank()) {
                    scope.launch { _acknowledgedMessageIds.emit(ackedId) }
                    Log.d(TAG, "ACK received for message $ackedId from $sourceAddress")
                }
            }
            MeshMessageType.DATA -> {
                val message = MeshMessage(
                    sourceNodeId = sourceAddress,
                    destinationNodeId = "self",
                    type = type,
                    payload = payload
                )
                _receivedMessages.value = _receivedMessages.value + message
                messagesReceived++
                updateStats()
                Log.d(TAG, "Received DATA message from $sourceAddress – sending ACK")

                // Send ACK carrying the received message ID as payload
                val ack = MeshMessage(
                    sourceNodeId = "self",
                    destinationNodeId = sourceAddress,
                    type = MeshMessageType.ACK,
                    payload = message.id.toByteArray(Charsets.UTF_8)
                )
                sendMessage(ack)
            }
            else -> {
                val message = MeshMessage(
                    sourceNodeId = sourceAddress,
                    destinationNodeId = "self",
                    type = type,
                    payload = payload
                )
                _receivedMessages.value = _receivedMessages.value + message
                messagesReceived++
                updateStats()
                Log.d(TAG, "Received message type=$type from $sourceAddress")
            }
        }
    }

    private fun buildPacket(message: MeshMessage): ByteArray {
        return byteArrayOf(message.type.opcode) + message.payload
    }

    private fun updateStats(totalNodes: Int? = null) {
        val connected = gattManager.connectedDevices.value.size
        val total = totalNodes ?: bleScanner.scanResults.value.size
        val health = if (total > 0) connected.toFloat() / total.toFloat() else 0f
        _networkStats.value = NetworkStats(
            totalNodes = total,
            connectedNodes = connected,
            messagesSent = messagesSent,
            messagesReceived = messagesReceived,
            networkHealth = health.coerceIn(0f, 1f)
        )
    }
}


import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import com.turbomesh.computingmachine.data.models.NetworkStats
import com.turbomesh.computingmachine.mesh.MeshMessage
import com.turbomesh.computingmachine.mesh.MeshMessageType
import com.turbomesh.computingmachine.mesh.MeshNode
import com.turbomesh.computingmachine.mesh.MeshRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Manages the overall mesh network: provisioning, node tracking,
 * message sending, and network statistics.
 */
class MeshNetworkManager(
    private val context: Context,
    private val bleScanner: BleScanner,
    private val gattManager: BleGattManager,
    private val meshRouter: MeshRouter
) {
    companion object {
        private const val TAG = "MeshNetworkManager"
        private const val HEARTBEAT_INTERVAL_MS = 10_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _networkStats = MutableStateFlow(NetworkStats())
    val networkStats: StateFlow<NetworkStats> = _networkStats.asStateFlow()

    private val _provisionedNodes = MutableStateFlow<List<MeshNode>>(emptyList())
    val provisionedNodes: StateFlow<List<MeshNode>> = _provisionedNodes.asStateFlow()

    private val _receivedMessages = MutableStateFlow<List<MeshMessage>>(emptyList())
    val receivedMessages: StateFlow<List<MeshMessage>> = _receivedMessages.asStateFlow()

    private var messagesSent = 0
    private var messagesReceived = 0

    init {
        observeGattEvents()
        observeScanResults()
        startHeartbeat()
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    fun provisionNode(node: MeshNode) {
        val provisioned = node.copy(isProvisioned = true)
        val current = _provisionedNodes.value.toMutableList()
        val existingIndex = current.indexOfFirst { it.id == node.id }
        if (existingIndex >= 0) {
            current[existingIndex] = provisioned
        } else {
            current.add(provisioned)
        }
        _provisionedNodes.value = current
        bleScanner.updateNode(provisioned)
        meshRouter.registerDirectRoute(node.id)
        Log.d(TAG, "Node provisioned: ${node.id}")
        updateStats()
    }

    fun unprovisionNode(nodeId: String) {
        val current = _provisionedNodes.value.toMutableList()
        current.removeAll { it.id == nodeId }
        _provisionedNodes.value = current
        meshRouter.removeRoute(nodeId)
        Log.d(TAG, "Node unprovisioned: $nodeId")
        updateStats()
    }

    fun connectNode(device: BluetoothDevice) {
        gattManager.connect(device)
    }

    fun disconnectNode(deviceAddress: String) {
        gattManager.disconnect(deviceAddress)
        updateNodeConnectionState(deviceAddress, false)
    }

    fun sendMessage(message: MeshMessage) {
        val routed = meshRouter.routeMessage(message) ?: return

        val payload = buildPacket(routed)

        if (routed.destinationNodeId == MeshMessage.BROADCAST_DESTINATION) {
            gattManager.connectedDevices.value.forEach { address ->
                gattManager.sendData(address, payload)
            }
        } else {
            val nextHop = meshRouter.nextHop(routed.destinationNodeId)
            if (nextHop != null) {
                gattManager.sendData(nextHop, payload)
            } else {
                Log.w(TAG, "No route to ${routed.destinationNodeId}")
            }
        }

        messagesSent++
        updateStats()
        Log.d(TAG, "Message sent: ${message.id}")
    }

    fun destroy() {
        scope.cancel()
        gattManager.closeAll()
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private fun observeGattEvents() {
        scope.launch {
            gattManager.events.collectLatest { event ->
                event ?: return@collectLatest
                when (event.type) {
                    BleGattManager.GattEvent.EventType.CONNECTED -> {
                        updateNodeConnectionState(event.deviceAddress, true)
                    }
                    BleGattManager.GattEvent.EventType.DISCONNECTED -> {
                        updateNodeConnectionState(event.deviceAddress, false)
                    }
                    BleGattManager.GattEvent.EventType.DATA_RECEIVED -> {
                        event.data?.let { parseIncomingPacket(event.deviceAddress, it) }
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun observeScanResults() {
        scope.launch {
            bleScanner.scanResults.collectLatest { nodes ->
                updateStats(totalNodes = nodes.size)
            }
        }
    }

    private fun startHeartbeat() {
        scope.launch {
            while (true) {
                delay(HEARTBEAT_INTERVAL_MS)
                gattManager.connectedDevices.value.forEach { address ->
                    val hb = MeshMessage(
                        sourceNodeId = "self",
                        destinationNodeId = address,
                        type = MeshMessageType.HEARTBEAT,
                        payload = byteArrayOf()
                    )
                    sendMessage(hb)
                }
            }
        }
    }

    private fun updateNodeConnectionState(address: String, connected: Boolean) {
        val current = _provisionedNodes.value.toMutableList()
        val idx = current.indexOfFirst { it.id == address }
        if (idx >= 0) {
            current[idx] = current[idx].copy(isConnected = connected)
            _provisionedNodes.value = current
        }
        bleScanner.scanResults.value
            .firstOrNull { it.id == address }
            ?.let { bleScanner.updateNode(it.copy(isConnected = connected)) }
        updateStats()
    }

    private fun parseIncomingPacket(sourceAddress: String, data: ByteArray) {
        if (data.isEmpty()) return
        val opcode = data[0]
        val type = MeshMessageType.fromOpcode(opcode) ?: return
        val payload = if (data.size > 1) data.copyOfRange(1, data.size) else byteArrayOf()
        val message = MeshMessage(
            sourceNodeId = sourceAddress,
            destinationNodeId = "self",
            type = type,
            payload = payload
        )
        _receivedMessages.value = _receivedMessages.value + message
        messagesReceived++
        updateStats()
        Log.d(TAG, "Received message type=$type from $sourceAddress")
    }

    private fun buildPacket(message: MeshMessage): ByteArray {
        return byteArrayOf(message.type.opcode) + message.payload
    }

    private fun updateStats(totalNodes: Int? = null) {
        val connected = gattManager.connectedDevices.value.size
        val total = totalNodes ?: bleScanner.scanResults.value.size
        val health = if (total > 0) connected.toFloat() / total.toFloat() else 0f
        _networkStats.value = NetworkStats(
            totalNodes = total,
            connectedNodes = connected,
            messagesSent = messagesSent,
            messagesReceived = messagesReceived,
            networkHealth = health.coerceIn(0f, 1f)
        )
    }
}
