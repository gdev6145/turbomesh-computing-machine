package com.turbomesh.computingmachine.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists presence status strings for mesh nodes (feature 18).
 * The special key [MY_STATUS_KEY] holds the local user's own status line.
 */
class NodeStatusStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _statuses = MutableStateFlow(loadAll())
    val statuses: StateFlow<Map<String, String>> = _statuses.asStateFlow()

    fun getStatus(nodeId: String): String = _statuses.value[nodeId] ?: DEFAULT_STATUS

    fun setStatus(nodeId: String, status: String) {
        val trimmed = status.trim().take(80)
        prefs.edit().apply {
            if (trimmed.isBlank()) remove(nodeId) else putString(nodeId, trimmed)
            apply()
        }
        _statuses.value = loadAll()
    }

    fun getMyStatus(): String = getStatus(MY_STATUS_KEY)

    fun setMyStatus(status: String) = setStatus(MY_STATUS_KEY, status)

    private fun loadAll(): Map<String, String> =
        prefs.all.mapNotNull { (key, value) ->
            (value as? String)?.let { key to it }
        }.toMap()

    companion object {
        private const val PREFS_NAME = "node_statuses"
        const val MY_STATUS_KEY = "__self__"
        const val DEFAULT_STATUS = "Available"
    }
}
