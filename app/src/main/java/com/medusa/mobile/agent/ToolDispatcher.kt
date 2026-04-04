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

import android.content.Context
import com.medusa.mobile.agent.tools.AppLauncherTool
import com.medusa.mobile.agent.tools.CallHistoryTool
import com.medusa.mobile.agent.tools.ContactsTool
import com.medusa.mobile.agent.tools.GoogleDocsDriveTool
import com.medusa.mobile.agent.tools.MapsTool
import com.medusa.mobile.agent.tools.MemoryStoreTool
import com.medusa.mobile.agent.tools.SmsTool
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
    private val smsTool = SmsTool(context)
    private val callHistoryTool = CallHistoryTool(context)
    private val contactsTool = ContactsTool(context)
    private val mapsTool = MapsTool(context)        // mm-020
    private val memoryStoreTool = MemoryStoreTool(context)  // mm-017
    private val googleAuthManager = GoogleAuthManager(context)
    private val googleDocsDriveTool = GoogleDocsDriveTool(context, googleAuthManager) // mm-019
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

                "get_sms" -> {
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

                else -> {
                    ToolExecutionResult(
                        content = """{"error": "Unknown tool: $name. Available tools: get_sms, send_sms, get_call_history, get_notifications, get_contacts, memory_remember, memory_recall, memory_forget, google_docs_create, google_docs_edit, google_docs_share, google_drive_search, google_drive_list, get_location, open_maps, list_apps, launch_app, open_url"}""",
                        isError = true,
                        toolName = name
                    )
                }
            }
        } catch (e: Exception) {
            // Never let a tool crash kill the agentic loop
            ToolExecutionResult(
                content = """{"error": "Tool execution failed: ${e.message?.replace("\"", "'")}"}""",
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
