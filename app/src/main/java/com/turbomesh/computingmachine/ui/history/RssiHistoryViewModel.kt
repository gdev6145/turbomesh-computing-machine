package com.turbomesh.computingmachine.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.turbomesh.computingmachine.data.MeshRepository
import com.turbomesh.computingmachine.data.db.RssiLogEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

class RssiHistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MeshRepository(application)

    private var _rssiHistory: StateFlow<List<RssiLogEntity>> =
        flowOf(emptyList<RssiLogEntity>())
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val rssiHistory: StateFlow<List<RssiLogEntity>> get() = _rssiHistory

    fun init(nodeId: String) {
        _rssiHistory = repository.rssiLogDaoPublic.getHistory(nodeId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    }

    override fun onCleared() {
        super.onCleared()
        repository.destroy()
    }
}
