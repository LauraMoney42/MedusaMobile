package com.medusa.mobile.ui.screens

import android.Manifest
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import com.medusa.mobile.R
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.medusa.mobile.services.PermissionManager
import com.medusa.mobile.ui.theme.MedusaColors
import kotlinx.coroutines.launch

/**
 * First-launch permissions screen — requests all Medusa permissions in one shot.
 *
 * Why a dedicated screen?
 *   Hitting a permission wall mid-conversation breaks the user experience.
 *   Requesting everything upfront with clear explanations sets expectations early
 *   and gets the user fully set up before they start chatting.
 *
 * Flow:
 *   1. Shown on first launch (controlled by MainActivity via SharedPreferences flag)
 *   2. "Grant All" → requests all missing permissions in one batch
 *   3. After granting (or skipping) → onDone() navigates to ChatScreen
 */
@Composable
fun PermissionsSetupScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    // Track which permissions are granted (re-checked after each request)
    var grantedMap by remember {
        mutableStateOf(
            PermissionManager.REQUIRED_PERMISSIONS.associateWith {
                PermissionManager.isGranted(context, it)
            }
        )
    }
    var isRequesting by remember { mutableStateOf(false) }
    var showDoneHint  by remember { mutableStateOf(false) }

    val allGranted = grantedMap.values.all { it }

    // Permission metadata: icon, friendly name, rationale
    val permissionItems = remember {
        buildList {
            add(PermissionItem(
                permission   = Manifest.permission.READ_SMS,
                icon         = "💬",
                name         = "Text Messages",
                rationale    = "Read and send SMS so Medusa can check your texts and reply for you."
            ))
            add(PermissionItem(
                permission   = Manifest.permission.READ_CALL_LOG,
                icon         = "📞",
                name         = "Call History",
                rationale    = "See who called you, missed calls, and call durations."
            ))
            add(PermissionItem(
                permission   = Manifest.permission.READ_CONTACTS,
                icon         = "👥",
                name         = "Contacts",
                rationale    = "Look up names and numbers so Medusa knows who you're talking about."
            ))
            add(PermissionItem(
                permission   = Manifest.permission.READ_CALENDAR,
                icon         = "📅",
                name         = "Calendar",
                rationale    = "Read and create events — ask \"what's on my calendar?\" or \"add a meeting\"."
            ))
            add(PermissionItem(
                permission   = Manifest.permission.ACCESS_FINE_LOCATION,
                icon         = "📍",
                name         = "Location",
                rationale    = "Get directions, find nearby places, and navigate from where you are."
            ))
            // Photos permission differs by Android version
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(PermissionItem(
                    permission   = Manifest.permission.READ_MEDIA_IMAGES,
                    icon         = "🖼️",
                    name         = "Photos",
                    rationale    = "Browse your photo library — ask \"show me photos from last week\"."
                ))
            } else {
                add(PermissionItem(
                    permission   = Manifest.permission.READ_EXTERNAL_STORAGE,
                    icon         = "🖼️",
                    name         = "Photos & Files",
                    rationale    = "Browse your photos and read files from your device storage."
                ))
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MedusaColors.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 56.dp, bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            // ── Header ────────────────────────────────────────────────────────

            Text(
                text = "🐍",
                fontSize = 56.sp,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Text(
                text = "Set up Medusa",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = MedusaColors.textPrimary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Medusa needs a few permissions to be your personal assistant. Grant them now and she'll be ready to help with anything.",
                fontSize = 15.sp,
                color = MedusaColors.textSecondary,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))

            // ── Permission list ───────────────────────────────────────────────

            permissionItems.forEach { item ->
                val granted = grantedMap[item.permission] == true
                PermissionRow(item = item, granted = granted)
                Spacer(modifier = Modifier.height(10.dp))
            }

            Spacer(modifier = Modifier.height(28.dp))

            // ── Action buttons ────────────────────────────────────────────────

            if (allGranted) {
                // All granted — show success state
                Button(
                    onClick = onDone,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MedusaColors.accent,
                        contentColor   = MedusaColors.textOnAccent
                    )
                ) {
                    Text(
                        text = "✓  All Set — Start Chatting",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            } else {
                // Grant button
                Button(
                    onClick = {
                        scope.launch {
                            isRequesting = true
                            val results = PermissionManager.requestAllRequired(context)
                            // Refresh granted state after user responds
                            grantedMap = PermissionManager.REQUIRED_PERMISSIONS.associateWith {
                                PermissionManager.isGranted(context, it)
                            }
                            isRequesting = false
                            showDoneHint = true
                        }
                    },
                    enabled = !isRequesting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MedusaColors.accent,
                        contentColor   = MedusaColors.textOnAccent
                    )
                ) {
                    if (isRequesting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color    = MedusaColors.textOnAccent,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Requesting…", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    } else {
                        Text("Grant Permissions", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Skip option
                TextButton(
                    onClick = onDone,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Skip for now",
                        color = MedusaColors.textMuted,
                        fontSize = 14.sp
                    )
                }

                // Hint after first attempt
                AnimatedVisibility(
                    visible = showDoneHint && !allGranted,
                    enter = fadeIn() + slideInVertically()
                ) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Some permissions were denied. You can grant them later in Settings → Apps → Medusa → Permissions.",
                        color = MedusaColors.textMuted,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            }
        }
    }
}

// ── Permission row composable ─────────────────────────────────────────────────

private data class PermissionItem(
    val permission: String,
    val icon: String,
    val name: String,
    val rationale: String
)

@Composable
private fun PermissionRow(item: PermissionItem, granted: Boolean) {
    val borderColor = if (granted) MedusaColors.accent else MedusaColors.glassBorder
    val bgColor     = if (granted) MedusaColors.accentGlow else MedusaColors.cardBackground

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor, RoundedCornerShape(12.dp))
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = item.icon, fontSize = 28.sp)

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MedusaColors.textPrimary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = item.rationale,
                fontSize = 12.sp,
                color = MedusaColors.textSecondary,
                lineHeight = 16.sp
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        Text(
            text = if (granted) "✓" else "○",
            fontSize = 18.sp,
            color = if (granted) MedusaColors.accent else MedusaColors.textMuted,
            fontWeight = if (granted) FontWeight.Bold else FontWeight.Normal
        )
    }
}
