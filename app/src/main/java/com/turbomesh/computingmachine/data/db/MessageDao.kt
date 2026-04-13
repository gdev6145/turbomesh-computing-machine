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
}
