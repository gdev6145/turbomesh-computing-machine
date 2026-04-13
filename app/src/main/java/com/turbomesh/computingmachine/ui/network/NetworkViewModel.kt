package com.turbomesh.computingmachine.ui.network

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.turbomesh.computingmachine.data.MeshRepository
import com.turbomesh.computingmachine.data.models.NetworkStats
import com.turbomesh.computingmachine.data.models.NodeStats
import com.turbomesh.computingmachine.mesh.MeshNode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class NetworkViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MeshRepository(application)

    val networkStats: StateFlow<NetworkStats> = repository.networkStats
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NetworkStats())

    val activeNodes: StateFlow<List<MeshNode>> = repository.provisionedNodes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Node IDs currently in the mesh routing table. */
    val knownNodes: StateFlow<Set<String>> = repository.knownNodes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    /** Per-node sent/received counters. */
    val perNodeStats: StateFlow<Map<String, NodeStats>> = repository.perNodeStats
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** Rolling history of network health readings (0.0–1.0), newest last, up to 20 entries. */
    private val _healthHistory = MutableStateFlow<List<Float>>(emptyList())
    val healthHistory: StateFlow<List<Float>> = _healthHistory

    init {
        viewModelScope.launch {
            networkStats.collect { stats ->
                _healthHistory.value = (_healthHistory.value + stats.networkHealth).takeLast(20)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        repository.destroy()
    }
}


import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.turbomesh.computingmachine.data.MeshRepository
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

    /** Node IDs currently in the mesh routing table. */
    val knownNodes: StateFlow<Set<String>> = repository.knownNodes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    /** Per-node sent/received counters. */
    val perNodeStats: StateFlow<Map<String, NodeStats>> = repository.perNodeStats
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    override fun onCleared() {
        super.onCleared()
        repository.destroy()
    }
}
