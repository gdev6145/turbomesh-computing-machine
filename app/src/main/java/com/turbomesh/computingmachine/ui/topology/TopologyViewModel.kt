package com.turbomesh.computingmachine.ui.topology

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.turbomesh.computingmachine.data.MeshRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class TopologyViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MeshRepository(application)

    data class TopologyState(
        val nodes: List<TopologyView.TopologyNode>,
        val edges: List<TopologyView.TopologyEdge>
    )

    val topologyState: StateFlow<TopologyState> = combine(
        repository.provisionedNodes,
        repository.knownNodes
    ) { nodes, knownNodeIds ->
        val topoNodes = nodes.map { n ->
            TopologyView.TopologyNode(id = n.id, label = n.displayName)
        }
        val edges = nodes
            .filter { it.isConnected }
            .map { n -> TopologyView.TopologyEdge(fromId = "self", toId = n.id, rssi = n.rssi) }
        TopologyState(topoNodes, edges)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TopologyState(emptyList(), emptyList()))

    override fun onCleared() {
        super.onCleared()
        repository.destroy()
    }
}
