package com.medusa.mobile.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Medusa Mobile Material3 theme — always dark, green-accented.
 * mm-004: UI shell theme setup
 */
private val MedusaDarkColorScheme = darkColorScheme(
    primary             = MedusaColors.accent,
    onPrimary           = MedusaColors.textOnAccent,
    primaryContainer    = MedusaColors.surface,
    onPrimaryContainer  = MedusaColors.accent,
    secondary           = MedusaColors.accentSoft,
    onSecondary         = MedusaColors.textPrimary,
    background          = MedusaColors.background,
    onBackground        = MedusaColors.textPrimary,
    surface             = MedusaColors.cardBackground,
    onSurface           = MedusaColors.textPrimary,
    surfaceVariant      = MedusaColors.surface,
    onSurfaceVariant    = MedusaColors.textSecondary,
    error               = MedusaColors.error,
    outline             = MedusaColors.separator,
    outlineVariant      = MedusaColors.glassBorder,
)

@Composable
fun MedusaMobileTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Edge-to-edge: transparent status/nav bars with dark green tint
            window.statusBarColor = MedusaColors.background.toArgb()
            window.navigationBarColor = MedusaColors.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = MedusaDarkColorScheme,
        typography = MedusaTypography,
        content = content
    )
}
