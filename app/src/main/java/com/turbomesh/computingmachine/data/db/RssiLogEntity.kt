package com.turbomesh.computingmachine.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "rssi_log")
data class RssiLogEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val nodeId: String,
    val rssi: Int,
    val timestampMs: Long
)
