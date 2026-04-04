package com.medusa.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.medusa.mobile.ui.theme.MedusaColors

/**
 * Bottom input bar — frosted glass background, electric green send button.
 * mm-004: chat input UI shell
 */
@Composable
fun InputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    isLoading: Boolean = false,
    modifier: Modifier = Modifier
) {
    val canSend = text.isNotBlank() && !isLoading

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MedusaColors.glassSurface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        // Text field with glass styling
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp, max = 160.dp),
            placeholder = {
                Text(
                    "Message Medusa...",
                    color = MedusaColors.textMuted
                )
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
            shape = RoundedCornerShape(24.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
            maxLines = 6,
        )

        Spacer(modifier = Modifier.width(8.dp))

        // Send button — electric green circle
        IconButton(
            onClick = onSend,
            enabled = canSend,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(
                    if (canSend) MedusaColors.accent else MedusaColors.glassSurface
                )
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send",
                tint = if (canSend) MedusaColors.textOnAccent else MedusaColors.textMuted
            )
        }
    }
}
