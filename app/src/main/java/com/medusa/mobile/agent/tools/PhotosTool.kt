package com.medusa.mobile.agent.tools

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.medusa.mobile.models.PhotoDTO
import com.medusa.mobile.models.PhotoListDTO
import com.medusa.mobile.models.ToolResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * mm-018 — Photos / Media Tool for Medusa Mobile.
 *
 * Queries the device MediaStore for images. Returns photo metadata:
 * file name, album, size, dimensions, date taken, URI.
 *
 * Does NOT read file bytes — only metadata + content URI for display.
 * Claude can describe what photos exist, find by date/album, and surface URIs.
 *
 * Requires:
 *   Android < 13: READ_EXTERNAL_STORAGE
 *   Android 13+:  READ_MEDIA_IMAGES
 *
 * Claude tool name: "get_photos"
 */
class PhotosTool(private val context: Context) {

    // ── Claude Tool Definition ───────────────────────────────────────────────

    companion object {
        val claudeToolDefinition: Map<String, Any> = mapOf(
            "name" to "get_photos",
            "description" to """
                Searches and lists photos/images on the device.
                Returns metadata: file name, album/bucket, date taken, dimensions, size, content URI.
                Use this to answer "show me photos from last week", "how many pictures do I have from Paris?",
                "find photos in my Camera roll", "what photos do I have from July?", or
                "list my most recent pictures".
                Does NOT access file bytes — only metadata and content URIs.
                Requires READ_MEDIA_IMAGES (Android 13+) or READ_EXTERNAL_STORAGE permission.
            """.trimIndent(),
            "input_schema" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "query" to mapOf(
                        "type" to "string",
                        "description" to "Filter by album/bucket name or file name. Case-insensitive partial match. E.g. \"Camera\", \"Screenshots\", \"Paris\", \"IMG_2024\"."
                    ),
                    "limit" to mapOf(
                        "type" to "integer",
                        "description" to "Max photos to return. Default 20, max 100.",
                        "default" to 20
                    ),
                    "mode" to mapOf(
                        "type" to "string",
                        "enum" to listOf("recent", "album", "search", "all"),
                        "description" to "'recent' returns newest photos first (default). 'album' lists available albums. 'search' filters by query string. 'all' returns all ordered by date desc.",
                        "default" to "recent"
                    ),
                    "after_date" to mapOf(
                        "type" to "string",
                        "description" to "Optional ISO 8601 date filter — only return photos taken on or after this date. E.g. \"2024-01-01\"."
                    ),
                    "before_date" to mapOf(
                        "type" to "string",
                        "description" to "Optional ISO 8601 date filter — only return photos taken on or before this date. E.g. \"2024-12-31\"."
                    )
                ),
                "required" to emptyList<String>()
            )
        )

        private val ISO_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        private val DATETIME_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        // MediaStore columns we need
        private val PHOTO_PROJECTION = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.MIME_TYPE
        )
    }

    // ── Tool Execution ───────────────────────────────────────────────────────

    fun execute(
        query: String? = null,
        limit: Int = 20,
        mode: String = "recent",
        afterDate: String? = null,
        beforeDate: String? = null
    ): ToolResult {
        // Permission check — Android 13+ uses READ_MEDIA_IMAGES, older uses READ_EXTERNAL_STORAGE
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
            return ToolResult.denied(
                "Photos permission not granted. The user needs to allow ${
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        "READ_MEDIA_IMAGES" else "READ_EXTERNAL_STORAGE"
                } in Settings."
            )
        }

        val cap = limit.coerceIn(1, 100)

        return when (mode.lowercase()) {
            "album"  -> listAlbums(cap)
            "search" -> searchPhotos(query, cap, afterDate, beforeDate)
            "all"    -> queryPhotos(null, cap, afterDate, beforeDate)
            else     -> queryPhotos(null, cap, afterDate, beforeDate) // "recent" — date desc, no filter
        }
    }

    // ── Query Modes ──────────────────────────────────────────────────────────

    /**
     * Returns a deduplicated list of album (bucket) names with photo counts.
     * Useful when Claude needs to list what albums are available.
     */
    private fun listAlbums(limit: Int): ToolResult {
        val resolver = context.contentResolver
        val albumCounts = mutableMapOf<String, Int>()

        resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media.BUCKET_DISPLAY_NAME),
            null, null,
            "${MediaStore.Images.Media.DATE_TAKEN} DESC"
        )?.use { cursor ->
            val bucketIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val bucket = cursor.getString(bucketIdx) ?: "Unknown"
                albumCounts[bucket] = (albumCounts[bucket] ?: 0) + 1
            }
        }

        if (albumCounts.isEmpty()) {
            return ToolResult.success("No photo albums found on this device.")
        }

        // Sort by count desc, take top N
        val sorted = albumCounts.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { (name, count) -> mapOf("album" to name, "count" to count) }

        val summary = "${albumCounts.size} album(s) found. Showing top ${sorted.size}."
        // Return as PhotoListDTO with empty photos list — albums encoded in summary for now
        // Build a minimal DTO listing albums
        val albumList = sorted.map { entry ->
            PhotoDTO(
                id = 0L,
                displayName = entry["album"].toString(),
                album = entry["album"].toString(),
                dateTaken = null,
                dateModified = null,
                sizeBytes = (entry["count"] as Int).toLong(), // repurpose field as count
                widthPx = 0,
                heightPx = 0,
                mimeType = "album",
                contentUri = ""
            )
        }

        return ToolResult.success(
            summary = summary,
            data = PhotoListDTO(
                count = albumCounts.size,
                photos = albumList,
                note = "sizeBytes field contains photo count for album entries"
            )
        )
    }

    /**
     * Search photos by album name or file name.
     */
    private fun searchPhotos(
        query: String?,
        limit: Int,
        afterDate: String?,
        beforeDate: String?
    ): ToolResult {
        if (query.isNullOrBlank()) {
            return ToolResult.failure("'query' is required for search mode. Provide an album name or file name fragment.")
        }

        val selection = buildString {
            append("(${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} LIKE ? OR ${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?)")
            appendDateFilters(afterDate, beforeDate)
        }

        val selectionArgs = buildList {
            add("%$query%")
            add("%$query%")
            addDateArgs(afterDate, beforeDate)
        }.toTypedArray()

        return queryWithSelection(selection, selectionArgs, limit,
            "\"$query\"", afterDate, beforeDate)
    }

    /**
     * General photo query — recent or all, with optional date filters.
     */
    private fun queryPhotos(
        query: String?,
        limit: Int,
        afterDate: String?,
        beforeDate: String?
    ): ToolResult {
        val (selection, selectionArgs) = if (afterDate != null || beforeDate != null) {
            val sel = buildString { appendDateFilters(afterDate, beforeDate) }
                .removePrefix(" AND ")
            val args = buildList { addDateArgs(afterDate, beforeDate) }.toTypedArray()
            Pair(sel.ifBlank { null }, args)
        } else {
            Pair(null, emptyArray<String>())
        }

        return queryWithSelection(selection, selectionArgs, limit, null, afterDate, beforeDate)
    }

    // ── Core MediaStore Query ────────────────────────────────────────────────

    private fun queryWithSelection(
        selection: String?,
        selectionArgs: Array<String>,
        limit: Int,
        queryLabel: String?,
        afterDate: String?,
        beforeDate: String?
    ): ToolResult {
        val resolver = context.contentResolver
        val photos = mutableListOf<PhotoDTO>()

        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC LIMIT $limit"

        resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            PHOTO_PROJECTION,
            selection,
            selectionArgs.ifEmpty { null },
            sortOrder
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val bucketIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val dateTakenIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val dateModIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val widthIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val heightIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val mimeIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIdx)
                val contentUri = Uri.withAppendedPath(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString()
                ).toString()

                val dateTakenMs = cursor.getLong(dateTakenIdx)
                val dateModMs = cursor.getLong(dateModIdx) * 1000L // stored as seconds

                photos.add(PhotoDTO(
                    id = id,
                    displayName = cursor.getString(nameIdx) ?: "unknown",
                    album = cursor.getString(bucketIdx) ?: "Unknown",
                    dateTaken = if (dateTakenMs > 0) DATETIME_FORMAT.format(Date(dateTakenMs)) else null,
                    dateModified = if (dateModMs > 0) DATETIME_FORMAT.format(Date(dateModMs)) else null,
                    sizeBytes = cursor.getLong(sizeIdx),
                    widthPx = cursor.getInt(widthIdx),
                    heightPx = cursor.getInt(heightIdx),
                    mimeType = cursor.getString(mimeIdx) ?: "image/*",
                    contentUri = contentUri
                ))
            }
        }

        if (photos.isEmpty()) {
            val label = queryLabel?.let { " matching $it" } ?: ""
            val dateRange = buildDateRangeLabel(afterDate, beforeDate)
            return ToolResult.success("No photos found$label$dateRange.")
        }

        val label = queryLabel?.let { " matching $it" } ?: ""
        val dateRange = buildDateRangeLabel(afterDate, beforeDate)
        val summary = "${photos.size} photo(s)$label$dateRange."

        return ToolResult.success(
            summary = summary,
            data = PhotoListDTO(count = photos.size, photos = photos)
        )
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Appends " AND date_taken >= ? AND date_taken <= ?" as needed.
     * Called inside buildString — appends to the existing string.
     */
    private fun StringBuilder.appendDateFilters(afterDate: String?, beforeDate: String?): StringBuilder {
        afterDate?.let {
            val ms = parseIsoDate(it)
            if (ms != null) append(" AND ${MediaStore.Images.Media.DATE_TAKEN} >= $ms")
        }
        beforeDate?.let {
            val ms = parseIsoDate(it)
            if (ms != null) {
                // end of that day
                val endMs = ms + 86_400_000L - 1
                append(" AND ${MediaStore.Images.Media.DATE_TAKEN} <= $endMs")
            }
        }
        return this
    }

    private fun MutableList<String>.addDateArgs(afterDate: String?, beforeDate: String?) {
        // Date filters are baked into the selection string as literals, not args
        // (ms values embedded directly — safe since they're parsed from our own ISO input)
    }

    private fun parseIsoDate(isoDate: String): Long? {
        return try {
            ISO_FORMAT.parse(isoDate)?.time
        } catch (_: Exception) { null }
    }

    private fun buildDateRangeLabel(afterDate: String?, beforeDate: String?): String {
        return when {
            afterDate != null && beforeDate != null -> " from $afterDate to $beforeDate"
            afterDate != null -> " after $afterDate"
            beforeDate != null -> " before $beforeDate"
            else -> ""
        }
    }
}
