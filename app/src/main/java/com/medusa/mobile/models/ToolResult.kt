package com.medusa.mobile.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.Json

/**
 * Structured response returned from every Tool method.
 * Serialized to JSON and sent back to Claude as the tool_result content block.
 *
 * Pattern matches iAgent iOS ToolResult — identical contract for Claude.
 */
@Serializable
data class ToolResult(
    val success: Boolean,
    val summary: String,
    val data: JsonElement? = null,
    val error: String? = null
) {
    companion object {
        @PublishedApi internal val json = Json { encodeDefaults = true }

        fun denied(message: String) = ToolResult(
            success = false,
            summary = message,
            error = "permission_denied"
        )

        fun failure(message: String) = ToolResult(
            success = false,
            summary = message,
            error = message
        )

        /** Convenience: wrap any @Serializable DTO as the data payload. */
        inline fun <reified T> success(summary: String, data: T): ToolResult {
            return ToolResult(
                success = true,
                summary = summary,
                data = json.encodeToJsonElement(data)
            )
        }

        fun success(summary: String) = ToolResult(success = true, summary = summary)
    }
}

// ── SMS DTOs ─────────────────────────────────────────────────────────────────

@Serializable
data class SmsMessageDTO(
    val id: Long,
    val address: String,       // phone number
    val contactName: String?,  // resolved contact name or null
    val body: String,
    val date: String,          // ISO 8601
    val type: String           // "inbox", "sent", "draft", "outbox"
)

@Serializable
data class SmsListDTO(
    val count: Int,
    val messages: List<SmsMessageDTO>
)

// ── Call History DTOs ────────────────────────────────────────────────────────

@Serializable
data class CallDTO(
    val id: Long,
    val number: String,
    val contactName: String?,  // resolved contact name or null
    val type: String,          // "incoming", "outgoing", "missed", "rejected", "blocked", "voicemail"
    val date: String,          // ISO 8601
    val duration: Int          // seconds
)

@Serializable
data class CallListDTO(
    val count: Int,
    val calls: List<CallDTO>
)

// ── Contacts DTOs (mm-013) ─────────────────────────────────────────────────

@Serializable
data class ContactDTO(
    val id: Long,
    val displayName: String,
    val phones: List<String>,       // "Mobile: +1-555-1234", "Work: +1-555-5678"
    val emails: List<String>,       // "Personal: john@gmail.com", "Work: john@corp.com"
    val organization: String?,      // "Google — Senior Engineer"
    val birthday: String?,          // "1990-05-15"
    val address: String?,           // formatted postal address
    val notes: String?,             // free-text notes
    val photoUri: String?,          // content:// URI for contact photo
    val starred: Boolean            // true if favorited
)

@Serializable
data class ContactListDTO(
    val count: Int,
    val contacts: List<ContactDTO>
)

// ── Web Research DTOs (mm-021) ─────────────────────────────────────────────

@Serializable
data class WebSearchResultDTO(
    val position: Int,          // 1-indexed rank
    val title: String,
    val url: String,
    val snippet: String         // text excerpt from Google
)

@Serializable
data class WebSearchListDTO(
    val query: String,
    val count: Int,
    val results: List<WebSearchResultDTO>
)

@Serializable
data class WebFetchDTO(
    val url: String,
    val title: String?,
    val content: String,        // extracted readable text
    val truncated: Boolean,     // true if content was capped
    val contentLength: Int      // full length before truncation
)

// ── Memory Store DTOs (mm-017) ──────────────────────────────────────────────

@Serializable
data class MemoryItemDTO(
    val key: String,
    val value: String,
    val category: String,
    val tags: List<String>,
    val createdAt: String,       // ISO 8601
    val updatedAt: String        // ISO 8601
)

@Serializable
data class MemoryListDTO(
    val count: Int,
    val items: List<MemoryItemDTO>
)

// ── Location & Maps DTOs (mm-020) ───────────────────────────────────────────

@Serializable
data class LocationDTO(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Int,     // horizontal accuracy in metres
    val provider: String,        // "gps", "network", "fused"
    val street: String?,
    val city: String?,
    val state: String?,
    val country: String?,
    val postalCode: String?,
    val formattedAddress: String // full human-readable address or "lat, lng"
)

@Serializable
data class MapsActionDTO(
    val destination: String,     // address, place, or search query
    val mode: String,            // "driving", "walking", "transit", "bicycling"
    val action: String,          // "directions", "search", "navigate"
    val urlOpened: String        // the URI that was opened
)

// ── App Launcher DTOs (mm-015) ───────────────────────────────────────────────

@Serializable
data class AppInfoDTO(
    val name: String,            // display name (e.g. "Spotify")
    val packageName: String,     // package ID (e.g. "com.spotify.music")
    val isSystem: Boolean        // true if a system app
)

@Serializable
data class AppListDTO(
    val count: Int,
    val apps: List<AppInfoDTO>
)

@Serializable
data class AppLaunchResultDTO(
    val packageName: String,     // resolved package that was launched
    val appName: String          // display name of the launched app
)

// ── Intent Tool DTOs (mm-025) ────────────────────────────────────────────────

@Serializable
data class IntentResultDTO(
    val action: String,          // "call", "dial", "timer", "settings", "share"
    val target: String,          // phone number, duration, panel name, or text preview
    val launched: Boolean        // true if the intent was successfully dispatched
)

@Serializable
data class AlarmResultDTO(
    val hour: Int,               // 0–23
    val minutes: Int,            // 0–59
    val label: String?,          // optional alarm label
    val repeating: Boolean       // true if days were specified
)

// ── Photos / Media DTOs (mm-018) ─────────────────────────────────────────────

@Serializable
data class PhotoDTO(
    val id: Long,
    val displayName: String,     // file name, e.g. "IMG_20240715_143022.jpg"
    val album: String,           // bucket/album name, e.g. "Camera", "Screenshots"
    val dateTaken: String?,      // "2024-07-15 14:30:22" or null
    val dateModified: String?,   // "2024-07-15 14:30:25" or null
    val sizeBytes: Long,         // file size in bytes (or photo count for album entries)
    val widthPx: Int,            // image width in pixels
    val heightPx: Int,           // image height in pixels
    val mimeType: String,        // "image/jpeg", "image/png", etc.
    val contentUri: String       // "content://media/external/images/media/<id>"
)

@Serializable
data class PhotoListDTO(
    val count: Int,
    val photos: List<PhotoDTO>,
    val note: String? = null     // optional annotation (e.g. for album mode)
)

// ── Device Utilities DTOs (mm-026) ───────────────────────────────────────────

@Serializable
data class ClipboardDTO(
    val text: String?,           // clipboard text content, or null if empty/non-text
    val empty: Boolean,          // true if clipboard has no readable text
    val label: String?           // optional label set when the clip was created
)

@Serializable
data class BatteryDTO(
    val percentage: Int,         // 0–100
    val chargingStatus: String,  // "charging", "discharging", "full", "not charging"
    val plugged: String,         // "AC charger", "USB", "wireless", "not charging"
    val temperatureCelsius: Double?, // battery temp in °C, or null if unavailable
    val health: String           // "good", "overheating", "dead", "cold", etc.
)

@Serializable
data class FlashlightDTO(
    val on: Boolean,             // true if torch is now on, false if off
    val cameraId: String         // Camera2 camera ID used for the torch
)

@Serializable
data class DeviceInfoDTO(
    val manufacturer: String,    // e.g. "Samsung", "Google"
    val model: String,           // e.g. "Pixel 8 Pro", "SM-S918B"
    val brand: String,           // e.g. "google", "samsung"
    val androidVersion: String,  // e.g. "14"
    val sdkLevel: Int,           // e.g. 34
    val screenWidthDp: Int,
    val screenHeightDp: Int,
    val screenDensityDpi: Int,
    val screenWidthPx: Int,
    val screenHeightPx: Int
)
