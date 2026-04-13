package com.turbomesh.computingmachine.ui.network

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.turbomesh.computingmachine.data.MeshRepository
import com.turbomesh.computingmachine.data.NodeTelemetryStore
import com.turbomesh.computingmachine.data.models.NetworkStats
import com.turbomesh.computingmachine.data.models.NodeStats
import com.turbomesh.computingmachine.mesh.MeshNode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class NetworkViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MeshRepository(application)

    val networkStats: StateFlow<NetworkStats> = repository.networkStats
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NetworkStats())

    val activeNodes: StateFlow<List<MeshNode>> = repository.provisionedNodes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val knownNodes: StateFlow<Set<String>> = repository.knownNodes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val perNodeStats: StateFlow<Map<String, NodeStats>> = repository.perNodeStats
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** Feature 7: All discovered nodes for the radar view. */
    val discoveredNodes: StateFlow<List<MeshNode>> = repository.discoveredNodes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Feature 9 & 10: Live telemetry per node. */
    val nodeTelemetry: StateFlow<Map<String, NodeTelemetryStore.NodeTelemetry>> = repository.nodeTelemetry
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    override fun onCleared() {
        super.onCleared()
        repository.destroy()
    }
}
