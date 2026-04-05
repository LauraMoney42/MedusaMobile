package com.medusa.mobile.agent.tools

// mm-019 — Google Docs + Drive Tool for Medusa Mobile
//
// Enables Claude to create/edit/share Google Docs and list/search/upload to Drive.
// Uses Google REST APIs via OkHttp (already in deps) with OAuth2 tokens from
// GoogleAuthManager.
//
// Tool names:
//   - google_docs_create   → create a new Google Doc with content
//   - google_docs_edit     → append/replace content in an existing doc
//   - google_docs_share    → share a doc with another email
//   - google_drive_search  → search Drive files by name/type/content
//   - google_drive_list    → list recent files in Drive
//
// All operations are crash-safe — errors return ToolResult.failure() and never
// kill the agentic loop.

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
 * Google Docs + Drive tool — create docs, edit content, search files.
 *
 * "Create a Google Doc that says X" works end-to-end:
 *   1. Claude calls google_docs_create(title, content)
 *   2. This tool gets OAuth2 token from GoogleAuthManager
 *   3. Creates doc via Docs API, inserts content
 *   4. Returns doc URL for Claude to share with user
 */
class GoogleDocsDriveTool(
    private val context: Context,
    private val authManager: GoogleAuthManager
) {

    companion object {
        private const val DOCS_API = "https://docs.googleapis.com/v1/documents"
        private const val DRIVE_API = "https://www.googleapis.com/drive/v3/files"
        private const val DRIVE_UPLOAD_API = "https://www.googleapis.com/upload/drive/v3/files"
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // ── google_docs_create ──────────────────────────────────────────────

    /**
     * Create a new Google Doc with the given title and body content.
     * Returns the document URL.
     */
    suspend fun createDoc(title: String, content: String): ToolResult = withContext(Dispatchers.IO) {
        val token = authManager.getValidToken()
            ?: return@withContext ToolResult.failure(
                "Google account not connected. A sign-in prompt has been shown — please approve access and try again."
            )

        try {
            // Step 1: Create an empty document with the title
            val createBody = JSONObject().apply {
                put("title", title)
            }

            val createRequest = Request.Builder()
                .url(DOCS_API)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .post(createBody.toString().toRequestBody(JSON_TYPE))
                .build()

            val createResponse = httpClient.newCall(createRequest).execute()
            if (!createResponse.isSuccessful) {
                val errorBody = createResponse.body?.string() ?: ""
                return@withContext ToolResult.failure(
                    "Failed to create Google Doc (HTTP ${createResponse.code}): $errorBody"
                )
            }

            val docJson = JSONObject(createResponse.body?.string() ?: "{}")
            val documentId = docJson.optString("documentId", "")
            if (documentId.isEmpty()) {
                return@withContext ToolResult.failure("Created doc but no documentId returned.")
            }

            // Step 2: Insert content into the document
            if (content.isNotBlank()) {
                val insertResult = insertText(token, documentId, content)
                if (!insertResult) {
                    // Doc was created but content insertion failed — still return the URL
                    return@withContext ToolResult.success(
                        "Created Google Doc '$title' but failed to insert content. " +
                        "URL: https://docs.google.com/document/d/$documentId/edit"
                    )
                }
            }

            val docUrl = "https://docs.google.com/document/d/$documentId/edit"
            ToolResult.success(
                "Created Google Doc '$title'. URL: $docUrl"
            )
        } catch (e: Exception) {
            ToolResult.failure("Failed to create Google Doc: ${e.message}")
        }
    }

    // ── google_docs_edit ────────────────────────────────────────────────

    /**
     * Edit an existing Google Doc — append or replace content.
     */
    suspend fun editDoc(
        documentId: String,
        content: String,
        mode: String = "append"  // "append" or "replace"
    ): ToolResult = withContext(Dispatchers.IO) {
        val token = authManager.getValidToken()
            ?: return@withContext ToolResult.failure(
                "Google account not connected. A sign-in prompt has been shown — please approve access and try again."
            )

        try {
            if (mode == "replace") {
                // For replace: get current doc content length, delete all, then insert
                val docInfo = getDocInfo(token, documentId)
                if (docInfo == null) {
                    return@withContext ToolResult.failure("Could not read document $documentId.")
                }

                val endIndex = docInfo.optJSONObject("body")
                    ?.optJSONArray("content")
                    ?.let { contentArray ->
                        var maxEnd = 1
                        for (i in 0 until contentArray.length()) {
                            val elem = contentArray.getJSONObject(i)
                            val ei = elem.optInt("endIndex", 0)
                            if (ei > maxEnd) maxEnd = ei
                        }
                        maxEnd
                    } ?: 1

                // Delete existing content (if any beyond the initial newline)
                if (endIndex > 2) {
                    val deleteRequests = JSONArray().put(JSONObject().apply {
                        put("deleteContentRange", JSONObject().apply {
                            put("range", JSONObject().apply {
                                put("startIndex", 1)
                                put("endIndex", endIndex - 1)
                            })
                        })
                    })

                    val deleteBody = JSONObject().apply {
                        put("requests", deleteRequests)
                    }

                    val deleteRequest = Request.Builder()
                        .url("$DOCS_API/$documentId:batchUpdate")
                        .addHeader("Authorization", "Bearer $token")
                        .post(deleteBody.toString().toRequestBody(JSON_TYPE))
                        .build()

                    httpClient.newCall(deleteRequest).execute()
                }
            }

            // Insert new content
            val success = insertText(token, documentId, content)
            if (!success) {
                return@withContext ToolResult.failure("Failed to insert content into document.")
            }

            val docUrl = "https://docs.google.com/document/d/$documentId/edit"
            ToolResult.success(
                "Updated Google Doc. Mode: $mode. URL: $docUrl"
            )
        } catch (e: Exception) {
            ToolResult.failure("Failed to edit Google Doc: ${e.message}")
        }
    }

    // ── google_docs_share ───────────────────────────────────────────────

    /**
     * Share a Google Doc/Drive file with another user via email.
     */
    suspend fun shareDoc(
        fileId: String,
        email: String,
        role: String = "writer"  // "reader", "writer", "commenter"
    ): ToolResult = withContext(Dispatchers.IO) {
        val token = authManager.getValidToken()
            ?: return@withContext ToolResult.failure(
                "Google account not connected. A sign-in prompt has been shown — please approve access and try again."
            )

        try {
            val permBody = JSONObject().apply {
                put("type", "user")
                put("role", role)
                put("emailAddress", email)
            }

            val request = Request.Builder()
                .url("$DRIVE_API/$fileId/permissions?sendNotificationEmail=true")
                .addHeader("Authorization", "Bearer $token")
                .post(permBody.toString().toRequestBody(JSON_TYPE))
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: ""
                return@withContext ToolResult.failure(
                    "Failed to share document (HTTP ${response.code}): $errorBody"
                )
            }

            ToolResult.success("Shared document with $email as $role.")
        } catch (e: Exception) {
            ToolResult.failure("Failed to share document: ${e.message}")
        }
    }

    // ── google_drive_search ─────────────────────────────────────────────

    /**
     * Search Google Drive for files matching a query.
     */
    suspend fun searchDrive(
        query: String,
        fileType: String? = null,
        limit: Int = 10
    ): ToolResult = withContext(Dispatchers.IO) {
        val token = authManager.getValidToken()
            ?: return@withContext ToolResult.failure(
                "Google account not connected. A sign-in prompt has been shown — please approve access and try again."
            )

        try {
            // Build Drive API query string
            val queryParts = mutableListOf<String>()

            // Name search (fullText also searches content for Docs)
            if (query.isNotBlank()) {
                queryParts.add("fullText contains '${query.replace("'", "\\'")}'")
            }

            // File type filter
            when (fileType?.lowercase()) {
                "doc", "docs", "document" ->
                    queryParts.add("mimeType = 'application/vnd.google-apps.document'")
                "sheet", "sheets", "spreadsheet" ->
                    queryParts.add("mimeType = 'application/vnd.google-apps.spreadsheet'")
                "slide", "slides", "presentation" ->
                    queryParts.add("mimeType = 'application/vnd.google-apps.presentation'")
                "pdf" ->
                    queryParts.add("mimeType = 'application/pdf'")
                "folder" ->
                    queryParts.add("mimeType = 'application/vnd.google-apps.folder'")
                "image" ->
                    queryParts.add("mimeType contains 'image/'")
            }

            // Exclude trashed files
            queryParts.add("trashed = false")

            val q = queryParts.joinToString(" and ")
            val cap = limit.coerceIn(1, 50)

            val url = "$DRIVE_API?q=${java.net.URLEncoder.encode(q, "UTF-8")}" +
                "&pageSize=$cap" +
                "&fields=files(id,name,mimeType,modifiedTime,webViewLink,size,owners)" +
                "&orderBy=modifiedTime desc"

            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: ""
                return@withContext ToolResult.failure(
                    "Drive search failed (HTTP ${response.code}): $errorBody"
                )
            }

            val body = JSONObject(response.body?.string() ?: "{}")
            val files = body.optJSONArray("files") ?: JSONArray()

            if (files.length() == 0) {
                return@withContext ToolResult.success(
                    "No files found matching '$query' in Google Drive."
                )
            }

            // Format results
            val results = StringBuilder()
            results.append("Found ${files.length()} file(s) in Google Drive:\n\n")
            for (i in 0 until files.length()) {
                val file = files.getJSONObject(i)
                val name = file.optString("name", "Untitled")
                val link = file.optString("webViewLink", "")
                val modified = file.optString("modifiedTime", "")
                val mime = file.optString("mimeType", "")
                val typeLabel = mimeToLabel(mime)

                results.append("${i + 1}. **$name** ($typeLabel)\n")
                if (modified.isNotEmpty()) {
                    results.append("   Modified: ${modified.take(10)}\n")
                }
                if (link.isNotEmpty()) {
                    results.append("   Link: $link\n")
                }
                results.append("   ID: ${file.optString("id")}\n\n")
            }

            ToolResult.success(results.toString().trim())
        } catch (e: Exception) {
            ToolResult.failure("Drive search failed: ${e.message}")
        }
    }

    // ── google_drive_list ───────────────────────────────────────────────

    /**
     * List recent files in Google Drive.
     */
    suspend fun listRecentFiles(limit: Int = 10): ToolResult {
        return searchDrive(query = "", limit = limit)
    }

    // ── Internal Helpers ────────────────────────────────────────────────

    /**
     * Insert text at the beginning of a Google Doc (after the initial \n).
     */
    private fun insertText(token: String, documentId: String, text: String): Boolean {
        val requests = JSONArray().put(JSONObject().apply {
            put("insertText", JSONObject().apply {
                put("location", JSONObject().apply {
                    put("index", 1)  // After the mandatory initial newline
                })
                put("text", text)
            })
        })

        val body = JSONObject().apply {
            put("requests", requests)
        }

        val request = Request.Builder()
            .url("$DOCS_API/$documentId:batchUpdate")
            .addHeader("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()

        val response = httpClient.newCall(request).execute()
        return response.isSuccessful
    }

    /**
     * Get document metadata + content structure.
     */
    private fun getDocInfo(token: String, documentId: String): JSONObject? {
        val request = Request.Builder()
            .url("$DOCS_API/$documentId")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) return null

        return try {
            JSONObject(response.body?.string() ?: "{}")
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Convert MIME type to human-readable label.
     */
    private fun mimeToLabel(mime: String): String = when {
        mime.contains("document") -> "Google Doc"
        mime.contains("spreadsheet") -> "Google Sheet"
        mime.contains("presentation") -> "Google Slides"
        mime.contains("folder") -> "Folder"
        mime.contains("pdf") -> "PDF"
        mime.contains("image") -> "Image"
        mime.contains("video") -> "Video"
        mime.contains("audio") -> "Audio"
        else -> "File"
    }
}
