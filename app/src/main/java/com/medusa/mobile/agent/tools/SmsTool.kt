package com.medusa.mobile.agent.tools

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.medusa.mobile.models.SmsListDTO
import com.medusa.mobile.models.SmsMessageDTO
import com.medusa.mobile.models.ToolResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * mm-006 — SMS Reader Tool for Medusa Mobile.
 *
 * Reads SMS/MMS messages via Android's Telephony ContentProvider.
 * Requires: android.permission.READ_SMS + android.permission.READ_CONTACTS
 *
 * NO Shortcuts hack. NO bridge. Just native Android API — zero setup beyond
 * granting the permission once.
 *
 * Claude tool name: "get_sms"
 */
class SmsTool(private val context: Context) {

    // ── Claude Tool Definition ───────────────────────────────────────────────

    companion object {
        val claudeToolDefinitions: List<Map<String, Any>> = listOf(
            // 1. get_sms — read messages
            mapOf(
                "name" to "get_sms",
                "description" to """
                    Returns recent SMS and MMS text messages from the user's phone.
                    Use this to answer questions like "what did Sarah text me?",
                    "any messages about the meeting?", "show me my recent texts",
                    or "did I get a text from that number?".
                    Returns messages ordered newest-first.
                    Requires READ_SMS permission (requested automatically on first use).
                """.trimIndent(),
                "input_schema" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "limit" to mapOf(
                            "type" to "integer",
                            "description" to "Maximum number of messages to return. Default 20, max 100.",
                            "default" to 20
                        ),
                        "sender" to mapOf(
                            "type" to "string",
                            "description" to "Optional. Filter to messages from senders whose name or number contains this string (case-insensitive). E.g. \"Mom\", \"555-1234\"."
                        ),
                        "keyword" to mapOf(
                            "type" to "string",
                            "description" to "Optional. Filter to messages whose body contains this keyword (case-insensitive)."
                        ),
                        "since_hours" to mapOf(
                            "type" to "integer",
                            "description" to "Optional. Only return messages from the last N hours. E.g. 24 for 'today', 168 for 'this week'."
                        ),
                        "type" to mapOf(
                            "type" to "string",
                            "enum" to listOf("all", "inbox", "sent"),
                            "description" to "Optional. Filter by message type. Default \"all\".",
                            "default" to "all"
                        )
                    ),
                    "required" to emptyList<String>()
                )
            ),
            // 2. send_sms — send a text message
            mapOf(
                "name" to "send_sms",
                "description" to """
                    Send an SMS text message to a phone number.
                    Use this when the user asks you to "text Mom", "send a message to 555-1234",
                    or "reply to Sarah". Always confirm the recipient and message content with
                    the user before sending. Requires SEND_SMS permission.
                """.trimIndent(),
                "input_schema" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "phone_number" to mapOf(
                            "type" to "string",
                            "description" to "The recipient's phone number. E.g. \"+15551234567\" or \"555-123-4567\"."
                        ),
                        "message" to mapOf(
                            "type" to "string",
                            "description" to "The text message body to send."
                        )
                    ),
                    "required" to listOf("phone_number", "message")
                )
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
        sender: String? = null,
        keyword: String? = null,
        sinceHours: Int? = null,
        type: String? = null
    ): ToolResult {
        // Check permission
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return ToolResult.denied(
                "SMS permission not granted. The user needs to allow READ_SMS in Settings."
            )
        }

        val cap = limit.coerceIn(1, 100)
        val resolver = context.contentResolver

        // Build query
        val uri = Telephony.Sms.CONTENT_URI
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE
        )

        val selectionParts = mutableListOf<String>()
        val selectionArgs = mutableListOf<String>()

        // Time filter
        if (sinceHours != null && sinceHours > 0) {
            val cutoffMs = System.currentTimeMillis() - (sinceHours * 3600L * 1000L)
            selectionParts.add("${Telephony.Sms.DATE} > ?")
            selectionArgs.add(cutoffMs.toString())
        }

        // Type filter
        when (type?.lowercase()) {
            "inbox" -> {
                selectionParts.add("${Telephony.Sms.TYPE} = ?")
                selectionArgs.add(Telephony.Sms.MESSAGE_TYPE_INBOX.toString())
            }
            "sent" -> {
                selectionParts.add("${Telephony.Sms.TYPE} = ?")
                selectionArgs.add(Telephony.Sms.MESSAGE_TYPE_SENT.toString())
            }
        }

        val selection = if (selectionParts.isEmpty()) null else selectionParts.joinToString(" AND ")
        val args = if (selectionArgs.isEmpty()) null else selectionArgs.toTypedArray()

        // Query — fetch more than needed to allow in-memory filtering, then trim
        val fetchLimit = cap * 5  // over-fetch for post-query filters
        val cursor: Cursor? = resolver.query(
            uri,
            projection,
            selection,
            args,
            "${Telephony.Sms.DATE} DESC LIMIT $fetchLimit"
        )

        if (cursor == null) {
            return ToolResult.failure("Failed to query SMS database.")
        }

        val allMessages = mutableListOf<SmsMessageDTO>()
        cursor.use { c ->
            val idIdx = c.getColumnIndexOrThrow(Telephony.Sms._ID)
            val addrIdx = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIdx = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIdx = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val typeIdx = c.getColumnIndexOrThrow(Telephony.Sms.TYPE)

            while (c.moveToNext()) {
                val address = c.getString(addrIdx) ?: continue
                val body = c.getString(bodyIdx) ?: ""
                val dateMs = c.getLong(dateIdx)
                val smsType = c.getInt(typeIdx)
                val contactName = resolveContactName(resolver, address)

                val typeStr = when (smsType) {
                    Telephony.Sms.MESSAGE_TYPE_INBOX -> "inbox"
                    Telephony.Sms.MESSAGE_TYPE_SENT -> "sent"
                    Telephony.Sms.MESSAGE_TYPE_DRAFT -> "draft"
                    Telephony.Sms.MESSAGE_TYPE_OUTBOX -> "outbox"
                    else -> "unknown"
                }

                allMessages.add(
                    SmsMessageDTO(
                        id = c.getLong(idIdx),
                        address = address,
                        contactName = contactName,
                        body = body,
                        date = isoFormat.format(Date(dateMs)),
                        type = typeStr
                    )
                )
            }
        }

        // In-memory filters
        var filtered = allMessages

        if (!sender.isNullOrBlank()) {
            filtered = filtered.filter { msg ->
                msg.address.contains(sender, ignoreCase = true) ||
                (msg.contactName?.contains(sender, ignoreCase = true) == true)
            }.toMutableList()
        }

        if (!keyword.isNullOrBlank()) {
            filtered = filtered.filter { msg ->
                msg.body.contains(keyword, ignoreCase = true)
            }.toMutableList()
        }

        // Trim to limit
        val result = filtered.take(cap)

        if (result.isEmpty()) {
            val hint = if (allMessages.isEmpty()) {
                " No SMS messages found on this device."
            } else {
                " No messages match your filter criteria."
            }
            return ToolResult.success("No messages found.$hint")
        }

        val summary = "${result.size} message(s). Most recent from " +
            "${result.first().contactName ?: result.first().address} at ${result.first().date}."

        return ToolResult.success(
            summary = summary,
            data = SmsListDTO(count = result.size, messages = result)
        )
    }

    // ── Send SMS ──────────────────────────────────────────────────────────────

    /**
     * Send an SMS message via SmsManager.
     * Long messages are automatically split into multi-part SMS.
     */
    fun sendSms(phoneNumber: String, message: String): ToolResult {
        // Check permission
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return ToolResult.denied(
                "SMS send permission not granted. The user needs to allow SEND_SMS in Settings."
            )
        }

        val number = phoneNumber.trim()
        val body = message.trim()

        if (number.isEmpty()) {
            return ToolResult.failure("Phone number is required.")
        }
        if (body.isEmpty()) {
            return ToolResult.failure("Message body is required.")
        }

        return try {
            val smsManager = context.getSystemService(SmsManager::class.java)
            // Split long messages into parts (SMS limit is 160 chars for GSM)
            val parts = smsManager.divideMessage(body)
            if (parts.size == 1) {
                smsManager.sendTextMessage(number, null, body, null, null)
            } else {
                smsManager.sendMultipartTextMessage(number, null, parts, null, null)
            }

            // Resolve contact name for summary
            val contactName = resolveContactName(context.contentResolver, number)
            val recipient = contactName ?: number

            ToolResult.success("SMS sent to $recipient: \"${body.take(50)}${if (body.length > 50) "..." else ""}\"")
        } catch (e: Exception) {
            ToolResult.failure("Failed to send SMS: ${e.localizedMessage}")
        }
    }

    // ── Contact Resolution ───────────────────────────────────────────────────

    /** Resolves a phone number to a contact display name, or null if not in contacts. */
    private fun resolveContactName(resolver: ContentResolver, phoneNumber: String): String? {
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
}
