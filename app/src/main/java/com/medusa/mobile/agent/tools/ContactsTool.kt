package com.medusa.mobile.agent.tools

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds
import androidx.core.content.ContextCompat
import com.medusa.mobile.models.ContactDTO
import com.medusa.mobile.models.ContactListDTO
import com.medusa.mobile.models.ToolResult

/**
 * mm-013 — Contacts Tool for Medusa Mobile.
 *
 * Full read access to the device address book via ContactsContract ContentProvider.
 * Returns: display name, phone numbers (with labels), emails, organization,
 * birthday, address, notes, and photo URI.
 *
 * Requires: android.permission.READ_CONTACTS
 *
 * Claude tool name: "get_contacts"
 */
class ContactsTool(private val context: Context) {

    // ── Claude Tool Definition ───────────────────────────────────────────────

    companion object {
        val claudeToolDefinition: Map<String, Any> = mapOf(
            "name" to "get_contacts",
            "description" to """
                Searches the user's phone contacts / address book. Returns names, phone
                numbers, email addresses, organization, birthday, and notes.
                Use this to answer "what's Sarah's number?", "find contacts at Google",
                "who do I know named John?", "show me my recent contacts", or
                "do I have an email for Dr. Smith?".
                Requires READ_CONTACTS permission (requested automatically on first use).
            """.trimIndent(),
            "input_schema" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "query" to mapOf(
                        "type" to "string",
                        "description" to "Search query — matches against name, phone number, email, organization, and notes. Case-insensitive partial match. E.g. \"Sarah\", \"555-1234\", \"Google\", \"dentist\"."
                    ),
                    "limit" to mapOf(
                        "type" to "integer",
                        "description" to "Max contacts to return. Default 20, max 100.",
                        "default" to 20
                    ),
                    "mode" to mapOf(
                        "type" to "string",
                        "enum" to listOf("search", "recent", "favorites", "all"),
                        "description" to "Query mode. 'search' (default) filters by query string. 'recent' returns recently contacted. 'favorites' returns starred contacts. 'all' returns all contacts (use with small limit).",
                        "default" to "search"
                    )
                ),
                "required" to emptyList<String>()
            )
        )
    }

    // ── Tool Execution ───────────────────────────────────────────────────────

    fun execute(
        query: String? = null,
        limit: Int = 20,
        mode: String = "search"
    ): ToolResult {
        // Check permission
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return ToolResult.denied(
                "Contacts permission not granted. The user needs to allow READ_CONTACTS in Settings."
            )
        }

        val cap = limit.coerceIn(1, 100)
        val resolver = context.contentResolver

        return when (mode.lowercase()) {
            "recent"    -> queryRecent(resolver, cap)
            "favorites" -> queryFavorites(resolver, cap)
            "all"       -> queryAll(resolver, cap)
            else        -> querySearch(resolver, query, cap)
        }
    }

    // ── Query Modes ──────────────────────────────────────────────────────────

    /**
     * Search contacts by name, phone, email, org, or notes.
     * Uses ContactsContract CONTENT_FILTER_URI for name matching, then
     * enriches with phone/email/org data.
     */
    private fun querySearch(resolver: ContentResolver, query: String?, limit: Int): ToolResult {
        if (query.isNullOrBlank()) {
            return ToolResult.failure("'query' is required for search mode. Provide a name, number, or keyword.")
        }

        // ContactsContract.Contacts.CONTENT_FILTER_URI matches display_name and
        // phonetic name. For phone number search we also check PhoneLookup.
        val contactIds = mutableSetOf<Long>()

        // 1. Name-based search
        val filterUri = ContactsContract.Contacts.CONTENT_FILTER_URI
            .buildUpon()
            .appendPath(query)
            .build()

        resolver.query(
            filterUri,
            arrayOf(ContactsContract.Contacts._ID),
            null, null,
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC LIMIT ${limit * 3}"
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
            while (cursor.moveToNext()) {
                contactIds.add(cursor.getLong(idIdx))
            }
        }

        // 2. Phone number search (handles "555-1234" queries)
        if (query.any { it.isDigit() }) {
            try {
                val phoneUri = android.net.Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    android.net.Uri.encode(query)
                )
                resolver.query(
                    phoneUri,
                    arrayOf(ContactsContract.PhoneLookup.CONTACT_ID),
                    null, null, null
                )?.use { cursor ->
                    val idIdx = cursor.getColumnIndexOrThrow(ContactsContract.PhoneLookup.CONTACT_ID)
                    while (cursor.moveToNext()) {
                        contactIds.add(cursor.getLong(idIdx))
                    }
                }
            } catch (_: Exception) {
                // PhoneLookup can throw on malformed numbers — skip
            }
        }

        // 3. Email search
        resolver.query(
            CommonDataKinds.Email.CONTENT_URI,
            arrayOf(CommonDataKinds.Email.CONTACT_ID),
            "${CommonDataKinds.Email.ADDRESS} LIKE ?",
            arrayOf("%$query%"),
            null
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(CommonDataKinds.Email.CONTACT_ID)
            while (cursor.moveToNext()) {
                contactIds.add(cursor.getLong(idIdx))
            }
        }

        // 4. Organization search
        resolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.CONTACT_ID),
            "${ContactsContract.Data.MIMETYPE} = ? AND ${CommonDataKinds.Organization.COMPANY} LIKE ?",
            arrayOf(CommonDataKinds.Organization.CONTENT_ITEM_TYPE, "%$query%"),
            null
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(ContactsContract.Data.CONTACT_ID)
            while (cursor.moveToNext()) {
                contactIds.add(cursor.getLong(idIdx))
            }
        }

        if (contactIds.isEmpty()) {
            return ToolResult.success("No contacts found matching \"$query\".")
        }

        val contacts = enrichContacts(resolver, contactIds.take(limit).toList())
        val summary = "${contacts.size} contact(s) matching \"$query\"."
        return ToolResult.success(
            summary = summary,
            data = ContactListDTO(count = contacts.size, contacts = contacts)
        )
    }

    /** Returns recently contacted people (sorted by last_time_contacted DESC). */
    private fun queryRecent(resolver: ContentResolver, limit: Int): ToolResult {
        val contactIds = mutableListOf<Long>()

        @Suppress("DEPRECATION")
        resolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID),
            "${ContactsContract.Contacts.LAST_TIME_CONTACTED} > 0",
            null,
            "${ContactsContract.Contacts.LAST_TIME_CONTACTED} DESC LIMIT $limit"
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
            while (cursor.moveToNext()) {
                contactIds.add(cursor.getLong(idIdx))
            }
        }

        if (contactIds.isEmpty()) {
            return ToolResult.success("No recently contacted people found.")
        }

        val contacts = enrichContacts(resolver, contactIds)
        return ToolResult.success(
            summary = "${contacts.size} recently contacted.",
            data = ContactListDTO(count = contacts.size, contacts = contacts)
        )
    }

    /** Returns starred/favorite contacts. */
    private fun queryFavorites(resolver: ContentResolver, limit: Int): ToolResult {
        val contactIds = mutableListOf<Long>()

        resolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID),
            "${ContactsContract.Contacts.STARRED} = 1",
            null,
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC LIMIT $limit"
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
            while (cursor.moveToNext()) {
                contactIds.add(cursor.getLong(idIdx))
            }
        }

        if (contactIds.isEmpty()) {
            return ToolResult.success("No favorite/starred contacts found.")
        }

        val contacts = enrichContacts(resolver, contactIds)
        return ToolResult.success(
            summary = "${contacts.size} favorite contact(s).",
            data = ContactListDTO(count = contacts.size, contacts = contacts)
        )
    }

    /** Returns all contacts (use with small limit). */
    private fun queryAll(resolver: ContentResolver, limit: Int): ToolResult {
        val contactIds = mutableListOf<Long>()

        resolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID),
            null, null,
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC LIMIT $limit"
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
            while (cursor.moveToNext()) {
                contactIds.add(cursor.getLong(idIdx))
            }
        }

        if (contactIds.isEmpty()) {
            return ToolResult.success("No contacts on this device.")
        }

        val contacts = enrichContacts(resolver, contactIds)
        return ToolResult.success(
            summary = "${contacts.size} contact(s).",
            data = ContactListDTO(count = contacts.size, contacts = contacts)
        )
    }

    // ── Contact Enrichment ───────────────────────────────────────────────────

    /**
     * Given a list of contact IDs, load full details: name, phones, emails,
     * organization, birthday, address, notes, photo URI.
     */
    private fun enrichContacts(resolver: ContentResolver, ids: List<Long>): List<ContactDTO> {
        if (ids.isEmpty()) return emptyList()

        val contacts = mutableListOf<ContactDTO>()

        for (contactId in ids) {
            val name = getDisplayName(resolver, contactId) ?: continue
            val phones = getPhoneNumbers(resolver, contactId)
            val emails = getEmails(resolver, contactId)
            val organization = getOrganization(resolver, contactId)
            val birthday = getBirthday(resolver, contactId)
            val address = getAddress(resolver, contactId)
            val notes = getNotes(resolver, contactId)
            val photoUri = getPhotoUri(resolver, contactId)
            val starred = isStarred(resolver, contactId)

            contacts.add(ContactDTO(
                id = contactId,
                displayName = name,
                phones = phones,
                emails = emails,
                organization = organization,
                birthday = birthday,
                address = address,
                notes = notes,
                photoUri = photoUri,
                starred = starred
            ))
        }

        return contacts
    }

    // ── Data Extraction Helpers ──────────────────────────────────────────────

    private fun getDisplayName(resolver: ContentResolver, contactId: Long): String? {
        resolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
            "${ContactsContract.Contacts._ID} = ?",
            arrayOf(contactId.toString()),
            null
        )?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return null
    }

    /** Returns list of "label: number" strings. */
    private fun getPhoneNumbers(resolver: ContentResolver, contactId: Long): List<String> {
        val phones = mutableListOf<String>()
        resolver.query(
            CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(CommonDataKinds.Phone.NUMBER, CommonDataKinds.Phone.TYPE, CommonDataKinds.Phone.LABEL),
            "${CommonDataKinds.Phone.CONTACT_ID} = ?",
            arrayOf(contactId.toString()),
            null
        )?.use { c ->
            val numIdx = c.getColumnIndexOrThrow(CommonDataKinds.Phone.NUMBER)
            val typeIdx = c.getColumnIndexOrThrow(CommonDataKinds.Phone.TYPE)
            val labelIdx = c.getColumnIndexOrThrow(CommonDataKinds.Phone.LABEL)
            while (c.moveToNext()) {
                val number = c.getString(numIdx) ?: continue
                val type = c.getInt(typeIdx)
                val customLabel = c.getString(labelIdx)
                val label = CommonDataKinds.Phone.getTypeLabel(
                    context.resources, type, customLabel
                ).toString()
                phones.add("$label: $number")
            }
        }
        return phones
    }

    /** Returns list of "label: email" strings. */
    private fun getEmails(resolver: ContentResolver, contactId: Long): List<String> {
        val emails = mutableListOf<String>()
        resolver.query(
            CommonDataKinds.Email.CONTENT_URI,
            arrayOf(CommonDataKinds.Email.ADDRESS, CommonDataKinds.Email.TYPE, CommonDataKinds.Email.LABEL),
            "${CommonDataKinds.Email.CONTACT_ID} = ?",
            arrayOf(contactId.toString()),
            null
        )?.use { c ->
            val addrIdx = c.getColumnIndexOrThrow(CommonDataKinds.Email.ADDRESS)
            val typeIdx = c.getColumnIndexOrThrow(CommonDataKinds.Email.TYPE)
            val labelIdx = c.getColumnIndexOrThrow(CommonDataKinds.Email.LABEL)
            while (c.moveToNext()) {
                val addr = c.getString(addrIdx) ?: continue
                val type = c.getInt(typeIdx)
                val customLabel = c.getString(labelIdx)
                val label = CommonDataKinds.Email.getTypeLabel(
                    context.resources, type, customLabel
                ).toString()
                emails.add("$label: $addr")
            }
        }
        return emails
    }

    /** Returns "Company — Title" or null. */
    private fun getOrganization(resolver: ContentResolver, contactId: Long): String? {
        resolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(CommonDataKinds.Organization.COMPANY, CommonDataKinds.Organization.TITLE),
            "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(contactId.toString(), CommonDataKinds.Organization.CONTENT_ITEM_TYPE),
            null
        )?.use { c ->
            if (c.moveToFirst()) {
                val company = c.getString(0)
                val title = c.getString(1)
                return listOfNotNull(company, title).joinToString(" — ").ifBlank { null }
            }
        }
        return null
    }

    /** Returns birthday string (e.g. "1990-05-15") or null. */
    private fun getBirthday(resolver: ContentResolver, contactId: Long): String? {
        resolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(CommonDataKinds.Event.START_DATE),
            "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ? AND ${CommonDataKinds.Event.TYPE} = ?",
            arrayOf(
                contactId.toString(),
                CommonDataKinds.Event.CONTENT_ITEM_TYPE,
                CommonDataKinds.Event.TYPE_BIRTHDAY.toString()
            ),
            null
        )?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return null
    }

    /** Returns formatted postal address or null. */
    private fun getAddress(resolver: ContentResolver, contactId: Long): String? {
        resolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS),
            "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(contactId.toString(), CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE),
            null
        )?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return null
    }

    /** Returns contact notes or null. */
    private fun getNotes(resolver: ContentResolver, contactId: Long): String? {
        resolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(CommonDataKinds.Note.NOTE),
            "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(contactId.toString(), CommonDataKinds.Note.CONTENT_ITEM_TYPE),
            null
        )?.use { c ->
            if (c.moveToFirst()) return c.getString(0)?.ifBlank { null }
        }
        return null
    }

    /** Returns photo thumbnail URI string or null. */
    private fun getPhotoUri(resolver: ContentResolver, contactId: Long): String? {
        resolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts.PHOTO_THUMBNAIL_URI),
            "${ContactsContract.Contacts._ID} = ?",
            arrayOf(contactId.toString()),
            null
        )?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return null
    }

    /** Returns true if contact is starred/favorited. */
    private fun isStarred(resolver: ContentResolver, contactId: Long): Boolean {
        resolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts.STARRED),
            "${ContactsContract.Contacts._ID} = ?",
            arrayOf(contactId.toString()),
            null
        )?.use { c ->
            if (c.moveToFirst()) return c.getInt(0) == 1
        }
        return false
    }
}
