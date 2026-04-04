package com.medusa.mobile.agent

// AgentOrchestrator — The agentic loop that makes Medusa Mobile intelligent.
//
// This is the CRITICAL PATH component. Without it, nothing works.
// It drives the full cycle:
//   1. User sends a message
//   2. We stream it to Claude with tool definitions
//   3. If Claude responds with tool_use → dispatch to ToolDispatcher → inject result → loop
//   4. If Claude responds with end_turn → emit final text → done
//   5. Repeat up to MAX_TOOL_ITERATIONS for safety
//
// Design decisions:
//   - Pure Kotlin coroutine pipeline — no Android dependencies (except Context via ToolDispatcher)
//   - Emits AgentEvent flow so the ViewModel can observe without coupling
//   - Conversation history managed HERE, not in ViewModel (single source of truth)
//   - System prompt assembled HERE with device context and tool descriptions
//   - Testable in isolation by mocking ClaudeApiService + ToolDispatcher

import android.os.Build
import com.medusa.mobile.api.ClaudeApiService
import com.medusa.mobile.api.ContentBlock
import com.medusa.mobile.api.ConversationMessage
import com.medusa.mobile.api.MessageRole
import com.medusa.mobile.api.StreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONObject

// ── Agent Events (emitted to ViewModel) ─────────────────────────────────────

/** Events emitted by the orchestrator for the UI to observe. */
sealed class AgentEvent {
    /** Partial text — append to current assistant message as it streams in. */
    data class TextDelta(val text: String) : AgentEvent()

    /** Assistant message is complete (all text received). */
    data class MessageComplete(val fullText: String) : AgentEvent()

    /** A tool is about to be executed — show a "thinking" chip in the UI. */
    data class ToolStarted(val toolName: String, val toolId: String) : AgentEvent()

    /** A tool finished executing — update the chip with result summary. */
    data class ToolFinished(val toolName: String, val toolId: String, val isError: Boolean) : AgentEvent()

    /** The entire agentic loop is done (end_turn or max iterations). */
    object Done : AgentEvent()

    /** An error occurred. */
    data class Error(val message: String, val isRetryable: Boolean = false) : AgentEvent()

    /** Token usage stats for this turn. */
    data class Usage(val inputTokens: Int, val outputTokens: Int) : AgentEvent()

    /** Agent is thinking / waiting for Claude (show thinking indicator). */
    object Thinking : AgentEvent()
}

// ── Agent Orchestrator ──────────────────────────────────────────────────────

/**
 * Drives the agentic loop: User → Claude → Tool → Claude → ... → Response.
 *
 * Usage:
 * ```kotlin
 * val orchestrator = AgentOrchestrator(apiService, toolDispatcher)
 * orchestrator.chat("Who texted me today?").collect { event ->
 *     when (event) {
 *         is AgentEvent.TextDelta -> appendToUI(event.text)
 *         is AgentEvent.ToolStarted -> showToolChip(event.toolName)
 *         is AgentEvent.Done -> markComplete()
 *         is AgentEvent.Error -> showError(event.message)
 *     }
 * }
 * ```
 */
class AgentOrchestrator(
    private val apiService: ClaudeApiService,
    private val toolDispatcher: ToolDispatcher
) {

    companion object {
        /** Safety cap — prevents infinite tool loops. */
        private const val MAX_TOOL_ITERATIONS = 10

        /** Max conversation turns to keep in history (user+assistant pairs). */
        private const val MAX_HISTORY_TURNS = 50
    }

    // ── Conversation History ────────────────────────────────────────────────

    /** Full conversation history — maintained across chat() calls. */
    private val conversationHistory = mutableListOf<ConversationMessage>()

    // ── System Prompt ───────────────────────────────────────────────────────

    private fun buildSystemPrompt(): String = """
        You are Medusa, an AI assistant running natively on the user's Android phone.
        You have direct access to their device — SMS messages, call history, notifications
        from ALL apps, and more. You are NOT a chatbot — you are a phone-native agent
        with real tools.

        Your personality:
        - Concise and helpful. No fluff.
        - When the user asks about their phone data, USE YOUR TOOLS. Don't guess.
        - Summarize results naturally — don't dump raw JSON. Be conversational.
        - If a tool needs a permission the user hasn't granted, explain what's needed.
        - You can call multiple tools in one response if needed.

        Device info:
        - Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
        - ${Build.MANUFACTURER} ${Build.MODEL}
        - Medusa Mobile v1.0

        Available tools: get_sms, send_sms, get_call_history, get_notifications, get_contacts,
        memory_remember, memory_recall, memory_forget,
        google_docs_create, google_docs_edit, google_docs_share, google_drive_search, google_drive_list,
        get_location, open_maps.

        MEMORY: You have persistent memory via memory_remember / memory_recall / memory_forget.
        - When the user says "remember X" or tells you a fact/preference → use memory_remember.
        - When you think stored context would help answer a question → use memory_recall PROACTIVELY.
        - When the user says "forget X" or "delete that" → use memory_forget.
        - Memories survive across conversations and app restarts. Use them to be a truly personal assistant.
        - Example: "What did Adam tell me to bring?" → memory_recall with query "adam bring".

        Google Docs/Drive: You can create, edit, and share Google Docs, and search/list Drive files.
        For "create a doc that says X" → use google_docs_create. Always share the URL with the user.
        For "find my doc about X" → use google_drive_search. If user wants to edit → use google_docs_edit with the doc ID.
        Google account sign-in is required — if not connected, tell the user to go to Settings.

        For directions: use get_location first if you need the user's current position, then open_maps with the destination.
        Tool chaining example: user asks "directions to my meeting" → get calendar event → get location → open_maps.

        CRITICAL: For send_sms — ALWAYS confirm with the user before sending.
        Say "I'll text [name] saying '[message]' — should I send it?" and wait for confirmation.

        Important: When returning tool results, summarize them naturally. For example,
        instead of listing raw JSON, say "You got 3 texts today. Mom sent 'Don't forget
        dinner at 7' at 2:15 PM..." — make it feel like a human assistant read your phone.
    """.trimIndent()

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Send a user message and get a flow of [AgentEvent]s back.
     *
     * This drives the full agentic loop:
     *   1. Append user message to history
     *   2. Stream to Claude with tools
     *   3. If tool_use → execute tool → inject result → re-send (loop)
     *   4. If end_turn → emit final message → done
     *
     * The flow completes when the agent is done or an unrecoverable error occurs.
     */
    fun chat(userText: String): Flow<AgentEvent> = flow {
        // Add user message to history
        conversationHistory.add(ConversationMessage(MessageRole.USER, userText))
        trimHistory()

        var iterations = 0

        // ── Agentic Loop ────────────────────────────────────────────────────
        while (iterations < MAX_TOOL_ITERATIONS) {
            iterations++
            emit(AgentEvent.Thinking)

            // Accumulate the full assistant response for this iteration
            val assistantText = StringBuilder()
            val toolCalls = mutableListOf<StreamEvent.ToolCall>()
            var stopReason = ""
            var hadError = false

            // Stream from Claude
            apiService.streamMessage(
                messages = conversationHistory,
                systemPrompt = buildSystemPrompt(),
                tools = toolDispatcher.allToolDefinitions()
            ).collect { event ->
                when (event) {
                    is StreamEvent.TextDelta -> {
                        assistantText.append(event.text)
                        emit(AgentEvent.TextDelta(event.text))
                    }
                    is StreamEvent.ToolCall -> {
                        toolCalls.add(event)
                    }
                    is StreamEvent.Done -> {
                        stopReason = event.stopReason
                    }
                    is StreamEvent.Error -> {
                        emit(AgentEvent.Error(event.message, event.isRetryable))
                        hadError = true
                    }
                    is StreamEvent.Usage -> {
                        emit(AgentEvent.Usage(event.inputTokens, event.outputTokens))
                    }
                }
            }

            // If there was a streaming error, abort the loop
            if (hadError) {
                emit(AgentEvent.Done)
                return@flow
            }

            // ── Process the response ────────────────────────────────────────

            if (stopReason == "tool_use" && toolCalls.isNotEmpty()) {
                // Build the assistant message with text + tool_use blocks
                val assistantContent = mutableListOf<ContentBlock>()
                if (assistantText.isNotEmpty()) {
                    assistantContent.add(ContentBlock.Text(assistantText.toString()))
                }
                for (tc in toolCalls) {
                    assistantContent.add(ContentBlock.ToolUse(tc.id, tc.name, tc.input))
                }
                conversationHistory.add(ConversationMessage(MessageRole.ASSISTANT, assistantContent))

                // Execute each tool and build tool_result blocks
                val toolResults = mutableListOf<ContentBlock>()
                for (tc in toolCalls) {
                    emit(AgentEvent.ToolStarted(tc.name, tc.id))

                    val result = toolDispatcher.execute(tc.name, tc.input)

                    emit(AgentEvent.ToolFinished(tc.name, tc.id, result.isError))

                    toolResults.add(ContentBlock.ToolResult(
                        toolUseId = tc.id,
                        content = result.content,
                        isError = result.isError
                    ))
                }

                // Add tool results as a user message (API requirement)
                conversationHistory.add(ConversationMessage(MessageRole.USER, toolResults))

                // Loop — Claude will process the tool results and respond again

            } else {
                // stop_reason == "end_turn" or no tools — we're done
                if (assistantText.isNotEmpty()) {
                    // Add assistant's final text to history
                    conversationHistory.add(
                        ConversationMessage(MessageRole.ASSISTANT, assistantText.toString())
                    )
                    emit(AgentEvent.MessageComplete(assistantText.toString()))
                }

                emit(AgentEvent.Done)
                return@flow
            }
        }

        // If we hit max iterations, emit what we have and stop
        emit(AgentEvent.Error(
            "Reached maximum tool iterations ($MAX_TOOL_ITERATIONS). Stopping to prevent infinite loop.",
            isRetryable = false
        ))
        emit(AgentEvent.Done)
    }

    // ── History Management ───────────────────────────────────────────────────

    /**
     * Trims conversation history to prevent unbounded token growth.
     * Keeps the most recent turns, dropping oldest user/assistant pairs.
     */
    private fun trimHistory() {
        // Each "turn" is roughly 2 messages (user + assistant), but tool loops
        // can create more. Cap at MAX_HISTORY_TURNS * 2 messages.
        val maxMessages = MAX_HISTORY_TURNS * 2
        if (conversationHistory.size > maxMessages) {
            val excess = conversationHistory.size - maxMessages
            // Remove from the front (oldest messages)
            repeat(excess) { conversationHistory.removeAt(0) }
            // Ensure first message is always a user message (API requirement)
            while (conversationHistory.isNotEmpty() &&
                conversationHistory.first().role != MessageRole.USER) {
                conversationHistory.removeAt(0)
            }
        }
    }

    /**
     * Clears all conversation history. Call when starting a new chat.
     */
    fun clearHistory() {
        conversationHistory.clear()
    }

    /**
     * Returns the current conversation history (read-only).
     * Useful for persisting to Room DB.
     */
    fun getHistory(): List<ConversationMessage> = conversationHistory.toList()
}
