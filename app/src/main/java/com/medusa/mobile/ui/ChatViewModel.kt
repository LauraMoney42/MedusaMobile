package com.medusa.mobile.ui

// mm-011 — ChatViewModel: wires AgentOrchestrator ↔ Compose UI
//
// This is the last critical-path piece before "Who texted me?" works end-to-end.
//
// Responsibilities:
//   - Holds UI state as StateFlows (messages, thinking, input text, errors)
//   - On send: delegates to AgentOrchestrator.chat() and collects AgentEvents
//   - Streams text deltas into the current assistant message (live typing effect)
//   - Shows tool execution chips (ToolStarted/ToolFinished events)
//   - Creates ClaudeApiService from ApiKeyStore on init
//   - Provides new-chat / clear-history actions
//
// Design:
//   - AndroidViewModel (needs Context for ApiKeyStore + ToolDispatcher)
//   - Pure StateFlow observation — ChatScreen just collects and renders
//   - No business logic in ChatScreen — all in ViewModel

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.medusa.mobile.agent.AgentEvent
import com.medusa.mobile.agent.AgentOrchestrator
import com.medusa.mobile.agent.ToolDispatcher
import com.medusa.mobile.BuildConfig
import com.medusa.mobile.api.ApiKeyStore
import com.medusa.mobile.api.ClaudeApiService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

// ── UI Models ────────────────────────────────────────────────────────────────

/** A message displayed in the chat UI. */
data class UIMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val isUser: Boolean,
    /** For assistant messages: list of tools invoked during this turn. */
    val toolChips: List<ToolChipState> = emptyList()
)

/** State of a tool execution chip shown inside an assistant message. */
data class ToolChipState(
    val toolName: String,
    val toolId: String,
    val status: ToolChipStatus = ToolChipStatus.RUNNING
)

enum class ToolChipStatus { RUNNING, SUCCESS, ERROR }

/** Overall chat screen UI state. */
data class ChatUiState(
    val messages: List<UIMessage> = emptyList(),
    val isThinking: Boolean = false,
    val inputText: String = "",
    val error: String? = null,
    val hasApiKey: Boolean = false,
    /** Total tokens used this session (input + output). */
    val totalTokens: Int = 0,
    /**
     * True when a Gmail/Sheets tool call failed because the user hasn't granted
     * the new OAuth scopes yet. UI should show an in-context consent dialog.
     * Chat history is fully preserved — user returns here after approving.
     */
    val googleReAuthNeeded: Boolean = false
)

// ── ViewModel ────────────────────────────────────────────────────────────────

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext

    // ── State ────────────────────────────────────────────────────────────
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    // ── Agent components (lazily created when API key is available) ──────
    private var orchestrator: AgentOrchestrator? = null
    private val toolDispatcher = ToolDispatcher(context)

    init {
        // mm-env-001: If no key stored yet but BuildConfig has one (from .env at build time),
        // auto-save it now so refreshApiKeyState() sees it immediately — no user action needed.
        if (!ApiKeyStore.hasApiKey(context) && BuildConfig.ANTHROPIC_API_KEY.isNotBlank()) {
            ApiKeyStore.saveApiKey(context, BuildConfig.ANTHROPIC_API_KEY)
        }
        // Check if API key exists on startup
        refreshApiKeyState()

        // Observe GoogleAuthManager's re-auth signal.
        // When a tool call encounters UserRecoverableAuthException (new scopes not yet granted),
        // the auth manager sets reAuthNeeded = true. We mirror that into ChatUiState so
        // ChatScreen can show an in-context consent dialog without navigating away.
        viewModelScope.launch {
            toolDispatcher.googleAuth.reAuthNeeded.collect { needed ->
                _uiState.value = _uiState.value.copy(googleReAuthNeeded = needed)
            }
        }
    }

    // ── Public Actions ──────────────────────────────────────────────────

    /** Called when input text changes. */
    fun onInputChanged(text: String) {
        _uiState.value = _uiState.value.copy(inputText = text)
    }

    /** Send the current input text to Claude. */
    fun send() {
        val text = _uiState.value.inputText.trim()
        if (text.isEmpty() || _uiState.value.isThinking) return

        // Ensure we have an API key and orchestrator
        if (!ensureOrchestrator()) {
            _uiState.value = _uiState.value.copy(
                error = "No API key set. Go to Settings to enter your Claude API key."
            )
            return
        }

        // Add user message to UI
        val userMessage = UIMessage(text = text, isUser = true)
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages + userMessage,
            inputText = "",
            isThinking = true,
            error = null
        )

        // Launch the agentic loop
        viewModelScope.launch {
            // Placeholder assistant message that will be streamed into
            val assistantId = UUID.randomUUID().toString()
            val assistantMessage = UIMessage(
                id = assistantId,
                text = "",
                isUser = false
            )
            _uiState.value = _uiState.value.copy(
                messages = _uiState.value.messages + assistantMessage
            )

            // Track accumulated text and tool chips for the current assistant turn
            val textBuffer = StringBuilder()
            val toolChips = mutableListOf<ToolChipState>()

            try {
                orchestrator!!.chat(text).collect { event ->
                    when (event) {
                        is AgentEvent.TextDelta -> {
                            textBuffer.append(event.text)
                            updateAssistantMessage(assistantId, textBuffer.toString(), toolChips)
                        }

                        is AgentEvent.MessageComplete -> {
                            // Final text — ensure it's fully set
                            updateAssistantMessage(assistantId, event.fullText, toolChips)
                        }

                        is AgentEvent.ToolStarted -> {
                            toolChips.add(ToolChipState(
                                toolName = event.toolName,
                                toolId = event.toolId,
                                status = ToolChipStatus.RUNNING
                            ))
                            updateAssistantMessage(assistantId, textBuffer.toString(), toolChips)
                        }

                        is AgentEvent.ToolFinished -> {
                            // Update the matching chip status
                            val idx = toolChips.indexOfFirst { it.toolId == event.toolId }
                            if (idx >= 0) {
                                toolChips[idx] = toolChips[idx].copy(
                                    status = if (event.isError) ToolChipStatus.ERROR else ToolChipStatus.SUCCESS
                                )
                            }
                            updateAssistantMessage(assistantId, textBuffer.toString(), toolChips)

                            // After tool finishes, Claude will stream more text.
                            // Reset the text buffer for the new streaming segment —
                            // BUT we keep the previous text since AgentOrchestrator
                            // starts a new stream iteration. The orchestrator's
                            // TextDelta for the new iteration is fresh text, so we
                            // need to track that this is a continuation.
                            // Actually, the orchestrator emits TextDelta for each
                            // iteration independently, so we keep textBuffer as-is
                            // and append the new deltas. The MessageComplete at the
                            // end will have the final text for the last iteration.
                            // For multi-iteration display, we keep accumulating.
                        }

                        is AgentEvent.Thinking -> {
                            _uiState.value = _uiState.value.copy(isThinking = true)
                        }

                        is AgentEvent.Done -> {
                            _uiState.value = _uiState.value.copy(isThinking = false)
                        }

                        is AgentEvent.Error -> {
                            _uiState.value = _uiState.value.copy(
                                isThinking = false,
                                error = event.message
                            )
                            // If we have some text, keep showing it
                            if (textBuffer.isNotEmpty()) {
                                updateAssistantMessage(assistantId, textBuffer.toString(), toolChips)
                            }
                        }

                        is AgentEvent.Usage -> {
                            _uiState.value = _uiState.value.copy(
                                totalTokens = _uiState.value.totalTokens + event.inputTokens + event.outputTokens
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isThinking = false,
                    error = "Unexpected error: ${e.message}"
                )
            }

            // Ensure thinking is off when done
            _uiState.value = _uiState.value.copy(isThinking = false)

            // If the assistant message ended up empty (error before any text),
            // remove it to avoid a blank bubble
            if (textBuffer.isEmpty()) {
                _uiState.value = _uiState.value.copy(
                    messages = _uiState.value.messages.filter { it.id != assistantId }
                )
            }
        }
    }

    /** Start a new conversation. Clears messages and orchestrator history. */
    fun newChat() {
        orchestrator?.clearHistory()
        _uiState.value = ChatUiState(
            hasApiKey = _uiState.value.hasApiKey
        )
    }

    /** Dismiss the current error. */
    fun dismissError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    // ── Google Re-Auth (mm-gmail-auth-001) ──────────────────────────────

    /**
     * Returns the correct intent to launch for re-auth.
     * May be a UserRecoverableAuthException intent (OAuth2 scope grant) OR a
     * sign-in intent (first-time). Always use this — never getSignInIntent() directly.
     * Launching the wrong intent won't grant scopes and auth will keep failing.
     */
    fun getGoogleSignInIntent(): Intent = toolDispatcher.googleAuth.getReAuthIntent()

    /**
     * Called after the user completes (or cancels) the Google consent dialog.
     * Clears the re-auth signal so the dialog dismisses and the next tool
     * call succeeds with the newly granted scopes.
     */
    fun onGoogleSignInResult(data: Intent?) {
        // Process the sign-in result — updates the account in GoogleSignIn SDK
        toolDispatcher.googleAuth.handleSignInResult(data)
        // Clear the re-auth flag — next Gmail/Sheets call will get a fresh token
        toolDispatcher.googleAuth.clearReAuthNeeded()
    }

    /** Dismiss the re-auth dialog without completing sign-in (user cancelled). */
    fun dismissGoogleReAuth() {
        toolDispatcher.googleAuth.clearReAuthNeeded()
    }

    /** Call after saving API key in Settings to refresh the orchestrator. */
    fun refreshApiKeyState() {
        val hasKey = ApiKeyStore.hasApiKey(context)
        _uiState.value = _uiState.value.copy(hasApiKey = hasKey)
        if (hasKey) {
            ensureOrchestrator()
        } else {
            orchestrator = null
        }
    }

    // ── Internal Helpers ────────────────────────────────────────────────

    /**
     * Creates the orchestrator from stored API key. Returns true if ready.
     */
    private fun ensureOrchestrator(): Boolean {
        if (orchestrator != null) return true

        val apiService = ApiKeyStore.createService(context) ?: return false
        orchestrator = AgentOrchestrator(apiService, toolDispatcher)
        return true
    }

    /**
     * Updates the assistant message in the messages list with new text/chips.
     * Uses copy-on-write to trigger StateFlow emission.
     */
    private fun updateAssistantMessage(
        messageId: String,
        text: String,
        toolChips: List<ToolChipState>
    ) {
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages.map { msg ->
                if (msg.id == messageId) {
                    msg.copy(text = text, toolChips = toolChips.toList())
                } else {
                    msg
                }
            }
        )
    }
}
