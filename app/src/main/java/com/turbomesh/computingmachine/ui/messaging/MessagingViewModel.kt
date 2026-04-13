package com.turbomesh.computingmachine.ui.messaging

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.turbomesh.computingmachine.data.MeshRepository
import com.turbomesh.computingmachine.mesh.MeshMessage
import com.turbomesh.computingmachine.mesh.MeshMessageType
import com.turbomesh.computingmachine.mesh.MeshNode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MessagingViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MeshRepository(application)

    /** DB-backed list of all messages (sent + received), oldest first. */
    val allMessages: StateFlow<List<MeshMessage>> = repository.allMessages
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val availableDestinations: StateFlow<List<String>> = combine(
        repository.provisionedNodes,
        repository.connectedDevices
    ) { provisioned, connected ->
        val destinations = mutableListOf(MeshMessage.BROADCAST_DESTINATION)
        destinations.addAll(connected.map { addr ->
            provisioned.firstOrNull { it.id == addr }?.displayName?.takeIf { it.isNotBlank() } ?: addr
        })
        destinations
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), listOf(MeshMessage.BROADCAST_DESTINATION))

    val connectedNodes: StateFlow<List<MeshNode>> = combine(
        repository.provisionedNodes,
        repository.connectedDevices
    ) { provisioned, connected ->
        provisioned.filter { it.id in connected }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var selectedDestination: String = MeshMessage.BROADCAST_DESTINATION

    fun selectDestination(destination: String) {
        selectedDestination = destination
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            val message = MeshMessage(
                sourceNodeId = "self",
                destinationNodeId = selectedDestination,
                type = MeshMessageType.DATA,
                payload = text.toByteArray(Charsets.UTF_8)
            )
            repository.sendMessage(message)
        }
    }

    override fun onCleared() {
        super.onCleared()
        repository.destroy()
    }
}

