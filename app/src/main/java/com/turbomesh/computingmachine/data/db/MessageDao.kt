package com.turbomesh.computingmachine.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages ORDER BY timestamp ASC")
    fun getAllMessages(): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Query("UPDATE messages SET isAcknowledged = 1 WHERE id = :messageId")
    suspend fun markAcknowledged(messageId: String)

    /** Feature 2 – mark a message as read by the recipient. */
    @Query("UPDATE messages SET readAtMs = :readAtMs WHERE id = :messageId")
    suspend fun markRead(messageId: String, readAtMs: Long)

    /** Feature 13 – clear the pending-delivery flag once the message is sent. */
    @Query("UPDATE messages SET pendingDelivery = 0 WHERE id = :messageId")
    suspend fun clearPendingDelivery(messageId: String)

    /** Feature 13 – all messages queued for delivery to a specific destination. */
    @Query("SELECT * FROM messages WHERE pendingDelivery = 1 AND destinationNodeId = :destinationId ORDER BY timestamp ASC")
    suspend fun getPendingMessages(destinationId: String): List<MessageEntity>

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM messages")
    suspend fun deleteAll()

    /** Feature 20 – mark a message as edited. */
    @Query("UPDATE messages SET payload = :newPayload, isEdited = 1, editedAtMs = :editedAtMs WHERE id = :messageId")
    suspend fun markEdited(messageId: String, newPayload: ByteArray, editedAtMs: Long)

    /** Feature 21 – soft-delete a message (deleted for everyone). */
    @Query("UPDATE messages SET deletedAtMs = :deletedAtMs WHERE id = :messageId")
    suspend fun markDeleted(messageId: String, deletedAtMs: Long)

    /** Feature 22 – toggle pin state for a message. */
    @Query("UPDATE messages SET isPinned = :pinned WHERE id = :messageId")
    suspend fun setPinned(messageId: String, pinned: Boolean)

    /** Feature 22 – get all pinned messages in order. */
    @Query("SELECT * FROM messages WHERE isPinned = 1 ORDER BY timestamp ASC")
    fun getPinnedMessages(): Flow<List<MessageEntity>>

    /** Feature 23 – get scheduled messages that are due. */
    @Query("SELECT * FROM messages WHERE scheduledAtMs IS NOT NULL AND scheduledAtMs <= :nowMs AND pendingDelivery = 1 ORDER BY scheduledAtMs ASC")
    suspend fun getDueScheduledMessages(nowMs: Long): List<MessageEntity>

    /** Feature 24 – delete messages whose expiry time has passed. */
    @Query("DELETE FROM messages WHERE expiresAtMs IS NOT NULL AND expiresAtMs <= :nowMs")
    suspend fun deleteExpiredMessages(nowMs: Long)
}
