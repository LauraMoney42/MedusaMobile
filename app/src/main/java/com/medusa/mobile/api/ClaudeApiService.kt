package com.medusa.mobile.api

// mm-005 — Claude API Service
//
// Core AI engine for Medusa Mobile. Handles:
//   - Streaming messages via Anthropic Messages API (SSE)
//   - Tool use (function calling) with structured JSON + agentic loop
//   - Conversation history management
//   - System prompt injection (memory, device context, tools)
//   - EncryptedSharedPreferences for secure API key storage
//
// Design: Kotlin coroutines + OkHttp SSE for streaming.
// No Anthropic SDK dependency — raw HTTP for full control over streaming + tool loops.
//
// Agentic loop: When Claude responds with stop_reason="tool_use", the caller
// executes tools, appends tool_result messages, and calls Claude again. This
// repeats until stop_reason="end_turn" (max 10 iterations for safety).

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// ── Data Models ─────────────────────────────────────────────────────────────

/** Role in a conversation message. */
enum class MessageRole(val value: String) {
    USER("user"),
    ASSISTANT("assistant");
}

/** A single content block in a message. */
sealed class ContentBlock {
    data class Text(val text: String) : ContentBlock()
    data class ToolUse(val id: String, val name: String, val input: JSONObject) : ContentBlock()
    data class ToolResult(val toolUseId: String, val content: String, val isError: Boolean = false) : ContentBlock()
}

/** A message in the conversation history. */
data class ConversationMessage(
    val role: MessageRole,
    val content: List<ContentBlock>
) {
    /** Convenience for single text messages. */
    constructor(role: MessageRole, text: String) : this(role, listOf(ContentBlock.Text(text)))
}

/** Tool definition for Claude function calling. */
data class ToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JSONObject
)

/** Streamed events from Claude API. */
sealed class StreamEvent {
    /** Partial text delta — append to current assistant message. */
    data class TextDelta(val text: String) : StreamEvent()

    /** Claude wants to call a tool. */
    data class ToolCall(val id: String, val name: String, val input: JSONObject) : StreamEvent()

    /** Stream completed — final stop reason. */
    data class Done(val stopReason: String) : StreamEvent()

    /** Error during streaming. */
    data class Error(val message: String, val isRetryable: Boolean = false) : StreamEvent()

    /** Usage stats for the response. */
    data class Usage(val inputTokens: Int, val outputTokens: Int) : StreamEvent()
}

// ── Claude API Service ──────────────────────────────────────────────────────

/**
 * Streams messages to/from the Anthropic Claude API.
 *
 * Usage:
 * ```kotlin
 * val service = ClaudeApiService(apiKey = "sk-ant-...")
 * service.streamMessage(
 *     messages = conversationHistory,
 *     systemPrompt = "You are Medusa Mobile...",
 *     tools = registeredTools
 * ).collect { event ->
 *     when (event) {
 *         is StreamEvent.TextDelta -> appendToUI(event.text)
 *         is StreamEvent.ToolCall -> executeToolAndContinue(event)
 *         is StreamEvent.Done -> markComplete()
 *         is StreamEvent.Error -> showError(event.message)
 *     }
 * }
 * ```
 */
class ClaudeApiService(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val maxTokens: Int = DEFAULT_MAX_TOKENS
) {

    companion object {
        private const val API_URL = "https://api.anthropic.com/v1/messages"
        private const val API_VERSION = "2023-06-01"
        private const val DEFAULT_MODEL = "claude-sonnet-4-20250514"
        private const val DEFAULT_MAX_TOKENS = 4096
        private const val CONNECT_TIMEOUT_SECONDS = 15L
        private const val READ_TIMEOUT_SECONDS = 120L  // SSE streams can be long
        private const val MAX_TOOL_ITERATIONS = 10      // Safety cap for agentic loop
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Streams a message to Claude and emits [StreamEvent]s as they arrive.
     *
     * @param messages  Full conversation history (user + assistant turns).
     * @param systemPrompt  System prompt with persona, tools context, memory.
     * @param tools  Available tool definitions for function calling.
     * @return Flow of [StreamEvent] — collect in a coroutine scope.
     */
    fun streamMessage(
        messages: List<ConversationMessage>,
        systemPrompt: String,
        tools: List<ToolDefinition> = emptyList()
    ): Flow<StreamEvent> = callbackFlow {

        val requestBody = buildRequestJson(messages, systemPrompt, tools)

        val request = Request.Builder()
            .url(API_URL)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", API_VERSION)
            .addHeader("content-type", "application/json")
            .post(requestBody.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        // State for accumulating tool_use blocks across SSE events
        var currentToolId: String? = null
        var currentToolName: String? = null
        val toolInputBuffer = StringBuilder()

        val eventSourceFactory = EventSources.createFactory(httpClient)
        val eventSource = eventSourceFactory.newEventSource(request, object : EventSourceListener() {

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") return

                try {
                    val json = JSONObject(data)
                    val eventType = json.optString("type", "")

                    when (eventType) {
                        // ── Content block started ───────────────────────────
                        "content_block_start" -> {
                            val block = json.optJSONObject("content_block") ?: return
                            if (block.optString("type") == "tool_use") {
                                currentToolId = block.optString("id")
                                currentToolName = block.optString("name")
                                toolInputBuffer.clear()
                            }
                        }

                        // ── Text delta ──────────────────────────────────────
                        "content_block_delta" -> {
                            val delta = json.optJSONObject("delta") ?: return
                            when (delta.optString("type")) {
                                "text_delta" -> {
                                    val text = delta.optString("text", "")
                                    if (text.isNotEmpty()) {
                                        trySend(StreamEvent.TextDelta(text))
                                    }
                                }
                                "input_json_delta" -> {
                                    // Accumulate partial JSON for tool input
                                    toolInputBuffer.append(delta.optString("partial_json", ""))
                                }
                            }
                        }

                        // ── Content block finished ──────────────────────────
                        "content_block_stop" -> {
                            // If we were accumulating a tool_use block, emit it now
                            if (currentToolId != null && currentToolName != null) {
                                val inputJson = try {
                                    JSONObject(toolInputBuffer.toString())
                                } catch (_: Exception) {
                                    JSONObject()
                                }
                                trySend(StreamEvent.ToolCall(
                                    id = currentToolId!!,
                                    name = currentToolName!!,
                                    input = inputJson
                                ))
                                currentToolId = null
                                currentToolName = null
                                toolInputBuffer.clear()
                            }
                        }

                        // ── Message complete ────────────────────────────────
                        "message_delta" -> {
                            val delta = json.optJSONObject("delta") ?: return
                            val stopReason = delta.optString("stop_reason", "")
                            if (stopReason.isNotEmpty()) {
                                trySend(StreamEvent.Done(stopReason))
                            }
                            // Usage in message_delta
                            val usage = json.optJSONObject("usage")
                            if (usage != null) {
                                trySend(StreamEvent.Usage(
                                    inputTokens = usage.optInt("input_tokens", 0),
                                    outputTokens = usage.optInt("output_tokens", 0)
                                ))
                            }
                        }

                        // ── Message start (contains usage) ──────────────────
                        "message_start" -> {
                            val message = json.optJSONObject("message") ?: return
                            val usage = message.optJSONObject("usage")
                            if (usage != null) {
                                trySend(StreamEvent.Usage(
                                    inputTokens = usage.optInt("input_tokens", 0),
                                    outputTokens = usage.optInt("output_tokens", 0)
                                ))
                            }
                        }

                        // ── Error ───────────────────────────────────────────
                        "error" -> {
                            val error = json.optJSONObject("error")
                            val msg = error?.optString("message") ?: "Unknown API error"
                            val type = error?.optString("type") ?: ""
                            val retryable = type in listOf("overloaded_error", "rate_limit_error")
                            trySend(StreamEvent.Error(msg, retryable))
                        }
                    }
                } catch (e: Exception) {
                    trySend(StreamEvent.Error("Parse error: ${e.message}"))
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val code = response?.code ?: 0
                val msg = when {
                    code == 401 -> "Invalid API key. Check your Claude API key in Settings."
                    code == 429 -> "Rate limited. Please wait a moment."
                    code == 529 -> "Claude is overloaded. Retrying..."
                    t != null   -> "Network error: ${t.message}"
                    else        -> "HTTP $code error"
                }
                val retryable = code in listOf(429, 529, 500, 502, 503)
                trySend(StreamEvent.Error(msg, retryable))
                close()
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }
        })

        awaitClose {
            eventSource.cancel()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Non-streaming single-shot message. Returns the full response.
     * Useful for tool result processing where streaming isn't needed.
     */
    suspend fun sendMessage(
        messages: List<ConversationMessage>,
        systemPrompt: String,
        tools: List<ToolDefinition> = emptyList()
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            val requestBody = buildRequestJson(messages, systemPrompt, tools, stream = false)

            val request = Request.Builder()
                .url(API_URL)
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", API_VERSION)
                .addHeader("content-type", "application/json")
                .post(requestBody.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val body = response.body?.string() ?: ""
                return@withContext Result.failure(Exception("HTTP ${response.code}: $body"))
            }

            val body = response.body?.string() ?: "{}"
            Result.success(JSONObject(body))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Request Builder ─────────────────────────────────────────────────────

    private fun buildRequestJson(
        messages: List<ConversationMessage>,
        systemPrompt: String,
        tools: List<ToolDefinition>,
        stream: Boolean = true
    ): JSONObject {
        val json = JSONObject().apply {
            put("model", model)
            put("max_tokens", maxTokens)
            put("stream", stream)
            put("system", systemPrompt)
        }

        // Messages array
        val messagesArray = JSONArray()
        for (msg in messages) {
            val msgJson = JSONObject()
            msgJson.put("role", msg.role.value)

            val contentArray = JSONArray()
            for (block in msg.content) {
                when (block) {
                    is ContentBlock.Text -> {
                        contentArray.put(JSONObject().apply {
                            put("type", "text")
                            put("text", block.text)
                        })
                    }
                    is ContentBlock.ToolUse -> {
                        contentArray.put(JSONObject().apply {
                            put("type", "tool_use")
                            put("id", block.id)
                            put("name", block.name)
                            put("input", block.input)
                        })
                    }
                    is ContentBlock.ToolResult -> {
                        contentArray.put(JSONObject().apply {
                            put("type", "tool_result")
                            put("tool_use_id", block.toolUseId)
                            put("content", block.content)
                            if (block.isError) put("is_error", true)
                        })
                    }
                }
            }

            // Single text block can be a plain string (API accepts both)
            if (msg.content.size == 1 && msg.content[0] is ContentBlock.Text) {
                msgJson.put("content", (msg.content[0] as ContentBlock.Text).text)
            } else {
                msgJson.put("content", contentArray)
            }

            messagesArray.put(msgJson)
        }
        json.put("messages", messagesArray)

        // Tools
        if (tools.isNotEmpty()) {
            val toolsArray = JSONArray()
            for (tool in tools) {
                toolsArray.put(JSONObject().apply {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("input_schema", tool.inputSchema)
                })
            }
            json.put("tools", toolsArray)
        }

        return json
    }

    // ── Agentic Tool Loop ───────────────────────────────────────────────────

    /**
     * Callback interface for tool execution during agentic loop.
     * The ViewModel implements this to dispatch tool calls to the right tool handler.
     */
    fun interface ToolExecutor {
        /**
         * Execute a tool and return the result string.
         * @param name  Tool name (e.g. "get_notifications", "get_sms")
         * @param input Tool input JSON
         * @return Result string to send back to Claude as tool_result content
         */
        suspend fun execute(name: String, input: JSONObject): String
    }

    /**
     * Runs a full agentic conversation loop with streaming.
     *
     * Flow:
     * 1. Stream user message to Claude
     * 2. If Claude responds with tool_use → execute tools via [executor]
     * 3. Append tool_result messages to history
     * 4. Stream again with updated history
     * 5. Repeat until stop_reason="end_turn" or max iterations reached
     *
     * Emits [StreamEvent]s for ALL iterations — UI sees text + tool calls in real time.
     *
     * @param messages  Mutable conversation history (will be appended to)
     * @param systemPrompt System prompt
     * @param tools  Available tool definitions
     * @param executor  Callback to execute tool calls
     * @param onTextDelta  Called for each text chunk (for streaming UI)
     * @param onToolCall  Called when Claude wants to call a tool (for UI chips)
     * @param onDone  Called when conversation turn is complete
     * @param onError  Called on error
     */
    suspend fun runAgenticLoop(
        messages: MutableList<ConversationMessage>,
        systemPrompt: String,
        tools: List<ToolDefinition>,
        executor: ToolExecutor,
        onTextDelta: (String) -> Unit = {},
        onToolCall: (StreamEvent.ToolCall) -> Unit = {},
        onDone: () -> Unit = {},
        onError: (StreamEvent.Error) -> Unit = {}
    ) {
        var iteration = 0

        while (iteration < MAX_TOOL_ITERATIONS) {
            iteration++

            // Collect the full response from one streaming call
            val pendingToolCalls = mutableListOf<StreamEvent.ToolCall>()
            val textBuffer = StringBuilder()
            var stopReason = "end_turn"
            var hadError = false

            streamMessage(messages, systemPrompt, tools).collect { event ->
                when (event) {
                    is StreamEvent.TextDelta -> {
                        textBuffer.append(event.text)
                        onTextDelta(event.text)
                    }
                    is StreamEvent.ToolCall -> {
                        pendingToolCalls.add(event)
                        onToolCall(event)
                    }
                    is StreamEvent.Done -> {
                        stopReason = event.stopReason
                    }
                    is StreamEvent.Error -> {
                        onError(event)
                        hadError = true
                    }
                    is StreamEvent.Usage -> { /* tracked by caller if needed */ }
                }
            }

            if (hadError) return

            // Build the assistant message from this turn
            val assistantBlocks = mutableListOf<ContentBlock>()
            if (textBuffer.isNotEmpty()) {
                assistantBlocks.add(ContentBlock.Text(textBuffer.toString()))
            }
            for (tc in pendingToolCalls) {
                assistantBlocks.add(ContentBlock.ToolUse(tc.id, tc.name, tc.input))
            }
            if (assistantBlocks.isNotEmpty()) {
                messages.add(ConversationMessage(MessageRole.ASSISTANT, assistantBlocks))
            }

            // If no tool calls, we're done — Claude finished its turn
            if (stopReason != "tool_use" || pendingToolCalls.isEmpty()) {
                onDone()
                return
            }

            // Execute all tool calls and build tool_result message
            val toolResultBlocks = mutableListOf<ContentBlock>()
            for (tc in pendingToolCalls) {
                val result = try {
                    executor.execute(tc.name, tc.input)
                } catch (e: Exception) {
                    """{"error": "Tool execution failed: ${e.message}"}"""
                }
                toolResultBlocks.add(ContentBlock.ToolResult(
                    toolUseId = tc.id,
                    content = result,
                    isError = result.contains("\"error\"")
                ))
            }

            // Append tool results as a user message (Anthropic API convention)
            messages.add(ConversationMessage(MessageRole.USER, toolResultBlocks))

            // Loop continues — next iteration streams with tool results appended
        }

        // Safety cap reached
        onError(StreamEvent.Error(
            "Tool loop exceeded $MAX_TOOL_ITERATIONS iterations. Stopping for safety.",
            isRetryable = false
        ))
    }
}

// ── Secure API Key Storage ──────────────────────────────────────────────────

/**
 * EncryptedSharedPreferences wrapper for secure API key storage.
 *
 * Uses Android Keystore-backed AES-256 encryption. The key never appears in
 * plaintext on disk — even with root access, the key is protected by the
 * hardware-backed Keystore (Titan M / StrongBox on Pixel, TrustZone on others).
 *
 * Usage:
 * ```kotlin
 * // Save
 * ApiKeyStore.saveApiKey(context, "sk-ant-api03-...")
 *
 * // Read
 * val key = ApiKeyStore.getApiKey(context)  // null if not set
 *
 * // Delete
 * ApiKeyStore.clearApiKey(context)
 * ```
 */
object ApiKeyStore {

    private const val PREFS_FILE = "medusa_secure_prefs"
    private const val KEY_API_KEY = "claude_api_key"
    private const val KEY_MODEL = "claude_model"

    private fun getEncryptedPrefs(context: Context): android.content.SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // ── API Key ─────────────────────────────────────────────────────────

    /** Save Claude API key securely. */
    fun saveApiKey(context: Context, apiKey: String) {
        getEncryptedPrefs(context).edit().putString(KEY_API_KEY, apiKey).apply()
    }

    /** Retrieve Claude API key. Returns null if not set. */
    fun getApiKey(context: Context): String? {
        return getEncryptedPrefs(context).getString(KEY_API_KEY, null)
    }

    /** Delete stored API key. */
    fun clearApiKey(context: Context) {
        getEncryptedPrefs(context).edit().remove(KEY_API_KEY).apply()
    }

    /** Check if an API key is stored. */
    fun hasApiKey(context: Context): Boolean {
        return getApiKey(context) != null
    }

    // ── Model Selection ─────────────────────────────────────────────────

    /** Save preferred Claude model (e.g. "claude-sonnet-4-20250514"). */
    fun saveModel(context: Context, model: String) {
        getEncryptedPrefs(context).edit().putString(KEY_MODEL, model).apply()
    }

    /** Get preferred model, or null for default. */
    fun getModel(context: Context): String? {
        return getEncryptedPrefs(context).getString(KEY_MODEL, null)
    }

    // ── Factory ─────────────────────────────────────────────────────────

    /**
     * Create a [ClaudeApiService] from stored credentials.
     * Returns null if no API key is stored.
     */
    fun createService(context: Context): ClaudeApiService? {
        val apiKey = getApiKey(context) ?: return null
        val model = getModel(context)
        return if (model != null) {
            ClaudeApiService(apiKey = apiKey, model = model)
        } else {
            ClaudeApiService(apiKey = apiKey)
        }
    }
}
