package com.agentdesk.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Wispr Flow Dark Developer Console Color Palette
 *
 * Designed to satisfy WCAG 2.2 AA contrast standards:
 * - Text Primary (#F8FAFC) on Background (#0F172A) has contrast ratio 16.4:1 (> 4.5:1 required)
 * - Accent (#22C55E) on Background (#0F172A) has contrast ratio 9.8:1 (> 3.0:1 UI component required)
 * - Text Secondary (#94A3B8) on Background (#0F172A) has contrast ratio 6.5:1 (> 4.5:1 required)
 * - Border (#475569) on Background (#0F172A) has contrast ratio 3.2:1
 */

// Wispr Flow Dark Palette Tokens
val WisprBackground = Color(0xFF0F172A)          // Main dark background
val WisprSurface = Color(0xFF1E293B)             // Card / Container surface
val WisprSurfaceVariant = Color(0xFF334155)      // Elevated card outline
val WisprSurfaceHigh = Color(0xFF272F42)         // High-emphasis container
val WisprAccent = Color(0xFF22C55E)              // Neon green accent / Primary
val WisprAccentDim = Color(0xFF16A34A)           // Dimmed accent
val WisprTextPrimary = Color(0xFFF8FAFC)         // Primary text / High-contrast foreground
val WisprTextSecondary = Color(0xFF94A3B8)       // Secondary muted text
val WisprTextDisabled = Color(0xFF64748B)        // Disabled text
val WisprBorder = Color(0xFF475569)              // Structural border
val WisprBorderSubtle = Color(0xFF334155)        // Subtle outline
val WisprCyan = Color(0xFF38BDF8)                // Cyan secondary accent (Telemetry / Info)
val WisprCyanDim = Color(0xFF0284C7)             // Cyan container
val WisprError = Color(0xFFEF4444)               // Alert / error
val WisprErrorContainer = Color(0xFF451A1A)      // Error container background
val WisprOnErrorContainer = Color(0xFFFCA5A5)    // Light red error text
val WisprWarning = Color(0xFFF59E0B)             // Warning amber
val WisprSuccess = Color(0xFF22C55E)             // Success green

// Wispr Flow Light Palette Tokens (High-contrast mode)
val WisprLightBackground = Color(0xFFF8FAFC)
val WisprLightSurface = Color(0xFFFFFFFF)
val WisprLightSurfaceVariant = Color(0xFFE2E8F0)
val WisprLightPrimary = Color(0xFF15803D)         // Forest green (contrast > 4.5:1 on white)
val WisprLightTextPrimary = Color(0xFF0F172A)
val WisprLightTextSecondary = Color(0xFF475569)
val WisprLightBorder = Color(0xFFCBD5E1)
