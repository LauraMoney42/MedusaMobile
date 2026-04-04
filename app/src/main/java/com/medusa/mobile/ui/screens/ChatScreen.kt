package com.medusa.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.viewmodel.compose.viewModel
import com.medusa.mobile.ui.ChatViewModel
import com.medusa.mobile.ui.ToolChipStatus
import com.medusa.mobile.ui.components.ChatBubble
import com.medusa.mobile.ui.components.GlassCard
import com.medusa.mobile.ui.components.InputBar
import com.medusa.mobile.ui.components.ThinkingIndicator
import com.medusa.mobile.ui.theme.MedusaColors

/**
 * Main chat screen — wired to ChatViewModel for live Claude streaming.
 * mm-004 (UI shell) + mm-011 (ViewModel wiring)
 *
 * ChatViewModel drives the agentic loop:
 *   User types → ViewModel.send() → AgentOrchestrator → Claude → Tool → Response
 *   UI observes StateFlow<ChatUiState> and renders reactively.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel = viewModel(),
    onNavigateToSettings: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when new messages arrive or text streams in
    LaunchedEffect(uiState.messages.size, uiState.messages.lastOrNull()?.text?.length) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    // Show error as a snackbar
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(
                message = error,
                duration = SnackbarDuration.Long,
                actionLabel = "Dismiss"
            )
            viewModel.dismissError()
        }
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = MedusaColors.glassSurface,
                    contentColor = MedusaColors.textPrimary,
                    actionColor = MedusaColors.accent
                )
            }
        },
        containerColor = MedusaColors.background,
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MedusaColors.background)
        ) {
            // ── Header bar ──────────────────────────────────────────────
            TopAppBar(
                title = {
                    Text(
                        "Medusa",
                        fontWeight = FontWeight.Bold,
                        color = MedusaColors.accent
                    )
                },
                actions = {
                    // New chat button
                    if (uiState.messages.isNotEmpty()) {
                        IconButton(onClick = { viewModel.newChat() }) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = "New Chat",
                                tint = MedusaColors.textSecondary
                            )
                        }
                    }
                    // Settings button
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = "Settings",
                            tint = MedusaColors.textSecondary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MedusaColors.glassSurface,
                    scrolledContainerColor = MedusaColors.glassSurface,
                ),
            )

            // ── No API key banner ────────────────────────────────────────
            if (!uiState.hasApiKey) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    color = MedusaColors.cardBackground,
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = 2.dp
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "⚠️ No API key set. ",
                            color = MedusaColors.accent,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        TextButton(onClick = onNavigateToSettings) {
                            Text(
                                "Open Settings",
                                color = MedusaColors.accent,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // ── Message list ────────────────────────────────────────────
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                if (uiState.messages.isEmpty()) {
                    item {
                        // Welcome card
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            GlassCard(
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        "🐍",
                                        style = MaterialTheme.typography.headlineLarge
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        "Welcome to Medusa",
                                        style = MaterialTheme.typography.titleLarge,
                                        color = MedusaColors.accent
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        "Your AI assistant with full Android access.\n" +
                                        "SMS, calls, notifications, apps — all native.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MedusaColors.textSecondary,
                                        modifier = Modifier.padding(horizontal = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                items(uiState.messages, key = { it.id }) { msg ->
                    Column {
                        // Tool chips (shown above assistant text)
                        if (!msg.isUser && msg.toolChips.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                msg.toolChips.forEach { chip ->
                                    val (icon, color) = when (chip.status) {
                                        ToolChipStatus.RUNNING -> "⏳" to MedusaColors.textSecondary
                                        ToolChipStatus.SUCCESS -> "✅" to MedusaColors.accent
                                        ToolChipStatus.ERROR   -> "❌" to MedusaColors.textSecondary
                                    }
                                    Surface(
                                        color = MedusaColors.cardBackground,
                                        shape = MaterialTheme.shapes.small,
                                    ) {
                                        Text(
                                            "$icon ${chip.toolName}",
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = color
                                        )
                                    }
                                }
                            }
                        }

                        // Message bubble
                        if (msg.text.isNotEmpty()) {
                            ChatBubble(
                                text = msg.text,
                                isUser = msg.isUser
                            )
                        }
                    }
                }

                if (uiState.isThinking) {
                    item { ThinkingIndicator() }
                }
            }

            // ── Divider ─────────────────────────────────────────────────
            HorizontalDivider(
                color = MedusaColors.separator,
                thickness = 0.5.dp
            )

            // ── Input bar ───────────────────────────────────────────────
            InputBar(
                text = uiState.inputText,
                onTextChange = { viewModel.onInputChanged(it) },
                onSend = { viewModel.send() },
                isLoading = uiState.isThinking
            )
        }
    }
}
