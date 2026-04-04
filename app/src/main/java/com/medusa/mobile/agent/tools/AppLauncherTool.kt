package com.medusa.mobile.agent.tools

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import com.medusa.mobile.models.AppInfoDTO
import com.medusa.mobile.models.AppLaunchResultDTO
import com.medusa.mobile.models.AppListDTO
import com.medusa.mobile.models.ToolResult

/**
 * mm-015 — App Launcher & Deep Link Tool for Medusa Mobile.
 *
 * Three Claude tools in one class:
 *   1. `list_apps`   — list all installed user apps (searchable)
 *   2. `launch_app`  — open an app by name or package name
 *   3. `open_url`    — open a URL / deep link (tel:, mailto:, https:, custom schemes)
 *
 * This is what makes Medusa genuinely useful as a phone assistant — Claude can
 * open Spotify, Uber, WhatsApp, or any app the user asks for. No special
 * permissions needed for launching (just querying the package list).
 *
 * Claude tool names: "list_apps", "launch_app", "open_url"
 */
class AppLauncherTool(private val context: Context) {

    private val pm: PackageManager = context.packageManager

    // ── Claude Tool Definitions ──────────────────────────────────────────────

    companion object {
        val claudeToolDefinitions: List<Map<String, Any>> = listOf(
            // 1. list_apps
            mapOf(
                "name" to "list_apps",
                "description" to """
                    Returns a list of installed apps on the device. Searchable by name.
                    Use this before launching to find the exact package name, or to answer
                    "what apps do I have?", "is Spotify installed?", "find my banking app".
                    Only returns user-installed apps (not system apps) by default.
                """.trimIndent(),
                "input_schema" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "query" to mapOf(
                            "type" to "string",
                            "description" to "Optional. Filter apps whose name contains this string (case-insensitive). E.g. 'music', 'bank', 'google'."
                        ),
                        "include_system" to mapOf(
                            "type" to "boolean",
                            "description" to "If true, include system apps. Default false (user apps only).",
                            "default" to false
                        ),
                        "limit" to mapOf(
                            "type" to "integer",
                            "description" to "Max apps to return. Default 30, max 100.",
                            "default" to 30
                        )
                    ),
                    "required" to emptyList<String>()
                )
            ),
            // 2. launch_app
            mapOf(
                "name" to "launch_app",
                "description" to """
                    Opens an installed app by name or package name. Use when the user says
                    "open Spotify", "launch YouTube", "open my camera", "start WhatsApp".
                    If the app name is ambiguous, call list_apps first to confirm the package.
                    Brings the app to the foreground — user can return to Medusa with back button.
                """.trimIndent(),
                "input_schema" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "app_name" to mapOf(
                            "type" to "string",
                            "description" to "App display name to search for (case-insensitive partial match). E.g. 'Spotify', 'YouTube', 'Camera'."
                        ),
                        "package_name" to mapOf(
                            "type" to "string",
                            "description" to "Exact package name for precise launch. E.g. 'com.spotify.music'. Preferred over app_name if known."
                        )
                    ),
                    "required" to emptyList<String>()
                )
            ),
            // 3. open_url
            mapOf(
                "name" to "open_url",
                "description" to """
                    Opens a URL or deep link in the appropriate app or browser. Handles:
                    - https:// / http:// URLs → opens in browser or app
                    - tel: → dials a phone number (e.g. tel:+15551234567)
                    - mailto: → opens email composer (e.g. mailto:someone@example.com)
                    - Custom deep links → app-specific (e.g. spotify:track:xxx, youtube://...)
                    Use for "open this link", "call this number", "email Sarah",
                    "open the Uber app to book a ride".
                """.trimIndent(),
                "input_schema" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "url" to mapOf(
                            "type" to "string",
                            "description" to "The URL or deep link to open. E.g. 'https://example.com', 'tel:+15551234567', 'mailto:user@example.com', 'spotify:album:xxx'."
                        )
                    ),
                    "required" to listOf("url")
                )
            )
        )
    }

    // ── list_apps ─────────────────────────────────────────────────────────────

    /**
     * Returns installed apps, optionally filtered by query string.
     */
    fun listApps(
        query: String? = null,
        includeSystem: Boolean = false,
        limit: Int = 30
    ): ToolResult {
        val cap = limit.coerceIn(1, 100)

        val flags = PackageManager.GET_META_DATA
        val allPackages = try {
            pm.getInstalledApplications(flags)
        } catch (e: Exception) {
            return ToolResult.failure("Failed to list apps: ${e.localizedMessage}")
        }

        // Filter system vs user apps
        val filtered = allPackages.filter { app ->
            val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (!includeSystem && isSystem) return@filter false

            // Must have a launchable intent (skip background-only packages)
            if (pm.getLaunchIntentForPackage(app.packageName) == null) return@filter false

            // Name query filter
            if (!query.isNullOrBlank()) {
                val appName = pm.getApplicationLabel(app).toString()
                return@filter appName.contains(query, ignoreCase = true) ||
                    app.packageName.contains(query, ignoreCase = true)
            }

            true
        }

        // Sort by app name, take limit
        val sorted = filtered
            .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }
            .take(cap)

        if (sorted.isEmpty()) {
            return ToolResult.success(
                if (query.isNullOrBlank()) "No launchable apps found."
                else "No apps found matching \"$query\"."
            )
        }

        val apps = sorted.map { app ->
            AppInfoDTO(
                name = pm.getApplicationLabel(app).toString(),
                packageName = app.packageName,
                isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            )
        }

        val summary = "${apps.size} app(s) found" +
            if (!query.isNullOrBlank()) " matching \"$query\"." else "."

        return ToolResult.success(
            summary = summary,
            data = AppListDTO(count = apps.size, apps = apps)
        )
    }

    // ── launch_app ────────────────────────────────────────────────────────────

    /**
     * Launches an app by package name (preferred) or display name (fuzzy match).
     */
    fun launchApp(appName: String? = null, packageName: String? = null): ToolResult {
        if (appName.isNullOrBlank() && packageName.isNullOrBlank()) {
            return ToolResult.failure("Provide either app_name or package_name.")
        }

        // Resolve package name from display name if not provided
        val resolvedPackage: String = when {
            !packageName.isNullOrBlank() -> packageName

            else -> {
                // Fuzzy match by app display name
                val target = appName!!.lowercase().trim()
                val match = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                    .map { it to pm.getApplicationLabel(it).toString() }
                    .firstOrNull { (_, name) ->
                        name.lowercase() == target ||              // exact match first
                        name.lowercase().contains(target)           // then partial
                    }?.first?.packageName
                    ?: return ToolResult.failure(
                        "No installed app found matching \"$appName\". " +
                        "Use list_apps to see what's installed."
                    )
                match
            }
        }

        val launchIntent = pm.getLaunchIntentForPackage(resolvedPackage)
            ?: return ToolResult.failure(
                "App \"$resolvedPackage\" is installed but has no launchable entry point."
            )

        return try {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)

            val displayName = try {
                pm.getApplicationLabel(pm.getApplicationInfo(resolvedPackage, 0)).toString()
            } catch (_: Exception) { resolvedPackage }

            ToolResult.success(
                summary = "Opened $displayName.",
                data = AppLaunchResultDTO(packageName = resolvedPackage, appName = displayName)
            )
        } catch (e: Exception) {
            ToolResult.failure("Failed to launch $resolvedPackage: ${e.localizedMessage}")
        }
    }

    // ── open_url ──────────────────────────────────────────────────────────────

    /**
     * Opens a URL, tel: link, mailto: link, or custom deep link.
     */
    fun openUrl(url: String): ToolResult {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) {
            return ToolResult.failure("URL is required.")
        }

        val uri = try {
            Uri.parse(trimmed)
        } catch (e: Exception) {
            return ToolResult.failure("Invalid URL: $trimmed")
        }

        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        // Check if anything can handle this intent
        if (intent.resolveActivity(pm) == null) {
            return ToolResult.failure(
                "No app found to handle \"$trimmed\". " +
                "The app may not be installed or the URL scheme isn't supported."
            )
        }

        return try {
            context.startActivity(intent)
            val scheme = uri.scheme ?: "url"
            val desc = when (scheme.lowercase()) {
                "tel" -> "Dialing ${uri.schemeSpecificPart}"
                "mailto" -> "Opening email to ${uri.schemeSpecificPart}"
                "http", "https" -> "Opening ${uri.host ?: trimmed}"
                else -> "Opening $trimmed"
            }
            ToolResult.success(summary = desc)
        } catch (e: Exception) {
            ToolResult.failure("Failed to open \"$trimmed\": ${e.localizedMessage}")
        }
    }
}
