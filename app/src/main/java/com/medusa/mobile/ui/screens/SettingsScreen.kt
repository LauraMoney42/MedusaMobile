package com.medusa.mobile.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.medusa.mobile.api.ApiKeyStore
import com.medusa.mobile.services.NotificationReaderService
import com.medusa.mobile.ui.components.GlassCard
import com.medusa.mobile.ui.theme.MedusaColors

/**
 * Settings screen — API key entry, model selection, permission status.
 * Dark green glassy theme matching the Medusa brand.
 *
 * mm-012: SettingsScreen — API key entry + permission management
 */

// Available Claude models
private val CLAUDE_MODELS = listOf(
    "claude-sonnet-4-20250514" to "Claude Sonnet 4 (default)",
    "claude-opus-4-20250514" to "Claude Opus 4",
    "claude-haiku-3-20250307" to "Claude 3.5 Haiku (fast)",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    // ── API Key state ──────────────────────────────────────────────
    var apiKeyInput by remember { mutableStateOf("") }
    var keyIsStored by remember { mutableStateOf(ApiKeyStore.hasApiKey(context)) }
    var showKey by remember { mutableStateOf(false) }
    var saveSuccess by remember { mutableStateOf(false) }

    // ── Model state ────────────────────────────────────────────────
    var selectedModel by remember {
        mutableStateOf(ApiKeyStore.getModel(context) ?: CLAUDE_MODELS[0].first)
    }
    var modelMenuExpanded by remember { mutableStateOf(false) }

    // ── Permission state ───────────────────────────────────────────
    var notifEnabled by remember { mutableStateOf(NotificationReaderService.isEnabled(context)) }

    // Load masked key on first compose
    LaunchedEffect(Unit) {
        val stored = ApiKeyStore.getApiKey(context)
        if (stored != null) {
            apiKeyInput = maskKey(stored)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MedusaColors.background)
    ) {
        // ── Top bar ─────────────────────────────────────────────────
        TopAppBar(
            title = {
                Text(
                    "Settings",
                    fontWeight = FontWeight.Bold,
                    color = MedusaColors.textPrimary
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MedusaColors.accent
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MedusaColors.glassSurface,
            ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── API Key Section ─────────────────────────────────────
            SectionHeader(title = "Claude API Key", icon = Icons.Filled.Key)

            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Enter your Anthropic API key to connect Medusa to Claude.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MedusaColors.textSecondary
                    )

                    // Key input field
                    OutlinedTextField(
                        value = if (keyIsStored && !showKey) apiKeyInput else apiKeyInput,
                        onValueChange = {
                            apiKeyInput = it
                            saveSuccess = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("sk-ant-api03-...", color = MedusaColors.textMuted) },
                        visualTransformation = if (showKey) VisualTransformation.None
                            else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        trailingIcon = {
                            IconButton(onClick = { showKey = !showKey }) {
                                Icon(
                                    if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (showKey) "Hide" else "Show",
                                    tint = MedusaColors.textMuted
                                )
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MedusaColors.accent,
                            unfocusedBorderColor = MedusaColors.glassBorder,
                            cursorColor = MedusaColors.accent,
                            focusedContainerColor = MedusaColors.cardBackground,
                            unfocusedContainerColor = MedusaColors.cardBackground,
                            focusedTextColor = MedusaColors.textPrimary,
                            unfocusedTextColor = MedusaColors.textPrimary,
                        ),
                        shape = RoundedCornerShape(12.dp),
                    )

                    // Validation hint
                    if (apiKeyInput.isNotBlank() && !apiKeyInput.startsWith("sk-ant-") && !keyIsStored) {
                        Text(
                            "Key should start with \"sk-ant-\"",
                            style = MaterialTheme.typography.labelMedium,
                            color = MedusaColors.error
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Save button
                        Button(
                            onClick = {
                                val raw = apiKeyInput.trim()
                                if (raw.startsWith("sk-ant-")) {
                                    ApiKeyStore.saveApiKey(context, raw)
                                    keyIsStored = true
                                    apiKeyInput = maskKey(raw)
                                    showKey = false
                                    saveSuccess = true
                                }
                            },
                            enabled = apiKeyInput.trim().startsWith("sk-ant-"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MedusaColors.accent,
                                contentColor = MedusaColors.textOnAccent,
                                disabledContainerColor = MedusaColors.glassSurface,
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Save Key")
                        }

                        // Delete button (only if key stored)
                        if (keyIsStored) {
                            OutlinedButton(
                                onClick = {
                                    ApiKeyStore.clearApiKey(context)
                                    apiKeyInput = ""
                                    keyIsStored = false
                                    saveSuccess = false
                                    showKey = false
                                },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MedusaColors.error,
                                ),
                                border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                                    brush = androidx.compose.ui.graphics.SolidColor(MedusaColors.error.copy(alpha = 0.5f))
                                ),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete", modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Remove")
                            }
                        }
                    }

                    // Success indicator
                    AnimatedVisibility(visible = saveSuccess, enter = fadeIn(), exit = fadeOut()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = MedusaColors.accent,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "API key saved securely (AES-256 encrypted)",
                                style = MaterialTheme.typography.labelMedium,
                                color = MedusaColors.accent
                            )
                        }
                    }

                    // Status indicator
                    StatusRow(
                        label = "API Key",
                        isOk = keyIsStored,
                        okText = "Stored",
                        badText = "Not configured"
                    )
                }
            }

            // ── Model Selection ─────────────────────────────────────
            SectionHeader(title = "Model", icon = Icons.Filled.SmartToy)

            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Choose which Claude model to use for responses.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MedusaColors.textSecondary
                    )

                    ExposedDropdownMenuBox(
                        expanded = modelMenuExpanded,
                        onExpandedChange = { modelMenuExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = CLAUDE_MODELS.find { it.first == selectedModel }?.second ?: selectedModel,
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelMenuExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MedusaColors.accent,
                                unfocusedBorderColor = MedusaColors.glassBorder,
                                focusedContainerColor = MedusaColors.cardBackground,
                                unfocusedContainerColor = MedusaColors.cardBackground,
                                focusedTextColor = MedusaColors.textPrimary,
                                unfocusedTextColor = MedusaColors.textPrimary,
                            ),
                            shape = RoundedCornerShape(12.dp),
                        )

                        ExposedDropdownMenu(
                            expanded = modelMenuExpanded,
                            onDismissRequest = { modelMenuExpanded = false },
                            containerColor = MedusaColors.cardBackground,
                        ) {
                            CLAUDE_MODELS.forEach { (id, label) ->
                                DropdownMenuItem(
                                    text = { Text(label, color = MedusaColors.textPrimary) },
                                    onClick = {
                                        selectedModel = id
                                        ApiKeyStore.saveModel(context, id)
                                        modelMenuExpanded = false
                                    },
                                    trailingIcon = {
                                        if (id == selectedModel) {
                                            Icon(
                                                Icons.Filled.Check,
                                                contentDescription = null,
                                                tint = MedusaColors.accent
                                            )
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // ── Permissions Section ─────────────────────────────────
            SectionHeader(title = "Permissions", icon = Icons.Filled.Security)

            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Medusa needs these permissions to access your device.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MedusaColors.textSecondary
                    )

                    // Notification access
                    PermissionRow(
                        icon = Icons.Filled.Notifications,
                        label = "Notification Access",
                        description = "Read notifications from all apps",
                        isGranted = notifEnabled,
                        onRequest = {
                            NotificationReaderService.openSettings(context)
                        }
                    )

                    HorizontalDivider(color = MedusaColors.separator)

                    // SMS — runtime permission, shown as status only
                    StatusRow(
                        label = "SMS (READ_SMS)",
                        isOk = true, // Checked at runtime when tool is called
                        okText = "Requested at runtime",
                        badText = ""
                    )

                    StatusRow(
                        label = "Call Log (READ_CALL_LOG)",
                        isOk = true,
                        okText = "Requested at runtime",
                        badText = ""
                    )

                    StatusRow(
                        label = "Contacts (READ_CONTACTS)",
                        isOk = true,
                        okText = "Requested at runtime",
                        badText = ""
                    )

                    StatusRow(
                        label = "Location",
                        isOk = true,
                        okText = "Requested at runtime",
                        badText = ""
                    )
                }
            }

            // ── About Section ───────────────────────────────────────
            SectionHeader(title = "About", icon = Icons.Filled.Info)

            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("🐍", style = MaterialTheme.typography.headlineLarge)
                    Text(
                        "Medusa Mobile",
                        style = MaterialTheme.typography.titleLarge,
                        color = MedusaColors.accent,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "v0.1.0 • AI assistant with full Android access",
                        style = MaterialTheme.typography.labelMedium,
                        color = MedusaColors.textMuted
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// ── Helper Composables ───────────────────────────────────────────────────────

@Composable
private fun SectionHeader(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(start = 4.dp, top = 8.dp)
    ) {
        Icon(icon, contentDescription = null, tint = MedusaColors.accent, modifier = Modifier.size(20.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = MedusaColors.textPrimary,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun StatusRow(label: String, isOk: Boolean, okText: String, badText: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (isOk) MedusaColors.accent else MedusaColors.error)
        )
        Text(
            "$label: ${if (isOk) okText else badText}",
            style = MaterialTheme.typography.labelMedium,
            color = if (isOk) MedusaColors.textSecondary else MedusaColors.error
        )
    }
}

@Composable
private fun PermissionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    description: String,
    isGranted: Boolean,
    onRequest: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(icon, contentDescription = null, tint = MedusaColors.accent, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = MedusaColors.textPrimary)
            Text(description, style = MaterialTheme.typography.labelMedium, color = MedusaColors.textMuted)
        }
        if (isGranted) {
            Icon(Icons.Filled.CheckCircle, contentDescription = "Granted", tint = MedusaColors.accent)
        } else {
            TextButton(onClick = onRequest) {
                Text("Grant", color = MedusaColors.accent)
            }
        }
    }
}

/** Mask all but first 7 and last 4 chars of an API key. */
private fun maskKey(key: String): String {
    if (key.length <= 11) return key
    return key.take(7) + "•".repeat(key.length - 11) + key.takeLast(4)
}
