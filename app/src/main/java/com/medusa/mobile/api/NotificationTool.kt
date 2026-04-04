package com.medusa.mobile.api

// mm-008 — Notification Tool (Claude function calling interface)
//
// Exposes NotificationStore to Claude via the get_notifications tool.
// Claude can: list recent, filter by app, search by keyword, get unread count.
//
// This is what makes Android magical vs iOS — we see EVERY notification
// from EVERY app: WhatsApp, Gmail, Slack, Instagram DMs, bank alerts, everything.

import com.medusa.mobile.services.NotificationStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * Claude tool definitions and execution for notification reading.
 */
object NotificationTool {

    // ── Tool Definition (sent to Claude API) ────────────────────────────

    val toolDefinition = ToolDefinition(
        name = "get_notifications",
        description = """
            Reads the user's device notifications from ALL apps — messages (WhatsApp, Telegram,
            SMS), emails (Gmail, Outlook), social (Instagram, Twitter, TikTok), calls, banking
            alerts, delivery updates, and more. This sees everything that appears in the
            notification shade.

            Modes:
            - "recent" — last N notifications across all apps
            - "unread" — only new notifications since last check (marks as read)
            - "app" — notifications from a specific app (e.g. "WhatsApp", "Gmail")
            - "search" — find notifications matching a keyword
            - "active" — currently visible (not dismissed) notifications
            - "apps" — list all apps that have sent notifications

            Use this to answer questions like "do I have any new messages?", "what did
            my boss send me?", "any delivery updates?", "read my WhatsApp messages".
        """.trimIndent(),
        inputSchema = JSONObject("""
            {
                "type": "object",
                "properties": {
                    "mode": {
                        "type": "string",
                        "enum": ["recent", "unread", "app", "search", "active", "apps"],
                        "description": "Query mode. Default: 'unread' for new notifications."
                    },
                    "app_name": {
                        "type": "string",
                        "description": "App name to filter by (for mode='app'). e.g. 'WhatsApp', 'Gmail'. Case-insensitive partial match."
                    },
                    "query": {
                        "type": "string",
                        "description": "Search keyword (for mode='search'). Searches title, text, and app name."
                    },
                    "limit": {
                        "type": "integer",
                        "description": "Max notifications to return. Default: 20."
                    }
                },
                "required": ["mode"]
            }
        """.trimIndent())
    )

    // ── Tool Execution ──────────────────────────────────────────────────

    /**
     * Executes the get_notifications tool with the given input.
     * Returns a JSON string for the tool_result content block.
     */
    fun execute(input: JSONObject): String {
        val mode = input.optString("mode", "unread")
        val appName = input.optString("app_name", "")
        val query = input.optString("query", "")
        val limit = input.optInt("limit", 20)

        return when (mode) {
            "recent"  -> formatNotifications(NotificationStore.recent(limit), "recent")
            "unread"  -> formatNotifications(NotificationStore.drainUnread().take(limit), "unread")
            "app"     -> executeAppFilter(appName, limit)
            "search"  -> formatNotifications(NotificationStore.search(query, limit), "search results for '$query'")
            "active"  -> formatNotifications(NotificationStore.active(limit), "active")
            "apps"    -> formatKnownApps()
            else      -> JSONObject().apply {
                put("error", "Unknown mode: $mode")
            }.toString()
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun executeAppFilter(appName: String, limit: Int): String {
        if (appName.isBlank()) {
            return JSONObject().apply {
                put("error", "app_name is required for mode='app'")
            }.toString()
        }

        // Fuzzy match — find packages whose app name contains the query
        val allNotifs = NotificationStore.recent(500)
        val matched = allNotifs.filter {
            it.appName.lowercase().contains(appName.lowercase())
        }.take(limit)

        return formatNotifications(matched, "from '$appName'")
    }

    private fun formatNotifications(
        notifications: List<com.medusa.mobile.services.CapturedNotification>,
        label: String
    ): String {
        val result = JSONObject()
        result.put("type", label)
        result.put("count", notifications.size)
        result.put("unread_total", NotificationStore.unreadCount)

        if (notifications.isEmpty()) {
            result.put("notifications", JSONArray())
            result.put("summary", "No notifications $label.")
            return result.toString()
        }

        val array = JSONArray()
        for (n in notifications) {
            array.put(JSONObject().apply {
                put("app", n.appName)
                put("title", n.title)
                put("text", if (n.bigText != null && n.bigText.length > n.text.length) n.bigText else n.text)
                if (n.subText != null) put("sub_text", n.subText)
                put("time", n.formattedTime)
                put("category", n.category ?: "unknown")
                put("active", n.isActive)
                put("emoji", n.emoji)
            })
        }
        result.put("notifications", array)

        return result.toString()
    }

    private fun formatKnownApps(): String {
        val apps = NotificationStore.knownApps()
        return JSONObject().apply {
            put("type", "known_apps")
            put("count", apps.size)
            put("apps", JSONArray(apps))
        }.toString()
    }
}
