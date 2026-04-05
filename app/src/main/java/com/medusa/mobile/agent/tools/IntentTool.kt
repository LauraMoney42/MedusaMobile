package com.medusa.mobile.agent.tools

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.medusa.mobile.models.AlarmResultDTO
import com.medusa.mobile.models.IntentResultDTO
import com.medusa.mobile.models.ToolResult
import java.util.Calendar

/**
 * mm-025 — Intent Tools for Medusa Mobile.
 *
 * Five Claude tools for common Android system intents:
 *   1. `make_phone_call`  — dial a phone number (opens dialer or places call directly)
 *   2. `set_alarm`        — create an alarm via AlarmClock intent
 *   3. `set_timer`        — start a countdown timer
 *   4. `open_settings`    — open Android system settings (Wi-Fi, Bluetooth, etc.)
 *   5. `share_text`       — share text via Android share sheet
 *
 * All use standard Android intents — no special permissions beyond CALL_PHONE
 * for direct calls (dialer intent needs none). Zero dependencies.
 *
 * Claude tool names: "make_phone_call", "set_alarm", "set_timer",
 *                    "open_settings", "share_text"
 */
class IntentTool(private val context: Context) {

    // ── Claude Tool Definitions ──────────────────────────────────────────────

    companion object {
        val claudeToolDefinitions: List<Map<String, Any>> = listOf(

            // 1. make_phone_call
            mapOf(
                "name" to "make_phone_call",
                "description" to """
                    Dials a phone number. Opens the dialer with the number pre-filled so
                    the user can confirm before calling (default), or places the call
                    directly if direct=true and CALL_PHONE permission is granted.
                    Use when the user says "call Mom", "dial 555-1234", "phone the doctor".
                    Pair with get_contacts to resolve a name to a number first.
                """.trimIndent(),
                "input_schema" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "phone_number" to mapOf(
                            "type" to "string",
                            "description" to "Phone number to dial. E.g. '+15551234567', '555-123-4567'."
                        ),
                        "direct" to mapOf(
                            "type" to "boolean",
                            "description" to "If true, call directly without showing dialer. Requires CALL_PHONE permission. Default false (show dialer).",
                            "default" to false
                        )
                    ),
                    "required" to listOf("phone_number")
                )
            ),

            // 2. set_alarm
            mapOf(
                "name" to "set_alarm",
                "description" to """
                    Creates an alarm using the system clock app. Use when the user says
                    "set an alarm for 7am", "wake me up at 6:30", "alarm tomorrow at 8".
                    The system clock app opens to confirm. Supports repeating alarms.
                    For countdown timers, use set_timer instead.
                """.trimIndent(),
                "input_schema" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "hour" to mapOf(
                            "type" to "integer",
                            "description" to "Hour in 24-hour format (0–23). E.g. 7 for 7am, 14 for 2pm."
                        ),
                        "minutes" to mapOf(
                            "type" to "integer",
                            "description" to "Minutes (0–59). Default 0.",
                            "default" to 0
                        ),
                        "label" to mapOf(
                            "type" to "string",
                            "description" to "Optional alarm label. E.g. 'Doctor appointment', 'Take medication'."
                        ),
                        "days" to mapOf(
                            "type" to "array",
                            "items" to mapOf("type" to "integer"),
                            "description" to "Optional repeat days. Use Calendar constants: 1=Sun, 2=Mon, 3=Tue, 4=Wed, 5=Thu, 6=Fri, 7=Sat."
                        ),
                        "vibrate" to mapOf(
                            "type" to "boolean",
                            "description" to "Whether the alarm should vibrate. Default true.",
                            "default" to true
                        )
                    ),
                    "required" to listOf("hour")
                )
            ),

            // 3. set_timer
            mapOf(
                "name" to "set_timer",
                "description" to """
                    Starts a countdown timer in the system clock app. Use when the user says
                    "set a timer for 10 minutes", "remind me in 30 seconds", "timer 1 hour".
                    Opens the clock app with timer pre-set and auto-started.
                """.trimIndent(),
                "input_schema" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "seconds" to mapOf(
                            "type" to "integer",
                            "description" to "Timer duration in seconds. E.g. 600 for 10 minutes, 3600 for 1 hour."
                        ),
                        "label" to mapOf(
                            "type" to "string",
                            "description" to "Optional timer label. E.g. 'Pasta', 'Medication', 'Meeting'."
                        )
                    ),
                    "required" to listOf("seconds")
                )
            ),

            // 4. open_settings
            mapOf(
                "name" to "open_settings",
                "description" to """
                    Opens a specific Android settings screen. Use when the user says
                    "open WiFi settings", "go to Bluetooth settings", "open app permissions",
                    "show battery settings", "open accessibility settings".
                    Supported panels: wifi, bluetooth, location, battery, accessibility,
                    notifications, apps, display, sound, storage, security, date_time, main.
                """.trimIndent(),
                "input_schema" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "panel" to mapOf(
                            "type" to "string",
                            "enum" to listOf(
                                "main", "wifi", "bluetooth", "location", "battery",
                                "accessibility", "notifications", "apps", "display",
                                "sound", "storage", "security", "date_time"
                            ),
                            "description" to "Which settings panel to open. Default 'main'.",
                            "default" to "main"
                        )
                    ),
                    "required" to emptyList<String>()
                )
            ),

            // 5. share_text
            mapOf(
                "name" to "share_text",
                "description" to """
                    Opens the Android share sheet to share text via any app (WhatsApp,
                    Messages, Gmail, Notes, etc.). Use when the user says "share this",
                    "send this to WhatsApp", "share the address", "copy to clipboard and share".
                    The user picks the destination app from the system share sheet.
                """.trimIndent(),
                "input_schema" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "text" to mapOf(
                            "type" to "string",
                            "description" to "Text content to share."
                        ),
                        "title" to mapOf(
                            "type" to "string",
                            "description" to "Optional title/subject for the shared content (used in email subject, etc.)."
                        )
                    ),
                    "required" to listOf("text")
                )
            )
        )
    }

    // ── make_phone_call ───────────────────────────────────────────────────────

    fun makePhoneCall(phoneNumber: String, direct: Boolean = false): ToolResult {
        val number = phoneNumber.trim()
        if (number.isEmpty()) {
            return ToolResult.failure("Phone number is required.")
        }

        val uri = Uri.parse("tel:${Uri.encode(number)}")

        return try {
            if (direct) {
                // ACTION_CALL — places call immediately, requires CALL_PHONE permission
                val intent = Intent(Intent.ACTION_CALL, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (intent.resolveActivity(context.packageManager) == null) {
                    return ToolResult.failure("No phone app found on this device.")
                }
                context.startActivity(intent)
                ToolResult.success(
                    summary = "Calling $number…",
                    data = IntentResultDTO(action = "call", target = number, launched = true)
                )
            } else {
                // ACTION_DIAL — opens dialer, no permission needed
                val intent = Intent(Intent.ACTION_DIAL, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (intent.resolveActivity(context.packageManager) == null) {
                    return ToolResult.failure("No phone dialer found on this device.")
                }
                context.startActivity(intent)
                ToolResult.success(
                    summary = "Opened dialer for $number.",
                    data = IntentResultDTO(action = "dial", target = number, launched = true)
                )
            }
        } catch (e: SecurityException) {
            ToolResult.denied("CALL_PHONE permission not granted. Use direct=false to open the dialer instead.")
        } catch (e: Exception) {
            ToolResult.failure("Failed to open dialer: ${e.localizedMessage}")
        }
    }

    // ── set_alarm ────────────────────────────────────────────────────────────

    fun setAlarm(
        hour: Int,
        minutes: Int = 0,
        label: String? = null,
        days: List<Int>? = null,
        vibrate: Boolean = true
    ): ToolResult {
        if (hour !in 0..23) return ToolResult.failure("Hour must be 0–23.")
        if (minutes !in 0..59) return ToolResult.failure("Minutes must be 0–59.")

        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minutes)
                putExtra(AlarmClock.EXTRA_VIBRATE, vibrate)
                putExtra(AlarmClock.EXTRA_SKIP_UI, false) // show clock app to confirm
                if (!label.isNullOrBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
                if (!days.isNullOrEmpty()) putIntegerArrayListExtra(
                    AlarmClock.EXTRA_DAYS,
                    ArrayList(days)
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            if (intent.resolveActivity(context.packageManager) == null) {
                return ToolResult.failure("No clock app found to handle alarm.")
            }

            context.startActivity(intent)

            val timeStr = "%02d:%02d".format(hour, minutes)
            val labelStr = if (!label.isNullOrBlank()) " ($label)" else ""
            val daysStr = if (!days.isNullOrEmpty()) {
                val dayNames = mapOf(1 to "Sun", 2 to "Mon", 3 to "Tue",
                    4 to "Wed", 5 to "Thu", 6 to "Fri", 7 to "Sat")
                " repeating ${days.mapNotNull { dayNames[it] }.joinToString(", ")}"
            } else ""

            ToolResult.success(
                summary = "Alarm set for $timeStr$labelStr$daysStr.",
                data = AlarmResultDTO(
                    hour = hour,
                    minutes = minutes,
                    label = label,
                    repeating = !days.isNullOrEmpty()
                )
            )
        } catch (e: Exception) {
            ToolResult.failure("Failed to set alarm: ${e.localizedMessage}")
        }
    }

    // ── set_timer ────────────────────────────────────────────────────────────

    fun setTimer(seconds: Int, label: String? = null): ToolResult {
        if (seconds <= 0) return ToolResult.failure("Timer duration must be greater than 0 seconds.")

        return try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                // EXTRA_TIMER_AUTO_START not in public API — omitted; clock app auto-starts by default
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                if (!label.isNullOrBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            if (intent.resolveActivity(context.packageManager) == null) {
                return ToolResult.failure("No clock app found to handle timer.")
            }

            context.startActivity(intent)

            // Human-readable duration
            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            val s = seconds % 60
            val durationStr = buildString {
                if (h > 0) append("${h}h ")
                if (m > 0) append("${m}m ")
                if (s > 0 || (h == 0 && m == 0)) append("${s}s")
            }.trim()

            val labelStr = if (!label.isNullOrBlank()) " ($label)" else ""
            ToolResult.success(
                summary = "Timer started: $durationStr$labelStr.",
                data = IntentResultDTO(action = "timer", target = "${seconds}s", launched = true)
            )
        } catch (e: Exception) {
            ToolResult.failure("Failed to set timer: ${e.localizedMessage}")
        }
    }

    // ── open_settings ────────────────────────────────────────────────────────

    fun openSettings(panel: String = "main"): ToolResult {
        val action = when (panel.lowercase()) {
            "wifi"            -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth"       -> Settings.ACTION_BLUETOOTH_SETTINGS
            "location"        -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "battery"         -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "accessibility"   -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "notifications"   -> "android.settings.ACTION_NOTIFICATION_SETTINGS"
            "apps"            -> Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS
            "display"         -> Settings.ACTION_DISPLAY_SETTINGS
            "sound"           -> Settings.ACTION_SOUND_SETTINGS
            "storage"         -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
            "security"        -> Settings.ACTION_SECURITY_SETTINGS
            "date_time"       -> Settings.ACTION_DATE_SETTINGS
            else              -> Settings.ACTION_SETTINGS  // main
        }

        return try {
            val intent = Intent(action).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult.success(
                summary = "Opened ${panel.replace("_", " ")} settings.",
                data = IntentResultDTO(action = "settings", target = panel, launched = true)
            )
        } catch (e: Exception) {
            // Fall back to main settings if specific panel fails
            try {
                context.startActivity(
                    Intent(Settings.ACTION_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
                ToolResult.success("Opened main settings (${panel} panel not available on this device).")
            } catch (e2: Exception) {
                ToolResult.failure("Failed to open settings: ${e2.localizedMessage}")
            }
        }
    }

    // ── share_text ───────────────────────────────────────────────────────────

    fun shareText(text: String, title: String? = null): ToolResult {
        if (text.isBlank()) return ToolResult.failure("Text to share is required.")

        return try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                if (!title.isNullOrBlank()) putExtra(Intent.EXTRA_SUBJECT, title)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // Wrap in chooser so user picks the app
            val chooser = Intent.createChooser(intent, title ?: "Share via…").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(chooser)

            val preview = text.take(60) + if (text.length > 60) "…" else ""
            ToolResult.success(
                summary = "Share sheet opened: \"$preview\"",
                data = IntentResultDTO(action = "share", target = preview, launched = true)
            )
        } catch (e: Exception) {
            ToolResult.failure("Failed to open share sheet: ${e.localizedMessage}")
        }
    }
}
