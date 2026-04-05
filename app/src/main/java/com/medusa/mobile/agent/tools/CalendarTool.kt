package com.medusa.mobile.agent.tools

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.*

// CalendarTool — reads and writes Android Calendar events via CalendarContract.
//
// Why ContentProvider and not Google Calendar API?
//   CalendarContract is the local calendar store. It works offline and syncs
//   automatically with Google Calendar, iCloud, Exchange, etc. No Google
//   account sign-in ceremony — the OS handles it. Fast ContentResolver queries.
//
// Permissions (already declared in AndroidManifest.xml):
//   READ_CALENDAR  — list, search
//   WRITE_CALENDAR — create, delete

class CalendarTool(private val context: Context) {

    // ── Data models ─────────────────────────────────────────────────────────

    @Serializable
    data class CalendarEvent(
        val id: Long,
        val title: String,
        val description: String,
        val location: String,
        val startTime: String,      // ISO-8601 local
        val endTime: String,
        val startMillis: Long,
        val endMillis: Long,
        val allDay: Boolean,
        val calendarName: String,
        val organizer: String,
        val status: String          // "confirmed" | "tentative" | "cancelled"
    )

    @Serializable
    data class CalendarResult(
        val success: Boolean,
        val events: List<CalendarEvent> = emptyList(),
        val count: Int = 0,
        val message: String = "",
        val eventId: Long? = null   // populated on create_event
    )

    private val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)
    private fun Long.toIso(): String = isoFmt.format(Date(this))

    // ── Permission guards ────────────────────────────────────────────────────

    private fun canRead() = ContextCompat.checkSelfPermission(
        context, Manifest.permission.READ_CALENDAR
    ) == PackageManager.PERMISSION_GRANTED

    private fun canWrite() = ContextCompat.checkSelfPermission(
        context, Manifest.permission.WRITE_CALENDAR
    ) == PackageManager.PERMISSION_GRANTED

    // ── Calendar name lookup (cached per call via map) ───────────────────────

    private fun calendarName(calId: Long): String = try {
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.ACCOUNT_NAME),
            "${CalendarContract.Calendars._ID} = ?",
            arrayOf(calId.toString()),
            null
        )?.use { c ->
            if (c.moveToFirst()) c.getString(0) ?: c.getString(1) ?: "Calendar" else "Unknown"
        } ?: "Unknown"
    } catch (_: Exception) { "Unknown" }

    // ── list_events ──────────────────────────────────────────────────────────

    /**
     * Returns upcoming (and optionally recent) events across all calendars.
     *
     * @param daysAhead  Days into the future to include. Default 7.
     * @param daysBehind Days in the past to include. Default 0 (future only).
     * @param limit      Max events. Default 20.
     */
    fun listEvents(daysAhead: Int = 7, daysBehind: Int = 0, limit: Int = 20): CalendarResult {
        if (!canRead()) return CalendarResult(
            success = false,
            message = "READ_CALENDAR permission not granted. User must allow it in Settings."
        )
        return try {
            val now   = System.currentTimeMillis()
            val start = now - daysBehind * 86_400_000L
            val end   = now + daysAhead  * 86_400_000L
            val events = queryEvents(
                selection = "${CalendarContract.Events.DTSTART} >= ? AND " +
                        "${CalendarContract.Events.DTSTART} <= ? AND " +
                        "${CalendarContract.Events.DELETED} = 0",
                args = arrayOf(start.toString(), end.toString()),
                sort = "${CalendarContract.Events.DTSTART} ASC",
                limit = limit
            )
            CalendarResult(success = true, events = events, count = events.size)
        } catch (e: Exception) {
            CalendarResult(success = false, message = "list_events failed: ${e.message}")
        }
    }

    // ── search_events ────────────────────────────────────────────────────────

    /**
     * Searches events by keyword (title, description, location) with optional date range.
     *
     * @param query     Case-insensitive substring. Optional.
     * @param startDate "yyyy-MM-dd" lower bound. Optional.
     * @param endDate   "yyyy-MM-dd" upper bound. Optional.
     * @param limit     Max events. Default 20.
     */
    fun searchEvents(
        query: String?,
        startDate: String? = null,
        endDate: String? = null,
        limit: Int = 20
    ): CalendarResult {
        if (!canRead()) return CalendarResult(
            success = false, message = "READ_CALENDAR permission not granted."
        )
        return try {
            val conditions = mutableListOf("${CalendarContract.Events.DELETED} = 0")
            val args = mutableListOf<String>()
            val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

            if (!query.isNullOrBlank()) {
                val like = "%$query%"
                conditions += "(${CalendarContract.Events.TITLE} LIKE ? OR " +
                        "${CalendarContract.Events.DESCRIPTION} LIKE ? OR " +
                        "${CalendarContract.Events.EVENT_LOCATION} LIKE ?)"
                args += listOf(like, like, like)
            }
            if (!startDate.isNullOrBlank()) {
                val cal = Calendar.getInstance().apply {
                    time = dayFmt.parse(startDate) ?: Date()
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
                }
                conditions += "${CalendarContract.Events.DTSTART} >= ?"
                args += cal.timeInMillis.toString()
            }
            if (!endDate.isNullOrBlank()) {
                val cal = Calendar.getInstance().apply {
                    time = dayFmt.parse(endDate) ?: Date()
                    set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59)
                }
                conditions += "${CalendarContract.Events.DTSTART} <= ?"
                args += cal.timeInMillis.toString()
            }

            val events = queryEvents(
                selection = conditions.joinToString(" AND "),
                args = args.toTypedArray(),
                sort = "${CalendarContract.Events.DTSTART} ASC",
                limit = limit
            )
            CalendarResult(success = true, events = events, count = events.size)
        } catch (e: Exception) {
            CalendarResult(success = false, message = "search_events failed: ${e.message}")
        }
    }

    // ── create_event ─────────────────────────────────────────────────────────

    /**
     * Creates a new calendar event. Accepts ISO-8601 or "yyyy-MM-dd HH:mm" strings.
     *
     * @param title       Required.
     * @param startIso    Start time — "2024-03-15T14:00:00" or "2024-03-15 14:00".
     * @param endIso      End time — same format.
     * @param description Optional notes.
     * @param location    Optional place string.
     * @param allDay      Creates an all-day event when true.
     * @param calendarId  Target calendar ID. Uses primary calendar if null.
     */
    fun createEvent(
        title: String,
        startIso: String,
        endIso: String,
        description: String? = null,
        location: String? = null,
        allDay: Boolean = false,
        calendarId: Long? = null
    ): CalendarResult {
        if (!canWrite()) return CalendarResult(
            success = false, message = "WRITE_CALENDAR permission not granted."
        )
        return try {
            val startMs = parseDateTime(startIso)
                ?: return CalendarResult(success = false, message = "Could not parse start time: $startIso")
            val endMs = parseDateTime(endIso)
                ?: return CalendarResult(success = false, message = "Could not parse end time: $endIso")

            val calId = calendarId ?: primaryCalendarId()
                ?: return CalendarResult(success = false, message = "No calendar account found on device.")

            val values = ContentValues().apply {
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DTSTART, startMs)
                put(CalendarContract.Events.DTEND, endMs)
                put(CalendarContract.Events.CALENDAR_ID, calId)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                if (!description.isNullOrBlank()) put(CalendarContract.Events.DESCRIPTION, description)
                if (!location.isNullOrBlank()) put(CalendarContract.Events.EVENT_LOCATION, location)
                if (allDay) put(CalendarContract.Events.ALL_DAY, 1)
            }

            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            val id = uri?.lastPathSegment?.toLongOrNull()
                ?: return CalendarResult(success = false, message = "Insert returned null — calendar may be read-only.")

            CalendarResult(
                success = true,
                eventId = id,
                message = "Created event \"$title\" on ${startMs.toIso()}"
            )
        } catch (e: Exception) {
            CalendarResult(success = false, message = "create_event failed: ${e.message}")
        }
    }

    // ── delete_event ─────────────────────────────────────────────────────────

    /**
     * Deletes an event by its ID. Get IDs from list_events / search_events results.
     * Claude must confirm with the user before calling this.
     */
    fun deleteEvent(eventId: Long): CalendarResult {
        if (!canWrite()) return CalendarResult(
            success = false, message = "WRITE_CALENDAR permission not granted."
        )
        return try {
            val uri = CalendarContract.Events.CONTENT_URI.buildUpon()
                .appendPath(eventId.toString()).build()
            val deleted = context.contentResolver.delete(uri, null, null)
            if (deleted > 0)
                CalendarResult(success = true, message = "Event $eventId deleted.")
            else
                CalendarResult(success = false, message = "Event $eventId not found or already deleted.")
        } catch (e: Exception) {
            CalendarResult(success = false, message = "delete_event failed: ${e.message}")
        }
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    private fun queryEvents(
        selection: String,
        args: Array<String>,
        sort: String,
        limit: Int
    ): List<CalendarEvent> {
        val proj = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.ORGANIZER,
            CalendarContract.Events.STATUS
        )
        val cursor = context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI, proj, selection, args, "$sort LIMIT $limit"
        ) ?: return emptyList()

        val nameCache = mutableMapOf<Long, String>()
        val events = mutableListOf<CalendarEvent>()

        cursor.use { c ->
            while (c.moveToNext()) {
                val calId = c.getLong(7)
                events += CalendarEvent(
                    id           = c.getLong(0),
                    title        = c.getString(1) ?: "(No title)",
                    description  = c.getString(2) ?: "",
                    location     = c.getString(3) ?: "",
                    startMillis  = c.getLong(4),
                    endMillis    = c.getLong(5),
                    startTime    = c.getLong(4).toIso(),
                    endTime      = c.getLong(5).toIso(),
                    allDay       = c.getInt(6) == 1,
                    calendarName = nameCache.getOrPut(calId) { calendarName(calId) },
                    organizer    = c.getString(8) ?: "",
                    status       = when (c.getInt(9)) {
                        CalendarContract.Events.STATUS_TENTATIVE -> "tentative"
                        CalendarContract.Events.STATUS_CANCELED  -> "cancelled"
                        else                                     -> "confirmed"
                    }
                )
            }
        }
        return events
    }

    private fun primaryCalendarId(): Long? {
        // Try IS_PRIMARY = 1 first, then fall back to first visible calendar
        return context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID),
            "${CalendarContract.Calendars.IS_PRIMARY} = 1 AND ${CalendarContract.Calendars.VISIBLE} = 1",
            null, null
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
            ?: context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID),
                "${CalendarContract.Calendars.VISIBLE} = 1",
                null, null
            )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
    }

    private fun parseDateTime(s: String): Long? {
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ssZ",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy-MM-dd"
        )
        for (fmt in formats) {
            try {
                return SimpleDateFormat(fmt, Locale.US).parse(s.trim())?.time
            } catch (_: Exception) { }
        }
        return null
    }
}
