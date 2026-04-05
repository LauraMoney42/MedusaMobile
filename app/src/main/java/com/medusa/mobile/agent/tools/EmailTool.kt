package com.medusa.mobile.agent.tools

// mm-016 — Email Tool for Medusa Mobile
//
// Provides Claude access to email via two paths:
//   1. Gmail REST API — uses existing Google OAuth2 token from GoogleAuthManager.
//      Covers all Gmail and Google Workspace accounts. Zero-setup for users who
//      already connected Google via the Docs/Drive flow.
//   2. IMAP — covers iCloud Mail, Yahoo, AOL, Outlook, Exchange, and any standard
//      IMAP server. User provides email + app password once via email_imap_setup.
//      Credentials stored in EncryptedSharedPreferences.
//
// Tool names (Gmail):
//   gmail_search   → search Gmail inbox/all mail by query, sender, subject
//   gmail_read     → read full email content by message ID
//   gmail_send     → compose and send email via Gmail API
//
// Tool names (IMAP):
//   email_imap_setup  → store IMAP credentials (one-time setup)
//   email_imap_search → search IMAP inbox with keyword/subject/sender filter
//
// All tools are crash-safe — errors surface as ToolResult.failure(), never crash loop.

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.medusa.mobile.models.ToolResult
import com.medusa.mobile.services.GoogleAuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Properties
import java.util.concurrent.TimeUnit
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import javax.mail.search.AndTerm
import javax.mail.search.FromStringTerm
import javax.mail.search.OrTerm
import javax.mail.search.SubjectTerm
import javax.mail.search.BodyTerm
import javax.mail.search.SearchTerm

/**
 * Email access tool — Gmail REST API + IMAP fallback.
 *
 * "What's in my inbox?" → gmail_search with no query, returns recent unread.
 * "Any emails from Sarah?" → gmail_search(query="from:sarah")
 * "Send an email to John" → gmail_send
 * "Check my iCloud email" → email_imap_search (after email_imap_setup)
 */
class EmailTool(
    private val context: Context,
    private val authManager: GoogleAuthManager
) {

    companion object {
        private const val GMAIL_API = "https://gmail.googleapis.com/gmail/v1/users/me"

        // EncryptedSharedPreferences keys for IMAP config
        private const val PREFS_FILE = "medusa_imap_config"
        private const val KEY_IMAP_HOST = "imap_host"
        private const val KEY_IMAP_EMAIL = "imap_email"
        private const val KEY_IMAP_PASSWORD = "imap_password"
        private const val KEY_IMAP_PORT = "imap_port"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    // Lazily loaded EncryptedSharedPreferences for IMAP credentials
    private val encryptedPrefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // ── gmail_search ─────────────────────────────────────────────────────────

    /**
     * Search Gmail using the Gmail API query syntax.
     * Examples:
     *   query = "from:sarah" → emails from Sarah
     *   query = "subject:invoice" → emails with invoice in subject
     *   query = "is:unread" → unread emails
     *   query = "has:attachment" → emails with attachments
     *   query = "" → recent messages
     */
    suspend fun gmailSearch(
        query: String,
        limit: Int = 20,
        unreadOnly: Boolean = false,
        includeSnippet: Boolean = true
    ): ToolResult = withContext(Dispatchers.IO) {
        val token = getGmailToken()
            ?: return@withContext ToolResult.failure(
                "Google account not connected. Ask user to sign in via Settings → Connect Google Account."
            )

        try {
            // Build query: append is:unread if requested
            val fullQuery = buildList {
                if (query.isNotBlank()) add(query.trim())
                if (unreadOnly) add("is:unread")
            }.joinToString(" ")

            val cap = limit.coerceIn(1, 50)

            // Step 1: list message IDs matching query
            val listUrl = "$GMAIL_API/messages" +
                "?maxResults=$cap" +
                (if (fullQuery.isNotBlank()) "&q=${java.net.URLEncoder.encode(fullQuery, "UTF-8")}" else "") +
                "&format=minimal"

            val listRequest = Request.Builder()
                .url(listUrl)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            val listResponse = httpClient.newCall(listRequest).execute()
            if (!listResponse.isSuccessful) {
                val err = listResponse.body?.string() ?: ""
                return@withContext ToolResult.failure(
                    "Gmail search failed (HTTP ${listResponse.code}): $err"
                )
            }

            val listBody = JSONObject(listResponse.body?.string() ?: "{}")
            val messages = listBody.optJSONArray("messages") ?: JSONArray()

            if (messages.length() == 0) {
                val desc = if (fullQuery.isNotBlank()) "matching '$fullQuery'" else "in Gmail"
                return@withContext ToolResult.success("No emails found $desc.")
            }

            val resultCount = messages.length()

            // Step 2: Fetch metadata (headers + snippet) for each message
            // Uses format=metadata to avoid downloading full bodies for search results
            val results = StringBuilder()
            results.append("Found $resultCount email(s):\n\n")

            for (i in 0 until resultCount) {
                val msgId = messages.getJSONObject(i).optString("id", "")
                if (msgId.isEmpty()) continue

                val metaRequest = Request.Builder()
                    .url("$GMAIL_API/messages/$msgId?format=metadata&metadataHeaders=From&metadataHeaders=To&metadataHeaders=Subject&metadataHeaders=Date")
                    .addHeader("Authorization", "Bearer $token")
                    .get()
                    .build()

                val metaResponse = httpClient.newCall(metaRequest).execute()
                if (!metaResponse.isSuccessful) {
                    results.append("${i + 1}. [Error reading message $msgId]\n\n")
                    continue
                }

                val msgJson = JSONObject(metaResponse.body?.string() ?: "{}")
                val headers = msgJson.optJSONObject("payload")
                    ?.optJSONArray("headers") ?: JSONArray()

                val headerMap = (0 until headers.length()).associate {
                    val h = headers.getJSONObject(it)
                    h.optString("name").lowercase() to h.optString("value")
                }

                val isUnread = msgJson.optJSONArray("labelIds")
                    ?.let { labels ->
                        (0 until labels.length()).any { labels.getString(it) == "UNREAD" }
                    } ?: false

                val snippet = if (includeSnippet) msgJson.optString("snippet", "") else ""

                results.append("${i + 1}. ${if (isUnread) "🔵 " else ""}**${headerMap["subject"] ?: "(no subject)"}**\n")
                results.append("   From: ${headerMap["from"] ?: "Unknown"}\n")
                results.append("   Date: ${headerMap["date"] ?: "Unknown"}\n")
                results.append("   ID: $msgId\n")
                if (snippet.isNotBlank()) {
                    results.append("   Preview: ${snippet.take(120)}${if (snippet.length > 120) "…" else ""}\n")
                }
                results.append("\n")
            }

            results.append("Use gmail_read with the message ID to read full content.")
            ToolResult.success(results.toString().trim())

        } catch (e: Exception) {
            ToolResult.failure("Gmail search failed: ${e.message}")
        }
    }

    // ── gmail_read ───────────────────────────────────────────────────────────

    /**
     * Read the full content of a Gmail message by ID.
     * Returns decoded text/plain body with all headers.
     */
    suspend fun gmailRead(messageId: String): ToolResult = withContext(Dispatchers.IO) {
        val token = getGmailToken()
            ?: return@withContext ToolResult.failure(
                "Google account not connected."
            )

        try {
            val request = Request.Builder()
                .url("$GMAIL_API/messages/$messageId?format=full")
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val err = response.body?.string() ?: ""
                return@withContext ToolResult.failure(
                    "Failed to read email (HTTP ${response.code}): $err"
                )
            }

            val msgJson = JSONObject(response.body?.string() ?: "{}")

            // Extract headers
            val headers = msgJson.optJSONObject("payload")
                ?.optJSONArray("headers") ?: JSONArray()
            val headerMap = (0 until headers.length()).associate {
                val h = headers.getJSONObject(it)
                h.optString("name").lowercase() to h.optString("value")
            }

            // Extract body — walk MIME parts to find text/plain
            val bodyText = extractTextBody(msgJson.optJSONObject("payload"))
                ?: msgJson.optString("snippet", "(No text body found)")

            val result = buildString {
                append("**From:** ${headerMap["from"] ?: "Unknown"}\n")
                append("**To:** ${headerMap["to"] ?: "Unknown"}\n")
                if (!headerMap["cc"].isNullOrEmpty()) {
                    append("**CC:** ${headerMap["cc"]}\n")
                }
                append("**Subject:** ${headerMap["subject"] ?: "(no subject)"}\n")
                append("**Date:** ${headerMap["date"] ?: "Unknown"}\n")
                append("\n---\n\n")
                append(bodyText.take(4000))
                if (bodyText.length > 4000) append("\n\n[Message truncated — ${bodyText.length} chars total]")
            }

            ToolResult.success(result)

        } catch (e: Exception) {
            ToolResult.failure("Failed to read email: ${e.message}")
        }
    }

    // ── gmail_send ───────────────────────────────────────────────────────────

    /**
     * Send an email via Gmail API.
     * Builds RFC 2822 MIME message, base64url-encodes it, and sends via API.
     */
    suspend fun gmailSend(
        to: String,
        subject: String,
        body: String,
        cc: String? = null,
        replyToMessageId: String? = null
    ): ToolResult = withContext(Dispatchers.IO) {
        val token = getGmailToken()
            ?: return@withContext ToolResult.failure(
                "Google account not connected."
            )

        try {
            // Build RFC 2822 message
            val fromEmail = authManager.getSignedInEmail() ?: "me"
            val rawMessage = buildString {
                append("From: $fromEmail\r\n")
                append("To: $to\r\n")
                if (!cc.isNullOrBlank()) append("Cc: $cc\r\n")
                append("Subject: $subject\r\n")
                if (!replyToMessageId.isNullOrBlank()) {
                    // Thread reply — set In-Reply-To header
                    append("In-Reply-To: $replyToMessageId\r\n")
                }
                append("MIME-Version: 1.0\r\n")
                append("Content-Type: text/plain; charset=UTF-8\r\n")
                append("Content-Transfer-Encoding: 7bit\r\n")
                append("\r\n")
                append(body)
            }

            // Base64url encode (Gmail API requires URL-safe, no padding)
            val encoded = Base64.encodeToString(
                rawMessage.toByteArray(Charsets.UTF_8),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )

            val sendBody = JSONObject().apply {
                put("raw", encoded)
            }

            val sendRequest = Request.Builder()
                .url("$GMAIL_API/messages/send")
                .addHeader("Authorization", "Bearer $token")
                .post(sendBody.toString().toRequestBody(JSON_TYPE))
                .build()

            val sendResponse = httpClient.newCall(sendRequest).execute()
            if (!sendResponse.isSuccessful) {
                val err = sendResponse.body?.string() ?: ""
                return@withContext ToolResult.failure(
                    "Failed to send email (HTTP ${sendResponse.code}): $err"
                )
            }

            val responseJson = JSONObject(sendResponse.body?.string() ?: "{}")
            val sentId = responseJson.optString("id", "")

            ToolResult.success(
                "Email sent successfully to $to.\nSubject: $subject\nMessage ID: $sentId"
            )

        } catch (e: Exception) {
            ToolResult.failure("Failed to send email: ${e.message}")
        }
    }

    // ── email_imap_setup ─────────────────────────────────────────────────────

    /**
     * Store IMAP credentials in EncryptedSharedPreferences.
     * Called once by user when setting up a non-Gmail account.
     *
     * Common presets:
     *   iCloud:  host=imap.mail.me.com, port=993
     *   Yahoo:   host=imap.mail.yahoo.com, port=993
     *   Outlook: host=outlook.office365.com, port=993
     */
    suspend fun imapSetup(
        host: String,
        email: String,
        password: String,
        port: Int = 993
    ): ToolResult = withContext(Dispatchers.IO) {
        try {
            // Validate connection before saving
            val testResult = testImapConnection(host, email, password, port)
            if (!testResult) {
                return@withContext ToolResult.failure(
                    "Could not connect to IMAP server $host:$port. " +
                    "Verify host, email, and app password are correct. " +
                    "Note: use an App Password, not your regular password."
                )
            }

            encryptedPrefs.edit().apply {
                putString(KEY_IMAP_HOST, host)
                putString(KEY_IMAP_EMAIL, email)
                putString(KEY_IMAP_PASSWORD, password)
                putInt(KEY_IMAP_PORT, port)
                apply()
            }

            ToolResult.success(
                "IMAP account configured: $email at $host:$port. " +
                "You can now search this email account with email_imap_search."
            )
        } catch (e: Exception) {
            ToolResult.failure("IMAP setup failed: ${e.message}")
        }
    }

    // ── email_imap_search ────────────────────────────────────────────────────

    /**
     * Search the IMAP mailbox for messages matching a query.
     * Searches subject, from, and body fields.
     */
    suspend fun imapSearch(
        query: String,
        folder: String = "INBOX",
        limit: Int = 20
    ): ToolResult = withContext(Dispatchers.IO) {
        val host = encryptedPrefs.getString(KEY_IMAP_HOST, null)
        val email = encryptedPrefs.getString(KEY_IMAP_EMAIL, null)
        val password = encryptedPrefs.getString(KEY_IMAP_PASSWORD, null)
        val port = encryptedPrefs.getInt(KEY_IMAP_PORT, 993)

        if (host == null || email == null || password == null) {
            return@withContext ToolResult.failure(
                "No IMAP account configured. Use email_imap_setup first to set up iCloud, Yahoo, or other email accounts."
            )
        }

        try {
            val props = Properties().apply {
                put("mail.imap.host", host)
                put("mail.imap.port", port.toString())
                put("mail.imap.ssl.enable", "true")
                put("mail.imap.connectiontimeout", "12000")
                put("mail.imap.timeout", "20000")
                put("mail.imap.starttls.enable", "true")
                put("mail.imap.ssl.checkserveridentity", "true")
            }

            val session = Session.getInstance(props)
            val store = session.getStore("imap")
            store.connect(host, port, email, password)

            try {
                val mailFolder = store.getFolder(folder)
                mailFolder.open(Folder.READ_ONLY)

                try {
                    val cap = limit.coerceIn(1, 50)

                    // Build search term — search subject and body for the query
                    val messages = if (query.isBlank()) {
                        // No query: return most recent messages
                        val count = mailFolder.messageCount
                        val start = maxOf(1, count - cap + 1)
                        mailFolder.getMessages(start, count)
                    } else {
                        // Combine subject + from + body search with OR
                        val searchTerm: SearchTerm = OrTerm(
                            arrayOf(
                                SubjectTerm(query),
                                FromStringTerm(query),
                                BodyTerm(query)
                            )
                        )
                        mailFolder.search(searchTerm)
                    }

                    if (messages.isEmpty()) {
                        return@withContext ToolResult.success(
                            "No emails found${if (query.isNotBlank()) " matching '$query'" else ""} in $folder."
                        )
                    }

                    val recent = messages.takeLast(cap)
                    val results = StringBuilder()
                    results.append("Found ${recent.size} email(s) in $folder${if (query.isNotBlank()) " matching '$query'" else ""}:\n\n")

                    recent.reversed().forEachIndexed { idx, msg ->
                        val isUnread = !msg.isSet(Flags.Flag.SEEN)
                        val from = (msg.from?.firstOrNull() as? InternetAddress)?.toString() ?: "Unknown"
                        val subject = msg.subject ?: "(no subject)"
                        val date = msg.sentDate?.toString() ?: "Unknown"

                        // Extract plain text snippet (first 200 chars)
                        val snippet = try {
                            when {
                                msg.isMimeType("text/plain") ->
                                    (msg.content as? String)?.take(200) ?: ""
                                msg.isMimeType("multipart/*") -> {
                                    val mp = msg.content as? javax.mail.Multipart
                                    (0 until (mp?.count ?: 0))
                                        .firstOrNull { mp?.getBodyPart(it)?.isMimeType("text/plain") == true }
                                        ?.let { mp?.getBodyPart(it)?.content as? String }
                                        ?.take(200) ?: ""
                                }
                                else -> ""
                            }
                        } catch (e: Exception) { "" }

                        results.append("${idx + 1}. ${if (isUnread) "🔵 " else ""}**$subject**\n")
                        results.append("   From: $from\n")
                        results.append("   Date: $date\n")
                        if (snippet.isNotBlank()) {
                            results.append("   Preview: ${snippet.take(150)}${if (snippet.length >= 150) "…" else ""}\n")
                        }
                        results.append("\n")
                    }

                    ToolResult.success(results.toString().trim())

                } finally {
                    mailFolder.close(false)
                }
            } finally {
                store.close()
            }

        } catch (e: Exception) {
            ToolResult.failure("IMAP search failed: ${e.message}")
        }
    }

    // ── Internal Helpers ─────────────────────────────────────────────────────

    /**
     * Get a Gmail-scoped OAuth2 token by delegating to GoogleAuthManager.
     *
     * GoogleAuthManager now requests all scopes (Docs, Drive, Gmail, Sheets) during
     * sign-in, so a single token covers everything. Using authManager.getAccessToken()
     * as the single source of truth avoids duplicating scope logic here and ensures
     * Gmail never triggers a false "not connected" error when the user IS signed in.
     */
    private suspend fun getGmailToken(): String? = authManager.getAccessToken()

    /**
     * Test IMAP connection — returns true if login succeeds, false otherwise.
     * Used during setup to validate credentials before saving.
     */
    private fun testImapConnection(host: String, email: String, password: String, port: Int): Boolean {
        return try {
            val props = Properties().apply {
                put("mail.imap.host", host)
                put("mail.imap.port", port.toString())
                put("mail.imap.ssl.enable", "true")
                put("mail.imap.connectiontimeout", "10000")
                put("mail.imap.timeout", "10000")
            }
            val session = Session.getInstance(props)
            val store = session.getStore("imap")
            store.connect(host, port, email, password)
            store.close()
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Recursively extract text/plain body from a Gmail API payload JSON.
     * Handles nested multipart/alternative structures.
     */
    private fun extractTextBody(payload: JSONObject?): String? {
        if (payload == null) return null

        val mimeType = payload.optString("mimeType", "")

        // Direct text/plain part
        if (mimeType == "text/plain") {
            val data = payload.optJSONObject("body")?.optString("data", "") ?: ""
            if (data.isNotEmpty()) {
                return try {
                    String(Base64.decode(data, Base64.URL_SAFE), Charsets.UTF_8)
                } catch (e: Exception) { null }
            }
        }

        // Multipart: recurse into parts, prefer text/plain over text/html
        if (mimeType.startsWith("multipart/")) {
            val parts = payload.optJSONArray("parts") ?: return null
            var plainText: String? = null
            var htmlText: String? = null

            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                val partMime = part.optString("mimeType", "")
                val partData = part.optJSONObject("body")?.optString("data", "") ?: ""

                when {
                    partMime == "text/plain" && partData.isNotEmpty() -> {
                        plainText = try {
                            String(Base64.decode(partData, Base64.URL_SAFE), Charsets.UTF_8)
                        } catch (e: Exception) { null }
                    }
                    partMime == "text/html" && partData.isNotEmpty() && plainText == null -> {
                        // Fallback: strip tags if no plain text found
                        htmlText = try {
                            val raw = String(Base64.decode(partData, Base64.URL_SAFE), Charsets.UTF_8)
                            raw.replace(Regex("<[^>]+>"), "").trim()
                        } catch (e: Exception) { null }
                    }
                    partMime.startsWith("multipart/") -> {
                        // Nested multipart — recurse
                        plainText = plainText ?: extractTextBody(part)
                    }
                }
            }

            return plainText ?: htmlText
        }

        return null
    }
}
