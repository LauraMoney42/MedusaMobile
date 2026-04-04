package com.medusa.mobile.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.medusa.mobile.ui.theme.MedusaColors

/**
 * Animated "thinking..." indicator — three pulsing green dots in a glass pill.
 * mm-004: loading state UI
 */
@Composable
fun ThinkingIndicator(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "thinking")

    Row(
        modifier = modifier
            .padding(horizontal = 12.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        val shape = RoundedCornerShape(18.dp)
        Row(
            modifier = Modifier
                .clip(shape)
                .background(MedusaColors.glassSurface)
                .border(1.dp, MedusaColors.glassBorder, shape)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(3) { index ->
                val alpha by infiniteTransition.animateFloat(
                    initialValue = 0.3f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(600, easing = EaseInOut),
                        repeatMode = RepeatMode.Reverse,
                        initialStartOffset = StartOffset(index * 200)
                    ),
                    label = "dot$index"
                )
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .alpha(alpha)
                        .clip(CircleShape)
                        .background(MedusaColors.accent)
                )
            }
        }
    }
}
