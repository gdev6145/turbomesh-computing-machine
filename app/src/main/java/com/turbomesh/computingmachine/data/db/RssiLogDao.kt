package com.turbomesh.computingmachine.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RssiLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: RssiLogEntity)

    @Query("SELECT * FROM rssi_log WHERE nodeId = :nodeId ORDER BY timestampMs DESC LIMIT :limit")
    fun getHistory(nodeId: String, limit: Int = 200): Flow<List<RssiLogEntity>>

    @Query("SELECT * FROM rssi_log WHERE nodeId = :nodeId ORDER BY timestampMs DESC LIMIT :limit")
    suspend fun getHistoryOnce(nodeId: String, limit: Int = 200): List<RssiLogEntity>

    @Query("DELETE FROM rssi_log WHERE timestampMs < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long)

    @Query("DELETE FROM rssi_log")
    suspend fun deleteAll()
}
