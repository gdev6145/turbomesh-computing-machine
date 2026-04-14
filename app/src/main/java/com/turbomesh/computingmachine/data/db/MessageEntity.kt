package com.turbomesh.computingmachine.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.turbomesh.computingmachine.mesh.MeshMessage
import com.turbomesh.computingmachine.mesh.MeshMessageType

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val sourceNodeId: String,
    val destinationNodeId: String,
    val type: String,
    val payload: ByteArray,
    val timestamp: Long,
    val hopCount: Int,
    val ttl: Int,
    val isAcknowledged: Boolean = false,
    /** Epoch-ms when the recipient confirmed they read this message (feature 2). Null = unread. */
    val readAtMs: Long? = null,
    /** True when message could not be routed and is queued for delivery (feature 13). */
    val pendingDelivery: Boolean = false,
    val replyToMsgId: String? = null,
    val isEdited: Boolean = false,
    val editedAtMs: Long? = null,
    val deletedAtMs: Long? = null,
    val isPinned: Boolean = false,
    val scheduledAtMs: Long? = null,
    val expiresAtMs: Long? = null,
) {
    fun toMeshMessage(): MeshMessage = MeshMessage(
        id = id,
        sourceNodeId = sourceNodeId,
        destinationNodeId = destinationNodeId,
        type = MeshMessageType.valueOf(type),
        payload = payload,
        timestamp = timestamp,
        hopCount = hopCount,
        ttl = ttl,
        isAcknowledged = isAcknowledged,
        readAtMs = readAtMs,
        pendingDelivery = pendingDelivery,
        replyToMsgId = replyToMsgId,
        isEdited = isEdited,
        editedAtMs = editedAtMs,
        deletedAtMs = deletedAtMs,
        isPinned = isPinned,
        scheduledAtMs = scheduledAtMs,
        expiresAtMs = expiresAtMs,
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as MessageEntity
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}

fun MeshMessage.toEntity(): MessageEntity = MessageEntity(
    id = id,
    sourceNodeId = sourceNodeId,
    destinationNodeId = destinationNodeId,
    type = type.name,
    payload = payload,
    timestamp = timestamp,
    hopCount = hopCount,
    ttl = ttl,
    isAcknowledged = isAcknowledged,
    readAtMs = readAtMs,
    pendingDelivery = pendingDelivery,
    replyToMsgId = replyToMsgId,
    isEdited = isEdited,
    editedAtMs = editedAtMs,
    deletedAtMs = deletedAtMs,
    isPinned = isPinned,
    scheduledAtMs = scheduledAtMs,
    expiresAtMs = expiresAtMs,
)
