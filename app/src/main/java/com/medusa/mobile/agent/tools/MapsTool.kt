package com.medusa.mobile.agent.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import android.net.Uri
import androidx.core.content.ContextCompat
import com.medusa.mobile.models.LocationDTO
import com.medusa.mobile.models.MapsActionDTO
import com.medusa.mobile.models.ToolResult
import java.util.Locale

/**
 * mm-020 — Maps & Location Tool for Medusa Mobile.
 *
 * Two Claude tools in one class:
 *   1. `get_location` — current GPS coords + reverse geocoding
 *   2. `open_maps` — launch Google Maps with directions, search, or navigation
 *
 * Enables tool chaining: Claude reads calendar → gets meeting location →
 * calls open_maps with directions. All in one turn.
 *
 * Requires: ACCESS_FINE_LOCATION + ACCESS_COARSE_LOCATION (already in manifest)
 */
class MapsTool(private val context: Context) {

    // ── Claude Tool Definitions ──────────────────────────────────────────────

    companion object {
        val claudeToolDefinitions: List<Map<String, Any>> = listOf(
            // 1. get_location — current GPS position
            mapOf(
                "name" to "get_location",
                "description" to """
                    Returns the user's current GPS location — latitude, longitude, accuracy,
                    and reverse-geocoded street address. Use this to answer "where am I?",
                    "what's my current location?", or when you need the user's position for
                    directions or nearby searches. Requires ACCESS_FINE_LOCATION permission.
                """.trimIndent(),
                "input_schema" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "include_address" to mapOf(
                            "type" to "boolean",
                            "description" to "If true, reverse-geocode to get a street address. Default true.",
                            "default" to true
                        )
                    ),
                    "required" to emptyList<String>()
                )
            ),
            // 2. open_maps — launch Google Maps
            mapOf(
                "name" to "open_maps",
                "description" to """
                    Opens Google Maps with directions, a place search, or navigation.
                    Use this when the user asks "get directions to the airport", "find coffee
                    shops nearby", "navigate to 123 Main St", or "how do I get to my meeting?".
                    Supports driving, walking, transit, and bicycling modes.
                    Can combine with get_location to provide directions FROM the user's current
                    position, or with calendar to find the meeting location automatically.
                """.trimIndent(),
                "input_schema" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "destination" to mapOf(
                            "type" to "string",
                            "description" to "Destination address, place name, or lat,lng coordinates. Required for directions."
                        ),
                        "origin" to mapOf(
                            "type" to "string",
                            "description" to "Starting point. Omit to use current location. Can be an address or lat,lng."
                        ),
                        "mode" to mapOf(
                            "type" to "string",
                            "enum" to listOf("driving", "walking", "transit", "bicycling"),
                            "description" to "Travel mode. Default 'driving'.",
                            "default" to "driving"
                        ),
                        "action" to mapOf(
                            "type" to "string",
                            "enum" to listOf("directions", "search", "navigate"),
                            "description" to "'directions' shows route, 'search' finds nearby places, 'navigate' starts turn-by-turn. Default 'directions'.",
                            "default" to "directions"
                        ),
                        "query" to mapOf(
                            "type" to "string",
                            "description" to "Search query for 'search' action. E.g. 'coffee shops', 'gas stations', 'pharmacies'."
                        )
                    ),
                    "required" to emptyList<String>()
                )
            )
        )
    }

    // ── get_location ─────────────────────────────────────────────────────────

    /**
     * Returns the user's current GPS location with optional reverse geocoding.
     */
    @Suppress("MissingPermission")
    fun getLocation(includeAddress: Boolean = true): ToolResult {
        // Check permission
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return ToolResult.denied(
                "Location permission not granted. The user needs to allow location access in Settings."
            )
        }

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // Try GPS first, then network, then fused
        val location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            ?: locationManager.getLastKnownLocation(LocationManager.FUSED_PROVIDER)

        if (location == null) {
            return ToolResult.failure(
                "No location available. GPS may not have a fix yet. Ask the user to open Google Maps briefly to get a GPS lock, then try again."
            )
        }

        // Reverse geocode if requested
        var street: String? = null
        var city: String? = null
        var state: String? = null
        var country: String? = null
        var postalCode: String? = null
        var formattedAddress = "${location.latitude}, ${location.longitude}"

        if (includeAddress) {
            try {
                val geocoder = Geocoder(context, Locale.getDefault())
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                if (!addresses.isNullOrEmpty()) {
                    val addr = addresses[0]
                    street = addr.thoroughfare?.let { t ->
                        addr.subThoroughfare?.let { "$it $t" } ?: t
                    }
                    city = addr.locality
                    state = addr.adminArea
                    country = addr.countryName
                    postalCode = addr.postalCode
                    // Build formatted address
                    formattedAddress = listOfNotNull(
                        street,
                        city,
                        state?.let { s -> postalCode?.let { "$s $it" } ?: s },
                        country
                    ).joinToString(", ")
                }
            } catch (_: Exception) {
                // Geocoder failure is non-fatal — we still have coords
            }
        }

        val dto = LocationDTO(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = location.accuracy.toInt(),
            provider = location.provider ?: "unknown",
            street = street,
            city = city,
            state = state,
            country = country,
            postalCode = postalCode,
            formattedAddress = formattedAddress
        )

        val summary = if (street != null) {
            "Current location: $formattedAddress (±${location.accuracy.toInt()}m)"
        } else {
            "Current location: ${location.latitude}, ${location.longitude} (±${location.accuracy.toInt()}m)"
        }

        return ToolResult.success(summary = summary, data = dto)
    }

    // ── open_maps ────────────────────────────────────────────────────────────

    /**
     * Opens Google Maps with directions, search, or turn-by-turn navigation.
     */
    fun openMaps(
        destination: String? = null,
        origin: String? = null,
        mode: String = "driving",
        action: String = "directions",
        query: String? = null
    ): ToolResult {

        val uri: Uri
        val actionDesc: String

        when (action.lowercase()) {
            "search" -> {
                // Search: geo:lat,lng?q=query or geo:0,0?q=query
                val q = query ?: destination ?: return ToolResult.failure("Search requires a query or destination.")
                uri = Uri.parse("geo:0,0?q=${Uri.encode(q)}")
                actionDesc = "search for \"$q\""
            }

            "navigate" -> {
                // Turn-by-turn navigation
                val dest = destination ?: return ToolResult.failure("Navigation requires a destination.")
                val modeChar = when (mode.lowercase()) {
                    "walking" -> "w"
                    "bicycling" -> "b"
                    "transit" -> "r"
                    else -> "d"  // driving
                }
                uri = Uri.parse("google.navigation:q=${Uri.encode(dest)}&mode=$modeChar")
                actionDesc = "navigate to \"$dest\" ($mode)"
            }

            else -> {
                // Directions (default)
                val dest = destination ?: return ToolResult.failure("Directions requires a destination.")
                val modeParam = when (mode.lowercase()) {
                    "walking" -> "walking"
                    "bicycling" -> "bicycling"
                    "transit" -> "transit"
                    else -> "driving"
                }
                val originParam = origin?.let { "&origin=${Uri.encode(it)}" } ?: ""
                uri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${Uri.encode(dest)}$originParam&travelmode=$modeParam")
                actionDesc = "directions to \"$dest\" ($modeParam)"
            }
        }

        return try {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                // Try Google Maps first, fall back to any maps app
                setPackage("com.google.android.apps.maps")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // Check if Google Maps is installed
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
            } else {
                // Fallback: open without specifying package (any maps app)
                val fallback = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
            }

            val dto = MapsActionDTO(
                destination = destination ?: query ?: "",
                mode = mode,
                action = action,
                urlOpened = uri.toString()
            )

            ToolResult.success(
                summary = "Opened Maps: $actionDesc",
                data = dto
            )
        } catch (e: Exception) {
            ToolResult.failure("Failed to open Maps: ${e.localizedMessage}")
        }
    }
}
