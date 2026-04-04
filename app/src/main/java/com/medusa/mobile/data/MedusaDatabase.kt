package com.medusa.mobile.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * mm-017 — Room database for Medusa Mobile.
 *
 * Currently holds only the memories table, but designed to grow —
 * conversation history, cached tool results, user preferences, etc.
 * can all be added as new entities in future migrations.
 *
 * Singleton pattern via [getInstance] — one DB connection for the app.
 */
@Database(
    entities = [MemoryItem::class],
    version = 1,
    exportSchema = false
)
abstract class MedusaDatabase : RoomDatabase() {

    abstract fun memoryItemDao(): MemoryItemDao

    companion object {
        @Volatile
        private var INSTANCE: MedusaDatabase? = null

        /**
         * Returns the singleton database instance, creating it if needed.
         * Thread-safe via double-checked locking.
         */
        fun getInstance(context: Context): MedusaDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    MedusaDatabase::class.java,
                    "medusa_db"
                )
                    // Destructive migration for v1 — no user data to preserve yet.
                    // Replace with proper Migration objects once schema stabilizes.
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
