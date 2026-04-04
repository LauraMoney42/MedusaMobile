package com.medusa.mobile.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Medusa Mobile color palette — dark green + electric green accent.
 * Matches the Medusa PM app aesthetic: deep dark surfaces, glowing green accents,
 * frosted glass cards.
 *
 * mm-004: UI shell branding tokens
 */
object MedusaColors {
    // ── Backgrounds ──────────────────────────────────────────────────
    /** Near-black with green tint — main app background */
    val background      = Color(0xFF0A0F0A)
    /** Dark green surface — cards, sheets, nav bars */
    val surface         = Color(0xFF1B5E20)    // #1B5E20 — primary dark green
    /** Slightly lighter surface for elevated cards */
    val surfaceElevated = Color(0xFF1E6B23)
    /** Card/input field background — subtle dark with green undertone */
    val cardBackground  = Color(0xFF0F1A10)

    // ── Accent ───────────────────────────────────────────────────────
    /** Electric green — primary accent for buttons, links, active states */
    val accent          = Color(0xFF00E676)    // #00E676
    /** Dimmed accent for subtle highlights, borders */
    val accentSoft      = Color(0x5500E676)    // 33% opacity
    /** Glow color for shadows/halos behind accent elements */
    val accentGlow      = Color(0x3300E676)    // 20% opacity

    // ── Text ─────────────────────────────────────────────────────────
    val textPrimary     = Color(0xFFFFFFFF)
    val textSecondary   = Color(0xB3FFFFFF)    // 70% white
    val textMuted       = Color(0x80FFFFFF)    // 50% white
    val textOnAccent    = Color(0xFF0A0F0A)    // dark text on green buttons

    // ── Glass Effect ─────────────────────────────────────────────────
    /** Semi-transparent green for frosted glass overlay */
    val glassSurface    = Color(0x1A1B5E20)    // 10% of surface green
    val glassBorder     = Color(0x3300E676)    // subtle green border glow

    // ── Semantic ─────────────────────────────────────────────────────
    val error           = Color(0xFFCF6679)
    val separator       = Color(0x26FFFFFF)    // 15% white
    val userBubble      = Color(0xFF1B5E20)    // dark green — user messages
    val agentBubble     = Color(0xFF0F1A10)    // near-black — agent messages
}
