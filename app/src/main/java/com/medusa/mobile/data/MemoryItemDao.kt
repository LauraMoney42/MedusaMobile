package com.medusa.mobile.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

/**
 * mm-017 — Data Access Object for the memories table.
 *
 * All queries are suspend functions — Room handles the IO dispatcher
 * automatically via room-ktx. Safe to call from any coroutine scope.
 */
@Dao
interface MemoryItemDao {

    // ── Write Operations ─────────────────────────────────────────────────

    /** Insert or replace a memory (upsert by unique key). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: MemoryItem): Long

    /** Update an existing memory. */
    @Update
    suspend fun update(item: MemoryItem)

    /** Delete a specific memory. */
    @Delete
    suspend fun delete(item: MemoryItem)

    /** Delete a memory by its key. Returns number of rows deleted (0 or 1). */
    @Query("DELETE FROM memories WHERE key = :key")
    suspend fun deleteByKey(key: String): Int

    /** Delete all memories in a category. Returns rows deleted. */
    @Query("DELETE FROM memories WHERE category = :category")
    suspend fun deleteByCategory(category: String): Int

    /** Nuke everything — use with caution (e.g. "forget everything about me"). */
    @Query("DELETE FROM memories")
    suspend fun deleteAll()

    // ── Read Operations ──────────────────────────────────────────────────

    /** Get a single memory by exact key match. */
    @Query("SELECT * FROM memories WHERE key = :key LIMIT 1")
    suspend fun getByKey(key: String): MemoryItem?

    /** Get all memories in a category, newest first. */
    @Query("SELECT * FROM memories WHERE category = :category ORDER BY updatedAt DESC")
    suspend fun getByCategory(category: String): List<MemoryItem>

    /** Get all memories, newest first. */
    @Query("SELECT * FROM memories ORDER BY updatedAt DESC")
    suspend fun getAll(): List<MemoryItem>

    /** Get all memories, newest first, with limit. */
    @Query("SELECT * FROM memories ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<MemoryItem>

    /** Full-text search across key, value, and tags. Case-insensitive via LIKE. */
    @Query("""
        SELECT * FROM memories
        WHERE key LIKE '%' || :query || '%'
           OR value LIKE '%' || :query || '%'
           OR tags LIKE '%' || :query || '%'
           OR category LIKE '%' || :query || '%'
        ORDER BY updatedAt DESC
        LIMIT :limit
    """)
    suspend fun search(query: String, limit: Int = 50): List<MemoryItem>

    /** Count all memories. */
    @Query("SELECT COUNT(*) FROM memories")
    suspend fun count(): Int

    /** Get all distinct categories. */
    @Query("SELECT DISTINCT category FROM memories ORDER BY category")
    suspend fun getCategories(): List<String>
}
