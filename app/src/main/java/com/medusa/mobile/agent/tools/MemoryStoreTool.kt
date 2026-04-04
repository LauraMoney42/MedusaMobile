package com.medusa.mobile.agent.tools

import android.content.Context
import com.medusa.mobile.data.MedusaDatabase
import com.medusa.mobile.data.MemoryItem
import com.medusa.mobile.models.MemoryItemDTO
import com.medusa.mobile.models.MemoryListDTO
import com.medusa.mobile.models.ToolResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * mm-017 — MemoryStore Tool for Medusa Mobile.
 *
 * Gives Claude persistent memory across conversations via Room DB.
 * Claude can remember facts, preferences, reminders, and contextual data
 * that survive app restarts.
 *
 * Operations:
 *   - remember: Store or update a memory (key + value + category + tags)
 *   - recall:   Retrieve memories by key, category, or free-text search
 *   - forget:   Delete a specific memory by key, a category, or all
 *   - list:     Browse all memories or filter by category
 *
 * Examples of what Claude stores:
 *   "Remember I prefer window seats" → key: seat_preference, value: window, category: preference
 *   "Adam said bring wine for dinner" → key: dinner_friday_wine, value: Adam said bring wine, category: event
 *   "My mom's birthday is March 5" → key: mom_birthday, value: March 5, category: person
 *
 * No special permissions needed — this is app-private data in Room DB.
 */
class MemoryStoreTool(context: Context) {

    private val dao = MedusaDatabase.getInstance(context).memoryItemDao()

    // ── ISO 8601 formatter ───────────────────────────────────────────────

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private fun now(): String = isoFormat.format(Date())

    // ── Tool Execution ───────────────────────────────────────────────────

    /**
     * Store or update a memory. If a memory with the same key exists, it's updated.
     */
    suspend fun remember(
        key: String,
        value: String,
        category: String = "general",
        tags: String = ""
    ): ToolResult {
        if (key.isBlank()) return ToolResult.failure("Memory key cannot be empty.")
        if (value.isBlank()) return ToolResult.failure("Memory value cannot be empty.")

        val sanitizedKey = key.trim().lowercase().replace(Regex("[^a-z0-9_]"), "_")
        val timestamp = now()

        // Check if updating an existing memory
        val existing = dao.getByKey(sanitizedKey)
        val isUpdate = existing != null

        val item = MemoryItem(
            id = existing?.id ?: 0,
            key = sanitizedKey,
            value = value.trim(),
            category = category.trim().lowercase(),
            tags = tags.trim().lowercase(),
            createdAt = existing?.createdAt ?: timestamp,
            updatedAt = timestamp
        )

        dao.upsert(item)

        val verb = if (isUpdate) "Updated" else "Stored"
        val dto = item.toDTO()

        return ToolResult.success(
            summary = "$verb memory: [$sanitizedKey] = \"${value.take(60)}${if (value.length > 60) "..." else ""}\" (category: ${item.category})",
            data = dto
        )
    }

    /**
     * Retrieve memories — by exact key, by category, or by free-text search.
     */
    suspend fun recall(
        key: String? = null,
        category: String? = null,
        query: String? = null,
        limit: Int = 20
    ): ToolResult {
        val cap = limit.coerceIn(1, 100)

        // Exact key lookup — highest priority
        if (!key.isNullOrBlank()) {
            val sanitizedKey = key.trim().lowercase().replace(Regex("[^a-z0-9_]"), "_")
            val item = dao.getByKey(sanitizedKey)
            return if (item != null) {
                ToolResult.success(
                    summary = "Found memory: [${item.key}] = \"${item.value.take(80)}\"",
                    data = MemoryListDTO(count = 1, items = listOf(item.toDTO()))
                )
            } else {
                // Fall through to search — the key might be a natural language term
                val searchResults = dao.search(key.trim(), cap)
                if (searchResults.isNotEmpty()) {
                    val dtos = searchResults.map { it.toDTO() }
                    ToolResult.success(
                        summary = "No exact match for key '$sanitizedKey', but found ${dtos.size} related memory(ies).",
                        data = MemoryListDTO(count = dtos.size, items = dtos)
                    )
                } else {
                    ToolResult.success("No memories found matching '$sanitizedKey'.")
                }
            }
        }

        // Category filter
        if (!category.isNullOrBlank()) {
            val items = dao.getByCategory(category.trim().lowercase())
            val limited = items.take(cap)
            val dtos = limited.map { it.toDTO() }
            return if (dtos.isEmpty()) {
                ToolResult.success("No memories in category '${category.trim().lowercase()}'.")
            } else {
                ToolResult.success(
                    summary = "${dtos.size} memory(ies) in category '${category.trim().lowercase()}'.",
                    data = MemoryListDTO(count = dtos.size, items = dtos)
                )
            }
        }

        // Free-text search
        if (!query.isNullOrBlank()) {
            val items = dao.search(query.trim(), cap)
            val dtos = items.map { it.toDTO() }
            return if (dtos.isEmpty()) {
                ToolResult.success("No memories matching '${query.trim()}'.")
            } else {
                ToolResult.success(
                    summary = "${dtos.size} memory(ies) matching '${query.trim()}'.",
                    data = MemoryListDTO(count = dtos.size, items = dtos)
                )
            }
        }

        // No filters — return recent memories
        val items = dao.getRecent(cap)
        val dtos = items.map { it.toDTO() }
        return if (dtos.isEmpty()) {
            ToolResult.success("No memories stored yet.")
        } else {
            val categories = dao.getCategories()
            val total = dao.count()
            ToolResult.success(
                summary = "$total total memory(ies) across categories: ${categories.joinToString(", ")}. Showing ${dtos.size} most recent.",
                data = MemoryListDTO(count = dtos.size, items = dtos)
            )
        }
    }

    /**
     * Delete memories — by key, by category, or all.
     */
    suspend fun forget(
        key: String? = null,
        category: String? = null,
        all: Boolean = false
    ): ToolResult {
        // Safety: "forget everything" requires explicit all=true
        if (all) {
            val count = dao.count()
            dao.deleteAll()
            return ToolResult.success("Deleted all $count memory(ies). Memory store is now empty.")
        }

        // Delete by key
        if (!key.isNullOrBlank()) {
            val sanitizedKey = key.trim().lowercase().replace(Regex("[^a-z0-9_]"), "_")
            val deleted = dao.deleteByKey(sanitizedKey)
            return if (deleted > 0) {
                ToolResult.success("Forgot memory: [$sanitizedKey].")
            } else {
                ToolResult.success("No memory found with key '$sanitizedKey'. Nothing deleted.")
            }
        }

        // Delete by category
        if (!category.isNullOrBlank()) {
            val cat = category.trim().lowercase()
            val deleted = dao.deleteByCategory(cat)
            return ToolResult.success("Forgot $deleted memory(ies) in category '$cat'.")
        }

        return ToolResult.failure("Specify a key, category, or all=true to delete memories.")
    }

    // ── DTO Conversion ───────────────────────────────────────────────────

    private fun MemoryItem.toDTO() = MemoryItemDTO(
        key = key,
        value = value,
        category = category,
        tags = if (tags.isBlank()) emptyList() else tags.split(",").map { it.trim() },
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}
