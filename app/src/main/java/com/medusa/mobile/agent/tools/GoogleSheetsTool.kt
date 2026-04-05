package com.medusa.mobile.agent.tools

// mm-024 — Google Sheets Tool for Medusa Mobile
//
// Enables Claude to read, write, append, and create Google Sheets via REST API.
// Uses OAuth2 token from GoogleAuthManager (same sign-in as Docs/Drive).
//
// Tool names:
//   sheets_read    → read a range of cells from a spreadsheet
//   sheets_write   → write/update a range of cells
//   sheets_append  → append rows to the end of a sheet
//   sheets_create  → create a new spreadsheet with an optional title
//
// All operations use the Sheets REST API v4 via OkHttp — no extra library needed.
// Scope: https://www.googleapis.com/auth/spreadsheets (read/write)
//        https://www.googleapis.com/auth/drive.file   (create/list via Drive)
//
// Why Sheets?
//   "Add this to my grocery list spreadsheet" or "what's in row 5 of my budget sheet"
//   are natural user requests. Sheets is also the output target for structured data:
//   "save my workout log to a spreadsheet" leverages this tool.

import android.content.Context
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
import java.util.concurrent.TimeUnit

/**
 * Google Sheets tool — read, write, append, create spreadsheets.
 *
 * "Add milk to my grocery spreadsheet" → sheets_append
 * "What's in my budget sheet row 3?" → sheets_read
 * "Create a new workout log sheet" → sheets_create
 */
class GoogleSheetsTool(
    private val context: Context,
    private val authManager: GoogleAuthManager
) {

    companion object {
        private const val SHEETS_API = "https://sheets.googleapis.com/v4/spreadsheets"
        private const val DRIVE_API = "https://www.googleapis.com/drive/v3/files"
        private const val SCOPE_SHEETS = "https://www.googleapis.com/auth/spreadsheets"
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // ── sheets_read ──────────────────────────────────────────────────────────

    /**
     * Read a range of cells from a Google Sheet.
     * Range uses A1 notation: "Sheet1!A1:C10", "A1:Z100", "Sheet1!A:A" (whole column)
     */
    suspend fun readSheet(
        spreadsheetId: String,
        range: String,
        includeHeaders: Boolean = true
    ): ToolResult = withContext(Dispatchers.IO) {
        val token = getSheetsToken()
            ?: return@withContext ToolResult.failure(
                "Google account not connected. Sign in via Settings → Connect Google Account."
            )

        try {
            val encodedRange = java.net.URLEncoder.encode(range, "UTF-8")
            val url = "$SHEETS_API/$spreadsheetId/values/$encodedRange" +
                "?valueRenderOption=FORMATTED_VALUE" +
                "&dateTimeRenderOption=FORMATTED_STRING"

            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val err = response.body?.string() ?: ""
                return@withContext ToolResult.failure(
                    "Failed to read sheet (HTTP ${response.code}): $err"
                )
            }

            val body = JSONObject(response.body?.string() ?: "{}")
            val values = body.optJSONArray("values")

            if (values == null || values.length() == 0) {
                return@withContext ToolResult.success(
                    "Range '$range' is empty or contains no data."
                )
            }

            val rowCount = values.length()
            val result = StringBuilder()
            result.append("Range: $range ($rowCount row${if (rowCount != 1) "s" else ""})\n\n")

            // Format as a readable table
            val startRow = if (includeHeaders) 0 else 1
            if (includeHeaders && rowCount > 0) {
                val headerRow = values.getJSONArray(0)
                val headers = (0 until headerRow.length()).map { headerRow.optString(it) }
                result.append("**${headers.joinToString(" | ")}**\n")
                result.append("${headers.map { "-".repeat(it.length.coerceAtLeast(3)) }.joinToString("-+-")}\n")
            }

            for (i in startRow until rowCount) {
                val row = values.getJSONArray(i)
                val cells = (0 until row.length()).map { row.optString(it) }
                result.append("${cells.joinToString(" | ")}\n")
            }

            ToolResult.success(result.toString().trim())

        } catch (e: Exception) {
            ToolResult.failure("Failed to read sheet: ${e.message}")
        }
    }

    // ── sheets_write ─────────────────────────────────────────────────────────

    /**
     * Write values to a range in a Google Sheet (overwrites existing cells).
     * values is a 2D array: [[row1col1, row1col2], [row2col1, row2col2]]
     * Pass as a JSON array string for simplicity from Claude's JSON input.
     */
    suspend fun writeSheet(
        spreadsheetId: String,
        range: String,
        valuesJson: String   // JSON 2D array: [["Name", "Age"], ["Alice", "30"]]
    ): ToolResult = withContext(Dispatchers.IO) {
        val token = getSheetsToken()
            ?: return@withContext ToolResult.failure("Google account not connected.")

        try {
            val valuesArray = JSONArray(valuesJson)
            val encodedRange = java.net.URLEncoder.encode(range, "UTF-8")

            val requestBody = JSONObject().apply {
                put("range", range)
                put("majorDimension", "ROWS")
                put("values", valuesArray)
            }

            val url = "$SHEETS_API/$spreadsheetId/values/$encodedRange" +
                "?valueInputOption=USER_ENTERED"

            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .put(requestBody.toString().toRequestBody(JSON_TYPE))
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val err = response.body?.string() ?: ""
                return@withContext ToolResult.failure(
                    "Failed to write to sheet (HTTP ${response.code}): $err"
                )
            }

            val body = JSONObject(response.body?.string() ?: "{}")
            val updatedCells = body.optInt("updatedCells", 0)
            val updatedRange = body.optString("updatedRange", range)

            ToolResult.success(
                "Updated $updatedCells cell${if (updatedCells != 1) "s" else ""} in $updatedRange. " +
                "https://docs.google.com/spreadsheets/d/$spreadsheetId/edit"
            )

        } catch (e: Exception) {
            ToolResult.failure("Failed to write to sheet: ${e.message}")
        }
    }

    // ── sheets_append ────────────────────────────────────────────────────────

    /**
     * Append one or more rows to the end of a sheet (after last row with data).
     * valuesJson: JSON 2D array of rows to append.
     */
    suspend fun appendToSheet(
        spreadsheetId: String,
        range: String = "Sheet1",
        valuesJson: String
    ): ToolResult = withContext(Dispatchers.IO) {
        val token = getSheetsToken()
            ?: return@withContext ToolResult.failure("Google account not connected.")

        try {
            val valuesArray = JSONArray(valuesJson)
            val encodedRange = java.net.URLEncoder.encode(range, "UTF-8")

            val requestBody = JSONObject().apply {
                put("majorDimension", "ROWS")
                put("values", valuesArray)
            }

            val url = "$SHEETS_API/$spreadsheetId/values/$encodedRange:append" +
                "?valueInputOption=USER_ENTERED" +
                "&insertDataOption=INSERT_ROWS"

            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .post(requestBody.toString().toRequestBody(JSON_TYPE))
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val err = response.body?.string() ?: ""
                return@withContext ToolResult.failure(
                    "Failed to append to sheet (HTTP ${response.code}): $err"
                )
            }

            val body = JSONObject(response.body?.string() ?: "{}")
            val updatedCells = body.optJSONObject("updates")?.optInt("updatedCells", 0) ?: 0
            val appendedRange = body.optJSONObject("updates")?.optString("updatedRange", range) ?: range

            ToolResult.success(
                "Appended ${valuesArray.length()} row${if (valuesArray.length() != 1) "s" else ""} " +
                "($updatedCells cell${if (updatedCells != 1) "s" else ""}) to $appendedRange. " +
                "https://docs.google.com/spreadsheets/d/$spreadsheetId/edit"
            )

        } catch (e: Exception) {
            ToolResult.failure("Failed to append to sheet: ${e.message}")
        }
    }

    // ── sheets_create ────────────────────────────────────────────────────────

    /**
     * Create a new Google Spreadsheet with an optional title and initial headers.
     * Returns the spreadsheet ID and URL.
     */
    suspend fun createSheet(
        title: String = "Untitled Spreadsheet",
        headers: List<String> = emptyList()
    ): ToolResult = withContext(Dispatchers.IO) {
        val token = getSheetsToken()
            ?: return@withContext ToolResult.failure("Google account not connected.")

        try {
            // Build request body with title and optional header row
            val sheetsArray = JSONArray().put(
                JSONObject().apply {
                    put("properties", JSONObject().apply {
                        put("title", "Sheet1")
                    })
                    // Seed with headers if provided
                    if (headers.isNotEmpty()) {
                        val headerRow = JSONArray().apply {
                            headers.forEach { put(JSONObject().apply {
                                put("userEnteredValue", JSONObject().apply {
                                    put("stringValue", it)
                                })
                            })}
                        }
                        put("data", JSONArray().put(
                            JSONObject().apply {
                                put("startRow", 0)
                                put("startColumn", 0)
                                put("rowData", JSONArray().put(
                                    JSONObject().apply {
                                        put("values", headerRow)
                                    }
                                ))
                            }
                        ))
                    }
                }
            )

            val requestBody = JSONObject().apply {
                put("properties", JSONObject().apply {
                    put("title", title)
                })
                put("sheets", sheetsArray)
            }

            val request = Request.Builder()
                .url(SHEETS_API)
                .addHeader("Authorization", "Bearer $token")
                .post(requestBody.toString().toRequestBody(JSON_TYPE))
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val err = response.body?.string() ?: ""
                return@withContext ToolResult.failure(
                    "Failed to create spreadsheet (HTTP ${response.code}): $err"
                )
            }

            val body = JSONObject(response.body?.string() ?: "{}")
            val spreadsheetId = body.optString("spreadsheetId", "")
            val spreadsheetUrl = body.optString("spreadsheetUrl",
                "https://docs.google.com/spreadsheets/d/$spreadsheetId/edit")

            if (spreadsheetId.isEmpty()) {
                return@withContext ToolResult.failure("Created spreadsheet but no ID returned.")
            }

            val headerInfo = if (headers.isNotEmpty()) " with headers: ${headers.joinToString(", ")}" else ""
            ToolResult.success(
                "Created spreadsheet '$title'$headerInfo.\n" +
                "ID: $spreadsheetId\n" +
                "URL: $spreadsheetUrl"
            )

        } catch (e: Exception) {
            ToolResult.failure("Failed to create spreadsheet: ${e.message}")
        }
    }

    // ── sheets_search_drive ──────────────────────────────────────────────────

    /**
     * Search Google Drive for spreadsheets by name.
     * Helps Claude find a spreadsheet ID before reading/writing.
     */
    suspend fun findSpreadsheets(query: String, limit: Int = 10): ToolResult = withContext(Dispatchers.IO) {
        val token = getSheetsToken()
            ?: return@withContext ToolResult.failure("Google account not connected.")

        try {
            val q = buildString {
                append("mimeType = 'application/vnd.google-apps.spreadsheet'")
                append(" and trashed = false")
                if (query.isNotBlank()) {
                    append(" and name contains '${query.replace("'", "\\'")}'")
                }
            }

            val cap = limit.coerceIn(1, 50)
            val url = "$DRIVE_API?q=${java.net.URLEncoder.encode(q, "UTF-8")}" +
                "&pageSize=$cap" +
                "&fields=files(id,name,modifiedTime,webViewLink)" +
                "&orderBy=modifiedTime desc"

            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val err = response.body?.string() ?: ""
                return@withContext ToolResult.failure(
                    "Drive search failed (HTTP ${response.code}): $err"
                )
            }

            val body = JSONObject(response.body?.string() ?: "{}")
            val files = body.optJSONArray("files") ?: JSONArray()

            if (files.length() == 0) {
                return@withContext ToolResult.success(
                    "No spreadsheets found${if (query.isNotBlank()) " matching '$query'" else ""} in Google Drive."
                )
            }

            val result = StringBuilder()
            result.append("Found ${files.length()} spreadsheet(s):\n\n")
            for (i in 0 until files.length()) {
                val file = files.getJSONObject(i)
                result.append("${i + 1}. **${file.optString("name", "Untitled")}**\n")
                result.append("   ID: ${file.optString("id")}\n")
                result.append("   Modified: ${file.optString("modifiedTime", "").take(10)}\n")
                result.append("   Link: ${file.optString("webViewLink")}\n\n")
            }
            result.append("Use the ID with sheets_read, sheets_write, or sheets_append.")

            ToolResult.success(result.toString().trim())

        } catch (e: Exception) {
            ToolResult.failure("Drive search failed: ${e.message}")
        }
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    /**
     * Get OAuth2 token via GoogleAuthManager — single source of truth.
     * All scopes (Docs, Drive, Gmail, Sheets) are bundled in one token;
     * they're requested during sign-in so no extra consent is needed here.
     */
    private suspend fun getSheetsToken(): String? = authManager.getAccessToken()
}
