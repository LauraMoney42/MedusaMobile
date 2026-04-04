package com.medusa.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import com.medusa.mobile.ui.theme.MedusaColors

/**
 * Chat message bubble — user (dark green solid) or agent (frosted glass).
 * mm-004: chat UI shell
 */
@Composable
fun ChatBubble(
    text: String,
    isUser: Boolean,
    modifier: Modifier = Modifier
) {
    val userShape = RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp)
    val agentShape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp)
    val shape = if (isUser) userShape else agentShape

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = if (isUser) 280.dp else 320.dp)
                .shadow(
                    elevation = if (isUser) 6.dp else 4.dp,
                    shape = shape,
                    ambientColor = if (isUser) MedusaColors.accentGlow else MedusaColors.glassSurface,
                    spotColor = if (isUser) MedusaColors.accentGlow else MedusaColors.glassSurface
                )
                .clip(shape)
                .background(if (isUser) MedusaColors.userBubble else MedusaColors.glassSurface)
                .then(
                    if (!isUser) Modifier.border(1.dp, MedusaColors.glassBorder, shape)
                    else Modifier.border(1.dp, MedusaColors.accentSoft, shape)
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = MedusaColors.textPrimary
            )
        }
    }
}
