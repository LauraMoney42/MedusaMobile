package com.medusa.mobile.agent.tools

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.medusa.mobile.models.CallDTO
import com.medusa.mobile.models.CallListDTO
import com.medusa.mobile.models.ToolResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * mm-007 — Call History Tool for Medusa Mobile.
 *
 * Reads call log via Android's CallLog ContentProvider.
 * Requires: android.permission.READ_CALL_LOG + android.permission.READ_CONTACTS
 *
 * This is what iOS CANNOT do. Android gives us full call history — every
 * incoming, outgoing, missed, rejected, blocked, and voicemail call with
 * duration, timestamp, and contact resolution. Zero setup. Native API.
 *
 * Claude tool name: "get_call_history"
 */
class CallHistoryTool(private val context: Context) {

    // ── Claude Tool Definition ───────────────────────────────────────────────

    companion object {
        val claudeToolDefinition: Map<String, Any> = mapOf(
            "name" to "get_call_history",
            "description" to """
                Returns the user's phone call history — incoming, outgoing, missed,
                rejected, blocked, and voicemail calls. Includes caller name (from
                contacts), phone number, duration, and timestamp.
                Use this to answer questions like "who called me today?",
                "did Mom call?", "any missed calls?", "how long was my call with
                the doctor?", or "show me my recent calls".
                Returns calls ordered newest-first.
                Requires READ_CALL_LOG permission (requested automatically on first use).
                Unlike iOS, this reads FULL historical call logs — not just new calls.
            """.trimIndent(),
            "input_schema" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "limit" to mapOf(
                        "type" to "integer",
                        "description" to "Maximum number of calls to return. Default 20, max 100.",
                        "default" to 20
                    ),
                    "caller" to mapOf(
                        "type" to "string",
                        "description" to "Optional. Filter to calls from/to contacts whose name or number contains this string (case-insensitive). E.g. \"Mom\", \"555-1234\"."
                    ),
                    "direction" to mapOf(
                        "type" to "string",
                        "enum" to listOf("all", "incoming", "outgoing", "missed", "rejected", "blocked", "voicemail"),
                        "description" to "Optional. Filter by call type. Default \"all\".",
                        "default" to "all"
                    ),
                    "since_hours" to mapOf(
                        "type" to "integer",
                        "description" to "Optional. Only return calls from the last N hours. E.g. 24 for 'today', 168 for 'this week'."
                    ),
                    "min_duration" to mapOf(
                        "type" to "integer",
                        "description" to "Optional. Only return calls longer than N seconds. Useful for filtering out quick rings."
                    )
                ),
                "required" to emptyList<String>()
            )
        )
    }

    // ── ISO 8601 formatter ───────────────────────────────────────────────────

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    // ── Tool Execution ───────────────────────────────────────────────────────

    fun execute(
        limit: Int = 20,
        caller: String? = null,
        direction: String? = null,
        sinceHours: Int? = null,
        minDuration: Int? = null
    ): ToolResult {
        // Check permission
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return ToolResult.denied(
                "Call log permission not granted. The user needs to allow READ_CALL_LOG in Settings."
            )
        }

        val cap = limit.coerceIn(1, 100)
        val resolver = context.contentResolver

        // Build query
        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION
        )

        val selectionParts = mutableListOf<String>()
        val selectionArgs = mutableListOf<String>()

        // Time filter
        if (sinceHours != null && sinceHours > 0) {
            val cutoffMs = System.currentTimeMillis() - (sinceHours * 3600L * 1000L)
            selectionParts.add("${CallLog.Calls.DATE} > ?")
            selectionArgs.add(cutoffMs.toString())
        }

        // Direction filter at query level (more efficient than in-memory)
        val callType = when (direction?.lowercase()) {
            "incoming" -> CallLog.Calls.INCOMING_TYPE
            "outgoing" -> CallLog.Calls.OUTGOING_TYPE
            "missed" -> CallLog.Calls.MISSED_TYPE
            "rejected" -> CallLog.Calls.REJECTED_TYPE
            "blocked" -> CallLog.Calls.BLOCKED_TYPE
            "voicemail" -> CallLog.Calls.VOICEMAIL_TYPE
            else -> null
        }
        if (callType != null) {
            selectionParts.add("${CallLog.Calls.TYPE} = ?")
            selectionArgs.add(callType.toString())
        }

        // Duration filter
        if (minDuration != null && minDuration > 0) {
            selectionParts.add("${CallLog.Calls.DURATION} > ?")
            selectionArgs.add(minDuration.toString())
        }

        val selection = if (selectionParts.isEmpty()) null else selectionParts.joinToString(" AND ")
        val args = if (selectionArgs.isEmpty()) null else selectionArgs.toTypedArray()

        // Over-fetch to allow in-memory caller name filtering
        val fetchLimit = cap * 5
        val cursor: Cursor? = resolver.query(
            CallLog.Calls.CONTENT_URI,
            projection,
            selection,
            args,
            "${CallLog.Calls.DATE} DESC LIMIT $fetchLimit"
        )

        if (cursor == null) {
            return ToolResult.failure("Failed to query call log.")
        }

        val allCalls = mutableListOf<CallDTO>()
        cursor.use { c ->
            val idIdx = c.getColumnIndexOrThrow(CallLog.Calls._ID)
            val numIdx = c.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
            val nameIdx = c.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
            val typeIdx = c.getColumnIndexOrThrow(CallLog.Calls.TYPE)
            val dateIdx = c.getColumnIndexOrThrow(CallLog.Calls.DATE)
            val durIdx = c.getColumnIndexOrThrow(CallLog.Calls.DURATION)

            while (c.moveToNext()) {
                val number = c.getString(numIdx) ?: ""
                val cachedName = c.getString(nameIdx)
                // Use cached name from call log, fall back to contacts lookup
                val contactName = cachedName ?: resolveContactName(resolver, number)
                val rawType = c.getInt(typeIdx)
                val dateMs = c.getLong(dateIdx)
                val duration = c.getInt(durIdx)

                val typeStr = when (rawType) {
                    CallLog.Calls.INCOMING_TYPE -> "incoming"
                    CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                    CallLog.Calls.MISSED_TYPE -> "missed"
                    CallLog.Calls.REJECTED_TYPE -> "rejected"
                    CallLog.Calls.BLOCKED_TYPE -> "blocked"
                    CallLog.Calls.VOICEMAIL_TYPE -> "voicemail"
                    else -> "unknown"
                }

                allCalls.add(
                    CallDTO(
                        id = c.getLong(idIdx),
                        number = number,
                        contactName = contactName,
                        type = typeStr,
                        date = isoFormat.format(Date(dateMs)),
                        duration = duration
                    )
                )
            }
        }

        // In-memory caller filter (name or number)
        var filtered = allCalls
        if (!caller.isNullOrBlank()) {
            filtered = filtered.filter { call ->
                call.number.contains(caller, ignoreCase = true) ||
                (call.contactName?.contains(caller, ignoreCase = true) == true)
            }.toMutableList()
        }

        // Trim to limit
        val result = filtered.take(cap)

        if (result.isEmpty()) {
            val hint = if (allCalls.isEmpty()) {
                " No calls found in the call log."
            } else {
                " No calls match your filter criteria."
            }
            return ToolResult.success("No calls found.$hint")
        }

        val missedCount = result.count { it.type == "missed" }
        val missedNote = if (missedCount > 0) " ($missedCount missed)" else ""
        val mostRecent = result.first()
        val dirWord = if (mostRecent.type == "outgoing") "to" else "from"
        val callerDisplay = mostRecent.contactName ?: mostRecent.number

        val summary = "${result.size} call(s)$missedNote. Most recent: " +
            "${mostRecent.type} call $dirWord $callerDisplay at ${mostRecent.date}" +
            " (${formatDuration(mostRecent.duration)})."

        return ToolResult.success(
            summary = summary,
            data = CallListDTO(count = result.size, calls = result)
        )
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Resolves a phone number to a contact display name, or null. */
    private fun resolveContactName(resolver: ContentResolver, phoneNumber: String): String? {
        if (phoneNumber.isBlank()) return null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }

        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(phoneNumber)
        )
        val cursor = resolver.query(
            uri,
            arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
            null, null, null
        )
        cursor?.use {
            if (it.moveToFirst()) {
                return it.getString(0)
            }
        }
        return null
    }

    /** Formats seconds into human-readable duration: "2m 35s", "1h 5m", etc. */
    private fun formatDuration(seconds: Int): String {
        if (seconds < 60) return "${seconds}s"
        val mins = seconds / 60
        val secs = seconds % 60
        if (mins < 60) return "${mins}m ${secs}s"
        val hours = mins / 60
        val remainMins = mins % 60
        return "${hours}h ${remainMins}m"
    }
}
