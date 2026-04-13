package com.turbomesh.computingmachine.mesh

import java.util.UUID

data class MeshMessage(
    val id: String = UUID.randomUUID().toString(),
    val sourceNodeId: String,
    val destinationNodeId: String,
    val type: MeshMessageType,
    val payload: ByteArray,
    val timestamp: Long = System.currentTimeMillis(),
    val hopCount: Int = 0,
    val ttl: Int = 7
) {
    companion object {
        const val BROADCAST_DESTINATION = "BROADCAST"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as MeshMessage
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
