package com.medusa.mobile.agent.tools

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.BatteryManager
import android.os.Build
import androidx.core.content.getSystemService
import com.medusa.mobile.models.BatteryDTO
import com.medusa.mobile.models.ClipboardDTO
import com.medusa.mobile.models.DeviceInfoDTO
import com.medusa.mobile.models.FlashlightDTO
import com.medusa.mobile.models.ToolResult

/**
 * mm-026 — Device Utilities Tool for Medusa Mobile.
 *
 * Exposes device utility functions to Claude:
 *   - clipboard_read  — read current clipboard text
 *   - clipboard_write — write text to clipboard
 *   - battery_status  — get battery level, charging state, temperature
 *   - flashlight_on   — turn on torch via Camera2 API
 *   - flashlight_off  — turn off torch
 *   - device_info     — get device model, OS version, screen metrics
 *
 * No dangerous permissions required — all operations use system services
 * available to any foreground app.
 *
 * Claude tool name: "device_utility"
 */
class DeviceUtilitiesTool(private val context: Context) {

    // ── State ────────────────────────────────────────────────────────────────

    // Track which camera ID has the torch, cached after first lookup
    private var torchCameraId: String? = null
    private var isTorchOn = false

    // ── Claude Tool Definition ───────────────────────────────────────────────

    companion object {
        val claudeToolDefinition: Map<String, Any> = mapOf(
            "name" to "device_utility",
            "description" to """
                Controls device utilities and reads device status.
                Actions available:
                  - "clipboard_read"  — read the current text on the clipboard
                  - "clipboard_write" — write text to the clipboard
                  - "battery_status"  — get battery percentage, charging state, and temperature
                  - "flashlight_on"   — turn the flashlight / torch on
                  - "flashlight_off"  — turn the flashlight / torch off
                  - "device_info"     — get device model, Android version, and screen info
                Use this for requests like "what's on my clipboard?", "copy this text",
                "how's my battery?", "turn on flashlight", "turn off the torch",
                or "what phone am I using?".
                No special permissions required.
            """.trimIndent(),
            "input_schema" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "action" to mapOf(
                        "type" to "string",
                        "enum" to listOf(
                            "clipboard_read",
                            "clipboard_write",
                            "battery_status",
                            "flashlight_on",
                            "flashlight_off",
                            "device_info"
                        ),
                        "description" to "The utility action to perform."
                    ),
                    "text" to mapOf(
                        "type" to "string",
                        "description" to "Text to write to clipboard. Required only for 'clipboard_write' action."
                    )
                ),
                "required" to listOf("action")
            )
        )
    }

    // ── Tool Execution ───────────────────────────────────────────────────────

    fun execute(action: String, text: String? = null): ToolResult {
        return when (action.lowercase()) {
            "clipboard_read"  -> readClipboard()
            "clipboard_write" -> writeClipboard(text)
            "battery_status"  -> getBatteryStatus()
            "flashlight_on"   -> setFlashlight(on = true)
            "flashlight_off"  -> setFlashlight(on = false)
            "device_info"     -> getDeviceInfo()
            else -> ToolResult.failure("Unknown action '$action'. Valid: clipboard_read, clipboard_write, battery_status, flashlight_on, flashlight_off, device_info.")
        }
    }

    // ── Clipboard ────────────────────────────────────────────────────────────

    /**
     * Reads the current primary clip from the system clipboard.
     * Returns the text content or an empty indicator.
     *
     * Note: On Android 10+, apps can only read clipboard when they are the
     * foreground activity or an input method service. Medusa is a foreground
     * assistant app so this is expected to work in normal use.
     */
    private fun readClipboard(): ToolResult {
        val clipboard = context.getSystemService<ClipboardManager>()
            ?: return ToolResult.failure("ClipboardManager not available.")

        val clip = clipboard.primaryClip
        if (clip == null || clip.itemCount == 0) {
            return ToolResult.success(
                summary = "Clipboard is empty.",
                data = ClipboardDTO(text = null, empty = true, label = null)
            )
        }

        val item = clip.getItemAt(0)
        val text = item.coerceToText(context)?.toString()
        val label = clip.description?.label?.toString()

        if (text.isNullOrBlank()) {
            return ToolResult.success(
                summary = "Clipboard has no readable text (may contain a non-text item).",
                data = ClipboardDTO(text = null, empty = true, label = label)
            )
        }

        val preview = if (text.length > 200) text.take(200) + "…" else text
        return ToolResult.success(
            summary = "Clipboard: \"$preview\"",
            data = ClipboardDTO(text = text, empty = false, label = label)
        )
    }

    /**
     * Writes the given text to the system clipboard.
     */
    private fun writeClipboard(text: String?): ToolResult {
        if (text == null) {
            return ToolResult.failure("'text' is required for clipboard_write action.")
        }

        val clipboard = context.getSystemService<ClipboardManager>()
            ?: return ToolResult.failure("ClipboardManager not available.")

        val clip = ClipData.newPlainText("Medusa", text)
        clipboard.setPrimaryClip(clip)

        val preview = if (text.length > 100) text.take(100) + "…" else text
        return ToolResult.success(
            summary = "Copied to clipboard: \"$preview\"",
            data = ClipboardDTO(text = text, empty = false, label = "Medusa")
        )
    }

    // ── Battery ──────────────────────────────────────────────────────────────

    /**
     * Reads battery status via the ACTION_BATTERY_CHANGED sticky broadcast.
     * Returns level (0–100), plugged state, charging state, and temperature.
     */
    private fun getBatteryStatus(): ToolResult {
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        // Sticky broadcast — registerReceiver with null receiver returns last broadcast
        val batteryIntent = context.registerReceiver(null, intentFilter)
            ?: return ToolResult.failure("Battery status unavailable.")

        val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val percentage = if (scale > 0) (level * 100 / scale) else -1

        val pluggedExtra = batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        val plugged = when (pluggedExtra) {
            BatteryManager.BATTERY_PLUGGED_AC      -> "AC charger"
            BatteryManager.BATTERY_PLUGGED_USB     -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            0                                       -> "not charging"
            else                                    -> "unknown"
        }

        val statusExtra = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val chargingStatus = when (statusExtra) {
            BatteryManager.BATTERY_STATUS_CHARGING    -> "charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
            BatteryManager.BATTERY_STATUS_FULL        -> "full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not charging"
            else                                       -> "unknown"
        }

        // Temperature in tenths of a degree Celsius
        val tempTenths = batteryIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
        val tempCelsius = if (tempTenths > 0) tempTenths / 10.0 else null

        val healthExtra = batteryIntent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)
        val health = when (healthExtra) {
            BatteryManager.BATTERY_HEALTH_GOOD        -> "good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT    -> "overheating"
            BatteryManager.BATTERY_HEALTH_DEAD        -> "dead"
            BatteryManager.BATTERY_HEALTH_COLD        -> "cold"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over voltage"
            else                                       -> "unknown"
        }

        val summary = buildString {
            append("Battery: $percentage%")
            if (chargingStatus != "unknown") append(", $chargingStatus")
            if (pluggedExtra > 0) append(" via $plugged")
            if (tempCelsius != null) append(", ${tempCelsius}°C")
        }

        return ToolResult.success(
            summary = summary,
            data = BatteryDTO(
                percentage = percentage,
                chargingStatus = chargingStatus,
                plugged = plugged,
                temperatureCelsius = tempCelsius,
                health = health
            )
        )
    }

    // ── Flashlight ───────────────────────────────────────────────────────────

    /**
     * Toggles the camera torch via Camera2 CameraManager.
     * Finds the first rear-facing camera with a torch unit.
     *
     * Note: torch stays on until flashlight_off is called, the app dies,
     * or another app claims the torch. No permission required — CAMERA permission
     * is only needed to open a CameraDevice, not to use the torch.
     */
    private fun setFlashlight(on: Boolean): ToolResult {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return ToolResult.failure("Camera service not available.")

        // Cache the torch-capable camera ID
        if (torchCameraId == null) {
            torchCameraId = findTorchCameraId(cameraManager)
        }

        val cameraId = torchCameraId
            ?: return ToolResult.failure("No flashlight found on this device.")

        return try {
            cameraManager.setTorchMode(cameraId, on)
            isTorchOn = on
            val state = if (on) "on" else "off"
            ToolResult.success(
                summary = "Flashlight turned $state.",
                data = FlashlightDTO(on = on, cameraId = cameraId)
            )
        } catch (e: Exception) {
            ToolResult.failure("Failed to ${if (on) "enable" else "disable"} flashlight: ${e.message}")
        }
    }

    /**
     * Finds the first camera ID that has a rear-facing lens with torch support.
     * Falls back to any camera with torch support if no rear-facing one is found.
     */
    private fun findTorchCameraId(cameraManager: CameraManager): String? {
        var fallbackId: String? = null

        for (id in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics(id)
            val hasTorch = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            if (!hasTorch) continue

            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK) return id  // prefer rear
            if (fallbackId == null) fallbackId = id
        }

        return fallbackId
    }

    // ── Device Info ──────────────────────────────────────────────────────────

    /**
     * Returns device model, manufacturer, Android version, SDK level,
     * and screen metrics.
     */
    private fun getDeviceInfo(): ToolResult {
        val displayMetrics = context.resources.displayMetrics
        val screenWidthDp = (displayMetrics.widthPixels / displayMetrics.density).toInt()
        val screenHeightDp = (displayMetrics.heightPixels / displayMetrics.density).toInt()

        val dto = DeviceInfoDTO(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            brand = Build.BRAND,
            androidVersion = Build.VERSION.RELEASE,
            sdkLevel = Build.VERSION.SDK_INT,
            screenWidthDp = screenWidthDp,
            screenHeightDp = screenHeightDp,
            screenDensityDpi = displayMetrics.densityDpi,
            screenWidthPx = displayMetrics.widthPixels,
            screenHeightPx = displayMetrics.heightPixels
        )

        val summary = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"

        return ToolResult.success(summary = summary, data = dto)
    }
}
