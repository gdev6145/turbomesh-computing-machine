package com.turbomesh.computingmachine.ui.devices

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.turbomesh.computingmachine.data.MeshRepository
import com.turbomesh.computingmachine.mesh.MeshNode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DeviceViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MeshRepository(application)

    val discoveredNodes: StateFlow<List<MeshNode>> = repository.discoveredNodes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isScanning: StateFlow<Boolean> = repository.isScanning
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val connectedDevices: StateFlow<Set<String>> = repository.connectedDevices
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    fun toggleScan() {
        if (isScanning.value) {
            repository.stopScan()
        } else {
            repository.startScan()
        }
    }

    fun connectNode(node: MeshNode) {
        viewModelScope.launch {
            val bluetoothManager =
                getApplication<Application>().getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val device = try {
                bluetoothManager.adapter?.getRemoteDevice(node.address)
            } catch (e: Exception) {
                null
            } ?: return@launch
            repository.connectNode(device)
        }
    }

    fun disconnectNode(node: MeshNode) {
        repository.disconnectNode(node.address)
    }

    fun provisionNode(node: MeshNode) {
        repository.provisionNode(node)
    }

    fun unprovisionNode(node: MeshNode) {
        repository.unprovisionNode(node.id)
    }

    override fun onCleared() {
        super.onCleared()
        repository.destroy()
    }
}
