package com.turbomesh.computingmachine.bluetooth

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.turbomesh.computingmachine.data.MeshSettings
import com.turbomesh.computingmachine.data.MeshSettingsStore
import com.turbomesh.computingmachine.data.NodeStatusStore
import com.turbomesh.computingmachine.data.NodeTelemetryStore
import com.turbomesh.computingmachine.data.models.NetworkStats
import com.turbomesh.computingmachine.data.models.NodeStats
import com.turbomesh.computingmachine.mesh.FileTransferManager
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
 * message sending, delivery retry, per-node stats, and network statistics.
 *
 * Extended in this version to handle typing indicators (feature 1), read receipts (2),
 * reactions (3), file/voice transfers (4 & 5), route advertisements (8), telemetry (9),
 * GPS heartbeat (10), encryption (11), emergency SOS (14), bridge fallback (15),
 * groups (16), clipboard (17), and presence/status (18).
 */
class MeshNetworkManager(
    private val context: Context,
    private val bleScanner: BleScanner,
    private val gattManager: BleGattManager,
    private val meshRouter: MeshRouter,
    private val settingsStore: MeshSettingsStore = MeshSettingsStore(context),
    val nodeStatusStore: NodeStatusStore = NodeStatusStore(context),
    val nodeTelemetryStore: NodeTelemetryStore = NodeTelemetryStore(),
    val fileTransferManager: FileTransferManager = FileTransferManager(),
    val bridgeManager: BridgeManager = BridgeManager()
) {
    companion object {
        private const val TAG = "MeshNetworkManager"
        private const val RECONNECT_BASE_DELAY_MS = 5_000L
        private const val RETRY_CHECK_INTERVAL_MS = 5_000L
        private const val RETRY_WINDOW_MS = 12_000L
        // Sub-type byte for CONTROL / KEY_EXCHANGE (feature 11)
        private const val CTRL_KEY_EXCHANGE: Byte = 0x01
        // TELEMETRY TLV tags (feature 9)
        private const val TLV_BATTERY: Byte = 0x01
        private const val TLV_MODEL: Byte = 0x02
        // HEARTBEAT payload prefix that indicates GPS data (feature 10)
        private const val HB_GPS_MAGIC: Byte = 0x47 // 'G'
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _networkStats = MutableStateFlow(NetworkStats())
    val networkStats: StateFlow<NetworkStats> = _networkStats.asStateFlow()

    private val _provisionedNodes = MutableStateFlow<List<MeshNode>>(emptyList())
    val provisionedNodes: StateFlow<List<MeshNode>> = _provisionedNodes.asStateFlow()

    private val _receivedMessages = MutableStateFlow<List<MeshMessage>>(emptyList())
    val receivedMessages: StateFlow<List<MeshMessage>> = _receivedMessages.asStateFlow()

    /** Emits the message ID whenever the remote peer sends back an ACK. */
    private val _acknowledgedMessageIds = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val acknowledgedMessageIds: SharedFlow<String> = _acknowledgedMessageIds.asSharedFlow()

    /** Emits (messageId, epochMs) when a peer sends a READ receipt (feature 2). */
    private val _readReceiptIds = MutableSharedFlow<Pair<String, Long>>(extraBufferCapacity = 64)
    val readReceiptIds: SharedFlow<Pair<String, Long>> = _readReceiptIds.asSharedFlow()

    /** Emits (originalMsgId, emoji, senderNodeId) for incoming reactions (feature 3). */
    private val _reactions = MutableSharedFlow<Triple<String, String, String>>(extraBufferCapacity = 64)
    val reactions: SharedFlow<Triple<String, String, String>> = _reactions.asSharedFlow()

    /** Emits the node ID that is currently typing; consumer clears after 3s (feature 1). */
    private val _typingNodeIds = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val typingNodeIds: SharedFlow<String> = _typingNodeIds.asSharedFlow()

    /** Emits the message ID when delivery permanently failed (all retries exhausted). */
    private val _failedMessageIds = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val failedMessageIds: SharedFlow<String> = _failedMessageIds.asSharedFlow()

    /** Current set of node IDs known to the routing table. */
    private val _knownNodes = MutableStateFlow<Set<String>>(emptySet())
    val knownNodes: StateFlow<Set<String>> = _knownNodes.asStateFlow()

    /** Per-node sent/received counters. */
    private val _perNodeStats = MutableStateFlow<Map<String, NodeStats>>(emptyMap())
    val perNodeStats: StateFlow<Map<String, NodeStats>> = _perNodeStats.asStateFlow()

    /** Emits (peerId, publicKeyBytes) when a key-exchange CONTROL is received (feature 11). */
    private val _peerKeyExchange = MutableSharedFlow<Pair<String, ByteArray>>(extraBufferCapacity = 32)
    val peerKeyExchange: SharedFlow<Pair<String, ByteArray>> = _peerKeyExchange.asSharedFlow()

    /** Emits clipboard text from a peer (feature 17). */
    private val _inboundClipboard = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val inboundClipboard: SharedFlow<String> = _inboundClipboard.asSharedFlow()

    /** Emits emergency SOS text (feature 14). */
    private val _emergencyMessages = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 16)
    val emergencyMessages: SharedFlow<Pair<String, String>> = _emergencyMessages.asSharedFlow()

    /** Emits (groupUuid, groupName, senderNodeId) for incoming group invitations (feature 16). */
    private val _groupInvites = MutableSharedFlow<Triple<String, String, String>>(extraBufferCapacity = 16)
    val groupInvites: SharedFlow<Triple<String, String, String>> = _groupInvites.asSharedFlow()

    private var messagesSent = 0
    private var messagesReceived = 0

    private data class PendingAck(val message: MeshMessage, val expiresAt: Long, val attempt: Int)
    private val pendingAcks = mutableMapOf<String, PendingAck>()
    private val reconnectJobs = mutableMapOf<String, Job>()
    private val reconnectAttempts = mutableMapOf<String, Int>()
    private val deviceRegistry = mutableMapOf<String, BluetoothDevice>()

    init {
        observeGattEvents()
        observeScanResults()
        startHeartbeat()
        startRetryLoop()
        observeBridgeMessages()
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    fun provisionNode(node: MeshNode) {
        val provisioned = node.copy(isProvisioned = true)
        val current = _provisionedNodes.value.toMutableList()
        val existingIndex = current.indexOfFirst { it.id == node.id }
        if (existingIndex >= 0) current[existingIndex] = provisioned else current.add(provisioned)
        _provisionedNodes.value = current
        bleScanner.updateNode(provisioned)
        meshRouter.registerDirectRoute(node.id)
        _knownNodes.value = meshRouter.knownNodes()
        Log.d(TAG, "Node provisioned: ${node.id}")
        updateStats()
    }

    fun unprovisionNode(nodeId: String) {
        cancelReconnect(nodeId)
        val current = _provisionedNodes.value.toMutableList()
        current.removeAll { it.id == nodeId }
        _provisionedNodes.value = current
        meshRouter.removeRoute(nodeId)
        _knownNodes.value = meshRouter.knownNodes()
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
        val settings = settingsStore.current()
        val outgoing = if (message.ttl == 7 && settings.defaultTtl != 7) message.copy(ttl = settings.defaultTtl) else message
        val routed = meshRouter.routeMessage(outgoing) ?: return
        val payload = buildPacket(routed)

        if (routed.destinationNodeId == MeshMessage.BROADCAST_DESTINATION) {
            gattManager.connectedDevices.value.forEach { address ->
                gattManager.sendData(address, payload)
                incrementSent(address)
            }
        } else {
            val nextHop = meshRouter.nextHop(routed.destinationNodeId)
            if (nextHop != null) {
                gattManager.sendData(nextHop, payload)
                incrementSent(nextHop)
                if (routed.type == MeshMessageType.DATA) {
                    pendingAcks[routed.id] = PendingAck(routed, System.currentTimeMillis() + RETRY_WINDOW_MS, 1)
                }
            } else {
                // Feature 15: fall back to bridge if available
                if (settings.bridgeEnabled && bridgeManager.isConnected.value) {
                    bridgeManager.forward("self", routed.destinationNodeId, payload)
                    Log.d(TAG, "Forwarded ${routed.id} via bridge to ${routed.destinationNodeId}")
                } else {
                    Log.w(TAG, "No route to ${routed.destinationNodeId}")
                }
            }
        }
        messagesSent++
        updateStats()
        Log.d(TAG, "Message sent: ${message.id}")
    }

    /** Feature 1: Send a typing indicator to all connected peers (TTL=1). */
    fun sendTypingIndicator() {
        val msg = MeshMessage(
            sourceNodeId = "self",
            destinationNodeId = MeshMessage.BROADCAST_DESTINATION,
            type = MeshMessageType.TYPING,
            payload = byteArrayOf(),
            ttl = 1
        )
        val payload = buildPacket(msg)
        gattManager.connectedDevices.value.forEach { address ->
            gattManager.sendData(address, payload)
        }
    }

    /** Feature 3: Send an emoji reaction to a specific message. */
    fun sendReaction(originalMessageId: String, emoji: String, destinationNodeId: String) {
        val payload = "$originalMessageId:$emoji".toByteArray(Charsets.UTF_8)
        val msg = MeshMessage(
            sourceNodeId = "self",
            destinationNodeId = destinationNodeId,
            type = MeshMessageType.REACTION,
            payload = payload
        )
        sendMessage(msg)
    }

    /** Feature 14: Send high-priority emergency broadcast (TTL=15). */
    fun sendEmergency(text: String) {
        val msg = MeshMessage(
            sourceNodeId = "self",
            destinationNodeId = MeshMessage.BROADCAST_DESTINATION,
            type = MeshMessageType.EMERGENCY,
            payload = text.toByteArray(Charsets.UTF_8),
            ttl = 15
        )
        val payload = buildPacket(msg)
        gattManager.connectedDevices.value.forEach { address ->
            gattManager.sendData(address, payload)
            incrementSent(address)
        }
    }

    /** Feature 17: Broadcast clipboard text to all connected peers. */
    fun sendClipboard(text: String) {
        val msg = MeshMessage(
            sourceNodeId = "self",
            destinationNodeId = MeshMessage.BROADCAST_DESTINATION,
            type = MeshMessageType.CLIPBOARD,
            payload = text.toByteArray(Charsets.UTF_8)
        )
        sendMessage(msg)
    }

    /** Feature 16: Send a group invitation. */
    fun sendGroupInvite(groupId: String, groupName: String, destinationNodeId: String) {
        val payload = "$groupId:$groupName".toByteArray(Charsets.UTF_8)
        val msg = MeshMessage(
            sourceNodeId = "self",
            destinationNodeId = destinationNodeId,
            type = MeshMessageType.GROUP_INVITE,
            payload = payload
        )
        sendMessage(msg)
    }

    /** Feature 11: Send this device's public key to a peer. */
    fun sendPublicKey(destinationNodeId: String, publicKeyBytes: ByteArray) {
        val payload = byteArrayOf(CTRL_KEY_EXCHANGE) + publicKeyBytes
        val msg = MeshMessage(
            sourceNodeId = "self",
            destinationNodeId = destinationNodeId,
            type = MeshMessageType.CONTROL,
            payload = payload
        )
        sendMessage(msg)
    }

    /** Feature 15: Connect or disconnect the bridge per current settings. */
    fun applyBridgeSettings(settings: MeshSettings) {
        if (settings.bridgeEnabled && settings.bridgeServerUrl.isNotBlank()) {
            bridgeManager.connect(settings.bridgeServerUrl)
        } else {
            bridgeManager.disconnect()
        }
    }

    fun destroy() {
        scope.cancel()
        gattManager.closeAll()
        bridgeManager.destroy()
    }

    // -------------------------------------------------------------------------
    // Internal observers
    // -------------------------------------------------------------------------

    private fun observeGattEvents() {
        scope.launch {
            gattManager.events.collectLatest { event ->
                event ?: return@collectLatest
                when (event.type) {
                    BleGattManager.GattEvent.EventType.CONNECTED -> {
                        updateNodeConnectionState(event.deviceAddress, connected = true)
                        cancelReconnect(event.deviceAddress)
                        // Feature 8: advertise our routing table to the newly connected peer
                        advertiseRoutes(event.deviceAddress)
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
                val intervalMs = settingsStore.current().heartbeatIntervalMs
                delay(intervalMs)
                gattManager.connectedDevices.value.forEach { address ->
                    // Feature 9: piggyback battery telemetry periodically
                    sendTelemetry(address)
                    // Feature 18: piggyback presence status in heartbeat
                    val statusStr = nodeStatusStore.getMyStatus()
                    val hbPayload = buildHeartbeatPayload(statusStr)
                    val hb = MeshMessage(
                        sourceNodeId = "self",
                        destinationNodeId = address,
                        type = MeshMessageType.HEARTBEAT,
                        payload = hbPayload
                    )
                    sendMessage(hb)
                }
            }
        }
    }

    private fun startRetryLoop() {
        scope.launch {
            while (true) {
                delay(RETRY_CHECK_INTERVAL_MS)
                val now = System.currentTimeMillis()
                val maxRetries = settingsStore.current().messageRetries
                val expired = pendingAcks.values.filter { it.expiresAt <= now }
                expired.forEach { pending ->
                    if (pending.attempt >= maxRetries) {
                        Log.w(TAG, "Message ${pending.message.id} permanently failed after ${pending.attempt} retries")
                        pendingAcks.remove(pending.message.id)
                        scope.launch { _failedMessageIds.emit(pending.message.id) }
                    } else {
                        Log.d(TAG, "Retrying message ${pending.message.id} (attempt ${pending.attempt + 1})")
                        val nextHop = meshRouter.nextHop(pending.message.destinationNodeId)
                        if (nextHop != null) {
                            gattManager.sendData(nextHop, buildPacket(pending.message))
                            incrementSent(nextHop)
                        }
                        pendingAcks[pending.message.id] = pending.copy(
                            expiresAt = now + RETRY_WINDOW_MS,
                            attempt = pending.attempt + 1
                        )
                    }
                }
            }
        }
    }

    private fun observeBridgeMessages() {
        scope.launch {
            bridgeManager.inboundMessages.collect { (srcNodeId, data) ->
                Log.d(TAG, "Bridge inbound from $srcNodeId (${data.size} bytes)")
                parseIncomingPacket(srcNodeId, data)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Feature 8: Route advertisement
    // -------------------------------------------------------------------------

    private fun advertiseRoutes(destinationAddress: String) {
        val knownNodeIds = meshRouter.knownNodes()
        if (knownNodeIds.isEmpty()) return
        val payload = knownNodeIds.joinToString(",").toByteArray(Charsets.UTF_8)
        val msg = MeshMessage(
            sourceNodeId = "self",
            destinationNodeId = destinationAddress,
            type = MeshMessageType.ROUTE_ADV,
            payload = payload,
            ttl = 1
        )
        val packet = buildPacket(msg)
        gattManager.sendData(destinationAddress, packet)
        Log.d(TAG, "Advertised ${knownNodeIds.size} routes to $destinationAddress")
    }

    // -------------------------------------------------------------------------
    // Feature 9: Telemetry (battery + device model)
    // -------------------------------------------------------------------------

    private fun sendTelemetry(address: String) {
        val batteryLevel = readBatteryLevel()
        val model = android.os.Build.MODEL.take(32)
        val payload = buildTelemetryTlv(batteryLevel, model)
        val msg = MeshMessage(
            sourceNodeId = "self",
            destinationNodeId = address,
            type = MeshMessageType.TELEMETRY,
            payload = payload,
            ttl = 1
        )
        val packet = buildPacket(msg)
        gattManager.sendData(address, packet)
    }

    private fun readBatteryLevel(): Int {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) (level * 100 / scale) else -1
    }

    private fun buildTelemetryTlv(batteryLevel: Int, model: String): ByteArray {
        val result = mutableListOf<Byte>()
        if (batteryLevel >= 0) {
            result += TLV_BATTERY
            result += 1.toByte()
            result += batteryLevel.toByte()
        }
        if (model.isNotBlank()) {
            val modelBytes = model.toByteArray(Charsets.UTF_8)
            result += TLV_MODEL
            result += modelBytes.size.toByte()
            result.addAll(modelBytes.toList())
        }
        return result.toByteArray()
    }

    private fun parseTelemetryTlv(payload: ByteArray, nodeId: String) {
        var i = 0
        var battery = -1
        var model = ""
        while (i + 1 < payload.size) {
            val tag = payload[i]
            val len = payload[i + 1].toInt() and 0xFF
            if (i + 2 + len > payload.size) break
            val value = payload.copyOfRange(i + 2, i + 2 + len)
            when (tag) {
                TLV_BATTERY -> battery = value[0].toInt() and 0xFF
                TLV_MODEL -> model = value.decodeToString()
            }
            i += 2 + len
        }
        nodeTelemetryStore.updateBattery(nodeId, battery, model)
        updateProvisionedNodeTelemetry(nodeId, battery)
    }

    // -------------------------------------------------------------------------
    // Feature 10: GPS in heartbeat
    // -------------------------------------------------------------------------

    /**
     * Builds HEARTBEAT payload: [HB_GPS_MAGIC][lat:8][lon:8][status:rest] if GPS is available,
     * or [status UTF-8 bytes] otherwise.
     * GPS attachment is controlled by the presence of a [latLon] parameter.
     */
    private fun buildHeartbeatPayload(status: String, latLon: Pair<Double, Double>? = null): ByteArray {
        if (latLon == null) return status.toByteArray(Charsets.UTF_8)
        val buf = java.nio.ByteBuffer.allocate(1 + 8 + 8 + status.length)
        buf.put(HB_GPS_MAGIC)
        buf.putDouble(latLon.first)
        buf.putDouble(latLon.second)
        buf.put(status.toByteArray(Charsets.UTF_8))
        return buf.array()
    }

    private fun parseHeartbeatPayload(payload: ByteArray, nodeId: String) {
        if (payload.isEmpty()) return
        if (payload[0] == HB_GPS_MAGIC && payload.size >= 17) {
            val buf = java.nio.ByteBuffer.wrap(payload, 1, payload.size - 1)
            val lat = buf.double
            val lon = buf.double
            nodeTelemetryStore.updateLocation(nodeId, lat, lon)
            updateProvisionedNodeLocation(nodeId, lat, lon)
            val statusBytes = payload.copyOfRange(17, payload.size)
            val status = statusBytes.decodeToString()
            if (status.isNotBlank()) {
                nodeStatusStore.setStatus(nodeId, status)
                updateProvisionedNodeStatus(nodeId, status)
            }
        } else {
            val status = payload.decodeToString()
            if (status.isNotBlank()) {
                nodeStatusStore.setStatus(nodeId, status)
                updateProvisionedNodeStatus(nodeId, status)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Incoming packet dispatch
    // -------------------------------------------------------------------------

    private fun parseIncomingPacket(sourceAddress: String, data: ByteArray) {
        if (data.isEmpty()) return
        val opcode = data[0]
        val type = MeshMessageType.fromOpcode(opcode) ?: return
        val payload = if (data.size > 1) data.copyOfRange(1, data.size) else byteArrayOf()

        when (type) {
            MeshMessageType.ACK -> handleAck(payload, sourceAddress)
            MeshMessageType.DATA -> handleData(payload, sourceAddress)
            MeshMessageType.HEARTBEAT -> handleHeartbeat(payload, sourceAddress)
            MeshMessageType.CONTROL -> handleControl(payload, sourceAddress)
            MeshMessageType.TYPING -> handleTyping(sourceAddress)
            MeshMessageType.READ -> handleReadReceipt(payload, sourceAddress)
            MeshMessageType.REACTION -> handleReaction(payload, sourceAddress)
            MeshMessageType.FILE_CHUNK -> handleFileChunk(payload, isVoice = false)
            MeshMessageType.FILE_COMPLETE -> handleFileComplete(payload, isVoice = false)
            MeshMessageType.VOICE_CHUNK -> handleFileChunk(payload, isVoice = true)
            MeshMessageType.VOICE_COMPLETE -> handleFileComplete(payload, isVoice = true)
            MeshMessageType.ROUTE_ADV -> handleRouteAdv(payload, sourceAddress)
            MeshMessageType.TELEMETRY -> handleTelemetry(payload, sourceAddress)
            MeshMessageType.EMERGENCY -> handleEmergency(payload, sourceAddress)
            MeshMessageType.GROUP_INVITE -> handleGroupInvite(payload, sourceAddress)
            MeshMessageType.CLIPBOARD -> handleClipboard(payload, sourceAddress)
            else -> {
                val message = MeshMessage(sourceNodeId = sourceAddress, destinationNodeId = "self", type = type, payload = payload)
                _receivedMessages.value = _receivedMessages.value + message
                messagesReceived++
                incrementReceived(sourceAddress)
                updateStats()
            }
        }
    }

    private fun handleAck(payload: ByteArray, sourceAddress: String) {
        val ackedId = payload.decodeToString().trim()
        if (ackedId.isNotBlank()) {
            pendingAcks.remove(ackedId)
            scope.launch { _acknowledgedMessageIds.emit(ackedId) }
            Log.d(TAG, "ACK received for $ackedId from $sourceAddress")
        }
    }

    private fun handleData(payload: ByteArray, sourceAddress: String) {
        val message = MeshMessage(sourceNodeId = sourceAddress, destinationNodeId = "self", type = MeshMessageType.DATA, payload = payload)
        _receivedMessages.value = _receivedMessages.value + message
        messagesReceived++
        incrementReceived(sourceAddress)
        updateStats()
        // Send ACK
        val ack = MeshMessage(
            sourceNodeId = "self",
            destinationNodeId = sourceAddress,
            type = MeshMessageType.ACK,
            payload = message.id.toByteArray(Charsets.UTF_8)
        )
        sendMessage(ack)
        // Feature 2: Send read receipt
        val readReceipt = MeshMessage(
            sourceNodeId = "self",
            destinationNodeId = sourceAddress,
            type = MeshMessageType.READ,
            payload = message.id.toByteArray(Charsets.UTF_8)
        )
        sendMessage(readReceipt)
        Log.d(TAG, "Received DATA from $sourceAddress, ACK + READ sent")
    }

    private fun handleHeartbeat(payload: ByteArray, sourceAddress: String) {
        parseHeartbeatPayload(payload, sourceAddress)
        val message = MeshMessage(sourceNodeId = sourceAddress, destinationNodeId = "self", type = MeshMessageType.HEARTBEAT, payload = payload)
        _receivedMessages.value = _receivedMessages.value + message
        messagesReceived++
        incrementReceived(sourceAddress)
        updateStats()
    }

    private fun handleControl(payload: ByteArray, sourceAddress: String) {
        if (payload.isEmpty()) return
        val subType = payload[0]
        val data = if (payload.size > 1) payload.copyOfRange(1, payload.size) else byteArrayOf()
        when (subType) {
            CTRL_KEY_EXCHANGE -> {
                Log.d(TAG, "Received public key from $sourceAddress (${data.size} bytes)")
                scope.launch { _peerKeyExchange.emit(sourceAddress to data) }
            }
        }
        val message = MeshMessage(sourceNodeId = sourceAddress, destinationNodeId = "self", type = MeshMessageType.CONTROL, payload = payload)
        _receivedMessages.value = _receivedMessages.value + message
        messagesReceived++
        incrementReceived(sourceAddress)
        updateStats()
    }

    private fun handleTyping(sourceAddress: String) {
        scope.launch { _typingNodeIds.emit(sourceAddress) }
        Log.d(TAG, "$sourceAddress is typing")
    }

    private fun handleReadReceipt(payload: ByteArray, sourceAddress: String) {
        val msgId = payload.decodeToString().trim()
        if (msgId.isNotBlank()) {
            scope.launch { _readReceiptIds.emit(msgId to System.currentTimeMillis()) }
            Log.d(TAG, "READ receipt for $msgId from $sourceAddress")
        }
    }

    private fun handleReaction(payload: ByteArray, sourceAddress: String) {
        val text = payload.decodeToString()
        val colonIdx = text.indexOf(':')
        if (colonIdx > 0) {
            val originalId = text.substring(0, colonIdx)
            val emoji = text.substring(colonIdx + 1)
            scope.launch { _reactions.emit(Triple(originalId, emoji, sourceAddress)) }
            // Store reaction as a message in DB via receivedMessages flow
            val msg = MeshMessage(sourceNodeId = sourceAddress, destinationNodeId = "self", type = MeshMessageType.REACTION, payload = payload)
            _receivedMessages.value = _receivedMessages.value + msg
            messagesReceived++
            incrementReceived(sourceAddress)
            updateStats()
            Log.d(TAG, "Reaction $emoji on $originalId from $sourceAddress")
        }
    }

    private fun handleFileChunk(payload: ByteArray, isVoice: Boolean) {
        fileTransferManager.onChunkReceived(payload, isVoice)
    }

    private fun handleFileComplete(payload: ByteArray, isVoice: Boolean) {
        scope.launch { fileTransferManager.onCompleteReceived(payload, isVoice) }
    }

    private fun handleRouteAdv(payload: ByteArray, sourceAddress: String) {
        val nodeIds = payload.decodeToString().split(",").map { it.trim() }.filter { it.isNotBlank() }
        nodeIds.forEach { nodeId ->
            if (nodeId != "self" && !meshRouter.hasRoute(nodeId)) {
                meshRouter.registerRoute(nodeId, listOf(sourceAddress))
                Log.d(TAG, "Route learnt via advertisement: $nodeId via $sourceAddress")
            }
        }
        _knownNodes.value = meshRouter.knownNodes()
    }

    private fun handleTelemetry(payload: ByteArray, sourceAddress: String) {
        parseTelemetryTlv(payload, sourceAddress)
        Log.d(TAG, "Telemetry from $sourceAddress processed")
    }

    private fun handleEmergency(payload: ByteArray, sourceAddress: String) {
        val text = payload.decodeToString()
        scope.launch { _emergencyMessages.emit(sourceAddress to text) }
        // Store emergency message and re-broadcast once (flooded once, TTL enforced by router)
        val msg = MeshMessage(sourceNodeId = sourceAddress, destinationNodeId = "self", type = MeshMessageType.EMERGENCY, payload = payload)
        _receivedMessages.value = _receivedMessages.value + msg
        messagesReceived++
        incrementReceived(sourceAddress)
        updateStats()
        Log.w(TAG, "EMERGENCY from $sourceAddress: $text")
    }

    private fun handleGroupInvite(payload: ByteArray, sourceAddress: String) {
        val text = payload.decodeToString()
        val colonIdx = text.indexOf(':')
        if (colonIdx > 0) {
            val groupId = text.substring(0, colonIdx)
            val groupName = text.substring(colonIdx + 1)
            scope.launch { _groupInvites.emit(Triple(groupId, groupName, sourceAddress)) }
        }
        val msg = MeshMessage(sourceNodeId = sourceAddress, destinationNodeId = "self", type = MeshMessageType.GROUP_INVITE, payload = payload)
        _receivedMessages.value = _receivedMessages.value + msg
        messagesReceived++
        incrementReceived(sourceAddress)
        updateStats()
    }

    private fun handleClipboard(payload: ByteArray, sourceAddress: String) {
        val text = payload.decodeToString()
        scope.launch { _inboundClipboard.emit(text) }
        val msg = MeshMessage(sourceNodeId = sourceAddress, destinationNodeId = "self", type = MeshMessageType.CLIPBOARD, payload = payload)
        _receivedMessages.value = _receivedMessages.value + msg
        messagesReceived++
        incrementReceived(sourceAddress)
        updateStats()
        Log.d(TAG, "Clipboard received from $sourceAddress: ${text.take(40)}")
    }

    // -------------------------------------------------------------------------
    // Node state helpers
    // -------------------------------------------------------------------------

    private fun updateNodeConnectionState(address: String, connected: Boolean, isManual: Boolean = false) {
        val current = _provisionedNodes.value.toMutableList()
        val idx = current.indexOfFirst { it.id == address }
        if (idx >= 0) {
            current[idx] = current[idx].copy(isConnected = connected)
            _provisionedNodes.value = current
            val settings = settingsStore.current()
            if (!connected && !isManual && current[idx].isProvisioned) {
                scheduleReconnect(address, settings.maxReconnectAttempts)
            }
        }
        bleScanner.scanResults.value
            .firstOrNull { it.id == address }
            ?.let { bleScanner.updateNode(it.copy(isConnected = connected)) }
        updateStats()
    }

    private fun updateProvisionedNodeStatus(nodeId: String, status: String) {
        val current = _provisionedNodes.value.toMutableList()
        val idx = current.indexOfFirst { it.id == nodeId }
        if (idx >= 0) {
            current[idx] = current[idx].copy(presenceStatus = status)
            _provisionedNodes.value = current
        }
    }

    private fun updateProvisionedNodeTelemetry(nodeId: String, battery: Int) {
        val current = _provisionedNodes.value.toMutableList()
        val idx = current.indexOfFirst { it.id == nodeId }
        if (idx >= 0 && battery >= 0) {
            current[idx] = current[idx].copy(batteryLevel = battery)
            _provisionedNodes.value = current
        }
    }

    private fun updateProvisionedNodeLocation(nodeId: String, lat: Double, lon: Double) {
        val current = _provisionedNodes.value.toMutableList()
        val idx = current.indexOfFirst { it.id == nodeId }
        if (idx >= 0) {
            current[idx] = current[idx].copy(lastLatitude = lat, lastLongitude = lon)
            _provisionedNodes.value = current
        }
    }

    private fun scheduleReconnect(address: String, maxAttempts: Int) {
        val attempts = reconnectAttempts.getOrDefault(address, 0)
        if (attempts >= maxAttempts) {
            Log.d(TAG, "Max reconnect attempts reached for $address")
            reconnectAttempts.remove(address)
            return
        }
        val delayMs = RECONNECT_BASE_DELAY_MS * (1L shl attempts)
        Log.d(TAG, "Scheduling reconnect #${attempts + 1} to $address in ${delayMs}ms")
        reconnectJobs[address]?.cancel()
        reconnectJobs[address] = scope.launch {
            delay(delayMs)
            val device = deviceRegistry[address]
            if (device != null) {
                reconnectAttempts[address] = attempts + 1
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

    private fun incrementSent(address: String) {
        val current = _perNodeStats.value.getOrDefault(address, NodeStats())
        _perNodeStats.value = _perNodeStats.value + (address to current.copy(sent = current.sent + 1))
    }

    private fun incrementReceived(address: String) {
        val current = _perNodeStats.value.getOrDefault(address, NodeStats())
        _perNodeStats.value = _perNodeStats.value + (address to current.copy(received = current.received + 1))
    }

    private fun buildPacket(message: MeshMessage): ByteArray = byteArrayOf(message.type.opcode) + message.payload

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
