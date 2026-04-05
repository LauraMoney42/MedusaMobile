package com.medusa.mobile.agent

// ToolDispatcher — Routes Claude tool_use calls to the correct Tool implementation.
//
// Why a dispatcher layer?
//   1. Single point of tool registration — add a tool in one place
//   2. Decouples AgentOrchestrator from individual tool implementations
//   3. Handles JSON input parsing so tools get typed parameters
//   4. Provides allToolDefinitions() for the Claude API request
//   5. Graceful error handling — unknown tools and crashes don't break the loop
//
// Adding a new tool:
//   1. Create FooTool.kt in agent/tools/
//   2. Add it to the `tools` map below
//   3. Add a `when` branch in execute()
//   4. Add its ToolDefinition to allToolDefinitions()
//   That's it — the orchestrator picks it up automatically.

import android.Manifest
import android.content.Context
import android.os.Build
import com.medusa.mobile.services.PermissionManager
import com.medusa.mobile.agent.tools.AppLauncherTool
import com.medusa.mobile.agent.tools.CallHistoryTool
import com.medusa.mobile.agent.tools.IntentTool
import com.medusa.mobile.agent.tools.ContactsTool
import com.medusa.mobile.agent.tools.EmailTool
import com.medusa.mobile.agent.tools.GoogleDocsDriveTool
import com.medusa.mobile.agent.tools.GoogleSheetsTool
import com.medusa.mobile.agent.tools.MapsTool
import com.medusa.mobile.agent.tools.MemoryStoreTool
import com.medusa.mobile.agent.tools.SmsTool
import com.medusa.mobile.agent.tools.CalendarTool
import com.medusa.mobile.agent.tools.DeviceUtilitiesTool
import com.medusa.mobile.agent.tools.FileManagerTool
import com.medusa.mobile.agent.tools.PhotosTool
import com.medusa.mobile.agent.tools.WebResearchTool
import com.medusa.mobile.services.GoogleAuthManager
import com.medusa.mobile.api.NotificationTool
import com.medusa.mobile.api.ToolDefinition
import com.medusa.mobile.models.ToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.json.JSONObject

/**
 * Routes tool_use blocks from Claude to the correct tool implementation.
 *
 * Thread-safe: each tool holds only a Context ref and does its own
 * ContentResolver queries — no shared mutable state.
 */
class ToolDispatcher(private val context: Context) {

    // ── Tool Instances ──────────────────────────────────────────────────────

    private val appLauncherTool = AppLauncherTool(context)  // mm-015
    private val intentTool = IntentTool(context)             // mm-025
    private val smsTool = SmsTool(context)
    private val callHistoryTool = CallHistoryTool(context)
    private val contactsTool = ContactsTool(context)
    private val mapsTool = MapsTool(context)        // mm-020
    private val memoryStoreTool = MemoryStoreTool(context)  // mm-017
    private val googleAuthManager = GoogleAuthManager(context)
    private val googleDocsDriveTool = GoogleDocsDriveTool(context, googleAuthManager) // mm-019
    private val googleSheetsTool = GoogleSheetsTool(context, googleAuthManager)     // mm-024
    private val emailTool = EmailTool(context, googleAuthManager)                   // mm-016
    private val webResearchTool = WebResearchTool()                                 // mm-021
    private val calendarTool = CalendarTool(context)                                // mm-014
    private val fileManagerTool = FileManagerTool(context)                          // mm-022
    private val photosTool = PhotosTool(context)                                    // mm-018
    private val deviceUtilitiesTool = DeviceUtilitiesTool(context)                  // mm-026
    // NotificationTool is an object (singleton) — no instantiation needed

    /** Expose auth manager so ChatViewModel can trigger Google Sign-In UI. */
    val googleAuth: GoogleAuthManager get() = googleAuthManager

    // ── JSON serializer for ToolResult ──────────────────────────────────────

    private val json = Json { encodeDefaults = true; prettyPrint = false }

    // ── Tool Definitions (sent to Claude in the API request) ────────────────

    /**
     * Returns all registered tool definitions for the Claude Messages API.
     * These go into the `tools` array of the request body.
     */
    fun allToolDefinitions(): List<ToolDefinition> = listOf(
        // SMS Tool
        ToolDefinition(
            name = "get_sms",
            description = """
                Returns recent SMS and MMS text messages from the user's phone.
                Use this to answer questions like "what did Sarah text me?",
                "any messages about the meeting?", "show me my recent texts",
                or "did I get a text from that number?".
                Returns messages ordered newest-first.
                Requires READ_SMS permission (requested automatically on first use).
            """.trimIndent(),
            inputSchema = JSONObject("""
                {
                    "type": "object",
                    "properties": {
                        "limit": { "type": "integer", "description": "Max messages to return. Default 20, max 100.", "default": 20 },
                        "sender": { "type": "string", "description": "Filter by sender name or number (case-insensitive)." },
                        "keyword": { "type": "string", "description": "Filter messages containing this keyword (case-insensitive)." },
                        "since_hours": { "type": "integer", "description": "Only messages from last N hours. 24=today, 168=this week." },
                        "type": { "type": "string", "enum": ["all", "inbox", "sent"], "description": "Message type filter. Default 'all'.", "default": "all" }
                    },
                    "required": []
                }
            """.trimIndent())
        ),

        // Call History Tool
        ToolDefinition(
            name = "get_call_history",
            description = """
                Returns the user's phone call history — incoming, outgoing, missed,
                rejected, blocked, and voicemail calls. Includes caller name, number,
                duration, and timestamp. Full historical log — not just new calls.
                Use for "who called me?", "any missed calls?", "how long was my call with X?".
                Requires READ_CALL_LOG permission.
            """.trimIndent(),
            inputSchema = JSONObject("""
                {
                    "type": "object",
                    "properties": {
                        "limit": { "type": "integer", "description": "Max calls to return. Default 20, max 100.", "default": 20 },
                        "caller": { "type": "string", "description": "Filter by caller name or number (case-insensitive)." },
                        "direction": { "type": "string", "enum": ["all", "incoming", "outgoing", "missed", "rejected", "blocked", "voicemail"], "description": "Call type filter. Default 'all'.", "default": "all" },
                        "since_hours": { "type": "integer", "description": "Only calls from last N hours." },
                        "min_duration": { "type": "integer", "description": "Only calls longer than N seconds." }
                    },
                    "required": []
                }
            """.trimIndent())
        ),

        // Notification Tool (already has its definition as a ToolDefinition object)
        NotificationTool.toolDefinition,

        // Send SMS Tool (mm-006 — Dev4 added send capability)
        ToolDefinition(
            name = "send_sms",
            description = """
                Send an SMS text message to a phone number. Use when the user asks
                "text Mom", "send a message to 555-1234", or "reply to Sarah".
                IMPORTANT: Always confirm the recipient and message content with the
                user before sending. Auto-splits long messages (160 char SMS limit).
                Requires SEND_SMS permission.
            """.trimIndent(),
            inputSchema = JSONObject("""
                {
                    "type": "object",
                    "properties": {
                        "phone_number": { "type": "string", "description": "Recipient phone number. E.g. '+15551234567' or '555-123-4567'." },
                        "message": { "type": "string", "description": "The text message body to send." }
                    },
                    "required": ["phone_number", "message"]
                }
            """.trimIndent())
        ),

        // Contacts Tool (mm-013)
        ToolDefinition(
            name = "get_contacts",
            description = """
                Searches the user's phone contacts / address book. Returns names, phone
                numbers, email addresses, organization, birthday, and notes.
                Use this to answer "what's Sarah's number?", "find contacts at Google",
                "who do I know named John?", "show me my favorites", or
                "do I have an email for Dr. Smith?".
                Requires READ_CONTACTS permission.
            """.trimIndent(),
            inputSchema = JSONObject("""
                {
                    "type": "object",
                    "properties": {
                        "query": { "type": "string", "description": "Search query — matches name, phone, email, organization, notes. Case-insensitive partial match." },
                        "limit": { "type": "integer", "description": "Max contacts to return. Default 20, max 100.", "default": 20 },
                        "mode": { "type": "string", "enum": ["search", "recent", "favorites", "all"], "description": "Query mode. Default 'search'.", "default": "search" }
                    },
                    "required": []
                }
            """.trimIndent())
        ),

        // Location Tool (mm-020)
        ToolDefinition(
            name = "get_location",
            description = """
                Returns the user's current GPS location — latitude, longitude, accuracy,
                and reverse-geocoded street address. Use for "where am I?", "what's nearby?",
                or when you need the user's position for directions or place searches.
                Requires ACCESS_FINE_LOCATION permission.
            """.trimIndent(),
            inputSchema = JSONObject("""
                {
                    "type": "object",
                    "properties": {
                        "include_address": { "type": "boolean", "description": "Reverse-geocode to get street address. Default true.", "default": true }
                    },
                    "required": []
                }
            """.trimIndent())
        ),

        // Google Docs — create (mm-019)
        ToolDefinition(
            name = "google_docs_create",
            description = """
                Create a new Google Doc with a title and body content. Returns the
                document URL. Use when the user says "create a google doc that says...",
                "make a document about...", "write up a doc for...".
                Requires Google account sign-in.
            """.trimIndent(),
            inputSchema = JSONObject("""
                {
                    "type": "object",
                    "properties": {
                        "title": { "type": "string", "description": "Document title." },
                        "content": { "type": "string", "description": "Document body text content." }
                    },
                    "required": ["title", "content"]
                }
            """.trimIndent())
        ),

        // Google Docs — edit (mm-019)
        ToolDefinition(
            name = "google_docs_edit",
            description = """
                Edit an existing Google Doc — append or replace content. Requires the
                document ID (from a previous search or create). Use for "add to my doc",
                "update the meeting notes", "replace the content of...".
                Requires Google account sign-in.
            """.trimIndent(),
            inputSchema = JSONObject("""
                {
                    "type": "object",
                    "properties": {
                        "document_id": { "type": "string", "description": "Google Doc document ID (from URL or previous search)." },
                        "content": { "type": "string", "description": "Text content to insert." },
                        "mode": { "type": "string", "enum": ["append", "replace"], "description": "Edit mode. 'append' adds to end, 'replace' replaces all content. Default 'append'.", "default": "append" }
                    },
                    "required": ["document_id", "content"]
                }
            """.trimIndent())
        ),

        // Google Docs — share (mm-019)
        ToolDefinition(
            name = "google_docs_share",
            description = """
                Share a Google Doc or Drive file with someone via email. Use for "share
                that doc with Sarah", "give John access to...". Sends email notification.
                Requires Google account sign-in.
            """.trimIndent(),
            inputSchema = JSONObject("""
                {
                    "type": "object",
                    "properties": {
                        "file_id": { "type": "string", "description": "Google Drive file ID (document ID works too)." },
                        "email": { "type": "string", "description": "Recipient's email address." },
                        "role": { "type": "string", "enum": ["reader", "writer", "commenter"], "description": "Permission level. Default 'writer'.", "default": "writer" }
                    },
                    "required": ["file_id", "email"]
                }
            """.trimIndent())
        ),

        // Google Drive — search (mm-019)
        ToolDefinition(
            name = "google_drive_search",
            description = """
                Search Google Drive for files by name, content, or type. Returns file
                names, types, dates, and links. Use for "find my doc about...",
                "search drive for...", "where's my spreadsheet about...".
                Requires Google account sign-in.
            """.trimIndent(),
            inputSchema = JSONObject("""
                {
                    "type": "object",
                    "properties": {
                        "query": { "type": "string", "description": "Search query — matches file names and content." },
                        "file_type": { "type": "string", "enum": ["doc", "sheet", "slide", "pdf", "folder", "image"], "description": "Optional file type filter." },
                        "limit": { "type": "integer", "description": "Max results. Default 10, max 50.", "default": 10 }
                    },
                    "required": ["query"]
                }
            """.trimIndent())
        ),

        // Google Drive — list recent (mm-019)
        ToolDefinition(
            name = "google_drive_list",
            description = """
                List recent files in Google Drive, ordered by last modified.
                Use for "show my recent docs", "what's in my drive?", "list my files".
                Requires Google account sign-in.
            """.trimIndent(),
            inputSchema = JSONObject("""
                {
                    "type": "object",
                    "properties": {
                        "limit": { "type": "integer", "description": "Max files to return. Default 10, max 50.", "default": 10 }
                    },
                    "required": []
                }
            """.trimIndent())
        ),

        // Web Research — search (mm-021)
        ToolDefinition(
            name = "web_search",
            description = """
                Search the web using Google. Returns top results with title, URL, and snippet.
                Use when the user asks a general knowledge question, wants current info, or
                needs to look something up. Examples: "what's the weather in NYC?",
                "latest news about Tesla", "best Italian restaurants near me".
                Follow up with web_fetch to read a specific page in detail.
            """.trimIndent(),
            inputSchema = JSONObject("""
                {
                    "type": "object",
                    "properties": {
                        "query": { "type": "string", "description": "The search query. Be specific for better results." },
                        "num_results": { "type": "integer", "description": "Number of results to return. Default 5, max 10.", "default": 5 }
                    },
                    "required": ["query"]
                }
            """.trimIndent())
        ),

        // Web Research — fetch (mm-021)
        ToolDefinition(
            name = "web_fetch",
            description = """
                Fetch a web page and extract readable text (HTML tags stripped).
                Use after web_search to read a result in full, or when user provides a URL.
                Useful for reading articles, documentation, recipes, etc.
                Max content: 8000 characters.
            """.trimIndent(),
            inputSchema = JSONObject("""
                {
                    "type": "object",
                    "properties": {
                        "url": { "type": "string", "description": "Full URL to fetch. Must start with http:// or https://." },
                        "max_length": { "type": "integer", "description": "Max characters to return. Default 8000.", "default": 8000 }
                    },
                    "required": ["url"]
                }
            """.trimIndent())
        ),

        // Google Sheets — find (mm-024)
        ToolDefinition(
            name = "sheets_find",
            description = """
                Search Google Drive for spreadsheets by name — use this first to get a
                spreadsheet ID before reading or writing. Returns names, IDs, links.
                Requires Google account sign-in.
            """.trimIndent(),
            inputSchema = JSONObject("""
                {
                    "type": "object",
                    "properties": {
                        "query": { "type": "string", "description": "Spreadsheet name to search. Blank = list recent." },
                        "limit": { "type": "integer", "description": "Max results. Default 10.", "default": 10 }
                    },
                    "required": []
                }
            """.trimIndent())
        ),

        // Google Sheets — read (mm-024)
        ToolDefinition(
            name = "sheets_read",
            description = """
                Read a range of cells from a Google Spreadsheet.
                Use A1 notation: "A1:C10", "Sheet1!A:A", "Sheet1".
                Use sheets_find first if you need the spreadsheet ID.
                Requires Google account sign-in.
            """.trimIndent(),
            inputSchema = JSONObject("""
                {
                    "type": "object",
                    "properties": {
                        "spreadsheet_id": { "type": "string", "description": "Spreadsheet ID from sheets_find or URL." },
                        "range": { "type": "string", "description": "Cell range in A1 notation. Default 'Sheet1'.", "default": "Sheet1" },
                        "include_headers": { "type": "boolean", "description": "Format first row as headers. Default true.", "default": true }
                    },
                    "required": ["spreadsheet_id"]
                }
            """.trimIndent())
        ),

        // Google Sheets — write (mm-024)
        ToolDefinition(
            name = "sheets_write",
            description = """
                Write/overwrite cells in a Google Spreadsheet. Pass values as JSON 2D array.
                Use sheets_append to ADD rows without overwriting.
                Requires Google account sign-in.
            """.trimIndent(),
            inputSchema = JSONObject("""
                {
                    "type": "object",
                    "properties": {
                        "spreadsheet_id": { "type": "string", "description": "Spreadsheet ID." },
                        "range": { "type": "string", "description": "A1 notation range to write to." },
                        "values": { "type": "string", "description": "JSON 2D array: e.g. '[[\"Name\",\"Score\"],[\"Alice\",\"95\"]]'." }
                    },
                    "required": ["spreadsheet_id", "range", "values"]
                }
            """.trimIndent())
        ),

        // Google Sheets — append (mm-024)
        ToolDefinition(
            name = "sheets_append",
            description = """
                Append rows to the end of a Google Spreadsheet (after existing data).
                Use for "add to my grocery list", "log today's workout", "record this entry".
                Requires Google account sign-in.
            """.trimIndent(),
            inputSchema = JSONObject("""
                {
                    "type": "object",
                    "properties": {
                        "spreadsheet_id": { "type": "string", "description": "Spreadsheet ID." },
                        "range": { "type": "string", "description": "Sheet/table to append to. Default 'Sheet1'.", "default": "Sheet1" },
                        "values": { "type": "string", "description": "JSON 2D array of rows to append: e.g. '[[\"Milk\"],[\"Eggs\"]]'." }
                    },
                    "required": ["spreadsheet_id", "values"]
                }
            """.trimIndent())
        ),

        // Google Sheets — create (mm-024)
        ToolDefinition(
            name = "sheets_create",
            description = """
                Create a new Google Spreadsheet with an optional title and header row.
                Use for "make a new spreadsheet for...", "create a workout log sheet".
                Requires Google account sign-in.
            """.trimIndent(),
            inputSchema = JSONObject("""
                {
                    "type": "object",
                    "properties": {
                        "title": { "type": "string", "description": "Spreadsheet title.", "default": "Untitled Spreadsheet" },
                        "headers": { "type": "string", "description": "Comma-separated column headers for row 1. E.g. 'Date,Exercise,Reps'." }
                    },
                    "required": []
                }
            """.trimIndent())
        ),

        // Email — Gmail search (mm-016)
        ToolDefinition(
            name = "gmail_search",
            description = """
                Search Gmail inbox or all mail. Supports Gmail search syntax:
                  from:sarah, to:me, subject:invoice, is:unread, has:attachment,
                  after:2024/1/1, before:2024/12/31, label:important, etc.
                Use for "any emails from Sarah?", "show unread", "find invoice emails",
                "what did John email me?", "check my inbox".
                Requires Google account sign-in (same account as Docs/Drive).
            """.trimIndent(),
            inputSchema = JSONObject("""
                {
                    "type": "object",
                    "properties": {
                        "query": { "type": "string", "description": "Gmail search query. Leave blank for recent messages. Supports full Gmail query syntax." },
                        "limit": { "type": "integer", "description": "Max emails to return. Default 20, max 50.", "default": 20 },
                        "unread_only": { "type": "boolean", "description": "Only return unread emails. Default false.", "default": false }
                    },
                    "required": []
                }
            """.trimIndent())
        ),

        // Email — Gmail read (mm-016)
        ToolDefinition(
            name = "gmail_read",
            description = """
                Read the full content of a Gmail email by message ID.
                Get message IDs from gmail_search results. Returns full headers
                (From, To, Subject, Date) and decoded body text.
            """.trimIndent(),
            inputSchema = JSONObject("""
                {
                    "type": "object",
                    "properties": {
                        "message_id": { "type": "string", "description": "Gmail message ID from gmail_search results." }
                    },
                    "required": ["message_id"]
                }
            """.trimIndent())
        ),

        // Email — Gmail send (mm-016)
        ToolDefinition(
            name = "gmail_send",
            description = """
                Send an email via Gmail. IMPORTANT: Always confirm recipient, subject,
                and message content with the user before sending — this action is irreversible.
                Supports plain text body. Use for "email Sarah", "send a message to...",
                "reply to...".
                Requires Google account sign-in.
            """.trimIndent(),
            inputSchema = JSONObject("""
                {
                    "type": "object",
                    "properties": {
                        "to": { "type": "string", "description": "Recipient email address(es). Comma-separate for multiple." },
                        "subject": { "type": "string", "description": "Email subject line." },
                        "body": { "type": "string", "description": "Email body text (plain text)." },
                        "cc": { "type": "string", "description": "CC recipients, comma-separated. Optional." },
                        "reply_to_message_id": { "type": "string", "description": "Message ID to thread this reply under. Optional." }
                    },
                    "required": ["to", "subject", "body"]
                }
            """.trimIndent())
        ),

        // Email — IMAP setup (mm-016)
        ToolDefinition(
            name = "email_imap_setup",
            description = """
                Set up a non-Gmail email account via IMAP (iCloud Mail, Yahoo, AOL,
                Outlook, Exchange, or any standard IMAP server). Store credentials securely
                in EncryptedSharedPreferences. One-time setup per account.
                IMPORTANT: Requires an App Password, NOT the regular password.
                Common presets:
                  iCloud: host=imap.mail.me.com, port=993
                  Yahoo:  host=imap.mail.yahoo.com, port=993
                  Outlook/Exchange: host=outlook.office365.com, port=993
            """.trimIndent(),
            inputSchema = JSONObject("""
                {
                    "type": "object",
                    "properties": {
                        "host": { "type": "string", "description": "IMAP server hostname. E.g. 'imap.mail.me.com' for iCloud." },
                        "email": { "type": "string", "description": "Email address to log in with." },
                        "password": { "type": "string", "description": "App password (NOT regular password). Generate in account security settings." },
                        "port": { "type": "integer", "description": "IMAP port. Default 993 (SSL).", "default": 993 }
                    },
                    "required": ["host", "email", "password"]
                }
            """.trimIndent())
        ),

        // Email — IMAP search (mm-016)
        ToolDefinition(
            name = "email_imap_search",
            description = """
                Search email via IMAP — works with iCloud, Yahoo, Outlook, AOL, and any
                standard email provider configured via email_imap_setup. Searches subject,
                sender, and body. Use for "check my iCloud email", "any Yahoo mail from...",
                "search my work inbox for...".
            """.trimIndent(),
            inputSchema = JSONObject("""
                {
                    "type": "object",
                    "properties": {
                        "query": { "type": "string", "description": "Search term — matches subject, sender name, and body. Leave blank for recent messages." },
                        "folder": { "type": "string", "description": "Mailbox folder to search. Default 'INBOX'.", "default": "INBOX" },
                        "limit": { "type": "integer", "description": "Max messages to return. Default 20, max 50.", "default": 20 }
                    },
                    "required": []
                }
            """.trimIndent())
        ),

        // Memory Store — remember (mm-017)
        ToolDefinition(
            name = "memory_remember",
            description = """
                Store a piece of information in persistent memory. Use when the user says
                "remember that...", "keep in mind...", "note that...", "save this...", or
                anytime you learn a fact, preference, or detail worth retaining across
                conversations. Memories survive app restarts. Use descriptive keys.
            """.trimIndent(),
            inputSchema = JSONObject("""
                {
                    "type": "object",
                    "properties": {
                        "key": { "type": "string", "description": "Short snake_case identifier. E.g. 'mom_birthday', 'seat_preference', 'dinner_friday'." },
                        "value": { "type": "string", "description": "The information to remember. Be specific and complete." },
                        "category": { "type": "string", "enum": ["preference", "fact", "person", "event", "reminder", "general"], "description": "Category for grouping. Default 'general'.", "default": "general" },
                        "tags": { "type": "string", "description": "Comma-separated tags for search. E.g. 'food,dinner,adam'." }
                    },
                    "required": ["key", "value"]
                }
            """.trimIndent())
        ),

        // Memory Store — recall (mm-017)
        ToolDefinition(
            name = "memory_recall",
            description = """
                Retrieve stored memories. Use when the user asks "what did I tell you about...",
                "do you remember...", "what are my preferences?", or when you need previously
                stored context. Search by key, category, or free text. Call this PROACTIVELY
                when you think stored knowledge would help answer the user's question.
            """.trimIndent(),
            inputSchema = JSONObject("""
                {
                    "type": "object",
                    "properties": {
                        "key": { "type": "string", "description": "Exact memory key to look up. Falls back to search if not found." },
                        "category": { "type": "string", "description": "Filter by category: preference, fact, person, event, reminder, general." },
                        "query": { "type": "string", "description": "Free-text search across all memory fields." },
                        "limit": { "type": "integer", "description": "Max memories to return. Default 20.", "default": 20 }
                    },
                    "required": []
                }
            """.trimIndent())
        ),

        // Memory Store — forget (mm-017)
        ToolDefinition(
            name = "memory_forget",
            description = """
                Delete stored memories. Use when the user says "forget that...",
                "delete the memory about...", "clear all my preferences", "forget everything".
                Can delete by key, by category, or all memories. Confirm before deleting all.
            """.trimIndent(),
            inputSchema = JSONObject("""
                {
                    "type": "object",
                    "properties": {
                        "key": { "type": "string", "description": "Delete the memory with this key." },
                        "category": { "type": "string", "description": "Delete all memories in this category." },
                        "all": { "type": "boolean", "description": "Delete ALL memories. Use with extreme caution — confirm with user first.", "default": false }
                    },
                    "required": []
                }
            """.trimIndent())
        ),

        // App Launcher — list (mm-015)
        ToolDefinition(
            name = "list_apps",
            description = """
                Returns a list of installed apps on the device, searchable by name.
                Use before launching to confirm the app name, or to answer "what apps
                do I have?", "is Spotify installed?", "find my banking app".
                Returns user-installed apps only by default.
            """.trimIndent(),
            inputSchema = JSONObject("""
                {
                    "type": "object",
                    "properties": {
                        "query": { "type": "string", "description": "Filter apps whose name contains this string (case-insensitive)." },
                        "include_system": { "type": "boolean", "description": "Include system apps. Default false.", "default": false },
                        "limit": { "type": "integer", "description": "Max apps to return. Default 30, max 100.", "default": 30 }
                    },
                    "required": []
                }
            """.trimIndent())
        ),

        // App Launcher — launch (mm-015)
        ToolDefinition(
            name = "launch_app",
            description = """
                Opens an installed app by name or package name. Use when the user says
                "open Spotify", "launch YouTube", "open my camera", "start WhatsApp".
                Use list_apps first if the name is ambiguous. Brings app to foreground.
            """.trimIndent(),
            inputSchema = JSONObject("""
                {
                    "type": "object",
                    "properties": {
                        "app_name": { "type": "string", "description": "App display name (case-insensitive partial match). E.g. 'Spotify', 'YouTube'." },
                        "package_name": { "type": "string", "description": "Exact package name for precise launch. Preferred if known." }
                    },
                    "required": []
                }
            """.trimIndent())
        ),

        // App Launcher — open URL / deep link (mm-015)
        ToolDefinition(
            name = "open_url",
            description = """
                Opens a URL or deep link: https://, http://, tel:, mailto:, or custom
                app schemes (spotify:, youtube://, etc.). Use for "open this link",
                "call this number", "email Sarah", or app-specific deep links.
            """.trimIndent(),
            inputSchema = JSONObject("""
                {
                    "type": "object",
                    "properties": {
                        "url": { "type": "string", "description": "URL or deep link to open. E.g. 'https://example.com', 'tel:+15551234567', 'mailto:user@example.com'." }
                    },
                    "required": ["url"]
                }
            """.trimIndent())
        ),

        // Maps Tool (mm-020)
        ToolDefinition(
            name = "open_maps",
            description = """
                Opens Google Maps with directions, a place search, or turn-by-turn navigation.
                Use for "get directions to the airport", "find coffee nearby", "navigate to
                123 Main St". Supports driving, walking, transit, bicycling. Chain with
                get_location for origin or calendar for meeting location.
            """.trimIndent(),
            inputSchema = JSONObject("""
                {
                    "type": "object",
                    "properties": {
                        "destination": { "type": "string", "description": "Destination address, place, or lat,lng. Required for directions/navigate." },
                        "origin": { "type": "string", "description": "Starting point. Omit for current location." },
                        "mode": { "type": "string", "enum": ["driving", "walking", "transit", "bicycling"], "description": "Travel mode. Default 'driving'.", "default": "driving" },
                        "action": { "type": "string", "enum": ["directions", "search", "navigate"], "description": "directions=show route, search=find places, navigate=turn-by-turn. Default 'directions'.", "default": "directions" },
                        "query": { "type": "string", "description": "Search query for 'search' action. E.g. 'coffee shops', 'gas stations'." }
                    },
                    "required": []
                }
            """.trimIndent())
        ),

        // Intent — phone call (mm-025)
        ToolDefinition(
            name = "make_phone_call",
            description = """
                Dials a phone number — opens the dialer pre-filled (default) or places
                the call directly (direct=true, needs CALL_PHONE permission).
                Use for "call Mom", "dial 555-1234", "phone the doctor".
                Pair with get_contacts to resolve a name to a number first.
            """.trimIndent(),
            inputSchema = JSONObject("""
                {
                    "type": "object",
                    "properties": {
                        "phone_number": { "type": "string", "description": "Phone number to dial. E.g. '+15551234567'." },
                        "direct": { "type": "boolean", "description": "If true, call directly (needs CALL_PHONE). Default false = open dialer.", "default": false }
                    },
                    "required": ["phone_number"]
                }
            """.trimIndent())
        ),

        // Intent — set alarm (mm-025)
        ToolDefinition(
            name = "set_alarm",
            description = """
                Creates an alarm in the system clock app. Use for "set an alarm for 7am",
                "wake me up at 6:30", "alarm tomorrow at 8". Supports repeat days and labels.
            """.trimIndent(),
            inputSchema = JSONObject("""
                {
                    "type": "object",
                    "properties": {
                        "hour": { "type": "integer", "description": "Hour in 24-hour format (0-23). E.g. 7 for 7am, 14 for 2pm." },
                        "minutes": { "type": "integer", "description": "Minutes (0-59). Default 0.", "default": 0 },
                        "label": { "type": "string", "description": "Optional alarm label. E.g. 'Doctor appointment'." },
                        "days": { "type": "array", "items": {"type": "integer"}, "description": "Repeat days: 1=Sun, 2=Mon, 3=Tue, 4=Wed, 5=Thu, 6=Fri, 7=Sat." },
                        "vibrate": { "type": "boolean", "description": "Alarm vibration. Default true.", "default": true }
                    },
                    "required": ["hour"]
                }
            """.trimIndent())
        ),

        // Intent — set timer (mm-025)
        ToolDefinition(
            name = "set_timer",
            description = """
                Starts a countdown timer in the system clock app. Use for "set a timer
                for 10 minutes", "remind me in 30 seconds", "timer 1 hour".
            """.trimIndent(),
            inputSchema = JSONObject("""
                {
                    "type": "object",
                    "properties": {
                        "seconds": { "type": "integer", "description": "Duration in seconds. E.g. 600 = 10 minutes, 3600 = 1 hour." },
                        "label": { "type": "string", "description": "Optional timer label. E.g. 'Pasta', 'Medication'." }
                    },
                    "required": ["seconds"]
                }
            """.trimIndent())
        ),

        // Intent — open settings (mm-025)
        ToolDefinition(
            name = "open_settings",
            description = """
                Opens a specific Android settings panel. Use for "open WiFi settings",
                "go to Bluetooth", "show accessibility settings", "open battery settings".
                Panels: main, wifi, bluetooth, location, battery, accessibility,
                notifications, apps, display, sound, storage, security, date_time.
            """.trimIndent(),
            inputSchema = JSONObject("""
                {
                    "type": "object",
                    "properties": {
                        "panel": { "type": "string", "enum": ["main","wifi","bluetooth","location","battery","accessibility","notifications","apps","display","sound","storage","security","date_time"], "description": "Settings panel to open. Default 'main'.", "default": "main" }
                    },
                    "required": []
                }
            """.trimIndent())
        ),

        // Intent — share text (mm-025)
        ToolDefinition(
            name = "share_text",
            description = """
                Opens the Android share sheet to share text via any app (WhatsApp,
                Messages, Gmail, etc.). Use for "share this", "send to WhatsApp",
                "share the address". User picks the destination app.
            """.trimIndent(),
            inputSchema = JSONObject("""
                {
                    "type": "object",
                    "properties": {
                        "text": { "type": "string", "description": "Text content to share." },
                        "title": { "type": "string", "description": "Optional subject/title (used as email subject, etc.)." }
                    },
                    "required": ["text"]
                }
            """.trimIndent())
        ),

        // ── Calendar Tool (mm-014) ────────────────────────────────────────────

        ToolDefinition(
            name = "calendar_list_events",
            description = """
                Lists upcoming (and optionally recent past) calendar events across all
                synced calendars — Google, iCloud, Exchange, etc. Returns title, date/time,
                location, description, and calendar name. Use for "what's on my calendar?",
                "any events this week?", "show me today's schedule", "what do I have tomorrow?".
                Requires READ_CALENDAR permission.
            """.trimIndent(),
            inputSchema = JSONObject("""
                {
                    "type": "object",
                    "properties": {
                        "days_ahead":  { "type": "integer", "description": "Days into the future to include. Default 7.", "default": 7 },
                        "days_behind": { "type": "integer", "description": "Days in the past to include. Default 0.", "default": 0 },
                        "limit":       { "type": "integer", "description": "Max events to return. Default 20.", "default": 20 }
                    },
                    "required": []
                }
            """.trimIndent())
        ),

        ToolDefinition(
            name = "calendar_search_events",
            description = """
                Searches calendar events by keyword (title, description, location) with
                optional date range. Use for "do I have anything with John?",
                "find dentist appointment", "any meetings about the project?",
                "what events are in March?".
                Requires READ_CALENDAR permission.
            """.trimIndent(),
            inputSchema = JSONObject("""
                {
                    "type": "object",
                    "properties": {
                        "query":      { "type": "string", "description": "Keyword to search in title, description, and location. Case-insensitive." },
                        "start_date": { "type": "string", "description": "Lower bound date — 'yyyy-MM-dd'. Optional." },
                        "end_date":   { "type": "string", "description": "Upper bound date — 'yyyy-MM-dd'. Optional." },
                        "limit":      { "type": "integer", "description": "Max events. Default 20.", "default": 20 }
                    },
                    "required": []
                }
            """.trimIndent())
        ),

        ToolDefinition(
            name = "calendar_create_event",
            description = """
                Creates a new calendar event. IMPORTANT: Always confirm event details with
                the user before creating — title, date, time, and any recurrence.
                Use for "add a meeting on Friday at 2pm", "create a dentist appointment",
                "schedule lunch with Sarah next Tuesday".
                Requires WRITE_CALENDAR permission.
            """.trimIndent(),
            inputSchema = JSONObject("""
                {
                    "type": "object",
                    "properties": {
                        "title":       { "type": "string", "description": "Event title (required)." },
                        "start_time":  { "type": "string", "description": "Start date/time — 'yyyy-MM-dd HH:mm' or ISO-8601. E.g. '2024-03-15 14:00'." },
                        "end_time":    { "type": "string", "description": "End date/time — same format as start_time." },
                        "description": { "type": "string", "description": "Optional event notes or agenda." },
                        "location":    { "type": "string", "description": "Optional location string." },
                        "all_day":     { "type": "boolean", "description": "All-day event. Default false.", "default": false }
                    },
                    "required": ["title", "start_time", "end_time"]
                }
            """.trimIndent())
        ),

        ToolDefinition(
            name = "calendar_delete_event",
            description = """
                Deletes a calendar event by its ID. Get event IDs from calendar_list_events
                or calendar_search_events results.
                IMPORTANT: Always confirm with the user before deleting — this is irreversible.
                Requires WRITE_CALENDAR permission.
            """.trimIndent(),
            inputSchema = JSONObject("""
                {
                    "type": "object",
                    "properties": {
                        "event_id": { "type": "integer", "description": "Calendar event ID from list/search results." }
                    },
                    "required": ["event_id"]
                }
            """.trimIndent())
        ),

        // ── File Manager Tool (mm-022) ────────────────────────────────────────

        ToolDefinition(
            name = "file_list",
            description = """
                Lists files in a public device directory (Downloads, Documents, Pictures,
                Music, Movies). Sorted newest-first. Use for "what's in my downloads?",
                "show my documents", "list my PDFs", "what files do I have?".
                Does not require special permission on Android 13+.
            """.trimIndent(),
            inputSchema = JSONObject("""
                {
                    "type": "object",
                    "properties": {
                        "directory": { "type": "string", "enum": ["downloads","documents","pictures","music","movies","dcim"], "description": "Which public directory to list. Default 'downloads'.", "default": "downloads" },
                        "extension": { "type": "string", "description": "Filter by file extension — 'txt', 'pdf', 'jpg', etc. Optional." },
                        "limit":     { "type": "integer", "description": "Max entries. Default 30.", "default": 30 }
                    },
                    "required": []
                }
            """.trimIndent())
        ),

        ToolDefinition(
            name = "file_read",
            description = """
                Reads and returns the text content of a file. Works with .txt, .md,
                .csv, .json, .xml, .html, .kt, .py, .js, and other text files.
                Cannot read binary files (images, video, audio, PDF, APK, etc.).
                Use for "read my notes.txt", "show me the content of that file",
                "what does my shopping list say?".
                Returns first 8000 characters if the file is large.
            """.trimIndent(),
            inputSchema = JSONObject("""
                {
                    "type": "object",
                    "properties": {
                        "path":      { "type": "string", "description": "Absolute file path, or filename relative to Downloads. E.g. 'notes.txt' or '/storage/emulated/0/Documents/report.md'." },
                        "max_chars": { "type": "integer", "description": "Max characters to return. Default 8000.", "default": 8000 }
                    },
                    "required": ["path"]
                }
            """.trimIndent())
        ),

        ToolDefinition(
            name = "file_write",
            description = """
                Creates or overwrites a text file in a public directory (default: Documents).
                Use for "save this as a note", "write my grocery list to a file",
                "create a text file called agenda.txt with...", "update my notes".
                Only plain text — not images or other binary formats.
            """.trimIndent(),
            inputSchema = JSONObject("""
                {
                    "type": "object",
                    "properties": {
                        "filename":  { "type": "string", "description": "File name only, e.g. 'notes.txt'. No path separators." },
                        "content":   { "type": "string", "description": "Text content to write." },
                        "directory": { "type": "string", "enum": ["documents","downloads"], "description": "Target directory. Default 'documents'.", "default": "documents" },
                        "overwrite": { "type": "boolean", "description": "Overwrite if exists. Default true.", "default": true }
                    },
                    "required": ["filename", "content"]
                }
            """.trimIndent())
        ),

        ToolDefinition(
            name = "file_search",
            description = """
                Searches for files by name across all public directories (Downloads,
                Documents, Pictures, Music, Movies). Use for "find the file called budget",
                "search for any PDFs", "where's my invoice?", "find files with 'report' in the name".
            """.trimIndent(),
            inputSchema = JSONObject("""
                {
                    "type": "object",
                    "properties": {
                        "query":     { "type": "string", "description": "Filename substring to match (case-insensitive)." },
                        "extension": { "type": "string", "description": "File extension filter — 'pdf', 'txt', 'jpg'. Optional." },
                        "directory": { "type": "string", "description": "Limit search to one directory. Searches all if omitted." },
                        "limit":     { "type": "integer", "description": "Max results. Default 20.", "default": 20 }
                    },
                    "required": []
                }
            """.trimIndent())
        ),

        ToolDefinition(
            name = "file_delete",
            description = """
                Deletes a file from device storage.
                IMPORTANT: Always confirm with the user before deleting — cannot be undone.
                Use for "delete that file", "remove notes.txt from my Downloads".
            """.trimIndent(),
            inputSchema = JSONObject("""
                {
                    "type": "object",
                    "properties": {
                        "path": { "type": "string", "description": "Absolute file path, or filename relative to Downloads." }
                    },
                    "required": ["path"]
                }
            """.trimIndent())
        ),

        // ── Photos Tool (mm-018) ──────────────────────────────────────────────

        ToolDefinition(
            name = "get_photos",
            description = """
                Searches and lists photos/images on the device via MediaStore.
                Returns metadata: file name, album/bucket, date taken, dimensions, size, content URI.
                Does NOT access file bytes — only metadata and content URIs for display.
                Use for "show me photos from last week", "how many pictures from Paris?",
                "find photos in my Camera roll", "what photos do I have from July?",
                "list my most recent pictures", "show available albums".
                Requires READ_MEDIA_IMAGES (Android 13+) or READ_EXTERNAL_STORAGE.
            """.trimIndent(),
            inputSchema = JSONObject("""
                {
                    "type": "object",
                    "properties": {
                        "query":       { "type": "string", "description": "Filter by album/bucket name or file name. Case-insensitive partial match. E.g. 'Camera', 'Screenshots', 'Paris'." },
                        "limit":       { "type": "integer", "description": "Max photos to return. Default 20, max 100.", "default": 20 },
                        "mode":        { "type": "string", "enum": ["recent", "album", "search", "all"], "description": "'recent' returns newest first (default). 'album' lists available albums. 'search' filters by query. 'all' returns all ordered by date.", "default": "recent" },
                        "after_date":  { "type": "string", "description": "Only photos taken on or after this date — 'yyyy-MM-dd'. E.g. '2024-01-01'." },
                        "before_date": { "type": "string", "description": "Only photos taken on or before this date — 'yyyy-MM-dd'. E.g. '2024-12-31'." }
                    },
                    "required": []
                }
            """.trimIndent())
        ),

        // ── Device Utilities Tool (mm-026) ────────────────────────────────────

        ToolDefinition(
            name = "device_utility",
            description = """
                Controls device utilities and reads device status. Actions:
                  "clipboard_read"  — read the current text on the clipboard
                  "clipboard_write" — write text to the clipboard
                  "battery_status"  — get battery percentage, charging state, temperature
                  "flashlight_on"   — turn the flashlight / torch on
                  "flashlight_off"  — turn the flashlight / torch off
                  "device_info"     — get device model, Android version, screen info
                Use for "what's on my clipboard?", "copy this text", "how's my battery?",
                "turn on flashlight", "turn off the torch", "what phone am I using?".
                No special permissions required.
            """.trimIndent(),
            inputSchema = JSONObject("""
                {
                    "type": "object",
                    "properties": {
                        "action": { "type": "string", "enum": ["clipboard_read", "clipboard_write", "battery_status", "flashlight_on", "flashlight_off", "device_info"], "description": "The utility action to perform." },
                        "text":   { "type": "string", "description": "Text to write to clipboard. Required only for 'clipboard_write' action." }
                    },
                    "required": ["action"]
                }
            """.trimIndent())
        )
    )

    // ── Tool Execution ──────────────────────────────────────────────────────

    /**
     * Executes a tool by name with the given JSON input.
     *
     * @param name   The tool name from Claude's tool_use block (e.g. "get_sms")
     * @param input  The input JSON object from Claude's tool_use block
     * @return JSON string to send back as tool_result content
     */
    // ── Permission pre-flight helper ─────────────────────────────────────────

    /**
     * Ensures the given permission is granted before a tool runs.
     * If not granted, shows the native Android permission dialog (via PermissionManager)
     * and suspends until the user responds.
     *
     * Returns null if the permission is (now) granted, or a ready-to-return
     * ToolExecutionResult explaining the denial if the user refused.
     *
     * Why here and not inside each tool?
     *   Tools receive a plain Context and can't trigger the permission dialog —
     *   that requires an ActivityResultLauncher registered in MainActivity.
     *   PermissionManager bridges the two. ToolDispatcher is the right place to
     *   call it because execute() is already suspend.
     */
    private suspend fun checkPermission(
        permission: String,
        toolName: String,
        friendlyName: String
    ): ToolExecutionResult? {
        if (PermissionManager.ensurePermission(context, permission)) return null
        return ToolExecutionResult(
            content  = """{"success":false,"summary":"$friendlyName permission was denied. Please go to Settings → Apps → Medusa → Permissions and enable it.","error":"permission_denied"}""",
            isError  = true,
            toolName = toolName
        )
    }

    suspend fun execute(name: String, input: JSONObject): ToolExecutionResult {
        return try {
            when (name) {
                "list_apps" -> {
                    val result = appLauncherTool.listApps(
                        query = input.optString("query").ifBlank { null },
                        includeSystem = input.optBoolean("include_system", false),
                        limit = input.optInt("limit", 30)
                    )
                    ToolExecutionResult(
                        content = json.encodeToString(result),
                        isError = !result.success,
                        toolName = name
                    )
                }

                "launch_app" -> {
                    val result = appLauncherTool.launchApp(
                        appName = input.optString("app_name").ifBlank { null },
                        packageName = input.optString("package_name").ifBlank { null }
                    )
                    ToolExecutionResult(
                        content = json.encodeToString(result),
                        isError = !result.success,
                        toolName = name
                    )
                }

                "open_url" -> {
                    val result = appLauncherTool.openUrl(
                        url = input.optString("url", "")
                    )
                    ToolExecutionResult(
                        content = json.encodeToString(result),
                        isError = !result.success,
                        toolName = name
                    )
                }

                "make_phone_call" -> {
                    val result = intentTool.makePhoneCall(
                        phoneNumber = input.optString("phone_number", ""),
                        direct = input.optBoolean("direct", false)
                    )
                    ToolExecutionResult(
                        content = json.encodeToString(result),
                        isError = !result.success,
                        toolName = name
                    )
                }

                "set_alarm" -> {
                    val daysArray = input.optJSONArray("days")
                    val days = if (daysArray != null) {
                        (0 until daysArray.length()).map { daysArray.getInt(it) }
                    } else null
                    val result = intentTool.setAlarm(
                        hour = input.optInt("hour", 0),
                        minutes = input.optInt("minutes", 0),
                        label = input.optString("label").ifBlank { null },
                        days = days,
                        vibrate = input.optBoolean("vibrate", true)
                    )
                    ToolExecutionResult(
                        content = json.encodeToString(result),
                        isError = !result.success,
                        toolName = name
                    )
                }

                "set_timer" -> {
                    val result = intentTool.setTimer(
                        seconds = input.optInt("seconds", 60),
                        label = input.optString("label").ifBlank { null }
                    )
                    ToolExecutionResult(
                        content = json.encodeToString(result),
                        isError = !result.success,
                        toolName = name
                    )
                }

                "open_settings" -> {
                    val result = intentTool.openSettings(
                        panel = input.optString("panel", "main").ifBlank { "main" }
                    )
                    ToolExecutionResult(
                        content = json.encodeToString(result),
                        isError = !result.success,
                        toolName = name
                    )
                }

                "share_text" -> {
                    val result = intentTool.shareText(
                        text = input.optString("text", ""),
                        title = input.optString("title").ifBlank { null }
                    )
                    ToolExecutionResult(
                        content = json.encodeToString(result),
                        isError = !result.success,
                        toolName = name
                    )
                }

                "get_sms" -> {
                    checkPermission(Manifest.permission.READ_SMS, name, "SMS")?.let { return it }
                    val result = smsTool.execute(
                        limit = input.optInt("limit", 20),
                        sender = input.optString("sender").ifBlank { null },
                        keyword = input.optString("keyword").ifBlank { null },
                        sinceHours = if (input.has("since_hours")) input.optInt("since_hours") else null,
                        type = input.optString("type", "all").ifBlank { "all" }
                    )
                    ToolExecutionResult(
                        content = json.encodeToString(result),
                        isError = !result.success,
                        toolName = name
                    )
                }

                "get_call_history" -> {
                    checkPermission(Manifest.permission.READ_CALL_LOG, name, "Call Log")?.let { return it }
                    val result = callHistoryTool.execute(
                        limit = input.optInt("limit", 20),
                        caller = input.optString("caller").ifBlank { null },
                        direction = input.optString("direction", "all").ifBlank { "all" },
                        sinceHours = if (input.has("since_hours")) input.optInt("since_hours") else null,
                        minDuration = if (input.has("min_duration")) input.optInt("min_duration") else null
                    )
                    ToolExecutionResult(
                        content = json.encodeToString(result),
                        isError = !result.success,
                        toolName = name
                    )
                }

                "get_notifications" -> {
                    val content = NotificationTool.execute(input)
                    ToolExecutionResult(
                        content = content,
                        isError = content.contains("\"error\""),
                        toolName = name
                    )
                }

                "send_sms" -> {
                    checkPermission(Manifest.permission.SEND_SMS, name, "SMS")?.let { return it }
                    val result = smsTool.sendSms(
                        phoneNumber = input.optString("phone_number", ""),
                        message = input.optString("message", "")
                    )
                    ToolExecutionResult(
                        content = json.encodeToString(result),
                        isError = !result.success,
                        toolName = name
                    )
                }

                "get_contacts" -> {
                    checkPermission(Manifest.permission.READ_CONTACTS, name, "Contacts")?.let { return it }
                    val result = contactsTool.execute(
                        query = input.optString("query").ifBlank { null },
                        limit = input.optInt("limit", 20),
                        mode = input.optString("mode", "search").ifBlank { "search" }
                    )
                    ToolExecutionResult(
                        content = json.encodeToString(result),
                        isError = !result.success,
                        toolName = name
                    )
                }

                "web_search" -> {
                    val result = webResearchTool.search(
                        query = input.optString("query", ""),
                        numResults = input.optInt("num_results", 5)
                    )
                    ToolExecutionResult(
                        content = json.encodeToString(result),
                        isError = !result.success,
                        toolName = name
                    )
                }

                "web_fetch" -> {
                    val result = webResearchTool.fetch(
                        url = input.optString("url", ""),
                        maxLength = input.optInt("max_length", 8000)
                    )
                    ToolExecutionResult(
                        content = json.encodeToString(result),
                        isError = !result.success,
                        toolName = name
                    )
                }

                "sheets_find" -> {
                    val result = googleSheetsTool.findSpreadsheets(
                        query = input.optString("query", ""),
                        limit = input.optInt("limit", 10)
                    )
                    ToolExecutionResult(
                        content = json.encodeToString(result),
                        isError = !result.success,
                        toolName = name
                    )
                }

                "sheets_read" -> {
                    val result = googleSheetsTool.readSheet(
                        spreadsheetId = input.optString("spreadsheet_id", ""),
                        range = input.optString("range", "Sheet1").ifBlank { "Sheet1" },
                        includeHeaders = input.optBoolean("include_headers", true)
                    )
                    ToolExecutionResult(
                        content = json.encodeToString(result),
                        isError = !result.success,
                        toolName = name
                    )
                }

                "sheets_write" -> {
                    val result = googleSheetsTool.writeSheet(
                        spreadsheetId = input.optString("spreadsheet_id", ""),
                        range = input.optString("range", ""),
                        valuesJson = input.optString("values", "[]")
                    )
                    ToolExecutionResult(
                        content = json.encodeToString(result),
                        isError = !result.success,
                        toolName = name
                    )
                }

                "sheets_append" -> {
                    val result = googleSheetsTool.appendToSheet(
                        spreadsheetId = input.optString("spreadsheet_id", ""),
                        range = input.optString("range", "Sheet1").ifBlank { "Sheet1" },
                        valuesJson = input.optString("values", "[]")
                    )
                    ToolExecutionResult(
                        content = json.encodeToString(result),
                        isError = !result.success,
                        toolName = name
                    )
                }

                "sheets_create" -> {
                    val headersRaw = input.optString("headers", "").trim()
                    val headers = if (headersRaw.isNotEmpty())
                        headersRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    else emptyList()
                    val result = googleSheetsTool.createSheet(
                        title = input.optString("title", "Untitled Spreadsheet").ifBlank { "Untitled Spreadsheet" },
                        headers = headers
                    )
                    ToolExecutionResult(
                        content = json.encodeToString(result),
                        isError = !result.success,
                        toolName = name
                    )
                }

                "gmail_search" -> {
                    val result = emailTool.gmailSearch(
                        query = input.optString("query", ""),
                        limit = input.optInt("limit", 20),
                        unreadOnly = input.optBoolean("unread_only", false)
                    )
                    ToolExecutionResult(
                        content = json.encodeToString(result),
                        isError = !result.success,
                        toolName = name
                    )
                }

                "gmail_read" -> {
                    val result = emailTool.gmailRead(
                        messageId = input.optString("message_id", "")
                    )
                    ToolExecutionResult(
                        content = json.encodeToString(result),
                        isError = !result.success,
                        toolName = name
                    )
                }

                "gmail_send" -> {
                    val result = emailTool.gmailSend(
                        to = input.optString("to", ""),
                        subject = input.optString("subject", ""),
                        body = input.optString("body", ""),
                        cc = input.optString("cc").ifBlank { null },
                        replyToMessageId = input.optString("reply_to_message_id").ifBlank { null }
                    )
                    ToolExecutionResult(
                        content = json.encodeToString(result),
                        isError = !result.success,
                        toolName = name
                    )
                }

                "email_imap_setup" -> {
                    val result = emailTool.imapSetup(
                        host = input.optString("host", ""),
                        email = input.optString("email", ""),
                        password = input.optString("password", ""),
                        port = input.optInt("port", 993)
                    )
                    ToolExecutionResult(
                        content = json.encodeToString(result),
                        isError = !result.success,
                        toolName = name
                    )
                }

                "email_imap_search" -> {
                    val result = emailTool.imapSearch(
                        query = input.optString("query", ""),
                        folder = input.optString("folder", "INBOX").ifBlank { "INBOX" },
                        limit = input.optInt("limit", 20)
                    )
                    ToolExecutionResult(
                        content = json.encodeToString(result),
                        isError = !result.success,
                        toolName = name
                    )
                }

                "google_docs_create" -> {
                    val result = googleDocsDriveTool.createDoc(
                        title = input.optString("title", "Untitled"),
                        content = input.optString("content", "")
                    )
                    ToolExecutionResult(
                        content = json.encodeToString(result),
                        isError = !result.success,
                        toolName = name
                    )
                }

                "google_docs_edit" -> {
                    val result = googleDocsDriveTool.editDoc(
                        documentId = input.optString("document_id", ""),
                        content = input.optString("content", ""),
                        mode = input.optString("mode", "append").ifBlank { "append" }
                    )
                    ToolExecutionResult(
                        content = json.encodeToString(result),
                        isError = !result.success,
                        toolName = name
                    )
                }

                "google_docs_share" -> {
                    val result = googleDocsDriveTool.shareDoc(
                        fileId = input.optString("file_id", ""),
                        email = input.optString("email", ""),
                        role = input.optString("role", "writer").ifBlank { "writer" }
                    )
                    ToolExecutionResult(
                        content = json.encodeToString(result),
                        isError = !result.success,
                        toolName = name
                    )
                }

                "google_drive_search" -> {
                    val result = googleDocsDriveTool.searchDrive(
                        query = input.optString("query", ""),
                        fileType = input.optString("file_type").ifBlank { null },
                        limit = input.optInt("limit", 10)
                    )
                    ToolExecutionResult(
                        content = json.encodeToString(result),
                        isError = !result.success,
                        toolName = name
                    )
                }

                "google_drive_list" -> {
                    val result = googleDocsDriveTool.listRecentFiles(
                        limit = input.optInt("limit", 10)
                    )
                    ToolExecutionResult(
                        content = json.encodeToString(result),
                        isError = !result.success,
                        toolName = name
                    )
                }

                "memory_remember" -> {
                    val result = memoryStoreTool.remember(
                        key = input.optString("key", ""),
                        value = input.optString("value", ""),
                        category = input.optString("category", "general").ifBlank { "general" },
                        tags = input.optString("tags", "").ifBlank { "" }
                    )
                    ToolExecutionResult(
                        content = json.encodeToString(result),
                        isError = !result.success,
                        toolName = name
                    )
                }

                "memory_recall" -> {
                    val result = memoryStoreTool.recall(
                        key = input.optString("key").ifBlank { null },
                        category = input.optString("category").ifBlank { null },
                        query = input.optString("query").ifBlank { null },
                        limit = input.optInt("limit", 20)
                    )
                    ToolExecutionResult(
                        content = json.encodeToString(result),
                        isError = !result.success,
                        toolName = name
                    )
                }

                "memory_forget" -> {
                    val result = memoryStoreTool.forget(
                        key = input.optString("key").ifBlank { null },
                        category = input.optString("category").ifBlank { null },
                        all = input.optBoolean("all", false)
                    )
                    ToolExecutionResult(
                        content = json.encodeToString(result),
                        isError = !result.success,
                        toolName = name
                    )
                }

                "get_location" -> {
                    val result = mapsTool.getLocation(
                        includeAddress = input.optBoolean("include_address", true)
                    )
                    ToolExecutionResult(
                        content = json.encodeToString(result),
                        isError = !result.success,
                        toolName = name
                    )
                }

                "open_maps" -> {
                    val result = mapsTool.openMaps(
                        destination = input.optString("destination").ifBlank { null },
                        origin = input.optString("origin").ifBlank { null },
                        mode = input.optString("mode", "driving").ifBlank { "driving" },
                        action = input.optString("action", "directions").ifBlank { "directions" },
                        query = input.optString("query").ifBlank { null }
                    )
                    ToolExecutionResult(
                        content = json.encodeToString(result),
                        isError = !result.success,
                        toolName = name
                    )
                }

                // ── Calendar ────────────────────────────────────────────────

                "calendar_list_events" -> {
                    checkPermission(Manifest.permission.READ_CALENDAR, name, "Calendar")?.let { return it }
                    val result = calendarTool.listEvents(
                        daysAhead  = input.optInt("days_ahead", 7),
                        daysBehind = input.optInt("days_behind", 0),
                        limit      = input.optInt("limit", 20)
                    )
                    ToolExecutionResult(
                        content  = json.encodeToString(result),
                        isError  = !result.success,
                        toolName = name
                    )
                }

                "calendar_search_events" -> {
                    checkPermission(Manifest.permission.READ_CALENDAR, name, "Calendar")?.let { return it }
                    val result = calendarTool.searchEvents(
                        query     = input.optString("query").ifBlank { null },
                        startDate = input.optString("start_date").ifBlank { null },
                        endDate   = input.optString("end_date").ifBlank { null },
                        limit     = input.optInt("limit", 20)
                    )
                    ToolExecutionResult(
                        content  = json.encodeToString(result),
                        isError  = !result.success,
                        toolName = name
                    )
                }

                "calendar_create_event" -> {
                    checkPermission(Manifest.permission.WRITE_CALENDAR, name, "Calendar")?.let { return it }
                    val result = calendarTool.createEvent(
                        title       = input.optString("title", ""),
                        startIso    = input.optString("start_time", ""),
                        endIso      = input.optString("end_time", ""),
                        description = input.optString("description").ifBlank { null },
                        location    = input.optString("location").ifBlank { null },
                        allDay      = input.optBoolean("all_day", false)
                    )
                    ToolExecutionResult(
                        content  = json.encodeToString(result),
                        isError  = !result.success,
                        toolName = name
                    )
                }

                "calendar_delete_event" -> {
                    checkPermission(Manifest.permission.WRITE_CALENDAR, name, "Calendar")?.let { return it }
                    val result = calendarTool.deleteEvent(
                        eventId = input.optLong("event_id", -1L)
                    )
                    ToolExecutionResult(
                        content  = json.encodeToString(result),
                        isError  = !result.success,
                        toolName = name
                    )
                }

                // ── File Manager ─────────────────────────────────────────────

                "file_list" -> {
                    val result = fileManagerTool.listFiles(
                        directory = input.optString("directory", "downloads").ifBlank { "downloads" },
                        extension = input.optString("extension").ifBlank { null },
                        limit     = input.optInt("limit", 30)
                    )
                    ToolExecutionResult(
                        content  = json.encodeToString(result),
                        isError  = !result.success,
                        toolName = name
                    )
                }

                "file_read" -> {
                    val result = fileManagerTool.readFile(
                        path     = input.optString("path", ""),
                        maxChars = input.optInt("max_chars", 8_000)
                    )
                    ToolExecutionResult(
                        content  = json.encodeToString(result),
                        isError  = !result.success,
                        toolName = name
                    )
                }

                "file_write" -> {
                    val result = fileManagerTool.writeFile(
                        filename  = input.optString("filename", ""),
                        content   = input.optString("content", ""),
                        directory = input.optString("directory", "documents").ifBlank { "documents" },
                        overwrite = input.optBoolean("overwrite", true)
                    )
                    ToolExecutionResult(
                        content  = json.encodeToString(result),
                        isError  = !result.success,
                        toolName = name
                    )
                }

                "file_search" -> {
                    val result = fileManagerTool.searchFiles(
                        query     = input.optString("query").ifBlank { null },
                        extension = input.optString("extension").ifBlank { null },
                        directory = input.optString("directory").ifBlank { null },
                        limit     = input.optInt("limit", 20)
                    )
                    ToolExecutionResult(
                        content  = json.encodeToString(result),
                        isError  = !result.success,
                        toolName = name
                    )
                }

                "file_delete" -> {
                    val result = fileManagerTool.deleteFile(
                        path = input.optString("path", "")
                    )
                    ToolExecutionResult(
                        content  = json.encodeToString(result),
                        isError  = !result.success,
                        toolName = name
                    )
                }

                // ── Photos ───────────────────────────────────────────────────

                "get_photos" -> {
                    val photoPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        Manifest.permission.READ_MEDIA_IMAGES
                    else
                        Manifest.permission.READ_EXTERNAL_STORAGE
                    checkPermission(photoPermission, name, "Photos")?.let { return it }
                    val result = photosTool.execute(
                        query      = input.optString("query").ifBlank { null },
                        limit      = input.optInt("limit", 20),
                        mode       = input.optString("mode", "recent").ifBlank { "recent" },
                        afterDate  = input.optString("after_date").ifBlank { null },
                        beforeDate = input.optString("before_date").ifBlank { null }
                    )
                    ToolExecutionResult(
                        content  = json.encodeToString(result),
                        isError  = !result.success,
                        toolName = name
                    )
                }

                // ── Device Utilities ─────────────────────────────────────────

                "device_utility" -> {
                    val result = deviceUtilitiesTool.execute(
                        action = input.optString("action", ""),
                        text   = input.optString("text").ifBlank { null }
                    )
                    ToolExecutionResult(
                        content  = json.encodeToString(result),
                        isError  = !result.success,
                        toolName = name
                    )
                }

                else -> {
                    ToolExecutionResult(
                        content = """{"error": "Unknown tool: $name. Available tools: get_sms, send_sms, get_call_history, get_notifications, get_contacts, memory_remember, memory_recall, memory_forget, google_docs_create, google_docs_edit, google_docs_share, google_drive_search, google_drive_list, get_location, open_maps, list_apps, launch_app, open_url, gmail_search, gmail_read, gmail_send, email_imap_setup, email_imap_search, make_phone_call, set_alarm, set_timer, open_settings, share_text, web_search, web_fetch, sheets_find, sheets_read, sheets_write, sheets_append, sheets_create, calendar_list_events, calendar_search_events, calendar_create_event, calendar_delete_event, file_list, file_read, file_write, file_search, file_delete, get_photos, device_utility"}""",
                        isError = true,
                        toolName = name
                    )
                }
            }
        } catch (e: Exception) {
            // Never let a tool crash kill the agentic loop
            val errMsg = e.message?.replace("\"", "'") ?: "unknown error"
            ToolExecutionResult(
                content = """{"error": "Tool execution failed: $errMsg"}""",
                isError = true,
                toolName = name
            )
        }
    }
}

/**
 * Result of executing a tool — wraps the JSON content and metadata.
 * Used by AgentOrchestrator to build the tool_result message block.
 */
data class ToolExecutionResult(
    val content: String,       // JSON string for tool_result content
    val isError: Boolean,      // If true, sent as is_error: true in the API
    val toolName: String       // For logging/UI display
)
