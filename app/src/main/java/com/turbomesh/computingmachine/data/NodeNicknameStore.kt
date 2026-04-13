package com.turbomesh.computingmachine.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists user-assigned nicknames for mesh nodes using SharedPreferences.
 * Exposes a [StateFlow] of the current nickname map so observers can react
 * to changes without polling.
 */
class NodeNicknameStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _nicknames = MutableStateFlow(loadAll())
    val nicknames: StateFlow<Map<String, String>> = _nicknames.asStateFlow()

    fun getNickname(nodeId: String): String = _nicknames.value[nodeId] ?: ""

    fun setNickname(nodeId: String, nickname: String) {
        val trimmed = nickname.trim()
        prefs.edit().apply {
            if (trimmed.isBlank()) remove(nodeId) else putString(nodeId, trimmed)
            apply()
        }
        _nicknames.value = loadAll()
    }

    private fun loadAll(): Map<String, String> =
        prefs.all.mapNotNull { (key, value) ->
            (value as? String)?.let { key to it }
        }.toMap()

    companion object {
        private const val PREFS_NAME = "node_nicknames"
    }
}
