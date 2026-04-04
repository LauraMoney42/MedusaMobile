package com.medusa.mobile.services

// mm-008 — Notification Reader Service
//
// Android's NotificationListenerService gives us what iOS can NEVER provide:
//   - Read ALL notifications from ALL apps in real time
//   - Extract sender, text, app name, timestamp, actions
//   - Dismiss or interact with notifications programmatically
//
// Requires: User grants "Notification Access" in Settings → Apps → Special Access.
// No root, no ADB, no special entitlement. Just one toggle.
//
// Architecture:
//   NotificationReaderService (system callback) → NotificationStore (in-memory ring buffer)
//   Claude tools query NotificationStore via get_notifications tool (NotificationTool.kt).

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

// ── Data Models ─────────────────────────────────────────────────────────────

/**
 * A captured notification, extracted from [StatusBarNotification].
 * Stored in [NotificationStore] for Claude to query.
 */
data class CapturedNotification(
    val key: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val subText: String?,
    val bigText: String?,
    val postedAt: Long,
    val category: String?,
    var isActive: Boolean = true,
    var isRead: Boolean = false
) {
    val formattedTime: String
        get() = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(postedAt))

    val emoji: String
        get() = when (category) {
            Notification.CATEGORY_MESSAGE   -> "\uD83D\uDCAC"
            Notification.CATEGORY_EMAIL     -> "\uD83D\uDCE7"
            Notification.CATEGORY_CALL      -> "\uD83D\uDCDE"
            Notification.CATEGORY_SOCIAL    -> "\uD83D\uDC65"
            Notification.CATEGORY_TRANSPORT -> "\uD83D\uDE97"
            Notification.CATEGORY_REMINDER  -> "\u23F0"
            Notification.CATEGORY_ALARM     -> "\u23F0"
            else                            -> "\uD83D\uDD14"
        }
}

// ── Notification Store (thread-safe in-memory ring buffer) ──────────────────

/**
 * Thread-safe store for captured notifications. Capped at [MAX_NOTIFICATIONS].
 * Claude tools read from here via [recent], [drainUnread], [forApp], [search].
 */
object NotificationStore {

    private const val MAX_NOTIFICATIONS = 500

    private val notifications = ConcurrentLinkedDeque<CapturedNotification>()
    private val unreadQueue = ConcurrentLinkedDeque<CapturedNotification>()

    // ── Write ───────────────────────────────────────────────────────────

    fun add(notification: CapturedNotification) {
        // Dedup by key — replace existing
        notifications.removeIf { it.key == notification.key }
        notifications.addFirst(notification)
        unreadQueue.addFirst(notification)

        // Cap size
        while (notifications.size > MAX_NOTIFICATIONS) {
            notifications.removeLast()
        }
    }

    fun markDismissed(key: String) {
        notifications.find { it.key == key }?.isActive = false
    }

    fun clear() {
        notifications.clear()
        unreadQueue.clear()
    }

    // ── Read (for Claude tools) ─────────────────────────────────────────

    fun recent(limit: Int = 50): List<CapturedNotification> =
        notifications.take(limit)

    /** Drain unread queue — returns and marks all as read. */
    fun drainUnread(): List<CapturedNotification> {
        val result = unreadQueue.toList()
        for (n in result) n.isRead = true
        unreadQueue.clear()
        return result
    }

    fun forApp(packageName: String, limit: Int = 50): List<CapturedNotification> =
        notifications.filter { it.packageName == packageName }.take(limit)

    fun search(query: String, limit: Int = 50): List<CapturedNotification> {
        val q = query.lowercase()
        return notifications.filter {
            it.title.lowercase().contains(q) ||
            it.text.lowercase().contains(q) ||
            it.bigText?.lowercase()?.contains(q) == true ||
            it.appName.lowercase().contains(q)
        }.take(limit)
    }

    fun active(limit: Int = 50): List<CapturedNotification> =
        notifications.filter { it.isActive }.take(limit)

    val unreadCount: Int get() = unreadQueue.size

    val hasAny: Boolean get() = notifications.isNotEmpty()

    fun knownApps(): List<String> =
        notifications.map { it.appName }.distinct().sorted()
}

// ── NotificationListenerService Implementation ─────────────────────────────

/**
 * System service that captures ALL device notifications in real time.
 *
 * Registered in AndroidManifest.xml with BIND_NOTIFICATION_LISTENER_SERVICE
 * permission. User must grant access in system settings.
 */
class NotificationReaderService : NotificationListenerService() {

    companion object {
        private const val TAG = "MedusaNotifReader"

        // Apps to ignore (system noise + our own notifications)
        private val IGNORED_PACKAGES = setOf(
            "com.medusa.mobile",
            "com.medusa.mobile.debug",
            "android",
            "com.android.systemui",
            "com.android.providers.downloads",
        )

        // ── Permission Helpers (call from Activity/ViewModel) ───────────

        /** Check if notification listener permission is granted. */
        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            )
            return flat?.contains(context.packageName) == true
        }

        /** Open system settings for notification access grant. */
        fun openSettings(context: Context) {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    // ── Notification Posted ─────────────────────────────────────────────

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return

        // Skip ignored packages
        if (notification.packageName in IGNORED_PACKAGES) return

        // Skip ongoing notifications (music players, navigation, etc.)
        if (notification.isOngoing) return

        // Skip group summaries — we want individual messages
        if (notification.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        try {
            val captured = extractNotification(notification)
            if (captured != null) {
                NotificationStore.add(captured)
                Log.d(TAG, "Captured: [${captured.appName}] ${captured.title}: ${captured.text}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting notification from ${notification.packageName}", e)
        }
    }

    // ── Notification Removed ────────────────────────────────────────────

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn?.let { NotificationStore.markDismissed(it.key) }
    }

    // ── Listener Connected ──────────────────────────────────────────────

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Medusa NotificationReader connected.")

        // Load currently active notifications on connect
        try {
            val active = activeNotifications ?: return
            for (sbn in active) {
                if (sbn.packageName !in IGNORED_PACKAGES && !sbn.isOngoing) {
                    extractNotification(sbn)?.let { NotificationStore.add(it) }
                }
            }
            Log.i(TAG, "Loaded ${active.size} existing notifications.")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading existing notifications", e)
        }
    }

    // ── Extraction ──────────────────────────────────────────────────────

    /**
     * Extracts structured data from a [StatusBarNotification].
     * Handles MessagingStyle, BigTextStyle, InboxStyle, and standard notifications.
     */
    private fun extractNotification(sbn: StatusBarNotification): CapturedNotification? {
        val extras = sbn.notification.extras

        // Title
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: ""

        // Body
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

        // Skip empty
        if (title.isBlank() && text.isBlank()) return null

        // Sub-text (e.g. "3 new messages", account name)
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()

        // Big text — expanded notification content (email body, long messages)
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()

        // MessagingStyle — extract full conversation if available
        val messagingText = extractMessagingStyle(extras)

        // Resolve app label from package name
        val appName = resolveAppName(sbn.packageName)

        return CapturedNotification(
            key = sbn.key,
            packageName = sbn.packageName,
            appName = appName,
            title = title,
            text = messagingText ?: text,
            subText = subText,
            bigText = bigText,
            postedAt = sbn.postTime,
            category = sbn.notification.category,
            isActive = true,
            isRead = false
        )
    }

    /**
     * For messaging apps (WhatsApp, Telegram, SMS), extract the full
     * MessagingStyle conversation — includes sender name + message pairs.
     */
    private fun extractMessagingStyle(extras: Bundle): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null

        @Suppress("DEPRECATION")
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return null
        if (messages.isEmpty()) return null

        val lines = mutableListOf<String>()
        for (msg in messages) {
            if (msg is Bundle) {
                val sender = msg.getCharSequence("sender")?.toString() ?: "Unknown"
                val msgText = msg.getCharSequence("text")?.toString() ?: ""
                if (msgText.isNotEmpty()) {
                    lines.add("$sender: $msgText")
                }
            }
        }

        return lines.ifEmpty { null }?.joinToString("\n")
    }

    /**
     * Resolves package name → human-readable app label.
     * e.g. "com.whatsapp" → "WhatsApp"
     */
    private fun resolveAppName(packageName: String): String {
        return try {
            val pm = applicationContext.packageManager
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName.substringAfterLast(".").replaceFirstChar { it.uppercase() }
        }
    }
}
