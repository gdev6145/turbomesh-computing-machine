package com.turbomesh.computingmachine.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [MessageEntity::class, RssiLogEntity::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao
    abstract fun rssiLogDao(): RssiLogDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE messages ADD COLUMN readAtMs INTEGER")
                database.execSQL("ALTER TABLE messages ADD COLUMN pendingDelivery INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE messages ADD COLUMN replyToMsgId TEXT")
                database.execSQL("ALTER TABLE messages ADD COLUMN isEdited INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE messages ADD COLUMN editedAtMs INTEGER")
                database.execSQL("ALTER TABLE messages ADD COLUMN deletedAtMs INTEGER")
                database.execSQL("ALTER TABLE messages ADD COLUMN isPinned INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE messages ADD COLUMN scheduledAtMs INTEGER")
                database.execSQL("ALTER TABLE messages ADD COLUMN expiresAtMs INTEGER")
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS rssi_log (" +
                    "rowId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "nodeId TEXT NOT NULL, " +
                    "rssi INTEGER NOT NULL, " +
                    "timestampMs INTEGER NOT NULL)"
                )
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "turbomesh.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build().also { INSTANCE = it }
            }
        }
    }
}
