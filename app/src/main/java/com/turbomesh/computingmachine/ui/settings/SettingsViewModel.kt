package com.turbomesh.computingmachine.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.turbomesh.computingmachine.data.MeshRepository
import com.turbomesh.computingmachine.data.MeshSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MeshRepository(application)

    val settings: StateFlow<MeshSettings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MeshSettings.DEFAULT)

    fun applySettings(settings: MeshSettings) {
        repository.updateSettings(settings)
    }

    fun resetToDefaults() {
        repository.updateSettings(MeshSettings.DEFAULT)
    }

    override fun onCleared() {
        super.onCleared()
        repository.destroy()
    }
}
