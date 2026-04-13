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
    val ttl: Int = 7,
    val isAcknowledged: Boolean = false,
    /** Epoch-ms when the recipient read this message; null until confirmed (feature 2). */
    val readAtMs: Long? = null,
    /** True when this message is stored waiting for a route to its destination (feature 13). */
    val pendingDelivery: Boolean = false,
    /** ID of the message being replied to (feature 19). */
    val replyToMsgId: String? = null,
    /** True if this message has been edited (feature 20). */
    val isEdited: Boolean = false,
    /** Epoch-ms when this message was last edited (feature 20). */
    val editedAtMs: Long? = null,
    /** Epoch-ms when this message was deleted/recalled (feature 21). Null = not deleted. */
    val deletedAtMs: Long? = null,
    /** True if pinned in the conversation (feature 22). */
    val isPinned: Boolean = false,
    /** Epoch-ms when this message should be sent (feature 23). Null = send immediately. */
    val scheduledAtMs: Long? = null,
    /** Epoch-ms when this message expires (feature 24). Null = no expiry. */
    val expiresAtMs: Long? = null,
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
