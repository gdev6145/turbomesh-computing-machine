package com.turbomesh.computingmachine.bluetooth

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * Manages a WebSocket connection to a relay server for internet-bridged mesh messages (feature 15).
 *
 * Wire format: messages are Base64-encoded raw BLE packets delimited by JSON:
 *   {"src":"<nodeId>","dst":"<nodeId>","data":"<base64>"}
 *
 * When a message cannot be routed locally, [MeshNetworkManager] forwards it here.
 * When a message arrives from the relay, it is emitted via [inboundMessages] for injection
 * back into the local mesh.
 */
class BridgeManager {

    companion object {
        private const val TAG = "BridgeManager"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    /** Raw relay packets received from the server: Pair(sourceNodeId, rawPayloadBytes). */
    private val _inboundMessages = MutableSharedFlow<Pair<String, ByteArray>>(extraBufferCapacity = 64)
    val inboundMessages: SharedFlow<Pair<String, ByteArray>> = _inboundMessages.asSharedFlow()

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    fun connect(serverUrl: String) {
        if (_isConnected.value) return
        Log.d(TAG, "Connecting to relay: $serverUrl")
        val request = Request.Builder().url(serverUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                _isConnected.value = true
                Log.d(TAG, "Bridge connected")
            }

            override fun onMessage(ws: WebSocket, text: String) {
                parseRelayMessage(text)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                _isConnected.value = false
                Log.w(TAG, "Bridge failure: ${t.message}")
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                _isConnected.value = false
                Log.d(TAG, "Bridge closed: $reason")
            }
        })
    }

    fun disconnect() {
        webSocket?.close(1000, "Disconnecting")
        webSocket = null
        _isConnected.value = false
    }

    /**
     * Forward a packet to the relay server.
     * [sourceId] and [destId] are mesh node IDs.
     * [rawPacket] is the BLE wire-format packet (opcode + payload).
     */
    fun forward(sourceId: String, destId: String, rawPacket: ByteArray) {
        if (!_isConnected.value) return
        val encoded = Base64.encodeToString(rawPacket, Base64.NO_WRAP)
        val json = """{"src":"$sourceId","dst":"$destId","data":"$encoded"}"""
        webSocket?.send(json)
        Log.d(TAG, "Forwarded ${rawPacket.size} bytes to relay for $destId")
    }

    fun destroy() {
        disconnect()
        scope.cancel()
        client.dispatcher.executorService.shutdown()
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private fun parseRelayMessage(json: String) {
        try {
            // Minimal JSON parsing without extra dependencies
            val srcMatch = Regex(""""src"\s*:\s*"([^"]+)"""").find(json)
            val dataMatch = Regex(""""data"\s*:\s*"([^"]+)"""").find(json)
            val src = srcMatch?.groupValues?.get(1) ?: return
            val dataB64 = dataMatch?.groupValues?.get(1) ?: return
            val data = Base64.decode(dataB64, Base64.NO_WRAP)
            scope.launch { _inboundMessages.emit(src to data) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse relay message", e)
        }
    }
}
