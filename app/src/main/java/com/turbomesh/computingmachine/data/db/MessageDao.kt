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

    @Query("DELETE FROM messages")
    suspend fun deleteAll()
}
