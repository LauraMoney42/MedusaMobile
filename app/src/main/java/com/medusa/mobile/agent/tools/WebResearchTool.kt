package com.medusa.mobile.agent.tools

// mm-021 — Web Research Tool for Medusa Mobile.
//
// Enables Claude to search the web and fetch page content for general knowledge
// queries. Handles two operations:
//   1. web_search  — Google search via scraping, returns top results with snippets
//   2. web_fetch   — Fetch a URL and extract readable text content
//
// This is essential for tool CHAINING — Claude can search for info, fetch details,
// then combine with on-device data (calendar, contacts, etc.) in a single turn.
//
// No API key needed — uses Google's public search page + OkHttp for fetching.
// INTERNET permission already declared in manifest.

import com.medusa.mobile.models.ToolResult
import com.medusa.mobile.models.WebSearchResultDTO
import com.medusa.mobile.models.WebSearchListDTO
import com.medusa.mobile.models.WebFetchDTO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * mm-021 — Web Research Tool.
 *
 * Gives Claude the ability to search the web and read web pages.
 * No special permissions beyond INTERNET (already granted).
 *
 * Claude tool names: "web_search", "web_fetch"
 */
class WebResearchTool {

    // ── Claude Tool Definitions ──────────────────────────────────────────────

    companion object {
        val claudeToolDefinitions: List<Map<String, Any>> = listOf(
            // 1. web_search — Google search
            mapOf(
                "name" to "web_search",
                "description" to """
                    Search the web using Google. Returns top results with title, URL, and
                    snippet for each. Use this when the user asks a general knowledge question,
                    wants current information, or needs to look something up that isn't on
                    their device. Examples: "what's the weather in NYC?", "latest news about
                    Tesla", "best Italian restaurants near me", "how to tie a bowline knot".
                    Follow up with web_fetch to read a specific page in detail.
                """.trimIndent(),
                "input_schema" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "query" to mapOf(
                            "type" to "string",
                            "description" to "The search query. Be specific for better results."
                        ),
                        "num_results" to mapOf(
                            "type" to "integer",
                            "description" to "Number of results to return. Default 5, max 10.",
                            "default" to 5
                        )
                    ),
                    "required" to listOf("query")
                )
            ),
            // 2. web_fetch — fetch and extract page content
            mapOf(
                "name" to "web_fetch",
                "description" to """
                    Fetch a web page and extract its readable text content. Use this after
                    web_search to read a specific result in detail, or when the user provides
                    a URL directly. Returns the page title and extracted body text (HTML tags
                    stripped). Useful for reading articles, documentation, recipes, etc.
                    Max content: 8000 characters (truncated if longer).
                """.trimIndent(),
                "input_schema" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "url" to mapOf(
                            "type" to "string",
                            "description" to "The full URL to fetch. Must start with http:// or https://."
                        ),
                        "max_length" to mapOf(
                            "type" to "integer",
                            "description" to "Max characters of body text to return. Default 8000.",
                            "default" to 8000
                        )
                    ),
                    "required" to listOf("url")
                )
            )
        )
    }

    // ── HTTP Client ──────────────────────────────────────────────────────────

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    // Mobile user agent — many sites return simpler/cleaner HTML for mobile
    private val userAgent = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    // ── web_search ───────────────────────────────────────────────────────────

    /**
     * Performs a Google search and extracts top results.
     * Uses Google's public search page — no API key needed.
     */
    suspend fun search(query: String, numResults: Int = 5): ToolResult = withContext(Dispatchers.IO) {
        val cap = numResults.coerceIn(1, 10)

        if (query.isBlank()) {
            return@withContext ToolResult.failure("Search query cannot be empty.")
        }

        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val searchUrl = "https://www.google.com/search?q=$encoded&num=$cap&hl=en"

            val request = Request.Builder()
                .url(searchUrl)
                .addHeader("User-Agent", userAgent)
                .addHeader("Accept", "text/html")
                .addHeader("Accept-Language", "en-US,en;q=0.9")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext ToolResult.failure("Search failed: HTTP ${response.code}")
            }

            val html = response.body?.string() ?: ""
            val results = parseGoogleResults(html, cap)

            if (results.isEmpty()) {
                return@withContext ToolResult.success("No results found for \"$query\".")
            }

            val summary = "${results.size} result(s) for \"$query\". Top: ${results.first().title}"
            ToolResult.success(
                summary = summary,
                data = WebSearchListDTO(
                    query = query,
                    count = results.size,
                    results = results
                )
            )
        } catch (e: Exception) {
            ToolResult.failure("Search error: ${e.message}")
        }
    }

    // ── web_fetch ────────────────────────────────────────────────────────────

    /**
     * Fetches a URL and extracts readable text content.
     * Strips HTML tags, scripts, styles, and returns clean text.
     */
    suspend fun fetch(url: String, maxLength: Int = 8000): ToolResult = withContext(Dispatchers.IO) {
        if (url.isBlank()) {
            return@withContext ToolResult.failure("URL cannot be empty.")
        }

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return@withContext ToolResult.failure("URL must start with http:// or https://")
        }

        try {
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", userAgent)
                .addHeader("Accept", "text/html,application/xhtml+xml,text/plain")
                .addHeader("Accept-Language", "en-US,en;q=0.9")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext ToolResult.failure("Fetch failed: HTTP ${response.code}")
            }

            val contentType = response.header("Content-Type") ?: ""
            val html = response.body?.string() ?: ""

            // Extract title
            val title = extractTitle(html)

            // Extract readable text
            val text = if (contentType.contains("text/plain")) {
                html.take(maxLength)
            } else {
                extractReadableText(html, maxLength)
            }

            if (text.isBlank()) {
                return@withContext ToolResult.success("Page loaded but no readable text content found at $url")
            }

            val cap = maxLength.coerceIn(500, 15000)
            val truncated = text.length > cap
            val content = if (truncated) text.take(cap) + "\n\n[... truncated at $cap chars]" else text

            ToolResult.success(
                summary = "Fetched: ${title ?: url} (${content.length} chars)",
                data = WebFetchDTO(
                    url = url,
                    title = title,
                    content = content,
                    truncated = truncated,
                    contentLength = text.length
                )
            )
        } catch (e: Exception) {
            ToolResult.failure("Fetch error: ${e.message}")
        }
    }

    // ── HTML Parsing Helpers ─────────────────────────────────────────────────

    /**
     * Parse Google search results from HTML.
     * Extracts title, URL, and snippet from each result block.
     *
     * Note: This is fragile — Google changes their HTML frequently.
     * But for an Android tool it's acceptable; we can update patterns as needed.
     */
    private fun parseGoogleResults(html: String, limit: Int): List<WebSearchResultDTO> {
        val results = mutableListOf<WebSearchResultDTO>()

        // Google wraps each result in <div class="g"> or similar
        // We look for <a href="/url?q=..."> patterns which contain the actual URLs
        // and extract surrounding text for title and snippet

        // Pattern 1: Extract URLs from Google's redirect links
        val urlPattern = Pattern.compile("""<a[^>]+href="/url\?q=([^"&]+)[^"]*"[^>]*>""")
        val urlMatcher = urlPattern.matcher(html)

        val urls = mutableListOf<String>()
        while (urlMatcher.find() && urls.size < limit * 2) {
            val rawUrl = urlMatcher.group(1) ?: continue
            val decoded = java.net.URLDecoder.decode(rawUrl, "UTF-8")
            // Skip Google's own pages and ads
            if (decoded.startsWith("http") &&
                !decoded.contains("google.com/") &&
                !decoded.contains("accounts.google") &&
                !decoded.contains("support.google") &&
                !decoded.contains("maps.google")) {
                if (decoded !in urls) urls.add(decoded)
            }
        }

        // Pattern 2: Extract titles — text inside <h3> tags
        val titlePattern = Pattern.compile("""<h3[^>]*>(.*?)</h3>""", Pattern.DOTALL)
        val titleMatcher = titlePattern.matcher(html)
        val titles = mutableListOf<String>()
        while (titleMatcher.find()) {
            val rawTitle = titleMatcher.group(1) ?: continue
            titles.add(stripHtmlTags(rawTitle).trim())
        }

        // Pattern 3: Extract snippets — text in <span> after result blocks
        // Google uses various class names; we look for longer text spans near results
        val snippetPattern = Pattern.compile(
            """<span[^>]*>([^<]{40,300})</span>""",
            Pattern.DOTALL
        )
        val snippetMatcher = snippetPattern.matcher(html)
        val snippets = mutableListOf<String>()
        while (snippetMatcher.find()) {
            val rawSnippet = snippetMatcher.group(1) ?: continue
            val cleaned = stripHtmlTags(rawSnippet).trim()
            if (cleaned.length > 30 && !cleaned.contains("function(") && !cleaned.contains("{")) {
                snippets.add(cleaned)
            }
        }

        // Combine: zip URLs with titles and snippets
        val count = minOf(urls.size, limit)
        for (i in 0 until count) {
            results.add(WebSearchResultDTO(
                position = i + 1,
                title = titles.getOrElse(i) { "Result ${i + 1}" },
                url = urls[i],
                snippet = snippets.getOrElse(i) { "" }
            ))
        }

        return results
    }

    /** Extract <title> content from HTML. */
    private fun extractTitle(html: String): String? {
        val pattern = Pattern.compile("""<title[^>]*>(.*?)</title>""", Pattern.DOTALL or Pattern.CASE_INSENSITIVE)
        val matcher = pattern.matcher(html)
        return if (matcher.find()) {
            stripHtmlTags(matcher.group(1) ?: "").trim().ifBlank { null }
        } else null
    }

    /**
     * Extract readable text from HTML.
     * Removes scripts, styles, nav, footer, then strips remaining tags.
     */
    private fun extractReadableText(html: String, maxLength: Int): String {
        var text = html

        // Remove scripts, styles, and non-content elements
        val removePatterns = listOf(
            """<script[^>]*>.*?</script>""",
            """<style[^>]*>.*?</style>""",
            """<nav[^>]*>.*?</nav>""",
            """<footer[^>]*>.*?</footer>""",
            """<header[^>]*>.*?</header>""",
            """<aside[^>]*>.*?</aside>""",
            """<!--.*?-->""",
            """<noscript[^>]*>.*?</noscript>""",
        )

        for (pattern in removePatterns) {
            text = Pattern.compile(pattern, Pattern.DOTALL or Pattern.CASE_INSENSITIVE)
                .matcher(text)
                .replaceAll(" ")
        }

        // Convert block elements to newlines for readability
        text = text.replace(Regex("""<(p|div|br|h[1-6]|li|tr)[^>]*>""", RegexOption.IGNORE_CASE), "\n")

        // Strip all remaining HTML tags
        text = stripHtmlTags(text)

        // Decode common HTML entities
        text = text.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .replace("&#x27;", "'")
            .replace("&#x2F;", "/")

        // Collapse whitespace
        text = text.replace(Regex("""[ \t]+"""), " ")
        text = text.replace(Regex("""\n{3,}"""), "\n\n")
        text = text.trim()

        return text.take(maxLength)
    }

    /** Strip all HTML tags from a string. */
    private fun stripHtmlTags(html: String): String {
        return html.replace(Regex("""<[^>]+>"""), "")
    }
}
