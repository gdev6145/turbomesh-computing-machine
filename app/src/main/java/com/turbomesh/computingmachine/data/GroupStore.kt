package com.turbomesh.computingmachine.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Persists mesh groups (node channels) in SharedPreferences (feature 16).
 *
 * Storage format per key "group_<uuid>": "<name>|<member1>,<member2>,..."
 */
class GroupStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _groups = MutableStateFlow(loadAll())
    val groups: StateFlow<List<MeshGroup>> = _groups.asStateFlow()

    /** Create a new group and return its UUID. */
    fun createGroup(name: String, members: List<String> = emptyList()): String {
        val id = UUID.randomUUID().toString()
        save(MeshGroup(id, name, members.toMutableSet()))
        return id
    }

    /** Update an existing group (add/remove members or rename). */
    fun updateGroup(group: MeshGroup) {
        save(group)
    }

    fun deleteGroup(groupId: String) {
        prefs.edit().remove("group_$groupId").apply()
        _groups.value = _groups.value.filter { it.id != groupId }
    }

    fun getGroup(groupId: String): MeshGroup? = _groups.value.firstOrNull { it.id == groupId }

    fun addMember(groupId: String, nodeId: String) {
        val group = getGroup(groupId) ?: return
        save(group.copy(members = (group.members + nodeId).toMutableSet()))
    }

    private fun save(group: MeshGroup) {
        val value = "${group.name}|${group.members.joinToString(",")}"
        prefs.edit().putString("group_${group.id}", value).apply()
        val updated = _groups.value.toMutableList()
        val idx = updated.indexOfFirst { it.id == group.id }
        if (idx >= 0) updated[idx] = group else updated.add(group)
        _groups.value = updated
    }

    private fun loadAll(): List<MeshGroup> =
        prefs.all.entries
            .filter { it.key.startsWith("group_") }
            .mapNotNull { (key, value) ->
                val id = key.removePrefix("group_")
                val str = value as? String ?: return@mapNotNull null
                val pipeIdx = str.indexOf('|')
                if (pipeIdx < 0) return@mapNotNull null
                val name = str.substring(0, pipeIdx)
                val membersStr = str.substring(pipeIdx + 1)
                val members = if (membersStr.isBlank()) mutableSetOf()
                else membersStr.split(",").toMutableSet()
                MeshGroup(id, name, members)
            }

    companion object {
        private const val PREFS_NAME = "mesh_groups"
    }
}

data class MeshGroup(
    val id: String,
    val name: String,
    val members: MutableSet<String> = mutableSetOf()
)
