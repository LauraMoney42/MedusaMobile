package com.medusa.mobile.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import com.medusa.mobile.R
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
    // mm-keyboard-001: keyboard controller for hide-on-send + hide-on-tap-outside
    val keyboardController = LocalSoftwareKeyboardController.current

    // ── mm-gmail-auth-001: In-context Google consent launcher ────────────────
    // Uses rememberLauncherForActivityResult so the Google consent screen launches
    // as an Activity result (not a navigation event). ChatViewModel is NOT destroyed,
    // conversation history is fully preserved. User returns here after Allow/Deny.
    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.onGoogleSignInResult(result.data)
    }

    // Show the in-context Google consent dialog when re-auth is needed
    if (uiState.googleReAuthNeeded) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissGoogleReAuth() },
            containerColor = MedusaColors.cardBackground,
            shape = RoundedCornerShape(16.dp),
            title = {
                Text(
                    "Gmail Access Needed",
                    color = MedusaColors.textPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    "Medusa needs access to Gmail and Sheets to complete your request. " +
                    "Tap Allow to grant access — you'll return here immediately after.",
                    color = MedusaColors.textSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        googleSignInLauncher.launch(viewModel.getGoogleSignInIntent())
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MedusaColors.accent
                    )
                ) {
                    Text("Allow", color = MedusaColors.background)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissGoogleReAuth() }) {
                    Text("Not Now", color = MedusaColors.textSecondary)
                }
            }
        )
    }

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
                // mm-keyboard-001: lifts the column (and input bar) above the soft keyboard
                .imePadding()
                .background(MedusaColors.background)
        ) {
            // ── Compact header bar — mm-header-styleguide-001 ───────────
            // Slim toolbar: ~48dp visible height vs M3 TopAppBar's 64dp default.
            // statusBarsPadding() handles insets on all screen sizes — no hardcoded px.
            // Text uses MaterialTheme.typography (sp units) for font-scaling support.
            // Icon touch targets are 40.dp (above the 36dp min; accessibility OK).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MedusaColors.glassSurface)
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Medusa",
                    style = MaterialTheme.typography.titleMedium, // 16sp, responsive
                    fontWeight = FontWeight.Bold,
                    color = MedusaColors.accent,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp)
                )
                // New chat button — only shown when conversation exists
                if (uiState.messages.isNotEmpty()) {
                    IconButton(
                        onClick = { viewModel.newChat() },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = "New Chat",
                            tint = MedusaColors.textSecondary
                        )
                    }
                }
                // Settings button
                IconButton(
                    onClick = onNavigateToSettings,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = "Settings",
                        tint = MedusaColors.textSecondary
                    )
                }
            }

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
                    .fillMaxWidth()
                    // mm-keyboard-001: tap anywhere on the message list to dismiss keyboard
                    .pointerInput(Unit) {
                        detectTapGestures { keyboardController?.hide() }
                    },
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
                                    // mm-icon-001: Medusa logo
                                    // mm-icon-002: Circular crop + electric green (#00E676) neon glow ring
                                    val glowColor = Color(0xFF00E676)
                                    Image(
                                        painter = painterResource(id = R.drawable.medusa_logo),
                                        contentDescription = "Medusa logo",
                                        modifier = Modifier
                                            .size(96.dp)
                                            // Neon glow: draw blurred green halo behind the circle
                                            .drawBehind {
                                                drawIntoCanvas { canvas ->
                                                    val paint = Paint().apply {
                                                        asFrameworkPaint().apply {
                                                            isAntiAlias = true
                                                            color = android.graphics.Color.TRANSPARENT
                                                            setShadowLayer(
                                                                24f,   // blur radius
                                                                0f, 0f, // offset x/y
                                                                glowColor.copy(alpha = 0.85f).toArgb()
                                                            )
                                                        }
                                                    }
                                                    val r = size.minDimension / 2f
                                                    canvas.drawCircle(center, r, paint)
                                                }
                                            }
                                            // Green ring border
                                            .border(2.dp, glowColor.copy(alpha = 0.9f), CircleShape)
                                            // Circular clip
                                            .clip(CircleShape)
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
                onSend = {
                    // mm-keyboard-001: hide keyboard when message is sent
                    keyboardController?.hide()
                    viewModel.send()
                },
                isLoading = uiState.isThinking
            )
        }
    }
}
