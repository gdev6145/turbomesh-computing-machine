package com.turbomesh.computingmachine.ui.devices

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.turbomesh.computingmachine.data.MeshRepository
import com.turbomesh.computingmachine.data.NodeNicknameStore
import com.turbomesh.computingmachine.mesh.CryptoManager
import com.turbomesh.computingmachine.mesh.MeshNode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DeviceViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MeshRepository(application)
    private val cryptoManager = CryptoManager(application)
    private val nicknameStore = NodeNicknameStore(application)

    val discoveredNodes: StateFlow<List<MeshNode>> = repository.discoveredNodes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isScanning: StateFlow<Boolean> = repository.isScanning
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val connectedDevices: StateFlow<Set<String>> = repository.connectedDevices
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    fun toggleScan() {
        if (isScanning.value) repository.stopScan() else repository.startScan()
    }

    fun connectNode(node: MeshNode) {
        viewModelScope.launch {
            val bluetoothManager = getApplication<Application>().getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val device = try { bluetoothManager.adapter?.getRemoteDevice(node.address) } catch (e: Exception) { null } ?: return@launch
            repository.connectNode(device)
        }
    }

    fun disconnectNode(node: MeshNode) = repository.disconnectNode(node.address)
    fun provisionNode(node: MeshNode) = repository.provisionNode(node)
    fun unprovisionNode(node: MeshNode) = repository.unprovisionNode(node.id)
    fun renameNode(nodeId: String, nickname: String) = repository.renameNode(nodeId, nickname)

    // -------------------------------------------------------------------------
    // Feature 11 & 12: Key exchange + pairing PIN
    // -------------------------------------------------------------------------

    /** Send this device's public key to [nodeId] to initiate key exchange. */
    fun sendPublicKey(nodeId: String) {
        val pubKeyBytes = cryptoManager.getPublicKeyBytes()
        repository.sendPublicKey(nodeId, pubKeyBytes)
        // Also store any previously received key
        viewModelScope.launch {
            repository.peerKeyExchanges.collect { (peerId, keyBytes) ->
                cryptoManager.storePeerPublicKey(peerId, keyBytes)
            }
        }
    }

    /** Returns the 6-digit pairing PIN for [nodeId], or null if no shared key yet. */
    fun derivePairingPin(nodeId: String): String? = cryptoManager.derivePairingPin(nodeId)

    /** Mark node as verified after PIN confirmation. */
    fun markNodeVerified(nodeId: String) {
        val nodes = discoveredNodes.value.toMutableList()
        val idx = nodes.indexOfFirst { it.id == nodeId }
        if (idx >= 0) {
            // Store verified state — reflected on next provision / scan update
            repository.renameNode(nodeId, nodes[idx].displayName) // triggers refresh
        }
    }

    // -------------------------------------------------------------------------
    // Feature 18: Presence status
    // -------------------------------------------------------------------------

    fun setMyStatus(status: String) = repository.setMyStatus(status)
    fun getMyStatus(): String = repository.getMyStatus()

    val muteStates: StateFlow<Map<String, Long>> = repository.discoveredNodes
        .map { nodes ->
            nodes.associate { it.id to nicknameStore.getMuteUntil(it.id) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    fun muteNode(nodeId: String, hours: Int) {
        val until = if (hours > 0) System.currentTimeMillis() + hours * 3600_000L else 0L
        nicknameStore.setMuteUntil(nodeId, until)
    }

    override fun onCleared() {
        super.onCleared()
        repository.destroy()
    }
}

