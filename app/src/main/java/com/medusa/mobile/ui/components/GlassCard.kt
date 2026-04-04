package com.medusa.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.medusa.mobile.ui.theme.MedusaColors

/**
 * Frosted glass card — Medusa's signature UI element.
 * Semi-transparent dark green background with subtle green border glow,
 * matching the Medusa PM app's glassy modern aesthetic.
 *
 * mm-004: core glass UI component
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .shadow(
                elevation = 8.dp,
                shape = shape,
                ambientColor = MedusaColors.accentGlow,
                spotColor = MedusaColors.accentGlow
            )
            .clip(shape)
            .background(MedusaColors.glassSurface)
            .border(
                width = 1.dp,
                color = MedusaColors.glassBorder,
                shape = shape
            )
            .padding(16.dp),
        content = content
    )
}
