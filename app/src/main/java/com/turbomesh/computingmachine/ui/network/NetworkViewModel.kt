package com.turbomesh.computingmachine.ui.network

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.turbomesh.computingmachine.data.MeshRepository
import com.turbomesh.computingmachine.data.models.NetworkStats
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

    override fun onCleared() {
        super.onCleared()
        repository.destroy()
    }
}

