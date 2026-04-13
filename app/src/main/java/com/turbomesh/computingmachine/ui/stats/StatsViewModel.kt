package com.turbomesh.computingmachine.ui.stats

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.turbomesh.computingmachine.data.DeliveryStatsStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class StatsViewModel(application: Application) : AndroidViewModel(application) {

    private val deliveryStatsStore = DeliveryStatsStore(application)

    private val _stats = MutableStateFlow<List<DeliveryStatsStore.NodeDeliveryStats>>(emptyList())
    val stats: StateFlow<List<DeliveryStatsStore.NodeDeliveryStats>> = _stats

    init {
        viewModelScope.launch {
            while (true) {
                refresh()
                delay(5000)
            }
        }
    }

    fun refresh() {
        _stats.value = deliveryStatsStore.allStats().values.sortedByDescending { it.sent }
    }

    fun resetAll() {
        deliveryStatsStore.resetAll()
        _stats.value = emptyList()
    }
}
