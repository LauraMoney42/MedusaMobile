package com.medusa.mobile.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * mm-017 — Persistent memory entity for Medusa's long-term recall.
 *
 * Stores facts, preferences, reminders, and contextual data that persist
 * across conversations. Claude uses this to "remember" things the user
 * tells it — "remember I like window seats", "my mom's birthday is March 5",
 * "Adam said to bring wine for dinner".
 *
 * Indexed on [key] for fast lookups and [category] for group queries.
 * Full-text search supported via LIKE queries on key + value + tags.
 */
@Entity(
    tableName = "memories",
    indices = [
        Index(value = ["key"], unique = true),
        Index(value = ["category"])
    ]
)
data class MemoryItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Short identifier — e.g. "mom_birthday", "seat_preference", "dinner_friday" */
    val key: String,

    /** The actual memory content — free-form text. */
    val value: String,

    /** Grouping label — "preference", "fact", "reminder", "person", "event", etc. */
    val category: String = "general",

    /** Comma-separated tags for flexible search — "food,dinner,adam" */
    val tags: String = "",

    /** ISO 8601 timestamp when first stored. */
    val createdAt: String,

    /** ISO 8601 timestamp of last update. */
    val updatedAt: String
)
